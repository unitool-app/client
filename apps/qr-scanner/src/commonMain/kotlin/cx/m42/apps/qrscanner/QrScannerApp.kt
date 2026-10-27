package cx.m42.apps.qrscanner

import cx.m42.superizer.app.AppConfigSpec
import cx.m42.superizer.app.AppIcon
import cx.m42.superizer.app.AppId
import cx.m42.superizer.app.AppInstance
import cx.m42.superizer.app.AppManifest
import cx.m42.superizer.app.AppMetadata
import cx.m42.superizer.app.SuperizerApp
import cx.m42.superizer.app.localized
import cx.m42.superizer.runtime.InstanceRuntime

/**
 * A QR scanner (.ai-notes/qr-scanner): what a code holds, read out — a link, Wi-Fi, a contact and
 * the rest — anything that is not text as hex, and a file cut into many codes
 * (`unitool qr encode --file`) gathered back in any order. Copy the text, save the file.
 *
 * Public and offline, no lock, and nothing kept: no history, no setting (D629).
 */
public class QrScannerApp : SuperizerApp<Unit>() {

    override val manifest: AppManifest = AppManifest(
        id = AppId(ID),
        version = "1.0.0",
        metadata = AppMetadata(
            title = localized("ru" to "QR-сканер", "en" to "QR scanner"),
            description = localized(
                "ru" to "Ссылки, Wi-Fi, контакты и любые данные из QR-кодов; файл из многих кодов",
                "en" to "Links, Wi-Fi, contacts and any data in QR codes; a file from many codes",
            ),
            // Three finder squares and a scatter of modules.
            icon = AppIcon.Path(
                "M3 3h8v8H3zM5 5v4h4V5zM13 3h8v8h-8zM15 5v4h4V5zM3 13h8v8H3zM5 15v4h4v-4z" +
                    "M6 6h2v2H6zM16 6h2v2h-2zM6 16h2v2H6z" +
                    "M13 13h2v2h-2zM15 15h2v2h-2zM17 13h4v2h-4zM13 17h2v4h-2zM17 17h2v2h-2zM19 19h2v2h-2z",
            ),
            category = "Tools",
        ),
    )

    override val configSpec: AppConfigSpec<Unit> = AppConfigSpec.None

    override fun launch(runtime: InstanceRuntime, config: Unit): AppInstance = QrScannerInstance(runtime)

    public companion object {
        public const val ID: String = "qr-scanner"
    }
}
