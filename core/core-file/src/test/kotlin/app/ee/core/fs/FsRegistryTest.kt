package app.ee.core.fs

import app.ee.core.model.FsCapability
import app.ee.core.model.FsKind
import app.ee.core.model.FsMetadata
import app.ee.core.model.FsNode
import app.ee.core.model.FsType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import okio.Buffer
import okio.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** In-memory provider used to exercise the registry and URI contract. */
private class FakeProvider(
    override val type: FsType = FsType.LOCAL,
    override val capabilities: Set<FsCapability> = setOf(
        FsCapability.READ,
        FsCapability.WRITE,
        FsCapability.CREATE,
        FsCapability.CREATE_DIR,
        FsCapability.RENAME,
        FsCapability.DELETE,
    ),
) : FsProvider {

    override suspend fun root(uri: String): FsNode {
        val parsed = FsUri.parse(uri)
        require(parsed.type == type) { "root() got foreign uri: $uri" }
        return FsNode(
            id = "root",
            uri = uri,
            name = type.name,
            providerType = type,
            metadata = FsMetadata(isDirectory = true),
        )
    }

    override fun list(node: FsNode): Flow<FsNode> = flowOf(
        FsNode(
            id = "child-1",
            uri = FsUri.encode(type, "a.txt"),
            name = "a.txt",
            providerType = type,
            parent = node,
            metadata = FsMetadata(sizeBytes = 3, isDirectory = false, mimeType = "text/plain"),
        ),
    )

    override suspend fun metadata(node: FsNode): FsMetadata =
        node.metadata ?: FsMetadata(isDirectory = node.isDirectory)

    override fun open(node: FsNode): Source = Buffer().write("hi")

    override suspend fun write(node: FsNode, bytes: Source): Flow<Progress> = flowOf(
        Progress(done = bytes.readByteArray().sizeToLong()),
    )

    override suspend fun create(node: FsNode, kind: FsKind, name: String): FsNode =
        node.copy(id = node.id + "+" + name)

    override suspend fun rename(node: FsNode, newName: String): FsNode =
        node.copy(name = newName)

    override suspend fun delete(node: FsNode, force: Boolean) = Unit
}

class FsUriTest {

    @Test
    fun `encode produces canonical uri`() {
        assertEquals("ee://local/emulated/0/Download/report.pdf", FsUri.encode(FsType.LOCAL, "emulated/0/Download/report.pdf"))
        assertEquals("ee://local/emulated/0/Download/report.pdf", FsUri.encode(FsType.LOCAL, "/emulated/0/Download/report.pdf"))
    }

    @Test
    fun `parse round trips encode`() {
        for (type in FsType.entries) {
            val path = "a/b/c$d"
            val uri = FsUri.encode(type, path)
            val parsed = FsUri.parse(uri)
            assertEquals(type, parsed.type)
            assertEquals(path, parsed.path)
            assertFalse(parsed.isRoot)
        }
    }

    @Test
    fun `parse rejects bad inputs`() {
        for (bad in listOf("", "ee://", "http://local/x", "ee://nope/x", "ee://LOCAL")) {
            if (bad == "ee://LOCAL") continue // valid (case-insensitive type)
            try {
                FsUri.parse(bad)
                fail("expected failure for: '$bad'")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun `type parsing is case-insensitive`() {
        assertEquals(FsType.SMB, FsUri.parse("ee://SMB/host/share").type)
    }
}

class FsRegistryTest {

    @Test
    fun `register then lookup`() {
        val registry = FsRegistry()
        val provider = FakeProvider()
        registry.register(provider)
        assertEquals(provider, registry.provider(FsType.LOCAL))
        assertEquals(setOf(FsType.LOCAL), registry.types())
    }

    @Test
    fun `duplicate provider rejected`() {
        val registry = FsRegistry()
        registry.register(FakeProvider())
        try {
            registry.register(FakeProvider())
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("duplicate"))
        }
    }

    @Test
    fun `missing provider gives typed error`() {
        val registry = FsRegistry()
        try {
            registry.provider(FsType.SMB)
            fail("expected FsException.Unsupported")
        } catch (e: FsException.Unsupported) {
            assertTrue(e.message!!.contains("SMB"))
        }
    }

    @Test
    fun `supports checks uri type`() {
        val registry = FsRegistry()
        registry.register(FakeProvider(type = FsType.SMB))
        assertTrue(registry.supports("ee://smb/host/share"))
        assertFalse(registry.supports("ee://ftp/host/"))
        assertFalse(registry.supports("garbage"))
    }
}
