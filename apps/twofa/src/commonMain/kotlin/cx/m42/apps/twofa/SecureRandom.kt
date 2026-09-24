package cx.m42.apps.twofa

/**
 * Bytes from the platform's CSPRNG: `SecureRandom` on the JVM and Android, `crypto.getRandomValues`
 * in the browser.
 *
 * Not `kotlin.random.Random`, which is fine for an account id and wrong for a key: it is seeded
 * predictably and nothing promises otherwise.
 */
internal expect fun secureRandomBytes(size: Int): ByteArray
