package cx.m42.services.share

/**
 * The browser's download: the text as a file of [fileName], saved where the browser saves. Nothing
 * leaves the page but to the person's own disk.
 */
public class WebFileSharer : FileSharer {
    override suspend fun share(fileName: String, text: String, mimeType: String, message: String?): Boolean {
        download(fileName, text, mimeType)
        return true
    }
}

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
private fun download(name: String, text: String, type: String): Unit = js(
    """{
    const url = URL.createObjectURL(new Blob([text], { type: type }));
    const a = document.createElement('a');
    a.href = url;
    a.download = name;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
}""",
)
