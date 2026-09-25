package app.ee

/**
 * App-level navigation (docs/02-specification.md §4.6). M2 adds the media
 * players, image viewer and the transfer station to the backstack.
 */
sealed interface Screen {
    data object Home : Screen
    data class Browser(val startUri: String) : Screen
    data object Media : Screen
    data object Transfers : Screen
    data class Image(val uri: String, val title: String) : Screen
    data class Video(val uri: String, val title: String) : Screen
    data class Audio(val uri: String, val title: String) : Screen
    data object Settings : Screen
}

/** Minimal backstack-based router. Replaced by navigation-compose only if
 *  a later milestone really needs arguments/actions/deep-links. */
class Router(initial: Screen = Screen.Home) {
    private val _stack = androidx.compose.runtime.mutableStateListOf(initial)
    val stack: List<Screen> get() = _stack

    val current: Screen
        get() = _stack.lastOrNull() ?: Screen.Home

    fun push(screen: Screen) {
        _stack.add(screen)
    }

    /** Pops one entry; false when we're already at the root. */
    fun pop(): Boolean {
        if (_stack.size <= 1) return false
        _stack.removeAt(_stack.size - 1)
        return true
    }
}
