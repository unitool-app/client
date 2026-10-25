package cx.m42.services.ssh.key

import cx.m42.superizer.ssh.ImportRefusal
import cx.m42.unitool.kit.ssh.KeyFileRefusal

/** The kit's reasons (its `OpenSshKeyFile`, passwords-browser D545) as the contract's — one for one. */
internal fun KeyFileRefusal.toImportRefusal(): ImportRefusal = when (this) {
    KeyFileRefusal.Dsa -> ImportRefusal.Dsa
    KeyFileRefusal.OldFormat -> ImportRefusal.OldFormat
    KeyFileRefusal.PuttyFormat -> ImportRefusal.PuttyFormat
    KeyFileRefusal.PublicKeyOnly -> ImportRefusal.PublicKeyOnly
    KeyFileRefusal.NotAKey -> ImportRefusal.NotAKey
    KeyFileRefusal.Corrupted -> ImportRefusal.Corrupted
    KeyFileRefusal.UnsupportedCipher -> ImportRefusal.UnsupportedCipher
    KeyFileRefusal.UnsupportedKdf -> ImportRefusal.UnsupportedKdf
    KeyFileRefusal.RsaTooShort -> ImportRefusal.RsaTooShort
    KeyFileRefusal.HardwareToken -> ImportRefusal.HardwareToken
    KeyFileRefusal.UnsupportedType -> ImportRefusal.UnsupportedType
}
