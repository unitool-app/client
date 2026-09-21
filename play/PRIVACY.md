# Privacy Policy — Unitool

**Last updated: 21 September 2026**

## The short version

Unitool has no account, no sign-in, no ads, no analytics and no crash reporter, and nothing you
type into it is sent anywhere. Your arithmetic is done on your phone and stays there.

It is not, however, an offline app any more. It has the internet permission, and it uses the
network for exactly two things: the currency converter downloads published exchange rates, and the
app can receive push notifications. Both are described below, because they are the only parts of
this policy with anything in them.

## What the app stores

On your device only, in the app's private storage, which on Android only this app can read:

| What | Why |
|---|---|
| The interface language you chose | so the app opens in it next time |
| Whether key presses vibrate | so the app remembers the switch |
| Which apps you put on the home screen | so your home screen survives a restart |
| The last exchange-rate table it downloaded | so the converter still works with no signal |

Nothing you type is written anywhere. What you enter into the calculator or the converter exists
while the app is open and is gone when it closes.

## What leaves the device

Three things, and this is all of them.

**Exchange rates.** When you open the currency converter it requests a rate table published by the
European Central Bank, from `api.frankfurter.app` (or the documented fallback, `open.er-api.com`).
The request asks for the whole table and carries nothing of yours — no amount, no currency you
picked, no identifier. As with any request to any server, the provider can see the address it came
from. The answer is cached on your device, and a cached table is used without asking when you are
offline.

**A push registration token.** The app uses Firebase Cloud Messaging so that notifications can
reach it. To make that possible, Firebase creates a registration token and a Firebase installation
ID and sends them to Google. These identify this installation of this app on this device — they are
not a name, an email or an account, and they are not tied to you. Both are regenerated if you
reinstall the app or clear its data. Google's handling of them is covered by the
[Firebase privacy documentation](https://firebase.google.com/support/privacy) and the
[Google Privacy Policy](https://policies.google.com/privacy).

**A topic subscription.** So that a message about, say, exchange rates reaches the phones that want
it, the app tells Firebase which topics this installation is subscribed to. A topic is a word, not
a profile.

That is the list. No calculation, no converter input and no content you enter is transmitted.

## What the app collects

For itself: nothing. There is no analytics SDK and no crash reporter in the app, and there is no
server of ours for anything to arrive at.

In the terms Google Play's Data safety form uses:

- **No personal information** — no name, email, phone number, address or account of any kind.
- **No financial information.** The converter is a calculator over public rates; it has no idea
  whose money it is.
- **No location**, precise or approximate.
- **No contacts, photos, files, messages, calendar or call logs.**
- **No advertising ID**, and no profiling or advertising of any kind.
- **Device or other IDs — yes, one kind**: the Firebase Cloud Messaging registration token and the
  Firebase installation ID described above, used only to deliver notifications.
- **No app activity, diagnostics, crash logs or analytics.**

Nothing is sold, and nothing is shared with anyone beyond what delivering a notification requires.

## Permissions

Six, which is all of them. This list was read off the built app, not off the source, because
three of them are added by the Firebase messaging library rather than written by us — and a
permission list that omits those would be wrong in exactly the way nobody checks.

Asked for, and refusable:

- `android.permission.POST_NOTIFICATIONS` — on Android 13 and later, as a dialog rather than
  silently. You can refuse it. The app works exactly as before if you do; you simply do not see
  notifications.

Granted at install time, never prompted for:

- `android.permission.INTERNET` — to fetch exchange rates and to receive notifications.
- `android.permission.VIBRATE` — the short tap you feel when a key is pressed. It cannot read
  anything, and the vibration itself can be turned off in Settings.
- `android.permission.ACCESS_NETWORK_STATE` — added by Firebase, to tell whether there is a
  connection before trying to use one. It reports whether the device is online, not what it is
  connected to and not where it is.
- `android.permission.WAKE_LOCK` — added by Firebase, so that delivering a message can finish if
  it arrives as the screen goes off.
- `com.google.android.c2dm.permission.RECEIVE` — added by Firebase. It is what allows this app,
  and only this app, to be handed its own messages.

## Notifications

A push message carries two things: the text you see, and routing data saying which screen in the
app a tap should open. It carries nothing about you, and the app sends nothing back when one
arrives. You can turn notifications off in Android's own settings for the app at any time, and the
rest of the app is unaffected.

## Backup

If Android's own backup is enabled on your device, the settings above may be included in your
device backup, held by Google under your Google account and your device's own encryption. That is
Android's backup, not ours — we never see it. Nothing else in the app is included; see
`composeApp/src/androidMain/res/xml/data_extraction_rules.xml` for exactly what is and is not.

## Children

The app is a calculator and a currency converter. It contains no ads, no purchases, no user
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
