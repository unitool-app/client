package cx.m42.services.ssh.keyring

import cx.m42.superizer.ssh.Chip
import cx.m42.superizer.ssh.KeyStorage

/**
 * The same chip, sealing the key instead of holding it (09, D244): where a key goes when its
 * passphrase is kept as a second lock. A chip that can hold a key can also seal one; level 3 has
 * no chip to seal with, so null.
 */
public fun KeyStorage.sealedBySameChip(): KeyStorage? = when (this) {
    KeyStorage.StrongBox -> KeyStorage.SealedByChip(Chip.StrongBox)
    KeyStorage.Tee -> KeyStorage.SealedByChip(Chip.Tee)
    KeyStorage.SecureEnclave -> KeyStorage.SealedByChip(Chip.SecureEnclave)
    is KeyStorage.SealedByChip -> this
    KeyStorage.SealedByPassphrase -> null
}
