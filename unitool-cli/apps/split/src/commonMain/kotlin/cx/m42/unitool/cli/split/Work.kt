package cx.m42.unitool.cli.split

import cx.m42.unitool.cli.Io
import cx.m42.unitool.cli.fail
import cx.m42.unitool.cli.hidden
import cx.m42.unitool.cli.usage
import cx.m42.unitool.cli.write
import cx.m42.unitool.kit.crypt.Decrypted
import cx.m42.unitool.kit.crypt.Header
import cx.m42.unitool.kit.crypt.Inspected
import cx.m42.unitool.kit.crypt.Ucrypt
import cx.m42.unitool.kit.crypt.Unlocked
import cx.m42.unitool.kit.qr.ErrorCorrection
import cx.m42.unitool.kit.qr.Qr
import cx.m42.unitool.kit.qr.QrImage
import cx.m42.unitool.kit.qr.QrTerminal
import cx.m42.unitool.kit.split.BaseProblem
import cx.m42.unitool.kit.split.PartReading
import cx.m42.unitool.kit.split.SplitLink
import cx.m42.unitool.kit.split.SplitPart
import cx.m42.unitool.kit.split.SplitPlan
import cx.m42.unitool.kit.split.SplitProblem

/*
 * What `make`, `join` and `inspect` do, shared by the commands and the wizard, so the wizard cannot
 * do anything a command cannot (D370).
 */

/** Clears the screen and the scrollback, so a part that was shown is gone, not just scrolled up. */
internal const val CLEAR_ALL: String = "\u001b[H\u001b[2J\u001b[3J"

internal class MakePlan(
    val n: Int,
    val k: Int,
    val base: String,
    val out: String?,
    val armor: Boolean,
    val show: Show,
    val outDir: String,
) {
    /** Where each part goes when it goes to a file. */
    val partPaths: List<String> = show.extension?.let { ext ->
        (1..n).map { x -> (if (outDir == ".") "" else outDir.trimEnd('/') + "/") + "part-$x-of-$n.$ext" }
    }.orEmpty()

    /**
     * Everything that can be wrong before the secret is typed: nobody should type a secret and then
     * learn the address was wrong. Nothing is written until every path is known to be free.
     */
    fun check(io: Io) {
        SplitPlan.meaning(n, k)
        SplitLink.checkBase(base)?.let { usage("--base: ${words(it)}") }
        if (show == Show.Screen) {
            if (!io.stdoutIsTerminal) usage("--show screen needs a terminal on stdout; give png, svg or txt")
            if (out == null) usage("with --show screen the container goes to a file: give -o FILE")
        }
        (listOfNotNull(out) + partPaths).firstOrNull { io.exists(it) }?.let {
            fail("$it exists. Nothing was written: a container or a part is never replaced, it may be the only copy.")
        }
    }

    fun make(io: Io, text: ByteArray): Int {
        if (text.isEmpty()) fail("empty secret: nothing to encrypt")
        val sealed = Ucrypt.encryptSplit(text, k, n)
        text.fill(0)
        val links = sealed.parts.map { SplitLink.format(it, base) }
        val setId = sealed.parts.first().setIdHex
        sealed.parts.forEach(SplitPart::close)

        val container = if (armor || (out == null && io.stdoutIsTerminal)) Ucrypt.armor(sealed.file).encodeToByteArray() else sealed.file
        out?.let { io.writeFile(it, container, private = true, replace = false) }

        when (show) {
            Show.Screen -> showOnScreen(io, links, setId)
            else -> links.forEachIndexed { index, link ->
                io.writeFile(partPaths[index], render(show, link), private = true, replace = false)
            }
        }
        if (out == null) io.write(container)

        val where = out?.let { "Container: $it." } ?: "Container: on stdout."
        val parts = if (show == Show.Screen) "The $n parts were shown one at a time." else "Parts: ${partPaths.joinToString(", ")}."
        io.say("$where $parts Set $setId.")
        io.say("Keep the parts in different places. ${meaning(n, k)}")
        return 0
    }

    private fun showOnScreen(io: Io, links: List<String>, setId: String) {
        if (!io.pause("Ready to show part 1 of $n? Make sure nobody is looking, then press Enter")) {
            fail("no keyboard to wait on between parts; use --show png, svg or txt")
        }
        links.forEachIndexed { index, link ->
            io.write(CLEAR_ALL)
            io.say("Part ${index + 1} of $n · any $k open the secret · set $setId")
            io.write(render(Show.Screen, link))
            io.pause("Press Enter when part ${index + 1} is saved")
        }
        io.write(CLEAR_ALL)
    }
}

/** The part's QR code at level M (02 §2.3) in the chosen form, or its link as text. */
internal fun render(show: Show, link: String): ByteArray {
    if (show == Show.Txt) return (link + "\n").encodeToByteArray()
    val code = Qr.encode(link, ErrorCorrection.M) ?: fail("the link does not fit a QR code: ${link.length} characters")
    return when (show) {
        Show.Screen -> (QrTerminal.ansi(code) + link + "\n").encodeToByteArray()
        Show.Png -> QrImage.png(code)
        Show.Svg -> QrImage.svg(code).encodeToByteArray()
        Show.Txt -> error("unreachable")
    }
}

internal class JoinPlan(val file: String?, val partFiles: List<String>, val out: String?) {

    fun join(io: Io): Int {
        if (file == null && io.stdinIsTerminal) usage("give the container FILE, or pipe it to stdin")
        out?.let { if (io.exists(it)) fail("$it exists; it was left as it was") }
        val name = file ?: "stdin"
        val (header, bytes) = readContainer(io, if (file != null) io.readFile(file) else io.readStdin(), name)
        val record = header.split ?: fail("$name is for SSH keys, not opened by parts; this unitool opens only split containers yet")

        val parts = partFiles.map { path ->
            when (val reading = SplitLink.read(io.readFile(path).decodeToString())) {
                is PartReading.Ok -> reading.part
                else -> fail("$path: ${words(reading)}")
            }
        }.toMutableList()
        SplitPlan.check(record.setId, record.k, record.n, parts)?.let { problem ->
            if (problem !is SplitProblem.TooFew) fail(words(problem) { partFiles[it] })
        }
        while (parts.size < record.k) {
            val number = parts.size + 1
            val text = io.hidden("Part $number of ${record.k} (the link, or its 64 characters): ")
            val reading = SplitLink.read(text)
            if (reading !is PartReading.Ok) {
                io.say("  ${words(reading)}")
                continue
            }
            val problem = SplitPlan.check(record.setId, record.k, record.n, parts + reading.part)
            if (problem != null && problem !is SplitProblem.TooFew) {
                io.say("  ${words(problem) { if (it == parts.size) "this part" else "part ${it + 1}" }}")
                continue
            }
            parts += reading.part
        }

        val unlocked = header.unlock(parts)
        parts.forEach(SplitPart::close)
        val opened = when (unlocked) {
            is Unlocked.Ok -> unlocked
            is Unlocked.DoNotMatch -> fail(
                "the parts do not open $name: one of them is damaged or forged, or the container was changed." +
                    if (parts.size < record.n) " With one more part, unitool can tell which." else "",
            )
            is Unlocked.Parts -> fail(words(unlocked.problem) { "part ${it + 1}" })
            is Unlocked.Damaged -> fail("$name is damaged: ${unlocked.what}")
            is Unlocked.NotSplit -> fail("$name is not opened by parts")
        }
        opened.disagreeing.forEach { io.say("Warning: ${label(it)} does not belong with the others: damaged or forged. The rest opened the secret.") }
        val secret = opened.decryptor.use {
            when (val decrypted = it.decrypt(bytes)) {
                is Decrypted.Ok -> decrypted.plaintext
                is Decrypted.WrongLength -> fail(
                    if (decrypted.actual < decrypted.expected) "$name is cut short: ${decrypted.actual} of ${decrypted.expected} bytes"
                    else "$name has ${decrypted.actual - decrypted.expected} bytes after its end",
                )
                is Decrypted.BadChunk -> fail("$name is damaged: chunk ${decrypted.index} fails its check")
            }
        }
        if (out != null) io.writeFile(out, secret, private = true, replace = false) else io.write(secret)
        secret.fill(0)
        return 0
    }

    private fun label(index: Int): String = partFiles.getOrNull(index) ?: "part ${index + 1}"
}

/** The binary container in [bytes], armored or not, and its header. */
internal fun readContainer(io: Io, bytes: ByteArray, name: String): Pair<Header, ByteArray> {
    val binary = if (Ucrypt.isArmored(bytes)) Ucrypt.dearmor(bytes) ?: fail("$name: the text form is damaged") else bytes
    return when (val inspected = Ucrypt.inspect(binary)) {
        is Inspected.Ok -> inspected.header to binary
        is Inspected.NotUcrypt -> fail("$name is not an encrypted container")
        is Inspected.Newer -> fail(
            "$name is ucrypt ${inspected.major}.${inspected.minor}" + (inspected.missing?.let { " and uses $it" } ?: "") +
                "; this unitool reads ${Ucrypt.MAJOR}.${Ucrypt.MINOR}. Update unitool.",
        )
        is Inspected.Damaged -> fail("$name is damaged: ${inspected.what}")
    }.also { io.flush() }
}

/** `inspect`: what a container or a part says about itself. Nothing is opened. */
internal fun inspect(io: Io, file: String?): Int {
    val bytes = when {
        file != null -> io.readFile(file)
        !io.stdinIsTerminal -> io.readStdin()
        else -> io.hidden("A part (the link, or its 64 characters): ").encodeToByteArray()
    }
    val name = file ?: "stdin"
    if (Ucrypt.isUcrypt(bytes) || Ucrypt.isArmored(bytes)) {
        io.write(describe(readContainer(io, bytes, name).first))
        return 0
    }
    when (val reading = SplitLink.read(bytes.decodeToString())) {
        is PartReading.Ok -> reading.part.use { io.write(describe(it)) }
        else -> fail("$name: neither a container nor a part: ${words(reading)}")
    }
    return 0
}

internal fun describe(header: Header): String = buildString {
    appendLine("ucrypt ${header.major}.${header.minor} container")
    appendLine("file id:      ${header.fileId}")
    val split = header.split
    if (split != null) {
        appendLine("opened by:    any ${split.k} of ${split.n} parts of set ${split.setIdHex}")
    } else {
        appendLine("opened by:    SSH keys (record types ${header.recipientTypes.joinToString(", ") { "0x" + it.toString(16).padStart(4, '0') }})")
    }
    appendLine("header:       ${header.headerSize} bytes")
    val chunks = header.chunkCount
    appendLine("content:      ${header.payloadSize} bytes at ${header.payloadOffset}, $chunks chunk${if (chunks == 1L) "" else "s"} of up to ${header.chunkSize}")
    appendLine("decrypts to:  ${header.plaintextSize} bytes")
    appendLine("metadata:     ${if (header.hasMeta) "present, encrypted" else "none"}")
    appendLine("verified:     no; the sizes are checked when the parts open the container")
}

internal fun describe(part: SplitPart): String = buildString {
    appendLine("part ${part.x} of ${part.n}; any ${part.k} open the secret")
    appendLine("set:          ${part.setIdHex}")
    appendLine("check:        ok")
}

internal fun meaning(n: Int, k: Int): String {
    val lose = SplitPlan.meaning(n, k).mayLose
    return if (lose == 0) {
        "All $n parts are needed: losing any one loses the secret for good."
    } else {
        "Any $k of the $n open the secret. ${if (lose == 1) "1 part" else "$lose parts"} may be lost; losing ${lose + 1} loses the secret for good."
    }
}

internal fun words(problem: BaseProblem): String = when (problem) {
    BaseProblem.NotHttp -> "the address starts with https:// or http://"
    BaseProblem.NoHost -> "the address has no host after ://"
    BaseProblem.Fragment -> "the address has a '#' of its own; the part goes there"
    BaseProblem.Spaces -> "the address has spaces in it"
}

internal fun words(reading: PartReading): String = when (reading) {
    is PartReading.Ok -> "a part"
    is PartReading.NotAPart -> "not a part: a part is a link ending in '#' and 64 characters, or those 64 characters alone"
    is PartReading.Damaged -> "not a whole part: a typo, or the link was cut short"
    is PartReading.Unreadable -> "not a part this unitool reads (format ${reading.version}); a newer unitool may"
}

/** [label] names a part by its index in the list the problem is about. */
internal fun words(problem: SplitProblem, label: (Int) -> String): String = when (problem) {
    is SplitProblem.OtherSet -> "${label(problem.index)} is from another set (${problem.setId}); this container needs set ${problem.expected}"
    is SplitProblem.Inconsistent -> "${label(problem.index)} has this container's set but another count of parts: it is damaged"
    is SplitProblem.SameNumber -> "${label(problem.index)} is part ${problem.x} again, like ${label(problem.earlier)}"
    is SplitProblem.TooFew -> "${problem.have} part${if (problem.have == 1) "" else "s"}, and it takes ${problem.need}"
}
