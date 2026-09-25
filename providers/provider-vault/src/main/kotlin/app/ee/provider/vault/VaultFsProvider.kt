package app.ee.provider.vault

import app.ee.core.fs.FsException
import app.ee.core.fs.FsProvider
import app.ee.core.fs.FsUri
import app.ee.core.fs.Progress
import app.ee.core.model.FsCapability
import app.ee.core.model.FsKind
import app.ee.core.model.FsMetadata
import app.ee.core.model.FsNode
import app.ee.core.model.FsType
import app.ee.core.security.Vault
import app.ee.core.security.VaultCrypto
import app.ee.core.security.VaultLockedException
import java.io.File
import java.time.Instant
import javax.crypto.AEADBadTagException
import javax.crypto.GeneralSecurityException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.Source

/**
 * File vault provider (M4 — P0-1): an encrypted directory browsed through
 * the normal browser. Files are stored as `<name>.eev` containers
 * (AES-256-GCM, PBKDF2-derived key — see [VaultCrypto]); the plaintext name
 * is what the user sees.
 *
 * Locked state: read-only (listing works, open/write fail with
 * [FsException.Unauthorized]); the UI routes the user back to the vault
 * unlock screen.
 *
 * Writes buffer the whole file (spec M4 limit: [VaultCrypto.MAX_FILE_BYTES]).
 */
class VaultFsProvider(private val vault: Vault) : FsProvider {

    override val type: FsType = FsType.VAULT

    override val capabilities: Set<FsCapability>
        get() = if (vault.isUnlocked) {
            setOf(
                FsCapability.READ,
                FsCapability.WRITE,
                FsCapability.CREATE,
                FsCapability.CREATE_DIR,
                FsCapability.RENAME,
                FsCapability.DELETE,
            )
        } else {
            setOf(FsCapability.READ)
        }

    override suspend fun root(uri: String): FsNode =
        nodeFor(path = "", parent = null, isDir = true, size = null, modified = null)

    override fun list(node: FsNode): Flow<FsNode> = flow {
        val path = FsUri.parse(node.uri).path
        for (file in withContext(Dispatchers.IO) { vault.children(path) }) {
            val isDir = file.isDirectory
            val childPath = if (path.isEmpty()) file.name else "$path/${file.name}"
            emit(
                nodeFor(
                    path = childPath,
                    parent = node,
                    isDir = isDir,
                    size = if (isDir) null else file.length(),
                    modified = file.lastModified().takeIf { it > 0 },
                ),
            )
        }
    }

    override suspend fun metadata(node: FsNode): FsMetadata {
        val path = FsUri.parse(node.uri).path
        return withContext(Dispatchers.IO) {
            val file = if (path.isEmpty()) vault.dirFor(path) else vault.fileFor(path)
            FsMetadata(
                sizeBytes = if (file.isFile) file.length() else null,
                modifiedAt = file.lastModified().takeIf { it > 0 }?.let { Instant.ofEpochMilli(it) },
                isDirectory = file.isDirectory,
                extra = mapOf("encrypted" to "true"),
            )
        }
    }

    override fun open(node: FsNode): Source = try {
        val path = FsUri.parse(node.uri).path
        val file = vault.fileFor(path)
        if (!file.isFile) throw FsException.NotFound(node.uri)
        if (file.length() > VaultCrypto.MAX_FILE_BYTES) {
            throw FsException.Unsupported("file too large for vault")
        }
        val data = file.readBytes()
        Buffer().write(VaultCrypto.decrypt(data, vault.requireKey()))
    } catch (e: VaultLockedException) {
        throw FsException.Unauthorized("Vault is locked — unlock to open files")
    } catch (e: AEADBadTagException) {
        throw FsException.Unauthorized("Cannot decrypt (wrong key?)", e)
    } catch (e: GeneralSecurityException) {
        throw FsException.Remote("Decryption failed", e)
    }

    override suspend fun write(node: FsNode, bytes: Source): Flow<Progress> = flow {
        withContext(Dispatchers.IO) {
            val key = requireUnlocked()
            val buffer = Buffer()
            bytes.use { buffer.writeAll(it) }
            val data = buffer.readByteArray()
            if (data.size > VaultCrypto.MAX_FILE_BYTES) {
                throw FsException.Unsupported("file too large for vault")
            }
            val file = vault.fileFor(FsUri.parse(node.uri).path)
            file.parentFile?.mkdirs()
            file.writeBytes(VaultCrypto.encrypt(data, key))
        }
        emit(Progress(1, 1))
    }

    override suspend fun create(node: FsNode, kind: FsKind, name: String): FsNode {
        val parentPath = FsUri.parse(node.uri).path
        val childPath = if (parentPath.isEmpty()) name else "$parentPath/$name"
        return withContext(Dispatchers.IO) {
            if (kind == FsKind.DIRECTORY) {
                val dir = vault.dirFor(childPath)
                if (!dir.exists()) require(dir.mkdirs()) { "cannot create dir" }
            } else {
                requireUnlocked()
                val file = vault.fileFor(childPath)
                file.parentFile?.mkdirs()
                file.writeBytes(VaultCrypto.encrypt(ByteArray(0), vault.requireKey()))
            }
            nodeFor(
                path = childPath,
                parent = node,
                isDir = kind == FsKind.DIRECTORY,
                size = if (kind == FsKind.FILE) 0L else null,
                modified = null,
            )
        }
    }

    override suspend fun rename(node: FsNode, newName: String): FsNode =
        withContext(Dispatchers.IO) {
            val oldPath = FsUri.parse(node.uri).path
            require(oldPath.isNotEmpty()) { "cannot rename vault root" }
            val parentPath = oldPath.substringBeforeLast('/').ifEmpty { "" }
            val newPath = if (parentPath.isEmpty()) newName else "$parentPath/$newName"
            val isDir = node.metadata?.isDirectory ?: vault.dirFor(oldPath).isDirectory
            val oldFile: File = if (isDir) vault.dirFor(oldPath) else vault.fileFor(oldPath)
            val newFile: File = if (isDir) vault.dirFor(newPath) else vault.fileFor(newPath)
            require(oldFile.exists()) { "not found: ${node.uri}" }
            require(oldFile.renameTo(newFile)) { "rename failed" }
            nodeFor(
                path = newPath,
                parent = node.parent,
                isDir = isDir,
                size = if (isDir) null else oldFile.length(),
                modified = null,
            )
        }

    override suspend fun delete(node: FsNode, force: Boolean) {
        withContext(Dispatchers.IO) {
            val path = FsUri.parse(node.uri).path
            if (path.isEmpty()) throw FsException.Unsupported("cannot delete vault root")
            val isDir = node.metadata?.isDirectory ?: vault.dirFor(path).isDirectory
            val file: File = if (isDir) vault.dirFor(path) else vault.fileFor(path)
            if (file.isDirectory) {
                if (!force) {
                    val hasChildren = file.listFiles()?.isNotEmpty() == true
                    if (hasChildren) throw FsException.Unsupported("directory not empty")
                }
                require(file.deleteRecursively()) { "delete failed" }
            } else {
                require(file.delete()) { "delete failed" }
            }
        }
    }

    private fun requireUnlocked(): ByteArray =
        try {
            vault.requireKey()
        } catch (e: VaultLockedException) {
            throw FsException.Unauthorized("Vault is locked — unlock to write")
        }

    private fun nodeFor(
        path: String,
        parent: FsNode?,
        isDir: Boolean,
        size: Long?,
        modified: Long?,
    ): FsNode {
        val rawName = if (path.isEmpty()) "vault" else path.substringAfterLast('/')
        return FsNode(
            id = "vault:$path",
            uri = FsUri.encode(FsType.VAULT, path),
            name = if (isDir) rawName else rawName.removeSuffix(Vault.EXT),
            providerType = FsType.VAULT,
            parent = parent,
            metadata = FsMetadata(
                sizeBytes = size,
                modifiedAt = modified?.let { Instant.ofEpochMilli(it) },
                isDirectory = isDir,
                extra = mapOf("encrypted" to "true"),
            ),
        )
    }
}
