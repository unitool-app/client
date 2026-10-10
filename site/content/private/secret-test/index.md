---
title: The secret test app
description: How secret apps behave — opened only by their code, for one visit, never listed — and the test app that shows it.
---

# The secret test app

A **secret app** is a tool that only its code opens, and only for one visit. Nothing in Unitool
lists it or leads to it. The secret test app — titled **Secret app** — exists to show what that is
like from the inside, and that nothing on the outside gives it away.

## Opening it

1. Open the menu and tap **Premium**.
2. Tap **Have a promo code?**, type the code you were given and tap **Apply**.

The app opens at once. It is not added to Home or to **All apps**. To open it again, enter the code
again.

## What it shows

| Section | What is in it |
|---|---|
| **This visit** | *Visit N*, and when the previous visit began — or *The first one on this device* |
| **Where you will not find it** | the list of places it is absent from, as below |
| **What it keeps** | *Its data stays between visits and goes into the backup, but the backup's list does not show it.* Under it, `mode = promo`: the configuration the code opened it with |

The visit counter is kept in the app's own storage. It going up by one each time is the proof that
a secret app's data outlives the visit.

## How secret apps behave

### The code is the only way in

- A secret app is not in **All apps**, on Home, in the menu, in Settings or in Storage & security.
- No QR code and no link opens it, even one that names it: Unitool answers as it would for a tool
  that does not exist. Another tool cannot open it either, and cannot see it in the list of tools.
- A secret app cannot receive notifications, so a notification can never open it.
- Codes are kept in the app only as one-way hashes; the list of codes cannot be read out of it.

### One visit at a time

A visit ends when you:

- go back;
- choose **Home** or another tool in the menu;
- close Unitool, or the system closes it.

Opening **Settings** from the menu does not end the visit; back from Settings returns to the app.
Switching to another app on the phone for a moment does not end it either. After **more than five
minutes** in the background, it closes, and Unitool comes back on Home.

### What stays

The app's data stays on the device between visits. It is included in a
[backup](/docs/ssh-keys/backup/) without a line of its own, and a restore always brings it back with
the rest.

### What "secret" does not cover

A code belongs to the build, not to a person: anyone who knows it and holds your unlocked phone can
open the app and see its data. Only a secret app with a lock of its own — like the 2FA app's,
see [the app lock](/docs/security/#the-app-lock) — keeps them out. The secret test app has none.
