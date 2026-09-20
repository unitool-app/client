package cx.m42.unitool

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import cx.m42.unitool.components.MenuEntry
import cx.m42.unitool.i18n.AppLocale
import cx.m42.unitool.i18n.strings
import cx.m42.unitool.platform.SystemBackHandler
import cx.m42.unitool.screens.CalculatorScreen
import cx.m42.unitool.screens.SettingsScreen

/**
 * Where the user is. A sealed hierarchy rather than a string route: a destination carries what the
 * screen behind it was opened with, which keeps each screen a pure function of where the user
 * navigated — and keeps "back" answerable without a navigation library.
 */
private sealed interface Destination {

    /** The app itself. There is one content screen; a second one is added as a sibling here. */
    data object Calculator : Destination

    /**
     * The page behind the drawer's footer cog. It carries where it was opened from, so back
     * returns there rather than to a fixed home — settings are reachable from anywhere, so there
     * is no one place "back" could otherwise mean.
     */
    data class Settings(val from: Destination) : Destination
}

/**
 * Which way the screen change should slide. Not derivable from the destinations themselves — the
 * drawer jumps sideways across the graph — so every navigation states it explicitly: [go] pushes
 * from the right, [goBack] returns from the left, GitHub-app style.
 */
private enum class NavDirection { Forward, Back }

/**
 * Root of the app: one source of truth for where the user is, and the drawer that is the same on
 * every screen.
 *
 * This is the starter's shape in miniature — a destination, a menu built once from it, and an
 * [AnimatedContent] that renders exactly one screen. Growing the app means adding a destination and
 * a `when` branch; nothing else here has to change.
 */
@Composable
fun App() {
    // Resolved once, at the first composition rather than in an effect, so the very first frame is
    // already in the right language — no paragraph rendered in one language and repainted in
    // another a moment later.
    remember { AppLocale.start() }
    var destination by remember { mutableStateOf<Destination>(Destination.Calculator) }
    var navDirection by remember { mutableStateOf(NavDirection.Forward) }

    fun go(dest: Destination) {
        navDirection = NavDirection.Forward
        destination = dest
    }

    fun goBack(dest: Destination) {
        navDirection = NavDirection.Back
        destination = dest
    }

    /**
     * The drawer's contents. Built here rather than inside each screen because the menu is about
     * the app, not the page: it is the same list everywhere, with the entry for wherever you
     * already are disabled.
     */
    val menu = listOf(
        MenuEntry(
            label = strings.calculator,
            onClick = { goBack(Destination.Calculator) },
            enabled = destination !is Destination.Calculator,
        ),
    )

    // Captured once so every screen opens the footer page the same way, remembering where it was
    // opened from.
    val openSettings = { go(Destination.Settings(destination)) }

    // Back means the same thing the top bar's arrow means, and for the same reason it is stated
    // per-navigation rather than derived: the destination knows where it was opened from.
    // Registered above the screens, so an open drawer — whose handler is composed deeper — gets
    // the event first and closes itself instead of navigating. Disabled on the root destination,
    // where back should leave the app, which is what the system does when nobody claims it.
    val here = destination
    SystemBackHandler(enabled = here is Destination.Settings) {
        if (here is Destination.Settings) goBack(here.from)
    }

    // GitHub-app style screen changes: forward pushes the new screen in from the right while the
    // old one recedes a third of the way left; back is the mirror image. The z-order flips with the
    // direction, or a return would look like another push in reverse.
    AnimatedContent(
        targetState = destination,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            val spec = tween<IntOffset>(durationMillis = 300, easing = FastOutSlowInEasing)
            if (navDirection == NavDirection.Forward) {
                (slideInHorizontally(spec) { it } togetherWith slideOutHorizontally(spec) { -it / 3 })
                    .apply { targetContentZIndex = 1f }
            } else {
                (slideInHorizontally(spec) { -it / 3 } togetherWith slideOutHorizontally(spec) { it })
                    .apply { targetContentZIndex = -1f }
            }
        },
    ) { where ->
        when (where) {
            is Destination.Calculator -> CalculatorScreen(
                menu = menu,
                onOpenSettings = openSettings,
            )

            // The footer cog on the page it already opens is left inert rather than pushing a
            // second copy of it — tapping «Настройки» from settings should do nothing, not deepen
            // the back stack by one indistinguishable screen.
            is Destination.Settings -> SettingsScreen(
                menu = menu,
                onBack = { goBack(where.from) },
                onOpenSettings = {},
            )
        }
    }
}
