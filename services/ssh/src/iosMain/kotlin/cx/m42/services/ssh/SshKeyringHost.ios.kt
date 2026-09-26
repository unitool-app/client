package cx.m42.services.ssh

import cx.m42.services.ssh.keyring.KeyPrompter
import cx.m42.services.ssh.keyring.PromptText
import cx.m42.services.ssh.keyring.RecordStore
import cx.m42.services.ssh.keyring.SecureEnclaveBackend
import cx.m42.services.ssh.keyring.UseReason
import cx.m42.superizer.runtime.Clock
import cx.m42.superizer.runtime.Logger
import cx.m42.superizer.runtime.Platform
import kotlinx.coroutines.CoroutineScope

/**
 * The keyring on iOS (05 §4): imported keys sealed to the Secure Enclave, a P-256 made inside it,
 * and where there is no enclave (a simulator, P10), everything under the key's passphrase. [texts]
 * are the host's words for the system's Face ID / passcode prompt.
 */
public fun SshKeyringHost.Companion.ios(
    texts: (UseReason) -> PromptText,
    records: RecordStore,
    prompter: KeyPrompter,
    clock: Clock,
    scope: CoroutineScope,
    logger: Logger? = null,
): SshKeyringHost = withChip(records, prompter, SecureEnclaveBackend(texts), clock, Platform.Ios, scope, logger)
