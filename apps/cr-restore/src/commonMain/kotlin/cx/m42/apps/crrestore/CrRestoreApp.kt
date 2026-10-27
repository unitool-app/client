package cx.m42.apps.crrestore

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

/**
 * Opens what age, `openssl enc` and a split secret sealed — from QR codes or a file — and keeps
 * none of it (.ai-notes/cr-restore).
 *
 * Secret (idea/09): only the code `[cr-restore]` opens it, for one visit. No lock to come in; the
 * lock while something decrypted is on screen (`WhileHolding`, D598). No screenshots (D613). Nothing
 * is written anywhere — not the result, not a setting (D597).
 */
public class CrRestoreApp : SuperizerApp<Unit>() {

    override val manifest: AppManifest = AppManifest(
        id = AppId(ID),
        version = "1.0.0",
        // 6: `LockPolicy.WhileHolding` and `SecretHold`.
        minHostContract = 6,
        metadata = AppMetadata(
            title = localized("ru" to ID, "en" to ID),
            // A key over a broken line: what was split or sealed, put back.
            icon = AppIcon.Path(
                "M7 14a3 3 0 1 1 0-6a3 3 0 0 1 0 6zm0-2a1 1 0 1 0 0-2a1 1 0 0 0 0 2z" +
                    "M9.8 10h11.2v2h-2v3h-2v-3h-2v2h-2v-2H9.8z" +
                    "M3 19h4v2H3zM10 19h4v2h-4zM17 19h4v2h-4z",
            ),
            hidden = true,
            secret = true,
        ),
        protection = AppProtection(lock = LockPolicy.WhileHolding, secureWindow = true),
    )

    override val configSpec: AppConfigSpec<Unit> = AppConfigSpec.None

    override fun launch(runtime: InstanceRuntime, config: Unit): AppInstance = CrRestoreInstance(runtime)

    public companion object {
        public const val ID: String = "cr-restore"
    }
}
