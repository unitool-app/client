---
title: Backup and restore
description: Every app's data in one file encrypted to your GitHub account's SSH keys, and restoring it with a key on the new device.
---

# Backup and restore

*Every app's data in one file, encrypted to your GitHub account's SSH keys. Keys are never in it.*

The backup is in **Settings → Backup → Open**, not on the SSH keys screen. Making one needs no key
on the device. Restoring one needs the matching private key — on the device, or on a computer.

## Making a backup

1. **In the backup** lists the apps whose data goes in. *Left out: SSH keys, and apps that asked
   to be.*
2. Type a GitHub username under **GitHub login** and tap **Find keys**. The app fetches the public
   SSH keys anyone can see for that account.
3. **Encrypted to** lists them. The file is encrypted to every key that can take it; the others
   are marked *age cannot encrypt to this kind of key* — `age` takes Ed25519 keys and RSA keys of
   2048 bits or more.
4. Tap **Make a backup**. The share sheet opens with `unitool-backup-<date>.age`. On the desktop
   the file is saved; in the browser it is downloaded. The screen says *Backup made.*

The file is in the open [age](https://age-encryption.org) format. Only the holder of one of those
private keys can read it.

If a secret cannot be opened at that moment, nothing is written: *A secret could not be opened, so
no backup was made.*

| Message | What to do |
|---|---|
| There is no such GitHub account. | check the spelling |
| This account has no public SSH keys. | add one on GitHub, or use another account |
| None of this account's keys can be encrypted to: age takes Ed25519 and RSA keys of 2048 bits or more. | add an Ed25519 key on GitHub |
| GitHub is limiting requests from this address. Try again in an hour. | wait |
| No connection. | connect and try again |

Settings shows when the last backup was made, and Storage & security notes *No backup has been
made.* until there is one.

## Restoring on another device

First add, on the new device, the SSH key the backup was encrypted to — see
[Adding a key](/docs/ssh-keys/add/). It must be an Ed25519 or RSA key.

1. **Settings → Backup → Open**, then under **Restore from a backup** tap **Choose the backup
   file**.
2. Confirm the use of the key, as for any [use of a key](/docs/ssh-keys/use/#confirming-a-use).
   If no key here opens the file: *This is not a backup the keys here can open.*
3. The screen lists the apps in the backup, each with its number of entries, and
   **Also the host's settings**. Untick what you do not want.
4. Tap **Restore**. Each ticked app's data on this device is replaced by the backup's.

The screen says how many apps were restored. Secrets in the backup are sealed again by this
device's own protection.

The backup holds the names of the keys the old device had, not the keys. After a restore the screen
lists them: *The old device had these keys. Import them again from your computer:* — each with its
name and fingerprint.

## Without a key on the device

Decrypt the backup on the computer that has the private key, and restore the decrypted file:

```sh
# install age: brew install age · sudo apt install age · winget install FiloSottile.age
age -d -i ~/.ssh/id_ed25519 unitool-backup-2026-10-10.age > backup.json
```

Copy `backup.json` to the device and choose it under **Restore from a backup**; a decrypted backup
is read as it is.

> `backup.json` holds everything in the backup — 2FA keys included — in plain text. Delete it from
> the computer and the device once the restore is done.
