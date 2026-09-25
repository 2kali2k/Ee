package app.ee.core.fs

import app.ee.core.model.FsType

/**
 * Maps [FsType] to its [FsProvider]. The `app` module registers every enabled
 * provider once at startup; features only ever talk to the registry.
 *
 * Not thread-safe by design — populate it during startup, then treat it as
 * immutable (all lookups after that are safe).
 */
class FsRegistry {

    private val providers = linkedMapOf<FsType, FsProvider>()
    private var frozen = false

    fun register(provider: FsProvider) {
        check(!frozen) { "registry is frozen after first lookup" }
        val existing = providers[provider.type]
        check(existing == null) { "duplicate provider for ${provider.type}: $existing" }
        providers[provider.type] = provider
    }

    fun provider(type: FsType): FsProvider {
        frozen = true
        return providers[type]
            ?: throw FsException.Unsupported("no provider registered for $type")
    }

    /** All registered types, in registration order. */
    fun types(): Set<FsType> {
        frozen = true
        return providers.keys
    }

    fun supports(uri: String): Boolean {
        return runCatching { FsUri.parse(uri).type }
            .map { it in providers }
            .getOrDefault(false)
    }
}
