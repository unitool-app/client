package cx.m42.apps.counters

import kotlinx.serialization.Serializable

/** One name with its number — a player and their level. */
@Serializable
internal data class Counter(
    val id: String,
    val name: String,
    val value: Long = 0,
)

/**
 * A score: a name ("Munchkin, Friday") and the counters inside it, in the order they were added.
 * Archived ones leave the main list but keep their numbers.
 */
@Serializable
internal data class Board(
    val id: String,
    val name: String,
    val createdAt: Long,
    val archived: Boolean = false,
    val counters: List<Counter> = emptyList(),
)

/** Newest first: tonight's game is the one at the top. */
internal fun List<Board>.active(): List<Board> = filter { !it.archived }.sortedByDescending { it.createdAt }

internal fun List<Board>.archived(): List<Board> = filter { it.archived }.sortedByDescending { it.createdAt }

internal fun List<Board>.updated(id: String, change: Board.() -> Board): List<Board> =
    map { if (it.id == id) it.change() else it }

internal fun Board.updatedCounter(id: String, change: Counter.() -> Counter): Board =
    copy(counters = counters.map { if (it.id == id) it.change() else it })

/** A name as it is kept: trimmed, and null when nothing is left — a blank name changes nothing. */
internal fun cleanName(text: String): String? = text.trim().takeIf { it.isNotEmpty() }
