package cx.m42.apps.crnote

/** The app's own words (D11). */
internal interface CrNoteStrings {
    val faceReason: String
    val cancel: String

    val changedTitle: String
    val changedBody: String
    val startAgain: String

    val choosePin: String
    val repeatPin: String
    val enterPin: String
    val pinMismatch: String
    val pinForbidden: String
    val erase: String

    val empty: String
    val newNote: String
    val untitled: String
    val menu: String
    val export: String
    val import: String
    val back: String
    val delete: String
    val deleteQuestion: String
    val noteHint: String
    val scanQr: String

    val exportTitle: String
    val exportAll: String
    val methodAgePassword: String
    val methodAgeKeys: String
    val methodOpenssl: String
    val password: String
    val repeatPassword: String
    val showPassword: String
    val hidePassword: String
    val generate: String
    val passwordHint: String
    val phoneKeysNone: String
    fun phoneKey(name: String): String
    val recipientPlaceholder: String
    val add: String
    val githubPlaceholder: String
    val githubFetch: String
    fun github(state: GitHubState): String?
    val makeExport: String
    val working: String
    fun exportProblem(problem: ExportProblem): String
    fun ready(name: String): String
    val saveFile: String
    val share: String
    val opensWith: String
    val handedOver: String

    val importTitle: String
    val importHint: String
    val importFile: String
    val importQr: String
    val scanHint: String
    fun scanProgress(have: Int, of: Int): String
    val importAge: String
    val importAgeKey: String
    val importOpenssl: String
    val openWithPhoneKey: String
    val open: String
    fun importProblem(problem: ImportProblem): String
    fun addQuestion(count: Int): String
    val addConfirm: String
    fun added(count: Int): String
}

private fun ruNotes(n: Int): String {
    val tens = n % 100
    val ones = n % 10
    return when {
        tens in 11..14 -> "записок"
        ones == 1 -> "записку"
        ones in 2..4 -> "записки"
        else -> "записок"
    }
}

internal object CrNoteStringsRu : CrNoteStrings {
    override val faceReason = "cr-note"
    override val cancel = "Отмена"

    override val changedTitle = "Записки недоступны"
    override val changedBody = "Изменилась биометрия: лица или отпечатки на телефоне теперь другие. Прежние записки открыть нельзя."
    override val startAgain = "Начать заново"

    override val choosePin = "Придумайте PIN"
    override val repeatPin = "Повторите PIN"
    override val enterPin = "PIN"
    override val pinMismatch = "PIN не совпали. Ещё раз."
    override val pinForbidden = "Этот PIN поставить нельзя."
    override val erase = "Стереть цифру"

    override val empty = "Записок нет"
    override val newNote = "Новая записка"
    override val untitled = "Без заголовка"
    override val menu = "Меню"
    override val export = "Экспорт"
    override val import = "Импорт"
    override val back = "Назад"
    override val delete = "Удалить"
    override val deleteQuestion = "Удалить записку?"
    override val noteHint = "Текст записки"
    override val scanQr = "QR"

    override val exportTitle = "Экспорт"
    override val exportAll = "Все записки этой директории — одним зашифрованным файлом."
    override val methodAgePassword = "age, паролем"
    override val methodAgeKeys = "age, на ключи"
    override val methodOpenssl = "openssl enc, паролем"
    override val password = "Пароль"
    override val repeatPassword = "Повторите пароль"
    override val showPassword = "Показать"
    override val hidePassword = "Скрыть"
    override val generate = "Сгенерировать"
    override val passwordHint = "Не меньше 12 знаков. Без него файл не открыть."
    override val phoneKeysNone = "Ключей пока нет: добавьте ниже"
    override fun phoneKey(name: String) = "Этот телефон · $name"
    override val recipientPlaceholder = "age1… или строка открытого SSH-ключа"
    override val add = "Добавить"
    override val githubPlaceholder = "Логин GitHub"
    override val githubFetch = "Ключи GitHub"
    override fun github(state: GitHubState) = when (state) {
        GitHubState.Idle -> null
        GitHubState.Loading -> "Загружаю…"
        GitHubState.NoSuchUser -> "Такого пользователя нет"
        GitHubState.NoKeys -> "У пользователя нет ключей, на которые шифрует age"
        GitHubState.Offline -> "GitHub не ответил"
    }
    override val makeExport = "Зашифровать"
    override val working = "Шифрую…"
    override fun exportProblem(problem: ExportProblem) = when (problem) {
        ExportProblem.ShortPassword -> "Пароль короче 12 знаков"
        ExportProblem.PasswordsDiffer -> "Пароли не совпали"
        ExportProblem.NoRecipients -> "Отметьте хотя бы один ключ"
        ExportProblem.BadRecipient -> "Это не ключ age и не открытый SSH-ключ"
        ExportProblem.Failed -> "Не получилось зашифровать"
    }
    override fun ready(name: String) = "Готово: $name"
    override val saveFile = "Сохранить файл"
    override val share = "Поделиться"
    override val opensWith = "Открыть на компьютере:"
    override val handedOver = "Файл передан"

    override val importTitle = "Импорт"
    override val importHint = "Только зашифрованное: экспорт cr-note или любой файл age или openssl enc с текстом. Записки добавятся к этим."
    override val importFile = "Открыть файл"
    override val importQr = "Сканировать QR"
    override val scanHint = "Наведите на QR-код"
    override fun scanProgress(have: Int, of: Int) = "$have из $of"
    override val importAge = "Файл age, паролем"
    override val importAgeKey = "Файл age, на ключ"
    override val importOpenssl = "Файл openssl enc"
    override val openWithPhoneKey = "Открыть ключом телефона"
    override val open = "Открыть"
    override fun importProblem(problem: ImportProblem) = when (problem) {
        ImportProblem.Refused -> "Это не зашифрованный файл age или openssl enc"
        ImportProblem.WrongPassword -> "Пароль не подошёл"
        ImportProblem.NoKey -> "Ни один ключ этого телефона его не открывает"
        ImportProblem.NotText -> "Внутри не текст"
        ImportProblem.NothingNew -> "Все эти записки уже здесь"
        ImportProblem.Damaged -> "Файл повреждён"
    }
    override fun addQuestion(count: Int) = "Добавить $count ${ruNotes(count)}?"
    override val addConfirm = "Добавить"
    override fun added(count: Int) = "Добавлено: $count"
}

internal object CrNoteStringsEn : CrNoteStrings {
    override val faceReason = "cr-note"
    override val cancel = "Cancel"

    override val changedTitle = "Notes unavailable"
    override val changedBody = "The biometrics changed: the faces or fingerprints on this phone are not the same. The old notes cannot be opened."
    override val startAgain = "Start again"

    override val choosePin = "Choose a PIN"
    override val repeatPin = "Repeat the PIN"
    override val enterPin = "PIN"
    override val pinMismatch = "The PINs differ. Once more."
    override val pinForbidden = "This PIN cannot be set."
    override val erase = "Erase a digit"

    override val empty = "No notes"
    override val newNote = "New note"
    override val untitled = "Untitled"
    override val menu = "Menu"
    override val export = "Export"
    override val import = "Import"
    override val back = "Back"
    override val delete = "Delete"
    override val deleteQuestion = "Delete the note?"
    override val noteHint = "The note"
    override val scanQr = "QR"

    override val exportTitle = "Export"
    override val exportAll = "Every note of this directory, as one encrypted file."
    override val methodAgePassword = "age, with a password"
    override val methodAgeKeys = "age, to keys"
    override val methodOpenssl = "openssl enc, with a password"
    override val password = "Password"
    override val repeatPassword = "Repeat the password"
    override val showPassword = "Show"
    override val hidePassword = "Hide"
    override val generate = "Generate"
    override val passwordHint = "At least 12 characters. Without it the file does not open."
    override val phoneKeysNone = "No keys yet: add one below"
    override fun phoneKey(name: String) = "This phone · $name"
    override val recipientPlaceholder = "age1… or an SSH public key line"
    override val add = "Add"
    override val githubPlaceholder = "GitHub login"
    override val githubFetch = "GitHub keys"
    override fun github(state: GitHubState) = when (state) {
        GitHubState.Idle -> null
        GitHubState.Loading -> "Loading…"
        GitHubState.NoSuchUser -> "No such user"
        GitHubState.NoKeys -> "The user has no key age encrypts to"
        GitHubState.Offline -> "GitHub did not answer"
    }
    override val makeExport = "Encrypt"
    override val working = "Encrypting…"
    override fun exportProblem(problem: ExportProblem) = when (problem) {
        ExportProblem.ShortPassword -> "The password is shorter than 12 characters"
        ExportProblem.PasswordsDiffer -> "The passwords differ"
        ExportProblem.NoRecipients -> "Tick at least one key"
        ExportProblem.BadRecipient -> "Not an age key and not an SSH public key"
        ExportProblem.Failed -> "Could not encrypt"
    }
    override fun ready(name: String) = "Ready: $name"
    override val saveFile = "Save file"
    override val share = "Share"
    override val opensWith = "To open it on a computer:"
    override val handedOver = "The file was handed over"

    override val importTitle = "Import"
    override val importHint = "Encrypted only: a cr-note export, or any age or openssl enc file with text inside. The notes are added to these."
    override val importFile = "Open a file"
    override val importQr = "Scan QR"
    override val scanHint = "Point at the QR code"
    override fun scanProgress(have: Int, of: Int) = "$have of $of"
    override val importAge = "age file, with a password"
    override val importAgeKey = "age file, to a key"
    override val importOpenssl = "openssl enc file"
    override val openWithPhoneKey = "Open with the phone's key"
    override val open = "Open"
    override fun importProblem(problem: ImportProblem) = when (problem) {
        ImportProblem.Refused -> "Not an encrypted age or openssl enc file"
        ImportProblem.WrongPassword -> "The password did not fit"
        ImportProblem.NoKey -> "No key of this phone opens it"
        ImportProblem.NotText -> "There is no text inside"
        ImportProblem.NothingNew -> "All these notes are already here"
        ImportProblem.Damaged -> "The file is damaged"
    }
    override fun addQuestion(count: Int) = if (count == 1) "Add 1 note?" else "Add $count notes?"
    override val addConfirm = "Add"
    override fun added(count: Int) = "Added: $count"
}

internal val CrNoteStringTables: Map<String, CrNoteStrings> = mapOf("ru" to CrNoteStringsRu, "en" to CrNoteStringsEn)
