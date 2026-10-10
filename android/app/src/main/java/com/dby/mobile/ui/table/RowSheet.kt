package com.dby.mobile.ui.table

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dby.core.Cell
import com.dby.core.CellRequest
import com.dby.core.ChangeKind
import com.dby.core.ColumnDef
import com.dby.core.FieldValue
import com.dby.core.RowChange
import com.dby.mobile.data.display
import com.dby.mobile.data.isTrimmed
import com.dby.mobile.data.rawText
import com.dby.mobile.data.rowJson
import com.dby.mobile.ui.DbyIcons
import com.dby.mobile.ui.common.Action
import com.dby.mobile.ui.common.ActionSheet
import com.dby.mobile.ui.common.ConfirmSheet
import com.dby.mobile.ui.common.Hairline
import com.dby.mobile.ui.common.Input
import com.dby.mobile.ui.common.PrimaryButton
import com.dby.mobile.ui.common.ProblemBanner
import com.dby.mobile.ui.common.RoundButton
import com.dby.mobile.ui.common.SecondaryButton
import com.dby.mobile.ui.common.Sheet
import com.dby.mobile.ui.common.SheetHeader
import com.dby.mobile.ui.common.SqlBox
import com.dby.mobile.ui.common.copyText
import com.dby.mobile.ui.glass.lightGlass
import com.dby.mobile.ui.theme.Dby
import com.dby.mobile.ui.theme.LocalAccent
import com.dby.mobile.ui.theme.Type
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Types an edit field never writes: binary values only reach the phone as a preview. */
private val BINARY = setOf("binary", "varbinary", "tinyblob", "blob", "mediumblob", "longblob", "geometry", "point", "linestring", "polygon")

/**
 * The Edit row sheet's state (spec §7, §8): one text and one NULL flag per column. The change
 * sent is only the columns that differ; a value the page trimmed stays read-only until its
 * full value is loaded, so a preview is never written back.
 */
class RowEditor(val model: TableModel, val rowIndex: Int?) {
    val defs: List<ColumnDef?> = model.defs
    private val original: List<Cell>? = rowIndex?.let { model.rows[it] }
    val values = mutableStateListOf<Cell?>().apply { addAll(original ?: model.columns.map { null }) }
    val texts = mutableStateListOf<String>().apply { addAll(original?.map { it.rawText().orEmpty() } ?: model.columns.map { "" }) }
    val nulls = mutableStateListOf<Boolean>().apply { addAll(original?.map { it is Cell.Null } ?: model.columns.map { false }) }
    var busy by mutableStateOf(false)
    var problem by mutableStateOf<Throwable?>(null)
    var deleting by mutableStateOf(false)
    var menuOpen by mutableStateOf(false)
    val isInsert = rowIndex == null

    fun editable(i: Int): Boolean {
        val def = defs[i] ?: return false
        if (def.dataType in BINARY || values[i] is Cell.Bytes) return false
        if (!isInsert && def.pkSeq != null) return false
        return values[i]?.isTrimmed() != true
    }

    /** Compared with the value as last read: the page's cell, or the full value once loaded. */
    fun changed(i: Int): Boolean {
        val before = values[i] ?: return nulls[i] || texts[i].isNotEmpty()
        return nulls[i] != (before is Cell.Null) || (!nulls[i] && texts[i] != before.rawText().orEmpty())
    }

    /** The primary-key values of the row being edited, in key order. */
    fun key(): List<Cell> {
        val row = original ?: return emptyList()
        return defs.withIndex().filter { it.value?.pkSeq != null }.sortedBy { it.value!!.pkSeq }.map { row[it.index] }
    }

    fun change(): RowChange? {
        val fields = model.columns.indices
            .filter { editable(it) && changed(it) }
            .map { FieldValue(model.columns[it].name, if (nulls[it]) null else texts[it]) }
        if (fields.isEmpty()) return null
        return RowChange(model.table, if (isInsert) ChangeKind.INSERT else ChangeKind.UPDATE, key(), fields)
    }

    fun preview(change: RowChange?): String? = change?.let { runCatching { model.session?.previewRowChange(it) }.getOrNull() }

    fun loadFull(i: Int) {
        val session = model.session ?: return
        model.scope.launch {
            try {
                val cell = session.fullCell(CellRequest(model.table, model.columns[i].name, key()))
                values[i] = cell
                texts[i] = cell.rawText().orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = e
            }
        }
    }

    fun commit() = apply(change())

    fun delete() = apply(RowChange(model.table, ChangeKind.DELETE, key(), emptyList()))

    private fun apply(change: RowChange?) {
        val session = model.session ?: return
        if (change == null) return
        model.scope.launch {
            busy = true
            problem = null
            try {
                session.applyRowChange(change)
                model.editor = null
                model.reloadCurrent()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = e
            } finally {
                busy = false
            }
        }
    }

    fun json(): String = original?.let { rowJson(model.columns, it).toString(2) } ?: "{}"

    /** A new-row editor holding this row's values, without its key. */
    fun duplicate(): RowEditor = RowEditor(model, null).also { copy ->
        for (i in model.columns.indices) {
            if (defs[i]?.pkSeq == null && editable(i)) {
                copy.texts[i] = texts[i]
                copy.nulls[i] = nulls[i]
            }
        }
    }
}

@Composable
fun RowSheet(editor: RowEditor, backdrop: Backdrop) {
    val model = editor.model
    val context = LocalContext.current
    val close = { model.editor = null }
    // Keyed: Duplicate swaps in a new editor at the same call site.
    val change by remember(editor) { derivedStateOf { editor.change() } }
    val sql = editor.preview(change)
    Sheet(backdrop, close) {
        val keyText = editor.key().joinToString(", ") { it.display() }
        SheetHeader(
            if (editor.isInsert) "New row" else "Edit row",
            subtitle = if (editor.isInsert) model.table else "${model.table} · ${keyText.ifEmpty { "row ${editor.rowIndex!! + 1}" }}",
            onClose = close,
        ) {
            if (!editor.isInsert) RoundButton(DbyIcons.More, "Row actions: copy as JSON, duplicate, delete", { editor.menuOpen = true })
        }
        Column(
            Modifier.weight(1f, fill = false).fillMaxWidth().lightGlass(RoundedCornerShape(20.dp)).verticalScroll(rememberScrollState()),
        ) {
            model.columns.forEachIndexed { i, column ->
                if (i > 0) Hairline()
                FieldLine(editor, i, column.name)
            }
        }
        if (sql != null) SqlBox(sql, header = "SQL to run", note = if (editor.isInsert) "1 row will be added" else "1 row will change")
        if (model.readOnly) {
            Text("This connection is read-only. Unlock writes on its Explorer screen to save.", style = Type.Secondary, color = Dby.Danger)
        }
        editor.problem?.let { ProblemBanner(it, modifier = Modifier.padding(0.dp)) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Discard", close, Modifier.weight(1f))
            PrimaryButton(
                if (editor.isInsert) "Add row" else "Commit change",
                editor::commit,
                Modifier.weight(1.4f),
                enabled = change != null && !model.readOnly,
                icon = DbyIcons.Check,
                busy = editor.busy,
            )
        }
    }
    if (editor.menuOpen) {
        ActionSheet(
            backdrop,
            null,
            listOf(
                Action("Copy as JSON", DbyIcons.Copy) { editor.menuOpen = false; copyText(context, model.table, editor.json()) },
                Action("Duplicate", DbyIcons.Plus) { editor.menuOpen = false; model.editor = editor.duplicate() },
                Action("Delete row", DbyIcons.Trash, danger = true) { editor.menuOpen = false; editor.deleting = true },
            ),
        ) { editor.menuOpen = false }
    }
    if (editor.deleting) {
        val delete = RowChange(model.table, ChangeKind.DELETE, editor.key(), emptyList())
        ConfirmSheet(
            backdrop,
            title = "Delete this row?",
            sql = editor.preview(delete),
            confirm = "Delete",
            danger = true,
            busy = editor.busy,
            problem = editor.problem,
            onConfirm = editor::delete,
            onDismiss = { editor.deleting = false },
        )
    }
}

@Composable
private fun FieldLine(editor: RowEditor, i: Int, name: String) {
    val def = editor.defs[i]
    val accent = LocalAccent.current
    val editable = editor.editable(i) && !editor.model.readOnly
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.width(128.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(name, style = Type.Mono.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (editor.changed(i) && !editor.isInsert) {
                val was = editor.values[i]?.display() ?: ""
                Text("Edited · was $was", style = Type.Caption.copy(fontWeight = FontWeight.SemiBold), color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                val type = listOfNotNull(def?.columnType ?: def?.dataType, if (def?.pkSeq != null) "key" else null, if (def?.nullable == true) "nullable" else null)
                Text(type.joinToString(" · "), style = Type.Caption, color = Dby.Tertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        val cell = editor.values[i]
        when {
            cell != null && cell.isTrimmed() && cell !is Cell.Bytes -> Text(
                "Load full value",
                style = Type.Secondary.copy(fontWeight = FontWeight.SemiBold),
                color = accent,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f).clickable { editor.loadFull(i) }.padding(vertical = 12.dp, horizontal = 8.dp),
            )
            !editable -> Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                Icon(DbyIcons.Lock, contentDescription = "Read only", tint = Dby.Faint, modifier = Modifier.size(14.dp))
                Text(cell?.display() ?: "", style = Type.Mono, color = Dby.Tertiary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 8.dp))
            }
            def != null && def.enumValues.isNotEmpty() -> EnumPicker(editor, i, def.enumValues, Modifier.weight(1f))
            editor.nulls[i] -> Text(
                "NULL",
                style = Type.Mono.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                color = Dby.Faint,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f).clickable { editor.nulls[i] = false }.padding(vertical = 12.dp, horizontal = 8.dp),
            )
            else -> Input(
                editor.texts[i],
                { editor.texts[i] = it },
                Modifier.weight(1f).padding(end = 8.dp),
                placeholder = if (editor.isInsert) "default" else "",
                mono = true,
                align = TextAlign.End,
            )
        }
        if (editable && def?.nullable == true) {
            Text(
                "NULL",
                style = Type.Caption.copy(fontWeight = FontWeight.Bold),
                color = if (editor.nulls[i]) Dby.Bg else Dby.Tertiary,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (editor.nulls[i]) Dby.Fg else Dby.Fill)
                    .clickable { editor.nulls[i] = !editor.nulls[i] }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun EnumPicker(editor: RowEditor, i: Int, options: List<String>, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    val accent = LocalAccent.current
    Box(modifier, contentAlignment = Alignment.CenterEnd) {
        Row(
            Modifier.clip(RoundedCornerShape(12.dp)).clickable { open = true }.padding(horizontal = 10.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(editor.texts[i].ifEmpty { "choose" }, style = Type.Mono.copy(fontWeight = FontWeight.SemiBold), color = if (editor.changed(i)) accent else Dby.Fg)
            Icon(DbyIcons.ChevronDown, contentDescription = null, tint = Dby.Secondary, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(open, { open = false }, containerColor = Dby.Solid, shape = RoundedCornerShape(16.dp)) {
            for (option in options) {
                DropdownMenuItem(
                    text = { Text(option, style = Type.Mono) },
                    onClick = {
                        editor.texts[i] = option
                        editor.nulls[i] = false
                        open = false
                    },
                )
            }
        }
    }
}
