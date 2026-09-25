package app.ee.core.security

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM envelope crypto for connection secrets (docs/02-specification.md
 * §6.3). Pure JCE so the primitives are unit-testable on the JVM; the box key
 * itself is protected by the Android Keystore (see [KeystoreSecretStore]).
 *
 * Envelope layout: `iv (12 B) || ciphertext || tag (16 B)`.
 */
object SecretBox {

    const val ALGORITHM = "AES/GCM/NoPadding"
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    fun generateBoxKey(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    fun encrypt(key: SecretKey, plaintext: ByteArray): ByteArray {
        val iv = ByteArray(GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return iv + cipher.doFinal(plaintext)
    }

    fun decrypt(key: SecretKey, envelope: ByteArray): ByteArray {
        require(envelope.size > GCM_IV_BYTES + GCM_TAG_BITS / 8) { "envelope too short" }
        val iv = envelope.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = envelope.copyOfRange(GCM_IV_BYTES, envelope.size)
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return try {
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            throw SecurityException("decryption failed (tampered or wrong key)", e)
        }
    }
}
