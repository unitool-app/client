package cx.m42.apps.passwords

import cx.m42.unitool.kit.password.PasswordMode
import cx.m42.unitool.kit.password.PasswordRules

/** The Passwords app's own words (D11). */
internal interface PasswordsStrings {
    val emptyTitle: String
    val emptyHint: String
    val add: String
    val search: String
    val nothingFound: String
    val copy: String
    val copied: String
    val details: String

    val newEntry: String
    val editEntry: String
    val title: String
    val titleHint: String
    val username: String
    val password: String
    val generate: String
    /** Under the button: how long the password is and how many bits of the device's entropy it holds. */
    fun generateHint(rules: PasswordRules, bits: Int): String
    val generatorSettings: String
    fun mode(mode: PasswordMode): String
    val length: String
    val wordCount: String
    val lower: String
    val upper: String
    val digits: String
    val symbols: String
    val lookAlike: String
    val drawEntropy: String
    val drawHint: String
    fun drawProgress(bits: Int, target: Int, seconds: Long): String
    /** Added to the hint under Generate while a drawing is mixed in. */
    fun withDrawing(bits: Int): String
    val drawDone: String
    val drawReset: String
    val url: String
    val note: String
    val save: String
    val cancel: String

    val showPassword: String
    val hidePassword: String
    val edit: String
    val delete: String
    val deleteConfirm: String
    val deleteForever: String
    val back: String
    fun changed(date: String): String

    /** What the device's sheet says each confirmation is for (06 §5.5). */
    val confirmShow: String
    val confirmEdit: String
    val confirmExport: String
    val confirmDelete: String

    val import: String
    val importTitle: String
    val importIntroText: String
    val scan: String
    val scanHint: String
    val importFile: String
    val importFileHint: String
    val orPaste: String
    val pasteHint: String
    val readText: String
    val alreadyHere: String
    val passwordDiffers: String
    val willReplace: String
    val willAddBeside: String
    fun differing(count: Int): String
    val keepBoth: String
    val keepBothHint: String
    val more: String
    fun filter(filter: ImportFilter): String
    val selectAll: String
    val selectNone: String
    val scanMore: String
    val scanMoreHint: String
    fun fileProblem(problem: FileProblem): String
    fun importIntro(found: Int, fresh: Int, differing: Int): String
    fun importAdd(add: Int, replace: Int): String
    fun importFailed(failed: ImportBatch.Failed): String

    val export: String
    val exportTitle: String
    fun exportIntro(entries: Int): String
    val savedKeys: String
    val savedKeysHint: String
    val githubAccount: String
    val githubHint: String
    val findKeys: String
    val findingKeys: String
    fun keysOf(login: String): String
    val keysHint: String
    fun keysError(error: KeysError): String
    val unsupportedKey: String
    val weakKey: String
    val shareExport: String
    val copyExport: String
    fun exportDone(done: ExportDone): String
    fun shareMessage(audience: String, command: String, transfer: String): String
    val decryptTitle: String
    val decryptHint: String
    val tapToCopyCommand: String
    val commandCopied: String
    val transferTitle: String
    val transferHint: String

    fun error(error: DraftError): String
    fun storageProblem(problem: StorageProblem): String
    val unlock: String
}

internal object PasswordsStringsRu : PasswordsStrings {
    override val emptyTitle = "Паролей пока нет"
    override val emptyHint = "Добавьте первый или импортируйте из браузера: Chrome, Firefox, Safari, Bitwarden и другие сохраняют пароли в CSV."
    override val add = "Добавить"
    override val search = "Поиск"
    override val nothingFound = "Ничего не нашлось"
    override val copy = "Копировать"
    override val copied = "Скопировано"
    override val details = "Подробнее"

    override val newEntry = "Новый пароль"
    override val editEntry = "Изменить"
    override val title = "Название"
    override val titleHint = "GitHub"
    override val username = "Логин"
    override val password = "Пароль"
    override val generate = "Сгенерировать"
    override fun generateHint(rules: PasswordRules, bits: Int): String {
        val size = when (rules.mode) {
            PasswordMode.Characters -> count(rules.length, "символ", "символа", "символов")
            PasswordMode.Words -> count(rules.words, "слово", "слова", "слов")
            PasswordMode.Mixed -> count(rules.words, "слово", "слова", "слов") + " с цифрами и знаками между ними"
        }
        return "$size · ${count(bits, "бит", "бита", "бит")} энтропии устройства"
    }
    override val generatorSettings = "Настроить"
    override fun mode(mode: PasswordMode) = when (mode) {
        PasswordMode.Characters -> "Символы"
        PasswordMode.Words -> "Слова"
        PasswordMode.Mixed -> "Слова и символы"
    }
    override val length = "Длина"
    override val wordCount = "Слов"
    override val lower = "Строчные a–z"
    override val upper = "Заглавные A–Z"
    override val digits = "Цифры 0–9"
    override val symbols = "Знаки ! # $ % & * + - = ? @ ^ _"
    override val lookAlike = "Похожие l, I, O, 0, 1"
    override val drawEntropy = "Добавить энтропию рисованием"
    override val drawHint = "Водите пальцем или мышью по полю — чем дольше и неровнее, тем больше. Основа остаётся системной: рисунок подмешивается к ней и ослабить пароль не может."
    override fun drawProgress(bits: Int, target: Int, seconds: Long) =
        "≈ ${minOf(bits, target)} из $target бит" + (if (bits >= target) " — достаточно" else "") + " · ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    override fun withDrawing(bits: Int) = " + рисование ≈ ${count(bits, "бит", "бита", "бит")}"
    override val drawDone = "Готово"
    override val drawReset = "Сбросить"
    override val url = "Сайт"
    override val note = "Заметка"
    override val save = "Сохранить"
    override val cancel = "Отмена"

    override val showPassword = "Показать"
    override val hidePassword = "Скрыть"
    override val edit = "Изменить"
    override val delete = "Удалить"
    override val deleteConfirm = "Удалить запись насовсем? Пароль не восстановить, если его нет в экспорте."
    override val deleteForever = "Удалить"
    override val back = "Назад"
    override fun changed(date: String) = "Изменено $date"

    override val confirmShow = "Показать пароль"
    override val confirmEdit = "Изменить пароль"
    override val confirmExport = "Экспорт всех паролей"
    override val confirmDelete = "Удалить пароль"

    override val import = "Импорт"
    override val importTitle = "Импорт"
    override val importIntroText = "Файл CSV из браузера или менеджера паролей, или экспорт Unitool, показанный на компьютере QR-кодом."
    override val scan = "Сканировать QR-код"
    override val scanHint = "Экспорт Unitool, открытый на компьютере командой со страницы экспорта."
    override val importFile = "Выбрать файл"
    override val importFileHint = "CSV из Chrome, Firefox, Safari, Bitwarden, KeePassXC, 1Password — или файл .age экспорта Unitool, если ключ SSH на этом телефоне."
    override val orPaste = "Или вставьте текст CSV"
    override val pasteHint = "Первая строка — заголовок: name,url,username,password,note"
    override val readText = "Прочитать"
    override val alreadyHere = "Уже есть на этом устройстве"
    override val passwordDiffers = "Пароль отличается от сохранённого"
    override val willReplace = "заменит сохранённый"
    override val willAddBeside = "добавится второй записью"
    override fun differing(count: Int) = "У ${entries(count)} пароль не такой, как на устройстве."
    override val keepBoth = "Оставить оба пароля"
    override val keepBothHint = "Выключено — сохранённый пароль заменится прочитанным. Включено — прочитанный добавится рядом, отдельной записью."
    override val more = "Ещё"
    override fun filter(filter: ImportFilter) = when (filter) {
        ImportFilter.All -> "Все"
        ImportFilter.New -> "Новые"
        ImportFilter.Differing -> "Другой пароль"
        ImportFilter.Known -> "Уже есть"
    }
    override val selectAll = "Отметить все"
    override val selectNone = "Снять все"
    override val scanMore = "Сканировать ещё"
    override val scanMoreHint = "Длинный экспорт показывается несколькими кодами: отсканируйте все, записи соберутся в один список."
    override fun fileProblem(problem: FileProblem) = when (problem) {
        FileProblem.NoKey -> "Этот файл зашифрован, и на телефоне нет ключа SSH, которым его открыть. Расшифруйте его на компьютере."
        FileProblem.NotOpened -> "Файл не открылся."
        FileProblem.NoEntries -> "В файле не нашлось ни одного пароля."
    }
    override fun importIntro(found: Int, fresh: Int, differing: Int) = when {
        found == 0 -> "Не прочиталось ни одной записи."
        fresh == 0 && differing == 0 -> "Найдено ${entries(found)}, все уже есть на этом устройстве."
        fresh == found -> "Найдено ${entries(found)}. Отмеченные будут добавлены."
        else -> "Найдено ${entries(found)}, из них новых $fresh. Отмеченные будут сохранены."
    }
    override fun importAdd(add: Int, replace: Int) = when {
        replace == 0 -> "Добавить ${entries(add)}"
        add == 0 -> "Заменить ${entries(replace)}"
        else -> "Добавить $add, заменить $replace"
    }
    override fun importFailed(failed: ImportBatch.Failed) =
        "${failed.title.ifEmpty { "Строка ${failed.line}" }}: ${error(failed.error)}"

    private fun entries(n: Int): String = count(n, "запись", "записи", "записей")

    private fun count(n: Int, one: String, few: String, many: String): String {
        val word = when {
            n % 100 in 11..14 -> many
            n % 10 == 1 -> one
            n % 10 in 2..4 -> few
            else -> many
        }
        return "$n $word"
    }

    override val export = "Экспорт"
    override val exportTitle = "Экспорт"
    override fun exportIntro(entries: Int) =
        "Все пароли (сейчас их $entries) попадут в файл CSV, зашифрованный ключом SSH с GitHub. " +
            "Шифрование — здесь, на устройстве; открыть файл сможет только владелец закрытого ключа."
    override val savedKeys = "Сохранённые ключи"
    override val savedKeysHint = "Ключи других ваших устройств."
    override val githubAccount = "Аккаунт GitHub"
    override val githubHint = "Открытые ключи берутся с github.com/<имя>.keys"
    override val findKeys = "Найти ключи"
    override val findingKeys = "Ищу ключи…"
    override fun keysOf(login: String) = "Ключи github.com/$login"
    override val keysHint = "Файл откроет любой из отмеченных ключей."
    override fun keysError(error: KeysError) = when (error) {
        KeysError.BadLogin -> "Такого имени на GitHub не бывает"
        KeysError.NoSuchUser -> "На GitHub нет такого пользователя"
        KeysError.NoKeys -> "У этого аккаунта GitHub нет ключей SSH"
        KeysError.NoUsableKeys -> "Ни один ключ не подходит: age нужен ed25519 или RSA от 2048 бит"
        KeysError.RateLimited -> "GitHub ограничил запросы с этого адреса. Попробуйте через час."
        KeysError.Offline -> "GitHub недоступен"
        KeysError.Server -> "GitHub ответил ошибкой. Попробуйте позже."
    }
    override val unsupportedKey = "Этот тип ключа age не поддерживает"
    override val weakKey = "Слишком короткий ключ RSA"
    override val shareExport = "Зашифровать и отправить"
    override val copyExport = "Зашифровать и скопировать"
    override fun exportDone(done: ExportDone) = when (done.via) {
        ExportDone.Via.Shared -> "${done.fileName} зашифрован и передан"
        ExportDone.Via.Copied -> "Скопировано. Сохраните текст как ${done.fileName}"
    }
    override fun shareMessage(audience: String, command: String, transfer: String) =
        "Пароли из Unitool, зашифрованы для $audience.\nРасшифровать: $command\n" +
            "Перенести на другой телефон QR-кодом: $transfer"
    override val decryptTitle = "Как открыть на компьютере"
    override val decryptHint = "Пароли выводятся в терминал и на диск не пишутся. Добавьте > passwords.csv, чтобы импортировать их в браузер — и удалите файл после."
    override val tapToCopyCommand = "Нажмите на команду, чтобы скопировать"
    override val commandCopied = "Команда скопирована"
    override val transferTitle = "Перенести на другой телефон"
    override val transferHint = "Команда покажет пароли QR-кодом; на новом телефоне — «Импорт» → «Сканировать QR-код»."

    override fun error(error: DraftError) = when (error) {
        DraftError.NoPassword -> "Нет пароля"
        DraftError.NoName -> "Нужно название, сайт или логин"
        DraftError.NothingRead -> "Здесь не нашлось ни одной записи с паролем"
    }
    override fun storageProblem(problem: StorageProblem) = when (problem) {
        StorageProblem.Unreadable ->
            "Сохранённые пароли не расшифровываются на этом устройстве. Они не удалены и не будут перезаписаны."
        StorageProblem.Locked ->
            "Пароли заперты: без парольной фразы их не открыть. Они на месте — разблокируйте, чтобы увидеть."
        StorageProblem.NotSaved ->
            "Изменение не удалось зашифровать, поэтому оно не сохранено и пропадёт при закрытии. Пароли никогда не пишутся открытыми."
    }
    override val unlock = "Разблокировать"
}

internal object PasswordsStringsEn : PasswordsStrings {
    override val emptyTitle = "No passwords yet"
    override val emptyHint = "Add the first one, or import from a browser: Chrome, Firefox, Safari, Bitwarden and others save passwords as CSV."
    override val add = "Add"
    override val search = "Search"
    override val nothingFound = "Nothing found"
    override val copy = "Copy"
    override val copied = "Copied"
    override val details = "Details"

    override val newEntry = "New password"
    override val editEntry = "Edit"
    override val title = "Title"
    override val titleHint = "GitHub"
    override val username = "Username"
    override val password = "Password"
    override val generate = "Generate"
    override fun generateHint(rules: PasswordRules, bits: Int): String {
        val size = when (rules.mode) {
            PasswordMode.Characters -> "${rules.length} characters"
            PasswordMode.Words -> "${rules.words} words"
            PasswordMode.Mixed -> "${rules.words} words with digits and symbols between them"
        }
        return "$size · $bits bits of the device's entropy"
    }
    override val generatorSettings = "Settings"
    override fun mode(mode: PasswordMode) = when (mode) {
        PasswordMode.Characters -> "Characters"
        PasswordMode.Words -> "Words"
        PasswordMode.Mixed -> "Words and characters"
    }
    override val length = "Length"
    override val wordCount = "Words"
    override val lower = "Lower case a–z"
    override val upper = "Upper case A–Z"
    override val digits = "Digits 0–9"
    override val symbols = "Symbols ! # $ % & * + - = ? @ ^ _"
    override val lookAlike = "Look-alikes l, I, O, 0, 1"
    override val drawEntropy = "Add entropy by drawing"
    override val drawHint = "Move a finger or the mouse over the field — the longer and the less even, the more. The system's random stays the base: the drawing is mixed into it and cannot weaken the password."
    override fun drawProgress(bits: Int, target: Int, seconds: Long) =
        "≈ ${minOf(bits, target)} of $target bits" + (if (bits >= target) " — enough" else "") + " · ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    override fun withDrawing(bits: Int) = " + drawing ≈ $bits bits"
    override val drawDone = "Done"
    override val drawReset = "Reset"
    override val url = "Website"
    override val note = "Note"
    override val save = "Save"
    override val cancel = "Cancel"

    override val showPassword = "Show"
    override val hidePassword = "Hide"
    override val edit = "Edit"
    override val delete = "Delete"
    override val deleteConfirm = "Delete this entry for good? The password can't be recovered unless it is in an export."
    override val deleteForever = "Delete"
    override val back = "Back"
    override fun changed(date: String) = "Changed $date"

    override val confirmShow = "Show the password"
    override val confirmEdit = "Edit the password"
    override val confirmExport = "Export every password"
    override val confirmDelete = "Delete the password"

    override val import = "Import"
    override val importTitle = "Import"
    override val importIntroText = "A CSV file from a browser or a password manager, or a Unitool export shown as a QR code on a computer."
    override val scan = "Scan a QR code"
    override val scanHint = "A Unitool export, opened on a computer with the command from the export page."
    override val importFile = "Choose a file"
    override val importFileHint = "CSV from Chrome, Firefox, Safari, Bitwarden, KeePassXC, 1Password — or a Unitool .age export, if its SSH key is on this phone."
    override val orPaste = "Or paste CSV text"
    override val pasteHint = "The first line is the header: name,url,username,password,note"
    override val readText = "Read"
    override val alreadyHere = "Already on this device"
    override val passwordDiffers = "The password differs from the saved one"
    override val willReplace = "replaces the saved one"
    override val willAddBeside = "added as a second entry"
    override fun differing(count: Int) = if (count == 1) "1 entry has a password unlike the one on this device." else "$count entries have a password unlike the one on this device."
    override val keepBoth = "Keep both passwords"
    override val keepBothHint = "Off: the saved password is replaced with the one read. On: the one read is added beside it as a separate entry."
    override val more = "More"
    override fun filter(filter: ImportFilter) = when (filter) {
        ImportFilter.All -> "All"
        ImportFilter.New -> "New"
        ImportFilter.Differing -> "Other password"
        ImportFilter.Known -> "Already here"
    }
    override val selectAll = "Tick all"
    override val selectNone = "Untick all"
    override val scanMore = "Scan another"
    override val scanMoreHint = "A long export is shown as several codes: scan them all and the entries join one list."
    override fun fileProblem(problem: FileProblem) = when (problem) {
        FileProblem.NoKey -> "This file is encrypted, and no SSH key on this phone opens it. Decrypt it on the computer."
        FileProblem.NotOpened -> "The file didn't open."
        FileProblem.NoEntries -> "Not one password was found in the file."
    }
    override fun importIntro(found: Int, fresh: Int, differing: Int) = when {
        found == 0 -> "Not one entry could be read."
        fresh == 0 && differing == 0 -> "Found ${entries(found)}, all of them already on this device."
        fresh == found -> "Found ${entries(found)}. The ticked ones will be added."
        else -> "Found ${entries(found)}, $fresh of them new. The ticked ones will be saved."
    }
    override fun importAdd(add: Int, replace: Int) = when {
        replace == 0 -> "Add ${entries(add)}"
        add == 0 -> "Replace ${entries(replace)}"
        else -> "Add $add, replace $replace"
    }
    override fun importFailed(failed: ImportBatch.Failed) =
        "${failed.title.ifEmpty { "Line ${failed.line}" }}: ${error(failed.error)}"

    private fun entries(n: Int): String = if (n == 1) "1 entry" else "$n entries"

    override val export = "Export"
    override val exportTitle = "Export"
    override fun exportIntro(entries: Int) =
        "Every password ($entries right now) goes into a CSV file encrypted to an SSH key from GitHub. " +
            "It is encrypted here, on the device; only whoever holds the private key can open it."
    override val savedKeys = "Saved keys"
    override val savedKeysHint = "Keys of your other devices."
    override val githubAccount = "GitHub account"
    override val githubHint = "Public keys are read from github.com/<name>.keys"
    override val findKeys = "Find keys"
    override val findingKeys = "Finding keys…"
    override fun keysOf(login: String) = "Keys of github.com/$login"
    override val keysHint = "Any one of the ticked keys opens the file."
    override fun keysError(error: KeysError) = when (error) {
        KeysError.BadLogin -> "That cannot be a GitHub username"
        KeysError.NoSuchUser -> "GitHub has no user by that name"
        KeysError.NoKeys -> "This GitHub account has no SSH keys"
        KeysError.NoUsableKeys -> "None of the keys will do: age needs ed25519, or RSA of 2048 bits or more"
        KeysError.RateLimited -> "GitHub is limiting requests from this address. Try again in an hour."
        KeysError.Offline -> "Cannot reach GitHub"
        KeysError.Server -> "GitHub answered with an error. Try again later."
    }
    override val unsupportedKey = "age doesn't support this kind of key"
    override val weakKey = "RSA key too short"
    override val shareExport = "Encrypt and share"
    override val copyExport = "Encrypt and copy"
    override fun exportDone(done: ExportDone) = when (done.via) {
        ExportDone.Via.Shared -> "${done.fileName} is encrypted and handed to the share sheet"
        ExportDone.Via.Copied -> "Copied. Save the text as ${done.fileName}"
    }
    override fun shareMessage(audience: String, command: String, transfer: String) =
        "Passwords from Unitool, encrypted for $audience.\nTo decrypt: $command\n" +
            "To move them to another phone as a QR code: $transfer"
    override val decryptTitle = "How to open it on a computer"
    override val decryptHint = "The passwords go to the terminal, not to disk. Add > passwords.csv to import them into a browser — and delete the file afterwards."
    override val tapToCopyCommand = "Tap a command to copy it"
    override val commandCopied = "Command copied"
    override val transferTitle = "Move to another phone"
    override val transferHint = "The command shows the passwords as a QR code; on the new phone, Import → Scan a QR code."

    override fun error(error: DraftError) = when (error) {
        DraftError.NoPassword -> "No password"
        DraftError.NoName -> "Needs a title, a website or a username"
        DraftError.NothingRead -> "No entry with a password was found here"
    }
    override fun storageProblem(problem: StorageProblem) = when (problem) {
        StorageProblem.Unreadable ->
            "The saved passwords can't be decrypted on this device. They have not been deleted and won't be overwritten."
        StorageProblem.Locked ->
            "The passwords are locked: the host could not open them without the passphrase. They are still there — unlock them to see them."
        StorageProblem.NotSaved ->
            "The change couldn't be encrypted, so it wasn't saved and will be gone when the app closes. Passwords are never written unencrypted."
    }
    override val unlock = "Unlock"
}

internal val PasswordsStringTables: Map<String, PasswordsStrings> = mapOf(
    "ru" to PasswordsStringsRu,
    "en" to PasswordsStringsEn,
)
