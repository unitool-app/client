package cx.m42.unitool

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import cx.m42.superizer.ui.i18n.LocalDiagnosticsStrings
import cx.m42.unitool.i18n.diagnosticsWords
import cx.m42.unitool.security.KeyPromptSheet
import cx.m42.apps.calculator.CalculatorApp
import cx.m42.apps.calculator.CalculatorConfig
import cx.m42.apps.converter.ConverterApp
import cx.m42.apps.counters.CountersApp
import cx.m42.apps.passwords.PasswordsApp
import cx.m42.apps.dates.DatesApp
import cx.m42.apps.testapp.SecretTestApp
import cx.m42.apps.testapp.TestApp
import cx.m42.apps.twofa.TwoFaApp
import cx.m42.superizer.LanguageOption
import cx.m42.superizer.Superizer
import cx.m42.superizer.SuperizerContract
import cx.m42.superizer.activation.Activation
import cx.m42.superizer.activation.ActivationResult
import cx.m42.superizer.app.AppConfig
import cx.m42.superizer.app.AppId
import cx.m42.superizer.app.SuperizerApp
import cx.m42.superizer.host.SuperizerBuilder
import cx.m42.superizer.host.activation.HashedPromoCodes
import cx.m42.superizer.host.build
import cx.m42.superizer.host.platform.currentPlatform
import cx.m42.superizer.runtime.HostInfo
import cx.m42.superizer.ui.shell.SuperizerShell
import cx.m42.unitool.premium.PremiumOffer
import cx.m42.unitool.security.UnitoolSecurity
import cx.m42.unitool.settings.HostTools
import cx.m42.unitool.settings.SshKeysScreen
import cx.m42.unitool.settings.WebsiteSection
import cx.m42.unitool.web.WebSignIn
import cx.m42.services.link.LocalLink
import cx.m42.services.qr.QrScanner
import cx.m42.services.share.FileSharer
import cx.m42.superizer.runtime.FilePicker
import cx.m42.superizer.runtime.NetworkService
import cx.m42.superizer.host.net.KtorNetworkService
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Unitool, in one function.
 *
 * This is the whole of what makes Unitool a product rather than a framework: a name, four apps and
 * a table of promo codes. Everything else — the drawer, All Apps, Settings, activation, the app
 * container, the Service Menu — comes from the library and is identical in any other host.
 *
 * Registration is explicit (D1), which is also why this reads as an inventory: what is in the build
 * is what is listed here, with no classpath scanning to go looking for and no `@Keep` for R8.
 *
 * **The Definition of Done lives in this file** (§24). Adding a fourth app means one `register(…)`
 * line, one dependency in `composeApp/build.gradle.kts` and one line in `settings.gradle.kts` —
 * and nothing in `core`, `ui` or `host`. `ExtensibilityTest` is that claim, as a test.
 */
public fun buildUnitool(
    scope: CoroutineScope,
    debug: Boolean,
    security: UnitoolSecurity? = null,
    tools: HostTools = HostTools(),
    network: NetworkService? = null,
    /** The Unitool server — device registry, push, and the website this app signs in to. */
    server: String = PUSH_SERVER,
    extra: SuperizerBuilder.() -> Unit = {},
): Superizer {
    // Made here rather than left to the library's default, so the host's own screens — the
    // backup's GitHub keys — use the very service the apps do. A test hands in its fake.
    val net = network ?: KtorNetworkService(KtorNetworkService.defaultClient())
    // Signing in to the website with the app (.ai-notes/site/02): Settings → Website.
    val web = WebSignIn(net, server, currentPlatform(), BuildInfo.VERSION)
    return buildHost(scope, debug, security, tools.with(net, web.opened), net, server, web, extra)
}

private fun buildHost(
    scope: CoroutineScope,
    debug: Boolean,
    security: UnitoolSecurity?,
    tools: HostTools,
    network: NetworkService,
    server: String,
    web: WebSignIn,
    extra: SuperizerBuilder.() -> Unit,
): Superizer = Superizer.build(scope) {
    network(network)
    host(
        HostInfo(
            name = "Unitool",
            version = BuildInfo.VERSION,
            build = BuildInfo.COMMIT + if (BuildInfo.DIRTY) "*" else "",
            platform = currentPlatform(),
            contractVersion = SuperizerContract.VERSION,
            // D34: this is what decides whether seven taps on the build stamp do anything. In a
            // release they do not, and the Service Menu's only door is the promo code below.
            debug = debug,
        ),
    )

    // The scheme in `unitool://app/<id>/<path>` and `unitool://activate?…`, and what the Android
    // manifest's intent filters have to agree with.
    scheme(SCHEME)

    // Where this device registers for push (push-opt-in D422): only once the person uses an app
    // that takes pushes, never at first start. Desktop and the web have no token and send nothing.
    pushServer(server)

    languages(
        LanguageOption("ru", "Русский"),
        LanguageOption("en", "English"),
        // English rather than Russian for a device that has neither: the app is listed worldwide,
        // and English is the language such a reader is likeliest to have some of.
        fallback = "en",
    )

    register(CalculatorApp())
    register(ConverterApp())
    // Offline TOTP codes. In the catalog, not on a fresh Home (D48): not everyone has 2FA to manage.
    register(TwoFaApp())
    // Days between two dates. Public and offline, in the catalog like the converter.
    register(DatesApp())
    // Named counters with plus and minus — levels in a board game. Public and offline, in the catalog.
    register(CountersApp())
    // Logins and passwords, kept like the 2FA keys: sealed, behind the device lock. In the catalog.
    register(PasswordsApp())
    // Hidden (D8). It is a diagnostic instrument, and it stays invisible until someone types the
    // code below — which is also the only way the Service Menu opens in a release.
    register(TestApp())
    // Secret (done/idea/09): never listed anywhere, opened by its code for one visit at a time. A test
    // piece, to see what the type looks like; its code goes to the stores' reviewers with the rest.
    register(SecretTestApp())

    // What a fresh install has on Home (D48): the calculator, because that is what Unitool was.
    // The converter is one tap away in the catalog; the bench app gets there by its promo code.
    home("calculator")

    // Codes as hashes (done/idea/09, D256): the APK carries none that `strings` would print. A key is
    // the output of `scripts/promo-hash.sh <code>`; the codes themselves, and what each opens, are
    // in `.secrets/promo-codes.md` — the list that goes to the stores' reviewers (RELEASING.md).
    promoCodes(
        HashedPromoCodes(
            salt = PROMO_SALT,
            codes = mapOf(
                // The calculator in scientific mode: the visible effect of a config (07).
                "527ca0002cf993fe333a82300743ab1d0afce7b25d0d03acfcc2628b5dfb0972" to ActivationResult.Success(
                    Activation(
                        AppId("calculator"),
                        AppConfig(buildJsonObject { put("mode", JsonPrimitive(CalculatorConfig.SCIENTIFIC)) }),
                    ),
                ),
                // The bench app, unlocked onto Home (D8, D48).
                "b63a66698da50056b59749499b4d1d2ba6b7f99c8f17e50f8a8ab23b0196080c" to ActivationResult.Success(
                    Activation(AppId("test-app"), TestApp.promoConfig()),
                ),
                // The secret test app, for one visit (done/idea/09).
                "601ada16eec56caa326c645b27c9d66cf9fc81ccde4aae82875625796e615037" to ActivationResult.Success(
                    Activation(AppId("secret-test"), SecretTestApp.promoConfig()),
                ),
                // The Service Menu — the one door into it in a release build (D34). A host command,
                // not a reserved app id (D20), and a row like any other so its code is hashed too.
                "5fbc3d115453b3a3426b4c6dc14737226136dec4c71526dc7a9c4aab3c3f8bdc" to
                    ActivationResult.HostCommand(ActivationResult.Command.OpenServiceMenu),
                // SSH keys, which Settings does not list (done/idea/09). Bracketed, so exact: only the
                // code as written opens them, and a near miss opens nothing. Both brackets — the
                // owner wrote it with each. A build without the security layer has no such screen,
                // and there the code is simply unknown.
                "f458affb8d674cd1effa3e6429cfe36901c0890602fd4153a95f939f6741f2c1" to ActivationResult.OpenHostScreen(SshKeysScreen.ID),
                "b9666358dbb806fbfafde72c98e46c2b92bca3fa811e6a23280c123d8cce657e" to ActivationResult.OpenHostScreen(SshKeysScreen.ID),
            ),
        ),
    )

    // Premium, above the code on Activate: a mock-up that sells nothing yet (PremiumOffer). The
    // drawer's door to it is a star called Premium, not a QR code.
    activateBanner(PremiumOffer)
    activateEntry(PremiumOffer)

    // The platform's optional services (D19), for the apps through the registry: the 2FA app draws
    // its scan button from whether the camera is there, its export from whether there is a share
    // sheet. The host's own screens get the same objects directly.
    tools.qr?.let { camera ->
        service(QrScanner.Key, camera)
        // The same camera behind the Activate screen's scan button: point it at an activation QR.
        qrScanner { camera.scan() }
    }
    tools.sharer?.let { service(FileSharer.Key, it) }
    // The phone's side of a computer's browser asking for a password (passwords-browser D538).
    tools.link?.let { service(LocalLink.Key, it) }
    tools.files?.let { service(FilePicker.Key, it) }

    // Keys, the vault behind runtime.secrets, and the diagnostics of both (ssh-new 07 §2.2): what
    // the platform brought, handed to the host. A test builds without it and gets a host with none.
    security?.install(this, BuildInfo.VERSION, tools)

    // Last of the host's blocks, so it sits at the bottom of the host's part of Settings: sign in on
    // the website, and the browsers that are.
    hostSection(WebsiteSection(web, tools))

    extra()
}.also { superizer ->
    security?.attach(superizer, scope)
    web.attach(superizer, scope)
}

/**
 * The root composable every entry point calls: the shell, the host's words for how things are kept
 * (06 §6), and — over everything — the keyring's sheet (02 §7) when there is a keyring.
 */
@Composable
public fun UnitoolApp(superizer: Superizer, security: UnitoolSecurity? = null) {
    val langTag by superizer.settings.langTag.collectAsState()
    CompositionLocalProvider(LocalDiagnosticsStrings provides diagnosticsWords(langTag)) {
        Box(modifier = Modifier.fillMaxSize()) {
            SuperizerShell(superizer)
            security?.let { KeyPromptSheet(it.prompts, langTag) }
        }
    }
}

/** Registers an app the host was built without — the seam `ExtensibilityTest` uses (§24). */
public fun SuperizerBuilder.registerExtra(app: SuperizerApp<*>) {
    register(app)
}

public const val SCHEME: String = "unitool"

/** The Unitool server (`/prj/layers-fw/unitool-server`): device registry, push and the website. */
public const val PUSH_SERVER: String = "https://unitool.m42.cx"

/**
 * What `HashedPromoCodes` mixes into every code before hashing it, and what `scripts/promo-hash.sh`
 * uses by default. Changing it changes every key in the table above.
 */
public const val PROMO_SALT: String = "unitool:"
