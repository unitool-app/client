package cx.m42.apps.crnote

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import cx.m42.superizer.runtime.LocalAppRuntime
import cx.m42.superizer.theme.AppTheme
import cx.m42.superizer.ui.components.AppButton
import cx.m42.superizer.ui.components.AppTextField
import cx.m42.superizer.ui.components.ButtonSize
import cx.m42.superizer.ui.components.ButtonVariant
import cx.m42.superizer.ui.components.ChoiceRow
import cx.m42.superizer.ui.components.RowDivider
import cx.m42.superizer.ui.components.ToggleRow
import cx.m42.superizer.ui.platform.SystemBackHandler

private const val TAG = CrNoteApp.ID

/** Never learnt, never suggested over (06 §7). */
private val SecretKeyboard = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password)
private val PlainKeyboard = KeyboardOptions(autoCorrectEnabled = false)

@Composable
internal fun CrNoteScreen(instance: CrNoteInstance) {
    val runtime = LocalAppRuntime.current
    val langTag by runtime.locale.langTag.collectAsState()
    val strings = remember(langTag) { runtime.locale.pick(CrNoteStringTables, CrNoteStringsEn) }
    val page by instance.page.collectAsState()

    SystemBackHandler(enabled = page is Page.Editor || page == Page.Export || page == Page.Import) { instance.back() }

    NoCopy {
        Box(modifier = Modifier.fillMaxSize().background(AppTheme.pageBackground)) {
            when (val current = page) {
                Page.Starting -> Box(Modifier.fillMaxSize().testTag("$TAG:starting"))
                Page.Changed -> ChangedPage(instance, strings)
                Page.NewPin, Page.Pin -> PinPage(instance, strings, current == Page.NewPin)
                Page.Notes -> NotesPage(instance, strings)
                is Page.Editor -> EditorPage(instance, strings)
                Page.Export -> ExportPage(instance, strings)
                Page.Import -> ImportPage(instance, strings)
            }
        }
    }
}

// -------------------------------------------------------------------- the faces changed (D656)

@Composable
private fun ChangedPage(instance: CrNoteInstance, strings: CrNoteStrings) {
    Column(Modifier.fillMaxSize().padding(24.dp).testTag("$TAG:changed"), verticalArrangement = Arrangement.Center) {
        Text(strings.changedTitle, style = AppTheme.header, color = AppTheme.foreground)
        Text(strings.changedBody, style = AppTheme.body, color = AppTheme.muted, modifier = Modifier.padding(top = 8.dp, bottom = 24.dp))
        AppButton(strings.startAgain, onClick = instance::startAgain, modifier = Modifier.fillMaxWidth().testTag("$TAG:start-again"))
    }
}

// -------------------------------------------------------------------- the PIN (D652, D653)

@Composable
private fun PinPage(instance: CrNoteInstance, strings: CrNoteStrings, creating: Boolean) {
    val pin by instance.pin.collectAsState()
    val repeating by instance.repeating.collectAsState()
    val problem by instance.pinProblem.collectAsState()
    Column(
        Modifier.fillMaxSize().padding(24.dp).testTag("$TAG:pin-page"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val title = when {
            !creating -> strings.enterPin
            repeating -> strings.repeatPin
            else -> strings.choosePin
        }
        Text(title, style = AppTheme.header, color = AppTheme.foreground, modifier = Modifier.testTag("$TAG:pin-title"))
        Row(Modifier.padding(vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(NoteVault.PIN_LENGTH) { index ->
                val filled = index < pin.length
                Box(
                    Modifier.size(14.dp).clip(CircleShape)
                        .background(if (filled) AppTheme.foreground else AppTheme.background)
                        .border(1.dp, AppTheme.foreground, CircleShape),
                )
            }
        }
        Text(
            when (problem) {
                PinProblem.Mismatch -> strings.pinMismatch
                PinProblem.Forbidden -> strings.pinForbidden
                null -> ""
            },
            style = AppTheme.body,
            color = AppTheme.danger,
            modifier = Modifier.heightIn(min = 24.dp).testTag("$TAG:pin-problem"),
        )
        Spacer(Modifier.height(12.dp))
        for (row in listOf("123", "456", "789")) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                row.forEach { d -> Key(d.toString(), "$TAG:digit-$d") { instance.digit(d) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            Box(Modifier.size(72.dp))
            Key("0", "$TAG:digit-0") { instance.digit('0') }
            Key("⌫", "$TAG:erase", description = strings.erase) { instance.erase() }
        }
    }
}

@Composable
private fun Key(label: String, tag: String, description: String? = null, onClick: () -> Unit) {
    Box(
        Modifier.size(72.dp).clip(CircleShape).background(AppTheme.background)
            .clickable(role = Role.Button, onClick = onClick)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = AppTheme.title, color = AppTheme.foreground)
    }
}

// -------------------------------------------------------------------- the directory (D657, D663)

@Composable
private fun NotesPage(instance: CrNoteInstance, strings: CrNoteStrings) {
    val notes by instance.notes.collectAsState()
    val menuOpen by instance.menuOpen.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AppButton(strings.newNote, onClick = instance::newNote, size = ButtonSize.Sm, modifier = Modifier.testTag("$TAG:new"))
            Spacer(Modifier.weight(1f))
            Box {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClick = instance::openMenu)
                        .semantics { contentDescription = strings.menu }.testTag("$TAG:menu"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("⋮", style = AppTheme.title, color = AppTheme.foreground)
                }
            }
        }
        if (menuOpen) {
            Card(Modifier.testTag("$TAG:menu-items")) {
                Text(strings.export, style = AppTheme.body, color = AppTheme.foreground,
                    modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = instance::startExport).padding(vertical = 10.dp).testTag("$TAG:export"))
                RowDivider()
                Text(strings.import, style = AppTheme.body, color = AppTheme.foreground,
                    modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = instance::startImport).padding(vertical = 10.dp).testTag("$TAG:import"))
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if (notes.isEmpty()) {
                Text(strings.empty, style = AppTheme.body, color = AppTheme.muted, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(32.dp).testTag("$TAG:empty"))
            }
            notes.forEach { note ->
                Card(Modifier.clickable(role = Role.Button) { instance.openNote(note.id) }.testTag("$TAG:note")) {
                    Text(note.title.ifEmpty { strings.untitled }, style = AppTheme.subtitle, color = AppTheme.foreground, maxLines = 1)
                    Text(Transfer.shortDate(note.modified), style = AppTheme.footnote, color = AppTheme.muted, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

// -------------------------------------------------------------------- a note (D657, D660)

@Composable
private fun EditorPage(instance: CrNoteInstance, strings: CrNoteStrings) {
    val text by instance.editorText.collectAsState()
    val confirm by instance.confirmDelete.collectAsState()
    val tokens = AppTheme
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppButton(strings.back, onClick = { instance.back() }, variant = ButtonVariant.Ghost, size = ButtonSize.Sm, modifier = Modifier.testTag("$TAG:back"))
            Spacer(Modifier.weight(1f))
            if (instance.canScan) {
                AppButton(strings.scanQr, onClick = instance::scanIntoNote, variant = ButtonVariant.Outline, size = ButtonSize.Sm,
                    modifier = Modifier.padding(end = 8.dp).testTag("$TAG:scan-qr"))
            }
            AppButton(strings.delete, onClick = instance::askDelete, variant = ButtonVariant.Destructive, size = ButtonSize.Sm, modifier = Modifier.testTag("$TAG:delete"))
        }
        if (confirm) {
            Card(Modifier.testTag("$TAG:delete-question")) {
                Text(strings.deleteQuestion, style = AppTheme.body, color = AppTheme.foreground)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppButton(strings.delete, onClick = instance::delete, variant = ButtonVariant.Destructive, size = ButtonSize.Sm, modifier = Modifier.testTag("$TAG:delete-yes"))
                    AppButton(strings.cancel, onClick = instance::cancelDelete, variant = ButtonVariant.Ghost, size = ButtonSize.Sm)
                }
            }
        }
        Box(
            Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp).clip(RoundedCornerShape(tokens.radius)).background(tokens.background).padding(12.dp),
        ) {
            if (text.isEmpty()) Text(strings.noteHint, style = tokens.body, color = tokens.muted)
            // The field's own value, so that text arriving from elsewhere — a scanned QR code (D669) —
            // puts the cursor after itself, where the person goes on typing.
            var field by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
            if (field.text != text) field = TextFieldValue(text, TextRange(text.length))
            BasicTextField(
                value = field,
                onValueChange = {
                    field = it
                    if (it.text != text) instance.edit(it.text)
                },
                textStyle = tokens.body.copy(color = tokens.foreground),
                cursorBrush = SolidColor(tokens.foreground),
                keyboardOptions = PlainKeyboard,
                modifier = Modifier.fillMaxSize().noCopy().testTag("$TAG:text"),
            )
        }
    }
}

// -------------------------------------------------------------------- export (D660)

@Composable
private fun ExportPage(instance: CrNoteInstance, strings: CrNoteStrings) {
    val method by instance.exportMethod.collectAsState()
    val ready by instance.exportReady.collectAsState()
    val working by instance.working.collectAsState()
    val problem by instance.exportProblem.collectAsState()
    val done by instance.exportDone.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        Hint(strings.exportAll, "$TAG:export-all")
        val file = ready
        if (file != null) {
            Card(Modifier.testTag("$TAG:export-ready")) {
                Text(strings.ready(file.name), style = AppTheme.subtitle, color = AppTheme.foreground)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (instance.canSave) AppButton(strings.saveFile, onClick = instance::saveExport, size = ButtonSize.Sm, modifier = Modifier.testTag("$TAG:save"))
                    if (instance.canShare) AppButton(strings.share, onClick = instance::shareExport, variant = ButtonVariant.Outline, size = ButtonSize.Sm, modifier = Modifier.testTag("$TAG:share"))
                }
                Text(strings.opensWith, style = AppTheme.label, color = AppTheme.muted, modifier = Modifier.padding(top = 12.dp))
                Text(Transfer.command(file.method, file.name), style = AppTheme.mono, color = AppTheme.foreground, modifier = Modifier.padding(top = 4.dp).testTag("$TAG:command"))
                if (done) Text(strings.handedOver, style = AppTheme.body, color = AppTheme.success, modifier = Modifier.padding(top = 8.dp).testTag("$TAG:handed-over"))
            }
            return@Column
        }
        Card {
            for ((option, label) in listOf(
                ExportMethod.AgePassword to strings.methodAgePassword,
                ExportMethod.AgeKeys to strings.methodAgeKeys,
                ExportMethod.Openssl to strings.methodOpenssl,
            )) {
                Box(Modifier.testTag("$TAG:method-${option.name.lowercase()}")) {
                    ChoiceRow(label, option == method) { instance.exportMethod.value = option }
                }
            }
        }
        if (method == ExportMethod.AgeKeys) RecipientsCard(instance, strings) else PasswordCard(instance, strings)
        problem?.let { Message(strings.exportProblem(it), "$TAG:export-problem") }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (working) {
                Text(strings.working, style = AppTheme.body, color = AppTheme.muted, modifier = Modifier.testTag("$TAG:working"))
            } else {
                AppButton(strings.makeExport, onClick = instance::export, modifier = Modifier.fillMaxWidth().testTag("$TAG:encrypt"))
            }
        }
    }
}

@Composable
private fun PasswordCard(instance: CrNoteInstance, strings: CrNoteStrings) {
    val password by instance.exportPassword.collectAsState()
    val repeat by instance.exportRepeat.collectAsState()
    val shown by instance.exportShowPassword.collectAsState()
    Card {
        SecretField(password, { instance.exportPassword.value = it }, strings.password, shown, "$TAG:password")
        Spacer(Modifier.height(8.dp))
        SecretField(repeat, { instance.exportRepeat.value = it }, strings.repeatPassword, shown, "$TAG:password-repeat")
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton(strings.generate, onClick = instance::generatePassword, variant = ButtonVariant.Outline, size = ButtonSize.Sm, modifier = Modifier.testTag("$TAG:generate"))
            AppButton(if (shown) strings.hidePassword else strings.showPassword, onClick = { instance.exportShowPassword.value = !shown },
                variant = ButtonVariant.Ghost, size = ButtonSize.Sm)
        }
        Text(strings.passwordHint, style = AppTheme.footnote, color = AppTheme.muted, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun RecipientsCard(instance: CrNoteInstance, strings: CrNoteStrings) {
    val recipients by instance.recipients.collectAsState()
    val chosen by instance.chosen.collectAsState()
    val text by instance.recipientText.collectAsState()
    val login by instance.githubLogin.collectAsState()
    val github by instance.githubState.collectAsState()
    Card(Modifier.testTag("$TAG:recipients")) {
        if (recipients.isEmpty()) Text(strings.phoneKeysNone, style = AppTheme.body, color = AppTheme.muted)
        recipients.forEach { r ->
            ToggleRow(r.label, r.id in chosen, { instance.toggle(r.id) }, modifier = Modifier.testTag("$TAG:recipient"))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            Column(Modifier.weight(1f)) {
                AppTextField(text, { instance.recipientText.value = it }, placeholder = strings.recipientPlaceholder, singleLine = false,
                    keyboardOptions = PlainKeyboard, modifier = Modifier.testTag("$TAG:recipient-text"))
            }
            AppButton(strings.add, onClick = instance::addRecipients, enabled = text.isNotBlank(), size = ButtonSize.Sm, modifier = Modifier.testTag("$TAG:recipient-add"))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            Column(Modifier.weight(1f)) {
                AppTextField(login, { instance.githubLogin.value = it }, placeholder = strings.githubPlaceholder,
                    keyboardOptions = PlainKeyboard, modifier = Modifier.testTag("$TAG:github"))
            }
            AppButton(strings.githubFetch, onClick = instance::fetchGitHub, enabled = login.isNotBlank() && github != GitHubState.Loading,
                variant = ButtonVariant.Outline, size = ButtonSize.Sm, modifier = Modifier.testTag("$TAG:github-fetch"))
        }
        strings.github(github)?.let { Text(it, style = AppTheme.footnote, color = AppTheme.muted, modifier = Modifier.padding(top = 4.dp)) }
    }
}

// -------------------------------------------------------------------- import (D661)

@Composable
private fun ImportPage(instance: CrNoteInstance, strings: CrNoteStrings) {
    val incoming by instance.incoming.collectAsState()
    val password by instance.importPassword.collectAsState()
    val problem by instance.importProblem.collectAsState()
    val found by instance.importFound.collectAsState()
    val added by instance.importAdded.collectAsState()
    val working by instance.working.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        Hint(strings.importHint, "$TAG:import-hint")
        Card {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (instance.canPick) AppButton(strings.importFile, onClick = instance::importFile, size = ButtonSize.Sm, modifier = Modifier.testTag("$TAG:import-file"))
                if (instance.canScan) AppButton(strings.importQr, onClick = instance::importQr, variant = ButtonVariant.Outline, size = ButtonSize.Sm, modifier = Modifier.testTag("$TAG:import-qr"))
            }
        }
        val what = incoming
        val list = found
        when {
            list != null -> Card(Modifier.testTag("$TAG:import-question")) {
                Text(strings.addQuestion(list.size), style = AppTheme.subtitle, color = AppTheme.foreground)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppButton(strings.addConfirm, onClick = instance::confirmImport, size = ButtonSize.Sm, modifier = Modifier.testTag("$TAG:import-yes"))
                    AppButton(strings.cancel, onClick = instance::cancelImport, variant = ButtonVariant.Ghost, size = ButtonSize.Sm)
                }
            }
            what != null && what != Incoming.Refused -> Card(Modifier.testTag("$TAG:import-incoming")) {
                val label = when {
                    what is Incoming.Age && what.password -> strings.importAge
                    what is Incoming.Age -> strings.importAgeKey
                    else -> strings.importOpenssl
                }
                Text(label, style = AppTheme.subtitle, color = AppTheme.foreground)
                if (what is Incoming.Age && !what.password) {
                    if (instance.hasPhoneKeys) {
                        AppButton(strings.openWithPhoneKey, onClick = instance::openImport, enabled = !working, size = ButtonSize.Sm,
                            modifier = Modifier.padding(top = 8.dp).testTag("$TAG:import-open"))
                    }
                } else {
                    Spacer(Modifier.height(8.dp))
                    SecretField(password, { instance.importPassword.value = it }, strings.password, false, "$TAG:import-password")
                    AppButton(if (working) strings.working else strings.open, onClick = instance::openImport, enabled = !working, size = ButtonSize.Sm,
                        modifier = Modifier.padding(top = 8.dp).testTag("$TAG:import-open"))
                }
            }
        }
        problem?.let { Message(strings.importProblem(it), "$TAG:import-problem") }
        added?.let { Hint(strings.added(it), "$TAG:import-added") }
    }
}

// -------------------------------------------------------------------- pieces

@Composable
private fun SecretField(value: String, onChange: (String) -> Unit, placeholder: String, shown: Boolean, tag: String) {
    val tokens = AppTheme
    Box(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(tokens.radius))
            .border(1.dp, tokens.border, RoundedCornerShape(tokens.radius)).padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        if (value.isEmpty()) Text(placeholder, style = tokens.body, color = tokens.muted)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = tokens.body.copy(color = tokens.foreground),
            cursorBrush = SolidColor(tokens.foreground),
            keyboardOptions = SecretKeyboard,
            visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().noCopy().testTag(tag),
        )
    }
}

@Composable
private fun Hint(text: String, tag: String) {
    Text(text, style = AppTheme.body, color = AppTheme.muted, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).testTag(tag))
}

@Composable
private fun Message(text: String, tag: String) {
    Text(text, style = AppTheme.body, color = AppTheme.danger, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).testTag(tag))
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val tokens = AppTheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(tokens.radius))
            .background(tokens.background)
            .then(modifier)
            .padding(16.dp),
    ) {
        content()
    }
}
