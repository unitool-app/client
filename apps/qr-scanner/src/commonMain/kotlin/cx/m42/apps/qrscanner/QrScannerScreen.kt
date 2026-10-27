package cx.m42.apps.qrscanner

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import cx.m42.superizer.runtime.LocalAppRuntime
import cx.m42.superizer.theme.AppTheme
import cx.m42.superizer.ui.components.AppButton
import cx.m42.superizer.ui.components.ButtonSize
import cx.m42.superizer.ui.components.ButtonVariant
import cx.m42.superizer.ui.platform.SystemBackHandler
import cx.m42.unitool.kit.qr.QrFileGathering

private const val TAG = QrScannerApp.ID

/** Cells in a row of the gathering grid (D632): one per chunk, filled once it is in. */
private const val GRID_COLUMNS = 25

/**
 * One page (D625): Scan and Paste on top, the result below with what can be done with it. Back
 * clears a result before it leaves the app.
 */
@Composable
internal fun QrScannerScreen(instance: QrScannerInstance) {
    val runtime = LocalAppRuntime.current
    val langTag by runtime.locale.langTag.collectAsState()
    val strings = remember(langTag) { runtime.locale.pick(QrScannerStringTables, QrScannerStringsEn) }
    val result by instance.result.collectAsState()
    val notice by instance.notice.collectAsState()
    val clipboard = LocalClipboardManager.current

    SystemBackHandler(enabled = result != null) { instance.reset() }

    Box(modifier = Modifier.fillMaxSize().background(AppTheme.pageBackground)) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 12.dp)) {
                if (instance.canScan) {
                    AppButton(strings.scan, onClick = instance::scan, modifier = Modifier.weight(1f).testTag("$TAG:scan"))
                }
                AppButton(
                    strings.paste,
                    onClick = { instance.paste(clipboard.getText()?.text.orEmpty()) },
                    variant = if (instance.canScan) ButtonVariant.Outline else ButtonVariant.Default,
                    modifier = Modifier.weight(1f).testTag("$TAG:paste"),
                )
            }
            notice?.let {
                val text = when (it) {
                    Notice.Copied -> strings.copied
                    Notice.Saved -> strings.saved
                    Notice.NotSaved -> strings.notSaved
                }
                Hint(text, "$TAG:notice")
            }
            when (val current = result) {
                null -> Hint(if (instance.canScan) strings.empty else strings.emptyNoCamera, "$TAG:empty")
                is Result.Single -> {
                    SingleResult(instance, strings, current)
                    AgainButton(instance, strings)
                }
                is Result.Gathering -> GatheringResult(instance, strings, current)
                is Result.File -> {
                    FileResult(instance, strings, current)
                    AgainButton(instance, strings)
                }
                Result.Damaged -> {
                    Message(strings.damaged, "$TAG:damaged", AppTheme.danger)
                    AgainButton(instance, strings)
                }
            }
        }
    }
}

// -------------------------------------------------------------------- one code

@Composable
private fun SingleResult(instance: QrScannerInstance, strings: QrScannerStrings, result: Result.Single) {
    val hex by instance.hex.collectAsState()
    val text = result.text
    Actions(instance, strings, canHex = text != null, hex = hex) {
        if (text != null) {
            OpenButton(strings, result.content)
            if (instance.canScan) {
                AppButton(strings.oneMore, onClick = instance::scanMore, variant = ButtonVariant.Outline, size = ButtonSize.Sm, modifier = Modifier.testTag("$TAG:more"))
            }
            if (instance.decodable() != null) {
                AppButton(strings.decode, onClick = instance::decode, variant = ButtonVariant.Outline, size = ButtonSize.Sm, modifier = Modifier.testTag("$TAG:decode"))
            }
        }
    }
    Card {
        val kind = if (text == null) strings.binary(result.bytes.size) else kind(strings, result.content)
        val suffix = if (result.codes > 1) " · " + strings.codes(result.codes) else ""
        Text(kind + suffix, style = AppTheme.label, color = AppTheme.muted, modifier = Modifier.testTag("$TAG:kind"))
        if (text != null && !hex) {
            Fields(strings, result.content)
            Body(text, mono = result.content == Content.Text, tag = "$TAG:text")
        } else {
            HexDump(strings, result.bytes)
        }
    }
}

private fun kind(strings: QrScannerStrings, content: Content): String = when (content) {
    is Content.Link -> strings.kindLink
    is Content.Wifi -> strings.kindWifi
    is Content.Email -> strings.kindEmail
    is Content.Phone -> strings.kindPhone
    is Content.Sms -> strings.kindSms
    is Content.Geo -> strings.kindGeo
    is Content.Contact -> strings.kindContact
    is Content.Event -> strings.kindEvent
    is Content.Otp -> strings.kindOtp
    Content.Text -> strings.kindText
}

/** The fields worth a line of their own (D626); the raw text follows below them. */
@Composable
private fun Fields(strings: QrScannerStrings, content: Content) {
    when (content) {
        is Content.Wifi -> {
            Field(strings.network, content.ssid)
            content.security?.let { Field(strings.security, it) }
            content.password?.let { Password(strings, it) }
            if (content.hidden) Field(strings.hiddenNetwork, "✓")
        }
        is Content.Email -> {
            Field(strings.to, content.to)
            content.subject?.let { Field(strings.subject, it) }
            content.body?.let { Field(strings.body, it) }
        }
        is Content.Phone -> Field(strings.number, content.number)
        is Content.Sms -> {
            Field(strings.number, content.number)
            content.body?.let { Field(strings.body, it) }
        }
        is Content.Geo -> Field(strings.coordinates, "${content.latitude}, ${content.longitude}")
        is Content.Contact -> {
            content.name?.let { Field(strings.name, it) }
            content.phones.forEach { Field(strings.phone, it) }
            content.emails.forEach { Field(strings.email, it) }
            content.organization?.let { Field(strings.organization, it) }
        }
        is Content.Event -> {
            content.summary?.let { Field(strings.summary, it) }
            content.start?.let { Field(strings.start, it) }
            content.end?.let { Field(strings.end, it) }
            content.location?.let { Field(strings.location, it) }
        }
        is Content.Otp -> {
            content.issuer?.let { Field(strings.issuer, it) }
            content.account?.let { Field(strings.account, it) }
            Text(if (content.migration) strings.migrationNote else strings.otpNote, style = AppTheme.footnote, color = AppTheme.muted,
                modifier = Modifier.padding(top = 8.dp).testTag("$TAG:otp-note"))
        }
        is Content.Link, Content.Text -> Unit
    }
}

/** The Wi-Fi password, hidden until tapped, with its own copy button. */
@Composable
private fun Password(strings: QrScannerStrings, password: String) {
    var shown by remember(password) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    Text(strings.password, style = AppTheme.label, color = AppTheme.muted, modifier = Modifier.padding(top = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (shown) password else "•".repeat(minOf(password.length, 12)) + "  " + strings.showPassword,
            style = AppTheme.mono,
            color = AppTheme.foreground,
            modifier = Modifier.weight(1f).clickable { shown = !shown }.testTag("$TAG:password"),
        )
        AppButton(strings.copyPassword, onClick = { clipboard.setText(AnnotatedString(password)) }, variant = ButtonVariant.Ghost,
            size = ButtonSize.Sm, modifier = Modifier.testTag("$TAG:copy-password"))
    }
}

/** "Open" for what the system knows how to open: a link, mail, a call, a message, a map. */
@Composable
private fun OpenButton(strings: QrScannerStrings, content: Content) {
    val uri = when (content) {
        is Content.Link -> content.url
        is Content.Email -> content.uri
        is Content.Phone -> content.uri
        is Content.Sms -> content.uri
        is Content.Geo -> content.uri
        else -> null
    } ?: return
    val handler = LocalUriHandler.current
    AppButton(strings.open, onClick = { runCatching { handler.openUri(uri) } }, size = ButtonSize.Sm, modifier = Modifier.testTag("$TAG:open"))
}

// -------------------------------------------------------------------- a file

@Composable
private fun GatheringResult(instance: QrScannerInstance, strings: QrScannerStrings, result: Result.Gathering) {
    val gathering = result.gathering
    Card {
        Text(strings.gatheringTitle, style = AppTheme.label, color = AppTheme.muted)
        val name = gathering.name?.let { name -> name + (gathering.size?.let { " · " + strings.size(it) } ?: "") }
        name?.let { Text(it, style = AppTheme.subtitle, color = AppTheme.foreground, modifier = Modifier.padding(top = 4.dp).testTag("$TAG:file-name")) }
        Text(strings.progress(gathering.have, gathering.count), style = AppTheme.header, color = AppTheme.foreground,
            modifier = Modifier.padding(top = 4.dp).testTag("$TAG:progress"))
        Grid(gathering)
        Text(strings.missing(ranges(gathering.missing)), style = AppTheme.footnote, color = AppTheme.muted,
            modifier = Modifier.padding(top = 8.dp).testTag("$TAG:missing"))
    }
    if (result.otherFile) Message(strings.otherFileNotice, "$TAG:other-file", AppTheme.warning)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        if (instance.canScan) {
            AppButton(strings.continueScanning, onClick = instance::scan, modifier = Modifier.weight(1f).testTag("$TAG:continue"))
        }
        AppButton(strings.startOther, onClick = instance::reset, variant = ButtonVariant.Ghost, modifier = Modifier.testTag("$TAG:again"))
    }
}

/** One cell per chunk, drawn on one canvas: a thousand of them would be too many composables. */
@Composable
private fun Grid(gathering: QrFileGathering) {
    val rows = (gathering.count + GRID_COLUMNS - 1) / GRID_COLUMNS
    val filled = AppTheme.foreground
    val empty = AppTheme.muted.copy(alpha = 0.25f)
    Canvas(modifier = Modifier.fillMaxWidth().padding(top = 8.dp).aspectRatio(GRID_COLUMNS.toFloat() / rows).testTag("$TAG:grid")) {
        val cell = size.width / GRID_COLUMNS
        val gap = cell * 0.15f
        for (i in 0 until gathering.count) {
            drawRect(
                color = if (gathering.has(i + 1)) filled else empty,
                topLeft = Offset(i % GRID_COLUMNS * cell + gap / 2, i / GRID_COLUMNS * cell + gap / 2),
                size = Size(cell - gap, cell - gap),
            )
        }
    }
}

/** `3, 7–9, 12…` for the missing numbers, the first twenty runs at most. */
internal fun ranges(numbers: List<Int>): String {
    val runs = mutableListOf<IntRange>()
    for (n in numbers) {
        val last = runs.lastOrNull()
        if (last != null && last.last + 1 == n) runs[runs.size - 1] = last.first..n else runs += n..n
    }
    val shown = runs.take(20).joinToString(", ") { if (it.first == it.last) "${it.first}" else "${it.first}–${it.last}" }
    return if (runs.size > 20) "$shown…" else shown
}

@Composable
private fun FileResult(instance: QrScannerInstance, strings: QrScannerStrings, result: Result.File) {
    val hex by instance.hex.collectAsState()
    val text = result.text
    Actions(instance, strings, canHex = text != null, hex = hex) {}
    Card {
        Text(strings.kindFile, style = AppTheme.label, color = AppTheme.muted)
        Text("${result.name} · ${strings.size(result.bytes.size)}", style = AppTheme.subtitle, color = AppTheme.foreground,
            modifier = Modifier.padding(top = 4.dp).testTag("$TAG:file"))
        if (text != null && !hex) Body(text, mono = true, tag = "$TAG:text") else HexDump(strings, result.bytes)
    }
}

// -------------------------------------------------------------------- shared

/** Copy, Save, the Text / Hex switch and whatever [extra] adds — above the result, which can be long. */
@Composable
private fun Actions(instance: QrScannerInstance, strings: QrScannerStrings, canHex: Boolean, hex: Boolean, extra: @Composable () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        AppButton(strings.copy, onClick = {
            instance.copyText()?.let { clipboard.setText(AnnotatedString(it)) }
            instance.copied()
        }, size = ButtonSize.Sm, modifier = Modifier.testTag("$TAG:copy"))
        if (instance.canSave) {
            AppButton(strings.save, onClick = instance::save, variant = ButtonVariant.Outline, size = ButtonSize.Sm, modifier = Modifier.testTag("$TAG:save"))
        }
        if (canHex) {
            AppButton(if (hex) strings.text else strings.hex, onClick = { instance.showHex(!hex) }, variant = ButtonVariant.Outline,
                size = ButtonSize.Sm, modifier = Modifier.testTag("$TAG:hex"))
        }
        extra()
    }
}

@Composable
private fun AgainButton(instance: QrScannerInstance, strings: QrScannerStrings) {
    AppButton(strings.again, onClick = instance::reset, variant = ButtonVariant.Ghost,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("$TAG:again"))
}

@Composable
private fun Field(label: String, value: String) {
    Text(label, style = AppTheme.label, color = AppTheme.muted, modifier = Modifier.padding(top = 8.dp))
    SelectionContainer { Text(value, style = AppTheme.body, color = AppTheme.foreground) }
}

@Composable
private fun Body(text: String, mono: Boolean, tag: String) {
    SelectionContainer(modifier = Modifier.padding(top = 8.dp)) {
        Text(text, style = if (mono) AppTheme.mono else AppTheme.body, color = AppTheme.foreground, modifier = Modifier.testTag(tag))
    }
}

/** `hexdump -C` style, scrolled sideways on a narrow screen; only the first 64 KB (D627). */
@Composable
private fun HexDump(strings: QrScannerStrings, bytes: ByteArray) {
    val dump = remember(bytes) { Bytes.dump(bytes) }
    Box(modifier = Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState())) {
        SelectionContainer { Text(dump, style = AppTheme.mono, color = AppTheme.foreground, modifier = Modifier.testTag("$TAG:hex-dump")) }
    }
    if (bytes.size > Bytes.MAX_SHOWN) {
        Text(strings.tooLongToShow, style = AppTheme.footnote, color = AppTheme.muted, modifier = Modifier.padding(top = 8.dp))
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
private fun Card(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(AppTheme.radius))
            .background(AppTheme.background)
            .padding(16.dp),
    ) {
        content()
    }
}
