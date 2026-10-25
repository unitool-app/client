package cx.m42.unitool.kit.link

import cx.m42.unitool.kit.ssh.WireReader
import cx.m42.unitool.kit.ssh.WireWriter

/**
 * The messages inside the Noise transport (passwords-browser 01 §4, D559), in SSH wire with the
 * type as the first byte. [encode] and [decode] are each other's inverse; [decode] answers null for
 * anything it does not know rather than guessing.
 */
public sealed interface LinkMessage {

    public data class Error(val code: String) : LinkMessage

    /** The phone, once pairing's third message proved the psk: who it is, and the name to look for it by. */
    public class Welcome(public val phoneName: String, public val linkId: ByteArray) : LinkMessage

    /** The computer has kept the phone; the phone keeps the computer now. */
    public data object Paired : LinkMessage

    public class PasswordRequest(
        public val id: ByteArray,
        public val origin: String,
        public val browser: String,
        /** Epoch milliseconds on the computer's clock. */
        public val issuedAt: Long,
        public val version: Int = 1,
    ) : LinkMessage

    /** The sheet is on the phone's screen: the popup can say "confirm on the phone". */
    public class PasswordShown(public val id: ByteArray) : LinkMessage

    public class PasswordResponse(
        public val id: ByteArray,
        public val decision: String,
        /** age to the computer's SSH key; empty unless [decision] is [FILL]. */
        public val box: ByteArray,
        public val version: Int = 1,
    ) : LinkMessage

    public companion object {
        public const val ERROR: Int = 1
        public const val WELCOME: Int = 10
        public const val PAIRED: Int = 11
        public const val PW_REQ: Int = 20
        public const val PW_SHOWN: Int = 21
        public const val PW_RES: Int = 22

        public const val FILL: String = "fill"
        public const val DENY: String = "deny"
        public const val TIMEOUT: String = "timeout"

        /** The codes an [Error] carries (01 §4). */
        public const val CLOCK: String = "clock"
        public const val REPLAY: String = "replay"
        public const val BUSY: String = "busy"
        public const val VERSION_CODE: String = "version"
        public const val ORIGIN: String = "origin"
        public const val LOCKED: String = "locked"

        public fun encode(message: LinkMessage): ByteArray {
            val w = WireWriter()
            when (message) {
                is Error -> w.byte(ERROR).string(message.code)
                is Welcome -> w.byte(WELCOME).string(message.phoneName).string(message.linkId)
                Paired -> w.byte(PAIRED)
                is PasswordRequest -> w.byte(PW_REQ).uint32(message.version.toLong()).string(message.id)
                    .string(message.origin).string(message.browser).uint64(message.issuedAt)
                is PasswordShown -> w.byte(PW_SHOWN).string(message.id)
                is PasswordResponse -> w.byte(PW_RES).uint32(message.version.toLong()).string(message.id)
                    .string(message.decision).string(message.box)
            }
            return w.toByteArray()
        }

        public fun decode(bytes: ByteArray): LinkMessage? {
            val r = WireReader(bytes)
            val message: LinkMessage = when (r.byte() ?: return null) {
                ERROR -> Error(r.text() ?: return null)
                WELCOME -> Welcome(r.text() ?: return null, r.string() ?: return null)
                PAIRED -> Paired
                PW_REQ -> {
                    val version = r.uint32()?.toInt() ?: return null
                    PasswordRequest(r.string() ?: return null, r.text() ?: return null, r.text() ?: return null, r.uint64() ?: return null, version)
                }
                PW_SHOWN -> PasswordShown(r.string() ?: return null)
                PW_RES -> {
                    val version = r.uint32()?.toInt() ?: return null
                    PasswordResponse(r.string() ?: return null, r.text() ?: return null, r.string() ?: return null, version)
                }
                else -> return null
            }
            return message.takeIf { r.done }
        }
    }
}
