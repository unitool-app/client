package cx.m42.unitool.i18n

import cx.m42.superizer.diagnostics.DiagnosticsAction
import cx.m42.superizer.diagnostics.DiagnosticsCode
import cx.m42.superizer.diagnostics.DiagnosticsStrings
import cx.m42.superizer.diagnostics.SelfTestCode
import cx.m42.superizer.diagnostics.Status

/**
 * The one place a protection label is written (06 §6, D179): every code of the catalogue, a title
 * and a detail, English first. A code added to the catalogue without a text here fails this file's
 * `when` at compile time; a translation is the same interface again, so it cannot miss one either.
 */
internal abstract class DiagnosticsWords : DiagnosticsStrings {

    protected abstract fun title(code: DiagnosticsCode): String

    protected abstract fun detail(code: DiagnosticsCode): String

    protected abstract fun step(code: SelfTestCode): String

    final override fun title(code: String): String =
        DiagnosticsCode.of(code)?.let(::title) ?: SelfTestCode.of(code)?.let(::step) ?: code

    final override fun detail(code: String, facts: Map<String, String>): String {
        val template = DiagnosticsCode.of(code)?.let(::detail) ?: return ""
        return fill(template, facts)
    }

    /** `{name}` from [facts]; `{date}` as a date. An unknown name stays as it is, visibly. */
    private fun fill(template: String, facts: Map<String, String>): String =
        Regex("\\{([a-z]+)\\}").replace(template) { match ->
            val name = match.groupValues[1]
            val value = facts[name] ?: return@replace match.value
            if (name == "date") date(value.toLongOrNull()) ?: value else value
        }

    protected abstract fun date(millis: Long?): String?
}

internal object DiagnosticsWordsEn : DiagnosticsWords() {

    override fun title(code: DiagnosticsCode): String = when (code) {
        DiagnosticsCode.VaultChipOk -> "Secrets are protected by this device's secure hardware."
        DiagnosticsCode.VaultSoftwareKey -> "Secrets are sealed by a software key."
        DiagnosticsCode.VaultPassphraseOk -> "Secrets are protected by the passphrase of your SSH key {comment}."
        DiagnosticsCode.VaultPassphraseBrowser -> "Secrets are protected only by a passphrase, inside the browser."
        DiagnosticsCode.VaultMissing -> "Secret storage is not set up on this platform. Secrets are stored as plain text."
        DiagnosticsCode.VaultNotImplemented -> "This platform has no secret storage yet."
        DiagnosticsCode.VaultKeyLost -> "The key that sealed your secrets is gone."
        DiagnosticsCode.VaultBroken -> "Secret storage failed its self-test at step {step}."
        DiagnosticsCode.VaultSessionOpen -> "Secrets are unlocked for this session."
        DiagnosticsCode.KeyLevel1 -> "Stored in the secure chip ({hardware}); never leaves it."
        DiagnosticsCode.KeyLevel2 -> "Encrypted with a key from the secure chip ({hardware})."
        DiagnosticsCode.KeyLevel3 -> "Stored in the app's files, protected only by its passphrase."
        DiagnosticsCode.KeyLevel3Browser -> "Stored in the browser, protected only by its passphrase."
        DiagnosticsCode.KeyLevel3ChipAvailable -> "Stored in the app's files, protected only by its passphrase."
        DiagnosticsCode.KeyNoSecondLock -> "Anyone who can unlock this device can use this key."
        DiagnosticsCode.KeyBiometricOnly -> "Opens only with your fingerprint, not with the screen lock."
        DiagnosticsCode.KeyUnusable -> "This key can no longer be used here:"
        DiagnosticsCode.KeySecretsAnchor -> "This key seals the host's secrets."
        DiagnosticsCode.KeySessionPolicy -> "Confirmation is remembered for {minutes} minutes."
        DiagnosticsCode.DeviceNoScreenLock -> "No screen lock."
        DiagnosticsCode.DeviceNoSecureHardware -> "This device has no secure hardware ({reason})."
        DiagnosticsCode.DeviceEmulator -> "Running on an emulator."
        DiagnosticsCode.PassphraseSlow -> "Unlocking with a passphrase takes {seconds} s on this device."
        DiagnosticsCode.BackupNever -> "No backup has been made."
        DiagnosticsCode.BackupStale -> "Last backup was {days} days ago."
        DiagnosticsCode.BackupOk -> "Last backup: {date}."
        DiagnosticsCode.SelfTestNever -> "The self-test has not been run on this device."
        DiagnosticsCode.SelfTestOk -> "Self-test passed on {date} ({n} checks)."
        DiagnosticsCode.SelfTestFailed -> "Self-test failed: {step}."
        DiagnosticsCode.AppSecretsSealed -> "{n} secrets, sealed by the host."
        DiagnosticsCode.AppSecretsPlain -> "{n} secrets are stored as plain text"
    }

    override fun detail(code: DiagnosticsCode): String = when (code) {
        DiagnosticsCode.VaultChipOk -> "Sealed by a key that never leaves the {hardware} and cannot be copied with the app's files."
        DiagnosticsCode.VaultSoftwareKey -> "This device has no secure hardware for the key, so anyone with root access can read the secrets."
        DiagnosticsCode.VaultPassphraseOk -> "There is no secure hardware on this platform; the passphrase is the only lock. Choose it well and keep a backup."
        DiagnosticsCode.VaultPassphraseBrowser -> "Any script on this site can read the storage and the tab's memory. Prefer the phone or desktop app for secrets."
        DiagnosticsCode.VaultMissing -> "Add an SSH key with a passphrase; the host will seal all app secrets with it."
        DiagnosticsCode.VaultNotImplemented -> "Secrets are stored as plain text until one is implemented. Report this so it gets built."
        DiagnosticsCode.VaultKeyLost -> "The screen lock was removed or the device was restored. Sealed data on this device cannot be opened; restore it from a backup."
        DiagnosticsCode.VaultBroken -> "Secrets may be unreadable. Share the report so this can be fixed."
        DiagnosticsCode.VaultSessionOpen -> "They lock again when the app closes or you lock them."
        DiagnosticsCode.KeyLevel1 -> ""
        DiagnosticsCode.KeyLevel2 -> "Decrypted into memory only for one operation, after you confirm."
        DiagnosticsCode.KeyLevel3 -> ""
        DiagnosticsCode.KeyLevel3Browser -> "The decrypted key lives in the tab's memory while unlocked."
        DiagnosticsCode.KeyLevel3ChipAvailable -> "This device has a secure chip. To keep the key in it, delete the key and add it again."
        DiagnosticsCode.KeyNoSecondLock -> "The screen lock — PIN, pattern, passcode, fingerprint or face — is enough. The key's own passphrase is a second lock the device does not have."
        DiagnosticsCode.KeyBiometricOnly -> "A face of the strongest class counts as a fingerprint. If a fingerprint is added or all are removed, the key stops working here: import it again from your computer."
        DiagnosticsCode.KeyUnusable -> "the screen lock was removed, or — for a key only a fingerprint opens — the fingerprints changed. Import it again from your computer."
        DiagnosticsCode.KeySecretsAnchor -> "It cannot be deleted until secrets are re-sealed with another key."
        DiagnosticsCode.KeySessionPolicy -> "Anyone holding the unlocked device can use this key within that window."
        DiagnosticsCode.DeviceNoScreenLock -> "Keys that need your confirmation cannot be created; secret storage is weaker. Set a PIN, pattern or passcode."
        DiagnosticsCode.DeviceNoSecureHardware -> "Keys and secrets can only be protected by a passphrase here."
        DiagnosticsCode.DeviceEmulator -> "Hardware protection is simulated; nothing here should be trusted with real keys."
        DiagnosticsCode.PassphraseSlow -> "bcrypt with 24 rounds is slow here; the key is as safe as before, only slower to use."
        DiagnosticsCode.BackupNever -> "If this device is lost, sealed secrets are lost with it."
        DiagnosticsCode.BackupStale -> ""
        DiagnosticsCode.BackupOk -> "Keys are not in backups; only app data."
        DiagnosticsCode.SelfTestNever -> ""
        DiagnosticsCode.SelfTestOk -> ""
        DiagnosticsCode.SelfTestFailed -> "{evidence}"
        DiagnosticsCode.AppSecretsSealed -> ""
        DiagnosticsCode.AppSecretsPlain -> "because the host has no secret storage here."
    }

    override fun step(code: SelfTestCode): String = when (code) {
        SelfTestCode.VaultRoundtrip -> "Vault seals and opens"
        SelfTestCode.VaultHardware -> "Vault key is in secure hardware"
        SelfTestCode.VaultTamper -> "Vault refuses a changed secret"
        SelfTestCode.VaultNonce -> "Vault never seals the same way twice"
        SelfTestCode.WrapAuthRequired -> "Chip refuses without you"
        SelfTestCode.WrapConfirmed -> "Chip works after you confirm"
        SelfTestCode.WrapPasscodeClass -> "Keys need a screen lock"
        SelfTestCode.EnclaveSign -> "Secure Enclave signs"
        SelfTestCode.PassphraseRoundtrip -> "Passphrase opens a key"
        SelfTestCode.AgeRoundtrip -> "Encryption to an SSH key works"
        SelfTestCode.StorageLocation -> "Key records stay out of backups"
        SelfTestCode.MemoryZeroed -> "Secrets are wiped from memory"
        SelfTestCode.ReportClean -> "The report carries no secret"
    }

    override fun action(action: DiagnosticsAction): String = when (action) {
        DiagnosticsAction.AddSshKey -> "Add an SSH key"
        DiagnosticsAction.SetPassphrase -> "Set a passphrase"
        DiagnosticsAction.Rekey -> "Change passphrase"
        DiagnosticsAction.EnableScreenLock -> "Open security settings"
        DiagnosticsAction.RunSelfTest -> "Run self-test"
        DiagnosticsAction.MakeBackup -> "Make a backup"
        DiagnosticsAction.ShareReport -> "Share report"
        DiagnosticsAction.ImportKeyAgain -> "Import the key again"
    }

    override fun status(status: Status): String = when (status) {
        Status.Ok -> "OK"
        Status.Info -> "Note"
        Status.Attention -> "Needs attention"
        Status.Unsafe -> "Unsafe"
        Status.Missing -> "Not protected"
        Status.Broken -> "Broken"
    }

    override fun date(millis: Long?): String? = millis?.let { isoDate(it) }
}

internal object DiagnosticsWordsRu : DiagnosticsWords() {

    override fun title(code: DiagnosticsCode): String = when (code) {
        DiagnosticsCode.VaultChipOk -> "Секреты защищены аппаратным чипом этого устройства."
        DiagnosticsCode.VaultSoftwareKey -> "Секреты запечатаны программным ключом."
        DiagnosticsCode.VaultPassphraseOk -> "Секреты защищены паролем вашего SSH-ключа {comment}."
        DiagnosticsCode.VaultPassphraseBrowser -> "Секреты защищены только паролем, внутри браузера."
        DiagnosticsCode.VaultMissing -> "Хранилище секретов на этой платформе не настроено. Секреты лежат открытым текстом."
        DiagnosticsCode.VaultNotImplemented -> "На этой платформе хранилища секретов пока нет."
        DiagnosticsCode.VaultKeyLost -> "Ключ, которым запечатаны ваши секреты, пропал."
        DiagnosticsCode.VaultBroken -> "Хранилище секретов не прошло самопроверку на шаге {step}."
        DiagnosticsCode.VaultSessionOpen -> "Секреты открыты на этот сеанс."
        DiagnosticsCode.KeyLevel1 -> "Хранится в защищённом чипе ({hardware}) и не покидает его."
        DiagnosticsCode.KeyLevel2 -> "Зашифрован ключом из защищённого чипа ({hardware})."
        DiagnosticsCode.KeyLevel3 -> "Лежит в файлах приложения, защищён только паролем ключа."
        DiagnosticsCode.KeyLevel3Browser -> "Лежит в браузере, защищён только паролем ключа."
        DiagnosticsCode.KeyLevel3ChipAvailable -> "Лежит в файлах приложения, защищён только паролем ключа."
        DiagnosticsCode.KeyNoSecondLock -> "Ключом может воспользоваться любой, кто может разблокировать устройство."
        DiagnosticsCode.KeyBiometricOnly -> "Открывается только вашим отпечатком, блокировка экрана его не открывает."
        DiagnosticsCode.KeyUnusable -> "Этим ключом здесь больше пользоваться нельзя:"
        DiagnosticsCode.KeySecretsAnchor -> "Этим ключом запечатаны секреты хоста."
        DiagnosticsCode.KeySessionPolicy -> "Подтверждение действует {minutes} мин."
        DiagnosticsCode.DeviceNoScreenLock -> "Нет блокировки экрана."
        DiagnosticsCode.DeviceNoSecureHardware -> "В этом устройстве нет защищённого чипа ({reason})."
        DiagnosticsCode.DeviceEmulator -> "Работает на эмуляторе."
        DiagnosticsCode.PassphraseSlow -> "Открытие ключа паролем занимает здесь {seconds} с."
        DiagnosticsCode.BackupNever -> "Резервной копии ещё не было."
        DiagnosticsCode.BackupStale -> "Последняя резервная копия — {days} дн. назад."
        DiagnosticsCode.BackupOk -> "Последняя резервная копия: {date}."
        DiagnosticsCode.SelfTestNever -> "Самопроверка на этом устройстве не запускалась."
        DiagnosticsCode.SelfTestOk -> "Самопроверка пройдена {date} ({n} проверок)."
        DiagnosticsCode.SelfTestFailed -> "Самопроверка не пройдена: {step}."
        DiagnosticsCode.AppSecretsSealed -> "Секретов: {n}, запечатаны хостом."
        DiagnosticsCode.AppSecretsPlain -> "Секретов открытым текстом: {n}"
    }

    override fun detail(code: DiagnosticsCode): String = when (code) {
        DiagnosticsCode.VaultChipOk -> "Их запечатывает ключ, который никогда не покидает {hardware} и не копируется вместе с файлами приложения."
        DiagnosticsCode.VaultSoftwareKey -> "В устройстве нет защищённого чипа для ключа, поэтому любой с root-доступом прочитает секреты."
        DiagnosticsCode.VaultPassphraseOk -> "Защищённого чипа на этой платформе нет; пароль — единственный замок. Выберите его хорошо и держите резервную копию."
        DiagnosticsCode.VaultPassphraseBrowser -> "Любой скрипт этого сайта читает хранилище и память вкладки. Для секретов лучше приложение на телефоне или компьютере."
        DiagnosticsCode.VaultMissing -> "Добавьте SSH-ключ с паролем — хост запечатает им секреты всех приложений."
        DiagnosticsCode.VaultNotImplemented -> "Пока его нет, секреты лежат открытым текстом. Отправьте отчёт, чтобы его сделали."
        DiagnosticsCode.VaultKeyLost -> "Снята блокировка экрана или устройство восстановлено. Запечатанные данные на этом устройстве не открыть; восстановите их из резервной копии."
        DiagnosticsCode.VaultBroken -> "Секреты могут не читаться. Отправьте отчёт, чтобы это исправили."
        DiagnosticsCode.VaultSessionOpen -> "Они закроются снова, когда приложение закроется или вы их заблокируете."
        DiagnosticsCode.KeyLevel1 -> ""
        DiagnosticsCode.KeyLevel2 -> "Расшифровывается в память только на одну операцию, после вашего подтверждения."
        DiagnosticsCode.KeyLevel3 -> ""
        DiagnosticsCode.KeyLevel3Browser -> "Пока ключ открыт, расшифрованный ключ живёт в памяти вкладки."
        DiagnosticsCode.KeyLevel3ChipAvailable -> "В устройстве есть защищённый чип. Чтобы ключ хранился в нём, удалите ключ и добавьте заново."
        DiagnosticsCode.KeyNoSecondLock -> "Достаточно блокировки экрана — PIN-кода, графического ключа, код-пароля, отпечатка или лица. Пароль самого ключа — второй замок, которого у устройства нет."
        DiagnosticsCode.KeyBiometricOnly -> "Лицо высшего класса считается как отпечаток. Если добавить отпечаток или удалить все, ключ здесь перестанет работать — импортируйте его заново с компьютера."
        DiagnosticsCode.KeyUnusable -> "снята блокировка экрана или — у ключа, который открывается только отпечатком, — изменились отпечатки. Импортируйте его заново с компьютера."
        DiagnosticsCode.KeySecretsAnchor -> "Его нельзя удалить, пока секреты не перезапечатаны другим ключом."
        DiagnosticsCode.KeySessionPolicy -> "Любой, у кого в руках разблокированное устройство, может в это время пользоваться ключом."
        DiagnosticsCode.DeviceNoScreenLock -> "Ключи, требующие вашего подтверждения, не создаются; хранилище секретов слабее. Задайте PIN-код, графический ключ или пароль."
        DiagnosticsCode.DeviceNoSecureHardware -> "Ключи и секреты здесь защищены только паролем."
        DiagnosticsCode.DeviceEmulator -> "Аппаратная защита лишь имитируется; настоящие ключи сюда класть нельзя."
        DiagnosticsCode.PassphraseSlow -> "bcrypt с 24 раундами здесь медленный; ключ так же надёжен, только открывается дольше."
        DiagnosticsCode.BackupNever -> "Если устройство пропадёт, запечатанные секреты пропадут вместе с ним."
        DiagnosticsCode.BackupStale -> ""
        DiagnosticsCode.BackupOk -> "Ключей в резервных копиях нет — только данные приложений."
        DiagnosticsCode.SelfTestNever -> ""
        DiagnosticsCode.SelfTestOk -> ""
        DiagnosticsCode.SelfTestFailed -> "{evidence}"
        DiagnosticsCode.AppSecretsSealed -> ""
        DiagnosticsCode.AppSecretsPlain -> "потому что у хоста здесь нет хранилища секретов."
    }

    override fun step(code: SelfTestCode): String = when (code) {
        SelfTestCode.VaultRoundtrip -> "Сейф запечатывает и открывает"
        SelfTestCode.VaultHardware -> "Ключ сейфа — в защищённом чипе"
        SelfTestCode.VaultTamper -> "Сейф отвергает изменённый секрет"
        SelfTestCode.VaultNonce -> "Сейф не запечатывает одинаково дважды"
        SelfTestCode.WrapAuthRequired -> "Чип отказывает без вас"
        SelfTestCode.WrapConfirmed -> "Чип работает после подтверждения"
        SelfTestCode.WrapPasscodeClass -> "Ключам нужна блокировка экрана"
        SelfTestCode.EnclaveSign -> "Secure Enclave подписывает"
        SelfTestCode.PassphraseRoundtrip -> "Пароль открывает ключ"
        SelfTestCode.AgeRoundtrip -> "Шифрование на SSH-ключ работает"
        SelfTestCode.StorageLocation -> "Записи ключей не попадают в бэкап"
        SelfTestCode.MemoryZeroed -> "Секреты стираются из памяти"
        SelfTestCode.ReportClean -> "В отчёте нет секретов"
    }

    override fun action(action: DiagnosticsAction): String = when (action) {
        DiagnosticsAction.AddSshKey -> "Добавить SSH-ключ"
        DiagnosticsAction.SetPassphrase -> "Задать пароль"
        DiagnosticsAction.Rekey -> "Сменить пароль"
        DiagnosticsAction.EnableScreenLock -> "Открыть настройки безопасности"
        DiagnosticsAction.RunSelfTest -> "Запустить самопроверку"
        DiagnosticsAction.MakeBackup -> "Сделать резервную копию"
        DiagnosticsAction.ShareReport -> "Отправить отчёт"
        DiagnosticsAction.ImportKeyAgain -> "Импортировать ключ заново"
    }

    override fun status(status: Status): String = when (status) {
        Status.Ok -> "В порядке"
        Status.Info -> "К сведению"
        Status.Attention -> "Требует внимания"
        Status.Unsafe -> "Небезопасно"
        Status.Missing -> "Не защищено"
        Status.Broken -> "Сломано"
    }

    override fun date(millis: Long?): String? = millis?.let { isoDate(it) }
}

/** `2026-09-26`, without a date library: the civil-from-days algorithm (Hinnant). */
internal fun isoDate(millis: Long): String {
    val days = millis.floorDiv(86_400_000L)
    val z = days + 719_468
    val era = z.floorDiv(146_097L)
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val day = doy - (153 * mp + 2) / 5 + 1
    val month = if (mp < 10) mp + 3 else mp - 9
    val year = yoe + era * 400 + if (month <= 2) 1 else 0
    return "$year-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
}

internal fun diagnosticsWords(langTag: String): DiagnosticsStrings =
    if (langTag.substringBefore('-').lowercase() == "ru") DiagnosticsWordsRu else DiagnosticsWordsEn
