package cx.m42.unitool.kit.qr

/** A QR code as terminal output. */
public object QrTerminal {

    /**
     * Two modules per character cell, [margin] light modules around. Every cell is `▀`, its foreground
     * the upper module and its background the lower one, both colours set explicitly. That way the
     * code is black on white whatever the terminal's theme, and the gap some fonts leave between lines
     * is filled by the background instead of striping the dark areas, as `█` would. Each line ends by
     * resetting the colours.
     */
    public fun ansi(code: QrCode, margin: Int = 2): String = buildString {
        require(margin >= 0) { "a margin is 0 or more modules, not $margin" }
        val range = -margin until code.size + margin
        for (y in range step 2) {
            var colours = ""
            for (x in range) {
                val next = "${if (code.isDark(x, y)) FG_DARK else FG_LIGHT};${if (code.isDark(x, y + 1)) BG_DARK else BG_LIGHT}"
                if (next != colours) {
                    append("\u001b[").append(next).append('m')
                    colours = next
                }
                append('▀')
            }
            append("\u001b[0m\n")
        }
    }

    private const val FG_DARK = "30"
    private const val FG_LIGHT = "97"
    private const val BG_DARK = "40"
    private const val BG_LIGHT = "107"
}
