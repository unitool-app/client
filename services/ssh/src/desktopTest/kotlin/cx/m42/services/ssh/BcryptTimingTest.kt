package cx.m42.services.ssh

import cx.m42.unitool.kit.crypto.BcryptPbkdf
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * P9 on the JVM (04 §2): what 24 rounds of `bcrypt_pbkdf` cost here, for a 48-byte key and IV —
 * exactly what opening an `aes256-ctr` key file asks. Printed for the table; asserted only loosely,
 * because a slow CI runner is not a bug and a pathological regression is.
 */
class BcryptTimingTest {
    @Test
    fun twentyFourRoundsForAKeyFile() {
        BcryptPbkdf.derive("warm up".toByteArray(), ByteArray(16) { it.toByte() }, 2, 48)
        val started = System.nanoTime()
        BcryptPbkdf.derive("correct horse battery".toByteArray(), ByteArray(16) { it.toByte() }, 24, 48)
        val millis = (System.nanoTime() - started) / 1_000_000
        println("P9 JVM: bcrypt_pbkdf 24 rounds, 48 bytes: $millis ms")
        assertTrue(millis < 30_000, "$millis ms")
    }
}
