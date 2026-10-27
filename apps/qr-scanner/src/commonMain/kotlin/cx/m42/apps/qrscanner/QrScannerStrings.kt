package cx.m42.apps.qrscanner

/** The app's own words (D11). */
internal interface QrScannerStrings {
    val scan: String
    val paste: String
    val empty: String
    val emptyNoCamera: String
    val aim: String
    val notAChunk: String
    val otherFile: String
    val otherFileNotice: String
    val startOther: String
    val continueScanning: String
    val gatheringTitle: String
    val damaged: String

    val kindLink: String
    val kindWifi: String
    val kindEmail: String
    val kindPhone: String
    val kindSms: String
    val kindGeo: String
    val kindContact: String
    val kindEvent: String
    val kindOtp: String
    val kindText: String
    val kindFile: String

    val network: String
    val security: String
    val password: String
    val hiddenNetwork: String
    val showPassword: String
    val copyPassword: String
    val to: String
    val subject: String
    val body: String
    val number: String
    val coordinates: String
    val name: String
    val phone: String
    val email: String
    val organization: String
    val summary: String
    val start: String
    val end: String
    val location: String
    val issuer: String
    val account: String
    val otpNote: String
    val migrationNote: String

    val open: String
    val copy: String
    val save: String
    val text: String
    val hex: String
    val oneMore: String
    val decode: String
    val again: String
    val copied: String
    val saved: String
    val notSaved: String
    val tooLongToShow: String

    fun binary(bytes: Int): String
    fun progress(have: Int, count: Int): String
    fun missing(numbers: String): String
    fun codes(n: Int): String
    fun size(bytes: Int): String
}

internal object QrScannerStringsEn : QrScannerStrings {
    override val scan = "Scan"
    override val paste = "Paste"
    override val empty = "Point the camera at a QR code. A file cut into many codes is gathered in any order."
    override val emptyNoCamera = "No camera here: paste the text of a code."
    override val aim = "Point at a QR code"
    override val notAChunk = "Not a piece of this file"
    override val otherFile = "A code of another file — skipped"
    override val otherFileNotice = "A code of another file turned up. It was not mixed in."
    override val startOther = "Start over"
    override val continueScanning = "Continue scanning"
    override val gatheringTitle = "Gathering a file"
    override val damaged = "Every piece is in, but the file does not check out. Scan it again from the start."

    override val kindLink = "Link"
    override val kindWifi = "Wi-Fi"
    override val kindEmail = "Email"
    override val kindPhone = "Phone"
    override val kindSms = "SMS"
    override val kindGeo = "Place"
    override val kindContact = "Contact"
    override val kindEvent = "Event"
    override val kindOtp = "2FA key"
    override val kindText = "Text"
    override val kindFile = "File"

    override val network = "Network"
    override val security = "Security"
    override val password = "Password"
    override val hiddenNetwork = "Hidden network"
    override val showPassword = "Show"
    override val copyPassword = "Copy password"
    override val to = "To"
    override val subject = "Subject"
    override val body = "Text"
    override val number = "Number"
    override val coordinates = "Coordinates"
    override val name = "Name"
    override val phone = "Phone"
    override val email = "Email"
    override val organization = "Organization"
    override val summary = "Title"
    override val start = "Starts"
    override val end = "Ends"
    override val location = "Where"
    override val issuer = "Service"
    override val account = "Account"
    override val otpNote = "The secret is not shown. Add it in the 2FA app by scanning there."
    override val migrationNote = "Accounts moved from Google Authenticator. Import them in the 2FA app."

    override val open = "Open"
    override val copy = "Copy"
    override val save = "Save file"
    override val text = "Text"
    override val hex = "Hex"
    override val oneMore = "One more code"
    override val decode = "Decode base64"
    override val again = "New scan"
    override val copied = "Copied"
    override val saved = "Saved"
    override val notSaved = "Not saved"
    override val tooLongToShow = "Only the beginning is shown. Save the file to see all of it."

    override fun binary(bytes: Int) = "Binary data · ${size(bytes)}"
    override fun progress(have: Int, count: Int) = "$have of $count"
    override fun missing(numbers: String) = "Missing: $numbers"
    override fun codes(n: Int) = if (n == 1) "1 code" else "$n codes"
    override fun size(bytes: Int) = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${(bytes + 512) / 1024} KB"
        else -> "${(bytes * 10L / (1024 * 1024)) / 10.0} MB"
    }
}

internal object QrScannerStringsRu : QrScannerStrings {
    override val scan = "Сканировать"
    override val paste = "Вставить"
    override val empty = "Наведите камеру на QR-код. Файл, разрезанный на много кодов, собирается в любом порядке."
    override val emptyNoCamera = "Камеры нет: вставьте текст кода."
    override val aim = "Наведите на QR-код"
    override val notAChunk = "Это не кусок этого файла"
    override val otherFile = "Код другого файла — пропущен"
    override val otherFileNotice = "Попался код другого файла. Он не смешан с этим."
    override val startOther = "Начать заново"
    override val continueScanning = "Продолжить сканирование"
    override val gatheringTitle = "Сбор файла"
    override val damaged = "Все куски собраны, но файл не сходится. Отсканируйте его заново."

    override val kindLink = "Ссылка"
    override val kindWifi = "Wi-Fi"
    override val kindEmail = "Почта"
    override val kindPhone = "Телефон"
    override val kindSms = "SMS"
    override val kindGeo = "Место"
    override val kindContact = "Контакт"
    override val kindEvent = "Событие"
    override val kindOtp = "Ключ 2FA"
    override val kindText = "Текст"
    override val kindFile = "Файл"

    override val network = "Сеть"
    override val security = "Защита"
    override val password = "Пароль"
    override val hiddenNetwork = "Скрытая сеть"
    override val showPassword = "Показать"
    override val copyPassword = "Копировать пароль"
    override val to = "Кому"
    override val subject = "Тема"
    override val body = "Текст"
    override val number = "Номер"
    override val coordinates = "Координаты"
    override val name = "Имя"
    override val phone = "Телефон"
    override val email = "Почта"
    override val organization = "Организация"
    override val summary = "Название"
    override val start = "Начало"
    override val end = "Конец"
    override val location = "Где"
    override val issuer = "Сервис"
    override val account = "Аккаунт"
    override val otpNote = "Секрет не показывается. Добавьте ключ в приложении 2FA, отсканировав код там."
    override val migrationNote = "Аккаунты, перенесённые из Google Authenticator. Импортируйте их в приложении 2FA."

    override val open = "Открыть"
    override val copy = "Копировать"
    override val save = "Сохранить файл"
    override val text = "Текст"
    override val hex = "Hex"
    override val oneMore = "Ещё код"
    override val decode = "Раскодировать base64"
    override val again = "Новое сканирование"
    override val copied = "Скопировано"
    override val saved = "Сохранено"
    override val notSaved = "Не сохранено"
    override val tooLongToShow = "Показано только начало. Сохраните файл, чтобы увидеть всё."

    override fun binary(bytes: Int) = "Двоичные данные · ${size(bytes)}"
    override fun progress(have: Int, count: Int) = "$have из $count"
    override fun missing(numbers: String) = "Не хватает: $numbers"
    override fun codes(n: Int): String {
        val word = when {
            n % 10 == 1 && n % 100 != 11 -> "код"
            n % 10 in 2..4 && n % 100 !in 12..14 -> "кода"
            else -> "кодов"
        }
        return "$n $word"
    }
    override fun size(bytes: Int) = when {
        bytes < 1024 -> "$bytes Б"
        bytes < 1024 * 1024 -> "${(bytes + 512) / 1024} КБ"
        else -> "${((bytes * 10L / (1024 * 1024)) / 10.0).toString().replace('.', ',')} МБ"
    }
}

internal val QrScannerStringTables: Map<String, QrScannerStrings> = mapOf(
    "ru" to QrScannerStringsRu,
    "en" to QrScannerStringsEn,
)
