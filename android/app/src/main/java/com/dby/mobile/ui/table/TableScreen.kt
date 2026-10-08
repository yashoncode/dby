package com.dby.mobile.ui.table

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dby.core.Cell
import com.dby.core.ColumnDef
import com.dby.core.ColumnOut
import com.dby.core.Filter
import com.dby.core.FilterOp
import com.dby.core.Sort
import com.dby.mobile.DbyApp
import com.dby.mobile.bottomSpace
import com.dby.mobile.data.bytes
import com.dby.mobile.data.count
import com.dby.mobile.data.csv
import com.dby.mobile.data.display
import com.dby.mobile.topSpace
import com.dby.mobile.ui.DbyIcons
import com.dby.mobile.ui.common.Busy
import com.dby.mobile.ui.common.Chip
import com.dby.mobile.ui.common.EmptyState
import com.dby.mobile.ui.common.Hairline
import com.dby.mobile.ui.common.Input
import com.dby.mobile.ui.common.ListRow
import com.dby.mobile.ui.common.PrimaryButton
import com.dby.mobile.ui.common.ProblemBanner
import com.dby.mobile.ui.common.RoundButton
import com.dby.mobile.ui.common.Segmented
import com.dby.mobile.ui.common.Sheet
import com.dby.mobile.ui.common.TopBar
import com.dby.mobile.ui.common.gridItems
import com.dby.mobile.ui.glass.GlassHost
import com.dby.mobile.ui.glass.frostedGlass
import com.dby.mobile.ui.glass.lightGlass
import com.dby.mobile.ui.nav.Screen
import com.dby.mobile.ui.theme.Dby
import com.dby.mobile.ui.theme.LocalAccent
import com.dby.mobile.ui.theme.Type
import com.kyant.backdrop.Backdrop
import com.kyant.shapes.RoundedRectangle

private val TEMPORAL = setOf("date", "datetime", "timestamp", "time", "year")
private val AMOUNT = setOf("decimal", "numeric", "float", "double")
private val TEXT = setOf("char", "varchar", "tinytext", "text", "mediumtext", "longtext")

/** Which columns a row card shows, picked from the schema: a title, an amount, a time, a status pill. */
private data class CardLayout(val title: Int, val amount: Int?, val time: Int?, val pill: Int?, val key: Int?)

private fun cardLayout(defs: List<ColumnDef?>, keys: Set<Int>): CardLayout {
    fun first(test: (ColumnDef) -> Boolean) = defs.indices.firstOrNull { it !in keys && defs[it]?.let(test) == true }
    return CardLayout(
        title = first { it.dataType in TEXT } ?: 0,
        amount = first { it.dataType in AMOUNT },
        time = first { it.dataType in TEMPORAL },
        pill = first { it.enumValues.isNotEmpty() },
        key = keys.minOrNull(),
    )
}

/** Status pills cycle through the canvas's four tints by value. */
private val PILLS = listOf(
    Color(0x295AC8FA) to Color(0xFF9BDFFF),
    Color(0x2EFF9F0A) to Color(0xFFFFC56B),
    Color(0x2930D158) to Color(0xFF86E8A0),
    Color(0x1AFFFFFF) to Color(0xB8FFFFFF),
)

@Composable
fun TableScreen(app: DbyApp, connectionId: String, table: String) {
    val nav = app.nav
    val model = nav.model(Screen.Table(connectionId, table)) { TableModel(connectionId, table, app.sessions, app.prefs) }
    val context = LocalContext.current
    GlassHost(
        overlay = { backdrop ->
            if (!nav.sheetOpen) PagerBar(model, backdrop, Modifier.align(Alignment.BottomCenter))
            model.filtering?.let { op -> FilterSheet(model, op, backdrop) { model.filtering = null } }
            if (model.sorting) SortSheet(model, backdrop)
            model.editor?.let { RowSheet(it, backdrop) }
            model.viewing?.let { (row, column) -> CellViewer(model, row, column, backdrop) { model.viewing = null } }
        },
    ) {
        val hScroll = rememberScrollState()
        val defs = model.defs
        val keys = model.keyColumns
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = topSpace(), bottom = bottomSpace(80.dp))) {
            item {
                TopBar(onBack = { nav.back() }) {
                    RoundButton(DbyIcons.Search, "Search in table", { model.filtering = FilterOp.CONTAINS })
                    RoundButton(DbyIcons.Export, "Export rows", { shareCsv(context, table, csv(model.columns, model.rows)) }, enabled = model.rows.isNotEmpty())
                    RoundButton(DbyIcons.Plus, "Insert row", model::insert, enabled = !model.readOnly && model.info?.isView == false)
                }
            }
            item {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(table, style = Type.MonoTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val info = model.info
                    val details = listOfNotNull(
                        model.database,
                        info?.rowsEstimate?.let { "~${count(it.toLong())} rows" },
                        info?.bytes?.let { bytes(it.toLong()) },
                        if (model.readOnly) "read-only" else null,
                    )
                    Text(details.joinToString(" · "), style = Type.Secondary, color = Dby.Secondary)
                }
            }
            item {
                LazyRow(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Facet filters show as the selected facet chip instead.
                    val shown = model.filters.withIndex().filterNot { it.value.column == model.facetColumn?.name && it.value.op == FilterOp.EQ }
                    items(shown) { (i, f) ->
                        Chip(f.label(), selected = false, onClick = { model.removeFilter(i) }, icon = DbyIcons.Filter, onClose = { model.removeFilter(i) })
                    }
                    item { Chip("Filter", selected = false, onClick = { model.filtering = FilterOp.EQ }, icon = DbyIcons.Plus) }
                }
            }
            model.facetColumn?.let { facet ->
                item {
                    val counts = model.facets?.associate { it.value to it.count }
                    LazyRow(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            val all = counts?.values?.sum()?.toLong()
                            Chip("All", model.facetValue == null, { model.pickFacet(null) }, count = all?.let { count(it) })
                        }
                        items(facet.enumValues) { value ->
                            Chip(value, model.facetValue == value, { model.pickFacet(value) }, count = counts?.get(value)?.let { count(it.toLong()) })
                        }
                    }
                }
            }
            item {
                Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.clip(CircleShape).clickable(role = Role.Button) { model.sorting = true }.padding(horizontal = 4.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(DbyIcons.Sort, contentDescription = null, tint = LocalAccent.current, modifier = Modifier.size(18.dp))
                        val sort = model.sort
                        Text(
                            if (sort == null) "Key order" else "${sort.column} ${if (sort.descending) "↓" else "↑"}",
                            style = Type.Secondary.copy(fontWeight = FontWeight.SemiBold),
                            color = LocalAccent.current,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Segmented(listOf(true, false), model.cards, { model.cards = it }, { if (it) "Cards" else "Grid" }, Modifier.size(width = 168.dp, height = 44.dp))
                }
            }
            model.problem?.let { p -> item { ProblemBanner(p, onRetry = model::reload, modifier = Modifier.padding(vertical = 8.dp)) } }
            if (model.loading && model.rows.isEmpty()) item { Busy("Loading rows…") }
            if (!model.loading && model.problem == null && model.rows.isEmpty()) {
                item { EmptyState("No rows", if (model.filters.isEmpty()) "This table is empty." else "Nothing matches these filters.") }
            }
            if (model.cards) {
                cardItems(model, cardLayout(defs, keys))
            } else {
                val sorted = model.sort?.let { s -> model.columns.indexOfFirst { it.name == s.column }.takeIf { it >= 0 }?.let { it to s.descending } }
                gridItems(model.columns, model.rows, hScroll, keys, sorted, onRow = model::edit, onCell = { r, c -> model.viewing = r to c })
            }
        }
    }
}

private fun LazyListScope.cardItems(model: TableModel, layout: CardLayout) {
    itemsIndexed(model.rows, key = { i, _ -> "card-$i" }) { i, row ->
        Column(
            Modifier
                .padding(horizontal = 16.dp, vertical = 5.dp)
                .fillMaxWidth()
                .lightGlass(RoundedCornerShape(22.dp))
                .clickable { model.edit(i) }
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(row.getOrElse(layout.title) { Cell.Null }.display(), style = Type.Body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                layout.amount?.let { Text(row[it].display(), style = Type.Mono.copy(fontWeight = FontWeight.SemiBold)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val sub = listOfNotNull(layout.key?.let { "#${row[it].display()}" }, layout.time?.let { row[it].display() })
                Text(sub.joinToString(" · "), style = Type.Secondary, color = Dby.Secondary, maxLines = 1, modifier = Modifier.weight(1f))
                layout.pill?.let { p ->
                    val value = row[p].display()
                    val (bg, fg) = PILLS[Math.floorMod(value.hashCode(), PILLS.size)]
                    Text(value, style = Type.Caption.copy(fontWeight = FontWeight.SemiBold), color = fg, modifier = Modifier.clip(CircleShape).background(bg).padding(horizontal = 10.dp, vertical = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun PagerBar(model: TableModel, backdrop: Backdrop, modifier: Modifier) {
    Row(
        modifier
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 100.dp)
            .fillMaxWidth()
            .frostedGlass(backdrop, RoundedRectangle(28.dp))
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundButton(DbyIcons.Back, "Previous page", model::previous, enabled = model.pageIndex > 0)
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(model.rangeText, style = Type.Secondary.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
            Text("Tap a row to view or edit", style = Type.Caption, color = Dby.Secondary)
        }
        RoundButton(DbyIcons.Chevron, "Next page", model::next, enabled = model.hasNext)
    }
}

@Composable
private fun FilterSheet(model: TableModel, initial: FilterOp, backdrop: Backdrop, onDismiss: () -> Unit) {
    val columns = model.columns.map(ColumnOut::name)
    val firstText = model.defs.indexOfFirst { it?.dataType in TEXT }.takeIf { it >= 0 }
    var column by remember { mutableStateOf(columns.getOrElse(if (initial == FilterOp.CONTAINS) firstText ?: 0 else 0) { "" }) }
    var op by remember { mutableStateOf(initial) }
    var value by remember { mutableStateOf("") }
    val needsValue = op != FilterOp.IS_NULL && op != FilterOp.IS_NOT_NULL
    val def = model.info?.columns?.firstOrNull { it.name == column }
    Sheet(backdrop, onDismiss) {
        Text(if (initial == FilterOp.CONTAINS) "Search in ${model.table}" else "Add filter", style = Type.Title)
        Text("Column", style = Type.Caption, color = Dby.Secondary)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(columns) { name -> Chip(name, name == column, { column = name }) }
        }
        Text("Condition", style = Type.Caption, color = Dby.Secondary)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(FilterOp.entries) { o -> Chip(o.symbol(), o == op, { op = o }) }
        }
        if (needsValue) {
            if (def != null && def.enumValues.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(def.enumValues) { v -> Chip(v, v == value, { value = v }) }
                }
            } else {
                Input(
                    value,
                    { value = it },
                    Modifier.fillMaxWidth().lightGlass(RoundedCornerShape(18.dp)).padding(horizontal = 16.dp),
                    placeholder = def?.columnType ?: "Value",
                    mono = true,
                )
            }
        }
        PrimaryButton(
            "Apply",
            {
                model.addFilter(Filter(column, op, if (needsValue) value else ""))
                onDismiss()
            },
            Modifier.fillMaxWidth(),
            enabled = column.isNotEmpty() && (!needsValue || value.isNotEmpty()),
        )
    }
}

@Composable
private fun SortSheet(model: TableModel, backdrop: Backdrop) {
    var descending by remember { mutableStateOf(model.sort?.descending ?: true) }
    Sheet(backdrop, { model.sorting = false }) {
        Text("Sort by", style = Type.Title)
        Segmented(listOf(true, false), descending, { descending = it }, { if (it) "Descending" else "Ascending" }, Modifier.fillMaxWidth())
        Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).lightGlass(RoundedCornerShape(22.dp)).verticalScroll(rememberScrollState())) {
            ListRow("Key order", onClick = { model.sortBy(null) }, trailing = { if (model.sort == null) Icon(DbyIcons.Check, null, tint = LocalAccent.current) })
            for (c in model.columns) {
                Hairline()
                ListRow(
                    c.name,
                    subtitle = c.typeName,
                    titleStyle = Type.Mono,
                    onClick = { model.sortBy(Sort(c.name, descending)) },
                    trailing = { if (model.sort?.column == c.name) Icon(DbyIcons.Check, null, tint = LocalAccent.current) },
                )
            }
        }
    }
}

private fun shareCsv(context: Context, table: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_SUBJECT, "$table.csv").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, "Export $table"))
}

@Composable
private fun RowSheet(editor: RowEditor, backdrop: Backdrop) = Sheet(backdrop, { editor.model.editor = null }) { Text("Row ${editor.rowIndex}") }

@Composable
private fun CellViewer(model: TableModel, row: Int, column: Int, backdrop: Backdrop, onDismiss: () -> Unit) = Sheet(backdrop, onDismiss) { Text(model.rows[row][column].display()) }
