package app.ee.core.security

import java.io.File

/**
 * A password-protected vault directory (M4 — P0-1).
 *
 * Layout on disk:
 * ```
 *   <dir>/.vault_salt        16 random bytes (PBKDF2 salt, per vault)
 *   <dir>/.vault_check       encrypted check value (proves the passphrase)
 *   <dir>/<name>.eev         encrypted files (see VaultCrypto)
 * ```
 *
 * The derived key lives in memory only, between [unlock] and [lock]; it is
 * never persisted (spec §6.3 — credentials in Keystore, never plaintext).
 * The key is not secret-at-rest on a rooted device; the threat model is
 * casual access + theft of the device with the app installed.
 */
class Vault(private val dir: File) {

    private var salt: ByteArray? = null
    @Volatile
    private var key: ByteArray? = null

    /** True after a successful [unlock]; cheap to call on every operation. */
    val isUnlocked: Boolean get() = key != null

    fun requireKey(): ByteArray =
        key ?: throw VaultLockedException()

    /** Creates the vault (salt + check file) if missing; idempotent. */
    fun init() {
        if (!dir.exists()) require(dir.mkdirs()) { "cannot create vault dir" }
        val saltFile = File(dir, SALT_FILE)
        if (saltFile.exists()) {
            salt = saltFile.readBytes()
            return
        }
        val s = VaultCrypto.randomSalt()
        saltFile.writeBytes(s)
        salt = s
        // check value proves the passphrase; written at unlock() time
        // (init() takes no passphrase yet — first unlock creates it)
    }

    /** First unlock with a passphrase establishes the vault (check file). */
    fun unlock(passphrase: String): Boolean {
        init()
        val s = salt ?: throw IllegalStateException("vault not initialized")
        val derived = VaultCrypto.deriveKey(passphrase, s)
        val checkFile = File(dir, CHECK_FILE)
        return if (checkFile.exists()) {
            val expected = checkFile.readBytes()
            runCatching { VaultCrypto.decrypt(expected, derived) }
                .map { it == CHECK_VALUE }
                .getOrDefault(false)
                .also { if (it) key = derived else key = null }
        } else {
            // new vault: remember the check value for future unlocks
            checkFile.writeBytes(VaultCrypto.encrypt(CHECK_VALUE, derived))
            key = derived
            true
        }
    }

    fun lock() {
        key = null
    }

    /** Plaintext name of an encrypted file, or null for meta files. */
    fun visibleName(fileName: String): String? =
        if (isMetaFile(fileName)) null else fileName.removeSuffix(EXT)

    /** File for a vault-relative plain path ("" → the vault root dir). */
    fun dirFor(path: String): File = if (path.isEmpty()) dir else File(dir, path)

    /** Physical file storing vault-relative plain [path] (files get [EXT]). */
    fun fileFor(plainPath: String): File = File(dir, plainPath + EXT)

    /**
     * User-visible entries of vault-relative [path] (meta files at the root
     * are hidden; names keep their on-disk form — the provider strips [EXT]).
     */
    fun children(path: String): List<File> =
        dirFor(path).listFiles()
            ?.filter { visibleName(it.name) != null }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

    private fun isMetaFile(name: String): Boolean =
        name == SALT_FILE || name == CHECK_FILE

    companion object {
        const val SALT_FILE = ".vault_salt"
        const val CHECK_FILE = ".vault_check"
        const val EXT = ".eev"

        // deterministic known plaintext — its secrecy comes from the key
        private val CHECK_VALUE = "EEVAULT-CHECK-01".toByteArray(Charsets.US_ASCII)
    }
}

class VaultLockedException : Exception("Vault is locked")
