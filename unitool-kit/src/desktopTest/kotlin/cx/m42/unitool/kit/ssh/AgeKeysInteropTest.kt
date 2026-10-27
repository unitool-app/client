package cx.m42.unitool.kit.ssh

import java.io.File
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * age's own recipients against the real `age` and `age-keygen`, live (cr-restore D618): keys
 * `age-keygen` makes, files `age -r` and `age -p` write, and the other way round. `age -p` reads
 * the passphrase from a terminal only, so it gets one from `script`.
 */
class AgeKeysInteropTest {

    private val random = Random(27)

    /** `age` with [passphrase] typed into its terminal [times] times. */
    private fun ageWithTerminal(dir: File, args: String, passphrase: String, times: Int): Tools.Result {
        val typed = (passphrase + "\n").repeat(times).encodeToByteArray()
        return Tools.run(listOf("script", "-qec", "age $args", "/dev/null"), input = typed, dir = dir)
    }

    @Test
    fun x25519BothWaysWithSeveralRecipients() = Tools.tempDir { dir ->
        Tools.require("age", "age-keygen")
        val keys = (1..3).map { i -> File(dir, "k$i.txt").also { Tools.ok(listOf("age-keygen", "-o", it.path)) } }
        val identities = keys.map { AgeKeys.parseIdentities(it.readText())!!.single() }
        for (size in listOf(0, 1, 64 * 1024 - 1, 64 * 1024, 64 * 1024 + 1, 200_000)) {
            val data = random.nextBytes(size)
            val args = identities.flatMap { listOf("-r", it.recipient) }
            for (armor in listOf(false, true)) {
                val byAge = Tools.ok(listOf("age") + args + (if (armor) listOf("-a") else emptyList()), input = data).out
                for (identity in identities) {
                    val opened = assertIs<AgeKeys.Opened.Ok>(AgeKeys.decrypt(byAge, identities = listOf(identity)), "$size $armor")
                    assertContentEquals(data, opened.plaintext)
                }
            }
            val byUs = AgeKeys.encryptTo(data, identities.map { AgeKeys.parseRecipient(it.recipient)!! })
            for (key in keys) assertContentEquals(data, Tools.ok(listOf("age", "-d", "-i", key.path), input = byUs).out, "$size by us")
        }
    }

    @Test
    fun passphraseBothWays() = Tools.tempDir { dir ->
        Tools.require("age", "script")
        for (passphrase in listOf("pw", "пароль с пробелом ✓", "x".repeat(70))) {
            val data = random.nextBytes(random.nextInt(0, 5000))
            File(dir, "in").writeBytes(data)
            val sealed = ageWithTerminal(dir, "-p -o in.age in", passphrase, times = 2)
            check(sealed.exit == 0) { "age -p failed: ${sealed.err}" }
            val byAge = File(dir, "in.age").readBytes()
            val opened = assertIs<AgeKeys.Opened.Ok>(AgeKeys.decrypt(byAge, passphrase = passphrase.encodeToByteArray()), passphrase)
            assertContentEquals(data, opened.plaintext)
            assertEquals(AgeKeys.Opened.NoIdentity, AgeKeys.decrypt(byAge, passphrase = "$passphrase!".encodeToByteArray()))

            File(dir, "ours.age").writeBytes(AgeKeys.encryptWithPassphrase(data, passphrase.encodeToByteArray()))
            check(ageWithTerminal(dir, "-d -o ours.out ours.age", passphrase, times = 1).exit == 0) { "age -d refused ours" }
            assertContentEquals(data, File(dir, "ours.out").readBytes(), passphrase)
            File(dir, "in.age").delete()
            File(dir, "ours.out").delete()
        }
    }

    @Test
    fun theFrozenPassphraseFileOpensHere() {
        val opened = assertIs<AgeKeys.Opened.Ok>(
            AgeKeys.decrypt(Base64.Default.decode(AgeKeysFixtures.WITH_PASSPHRASE), passphrase = AgeKeysFixtures.PASSPHRASE.encodeToByteArray()),
        )
        assertEquals(AgeKeysFixtures.PLAINTEXT, Base64.Default.encode(opened.plaintext))
    }

    @Test
    fun scryptWithAnotherRecipientIsRefusedLikeAgeDoes() {
        val identity = AgeKeys.generate()
        val sealed = AgeKeys.encryptWithPassphrase("x".encodeToByteArray(), "pw".encodeToByteArray(), logN = 10)
        val other = AgeKeys.encryptTo("x".encodeToByteArray(), listOf(AgeKeys.parseRecipient(identity.recipient)!!))
        // Splice the X25519 stanza into the scrypt file's header: the MAC breaks, but the rule comes first.
        val text = sealed.decodeToString(0, sealed.indexOf('-'.code.toByte(), sealed.indexOf("---".encodeToByteArray())))
        val stanza = other.decodeToString().lines().drop(1).takeWhile { !it.startsWith("---") }.joinToString("\n") + "\n"
        val spliced = (text.lines().first() + "\n" + stanza + text.lines().drop(1).joinToString("\n")).encodeToByteArray() +
            sealed.copyOfRange(text.length, sealed.size)
        assertEquals(AgeKeys.Opened.ScryptNotAlone, AgeKeys.decrypt(spliced, identities = listOf(identity), passphrase = "pw".encodeToByteArray()))
    }

    private fun ByteArray.indexOf(b: Byte, from: Int): Int = (from until size).first { this[it] == b }

    private fun ByteArray.indexOf(needle: ByteArray): Int =
        (0..size - needle.size).first { i -> needle.indices.all { this[i + it] == needle[it] } }
}
