package cx.m42.unitool.kit.crypto

/**
 * Bech32 (BIP 173, the original checksum, not bech32m) as age uses it for `age1…` recipients and
 * `AGE-SECRET-KEY-1…` identities — without BIP 173's 90-character limit, which age does not keep
 * either. Case-insensitive on reading, as long as the string is not mixed.
 */
public object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GENERATOR = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    public class Decoded(public val hrp: String, public val data: ByteArray)

    public fun encode(hrp: String, data: ByteArray): String {
        val values = convertBits(data, 8, 5, pad = true)!!
        val checksum = checksum(hrp, values)
        return hrp + "1" + (values + checksum).joinToString("") { CHARSET[it].toString() }
    }

    /** Null for anything that is not one valid Bech32 string. The [Decoded.hrp] is lower case. */
    public fun decode(text: String): Decoded? {
        if (text.any { it.code < 33 || it.code > 126 }) return null
        if (text != text.lowercase() && text != text.uppercase()) return null
        val s = text.lowercase()
        val separator = s.lastIndexOf('1')
        if (separator < 1 || separator + 7 > s.length) return null
        val hrp = s.substring(0, separator)
        val values = IntArray(s.length - separator - 1) { CHARSET.indexOf(s[separator + 1 + it]).also { v -> if (v < 0) return null } }
        if (polymod(expand(hrp) + values) != 1) return null
        val data = convertBits(values.copyOf(values.size - 6), 5, 8, pad = false) ?: return null
        return Decoded(hrp, data.map { it.toByte() }.toByteArray())
    }

    private fun checksum(hrp: String, values: IntArray): IntArray {
        val mod = polymod(expand(hrp) + values + IntArray(6)) xor 1
        return IntArray(6) { (mod ushr (5 * (5 - it))) and 31 }
    }

    private fun polymod(values: IntArray): Int {
        var chk = 1
        for (v in values) {
            val top = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0 until 5) if ((top ushr i) and 1 != 0) chk = chk xor GENERATOR[i]
        }
        return chk
    }

    private fun expand(hrp: String): IntArray =
        IntArray(hrp.length) { hrp[it].code ushr 5 } + intArrayOf(0) + IntArray(hrp.length) { hrp[it].code and 31 }

    private fun convertBits(data: ByteArray, from: Int, to: Int, pad: Boolean): IntArray? =
        convertBits(IntArray(data.size) { data[it].toInt() and 0xff }, from, to, pad)

    private fun convertBits(data: IntArray, from: Int, to: Int, pad: Boolean): IntArray? {
        var acc = 0
        var bits = 0
        val out = ArrayList<Int>()
        val max = (1 shl to) - 1
        for (value in data) {
            if (value ushr from != 0) return null
            acc = (acc shl from) or value
            bits += from
            while (bits >= to) {
                bits -= to
                out += (acc ushr bits) and max
            }
        }
        if (pad) {
            if (bits > 0) out += (acc shl (to - bits)) and max
        } else if (bits >= from || ((acc shl (to - bits)) and max) != 0) {
            return null
        }
        return out.toIntArray()
    }
}
