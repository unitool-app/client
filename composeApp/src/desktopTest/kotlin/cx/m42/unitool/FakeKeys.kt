package cx.m42.unitool

import cx.m42.services.ssh.KnownKey
import cx.m42.superizer.app.AppId
import cx.m42.superizer.runtime.Platform
import cx.m42.superizer.ssh.Chip
import cx.m42.superizer.ssh.GeneratedKind
import cx.m42.superizer.ssh.Grant
import cx.m42.superizer.ssh.ImportOptions
import cx.m42.superizer.ssh.ImportOutcome
import cx.m42.superizer.ssh.ImportRefusal
import cx.m42.superizer.ssh.Inspection
import cx.m42.superizer.ssh.KeyCapability
import cx.m42.superizer.ssh.KeyId
import cx.m42.superizer.ssh.KeyOperation
import cx.m42.superizer.ssh.KeyOrigin
import cx.m42.superizer.ssh.KeyPurpose
import cx.m42.superizer.ssh.KeyStorage
import cx.m42.superizer.ssh.KeyringDevice
import cx.m42.superizer.ssh.SshKeyAdmin
import cx.m42.superizer.ssh.SshKeyInfo
import cx.m42.superizer.ssh.SshOutcome
import cx.m42.superizer.ssh.UseRecord
import cx.m42.superizer.ssh.UsePolicy
import cx.m42.unitool.settings.KeysUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A phone's keyring, as data: three keys at the three levels (05 §2), a grant and a journal — what
 * the screenshots draw, since a desktop has no chip to put a key in. Nothing here is a real key: the
 * public halves are the test ones from [ratesNetwork], the fingerprints are made up.
 */
internal class FakeKeys(
    keys: List<SshKeyInfo> = PhoneKeys,
    device: KeyringDevice = AndroidPhone,
    wouldBe: KeyStorage = KeyStorage.Tee,
) : KeysUi {
    override val admin: FakeKeyAdmin = FakeKeyAdmin(keys, device, wouldBe)
    override val secretsKey: StateFlow<KeyId?> = MutableStateFlow(null)
    override val sealsToKey: Boolean = false
    override val inBrowser: Boolean = false

    override suspend fun sealSecretsWith(id: KeyId) = Unit

    override suspend fun export(id: KeyId, to: KnownKey): SshOutcome<ByteArray> = SshOutcome.Cancelled

    override suspend fun verify(id: KeyId): SshOutcome<Boolean> = SshOutcome.Cancelled
}

internal class FakeKeyAdmin(
    keys: List<SshKeyInfo>,
    override val device: KeyringDevice = AndroidPhone,
    private val wouldBe: KeyStorage = KeyStorage.Tee,
) : SshKeyAdmin {
    private val list = MutableStateFlow(keys)
    override val keys: StateFlow<List<SshKeyInfo>> = list

    /** What the wizard asked for, in order: imports and creations alike. */
    val asked = mutableListOf<Pair<GeneratedKind?, ImportOptions>>()

    /** By what the text says: a PEM key is the old format, a marked one has no passphrase, the rest is an encrypted Ed25519. */
    override fun inspect(file: ByteArray): Inspection {
        val text = file.decodeToString()
        return when {
            "BEGIN RSA PRIVATE KEY" in text -> Inspection(refusal = ImportRefusal.OldFormat)
            text.startsWith("ssh-") -> Inspection(refusal = ImportRefusal.PublicKeyOnly)
            else -> Inspection(
                type = "ssh-ed25519",
                id = KeyId("SHA256:q3J8bXv0T6mYk2GfZ1pWc9LhRr5sNd7Ea4Uo0iKjBxM"),
                label = "ED25519",
                comment = "ivan@laptop",
                encrypted = "unencrypted" !in text,
                rounds = 24,
                wouldBe = wouldBe,
            )
        }
    }

    override suspend fun import(file: ByteArray, passphrase: CharArray?, options: ImportOptions, newPassphrase: CharArray?): ImportOutcome {
        file.fill(0)
        passphrase?.fill('\u0000')
        newPassphrase?.fill('\u0000')
        asked += null to options
        return ImportOutcome.Cancelled
    }

    override suspend fun generate(kind: GeneratedKind, options: ImportOptions, passphrase: CharArray?): ImportOutcome {
        passphrase?.fill('\u0000')
        asked += kind to options
        return ImportOutcome.Cancelled
    }

    override suspend fun rekey(id: KeyId, newPassphrase: CharArray): SshOutcome<Unit> = SshOutcome.Cancelled

    override suspend fun rename(id: KeyId, comment: String) = Unit

    override suspend fun delete(id: KeyId): SshOutcome<Unit> = SshOutcome.Cancelled

    override fun grants(id: KeyId): StateFlow<List<Grant>> =
        MutableStateFlow(if (id == PhoneKeys[0].id) listOf(Grant(AppId("2fa"), id, KeyPurpose.Decrypt, grantedAt = AT)) else emptyList())

    override suspend fun revoke(grant: Grant) = Unit

    override fun journal(id: KeyId): StateFlow<List<UseRecord>> = MutableStateFlow(
        if (id == PhoneKeys[0].id) {
            listOf(
                UseRecord(AT, null, id, KeyOperation.Import, outcome = "ok"),
                UseRecord(AT + 60_000, AppId("2fa"), id, KeyOperation.Grant, outcome = "ok"),
                UseRecord(AT + 120_000, AppId("2fa"), id, KeyOperation.Decrypt, digest = "3f9a01c2", outcome = "ok"),
            )
        } else {
            emptyList()
        },
    )

    override fun lockAll() = Unit
}

/** A phone with StrongBox and Curve25519 in its Keystore: every level there is — and a PIN, but no fingerprint. */
internal val AndroidPhone = KeyringDevice(
    Platform.Android, sdk = 35, strongBox = true, tee = true, curve25519InKeystore = true, screenLockSet = true, levels = setOf(1, 2, 3),
)

/** The same phone with a fingerprint enrolled (D245). */
internal val AndroidWithFingerprint = AndroidPhone.copy(strongBiometric = true)

/** An iPhone with a passcode and no Face ID or Touch ID enrolled: the Secure Enclave seals imported keys and makes P-256 ones. */
internal val IPhone = KeyringDevice(Platform.Ios, secureEnclave = true, screenLockSet = true, levels = setOf(1, 2))

/** The same iPhone with Face ID set up (D246). */
internal val IPhoneWithFaceId = IPhone.copy(strongBiometric = true)

/** 2023-11-14 22:13:42 UTC, the 2FA frames' moment. */
private const val AT = 1_700_000_022_000L

internal val PhoneKeys: List<SshKeyInfo> = listOf(
    SshKeyInfo(
        id = KeyId("SHA256:q3J8bXv0T6mYk2GfZ1pWc9LhRr5sNd7Ea4Uo0iKjBxM"),
        type = "ssh-ed25519",
        publicKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOh1VbDdG6aEqkiUkfr6tLE1wlJZ/jm90yZxcCjDq3xS",
        label = "ED25519",
        comment = "ivan@laptop",
        storage = KeyStorage.Tee,
        policy = UsePolicy.EveryUse,
        origin = KeyOrigin.Imported,
        can = setOf(KeyCapability.Sign, KeyCapability.DecryptAge),
        createdAt = AT,
    ),
    SshKeyInfo(
        id = KeyId("SHA256:Zk1mP0aQ7rT2vB9wX4yC6dE8fG3hJ5kL1nM2oQ4rS6t"),
        type = "ecdsa-sha2-nistp256",
        publicKey = "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBEIsMsXqmDgDTtFzJYG8gCJX0455Aa7IlDHEiYA0PtCHVoTPjGzWFYwQgxsQRhLRYXwgEw9EjUXF79TLbDAzMEo=",
        label = "ECDSA 256",
        comment = "phone",
        storage = KeyStorage.StrongBox,
        policy = UsePolicy.EveryUse,
        origin = KeyOrigin.Generated(attested = true),
        can = setOf(KeyCapability.Sign),
        createdAt = AT,
    ),
    SshKeyInfo(
        id = KeyId("SHA256:b7Hc2Jd4Kf6Lg8Mh0Nj1Pk3Ql5Rm7Sn9To1Up3Vq5Wr"),
        type = "ssh-rsa",
        publicKey = "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABgQDF9f3LvEHB12a4AXHr0ZCeEBSJaK3MNbkcwnETRnaaOa35",
        label = "RSA 3072",
        comment = "work",
        storage = KeyStorage.SealedByChip(Chip.Tee),
        policy = UsePolicy.Session(300),
        origin = KeyOrigin.Imported,
        can = setOf(KeyCapability.Sign, KeyCapability.DecryptAge),
        createdAt = AT,
    ),
    SshKeyInfo(
        id = KeyId("SHA256:x9Yw8Vu7Ts6Rq5Po4Nm3Lk2Ji1Hg0Fe9Dc8Ba7Zy6Xw"),
        type = "ssh-ed25519",
        publicKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOh1VbDdG6aEqkiUkfr6tLE1wlJZ/jm90yZxcCjDq3xT",
        label = "ED25519",
        comment = "old-server",
        storage = KeyStorage.SealedByPassphrase,
        policy = UsePolicy.EveryUse,
        origin = KeyOrigin.Imported,
        can = setOf(KeyCapability.Sign, KeyCapability.DecryptAge),
        createdAt = AT,
    ),
)
