package cx.m42.unitool.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import cx.m42.services.ssh.AddKnownOutcome
import cx.m42.services.ssh.PublicKeyBookAdmin
import cx.m42.superizer.HostScreen
import cx.m42.superizer.Superizer
import cx.m42.superizer.ssh.KeyId
import cx.m42.superizer.theme.AppTheme
import cx.m42.superizer.ui.components.AppTextField
import cx.m42.superizer.ui.components.ButtonVariant
import cx.m42.superizer.ui.components.RowDivider
import cx.m42.superizer.ui.components.Section
import cx.m42.superizer.ui.platform.SystemBackHandler
import cx.m42.unitool.i18n.SecurityStrings
import cx.m42.unitool.i18n.securityStrings
import cx.m42.unitool.kit.qr.ErrorCorrection
import cx.m42.unitool.kit.qr.Qr
import kotlinx.coroutines.launch

/*
 * Other devices' public keys (.ai-notes/public-keys): a phone shows its key as a QR code, another
 * reads it and keeps only the public half, to encrypt to. The keyring holds the list; these screens
 * are the host's door to it (D512).
 */

/** The second card of SSH keys (D513): the saved public keys and the way to add one. */
@Composable
internal fun KnownKeysCard(book: PublicKeyBookAdmin, tools: HostTools, strings: SecurityStrings, open: (HostScreen) -> Unit) {
    val known by book.keys.collectAsState()
    Section(title = strings.knownKeys, hint = strings.knownKeysHint) {
        if (known.isEmpty()) {
            RowText(strings.noKnownKeys, Modifier.testTag("known:empty"), color = AppTheme.muted)
            RowDivider()
        }
        known.forEachIndexed { i, key ->
            ActionRow(key.name, "known:row-$i", "${key.key.label} · ${shortFingerprint(key.id)}") {
                open(KnownKeyCardScreen(book, tools, key.id))
            }
            RowDivider()
        }
        ActionRow(strings.addPublicKey, "known:add") { open(AddPublicKeyScreen(book, tools)) }
    }
}

/**
 * "Add a public key": a QR code off the other phone's screen, a `.pub` file, or a pasted line; then
 * the fingerprint to compare and a name (D511). Nothing is stored before "Save".
 */
internal class AddPublicKeyScreen(private val book: PublicKeyBookAdmin, private val tools: HostTools) : HostScreen {
    override val id: String = "public-key-add"

    override fun title(langTag: String): String = securityStrings(langTag).addPublicKey

    @Composable
    override fun Content(superizer: Superizer, back: () -> Unit, open: (HostScreen) -> Unit) {
        val langTag by superizer.settings.langTag.collectAsState()
        val strings = securityStrings(langTag)
        val scope = rememberCoroutineScope()
        var pasting by remember { mutableStateOf(false) }
        var pasted by remember { mutableStateOf("") }
        var found by remember { mutableStateOf<AddKnownOutcome.Ok?>(null) }
        var name by remember { mutableStateOf("") }
        var problem by remember { mutableStateOf<AddKnownOutcome?>(null) }

        fun take(text: String) {
            when (val seen = book.inspect(text.take(MAX_TEXT))) {
                is AddKnownOutcome.Ok -> {
                    found = seen
                    name = seen.comment
                    problem = null
                }
                else -> problem = seen
            }
        }

        SystemBackHandler(enabled = found != null || pasting) {
            if (found != null) found = null else pasting = false
        }

        val key = found
        Page(if (key == null) "known-add:source" else "known-add:confirm") {
            if (key == null) {
                if (pasting) {
                    Note(strings.pastePublicHint)
                    Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
                        AppTextField(
                            value = pasted,
                            onValueChange = { pasted = it; problem = null },
                            placeholder = "ssh-ed25519 AAAA…",
                            label = strings.publicKey,
                            singleLine = false,
                            minHeight = 120.dp,
                            textStyle = AppTheme.mono,
                            keyboardOptions = PastedKeyKeyboard,
                            modifier = Modifier.testTag("known-add:paste-field"),
                        )
                    }
                    ProblemNote(problem, strings)
                    PageButton(strings.continueLabel, "known-add:paste-next", enabled = pasted.isNotBlank()) { take(pasted) }
                    PageButton(strings.back, "known-add:back", variant = ButtonVariant.Outline) { pasting = false }
                } else {
                    Card {
                        // Each source only where the platform has it (D19).
                        if (tools.qr != null) {
                            ActionRow(strings.scanQr, "known-add:qr", strings.scanPublicQrHint) {
                                scope.launch { tools.qr?.scan()?.let(::take) }
                            }
                            RowDivider()
                        }
                        if (tools.files != null) {
                            ActionRow(strings.importFile, "known-add:file", strings.publicFileHint) {
                                scope.launch {
                                    val picked = tools.files?.pick() ?: return@launch
                                    take(picked.bytes.decodeToString())
                                }
                            }
                            RowDivider()
                        }
                        ActionRow(strings.paste, "known-add:paste", strings.pastePublicHint) { pasting = true }
                    }
                    ProblemNote(problem, strings)
                }
            } else {
                Section(title = strings.publicKey) {
                    RowText(key.key.label, Modifier.testTag("known-add:kind"))
                    RowDivider()
                    RowText("${strings.fingerprint}: ${key.key.fingerprint}", Modifier.testTag("known-add:fingerprint"), mono = true)
                }
                Note(strings.checkFingerprint)
                FieldLabel(strings.keyName)
                Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
                    AppTextField(
                        value = name,
                        onValueChange = { name = it },
                        placeholder = key.key.label,
                        label = strings.keyName,
                        modifier = Modifier.testTag("known-add:name"),
                    )
                }
                ProblemNote(problem, strings)
                PageButton(strings.save, "known-add:save") {
                    scope.launch {
                        when (val added = book.add(key.key, name)) {
                            is AddKnownOutcome.Ok -> back()
                            else -> problem = added
                        }
                    }
                }
                PageButton(strings.back, "known-add:back", variant = ButtonVariant.Outline) { found = null }
            }
        }
    }

    private companion object {
        /** Far more than an RSA-8192 line; a picked photo or a private key file is cut short and refused. */
        const val MAX_TEXT = 16_384
    }
}

@Composable
private fun ProblemNote(problem: AddKnownOutcome?, strings: SecurityStrings) {
    if (problem != null && problem !is AddKnownOutcome.Ok) Note(strings.knownProblem(problem), color = AppTheme.danger, tag = "known-add:problem")
}

/** One saved public key: its name to change, the line to copy, its QR code to pass on, and delete. */
internal class KnownKeyCardScreen(private val book: PublicKeyBookAdmin, private val tools: HostTools, private val keyId: KeyId) : HostScreen {
    override val id: String = "public-key"

    override fun title(langTag: String): String =
        book.keys.value.firstOrNull { it.id == keyId }?.name ?: securityStrings(langTag).publicKey

    @Composable
    override fun Content(superizer: Superizer, back: () -> Unit, open: (HostScreen) -> Unit) {
        val langTag by superizer.settings.langTag.collectAsState()
        val strings = securityStrings(langTag)
        val known by book.keys.collectAsState()
        val key = known.firstOrNull { it.id == keyId } ?: return
        val scope = rememberCoroutineScope()
        val clipboard = LocalClipboardManager.current
        var name by remember(key.name) { mutableStateOf(key.name) }
        var copied by remember { mutableStateOf(false) }
        var deleting by remember { mutableStateOf(false) }

        Page("known:card") {
            Section(title = strings.publicKey) {
                RowText(key.line, Modifier.testTag("known:line"), mono = true)
                RowDivider()
                RowText("${strings.fingerprint}: ${key.key.fingerprint}", mono = true, small = true)
                SmallButtons(
                    Triple(if (copied) strings.copied else strings.copy, "known:copy") { clipboard.setText(AnnotatedString(key.line)); copied = true },
                    Triple(strings.showQr, "known:qr") { open(PublicKeyQrScreen(key.name, key.line, key.key.fingerprint)) },
                )
            }
            FieldLabel(strings.keyName)
            Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
                AppTextField(value = name, onValueChange = { name = it }, label = strings.keyName, modifier = Modifier.testTag("known:name"))
            }
            PageButton(strings.save, "known:rename", enabled = name.isNotBlank() && name.trim() != key.name, variant = ButtonVariant.Secondary) {
                scope.launch { book.rename(keyId, name) }
            }
            // Two taps rather than the keyring's sheet: there is nothing secret to lose, only a line to paste again.
            PageButton(if (deleting) strings.deleteAgain else strings.delete, "known:delete", variant = ButtonVariant.Destructive) {
                if (!deleting) {
                    deleting = true
                } else {
                    scope.launch {
                        book.remove(keyId)
                        back()
                    }
                }
            }
        }
    }
}

/**
 * A public key as a QR code, for another phone to read (D510): the very line "Copy" gives, with its
 * comment, and the fingerprint under it to compare (D511). Not a secure screen — nothing on it is secret.
 */
internal class PublicKeyQrScreen(private val name: String, private val line: String, private val fingerprint: String) : HostScreen {
    override val id: String = "public-key-qr"

    override fun title(langTag: String): String = name.ifEmpty { securityStrings(langTag).publicKey }

    @Composable
    override fun Content(superizer: Superizer, back: () -> Unit, open: (HostScreen) -> Unit) {
        val langTag by superizer.settings.langTag.collectAsState()
        val strings = securityStrings(langTag)
        Page("key-qr:screen") {
            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 16.dp), contentAlignment = Alignment.Center) {
                QrCodeImage(line, Modifier.widthIn(max = 420.dp).fillMaxWidth().testTag("key-qr:code"))
            }
            Section(title = strings.fingerprint) {
                RowText(fingerprint, Modifier.testTag("key-qr:fingerprint"), mono = true)
            }
            Note(strings.qrScreenHint)
        }
    }
}

/**
 * [text] as a QR code: black on white whatever the theme, with the four-module quiet zone the
 * standard asks for, because a camera reads contrast and not the app's colours.
 */
@Composable
internal fun QrCodeImage(text: String, modifier: Modifier = Modifier) {
    val code = remember(text) { Qr.encode(text, ErrorCorrection.M) } ?: return
    Canvas(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(AppTheme.radius))
            .background(Color.White),
    ) {
        val cells = code.size + 2 * QUIET_ZONE
        // Whole pixels per module: fractional ones leave hairlines between dark cells.
        val cell = kotlin.math.floor(size.minDimension / cells).coerceAtLeast(1f)
        val origin = (size.minDimension - cell * code.size) / 2
        for (y in 0 until code.size) {
            for (x in 0 until code.size) {
                if (code.isDark(x, y)) drawRect(Color.Black, Offset(origin + x * cell, origin + y * cell), Size(cell, cell))
            }
        }
    }
}

private const val QUIET_ZONE = 4
