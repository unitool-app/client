---
title: Adding a key
description: Importing an existing SSH private key from a file, the clipboard or a QR code.
---

# Adding a key

On the SSH keys screen, tap **Add a key**. The screen offers what this device can do:

| Option | When it is there | What it takes |
|---|---|---|
| **Import from a file** | where the system file picker is | the private key file — the one without `.pub`, e.g. `~/.ssh/id_ed25519` |
| **Paste** | always | the key's text, `-----BEGIN OPENSSH PRIVATE KEY-----` and all |
| **Scan a QR code** | phones with a camera | a QR code of the key file, shown on your computer |
| **Create a key** | always | nothing: the key is made here. See [Creating a key](/docs/ssh-keys/create/). |

On Android, none of these screens can be captured by a screenshot or a screen recording.

## From a file

Tap **Import from a file** and pick the private key. Copy it to the phone first if it is not
there; delete the copy once the key is added.

## By pasting

**Paste** opens a text field. Paste the key and tap **Continue**.

> Pasted text passes through the clipboard and the keyboard, which may keep a copy. A file or a QR
> code of a key with a passphrase is better.

After the key is added, Unitool clears the clipboard if it still holds a private key.

## From a QR code

On the computer, show the key file as a QR code in the terminal:

```sh
# install qrencode: brew install qrencode · sudo apt install qrencode
qrencode -t ansiutf8 < ~/.ssh/id_ed25519; read _; clear
```

Tap **Scan a QR code** and point the camera at it. Press Enter in the terminal to clear it.

> A QR code of a key without a passphrase is the key itself, on a screen.

## Checking the key

Before it asks for anything, the screen shows the key's name, type and fingerprint, and
**Where it will live**. Compare the fingerprint with `ssh-keygen -lf ~/.ssh/id_ed25519.pub` if you
are unsure which key it is.

On a device with a secure chip you choose where the key goes — **In the device's chip** (the
default) or **Under the key's passphrase only** — and, in the chip, which lock it has of its own.
[Where keys live](/docs/ssh-keys/storage/#choosing-where-a-key-lives) explains the choices.

## The passphrase

If the file is encrypted, type its passphrase under **The key's passphrase**. The screen counts the
characters as you type.

A key is never kept without a passphrase of at least 12 characters. When the file has none, or a
shorter one, tapping **Add** leads to a second step:

- **Set a passphrase** — the file has none;
- **The passphrase is too short** — *A passphrase of N characters can be guessed from a copy of
  the file within hours.*

Type a new passphrase under **New passphrase** and again under **The same again**, then tap
**Add**. Only the length is counted — the app does not pretend to judge strength. The copy on your
computer keeps its old passphrase; only the one on this device changes.

## How often to ask

**Ask to confirm** sets how often a use of the key asks you:

| Choice | What it means |
|---|---|
| **Every time the key is used** | the default |
| **Once, then for 5 minutes** | after one confirmation, the key is used without asking for five minutes |

> In a session, anyone holding the unlocked device can use the key without being asked.

A session ends after five minutes, when you tap **Lock keys now**, when the app is closed, and — on
Android — when the screen turns off.

Tap **Add**. A key going into the chip may make the device ask for your fingerprint, face or screen
lock first. The key appears on the list.

## When a key is refused

The screen says why, and where there is a fix on the computer, shows the command:

| Message | Fix |
|---|---|
| This is not an SSH private key. | — |
| This is a public key (.pub). The file without .pub is needed. | pick the other file |
| An old key format. Rewrite it in the new one on your computer — the passphrase can stay the same: | `ssh-keygen -p -f ~/.ssh/id_rsa` |
| A PuTTY key. Convert it on your computer: | `puttygen key.ppk -O private-openssh-new -o id_key` |
| Encrypted with a cipher this app does not read. Re-encrypt it on your computer: | `ssh-keygen -p -Z aes256-ctr -f ~/.ssh/id_ed25519` |
| Encrypted in a way this app does not read. | — |
| This key lives on a hardware token; the file only points to it. | — |
| DSA keys were switched off in OpenSSH 7.0. | — |
| This RSA key is too short. Make a new one: | `ssh-keygen -t ed25519` |
| This kind of key is not supported. | — |
| The file is damaged or was edited by hand. | — |

Supported: Ed25519, RSA of 2048 bits or more, and ECDSA (P-256, P-384, P-521), in the
`openssh-key-v1` format that `ssh-keygen` writes today.

Other messages:

- **This key is already here.** — the same key is on the list.
- **Wrong passphrase. Try again.**
- **Set a screen lock first: keys here are bound to it.** — set a PIN, pattern or passcode in the
  phone's settings, or keep the key under its passphrase only.
