package com.dby.mobile.ui.table

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dby.core.Cell
import com.dby.core.CellRequest
import com.dby.mobile.data.count
import com.dby.mobile.data.display
import com.dby.mobile.data.isTrimmed
import com.dby.mobile.data.rawText
import com.dby.mobile.ui.DbyIcons
import com.dby.mobile.ui.common.Busy
import com.dby.mobile.ui.common.ProblemBanner
import com.dby.mobile.ui.common.RoundButton
import com.dby.mobile.ui.common.Segmented
import com.dby.mobile.ui.common.Sheet
import com.dby.mobile.ui.common.SheetHeader
import com.dby.mobile.ui.common.copyText
import com.dby.mobile.ui.glass.lightGlass
import com.dby.mobile.ui.theme.Dby
import com.dby.mobile.ui.theme.Type
import com.kyant.backdrop.Backdrop
import org.json.JSONArray
import org.json.JSONObject

/** Pretty-printed JSON, or null when the text is not a JSON object or array. */
private fun prettyJson(text: String): String? = runCatching {
    val t = text.trim()
    when {
        t.startsWith("{") -> JSONObject(t).toString(2)
        t.startsWith("[") -> JSONArray(t).toString(2)
        else -> null
    }
}.getOrNull()

/** Bytes as an offset / hex / text dump, 16 per line. */
private fun hexDump(bytes: ByteArray): String = bytes.toList().chunked(16).withIndex().joinToString("\n") { (line, chunk) ->
    val hex = chunk.joinToString(" ") { "%02x".format(it) }.padEnd(47)
    val text = chunk.map { b -> val c = b.toInt() and 0xff; if (c in 32..126) c.toChar() else '.' }.joinToString("")
    "%06x  %s  %s".format(line * 16, hex, text)
}

/** One cell in full (spec §6 rule 9): loads what the page trimmed, pretty-prints JSON, dumps bytes. */
@Composable
fun CellViewer(model: TableModel, row: Int, column: Int, backdrop: Backdrop, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val name = model.columns[column].name
    val shown = model.rows[row][column]
    var cell by remember(row, column) { mutableStateOf(shown) }
    var problem by remember(row, column) { mutableStateOf<Throwable?>(null) }
    var pretty by remember { mutableStateOf(true) }
    LaunchedEffect(row, column) {
        val session = model.session
        val keys = model.defs.withIndex().filter { it.value?.pkSeq != null }.sortedBy { it.value!!.pkSeq }.map { model.rows[row][it.index] }
        if (shown.isTrimmed() && session != null && keys.isNotEmpty()) {
            problem = runCatching { cell = session.fullCell(CellRequest(model.table, name, keys)) }.exceptionOrNull()
        }
    }
    val text = when (val c = cell) {
        is Cell.Bytes -> hexDump(c.preview)
        else -> c.rawText() ?: c.display()
    }
    val json = remember(text) { prettyJson(text) }
    Sheet(backdrop, onDismiss) {
        val size = when (val c = cell) {
            is Cell.Bytes -> "${count(c.len.toLong())} bytes"
            is Cell.Text -> "${count(c.fullLen.toLong())} characters"
            else -> model.columns[column].typeName
        }
        SheetHeader(name, subtitle = size, onClose = onDismiss) {
            RoundButton(DbyIcons.Copy, "Copy value", { copyText(context, name, json?.takeIf { pretty } ?: text) })
        }
        if (cell.isTrimmed()) {
            if (problem == null) Busy("Loading the full value…")
            Text(
                "Showing the first ${if (cell is Cell.Bytes) "bytes" else "characters"} only.",
                style = Type.Caption,
                color = Dby.Secondary,
            )
        }
        problem?.let { ProblemBanner(it, modifier = Modifier.padding(0.dp)) }
        if (json != null) Segmented(listOf(true, false), pretty, { pretty = it }, { if (it) "Formatted" else "Raw" }, Modifier.fillMaxWidth())
        SelectionContainer(
            Modifier.fillMaxWidth().heightIn(max = 520.dp).lightGlass(RoundedCornerShape(18.dp)).verticalScroll(rememberScrollState()),
        ) {
            Text(
                if (pretty && json != null) json else text,
                style = Type.MonoSmall,
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(16.dp),
            )
        }
    }
}
