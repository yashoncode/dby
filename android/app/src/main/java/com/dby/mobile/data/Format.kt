package com.dby.mobile.data

import com.dby.core.Cell
import com.dby.core.DbyException
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

/** A cell as one line of text for lists and grids. A value the server trimmed ends with "…". */
fun Cell.display(): String = when (this) {
    is Cell.Null -> "NULL"
    is Cell.Bytes -> "<${count(len.toLong())} bytes>"
    is Cell.Text -> if (isTrimmed()) "$v…" else v
    else -> rawText().orEmpty()
}

/** The value an edit field starts with, or null for NULL and binary (which fields cannot hold). */
fun Cell.rawText(): String? = when (this) {
    is Cell.Null, is Cell.Bytes -> null
    is Cell.Signed -> v.toString()
    is Cell.Unsigned -> v.toString()
    is Cell.Real -> v.toString()
    is Cell.Exact -> v
    is Cell.Text -> v
    is Cell.Temporal -> v
}

/** True when the page holds only the start of this value. The core counts characters, not UTF-16 units. */
fun Cell.isTrimmed(): Boolean = when (this) {
    is Cell.Text -> fullLen > v.codePointCount(0, v.length).toULong()
    is Cell.Bytes -> len > preview.size.toULong()
    else -> false
}

fun count(n: Long): String = NumberFormat.getIntegerInstance(Locale.US).format(n)

/** "412 MB", "9.8 MB", "64 KB": one decimal below ten, 1024-based. */
fun bytes(n: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = n.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    val text = if (unit > 0 && value < 10) String.format(Locale.US, "%.1f", value).removeSuffix(".0") else value.roundToLong().toString()
    return "$text ${units[unit]}"
}

fun ago(atMs: Long, nowMs: Long): String {
    val s = (nowMs - atMs) / 1000
    return when {
        s < 45 -> "just now"
        s < 3600 -> "${(s + 30) / 60} min ago"
        s < 86_400 -> "${s / 3600} h ago"
        s < 7 * 86_400 -> "${s / 86_400} d ago"
        else -> SimpleDateFormat("MMM d", Locale.US).format(Date(atMs))
    }
}

/** One short sentence for a failure (spec §9); the raw text goes behind "Details". */
fun Throwable.sentence(): String = when (this) {
    is DbyException.Network -> "Can't reach the server. Check the host, the port and your network."
    is DbyException.Tls -> "The secure connection failed."
    is DbyException.Auth -> "Wrong user or password."
    is DbyException.UnknownDatabase -> "That database doesn't exist on this server."
    is DbyException.Server -> "The server refused it (error $code)."
    is DbyException.NotFound -> "It's no longer there."
    is DbyException.ReadOnlyBlocked -> detail.trimEnd('.').replaceFirstChar { it.uppercase() } + "."
    is DbyException.RowEditMismatch -> "The row was changed or deleted elsewhere, so nothing was saved."
    is DbyException.Cancelled -> "Cancelled."
    is DbyException.Timeout -> "The server took too long to answer."
    is DbyException.Storage -> "Couldn't read or save on this phone."
    is DbyException.Internal -> "Something went wrong."
    else -> message ?: "Something went wrong."
}

/** The raw text behind a [sentence]. */
fun Throwable.details(): String = message ?: toString()
