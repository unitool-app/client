package cx.m42.unitool.kit.qr

/** A QR code as a picture. [margin] is the quiet zone in modules, [scale] the pixels per module. */
public object QrImage {

    /** A 1-bit grayscale PNG, black on white. */
    public fun png(code: QrCode, margin: Int = 4, scale: Int = 8): ByteArray {
        requireLayout(margin, scale)
        val side = (code.size + 2 * margin) * scale
        return Png.encode(side, side) { x, y -> code.isDark(x / scale - margin, y / scale - margin) }
    }

    /**
     * An SVG of one path of horizontal runs on a white square. `crispEdges` keeps a module a module
     * when the picture is scaled; [scale] only sets the size it opens at.
     */
    public fun svg(code: QrCode, margin: Int = 4, scale: Int = 8): String = buildString {
        requireLayout(margin, scale)
        val side = code.size + 2 * margin
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"${side * scale}\" height=\"${side * scale}\" ")
        append("viewBox=\"0 0 $side $side\" shape-rendering=\"crispEdges\">\n")
        append("<rect width=\"$side\" height=\"$side\" fill=\"#ffffff\"/>\n")
        append("<path fill=\"#000000\" d=\"")
        for (y in 0 until code.size) {
            var x = 0
            while (x < code.size) {
                if (!code.isDark(x, y)) {
                    x++
                    continue
                }
                val start = x
                while (x < code.size && code.isDark(x, y)) x++
                append("M${start + margin} ${y + margin}h${x - start}v1h-${x - start}z")
            }
        }
        append("\"/>\n</svg>\n")
    }

    private fun requireLayout(margin: Int, scale: Int) {
        require(margin >= 0) { "a margin is 0 or more modules, not $margin" }
        require(scale >= 1) { "a scale is 1 or more pixels per module, not $scale" }
    }
}
