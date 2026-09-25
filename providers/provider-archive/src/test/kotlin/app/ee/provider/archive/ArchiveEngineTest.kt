package app.ee.provider.archive

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ArchiveEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(name: String, content: String): File {
        val f = tmp.newFile(name)
        f.writeText(content)
        return f
    }

    @Test
    fun `zip create, list, read, extract`() {
        val a = file("a.txt", "hello")
        val dir = tmp.newFolder("sub")
        File(dir, "b.txt").writeText("world")

        val zipFile = tmp.newFile("out.zip")
        val count = ArchiveEngine.createZip(zipFile, dir)
        assertEquals(1, count)

        // zip a single file
        val zip2 = tmp.newFile("single.zip")
        ArchiveEngine.createZip(zip2, a)

        val entries = ArchiveEngine.entries(zip2)
        assertEquals(1, entries.size)
        val entry = entries.first()
        assertFalse(entry.isDirectory)
        assertEquals("a.txt", entry.name)
        assertEquals(5L, entry.sizeBytes)

        val content = ArchiveEngine.openEntry(zip2, "a.txt").readBytes().decodeToString()
        assertEquals("hello", content)

        val dest = tmp.newFolder("extracted")
        ArchiveEngine.extractAll(zip2, dest)
        assertEquals("hello", File(dest, "a.txt").readText())
    }

    @Test
    fun `tar gz round trip`() {
        val content = "tar-content-123"
        val source = file("payload.txt", content)
        val tgz = File(tmp.root, "payload.tar.gz")
        java.util.zip.GZIPOutputStream(tgz.outputStream()).use { gz ->
            TarArchiveOutputStream(gz).use { tar ->
                tar.putArchiveEntry(TarArchiveEntry(source, "payload.txt"))
                source.inputStream().use { it.copyTo(tar) }
                tar.closeArchiveEntry()
            }
        }

        val entries = ArchiveEngine.entries(tgz)
        assertEquals(listOf("payload.txt"), entries.map { it.name })
        assertEquals(content.length.toLong(), entries.first().sizeBytes)

        val read = ArchiveEngine.openEntry(tgz, "payload.txt").readBytes().decodeToString()
        assertEquals(content, read)

        val dest = tmp.newFolder("tgz-out")
        val n = ArchiveEngine.extractAll(tgz, dest)
        assertEquals(1, n)
        assertEquals(content, File(dest, "payload.txt").readText())
    }

    @Test
    fun `single gz round trip`() {
        val source = file("data.txt", "gz-payload")
        val gz = File(tmp.root, "data.gz")
        java.util.zip.GZIPOutputStream(gz.outputStream()).use { gzOut ->
            source.inputStream().use { gzOut.write(it.readBytes()) }
        }

        val entries = ArchiveEngine.entries(gz)
        assertEquals(listOf("data.txt"), entries.map { it.name })
        val read = ArchiveEngine.openEntry(gz, "data.txt").readBytes().decodeToString()
        assertEquals("gz-payload", read)

        val dest = tmp.newFolder("gz-out")
        ArchiveEngine.extractAll(gz, dest)
        assertEquals("gz-payload", File(dest, "data.txt").readText())
    }

    @Test
    fun `zip slip is blocked`() {
        val evil = tmp.newFile("evil.zip")
        ZipFile(evil, "w").use { zip ->
            zip.putArchiveEntry(ZipArchiveEntry("../pwned.txt"))
            zip.write("x".toByteArray())
            zip.closeArchiveEntry()
        }
        val dest = tmp.newFolder("safe")
        try {
            ArchiveEngine.extractAll(evil, dest)
            fail("expected SecurityException")
        } catch (e: SecurityException) {
            assertTrue(e.message!!.contains("slip"))
        }
        assertFalse(File(dest.parentFile, "pwned.txt").exists())
    }

    @Test
    fun `unsupported extension is rejected`() {
        val rar = file("x.rar", "fake")
        try {
            ArchiveEngine.entries(rar)
            fail("expected UnsupportedOperationException")
        } catch (e: UnsupportedOperationException) {
            // expected
        }
        assertFalse(ArchiveEngine.isSupported("x.rar"))
        assertTrue(ArchiveEngine.isSupported("x.tar.gz"))
        assertTrue(ArchiveEngine.isSupported("x.ZIP"))
    }
}
