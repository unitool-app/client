package cx.m42.services.ssh.keyring

import cx.m42.unitool.kit.ssh.SshPublicKey
import cx.m42.unitool.kit.ssh.PrivateKey
import cx.m42.superizer.ssh.GeneratedKind
import cx.m42.superizer.ssh.KeyStorage
import cx.m42.superizer.ssh.KeyringDevice
import cx.m42.superizer.ssh.UsePolicy
import cx.m42.unitool.kit.crypto.Hash

/**
 * What a chip adds to the keyring — levels 1 and 2 (05 §2): Android's Keystore, iOS's Secure
 * Enclave. None on desktop and the web, and none where the chip is software (the emulator, a broken
 * KeyMint), so everything there is level 3.
 *
 * Every method that touches a key bound to authentication puts the device's own prompt up, bound to
 * that one operation (`CryptoObject` / `LAContext`, 01 §5.1), with the words the host gives it for
 * [UseReason]. The keyring never sees the device's answer — only the result or its absence.
 *
 * Who may answer is `reason.key.biometricOnly` (09, D245): a strong biometric alone, or that or the
 * screen lock. [store] and [generate] bind the key to it; every use asks the same way.
 */
internal interface ChipBackend {

    val device: KeyringDevice

    /** Where [key] would live here — level 1 or 2 — or null for "only level 3". [generated]: made on the device. */
    fun placement(key: SshPublicKey, generated: Boolean = false): KeyStorage?

    /**
     * Takes a key in under [alias]. Level 1 imports [key] itself into the chip; level 2 makes a fresh
     * wrapping key and seals [file] (an `openssh-key-v1`) with it, and answers the sealed text.
     */
    suspend fun store(alias: String, storage: KeyStorage, key: PrivateKey, file: ByteArray, policy: UsePolicy, reason: UseReason): ChipResult<String?>

    /** A key made inside the chip (01 §4.4, 05 §4.3). */
    suspend fun generate(alias: String, kind: GeneratedKind, policy: UsePolicy, reason: UseReason): ChipResult<Pair<SshPublicKey, KeyStorage>>

    /** Level 2: the sealed file, opened — behind the device's prompt. */
    suspend fun unseal(alias: String, sealed: String, reason: UseReason): ChipResult<ByteArray>

    /** Level 1: an SSH signature blob over [data] by the key under [alias]. [rsaHash] for RSA keys only. */
    suspend fun sign(alias: String, key: SshPublicKey, data: ByteArray, rsaHash: Hash, reason: UseReason): ChipResult<ByteArray>

    /** Level 1 Ed25519: X25519 of the key's scalar with [peer] — age's private step (P2). */
    suspend fun agree(alias: String, peer: ByteArray, reason: UseReason): ChipResult<ByteArray>

    /** Level 1 RSA: the raw private operation — age's private step (P4). */
    suspend fun rsaDecrypt(alias: String, ciphertext: ByteArray, reason: UseReason): ChipResult<ByteArray>

    /** Whether the chip still has [alias] — removing the screen lock deletes it (step 0, P7). */
    fun exists(alias: String): Boolean

    fun delete(alias: String)
}

internal sealed interface ChipResult<out T> {
    data class Ok<T>(val value: T) : ChipResult<T>
    data object Cancelled : ChipResult<Nothing>
    /** The chip no longer has the key: the screen lock was removed (01 §5.7). */
    data object Unusable : ChipResult<Nothing>
    data object NoScreenLock : ChipResult<Nothing>
    data object Busy : ChipResult<Nothing>
    data class Failed(val code: String, val evidence: String? = null) : ChipResult<Nothing>
}
