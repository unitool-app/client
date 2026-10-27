package cx.m42.apps.dates

import cx.m42.superizer.testing.AppContractTest

/** Everything the host assumes about this app, checked in one line (D21). */
class DatesContractTest : AppContractTest(DatesApp()) {

    override val configFixtures: List<String> = listOf(
        """{"schemaVersion":1,"inclusive":true}""",
        """{"inclusive":false}""",
        """{}""",
    )

    override val stateFixtures: List<String> = listOf(
        """{"start":"01.10.2026","end":"18.10.2026","inclusive":"true"}""",
        // Half a snapshot: what is missing is whatever a fresh launch has.
        """{"end":"31.12.2026"}""",
        """{"mode":"Shift","base":"01.10.2026","subtract":"true","amount":"60","unit":"Weekdays","countStart":"true","includeStart":"false"}""",
        // A name this build does not know falls back rather than throwing.
        """{"mode":"Calendar","unit":"Fortnights","amount":"x"}""",
    )
}
