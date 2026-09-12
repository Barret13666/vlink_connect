/*
 * ============================================================================
 *  BridgeProtocol.kt — сервис настройки моста UART-дисплея
 * ============================================================================
 *
 *  Мост — это ESP32 внутри самоката: с одной стороны к нему проводом
 *  подключён штатный дисплей веска, с другой он по воздуху ходит к модулю
 *  за теми же байтами, что раньше шли по проводу. Для BLE он такой же
 *  центральный, как телефон, и защищённый модуль пускает его на тех же
 *  условиях — только по бонду.
 *
 *  ПОЧЕМУ ПРИВЯЗКУ МОСТА НЕЛЬЗЯ СДЕЛАТЬ ТАК ЖЕ, КАК ПРИВЯЗКУ ТЕЛЕФОНА
 *
 *  Кнопка «Привязать этот телефон» работает потому, что телефон и есть тот,
 *  кто ввёл пароль: сопряжение идёт внутри того же соединения. С мостом так
 *  не выйдет по двум причинам сразу. Пароля он не знает — и не должен:
 *  он стоит внутри самоката, и вынуть из него что угодно проще, чем из
 *  телефона. А соединение у модуля одно, и пока на нём висит телефон,
 *  мосту физически некуда подключиться.
 *
 *  Поэтому привязка разнесена во времени. Телефон, уже прошедший пароль,
 *  открывает в модуле окно: «ближайшие две минуты жди сопряжения вот с
 *  этого адреса». Затем отпускает модуль. Мост подключается сам и
 *  сопрягается. Разрешение одноразовое и привязано к конкретному адресу.
 *
 *  ПОРЯДОК ДЕЙСТВИЙ, КОТОРЫЙ ИЗ ЭТОГО СЛЕДУЕТ
 *
 *    1. Подключиться к мосту, спросить STATUS — узнать его собственный
 *       адрес. Без адреса окно в модуле пришлось бы открывать «для любого».
 *    2. Модулю: CMD_ALLOW с этим адресом и именем будущей записи.
 *    3. Мосту: TARGET с адресом модуля, затем PAIR.
 *    4. Отключиться от модуля — иначе мосту не к чему подключаться.
 *    5. Дождаться от моста BR_EVT_BOUND.
 *
 *  Шаг 4 обязателен и именно в этом месте: PAIR отдаётся раньше, чтобы
 *  мост уже искал модуль к моменту, когда тот освободится.
 * ============================================================================
 */

package ru.vlink.connect

import java.util.UUID

object BridgeProtocol {

    // ------------------------------------------------------------- UUID

    val SERVICE: UUID = UUID.fromString("1e320001-6a54-4f2b-8f0a-ff6099d19348")
    val CTRL: UUID    = UUID.fromString("1e320002-6a54-4f2b-8f0a-ff6099d19348")
    val EVT: UUID     = UUID.fromString("1e320003-6a54-4f2b-8f0a-ff6099d19348")

    /** Имя, под которым мост объявляет себя в эфире. Поиск идёт по UUID
     *  сервиса — он лежит в самом рекламном пакете, и фильтр Android до
     *  него точно дотянется. Имя приезжает в scan response и нужно только
     *  человеку.
     *
     *  В эфире мост держится недолго: две минуты после подачи питания, пока
     *  открыто окно настройки. Дальше он из эфира уходит — настраивать
     *  нечего, а лишний открытый сервис это лишняя дверь. Не нашли мост в
     *  списке — снимите и подайте питание. */
    const val NAME = "VESC BLE <-> UART Bridge"

    // --------------------------------------------------------- команды

    const val CMD_STATUS: Byte  = 0x01
    const val CMD_TARGET: Byte  = 0x03
    const val CMD_PAIR: Byte    = 0x04
    const val CMD_UNPAIR: Byte  = 0x05
    /* 0x02 и 0x06 были командами «отойди в сторону» и «вернись». Убраны
       намеренно: команда, освобождающая модуль по воздуху, обязана была бы
       работать всегда — иначе владелец запер бы себя сам, — а значит,
       гасить чужой дисплей мог бы любой прохожий. Модуль освобождают
       снятием питания с моста. Номера оставлены дырой. */

    // ----------------------------------------------------------- ответы

    const val EVT_STATUS = 0x81
    const val EVT_RESULT = 0x82
    const val EVT_BOUND  = 0x83

    // ------------------------------------------------------------ флаги

    const val FLAG_PROV_OPEN = 0x01
    const val FLAG_BONDED    = 0x02
    const val FLAG_TARGET    = 0x04
    const val FLAG_LINK      = 0x08
    const val FLAG_ENCRYPTED = 0x10

    // -------------------------------------------------- коды результата

    const val OK           = 0x00
    const val ERR_CLOSED   = 0x01
    const val ERR_ARGS     = 0x02
    const val ERR_NOTARGET = 0x03
    const val ERR_FAIL     = 0x04

    // ------------------------------- причины в кадре BOUND

    const val WHY_OK        = 0x00
    const val WHY_ENCRYPT   = 0x01
    const val WHY_NOSVC     = 0x02
    const val WHY_NOCHAR    = 0x03
    const val WHY_NONOTIFY  = 0x04

    /** Почему мост не заработал. Разделять «сопряжение не прошло» и
     *  «сопряглись, но сервис не нашли» обязательно: лечится это совершенно
     *  по-разному, а раньше оба случая выглядели как успех. */
    fun boundWhyText(why: Int): String = when (why) {
        WHY_OK        -> "Готово."
        WHY_ENCRYPT   -> "Модуль не принял сопряжение. Чаще всего окно " +
                         "привязки уже истекло или модуль был занят. " +
                         "Начните заново после подачи питания."
        WHY_NOSVC     -> "Сопряжение прошло, но на модуле не нашёлся сервис " +
                         "UART (NUS). Похоже, мост подключился не к тому " +
                         "устройству — проверьте адрес модуля."
        WHY_NOCHAR    -> "Сопряжение прошло, но сервис UART на модуле " +
                         "неполный."
        WHY_NONOTIFY  -> "Сопряжение прошло, но подписаться на данные не " +
                         "удалось: шифрование поднялось не до конца. " +
                         "Повторите привязку после подачи питания."
        else          -> "Мост не заработал (причина $why)."
    }

    fun resultText(code: Int): String = when (code) {
        OK           -> "Готово."
        ERR_CLOSED   -> "Окно настройки моста закрыто. Снимите и подайте " +
                        "питание на самокат и повторите."
        ERR_ARGS     -> "Мост не принял команду."
        ERR_NOTARGET -> "Мосту не задан адрес модуля."
        ERR_FAIL     -> "У моста не получилось."
        else         -> "Неизвестный ответ моста ($code)."
    }

    // ------------------------------------------------------- состояния

    const val ST_IDLE       = 0
    const val ST_SCANNING   = 1
    const val ST_CONNECTING = 2
    const val ST_PAIRING    = 3
    const val ST_BRIDGING   = 4

    /** Одной строкой, для показа на экране. */
    fun summary(st: Status): String = buildString {
        append(stateText(st.state))
        append("\n")
        append(if (st.paired) "Привязка к защищённому модулю есть"
               else "Привязки нет")
        if (st.targetMac.isNotEmpty()) append(", модуль ").append(st.targetMac)
        else append(", модуль не задан")
        append("\n")
        append(
            when {
                st.encrypted -> "Связь с модулем зашифрована"
                // Без шифрования - это не обязательно беда: так мост
                // работает с обычным модулем Веддера, где защищать нечего.
                st.linked    -> if (st.state == ST_BRIDGING)
                                    "Связь без шифрования — модуль без защиты"
                                else
                                    "Подключён, но шифрование не поднято"
                else         -> "С модулем не соединён"
            }
        )
        if (!st.provOpen) append("\nОкно настройки закрыто")
    }

    fun stateText(state: Int): String = when (state) {
        ST_IDLE       -> "Не настроен, ждёт привязки"
        ST_SCANNING   -> "Ищет модуль"
        ST_CONNECTING -> "Подключается к модулю"
        ST_PAIRING    -> "Сопрягается"
        ST_BRIDGING   -> "Работает: дисплей на связи"
        else          -> "Состояние $state"
    }

    // ---------------------------------------------------- сборка команд

    fun status()  = byteArrayOf(CMD_STATUS)
    fun pair()    = byteArrayOf(CMD_PAIR)
    fun unpair()  = byteArrayOf(CMD_UNPAIR)

    /** Адрес модуля в том виде, в каком его показывает Android:
     *  «AA:BB:CC:DD:EE:FF», старший байт первым. */
    fun target(mac: String): ByteArray {
        val a = parseMac(mac) ?: throw IllegalArgumentException("не адрес: $mac")
        return byteArrayOf(CMD_TARGET) + a
    }

    // ------------------------------------------------------------ адрес

    /** Шесть байт из строки MAC, или null, если это не MAC. Порядок тот же,
     *  в каком строка написана. */
    fun parseMac(mac: String): ByteArray? {
        val parts = mac.trim().split(':', '-')
        if (parts.size != 6) return null
        val out = ByteArray(6)
        for (i in 0 until 6) {
            val v = parts[i].toIntOrNull(16) ?: return null
            if (v !in 0..255) return null
            out[i] = v.toByte()
        }
        return out
    }

    fun macToString(a: ByteArray): String =
        a.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }

    private fun isZero(a: ByteArray) = a.all { it.toInt() == 0 }

    // ----------------------------------------------------- разбор ответов

    data class Status(
        val provOpen: Boolean,
        /** Мост считает себя привязанным (его собственный признак в NVS,
         *  а не то, что видно в хранилище бондов). */
        val paired: Boolean,
        val hasTarget: Boolean,
        val linked: Boolean,
        val encrypted: Boolean,
        val state: Int,
        /** Собственный адрес моста — его отдаём модулю в CMD_ALLOW. */
        val selfMac: String,
        /** Адрес модуля, который мост считает своим, либо пустая строка. */
        val targetMac: String
    )

    sealed class Event {
        data class State(val status: Status) : Event()
        data class Result(val command: Int, val code: Int) : Event()
        data class Bound(val ok: Boolean, val why: Int) : Event()
        data class Unknown(val raw: ByteArray) : Event()
    }

    fun parse(frame: ByteArray): Event {
        if (frame.isEmpty()) return Event.Unknown(frame)
        return when (frame[0].toInt() and 0xFF) {
            EVT_STATUS -> {
                if (frame.size < 15) return Event.Unknown(frame)
                val f = frame[1].toInt() and 0xFF
                val self = frame.copyOfRange(3, 9)
                val target = frame.copyOfRange(9, 15)
                Event.State(
                    Status(
                        provOpen  = f and FLAG_PROV_OPEN != 0,
                        paired    = f and FLAG_BONDED != 0,
                        hasTarget = f and FLAG_TARGET != 0,
                        linked    = f and FLAG_LINK != 0,
                        encrypted = f and FLAG_ENCRYPTED != 0,
                        state     = frame[2].toInt() and 0xFF,
                        selfMac   = if (isZero(self)) "" else macToString(self),
                        targetMac = if (isZero(target)) "" else macToString(target)
                    )
                )
            }

            EVT_RESULT ->
                if (frame.size < 3) Event.Unknown(frame)
                else Event.Result(frame[1].toInt() and 0xFF, frame[2].toInt() and 0xFF)

            EVT_BOUND ->
                if (frame.size < 2) Event.Unknown(frame)
                else Event.Bound(
                    ok = frame[1].toInt() != 0,
                    // Третий байт появился позже кадра. Прошивка без него
                    // считается «причина не указана».
                    why = if (frame.size >= 3) frame[2].toInt() and 0xFF else -1
                )

            else -> Event.Unknown(frame)
        }
    }
}
