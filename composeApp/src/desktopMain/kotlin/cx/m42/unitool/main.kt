package cx.m42.unitool

import androidx.compose.runtime.remember
import cx.m42.services.ssh.SshKeyringHost
import cx.m42.services.ssh.keyring.FileRecordStore
import cx.m42.superizer.host.SystemClock
import cx.m42.superizer.runtime.Platform
import cx.m42.unitool.security.KeyPrompts
import cx.m42.unitool.security.UnitoolSecurity
import cx.m42.unitool.settings.HostTools
import cx.m42.services.files.DesktopFilePicker
import cx.m42.services.share.DesktopFileSharer
import java.io.File
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
    val security = remember { desktopSecurity(scope) }
    val superizer = remember { buildUnitool(scope, debug = true, security = security, tools = HostTools(files = DesktopFilePicker(), sharer = DesktopFileSharer())) }

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
        UnitoolApp(superizer, security)
    }
}

/**
 * No chip on a desktop: every key at level 3, under its passphrase, and the host's secrets sealed to
 * the first such key (05 §2.1, §3). The records sit next to the host's own settings.
 */
private fun desktopSecurity(scope: CoroutineScope): UnitoolSecurity {
    val prompts = KeyPrompts()
    val records = FileRecordStore(File(System.getProperty("user.home"), ".superizer/cx.m42.services.ssh.json"))
    return UnitoolSecurity(SshKeyringHost.passphraseOnly(records, prompts, SystemClock, Platform.Desktop, scope), prompts)
}
