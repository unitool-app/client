# Privacy Policy — Unitool

**Last updated: 20 September 2026**

## The short version

Unitool collects nothing, sends nothing and has no servers. It has no network permission, so it
could not transmit anything even if it tried.

## What the app stores

Two settings, on your device only:

| What | Why |
|---|---|
| The interface language you chose | so the app opens in it next time |
| Whether key presses vibrate | so the app remembers the switch |

They live in the app's private storage, which on Android only this app can read. Nothing you type
into the calculator is written anywhere — it exists while the app is open and is gone when it
closes.

## What the app collects

Nothing. Specifically, and in the terms Google Play's Data safety form uses:

- **No personal information** — no name, email, phone number, address or account of any kind.
- **No financial information.**
- **No location**, precise or approximate.
- **No contacts, photos, files, messages, calendar or call logs.**
- **No device or other identifiers** — no advertising ID, no device ID, no installation ID.
- **No app activity, diagnostics, crash logs or analytics.** There is no analytics SDK and no
  crash reporter in the app.

No data is shared with anyone, because no data is gathered to share.

## Permissions

One, and it is the only one:

- `android.permission.VIBRATE` — the short tap you feel when a key is pressed. It is granted at
  install time, is not capable of reading anything, and the vibration itself can be turned off in
  Settings.

## Backup

If Android's own backup is enabled on your device, the two settings above may be included in your
device backup, held by Google under your Google account and your device's own encryption. That is
Android's backup, not ours — we never see it. Nothing else in the app is included; see
`composeApp/src/androidMain/res/xml/data_extraction_rules.xml` for exactly what is and is not.

## Children

The app is a calculator. It contains no ads, no purchases, no user content, no links out and no
means of communication, and it collects nothing — including from children.

## Changes

If this policy ever changes, the date at the top changes with it, and a new version ships in the
same place as the app.

## Contact

<!-- Required by Google Play: replace with a real address you read before the listing goes live. -->
**TODO: contact email address**
