---
title: Storage & security
description: The screen that says how secrets and keys are kept on this device, and the self-test that checks it.
---

# Storage & security

*How secrets and keys are kept on this device, and a self-test that checks it.* It is in
**Settings → Storage & security → Open**. Settings shows its worst finding on the same line.

Every finding on this screen comes from one fixed list of sentences, and each says only what the
app has checked on this device.

## The summary

The top line is the worst status on the screen, with the finding behind it:

| Status | Meaning |
|---|---|
| **OK** | nothing to do |
| **Note** | worth knowing; nothing is wrong |
| **Needs attention** | something is weaker than it could be |
| **Unsafe** | something is weaker than Unitool promises |
| **Not protected** | something is stored without protection |
| **Broken** | a check failed |

While the worst status is **Unsafe** or worse, the same line appears in red above the tiles on
Home. Tap it to open this screen. It goes away when the problem is fixed.

## Findings

Findings are grouped: **Secrets**, **This device**, **SSH keys** (one block per key, under its
name), **Apps** and **Backup**. Where something can be done, a button under the finding does it:

| Button | Where it leads |
|---|---|
| **Add an SSH key**, **Import the key again** | the add-a-key screen |
| **Set a passphrase**, **Change passphrase** | the key's card |
| **Open security settings** | the phone's settings, to set a screen lock |
| **Make a backup** | Settings → Backup |
| **Run self-test**, **Share report** | as below |

These buttons open the add-a-key screen and key cards directly, without the code; the list of keys
still needs it.

## The self-test

The self-test runs the real cryptography on this device, with throwaway keys made for the test and
wiped after it. Your own keys and secrets are not touched.

It runs by itself, without asking anything, the first time each new version of Unitool starts.
**Run self-test** runs it again, including the one step that needs you: the device asks for your
fingerprint, face or screen lock once.

| Step | What it checks |
|---|---|
| Vault seals and opens | the device's secret storage encrypts and decrypts |
| Vault key is in secure hardware | its key is in the chip, not in software |
| Vault refuses a changed secret | tampered data is rejected |
| Vault never seals the same way twice | no two sealings look alike |
| Chip refuses without you | a key bound to you cannot be used without you |
| Chip works after you confirm | and can be, once you confirm — only from **Run self-test** |
| Keys need a screen lock | keys are bound to the screen lock |
| Secure Enclave signs | the Secure Enclave makes a valid signature (iPhone and iPad) |
| Passphrase opens a key | a key file under a passphrase opens, and how long it takes |
| Encryption to an SSH key works | `age` encryption and SSH signatures to a fresh key, and back |
| Key records stay out of backups | the key records are excluded from the device's backups |
| Secrets are wiped from memory | memory that held a secret is zeroed |
| The report carries no secret | the test's own key and passphrase are not in the report |

Each step shows ✓, ✗ with the reason, or – with why it did not run: *not on this platform*,
*this device has no screen lock*, *skipped: the step above failed*, or *only from the Run self-test
button: it asks you to confirm*. On the desktop and the web the vault and chip steps do not run.

If unlocking a passphrase takes more than three seconds on the device, Storage & security notes
*Unlocking with a passphrase takes N s on this device.* The key is as safe; it is only slower to
use.

## The report

**Share report** sends the whole screen as a text file, `unitool-storage-report.txt`, through the
share sheet; where there is none, **Copy report** puts it on the clipboard. It holds the findings,
the self-test steps and the device facts listed under **How storage works here** — the platform,
the chips present, whether there is a screen lock, how many keys there are. It holds no key, no
passphrase and no secret; the last self-test step checks exactly that.

Send it with a description of the problem to [{{contact}}](mailto:{{contact}}).
