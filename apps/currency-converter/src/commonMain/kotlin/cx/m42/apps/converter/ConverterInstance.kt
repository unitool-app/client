package cx.m42.apps.converter

import androidx.compose.runtime.Composable
import cx.m42.superizer.app.AppInstance
import cx.m42.superizer.runtime.HostLifecycle
import cx.m42.superizer.runtime.InstanceRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What the screen is showing: rates, or the reason there are none. */
internal sealed interface RatesState {
    data object Loading : RatesState
    data class Ready(val rates: Rates, val fromCache: Boolean) : RatesState
    data object Failed : RatesState
}

/**
 * One open converter.
 *
 * Two things here are the app's half of decisions the framework made:
 *
 *  - It does not poll in the background (D17). `onBackground` cancels the refresh; a converter that
 *    kept asking for rates while nobody was looking would be a battery complaint.
 *  - It never fetches from `onLaunch` — that is the budget in the contract test (12 §4.7) and the
 *    reason the container can show a frame immediately. The first request happens on the screen's
 *    own scope, after the first frame.
 */
internal class ConverterInstance(
    val runtime: InstanceRuntime,
    val config: ConverterConfig,
) : AppInstance() {

    private val repository = RatesRepository(runtime.network, runtime.storage, runtime.logger, runtime.clock)

    val amount = MutableStateFlow("100")
    val from = MutableStateFlow(config.from ?: config.base)
    val to = MutableStateFlow(config.to ?: config.favorites.firstOrNull() ?: "EUR")

    private val _rates = MutableStateFlow<RatesState>(RatesState.Loading)
    val rates: StateFlow<RatesState> get() = _rates.asStateFlow()

    private var refreshing: kotlinx.coroutines.Job? = null

    override suspend fun onLaunch() {
        runtime.analytics.screen("currency-converter")

        // A currency named by the config wins: that is a deep link or a QR code saying what this
        // *launch* is about. Otherwise the preference from Settings, and only then the default —
        // the config is an instruction, the preference is a standing wish.
        if (config.from == null) {
            runtime.storage.get(KEY_BASE)?.let { stored -> from.value = stored }
        }

        // Only the cache, and only if it is already there: reading it is cheap, and it means the
        // first frame has numbers on it rather than a spinner.
        repository.cached(config.base)?.let { _rates.value = RatesState.Ready(it, fromCache = true) }
    }

    /** Called by the screen once it is composed — the first network request of the app's life. */
    fun refresh(force: Boolean = false) {
        if (runtime.lifecycle.value == HostLifecycle.Background) return
        refreshing?.cancel()
        refreshing = runtime.scope.launch {
            if (_rates.value !is RatesState.Ready) _rates.value = RatesState.Loading
            repository.load(config.ratesUrl, config.base, force)
                .onSuccess { _rates.value = RatesState.Ready(it.rates, it.fromCache) }
                .onFailure { if (_rates.value !is RatesState.Ready) _rates.value = RatesState.Failed }
        }
    }

    override fun onBackground() {
        refreshing?.cancel()
    }

    override fun onForeground() {
        // Cheap: an unchanged day comes back from the cache, and the user is looking at it again.
        refresh()
    }

    val result: Double?
        get() {
            val ready = _rates.value as? RatesState.Ready ?: return null
            val value = amount.value.replace(',', '.').toDoubleOrNull() ?: return null
            return ready.rates.convert(value, from.value, to.value)
        }

    fun currencies(): List<String> {
        val ready = _rates.value as? RatesState.Ready
        // Favourites first, because they are what the user asked to see; everything the endpoint
        // knows about after them.
        return (listOf(config.base) + config.favorites + ready?.rates?.currencies.orEmpty()).distinct()
    }

    fun setAmount(value: String) {
        // Digits and one separator: the field is a number, and filtering here means nothing
        // downstream has to cope with "12.3.4".
        val cleaned = value.filter { it.isDigit() || it == '.' || it == ',' }
        amount.value = cleaned
    }

    fun swap() {
        val previous = from.value
        from.value = to.value
        to.value = previous
        runtime.haptics.tick()
    }

    override fun saveState(): JsonObject = buildJsonObject {
        put("amount", JsonPrimitive(amount.value))
        put("from", JsonPrimitive(from.value))
        put("to", JsonPrimitive(to.value))
    }

    override fun restore(state: JsonObject) {
        state["amount"]?.jsonPrimitive?.content?.let { amount.value = it }
        state["from"]?.jsonPrimitive?.content?.let { from.value = it }
        state["to"]?.jsonPrimitive?.content?.let { to.value = it }
    }

    override fun dispose() {
        refreshing?.cancel()
    }

    @Composable
    override fun Content() {
        ConverterScreen(this)
    }
}
