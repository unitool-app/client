package cx.m42.unitool

import androidx.compose.runtime.remember
import cx.m42.services.ssh.SshKeyringHost
import cx.m42.services.ssh.keyring.LocalStorageRecordStore
import cx.m42.superizer.host.SystemClock
import cx.m42.superizer.runtime.Platform
import cx.m42.unitool.security.KeyPrompts
import cx.m42.unitool.security.UnitoolSecurity
import cx.m42.unitool.settings.HostTools
import cx.m42.services.files.WebFilePicker
import cx.m42.services.files.WebFileSaver
import cx.m42.services.share.WebFileSharer
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(viewportContainerId = "composeTarget") {
        val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
        val security = remember {
            // In the browser: level 3, and the words say "in the browser" (05 §2.3).
            val prompts = KeyPrompts()
            UnitoolSecurity(SshKeyringHost.passphraseOnly(LocalStorageRecordStore(), prompts, SystemClock, Platform.Web, scope), prompts)
        }
        val superizer = remember { buildUnitool(scope, debug = true, security = security, tools = HostTools(files = WebFilePicker(), sharer = WebFileSharer(), saver = WebFileSaver())) }

        remember {
            // `?activate=` and `?link=` are the browser's version of a QR code and a notification
            // tap (06). Both go through the same consume-once route, so the shell has one thing to
            // read and the parser has one entry point.
            val search = window.location.search
            queryParam(search, "link")?.let { superizer.route.deliver(it) }
            queryParam(search, "activate")?.let { superizer.route.deliver(it) }
            if (queryParam(search, "test") != null) installTestBridge(superizer)
        }

        UnitoolApp(superizer, security)
    }
}

/** Percent-decoded, because a deep link in a query string arrives escaped. */
internal fun queryParam(search: String, name: String): String? = search
    .removePrefix("?")
    .split('&')
    .firstOrNull { it.startsWith("$name=") }
    ?.substringAfter('=')
    ?.let { runCatching { decodeUriComponent(it) }.getOrDefault(it) }

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
private fun decodeUriComponent(value: String): String = js("decodeURIComponent(value)")
