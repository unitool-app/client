---
title: How your data is kept
description: Where Unitool keeps your data, how the app lock works, and what leaves the phone.
---

# How your data is kept

This page is the plain-language version. The [privacy policy](/privacy/) is the complete list,
permission by permission.

## On the phone

Everything Unitool keeps is in the app's private storage: your language, the switches in Settings,
what is on Home, the last exchange-rate table — and the 2FA accounts.

**2FA accounts are never written in readable form.** Before they reach storage they are encrypted
(AES-256-GCM) with a key that the phone's secure hardware creates and keeps:

| Phone | Where the key lives |
|---|---|
| Android | the Android Keystore — in secure hardware on phones that have it |
| iPhone | the Secure Enclave |

The key cannot be read out — not by the app, and not by anyone who copies the app's files. A copy
of the data taken off the phone holds the accounts only as ciphertext. The key is deleted with the
app and cannot be moved to another phone; the [encrypted export](/docs/two-factor/#exporting-your-accounts)
is how accounts move.

What you type into the calculator or the converter is never stored.

## The app lock

Tools that hold secrets — today, the 2FA app — open only after the phone confirms it is you, with
a fingerprint, a face, or the screen lock's PIN, pattern or password. The check is the operating
system's own: it shows the prompt, compares, counts the attempts, and tells Unitool only *yes* or
*no*. Unitool never sees anything about your fingerprint or face, and has no PIN of its own.

The *yes* is kept in memory only. It is forgotten:

- when the screen turns off;
- when you have been in another app longer than **Settings → Protection → Lock after leaving the
  app** (immediately, 30 seconds, 1 minute — the default — or 5 minutes);
- when the app is closed;
- when you tap **Lock now** in Settings.

Showing a key, exporting and deleting ask again whatever the timer says. On Android, while a
protected tool is on screen, the system is told not to allow screenshots or screen recording and
not to show it in the recent-apps preview.

If the phone has no screen lock, there is nothing to check with: the tool opens and says, above
its screen, that it is not protected, with a **Set up** button that opens the phone's settings.

## What leaves the phone

Only these, and only when the tool that needs them is used:

| What | When | To |
|---|---|---|
| A request for the whole exchange-rate table | you open the converter | `open.er-api.com` or `api.frankfurter.app` |
| A GitHub username | you tap **Find keys** in a 2FA export | `api.github.com` |
| A notification registration | you start using a tool that sends notifications | Firebase Cloud Messaging and `unitool.m42.cx` |

No calculation, no converter input and no 2FA key in readable form is ever sent. There is no
analytics, no crash reporter, no advertising ID and no account.

## Notifications

Until you use a tool that sends notifications, Unitool does not ask for the notification
permission, does not start Firebase and registers nothing anywhere. When you do, it registers this
installation — a random id, the push token, the platform, the app version, the language and the
names of the tools that may notify you — and nothing else. Refuse the permission and everything else
works the same. A notification can open a screen in a tool you use; it can never open one you have not activated.

## Reporting a security problem

Write to [{{contact}}](mailto:{{contact}}). Please do not open a public issue for a vulnerability.
