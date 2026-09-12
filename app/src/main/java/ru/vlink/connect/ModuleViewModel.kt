package ru.vlink.connect

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { SCAN, AUTH, HOME, PHONES, PASSWORD, BRIDGES }

data class PeerRow(val index: Int, val name: String, val isThisPhone: Boolean)

/**
 * Ход мастера привязки моста. Показывается человеку словами: шагов много,
 * между ними телефон отпускает модуль, и без бегущей строки это выглядит
 * как «нажал, и ничего не произошло».
 */
enum class BridgeStep {
    IDLE,           // ничего не делаем
    CONNECTING,     // подключаемся к мосту
    ASKING,         // спрашиваем у моста его адрес
    ALLOWING,       // открываем окно в модуле
    TARGETING,      // отдаём мосту адрес модуля
    ARMING,         // командуем мосту сопрягаться
    RELEASING,      // отпускаем модуль
    WAITING,        // ждём, пока мост сопряжётся
    DONE,
    FAILED
}

data class UiState(
    val screen: Screen = Screen.SCAN,
    val link: Link = Link.IDLE,
    val found: List<FoundModule> = emptyList(),
    val moduleName: String = "",
    /** Адрес модуля, к которому подключены. Нужен мосту: искать по имени он
     *  умеет, но рядом может стоять чужой самокат с таким же именем. */
    val moduleAddress: String = "",
    val status: VescProtocol.Status? = null,
    val login: String = "",
    val peers: List<PeerRow> = emptyList(),
    val busy: String? = null,
    val message: String? = null,

    // --- мост UART-дисплея ---
    val bridgeLink: Link = Link.IDLE,
    val bridgeName: String = "",
    val bridgeAddress: String = "",
    val bridgeStatus: BridgeProtocol.Status? = null,
    val bridgeStep: BridgeStep = BridgeStep.IDLE,
    /** Что мастер сделал к этой секунде, по строке на шаг. */
    val bridgeLog: List<String> = emptyList()
) {
    val bridges: List<FoundModule> get() = found.filter { it.kind == DeviceKind.BRIDGE }
    val modules: List<FoundModule> get() = found.filter { it.kind == DeviceKind.MODULE }
}

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
    private val bridge: BridgeClient = BleBridgeClient(app)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    // Секреты сеанса
    private var salt: ByteArray? = null
    private var sessionKeyMaterial: ByteArray? = null   // K
    private var authChallenge: ByteArray? = null
    private var awaitingAuth = false

    private val incoming = mutableListOf<PeerRow>()

    /** Имя, которое человек дал мосту: модуль запишет его сам в момент
     *  сопряжения. Прийти и переименовать запись потом будет негде —
     *  служебная минута к тому времени кончится, а модуль займёт сам мост. */
    private var bridgeWantedName = "Мост дисплея"

    private var bridgeTimeout: Job? = null

    init {
        viewModelScope.launch { client.link.collect(::onLink) }
        viewModelScope.launch { client.found.collect { f -> _ui.update { it.copy(found = f) } } }
        viewModelScope.launch { client.events.collect(::onEvent) }
        viewModelScope.launch {
            client.notice.collect { text -> _ui.update { it.copy(busy = null, message = text) } }
        }

        viewModelScope.launch { bridge.link.collect(::onBridgeLink) }
        viewModelScope.launch { bridge.events.collect(::onBridgeEvent) }
        viewModelScope.launch {
            bridge.notice.collect { text -> _ui.update { it.copy(message = text) } }
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
        _ui.update {
            it.copy(moduleName = m.name, moduleAddress = m.id, busy = "Подключаюсь...")
        }
        client.connect(m.id)
    }

    fun disconnect() {
        client.disconnect()
        bridge.disconnect()
        bridgeTimeout?.cancel()
        forgetSession()
        _ui.update {
            UiState(message = "Отключились. Модуль снова в эфире — можно открывать VESC Tool.")
        }
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

    /**
     * Обычно логин приходит от модуля кадром 0x88 — это нужно, чтобы телефон
     * друга, которого владелец добавляет впервые, мог посчитать ключ. Но
     * прошивки до появления этого кадра логин не сообщают, и тогда
     * приходится спрашивать у человека: [typedLogin].
     */
    fun login(password: String, typedLogin: String = "") {
        val s = salt ?: return fail("Модуль не прислал соль")
        val l = _ui.value.login.ifEmpty { typedLogin.trim() }
        if (l.isEmpty()) {
            return fail("Введите логин: этот модуль его не сообщает")
        }
        (VescCredentials.checkLogin(l) as? VescCredentials.Verdict.Error)
            ?.let { return fail(it.text) }
        (VescCredentials.checkPassword(password) as? VescCredentials.Verdict.Error)
            ?.let { return fail(it.text) }

        viewModelScope.launch {
            _ui.update { it.copy(busy = "Считаю ключ...", login = l) }
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

    // ==================================================== мост UART-дисплея

    /**
     * Привязать мост к модулю, к которому мы сейчас подключены.
     *
     * Мастер идёт по шагам и в конце отпускает модуль — иначе мосту некуда
     * будет подключиться. Возврата на экран модуля после этого нет: чтобы
     * снова с ним поговорить, нужна новая подача питания и новое служебное
     * окно. Так что предупредить человека надо ДО запуска, а не после.
     */
    fun bridgeBind(m: FoundModule, name: String) {
        val st = _ui.value.status
        if (st?.authed != true) {
            return fail("Сначала войдите в модуль: привязать мост может " +
                        "только тот, кто знает пароль.")
        }
        if (_ui.value.moduleAddress.isEmpty()) {
            return fail("Не знаю адреса модуля — переподключитесь к нему.")
        }
        if (st.peerCount >= st.maxPeers) {
            return fail("В памяти модуля уже нет свободных мест. " +
                        "Удалите лишний телефон и повторите.")
        }

        bridgeWantedName = name.ifBlank { "Мост дисплея" }
        _ui.update {
            it.copy(
                bridgeName = m.name, bridgeAddress = m.id,
                bridgeStep = BridgeStep.CONNECTING,
                bridgeLog = listOf("Подключаюсь к мосту..."),
                screen = Screen.BRIDGES
            )
        }
        bridge.connect(m.id)
    }

    fun bridgeDisconnect() {
        bridge.disconnect()
        bridgeTimeout?.cancel()
        _ui.update {
            it.copy(bridgeStep = BridgeStep.IDLE, bridgeStatus = null, bridgeLog = emptyList())
        }
    }

    /** Забыть привязку на стороне моста. Запись в модуле при этом остаётся —
     *  её удаляют в списке телефонов, как любую другую. */
    fun bridgeUnpair() {
        bridge.send(BridgeProtocol.unpair())
    }

    private fun bridgeLog(text: String) {
        _ui.update { it.copy(bridgeLog = it.bridgeLog + text) }
    }

    private fun bridgeFail(text: String) {
        bridgeTimeout?.cancel()
        _ui.update {
            it.copy(bridgeStep = BridgeStep.FAILED, busy = null, message = text)
        }
        bridgeLog("Не вышло: $text")
    }

    private fun onBridgeLink(l: Link) {
        _ui.update { it.copy(bridgeLink = l) }
        when (l) {
            Link.READY -> {
                bridgeLog("Мост на связи, спрашиваю его состояние")
                if (_ui.value.bridgeStep == BridgeStep.CONNECTING) {
                    _ui.update { it.copy(bridgeStep = BridgeStep.ASKING) }
                }
                bridge.send(BridgeProtocol.status())
            }
            Link.LOST -> {
                // Во время ожидания сопряжения обрыв — обычное дело: мост
                // занят модулем и может закрыть связь с телефоном. Мастер к
                // этому моменту уже всё отдал, так что честно говорим, где
                // смотреть результат, вместо крика об ошибке.
                when (_ui.value.bridgeStep) {
                    BridgeStep.WAITING ->
                        bridgeLog("Мост отключился от телефона. Результат " +
                                  "видно в списке телефонов модуля.")
                    BridgeStep.IDLE, BridgeStep.DONE, BridgeStep.FAILED -> {}
                    else -> bridgeFail("Мост закрыл связь")
                }
            }
            else -> {}
        }
    }

    private fun onBridgeEvent(e: BridgeProtocol.Event) {
        when (e) {
            is BridgeProtocol.Event.State -> {
                _ui.update { it.copy(bridgeStatus = e.status) }
                if (_ui.value.bridgeStep != BridgeStep.ASKING) return

                if (!e.status.provOpen) {
                    bridgeFail(
                        "Окно настройки моста закрыто: оно живёт пять минут " +
                        "после подачи питания. Выключите и включите самокат."
                    )
                    return
                }
                if (e.status.selfMac.isEmpty()) {
                    bridgeFail("Мост не сообщил свой адрес")
                    return
                }

                bridgeLog("Адрес моста: ${e.status.selfMac}")
                bridgeLog("Открываю в модуле окно привязки на две минуты")
                _ui.update { it.copy(bridgeStep = BridgeStep.ALLOWING) }
                client.send(VescProtocol.allowAddress(e.status.selfMac))
                client.send(VescProtocol.allowName(bridgeWantedName))
            }

            is BridgeProtocol.Event.Result -> {
                if (e.code != BridgeProtocol.OK) {
                    bridgeFail(BridgeProtocol.resultText(e.code))
                    return
                }
                when (e.command) {
                    BridgeProtocol.CMD_TARGET.toInt() -> {
                        if (_ui.value.bridgeStep != BridgeStep.TARGETING) return
                        bridgeLog("Командую мосту сопрягаться")
                        _ui.update { it.copy(bridgeStep = BridgeStep.ARMING) }
                        bridge.send(BridgeProtocol.pair())
                    }
                    BridgeProtocol.CMD_PAIR.toInt() -> {
                        if (_ui.value.bridgeStep != BridgeStep.ARMING) return
                        releaseModuleForBridge()
                    }
                    BridgeProtocol.CMD_UNPAIR.toInt() -> {
                        bridgeLog("Мост забыл привязку. Запись в модуле " +
                                  "удалите в списке телефонов.")
                        bridge.send(BridgeProtocol.status())
                    }
                }
            }

            is BridgeProtocol.Event.Bound -> {
                bridgeTimeout?.cancel()
                if (e.ok) {
                    _ui.update { it.copy(bridgeStep = BridgeStep.DONE, busy = null) }
                    bridgeLog("Готово. Мост привязан, дисплей должен ожить.")
                } else {
                    bridgeFail(
                        "Модуль не принял сопряжение. Чаще всего это значит, " +
                        "что окно привязки уже истекло. Начните заново после " +
                        "подачи питания."
                    )
                }
                bridge.send(BridgeProtocol.status())
            }

            is BridgeProtocol.Event.Unknown -> {}
        }
    }

    /**
     * Последний шаг мастера: отпустить модуль.
     *
     * Тут телефон сознательно рвёт своё соединение, чтобы мост смог занять
     * его место. Обычный disconnect() не годится — он сбрасывает состояние в
     * начальное и уводит на экран поиска, а нам нужно остаться на экране
     * мостов и досмотреть, чем кончится.
     */
    private fun releaseModuleForBridge() {
        bridgeLog("Отпускаю модуль, чтобы мост мог к нему подключиться")
        _ui.update { it.copy(bridgeStep = BridgeStep.RELEASING) }

        client.disconnect()
        forgetSession()
        _ui.update {
            it.copy(
                link = Link.IDLE, status = null, peers = emptyList(),
                bridgeStep = BridgeStep.WAITING,
                busy = "Жду, пока мост сопряжётся..."
            )
        }
        bridgeLog("Жду. Обычно это занимает несколько секунд.")

        // Окно в модуле живёт две минуты. Если за это время мост не
        // отчитался, дальше ждать нечего: разрешение сгорело.
        bridgeTimeout?.cancel()
        bridgeTimeout = viewModelScope.launch {
            delay(125_000)
            if (_ui.value.bridgeStep == BridgeStep.WAITING) {
                bridgeFail(
                    "Мост не успел за две минуты. Проверьте, что он рядом с " +
                    "модулем и питание не пропадало, и повторите после " +
                    "нового включения."
                )
            }
        }
    }

    // ------------------------------------------------------------- события

    private fun onLink(l: Link) {
        _ui.update { it.copy(link = l) }
        when (l) {
            Link.READY -> {
                _ui.update { it.copy(busy = null) }
                client.send(VescProtocol.status())
            }
            Link.LOST -> {
                // Во время привязки моста разрыв — наших рук дело, и пугать
                // человека сообщением про потерянную связь незачем.
                val duringWizard = _ui.value.bridgeStep == BridgeStep.RELEASING ||
                                   _ui.value.bridgeStep == BridgeStep.WAITING
                forgetSession()
                if (duringWizard) return
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
                        busy = if (it.bridgeStep == BridgeStep.IDLE ||
                                   it.bridgeStep == BridgeStep.DONE) null else it.busy,
                        screen = when {
                            // Пока идёт мастер привязки моста, экран не
                            // трогаем: человек смотрит на ход дела.
                            it.screen == Screen.BRIDGES -> it.screen
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

            is VescProtocol.Event.Allow -> {
                if (e.open) {
                    bridgeLog(
                        if (e.anyAddress) "Окно открыто на ${e.secondsLeft} с (для любого адреса)"
                        else "Окно открыто на ${e.secondsLeft} с для ${e.mac}"
                    )
                }
            }

            is VescProtocol.Event.Result -> onResult(e)

            is VescProtocol.Event.Unknown -> {}
        }
    }

    private fun onResult(r: VescProtocol.Event.Result) {
        val cmd = r.command
        if (r.code != VescProtocol.OK) {
            if (cmd == VescProtocol.CMD_ALLOW.toInt()) {
                bridgeFail("Модуль не открыл окно: " + VescProtocol.resultText(r.code))
                return
            }
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
            VescProtocol.CMD_ALLOW.toInt() -> {
                // Кадра два, а идём дальше по первому же подтверждению, и
                // считать их нельзя. Очередь ответов в модуле — это флаги, а
                // не буфер кадров: если второй кадр придёт раньше, чем
                // отправился ответ на первый, ответ будет ровно один.
                // Счётчик, ждущий двух, в этом случае завис бы навсегда.
                //
                // Уйти раньше, чем модуль обработает имя, не страшно:
                // дальше идут два обмена с мостом, и к моменту, когда мы
                // отпустим модуль, второй кадр давно доехал — очередь
                // записей в приложении отправляет их по одной и по порядку.
                if (_ui.value.bridgeStep != BridgeStep.ALLOWING) return
                val mac = _ui.value.moduleAddress
                bridgeLog("Отдаю мосту адрес модуля: $mac")
                _ui.update { it.copy(bridgeStep = BridgeStep.TARGETING) }
                bridge.send(BridgeProtocol.target(mac))
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
        bridgeTimeout?.cancel()
        client.disconnect()
        bridge.disconnect()
        forgetSession()
    }
}
