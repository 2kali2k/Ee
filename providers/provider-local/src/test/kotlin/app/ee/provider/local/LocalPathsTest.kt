package app.ee.provider.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPathsTest {

    @Test
    fun `uri round trips with absolute path`() {
        val path = "/storage/emulated/0/Download/report.pdf"
        val uri = LocalPaths.uriFor(path)
        assertEquals("ee://local/storage/emulated/0/Download/report.pdf", uri)
        assertEquals(path, LocalPaths.pathForUri(uri))
    }

    @Test
    fun `trash detection`() {
        val root = "/storage/emulated/0"
        assertTrue(LocalPaths.inTrash("$root/.ee_trash", root))
        assertTrue(LocalPaths.inTrash("$root/.ee_trash/1700000000000__notes.txt", root))
        assertFalse(LocalPaths.inTrash("$root/.ee_trash_sub/x", root))
        assertFalse(LocalPaths.inTrash("$root/Download", root))
    }

    @Test
    fun `trash entry name avoids collisions`() {
        val used = mutableSetOf("1__a.txt")
        assertEquals("2__a.txt", LocalPaths.trashEntryName(2, "a.txt", used))
        val used2 = setOf("3__a.txt", "3__a.txt~1")
        assertEquals("3__a.txt~2", LocalPaths.trashEntryName(3, "a.txt", used2))
    }

    @Test
    fun `extension extraction`() {
        assertEquals("pdf", LocalPaths.extension("report.PDF"))
        assertEquals("tar", LocalPaths.extension("archive.tar.gz"))
        assertEquals("", LocalPaths.extension("Makefile"))
        assertEquals("", LocalPaths.extension(".hidden"))
    }
}
