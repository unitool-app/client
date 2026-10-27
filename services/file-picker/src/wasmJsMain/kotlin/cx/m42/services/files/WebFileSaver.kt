package cx.m42.services.files

/**
 * A download, made in the page from the bytes: nothing goes to any server. The browser decides
 * where it lands, and says nothing back, so a download that started counts as saved.
 */
public class WebFileSaver : FileSaver {

    override suspend fun save(suggestedName: String, bytes: ByteArray, mimeType: String): Boolean {
        download(suggestedName, mimeType, bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') })
        return true
    }
}

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
private fun download(name: String, type: String, hex: String): Unit = js(
    """{
    const bytes = new Uint8Array(hex.length / 2);
    for (let i = 0; i < bytes.length; i++) bytes[i] = parseInt(hex.substr(2 * i, 2), 16);
    const url = URL.createObjectURL(new Blob([bytes], { type: type }));
    const a = document.createElement('a');
    a.href = url;
    a.download = name;
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
}""",
)
