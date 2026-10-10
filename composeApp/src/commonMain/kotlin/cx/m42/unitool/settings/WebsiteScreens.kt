package cx.m42.unitool.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cx.m42.superizer.HostScreen
import cx.m42.superizer.HostSection
import cx.m42.superizer.Superizer
import cx.m42.superizer.theme.AppTheme
import cx.m42.superizer.ui.components.AppTextField
import cx.m42.superizer.ui.components.ButtonVariant
import cx.m42.superizer.ui.components.RowDivider
import cx.m42.unitool.i18n.WebStrings
import cx.m42.unitool.i18n.webStrings
import cx.m42.unitool.web.WebLogin
import cx.m42.unitool.web.WebResult
import cx.m42.unitool.web.WebSession
import cx.m42.unitool.web.WebSignIn
import kotlinx.coroutines.launch

/*
 * Settings → Website (.ai-notes/site/02): sign a browser in to the site with this app, and see and
 * sign out the browsers that are. The sign-in itself is two steps on purpose — read the code, then
 * see which browser asked and tap — so a code someone else shows the person reads as a stranger's.
 */

/** The block in Settings: the way in by camera or by link, and the list of browsers. */
internal class WebsiteSection(private val web: WebSignIn, private val tools: HostTools) : HostSection {
    override val id: String = "website"

    override fun title(langTag: String): String = webStrings(langTag).website

    @Composable
    override fun Content(superizer: Superizer, open: (HostScreen) -> Unit) {
        val langTag by superizer.settings.langTag.collectAsState()
        val strings = webStrings(langTag)
        val scope = rememberCoroutineScope()
        var problem by remember { mutableStateOf<String?>(null) }

        val host = web.server.substringAfter("://")
        RowText(strings.websiteHint(host), color = AppTheme.muted, small = true)
        tools.qr?.let { camera ->
            RowDivider()
            ActionRow(strings.scan, "web:scan", hint = strings.scanHint(host)) {
                scope.launch {
                    val text = camera.scan() ?: return@launch
                    val token = web.tokenOf(text)
                    problem = if (token == null) strings.notACode else null
                    if (token != null) open(WebSignInScreen(web, token))
                }
            }
        }
        RowDivider()
        ActionRow(strings.paste, "web:paste", hint = strings.pasteHint) { open(WebPasteScreen(web)) }
        RowDivider()
        ActionRow(strings.browsers, "web:browsers", hint = strings.browsersHint) { open(WebBrowsersScreen(web)) }
        problem?.let {
            RowDivider()
            RowText(it, Modifier.testTag("web:problem"), color = AppTheme.danger, small = true)
        }
    }
}

/** Where there is no camera — desktop, the web, an emulator: the link printed under the code. */
internal class WebPasteScreen(private val web: WebSignIn) : HostScreen {
    override val id: String = "website-paste"

    override fun title(langTag: String): String = webStrings(langTag).paste

    @Composable
    override fun Content(superizer: Superizer, back: () -> Unit, open: (HostScreen) -> Unit) {
        val langTag by superizer.settings.langTag.collectAsState()
        val strings = webStrings(langTag)
        var text by remember { mutableStateOf("") }
        var wrong by remember { mutableStateOf(false) }

        Page("web:paste-screen") {
            FieldLabel(strings.link)
            Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
                AppTextField(
                    value = text,
                    onValueChange = { text = it; wrong = false },
                    placeholder = "${web.server}/login/app/#wlt_…",
                    label = strings.link,
                    modifier = Modifier.testTag("web:link"),
                )
            }
            if (wrong) Note(strings.notACode, color = AppTheme.danger, tag = "web:not-a-code")
            PageButton(strings.next, "web:next", enabled = text.isNotBlank()) {
                val token = web.tokenOf(text)
                if (token == null) wrong = true else open(WebSignInScreen(web, token))
            }
        }
    }
}

/** What is asking, and the tap that signs it in. */
internal class WebSignInScreen(private val web: WebSignIn, private val token: String) : HostScreen {
    override val id: String = "website-sign-in"

    override fun title(langTag: String): String = webStrings(langTag).website

    @Composable
    override fun Content(superizer: Superizer, back: () -> Unit, open: (HostScreen) -> Unit) {
        val langTag by superizer.settings.langTag.collectAsState()
        val strings = webStrings(langTag)
        val scope = rememberCoroutineScope()
        var step by remember { mutableStateOf<Step>(Step.Checking) }

        LaunchedEffect(token) {
            step = when (val result = web.inspect(token)) {
                is WebResult.Ok -> Step.Asking(result.value)
                WebResult.Gone -> Step.Gone
                is WebResult.Failed -> Step.Failed
            }
        }

        Page("web:sign-in-screen") {
            when (val now = step) {
                Step.Checking -> Note(strings.checking, tag = "web:checking")
                is Step.Asking -> Asking(strings, now.login, busy = false, onSignIn = {
                    step = Step.Signing(now.login)
                    scope.launch {
                        step = when (web.claim(token)) {
                            is WebResult.Ok -> Step.Done
                            WebResult.Gone -> Step.Gone
                            is WebResult.Failed -> Step.Failed
                        }
                    }
                }, onCancel = back)
                is Step.Signing -> Asking(strings, now.login, busy = true, onSignIn = {}, onCancel = back)
                Step.Done -> {
                    Card {
                        RowText(strings.signedIn, Modifier.testTag("web:signed-in"))
                        RowDivider()
                        RowText(strings.signedInHint, color = AppTheme.muted, small = true)
                    }
                    PageButton(strings.done, "web:done", onClick = back)
                }
                Step.Gone -> {
                    Note(strings.gone, color = AppTheme.danger, tag = "web:gone")
                    PageButton(strings.done, "web:done", variant = ButtonVariant.Outline, onClick = back)
                }
                Step.Failed -> {
                    Note(strings.failed, color = AppTheme.danger, tag = "web:failed")
                    PageButton(strings.done, "web:done", variant = ButtonVariant.Outline, onClick = back)
                }
            }
        }
    }

    @Composable
    private fun Asking(strings: WebStrings, login: WebLogin, busy: Boolean, onSignIn: () -> Unit, onCancel: () -> Unit) {
        Card {
            RowText(strings.confirmTitle, Modifier.testTag("web:confirm"))
            RowDivider()
            RowText("${strings.browser}: ${describeBrowser(login.browser.userAgent) ?: strings.unknownBrowser}", Modifier.testTag("web:browser"), small = true)
            login.browser.ip?.let {
                RowDivider()
                RowText("${strings.address}: $it (${strings.addressNote})", small = true)
            }
            RowDivider()
            RowText(isoMinute(login.createdAt * 1000), color = AppTheme.muted, small = true)
        }
        Note(strings.confirmHint)
        PageButton(strings.signIn, "web:sign-in", enabled = !busy, onClick = onSignIn)
        PageButton(strings.cancel, "web:cancel", variant = ButtonVariant.Outline, onClick = onCancel)
    }

    private sealed interface Step {
        data object Checking : Step
        data class Asking(val login: WebLogin) : Step
        data class Signing(val login: WebLogin) : Step
        data object Done : Step
        data object Gone : Step
        data object Failed : Step
    }
}

/** The browsers signed in as this install, each with its own Sign out. */
internal class WebBrowsersScreen(private val web: WebSignIn) : HostScreen {
    override val id: String = "website-browsers"

    override fun title(langTag: String): String = webStrings(langTag).browsers

    @Composable
    override fun Content(superizer: Superizer, back: () -> Unit, open: (HostScreen) -> Unit) {
        val langTag by superizer.settings.langTag.collectAsState()
        val strings = webStrings(langTag)
        val scope = rememberCoroutineScope()
        var sessions by remember { mutableStateOf<List<WebSession>?>(null) }
        var failed by remember { mutableStateOf(false) }
        var reload by remember { mutableStateOf(0) }

        LaunchedEffect(reload) {
            when (val result = web.sessions()) {
                is WebResult.Ok -> { sessions = result.value; failed = false }
                else -> failed = true
            }
        }

        Page("web:browsers-screen") {
            if (failed) Note(strings.failed, color = AppTheme.danger, tag = "web:failed")
            val list = sessions ?: return@Page
            if (list.isEmpty()) {
                Note(strings.noBrowsers, tag = "web:no-browsers")
                return@Page
            }
            Card {
                list.forEachIndexed { i, session ->
                    if (i > 0) RowDivider()
                    RowText(describeBrowser(session.userAgent) ?: strings.unknownBrowser, Modifier.testTag("web:session:${session.id}"))
                    val seen = session.lastSeenAt?.let { ", " + strings.lastSeen(isoMinute(it * 1000)) }.orEmpty()
                    RowText(strings.signedInSince(isoMinute(session.createdAt * 1000)) + seen, color = AppTheme.muted, small = true)
                    SmallButtons(Triple(strings.signOut, "web:sign-out:${session.id}") {
                        scope.launch { web.signOut(session.id); reload++ }
                    })
                }
            }
            PageButton(strings.signOutAll, "web:sign-out-all", variant = ButtonVariant.Outline) {
                scope.launch { web.signOut(null); reload++ }
            }
        }
    }
}

/**
 * "Firefox on Linux" from a user-agent string — enough to recognise one's own computer, without a
 * library that knows every browser ever made. Null when there is no user agent.
 */
internal fun describeBrowser(userAgent: String?): String? {
    if (userAgent.isNullOrBlank()) return null
    val browser = when {
        "Edg/" in userAgent -> "Edge"
        "OPR/" in userAgent -> "Opera"
        "YaBrowser/" in userAgent -> "Yandex Browser"
        "Firefox/" in userAgent -> "Firefox"
        "Chrome/" in userAgent || "Chromium/" in userAgent -> "Chrome"
        "Safari/" in userAgent -> "Safari"
        else -> null
    }
    val system = when {
        "Android" in userAgent -> "Android"
        "iPhone" in userAgent || "iPad" in userAgent -> "iOS"
        "Windows" in userAgent -> "Windows"
        "Mac OS X" in userAgent || "Macintosh" in userAgent -> "macOS"
        "CrOS" in userAgent -> "ChromeOS"
        "Linux" in userAgent -> "Linux"
        else -> null
    }
    return when {
        browser != null && system != null -> "$browser · $system"
        browser != null -> browser
        system != null -> system
        else -> userAgent.take(60)
    }
}
