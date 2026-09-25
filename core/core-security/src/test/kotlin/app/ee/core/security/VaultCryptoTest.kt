package app.ee.core.security

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VaultCryptoTest {

    @Test
    fun encryptDecryptRoundTrip() {
        val key = VaultCrypto.deriveKey("correct horse battery staple", VaultCrypto.randomSalt())
        val plain = "hello vault — أرقام 123".toByteArray(Charsets.UTF_8)
        val container = VaultCrypto.encrypt(plain, key)
        assertEquals(VaultCrypto.MAGIC, String(container, 0, 5, Charsets.US_ASCII))
        assertArrayEquals(plain, VaultCrypto.decrypt(container, key))
    }

    @Test
    fun freshIvPerEncryption() {
        val key = VaultCrypto.deriveKey("pw", VaultCrypto.randomSalt())
        val plain = "same bytes".toByteArray()
        val a = VaultCrypto.encrypt(plain, key)
        val b = VaultCrypto.encrypt(plain, key)
        val ivA = a.copyOfRange(5, 17)
        val ivB = b.copyOfRange(5, 17)
        assertFalse("IVs must differ", ivA.contentEquals(ivB))
    }

    @Test
    fun wrongKeyFails() {
        val salt = VaultCrypto.randomSalt()
        val k1 = VaultCrypto.deriveKey("right", salt)
        val k2 = VaultCrypto.deriveKey("wrong", salt)
        val container = VaultCrypto.encrypt("data".toByteArray(), k1)
        try {
            VaultCrypto.decrypt(container, k2)
            fail("expected AEAD failure")
        } catch (expected: Exception) {
        }
    }

    @Test
    fun tamperedContainerFails() {
        val key = VaultCrypto.deriveKey("pw", VaultCrypto.randomSalt())
        val container = VaultCrypto.encrypt("data".toByteArray(), key)
        container[container.size - 1] = (container[container.size - 1] + 1).toByte()
        try {
            VaultCrypto.decrypt(container, key)
            fail("expected AEAD failure")
        } catch (expected: Exception) {
        }
    }

    @Test
    fun badMagicFails() {
        val key = VaultCrypto.deriveKey("pw", VaultCrypto.randomSalt())
        val container = VaultCrypto.encrypt("data".toByteArray(), key)
        container[0] = 'X'
        try {
            VaultCrypto.decrypt(container, key)
            fail("expected magic failure")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun deriveKeyIsDeterministic() {
        val salt = VaultCrypto.randomSalt()
        assertArrayEquals(
            VaultCrypto.deriveKey("pw", salt),
            VaultCrypto.deriveKey("pw", salt),
        )
    }

    @Test
    fun vaultUnlockFlow(temp: TemporaryFolder) {
        val dir: File = temp.newFolder("vault")
        val vault = Vault(dir)

        // first unlock creates the vault
        assertTrue(vault.unlock("hunter2"))
        assertTrue(vault.isUnlocked)

        // files round-trip
        val k = vault.requireKey()
        val file = vault.fileFor("notes.txt")
        file.writeBytes(VaultCrypto.encrypt("secret".toByteArray(), k))
        assertArrayEquals(
            "secret".toByteArray(),
            VaultCrypto.decrypt(file.readBytes(), k),
        )

        // lock + wrong password rejected
        vault.lock()
        assertFalse(vault.unlock("nope"))
        assertFalse(vault.isUnlocked)

        // right password restores
        assertTrue(vault.unlock("hunter2"))
        assertEquals("notes.txt.eev", vault.children("").single().name)
    }

    @Test
    fun vaultSurvivesNewInstance(temp: TemporaryFolder) {
        val dir: File = temp.newFolder("vault")
        Vault(dir).unlock("passphrase-1")
        // simulate process restart: fresh Vault + fresh checksum constant check
        val again = Vault(dir)
        assertFalse(again.isUnlocked)
        assertTrue(again.unlock("passphrase-1"))
        assertFalse(again.unlock("passphrase-1").also { again.lock() })
        assertTrue(Vault(dir).unlock("passphrase-1"))
    }
}
