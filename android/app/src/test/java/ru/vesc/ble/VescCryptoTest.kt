package ru.vesc.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проверка того, что сторона телефона считает то же самое, что модуль.
 *
 * Расхождение здесь не даёт ни исключения, ни ошибки компиляции — оно
 * выглядит как «пароль верный, а модуль не пускает», и ищется часами.
 * Поэтому векторы гоняются на каждой сборке.
 */
class VescCryptoTest {

    @Test
    fun `векторы FIPS-197 и RFC 4493 и общий с модулем набор`() {
        val bad = VescCrypto.selfTest()
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun `проверки логина и пароля отсеивают кириллицу`() {
        val bad = VescCredentials.selfTest()
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun `новый ключ распаковывается обратно`() {
        val salt = ByteArray(16) { (it + 1).toByte() }
        val k = VescCrypto.kdf(salt, "owner", "correct horse")
        val challenge = ByteArray(16) { 0x5C }
        val newK = VescCrypto.kdf(salt, "owner", "a whole new password")

        val frames = VescProtocol.newKey(k, challenge, newK)
        assertEquals(2, frames.size)

        // Так распаковывает модуль.
        val sk = VescCrypto.sessionKey(k, challenge)
        val wrapped = frames[0].copyOfRange(2, 18)
        val tag = frames[1].copyOfRange(2, 18)
        assertTrue(VescCrypto.keyTag(sk, wrapped).contentEquals(tag))

        val pad = VescCrypto.keyPad(sk, challenge)
        val got = ByteArray(16) { (wrapped[it].toInt() xor pad[it].toInt()).toByte() }
        assertTrue(got.contentEquals(newK))
        assertTrue(!wrapped.contentEquals(newK))
    }
}
