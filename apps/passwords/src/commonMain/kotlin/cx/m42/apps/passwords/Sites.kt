package cx.m42.apps.passwords

/**
 * Which entries belong to the site a computer asks for (passwords-browser D543, D561): the same
 * registrable domain — eTLD+1 by the Public Suffix List — so `accounts.example.com` finds an entry
 * saved for `example.com`, and `a.github.io` never finds one for `b.github.io`.
 */
internal object Sites {

    /** The host of an origin as the browser sends it (`https://github.com`, `http://localhost:8080`); null for anything else. */
    fun originHost(origin: String): String? {
        val rest = when {
            origin.startsWith("https://") -> origin.removePrefix("https://")
            origin.startsWith("http://") -> origin.removePrefix("http://")
            else -> return null
        }
        if (rest.isEmpty() || rest.any { it == '/' || it == '?' || it == '#' || it == '@' || it.isWhitespace() }) return null
        val host = if (rest.startsWith("[")) rest.substringBefore(']') + "]" else rest.substringBefore(':')
        return host.lowercase().trimEnd('.').takeIf { it.isNotEmpty() }
    }

    /** An entry's site as ASCII, the form an origin has: `пример.рф` → `xn--e1afmkfd.xn--p1ai`. */
    fun entryHost(url: String): String = Punycode.toAscii(Site.host(url).trimEnd('.'))

    /** Whether [entry] was saved for [requestHost]'s site. An entry without a site belongs to none. */
    fun sameSite(entry: PasswordEntry, requestHost: String): Boolean {
        val host = entryHost(entry.url)
        if (host.isEmpty()) return false
        return registrable(host) == registrable(requestHost)
    }

    /**
     * eTLD+1, or the host itself where there is none: an IP address, `localhost`, a single label,
     * or a host that is itself a public suffix.
     */
    fun registrable(host: String): String {
        val h = host.lowercase().trimEnd('.')
        if (h.startsWith("[") || h.all { it.isDigit() || it == '.' } || !h.contains('.')) return h
        val labels = h.split('.')
        val suffix = PublicSuffixes.suffixLength(labels)
        return if (labels.size > suffix) labels.takeLast(suffix + 1).joinToString(".") else h
    }

    /** The host as a person reads it: Unicode labels where the ASCII form had `xn--`. */
    fun unicode(host: String): String = Punycode.toUnicode(host)
}

/** The list itself, read once (PublicSuffixData, scripts/update-psl.sh). */
internal object PublicSuffixes {
    private val rules: Set<String> by lazy {
        HashSet<String>(16_384).apply { PublicSuffixData.CHUNKS.forEach { chunk -> addAll(chunk.split('\n')) } }
    }

    /**
     * How many labels on the right are the public suffix (publicsuffix.org's algorithm): the longest
     * matching rule, an exception rule winning over the rest, `*` when nothing matches.
     */
    fun suffixLength(labels: List<String>): Int {
        var best = 1
        for (n in 1..labels.size) {
            val tail = labels.takeLast(n).joinToString(".")
            if ("!$tail" in rules) return n - 1
            val wildcard = if (n > 1) "*." + labels.takeLast(n - 1).joinToString(".") else null
            if (tail in rules || (wildcard != null && wildcard in rules)) best = n
        }
        return best
    }
}

/** RFC 3492 with IDNA's `xn--` per label — enough to show a host both ways and to compare them. */
internal object Punycode {
    private const val BASE = 36
    private const val TMIN = 1
    private const val TMAX = 26
    private const val SKEW = 38
    private const val DAMP = 700
    private const val INITIAL_BIAS = 72
    private const val INITIAL_N = 128

    fun toAscii(host: String): String = host.lowercase().split('.').joinToString(".") { label ->
        if (label.all { it.code < 0x80 }) label else encode(label)?.let { "xn--$it" } ?: label
    }

    fun toUnicode(host: String): String = host.split('.').joinToString(".") { label ->
        if (label.startsWith("xn--", ignoreCase = true)) decode(label.substring(4).lowercase()) ?: label else label
    }

    private fun adapt(deltaIn: Int, points: Int, first: Boolean): Int {
        var delta = if (first) deltaIn / DAMP else deltaIn / 2
        delta += delta / points
        var k = 0
        while (delta > ((BASE - TMIN) * TMAX) / 2) {
            delta /= BASE - TMIN
            k += BASE
        }
        return k + (BASE - TMIN + 1) * delta / (delta + SKEW)
    }

    private fun digit(d: Int): Char = if (d < 26) 'a' + d else '0' + (d - 26)

    private fun value(c: Char): Int = when (c) {
        in 'a'..'z' -> c - 'a'
        in 'A'..'Z' -> c - 'A'
        in '0'..'9' -> c - '0' + 26
        else -> BASE
    }

    private fun codePoints(s: String): IntArray {
        val out = ArrayList<Int>(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                out += 0x10000 + ((c.code - 0xD800) shl 10) + (s[i + 1].code - 0xDC00)
                i += 2
            } else {
                out += c.code
                i++
            }
        }
        return out.toIntArray()
    }

    private fun StringBuilder.appendCodePoint(cp: Int) {
        if (cp < 0x10000) {
            append(cp.toChar())
        } else {
            val v = cp - 0x10000
            append((0xD800 + (v shr 10)).toChar())
            append((0xDC00 + (v and 0x3FF)).toChar())
        }
    }

    fun encode(input: String): String? {
        val points = codePoints(input)
        val out = StringBuilder()
        points.filter { it < 0x80 }.forEach { out.append(it.toChar()) }
        val basic = out.length
        var handled = basic
        if (basic > 0) out.append('-')
        var n = INITIAL_N
        var delta = 0
        var bias = INITIAL_BIAS
        while (handled < points.size) {
            val m = points.filter { it >= n }.minOrNull() ?: return null
            if (m - n > (Int.MAX_VALUE - delta) / (handled + 1)) return null
            delta += (m - n) * (handled + 1)
            n = m
            for (c in points) {
                if (c < n) delta++
                if (c == n) {
                    var q = delta
                    var k = BASE
                    while (true) {
                        val t = if (k <= bias) TMIN else if (k >= bias + TMAX) TMAX else k - bias
                        if (q < t) break
                        out.append(digit(t + (q - t) % (BASE - t)))
                        q = (q - t) / (BASE - t)
                        k += BASE
                    }
                    out.append(digit(q))
                    bias = adapt(delta, handled + 1, handled == basic)
                    delta = 0
                    handled++
                }
            }
            delta++
            n++
        }
        return out.toString()
    }

    fun decode(input: String): String? {
        val output = ArrayList<Int>()
        val dash = input.lastIndexOf('-')
        if (dash > 0) input.substring(0, dash).forEach { if (it.code >= 0x80) return null; output += it.code }
        var n = INITIAL_N
        var i = 0
        var bias = INITIAL_BIAS
        var at = if (dash > 0) dash + 1 else 0
        while (at < input.length) {
            val oldI = i
            var w = 1
            var k = BASE
            while (true) {
                if (at >= input.length) return null
                val d = value(input[at++])
                if (d >= BASE) return null
                if (d > (Int.MAX_VALUE - i) / w) return null
                i += d * w
                val t = if (k <= bias) TMIN else if (k >= bias + TMAX) TMAX else k - bias
                if (d < t) break
                w *= BASE - t
                k += BASE
            }
            bias = adapt(i - oldI, output.size + 1, oldI == 0)
            n += i / (output.size + 1)
            i %= output.size + 1
            if (n > 0x10FFFF) return null
            output.add(i, n)
            i++
        }
        return buildString { output.forEach { appendCodePoint(it) } }
    }
}
