package cx.m42.apps.converter

import cx.m42.superizer.testing.AppContractTest

/** The guarantees the host assumes, in one line (D21). */
class ConverterContractTest : AppContractTest(ConverterApp()) {

    override val configFixtures: List<String> = listOf(
        """{"schemaVersion":1,"base":"USD","favorites":["EUR","RUB"],"ratesUrl":"https://api.frankfurter.app/latest?from=USD"}""",
        // What a deep link produces, and what an older QR without the newer fields carries.
        """{"from":"EUR","to":"RUB"}""",
        """{"base":"EUR"}""",
    )

    override val stateFixtures: List<String> = listOf(
        """{"amount":"250","from":"USD","to":"RUB"}""",
        """{"amount":"1"}""",
    )
}
