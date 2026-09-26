package cx.m42.unitool.security

import cx.m42.services.ssh.keyring.KeyPrompter
import cx.m42.services.ssh.keyring.UseReason
import cx.m42.superizer.app.AppId
import cx.m42.superizer.ssh.KeyId
import cx.m42.superizer.ssh.KeyPurpose
import cx.m42.superizer.ssh.SshKeyInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The keyring's questions, as state the host's sheet draws (02 §7, 05 §2.2): which app wants which
 * key for what, the passphrase of a level-3 key, a confirmation before a delete. One question at a
 * time; each suspends its caller until the person answers or closes the sheet.
 *
 * The sheet is Unitool's own (07 §2.2), drawn over the whole shell by [KeyPromptSheet] on every
 * platform. On a phone the device's biometric prompt, bound to the key, comes from the chip backend
 * instead; this carries only what the chip cannot ask.
 */
public class KeyPrompts : KeyPrompter {

    public sealed interface Question {
        public data class Choose(val app: AppId, val purpose: KeyPurpose, val candidates: List<SshKeyInfo>) : Question
        public data class Grant(val app: AppId, val key: SshKeyInfo, val purpose: KeyPurpose) : Question
        public data class Passphrase(val reason: UseReason, val wrong: Int) : Question
        public data class Confirm(val reason: UseReason) : Question
    }

    private class Pending(val question: Question, val answer: CompletableDeferred<Any?>)

    private val _current = MutableStateFlow<Question?>(null)

    /** What the sheet shows now; null when nothing is asked. */
    public val current: StateFlow<Question?> get() = _current.asStateFlow()

    private var pending: Pending? = null
    private val oneAtATime = Mutex()

    private suspend fun ask(question: Question): Any? = oneAtATime.withLock {
        val answer = CompletableDeferred<Any?>()
        pending = Pending(question, answer)
        _current.value = question
        try {
            answer.await()
        } finally {
            pending = null
            _current.value = null
        }
    }

    /** The sheet's answer: a [KeyId] for Choose, true/false for Grant and Confirm, a CharArray for Passphrase; null is "closed". */
    public fun answer(value: Any?) {
        pending?.answer?.complete(value)
    }

    override suspend fun choose(app: AppId, purpose: KeyPurpose, candidates: List<SshKeyInfo>): KeyId? =
        ask(Question.Choose(app, purpose, candidates)) as? KeyId

    override suspend fun grant(app: AppId, key: SshKeyInfo, purpose: KeyPurpose): Boolean =
        ask(Question.Grant(app, key, purpose)) == true

    override suspend fun passphrase(reason: UseReason, wrong: Int): CharArray? =
        ask(Question.Passphrase(reason, wrong)) as? CharArray

    override suspend fun confirm(reason: UseReason): Boolean =
        ask(Question.Confirm(reason)) == true
}
