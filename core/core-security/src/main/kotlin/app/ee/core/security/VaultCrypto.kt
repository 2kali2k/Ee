package app.ee.core.security

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * File-vault primitives (M4 — P0-1, docs/02-specification.md §6.3).
 *
 * Password → AES-256-GCM key via PBKDF2-HMAC-SHA256 (210k iterations, OWASP
 * 2023 minimum for SHA-256). Each file gets a fresh 12-byte IV.
 *
 * Container format (big-endian, constant per file):
 * ```
 *   offset  size  field
 *   0       5     magic "EEVF1"
 *   5       12    IV
 *   17      *     ciphertext + 16-byte GCM tag
 * ```
 *
 * Pure JVM — fully unit-testable, no Android dependency.
 */
object VaultCrypto {

    const val MAGIC = "EEVF1"
    const val VERSION = 1
    const val SALT_SIZE = 16
    const val IV_SIZE = 12
    const val KEY_SIZE_BITS = 256
    const val GCM_TAG_BITS = 128
    const val PBKDF2_ITERATIONS = 210_000
    const val MAX_FILE_BYTES: Long = 512L * 1024 * 1024

    private val random = SecureRandom()

    fun randomSalt(): ByteArray {
        val b = ByteArray(SALT_SIZE)
        random.nextBytes(b)
        return b
    }

    fun randomIv(): ByteArray {
        val b = ByteArray(IV_SIZE)
        random.nextBytes(b)
        return b
    }

    /** PBKDF2-HMAC-SHA256 → raw 32-byte AES key. */
    fun deriveKey(passphrase: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_SIZE_BITS)
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded.also { spec.clear() }
    }

    private fun secretKey(keyBytes: ByteArray): SecretKey =
        SecretKeySpec(keyBytes, "AES")

    /** Encrypt → full container (magic + iv + ciphertext+tag). */
    fun encrypt(plain: ByteArray, key: ByteArray): ByteArray {
        check(plain.size <= MAX_FILE_BYTES) { "file too large for vault" }
        val iv = randomIv()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey(key), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ct = cipher.doFinal(plain)
        return (MAGIC.toByteArray(Charsets.US_ASCII) + iv + ct)
    }

    /** Decrypt a full container; throws on bad magic / tamper / wrong key. */
    fun decrypt(container: ByteArray, key: ByteArray): ByteArray {
        require(container.size > MAGIC.length + IV_SIZE) { "vault file too short" }
        val magic = String(container, 0, MAGIC.length, Charsets.US_ASCII)
        require(magic == MAGIC) { "not a vault file (bad magic)" }
        val iv = container.copyOfRange(MAGIC.length, MAGIC.length + IV_SIZE)
        val ct = container.copyOfRange(MAGIC.length + IV_SIZE, container.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(key), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ct)
    }
}
