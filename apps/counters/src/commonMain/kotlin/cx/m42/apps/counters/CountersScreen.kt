package cx.m42.apps.counters

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import cx.m42.superizer.runtime.LocalAppRuntime
import cx.m42.superizer.theme.AppTheme
import cx.m42.superizer.ui.components.AppButton
import cx.m42.superizer.ui.components.AppTextField
import cx.m42.superizer.ui.components.ButtonSize
import cx.m42.superizer.ui.components.ButtonVariant
import cx.m42.superizer.ui.components.RowDivider
import cx.m42.superizer.ui.platform.SystemBackHandler

private const val TAG = CountersApp.ID

/** Names are words people say aloud: each starts with a capital, and the keyboard may suggest. */
private val NameKeyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)

/**
 * The list of scores, the archive, and one open score — three pages switched on
 * [CountersInstance.page]. The open score is the page that is used during a game, so it carries
 * nothing but names and plus and minus; renaming and the rest wait behind "Edit".
 */
@Composable
internal fun CountersScreen(instance: CountersInstance) {
    val runtime = LocalAppRuntime.current
    val langTag by runtime.locale.langTag.collectAsState()
    val strings = remember(langTag) { runtime.locale.pick(CountersStringTables, CountersStringsEn) }
    val page by instance.page.collectAsState()
    val loaded by instance.loaded.collectAsState()

    // Composed inside the app, so it claims back before the host does — and only while there is
    // somewhere inside the app to go back to.
    SystemBackHandler(enabled = page != Page.List) { instance.back() }

    Box(modifier = Modifier.fillMaxSize().background(AppTheme.pageBackground)) {
        if (!loaded) return@Box
        when (val current = page) {
            Page.List -> ListPage(instance, strings)
            Page.Archive -> ArchivePage(instance, strings)
            is Page.Open -> BoardPage(instance, strings, current.id)
        }
    }
}

// -------------------------------------------------------------------- list

@Composable
private fun ListPage(instance: CountersInstance, strings: CountersStrings) {
    val tokens = AppTheme
    val boards by instance.boards.collectAsState()
    var name by remember { mutableStateOf("") }
    val active = boards.active()
    val archivedCount = boards.count { it.archived }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
        Card {
            Text(text = strings.newBoard, style = tokens.label, color = tokens.muted, modifier = Modifier.padding(bottom = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    AppTextField(
                        value = name,
                        onValueChange = { name = it },
                        placeholder = strings.newBoardHint,
                        label = strings.newBoard,
                        keyboardOptions = NameKeyboard,
                        modifier = Modifier.testTag("$TAG:new-name"),
                    )
                }
                AppButton(
                    text = strings.create,
                    onClick = { if (instance.createBoard(name)) name = "" },
                    enabled = cleanName(name) != null,
                    size = ButtonSize.Sm,
                    modifier = Modifier.testTag("$TAG:create"),
                )
            }
        }

        if (active.isEmpty()) {
            Hint(strings.noBoards, "$TAG:empty")
        } else {
            BoardList(active, onOpen = instance::open)
        }

        if (archivedCount > 0) {
            AppButton(
                text = strings.archiveCount(archivedCount),
                onClick = instance::openArchive,
                variant = ButtonVariant.Ghost,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("$TAG:archive"),
            )
        }
    }
}

@Composable
private fun ArchivePage(instance: CountersInstance, strings: CountersStrings) {
    val boards by instance.boards.collectAsState()
    val archived = boards.archived()

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
        if (archived.isEmpty()) {
            Hint(strings.archiveEmpty, "$TAG:archive-empty")
        } else {
            BoardList(archived, onOpen = instance::open)
        }
        BackButton(instance, strings)
    }
}

/** A score per row: its name, and under it who has how much — enough to find last week's game. */
@Composable
private fun BoardList(boards: List<Board>, onOpen: (String) -> Unit) {
    val tokens = AppTheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(tokens.radius))
            .background(tokens.background),
    ) {
        boards.forEachIndexed { index, board ->
            if (index > 0) RowDivider()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { onOpen(board.id) }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .testTag("$TAG:board:$index"),
            ) {
                Text(text = board.name, style = tokens.body, color = tokens.foreground)
                if (board.counters.isNotEmpty()) {
                    Text(
                        text = board.counters.joinToString(" · ") { "${it.name} ${it.value}" },
                        style = tokens.footnote,
                        color = tokens.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------------- one score

@Composable
private fun BoardPage(instance: CountersInstance, strings: CountersStrings, id: String) {
    val tokens = AppTheme
    val boards by instance.boards.collectAsState()
    val board = boards.firstOrNull { it.id == id } ?: return
    var editing by remember(id) { mutableStateOf(false) }
    var newName by remember(id) { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
        if (editing) {
            Card {
                Text(text = strings.boardName, style = tokens.label, color = tokens.muted, modifier = Modifier.padding(bottom = 4.dp))
                NameField(board.id, board.name, strings.boardName, "$TAG:board-name") { instance.renameBoard(board.id, it) }
            }
        }

        if (board.counters.isEmpty()) {
            Hint(strings.noCounters, "$TAG:no-counters")
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(tokens.radius))
                    .background(tokens.background),
            ) {
                board.counters.forEachIndexed { index, counter ->
                    if (index > 0) RowDivider()
                    if (editing) {
                        EditRow(instance, strings, board, counter, index)
                    } else {
                        CounterRow(instance, strings, board, counter, index)
                    }
                }
            }
        }

        Card {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    AppTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        placeholder = strings.counterName,
                        keyboardOptions = NameKeyboard,
                        modifier = Modifier.testTag("$TAG:counter-name"),
                    )
                }
                AppButton(
                    text = strings.add,
                    onClick = { if (instance.addCounter(board.id, newName)) newName = "" },
                    enabled = cleanName(newName) != null,
                    size = ButtonSize.Sm,
                    modifier = Modifier.testTag("$TAG:add"),
                )
            }
        }

        AppButton(
            text = if (editing) strings.done else strings.edit,
            onClick = { editing = !editing },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Default,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("$TAG:edit"),
        )

        if (editing) {
            Spacer(Modifier.height(8.dp))
            AppButton(
                text = if (board.archived) strings.fromArchive else strings.toArchive,
                onClick = { instance.setArchived(board.id, !board.archived) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Default,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("$TAG:archive-toggle"),
            )
            Spacer(Modifier.height(8.dp))
            DeleteBoard(instance, strings, board.id)
        }

        Spacer(Modifier.height(8.dp))
        BackButton(instance, strings)
    }
}

/** What is used during a game: the name, then minus, the number, plus — big enough for a thumb. */
@Composable
private fun CounterRow(instance: CountersInstance, strings: CountersStrings, board: Board, counter: Counter, index: Int) {
    val tokens = AppTheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = counter.name,
            style = tokens.body,
            color = tokens.foreground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        StepButton("−", strings.minus, "$TAG:counter:$index:minus") { instance.step(board.id, counter.id, -1) }
        Text(
            text = counter.value.toString(),
            style = tokens.title,
            color = tokens.foreground,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 56.dp).padding(horizontal = 8.dp).testTag("$TAG:counter:$index:value"),
        )
        StepButton("+", strings.plus, "$TAG:counter:$index:plus") { instance.step(board.id, counter.id, 1) }
    }
}

@Composable
private fun EditRow(instance: CountersInstance, strings: CountersStrings, board: Board, counter: Counter, index: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            NameField(counter.id, counter.name, strings.counterName, "$TAG:counter:$index:name") {
                instance.renameCounter(board.id, counter.id, it)
            }
        }
        AppButton(
            text = strings.removeCounter,
            onClick = { instance.removeCounter(board.id, counter.id) },
            variant = ButtonVariant.Destructive,
            size = ButtonSize.Sm,
            modifier = Modifier.testTag("$TAG:counter:$index:remove"),
        )
    }
}

/**
 * A field that renames as it is typed. While it is blank the old name stays stored — an emptied
 * field is a name being retyped, not a request for a nameless player.
 */
@Composable
private fun NameField(key: String, name: String, label: String, tag: String, onRename: (String) -> Unit) {
    var text by remember(key) { mutableStateOf(name) }
    AppTextField(
        value = text,
        onValueChange = {
            text = it
            onRename(it)
        },
        placeholder = label,
        keyboardOptions = NameKeyboard,
        modifier = Modifier.testTag(tag),
    )
}

@Composable
private fun DeleteBoard(instance: CountersInstance, strings: CountersStrings, id: String) {
    val tokens = AppTheme
    var confirming by remember(id) { mutableStateOf(false) }
    if (!confirming) {
        AppButton(
            text = strings.delete,
            onClick = { confirming = true },
            variant = ButtonVariant.Destructive,
            size = ButtonSize.Default,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("$TAG:delete"),
        )
        return
    }
    Card {
        Text(text = strings.deleteConfirm, style = tokens.body, color = tokens.foreground)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton(
                text = strings.deleteForever,
                onClick = { instance.deleteBoard(id) },
                variant = ButtonVariant.Destructive,
                size = ButtonSize.Default,
                modifier = Modifier.testTag("$TAG:delete-confirm"),
            )
            AppButton(
                text = strings.cancel,
                onClick = { confirming = false },
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Default,
                modifier = Modifier.testTag("$TAG:delete-cancel"),
            )
        }
    }
}

// -------------------------------------------------------------------- pieces

/** A round 52.dp button with a sign on it — larger than the 44.dp minimum, because it is tapped all evening. */
@Composable
private fun StepButton(sign: String, label: String, tag: String, onClick: () -> Unit) {
    val tokens = AppTheme
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .border(1.dp, tokens.border, CircleShape)
            .background(tokens.surface)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = sign, style = tokens.title, color = tokens.foreground)
    }
}

/** Back inside the app, for platforms whose back is the host's top bar alone. */
@Composable
private fun BackButton(instance: CountersInstance, strings: CountersStrings) {
    AppButton(
        text = strings.back,
        onClick = { instance.back() },
        variant = ButtonVariant.Ghost,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("$TAG:back"),
    )
}

@Composable
private fun Hint(text: String, tag: String) {
    val tokens = AppTheme
    Text(
        text = text,
        style = tokens.body,
        color = tokens.muted,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(20.dp).testTag(tag),
    )
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    val tokens = AppTheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(tokens.radius))
            .background(tokens.background)
            .padding(16.dp),
    ) {
        content()
    }
}
