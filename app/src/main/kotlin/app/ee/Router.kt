package app.ee

import androidx.compose.runtime.mutableStateListOf

/**
 * M1 navigation: a plain backstack of screens (Compose-recomposable via
 * snapshot state). Formal navigation (deep links, arguments, saved state)
 * lands with the network features in M3.
 */
sealed interface Screen {
    data object Home : Screen
    data class Browser(val startUri: String) : Screen
    data object Settings : Screen
}

class Router {
    private val _stack = mutableStateListOf<Screen>(Screen.Home)

    val stack: List<Screen>
        get() = _stack

    fun current(): Screen = _stack.last()

    fun navigate(screen: Screen) {
        _stack.add(screen)
    }

    /** Pops the top; false when at the root (activity finishes). */
    fun back(): Boolean {
        return if (_stack.size > 1) {
            _stack.removeAt(_stack.size - 1)
            true
        } else {
            false
        }
    }
}
