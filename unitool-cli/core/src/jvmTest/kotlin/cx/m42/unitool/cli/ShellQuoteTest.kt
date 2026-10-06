package cx.m42.unitool.cli

import kotlin.test.Test
import kotlin.test.assertEquals

/** Needs a real `sh`, so it runs where a process can be started: on the JVM. */
class ShellQuoteTest {
    @Test
    fun `shell quoting round-trips through sh`() {
        for (word in listOf("plain", "with space", "it's", "\$HOME", "a\nb", "", "-x", "привет")) {
            val quoted = shellQuote(word)
            val process = ProcessBuilder("sh", "-c", "printf %s $quoted").start()
            assertEquals(word, process.inputStream.readBytes().decodeToString(), quoted)
            process.waitFor()
        }
    }
}
