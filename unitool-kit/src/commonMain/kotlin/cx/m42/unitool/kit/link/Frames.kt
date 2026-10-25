package cx.m42.unitool.kit.link

/**
 * What goes on the pipe before and around Noise (passwords-browser 01 §1, D556): four bytes that say
 * which handshake follows, then frames of `uint16` length ‖ one Noise message.
 */
public object LinkWire {
    /** The phone's port in the network, the same as usb-sign's loopback port on an iPhone (D538). */
    public const val PORT: Int = 47370

    /** The mDNS service type both sides use (D538, D551). */
    public const val SERVICE_TYPE: String = "_unitool._tcp"

    public const val VERSION: Int = 1

    public enum class Mode(public val code: Int, public val pattern: NoisePattern) {
        Pair(1, NoisePattern.XXpsk3),
        Session(2, NoisePattern.KK),
    }

    public fun preamble(mode: Mode): ByteArray = byteArrayOf(0x55, 0x4C, VERSION.toByte(), mode.code.toByte())

    /** The mode four bytes ask for, or null for anything that is not this protocol at this version. */
    public fun mode(preamble: ByteArray): Mode? {
        if (preamble.size != 4 || preamble[0] != 0x55.toByte() || preamble[1] != 0x4C.toByte()) return null
        if (preamble[2].toInt() != VERSION) return null
        return Mode.entries.firstOrNull { it.code == preamble[3].toInt() }
    }

    /** Noise's prologue: the name and the preamble, so a changed mode byte breaks the handshake. */
    public fun prologue(mode: Mode): ByteArray = "unitool-link/1".encodeToByteArray() + preamble(mode)

    public fun frame(message: ByteArray): ByteArray {
        require(message.size <= Noise.MAX_MESSAGE) { "frame too long" }
        val out = ByteArray(2 + message.size)
        out[0] = (message.size ushr 8).toByte()
        out[1] = message.size.toByte()
        message.copyInto(out, 2)
        return out
    }
}

/**
 * Bytes from the pipe in, whole frames out — however the reads happened to split them. Sans-IO:
 * the loop that reads the socket feeds [push] and takes [next].
 */
public class FrameReader {
    private var buffer = ByteArray(0)

    public fun push(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        if (length == 0) return
        val next = ByteArray(buffer.size + length)
        buffer.copyInto(next)
        bytes.copyInto(next, buffer.size, offset, offset + length)
        buffer.fill(0)
        buffer = next
    }

    /** The next whole frame's message, or null until enough bytes have come. */
    public fun next(): ByteArray? {
        if (buffer.size < 2) return null
        val length = ((buffer[0].toInt() and 0xff) shl 8) or (buffer[1].toInt() and 0xff)
        if (buffer.size < 2 + length) return null
        val message = buffer.copyOfRange(2, 2 + length)
        val rest = buffer.copyOfRange(2 + length, buffer.size)
        buffer.fill(0)
        buffer = rest
        return message
    }

    /** How many bytes wait for the rest of their frame. */
    public val pending: Int get() = buffer.size

    public fun wipe() {
        buffer.fill(0)
        buffer = ByteArray(0)
    }
}
