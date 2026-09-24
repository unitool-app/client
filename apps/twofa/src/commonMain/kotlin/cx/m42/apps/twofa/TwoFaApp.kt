package cx.m42.apps.twofa

import cx.m42.superizer.app.AppConfig
import cx.m42.superizer.app.AppConfigSpec
import cx.m42.superizer.app.AppIcon
import cx.m42.superizer.app.AppId
import cx.m42.superizer.app.AppInstance
import cx.m42.superizer.app.AppManifest
import cx.m42.superizer.app.AppMetadata
import cx.m42.superizer.app.AppSetupContext
import cx.m42.superizer.app.SuperizerApp
import cx.m42.superizer.app.localized
import cx.m42.superizer.runtime.InstanceRuntime
import kotlinx.serialization.Serializable

/**
 * What the 2FA app can be told.
 *
 * One field: a link to *offer*. The app opens its add form filled in from it and waits for the
 * person to press Save — a config comes from a QR code, a deep link or a promo code, and none of
 * those should be able to put a secret on the device without anyone looking.
 */
@Serializable
public data class TwoFaConfig(
    val otpauth: String? = null,
)

/**
 * TOTP codes (RFC 6238) for the accounts a person adds — Google Authenticator's job, as an app.
 *
 * No push, and no network for the codes: they are a function of a secret and the clock, and both
 * are on the device. The one request the app ever makes is the export's, for the public SSH keys of
 * the GitHub account the person names — the secrets are encrypted to those keys on the device.
 */
public class TwoFaApp : SuperizerApp<TwoFaConfig>() {

    override val manifest: AppManifest = AppManifest(
        id = ID,
        version = "0.1.0",
        metadata = AppMetadata(
            title = localized("ru" to "Коды 2FA", "en" to "2FA codes"),
            description = localized(
                "ru" to "Одноразовые коды для входа, без сети",
                "en" to "One-time sign-in codes, offline",
            ),
            // A padlock: the shackle as a band, the body with a keyhole wound the other way so it
            // stays open under the non-zero fill the host draws paths with.
            icon = AppIcon.Path(
                "M7 10V7a5 5 0 0 1 10 0v3h-2V7a3 3 0 0 0-6 0v3z" +
                    "M5 10h14a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V11a1 1 0 0 1 1-1z" +
                    "M12 13.5a1.5 1.5 0 0 0-.75 2.8V19h1.5v-2.7a1.5 1.5 0 0 0-.75-2.8z",
            ),
            category = "Безопасность",
        ),
        deepLinks = setOf("add"),
    )

    override val configSpec: AppConfigSpec<TwoFaConfig> =
        AppConfigSpec(TwoFaConfig.serializer(), TwoFaConfig())

    override fun setup(ctx: AppSetupContext) {
        // `unitool://app/2fa/add?uri=otpauth%3A%2F%2Ftotp%2F…` — a site that knows Unitool can
        // link straight into the add form. It still ends at the Save button.
        ctx.deepLink("add") { params ->
            params["uri"]?.let { configSpec.encode(TwoFaConfig(otpauth = it)) } ?: AppConfig.Empty
        }
    }

    override fun launch(runtime: InstanceRuntime, config: TwoFaConfig): AppInstance =
        TwoFaInstance(runtime, config)

    public companion object {
        public val ID: AppId = AppId("2fa")
    }
}
