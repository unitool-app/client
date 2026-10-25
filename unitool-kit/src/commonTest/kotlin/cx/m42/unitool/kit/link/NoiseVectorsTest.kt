package cx.m42.unitool.kit.link

import cx.m42.unitool.kit.crypto.Curve25519
import cx.m42.unitool.kit.crypto.hex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * cacophony's vectors (github.com/haskell-cryptography/cacophony, `vectors/cacophony.txt`) for the
 * two patterns the link uses (usb-sign D310): every handshake message and every transport message
 * after it, byte for byte, with the fixed keys the vector gives — on every target the kit builds.
 */
class NoiseVectorsTest {

    private class Vector(
        val pattern: NoisePattern,
        val prologue: String,
        val psk: String,
        val initStatic: String,
        val initEphemeral: String,
        val respStatic: String,
        val respEphemeral: String,
        val handshakeHash: String,
        val messages: List<Pair<String, String>>,
    )

    private val vectors = listOf(
    Vector(
        pattern = NoisePattern.KK,
        prologue = "4a6f686e2047616c74",
        psk = "",
        initStatic = "e61ef9919cde45dd5f82166404bd08e38bceb5dfdfded0a34c8df7ed542214d1",
        initEphemeral = "893e28b9dc6ca8d611ab664754b8ceb7bac5117349a4439a6b0569da977c464a",
        respStatic = "4a3acbfdb163dec651dfa3194dece676d437029c62a408b4c5ea9114246e4893",
        respEphemeral = "bbdb4cdbd309f1a1f2e1456967fe288cadd6f712d65dc7b7793d5e63da6b375b",
        handshakeHash = "24c6b51ecb76277140ca018b5985bc9f03de321dae2d34dcae433dafef0131d9",
        messages = listOf(
            "4c756477696720766f6e204d69736573" to
                "ca35def5ae56cec33dc2036731ab14896bc4c75dbb07a61f879f8e3afa4c79440177015efc1fe7a37c629af7120a96274e6ab7afcc9261901d0e09ae32a5bb96",
            "4d757272617920526f746862617264" to
                "95ebc60d2b1fa672c1f46a8aa265ef51bfe38e7ccb39ec5be34069f144808843b274d3429adc47ca093ba63ef90f8da89fda108db471dccfa4894aa7b00003",
            "462e20412e20486179656b" to
                "966b05bc69ec01b8454d3160a214e6f24a3d884eb31ec2408af63f",
            "4361726c204d656e676572" to
                "0ad887fba4f611bbb4afe44ba3556b8164332ca7d5934634d63d80",
            "4a65616e2d426170746973746520536179" to
                "012b28ae646ae7830e2c5472cb023eab071c1db3d8413ec69b513b83832f974c2d",
            "457567656e2042f6686d20766f6e2042617765726b" to
                "bb3e6a48160d9c5971d37f975727294e0d868342db31832e54d07191ab0ca3c3703b5ed3d9",
        ),
    ),
    Vector(
        pattern = NoisePattern.XXpsk3,
        prologue = "4a6f686e2047616c74",
        psk = "54686973206973206d7920417573747269616e20706572737065637469766521",
        initStatic = "e61ef9919cde45dd5f82166404bd08e38bceb5dfdfded0a34c8df7ed542214d1",
        initEphemeral = "893e28b9dc6ca8d611ab664754b8ceb7bac5117349a4439a6b0569da977c464a",
        respStatic = "4a3acbfdb163dec651dfa3194dece676d437029c62a408b4c5ea9114246e4893",
        respEphemeral = "bbdb4cdbd309f1a1f2e1456967fe288cadd6f712d65dc7b7793d5e63da6b375b",
        handshakeHash = "a477edf6a131bbdb54707f6ea30eab6cd935d9b560f0e5fd1f053a95a99669fb",
        messages = listOf(
            "4c756477696720766f6e204d69736573" to
                "ca35def5ae56cec33dc2036731ab14896bc4c75dbb07a61f879f8e3afa4c7944c9f5ff0e8079630cb7e270c20bbf480821b77a384a645c71a2fd9b3db1c16a5f",
            "4d757272617920526f746862617264" to
                "95ebc60d2b1fa672c1f46a8aa265ef51bfe38e7ccb39ec5be34069f144808843b123def17f71e6ae8e57e0e1dec5949c5f7415c6f33517398747d821a06dc23ad430aa1fd7381d46195c378a819fd574425462cbb2d4ca339e738a0b7001dc91423fbf55a99af0c6f1df21012ceb2f",
            "462e20412e20486179656b" to
                "52187316111b118d4c060364f7b975dc0809b2590779aff2d63113c564f11744493384db7bf32d5ae6686df6ab06d508d2e07caaf1d6afc010b978735fc78900e71ae1d314130d042e729a",
            "4361726c204d656e676572" to
                "eaedc672d4c21e0e2955758756fb98f194c4e90d5deb5b6cf30b27",
            "4a65616e2d426170746973746520536179" to
                "522d543c5fe799d09a3d9da7ff54d0dc03c8af1dc7751d2ff708339d2290943e98",
            "457567656e2042f6686d20766f6e2042617765726b" to
                "7d4e2c3873eef6a213b04e72f9df60a91666072d3544c5d96c34a09e2329b5030bee796741",
        ),
    ),
    )

    private fun pair(private: String) = NoiseKeyPair.fromPrivate(hex(private))

    @Test
    fun everyMessageMatchesCacophony() {
        for (v in vectors) {
            val initStatic = pair(v.initStatic)
            val respStatic = pair(v.respStatic)
            val psk = v.psk.takeIf { it.isNotEmpty() }?.let(::hex)
            val kk = v.pattern == NoisePattern.KK
            val initiator = NoiseHandshake.forVector(
                v.pattern, true, initStatic, if (kk) respStatic.public else null, psk, pair(v.initEphemeral), hex(v.prologue),
            )
            val responder = NoiseHandshake.forVector(
                v.pattern, false, respStatic, if (kk) initStatic.public else null, psk, pair(v.respEphemeral), hex(v.prologue),
            )
            var sendI: NoiseTransport? = null
            var sendR: NoiseTransport? = null
            v.messages.forEachIndexed { i, (payload, ciphertext) ->
                val fromInitiator = i % 2 == 0
                if (!initiator.isComplete) {
                    val (writer, reader) = if (fromInitiator) initiator to responder else responder to initiator
                    val written = writer.write(hex(payload))
                    assertEquals(ciphertext, written.hex(), "${v.pattern} message $i")
                    assertContentEquals(hex(payload), reader.read(written), "${v.pattern} message $i read back")
                    if (initiator.isComplete) {
                        assertTrue(responder.isComplete)
                        sendI = initiator.split()
                        sendR = responder.split()
                        assertEquals(v.handshakeHash, sendI!!.handshakeHash.hex(), "${v.pattern} handshake hash")
                        assertContentEquals(sendI!!.handshakeHash, sendR!!.handshakeHash)
                        assertContentEquals(respStatic.public, sendI!!.remoteStatic)
                        assertContentEquals(initStatic.public, sendR!!.remoteStatic)
                    }
                } else {
                    val (writer, reader) = if (fromInitiator) sendI!! to sendR!! else sendR!! to sendI!!
                    val sealed = writer.seal(hex(payload))
                    assertEquals(ciphertext, sealed.hex(), "${v.pattern} transport message $i")
                    assertContentEquals(hex(payload), reader.open(sealed))
                }
            }
            assertNotNull(sendI)
        }
    }

    @Test
    fun publicKeysAreX25519OfThePrivate() {
        val p = NoiseKeyPair.generate()
        assertContentEquals(Curve25519.x25519(p.private.bytes, Curve25519.BASE), p.public)
    }

    @Test
    fun kkRefusesAStrangerAndKillsTheHandshake() {
        val computer = NoiseKeyPair.generate()
        val phone = NoiseKeyPair.generate()
        val stranger = NoiseKeyPair.generate()
        // The stranger claims to be the computer the phone knows, without its private key.
        val forged = NoiseHandshake.initiator(NoisePattern.KK, stranger, phone.public).write()
        val responder = NoiseHandshake.responder(NoisePattern.KK, phone, computer.public)
        assertNull(responder.read(forged))
        assertNull(responder.read(forged))
        assertTrue(!responder.isComplete && !responder.isMyTurn)
    }

    @Test
    fun xxpsk3WithTheWrongPskFailsAtTheThirdMessage() {
        val a = NoiseHandshake.initiator(NoisePattern.XXpsk3, NoiseKeyPair.generate(), psk = ByteArray(32) { 1 })
        val b = NoiseHandshake.responder(NoisePattern.XXpsk3, NoiseKeyPair.generate(), psk = ByteArray(32) { 2 })
        assertNotNull(b.read(a.write()))
        assertNotNull(a.read(b.write()))
        assertNull(b.read(a.write()))
    }

    @Test
    fun aTransportMessageOpensOnceAndOnlyInOrder() {
        val c = NoiseKeyPair.generate()
        val p = NoiseKeyPair.generate()
        val i = NoiseHandshake.initiator(NoisePattern.KK, c, p.public)
        val r = NoiseHandshake.responder(NoisePattern.KK, p, c.public)
        r.read(i.write())
        i.read(r.write())
        val ti = i.split()
        val tr = r.split()
        val first = ti.seal("one".encodeToByteArray())
        val second = ti.seal("two".encodeToByteArray())
        assertNull(tr.open(second))
        assertEquals("one", tr.open(first)!!.decodeToString())
        assertNull(tr.open(first))
        assertEquals("two", tr.open(second)!!.decodeToString())
    }
}
