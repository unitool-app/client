package cx.m42.unitool.kit.password

import cx.m42.unitool.kit.crypto.secureRandomBytes
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow

/** What a generated password is made of. */
public enum class PasswordMode {
    /** [PasswordRules.length] characters, at least one of every chosen kind. */
    Characters,

    /** [PasswordRules.words] words of the BIP-39 English list, separated by spaces: a passphrase. */
    Words,

    /** The same words, each joined to the next by one random digit or symbol of the chosen kinds. */
    Mixed,
}

/**
 * How to generate. The kinds are the character sets of [PasswordMode.Characters]; the digits and
 * symbols among them are also the joints of [PasswordMode.Mixed] (a letter there would only glue
 * two words into one). [PasswordMode.Words] uses none of them.
 *
 * The symbols leave out quotes, the comma and the backslash, so a password can be pasted into a CSV
 * file or a shell without anyone thinking about escaping. Unless [lookAlike] is on, letters and
 * digits that look alike (l, I, O, 0, 1) are left out too: a password is sometimes typed by hand.
 */
public data class PasswordRules(
    val mode: PasswordMode = PasswordMode.Characters,
    val length: Int = PasswordGenerator.DEFAULT_LENGTH,
    val words: Int = PasswordGenerator.DEFAULT_WORDS,
    val lower: Boolean = true,
    val upper: Boolean = true,
    val digits: Boolean = true,
    val symbols: Boolean = true,
    val lookAlike: Boolean = false,
) {
    /** The chosen character sets, in a fixed order; empty when none is on. */
    public val kinds: List<String>
        get() = buildList {
            if (lower) add(if (lookAlike) LOWER + "l" else LOWER)
            if (upper) add(if (lookAlike) UPPER + "IO" else UPPER)
            if (digits) add(if (lookAlike) DIGITS + "01" else DIGITS)
            if (symbols) add(SYMBOLS)
        }

    /** What [PasswordMode.Mixed] puts between words: the chosen digits and symbols. */
    public val joints: String
        get() = kinds.filter { !it.first().isLetter() }.joinToString("")

    /** Why these rules cannot make a password, or null when they can. */
    public val problem: String?
        get() = when {
            mode == PasswordMode.Characters && kinds.isEmpty() -> "no character set is chosen"
            mode == PasswordMode.Mixed && joints.isEmpty() -> "words and characters need digits or symbols between the words"
            mode == PasswordMode.Characters && length !in PasswordGenerator.LENGTHS ->
                "the length is ${PasswordGenerator.LENGTHS.first} to ${PasswordGenerator.LENGTHS.last}, not $length"
            mode != PasswordMode.Characters && words !in PasswordGenerator.WORD_COUNTS ->
                "the number of words is ${PasswordGenerator.WORD_COUNTS.first} to ${PasswordGenerator.WORD_COUNTS.last}, not $words"
            else -> null
        }

    private companion object {
        const val LOWER = "abcdefghijkmnopqrstuvwxyz"
        const val UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ"
        const val DIGITS = "23456789"
        const val SYMBOLS = "!#$%&*+-=?@^_"
    }
}

/**
 * Passwords from the device's own entropy: every choice is drawn from [secureRandomBytes] (the
 * platform's CSPRNG — SecureRandom on Android and the JVM, SecRandomCopyBytes on iOS, getrandom on
 * Linux, crypto.getRandomValues on the web), with no modulo bias. Shared by the Passwords app and
 * `unitool password`.
 */
public object PasswordGenerator {
    public const val DEFAULT_LENGTH: Int = 20
    public const val DEFAULT_WORDS: Int = 6

    /** From 4, so a password of every kind always fits. */
    public val LENGTHS: IntRange = 4..128
    public val WORD_COUNTS: IntRange = 3..24

    /** The BIP-39 English list: 2048 words, 11 bits each; the first four letters tell any two apart. */
    public val wordList: List<String> by lazy { BIP39_ENGLISH.split(' ') }

    public fun generate(rules: PasswordRules = PasswordRules(), random: (Int) -> ByteArray = ::secureRandomBytes): String {
        rules.problem?.let { throw IllegalArgumentException(it) }
        val source = Draws(random)
        return when (rules.mode) {
            PasswordMode.Characters -> characters(rules, source)
            PasswordMode.Words -> List(rules.words) { source.pick(wordList) }.joinToString(" ")
            PasswordMode.Mixed -> {
                val joints = rules.joints
                buildString {
                    repeat(rules.words) { i ->
                        if (i > 0) append(joints[source.below(joints.length)])
                        append(source.pick(wordList))
                    }
                }
            }
        }
    }

    /**
     * How many bits of entropy a password made by [rules] holds: log2 of how many different
     * passwords they can make, all equally likely. For characters that counts only the strings
     * with every kind in them, which is what [generate] draws from.
     */
    public fun entropyBits(rules: PasswordRules): Double {
        rules.problem?.let { throw IllegalArgumentException(it) }
        val wordBits = log2(wordList.size.toDouble())
        return when (rules.mode) {
            PasswordMode.Characters -> characterBits(rules.kinds.map { it.length }, rules.length)
            PasswordMode.Words -> rules.words * wordBits
            PasswordMode.Mixed -> rules.words * wordBits + (rules.words - 1) * log2(rules.joints.length.toDouble())
        }
    }

    private fun characters(rules: PasswordRules, source: Draws): String {
        val kinds = rules.kinds
        val all = kinds.joinToString("")
        while (true) {
            val chars = CharArray(rules.length) { all[source.below(all.length)] }
            if (kinds.all { kind -> chars.any { it in kind } }) return chars.concatToString()
        }
    }

    /**
     * log2 of the number of [length]-long strings over sets of [sizes] that use every set
     * (inclusion–exclusion), computed relative to the total so 128 characters do not overflow.
     */
    private fun characterBits(sizes: List<Int>, length: Int): Double {
        val total = sizes.sum().toDouble()
        var share = 0.0
        for (mask in 0 until (1 shl sizes.size)) {
            val left = total - sizes.indices.filter { mask and (1 shl it) != 0 }.sumOf { sizes[it] }
            val sign = if (mask.countOneBits() % 2 == 0) 1.0 else -1.0
            share += sign * (left / total).pow(length)
        }
        return length * log2(total) + ln(share) / ln(2.0)
    }

    /** Uniform numbers from a CSPRNG, bytes fetched in batches. */
    private class Draws(private val random: (Int) -> ByteArray) {
        private var buffer = ByteArray(0)
        private var at = 0

        fun <T> pick(list: List<T>): T = list[below(list.size)]

        /** 0 until [bound] (at most 65536), with no bias: draws past the last whole multiple of [bound] are drawn again. */
        fun below(bound: Int): Int {
            require(bound in 1..65536)
            val limit = 65536 - 65536 % bound
            while (true) {
                val v = (byte() shl 8) or byte()
                if (v < limit) return v % bound
            }
        }

        private fun byte(): Int {
            if (at == buffer.size) {
                buffer = random(64)
                at = 0
            }
            return buffer[at++].toInt() and 0xFF
        }
    }
}
