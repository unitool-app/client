package cx.m42.apps.crrestore

import cx.m42.unitool.kit.split.SplitProblem

/** The app's own words (D11, D602). */
internal interface CrRestoreStrings {
    val chooseTitle: String
    val ageTitle: String
    val ageHint: String
    val opensslTitle: String
    val opensslHint: String
    val splitTitle: String
    val splitHint: String

    val scan: String
    val scanMore: String
    val openFile: String
    val paste: String
    val pasteHere: String
    val add: String
    val decrypt: String
    val decrypting: String
    val cancel: String
    val startOver: String
    val back: String

    // age
    val ageFor: String
    val agePhoneKey: String
    val ageKey: String
    val ageKeyHint: String
    val ageKeyScan: String
    val ageKeyFile: String
    val ageKeyPassphrase: String
    val agePassphrase: String
    fun ageRecipientSsh(type: String, tag: String): String
    val ageRecipientX25519: String
    fun ageRecipientScrypt(logN: Int): String
    fun ageRecipientOther(type: String): String
    fun ageSlow(logN: Int): String

    // openssl
    val password: String
    val showPassword: String
    val hidePassword: String
    val rawKey: String
    val keyHex: String
    val ivHex: String
    val iterations: String
    val iterationsHint: String
    val more: String
    val less: String
    val cipher: String
    val kdf: String
    val kdfPbkdf2: String
    val kdfLegacy: String
    val digest: String
    val salt: String
    val saltInFile: String
    val saltNone: String
    val noPadding: String
    val commandHint: String

    // split
    val container: String
    val containerMissing: String
    fun containerReady(k: Int, n: Int): String
    fun partsGathered(have: Int, need: Int?): String
    val partAgain: String
    val partOtherSet: String

    // input
    fun codesRead(codes: Int, chars: Int): String
    fun fileRead(name: String, bytes: Int): String
    fun looksLike(method: String): String
    val openThere: String
    val notRecognised: String

    // result
    val resultTitle: String
    fun hidden(chars: Int): String
    val tapToShow: String
    val tapToHide: String
    val copy: String
    val copied: String
    val save: String
    val saveAsFile: String
    val saved: String
    val notSaved: String
    fun fileResult(name: String, bytes: Int): String
    val uncheckedWarning: String
    fun disagreeingWarning(numbers: List<Int>): String

    // problems
    fun problem(problem: Problem): String
    fun splitProblem(problem: SplitProblem): String
}

internal object CrRestoreStringsEn : CrRestoreStrings {
    override val chooseTitle = "How was it encrypted?"
    override val ageTitle = "age"
    override val ageHint = "A file from age: with this phone's SSH key, your own key, or a passphrase"
    override val opensslTitle = "openssl"
    override val opensslHint = "openssl enc: a password, or a key and IV"
    override val splitTitle = "split-qr"
    override val splitHint = "A secret split into QR-code parts and a container"

    override val scan = "Scan QR"
    override val scanMore = "Scan the next QR"
    override val openFile = "Open file"
    override val paste = "Paste"
    override val pasteHere = "Or paste the text here"
    override val add = "Add"
    override val decrypt = "Decrypt"
    override val decrypting = "Decrypting…"
    override val cancel = "Cancel"
    override val startOver = "Start over"
    override val back = "Back"

    override val ageFor = "Encrypted for"
    override val agePhoneKey = "Open with this phone's key"
    override val ageKey = "Key"
    override val ageKeyHint = "AGE-SECRET-KEY-1… or a private SSH key"
    override val ageKeyScan = "Scan key"
    override val ageKeyFile = "Key file"
    override val ageKeyPassphrase = "Key passphrase (if it has one)"
    override val agePassphrase = "Passphrase"
    override fun ageRecipientSsh(type: String, tag: String) = "$type · tag $tag"
    override val ageRecipientX25519 = "an age key (X25519)"
    override fun ageRecipientScrypt(logN: Int) = "a passphrase (scrypt, work 2^$logN)"
    override fun ageRecipientOther(type: String) = "$type — needs a plugin, which does not run on a phone"
    override fun ageSlow(logN: Int) = "Work 2^$logN: this takes a while and much memory."

    override val password = "Password"
    override val showPassword = "Show"
    override val hidePassword = "Hide"
    override val rawKey = "Key and IV in hex (-K, -iv)"
    override val keyHex = "Key, hex"
    override val ivHex = "IV, hex"
    override val iterations = "Iterations"
    override val iterationsHint = "The same number as -iter; 10000 when it was not given"
    override val more = "More ▸"
    override val less = "Less ▾"
    override val cipher = "Cipher"
    override val kdf = "Key from the password"
    override val kdfPbkdf2 = "PBKDF2 (-pbkdf2)"
    override val kdfLegacy = "Old, without -pbkdf2"
    override val digest = "Digest (-md)"
    override val salt = "Salt"
    override val saltInFile = "In the file"
    override val saltNone = "None (-nosalt)"
    override val noPadding = "No padding (-nopad)"
    override val commandHint = "Encrypt on a computer with:"

    override val container = "Container"
    override val containerMissing = "Container: not yet"
    override fun containerReady(k: Int, n: Int) = "Container ✓ — takes $k of $n parts"
    override fun partsGathered(have: Int, need: Int?) = if (need == null) "Parts: $have" else "Parts: $have of $need"
    override val partAgain = "This part is already here"
    override val partOtherSet = "This part is from another secret"

    override fun codesRead(codes: Int, chars: Int) = "$codes QR, $chars characters"
    override fun fileRead(name: String, bytes: Int) = "$name, $bytes bytes"
    override fun looksLike(method: String) = "This looks like $method."
    override val openThere = "Open there"
    override val notRecognised = "This is neither a part nor a container"

    override val resultTitle = "Decrypted"
    override fun hidden(chars: Int) = "•••• $chars characters"
    override val tapToShow = "Tap to show"
    override val tapToHide = "Tap to hide"
    override val copy = "Copy"
    override val copied = "Copied. The clipboard is cleared in a minute."
    override val save = "Save file"
    override val saveAsFile = "Save as a file"
    override val saved = "Saved"
    override val notSaved = "Not saved"
    override fun fileResult(name: String, bytes: Int) = "File $name, $bytes bytes"
    override val uncheckedWarning = "openssl enc does not check integrity: if this is not what you expected, the password or settings are wrong."
    override fun disagreeingWarning(numbers: List<Int>) = "Part ${numbers.joinToString(", ")} did not fit the others — it may have been changed."

    override fun problem(problem: Problem): String = when (problem) {
        is Problem.NotThisFormat -> problem.looksLike?.let { "Not this format. ${looksLike(it.name.lowercase())}" } ?: "This is not what this method opens."
        Problem.WrongKey -> "That did not open it: a wrong password, key or setting."
        Problem.Damaged -> "The file is damaged."
        Problem.Newer -> "Made by a newer Unitool."
        Problem.NoKeyOnPhone -> "Not encrypted for this phone's keys."
        Problem.KeyUnusable -> "The key is there but cannot be used now."
        Problem.Cancelled -> "Cancelled."
        Problem.Busy -> "The key is busy; try again."
        Problem.NothingToOpenWith -> "Give a key or a passphrase."
        Problem.ScryptNotAlone -> "A passphrase file with other recipients: age refuses it, and so does this."
        is Problem.TooHard -> "Work 2^${problem.logN} is more than this phone can take."
        is Problem.Plugin -> "Needs ${problem.type}, a plugin that does not run on a phone."
        Problem.BadKey -> "That is not a key age can use."
        Problem.KeyNeedsPassphrase -> "The key has a passphrase: enter it."
        Problem.KeyPassphraseWrong -> "Wrong key passphrase."
        is Problem.Unsupported -> "Not supported here: ${problem.what}."
        Problem.NoContainer -> "Add the container."
        is Problem.Parts -> splitProblem(problem.problem)
        Problem.PartsDoNotMatch -> "The parts do not fit this container."
    }

    override fun splitProblem(problem: SplitProblem): String = when (problem) {
        is SplitProblem.OtherSet -> "Part ${problem.index + 1} is from another secret."
        is SplitProblem.Inconsistent -> "Part ${problem.index + 1} is damaged."
        is SplitProblem.SameNumber -> "Parts ${problem.earlier + 1} and ${problem.index + 1} are the same part."
        is SplitProblem.TooFew -> "${problem.have} of ${problem.need} parts."
    }
}

internal object CrRestoreStringsRu : CrRestoreStrings {
    override val chooseTitle = "Чем зашифровано?"
    override val ageTitle = "age"
    override val ageHint = "Файл age: SSH-ключом этого телефона, своим ключом или паролем"
    override val opensslTitle = "openssl"
    override val opensslHint = "openssl enc: пароль или ключ и IV"
    override val splitTitle = "split-qr"
    override val splitHint = "Секрет, разделённый на части-QR, и контейнер"

    override val scan = "Сканировать QR"
    override val scanMore = "Сканировать следующий QR"
    override val openFile = "Открыть файл"
    override val paste = "Вставить"
    override val pasteHere = "Или вставьте текст сюда"
    override val add = "Добавить"
    override val decrypt = "Расшифровать"
    override val decrypting = "Расшифровываю…"
    override val cancel = "Отменить"
    override val startOver = "Начать заново"
    override val back = "Назад"

    override val ageFor = "Зашифровано для"
    override val agePhoneKey = "Открыть ключом этого телефона"
    override val ageKey = "Ключ"
    override val ageKeyHint = "AGE-SECRET-KEY-1… или закрытый SSH-ключ"
    override val ageKeyScan = "Сканировать ключ"
    override val ageKeyFile = "Файл ключа"
    override val ageKeyPassphrase = "Пароль ключа (если есть)"
    override val agePassphrase = "Пароль"
    override fun ageRecipientSsh(type: String, tag: String) = "$type · метка $tag"
    override val ageRecipientX25519 = "ключ age (X25519)"
    override fun ageRecipientScrypt(logN: Int) = "пароль (scrypt, сложность 2^$logN)"
    override fun ageRecipientOther(type: String) = "$type — нужен плагин, на телефоне он не работает"
    override fun ageSlow(logN: Int) = "Сложность 2^$logN: это долго и требует много памяти."

    override val password = "Пароль"
    override val showPassword = "Показать"
    override val hidePassword = "Скрыть"
    override val rawKey = "Ключ и IV в hex (-K, -iv)"
    override val keyHex = "Ключ, hex"
    override val ivHex = "IV, hex"
    override val iterations = "Итерации"
    override val iterationsHint = "То же число, что в -iter; 10000, если его не указывали"
    override val more = "Ещё ▸"
    override val less = "Меньше ▾"
    override val cipher = "Шифр"
    override val kdf = "Ключ из пароля"
    override val kdfPbkdf2 = "PBKDF2 (-pbkdf2)"
    override val kdfLegacy = "Старый, без -pbkdf2"
    override val digest = "Дайджест (-md)"
    override val salt = "Соль"
    override val saltInFile = "В файле"
    override val saltNone = "Нет (-nosalt)"
    override val noPadding = "Без паддинга (-nopad)"
    override val commandHint = "Шифровать на компьютере так:"

    override val container = "Контейнер"
    override val containerMissing = "Контейнер: ещё нет"
    override fun containerReady(k: Int, n: Int) = "Контейнер ✓ — нужно $k из $n частей"
    override fun partsGathered(have: Int, need: Int?) = if (need == null) "Части: $have" else "Части: $have из $need"
    override val partAgain = "Эта часть уже есть"
    override val partOtherSet = "Эта часть от другого секрета"

    override fun codesRead(codes: Int, chars: Int) = "QR: $codes, знаков: $chars"
    override fun fileRead(name: String, bytes: Int) = "$name, байт: $bytes"
    override fun looksLike(method: String) = "Похоже на $method."
    override val openThere = "Открыть там"
    override val notRecognised = "Это не часть и не контейнер"

    override val resultTitle = "Расшифровано"
    override fun hidden(chars: Int) = "•••• знаков: $chars"
    override val tapToShow = "Нажмите, чтобы показать"
    override val tapToHide = "Нажмите, чтобы скрыть"
    override val copy = "Копировать"
    override val copied = "Скопировано. Буфер очистится через минуту."
    override val save = "Сохранить файл"
    override val saveAsFile = "Сохранить файлом"
    override val saved = "Сохранено"
    override val notSaved = "Не сохранено"
    override fun fileResult(name: String, bytes: Int) = "Файл $name, байт: $bytes"
    override val uncheckedWarning = "openssl enc не проверяет целостность: если результат не тот, неверны пароль или параметры."
    override fun disagreeingWarning(numbers: List<Int>) = "Часть ${numbers.joinToString(", ")} не подошла к остальным — возможно, подменена."

    override fun problem(problem: Problem): String = when (problem) {
        is Problem.NotThisFormat -> problem.looksLike?.let { "Не этот формат. ${looksLike(it.name.lowercase())}" } ?: "Это не то, что открывает этот способ."
        Problem.WrongKey -> "Не подошло: неверный пароль, ключ или параметр."
        Problem.Damaged -> "Файл повреждён."
        Problem.Newer -> "Сделано более новым Unitool."
        Problem.NoKeyOnPhone -> "Зашифровано не для ключей этого телефона."
        Problem.KeyUnusable -> "Ключ есть, но сейчас недоступен."
        Problem.Cancelled -> "Отменено."
        Problem.Busy -> "Ключ занят, попробуйте ещё раз."
        Problem.NothingToOpenWith -> "Нужен ключ или пароль."
        Problem.ScryptNotAlone -> "Файл с паролем и другими получателями: age его не открывает, и мы тоже."
        is Problem.TooHard -> "Сложность 2^${problem.logN} больше, чем выдержит телефон."
        is Problem.Plugin -> "Нужен ${problem.type} — плагин, на телефоне он не работает."
        Problem.BadKey -> "Это не ключ, который понимает age."
        Problem.KeyNeedsPassphrase -> "У ключа есть пароль: введите его."
        Problem.KeyPassphraseWrong -> "Неверный пароль ключа."
        is Problem.Unsupported -> "Здесь не поддерживается: ${problem.what}."
        Problem.NoContainer -> "Добавьте контейнер."
        is Problem.Parts -> splitProblem(problem.problem)
        Problem.PartsDoNotMatch -> "Части не подходят к этому контейнеру."
    }

    override fun splitProblem(problem: SplitProblem): String = when (problem) {
        is SplitProblem.OtherSet -> "Часть ${problem.index + 1} — от другого секрета."
        is SplitProblem.Inconsistent -> "Часть ${problem.index + 1} повреждена."
        is SplitProblem.SameNumber -> "Части ${problem.earlier + 1} и ${problem.index + 1} — одна и та же."
        is SplitProblem.TooFew -> "Частей ${problem.have} из ${problem.need}."
    }
}

internal val CrRestoreStringTables: Map<String, CrRestoreStrings> = mapOf("ru" to CrRestoreStringsRu, "en" to CrRestoreStringsEn)
