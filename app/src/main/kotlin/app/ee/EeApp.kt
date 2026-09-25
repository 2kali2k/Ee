package app.ee

import android.app.Application
import app.ee.core.fs.FsRegistry

/**
 * Composition root. M0 builds an empty [FsRegistry]; M1 registers
 * provider-local, M3 adds the network providers, and so on
 * (docs/02-specification.md §4.1 dependency rule: only `app` wires
 * providers into the registry).
 */
class EeApp : Application() {

    lateinit var vfs: FsRegistry
        private set

    override fun onCreate() {
        super.onCreate()
        vfs = FsRegistry()
        // M1: vfs.register(LocalFsProvider(...))
    }
}
