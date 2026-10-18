package cx.m42.apps.counters

import cx.m42.superizer.app.AppConfigSpec
import cx.m42.superizer.app.AppIcon
import cx.m42.superizer.app.AppId
import cx.m42.superizer.app.AppInstance
import cx.m42.superizer.app.AppManifest
import cx.m42.superizer.app.AppMetadata
import cx.m42.superizer.app.SuperizerApp
import cx.m42.superizer.app.localized
import cx.m42.superizer.runtime.InstanceRuntime
import kotlinx.serialization.Serializable

/** Nothing to configure yet; the type exists so a field can be added without a new contract. */
@Serializable
public data class CountersConfig(val schemaVersion: Int = 1)

/**
 * Named counters with a plus and a minus, gathered into scores — one per game evening, one per
 * player inside it.
 *
 * Public and offline: the scores live in the app's own storage and nothing leaves the device. The
 * rules of what a change does are in `Boards.kt`, pure and tested without a screen.
 */
public class CountersApp : SuperizerApp<CountersConfig>() {

    override val manifest: AppManifest = AppManifest(
        id = AppId(ID),
        version = "1.0.0",
        metadata = AppMetadata(
            title = localized("ru" to "Счётчики", "en" to "Counters"),
            description = localized(
                "ru" to "Счёт в игре: у каждого своё имя и кнопки плюс и минус",
                "en" to "Keep score in a game: a name for each, with plus and minus",
            ),
            // A square with a plus in one corner and a minus in the other.
            icon = AppIcon.Path(
                "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z" +
                    "M5 5v14h14V5H5z" +
                    "M7 9h2V7h2v2h2v2h-2v2H9v-2H7z" +
                    "M13 15h4v2h-4z",
            ),
            category = "Tools",
        ),
    )

    override val configSpec: AppConfigSpec<CountersConfig> =
        AppConfigSpec(CountersConfig.serializer(), CountersConfig())

    override fun launch(runtime: InstanceRuntime, config: CountersConfig): AppInstance =
        CountersInstance(runtime)

    public companion object {
        public const val ID: String = "counters"
    }
}
