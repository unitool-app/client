package cx.m42.services.files

import cx.m42.superizer.runtime.FilePicker
import cx.m42.superizer.runtime.PickedFile
import kotlinx.coroutines.CompletableDeferred

/**
 * `<input type=file>`, made and clicked for one pick. The file is read in the page and nothing is
 * uploaded anywhere: the bytes cross into the app as hex, the one form that crosses the wasm/JS
 * boundary without a typed-array bridge.
 *
 * A browser without the input's `cancel` event (before Chrome 113, Safari 16.4) never says the
 * person closed the dialog; the next pick answers the previous one with "closed" instead.
 */
public class WebFilePicker : FilePicker {

    private var pending: CompletableDeferred<PickedFile?>? = null

    override suspend fun pick(mimeTypes: List<String>): PickedFile? {
        val result = CompletableDeferred<PickedFile?>()
        pending?.complete(null)
        pending = result
        openPicker(mimeTypes.joinToString(","), MAX_PICKED_BYTES) { name, hex ->
            result.complete(name?.let { PickedFile(it, hex?.let(::fromHex) ?: ByteArray(0)) })
        }
        return try {
            result.await()
        } finally {
            if (pending === result) pending = null
        }
    }
}

private fun fromHex(hex: String): ByteArray = ByteArray(hex.length / 2) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() }

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
private fun openPicker(accept: String, max: Int, done: (String?, String?) -> Unit): Unit = js(
    """{
    const input = document.createElement('input');
    input.type = 'file';
    if (accept) input.accept = accept;
    let answered = false;
    const answer = (name, hex) => { if (!answered) { answered = true; done(name, hex); } };
    input.addEventListener('cancel', () => answer(null, null));
    input.addEventListener('change', () => {
        const file = input.files && input.files[0];
        if (!file) { answer(null, null); return; }
        if (file.size > max) { answer(file.name, ''); return; }
        file.arrayBuffer().then(
            (buffer) => answer(file.name, Array.from(new Uint8Array(buffer), (b) => b.toString(16).padStart(2, '0')).join('')),
            () => answer(file.name, ''),
        );
    });
    input.click();
}""",
)
