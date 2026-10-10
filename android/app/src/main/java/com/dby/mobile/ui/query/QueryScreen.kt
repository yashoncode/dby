package com.dby.mobile.ui.query

import android.content.Context
import android.net.Uri
import android.widget.Toast
import com.dby.mobile.data.fuzzySearch
import com.dby.mobile.ui.common.SearchField
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dby.core.Cell
import com.dby.core.Filter
import com.dby.core.FilterOp
import com.dby.core.Sort
import com.dby.mobile.DbyApp
import com.dby.mobile.bottomSpace
import com.dby.mobile.data.count
import com.dby.mobile.data.csv
import com.dby.mobile.data.display
import com.dby.mobile.data.json
import com.dby.mobile.data.rawText
import com.dby.mobile.data.rowJson
import com.dby.mobile.topSpace
import com.dby.mobile.ui.DbyIcons
import com.dby.mobile.ui.SqlTransformation
import com.dby.mobile.ui.common.Action
import com.dby.mobile.ui.common.ActionSheet
import com.dby.mobile.ui.common.Busy
import com.dby.mobile.ui.common.Chip
import com.dby.mobile.ui.common.ConfirmSheet
import com.dby.mobile.ui.common.EmptyState
import com.dby.mobile.ui.common.EnvBadge
import com.dby.mobile.ui.common.FieldRow
import com.dby.mobile.ui.common.Hairline
import com.dby.mobile.ui.common.Input
import com.dby.mobile.ui.common.LargeTitle
import com.dby.mobile.ui.common.ListRow
import com.dby.mobile.ui.common.PasswordSheet
import com.dby.mobile.ui.common.PrimaryButton
import com.dby.mobile.ui.common.ProblemBanner
import com.dby.mobile.ui.common.RoundButton
import com.dby.mobile.ui.common.SecondaryButton
import com.dby.mobile.ui.common.SectionHeader
import com.dby.mobile.ui.common.Segmented
import com.dby.mobile.ui.common.Sheet
import com.dby.mobile.ui.common.SheetHeader
import com.dby.mobile.ui.common.TopBar
import com.dby.mobile.ui.common.copyText
import com.dby.mobile.ui.common.gridItems
import com.dby.mobile.ui.common.rememberHaptics
import com.dby.mobile.ui.glass.GlassHost
import com.dby.mobile.ui.glass.lightGlass
import com.dby.mobile.ui.glass.liquidGlass
import com.dby.mobile.ui.nav.Screen
import com.dby.mobile.ui.table.symbol
import com.dby.mobile.ui.theme.Dby
import com.dby.mobile.ui.theme.GeistMono
import com.dby.mobile.ui.theme.LocalAccent
import com.dby.mobile.ui.theme.Type
import com.dby.mobile.ui.theme.colors
import com.kyant.backdrop.Backdrop

@Composable
fun QueryScreen(app: DbyApp) {
    val nav = app.nav
    val sessions = app.sessions
    val model = nav.model(Screen.Query) { QueryModel(sessions, app.prefs) }
    var picking by remember { mutableStateOf(false) }
    var pickingTable by remember { mutableStateOf(false) }
    var savedOpen by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var viewingRow by remember { mutableStateOf<Int?>(null) }
    var exporting by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val saveCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val r = model.result
        if (uri != null && r != null) writeFile(context, uri, csv(r.columns, r.rows))
    }
    val saveJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val r = model.result
        if (uri != null && r != null) writeFile(context, uri, json(r.columns, r.rows))
    }
    LaunchedEffect(sessions.activeId, model.session) { model.rebuild() }
    BackHandler(enabled = model.showResult) { model.showResult = false }
    GlassHost(
        overlay = { backdrop ->
            if (!model.showResult) RunBar(model, backdrop, { saving = true }, Modifier.align(Alignment.BottomCenter))
            viewingRow?.let { ResultRowSheet(model, it, backdrop) { viewingRow = null } }
            if (exporting) {
                ActionSheet(
                    backdrop,
                    "Export ${count(model.result?.rows?.size?.toLong() ?: 0)} rows",
                    listOf(
                        Action("Save as CSV", DbyIcons.Export) { exporting = false; saveCsv.launch("query-result.csv") },
                        Action("Save as JSON", DbyIcons.Export) { exporting = false; saveJson.launch("query-result.json") },
                    ),
                ) { exporting = false }
            }
            model.connector.asking?.let { c -> PasswordSheet(backdrop, c.name, model.connector::submit, model.connector::dismiss) }
            model.confirming?.let { text ->
                ConfirmSheet(
                    backdrop,
                    title = "Run this change?",
                    message = "It changes data on ${model.connection?.name ?: "the server"}.",
                    sql = text,
                    confirm = "Run it",
                    danger = model.connection?.env == com.dby.core.Env.PROD,
                    onConfirm = { model.execute(text) },
                    onDismiss = { model.confirming = null },
                )
            }
            if (picking) ConnectionPicker(app, model, backdrop) { picking = false }
            if (pickingTable) TablePicker(model, backdrop) { pickingTable = false }
            if (savedOpen) SavedSheet(model, backdrop) { savedOpen = false }
            if (saving) SaveSheet(model, backdrop) { saving = false }
        },
    ) {
        if (model.showResult) {
            ResultView(model, onRow = { viewingRow = it }, onExport = { exporting = true })
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = topSpace(), bottom = bottomSpace(84.dp)),
            ) {
                TopBar {
                    ConnectionButton(model) { picking = true }
                    Box(Modifier.weight(1f))
                    RoundButton(DbyIcons.Bookmark, "Saved queries", { model.refreshSaved(); savedOpen = true })
                }
                LargeTitle("Query", Modifier.padding(top = 8.dp, bottom = 12.dp))
                Segmented(Mode.entries, model.mode, { model.mode = it; model.rebuild() }, { if (it == Mode.BUILDER) "Builder" else "SQL" }, Modifier.padding(horizontal = 16.dp).fillMaxWidth())
                (model.connector.problem ?: model.problem)?.let { ProblemBanner(it, modifier = Modifier.padding(vertical = 10.dp)) }
                if (model.connector.busy) Busy("Connecting…")
                when {
                    model.connection == null -> EmptyState("Pick a connection", "Queries run against the connection you choose here.", "Choose connection") { picking = true }
                    model.session == null -> EmptyState("Not connected", "Connect to ${model.connection?.name} to run queries.", "Connect") { model.connection?.let(model::choose) }
                    model.mode == Mode.BUILDER -> Builder(model) { pickingTable = true }
                    else -> SqlEditor(model)
                }
            }
        }
    }
}

@Composable
private fun ConnectionButton(model: QueryModel, onClick: () -> Unit) {
    val c = model.connection
    Row(
        Modifier.clip(CircleShape).background(Dby.FillStrong).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (c != null) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (model.session != null) Dby.Success else Dby.Faint))
            Text(c.name, style = Type.Secondary.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(150.dp))
            EnvBadge(c.env)
        } else {
            Text("Choose connection", style = Type.Secondary.copy(fontWeight = FontWeight.SemiBold))
        }
        Icon(DbyIcons.ChevronDown, contentDescription = null, tint = Dby.Secondary, modifier = Modifier.size(16.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Builder(model: QueryModel, onPickTable: () -> Unit) {
    val info = model.tableInfo
    Column(Modifier.padding(top = 16.dp)) {
        Row(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().lightGlass(RoundedCornerShape(22.dp)).clickable(onClick = onPickTable).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Table", style = Type.Body, modifier = Modifier.weight(1f))
            Text(model.table ?: "Choose", style = Type.Mono.copy(fontWeight = FontWeight.SemiBold), color = if (model.table == null) LocalAccent.current else Dby.Fg)
            Icon(DbyIcons.ChevronDown, contentDescription = null, tint = Dby.Secondary, modifier = Modifier.padding(start = 6.dp).size(16.dp))
        }
        if (info == null) return
        val names = info.columns.map { it.name }
        SectionHeader("Fields") { Text("${model.fields.size.takeIf { it > 0 } ?: names.size} of ${names.size} shown", style = Type.Secondary, color = Dby.Secondary, modifier = Modifier.padding(end = 8.dp)) }
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (name in names) {
                val on = model.fields.isEmpty() || name in model.fields
                Chip(name, on, {
                    if (model.fields.isEmpty()) model.fields.addAll(names)
                    if (name in model.fields) model.fields.remove(name) else model.fields.add(name)
                    if (model.fields.size == names.size) model.fields.clear()
                    model.rebuild()
                })
            }
        }
        SectionHeader("Filters") {
            Text(
                if (model.matchAll) "Match all" else "Match any",
                style = Type.Secondary,
                color = LocalAccent.current,
                modifier = Modifier.clip(CircleShape).clickable { model.matchAll = !model.matchAll; model.rebuild() }.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            model.filters.forEachIndexed { i, f -> FilterLine(model, i, f, names) }
            SecondaryButton("Add filter", {
                model.filters.add(Filter(names.first(), FilterOp.EQ, ""))
            }, Modifier.fillMaxWidth(), icon = DbyIcons.Plus)
        }
        SectionHeader("Sort and limit")
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Picker(
                label = "Sort by",
                value = model.sort?.let { "${it.column} · ${if (it.descending) "newest" else "oldest"}" } ?: "Key order",
                options = listOf("Key order") + names.flatMap { listOf("$it ↓", "$it ↑") },
                modifier = Modifier.weight(1.4f),
            ) { choice ->
                model.sort = if (choice == "Key order") null else Sort(choice.dropLast(2), choice.endsWith("↓"))
                model.rebuild()
            }
            Picker("Limit", "${model.limit} rows", listOf("50", "100", "500", "1000"), Modifier.weight(1f)) {
                model.limit = it.toInt()
                model.rebuild()
            }
        }
    }
}

@Composable
private fun FilterLine(model: QueryModel, index: Int, filter: Filter, names: List<String>) {
    Row(
        Modifier.fillMaxWidth().lightGlass(RoundedCornerShape(18.dp)).padding(start = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Dropdown(filter.column, names, Modifier.width(120.dp)) { model.filters[index] = filter.copy(column = it); model.rebuild() }
        Dropdown(filter.op.symbol(), FilterOp.entries.map { it.symbol() }, Modifier.width(76.dp)) { s ->
            model.filters[index] = filter.copy(op = FilterOp.entries.first { it.symbol() == s })
            model.rebuild()
        }
        if (filter.op != FilterOp.IS_NULL && filter.op != FilterOp.IS_NOT_NULL) {
            Input(filter.value, { model.filters[index] = filter.copy(value = it); model.rebuild() }, Modifier.weight(1f), placeholder = "value", mono = true)
        } else {
            Box(Modifier.weight(1f))
        }
        RoundButton(DbyIcons.Close, "Remove ${filter.column} filter", { model.filters.removeAt(index); model.rebuild() })
    }
}

@Composable
private fun Dropdown(value: String, options: List<String>, modifier: Modifier, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.clip(RoundedCornerShape(12.dp)).clickable { open = true }.padding(horizontal = 10.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(value, style = Type.Mono.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Icon(DbyIcons.ChevronDown, contentDescription = null, tint = Dby.Secondary, modifier = Modifier.size(14.dp))
        }
        DropdownMenu(open, { open = false }, containerColor = Dby.Solid, shape = RoundedCornerShape(16.dp)) {
            for (option in options) DropdownMenuItem(text = { Text(option, style = Type.Mono) }, onClick = { open = false; onPick(option) })
        }
    }
}

@Composable
private fun Picker(label: String, value: String, options: List<String>, modifier: Modifier, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Column(
            Modifier.fillMaxWidth().lightGlass(RoundedCornerShape(18.dp)).clickable { open = true }.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(label, style = Type.Caption, color = Dby.Secondary)
            Text(value, style = Type.Mono.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(open, { open = false }, containerColor = Dby.Solid, shape = RoundedCornerShape(16.dp)) {
            for (option in options) DropdownMenuItem(text = { Text(option, style = Type.Mono) }, onClick = { open = false; onPick(option) })
        }
    }
}

/** The SQL editor: coloured as it is typed, with line numbers. */
@Composable
private fun SqlEditor(model: QueryModel) {
    val lines = model.sql.text.count { it == '\n' } + 1
    Column(Modifier.padding(top = 16.dp)) {
        Row(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().heightIn(min = 260.dp).lightGlass(RoundedCornerShape(22.dp)).padding(vertical = 14.dp),
        ) {
            Text(
                (1..lines).joinToString("\n"),
                style = Type.MonoCode,
                color = Dby.Faint,
                modifier = Modifier.padding(start = 14.dp, end = 10.dp),
            )
            BasicTextField(
                value = model.sql,
                onValueChange = model::editSql,
                textStyle = Type.MonoCode.copy(color = Dby.Fg),
                cursorBrush = SolidColor(LocalAccent.current),
                visualTransformation = SqlTransformation,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(end = 14.dp),
                decorationBox = { inner ->
                    Box {
                        if (model.sql.text.isEmpty()) Text("SELECT * FROM …", style = Type.MonoCode, color = Dby.Faint)
                        inner()
                    }
                },
            )
        }
        Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(DbyIcons.Info, contentDescription = null, tint = Dby.Secondary, modifier = Modifier.size(16.dp))
            Text(
                if (model.sqlEdited) "Edited by hand." else "Written from your fields and filters. Edit it here for anything the builder can't do.",
                style = Type.Caption,
                color = Dby.Secondary,
                modifier = Modifier.weight(1f),
            )
            if (model.sqlEdited && model.table != null) {
                Text("Reset to builder", style = Type.Caption.copy(fontWeight = FontWeight.SemiBold), color = LocalAccent.current, modifier = Modifier.clickable { model.resetToBuilder() })
            }
        }
    }
}

/** The floating bar above the tab pill: save on the left, run (or cancel) on the right. */
@Composable
private fun RunBar(model: QueryModel, backdrop: Backdrop, onSave: () -> Unit, modifier: Modifier) {
    if (model.session == null) return
    Row(
        modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 100.dp).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(52.dp).liquidGlass(backdrop).clickable(role = Role.Button, onClick = onSave),
            contentAlignment = Alignment.Center,
        ) { Icon(DbyIcons.Bookmark, contentDescription = "Save query", tint = Dby.Fg, modifier = Modifier.size(20.dp)) }
        if (model.running) {
            SecondaryButton("Cancel", model::cancel, Modifier.weight(1f), icon = DbyIcons.Close, tint = Dby.Danger)
        } else {
            PrimaryButton(if (model.mode == Mode.BUILDER) "Show rows" else "Run", model::run, Modifier.weight(1f), enabled = model.sql.text.isNotBlank(), icon = DbyIcons.Play)
        }
    }
}

@Composable
private fun ResultView(model: QueryModel, onRow: (Int) -> Unit, onExport: () -> Unit) {
    val result = model.result ?: return
    val hScroll = rememberScrollState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = topSpace(), bottom = bottomSpace())) {
        item {
            TopBar(onBack = { model.showResult = false }) {
                RoundButton(DbyIcons.Export, "Export results", onExport, enabled = result.rows.isNotEmpty())
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Result", style = Type.LargeTitle)
                val summary = if (result.columns.isEmpty()) {
                    "${count(result.affectedRows.toLong())} rows changed"
                } else {
                    "${count(result.rows.size.toLong())} rows" + if (result.truncated) " (stopped at 1,000)" else ""
                }
                Text("$summary · ${result.elapsedMs} ms", style = Type.Secondary, color = Dby.Secondary)
                Text(model.ranSql, style = Type.MonoSmall, color = Dby.Tertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (result.columns.isNotEmpty()) gridItems(result.columns, result.rows, hScroll, onRow = onRow)
    }
}

/** One result row as name / value pairs: tap a field to copy its value, long-press for "name: value". */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultRowSheet(model: QueryModel, index: Int, backdrop: Backdrop, onDismiss: () -> Unit) {
    val result = model.result ?: return
    val row = result.rows.getOrNull(index) ?: return
    val columns = result.columns
    val context = LocalContext.current
    val haptic = rememberHaptics()
    var menuOpen by remember { mutableStateOf(false) }
    Sheet(backdrop, onDismiss) {
        SheetHeader("Row ${index + 1}", subtitle = "of ${count(result.rows.size.toLong())} · ${columns.size} fields", onClose = onDismiss) {
            RoundButton(DbyIcons.Copy, "Copy row", { menuOpen = true })
        }
        Column(Modifier.weight(1f, fill = false).fillMaxWidth().lightGlass(RoundedCornerShape(20.dp)).verticalScroll(rememberScrollState())) {
            columns.forEachIndexed { i, column ->
                if (i > 0) Hairline()
                val cell = row.getOrElse(i) { Cell.Null }
                val value = cell.rawText() ?: cell.display()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { copyText(context, column.name, value) },
                            onLongClick = { haptic(HapticFeedbackType.LongPress); copyText(context, column.name, "${column.name}: $value") },
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        var expanded by remember(index, i) { mutableStateOf(false) }
                        var long by remember(index, i) { mutableStateOf(false) }
                        Text(column.name, style = Type.Caption.copy(fontFamily = GeistMono, fontWeight = FontWeight.SemiBold), color = Dby.Secondary)
                        Text(
                            cell.display(),
                            style = Type.Mono,
                            color = if (cell is Cell.Null) Dby.Faint else Dby.Fg,
                            maxLines = if (expanded) Int.MAX_VALUE else 3,
                            overflow = TextOverflow.Ellipsis,
                            onTextLayout = { if (!expanded) long = it.hasVisualOverflow },
                        )
                        if (long) {
                            Text(
                                if (expanded) "Collapse" else "Expand",
                                style = Type.Caption.copy(fontWeight = FontWeight.SemiBold),
                                color = LocalAccent.current,
                                modifier = Modifier.clip(CircleShape).clickable(role = Role.Button) { expanded = !expanded }.padding(vertical = 6.dp),
                            )
                        }
                    }
                    Icon(DbyIcons.Copy, contentDescription = null, tint = Dby.Faint, modifier = Modifier.size(16.dp))
                }
            }
        }
        Text("Tap a field to copy its value. Long-press to copy its name and value.", style = Type.Caption, color = Dby.Secondary)
    }
    if (menuOpen) {
        ActionSheet(
            backdrop,
            null,
            listOf(
                Action("Copy row as JSON", DbyIcons.Copy) { menuOpen = false; copyText(context, "Row ${index + 1}", rowJson(columns, row).toString(2)) },
                Action("Copy row as text", DbyIcons.Copy) {
                    menuOpen = false
                    copyText(context, "Row ${index + 1}", columns.indices.joinToString("\n") { "${columns[it].name}: ${row.getOrElse(it) { Cell.Null }.display()}" })
                },
            ),
        ) { menuOpen = false }
    }
}

/** Writes text to the file the person picked in the system's save dialog. */
private fun writeFile(context: Context, uri: Uri, text: String) {
    val saved = runCatching { context.contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) } }.isSuccess
    Toast.makeText(context, if (saved) "Saved" else "Couldn't save that file.", Toast.LENGTH_SHORT).show()
}

@Composable
private fun ConnectionPicker(app: DbyApp, model: QueryModel, backdrop: Backdrop, onDismiss: () -> Unit) {
    Sheet(backdrop, onDismiss) {
        Text("Run against", style = Type.Title)
        Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).lightGlass(RoundedCornerShape(22.dp)).verticalScroll(rememberScrollState())) {
            app.sessions.connections.forEachIndexed { i, c ->
                if (i > 0) Hairline()
                val (fg, _) = c.env.colors()
                ListRow(
                    c.name,
                    subtitle = if (app.sessions.open.containsKey(c.id)) "Connected" else "${c.host} · ${c.database}",
                    onClick = { onDismiss(); model.choose(c) },
                    titleExtra = { EnvBadge(c.env) },
                    trailing = { if (c.id == app.sessions.activeId) Icon(DbyIcons.Check, null, tint = fg) },
                )
            }
        }
    }
}

@Composable
private fun TablePicker(model: QueryModel, backdrop: Backdrop, onDismiss: () -> Unit) {
    Sheet(backdrop, onDismiss) {
        Text("Table", style = Type.Title)
        var search by remember { mutableStateOf("") }
        val all = model.schema?.tables.orEmpty()
        if (all.size > 8) SearchField(search, { search = it }, "Search tables", inset = 0.dp)
        val tables = fuzzySearch(all, search) { listOf(it.name) }
        if (tables.isEmpty()) Text("No table matches \"${search.trim()}\".", style = Type.Secondary, color = Dby.Secondary)
        else Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).lightGlass(RoundedCornerShape(22.dp)).verticalScroll(rememberScrollState())) {
            tables.forEachIndexed { i, t ->
                if (i > 0) Hairline()
                ListRow(t.name, subtitle = if (t.isView) "view" else "${t.columns.size} columns", titleStyle = Type.Mono, onClick = { model.pickTable(t.name); onDismiss() })
            }
        }
    }
}

@Composable
private fun SavedSheet(model: QueryModel, backdrop: Backdrop, onDismiss: () -> Unit) {
    Sheet(backdrop, onDismiss) {
        Text("Saved queries", style = Type.Title)
        if (model.saved.isEmpty()) {
            Text("Nothing saved yet. Use the bookmark button next to Run.", style = Type.Secondary, color = Dby.Secondary)
        } else {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).lightGlass(RoundedCornerShape(22.dp)).verticalScroll(rememberScrollState())) {
                model.saved.forEachIndexed { i, q ->
                    if (i > 0) Hairline()
                    ListRow(
                        q.title,
                        subtitle = q.sql.replace('\n', ' '),
                        onClick = { model.load(q.sql); onDismiss() },
                        onLongClick = { model.deleteSaved(q.sql) },
                    )
                }
            }
            Text("Long-press a query to delete it.", style = Type.Caption, color = Dby.Secondary)
        }
    }
}

@Composable
private fun SaveSheet(model: QueryModel, backdrop: Backdrop, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf(model.table?.let { "$it query" } ?: "") }
    Sheet(backdrop, onDismiss) {
        Text("Save query", style = Type.Title)
        Column(Modifier.fillMaxWidth().lightGlass(RoundedCornerShape(22.dp))) {
            FieldRow("Title", title, { title = it }, "Late shipments", mono = false)
        }
        PrimaryButton("Save", { model.save(title.trim()); onDismiss() }, Modifier.fillMaxWidth(), enabled = title.isNotBlank() && model.sql.text.isNotBlank())
    }
}
