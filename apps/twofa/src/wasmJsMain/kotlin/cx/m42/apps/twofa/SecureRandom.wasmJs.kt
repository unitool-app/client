package cx.m42.apps.twofa

/**
 * Through a hex string, because a string is the one thing that crosses the wasm/JS boundary
 * without a typed-array bridge — and these are a few dozen bytes per export.
 */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
private fun randomHex(size: Int): String =
    js("Array.from(crypto.getRandomValues(new Uint8Array(size)), b => b.toString(16).padStart(2, '0')).join('')")

internal actual fun secureRandomBytes(size: Int): ByteArray {
    val hex = randomHex(size)
    return ByteArray(size) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}
