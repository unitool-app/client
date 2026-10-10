package cx.m42.unitool

import cx.m42.superizer.app.AppId
import cx.m42.superizer.host.net.KtorNetworkService
import cx.m42.unitool.web.WebResult
import cx.m42.unitool.web.WebSignIn
import kotlin.test.Test
import kotlin.test.assertIs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

/**
 * The app's half of a real sign-in, against a running unitool-server (.ai-notes/site/02): the real
 * HTTP client, the real server, a real browser waiting on /login/. Skipped unless a link is given:
 *
 *   UNITOOL_E2E_LINK='http://localhost:17290/login/app/#wlt_…' \
 *     ./gradlew :composeApp:desktopTest --tests '*WebsiteEndToEndTest*'
 *
 * The phone it plays has the bench app unlocked and SSH keys opened, so the browser should then be
 * able to read both sections.
 */
class WebsiteEndToEndTest : UnitoolTest() {

    @Test
    fun theAppSignsInTheWaitingBrowser() {
        val link = System.getenv("UNITOOL_E2E_LINK") ?: return
        val server = link.substringBefore("/login/app/")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val network = KtorNetworkService(KtorNetworkService.defaultClient())
        val superizer = buildUnitool(scope, debug = true, network = network, server = server)
        val web = WebSignIn(network, server, cx.m42.superizer.runtime.Platform.Desktop, "e2e").also { it.attach(superizer, scope) }

        runBlocking {
            superizer.diagnostics.unlock(AppId("test-app"))
            web.opened.add("ssh-keys")
            val token = web.tokenOf(link) ?: error("not a sign-in link of $server: $link")
            val seen = assertIs<WebResult.Ok<cx.m42.unitool.web.WebLogin>>(web.inspect(token))
            println("E2E browser: ${seen.value.browser}")
            assertIs<WebResult.Ok<*>>(web.claim(token))
            assertIs<WebResult.Ok<List<cx.m42.unitool.web.WebSession>>>(web.sessions())
        }
    }
}
