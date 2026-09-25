package app.ee.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Keystore-backed secret store for connection credentials
 * (docs/02-specification.md §6.3):
 *
 *  master key — non-exportable AES-256-GCM key in the Android Keystore
 *  box key    — random 256-bit AES key, encrypted by the master key, kept in
 *               app prefs
 *  secrets    — each `put(ref, secret)` stores base64(box(secret)) in prefs
 *
 * Plaintext secrets never touch disk. [get] returns the resolved secret or
 * null when the ref is unknown.
 */
class KeystoreSecretStore(context: Context) {

    private val prefs = context.getSharedPreferences("ee_secrets", Context.MODE_PRIVATE)

    fun put(ref: String, secret: String) {
        prefs.edit().putString(ref, encode(SecretBox.encrypt(boxKey(), secret.toByteArray()))).apply()
    }

    fun get(ref: String): String? = runCatching {
        val envelope = prefs.getString(ref, null) ?: return null
        SecretBox.decrypt(boxKey(), decode(envelope)).toString(Charsets.UTF_8)
    }.getOrNull()

    fun delete(ref: String) {
        prefs.edit().remove(ref).apply()
    }

    private fun boxKey(): SecretKey {
        prefs.getString(KEY_BOX, null)?.let { stored ->
            return SecretKeySpec(decode(stored), "AES")
        }
        val key = SecretBox.generateBoxKey()
        val master = masterKey()
        val cipher = Cipher.getInstance(KeyProperties.KEY_ALGORITHM_AES)
        cipher.init(Cipher.ENCRYPT_MODE, master)
        // Android GCM without an explicit IV generates and prepends it
        prefs.edit().putString(KEY_BOX, encode(cipher.doFinal(key.encoded))).apply()
        return key
    }

    private fun masterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(spec)
        return generator.generateKey()
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(b64: String): ByteArray = Base64.decode(b64, Base64.NO_WRAP)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "ee/master"
        const val KEY_BOX = "box"
    }
}
