package cx.m42.services.ssh

import cx.m42.superizer.ssh.KeyId
import kotlin.io.encoding.Base64

/**
 * The public half of an SSH key. The class itself moved to the kit (passwords-browser D545) so the
 * console tool reads the same keys; the name stays here for everything that already said
 * `cx.m42.services.ssh.SshPublicKey`. Inside this module a file that needs `Kind` or `Curve` imports
 * the kit's class directly: nested types are not reachable through a type alias.
 */
public typealias SshPublicKey = cx.m42.unitool.kit.ssh.SshPublicKey

/** The keyring's id of a key: its fingerprint, as the contract names keys. */
public val SshPublicKey.id: KeyId get() = KeyId(fingerprint)

/** Standard Base64 without the `=` padding — the kit's, under the name this module always used. */
internal val Base64Raw: Base64 get() = cx.m42.unitool.kit.ssh.Base64Raw
