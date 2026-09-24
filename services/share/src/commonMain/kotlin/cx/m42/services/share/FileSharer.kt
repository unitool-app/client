package cx.m42.services.share

import cx.m42.superizer.runtime.ServiceKey

/**
 * Hands a file to the person, to send wherever they choose — Android's share sheet.
 *
 * Text in, as every service contract is (Services.kt): a file an app wants shared is one it built,
 * and one that has to cross a bridge to a non-Kotlin app one day. A binary format goes through as
 * its text form — the 2FA export is age's ASCII armor for exactly this reason.
 */
public interface FileSharer {

    /**
     * Offers [text] as a file called [fileName]. [message] rides along as the share's text where
     * the target takes one: a messenger's caption, a mail's body.
     *
     * True when the sheet opened. Where the file went, or whether it went anywhere, is the
     * person's business and nobody reports it back.
     */
    public suspend fun share(fileName: String, text: String, mimeType: String, message: String?): Boolean

    public companion object {
        public val Key: ServiceKey<FileSharer> = ServiceKey("file-sharer")
    }
}
