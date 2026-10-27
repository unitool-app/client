package cx.m42.apps.converter

import cx.m42.superizer.testing.AppContractTest

/** The guarantees the host assumes, in one line (D21). */
class ConverterContractTest : AppContractTest(ConverterApp()) {

    override val configFixtures: List<String> = listOf(
        """{"schemaVersion":1,"base":"USD","favorites":["EUR","RUB","VND"],"ratesUrl":"https://open.er-api.com/v6/latest/USD"}""",
        // A config from before the source changed: still a config, and still points at the ECB.
        """{"schemaVersion":1,"base":"USD","favorites":["EUR","RUB"],"ratesUrl":"https://api.frankfurter.app/latest?from=USD"}""",
        // What the deep link of 1.0 produced, and an older config without the newer fields: both still read.
        """{"from":"EUR","to":"RUB"}""",
        """{"base":"EUR"}""",
    )

    override val stateFixtures: List<String> = listOf(
        """{"rows":[{"from":"USD","to":"RUB","amount":"250"},{"from":"EUR","to":"USD","amount":"1"}]}""",
        // A snapshot of 1.0, with one pair and no rows: ignored rather than a crash.
        """{"amount":"250","from":"USD","to":"RUB"}""",
    )
}
