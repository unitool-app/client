package cx.m42.unitool.i18n

/** The words of Settings → Website, signing in to the site with the app (.ai-notes/site/02). */
internal interface WebStrings {
    val website: String
    fun websiteHint(host: String): String
    val scan: String
    fun scanHint(host: String): String
    val paste: String
    val pasteHint: String
    val link: String
    val next: String
    val notACode: String
    val browsers: String
    val browsersHint: String

    val confirmTitle: String
    val confirmHint: String
    val browser: String
    val address: String
    val addressNote: String
    val unknownBrowser: String
    val signIn: String
    val cancel: String
    val checking: String
    val signedIn: String
    val signedInHint: String
    val done: String
    val gone: String
    val failed: String

    val noBrowsers: String
    fun signedInSince(date: String): String
    fun lastSeen(date: String): String
    val signOut: String
    val signOutAll: String
}

internal object WebStringsEn : WebStrings {
    override val website = "Website"
    override fun websiteHint(host: String) =
        "Sign in on $host with this app. " +
            "The site learns only that it is this installation of Unitool — no name, no account."
    override val scan = "Scan QR code to sign in"
    override fun scanHint(host: String) = "Open $host$SIGN_IN_PATH on the computer and scan the code it shows"
    override val paste = "Paste a sign-in link"
    override val pasteHint = "Where there is no camera: copy the link under the code on the sign-in page"
    override val link = "Sign-in link"
    override val next = "Continue"
    override val notACode = "This is not a sign-in code of this app's website."
    override val browsers = "Signed-in browsers"
    override val browsersHint = "Where this app is signed in, and signing them out"

    override val confirmTitle = "Sign in on the website?"
    override val confirmHint =
        "Confirm only if you started this sign-in yourself, on a screen in front of you. " +
            "Someone who shows you their code would be signed in as you."
    override val browser = "Browser"
    override val address = "Address"
    override val addressNote = "as the server saw it"
    override val unknownBrowser = "Unknown browser"
    override val signIn = "Sign in"
    override val cancel = "Cancel"
    override val checking = "Asking the server…"
    override val signedIn = "Signed in"
    override val signedInHint = "Go back to the browser: the page opens by itself."
    override val done = "Done"
    override val gone = "This code has expired or has been used. Show a new one in the browser."
    override val failed = "The server cannot be reached. Try again."

    override val noBrowsers = "No browser is signed in with this app."
    override fun signedInSince(date: String) = "Signed in $date"
    override fun lastSeen(date: String) = "last seen $date"
    override val signOut = "Sign out"
    override val signOutAll = "Sign out everywhere"
}

internal object WebStringsRu : WebStrings {
    override val website = "Сайт"
    override fun websiteHint(host: String) =
        "Вход на $host через это приложение. " +
            "Сайт узнаёт только, что это ваша установка Unitool — без имени и без аккаунта."
    override val scan = "Сканировать QR-код для входа"
    override fun scanHint(host: String) = "Откройте $host$SIGN_IN_PATH на компьютере и наведите камеру на код"
    override val paste = "Вставить ссылку для входа"
    override val pasteHint = "Если камеры нет: скопируйте ссылку под кодом на странице входа"
    override val link = "Ссылка для входа"
    override val next = "Дальше"
    override val notACode = "Это не код входа на сайт этого приложения."
    override val browsers = "Браузеры, где выполнен вход"
    override val browsersHint = "Где приложение вошло на сайт, и выход из них"

    override val confirmTitle = "Войти на сайт?"
    override val confirmHint =
        "Подтверждайте, только если вход начали вы сами, на экране перед вами. " +
            "Кто покажет вам свой код, войдёт вместо вас."
    override val browser = "Браузер"
    override val address = "Адрес"
    override val addressNote = "по данным сервера"
    override val unknownBrowser = "Неизвестный браузер"
    override val signIn = "Войти"
    override val cancel = "Отмена"
    override val checking = "Спрашиваем сервер…"
    override val signedIn = "Вход выполнен"
    override val signedInHint = "Вернитесь в браузер: страница откроется сама."
    override val done = "Готово"
    override val gone = "Код истёк или уже использован. Покажите новый в браузере."
    override val failed = "Сервер недоступен. Попробуйте ещё раз."

    override val noBrowsers = "Ни в одном браузере вход не выполнен."
    override fun signedInSince(date: String) = "Вход $date"
    override fun lastSeen(date: String) = "последний раз $date"
    override val signOut = "Выйти"
    override val signOutAll = "Выйти везде"
}

private const val SIGN_IN_PATH = "/login"

internal fun webStrings(langTag: String): WebStrings =
    if (langTag.substringBefore('-').lowercase() == "ru") WebStringsRu else WebStringsEn
