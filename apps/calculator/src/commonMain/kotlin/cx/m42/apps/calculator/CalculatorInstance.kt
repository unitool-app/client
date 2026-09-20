package cx.m42.apps.calculator

import androidx.compose.runtime.Composable
import cx.m42.superizer.app.AppChrome
import cx.m42.superizer.app.AppInstance
import cx.m42.superizer.runtime.InstanceRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One open calculator: the sum being typed, and what happens to it when the app goes away.
 *
 * Two different kinds of persistence live here, and the difference is the whole of D16:
 *
 *  - `saveState`/`restore` carry the *session* — the half-typed sum — across a process death or a
 *    trip through another app. They are a snapshot the host holds, not storage.
 *  - `runtime.storage` carries the last *result*, which should still be there next week. That is
 *    the app's own data, and disabling the app does not touch it (D35).
 */
internal class CalculatorInstance(
    private val runtime: InstanceRuntime,
    private val config: CalculatorConfig,
) : AppInstance() {

    private val _state = MutableStateFlow(CalculatorState())
    val state: StateFlow<CalculatorState> get() = _state.asStateFlow()

    val scientific: Boolean get() = config.isScientific

    private val _chrome = MutableStateFlow(AppChrome())
    override val chrome: StateFlow<AppChrome> get() = _chrome.asStateFlow()

    override suspend fun onLaunch() {
        // The subtitle is how the mode is visible in the host's frame without the app drawing a
        // bar of its own (§14).
        _chrome.value = AppChrome(
            subtitle = if (scientific) {
                runtime.locale.pick(CalculatorStringTables, CalculatorStringsEn).scientific
            } else {
                null
            },
        )
        runtime.analytics.screen("calculator")
    }

    override fun saveState(): JsonObject = buildJsonObject {
        val current = _state.value
        put("entry", JsonPrimitive(current.entry))
        put("ribbon", JsonPrimitive(current.ribbon))
        current.accumulator?.let { put("accumulator", JsonPrimitive(it)) }
        current.pending?.let { put("pending", JsonPrimitive(it.name)) }
        put("fresh", JsonPrimitive(current.fresh))
        put("error", JsonPrimitive(current.error))
    }

    override fun restore(state: JsonObject) {
        // Tolerant by construction: a snapshot may have been written by an older build, and a
        // missing field means "whatever a fresh calculator has".
        _state.value = CalculatorState(
            entry = state["entry"]?.jsonPrimitive?.content ?: "0",
            accumulator = state["accumulator"]?.jsonPrimitive?.content?.toDoubleOrNull(),
            pending = state["pending"]?.jsonPrimitive?.content?.let { name ->
                Op.entries.firstOrNull { it.name == name }
            },
            ribbon = state["ribbon"]?.jsonPrimitive?.content.orEmpty(),
            error = state["error"]?.jsonPrimitive?.content == "true",
            fresh = state["fresh"]?.jsonPrimitive?.content != "false",
        )
    }

    fun press(key: Key) {
        runtime.haptics.tick()
        val before = _state.value
        val after = before.press(key)
        _state.value = after

        // Analytics without noise: one screen view at launch, and the one thing a user would
        // report as a bug.
        if (after.error && !before.error) runtime.analytics.event("error")

        // The last answer outlives the session; the sum being typed does not.
        if (key == Key.Equals && !after.error) {
            runtime.scope.launch { runtime.storage.set(KEY_LAST, after.entry) }
        }
    }

    @Composable
    override fun Content() {
        CalculatorScreen(this)
    }

    companion object {
        const val KEY_LAST = "last"
    }
}
