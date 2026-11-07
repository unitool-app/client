package cx.m42.unitool.i18n

import cx.m42.services.backup.GitHubKeys
import cx.m42.services.ssh.AddKnownOutcome
import cx.m42.services.ssh.keyring.HostAction
import cx.m42.services.ssh.keyring.UseReason
import cx.m42.superizer.ssh.GeneratedKind
import cx.m42.superizer.ssh.ImportRefusal
import cx.m42.superizer.ssh.KeyOperation
import cx.m42.superizer.ssh.KeyPurpose
import cx.m42.superizer.ssh.UseRecord

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
        when (val action = reason.action) {
            is HostAction.Export -> return "Отправить ключ «${reason.key.comment}» на «${action.to}»"
            HostAction.Verify -> return "Проверить ключ «${reason.key.comment}»"
            null -> Unit
        }
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
    override val keepInChip = "В чипе устройства"
    override val keepUnderPassphrase = "Только под паролем ключа"
    override val biometricOnly = "Только мой отпечаток — не PIN-код телефона"
    override val biometricOnlyIphone = "Только Face ID или Touch ID — не код-пароль"
    override fun opensWith(iphone: Boolean, biometricOnly: Boolean, passphrase: Boolean, canRemake: Boolean) = when {
        biometricOnly -> if (iphone) "Открывает только Face ID или Touch ID" else "Открывает только отпечаток"
        passphrase -> if (iphone) "Face ID или код-пароль, и пароль ключа" else "Отпечаток или PIN-код, и пароль ключа"
        iphone -> "Открывает и код-пароль iPhone" + if (canRemake) ": создайте ключ заново с «Только Face ID»" else ""
        else -> "Открывает и PIN-код телефона" + if (canRemake) ": создайте ключ заново с «Только отпечаток»" else ""
    }
    override val passphraseRequired = "Обязательно без отпечатка или лица: одна блокировка экрана ключ не открывает."
    override val noPassphraseInChip = "Создаётся внутри чипа: файла нет, поэтому и пароля у него нет."
    override val add = "Добавить"
    override val importing = "Добавляю…"

    override fun refusal(refusal: ImportRefusal) = when (refusal) {
        ImportRefusal.NotAKey -> "Это не закрытый SSH-ключ."
        ImportRefusal.PublicKeyOnly -> "Это открытый ключ (.pub). Нужен файл без .pub — а чтобы шифровать на чужой ключ, есть «Добавить открытый ключ» в SSH-ключах."
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
    override val lockKeys = "Заблокировать ключи сейчас"
    override val storedAt = "Где хранится"
    override val rekeyFailed = "Пароль не изменён."
    override fun atLeast(n: Int) = "нужно не меньше $n"
    override val working = "Выполняю…"
    override val create = "Создать"
    override val back = "Назад"
    override val continueLabel = "Далее"
    override fun keyKind(kind: GeneratedKind) = when (kind) {
        GeneratedKind.Ed25519 -> "Ed25519 — подписывает и расшифровывает"
        GeneratedKind.EcdsaP256 -> "ECDSA P-256 — только подписывает, создаётся внутри чипа"
    }
    override val keyName = "Имя"
    override val qrHint = "На компьютере покажите файл ключа QR-кодом:"
    override val qrWarning = "QR-код ключа без пароля — это сам ключ на экране."
    override val sessionHint = "В сеансе ключом может пользоваться без вопросов любой, у кого в руках разблокированное устройство."
    override fun purposeName(purpose: KeyPurpose) = when (purpose) {
        is KeyPurpose.Sign -> "Подпись для «${purpose.namespace}»"
        KeyPurpose.Decrypt -> "Расшифровка файлов"
    }
    override fun operationName(operation: KeyOperation) = when (operation) {
        KeyOperation.Sign -> "подпись"
        KeyOperation.Decrypt -> "расшифровка"
        KeyOperation.AgentSign -> "вход по SSH"
        KeyOperation.Import -> "добавлен"
        KeyOperation.Generate -> "создан"
        KeyOperation.Delete -> "удаление"
        KeyOperation.Rekey -> "пароль сменён"
        KeyOperation.Grant -> "разрешено"
        KeyOperation.Revoke -> "отозвано"
    }
    override fun actionName(namespace: String?) = when (namespace) {
        HostAction.EXPORT -> "отправлен на другое устройство"
        HostAction.VERIFY -> "проверка"
        else -> null
    }
    override fun journalLine(at: String, who: String, record: UseRecord) =
        "$at · $who · " + (actionName(record.namespace) ?: (operationName(record.operation) + (record.namespace?.let { " «$it»" } ?: ""))) +
            " · " + outcomeName(record.outcome)
    override fun outcomeName(outcome: String) = when {
        outcome == "ok" -> "готово"
        outcome == "cancelled" -> "отменено"
        outcome == "refused" -> "отказано"
        outcome.startsWith("failed:") -> "ошибка (${outcome.removePrefix("failed:")})"
        else -> outcome
    }

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
    override val notRunNeedsConfirmation = "только по кнопке «Запустить самопроверку»: она попросит подтверждения"
    override val notRunNoScreenLock = "на устройстве нет блокировки экрана"
    override val notRunAfterFailure = "не выполнялась: не прошёл шаг выше"
    override val subjectSecrets = "Секреты"
    override val subjectDevice = "Это устройство"
    override val subjectApps = "Приложения"
    override fun lastRun(at: String) = "Последний запуск: $at"
    override val copyReport = "Скопировать отчёт"

    override val backup = "Резервная копия"
    override val backupHint = "Данные всех приложений в одном файле, зашифрованном на SSH-ключи вашего аккаунта GitHub. Ключей в нём нет."
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
    override fun githubProblem(problem: GitHubKeys.Problem) = when (problem) {
        GitHubKeys.Problem.NoSuchUser -> "Такого аккаунта GitHub нет."
        GitHubKeys.Problem.NoKeys -> "У этого аккаунта нет открытых SSH-ключей."
        GitHubKeys.Problem.NoUsableKeys -> "Ни на один ключ этого аккаунта нельзя зашифровать: age берёт Ed25519 и RSA от 2048 бит."
        GitHubKeys.Problem.RateLimited -> "GitHub ограничил запросы с этого адреса. Попробуйте через час."
        GitHubKeys.Problem.Offline -> "Нет соединения."
        GitHubKeys.Problem.Server -> "GitHub ответил не так, как ожидалось. Попробуйте позже."
    }
    override val cannotEncryptTo = "age не шифрует на такой ключ"
    override val restoreHint = "Копия, сделанная на другом устройстве. Ключ, на который она зашифрована, сначала нужно добавить на это устройство; копия, открытая на компьютере (age -d … > backup.json), читается как есть."
    override val restoreButton = "Восстановить"
    override val cancelledText = "Ничего не сделано."
    override val secretsLocked = "Не удалось открыть секрет, поэтому копия не сделана."
    override val backupUnreadable = "Это не копия, которую открывают здешние ключи."
    override fun entries(n: Int) = "записей: $n"

    override val knownKeys = "Ключи других устройств"
    override val knownKeysHint = "Только открытая половина: на них можно шифровать — резервную копию, экспорт 2FA, — а открыть сможет только устройство, где лежит сам ключ."
    override val noKnownKeys = "Пока нет."
    override val addPublicKey = "Добавить открытый ключ"
    override val scanPublicQrHint = "На другом телефоне: SSH-ключи → ключ → «Показать QR-код»."
    override val publicFileHint = "Файл .pub, например ~/.ssh/id_ed25519.pub"
    override val pastePublicHint = "Одна строка: ssh-ed25519 AAAA… имя"
    override val fingerprint = "Отпечаток"
    override val checkFingerprint = "Если ключ пришёл не QR-кодом с экрана другого устройства, сверьте этот отпечаток с тем экраном, прежде чем сохранять."
    override val save = "Сохранить"
    override val showQr = "Показать QR-код"
    override val qrScreenHint = "На другом телефоне: SSH-ключи → «Добавить открытый ключ» → «Сканировать QR-код». В коде только открытая половина: на этот ключ можно зашифровать, но ничего нельзя открыть."
    override val deleteAgain = "Нажмите ещё раз, чтобы удалить"
    override val savedKeys = "Сохранённые ключи"
    override val savedKeysHint = "Открытые ключи других устройств. Открыть файл сможет только устройство с отмеченным ключом."
    override val orGithub = "Или ключи аккаунта GitHub"
    override fun knownProblem(outcome: AddKnownOutcome) = when (outcome) {
        is AddKnownOutcome.Ok -> ""
        AddKnownOutcome.NotAPublicKey -> "Это не одна строка открытого ключа. Приватный ключ добавляется через «Добавить ключ»."
        is AddKnownOutcome.CannotEncryptTo -> "На ключ ${outcome.key.label} зашифровать нельзя: подходят Ed25519 и RSA от 2048 бит. Ключ, созданный в чипе iPhone, — ECDSA."
        is AddKnownOutcome.OwnKey -> "Это ваш ключ «${outcome.comment}», он уже здесь вместе с приватной половиной."
        is AddKnownOutcome.AlreadySaved -> "Уже сохранён как «${outcome.name}»."
    }

    override val sendKey = "Отправить на другое устройство"
    override val sendKeyHint = "Ключ, зашифрованный на открытый ключ другого устройства, уходит через «Поделиться». Открыть файл сможет только то устройство — в cr-restore или на компьютере: age -d -i ~/.ssh/id_ed25519, — а потом добавить из него ключ через «Добавить ключ»."
    override val sendKeyWarning = "Копия ключа уходит с этого устройства. Добавить её сможет любой, у кого закрытая половина выбранного ключа: выбирайте только своё устройство."
    override val sendKeyNoPassphrase = "В файле ключ без своего пароля: другое устройство попросит задать новый, когда будет его добавлять. Открытый файл после этого удалите."
    override val sendTo = "На чей ключ зашифровать"
    override val noRecipients = "Открытых ключей других устройств пока нет. На другом устройстве: SSH-ключи → его ключ → «Показать QR-код», и добавьте его здесь."
    override fun sent(to: String) = "Отправлено, зашифровано на «$to»."
    override val sendFailed = "Ключ не отправлен."
    override val verifyKey = "Проверить ключ"
    override val verifyOk = "Ключ открывается: замок работает."
    override val verifyMismatch = "Ключ открылся, но проверка не сошлась."
    override val verifyFailed = "Ключ не открылся."
}

internal fun securityStrings(langTag: String): SecurityStrings =
    if (langTag.substringBefore('-').lowercase() == "ru") SecurityStringsRu else SecurityStringsEn
