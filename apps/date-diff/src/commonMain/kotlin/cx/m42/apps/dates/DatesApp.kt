package cx.m42.apps.dates

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

/**
 * What the app starts as: whether the last day is counted. A link from an HR page or a holiday
 * planner would ask for `true`; the person can still flip it, and their choice is what sticks.
 */
@Serializable
public data class DatesConfig(val inclusive: Boolean = false)

/**
 * Days between two dates: calendar days, weeks, years-months-days, and weekdays against weekends.
 *
 * Public and offline — no network, no secrets, nothing that leaves the device. The arithmetic is in
 * `CalendarDate.kt` and `DateSpan.kt`, pure and tested without a screen.
 */
public class DatesApp internal constructor(
    /** The device's zone, for "today". A test pins it, so midnight is not a flaky hour. */
    private val utcOffset: (Long) -> Long,
) : SuperizerApp<DatesConfig>() {

    public constructor() : this(::localUtcOffsetMillis)

    override val manifest: AppManifest = AppManifest(
        id = AppId(ID),
        version = "1.0.0",
        metadata = AppMetadata(
            title = localized("ru" to "Разница дат", "en" to "Date difference"),
            description = localized(
                "ru" to "Сколько дней, недель и месяцев между датами, будни и выходные",
                "en" to "Days, weeks and months between two dates, weekdays and weekends",
            ),
            // A calendar sheet: two rings, the page with its header cut out, one day marked.
            icon = AppIcon.Path(
                "M7 2h2v2h6V2h2v2h2a2 2 0 0 1 2 2v13a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2V2z" +
                    "M5 9v10h14V9H5zM7 11h4v4H7z",
            ),
            category = "Tools",
        ),
    )

    override val configSpec: AppConfigSpec<DatesConfig> =
        AppConfigSpec(DatesConfig.serializer(), DatesConfig())

    override fun launch(runtime: InstanceRuntime, config: DatesConfig): AppInstance =
        DatesInstance(runtime, config, utcOffset)

    public companion object {
        public const val ID: String = "date-diff"
    }
}
