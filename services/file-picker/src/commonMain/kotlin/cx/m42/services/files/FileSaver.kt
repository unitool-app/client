package cx.m42.services.files

import cx.m42.superizer.runtime.ServiceKey

/**
 * The platform's own "Save as" (cr-restore D603), the other half of the picker: the person chooses
 * where, the app hands over the bytes, and nothing is kept — no path, no permission to come back,
 * no copy in the app's own storage.
 *
 * Bytes rather than text, unlike the share sheet's `FileSharer`: what is saved here can be any file
 * that was encrypted, a photo or a PDF as much as a note. The caller zeroes them afterwards.
 *
 * Optional (D19): a host without one has no "Save" button to show.
 */
public interface FileSaver {

    /**
     * Asks where to put [bytes] as [suggestedName] and writes them there. True when written; false
     * when the person closed the dialog or the write failed — either way nothing is half-left in
     * the app.
     */
    public suspend fun save(suggestedName: String, bytes: ByteArray, mimeType: String = "application/octet-stream"): Boolean

    public companion object {
        public val Key: ServiceKey<FileSaver> = ServiceKey("file-saver")
    }
}
