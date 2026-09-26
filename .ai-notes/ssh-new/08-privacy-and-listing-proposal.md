# 08 — Предложение: PRIVACY и листинг после SSH-ключей и бэкапа

> **Дата:** 2026-09-26 · **Статус:** предложение, **не вписано**. Стоп-линия 00 §5: `play/`
> правит только владелец или агент после его «да». Шаг 7 кода (бэкап, импорт 2FA из файла)
> сделан; этот текст — то, что к нему нужно в `play/PRIVACY.md` и `play/listing/description-*.md`.
>
> Правило D179: каждое слово о защите — формула кода каталога 06 §6.1 (`DiagnosticsWords.en`),
> без обещаний сверх неё. Где ниже стоит «(код …)», текст повторяет заголовок этого кода.

## 1. Что проверено до текста

- **Разрешения** сняты с собранного release-APK (`aapt2 dump permissions`), а не с манифеста —
  см. §5. Новых нет: выбор файла идёт через системный пикер (SAF), ему разрешение не нужно;
  Keystore разрешений не требует.
- **Сеть.** Новых адресов нет: бэкап берёт ключи GitHub тем же запросом, что экспорт 2FA
  (`api.github.com/users/<login>/keys`). Модуль ключей `services/ssh` сети не имеет вовсе
  (проверка I-net в `verify.sh`).
- **Бэкап Android.** Записи ключей (`cx.m42.services.ssh.xml`) не входят ни в одно правило
  `include` (`BackupRulesTest`, I10).

## 2. `play/PRIVACY.md` — что поменять

Дата: `Last updated: <день выпуска>`.

### «The short version» — заменить второй абзац

> It is not, however, an offline app any more. It has the internet permission, and it uses the
> network for exactly three things: the currency converter downloads published exchange rates, the
> app can receive push notifications, and — only when you ask for it — the 2FA export and the
> backup look up the public keys of a GitHub account you name. All three are described below.

### «What the app stores» — строки в таблицу

| What | Why |
|---|---|
| SSH keys you add or create: the public half, a name, where the private half is kept — see below | so Settings → SSH keys can use them when you confirm |
| Which apps may use which key, and the last 200 uses of each key: when, which app, what kind of use, and the first 8 characters of a hash of what was signed or decrypted — never the data, never the signature | so the key's page can show who used it |
| When you last made a backup, and the result of the last self-test | so Settings → Storage & security can say so |

### Новый раздел «SSH keys» — после «The 2FA lock»

> You can add your own SSH keys in Settings → SSH keys: from a file, pasted, scanned as a QR code,
> or made on the phone. A key is never kept without a passphrase: a file with none, or with one
> shorter than twelve characters, is stored only after you set a new one.
>
> Where the private half lives depends on the phone, and the key's page says which:
>
> - **In the secure chip** — "Stored in the secure chip (TEE or StrongBox); never leaves it."
>   Android's Keystore holds the key itself; the app can ask it to sign or decrypt, and cannot
>   read the key out.
> - **Encrypted with a key from the secure chip** — "Encrypted with a key from the secure chip."
>   The key file is sealed by a Keystore key and opened into memory only for one operation.
> - **In the app's files, protected only by its passphrase** — "Stored in the app's files,
>   protected only by its passphrase." On phones whose Keystore is not in hardware, and below
>   Android 11.
>
> Every use asks you first: the system's fingerprint, face or screen-lock sheet for a key in or
> sealed by the chip, the key's passphrase for the rest. You can choose "once, then for five
> minutes" instead of every time; the key's page says what that means. An app can use a key only
> after you allowed that app, that key and that purpose in a sheet, and it never receives the key —
> only a signature or a decrypted file. The screens where a key or its passphrase is entered cannot
> be captured in screenshots or recordings.
>
> Keys are never sent anywhere and never included in any backup — neither Android's nor the app's
> own. The part of the app that holds them has no network access at all. Removing the screen lock
> makes Android delete the keys held in the chip; the public half stays, and the page says the key
> must be imported again.

### «What leaves the device» — «Four things» → «Five things», добавить

> **A backup, if you make one.** Settings → Backup makes one file of every app's data — your 2FA
> accounts, your settings, your home screen — and the names of your SSH keys (fingerprint and
> name, so the next phone can tell you which to import again), **never the keys themselves**. As
> with the 2FA export, you type a GitHub username, the app fetches that account's public keys from
> `api.github.com`, and the file is encrypted **on the phone** to them in the age format. It goes
> only where you send it; we never receive it. Restoring opens it with one of your SSH keys, after
> you confirm, and seals the data again on the new phone.
>
> **A self-test report, if you share one.** Settings → Storage & security can share a plain-text
> report of how secrets are kept on this phone: codes, the phone's model and Android version, and
> what the Keystore said. It holds no secret, no key and nothing you typed (checked by a test on
> every build). It goes only where you send it.

И последний абзац раздела: «No calculation, no converter input, no two-factor key and no SSH key is
transmitted in readable form.»

### «The two-factor accounts are never written in readable form» — уточнить

Сегодня абзац говорит «encrypted with a key that Android's Keystore generates». Теперь секреты
запечатывает хост, тем же ключом Keystore — формулировка остаётся верной. Добавить фразу по коду
`vault.chip.ok` / `vault.software-key`: «Settings → Storage & security says whether that key is in
secure hardware on your phone; on a phone where it is not, it says so.»

### «Permissions» — без изменений в списке

Список тот же (§5). Одна строка в конец: «Opening a key file or a backup uses Android's own file
picker, which hands the app the one file you chose and needs no permission.»

### «Backup» — дописать

> Your SSH keys are excluded from Android's backup, whatever your device's settings. The app's own
> backup (above) is separate and made only when you ask.

### «What the app collects» — Data safety

Ответы формы **не меняются**: ничего нового не уходит к нам, а «collected» в терминах Play — это
переданное с устройства. Файл, который человек выбирает (ключ, бэкап), обрабатывается на
устройстве. Предлагаю не трогать форму, а в этот раздел добавить: «Files you pick — a key or a
backup — are read on the phone and not sent anywhere.»

## 3. Листинг — `description-en.md` (полное описание)

Новый блок после «ONE APP, SEVERAL TOOLS»:

```
SSH KEYS AND BACKUP

• Add your SSH keys — from a file, pasted, a QR code, or made on the phone
• Kept in the phone's secure chip where it has one, under the key's own passphrase where it does not — and the app says which, key by key
• Every use asks you first; an app uses a key only after you allowed it
• Settings → Storage & security shows how everything is kept on this phone, with a self-test that checks it
• One-file backup of every app's data, encrypted on the phone to your GitHub account's SSH keys. Keys are never in it
```

В PRIVACY-абзац листинга: «…for two things and no others» → «for three things and no others», и
третья: «And when you make a backup or a 2FA export, the app fetches the public keys of the GitHub
account you name — nothing else is sent, and the file is encrypted on the phone.»

Короткое описание и название — без изменений.

`description-ru.md` — перевод тех же абзацев, после «да» на английский.

## 4. Вопрос владельцу

**Вопрос 90.** Вписать §2 и §3 как есть (я правлю `play/PRIVACY.md`, оба описания, дату и
`play/check-listing.py`, если он считает символы), или вы правите текст сами?

## 5. Разрешения из APK

`aapt2 dump permissions composeApp/build/outputs/apk/release/composeApp-release.apk`, сборка
шага 7 (2026-09-26):

```
uses-permission: android.permission.VIBRATE
uses-permission: android.permission.INTERNET
uses-permission: android.permission.POST_NOTIFICATIONS
uses-permission: android.permission.CAMERA
uses-permission: android.permission.USE_BIOMETRIC
uses-permission: android.permission.USE_FINGERPRINT
uses-permission: android.permission.ACCESS_NETWORK_STATE
uses-permission: android.permission.WAKE_LOCK
uses-permission: com.google.android.c2dm.permission.RECEIVE
permission + uses-permission: cx.m42.unitool.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
```

Те же десять, что в сегодняшнем `play/PRIVACY.md`. Шаги 5–7 разрешений не добавили: Keystore и
системный пикер файлов их не требуют.
