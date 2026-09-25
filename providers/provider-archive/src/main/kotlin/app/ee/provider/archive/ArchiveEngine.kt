package app.ee.provider.archive

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.File
import java.io.InputStream

/** A single entry inside an archive (flat name, '/' separated). */
data class ArchiveEntry(
    val name: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val isDirectory: Boolean,
)

/**
 * Pure-JVM archive engine (M2 scope — docs/02-specification.md P1-1):
 *
 *  read   : zip, tar, tar.gz, gz (single file), bz2 (single file)
 *  create : zip (from any file/dir)
 *  extract: anything readable, to a directory
 *
 * 7z and RAR are deliberately NOT here in M2 — they need the native
 * sevenzipjbinding engine, which lands with M5 hardening (the reference
 * app bundled `lib7-Zip-JBinding.so`; we re-introduce it intentionally,
 * not by accident).
 */
object ArchiveEngine {

    private val READ_EXTS = setOf("zip", "tar", "gz", "bz2", "tgz")

    fun isSupported(name: String): Boolean {
        val lower = name.lowercase()
        if (lower.endsWith(".tar.gz")) return true
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in READ_EXTS
    }

    fun entries(archive: File): List<ArchiveEntry> = when {
        !archive.isFile -> throw IllegalArgumentException("not a file: $archive")
        archive.name.lowercase().endsWith(".tar.gz") -> tarEntries(
            GzipCompressorInputStream(archive.inputStream()),
        )
        archive.name.lowercase().endsWith(".tar") -> tarEntries(archive.inputStream())
        archive.name.lowercase().endsWith(".zip") -> zipEntries(archive)
        archive.name.lowercase().endsWith(".gz") -> listOf(
            singleEntry(archive, GzipCompressorInputStream(archive.inputStream())),
        )
        archive.name.lowercase().endsWith(".bz2") -> listOf(
            singleEntry(archive, BZip2CompressorInputStream(archive.inputStream())),
        )
        else -> throw UnsupportedOperationException("unsupported archive: ${archive.name}")
    }

    fun openEntry(archive: File, entryName: String): InputStream = when {
        archive.name.lowercase().endsWith(".tar.gz") -> tarOpen(
            { GzipCompressorInputStream(archive.inputStream()) }, entryName,
        )
        archive.name.lowercase().endsWith(".tar") -> tarOpen({ archive.inputStream() }, entryName)
        archive.name.lowercase().endsWith(".zip") -> {
            ZipFile(archive).let { zip ->
                val entry = zip.getEntry(entryName)
                    ?: zip.close().let { throw java.io.FileNotFoundException(entryName) }
                zip.getInputStream(entry)
            }
        }
        archive.name.lowercase().endsWith(".gz") -> checkSingle(archive, entryName) {
            GzipCompressorInputStream(archive.inputStream())
        }
        archive.name.lowercase().endsWith(".bz2") -> checkSingle(archive, entryName) {
            BZip2CompressorInputStream(archive.inputStream())
        }
        else -> throw UnsupportedOperationException("unsupported archive: ${archive.name}")
    }

    /** Create a zip archive from [source] (file or directory). Returns entry count. */
    fun createZip(target: File, source: File, overwrite: Boolean = false): Int {
        if (target.exists() && !overwrite) throw IllegalStateException("exists: $target")
        target.parentFile?.mkdirs()
        var count = 0
        org.apache.commons.compress.archivers.zip.ZipFile(target, "w").use { zip ->
            if (source.isFile) {
                zip.putArchiveEntry(ZipArchiveEntry(source, source.name))
                source.inputStream().use { it.copyTo(zip) }
                zip.closeArchiveEntry()
                count++
            } else {
                source.walkTopDown().filter { it.isFile }.forEach { file ->
                    val name = source.relativePath(file).replace(File.separatorChar, '/')
                    zip.putArchiveEntry(ZipArchiveEntry(file, name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeArchiveEntry()
                    count++
                }
            }
        }
        return count
    }

    /** Extract every entry into [destDir]. Returns entry count. Path-safeguarded. */
    fun extractAll(archive: File, destDir: File): Int {
        destDir.mkdirs()
        val base = destDir.canonicalFile
        return when {
            archive.name.lowercase().endsWith(".tar.gz") -> extractTar(
                GzipCompressorInputStream(archive.inputStream()), destDir, base,
            )
            archive.name.lowercase().endsWith(".tar") -> extractTar(archive.inputStream(), destDir, base)
            archive.name.lowercase().endsWith(".zip") -> extractZip(archive, destDir, base)
            archive.name.lowercase().endsWith(".gz") -> extractSingle(
                GzipCompressorInputStream(archive.inputStream()), destDir,
                stripSuffix(archive.name, ".gz"),
            )
            archive.name.lowercase().endsWith(".bz2") -> extractSingle(
                BZip2CompressorInputStream(archive.inputStream()), destDir,
                stripSuffix(archive.name, ".bz2"),
            )
            else -> throw UnsupportedOperationException("unsupported archive: ${archive.name}")
        }
    }

    private fun stripSuffix(name: String, suffix: String): String =
        if (name.endsWith(suffix)) name.removeSuffix(suffix) else name

    private fun singleEntry(archive: File, stream: InputStream): ArchiveEntry =
        ArchiveEntry(
            name = stripSuffix(archive.name, ".gz").let { if (it == archive.name) stripSuffix(archive.name, ".bz2") else it },
            sizeBytes = archive.length(),
            modifiedAt = archive.lastModified(),
            isDirectory = false,
        )

    private fun zipEntries(archive: File): List<ArchiveEntry> {
        ZipFile(archive).use { zip ->
            return zip.entries.asSequence().map { e ->
                ArchiveEntry(
                    name = e.name,
                    sizeBytes = if (e.isDirectory) 0 else e.size.coerceAtLeast(0),
                    modifiedAt = e.time,
                    isDirectory = e.isDirectory,
                )
            }.toList()
        }
    }

    private fun tarEntries(stream: InputStream): List<ArchiveEntry> {
        stream.use { TarArchiveInputStream(it).use { tar ->
            var entry: TarArchiveEntry? = tar.nextEntry
            val out = mutableListOf<ArchiveEntry>()
            while (entry != null) {
                out += ArchiveEntry(
                    name = entry.name,
                    sizeBytes = if (entry.isDirectory) 0 else entry.size,
                    modifiedAt = entry.time,
                    isDirectory = entry.isDirectory,
                )
                entry = tar.nextEntry
            }
            return out
        } }
    }

    private fun tarOpen(openStream: () -> InputStream, entryName: String): InputStream {
        openStream().use { raw ->
            TarArchiveInputStream(raw).use { tar ->
                var entry: TarArchiveEntry? = tar.nextEntry
                while (entry != null && entry.name != entryName) {
                    entry = tar.nextEntry
                }
                if (entry == null) throw java.io.FileNotFoundException(entryName)
                if (entry.isDirectory) throw IllegalStateException("directory entry: $entryName")
                // read exactly this entry (read() returns -1 at entry end);
                // M2 buffers the entry in memory — chunked streaming lands in M5
                val buf = okio.Buffer()
                val tmp = ByteArray(64 * 1024)
                while (true) {
                    val n = tar.read(tmp)
                    if (n < 0) break
                    buf.write(tmp, 0, n)
                }
                return buf
            }
        }
    }

    private fun checkSingle(archive: File, entryName: String, openStream: () -> InputStream): InputStream {
        val expected = stripSuffix(archive.name, ".gz").let {
            if (it == archive.name) stripSuffix(archive.name, ".bz2") else it
        }
        if (entryName != expected) throw java.io.FileNotFoundException(entryName)
        return openStream()
    }

    private fun extractTar(stream: InputStream, destDir: File, base: File): Int {
        stream.use { TarArchiveInputStream(it).use { tar ->
            var count = 0
            var entry: TarArchiveEntry? = tar.nextEntry
            while (entry != null) {
                val target = base.resolve(entry.name).canonicalFile
                if (!target.path.startsWith(base.path)) {
                    throw SecurityException("tar slip: ${entry.name}")
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { tar.copyTo(it) }
                    count++
                }
                entry = tar.nextEntry
            }
            return count
        } }
    }

    private fun extractZip(archive: File, destDir: File, base: File): Int {
        ZipFile(archive).use { zip ->
            var count = 0
            zip.entries.forEach { e ->
                val target = base.resolve(e.name).canonicalFile
                if (!target.path.startsWith(base.path)) {
                    throw SecurityException("zip slip: ${e.name}")
                }
                if (e.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    zip.getInputStream(e).use { ins -> target.outputStream().use { ins.copyTo(it) } }
                    count++
                }
            }
            return count
        }
    }

    private fun extractSingle(stream: InputStream, destDir: File, name: String): Int {
        val target = destDir.resolve(name)
        target.outputStream().use { stream.copyTo(it) }
        return 1
    }
}
