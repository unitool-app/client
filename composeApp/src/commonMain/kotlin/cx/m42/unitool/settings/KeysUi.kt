package cx.m42.unitool.settings

import cx.m42.services.qr.QrScanner
import cx.m42.services.share.FileSharer
import cx.m42.services.ssh.PublicKeyBookAdmin
import cx.m42.superizer.runtime.FilePicker
import cx.m42.superizer.runtime.NetworkService
import cx.m42.superizer.ssh.KeyId
import cx.m42.superizer.ssh.SshKeyAdmin
import cx.m42.unitool.web.OpenedSections
import kotlinx.coroutines.flow.StateFlow

/**
 * What the host's key screens need of the keyring, and nothing of how it was built — so the
 * screenshots can draw three keys at three levels on a desktop that has no chip (04 step 6).
 */
internal interface KeysUi {
    val admin: SshKeyAdmin

    /** The key the host's secrets are sealed to where there is no chip (D173); null before one, and always where a chip seals them. */
    val secretsKey: StateFlow<KeyId?>

    /** No chip vault here: secrets are sealed to a key, and a key card offers to be that key. */
    val sealsToKey: Boolean

    /** The web: level 3 is "in the browser" (05 §2.3). */
    val inBrowser: Boolean

    /** The device has a chip, so level 3 is the person's choice (09, D242) and the wizard offers both. */
    val chipHere: Boolean get() = admin.device.levels.any { it < 3 }

    /** Other devices' public keys (.ai-notes/public-keys); null where the host keeps none — the screenshots' fake. */
    val publicKeys: PublicKeyBookAdmin? get() = null

    /** Reseals every secret to [id] and makes it the anchor (05 §3). */
    suspend fun sealSecretsWith(id: KeyId)
}

/**
 * The platform's optional services the host's own screens use (D19): the same objects the apps get
 * from the registry, handed to the screens directly because the host has no registry of its own.
 * Null is "not on this platform", and the screen leaves that way out.
 */
public class HostTools(
    public val files: FilePicker? = null,
    public val qr: QrScanner? = null,
    public val sharer: FileSharer? = null,
    /** The host's own network — the one the apps get — for the backup's GitHub keys. Set by [cx.m42.unitool.buildUnitool]. */
    public val network: NetworkService? = null,
    /** The parts opened by code, for the website's private docs (.ai-notes/site/02). Set by [cx.m42.unitool.buildUnitool]. */
    public val opened: OpenedSections? = null,
) {
    internal fun with(network: NetworkService, opened: OpenedSections): HostTools = HostTools(files, qr, sharer, network, opened)
}
