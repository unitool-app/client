package cx.m42.unitool.platform

/**
 * The browser's own preference (`navigator.language`) — what the user set in the browser, which is
 * not necessarily the OS language. Guarded like every other JS reach: a context without a
 * `navigator` answers an empty string rather than throwing on the first read.
 */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
actual fun deviceLanguageTag(): String = runCatching { jsNavigatorLanguage() }.getOrDefault("")

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
private fun jsNavigatorLanguage(): String = js("(navigator && navigator.language) || ''")
