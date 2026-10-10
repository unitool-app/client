package cx.m42.unitool

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import cx.m42.apps.testapp.SecretTestApp
import cx.m42.superizer.Superizer
import cx.m42.superizer.activation.ActivationResult
import cx.m42.superizer.app.AppId
import cx.m42.superizer.runtime.NetworkRequest
import cx.m42.superizer.runtime.NetworkResponse
import cx.m42.superizer.runtime.Platform
import cx.m42.superizer.testing.FakeNetwork
import cx.m42.unitool.settings.describeBrowser
import cx.m42.unitool.web.OpenedSections
import cx.m42.unitool.web.WebResult
import cx.m42.unitool.web.WebSignIn
import cx.m42.unitool.web.WebStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Settings → Website (.ai-notes/site/02) against a server faked at the HTTP level, so the requests
 * checked are the ones the real unitool-server answers (its `lib/web.test.ts` holds the other side).
 */
@OptIn(ExperimentalTestApi::class)
class WebsiteSignInTest : UnitoolTest() {

    /** The server's web API, in memory: one device, one waiting browser, the requests it was sent. */
    private class FakeServer(val network: FakeNetwork = ratesNetwork()) {
        val registrations = mutableListOf<String>()
        val claims = mutableListOf<List<String>>()
        val sectionUpdates = mutableListOf<List<String>>()
        var loginGone = false
        val sessions = mutableListOf("ws_AAAAAAAAAAAA", "ws_BBBBBBBBBBBB")

        private fun authorised(request: NetworkRequest) = request.headers["Authorization"] == "Bearer $DEVICE.${secret()}"
        var secretHash: String? = null
        private var plainSecret: String? = null
        private fun secret() = plainSecret

        private fun sectionsOf(body: String?) =
            Json.parseToJsonElement(body!!).jsonObject["sections"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

        init {
            network.respond("$API/devices") { request ->
                registrations += request.body!!
                NetworkResponse(201, """{"deviceId":"$DEVICE","push":"none","pushEnabled":false}""")
            }
            network.respond("$API/devices/me") { request ->
                sectionUpdates += sectionsOf(request.body)
                NetworkResponse(200, """{"deviceId":"$DEVICE","push":"none","pushEnabled":false}""")
            }
            network.respond("$API/web/logins/inspect") { request ->
                learnSecret(request)
                if (loginGone) NetworkResponse(410, """{"error":{"code":"gone","message":"expired"}}""") else NetworkResponse(200, LOGIN)
            }
            network.respond("$API/web/logins/claim") { request ->
                if (!authorised(request)) return@respond NetworkResponse(401, "{}")
                claims += sectionsOf(request.body)
                NetworkResponse(200, LOGIN)
            }
            network.respond("$API/web/sessions") { request ->
                if (!authorised(request)) return@respond NetworkResponse(401, "{}")
                when (request.method) {
                    "GET" -> NetworkResponse(200, sessions.joinToString(",", """{"sessions":[""", "]}") {
                        """{"id":"$it","userAgent":"$FIREFOX","ip":"203.0.113.7","createdAt":1791600000,"lastSeenAt":1791603600}"""
                    })
                    "DELETE" -> {
                        val id = request.url.substringAfterLast("/web/sessions").removePrefix("/")
                        if (id.isEmpty()) sessions.clear() else sessions.remove(id)
                        NetworkResponse(204, "")
                    }
                    else -> NetworkResponse(404, "{}")
                }
            }
        }

        /** The phone's key is `d_….ds_…`; the fake learns the secret from the first authorised call. */
        private fun learnSecret(request: NetworkRequest) {
            val key = request.headers["Authorization"]?.removePrefix("Bearer ") ?: return
            plainSecret = key.substringAfter('.')
        }
    }

    private fun ComposeUiTest.tap(tag: String) {
        runCatching { onNodeWithTag(tag).performScrollTo() }
        onNodeWithTag(tag).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.openWebsite() {
        onNodeWithContentDescription("Open menu").performClick()
        waitForIdle()
        onNodeWithContentDescription("Settings").performClick()
        waitForIdle()
    }

    private fun superizerWith(server: FakeServer): Superizer =
        buildUnitool(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), debug = true, network = server.network)
            .also { it.settings.chooseLanguage("en") }

    @Test
    fun aBrowserIsSignedInByPastingItsLinkAndConfirming() = runComposeUiTest {
        val server = FakeServer()
        val superizer = superizerWith(server)
        // A hidden app unlocked by its code: its docs are what the browser is for.
        runBlocking { superizer.diagnostics.unlock(AppId("test-app")) }
        setContent { UnitoolApp(superizer) }
        waitForIdle()

        openWebsite()
        tap("web:paste")
        onNodeWithTag("web:link").performTextInput("https://unitool.m42.cx/login/app/#$TOKEN")
        waitForIdle()
        tap("web:next")

        // What is asking, before anything is signed in.
        onNodeWithTag("web:confirm").assertIsDisplayed()
        onNodeWithTag("web:browser").assertTextContains("Firefox · Linux", substring = true)
        assertTrue(server.claims.isEmpty())

        tap("web:sign-in")
        onNodeWithTag("web:signed-in").assertIsDisplayed()

        // Registered once, on this first sign-in, with the secret only as its hash.
        val registration = Json.parseToJsonElement(server.registrations.single()).jsonObject
        assertEquals(64, registration["secretHash"]!!.jsonPrimitive.content.length)
        assertEquals("desktop", registration["platform"]!!.jsonPrimitive.content)
        assertEquals(listOf("test-app"), server.claims.single())
    }

    @Test
    fun aLinkOfAnotherSiteIsRefused() = runComposeUiTest {
        val server = FakeServer()
        setContent { UnitoolApp(superizerWith(server)) }
        waitForIdle()

        openWebsite()
        tap("web:paste")
        onNodeWithTag("web:link").performTextInput("https://evil.example/login/app/#$TOKEN")
        waitForIdle()
        tap("web:next")
        onNodeWithTag("web:not-a-code").assertIsDisplayed()
        assertTrue(server.network.calls.none { it.url.contains("/web/") })
    }

    @Test
    fun anExpiredCodeSaysSo() = runComposeUiTest {
        val server = FakeServer().apply { loginGone = true }
        setContent { UnitoolApp(superizerWith(server)) }
        waitForIdle()

        openWebsite()
        tap("web:paste")
        onNodeWithTag("web:link").performTextInput("https://unitool.m42.cx/login/app/#$TOKEN")
        waitForIdle()
        tap("web:next")
        onNodeWithTag("web:gone").assertIsDisplayed()
    }

    @Test
    fun theBrowsersAreListedAndSignedOut() = runComposeUiTest {
        val server = FakeServer()
        val superizer = superizerWith(server)
        setContent { UnitoolApp(superizer) }
        waitForIdle()

        // Before the first sign-in there is nothing to ask the server about.
        openWebsite()
        tap("web:browsers")
        onNodeWithTag("web:no-browsers").assertIsDisplayed()
        assertTrue(server.network.calls.none { it.url.contains("/web/sessions") })
    }

    @Test
    fun sectionsFollowWhatIsOpenedAndReachTheServerOnceRegistered() {
        val server = FakeServer()
        val superizer = superizerWith(server)
        val web = webOf(superizer, server)
        runBlocking {
            assertIs<WebResult.Ok<*>>(web.inspect(TOKEN))
            assertIs<WebResult.Ok<*>>(web.claim(TOKEN))
            assertEquals(emptyList(), server.claims.single())

            // A secret app opened by its code — for one visit, but its docs stay.
            val secret = assertIs<ActivationResult.Success>(superizer.activation.fromPromo(SecretTestApp.PROMO_CODE))
            superizer.activation.apply(secret.activation, "test")
            // SSH keys, a host screen opened by its code.
            web.opened.add("ssh-keys")
        }
        assertEquals(setOf("secret-test", "ssh-keys"), web.sections.value)
        assertEquals(listOf("secret-test", "ssh-keys"), server.sectionUpdates.last())

        runBlocking {
            val listed = assertIs<WebResult.Ok<List<cx.m42.unitool.web.WebSession>>>(web.sessions())
            assertEquals(2, listed.value.size)
            web.signOut("ws_AAAAAAAAAAAA")
            assertEquals(listOf("ws_BBBBBBBBBBBB"), server.sessions)
            web.signOut(null)
            assertTrue(server.sessions.isEmpty())
        }
    }

    @Test
    fun onlyThisServersSignInLinksAreAccepted() {
        val web = WebSignIn(FakeNetwork(), "https://unitool.m42.cx/", Platform.Android, "1.0", MapStore())
        assertEquals(TOKEN, web.tokenOf("https://unitool.m42.cx/login/app/#$TOKEN"))
        assertEquals(TOKEN, web.tokenOf("  https://unitool.m42.cx/login/app/#$TOKEN\n"))
        assertNull(web.tokenOf("https://unitool.m42.cx.evil.example/login/app/#$TOKEN"))
        assertNull(web.tokenOf("https://unitool.m42.cx/login/app/#wlt_short"))
        assertNull(web.tokenOf("otpauth://totp/x?secret=AAAA"))
    }

    @Test
    fun aBrowserReadsAsItsNameAndSystem() {
        assertEquals("Firefox · Linux", describeBrowser(FIREFOX))
        assertEquals("Chrome · Windows", describeBrowser("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0 Safari/537.36"))
        assertEquals("Safari · iOS", describeBrowser("Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"))
        assertNull(describeBrowser(null))
    }

    /** The host's own instance, reached the way the screens reach it: through Settings' block. */
    private fun webOf(superizer: Superizer, server: FakeServer): WebSignIn =
        WebSignIn(server.network, "https://unitool.m42.cx", Platform.Desktop, "1.0", MapStore(), OpenedSections(MapStore()))
            .also { it.attach(superizer, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)) }

    private class MapStore : WebStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    private companion object {
        const val API = "https://unitool.m42.cx/api/unitool/v1"
        const val DEVICE = "d_AAAAAAAAAAAA"
        const val TOKEN = "wlt_ABCDEFGHJKLMNPQRSTUVWXYZ"
        const val FIREFOX = "Mozilla/5.0 (X11; Linux x86_64; rv:142.0) Gecko/20100101 Firefox/142.0"
        val LOGIN = """{"loginId":"wl_AAAAAAAAAAAAAAAA","browser":{"userAgent":"$FIREFOX","ip":"203.0.113.7"},"createdAt":1791600000,"expiresAt":1791600300}"""
    }
}
