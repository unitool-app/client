---
title: SSH keys
description: The SSH keys screen — what it holds, how to open it, and what a key on the phone is for.
---

# SSH keys

Unitool can keep your SSH keys: the same keys `ssh` and `ssh-keygen` use. The private half stays
on the device, in its secure chip where there is one, and is used only after you confirm. The
public half is shown, copied and shared freely.

## Opening the screen

The SSH keys screen is not in Settings and not in the menu. Only the code you were given opens it:

1. Open the menu and tap **Premium**.
2. Tap **Have a promo code?**, type the code exactly as given — brackets included — and tap
   **Apply**.

The code is exact: a different case, a missing bracket or a space in the middle opens nothing.
Back from the screen goes to Home. To come back later, enter the code again. See
[Promo codes and QR activation](/docs/activation/#using-a-code) for the code screen itself.

## What is on the screen

At the top: *Keys for signing and decrypting, kept in the chip where there is one and under their
passphrase where there is not.* Below it, one row per key:

- the key's name;
- its type and the start of its fingerprint;
- where it lives, in one line — for example *Stored in the secure chip (StrongBox); never leaves
  it.* See [Where keys live](/docs/ssh-keys/storage/).

Tap a row to open the key's card. At the bottom:

| Row | What it does |
|---|---|
| **Add a key** | Imports a key from a file, the clipboard or a QR code, or creates one here. See [Adding a key](/docs/ssh-keys/add/) and [Creating a key](/docs/ssh-keys/create/). |
| **Lock keys now** | Ends every open session at once: the next use of any key asks again. Shown once there is a key. |

## The key card

| Section | What it shows |
|---|---|
| **Where it lives** | Where the private half is kept, any note about it, and **Ask to confirm**: every use, or once for five minutes. |
| **Public key** | The public line, as in a `.pub` file. **Copy** puts it on the clipboard; **Share** sends it as a `<name>.pub` file. |
| **Apps that may use it** | Each app you allowed, and for what. **Revoke** takes the permission back. |
| **Recent use** | The last 20 uses: when, by whom, what, and how it ended. |

Under the sections: **Change passphrase** (only for a key kept under its passphrase),
**Seal secrets with this key** (desktop and web only) and **Delete**. See
[Using a key](/docs/ssh-keys/use/).

## What a key here is for today

- **Opening files encrypted to it.** The 2FA app's **Import from a file** and
  [restoring a backup](/docs/ssh-keys/backup/) open `.age` files with a key on this device.
- **Signing for an app**, in that app's own name. Apps sign only in the SSH signature format with a
  namespace (`ssh-keygen -Y sign`), so an app's signature can never be passed off as an SSH login.
- **Sealing the app's secrets on the desktop and the web**, where there is no chip. See
  [Desktop and web](/docs/ssh-keys/storage/#desktop-and-web).

## What it never does

- **The private key never leaves.** There is no export, no copy and no backup of it. A key you
  imported still exists where you imported it from; a key created here exists only here.
- **No app uses a key without your permission**, and no permission covers more than one app and
  one purpose. A configuration, a notification or a link cannot grant one.
- **Nothing is used without asking.** Every use shows who asks, with which key and for what.
