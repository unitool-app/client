package cx.m42.unitool.premium

/**
 * Placeholder prices in the region's currency: what the Premium offer shows until a store tells it.
 *
 * A store answers in the currency of the person's storefront, already formatted. This answers from
 * the phone's region, which is usually the same country. Each currency gets one price point,
 * roughly what $2.99 a month and $19.99 a year come to in that store.
 */
internal class PremiumPrices private constructor(
    /** In the smallest unit the price is ever written in: cents, or whole roubles. */
    private val month: Long,
    private val year: Long,
    private val format: (Long) -> String,
) {
    val monthly: String get() = format(month)
    val yearly: String get() = format(year)

    /** What a year comes to a month, rounded to the nearest unit the price is written in. */
    val yearlyPerMonth: String get() = format((year + 6) / 12)

    /** How much less a year costs than twelve months, in percent, rounded down so it never promises more. */
    val yearlySaving: Int get() = ((month * 12 - year) * 100 / (month * 12)).toInt()

    companion object {
        /** [region] is an ISO 3166 code, as `Locale.region` gives it; an empty or unknown one gets dollars. */
        fun forRegion(region: String): PremiumPrices = when (region.uppercase()) {
            "RU" -> PremiumPrices(299, 1_990) { "${grouped(it)}${NBSP}₽" }
            "KZ" -> PremiumPrices(1_490, 9_990) { "${grouped(it)}${NBSP}₸" }
            "UA" -> PremiumPrices(119, 799) { "${grouped(it)}${NBSP}₴" }
            "GB" -> PremiumPrices(299, 1_999) { "£${decimal(it, '.')}" }
            in EURO -> PremiumPrices(299, 1_999) { "${decimal(it, ',')}${NBSP}€" }
            else -> PremiumPrices(299, 1_999) { "\$${decimal(it, '.')}" }
        }
    }
}

private const val NBSP = '\u00A0'

private val EURO = setOf(
    "AT", "BE", "CY", "DE", "EE", "ES", "FI", "FR", "GR", "HR",
    "IE", "IT", "LT", "LU", "LV", "MT", "NL", "PT", "SI", "SK",
)

/** 1990 → "1 990", with a space that does not break. */
private fun grouped(n: Long): String = n.toString().reversed().chunked(3).joinToString(NBSP.toString()).reversed()

/** 299 → "2.99" or "2,99". */
private fun decimal(cents: Long, mark: Char): String = "${cents / 100}$mark${(cents % 100).toString().padStart(2, '0')}"
