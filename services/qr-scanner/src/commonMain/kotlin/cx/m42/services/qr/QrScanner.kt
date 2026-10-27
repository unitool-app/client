package cx.m42.services.qr

import cx.m42.superizer.runtime.ServiceKey

/**
 * A camera that reads one QR code and hands back its text.
 *
 * Strings out and nothing else, by the rule `Services.kt` sets for every service: no preview
 * composable, no bitmap, no platform type. The scanner draws its own screen, full-screen and on
 * top, which is also what every authenticator does — and it keeps the camera, the permission
 * prompt and the refusal of that prompt entirely on the host's side.
 *
 * Optional (D19): a host with a camera registers one under [Key], a host without leaves it out,
 * and an app that reads `runtime.service(QrScanner.Key)` shows its "scan" button only when it is
 * there. Nobody should put this in `manifest.requires` — every app that reads a QR code can also
 * take the same text pasted.
 */
public interface QrScanner {

    /**
     * Opens the camera and suspends until a QR code is read — or until the person closes the
     * scanner, refuses the camera, or the camera will not start, all of which answer null. The
     * caller learns nothing about which: each means "no text", and what to say about the camera
     * was the scanner's to say while it was on screen.
     */
    public suspend fun scan(): String?

    /**
     * Keeps the camera open for a series of codes (qr-scanner D633): a file cut into 48 codes is not
     * 48 taps on "Scan". [onCode] gets every code read, on the main thread — the same text again
     * right after itself is not handed over twice — and answers what to show over the camera next,
     * or [SeriesStep.Done] to close it. [hint] is the line shown before the first code.
     *
     * True when [onCode] ended the series, false when the person closed the scanner or the camera
     * would not start. This default opens the one-code scanner again after every code; a host
     * whose camera can stay open overrides it.
     */
    public suspend fun scanSeries(hint: String? = null, onCode: (String) -> SeriesStep): Boolean {
        while (true) {
            val text = scan() ?: return false
            if (onCode(text) == SeriesStep.Done) return true
        }
    }

    public companion object {
        public val Key: ServiceKey<QrScanner> = ServiceKey("qr-scanner")
    }
}

/** What [QrScanner.scanSeries] does after a code: keep reading with [label] over the camera, or close. */
public sealed interface SeriesStep {
    public class Continue(public val label: String) : SeriesStep
    public data object Done : SeriesStep
}
