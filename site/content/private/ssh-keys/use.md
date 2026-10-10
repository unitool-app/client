---
title: Using a key
description: How apps ask for a key, what you confirm, the record of use, and the key card's actions.
---

# Using a key

A key is used in two steps: an app gets your **permission** once, and every **use** is confirmed
again. Unitool itself uses a key without a permission step — to open a backup — but still asks
you to confirm.

## Which apps use keys

| Where | What it does with the key |
|---|---|
| 2FA: **Add account → Link → Import from a file** | opens an export file (`.age`) encrypted to your key — see [Exporting your accounts](/docs/two-factor/#exporting-your-accounts). A file already decrypted on a computer is read as it is. |
| Settings → Backup → **Restore from a backup** | opens a backup encrypted to your key. See [Backup and restore](/docs/ssh-keys/backup/). |
| Test App: **Sign with a key** | signs a test message, for developers. See [Test App](/docs/test-app/cards/#ssh-keyring). |

Opening a file needs an Ed25519 or RSA key — the kinds `age` encrypts to. If none of your keys
opens it, 2FA says *None of the SSH keys here opens this file. Add the key it was encrypted to on
this device first.*

## Giving an app permission

The first time an app asks for a particular key, a sheet names the app (by its id), the purpose
and the key:

- *Let “2fa” decrypt files with your SSH key?* — *Files encrypted to this key. You will still
  confirm every decryption.*
- *Let “…” sign with your SSH key?* — *Signatures for “…”. You will still confirm every
  signature.* The second line names the namespace; for `git` and `file` it says *Signatures of git
  commits in your name* or *Signatures of files in your name*.

Tap **Allow** or **Don't allow**.

When an app asks you to pick a key instead, the sheet reads *Which key may “test-app” sign with?*
and lists the keys that fit. Tapping one gives the permission for that key; **Cancel** gives none.

A permission is for one app, one key and one purpose. For signing, the purpose is a namespace, so a
permission to sign for one thing is not a permission to sign for another. App signatures are SSH
signatures with a namespace (`ssh-keygen -Y sign -n …`); they cannot be used to log in anywhere.

## Confirming a use

Every use, after the permission, asks again — unless the key is in a
[five-minute session](/docs/ssh-keys/add/#how-often-to-ask).

- **A key in the chip** — the device's own prompt: fingerprint, face or screen lock, as the key's
  lock allows. Its title says what is being done, with which key and for which app, e.g.
  *Decrypt with “laptop” · app “2fa”*.
- **A key with a passphrase** — a sheet with the same title and a **Passphrase** field. Tap
  **Unlock**, or **Cancel** to stop. After three wrong passphrases the operation fails. On Android
  the sheet cannot be captured by a screenshot.

Cancelling changes nothing.

## Recent use

The key card's **Recent use** lists the last 20 entries, newest first, as
*time · who · what · outcome* — for example `2026-10-10 14:03 · 2FA codes · decrypt · done`.

| What | Meaning |
|---|---|
| `sign`, `decrypt` | a use |
| `allowed`, `revoked` | a permission given or taken back |
| `added`, `created` | the key arrived on the device |
| `passphrase changed` | **Change passphrase** was used |

| Outcome | Meaning |
|---|---|
| `done` | it happened |
| `cancelled` | you closed the prompt |
| `refused` | you did not allow it |
| `failed (…)` | it went wrong; the code in brackets says how |

## Revoking a permission

Under **Apps that may use it** on the key card, tap **Revoke** next to the app. The next time the
app asks, it asks for permission again.

## The public key

**Public key** on the card shows the line to put in a server's `~/.ssh/authorized_keys` or in your
GitHub account's SSH keys. **Copy** puts it on the clipboard; **Share** sends it as a `.pub` file
named after the key.

## Changing the passphrase

**Change passphrase** is on the card of a key kept under its passphrase only. The current
passphrase is asked first; then type the new one twice — at least 12 characters. The copy of the
key on your computer keeps its old passphrase.

## Lock keys now

On the SSH keys screen, **Lock keys now** ends every five-minute session and, on the desktop and
the web, closes the secrets the key had opened. The next use of any key asks again.

## Deleting a key

**Delete** at the bottom of the card. A sheet asks *Delete the key with “…”* — tap **Confirm**;
then the key, its permissions and
its record of use are gone from the device. A key created here cannot be brought back.
