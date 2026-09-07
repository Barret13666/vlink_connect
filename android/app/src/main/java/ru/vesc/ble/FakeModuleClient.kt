package ru.vesc.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.security.SecureRandom

/**
 * Имитатор прошивки: повторяет разбор команд из svcOnCtrlWrite, включая
 * минутное окно, счётчик из трёх попыток и запрет команд без пароля.
 * Криптография настоящая — та же VescCrypto, — поэтому неверный пароль
 * здесь не проходит по-честному, а не потому что так написано в заглушке.
 *
 * Чего он НЕ изображает: задержек эфира, обрывов связи, кэша GATT в
 * Android и системного окна сопряжения. Всё это ловится только на железе.
 */
class FakeModuleClient : ModuleClient {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val rng = SecureRandom()

    private val _link = MutableStateFlow(Link.IDLE)
    override val link: StateFlow<Link> = _link

    private val _found = MutableStateFlow<List<FoundModule>>(emptyList())
    override val found: StateFlow<List<FoundModule>> = _found

    private val _events = MutableSharedFlow<VescProtocol.Event>(extraBufferCapacity = 32)
    override val events: SharedFlow<VescProtocol.Event> = _events

    override val isFake = true

    // --- то, что в модуле лежит во флеше ---
    private var salt = ByteArray(16).also { rng.nextBytes(it) }
    private var login: String? = null
    private var k: ByteArray? = null
    private val names = arrayOfNulls<String>(VescProtocol.MAX_PEERS)

    // --- то, что в модуле живёт до снятия питания ---
    private var bootAt = System.currentTimeMillis()
    private var fails = 0
    private var locked = false

    // --- то, что своё у каждого соединения ---
    private var authed = false
    private var challenge: ByteArray? = null
    private var authChallenge: ByteArray? = null
    private var session: ByteArray? = null
    private var wrapped: ByteArray? = null
    private var activePeer: Int? = null
    private var bonded = false

    private fun windowOpen(): Boolean =
        !locked && System.currentTimeMillis() - bootAt < 60_000

    private fun secondsLeft(): Int {
        if (!windowOpen()) return 0
        return ((60_000 - (System.currentTimeMillis() - bootAt)) / 1000).toInt()
    }

    private fun peerCount() = names.count { it != null }

    override fun powerCycle() {
        bootAt = System.currentTimeMillis()
        fails = 0
        locked = false
        authed = false
        challenge = null
        bonded = false
        activePeer = null
    }

    override fun startScan() {
        _link.value = Link.SCANNING
        scope.launch {
            delay(400)
            _found.value = listOf(FoundModule("FAKE", "VESC BLE UART 6D:8F (имитатор)", -52))
        }
    }

    override fun stopScan() {
        if (_link.value == Link.SCANNING) _link.value = Link.IDLE
    }

    override fun connect(id: String) {
        _link.value = Link.CONNECTING
        scope.launch {
            delay(300)
            authed = false
            challenge = null
            _link.value = Link.READY
        }
    }

    override fun disconnect() {
        authed = false
        challenge = null
        _link.value = Link.IDLE
    }

    private fun emit(e: VescProtocol.Event) {
        scope.launch { delay(30); _events.emit(e) }
    }

    private fun result(cmd: Int, code: Int) =
        emit(VescProtocol.Event.Result(cmd, code))

    private fun status() = emit(
        VescProtocol.Event.State(
            VescProtocol.Status(
                empty = login == null,
                open = windowOpen(),
                authed = authed,
                locked = locked,
                encrypted = bonded,
                attemptsLeft = 3 - fails,
                peerCount = peerCount(),
                secondsLeft = secondsLeft(),
                maxPeers = VescProtocol.MAX_PEERS,
                activePeer = activePeer
            )
        )
    )

    override fun send(frame: ByteArray) {
        if (frame.isEmpty()) return
        val cmd = frame[0].toInt() and 0xFF

        if (!windowOpen()) {
            result(cmd, VescProtocol.ERR_CLOSED)
            scope.launch { delay(250); _link.value = Link.LOST }
            return
        }

        when (cmd) {
            VescProtocol.CMD_STATUS.toInt() -> {
                status()
                emit(VescProtocol.Event.Salt(salt.copyOf()))
                emit(VescProtocol.Event.Login(login ?: ""))
            }

            VescProtocol.CMD_CHALLENGE.toInt() -> {
                val c = ByteArray(16).also { rng.nextBytes(it) }
                challenge = c
                emit(VescProtocol.Event.Challenge(c))
            }

            VescProtocol.CMD_AUTH.toInt() -> {
                val c = challenge
                val key = k
                challenge = null                 // один запрос — одна попытка
                if (frame.size != 17 || c == null || key == null) {
                    result(cmd, VescProtocol.ERR_ARGS); return
                }
                val expect = VescCrypto.response(key, c)
                if (!expect.contentEquals(frame.copyOfRange(1, 17))) {
                    fails++
                    if (fails >= 3) locked = true
                    result(cmd, VescProtocol.ERR_BADPASS)
                    return
                }
                authed = true
                authChallenge = c
                session = VescCrypto.sessionKey(key, c)
                result(cmd, VescProtocol.OK)
            }

            VescProtocol.CMD_SETUP.toInt() -> {
                if (login != null) { result(cmd, VescProtocol.ERR_ALREADY); return }
                when (frame[1].toInt()) {
                    0 -> {
                        login = String(frame, 2, frame.size - 2, Charsets.UTF_8)
                        result(cmd, VescProtocol.OK)
                    }
                    1 -> {
                        k = frame.copyOfRange(2, 18)
                        result(cmd, VescProtocol.OK)
                        status()
                        emit(VescProtocol.Event.Login(login ?: ""))
                    }
                    else -> result(cmd, VescProtocol.ERR_ARGS)
                }
            }

            VescProtocol.CMD_LIST.toInt() -> {
                if (!authed) { result(cmd, VescProtocol.ERR_AUTH); return }
                names.forEachIndexed { i, n ->
                    if (n != null) emit(VescProtocol.Event.Peer(i, n))
                }
                emit(VescProtocol.Event.ListEnd(peerCount()))
            }

            VescProtocol.CMD_RENAME.toInt() -> {
                if (!authed) { result(cmd, VescProtocol.ERR_AUTH); return }
                val i = frame[1].toInt() and 0xFF
                if (i >= names.size || names[i] == null) {
                    result(cmd, VescProtocol.ERR_NOTFOUND); return
                }
                names[i] = String(frame, 2, frame.size - 2, Charsets.UTF_8)
                result(cmd, VescProtocol.OK)
            }

            VescProtocol.CMD_FORGET.toInt() -> {
                if (!authed) { result(cmd, VescProtocol.ERR_AUTH); return }
                val i = frame[1].toInt() and 0xFF
                if (i >= names.size || names[i] == null) {
                    result(cmd, VescProtocol.ERR_NOTFOUND); return
                }
                names[i] = null
                if (activePeer == i) { activePeer = null; bonded = false }
                result(cmd, VescProtocol.OK)
            }

            VescProtocol.CMD_BIND.toInt() -> {
                if (!authed) { result(cmd, VescProtocol.ERR_AUTH); return }
                val free = names.indexOfFirst { it == null }
                if (free < 0) { result(cmd, VescProtocol.ERR_FULL); return }
                names[free] = "phone"
                activePeer = free
                bonded = true
                emit(VescProtocol.Event.Bound(free))
                result(cmd, VescProtocol.OK)
            }

            VescProtocol.CMD_NEWKEY.toInt() -> {
                if (!authed) { result(cmd, VescProtocol.ERR_AUTH); return }
                val sk = session ?: run { result(cmd, VescProtocol.ERR_ARGS); return }
                when (frame[1].toInt()) {
                    0 -> { wrapped = frame.copyOfRange(2, 18); result(cmd, VescProtocol.OK) }
                    1 -> {
                        val w = wrapped ?: run { result(cmd, VescProtocol.ERR_ARGS); return }
                        wrapped = null
                        if (!VescCrypto.keyTag(sk, w).contentEquals(frame.copyOfRange(2, 18))) {
                            result(cmd, VescProtocol.ERR_ARGS); return
                        }
                        val pad = VescCrypto.keyPad(sk, authChallenge!!)
                        k = ByteArray(16) { (w[it].toInt() xor pad[it].toInt()).toByte() }
                        authed = false
                        challenge = null
                        result(cmd, VescProtocol.OK)
                    }
                    else -> result(cmd, VescProtocol.ERR_ARGS)
                }
            }

            else -> result(cmd, VescProtocol.ERR_ARGS)
        }
    }
}
