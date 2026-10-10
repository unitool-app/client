package cx.m42.unitool.kit.crypto

import java.security.SecureRandom

private val random = SecureRandom()

public actual fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }
