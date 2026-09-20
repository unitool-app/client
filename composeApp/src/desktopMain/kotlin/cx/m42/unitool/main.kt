package cx.m42.unitool

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        // Phone-shaped on purpose: the layout has no desktop breakpoint, and this is the aspect
        // ratio it is designed against.
        state = rememberWindowState(width = 420.dp, height = 820.dp),
        title = "Unitool",
    ) {
        App()
    }
}
