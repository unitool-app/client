package cx.m42.apps.counters

/** The app's own words (D11). */
internal interface CountersStrings {
    val newBoard: String
    val newBoardHint: String
    val create: String
    val noBoards: String
    val archive: String
    val archiveEmpty: String
    val noCounters: String
    val counterName: String
    val add: String
    val edit: String
    val done: String
    val boardName: String
    val removeCounter: String
    val toArchive: String
    val fromArchive: String
    val delete: String
    val deleteConfirm: String
    val deleteForever: String
    val cancel: String
    val back: String
    val plus: String
    val minus: String

    fun archiveCount(n: Int): String
}

internal object CountersStringsEn : CountersStrings {
    override val newBoard = "New score"
    override val newBoardHint = "Name, e.g. Munchkin, Friday"
    override val create = "Create"
    override val noBoards = "No scores yet. Name one above — then add a counter for each player."
    override val archive = "Archive"
    override val archiveEmpty = "The archive is empty"
    override val noCounters = "No counters yet. Add one for each player."
    override val counterName = "Name"
    override val add = "Add"
    override val edit = "Edit"
    override val done = "Done"
    override val boardName = "Score name"
    override val removeCounter = "Remove"
    override val toArchive = "Move to archive"
    override val fromArchive = "Back from the archive"
    override val delete = "Delete score"
    override val deleteConfirm = "Delete this score with all its counters? This cannot be undone."
    override val deleteForever = "Delete"
    override val cancel = "Cancel"
    override val back = "Back"
    override val plus = "Plus one"
    override val minus = "Minus one"

    override fun archiveCount(n: Int) = "Archive · $n"
}

internal object CountersStringsRu : CountersStrings {
    override val newBoard = "Новый счёт"
    override val newBoardHint = "Название, например «Манчкин, пятница»"
    override val create = "Создать"
    override val noBoards = "Счетов пока нет. Назовите первый выше — потом добавьте по счётчику на каждого игрока."
    override val archive = "Архив"
    override val archiveEmpty = "В архиве пусто"
    override val noCounters = "Счётчиков пока нет. Добавьте по одному на каждого игрока."
    override val counterName = "Имя"
    override val add = "Добавить"
    override val edit = "Изменить"
    override val done = "Готово"
    override val boardName = "Название счёта"
    override val removeCounter = "Убрать"
    override val toArchive = "В архив"
    override val fromArchive = "Вернуть из архива"
    override val delete = "Удалить счёт"
    override val deleteConfirm = "Удалить этот счёт со всеми счётчиками? Вернуть будет нельзя."
    override val deleteForever = "Удалить"
    override val cancel = "Отмена"
    override val back = "Назад"
    override val plus = "Плюс один"
    override val minus = "Минус один"

    override fun archiveCount(n: Int) = "Архив · $n"
}

internal val CountersStringTables: Map<String, CountersStrings> = mapOf(
    "ru" to CountersStringsRu,
    "en" to CountersStringsEn,
)
