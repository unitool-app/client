package cx.m42.unitool.kit.openssl

import cx.m42.unitool.kit.crypto.Aes
import cx.m42.unitool.kit.crypto.BlockMode
import cx.m42.unitool.kit.crypto.Hash
import cx.m42.unitool.kit.crypto.Pkcs7
import cx.m42.unitool.kit.crypto.evpBytesToKey
import cx.m42.unitool.kit.crypto.pbkdf2
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `openssl enc` on every target (cr-restore D618, layers 1 and 3): the standards' vectors for each
 * piece, then every file the real tool wrote in [OpensslFixtures].
 */
class OpensslEncTest {

    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    // ------------------------------------------------------------ the pieces

    @Test
    fun aesBothWaysOnTheFips197Vectors() {
        val plain = hex("00112233445566778899aabbccddeeff")
        val vectors = mapOf(
            "000102030405060708090a0b0c0d0e0f" to "69c4e0d86a7b0430d8cdb78070b4c55a",
            "000102030405060708090a0b0c0d0e0f1011121314151617" to "dda97ca4864cdfe06eaf70a0ec0d7191",
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f" to "8ea2b7ca516745bfeafc49904b496089",
        )
        for ((key, cipher) in vectors) {
            val aes = Aes(hex(key))
            val out = ByteArray(16)
            aes.encryptBlock(plain, 0, out, 0)
            assertEquals(cipher, out.hex(), key)
            aes.decryptBlock(out, 0, out, 0)
            assertEquals(plain.hex(), out.hex(), key)
        }
    }

    @Test
    fun everyModeRoundTripsAtEveryLength() {
        val key = ByteArray(32) { it.toByte() }
        val iv = ByteArray(16) { (0xf0 + it).toByte() }
        for (mode in BlockMode.entries) {
            for (n in listOf(0, 1, 15, 16, 17, 31, 32, 33, 100)) {
                val data = ByteArray(n) { (it * 13 + 1).toByte() }
                val input = if (mode.padded) Pkcs7.pad(data, 16) else data
                val sealed = mode.encrypt(Aes(key), iv, input)
                assertEquals(input.size, sealed.size, "$mode $n")
                if (n > 0) assertTrue(!sealed.contentEquals(input), "$mode $n encrypted nothing")
                val opened = mode.decrypt(Aes(key), iv, sealed)
                assertContentEquals(input, opened, "$mode $n")
            }
        }
    }

    @Test
    fun pkcs7RefusesWhatIsNotPadding() {
        assertContentEquals(ByteArray(0), Pkcs7.unpad(ByteArray(16) { 16 }, 16))
        assertNull(Pkcs7.unpad(ByteArray(16) { 0 }, 16))
        assertNull(Pkcs7.unpad(ByteArray(16) { 17 }, 16))
        assertNull(Pkcs7.unpad(ByteArray(16).also { it[15] = 3; it[14] = 3; it[13] = 2 }, 16))
        assertNull(Pkcs7.unpad(ByteArray(15), 16))
    }

    @Test
    fun pbkdf2OnTheRfc7914Vectors() {
        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc" +
                "49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            pbkdf2(Hash.SHA256, "passwd".encodeToByteArray(), "salt".encodeToByteArray(), 1, 64).hex(),
        )
        assertEquals(
            "4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56" +
                "a1d425a1225833549adb841b51c9b3176a272bdebba1d078478f62b397f33c8d",
            pbkdf2(Hash.SHA256, "Password".encodeToByteArray(), "NaCl".encodeToByteArray(), 80000, 64).hex(),
        )
    }

    @Test
    fun evpBytesToKeyChainsItsDigests() {
        val password = "p".encodeToByteArray()
        val salt = "12345678".encodeToByteArray()
        val d1 = Hash.SHA256.digest(password + salt)
        val d2 = Hash.SHA256.digest(d1 + password + salt)
        assertContentEquals(d1 + d2.copyOf(16), evpBytesToKey(Hash.SHA256, password, salt, 48))
    }

    // ------------------------------------------------------------ what the tool wrote

    private fun paramsOf(case: OpensslFixtures.Case) = OpensslEnc.Params(
        cipher = case.cipher,
        kdf = case.kdf,
        iterations = case.iterations,
        digest = case.digest,
        salted = case.salted,
        padding = case.padding,
    )

    private fun keyOf(case: OpensslFixtures.Case): OpensslEnc.Key =
        if (case.rawKey != null) OpensslEnc.Key.Raw(hex(case.rawKey), hex(case.rawIv!!))
        else OpensslEnc.Key.Password(OpensslFixtures.PASSWORD.encodeToByteArray())

    @Test
    fun everyFileTheRealToolWroteOpens() {
        for (case in OpensslFixtures.cases) {
            val input = if (case.armored) case.file.encodeToByteArray() else Base64.Default.decode(case.file)
            val opened = OpensslEnc.decrypt(input, keyOf(case), paramsOf(case))
            val ok = assertIs<OpensslEnc.Opened.Ok>(opened, case.label)
            assertEquals(case.plaintext, Base64.Default.encode(ok.plaintext), case.label)
        }
    }

    @Test
    fun theFilesWeWriteAreTheToolsByteForByteGivenItsSalt() {
        for (case in OpensslFixtures.cases.filter { !it.armored }) {
            val file = Base64.Default.decode(case.file)
            val salt = if (case.salted && case.rawKey == null) file.copyOfRange(8, 16) else ByteArray(0)
            val ours = OpensslEnc.encrypt(Base64.Default.decode(case.plaintext), keyOf(case), paramsOf(case), salt)
            assertEquals(case.file, Base64.Default.encode(ours), case.label)
        }
    }

    @Test
    fun ourArmorIsTheTools() {
        for (case in OpensslFixtures.cases.filter { it.armored && it.file.count { c -> c == '\n' } > 1 }) {
            assertEquals(case.file, OpensslEnc.armor(OpensslEnc.dearmor(case.file)!!), case.label)
        }
    }

    @Test
    fun aWrongPasswordIsWrongKeyInCbcAndUncheckedInCtr() {
        val case = OpensslFixtures.cases.first { it.label == "aes-256-cbc pbkdf2" }
        var refused = 0
        for (i in 0 until 20) {
            val opened = OpensslEnc.decrypt(Base64.Default.decode(case.file), OpensslEnc.Key.Password("wrong $i".encodeToByteArray()), paramsOf(case))
            if (opened == OpensslEnc.Opened.WrongKey) refused++ else assertTrue(!(opened as OpensslEnc.Opened.Ok).checked || opened.plaintext.isNotEmpty())
        }
        assertTrue(refused >= 18, "padding should refuse nearly every wrong password, refused $refused of 20")

        val ctr = OpensslFixtures.cases.first { it.label == "aes-256-ctr pbkdf2" }
        val opened = assertIs<OpensslEnc.Opened.Ok>(OpensslEnc.decrypt(Base64.Default.decode(ctr.file), OpensslEnc.Key.Password("wrong".encodeToByteArray()), paramsOf(ctr)))
        assertEquals(false, opened.checked)
    }

    @Test
    fun whatIsNotOpensslSaysSo() {
        val params = OpensslEnc.Params()
        val password = OpensslEnc.Key.Password("x".encodeToByteArray())
        assertEquals(OpensslEnc.Opened.NotOpenssl, OpensslEnc.decrypt("hello world".encodeToByteArray(), password, params))
        assertIs<OpensslEnc.Opened.Unsupported>(OpensslEnc.decrypt(ByteArray(32), password, params.copy(cipher = "rc4")))
        assertIs<OpensslEnc.Opened.Unsupported>(OpensslEnc.decrypt(ByteArray(32), password, params.copy(digest = "md5")))
    }
}
