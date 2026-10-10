package cx.m42.unitool.kit.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

internal fun hex(text: String): ByteArray = text.filter { it.isLetterOrDigit() }.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

internal fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

/**
 * The primitives the kit's own formats stand on, against their RFCs' published vectors, on every
 * target the kit builds for — the native binary's included. The comparisons with the JDK on random
 * input stay in `services/ssh` (`PrimitivesTest`, `AgeTest`), next to the age code they grew up with.
 */
class CryptoVectorsTest {

    @Test
    fun poly1305MatchesRfc8439Section252() {
        val key = hex("85d6be7857556d337f4452fe42d506a80103808afb0db2fd4abff6af4149f51b")
        val tag = ChaCha20Poly1305.poly1305(key, "Cryptographic Forum Research Group".encodeToByteArray())
        assertEquals("a8061dc1305136c6c22b8baf0c0127a9", tag.hex())
    }

    @Test
    fun chachaBlockMatchesRfc8439Section232() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = hex("000000090000004a00000000")
        val block = ChaCha20Poly1305.block(key, 1, nonce)
        assertEquals(
            "10f1e7e4d13b5915500fdd1fa32071c4c7d1f4c733c068030422aa9ac3d46c4e" +
                "d2826446079faa0914c2d705d98b02a2b5129cd1de164eb9cbd083e8a2503c4e",
            block.hex(),
        )
    }

    @Test
    fun aeadMatchesRfc8439Section282AndRefusesATamperedTag() {
        val plaintext = (
            "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, " +
                "sunscreen would be it."
            ).encodeToByteArray()
        val key = hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
        val nonce = hex("070000004041424344454647")
        val aad = hex("50515253c0c1c2c3c4c5c6c7")
        val sealed = ChaCha20Poly1305.seal(key, nonce, plaintext, aad)
        assertEquals(
            "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d6" +
                "3dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b36" +
                "92ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc" +
                "3ff4def08e4b7a9de576d26586cec64b6116" + "1ae10b594f09e26a7e902ecbd0600691",
            sealed.hex(),
        )
        assertContentEquals(plaintext, ChaCha20Poly1305.open(key, nonce, sealed, aad))
        sealed[sealed.size - 1] = (sealed.last().toInt() xor 1).toByte()
        assertNull(ChaCha20Poly1305.open(key, nonce, sealed, aad))
    }

    @Test
    fun hkdfMatchesRfc5869TestCase1StepByStep() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")
        val prk = hkdfExtract(salt, ikm)
        assertEquals("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5", prk.hex())
        val okm = "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"
        assertEquals(okm, hkdfExpand(prk, info, 42).hex())
        assertEquals(okm, hkdfSha256(ikm, salt, info, 42).hex())
        assertFailsWith<IllegalArgumentException> { hkdfExpand(prk, info, 255 * 32 + 1) }
    }

    @Test
    fun hmacSha256MatchesRfc4231TestCase2() {
        val mac = hmac(Hash.SHA256, "Jefe".encodeToByteArray(), "what do ya want for nothing?".encodeToByteArray())
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843", mac.hex())
    }

    /**
     * A check for a broken source, not for a weak one (.ai-notes/crypt 02 §1.1): a stub or a wrong
     * `actual` returns zeros or the same bytes twice. Only the choice of the system's CSPRNG guards
     * against a weak generator.
     */
    @Test
    fun secureRandomBytesAreFreshAndOfTheAskedSize() {
        assertEquals(0, secureRandomBytes(0).size)
        val a = secureRandomBytes(32)
        val b = secureRandomBytes(32)
        assertEquals(32, a.size)
        assertFalse(a.all { it == 0.toByte() }, "all zeros")
        assertFalse(a.contentEquals(b), "the same 32 bytes twice")
        val big = secureRandomBytes(200_000)
        assertEquals(200_000, big.size)
        // Every byte value turns up in 200 000 bytes; a source that fills only a prefix would not.
        assertEquals(256, big.map { it }.toSet().size)
        assertFalse(big.copyOfRange(150_000, 200_000).all { it == 0.toByte() }, "the tail was never filled")
    }
}
