package cx.m42.unitool.kit.crypto

/**
 * Through a hex string, because a string is the one thing that crosses the wasm/JS boundary
 * without a typed-array bridge — and these are a few dozen bytes at a time.
 */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
private fun randomHex(size: Int): String =
    js("Array.from(crypto.getRandomValues(new Uint8Array(size)), b => b.toString(16).padStart(2, '0')).join('')")

public actual fun secureRandomBytes(size: Int): ByteArray {
    if (size == 0) return ByteArray(0)
    val out = ByteArray(size)
    var filled = 0
    // getRandomValues refuses more than 65536 bytes at a time.
    while (filled < size) {
        val n = minOf(65_536, size - filled)
        val hex = randomHex(n)
        for (i in 0 until n) out[filled + i] = hex.substring(2 * i, 2 * i + 2).toInt(16).toByte()
        filled += n
    }
    return out
}
