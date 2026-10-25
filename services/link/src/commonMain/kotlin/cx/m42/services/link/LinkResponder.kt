package cx.m42.services.link

import cx.m42.unitool.kit.link.FrameReader
import cx.m42.unitool.kit.link.LinkMessage
import cx.m42.unitool.kit.link.LinkWire
import cx.m42.unitool.kit.link.NoiseHandshake
import cx.m42.unitool.kit.link.NoiseKeyPair
import cx.m42.unitool.kit.link.NoisePattern
import cx.m42.unitool.kit.link.NoiseTransport
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The phone's half of the protocol over one connection (passwords-browser 01 §1–§3): the preamble,
 * then either pairing (`XXpsk3`, the computer from the scanned QR) or a session (`KK`, one of the
 * paired computers, found by trying their keys on the first message). What is said after that is
 * the app's, through the [LinkChannel] this hands back.
 *
 * Anything that is not the protocol — an unknown preamble, an oversized or slow frame, a handshake
 * that does not open — ends in null, and the caller closes the pipe without a word (01 §1).
 */
public class LinkResponder(
    private val pipe: LinkPipe,
    private val handshakeTimeoutMillis: Long = HANDSHAKE_TIMEOUT_MILLIS,
) {
    private val frames = FrameReader()
    private val buffer = ByteArray(4096)

    public sealed interface Accepted {
        /** A pairing whose third message proved the psk and whose computer key is the QR's. */
        public class Pairing(public val channel: LinkChannel) : Accepted

        /** A session from the paired computer whose static key is [computerKey]. */
        public class Session(public val channel: LinkChannel, public val computerKey: ByteArray) : Accepted
    }

    /** What the screen expects right now: a pairing in progress (from the scanned code), the paired computers, or both. */
    public class Expected(
        public val phone: NoiseKeyPair,
        public val computers: List<ByteArray>,
        public val pairingPsk: ByteArray? = null,
        public val pairingComputer: ByteArray? = null,
    )

    public suspend fun accept(expected: Expected): Accepted? = withTimeoutOrNull(handshakeTimeoutMillis) {
        val preamble = readExactly(4) ?: return@withTimeoutOrNull null
        when (LinkWire.mode(preamble)) {
            LinkWire.Mode.Pair -> pair(expected)
            LinkWire.Mode.Session -> session(expected)
            null -> null
        }
    }

    private suspend fun pair(expected: Expected): Accepted? {
        val psk = expected.pairingPsk ?: return null
        val wanted = expected.pairingComputer ?: return null
        val hs = NoiseHandshake.responder(NoisePattern.XXpsk3, expected.phone, psk = psk, prologue = LinkWire.prologue(LinkWire.Mode.Pair))
        hs.read(readFrame() ?: return null) ?: return null
        writeFrame(hs.write())
        hs.read(readFrame() ?: return null) ?: return null
        // The psk is proved; the key must also be the one the QR showed (01 §2).
        if (!hs.isComplete || hs.remoteStatic?.contentEquals(wanted) != true) return null
        return Accepted.Pairing(LinkChannel(this, hs.split()))
    }

    private suspend fun session(expected: Expected): Accepted? {
        val first = readFrame() ?: return null
        for (computer in expected.computers) {
            val hs = NoiseHandshake.responder(NoisePattern.KK, expected.phone, computer, prologue = LinkWire.prologue(LinkWire.Mode.Session))
            if (hs.read(first) == null) continue
            writeFrame(hs.write())
            return Accepted.Session(LinkChannel(this, hs.split()), computer.copyOf())
        }
        return null
    }

    internal suspend fun readFrame(): ByteArray? {
        while (true) {
            frames.next()?.let { return it }
            val n = pipe.read(buffer)
            if (n < 0) return null
            frames.push(buffer, 0, n)
        }
    }

    internal suspend fun writeFrame(message: ByteArray) {
        pipe.write(LinkWire.frame(message))
    }

    /** The first [count] bytes; whatever came with them in the same read is the first frame's, and goes to [frames]. */
    private suspend fun readExactly(count: Int): ByteArray? {
        val out = ByteArray(count)
        var have = 0
        while (have < count) {
            val n = pipe.read(buffer)
            if (n < 0) return null
            val take = minOf(n, count - have)
            buffer.copyInto(out, have, 0, take)
            have += take
            if (n > take) frames.push(buffer, take, n - take)
        }
        return out
    }

    internal fun wipe() {
        frames.wipe()
        buffer.fill(0)
    }

    public companion object {
        /** The whole handshake, preamble included: a computer that is slower is not one of ours (01 §1). */
        public const val HANDSHAKE_TIMEOUT_MILLIS: Long = 10_000
    }
}

/** The Noise transport over the pipe, one [LinkMessage] at a time. */
public class LinkChannel internal constructor(private val responder: LinkResponder, private val transport: NoiseTransport) : AutoCloseable {

    /** The handshake's static key of the other side: the computer's. */
    public val remoteStatic: ByteArray get() = transport.remoteStatic.copyOf()

    public suspend fun send(message: LinkMessage) {
        val plain = LinkMessage.encode(message)
        try {
            responder.writeFrame(transport.seal(plain))
        } finally {
            plain.fill(0)
        }
    }

    /** The next message within [timeoutMillis], with its encoded bytes; null on a timeout, a close or anything that does not open. */
    public suspend fun receive(timeoutMillis: Long): Received? = withTimeoutOrNull(timeoutMillis) {
        val frame = responder.readFrame() ?: return@withTimeoutOrNull null
        val plain = transport.open(frame) ?: return@withTimeoutOrNull null
        LinkMessage.decode(plain)?.let { Received(it, plain) }
    }

    public class Received(public val message: LinkMessage, public val bytes: ByteArray)

    override fun close() {
        transport.close()
        responder.wipe()
    }
}
