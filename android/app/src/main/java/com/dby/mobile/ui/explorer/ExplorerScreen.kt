package com.dby.mobile.ui.explorer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dby.core.Env
import com.dby.core.Schema
import com.dby.core.Session
import com.dby.core.TableInfo
import com.dby.core.cachedSchema
import com.dby.mobile.DbyApp
import com.dby.mobile.bottomSpace
import com.dby.mobile.data.Prefs
import com.dby.mobile.data.Sessions
import com.dby.mobile.data.bytes
import com.dby.mobile.data.count
import com.dby.mobile.topSpace
import com.dby.mobile.ui.DbyIcons
import com.dby.mobile.ui.common.Action
import com.dby.mobile.ui.common.ActionSheet
import com.dby.mobile.ui.common.Busy
import com.dby.mobile.ui.common.ConfirmSheet
import com.dby.mobile.ui.common.Connector
import com.dby.mobile.ui.common.EmptyState
import com.dby.mobile.ui.common.EnvBadge
import com.dby.mobile.ui.common.GroupCard
import com.dby.mobile.ui.common.Hairline
import com.dby.mobile.ui.common.ListRow
import com.dby.mobile.ui.common.PasswordSheet
import com.dby.mobile.ui.common.ProblemBanner
import com.dby.mobile.ui.common.RoundButton
import com.dby.mobile.ui.common.SearchField
import com.dby.mobile.ui.common.SectionHeader
import com.dby.mobile.ui.common.Segmented
import com.dby.mobile.ui.common.Sheet
import com.dby.mobile.ui.common.TopBar
import com.dby.mobile.ui.glass.GlassHost
import com.dby.mobile.ui.glass.lightGlass
import com.dby.mobile.ui.nav.Screen
import com.dby.mobile.ui.nav.ScreenModel
import com.dby.mobile.ui.nav.Tab
import com.dby.mobile.ui.theme.Dby
import com.dby.mobile.ui.theme.LocalAccent
import com.dby.mobile.ui.theme.Type
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

enum class Kind { TABLES, VIEWS, ROUTINES }

class ExplorerModel(val id: String, private val sessions: Sessions, private val prefs: Prefs) : ScreenModel() {
    val connector = Connector(sessions, scope)
    val saved get() = sessions.saved(id)
    val session: Session? get() = sessions.open[id]
    var database by mutableStateOf(saved?.database ?: "")
        private set
    var schema by mutableStateOf<Schema?>(saved?.let { cachedSchema(id, it.database) })
        private set
    var loading by mutableStateOf(false)
        private set
    var problem by mutableStateOf<Throwable?>(null)
        private set
    var kind by mutableStateOf(Kind.TABLES)
    var filter by mutableStateOf("")
    var bySize by mutableStateOf(true)
    var pins by mutableStateOf(prefs.pinnedTables(id, database))
        private set
    var databases by mutableStateOf<List<String>?>(null)
        private set
    var readOnly by mutableStateOf(false)
        private set
    var menuOpen by mutableStateOf(false)
    var pickingDatabase by mutableStateOf(false)
    var unlocking by mutableStateOf(false)

    init {
        open()
    }

    /** Shows the cached schema at once, then connects (or reuses the open session) and refreshes it. */
    fun open() {
        val s = saved ?: return
        problem = null
        connector.open(s) { session -> refresh(session) }
    }

    private suspend fun refresh(session: Session) {
        loading = true
        try {
            readOnly = session.isReadOnly()
            database = session.database()
            schema = session.refreshSchema()
            pins = prefs.pinnedTables(id, database)
        } finally {
            loading = false
        }
    }

    fun reload() {
        val session = session ?: return open()
        launchGuarded { refresh(session) }
    }

    fun loadDatabases() {
        val session = session ?: return
        pickingDatabase = true
        launchGuarded { databases = session.databases() }
    }

    fun useDatabase(name: String) {
        val session = session ?: return
        pickingDatabase = false
        schema = cachedSchema(id, name)
        database = name
        pins = prefs.pinnedTables(id, name)
        launchGuarded {
            loading = true
            try {
                schema = session.useDatabase(name)
            } finally {
                loading = false
            }
        }
    }

    fun setWritable(writable: Boolean) {
        val session = session ?: return
        launchGuarded {
            session.setReadOnly(!writable)
            readOnly = !writable
            unlocking = false
        }
    }

    fun togglePin(table: String) {
        pins = if (table in pins) pins - table else pins + table
        prefs.setPinnedTables(id, database, pins)
    }

    fun disconnect(done: () -> Unit) {
        scope.launch {
            sessions.disconnect(id)
            done()
        }
    }

    val tables: List<TableInfo>
        get() {
            val all = schema?.tables.orEmpty().filter { (kind == Kind.VIEWS) == it.isView }
            val needle = filter.trim()
            val matched = if (needle.isEmpty()) all else all.filter { t -> t.name.contains(needle, true) || t.columns.any { it.name.contains(needle, true) } }
            return if (bySize) matched.sortedByDescending { it.bytes ?: 0u } else matched.sortedBy { it.name.lowercase() }
        }

    private fun launchGuarded(block: suspend () -> Unit) {
        scope.launch {
            problem = null
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = e
            }
        }
    }
}

private fun TableInfo.summary(): String {
    if (isView) return "View · ${columns.size} columns"
    val rows = rowsEstimate?.let { "~${count(it.toLong())} rows" } ?: "${columns.size} columns"
    return bytes?.let { "$rows · ${bytes(it.toLong())}" } ?: rows
}

@Composable
fun ExplorerScreen(app: DbyApp, connectionId: String) {
    val nav = app.nav
    val sessions = app.sessions
    val model = nav.model(Screen.Explorer(connectionId)) { ExplorerModel(connectionId, sessions, app.prefs) }
    val saved = model.saved ?: return
    GlassHost(
        overlay = { backdrop ->
            model.connector.asking?.let { c ->
                PasswordSheet(backdrop, c.name, onSubmit = { pw, remember -> model.connector.submit(pw, remember) }, onDismiss = { model.connector.dismiss(); nav.back() })
            }
            if (model.menuOpen) {
                val actions = buildList {
                    add(Action("Refresh schema", DbyIcons.Refresh) { model.menuOpen = false; model.reload() })
                    if (model.session != null && (model.readOnly || saved.env == Env.PROD)) {
                        add(
                            if (model.readOnly) Action("Unlock writes", DbyIcons.Unlock) { model.menuOpen = false; model.unlocking = true }
                            else Action("Lock writes", DbyIcons.Lock) { model.menuOpen = false; model.setWritable(false) },
                        )
                    }
                    add(Action("Edit connection", DbyIcons.Edit) { model.menuOpen = false; nav.push(Screen.EditConnection(connectionId)) })
                    if (model.session != null) add(Action("Disconnect", DbyIcons.Unplug) { model.menuOpen = false; model.disconnect { nav.back() } })
                }
                ActionSheet(backdrop, saved.name, actions) { model.menuOpen = false }
            }
            if (model.unlocking) {
                ConfirmSheet(
                    backdrop,
                    title = "Unlock writes on ${saved.name}?",
                    message = "This is a ${saved.env.name} connection. Edits and write statements will reach the server until you lock it again or disconnect.",
                    confirm = "Unlock",
                    danger = saved.env == Env.PROD,
                    onConfirm = { model.setWritable(true) },
                    onDismiss = { model.unlocking = false },
                )
            }
            if (model.pickingDatabase) {
                Sheet(backdrop, { model.pickingDatabase = false }) {
                    Text("Databases", style = Type.Title)
                    val list = model.databases
                    if (list == null) {
                        Busy("Loading databases…")
                    } else {
                        Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).lightGlass(RoundedCornerShape(22.dp)).verticalScroll(rememberScrollState())) {
                            list.forEachIndexed { i, name ->
                                if (i > 0) Hairline()
                                ListRow(
                                    title = name,
                                    titleStyle = Type.Mono,
                                    onClick = { model.useDatabase(name) },
                                    trailing = { if (name == model.database) Icon(DbyIcons.Check, null, tint = LocalAccent.current, modifier = Modifier.size(18.dp)) },
                                )
                            }
                        }
                    }
                }
            }
        },
    ) {
        val tables = model.tables
        val pinned = tables.filter { it.name in model.pins }
        val rest = tables.filterNot { it.name in model.pins }
        val schema = model.schema
        val openTable = { t: TableInfo -> nav.push(Screen.Table(connectionId, t.name)) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = topSpace(), bottom = bottomSpace())) {
            item {
                TopBar(onBack = { nav.back() }) {
                    RoundButton(DbyIcons.Query, "New query", {
                        sessions.activeId = connectionId
                        nav.select(Tab.QUERY)
                    }, enabled = model.session != null)
                    RoundButton(DbyIcons.More, "More options", { model.menuOpen = true })
                }
            }
            item {
                Row(
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    EnvBadge(saved.env)
                    val version = model.session?.serverInfo()?.version
                    Text(
                        listOfNotNull(saved.name, version).joinToString(" · "),
                        style = Type.Secondary,
                        color = Dby.Secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            item {
                Row(
                    Modifier
                        .padding(horizontal = 12.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = model.session != null, role = Role.Button) { model.loadDatabases() }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(model.database, style = Type.MonoTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Icon(DbyIcons.ChevronDown, contentDescription = "Switch database", tint = Dby.Secondary, modifier = Modifier.size(22.dp))
                }
            }
            if (model.readOnly) {
                item {
                    Row(
                        Modifier
                            .padding(start = 16.dp, top = 8.dp)
                            .clip(CircleShape)
                            .background(Color(0x33FF453A))
                            .clickable { model.unlocking = true }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(DbyIcons.Lock, contentDescription = null, tint = Dby.Danger, modifier = Modifier.size(14.dp))
                        Text("Read-only · Tap to unlock", style = Type.Caption.copy(fontWeight = FontWeight.SemiBold), color = Dby.Danger)
                    }
                }
            }
            if (model.connector.busy || model.loading) item { Busy(if (model.session == null) "Connecting…" else "Reading the schema…") }
            (model.connector.problem ?: model.problem)?.let { p -> item { ProblemBanner(p, onRetry = model::open, modifier = Modifier.padding(vertical = 8.dp)) } }
            if (sessions.lost[connectionId] == true) {
                item { ProblemBanner(IllegalStateException("The connection dropped while the app was away."), onRetry = model::reload, modifier = Modifier.padding(vertical = 8.dp)) }
            }
            if (schema != null) {
                item {
                    val tableCount = schema.tables.count { !it.isView }
                    val viewCount = schema.tables.count { it.isView }
                    Segmented(
                        Kind.entries,
                        model.kind,
                        { model.kind = it },
                        {
                            when (it) {
                                Kind.TABLES -> "Tables  $tableCount"
                                Kind.VIEWS -> "Views  $viewCount"
                                Kind.ROUTINES -> "Routines  ${schema.routines}"
                            }
                        },
                        Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp).fillMaxWidth(),
                    )
                }
                if (model.kind == Kind.ROUTINES) {
                    item {
                        EmptyState(
                            "${schema.routines} routines",
                            "Routines aren't browsable in this version. In Query, run SHOW PROCEDURE STATUS WHERE Db = DATABASE() to list them.",
                        )
                    }
                } else {
                    item { SearchField(model.filter, { model.filter = it }, "Filter tables and columns") }
                    if (pinned.isNotEmpty()) {
                        item { SectionHeader("Pinned") }
                        item { TableCard(pinned, openTable, model::togglePin) }
                    }
                    item {
                        SectionHeader(if (model.kind == Kind.VIEWS) "All views" else "All tables") {
                            Text(
                                if (model.bySize) "By size" else "By name",
                                style = Type.Secondary,
                                color = LocalAccent.current,
                                modifier = Modifier.clip(CircleShape).clickable { model.bySize = !model.bySize }.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                    if (rest.isEmpty()) {
                        item { Text("Nothing here.", style = Type.Secondary, color = Dby.Secondary, modifier = Modifier.padding(horizontal = 20.dp)) }
                    } else {
                        item { TableCard(rest, openTable, model::togglePin) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TableCard(tables: List<TableInfo>, onOpen: (TableInfo) -> Unit, onPin: (String) -> Unit) {
    GroupCard {
        tables.forEachIndexed { i, t ->
            if (i > 0) Hairline(60.dp)
            ListRow(
                title = t.name,
                subtitle = t.summary(),
                titleStyle = Type.Mono.copy(fontSize = 17.sp, fontWeight = FontWeight.Medium),
                onClick = { onOpen(t) },
                onLongClick = { onPin(t.name) },
                leading = {
                    Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(Dby.FillStrong), contentAlignment = Alignment.Center) {
                        Icon(if (t.isView) DbyIcons.View else DbyIcons.Table, contentDescription = null, tint = Dby.Secondary, modifier = Modifier.size(18.dp))
                    }
                },
            )
        }
    }
}
