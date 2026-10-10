---
title: Creating a key
description: Making a new SSH key on the device — Ed25519, or ECDSA P-256 born inside the chip.
---

# Creating a key

On the SSH keys screen, tap **Add a key → Create a key**. *Made on this device and never anywhere
else.*

## Choosing the kind

| Kind | What it can do | Where it is offered |
|---|---|---|
| **Ed25519 — signs and decrypts** | signs, and opens files encrypted to it (`.age` files, backups) | everywhere |
| **ECDSA P-256 — signs only, made inside the chip** | signs; cannot open encrypted files | only on a device with a secure chip and a fingerprint or face set up |

A P-256 key is created inside the chip and never exists anywhere else, not even for a moment in the
app's memory. It is the one key of which "it cannot be taken off the device" is literally true.
Because there is no file, there is no passphrase: its lock is your fingerprint or face only. The
screen says so: *Made inside the chip: there is no file, so there is no passphrase to keep.*

## Name

**Name** is the key's comment — what the list shows and what ends up at the end of the public
line, as `ssh-keygen -C` does.

## Where it lives and how often to ask

For Ed25519 on a device with a chip, choose **In the device's chip** or **Under the key's
passphrase only**, and its own lock, as when [adding a key](/docs/ssh-keys/add/#checking-the-key).
[Where keys live](/docs/ssh-keys/storage/) explains what each means. Then **Ask to confirm**:
**Every time the key is used** or **Once, then for 5 minutes**.

Tap **Create**. If the key will have a passphrase — under its passphrase only, or in the chip with
**Also ask for the key's own passphrase every time** — the next step is **Set a passphrase**: at
least 12 characters, typed twice.

## Before you rely on it

A key created here cannot be exported and is not in any backup. If the device is lost, reset, or
the key becomes unusable, the key is gone. Wherever you add its public half — a server's
`authorized_keys`, a GitHub account — keep another way in as well.

To use it, open its card and **Copy** or **Share** the public key. See
[Using a key](/docs/ssh-keys/use/#the-public-key).
