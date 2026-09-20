package cx.m42.apps.calculator

import cx.m42.superizer.testing.AppContractTest

/**
 * Everything the host assumes about this app, checked in one line (D21).
 *
 * The fixtures are the migration half: a config or a snapshot written by an older build has to
 * still read with today's spec, and an app that has never migrated anything still lists its current
 * shape so the test exists from day one rather than from the first time it was needed.
 */
class CalculatorContractTest : AppContractTest(CalculatorApp()) {

    override val configFixtures: List<String> = listOf(
        """{"schemaVersion":1,"mode":"standard"}""",
        """{"schemaVersion":1,"mode":"scientific"}""",
        // No version at all: what a QR code printed before the field existed carries.
        """{"mode":"scientific"}""",
    )

    override val stateFixtures: List<String> = listOf(
        """{"entry":"12","ribbon":"12 +","accumulator":"12.0","pending":"Plus","fresh":"false","error":"false"}""",
        // An older snapshot with fields this build no longer writes, and missing ones it does.
        """{"entry":"7"}""",
    )
}
