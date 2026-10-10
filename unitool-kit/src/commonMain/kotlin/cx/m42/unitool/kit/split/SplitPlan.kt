package cx.m42.unitool.kit.split

/**
 * The rules of the split wizard, shared by the CLI now and the phone later (.ai-notes/done/split D373):
 * how many parts may be asked for, what a choice means, and whether a set of parts can open a
 * container yet. Each interface keeps only its words and its screens, so the two cannot disagree.
 */
public object SplitPlan {

    /** How many parts a person may make, and how many it may take to open them (D369). */
    public val PARTS: IntRange = 2..5

    /** What the wizard suggests: any 2 of 3, so one part may be lost. */
    public const val SUGGESTED_N: Int = 3
    public const val SUGGESTED_K: Int = 2

    /** The thresholds allowed for [n] parts. */
    public fun thresholds(n: Int): IntRange = 2..n

    /** What [k] of [n] means, as numbers the interface puts into words. */
    public fun meaning(n: Int, k: Int): Meaning {
        require(n in PARTS && k in thresholds(n)) { "$k of $n is not a choice" }
        return Meaning(n = n, k = k, mayLose = n - k)
    }

    /**
     * Why [parts] cannot open the set [setId] that needs [k] of [n] yet, or null when they can. The
     * first problem found, in the order given: a part from another set, a part that disagrees with
     * its own set about k or n, a part given twice, and only then too few parts.
     */
    public fun check(setId: ByteArray, k: Int, n: Int, parts: List<SplitPart>): SplitProblem? {
        val expected = setId.toHex()
        parts.forEachIndexed { index, part ->
            if (part.setIdHex != expected) return SplitProblem.OtherSet(index, part.setIdHex, expected)
            if (part.k != k || part.n != n) return SplitProblem.Inconsistent(index)
            val earlier = parts.subList(0, index).indexOfFirst { it.x == part.x }
            if (earlier >= 0) return SplitProblem.SameNumber(index, earlier, part.x)
        }
        if (parts.size < k) return SplitProblem.TooFew(parts.size, k)
        return null
    }
}

/** [k] of [n] parts open the secret; [mayLose] of them can be lost without losing it. */
public class Meaning(public val n: Int, public val k: Int, public val mayLose: Int)

/** Why parts cannot be joined yet. Indices are into the list of parts as given. */
public sealed interface SplitProblem {
    /** Part [index] belongs to the set [setId], and this container to [expected]. */
    public class OtherSet(public val index: Int, public val setId: String, public val expected: String) : SplitProblem

    /** Part [index] has the right set id and the wrong k or n: damaged, or made by hand. */
    public class Inconsistent(public val index: Int) : SplitProblem

    /** Part [index] is number [x], like part [earlier]. */
    public class SameNumber(public val index: Int, public val earlier: Int, public val x: Int) : SplitProblem

    /** [have] parts, and it takes [need]. */
    public class TooFew(public val have: Int, public val need: Int) : SplitProblem
}
