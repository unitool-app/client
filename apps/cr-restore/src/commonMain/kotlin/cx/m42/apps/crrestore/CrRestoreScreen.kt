package cx.m42.apps.crrestore

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
import cx.m42.unitool.kit.crypto.BlockMode
import cx.m42.unitool.kit.openssl.OpensslEnc
import cx.m42.unitool.kit.ssh.AgeKeys

private const val TAG = CrRestoreApp.ID

/** Never learnt, never suggested over (06 §7). */
private val SecretKeyboard = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password)
private val PlainKeyboard = KeyboardOptions(autoCorrectEnabled = false)

/**
 * Three pages on one screen: the choice (D599), a method with its input and settings (D600–D611),
 * and the result (D602, D619).
 */
@Composable
internal fun CrRestoreScreen(instance: CrRestoreInstance) {
    val runtime = LocalAppRuntime.current
    val langTag by runtime.locale.langTag.collectAsState()
    val strings = remember(langTag) { runtime.locale.pick(CrRestoreStringTables, CrRestoreStringsEn) }
    val method by instance.method.collectAsState()
    val result by instance.result.collectAsState()

    SystemBackHandler(enabled = method != null) { instance.back() }

    Box(modifier = Modifier.fillMaxSize().background(AppTheme.pageBackground)) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
            val output = result
            when {
                output != null -> ResultPage(instance, strings, output)
                method == null -> ChoosePage(instance, strings)
                else -> MethodPage(instance, strings, method!!)
            }
        }
    }
}

// -------------------------------------------------------------------- choose

@Composable
private fun ChoosePage(instance: CrRestoreInstance, strings: CrRestoreStrings) {
    Text(strings.chooseTitle, style = AppTheme.header, color = AppTheme.foreground, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    for ((method, title, hint) in listOf(
        Triple(Method.Age, strings.ageTitle, strings.ageHint),
        Triple(Method.Openssl, strings.opensslTitle, strings.opensslHint),
        Triple(Method.Split, strings.splitTitle, strings.splitHint),
    )) {
        Card(Modifier.clickable(role = Role.Button) { instance.choose(method) }.testTag("$TAG:choose-${method.name.lowercase()}")) {
            Text(title, style = AppTheme.subtitle, color = AppTheme.foreground)
            Text(hint, style = AppTheme.body, color = AppTheme.muted, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

// -------------------------------------------------------------------- a method

@Composable
private fun MethodPage(instance: CrRestoreInstance, strings: CrRestoreStrings, method: Method) {
    val working by instance.working.collectAsState()
    val problem by instance.problem.collectAsState()
    val notice by instance.notice.collectAsState()
    val looksLike by instance.looksLike.collectAsState()

    Text(
        when (method) {
            Method.Age -> strings.ageTitle
            Method.Openssl -> strings.opensslTitle
            Method.Split -> strings.splitTitle
        },
        style = AppTheme.header,
        color = AppTheme.foreground,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )

    InputCard(instance, strings, method)

    looksLike?.let { other ->
        Card {
            Text(strings.looksLike(other.name.lowercase()), style = AppTheme.body, color = AppTheme.foreground)
            AppButton(strings.openThere, onClick = { instance.choose(other) }, variant = ButtonVariant.Outline, size = ButtonSize.Sm,
                modifier = Modifier.padding(top = 8.dp).testTag("$TAG:open-there"))
        }
    }

    when (method) {
        Method.Age -> AgeOptions(instance, strings)
        Method.Openssl -> OpensslOptions(instance, strings)
        Method.Split -> SplitStatus(instance, strings)
    }

    notice?.let { Hint(it, "$TAG:notice") }
    problem?.let { Message(strings.problem(it), "$TAG:problem", AppTheme.danger) }

    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (working) {
            Text(strings.decrypting, style = AppTheme.body, color = AppTheme.muted, modifier = Modifier.weight(1f).testTag("$TAG:working"))
            AppButton(strings.cancel, onClick = instance::cancel, variant = ButtonVariant.Ghost, size = ButtonSize.Sm, modifier = Modifier.testTag("$TAG:cancel"))
        } else if (method != Method.Split) {
            val input by instance.input.collectAsState()
            AppButton(strings.decrypt, onClick = instance::decrypt, enabled = input != null, modifier = Modifier.weight(1f).testTag("$TAG:decrypt"))
        }
    }
    AppButton(strings.back, onClick = { instance.back() }, variant = ButtonVariant.Ghost,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("$TAG:back"))
}

@Composable
private fun InputCard(instance: CrRestoreInstance, strings: CrRestoreStrings, method: Method) {
    val input by instance.input.collectAsState()
    val paste by instance.pasteBox.collectAsState()
    Card {
        if (method != Method.Split) {
            input?.let {
                val line = if (it.codes > 0) strings.codesRead(it.codes, it.bytes.size) else strings.fileRead(it.name ?: "—", it.bytes.size)
                Text(line, style = AppTheme.body, color = AppTheme.foreground, modifier = Modifier.padding(bottom = 8.dp).testTag("$TAG:input"))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (instance.canScan) {
                val more = method != Method.Split && (input?.codes ?: 0) > 0
                AppButton(if (more) strings.scanMore else strings.scan, onClick = instance::scan, size = ButtonSize.Sm, modifier = Modifier.testTag("$TAG:scan"))
            }
            if (instance.canPick) {
                AppButton(strings.openFile, onClick = instance::openFile, variant = ButtonVariant.Outline, size = ButtonSize.Sm, modifier = Modifier.testTag("$TAG:open-file"))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            Column(Modifier.weight(1f)) {
                AppTextField(value = paste, onValueChange = { instance.pasteBox.value = it }, placeholder = strings.pasteHere, singleLine = false,
                    keyboardOptions = PlainKeyboard, modifier = Modifier.testTag("$TAG:paste-box"))
            }
            AppButton(strings.add, onClick = instance::paste, enabled = paste.isNotBlank(), size = ButtonSize.Sm, modifier = Modifier.testTag("$TAG:paste"))
        }
    }
}

// -------------------------------------------------------------------- age

@Composable
private fun AgeOptions(instance: CrRestoreInstance, strings: CrRestoreStrings) {
    val input by instance.input.collectAsState()
    val recipients = remember(input) { instance.ageRecipients() }
    val keyText by instance.ageKeyText.collectAsState()
    val keyLoaded by instance.ageKeyLoaded.collectAsState()
    val keyPassphrase by instance.ageKeyPassphrase.collectAsState()
    val passphrase by instance.agePassphrase.collectAsState()
    if (recipients == null) return

    Card(Modifier.testTag("$TAG:age-for")) {
        Text(strings.ageFor, style = AppTheme.label, color = AppTheme.muted)
        recipients.forEach { r ->
            val line = when (r) {
                is AgeKeys.Recipient.Ssh -> strings.ageRecipientSsh(r.type, r.tag)
                AgeKeys.Recipient.X25519 -> strings.ageRecipientX25519
                is AgeKeys.Recipient.Scrypt -> strings.ageRecipientScrypt(r.logN)
                is AgeKeys.Recipient.Other -> strings.ageRecipientOther(r.type)
            }
            Text("• $line", style = AppTheme.body, color = AppTheme.foreground)
            if (r is AgeKeys.Recipient.Scrypt && r.logN > 18) Text(strings.ageSlow(r.logN), style = AppTheme.footnote, color = AppTheme.warning)
        }
    }

    if (recipients.any { it is AgeKeys.Recipient.Ssh } && instance.hasPhoneKeys) {
        AppButton(strings.agePhoneKey, onClick = instance::decryptWithPhoneKey, variant = ButtonVariant.Outline,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("$TAG:phone-key"))
    }

    if (recipients.any { it is AgeKeys.Recipient.Ssh || it is AgeKeys.Recipient.X25519 }) {
        Card {
            Text(strings.ageKey, style = AppTheme.label, color = AppTheme.muted)
            if (keyLoaded != null) {
                Text("✓ $keyLoaded", style = AppTheme.body, color = AppTheme.foreground, modifier = Modifier.testTag("$TAG:age-key-loaded"))
            } else {
                SecretField(keyText, { instance.ageKeyText.value = it }, strings.ageKeyHint, "$TAG:age-key", strings, singleLine = false)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                if (instance.canScan) AppButton(strings.ageKeyScan, onClick = instance::scanAgeKey, variant = ButtonVariant.Outline, size = ButtonSize.Sm)
                if (instance.canPick) AppButton(strings.ageKeyFile, onClick = instance::openAgeKeyFile, variant = ButtonVariant.Outline, size = ButtonSize.Sm)
            }
            SecretField(keyPassphrase, { instance.ageKeyPassphrase.value = it }, strings.ageKeyPassphrase, "$TAG:age-key-passphrase", strings)
        }
    }
    if (recipients.any { it is AgeKeys.Recipient.Scrypt }) {
        Card {
            SecretField(passphrase, { instance.agePassphrase.value = it }, strings.agePassphrase, "$TAG:age-passphrase", strings)
        }
    }
}

// -------------------------------------------------------------------- openssl

@Composable
private fun OpensslOptions(instance: CrRestoreInstance, strings: CrRestoreStrings) {
    val password by instance.password.collectAsState()
    val raw by instance.useRawKey.collectAsState()
    val keyHex by instance.keyHex.collectAsState()
    val ivHex by instance.ivHex.collectAsState()
    val iterations by instance.iterations.collectAsState()
    val params by instance.params.collectAsState()
    val more by instance.showMore.collectAsState()

    Card {
        if (raw) {
            SecretField(keyHex, { instance.keyHex.value = it }, strings.keyHex, "$TAG:key-hex", strings)
            SecretField(ivHex, { instance.ivHex.value = it }, strings.ivHex, "$TAG:iv-hex", strings)
        } else {
            SecretField(password, { instance.password.value = it }, strings.password, "$TAG:password", strings)
            if (params.kdf == OpensslEnc.Kdf.Pbkdf2) {
                Text(strings.iterations, style = AppTheme.label, color = AppTheme.muted, modifier = Modifier.padding(top = 8.dp))
                AppTextField(value = iterations, onValueChange = { v -> instance.iterations.value = v.filter { it.isDigit() } },
                    label = strings.iterations, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.testTag("$TAG:iterations"))
                Text(strings.iterationsHint, style = AppTheme.footnote, color = AppTheme.muted)
            }
        }
        ToggleRow(strings.rawKey, checked = raw, onCheckedChange = { instance.useRawKey.value = it }, modifier = Modifier.testTag("$TAG:raw-key"))
    }

    AppButton(if (more) strings.less else strings.more, onClick = { instance.showMore.value = !more }, variant = ButtonVariant.Ghost,
        size = ButtonSize.Sm, modifier = Modifier.padding(horizontal = 12.dp).testTag("$TAG:more"))
    if (more) {
        Card {
            Picker(strings.cipher, params.cipher, OpensslEnc.ciphers.keys.toList(), "$TAG:cipher") { instance.params.value = params.copy(cipher = it) }
            if (!raw) {
                Text(strings.kdf, style = AppTheme.label, color = AppTheme.muted, modifier = Modifier.padding(top = 8.dp))
                ChoiceRow(strings.kdfPbkdf2, params.kdf == OpensslEnc.Kdf.Pbkdf2) { instance.params.value = params.copy(kdf = OpensslEnc.Kdf.Pbkdf2) }
                RowDivider()
                ChoiceRow(strings.kdfLegacy, params.kdf == OpensslEnc.Kdf.Legacy) { instance.params.value = params.copy(kdf = OpensslEnc.Kdf.Legacy) }
                Picker(strings.digest, params.digest, OpensslEnc.digests.keys.toList(), "$TAG:digest") { instance.params.value = params.copy(digest = it) }
                Text(strings.salt, style = AppTheme.label, color = AppTheme.muted, modifier = Modifier.padding(top = 8.dp))
                ChoiceRow(strings.saltInFile, params.salted) { instance.params.value = params.copy(salted = true) }
                RowDivider()
                ChoiceRow(strings.saltNone, !params.salted) { instance.params.value = params.copy(salted = false) }
            }
            if (OpensslEnc.cipher(params.cipher)?.mode?.padded == true) {
                ToggleRow(strings.noPadding, checked = !params.padding, onCheckedChange = { instance.params.value = params.copy(padding = !it) })
            }
        }
    }
    Card {
        Text(strings.commandHint, style = AppTheme.label, color = AppTheme.muted)
        Text(command(params, iterations, raw), style = AppTheme.mono, color = AppTheme.foreground, modifier = Modifier.padding(top = 4.dp).testTag("$TAG:command"))
    }
}

/** The command line that makes what these settings open (D608). */
internal fun command(params: OpensslEnc.Params, iterations: String, raw: Boolean): String = buildString {
    append("openssl enc -").append(params.cipher)
    if (raw) {
        append(" -K <hex>")
        if (OpensslEnc.cipher(params.cipher)?.mode != BlockMode.ECB) append(" -iv <hex>")
    } else {
        if (params.kdf == OpensslEnc.Kdf.Pbkdf2) append(" -pbkdf2 -iter ").append(iterations.ifBlank { "10000" })
        if (params.digest != "sha256") append(" -md ").append(params.digest)
        if (!params.salted) append(" -nosalt")
    }
    if (!params.padding) append(" -nopad")
    append(" -a -in FILE | unitool qr encode --split")
}

// -------------------------------------------------------------------- split

@Composable
private fun SplitStatus(instance: CrRestoreInstance, strings: CrRestoreStrings) {
    val gathering by instance.split.collectAsState()
    Card(Modifier.testTag("$TAG:split-status")) {
        val need = gathering.need
        val total = gathering.total
        Text(if (need != null && total != null) strings.containerReady(need, total) else strings.containerMissing,
            style = AppTheme.body, color = AppTheme.foreground, modifier = Modifier.testTag("$TAG:container"))
        Text(strings.partsGathered(gathering.parts.size, need), style = AppTheme.body, color = AppTheme.foreground, modifier = Modifier.testTag("$TAG:parts"))
    }
}

// -------------------------------------------------------------------- the result

@Composable
private fun ResultPage(instance: CrRestoreInstance, strings: CrRestoreStrings, output: Output) {
    val revealed by instance.revealed.collectAsState()
    val saved by instance.saved.collectAsState()
    val copied by instance.copied.collectAsState()
    val warning by instance.warning.collectAsState()
    val clipboard = rememberSecretClipboard()

    Text(strings.resultTitle, style = AppTheme.header, color = AppTheme.foreground, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

    when (val w = warning) {
        Warning.Unchecked -> Message(strings.uncheckedWarning, "$TAG:warning", AppTheme.warning)
        is Warning.Disagreeing -> Message(strings.disagreeingWarning(w.numbers), "$TAG:warning", AppTheme.warning)
        null -> Unit
    }

    when (output) {
        is Output.Text -> Card(Modifier.clickable(role = Role.Button, onClick = instance::toggleReveal).testTag("$TAG:reveal")) {
            if (revealed) {
                Text(output.text, style = AppTheme.mono, color = AppTheme.foreground, modifier = Modifier.testTag("$TAG:text"))
                Text(strings.tapToHide, style = AppTheme.footnote, color = AppTheme.muted, modifier = Modifier.padding(top = 8.dp))
            } else {
                Text(strings.hidden(output.text.length), style = AppTheme.body, color = AppTheme.foreground, modifier = Modifier.testTag("$TAG:hidden"))
                Text(strings.tapToShow, style = AppTheme.footnote, color = AppTheme.muted)
            }
        }
        is Output.File -> Card {
            Text(strings.fileResult(output.name, output.bytes.size), style = AppTheme.body, color = AppTheme.foreground, modifier = Modifier.testTag("$TAG:file"))
        }
    }

    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (output is Output.Text) {
            AppButton(strings.copy, onClick = { instance.copyTo(clipboard) }, modifier = Modifier.weight(1f).testTag("$TAG:copy"))
        }
        if (instance.canSave) {
            AppButton(if (output is Output.Text) strings.saveAsFile else strings.save, onClick = instance::save,
                variant = if (output is Output.Text) ButtonVariant.Outline else ButtonVariant.Default,
                modifier = Modifier.weight(1f).testTag("$TAG:save"))
        }
    }
    if (copied) Hint(strings.copied, "$TAG:copied")
    saved?.let { Hint(if (it) strings.saved else strings.notSaved, "$TAG:saved") }
    AppButton(strings.startOver, onClick = instance::startOver, variant = ButtonVariant.Ghost,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("$TAG:start-over"))
}

// -------------------------------------------------------------------- pieces

/** A field whose text is dots until "Show" — for passwords and keys alike. */
@Composable
private fun SecretField(value: String, onChange: (String) -> Unit, label: String, tag: String, strings: CrRestoreStrings, singleLine: Boolean = true) {
    val tokens = AppTheme
    var shown by remember { mutableStateOf(false) }
    Text(label, style = tokens.label, color = tokens.muted, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = singleLine,
            textStyle = tokens.body.copy(color = tokens.foreground),
            cursorBrush = SolidColor(tokens.foreground),
            keyboardOptions = SecretKeyboard,
            visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(tokens.radius))
                .border(1.dp, tokens.border, RoundedCornerShape(tokens.radius))
                .padding(horizontal = 12.dp, vertical = 12.dp)
                .testTag(tag),
        )
        AppButton(if (shown) strings.hidePassword else strings.showPassword, onClick = { shown = !shown }, variant = ButtonVariant.Ghost, size = ButtonSize.Sm)
    }
}

/** One of [options], folded to the chosen one until tapped. */
@Composable
private fun Picker(label: String, chosen: String, options: List<String>, tag: String, onChoose: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Text(label, style = AppTheme.label, color = AppTheme.muted, modifier = Modifier.padding(top = 8.dp))
    Text("$chosen  ▾", style = AppTheme.body, color = AppTheme.foreground,
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { open = !open }.padding(vertical = 10.dp).testTag(tag))
    if (open) {
        options.forEach { option ->
            ChoiceRow(option, option == chosen) {
                onChoose(option)
                open = false
            }
        }
    }
}

@Composable
private fun Hint(text: String, tag: String) {
    Text(text, style = AppTheme.body, color = AppTheme.muted, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).testTag(tag))
}

@Composable
private fun Message(text: String, tag: String, color: androidx.compose.ui.graphics.Color) {
    Text(text, style = AppTheme.body, color = color, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).testTag(tag))
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
