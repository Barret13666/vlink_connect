package ru.vlink.connect

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { SCAN, AUTH, HOME, PHONES, PASSWORD }

data class PeerRow(val index: Int, val name: String, val isThisPhone: Boolean)

data class UiState(
    val screen: Screen = Screen.SCAN,
    val link: Link = Link.IDLE,
    val found: List<FoundModule> = emptyList(),
    val moduleName: String = "",
    val status: VescProtocol.Status? = null,
    val login: String = "",
    val peers: List<PeerRow> = emptyList(),
    val busy: String? = null,
    val message: String? = null
)

/**
 * Весь сеанс работы с модулем.
 *
 * Пароль здесь не хранится и никуда не сохраняется — только K, и только в
 * памяти, до конца сеанса. Класть K на диск было бы удобно, но это то же
 * самое, что записать туда пароль: доступ к управлению телефонами получил
 * бы любой, кто взял разблокированный телефон в руки.
 */
class ModuleViewModel(app: Application) : AndroidViewModel(app) {

    private val client: ModuleClient = BleModuleClient(app)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    // Секреты сеанса
    private var salt: ByteArray? = null
    private var sessionKeyMaterial: ByteArray? = null   // K
    private var authChallenge: ByteArray? = null
    private var awaitingAuth = false

    private val incoming = mutableListOf<PeerRow>()

    init {
        viewModelScope.launch { client.link.collect(::onLink) }
        viewModelScope.launch { client.found.collect { f -> _ui.update { it.copy(found = f) } } }
        viewModelScope.launch { client.events.collect(::onEvent) }
        viewModelScope.launch {
            client.notice.collect { text -> _ui.update { it.copy(busy = null, message = text) } }
        }

        // Часы окна тикают у нас: модуль присылает остаток только вместе со
        // статусом, а человек должен видеть, как время уходит.
        viewModelScope.launch {
            while (true) {
                delay(1000)
                val s = _ui.value.status ?: continue
                if (s.secondsLeft > 0) {
                    _ui.update { it.copy(status = s.copy(secondsLeft = s.secondsLeft - 1)) }
                } else if (s.open) {
                    _ui.update { it.copy(status = s.copy(open = false)) }
                }
            }
        }
    }

    // ------------------------------------------------------------ действия

    fun startScan() = client.startScan()
    fun stopScan() = client.stopScan()

    fun connect(m: FoundModule) {
        _ui.update { it.copy(moduleName = m.name, busy = "Подключаюсь...") }
        client.connect(m.id)
    }

    fun disconnect() {
        client.disconnect()
        forgetSession()
        _ui.update { UiState() }
    }

    fun setup(login: String, password: String, repeat: String) {
        val s = salt ?: return fail("Модуль не прислал соль")
        if (password != repeat) return fail("Пароли не совпадают")
        (VescCredentials.checkLogin(login) as? VescCredentials.Verdict.Error)
            ?.let { return fail(it.text) }
        (VescCredentials.checkPassword(password) as? VescCredentials.Verdict.Error)
            ?.let { return fail(it.text) }

        viewModelScope.launch {
            _ui.update { it.copy(busy = "Считаю ключ...") }
            val k = withContext(Dispatchers.Default) {
                VescCrypto.kdf(s, login, password)
            }
            sessionKeyMaterial = k
            _ui.update { it.copy(busy = "Настраиваю модуль...", login = login) }
            client.send(VescProtocol.setupLogin(login))
            client.send(VescProtocol.setupKey(k))
            // Сразу же входим: это заодно проверяет, что K доехал целым.
            awaitingAuth = true
            client.send(VescProtocol.challenge())
        }
    }

    fun login(password: String) {
        val s = salt ?: return fail("Модуль не прислал соль")
        val l = _ui.value.login
        if (l.isEmpty()) return fail("Модуль не прислал логин")
        (VescCredentials.checkPassword(password) as? VescCredentials.Verdict.Error)
            ?.let { return fail(it.text) }

        viewModelScope.launch {
            _ui.update { it.copy(busy = "Считаю ключ...") }
            sessionKeyMaterial = withContext(Dispatchers.Default) {
                VescCrypto.kdf(s, l, password)
            }
            _ui.update { it.copy(busy = "Проверяю пароль...") }
            awaitingAuth = true
            client.send(VescProtocol.challenge())
        }
    }

    fun bind() {
        _ui.update { it.copy(busy = "Сопряжение...") }
        client.send(VescProtocol.bind())
    }

    fun rename(index: Int, name: String) {
        client.send(VescProtocol.rename(index, name))
    }

    fun forget(index: Int) {
        client.send(VescProtocol.forget(index))
    }

    fun changePassword(newPassword: String, repeat: String) {
        val s = salt ?: return fail("Модуль не прислал соль")
        val k = sessionKeyMaterial ?: return fail("Сначала войдите")
        val c = authChallenge ?: return fail("Сначала войдите")
        if (newPassword != repeat) return fail("Пароли не совпадают")
        (VescCredentials.checkPassword(newPassword) as? VescCredentials.Verdict.Error)
            ?.let { return fail(it.text) }

        viewModelScope.launch {
            _ui.update { it.copy(busy = "Считаю новый ключ...") }
            val newK = withContext(Dispatchers.Default) {
                VescCrypto.kdf(s, _ui.value.login, newPassword)
            }
            VescProtocol.newKey(k, c, newK).forEach { client.send(it) }
        }
    }

    fun goTo(screen: Screen) = _ui.update { it.copy(screen = screen) }

    fun consumeMessage() = _ui.update { it.copy(message = null) }

    // ------------------------------------------------------------- события

    private fun onLink(l: Link) {
        _ui.update { it.copy(link = l) }
        when (l) {
            Link.READY -> {
                _ui.update { it.copy(busy = null) }
                client.send(VescProtocol.status())
            }
            Link.LOST -> {
                forgetSession()
                _ui.update {
                    it.copy(
                        screen = Screen.SCAN, busy = null, status = null, peers = emptyList(),
                        message = "Модуль закрыл связь. Снимите и подайте питание, " +
                                  "затем подключитесь в первую минуту."
                    )
                }
            }
            else -> {}
        }
    }

    private fun onEvent(e: VescProtocol.Event) {
        when (e) {
            is VescProtocol.Event.Salt -> salt = e.salt

            is VescProtocol.Event.Login -> _ui.update { it.copy(login = e.login) }

            is VescProtocol.Event.State -> {
                _ui.update {
                    it.copy(
                        status = e.status,
                        busy = null,
                        screen = when {
                            e.status.authed -> if (it.screen == Screen.SCAN ||
                                                   it.screen == Screen.AUTH) Screen.HOME else it.screen
                            else -> Screen.AUTH
                        }
                    )
                }
            }

            is VescProtocol.Event.Challenge -> {
                if (!awaitingAuth) return
                awaitingAuth = false
                val k = sessionKeyMaterial ?: return
                authChallenge = e.challenge
                client.send(VescProtocol.auth(k, e.challenge))
            }

            is VescProtocol.Event.Peer -> {
                val active = _ui.value.status?.activePeer
                incoming += PeerRow(e.index, e.name, e.index == active)
            }

            is VescProtocol.Event.ListEnd -> {
                val list = incoming.toList()
                incoming.clear()
                _ui.update { it.copy(peers = list) }
            }

            is VescProtocol.Event.Bound -> {
                _ui.update {
                    it.copy(message = "Телефон привязан. Дайте ему имя в списке телефонов.")
                }
                client.send(VescProtocol.status())
                client.send(VescProtocol.list())
            }

            is VescProtocol.Event.Result -> onResult(e)

            is VescProtocol.Event.Unknown -> {}
        }
    }

    private fun onResult(r: VescProtocol.Event.Result) {
        val cmd = r.command
        if (r.code != VescProtocol.OK) {
            _ui.update {
                it.copy(busy = null, message = VescProtocol.resultText(r.code))
            }
            if (cmd == VescProtocol.CMD_AUTH.toInt()) {
                sessionKeyMaterial = null
                client.send(VescProtocol.status())
            }
            return
        }

        when (cmd) {
            VescProtocol.CMD_AUTH.toInt() -> {
                _ui.update { it.copy(busy = null, screen = Screen.HOME) }
                client.send(VescProtocol.status())
                client.send(VescProtocol.list())
            }
            VescProtocol.CMD_RENAME.toInt(),
            VescProtocol.CMD_FORGET.toInt() -> {
                client.send(VescProtocol.list())
                client.send(VescProtocol.status())
            }
            VescProtocol.CMD_NEWKEY.toInt() -> {
                // Модуль сбросил аутентификацию вместе с ключом — это не сбой,
                // а защита: та же гамма второй раз не появится.
                forgetSession()
                _ui.update {
                    it.copy(
                        busy = null, screen = Screen.AUTH, peers = emptyList(),
                        message = "Пароль сменён. Войдите заново с новым паролем."
                    )
                }
                client.send(VescProtocol.status())
            }
            else -> _ui.update { it.copy(busy = null) }
        }
    }

    private fun fail(text: String) {
        _ui.update { it.copy(busy = null, message = text) }
    }

    private fun forgetSession() {
        sessionKeyMaterial = null
        authChallenge = null
        awaitingAuth = false
    }

    override fun onCleared() {
        client.disconnect()
        forgetSession()
    }
}
