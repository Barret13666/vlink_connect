/*
 * ============================================================================
 *  VescCrypto.kt — то же самое, что crypto.cpp, только на стороне телефона
 * ============================================================================
 *
 *  Приложение обязано считать K, ответ и ключ сеанса ровно так же, как
 *  модуль, иначе запрос-ответ не сойдётся, и понять почему будет тяжело:
 *  ошибка вылезет не как исключение, а как молчаливое несовпадение
 *  шестнадцати байт.
 *
 *  AES-CMAC в стандартном JCE Android нет, а тащить ради него BouncyCastle
 *  незачем: поверх Cipher("AES/ECB/NoPadding") это сорок строк.
 *
 *  KDF считается ЗДЕСЬ, на телефоне. Модуль хранит только соль и K и умеет
 *  проверить ответ — пароль по воздуху не уходит вообще (раздел 4 проекта).
 *  Десять тысяч итераций на телефоне занимают доли секунды, но всё равно
 *  уводите их с главного потока: на слабых аппаратах заметно.
 *
 *  Прежде чем писать протокол, запустите selfTest(). Если он не проходит,
 *  дальше идти бессмысленно.
 * ============================================================================
 */

package ru.vlink.connect

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object VescCrypto {

    /** Должно совпадать с CRYPTO_KDF_ROUNDS в crypto.h. */
    const val KDF_ROUNDS = 10_000

    /** Метки назначения. Менять нельзя: перестанут сходиться сохранённые K. */
    private const val TAG_RESPONSE: Byte = 0x01
    private const val TAG_SESSION: Byte = 0x02
    private const val TAG_KEYPAD: Byte = 0x03
    private const val TAG_KEYTAG: Byte = 0x04

    /** Логин ограничен полем в памяти модуля: 15 байт в UTF-8, то есть
     *  всего семь кириллических букв. Проверяйте длину в БАЙТАХ, а не в
     *  символах — иначе пользователь введёт «Владелец» и упрётся. */
    const val MAX_LOGIN_BYTES = 15
    const val MAX_PASSWORD_BYTES = 64

    // ---------------------------------------------------------------- CMAC

    /**
     * AES-CMAC по RFC 4493. Cipher инициализируется один раз в конструкторе:
     * doFinal возвращает объект в исходное состояние, так что для KDF можно
     * держать один экземпляр на все десять тысяч итераций.
     */
    class Cmac(key: ByteArray) {

        init { require(key.size == 16) { "ключ CMAC должен быть 16 байт" } }

        private val cipher: Cipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        }

        private val k1: ByteArray
        private val k2: ByteArray

        init {
            val l = cipher.doFinal(ByteArray(16))
            k1 = timesX(l)
            k2 = timesX(k1)
        }

        /** Сдвиг влево на бит по всему блоку, и если ушла единица —
         *  подмешать 0x87. Это умножение на x в GF(2^128). */
        private fun timesX(input: ByteArray): ByteArray {
            val out = ByteArray(16)
            var carry = 0
            for (i in 15 downTo 0) {
                val v = input[i].toInt() and 0xFF
                out[i] = ((v shl 1) or carry).toByte()
                carry = v ushr 7
            }
            if ((input[0].toInt() and 0x80) != 0) {
                out[15] = (out[15].toInt() xor 0x87).toByte()
            }
            return out
        }

        fun mac(msg: ByteArray): ByteArray {
            // Пустое сообщение считается одним неполным блоком: так в RFC.
            var blocks = (msg.size + 15) / 16
            val lastComplete = msg.isNotEmpty() && msg.size % 16 == 0
            if (blocks == 0) blocks = 1

            var x = ByteArray(16)
            val block = ByteArray(16)

            for (i in 0 until blocks) {
                val off = i * 16
                val last = i == blocks - 1

                if (!last || lastComplete) {
                    System.arraycopy(msg, off, block, 0, 16)
                    if (last) for (j in 0..15) {
                        block[j] = (block[j].toInt() xor k1[j].toInt()).toByte()
                    }
                } else {
                    val tail = msg.size - off
                    java.util.Arrays.fill(block, 0)
                    if (tail > 0) System.arraycopy(msg, off, block, 0, tail)
                    block[tail] = 0x80.toByte()          // дополнение
                    for (j in 0..15) {
                        block[j] = (block[j].toInt() xor k2[j].toInt()).toByte()
                    }
                }

                for (j in 0..15) block[j] = (block[j].toInt() xor x[j].toInt()).toByte()
                x = cipher.doFinal(block)
            }
            return x
        }
    }

    // ----------------------------------------------------------------- PRF

    /** AES-CMAC-PRF-128 по RFC 4615: то же, но ключ произвольной длины. */
    fun prf128(key: ByteArray, msg: ByteArray): ByteArray {
        val k = if (key.size == 16) key else Cmac(ByteArray(16)).mac(key)
        return Cmac(k).mac(msg)
    }

    // ----------------------------------------------------------------- KDF

    /**
     * K = KDF(соль, логин, пароль).
     *
     *   Kp   = пароль, если он ровно 16 байт, иначе CMAC(0^16, пароль)
     *   seed = соль[16] || логин в UTF-8 || 0x00 || 00 00 00 01
     *   U1   = CMAC(Kp, seed);  Ui = CMAC(Kp, U(i-1))
     *   K    = U1 xor U2 xor ... xor U10000
     *
     * Это PBKDF2 с PRF = AES-CMAC и длиной результата ровно в один блок.
     * Логин подмешан в соль, чтобы один пароль под разными логинами давал
     * разные K.
     *
     * Считать в фоне: на главном потоке это заметная пауза.
     */
    fun kdf(salt: ByteArray, login: String, password: String): ByteArray {
        require(salt.size == 16) { "соль должна быть 16 байт" }

        val loginBytes = login.toByteArray(Charsets.UTF_8)
        require(loginBytes.size <= MAX_LOGIN_BYTES) {
            "логин длиннее $MAX_LOGIN_BYTES байт в UTF-8"
        }
        val passBytes = password.toByteArray(Charsets.UTF_8)
        require(passBytes.size <= MAX_PASSWORD_BYTES) { "пароль слишком длинный" }

        val kp = if (passBytes.size == 16) passBytes
                 else Cmac(ByteArray(16)).mac(passBytes)

        val cmac = Cmac(kp)
        val seed = salt + loginBytes + byteArrayOf(0, 0, 0, 0, 1)

        var u = cmac.mac(seed)
        val acc = u.copyOf()
        for (i in 1 until KDF_ROUNDS) {
            u = cmac.mac(u)
            for (j in 0..15) acc[j] = (acc[j].toInt() xor u[j].toInt()).toByte()
        }
        return acc
    }

    // ------------------------------------------------------- запрос-ответ

    private fun tagged(k: ByteArray, tag: Byte, challenge: ByteArray): ByteArray {
        require(k.size == 16) { "K должен быть 16 байт" }
        require(challenge.size == 16) { "challenge должен быть 16 байт" }
        return Cmac(k).mac(byteArrayOf(tag) + challenge)
    }

    /** Ответ на запрос модуля. */
    fun response(k: ByteArray, challenge: ByteArray) =
        tagged(k, TAG_RESPONSE, challenge)

    /** Ключ сеанса: им закрывается новый K при смене пароля, чтобы тот не
     *  ушёл в эфир открытым текстом. */
    fun sessionKey(k: ByteArray, challenge: ByteArray) =
        tagged(k, TAG_SESSION, challenge)

    /** Гамма для нового K: AES-CMAC(ключ_сеанса, 0x03 || challenge).
     *  Однократная — challenge на каждую проверку пароля новый, а после
     *  смены ключа модуль сбрасывает аутентификацию. */
    fun keyPad(sessionKey: ByteArray, challenge: ByteArray) =
        tagged(sessionKey, TAG_KEYPAD, challenge)

    /** Метка целостности на закрытый гаммой ключ. */
    fun keyTag(sessionKey: ByteArray, wrapped: ByteArray) =
        tagged(sessionKey, TAG_KEYTAG, wrapped)

    // ------------------------------------------------------- самопроверка

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or
                                   s[it * 2 + 1].digitToInt(16)).toByte() }

    private fun hex(b: ByteArray): String =
        b.joinToString("") { "%02x".format(it) }

    /**
     * Прогоняет опубликованные векторы и контрольный набор, общий с модулем.
     * Возвращает список расхождений: пустой — всё сошлось.
     */
    fun selfTest(): List<String> {
        val bad = mutableListOf<String>()

        fun expect(name: String, got: ByteArray, want: String) {
            if (hex(got) != want) bad += "$name: получено ${hex(got)}, ожидалось $want"
        }

        // FIPS-197, приложение B
        val key = hex("2b7e151628aed2a6abf7158809cf4f3c")
        val c = Cipher.getInstance("AES/ECB/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        expect("AES-128", c.doFinal(hex("3243f6a8885a308d313198a2e0370734")),
               "3925841d02dc09fbdc118597196a0b32")

        // RFC 4493, все четыре примера
        val m = hex("6bc1bee22e409f96e93d7e117393172a" +
                    "ae2d8a571e03ac9c9eb76fac45af8e51" +
                    "30c81c46a35ce411e5fbc1191a0a52ef" +
                    "f69f2445df4f9b17ad2b417be66c3710")
        val cmac = Cmac(key)
        expect("CMAC len=0",  cmac.mac(ByteArray(0)),        "bb1d6929e95937287fa37d129b756746")
        expect("CMAC len=16", cmac.mac(m.copyOfRange(0, 16)), "070a16b46b4d4144f79bdd9dd04a287c")
        expect("CMAC len=40", cmac.mac(m.copyOfRange(0, 40)), "dfa66747de9ae63030ca32611497c827")
        expect("CMAC len=64", cmac.mac(m),                    "51f0bebf7e3b9d92fc49741779363cfe")

        // Контрольный набор, общий с модулем: команда 'v' на стенде печатает
        // ровно эти значения.
        val salt = hex("0102030405060708090a0b0c0d0e0f10")
        val k = kdf(salt, "owner", "correct horse")
        expect("KDF", k, "29efea2f8fd8d2b1448991501235c43b")

        val challenge = hex("deadbeef00112233445566778899aabb")
        expect("ответ",      response(k, challenge),   "87695c64643c7937f3b9945e0c03ad4f")
        expect("ключ сеанса", sessionKey(k, challenge), "313387d9ab33c2c2265fd1434e522750")

        return bad
    }
}
