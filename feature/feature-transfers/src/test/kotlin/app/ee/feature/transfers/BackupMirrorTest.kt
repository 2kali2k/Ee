package app.ee.feature.transfers

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupMirrorTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun copiesTreePreservingPaths() {
        val src = temp.newFolder("src")
        File(src, "a/b").mkdirs()
        File(src, "top.txt").writeText("hello")
        File(src, "a/b/deep.txt").writeText("deep")

        val dest = File(temp.root, "dest")
        val copied = BackupMirror.mirror(src, dest)

        assertEquals(2, copied)
        assertEquals("hello", File(dest, "top.txt").readText())
        assertEquals("deep", File(dest, "a/b/deep.txt").readText())
    }

    @Test
    fun secondRunCopiesNothing() {
        val src = temp.newFolder("src2")
        File(src, "x.txt").writeText("data")
        val dest = File(temp.root, "dest2")

        assertEquals(1, BackupMirror.mirror(src, dest))
        assertEquals(0, BackupMirror.mirror(src, dest))
    }

    @Test
    fun changedFileIsRecopied() {
        val src = temp.newFolder("src3")
        val f = File(src, "x.txt")
        f.writeText("v1")
        val dest = File(temp.root, "dest3")

        BackupMirror.mirror(src, dest)
        // force a distinct mtime so the cheap check notices
        Thread.sleep(20)
        f.writeText("v2 longer")
        val copied = BackupMirror.mirror(src, dest)

        assertEquals(1, copied)
        assertEquals("v2 longer", File(dest, "x.txt").readText())
    }

    @Test
    fun overlappingPathsRejected() {
        val src = temp.newFolder("src4")
        File(src, "inner").mkdirs()
        try {
            BackupMirror.mirror(src, File(src, "inner"))
            fail("expected rejection")
        } catch (expected: IllegalArgumentException) {
        }
        assertFalse(File(src, "inner/x.txt").exists())
    }

    @Test
    fun missingSourceThrows() {
        val missing = File(temp.root, "nope")
        try {
            BackupMirror.mirror(missing, File(temp.root, "d"))
            fail("expected rejection")
        } catch (expected: IllegalArgumentException) {
        }
        assertTrue(missing.exists() == false)
    }
}
