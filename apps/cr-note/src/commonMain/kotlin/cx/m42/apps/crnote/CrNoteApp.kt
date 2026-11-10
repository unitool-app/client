package cx.m42.apps.crnote

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
import cx.m42.superizer.backup.BackupPolicy
import cx.m42.superizer.runtime.InstanceRuntime

/**
 * Notes behind a face and a PIN (.ai-notes/cr-note). Secret (idea/09): only the code `[cr-note]`
 * opens it, for one visit.
 *
 * No host lock (D649): the app asks for the face itself, and that very face opens the chip key — a
 * host curtain on top would be a second sheet. No screenshots. Nothing in the host's backup (D658):
 * a backup would be a second copy that neither `178500` nor new faces could reach.
 */
public class CrNoteApp : SuperizerApp<Unit>() {

    override val manifest: AppManifest = AppManifest(
        id = AppId(ID),
        version = "1.0.0",
        minHostContract = 6,
        metadata = AppMetadata(
            title = localized("ru" to ID, "en" to ID),
            // A page with a folded corner and a keyhole.
            icon = AppIcon.Path(
                "M6 2h9l5 5v15H6zM8 4v16h10V8h-4V4z" +
                    "M13 11a2 2 0 1 1 0 4a2 2 0 0 1 0-4zm-0.8 3.6h1.6l0.5 3.4h-2.6z",
            ),
            hidden = true,
            secret = true,
        ),
        protection = AppProtection(lock = LockPolicy.Off, secureWindow = true),
        backup = BackupPolicy.None,
    )

    override val configSpec: AppConfigSpec<Unit> = AppConfigSpec.None

    override fun launch(runtime: InstanceRuntime, config: Unit): AppInstance = CrNoteInstance(runtime)

    public companion object {
        public const val ID: String = "cr-note"
    }
}
