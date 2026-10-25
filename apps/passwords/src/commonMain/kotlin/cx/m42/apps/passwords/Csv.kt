package cx.m42.apps.passwords

/**
 * RFC 4180, both ways: what browsers and password managers export, and what the export writes.
 *
 * Lenient on the way in — `\r\n`, `\n` or a lone `\r`, a byte-order mark, a missing last newline,
 * a quote in the middle of an unquoted field — because a file that came from somewhere else is
 * what it is, and the person cannot fix it on a phone.
 */
internal object Csv {

    fun parse(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = if (text.startsWith('﻿')) 1 else 0
        fun endField() {
            row += field.toString()
            field.clear()
        }
        fun endRow() {
            endField()
            // A blank line is no record; a row of empty fields between commas still is one.
            if (row.size > 1 || row[0].isNotEmpty()) rows += row
            row = mutableListOf()
        }
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                quoted && c == '"' -> quoted = false
                quoted -> field.append(c)
                c == '"' && field.isEmpty() -> quoted = true
                c == ',' -> endField()
                c == '\r' -> { endRow(); if (text.getOrNull(i + 1) == '\n') i++ }
                c == '\n' -> endRow()
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }

    fun format(rows: List<List<String>>): String = rows.joinToString("") { row -> row.joinToString(",") { quote(it) } + "\n" }

    private fun quote(field: String): String =
        if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' } || field != field.trim()) {
            "\"" + field.replace("\"", "\"\"") + "\""
        } else {
            field
        }
}

/**
 * What a batch read for import turned out to hold: a browser's or a password manager's CSV, or this
 * app's own export opened on a computer and shown as QR codes (D531, 230).
 */
internal data class ImportBatch(
    /** The entries read, in the order they came, the ones this device already has among them. */
    val found: List<Found> = emptyList(),
    /** The rows that are not an entry, and why. */
    val failed: List<Failed> = emptyList(),
    /**
     * The columns the first part named, for the parts after it: `split` cuts the export into QR
     * codes by lines, and only the first one carries the header.
     */
    val columns: Columns? = null,
) {
    /** [entry] has no id yet; it gets one when it is added. */
    data class Found(val entry: PasswordEntry, val known: Boolean)

    /** [line] counts records from 1, the header included, the way a spreadsheet numbers rows. */
    data class Failed(val line: Int, val error: DraftError, val title: String = "")

    val addable: List<Int> get() = found.indices.filter { !found[it].known }
}

/** Where each field is in a file's rows; -1 for a field the file does not have. */
internal data class Columns(
    val title: Int,
    val url: Int,
    val username: Int,
    val password: Int,
    val note: Int,
    /** Bitwarden's `type`: only `login` rows are logins. */
    val type: Int = -1,
) {
    companion object {
        /**
         * The export's own order, which is also Chrome's: `name,url,username,password,note`. Taken
         * for a part with no header — the second QR code of a long export.
         */
        val Export = Columns(title = 0, url = 1, username = 2, password = 3, note = 4)
    }
}

/** Why a picked file gave no entries. */
internal enum class FileProblem { NoKey, NotOpened, NoEntries }

internal object Import {

    /**
     * Header names as the common exporters spell them, lower-cased:
     * Chrome `name,url,username,password,note`; Firefox `url,username,password,…`;
     * Safari and Apple Passwords `Title,URL,Username,Password,Notes,OTPAuth`;
     * Bitwarden `folder,favorite,type,name,notes,…,login_uri,login_username,login_password`;
     * KeePassXC `Group,Title,Username,Password,URL,Notes,…`; 1Password `Title,Url,Username,Password,…`.
     */
    private val TITLE = setOf("name", "title")
    private val URL = setOf("url", "login_uri", "website", "web site", "uri")
    private val USERNAME = setOf("username", "login_username", "login", "user name", "user", "email")
    private val PASSWORD = setOf("password", "login_password")
    private val NOTE = setOf("note", "notes", "extra", "comments")

    /** The header's columns, or null when [row] is not a header — it names no password column. */
    fun header(row: List<String>): Columns? {
        val names = row.map { it.trim().lowercase() }
        fun find(set: Set<String>) = names.indexOfFirst { it in set }
        val password = find(PASSWORD).takeIf { it >= 0 } ?: return null
        return Columns(find(TITLE), find(URL), find(USERNAME), password, find(NOTE), names.indexOf("type"))
    }

    /**
     * [text]'s rows, added to [into]. An entry the device has already is marked known; one already
     * in the batch — the same QR code scanned twice — is left out. Null when [text] is not a CSV
     * of logins at all: no header and fewer columns than the export writes.
     */
    fun read(text: String, have: List<PasswordEntry>, into: ImportBatch = ImportBatch()): ImportBatch? {
        val rows = Csv.parse(text)
        if (rows.isEmpty()) return null
        val header = header(rows[0])
        val columns = header ?: into.columns ?: Columns.Export.takeIf { rows.all { it.size >= 4 } } ?: return null
        val found = into.found.toMutableList()
        val failed = into.failed.toMutableList()
        val body = if (header != null) rows.drop(1) else rows
        val first = if (header != null) 2 else 1
        body.forEachIndexed { i, row ->
            fun at(index: Int) = row.getOrNull(index).orEmpty()
            if (columns.type >= 0 && at(columns.type).isNotBlank() && !at(columns.type).equals("login", ignoreCase = true)) return@forEachIndexed
            val draft = EntryDraft(at(columns.title), at(columns.username), at(columns.password), at(columns.url), at(columns.note))
            draft.validate(id = "", now = 0)
                .onSuccess { read ->
                    if (found.none { it.entry.sameAs(read) }) found += ImportBatch.Found(read, known = have.any { it.sameAs(read) })
                }
                .onFailure {
                    val title = draft.title.ifBlank { Site.host(draft.url) }.ifBlank { draft.username }
                    failed += ImportBatch.Failed(first + i, (it as DraftException).error, title)
                }
        }
        return ImportBatch(found, failed, into.columns ?: columns)
    }
}
