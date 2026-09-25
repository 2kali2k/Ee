package app.ee.core.model

import java.util.Base64

/**
 * On-disk format of the encrypted vault container (provider-vault).
 *
 * Format `v1`:
 *  - symmetric cipher : AES-256-GCM
 *  - chunk size       : 4 MiB (each chunk encrypted independently)
 *  - per-chunk nonce  : 12 bytes = 8-byte little-endian chunk counter,
 *    zero-padded on the right (GCM requires 96-bit nonces; the fixed prefix
 *    makes nonces unique per chunk)
 *  - header           : 64 KiB — magic, version, params and (in v1) no
 *    directory index; the whole header is authenticated with HMAC-SHA256
 *    under a header key derived from the same master key.
 *
 * The password is never stored; it is stretched by the KDF below.
 */
data class VaultContainer(
    val path: String,
    val version: Int = 1,
    val kdf: Kdf,
    val aead: String = "AES-256-GCM",
    val chunkSizeBytes: Long = 4L * 1024 * 1024,
) {
    /**
     * Key-derivation parameters for opening the container.
     * `salt` is Base64-encoded so the type stays a value type (arrays would
     * break `data class` equality).
     */
    data class Kdf(
        val algorithm: String,
        val saltBase64: String,
        /** scrypt `n` as 2^logN (logN = 15 => n = 32768). */
        val logN: Int = 15,
        val r: Int = 8,
        val p: Int = 1,
    ) {
        val salt: ByteArray get() = Base64.getDecoder().decode(saltBase64)

        companion object {
            const val SCRYPT = "SCRYPT"
        }
    }
}
