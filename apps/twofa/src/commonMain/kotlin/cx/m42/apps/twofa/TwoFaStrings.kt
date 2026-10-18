package cx.m42.apps.twofa

/** The 2FA app's own words (D11). */
internal interface TwoFaStrings {
    val emptyTitle: String
    val emptyHint: String
    val add: String
    val search: String
    val nothingFound: String
    val copied: String
    val tapToCopy: String
    val details: String

    val newAccount: String
    val byLink: String
    val byKey: String
    val scan: String
    val scanHint: String
    val orPaste: String
    val link: String
    val linkHint: String
    val readLink: String
    val issuer: String
    val issuerHint: String
    val accountName: String
    val accountNameHint: String
    val secret: String
    val secretHint: String
    val advanced: String
    val algorithm: String
    val digits: String
    val period: String
    val save: String
    val cancel: String
    val preview: String

    val code: String
    val parameters: String
    val showSecret: String
    val hideSecret: String
    val rename: String
    val delete: String
    val deleteConfirm: String
    val deleteForever: String
    val back: String

    /** What the device's sheet says each confirmation is for (06 §5.5). */
    val confirmShowKey: String
    val confirmExport: String
    val confirmDelete: String

    val export: String
    val exportTitle: String
    val githubAccount: String
    val savedKeys: String
    val savedKeysHint: String
    val githubHint: String
    val findKeys: String
    val findingKeys: String
    val keysHint: String
    val unsupportedKey: String
    val weakKey: String
    val shareExport: String
    val copyExport: String
    val decryptTitle: String
    val decryptHint: String
    val tapToCopyCommand: String
    val commandCopied: String
    val transferTitle: String
    val transferHint: String

    val importTitle: String
    val alreadyHere: String
    val scanMore: String
    val scanMoreHint: String
    val importFile: String
    val importFileHint: String
    fun fileProblem(problem: FileProblem): String

    fun seconds(n: Int): String
    fun error(error: DraftError): String
    fun parametersLine(account: TotpAccount): String

    fun exportIntro(accounts: Int): String
    fun keysOf(login: String): String
    fun keysError(error: KeysError): String
    fun exportDone(done: ExportDone): String
    fun shareMessage(audience: String, command: String, transfer: String): String

    fun importIntro(found: Int, addable: Int): String
    fun importAdd(count: Int): String
    fun importFailed(failed: ImportBatch.Failed): String
    fun transferParts(transfer: ImportBatch.Transfer): String

    fun storageProblem(problem: StorageProblem): String
    val unlock: String
}

internal object TwoFaStringsRu : TwoFaStrings {
    override val emptyTitle = "Пока нет аккаунтов"
    override val emptyHint =
        "Включите двухфакторную защиту на сайте и добавьте сюда его ключ — ссылкой otpauth:// или вручную."
    override val add = "Добавить аккаунт"
    override val search = "Поиск"
    override val nothingFound = "Ничего не нашлось"
    override val copied = "Код скопирован"
    override val tapToCopy = "Нажмите на аккаунт, чтобы показать и скопировать код"
    override val details = "Подробнее"

    override val newAccount = "Новый аккаунт"
    override val byLink = "Ссылка"
    override val byKey = "Ключ вручную"
    override val scan = "Сканировать QR-код"
    override val scanHint =
        "Откроется камера — наведите её на QR-код, который показывает сайт, или на QR-код переноса из Google Authenticator."
    override val orPaste = "Или вставьте ссылку"
    override val link = "Ссылка otpauth://"
    override val linkHint =
        "Её содержит QR-код, который показывает сайт. Вставьте текст ссылки целиком — или сразу несколько, по одной на строку."
    override val readLink = "Прочитать ссылку"
    override val issuer = "Сервис"
    override val issuerHint = "Например, GitHub"
    override val accountName = "Аккаунт"
    override val accountNameHint = "Например, почта"
    override val secret = "Секретный ключ"
    override val secretHint = "Base32: буквы A–Z и цифры 2–7"
    override val advanced = "Параметры"
    override val algorithm = "Алгоритм"
    override val digits = "Цифр в коде"
    override val period = "Период, секунд"
    override val save = "Сохранить"
    override val cancel = "Отмена"
    override val preview = "Текущий код"

    override val code = "Код"
    override val parameters = "Параметры"
    override val showSecret = "Показать ключ"
    override val hideSecret = "Скрыть ключ"
    override val rename = "Переименовать"
    override val delete = "Удалить"
    override val deleteConfirm =
        "Аккаунт исчезнет с этого устройства. Если ключа больше нигде нет, войти на сайт без резервных кодов не получится."
    override val deleteForever = "Удалить навсегда"
    override val confirmShowKey = "Показать ключ аккаунта"
    override val confirmExport = "Экспорт всех ключей"
    override val confirmDelete = "Удалить аккаунт"
    override val back = "Назад"

    override val export = "Экспорт ключей"
    override val exportTitle = "Экспорт ключей"
    override val githubAccount = "Аккаунт GitHub"
    override val savedKeys = "Сохранённые ключи"
    override val savedKeysHint = "Открытые ключи других устройств, сохранённые в Unitool. Открыть файл сможет только устройство с отмеченным ключом — в 2FA через «Импорт из файла»."
    override val githubHint = "Имя пользователя. Приложение возьмёт открытые SSH-ключи, которые он выложил на GitHub."
    override val findKeys = "Найти ключи"
    override val findingKeys = "Спрашиваем GitHub…"
    override val keysHint =
        "Файл откроет любой отмеченный ключ. Сверьте отпечаток с выводом ssh-keygen -lf ~/.ssh/id_ed25519.pub"
    override val unsupportedKey = "age не шифрует для ключей этого типа"
    override val weakKey = "RSA короче 2048 бит — age такой не примет"
    override val shareExport = "Зашифровать и поделиться"
    override val copyExport = "Скопировать зашифрованный текст"
    override val decryptTitle = "Как расшифровать на компьютере"
    override val decryptHint =
        "Нужна утилита age: brew install age, sudo apt install age или winget install FiloSottile.age. " +
            "Команда выведет ссылки otpauth:// — по одной на аккаунт; их примет любое приложение-аутентификатор."
    override val tapToCopyCommand = "Нажмите на команду, чтобы скопировать"
    override val commandCopied = "Команда скопирована"
    override val transferTitle = "Как перенести на другой телефон"
    override val transferHint =
        "Команда расшифрует файл и покажет в терминале QR-код со всеми аккаунтами. На новом телефоне: " +
            "«Добавить аккаунт» → «Сканировать QR-код» — и все они добавятся одной кнопкой. " +
            "Нужен qrencode: brew install qrencode или sudo apt install qrencode. " +
            "Код крупный: разверните терминал и уменьшайте шрифт, пока он не поместится целиком."

    override val importTitle = "Импорт аккаунтов"
    override val alreadyHere = "Уже есть на этом устройстве"
    override val scanMore = "Сканировать ещё QR-код"
    override val importFile = "Импорт из файла"
    override val importFileHint = "Файл экспорта (.age) откроется вашим SSH-ключом, добавленным на это устройство; файл, уже расшифрованный на компьютере, читается как есть."
    override fun fileProblem(problem: FileProblem) = when (problem) {
        FileProblem.NoKey -> "Ни один SSH-ключ здесь не открывает этот файл. Сначала добавьте на это устройство ключ, на который он зашифрован."
        FileProblem.NotOpened -> "Файл не удалось открыть."
        FileProblem.NoAccounts -> "В этом файле нет аккаунтов."
    }
    override val scanMoreHint =
        "Если аккаунты показаны несколькими QR-кодами, отсканируйте следующий — он добавится к этому списку."

    override fun seconds(n: Int): String = "$n с"

    override fun error(error: DraftError): String = when (error) {
        DraftError.EmptyName -> "Укажите сервис или аккаунт"
        DraftError.EmptySecret -> "Введите секретный ключ"
        DraftError.BadSecret -> "Ключ не похож на Base32: допустимы буквы A–Z и цифры 2–7"
        DraftError.NotOtpAuth -> "Это не ссылка otpauth://totp/…"
        DraftError.Hotp -> "Коды по счётчику (HOTP) пока не поддерживаются"
        DraftError.Migration -> "Не удалось прочитать QR-код переноса из Google Authenticator. Отсканируйте его ещё раз."
        DraftError.BadAlgorithm -> "Такой алгоритм не поддерживается: только SHA1, SHA256 и SHA512"
        DraftError.BadDigits -> "В коде может быть от 6 до 8 цифр"
        DraftError.BadPeriod -> "Период — от 1 до 300 секунд"
    }

    override fun parametersLine(account: TotpAccount): String =
        "${account.algorithm.name} · ${account.digits} цифр · ${account.period} с"

    override fun exportIntro(accounts: Int): String =
        "Ключи всех аккаунтов (сейчас их $accounts) попадут в файл, зашифрованный SSH-ключом с GitHub. " +
            "Шифрование — здесь, на устройстве; открыть файл сможет только владелец закрытого ключа."

    override fun keysOf(login: String): String = "Ключи github.com/$login"

    override fun keysError(error: KeysError): String = when (error) {
        KeysError.BadLogin -> "Такого имени на GitHub быть не может"
        KeysError.NoSuchUser -> "На GitHub нет пользователя с таким именем"
        KeysError.NoKeys -> "У этого аккаунта на GitHub нет SSH-ключей"
        KeysError.NoUsableKeys -> "Ни один ключ не подходит: нужен ed25519 или RSA от 2048 бит"
        KeysError.RateLimited -> "GitHub временно ограничил запросы с этого адреса. Попробуйте через час."
        KeysError.Offline -> "Нет связи с GitHub"
        KeysError.Server -> "GitHub ответил ошибкой. Попробуйте позже."
    }

    override fun exportDone(done: ExportDone): String = when (done.via) {
        ExportDone.Via.Shared -> "Файл ${done.fileName} зашифрован и передан в меню «Поделиться»"
        ExportDone.Via.Copied -> "Скопировано. Сохраните текст в файл ${done.fileName}"
    }

    override fun shareMessage(audience: String, command: String, transfer: String): String =
        "Ключи 2FA из Unitool, зашифрованы для: $audience.\nРасшифровать: $command\n" +
            "Перенести на другой телефон одним QR-кодом: $transfer"

    override fun importIntro(found: Int, addable: Int): String = when {
        found == 0 -> "Ни одного аккаунта прочитать не удалось."
        addable == 0 -> "Найдено: ${accounts(found)} — все уже есть на этом устройстве."
        addable == found -> "Найдено: ${accounts(found)}. Отмеченные будут добавлены."
        else -> "Найдено: ${accounts(found)}, из них новых — $addable. Отмеченные будут добавлены."
    }

    override fun importAdd(count: Int): String = "Добавить ${accounts(count)}"

    override fun importFailed(failed: ImportBatch.Failed): String =
        "${failed.title.ifEmpty { "Ссылка ${failed.position}" }}: ${error(failed.error)}"

    override fun transferParts(transfer: ImportBatch.Transfer): String =
        if (transfer.scanned.size < transfer.size) {
            val codes = if (transfer.size % 10 == 1 && transfer.size % 100 != 11) "QR-кода" else "QR-кодов"
            "Google Authenticator: отсканировано ${transfer.scanned.size} из ${transfer.size} $codes. " +
                "Отсканируйте остальные — их аккаунты добавятся к этому списку."
        } else {
            "Google Authenticator: отсканированы все QR-коды переноса (${transfer.size})."
        }

    /** 1 аккаунт, 2 аккаунта, 5 аккаунтов, 11 аккаунтов, 21 аккаунт. */
    private fun accounts(n: Int): String {
        val word = when {
            n % 100 in 11..14 -> "аккаунтов"
            n % 10 == 1 -> "аккаунт"
            n % 10 in 2..4 -> "аккаунта"
            else -> "аккаунтов"
        }
        return "$n $word"
    }

    override fun storageProblem(problem: StorageProblem): String = when (problem) {
        StorageProblem.Unreadable ->
            "Сохранённые аккаунты не удаётся расшифровать на этом устройстве. Они не удалены и не будут " +
                "перезаписаны. Так бывает, если данные приложения перенесли на другой телефон: ключ, " +
                "которым они зашифрованы, не покидает защищённое хранилище старого."
        StorageProblem.Locked ->
            "Аккаунты заперты: хост не смог их открыть без пароля. Они на месте — откройте их, чтобы " +
                "увидеть коды."
        StorageProblem.NotSaved ->
            "Не удалось зашифровать изменения, поэтому они не сохранены и пропадут, когда приложение " +
                "закроется. Незашифрованными ключи не записываются никогда."
    }

    override val unlock = "Открыть"
}

internal object TwoFaStringsEn : TwoFaStrings {
    override val emptyTitle = "No accounts yet"
    override val emptyHint =
        "Turn on two-factor authentication on a site and add its key here — as an otpauth:// link or by hand."
    override val add = "Add account"
    override val search = "Search"
    override val nothingFound = "Nothing found"
    override val copied = "Code copied"
    override val tapToCopy = "Tap an account to show and copy its code"
    override val details = "Details"

    override val newAccount = "New account"
    override val byLink = "Link"
    override val byKey = "Enter key"
    override val scan = "Scan QR code"
    override val scanHint = "The camera opens — point it at the QR code the site shows, or at Google Authenticator's transfer code."
    override val orPaste = "Or paste the link"
    override val link = "otpauth:// link"
    override val linkHint = "It is what the site's QR code contains. Paste the whole link — or several, one per line."
    override val readLink = "Read link"
    override val issuer = "Service"
    override val issuerHint = "e.g. GitHub"
    override val accountName = "Account"
    override val accountNameHint = "e.g. your email"
    override val secret = "Secret key"
    override val secretHint = "Base32: letters A–Z and digits 2–7"
    override val advanced = "Parameters"
    override val algorithm = "Algorithm"
    override val digits = "Digits"
    override val period = "Period, seconds"
    override val save = "Save"
    override val cancel = "Cancel"
    override val preview = "Current code"

    override val code = "Code"
    override val parameters = "Parameters"
    override val showSecret = "Show key"
    override val hideSecret = "Hide key"
    override val rename = "Rename"
    override val delete = "Delete"
    override val deleteConfirm =
        "The account disappears from this device. If the key is nowhere else, you will need backup codes to sign in."
    override val deleteForever = "Delete for good"
    override val confirmShowKey = "Show the account's key"
    override val confirmExport = "Export every key"
    override val confirmDelete = "Delete the account"
    override val back = "Back"

    override val export = "Export keys"
    override val exportTitle = "Export keys"
    override val githubAccount = "GitHub account"
    override val savedKeys = "Saved keys"
    override val savedKeysHint = "Other devices' public keys, saved in Unitool. Only the device holding a ticked key opens the file — in 2FA, through “Import from a file”."
    override val githubHint = "A username. The app takes the public SSH keys that user has put on GitHub."
    override val findKeys = "Find keys"
    override val findingKeys = "Asking GitHub…"
    override val keysHint =
        "Any ticked key opens the file. Compare the fingerprint with ssh-keygen -lf ~/.ssh/id_ed25519.pub"
    override val unsupportedKey = "age cannot encrypt to this key type"
    override val weakKey = "RSA under 2048 bits — age refuses it"
    override val shareExport = "Encrypt and share"
    override val copyExport = "Copy encrypted text"
    override val decryptTitle = "How to decrypt on a computer"
    override val decryptHint =
        "You need age: brew install age, sudo apt install age or winget install FiloSottile.age. " +
            "The command prints the otpauth:// links, one per account; any authenticator app takes them."
    override val tapToCopyCommand = "Tap a command to copy it"
    override val commandCopied = "Command copied"
    override val transferTitle = "How to move them to another phone"
    override val transferHint =
        "The command decrypts the file and shows one QR code with every account in the terminal. On the new " +
            "phone: Add account → Scan QR code — and they are all added with one button. " +
            "You need qrencode: brew install qrencode or sudo apt install qrencode. " +
            "The code is large: maximise the terminal and shrink the font until it fits whole."

    override val importTitle = "Import accounts"
    override val alreadyHere = "Already on this device"
    override val scanMore = "Scan another QR code"
    override val importFile = "Import from a file"
    override val importFileHint = "The export file (.age) opens with your SSH key added on this device; a file already opened on a computer is read as it is."
    override fun fileProblem(problem: FileProblem) = when (problem) {
        FileProblem.NoKey -> "None of the SSH keys here opens this file. Add the key it was encrypted to on this device first."
        FileProblem.NotOpened -> "The file could not be opened."
        FileProblem.NoAccounts -> "There are no accounts in this file."
    }
    override val scanMoreHint = "If the accounts come as several QR codes, scan the next one — it joins this list."

    override fun seconds(n: Int): String = "${n}s"

    override fun error(error: DraftError): String = when (error) {
        DraftError.EmptyName -> "Name the service or the account"
        DraftError.EmptySecret -> "Enter the secret key"
        DraftError.BadSecret -> "That is not Base32: only letters A–Z and digits 2–7"
        DraftError.NotOtpAuth -> "That is not an otpauth://totp/… link"
        DraftError.Hotp -> "Counter-based codes (HOTP) are not supported yet"
        DraftError.Migration -> "This Google Authenticator transfer code could not be read. Scan it again."
        DraftError.BadAlgorithm -> "That algorithm is not supported: only SHA1, SHA256 and SHA512"
        DraftError.BadDigits -> "A code has 6 to 8 digits"
        DraftError.BadPeriod -> "The period is 1 to 300 seconds"
    }

    override fun parametersLine(account: TotpAccount): String =
        "${account.algorithm.name} · ${account.digits} digits · ${account.period}s"

    override fun exportIntro(accounts: Int): String =
        "The keys of every account ($accounts right now) go into a file encrypted to an SSH key from GitHub. " +
            "It is encrypted here, on the device; only whoever holds the private key can open it."

    override fun keysOf(login: String): String = "Keys of github.com/$login"

    override fun keysError(error: KeysError): String = when (error) {
        KeysError.BadLogin -> "That cannot be a GitHub username"
        KeysError.NoSuchUser -> "GitHub has no user by that name"
        KeysError.NoKeys -> "This GitHub account has no SSH keys"
        KeysError.NoUsableKeys -> "None of the keys will do: age needs ed25519, or RSA of 2048 bits or more"
        KeysError.RateLimited -> "GitHub is limiting requests from this address. Try again in an hour."
        KeysError.Offline -> "Cannot reach GitHub"
        KeysError.Server -> "GitHub answered with an error. Try again later."
    }

    override fun exportDone(done: ExportDone): String = when (done.via) {
        ExportDone.Via.Shared -> "${done.fileName} is encrypted and handed to the share sheet"
        ExportDone.Via.Copied -> "Copied. Save the text as ${done.fileName}"
    }

    override fun shareMessage(audience: String, command: String, transfer: String): String =
        "2FA keys from Unitool, encrypted for $audience.\nTo decrypt: $command\n" +
            "To move them to another phone as one QR code: $transfer"

    override fun importIntro(found: Int, addable: Int): String = when {
        found == 0 -> "Not one account could be read."
        addable == 0 -> "Found ${accounts(found)}, all of them already on this device."
        addable == found -> "Found ${accounts(found)}. The ticked ones will be added."
        else -> "Found ${accounts(found)}, $addable of them new. The ticked ones will be added."
    }

    override fun importAdd(count: Int): String = "Add ${accounts(count)}"

    override fun importFailed(failed: ImportBatch.Failed): String =
        "${failed.title.ifEmpty { "Link ${failed.position}" }}: ${error(failed.error)}"

    override fun transferParts(transfer: ImportBatch.Transfer): String =
        if (transfer.scanned.size < transfer.size) {
            "Google Authenticator: ${transfer.scanned.size} of ${transfer.size} QR codes scanned. Scan the rest — their accounts join this list."
        } else {
            "Google Authenticator: all ${transfer.size} transfer QR codes scanned."
        }

    private fun accounts(n: Int): String = if (n == 1) "1 account" else "$n accounts"

    override fun storageProblem(problem: StorageProblem): String = when (problem) {
        StorageProblem.Unreadable ->
            "The saved accounts can't be decrypted on this device. They have not been deleted and won't " +
                "be overwritten. This happens when the app's data is moved to another phone: the key they " +
                "were encrypted with never leaves the old phone's secure storage."
        StorageProblem.Locked ->
            "The accounts are locked: the host could not open them without the passphrase. They are " +
                "still there — unlock them to see the codes."
        StorageProblem.NotSaved ->
            "The change couldn't be encrypted, so it wasn't saved and will be gone when the app closes. " +
                "Keys are never written unencrypted."
    }

    override val unlock = "Unlock"
}

internal val TwoFaStringTables: Map<String, TwoFaStrings> = mapOf(
    "ru" to TwoFaStringsRu,
    "en" to TwoFaStringsEn,
)
