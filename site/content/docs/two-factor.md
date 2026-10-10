---
title: 2FA codes
description: Time-based two-factor codes — adding accounts, the lock, exporting to GitHub SSH keys and moving to a new phone.
---

# 2FA codes

The 2FA app shows the six- or eight-digit codes that sites ask for after your password — the same
codes Google Authenticator or Authy show. It works offline: a code is computed on the phone from a
key and the current time ([RFC 6238](https://www.rfc-editor.org/rfc/rfc6238)).

Open it from **+** on Home; it is not there on a fresh install.

## Adding an account

When a site turns on two-factor sign-in, it shows a QR code and usually a key in text. Tap
**Add account**, then one of:

- **Scan QR code** — point the camera at the site's QR code. *(Phones only.)*
- **Link** — paste the `otpauth://` link the QR code contains. Several links, one per line, add
  several accounts at once.
- **Enter key** — type the service, the account and the key by hand. The key is Base32: letters
  A–Z and digits 2–7. **Parameters** holds the algorithm (SHA1, SHA256 or SHA512), the number of
  digits (6 to 8) and the period (1 to 300 seconds); leave them alone unless the site says
  otherwise.

The form shows the **current code** before you save, so you can type it into the site to finish
setting up. Nothing is stored until you tap **Save**.

Counter-based codes (HOTP) are not supported.

## Using codes

The list shows every account with its code and the seconds until it changes. **Tap a code to copy
it.** The copied code is marked as sensitive, so Android 13 and later do not show it in the
clipboard preview, and it is cleared from the clipboard a minute later if nothing else has been
copied since.

**Search** filters the list. The **›** on an account opens its details: parameters, **Rename**,
**Show key** and **Delete**.

## The lock

The 2FA app opens only after the phone confirms it is you — fingerprint, face, or the screen lock's
PIN, pattern or password. Showing a key, exporting and deleting ask again. See
[the app lock](/docs/security/#the-app-lock) for when it locks and what it guards against.

## Importing from Google Authenticator

In Google Authenticator: **Transfer accounts → Export accounts**. It shows one or more QR codes.
In Unitool: **Add account → Scan QR code** and scan the first. The import page lists the accounts it
found and counts the codes (*1 of 3 QR codes scanned*); tap **Scan another QR code** for each of the
rest, then **Add**. Accounts already on the phone are marked *Already on this device*.

## Exporting your accounts

**Export keys** writes every account into one file, encrypted **on the phone** to the public SSH
keys of a GitHub account — yours, normally:

1. Type a GitHub username and tap **Find keys**. The app fetches the public keys that anyone can
   see at `github.com/<username>.keys`.
2. Tick the keys that should be able to open the file. Compare a fingerprint with
   `ssh-keygen -lf ~/.ssh/id_ed25519.pub` if you are unsure which is which. Ed25519 keys and RSA
   keys of 2048 bits or more work.
3. **Encrypt and share** hands the file to the share sheet; **Copy encrypted text** puts it on the
   clipboard instead.

The file is in the open [age](https://age-encryption.org) format. Only the holder of one of the
ticked private keys can read it — not us, not GitHub, not whoever stores the file.

### Reading it on a computer

```sh
# install age: brew install age · sudo apt install age · winget install FiloSottile.age
age -d -i ~/.ssh/id_ed25519 2fa-export.age
```

It prints one `otpauth://` link per account. Any authenticator app takes them.

## Moving to a new phone

The private SSH key stays on your computer; the new phone never needs it. Decrypt on the computer
and show everything as **one QR code** in the terminal:

```sh
# install qrencode: brew install qrencode · sudo apt install qrencode
age -d -i ~/.ssh/id_ed25519 2fa-export.age | qrencode -t ansiutf8
```

On the new phone: **Add account → Scan QR code**. Every account appears on the import page; one
tap adds them all.

The code is large. Maximise the terminal and make the font smaller until it fits. Past about 28
accounts one code is not enough; the export page then shows a command that prints several codes
one after another, and **Scan another QR code** joins them. The
[`unitool qr --split`](/docs/cli/#unitool-qr) command does the same.

> **Export before you need it.** The key that protects the accounts on a phone cannot leave that
> phone. If the phone is lost or reset, an export is the only way back — that, or each site's
> backup codes.
