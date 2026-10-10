# Privacy Policy — Unitool

**Last updated: 10 October 2026**

## The short version

Unitool has no account, no sign-in, no ads, no analytics and no crash reporter, and nothing you
type into it is sent anywhere — the one exception being a GitHub username, if you use the 2FA
app's export and type one. Your arithmetic is done on your phone and stays there.

It is not, however, an offline app any more. It has the internet permission, and it uses the
network for exactly four things: the currency converter downloads published exchange rates, the
app can receive push notifications, the 2FA app's export — only when you use it — looks up the
public keys it encrypts to, and — only when you ask it to — the app signs a browser in to our
website. All four are described below, because they are the only parts of this policy with
anything in them.

Notifications start only when you start using a tool that sends them. Until then the app does not
ask for the notification permission, Firebase is not started, and nothing about notifications
leaves the phone. Today the only such tool is a diagnostic one, opened by a code.

## What the app stores

On your device only, in the app's private storage, which on Android only this app can read:

| What | Why |
|---|---|
| The interface language you chose | so the app opens in it next time |
| Whether key presses vibrate | so the app remembers the switch |
| Which apps you put on the home screen | so your home screen survives a restart |
| The last exchange-rate table it downloaded | so the converter still works with no signal |
| The two-factor accounts you add: service, account name, secret key — encrypted, see below | so the 2FA app can show their codes |
| The GitHub username your last 2FA export was encrypted for | so the next export offers it |
| How long the 2FA lock waits before asking again, and any lock switches you changed | so Settings → Protection remembers them |
| Which tools that send notifications you have started using | so notifications reach those tools and no others |
| This installation's registration with our notification server: the id the server gave it and a random secret key, encrypted | so the app can keep that registration current |
| If you signed a browser in to our website: a second such registration — the id and a random secret key, encrypted — and which hidden parts of the app you have opened | so the app can sign browsers in and out, and keep the website's list of your parts current |

The two-factor accounts are never written in readable form. Before they reach storage they are
encrypted (AES-256-GCM) with a key that Android's Keystore generates for this app and keeps: the key
never leaves the Keystore — in secure hardware on phones that have it — and cannot be read out, by
the app or by anyone who copies its files. A copy of the app's data taken off the phone therefore
holds the accounts only as ciphertext. The key is deleted with the app, and it cannot be moved to
another phone, so neither can the accounts in this form; the 2FA app's export (below) is the way to
move them.

Nothing else you type is written anywhere. What you enter into the calculator or the converter
exists while the app is open and is gone when it closes. A two-factor account is kept only once you
tap Save, and its codes are computed on the phone from the stored key — no code is ever sent
anywhere, and a key leaves the phone only inside an export you make and send yourself, encrypted
(see below).

## The 2FA lock

The 2FA app opens only after your phone confirms it is you — with your fingerprint, your face, or
your screen lock's PIN, pattern or password. That check is Android's own: the system shows the
sheet, compares the fingerprint or face, counts the attempts, and tells the app only "yes" or "no".
The app never receives, stores or sends anything about your fingerprint or face, and it has no PIN
of its own.

The "yes" is kept in memory only. It is forgotten when the screen turns off, when you have been in
another app for longer than the time set in Settings → Protection (a minute unless you change it),
and whenever the app is closed. Showing an account's key, exporting the accounts and deleting one
ask again. While the 2FA app is on screen, Android is told not to allow screenshots or screen
recording of it and not to show it in the recent-apps preview.

A code you copy is marked as sensitive, which on Android 13 and later keeps it out of the clipboard
preview, and is cleared from the clipboard a minute later if nothing else has been copied since —
when Android lets the app see that, which it does only while the app is in front.

If your phone has no screen lock, there is nothing to confirm with: the 2FA app opens anyway and
says, above its screen, that it is not protected.

## What leaves the device

Six things, and this is all of them. Three only once you have started using a tool that sends
notifications, and the last only once you sign a browser in to our website.

**Exchange rates.** When you open the currency converter it requests a rate table published by
ExchangeRate-API, from `open.er-api.com` (or the European Central Bank's, from `api.frankfurter.app`,
if the converter has been set up to use that one).
The request asks for the whole table and carries nothing of yours — no amount, no currency you
picked, no identifier. As with any request to any server, the provider can see the address it came
from. The answer is cached on your device, and a cached table is used without asking when you are
offline.

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

**A 2FA export, if you make one.** The 2FA app can export your two-factor accounts for moving them
to a computer. You type a GitHub username and tap "Find keys"; the app then requests that account's
public SSH keys from `api.github.com` — the same list anyone can read at `github.com/<username>.keys`.
The request carries the username and nothing else, and GitHub, like any server, sees the address it
came from. Your accounts are then encrypted **on the phone** to the keys you tick, in the open
[age](https://age-encryption.org) format, so only the holder of one of those private keys can read
them. The encrypted file goes only where you send it — through Android's share menu, or the
clipboard if you copy it — and we never receive it. The unencrypted keys never leave the phone.

**A website sign-in, if you make one.** Settings → Website signs a browser in to
`unitool.m42.cx`, so that it can show you the documentation of the hidden parts of the app you have
opened. You start it by scanning the code the website shows, and the app asks before it does
anything. The first time, the app registers this installation with our server: a random id, a
one-way hash of a secret key that stays on the phone, whether it is Android or iOS, the app's
version, and the internal names of the hidden parts you have opened — nothing else, and no name or
account. It sends that list again when it changes. For each browser you sign in, the server keeps
the browser's own description of itself (its user-agent), the address it signed in from, when it
signed in and was last seen, and which pages of the website it opened and when. Page visits are
deleted after 90 days; a browser stays signed in until you sign it out — on the website or in
Settings → Website — or until 30 days after its last visit. Nothing is recorded about a browser
that is not signed in.

That is the list. No calculation, no converter input and no two-factor key in readable form is
transmitted.

## What the app collects

For itself: only the notification registration described above, and only once you use a tool that
sends notifications; and the website registration and its record of signed-in browsers and the
pages they opened, only once you sign a browser in. There is no analytics SDK and no crash reporter
in the app, and our server receives nothing else.

In the terms Google Play's Data safety form uses:

- **No personal information** — no name, email, phone number, address or account of any kind.
- **No financial information.** The converter is a calculator over public rates; it has no idea
  whose money it is.
- **No location**, precise or approximate.
- **No contacts, photos, files, messages, calendar or call logs.**
- **No advertising ID**, and no profiling or advertising of any kind.
- **Device or other IDs — yes**: the Firebase Cloud Messaging registration token and the Firebase
  installation ID described above, and the random id our server gives the installation, used only
  to deliver notifications — and only once you use a tool that sends them; and the random id of the
  website registration, only once you sign a browser in.
- **App activity — only on our website, only when signed in**: which pages of the website a browser
  signed in with your app opened, kept 90 days. Nothing about what you do inside the app.
- **No diagnostics, crash logs or analytics.**

Nothing is sold, and nothing is shared with anyone beyond what delivering a notification requires
and the GitHub key lookup you start yourself.

## Permissions

Ten, which is all of them. This list was read off the built app, not off the source, because six
of them are added by libraries rather than written by us — three by Firebase messaging, two by
AndroidX Biometric, one by AndroidX Core — and a permission list that omits those would be wrong in
exactly the way nobody checks.

Asked for, and refusable:

- `android.permission.POST_NOTIFICATIONS` — on Android 13 and later, as a dialog rather than
  silently, and only when you start using a tool that shows notifications — never when the app
  first opens. You can refuse it, and Settings → Notifications can ask again later. The app works exactly as before if you do; you simply do not see
  notifications.
- `android.permission.CAMERA` — only when you tap "Scan QR code" in the 2FA app, to read the setup
  code a site shows you, or "Scan a QR code" on the Activate screen, to read an activation code.
  The picture is examined on the phone as it arrives and is never saved or sent; the one thing
  that comes out of it is the text of the code. In the 2FA app that text is kept only if you then
  tap Save; on the Activate screen it opens the tool it names. Refuse it and nothing else changes:
  the same text can be pasted instead.

Granted at install time, never prompted for:

- `android.permission.INTERNET` — to fetch exchange rates, to receive notifications and register
  for them with our server and, when you export 2FA accounts, to fetch the public keys of the GitHub
  account you name.
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
  Biometric, so that the 2FA lock can show Android's own fingerprint and face sheet (see "The 2FA
  lock" above). They let the app *ask* the system to check you; the check, and everything about
  your fingerprint or face, stays with the system. `USE_FINGERPRINT` is the older name of the same
  thing, for Android 8 and earlier.
- `cx.m42.unitool.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` — added by AndroidX Core. It is this
  app's own permission, granted to nothing but this app, and it keeps its internal signals from
  being sent to it by anyone else.

## Notifications

A push message carries two things: the text you see, and routing data saying which screen in the
app a tap should open. It carries nothing about you, and the app sends nothing back when one
arrives. Messages go only to tools you use: the server does not send them for any other, and the
app drops one that arrives anyway. You can turn notifications off in Android's own settings for the app at any time, and the
rest of the app is unaffected.

## Backup

If Android's own backup is enabled on your device, the settings above may be included in your
device backup, held by Google under your Google account and your device's own encryption. That is
Android's backup, not ours — we never see it. Nothing else in the app is included; see
`composeApp/src/androidMain/res/xml/data_extraction_rules.xml` for exactly what is and is not.

## Children

The app is a calculator, a currency converter and a two-factor code generator. It contains no ads, no purchases, no user
content, no user-to-user communication and no way to sign in, and it collects no personal
information — including from children.

## Changes

If this policy ever changes, the date at the top changes with it, and a new version ships in the
same place as the app.

## Contact

Questions about this policy, or about anything the app does with data:

**privacy@m42.cx**

Play publishes that address on the store page and checks that it is reachable when a user complains
through the Console, so it has to be a mailbox somebody actually reads — not an alias that bounces.
