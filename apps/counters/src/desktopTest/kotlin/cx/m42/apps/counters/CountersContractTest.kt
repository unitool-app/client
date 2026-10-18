package cx.m42.apps.counters

import cx.m42.superizer.testing.AppContractTest

/** Everything the host assumes about this app, checked in one line (D21). */
class CountersContractTest : AppContractTest(CountersApp()) {

    override val configFixtures: List<String> = listOf(
        """{"schemaVersion":1}""",
        """{}""",
    )

    override val stateFixtures: List<String> = listOf(
        """{"open":"k3j2h1"}""",
        """{"page":"archive"}""",
        """{}""",
    )
}
