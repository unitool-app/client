package cx.m42.services.ssh.crypto

/**
 * ECDSA signatures between the platform's DER (`SEQUENCE { INTEGER r, INTEGER s }`) and SSH's two
 * `mpint`s (01 §4.1: "DER from the Keystore laid out as mpint r, mpint s"). Magnitudes, big-endian,
 * no sign byte.
 */
internal object EcdsaDer {

    fun decode(der: ByteArray): Pair<ByteArray, ByteArray>? {
        var i = 0
        fun length(): Int? {
            if (i >= der.size) return null
            val first = der[i++].toInt() and 0xff
            if (first < 0x80) return first
            val count = first and 0x7f
            if (count == 0 || count > 2) return null
            var n = 0
            repeat(count) {
                if (i >= der.size) return null
                n = (n shl 8) or (der[i++].toInt() and 0xff)
            }
            return n
        }
        fun integer(): ByteArray? {
            if (i >= der.size || der[i++] != 0x02.toByte()) return null
            val n = length() ?: return null
            if (n == 0 || i + n > der.size) return null
            return magnitude(der.copyOfRange(i, i + n)).also { i += n }
        }
        if (der.isEmpty() || der[i++] != 0x30.toByte()) return null
        val total = length() ?: return null
        if (i + total != der.size) return null
        val r = integer() ?: return null
        val s = integer() ?: return null
        return if (i == der.size) r to s else null
    }

    fun encode(r: ByteArray, s: ByteArray): ByteArray {
        val content = integer(r) + integer(s)
        return byteArrayOf(0x30) + length(content.size) + content
    }

    private fun integer(value: ByteArray): ByteArray {
        var body = magnitude(value)
        if (body.isEmpty()) body = byteArrayOf(0)
        if (body[0].toInt() and 0x80 != 0) body = byteArrayOf(0) + body
        return byteArrayOf(0x02) + length(body.size) + body
    }

    private fun length(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        else -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
    }

    fun magnitude(value: ByteArray): ByteArray {
        var start = 0
        while (start < value.size && value[start] == 0.toByte()) start++
        return value.copyOfRange(start, value.size)
    }
}
