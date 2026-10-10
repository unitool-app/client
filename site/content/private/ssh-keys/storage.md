---
title: Where keys live
description: The three places a private key can be kept, the lock each has, and what makes a key unusable.
---

# Where keys live

Every key is kept in one of three ways. The key's row and card say which, in the words below; the
add and create screens say it before anything is stored.

| What the app says | What it means | What opens it |
|---|---|---|
| *Stored in the secure chip (…); never leaves it.* | The private key is inside the chip — StrongBox, TEE or Secure Enclave. Signing happens in the chip. | your fingerprint or face |
| *Encrypted with a key from the secure chip (…).* | The key file is sealed by a key that lives in the chip. *Decrypted into memory only for one operation, after you confirm.* | the chip's prompt — fingerprint, face or screen lock — and the key's passphrase where it is kept; with the screen lock, it always is |
| *Stored in the app's files, protected only by its passphrase.* | The key file, encrypted with its passphrase (bcrypt and AES, as `ssh-keygen` writes it). | the key's passphrase |

The first two need a device with secure hardware and a screen lock. A copy of the app's files is
useless without that device's chip. The third is as strong as the passphrase — which is why a key
is never kept without one of at least 12 characters.

Neither the key records nor the keys are included in the device's backups.

## Choosing where a key lives

On a device with a chip, **Where it will live** offers:

- **In the device's chip** — the default;
- **Under the key's passphrase only** — the key does not touch the chip. On the card this reads
  *Stored in the app's files, protected only by its passphrase*, with the note *This device has a
  secure chip. To keep the key in it, delete the key and add it again.*

Which of the first two levels a key gets in the chip depends on the device and the kind of key; the
line under the choice says which before you tap **Add**.

## The key's own lock

A key in the chip always has a lock of its own. The screen lock alone — PIN, pattern, passcode —
never opens a key.

| Switch | Default | What it does |
|---|---|---|
| **Only my fingerprint — not the screen lock's PIN** (Android) or **Only Face ID or Touch ID — not the passcode** (iPhone) | on, where a fingerprint or face is set up | only a fingerprint or face opens the key: *Opens only with your fingerprint or face, not with the screen lock's PIN or passcode.* |
| **Also ask for the key's own passphrase every time** | off with the switch above; otherwise on and locked | the key's passphrase is asked too, on every use |

With the biometric switch off, or with no fingerprint or face set up, the passphrase switch is on
and cannot be turned off: *Required without a fingerprint or face: the screen lock alone never
opens a key.*

> Keys added to the chip by early test builds may have neither lock. Their card says *Anyone who
> can unlock this device can use this key.* To fix it, delete the key and add it again.

## When a key stops working

The card then says *This key can no longer be used here:* — *the screen lock was removed, or — for
a key only a fingerprint or face opens — they changed. Import it again from your computer.*

| Event | Effect |
|---|---|
| The screen lock or passcode is removed | every key in the chip stops working; the system deletes the chip's keys |
| A fingerprint or face is added, or all are removed (biometric-only keys) | the key stops working |
| The key's passphrase is forgotten | the key cannot be opened |
| Too many failed fingerprint or face attempts | the system pauses the biometric; the key itself is not affected |

An unusable key cannot be repaired. Delete it and import it again. A key created on the device
cannot be imported again, so it is lost.

## Moving a key

There is no move between levels, in either direction. To keep a key differently, delete it and add
it again from the original file.

## Android

The chip is used on Android 11 and later, on phones whose Keystore is hardware. On older Android,
on emulators and on phones with a software Keystore, every key is kept under its passphrase.

| Key | Where it goes, in the chip |
|---|---|
| RSA, imported | inside the chip (TEE) |
| ECDSA P-256, imported or created | inside the chip — StrongBox where the phone has one, otherwise the TEE |
| Ed25519 | inside the chip where the phone's Keystore takes this kind of key; otherwise sealed by the chip |
| any key with **Also ask for the key's own passphrase every time** | sealed by the chip, so the passphrase is a real second lock |

A face counts as a biometric for keys only where Android rates it in the strongest class. Removing
one fingerprint of several does not affect a key; the others still open it.

The confirmation is the system's own prompt. Its title says what is asked — for example *Decrypt
with “laptop” · app “2FA”* — and its subtitle names the key and its fingerprint. Every session ends
when the screen turns off.

## iPhone and iPad

The Secure Enclave does not take keys made elsewhere. So:

| Key | Where it goes, in the chip |
|---|---|
| any imported key | sealed by a Secure Enclave key |
| ECDSA P-256 created on the device | inside the Secure Enclave |

With **Only Face ID or Touch ID — not the passcode** on, the prompt has no passcode button, and
any change to the enrolled faces or fingerprints makes the key unusable. If Face ID or Touch ID
locks after failed attempts, unlock the device with its passcode; the key is not affected.

Removing the passcode deletes the keys, as it deletes everything the Secure Enclave guards.

## Desktop and web

There is no chip. Every key is kept under its passphrase; in the browser the card says *Stored in
the browser, protected only by its passphrase.* — *The decrypted key lives in the tab's memory while
unlocked.*

Here a key also protects the app's secrets — the 2FA accounts, for instance:

- Until there is a key, Storage & security says *Secret storage is not set up on this platform.
  Secrets are stored as plain text.* and offers **Add an SSH key**.
- The first key added that can decrypt (Ed25519 or RSA) seals every secret at once, without asking.
  Storage & security then says *Secrets are protected by the passphrase of your SSH key …*
- The passphrase is asked once to open the secrets; they stay open until the app closes or you tap
  **Lock keys now**.
- **Seal secrets with this key** on another key's card moves them to that key. The key that seals
  them cannot be deleted: *Secrets are sealed with this key; seal them with another before deleting
  it.*
