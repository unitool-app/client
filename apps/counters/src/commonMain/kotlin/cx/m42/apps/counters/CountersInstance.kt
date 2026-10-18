package cx.m42.apps.counters

import androidx.compose.runtime.Composable
import cx.m42.superizer.app.AppChrome
import cx.m42.superizer.app.AppInstance
import cx.m42.superizer.runtime.InstanceRuntime
import cx.m42.superizer.runtime.getJson
import cx.m42.superizer.runtime.setJson
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Where the app is inside itself. The host's frame only ever sees the title this implies. */
internal sealed interface Page {
    data object List : Page
    data object Archive : Page
    data class Open(val id: String) : Page
}

/**
 * The scores, and which one is open.
 *
 * The scores are not a session: they live in `runtime.storage` and are written after every tap, so
 * a game paused for a week is still there. Only the open page is the snapshot (D16).
 */
internal class CountersInstance(private val runtime: InstanceRuntime) : AppInstance() {

    private val strings get() = runtime.locale.pick(CountersStringTables, CountersStringsEn)

    private val _boards = MutableStateFlow<List<Board>>(emptyList())
    val boards: StateFlow<List<Board>> get() = _boards.asStateFlow()

    private val _page = MutableStateFlow<Page>(Page.List)
    val page: StateFlow<Page> get() = _page.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> get() = _loaded.asStateFlow()

    private val _chrome = MutableStateFlow(AppChrome())
    override val chrome: StateFlow<AppChrome> get() = _chrome.asStateFlow()

    /** One write at a time, each of the latest list — so the last tap is always what is stored. */
    private val writing = Mutex()

    override suspend fun onLaunch() {
        _boards.value = runtime.storage.getJson<List<Board>>(KEY_BOARDS).orEmpty()
        _loaded.value = true
        runtime.analytics.screen(CountersApp.ID)
    }

    override fun saveState(): JsonObject = buildJsonObject {
        when (val current = _page.value) {
            Page.List -> Unit
            Page.Archive -> put("page", JsonPrimitive("archive"))
            is Page.Open -> put("open", JsonPrimitive(current.id))
        }
    }

    override fun restore(state: JsonObject) {
        val open = state["open"]?.jsonPrimitive?.content
        go(
            when {
                open != null -> Page.Open(open)
                state["page"]?.jsonPrimitive?.content == "archive" -> Page.Archive
                else -> Page.List
            },
        )
    }

    // ---------------------------------------------------------------- navigation

    fun open(id: String) = go(Page.Open(id))

    fun openArchive() = go(Page.Archive)

    /** Back inside the app. False at the root, which is where the host takes over and closes it. */
    fun back(): Boolean {
        when (val current = _page.value) {
            Page.List -> return false
            Page.Archive -> go(Page.List)
            is Page.Open -> go(if (board(current.id)?.archived == true) Page.Archive else Page.List)
        }
        return true
    }

    private fun go(page: Page) {
        // A score that is gone — deleted, or never read — is no page to stay on.
        _page.value = if (page is Page.Open && _loaded.value && board(page.id) == null) Page.List else page
        updateChrome()
    }

    private fun updateChrome() {
        _chrome.value = when (val current = _page.value) {
            Page.List -> AppChrome()
            Page.Archive -> AppChrome(title = strings.archive, canGoBack = true)
            is Page.Open -> AppChrome(title = board(current.id)?.name, canGoBack = true)
        }
    }

    fun board(id: String): Board? = _boards.value.firstOrNull { it.id == id }

    // ---------------------------------------------------------------- scores

    /** A new score at the top of the list, opened straight away so its names can be typed in. */
    fun createBoard(name: String): Boolean {
        val clean = cleanName(name) ?: return false
        // Strictly after every other: "newest first" must hold even for two made in one millisecond.
        val createdAt = maxOf(runtime.clock.now(), (_boards.value.maxOfOrNull { it.createdAt } ?: 0) + 1)
        val board = Board(id = newId(), name = clean, createdAt = createdAt)
        change { it + board }
        runtime.analytics.event("counters_create")
        open(board.id)
        return true
    }

    fun renameBoard(id: String, name: String) {
        val clean = cleanName(name) ?: return
        change { it.updated(id) { copy(name = clean) } }
    }

    fun setArchived(id: String, archived: Boolean) {
        change { it.updated(id) { copy(archived = archived) } }
    }

    fun deleteBoard(id: String) {
        val wasArchived = board(id)?.archived == true
        change { boards -> boards.filterNot { it.id == id } }
        go(if (wasArchived) Page.Archive else Page.List)
    }

    // ---------------------------------------------------------------- counters

    fun addCounter(boardId: String, name: String): Boolean {
        val clean = cleanName(name) ?: return false
        change { it.updated(boardId) { copy(counters = counters + Counter(id = newId(), name = clean)) } }
        return true
    }

    fun renameCounter(boardId: String, counterId: String, name: String) {
        val clean = cleanName(name) ?: return
        change { it.updated(boardId) { updatedCounter(counterId) { copy(name = clean) } } }
    }

    fun removeCounter(boardId: String, counterId: String) {
        change { it.updated(boardId) { copy(counters = counters.filterNot { c -> c.id == counterId }) } }
    }

    /** Plus or minus one. Below zero is allowed: some games score down. */
    fun step(boardId: String, counterId: String, delta: Long) {
        change { it.updated(boardId) { updatedCounter(counterId) { copy(value = value + delta) } } }
    }

    private fun change(transform: (List<Board>) -> List<Board>) {
        _boards.value = transform(_boards.value)
        updateChrome()
        runtime.scope.launch {
            writing.withLock { runtime.storage.setJson(KEY_BOARDS, _boards.value) }
        }
    }

    private fun newId(): String = Random.nextLong().toULong().toString(36)

    @Composable
    override fun Content() {
        CountersScreen(this)
    }

    companion object {
        const val KEY_BOARDS = "boards"
    }
}
