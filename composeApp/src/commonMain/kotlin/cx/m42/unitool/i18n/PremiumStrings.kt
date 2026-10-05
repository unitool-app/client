package cx.m42.unitool.i18n

/**
 * The words of the Premium offer on Activate (`PremiumOffer`). English first, Russian as its
 * translation. The small print follows what each store's own subscription screens say.
 */
internal interface PremiumStrings {
    /** Activate's name in the drawer and on its bar: the door is the offer now. */
    val entry: String
    val title: String
    val lead: String
    val monthly: String
    val yearly: String
    val billedMonthly: String
    fun perMonth(price: String): String
    fun subscribeMonthly(price: String): String
    fun subscribeYearly(price: String): String
    val restore: String
    val notYet: String
    val appStorePrint: String
    val googlePlayPrint: String
    val terms: String
    val privacy: String
}

internal object PremiumStringsEn : PremiumStrings {
    override val entry = "Premium"
    override val title = "Unitool Premium"
    override val lead = "Buy Premium here, or enter a promo code below."
    override val monthly = "Monthly"
    override val yearly = "Yearly"
    override val billedMonthly = "billed monthly"
    override fun perMonth(price: String) = "≈ $price a month"
    override fun subscribeMonthly(price: String) = "Subscribe for $price a month"
    override fun subscribeYearly(price: String) = "Subscribe for $price a year"
    override val restore = "Restore Purchases"
    override val notYet = "Purchases are coming in a later version."
    override val appStorePrint =
        "Payment will be charged to your Apple ID account at confirmation of purchase. " +
            "The subscription automatically renews unless auto-renew is turned off at least 24 hours " +
            "before the end of the current period. Your account will be charged for renewal within " +
            "24 hours prior to the end of the current period. You can manage and cancel your " +
            "subscription in your App Store account settings after purchase."
    override val googlePlayPrint =
        "Payment will be charged to your Google Play account at confirmation of purchase. " +
            "The subscription automatically renews unless cancelled before the end of the current " +
            "period. You can cancel at any time in Google Play → Payments & subscriptions."
    override val terms = "Terms of Use"
    override val privacy = "Privacy Policy"
}

internal object PremiumStringsRu : PremiumStrings {
    override val entry = "Премиум"
    override val title = "Unitool Premium"
    override val lead = "Премиум можно купить здесь или ввести промокод ниже."
    override val monthly = "Месяц"
    override val yearly = "Год"
    override val billedMonthly = "оплата каждый месяц"
    override fun perMonth(price: String) = "≈ $price в месяц"
    override fun subscribeMonthly(price: String) = "Оформить за $price в месяц"
    override fun subscribeYearly(price: String) = "Оформить за $price в год"
    override val restore = "Восстановить покупки"
    override val notYet = "Покупки появятся в одной из следующих версий."
    override val appStorePrint =
        "Оплата спишется с вашего Apple ID при подтверждении покупки. Подписка продлевается " +
            "автоматически, если автопродление не отключено как минимум за 24 часа до конца текущего " +
            "периода. Плата за следующий период списывается в течение 24 часов до его начала. " +
            "Управлять подпиской и отменить её можно в настройках учётной записи App Store после покупки."
    override val googlePlayPrint =
        "Оплата спишется с вашего аккаунта Google Play при подтверждении покупки. Подписка " +
            "продлевается автоматически, если не отменить её до конца текущего периода. Отменить " +
            "подписку можно в любой момент в Google Play → Платежи и подписки."
    override val terms = "Условия использования"
    override val privacy = "Политика конфиденциальности"
}

internal fun premiumStrings(langTag: String): PremiumStrings =
    if (langTag.substringBefore('-').lowercase() == "ru") PremiumStringsRu else PremiumStringsEn
