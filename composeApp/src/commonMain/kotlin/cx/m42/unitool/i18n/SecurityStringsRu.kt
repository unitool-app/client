package cx.m42.unitool.i18n

import cx.m42.services.ssh.keyring.UseReason
import cx.m42.superizer.ssh.ImportRefusal
import cx.m42.superizer.ssh.KeyOperation
import cx.m42.superizer.ssh.KeyPurpose

internal object SecurityStringsRu : SecurityStrings {
    override val cancel = "Отмена"
    override val allow = "Разрешить"
    override val deny = "Не разрешать"
    override val unlock = "Открыть"
    override val confirm = "Подтвердить"
    override val passphrase = "Пароль"
    override val wrongPassphrase = "Неверный пароль. Попробуйте ещё раз."

    override fun grantTitle(app: String, purpose: KeyPurpose) = when (purpose) {
        is KeyPurpose.Sign -> "Разрешить «$app» подписывать вашим SSH-ключом?"
        KeyPurpose.Decrypt -> "Разрешить «$app» расшифровывать файлы вашим SSH-ключом?"
    }

    override fun grantBody(key: String, purpose: KeyPurpose) = when (purpose) {
        is KeyPurpose.Sign -> when (purpose.namespace) {
            "git" -> "$key\nПодписи коммитов git от вашего имени. Каждую подпись вы всё равно подтвердите."
            "file" -> "$key\nПодписи файлов от вашего имени. Каждую подпись вы всё равно подтвердите."
            else -> "$key\nПодписи для «${purpose.namespace}». Каждую подпись вы всё равно подтвердите."
        }
        KeyPurpose.Decrypt -> "$key\nФайлы, зашифрованные на этот ключ. Каждую расшифровку вы всё равно подтвердите."
    }

    override fun chooseTitle(app: String, purpose: KeyPurpose) = when (purpose) {
        is KeyPurpose.Sign -> "Каким ключом «$app» может подписывать?"
        KeyPurpose.Decrypt -> "Каким ключом «$app» может расшифровывать?"
    }

    override fun operation(reason: UseReason, app: String?): String {
        val who = app?.let { " · приложение «$it»" } ?: ""
        val what = when (reason.operation) {
            KeyOperation.Sign -> "Подписать" + (reason.namespace?.let { " для «$it»" } ?: "")
            KeyOperation.Decrypt -> "Расшифровать"
            KeyOperation.AgentSign -> "Подписать вход по SSH"
            KeyOperation.Import -> "Добавить ключ"
            KeyOperation.Generate -> "Создать ключ"
            KeyOperation.Delete -> "Удалить ключ"
            KeyOperation.Rekey -> "Сменить пароль"
            KeyOperation.Grant -> "Разрешить"
            KeyOperation.Revoke -> "Отозвать"
        }
        return "$what ключом «${reason.key.comment}»$who" + (reason.digest?.let { " · $it" } ?: "")
    }

    override fun keyLine(label: String, comment: String, fingerprint: String) = "$comment · $label $fingerprint"

    override val sshKeys = "SSH-ключи"
    override val sshKeysHint = "Ключи для подписи и расшифровки: в чипе, где он есть, и под паролем ключа, где его нет."
    override val noKeys = "Ключей пока нет."
    override val manageKeys = "Ключи"
    override val addKey = "Добавить ключ"
    override val importFile = "Из файла"
    override val paste = "Вставить"
    override val pasteHint = "Вставленный текст проходит через буфер обмена и клавиатуру, которые могут сохранить копию. Надёжнее файл или QR-код ключа с паролем."
    override val scanQr = "Сканировать QR-код"
    override val createKey = "Создать ключ"
    override val createHint = "Создаётся на этом устройстве и больше нигде не существует."
    override val keyFile = "Закрытый ключ"
    override val keyFileHint = "Файл без .pub, например ~/.ssh/id_ed25519"
    override val wouldLive = "Где будет храниться"
    override val passphraseOfFile = "Пароль ключа"
    override fun characters(n: Int): String {
        val word = when {
            n % 100 in 11..14 -> "символов"
            n % 10 == 1 -> "символ"
            n % 10 in 2..4 -> "символа"
            else -> "символов"
        }
        return "$n $word"
    }
    override val shortPassphrase = "Пароль слишком короткий"
    override fun shortPassphraseBody(n: Int) =
        if (n == 0) "У этого ключа нет пароля. Без пароля ключи не хранятся: задайте новый, не короче 12 символов."
        else "Пароль из $n символов подбирается по копии файла за часы. Задайте новый, не короче 12 символов."
    override val setPassphrase = "Задать пароль"
    override val setPassphraseHint = "Ключ будет храниться под этим паролем."
    override val newPassphrase = "Новый пароль"
    override val repeatPassphrase = "Ещё раз"
    override val passphrasesDiffer = "Пароли не совпадают."
    override val onlyLengthCounted = "Считается только длина: честно отличить стойкий пароль от угадываемого здесь не может никто."
    override val computerCopyNote = "Копия на компьютере остаётся со старым паролем; меняется только эта."
    override val policy = "Спрашивать подтверждение"
    override val everyUse = "При каждом использовании ключа"
    override fun session(minutes: Int) = "Один раз, затем $minutes мин"
    override val keepPassphrase = "И каждый раз спрашивать пароль самого ключа"
    override val add = "Добавить"
    override val importing = "Добавляю…"

    override fun refusal(refusal: ImportRefusal) = when (refusal) {
        ImportRefusal.NotAKey -> "Это не закрытый SSH-ключ."
        ImportRefusal.PublicKeyOnly -> "Это открытый ключ (.pub). Нужен файл без .pub."
        ImportRefusal.OldFormat -> "Старый формат ключа. Перепишите его в новый на компьютере — пароль можно оставить прежним:"
        ImportRefusal.PuttyFormat -> "Ключ PuTTY. Переведите его на компьютере:"
        ImportRefusal.UnsupportedCipher -> "Зашифрован шифром, который это приложение не читает. Перешифруйте на компьютере:"
        ImportRefusal.UnsupportedKdf -> "Зашифрован способом, который это приложение не читает."
        ImportRefusal.HardwareToken -> "Этот ключ живёт на аппаратном токене; в файле только ссылка на него."
        ImportRefusal.Dsa -> "Ключи DSA отключены в OpenSSH 7.0."
        ImportRefusal.RsaTooShort -> "Этот ключ RSA слишком короткий. Сделайте новый:"
        ImportRefusal.UnsupportedType -> "Такие ключи не поддерживаются."
        ImportRefusal.Corrupted -> "Файл повреждён или правлен вручную."
    }

    override fun refusalCommand(refusal: ImportRefusal) = SecurityStringsEn.refusalCommand(refusal)

    override val alreadyThere = "Этот ключ уже добавлен."
    override val noScreenLock = "Сначала задайте блокировку экрана: ключи здесь привязаны к ней."
    override val importFailed = "Не удалось добавить ключ."
    override val publicKey = "Открытый ключ"
    override val copy = "Скопировать"
    override val copied = "Скопировано"
    override val share = "Поделиться"
    override val grants = "Каким приложениям можно"
    override val noGrants = "Пока ни одному."
    override val revoke = "Отозвать"
    override val journal = "Недавнее использование"
    override val noJournal = "Ещё не использовался."
    override val changePassphrase = "Сменить пароль"
    override val useForSecrets = "Запечатывать секреты этим ключом"
    override val usedForSecrets = "Секреты запечатаны этим ключом."
    override val delete = "Удалить"
    override val deleteAnchor = "Этим ключом запечатаны секреты; перезапечатайте их другим, прежде чем удалять."
    override val unusable = "Этим ключом здесь больше пользоваться нельзя."

    override val storageSecurity = "Хранение и безопасность"
    override val storageSecurityHint = "Как на этом устройстве хранятся секреты и ключи, и самопроверка, которая это проверяет."
    override val open = "Открыть"
    override val howItWorks = "Как здесь устроено хранение"
    override val runSelfTest = "Запустить самопроверку"
    override val runningSelfTest = "Проверяю…"
    override val shareReport = "Отправить отчёт"
    override val selfTest = "Самопроверка"
    override val selfTestNever = "Ещё не запускалась."
    override val facts = "Факты"
    override val findings = "Находки"
    override val notRunHere = "не на этой платформе"

    override val backup = "Резервная копия"
    override val backupHint = "Данные всех приложений в одном файле, зашифрованном на SSH-ключи вашего аккаунта GitHub. Ключей в нём нет."
    override val backupScreen = "Резервная копия и восстановление"
    override val githubLogin = "Логин GitHub"
    override val findKeys = "Найти ключи"
    override val recipients = "Зашифровано на"
    override val makeBackup = "Сделать копию"
    override val backupReason = "Сделать резервную копию данных всех приложений"
    override val restore = "Восстановить из копии"
    override val restoreFile = "Выбрать файл копии"
    override val restoreReason = "Заменить данные приложений данными из копии"
    override val restoreSettings = "И настройки хоста"
    override fun restoreDone(apps: Int) = "Восстановлено приложений: $apps."
    override val keysToImport = "На старом устройстве были эти ключи. Импортируйте их заново с компьютера:"
    override val backupIncludes = "Что войдёт"
    override val backupExcludes = "Не войдут: SSH-ключи и приложения, которые просили их не брать."
    override val backupMade = "Копия сделана."
    override fun backupFailed(code: String) = "Копию сделать не удалось ($code)."
}

internal fun securityStrings(langTag: String): SecurityStrings =
    if (langTag.substringBefore('-').lowercase() == "ru") SecurityStringsRu else SecurityStringsEn
