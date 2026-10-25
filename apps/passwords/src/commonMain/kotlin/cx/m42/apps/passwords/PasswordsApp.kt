package cx.m42.apps.passwords

import cx.m42.superizer.app.AppConfigSpec
import cx.m42.superizer.app.AppIcon
import cx.m42.superizer.app.AppId
import cx.m42.superizer.app.AppInstance
import cx.m42.superizer.app.AppManifest
import cx.m42.superizer.app.AppMetadata
import cx.m42.superizer.app.AppProtection
import cx.m42.superizer.app.LockPolicy
import cx.m42.superizer.app.SuperizerApp
import cx.m42.superizer.app.localized
import cx.m42.superizer.runtime.InstanceRuntime
import kotlinx.serialization.Serializable

/**
 * Nothing to be told. No deep link either (D531): a link that fills in a password is a phishing
 * page's shortest way into the list.
 */
@Serializable
public data class PasswordsConfig(val schemaVersion: Int = 1)

/**
 * Logins and passwords, kept the way 2FA keeps its keys (.ai-notes/passwords): sealed by the host,
 * behind the device's lock, shown one at a time and only once the person confirms.
 *
 * No network for the passwords. The one request the app makes is the export's, for the public SSH
 * keys of the GitHub account the person names, exactly as in 2FA.
 */
public class PasswordsApp : SuperizerApp<PasswordsConfig>() {

    override val manifest: AppManifest = AppManifest(
        id = ID,
        version = "0.1.0",
        // Contract 3: the list lives in `runtime.secrets`, which the host seals; 2 brought the lock.
        minHostContract = 3,
        metadata = AppMetadata(
            title = localized("ru" to "Пароли", "en" to "Passwords"),
            description = localized(
                "ru" to "Логины и пароли под замком устройства",
                "en" to "Logins and passwords behind the device lock",
            ),
            // A key: the bow with its hole wound the other way, so the non-zero fill leaves it open,
            // then the shaft and two teeth.
            icon = AppIcon.Path(
                "M8 7a5 5 0 1 1 0 10a5 5 0 1 1 0-10z" +
                    "M8 10a2 2 0 1 0 0 4a2 2 0 1 0 0-4z" +
                    "M12.5 11h9.5v2h-9.5z" +
                    "M18 13h2v3h-2z" +
                    "M15 13h2v2h-2z",
            ),
            category = "Security",
        ),
        // As 2FA (06, D139): the device's lock on every opening, and no screenshots, recordings or
        // recents thumbnail of a page that may have a password on it.
        protection = AppProtection(lock = LockPolicy.Required, secureWindow = true),
    )

    override val configSpec: AppConfigSpec<PasswordsConfig> =
        AppConfigSpec(PasswordsConfig.serializer(), PasswordsConfig())

    override fun launch(runtime: InstanceRuntime, config: PasswordsConfig): AppInstance =
        PasswordsInstance(runtime)

    public companion object {
        public val ID: AppId = AppId("passwords")
    }
}
