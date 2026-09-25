package app.ee.core.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.charset.StandardCharsets

class SecretBoxTest {

    @Test
    fun `aes gcm round trip`() {
        val key = SecretBox.generateBoxKey()
        val secret = "hunter2-пароль-🔐".toByteArray(StandardCharsets.UTF_8)
        val envelope = SecretBox.encrypt(key, secret)
        // iv (12) + plaintext + tag (16)
        assertEquals(secret.size + 28, envelope.size)
        assertArrayEquals(secret, SecretBox.decrypt(key, envelope))
    }

    @Test
    fun `unique iv per encryption`() {
        val key = SecretBox.generateBoxKey()
        val a = SecretBox.encrypt(key, "same".toByteArray())
        val b = SecretBox.encrypt(key, "same".toByteArray())
        assertTrue("iv must be random", a.copyOfRange(0, 12).contentEquals(b.copyOfRange(0, 12)).not())
    }

    @Test
    fun `tampered ciphertext is rejected`() {
        val key = SecretBox.generateBoxKey()
        val envelope = SecretBox.encrypt(key, "data".toByteArray())
        envelope[envelope.size - 1] = (envelope[envelope.size - 1] + 1).toByte()
        try {
            SecretBox.decrypt(key, envelope)
            fail("expected SecurityException")
        } catch (e: SecurityException) {
            // expected
        }
    }

    @Test
    fun `wrong key is rejected`() {
        val a = SecretBox.generateBoxKey()
        val b = SecretBox.generateBoxKey()
        val envelope = SecretBox.encrypt(a, "data".toByteArray())
        try {
            SecretBox.decrypt(b, envelope)
            fail("expected SecurityException")
        } catch (e: SecurityException) {
            // expected
        }
    }

    @Test
    fun `empty plaintext round trips`() {
        val key = SecretBox.generateBoxKey()
        assertArrayEquals(ByteArray(0), SecretBox.decrypt(key, SecretBox.encrypt(key, ByteArray(0))))
    }

    @Test
    fun `short envelope is rejected`() {
        val key = SecretBox.generateBoxKey()
        try {
            SecretBox.decrypt(key, ByteArray(5))
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertNotNull(e.message)
        }
    }
}
