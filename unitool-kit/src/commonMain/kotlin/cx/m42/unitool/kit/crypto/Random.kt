package cx.m42.unitool.kit.crypto

/**
 * Bytes from the operating system's CSPRNG: `SecureRandom` on the JVM and Android,
 * `SecRandomCopyBytes` on iOS, `crypto.getRandomValues` in the browser, `getrandom(2)` on Linux.
 * There is no fallback: a platform that cannot answer throws, because no bytes is better than weak
 * ones for a key.
 *
 * Not `kotlin.random.Random`, which is fine for an id and wrong for a key.
 */
public expect fun secureRandomBytes(size: Int): ByteArray
