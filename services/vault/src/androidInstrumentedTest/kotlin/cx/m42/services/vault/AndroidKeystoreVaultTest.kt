package cx.m42.services.vault

import android.security.keystore.KeyInfo
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyStore
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import kotlin.io.encoding.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The vault against a real `AndroidKeyStore` — on an emulator or a phone, because nothing else has
 * one. Every test uses keys of its own and deletes them, so the app's key is never touched.
 */
@RunWith(AndroidJUnit4::class)
class AndroidKeystoreVaultTest {

    private val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val alias = "test.vault." + System.nanoTime()
    private val vault = AndroidKeystoreVault(alias)

    @After
    fun deleteKeys() {
        keystore.aliases().toList().filter { it.startsWith(alias) }.forEach { keystore.deleteEntry(it) }
    }

    @Test
    fun whatIsSealedOpensAgain() = runBlocking<Unit> {
        val text = """[{"issuer":"Пример","secret":"JBSWY3DPEHPK3PXP"}] ✓"""
        assertEquals(text, vault.open(vault.seal(text)))
        assertEquals("", vault.open(vault.seal("")))
    }

    @Test
    fun sealedTextShowsNothingOfThePlainText() = runBlocking<Unit> {
        val secret = "JBSWY3DPEHPK3PXP"
        val sealed = vault.seal("""{"secret":"$secret"}""")
        assertTrue(sealed.startsWith("v1:"))
        assertFalse(secret in sealed)
        assertFalse("secret" in sealed)
        // Nor in the bytes under the Base64: the nonce, then ciphertext and tag, nothing readable.
        assertFalse(secret in Base64.decode(sealed.removePrefix("v1:")).decodeToString())
    }

    @Test
    fun theSameTextNeverSealsTheSameWayTwice() = runBlocking<Unit> {
        val first = vault.seal("same")
        val second = vault.seal("same")
        assertNotEquals(first, second)
        assertNotEquals(first.substring(3, 19), second.substring(3, 19), "the nonce was reused")
    }

    @Test
    fun aChangedBitIsRefusedNotMisread() = runBlocking<Unit> {
        val sealed = vault.seal("JBSWY3DPEHPK3PXP")
        val body = Base64.decode(sealed.removePrefix("v1:"))
        for (index in listOf(0, 12, body.size / 2, body.size - 1)) {
            val flipped = body.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertNull(vault.open("v1:" + Base64.encode(flipped)), "a flip at byte $index was accepted")
        }
        assertNull(vault.open("v1:" + Base64.encode(body.copyOf(body.size - 1))), "a truncated text was accepted")
    }

    @Test
    fun textItDidNotSealIsNull() = runBlocking<Unit> {
        vault.seal("makes the key")
        assertNull(vault.open("JBSWY3DPEHPK3PXP"))
        assertNull(vault.open("""[{"secret":"JBSWY3DPEHPK3PXP"}]"""))
        assertNull(vault.open("v1:"))
        assertNull(vault.open("v1:not base64!"))
        assertNull(vault.open("v2:" + vault.seal("x").removePrefix("v1:")))
    }

    @Test
    fun anotherKeyCannotOpenIt() = runBlocking<Unit> {
        val other = AndroidKeystoreVault("$alias.other")
        assertNull(other.open(vault.seal("JBSWY3DPEHPK3PXP")))
    }

    @Test
    fun aDeletedKeyMeansNullAndANewKeyAfterIt() = runBlocking<Unit> {
        val sealed = vault.seal("JBSWY3DPEHPK3PXP")
        keystore.deleteEntry(alias)
        assertNull(vault.open(sealed))
        // The next seal makes a fresh key, which the old text still does not open under.
        val fresh = vault.seal("again")
        assertEquals("again", vault.open(fresh))
        assertNull(vault.open(sealed))
    }

    @Test
    fun theKeyLivesInTheKeystoreAndCannotBeReadOut() = runBlocking<Unit> {
        vault.seal("makes the key")
        val key = keystore.getKey(alias, null)
        assertTrue(key is SecretKey)
        assertEquals("AES", key.algorithm)
        assertNull(key.encoded, "the key material came out of the Keystore")
        val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        assertEquals(256, info.keySize)
        assertFalse(info.isUserAuthenticationRequired)
        // Hardware-backed on a phone, software on most emulators — reported, not asserted.
        @Suppress("DEPRECATION")
        Log.i("AndroidKeystoreVaultTest", "insideSecureHardware=${info.isInsideSecureHardware}")
    }

    @Test
    fun racingFirstSealsMakeOneKey() = runBlocking<Unit> {
        val texts = (1..16).map { "account $it" }
        val sealed = texts.map { text -> async(Dispatchers.IO) { vault.seal(text) } }.awaitAll()
        assertEquals(texts, sealed.map { vault.open(it) })
        assertNotNull(keystore.getKey(alias, null))
    }
}
