package cx.m42.unitool

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Unitool as a desktop window.
 *
 * `--activate=` and `--link=` are the desktop stand-ins for a QR code and a notification tap (06):
 * a machine has neither a camera nor intents, and the routing path still has to be drivable by hand.
 */
fun main(args: Array<String>) = application {
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
    val superizer = remember { buildUnitool(scope, debug = true) }

    remember {
        args.firstOrNull { it.startsWith("--link=") || it.startsWith("--activate=") }
            ?.substringAfter('=')
            ?.let { superizer.route.deliver(it) }
    }

    Window(
        onCloseRequest = ::exitApplication,
        // Phone-shaped on purpose: the layout has no desktop breakpoint, and this is the aspect
        // ratio it is designed against. The shell caps its content at 480 dp anyway (D36).
        state = rememberWindowState(width = 420.dp, height = 820.dp),
        title = "Unitool",
    ) {
        UnitoolApp(superizer)
    }
}
