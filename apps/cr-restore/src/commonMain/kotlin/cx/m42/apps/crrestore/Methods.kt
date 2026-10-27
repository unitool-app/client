package cx.m42.apps.crrestore

import cx.m42.superizer.ssh.SshKeyring
import cx.m42.superizer.ssh.SshOutcome
import cx.m42.unitool.kit.crypt.Decrypted
import cx.m42.unitool.kit.crypt.Inspected
import cx.m42.unitool.kit.crypt.Ucrypt
import cx.m42.unitool.kit.crypt.Unlocked
import cx.m42.unitool.kit.crypto.SecretBytes
import cx.m42.unitool.kit.openssl.OpensslEnc
import cx.m42.unitool.kit.split.SplitProblem
import cx.m42.unitool.kit.ssh.Age
import cx.m42.unitool.kit.ssh.AgeIdentity
import cx.m42.unitool.kit.ssh.AgeKeys
import cx.m42.unitool.kit.ssh.OpenSshKeyFile

/** How an attempt ended: something to show, or why not. */
internal sealed interface Outcome {
    class Done(val bytes: ByteArray, val insideName: String? = null, val warning: Warning? = null) : Outcome
    class Failed(val problem: Problem) : Outcome
}

internal sealed interface Warning {
    /** `openssl enc` without padding to check (stream modes, `-nopad`): anything comes out (D609). */
    data object Unchecked : Warning

    /** Split: these parts (1-based, in scan order) did not fit the others (D611). */
    class Disagreeing(val numbers: List<Int>) : Warning
}

internal sealed interface Problem {
    /** It is not this method's format; [looksLike] is what it seems to be instead, if anything. */
    class NotThisFormat(val looksLike: Method?) : Problem
    data object WrongKey : Problem
    data object Damaged : Problem
    data object Newer : Problem

    // age
    data object NoKeyOnPhone : Problem
    data object KeyUnusable : Problem
    data object Cancelled : Problem
    data object Busy : Problem
    data object NothingToOpenWith : Problem
    data object ScryptNotAlone : Problem
    class TooHard(val logN: Int) : Problem
    class Plugin(val type: String) : Problem
    data object BadKey : Problem
    data object KeyNeedsPassphrase : Problem
    data object KeyPassphraseWrong : Problem

    // openssl
    class Unsupported(val what: String) : Problem

    // split
    data object NoContainer : Problem
    class Parts(val problem: SplitProblem) : Problem
    data object PartsDoNotMatch : Problem
}

/**
 * The age screen's key, whatever form it came in (D604): `AGE-SECRET-KEY-1…` lines, an OpenSSH
 * private key (with its passphrase if it has one), or an identity file age itself sealed with a
 * passphrase. Everything made here is wiped by the caller through [Keys.wipe].
 */
internal class Keys(val x25519: List<AgeKeys.X25519Identity>, val ssh: List<AgeIdentity>, private val wipeSsh: () -> Unit) {
    fun wipe() {
        x25519.forEach { it.wipe() }
        wipeSsh()
    }

    companion object {
        val None = Keys(emptyList(), emptyList()) {}

        /** [keyBytes] read as a key; [passphrase] opens an OpenSSH key or a sealed identity file. */
        fun read(keyBytes: ByteArray, passphrase: ByteArray?, maxLogN: Int): Pair<Keys?, Problem?> {
            val text = runCatching { keyBytes.decodeToString() }.getOrDefault("")
            AgeKeys.parseIdentities(text)?.let { return Keys(it, emptyList()) {} to null }
            if (Age.header(keyBytes) != null) {
                // `age -d -i keys.age`: the identity file is itself an age file, with a passphrase.
                if (passphrase == null) return null to Problem.KeyNeedsPassphrase
                return when (val opened = AgeKeys.decrypt(keyBytes, passphrase = passphrase, maxLogN = maxLogN)) {
                    is AgeKeys.Opened.Ok -> {
                        val inner = AgeKeys.parseIdentities(opened.plaintext.decodeToString())
                        opened.plaintext.fill(0)
                        if (inner == null) null to Problem.BadKey else Keys(inner, emptyList()) {} to null
                    }
                    is AgeKeys.Opened.TooHard -> null to Problem.TooHard(opened.logN)
                    else -> null to Problem.KeyPassphraseWrong
                }
            }
            val parsed = OpenSshKeyFile.parse(keyBytes) as? OpenSshKeyFile.Parsed.Ok ?: return null to Problem.BadKey
            val file = parsed.file
            if (file.isEncrypted && passphrase == null) return null to Problem.KeyNeedsPassphrase
            val secret = passphrase?.let { SecretBytes(it.copyOf()) }
            val decrypted = try {
                file.decrypt(if (file.isEncrypted) secret else null)
            } finally {
                secret?.close()
                file.wipe()
            }
            return when (decrypted) {
                is OpenSshKeyFile.Decrypted.Ok -> {
                    val key = decrypted.key
                    val identity = Age.identity(key)
                    if (identity == null) {
                        key.close()
                        null to Problem.BadKey
                    } else {
                        Keys(emptyList(), listOf(identity)) { key.close() } to null
                    }
                }
                OpenSshKeyFile.Decrypted.WrongPassphrase -> null to Problem.KeyPassphraseWrong
                else -> null to Problem.BadKey
            }
        }
    }
}

internal object Methods {

    /**
     * age with the phone's own keys: the host finds the key the file is for, asks the person, and
     * the app never sees the key (D604).
     */
    suspend fun ageWithPhoneKey(file: ByteArray, keyring: SshKeyring): Outcome {
        AgeKeys.recipients(file) ?: return Outcome.Failed(Problem.NotThisFormat(Detect.method(file)))
        return when (val outcome = keyring.decrypt(file)) {
            is SshOutcome.Ok -> Outcome.Done(outcome.value)
            SshOutcome.NoKey -> Outcome.Failed(Problem.NoKeyOnPhone)
            SshOutcome.Unusable -> Outcome.Failed(Problem.KeyUnusable)
            SshOutcome.Cancelled, SshOutcome.Refused -> Outcome.Failed(Problem.Cancelled)
            SshOutcome.Busy -> Outcome.Failed(Problem.Busy)
            else -> Outcome.Failed(Problem.Damaged)
        }
    }

    /** age with keys or a passphrase the person gave (D604–D606). */
    fun age(file: ByteArray, keys: Keys, passphrase: ByteArray?, maxLogN: Int): Outcome {
        val recipients = AgeKeys.recipients(file) ?: return Outcome.Failed(Problem.NotThisFormat(Detect.method(file)))
        if (keys.x25519.isEmpty() && keys.ssh.isEmpty() && passphrase == null) return Outcome.Failed(Problem.NothingToOpenWith)
        return when (val opened = AgeKeys.decrypt(file, keys.x25519, keys.ssh, passphrase, maxLogN)) {
            is AgeKeys.Opened.Ok -> Outcome.Done(opened.plaintext)
            AgeKeys.Opened.NotAge -> Outcome.Failed(Problem.NotThisFormat(Detect.method(file)))
            AgeKeys.Opened.ScryptNotAlone -> Outcome.Failed(Problem.ScryptNotAlone)
            is AgeKeys.Opened.TooHard -> Outcome.Failed(Problem.TooHard(opened.logN))
            is AgeKeys.Opened.Damaged -> Outcome.Failed(Problem.Damaged)
            AgeKeys.Opened.NoIdentity -> {
                // Only plugins left: say which one, since nothing here could ever open it (D617).
                val plugin = recipients.filterIsInstance<AgeKeys.Recipient.Other>().firstOrNull()
                if (plugin != null && recipients.all { it is AgeKeys.Recipient.Other }) Outcome.Failed(Problem.Plugin(plugin.type))
                else Outcome.Failed(Problem.WrongKey)
            }
        }
    }

    /** `openssl enc -d` with what the screen says the command line said (D607–D609). */
    fun openssl(input: ByteArray, key: OpensslEnc.Key, params: OpensslEnc.Params): Outcome =
        when (val opened = OpensslEnc.decrypt(input, key, params)) {
            is OpensslEnc.Opened.Ok -> Outcome.Done(opened.plaintext, warning = if (opened.checked) null else Warning.Unchecked)
            OpensslEnc.Opened.WrongKey -> Outcome.Failed(Problem.WrongKey)
            OpensslEnc.Opened.NotOpenssl -> Outcome.Failed(Problem.NotThisFormat(Detect.method(input)?.takeIf { it != Method.Openssl }))
            is OpensslEnc.Opened.Unsupported -> Outcome.Failed(Problem.Unsupported(opened.what))
        }

    /** The container opened by the parts gathered so far (D611). */
    fun split(gathering: SplitGathering): Outcome {
        val container = gathering.container ?: return Outcome.Failed(Problem.NoContainer)
        val file = if (Ucrypt.isArmored(container)) Ucrypt.dearmor(container) ?: return Outcome.Failed(Problem.Damaged) else container
        val header = when (val inspected = Ucrypt.inspect(file)) {
            is Inspected.Ok -> inspected.header
            Inspected.NotUcrypt -> return Outcome.Failed(Problem.NotThisFormat(Detect.method(container)))
            is Inspected.Newer -> return Outcome.Failed(Problem.Newer)
            is Inspected.Damaged -> return Outcome.Failed(Problem.Damaged)
        }
        return when (val unlocked = header.unlock(gathering.parts)) {
            is Unlocked.Ok -> unlocked.decryptor.use { decryptor ->
                when (val decrypted = decryptor.decrypt(file)) {
                    is Decrypted.Ok -> Outcome.Done(
                        decrypted.plaintext,
                        insideName = decryptor.meta.name,
                        warning = unlocked.disagreeing.takeIf { it.isNotEmpty() }?.let { Warning.Disagreeing(it.map { i -> i + 1 }) },
                    )
                    else -> Outcome.Failed(Problem.Damaged)
                }
            }
            Unlocked.NotSplit -> Outcome.Failed(Problem.NotThisFormat(Method.Age))
            is Unlocked.Parts -> Outcome.Failed(Problem.Parts(unlocked.problem))
            Unlocked.DoNotMatch -> Outcome.Failed(Problem.PartsDoNotMatch)
            is Unlocked.Damaged -> Outcome.Failed(Problem.Damaged)
        }
    }
}
