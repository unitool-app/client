package cx.m42.apps.converter

import androidx.compose.runtime.Composable
import cx.m42.superizer.app.AppInstance
import cx.m42.superizer.runtime.HostLifecycle
import cx.m42.superizer.runtime.InstanceRuntime
import cx.m42.superizer.runtime.getJson
import cx.m42.superizer.runtime.setJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The person's set of pairs, in the order they are shown (D585). */
internal const val KEY_PAIRS = "pairs"

/** Where the one pair of versions before 1.1 lived; read once, moved into [KEY_PAIRS], removed (D586). */
internal const val KEY_BASE = "base"
internal const val KEY_TO = "to"

/** A new row converts one unit, so it shows the rate itself. */
internal const val DEFAULT_AMOUNT = "1"

/** What is stored of a pair: the two currencies, never the amount (D590). */
@Serializable
internal data class CurrencyPair(val from: String, val to: String)

/** One row on the screen. [id] lives only as long as the session — it is what the screen keys on. */
internal data class PairRow(
    val id: Int,
    val from: String,
    val to: String,
    val amount: String = DEFAULT_AMOUNT,
)

/** What the screen is showing: rates, or the reason there are none. */
internal sealed interface RatesState {
    data object Loading : RatesState
    data class Ready(val rates: Rates, val fromCache: Boolean) : RatesState
    data object Failed : RatesState
}

/**
 * One open converter: a list of pairs, each with its own amount.
 *
 * Every pair is answered from the same table — one rate per currency against [ConverterConfig.base]
 * — so three pairs or thirty cost the same single request a day.
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

    private val _rows = MutableStateFlow<List<PairRow>>(emptyList())
    val rows: StateFlow<List<PairRow>> get() = _rows.asStateFlow()

    private val _rates = MutableStateFlow<RatesState>(RatesState.Loading)
    val rates: StateFlow<RatesState> get() = _rates.asStateFlow()

    private var refreshing: kotlinx.coroutines.Job? = null
    private var nextId = 0

    /** One write at a time, each of the latest list — so the last tap is always what is stored. */
    private val writing = Mutex()

    override suspend fun onLaunch() {
        runtime.analytics.screen("currency-converter")

        val stored = runtime.storage.getJson<List<CurrencyPair>>(KEY_PAIRS)
        val pairs = stored ?: migrated()
        _rows.value = pairs.map { PairRow(nextId++, it.from, it.to) }
        if (stored == null) {
            runtime.storage.setJson(KEY_PAIRS, pairs)
            runtime.storage.remove(KEY_BASE)
            runtime.storage.remove(KEY_TO)
        }

        // Only the cache, and only if it is already there: reading it is cheap, and it means the
        // first frame has numbers on it rather than a spinner.
        repository.cached(config.base)?.let { _rates.value = RatesState.Ready(it, fromCache = true) }
    }

    /** The one pair an older version remembered, or the config's default when there was none. */
    private suspend fun migrated(): List<CurrencyPair> {
        val from = runtime.storage.get(KEY_BASE) ?: config.base
        val to = runtime.storage.get(KEY_TO)
            ?: config.favorites.firstOrNull { it != from }
            ?: if (from == "EUR") "USD" else "EUR"
        return listOf(CurrencyPair(from, to))
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

    fun result(row: PairRow): Double? {
        val ready = _rates.value as? RatesState.Ready ?: return null
        val value = row.amount.replace(',', '.').toDoubleOrNull() ?: return null
        return ready.rates.convert(value, row.from, row.to)
    }

    fun currencies(): List<String> {
        val ready = _rates.value as? RatesState.Ready
        // Favourites first, because they are what the user asked to see; everything the endpoint
        // knows about after them.
        return (listOf(config.base) + config.favorites + ready?.rates?.currencies.orEmpty()).distinct()
    }

    fun setAmount(id: Int, value: String) {
        // Digits and separators only: the field is a number, and filtering here means nothing
        // downstream has to cope with letters. Not stored — the amount is the session's (D590).
        val cleaned = value.filter { it.isDigit() || it == '.' || it == ',' }
        _rows.update { rows -> rows.map { if (it.id == id) it.copy(amount = cleaned) else it } }
    }

    fun pickFrom(id: Int, code: String) = change(id) { it.copy(from = code) }

    fun pickTo(id: Int, code: String) = change(id) { it.copy(to = code) }

    fun swap(id: Int) {
        change(id) { it.copy(from = it.to, to = it.from) }
        runtime.haptics.tick()
    }

    /** A new pair goes to the end; the same pair twice would be one row saying the same thing. */
    fun add(from: String, to: String) {
        if (_rows.value.any { it.from == from && it.to == to }) return
        _rows.update { it + PairRow(nextId++, from, to) }
        persist()
    }

    fun remove(id: Int) {
        _rows.update { rows -> rows.filterNot { it.id == id } }
        persist()
    }

    private fun change(id: Int, edit: (PairRow) -> PairRow) {
        _rows.update { rows -> rows.map { if (it.id == id) edit(it) else it } }
        persist()
    }

    private fun persist() {
        runtime.scope.launch {
            writing.withLock {
                runtime.storage.setJson(KEY_PAIRS, _rows.value.map { CurrencyPair(it.from, it.to) })
            }
        }
    }

    /** The amounts, row by row; the pairs themselves are already in storage. */
    override fun saveState(): JsonObject = buildJsonObject {
        put(
            "rows",
            buildJsonArray {
                _rows.value.forEach { row ->
                    add(
                        buildJsonObject {
                            put("from", JsonPrimitive(row.from))
                            put("to", JsonPrimitive(row.to))
                            put("amount", JsonPrimitive(row.amount))
                        },
                    )
                }
            },
        )
    }

    /** An amount comes back only onto the row it was typed in — same place, same pair. */
    override fun restore(state: JsonObject) {
        val saved = (state["rows"] as? JsonArray) ?: return
        _rows.update { rows ->
            rows.mapIndexed { index, row ->
                val old = saved.getOrNull(index)?.jsonObject ?: return@mapIndexed row
                val same = old["from"]?.jsonPrimitive?.content == row.from &&
                    old["to"]?.jsonPrimitive?.content == row.to
                val amount = old["amount"]?.jsonPrimitive?.content
                if (same && amount != null) row.copy(amount = amount) else row
            }
        }
    }

    override fun dispose() {
        refreshing?.cancel()
    }

    @Composable
    override fun Content() {
        ConverterScreen(this)
    }
}
