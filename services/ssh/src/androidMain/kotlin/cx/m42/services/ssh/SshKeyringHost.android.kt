package cx.m42.services.ssh

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import cx.m42.services.ssh.keyring.KeyPrompter
import cx.m42.services.ssh.keyring.KeystoreBackend
import cx.m42.services.ssh.keyring.PromptText
import cx.m42.services.ssh.keyring.RecordStore
import cx.m42.services.ssh.keyring.UseReason
import cx.m42.superizer.runtime.Clock
import cx.m42.superizer.runtime.Logger
import cx.m42.superizer.runtime.Platform
import kotlinx.coroutines.CoroutineScope

/**
 * The keyring on Android (01 §4–§5): keys in the Keystore where it is hardware and the device can
 * bind a key to the person (API 30+), under their passphrase where it cannot — an emulator, a
 * software KeyMint, an old phone (05 §2.1). [activity] is the one the system prompt is shown over;
 * [texts] are the host's words for it. Every session ends when the screen goes off.
 */
public fun SshKeyringHost.Companion.android(
    context: Context,
    activity: () -> Activity?,
    texts: (UseReason) -> PromptText,
    records: RecordStore,
    prompter: KeyPrompter,
    clock: Clock,
    scope: CoroutineScope,
    logger: Logger? = null,
): SshKeyringHost {
    val chip = if (Build.VERSION.SDK_INT >= 30) KeystoreBackend(context, activity, texts) else null
    return withChip(records, prompter, chip, clock, Platform.Android, scope, logger).also { host ->
        // A session ends when the screen goes off (04 step 5, I15): a phone left on a table asks
        // again. Only a receiver registered at run time hears this broadcast; it lives as the host does.
        val screenOff = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = host.admin.lockAll()
        }
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= 33) {
            context.applicationContext.registerReceiver(screenOff, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.applicationContext.registerReceiver(screenOff, filter)
        }
    }
}
