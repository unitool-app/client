# Privacy Policy — 37tool

**Last updated: 14 November 2026**

37tool is the app with the package name `cx.m42.unitool`. It was called Unitool until November
2026, and our server and website still use that name (`unitool.m42.cx`).

## The short version

37tool has no account, no sign-in to the app, no ads, no analytics and no crash reporter. What you
keep in its tools — calculations, counters, two-factor accounts, passwords — is kept on your phone,
and the two-factor accounts and the passwords are stored encrypted.

Something leaves the phone only in these cases, each described below:

- the currency converter downloads published exchange rates;
- once you use a tool that sends notifications, the app registers for them with Firebase and with
  our server;
- when you encrypt an export or a backup to someone's GitHub keys, the app looks those public keys
  up on GitHub;
- when you sign a browser in to our website with the app, the app and our server do that;
- when you have paired a computer with the Passwords tool, the phone gives it a password you
  confirm, over your local network and encrypted;
- a file, a code or a link that you yourself share, copy, save or open.

Nothing you keep in the app is ever sent to us.

## The tools

The app is a host for small tools. A fresh install has the calculator on its home screen; the
others are added from the "+" list:

- **Calculator** and **Currency converter**;
- **2FA codes** — one-time sign-in codes (TOTP) for your accounts;
- **Passwords** — your logins and passwords, and handing one to a computer you have paired;
- **QR scanner** — reads what a QR code holds;
- **Counters**, **Date difference** and **English** (English verbs, read aloud).

A few more are opened only by a code typed under Activate: a screen for SSH keys, a tool that
restores a secret from QR codes or a file, and two test tools. They are covered here too.

## What the app stores

On your device only, in the app's private storage, which on Android only this app can read.

| What | Why |
|---|---|
| The interface language, whether key presses vibrate, which tools are on the home screen and which you have unlocked with a code | so the app opens as you left it |
| The last exchange-rate table it downloaded | so the converter still works with no signal |
| **Encrypted:** the two-factor accounts you add — service, account name, secret key | so the 2FA tool can show their codes |
| **Encrypted:** the passwords you keep — title, username, password, website, note, date changed | so the Passwords tool can show them |
| **Encrypted:** the computers you paired with Passwords — their public keys and names, the phone's own key for talking to them, and a log of their last 20 requests (time, website, outcome) | so a paired computer can ask, and you can see what it asked |
| The GitHub username your last 2FA or password export was encrypted for, and the password generator's settings | so the next export and the next password start from them |
| Your counters — their names and values | so the Counters tool keeps them |
| The Date difference tool's switches, and in English the verbs you chose and the words you have seen | so those tools keep your place |
| How long the lock waits before asking again, and any lock switches you changed | so Settings → Protection remembers them |
| When you last made a backup | so Settings → Backup can say so |
| If you open the SSH keys screen: the keys you add or create (see below), their names, other devices' public keys you save, and a log of what used a key and when | so the keys can be used and you can see how they were |
| Which tools that send notifications you have started using, and this installation's registration with our notification server — the id the server gave it and a random secret key, encrypted | so notifications reach those tools and no others |
| If you signed a browser in to our website: a second such registration — the id and a random secret key, encrypted — and which Premium features are active in the app | so the app can sign browsers in and out, and keep the website's list of them current |

**Encryption.** The two-factor accounts, the passwords, the paired computers and the registration
keys are never written in readable form. Before they reach storage they are encrypted
(AES-256-GCM) with a key that Android's Keystore generates for this app and keeps: the key never
leaves the Keystore — in secure hardware on phones that have it — and cannot be read out, by the
app or by anyone who copies its files. A copy of the app's data taken off the phone therefore holds
them only as ciphertext. The key is deleted with the app and cannot be moved to another phone; an
export or a backup (below) is the way to move them.

**SSH keys** are kept apart from the rest. A key created on the phone, or imported into its most
protected level, lives in the Keystore itself and never leaves it; a key kept as a file is sealed
with a Keystore key; both need your fingerprint, face or screen lock each time they are used. A
key kept at the lowest level stays under the passphrase you give it.

**Where you left off.** When you leave the app, the screen of the tool you were in may be kept for
up to 24 hours, so that the app reopens on it: for example the number on the calculator, the dates
in Date difference, the search and the half-filled service and account name in 2FA codes, or the
search and the half-filled title, username and website of a new entry in Passwords — never a
password, a note or a two-factor key, and nothing from the restore tool.

Nothing else is written anywhere. What you type into the calculator or the converter, a QR code
you scan, and a file you open to import are used on the phone and not kept.

## The lock

2FA codes and Passwords open only after your phone confirms it is you — with your fingerprint, your
face, or your screen lock's PIN, pattern or password. So do using an SSH key, making a backup, and
the restore tool while it shows a result. That check is Android's own: the system shows the sheet,
compares the fingerprint or face, counts the attempts, and tells the app only "yes" or "no". The
app never receives, stores or sends anything about your fingerprint or face, and it has no PIN of
its own.

The "yes" is kept in memory only. It is forgotten when the screen turns off, when you have been in
another app for longer than the time set in Settings → Protection, and whenever the app is closed.
Passwords asks each time it is opened; in 2FA codes, showing an account's key, exporting and
deleting ask again. While 2FA codes, Passwords or the
restore tool is on screen, Android is told not to allow screenshots or screen recording of it and
not to show it in the recent-apps preview.

If your phone has no screen lock, there is nothing to confirm with: the 2FA tool opens anyway and
says, above its screen, that it is not protected.

## The clipboard

Copying is always something you do, and what you copy goes to Android's clipboard, where other apps
may be able to read it. The app limits that where it can:

- a two-factor code, a password, and a secret shown by the restore tool are marked as sensitive,
  which on Android 13 and later keeps them out of the clipboard preview, and are cleared from the
  clipboard a minute later if nothing else has been copied since — when Android lets the app see
  that, which it does only while the app is in front;
- a username copied in Passwords is marked as sensitive but not cleared;
- text or data copied from a QR code (including a Wi-Fi password it holds), an export copied as
  text, and a public key are neither marked nor cleared;
- a private key pasted into the SSH keys screen is wiped from the clipboard once it is imported.

## What leaves the device

This is all of it.

**Exchange rates.** When you open the currency converter it requests a rate table published by
ExchangeRate-API, from `open.er-api.com` (or the European Central Bank's, from `api.frankfurter.app`,
if the converter has been set up to use that one). The request asks for the whole table and carries
nothing of yours — no amount, no currency you picked, no identifier. As with any request to any
server, the provider can see the address it came from. The answer is cached on your device, and a
cached table is used without asking when you are offline.

**A push registration token.** The app uses Firebase Cloud Messaging so that notifications can
reach it. Firebase is not started until you begin using a tool that sends notifications. Then, to
make delivery possible, Firebase creates a registration token and a Firebase installation ID and
sends them to Google. These identify this installation of this app on this device — they are
not a name, an email or an account, and they are not tied to you. Both are regenerated if you
reinstall the app or clear its data. Google's handling of them is covered by the
[Firebase privacy documentation](https://firebase.google.com/support/privacy) and the
[Google Privacy Policy](https://policies.google.com/privacy).

**A topic subscription.** So that a message on a subject reaches the phones that want it, the app
tells Firebase which topics this installation is subscribed to — only for tools you use. A topic is
a word, not a profile.

**A registration with our notification server.** So that a notification can be addressed to this
installation, the app registers it with our server at `unitool.m42.cx`. The registration carries:
the push registration token above; whether the phone runs Android or iOS; the app's version; the
interface language; and the internal names of the tools on this phone that send notifications and
that you use. The server gives the installation a random id. To prove later updates come from the
same installation, the app makes a random secret key on the phone and sends only a one-way hash of
it; the key itself never leaves the phone. Nothing else is sent: no name, no account, nothing you
type. The registration is updated when the app starts and when the token or the list of tools
changes. When you stop using the last such tool, the app tells the server the list is now empty and
the server sends this installation nothing. Clearing the app's data or reinstalling it makes a new
registration; the old one receives nothing again.

**A GitHub key lookup, if you encrypt to one.** The 2FA export, the Passwords export and Settings →
Backup can encrypt what they make to the public SSH keys of a GitHub account. You type the
username and tap "Find keys"; the app then requests that account's public keys from
`api.github.com` — the same list anyone can read at `github.com/<username>.keys`. The request
carries the username and nothing else, and GitHub, like any server, sees the address it came from.

**Exports and backups you make.** Three things in the app make a file of what you keep in it:

- the 2FA export — your two-factor accounts;
- the Passwords export — your passwords, as a CSV file of the kind browsers import;
- Settings → Backup — the settings and the data of every tool, including the two-factor accounts,
  the passwords and the paired computers, so that a new phone can be set up from it. SSH private
  keys are not in it, only their names.

Each is encrypted **on the phone**, in the open [age](https://age-encryption.org) format, to the
public keys you tick, so only the holder of one of the matching private keys can read it; a backup
also asks your phone to confirm it is you first. The file goes only where you send it — through
Android's share menu, the clipboard if you copy it, or a place you choose to save it — and we
never receive it. The same goes for an SSH key you send to another device (encrypted to that
device's key), a public key you share, and the report of Settings → Storage & security (the app's
version and build, the platform, and the results of its storage checks), which you can share or
copy.

**A password to your own computer, if you pair one.** Passwords can give a password to a browser on
a computer you have paired with the phone, through our browser extension or command-line tool on
that computer. Nothing of this goes through the internet or through us:

- Pairing starts on the computer, which shows a QR code; you scan it with the phone and confirm.
- The phone then listens for that computer on your local network — on port 47370, or another free
  port — and announces itself there (mDNS, service `_unitool._tcp`, under a random name). It does
  so only while the Passwords list is on screen, the app is in front, and a computer is paired or
  being paired; it stops when you leave.
- The connection is encrypted end to end (the Noise protocol), and only the paired computer's key
  is accepted.
- A request names the website the browser is on. You choose the entry and confirm with your
  fingerprint, face or screen lock each time; only then does the phone send that entry's username
  and password, encrypted once more to the computer's SSH key.
- The phone tells the computer a device name ("Unitool · Android") and a link id, and keeps
  the last 20 requests in its log (see "What the app stores").

**A website sign-in, if you make one.** Settings → Website signs a browser in to
`unitool.m42.cx`, so that it can show you documentation for your Premium features. You start it by
scanning the code the website shows, and the app asks before it does anything. The first time, the
app registers this installation with our server: a random id, a one-way hash of a secret key that
stays on the phone, whether it is Android or iOS, the app's version, and the internal names of the
Premium features active on it — nothing else, and no name or account. It sends that list again
when it changes. For each browser you sign in, the server keeps the browser's own description of
itself (its user-agent), the address it signed in from, when it signed in and was last seen, and
which pages of the website it opened and when. Page visits are deleted after 90 days; a browser
stays signed in until you sign it out — on the website or in Settings → Website — or until 30 days
after its last visit. Nothing is recorded about a browser that is not signed in.

**Things you open.** "Open" on a link, an email address, a phone number or a place read from a QR
code hands it to the app on your phone that handles it. The English tool hands the phrases it reads
aloud — its own built-in text, nothing of yours — to your phone's text-to-speech engine, and picks a
voice that works without the network when the phone has one.

That is the list. No calculation, no converter input, no counter, and no two-factor key or password
in readable form is ever sent to us.

## What the app collects

For itself: only the notification registration described above, and only once you use a tool that
sends notifications; and the website registration and its record of signed-in browsers and the
pages they opened, only once you sign a browser in. There is no analytics SDK and no crash reporter
in the app, and our server receives nothing else. The passwords given to a paired computer go to
your computer, not to us.

In the terms Google Play's Data safety form uses:

- **No personal information** — no name, email, phone number, address or account of any kind.
- **No financial information.** The converter is a calculator over public rates; it has no idea
  whose money it is.
- **No location**, precise or approximate.
- **No contacts, messages, calendar or call logs.** A photo or a file you pick — to read a QR code
  from it or to import from it — is read on the phone and neither kept nor sent.
- **No advertising ID**, and no profiling or advertising of any kind.
- **Device or other IDs — yes**: the Firebase Cloud Messaging registration token and the Firebase
  installation ID described above, and the random id our server gives the installation, used only
  to deliver notifications — and only once you use a tool that sends them; and the random id of the
  website registration, only once you sign a browser in.
- **App activity — only on our website, only when signed in**: which pages of the website a browser
  signed in with your app opened, kept 90 days. Nothing about what you do inside the app.
- **No diagnostics, crash logs or analytics.**

Nothing is sold, and nothing is shared with anyone beyond what delivering a notification requires,
the GitHub key lookups you start yourself, and what you send yourself.

## Permissions

Eleven, which is all of them. This list was read off the built app, not off the source, because six
of them are added by libraries rather than written by us — three by Firebase messaging, two by
AndroidX Biometric, one by AndroidX Core — and a permission list that omits those would be wrong in
exactly the way nobody checks.

Asked for, and refusable:

- `android.permission.POST_NOTIFICATIONS` — on Android 13 and later, as a dialog rather than
  silently, and only when you start using a tool that shows notifications — never when the app
  first opens. You can refuse it, and Settings → Notifications can ask again later. The app works
  exactly as before if you do; you simply do not see notifications.
- `android.permission.CAMERA` — only when you open a QR code scanner yourself: in the QR scanner,
  in 2FA codes and Passwords to add from a code, to pair a computer, on the Activate screen, in
  Settings → Website, and on the screens opened by a code. The picture is examined on the phone as
  it arrives and is never saved or sent; the one thing that comes out of it is what the code holds.
  Refuse it and nothing else changes: every scanner can also read a photo or a file you pick, or
  take the text pasted.

Granted at install time, never prompted for:

- `android.permission.INTERNET` — to fetch exchange rates, to receive notifications and register
  for them with our server, to look up the GitHub keys an export or a backup is encrypted to, to
  sign a browser in to our website, and to talk to a paired computer on your local network.
- `android.permission.CHANGE_WIFI_MULTICAST_STATE` — so that a paired computer can find the phone
  on your Wi-Fi while the Passwords list is open (mDNS, above). It lets the app receive the local
  network's announcements; it does not reveal which network you are on or where you are.
- `android.permission.VIBRATE` — the short tap you feel when a key is pressed. It cannot read
  anything, and the vibration itself can be turned off in Settings.
- `android.permission.ACCESS_NETWORK_STATE` — added by Firebase, to tell whether there is a
  connection before trying to use one. It reports whether the device is online, not what it is
  connected to and not where it is.
- `android.permission.WAKE_LOCK` — added by Firebase, so that delivering a message can finish if
  it arrives as the screen goes off.
- `com.google.android.c2dm.permission.RECEIVE` — added by Firebase. It is what allows this app,
  and only this app, to be handed its own messages.
- `android.permission.USE_BIOMETRIC` and `android.permission.USE_FINGERPRINT` — added by AndroidX
  Biometric, so that the lock can show Android's own fingerprint and face sheet (see "The lock"
  above). They let the app *ask* the system to check you; the check, and everything about your
  fingerprint or face, stays with the system. `USE_FINGERPRINT` is the older name of the same
  thing, for Android 8 and earlier.
- `cx.m42.unitool.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` — added by AndroidX Core. It is this
  app's own permission, granted to nothing but this app, and it keeps its internal signals from
  being sent to it by anyone else.

## Notifications

A push message carries two things: the text you see, and routing data saying which screen in the
app a tap should open. It carries nothing about you, and the app sends nothing back when one
arrives. Messages go only to tools you use: the server does not send them for any other, and the
app drops one that arrives anyway. You can turn notifications off in Android's own settings for the
app at any time, and the rest of the app is unaffected.

## Backup

**Android's own backup.** The app limits Android's device backup and phone-to-phone transfer to a
single settings file (see `composeApp/src/androidMain/res/xml/data_extraction_rules.xml`). None of
the tools' data — no two-factor account, password, key, counter or progress — is in it. Where
Android backs that file up, it is held by Google under your Google account and your device's own
encryption; we never see it.

**The app's Backup.** Settings → Backup makes a file of everything the tools keep, encrypted to the
keys you choose, and restores from one — see "Exports and backups you make" above. It is made only
when you ask, goes only where you send it, and we never receive it.

## Children

The app is a set of everyday tools: a calculator, a currency converter, two-factor codes, a
password keeper, a QR scanner, counters, a date calculator and English verbs. It contains no ads,
no purchases, no user content shared with others, no user-to-user communication and no account to
sign in to, and it collects no personal information — including from children.

## Changes

If this policy ever changes, the date at the top changes with it, and a new version ships in the
same place as the app.

## Contact

Questions about this policy, or about anything the app does with data:

**privacy@m42.cx**

Play publishes that address on the store page and checks that it is reachable when a user complains
through the Console, so it has to be a mailbox somebody actually reads — not an alias that bounces.
