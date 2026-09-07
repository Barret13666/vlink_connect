/*
 * ============================================================================
 *  VescProtocol.kt — служебный сервис глазами приложения
 * ============================================================================
 *
 *  Кадр не длиннее 20 байт. S130 в nRF51 не умеет расширять ATT_MTU, так
 *  что 20 — это потолок одного пакета, и всё, что длиннее, приходится
 *  дробить. Дробится ровно две команды: первичная настройка и смена
 *  пароля, обе по два кадра.
 *
 *  Порядок работы:
 *
 *    1. Подключиться, подписаться на EVT.
 *    2. Написать STATUS. В ответ придут STATUS и SALT.
 *    3. Если в флагах EMPTY — модуль не настроен: спросить у пользователя
 *       логин и пароль, посчитать K = VescCrypto.kdf(salt, login, password)
 *       и отправить setupLogin() и setupKey().
 *       Если EMPTY нет — спросить пароль, посчитать K по той же соли.
 *    4. Написать CHALLENGE, дождаться EVT_CHALLENGE.
 *    5. Ответить authFrame(k, challenge). RESULT с кодом OK означает, что
 *       соединение прошло проверку.
 *    6. Дальше доступны LIST, RENAME, FORGET, NEWKEY.
 *
 *  Про попытки. Их три на весь цикл включения питания модуля, и успешный
 *  вход счётчик не обнуляет. Показывайте оставшееся число из STATUS
 *  постоянно: человек, потративший их на опечатки, вынужден выключать и
 *  включать модуль, и он должен понимать это заранее, а не по факту.
 * ============================================================================
 */

package ru.vesc.ble

import java.util.UUID

object VescProtocol {

    // ------------------------------------------------------------- UUID

    val SERVICE: UUID = UUID.fromString("1e310001-6a54-4f2b-8f0a-ff6099d19348")
    val CTRL: UUID    = UUID.fromString("1e310002-6a54-4f2b-8f0a-ff6099d19348")
    val EVT: UUID     = UUID.fromString("1e310003-6a54-4f2b-8f0a-ff6099d19348")

    /** Мост в VESC. Именно он лежит в пакете рекламы: на две 128-битные
     *  записи там места нет, поэтому модуль ищется по NUS, а служебный
     *  сервис обнаруживается уже после подключения. */
    val NUS: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")

    const val MAX_FRAME = 20
    const val MAX_PEERS = 8
    const val NAME_BYTES = 18

    // --------------------------------------------------------- команды

    const val CMD_STATUS: Byte    = 0x01
    const val CMD_CHALLENGE: Byte = 0x02
    const val CMD_AUTH: Byte      = 0x03
    const val CMD_LIST: Byte      = 0x04
    const val CMD_RENAME: Byte    = 0x05
    const val CMD_FORGET: Byte    = 0x06
    const val CMD_SETUP: Byte     = 0x07
    const val CMD_NEWKEY: Byte    = 0x08
    const val CMD_BIND: Byte      = 0x09

    // ----------------------------------------------------------- ответы

    const val EVT_STATUS    = 0x81
    const val EVT_CHALLENGE = 0x82
    const val EVT_RESULT    = 0x83
    const val EVT_PEER      = 0x84
    const val EVT_LISTEND   = 0x85
    const val EVT_SALT      = 0x86
    const val EVT_BOUND     = 0x87
    const val EVT_LOGIN     = 0x88

    // ------------------------------------------------- коды результата

    const val OK           = 0x00
    const val ERR_CLOSED   = 0x01
    const val ERR_AUTH     = 0x02
    const val ERR_BADPASS  = 0x03
    const val ERR_ARGS     = 0x04
    const val ERR_FULL     = 0x05
    const val ERR_NOTFOUND = 0x06
    const val ERR_FLASH    = 0x07
    const val ERR_ALREADY  = 0x08

    fun resultText(code: Int): String = when (code) {
        OK           -> "Готово."
        ERR_CLOSED   -> "Окно настройки закрыто. Выключите и снова включите " +
                        "модуль, затем повторите в первую минуту."
        ERR_AUTH     -> "Нужно сначала ввести пароль."
        ERR_BADPASS  -> "Пароль не подошёл."
        ERR_ARGS     -> "Модуль не принял команду."
        ERR_FULL     -> "В памяти уже восемь телефонов, освободите место."
        ERR_NOTFOUND -> "Такой записи нет."
        ERR_FLASH    -> "Модуль не смог записать память."
        ERR_ALREADY  -> "Модуль уже настроен."
        else         -> "Неизвестный ответ модуля ($code)."
    }

    // ------------------------------------------------------ флаги статуса

    const val FLAG_EMPTY  = 0x01
    const val FLAG_OPEN   = 0x02
    const val FLAG_AUTHED = 0x04
    const val FLAG_LOCKED    = 0x08
    const val FLAG_ENCRYPTED = 0x10

    // ---------------------------------------------------- сборка команд

    fun status()    = byteArrayOf(CMD_STATUS)
    fun challenge() = byteArrayOf(CMD_CHALLENGE)
    fun list()      = byteArrayOf(CMD_LIST)
    fun forget(index: Int) = byteArrayOf(CMD_FORGET, index.toByte())

    /**
     * Попросить модуль начать сопряжение. Доступна только после AUTH.
     * Модуль шлёт Security Request, дальше обменом руководит Android.
     * Система покажет свой диалог сопряжения — кода в нём не будет, только
     * согласие: у модуля нет ни экрана, ни клавиатуры, так что это Just
     * Works. Предупредите пользователя заранее и объясните, что нажать,
     * иначе он примет системное окно за что-то постороннее и отменит его.
     *
     * Успех приходит двумя кадрами: BOUND с номером записи и RESULT с OK.
     * Номер нужен, чтобы сразу дать телефону человеческое имя через
     * rename() — иначе в списке он останется «phone».
     */
    fun bind() = byteArrayOf(CMD_BIND)

    fun auth(k: ByteArray, challenge: ByteArray): ByteArray =
        byteArrayOf(CMD_AUTH) + VescCrypto.response(k, challenge)

    fun rename(index: Int, name: String): ByteArray {
        val n = name.toByteArray(Charsets.UTF_8).take(NAME_BYTES).toByteArray()
        return byteArrayOf(CMD_RENAME, index.toByte()) + n
    }

    /** Первичная настройка, кадр 1: логин. */
    fun setupLogin(login: String): ByteArray {
        val b = login.toByteArray(Charsets.UTF_8)
        require(b.size in 1..15) { "логин 1..15 байт" }
        return byteArrayOf(CMD_SETUP, 0) + b
    }

    /** Первичная настройка, кадр 2: K, посчитанный по выданной модулем соли. */
    fun setupKey(k: ByteArray): ByteArray {
        require(k.size == 16)
        return byteArrayOf(CMD_SETUP, 1) + k
    }

    /**
     * Смена пароля, два кадра. Новый K закрывается гаммой, выведенной из
     * ключа сеанса, — иначе он ушёл бы в эфир открытым текстом, и пассивный
     * слушатель получил бы доступ навсегда.
     *
     * challenge — тот самый, на котором прошла проверка пароля. Просить
     * новый между AUTH и NEWKEY нельзя: модуль считает гамму от старого.
     */
    fun newKey(k: ByteArray, challenge: ByteArray, newK: ByteArray): List<ByteArray> {
        require(newK.size == 16)
        val sk = VescCrypto.sessionKey(k, challenge)
        val pad = VescCrypto.keyPad(sk, challenge)
        val wrapped = ByteArray(16) { (newK[it].toInt() xor pad[it].toInt()).toByte() }
        val tag = VescCrypto.keyTag(sk, wrapped)
        return listOf(
            byteArrayOf(CMD_NEWKEY, 0) + wrapped,
            byteArrayOf(CMD_NEWKEY, 1) + tag
        )
    }

    // ----------------------------------------------------- разбор ответов

    data class Status(
        val empty: Boolean,
        val open: Boolean,
        val authed: Boolean,
        val locked: Boolean,
        val encrypted: Boolean,
        val attemptsLeft: Int,
        val peerCount: Int,
        val secondsLeft: Int,
        val maxPeers: Int,
        /** Номер записи телефона, который сейчас на связи, либо null. */
        val activePeer: Int?
    )

    sealed class Event {
        data class State(val status: Status) : Event()
        data class Salt(val salt: ByteArray) : Event()
        data class Challenge(val challenge: ByteArray) : Event()
        data class Result(val command: Int, val code: Int) : Event()
        data class Peer(val index: Int, val name: String) : Event()
        data class Bound(val index: Int) : Event()
        data class Login(val login: String) : Event()
        data class ListEnd(val count: Int) : Event()
        data class Unknown(val raw: ByteArray) : Event()
    }

    fun parse(frame: ByteArray): Event {
        if (frame.isEmpty()) return Event.Unknown(frame)
        return when (frame[0].toInt() and 0xFF) {
            EVT_STATUS -> {
                if (frame.size < 8) return Event.Unknown(frame)
                val f = frame[1].toInt() and 0xFF
                val active = frame[7].toInt() and 0xFF
                Event.State(
                    Status(
                        empty        = f and FLAG_EMPTY != 0,
                        open         = f and FLAG_OPEN != 0,
                        authed       = f and FLAG_AUTHED != 0,
                        locked       = f and FLAG_LOCKED != 0,
                        encrypted    = f and FLAG_ENCRYPTED != 0,
                        attemptsLeft = frame[2].toInt() and 0xFF,
                        peerCount    = frame[3].toInt() and 0xFF,
                        secondsLeft  = (frame[4].toInt() and 0xFF) or
                                       ((frame[5].toInt() and 0xFF) shl 8),
                        maxPeers     = frame[6].toInt() and 0xFF,
                        activePeer   = if (active == 0xFF) null else active
                    )
                )
            }
            EVT_SALT ->
                if (frame.size < 17) Event.Unknown(frame)
                else Event.Salt(frame.copyOfRange(1, 17))

            EVT_CHALLENGE ->
                if (frame.size < 17) Event.Unknown(frame)
                else Event.Challenge(frame.copyOfRange(1, 17))

            EVT_RESULT ->
                if (frame.size < 3) Event.Unknown(frame)
                else Event.Result(frame[1].toInt() and 0xFF, frame[2].toInt() and 0xFF)

            EVT_PEER -> {
                if (frame.size < 2) return Event.Unknown(frame)
                val raw = frame.copyOfRange(2, frame.size)
                val end = raw.indexOfFirst { it.toInt() == 0 }.let { if (it < 0) raw.size else it }
                Event.Peer(frame[1].toInt() and 0xFF,
                           String(raw, 0, end, Charsets.UTF_8))
            }

            EVT_LOGIN ->
                Event.Login(String(frame, 1, frame.size - 1, Charsets.UTF_8))

            EVT_BOUND ->
                if (frame.size < 2) Event.Unknown(frame)
                else Event.Bound(frame[1].toInt() and 0xFF)

            EVT_LISTEND ->
                if (frame.size < 2) Event.Unknown(frame)
                else Event.ListEnd(frame[1].toInt() and 0xFF)

            else -> Event.Unknown(frame)
        }
    }
}
