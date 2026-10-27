package cx.m42.unitool.kit.openssl

import cx.m42.unitool.kit.ssh.Tools
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs

/**
 * `openssl enc` live, both ways (cr-restore D618, layer 2): every cipher `OpensslEnc` knows ×
 * both ways of deriving the key × every digest, random data and passwords — what the tool seals
 * we open, what we seal the tool opens. The list of ciphers is checked against the tool's own
 * `-list`, so a name we offer that this `openssl` does not know fails here.
 */
class OpensslInteropTest {

    private val random = Random(20261027)

    private fun openssl(args: List<String>, input: ByteArray): ByteArray = Tools.ok(listOf("openssl", "enc") + args, input).out

    private fun passwordArgs(password: String, params: OpensslEnc.Params): List<String> = buildList {
        add("-" + params.cipher)
        add("-pass"); add("pass:$password")
        add("-md"); add(params.digest)
        if (params.kdf == OpensslEnc.Kdf.Pbkdf2) { add("-pbkdf2"); add("-iter"); add(params.iterations.toString()) }
        if (!params.salted) add("-nosalt")
        if (!params.padding) add("-nopad")
    }

    private fun randomPassword(): String {
        val alphabet = "abcXYZ019 !@#\$%^&*()_+-=[]{};:,.<>?/|~`пароль✓"
        return String(CharArray(random.nextInt(1, 24)) { alphabet[random.nextInt(alphabet.length)] })
    }

    @Test
    fun everyCipherKdfAndDigestBothWays() {
        Tools.require("openssl")
        val listed = Tools.ok(listOf("openssl", "enc", "-list"), null).text.split(Regex("\\s+")).map { it.removePrefix("-") }.toSet()
        for (cipher in OpensslEnc.ciphers.keys) {
            check(cipher in listed) { "this openssl does not list $cipher" }
            for (kdf in OpensslEnc.Kdf.entries) {
                for (digest in OpensslEnc.digests.keys) {
                    val params = OpensslEnc.Params(cipher = cipher, kdf = kdf, digest = digest, iterations = random.nextInt(1, 2000))
                    val password = randomPassword()
                    val data = random.nextBytes(listOf(0, 1, 15, 16, 17, 64, 1000, 4097)[random.nextInt(8)])
                    val label = "$cipher $kdf $digest ${data.size}"

                    val byTool = openssl(listOf("-e") + passwordArgs(password, params), data)
                    val opened = assertIs<OpensslEnc.Opened.Ok>(OpensslEnc.decrypt(byTool, OpensslEnc.Key.Password(password.encodeToByteArray()), params), label)
                    assertContentEquals(data, opened.plaintext, label)

                    val byUs = OpensslEnc.encrypt(data, OpensslEnc.Key.Password(password.encodeToByteArray()), params)
                    assertContentEquals(data, openssl(listOf("-d") + passwordArgs(password, params), byUs), label)
                }
            }
        }
    }

    @Test
    fun noSaltRawKeysNoPaddingAndBase64BothWays() {
        Tools.require("openssl")
        repeat(20) {
            val cipher = OpensslEnc.ciphers.values.random(random)
            val data = random.nextBytes(if (cipher.mode.padded) 16 * random.nextInt(0, 5) else random.nextInt(0, 300))
            val password = randomPassword()

            val nosalt = OpensslEnc.Params(cipher = cipher.name, salted = false)
            val byTool = openssl(listOf("-e") + passwordArgs(password, nosalt), data)
            assertContentEquals(data, assertIs<OpensslEnc.Opened.Ok>(OpensslEnc.decrypt(byTool, OpensslEnc.Key.Password(password.encodeToByteArray()), nosalt)).plaintext, "${cipher.name} nosalt")

            val key = random.nextBytes(cipher.keySize)
            val iv = random.nextBytes(cipher.ivSize)
            val raw = OpensslEnc.Key.Raw(key, iv)
            val rawArgs = listOf("-" + cipher.name, "-K", key.toHex()) + (if (cipher.ivSize > 0) listOf("-iv", iv.toHex()) else emptyList())
            val sealed = OpensslEnc.encrypt(data, raw, OpensslEnc.Params(cipher = cipher.name))
            assertContentEquals(data, openssl(listOf("-d") + rawArgs, sealed), "${cipher.name} -K")
            val byToolRaw = openssl(listOf("-e") + rawArgs, data)
            assertContentEquals(data, assertIs<OpensslEnc.Opened.Ok>(OpensslEnc.decrypt(byToolRaw, raw, OpensslEnc.Params(cipher = cipher.name))).plaintext, "${cipher.name} -K by tool")

            if (cipher.mode.padded) {
                val nopad = OpensslEnc.Params(cipher = cipher.name, padding = false)
                val byToolNopad = openssl(listOf("-e") + passwordArgs(password, nopad), data)
                assertContentEquals(data, assertIs<OpensslEnc.Opened.Ok>(OpensslEnc.decrypt(byToolNopad, OpensslEnc.Key.Password(password.encodeToByteArray()), nopad)).plaintext, "${cipher.name} -nopad")
            }

            val params = OpensslEnc.Params(cipher = cipher.name)
            for (single in listOf(false, true)) {
                val armored = openssl(listOf("-e", "-a") + (if (single) listOf("-A") else emptyList()) + passwordArgs(password, params), data)
                assertContentEquals(data, assertIs<OpensslEnc.Opened.Ok>(OpensslEnc.decrypt(armored, OpensslEnc.Key.Password(password.encodeToByteArray()), params)).plaintext, "${cipher.name} -a $single")
            }
            val ours = OpensslEnc.armor(OpensslEnc.encrypt(data, OpensslEnc.Key.Password(password.encodeToByteArray()), params))
            assertContentEquals(data, openssl(listOf("-d", "-a") + passwordArgs(password, params), ours.encodeToByteArray()), "${cipher.name} our -a")
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
