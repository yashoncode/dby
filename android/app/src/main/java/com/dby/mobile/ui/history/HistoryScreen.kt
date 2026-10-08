package com.dby.mobile.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.dby.core.HistoryEntry
import com.dby.core.SavedQuery
import com.dby.core.deleteHistory
import com.dby.core.deleteSavedQuery
import com.dby.core.history
import com.dby.core.savedQueries
import com.dby.mobile.DbyApp
import com.dby.mobile.bottomSpace
import com.dby.mobile.data.ago
import com.dby.mobile.data.count
import com.dby.mobile.topSpace
import com.dby.mobile.ui.common.EmptyState
import com.dby.mobile.ui.common.GroupCard
import com.dby.mobile.ui.common.Hairline
import com.dby.mobile.ui.common.LargeTitle
import com.dby.mobile.ui.common.ListRow
import com.dby.mobile.ui.common.Segmented
import com.dby.mobile.ui.glass.GlassHost
import com.dby.mobile.ui.nav.Screen
import com.dby.mobile.ui.nav.ScreenModel
import com.dby.mobile.ui.nav.Tab
import com.dby.mobile.ui.query.QueryModel
import com.dby.mobile.ui.theme.Dby
import com.dby.mobile.ui.theme.Type

class HistoryModel : ScreenModel() {
    var saved by mutableStateOf(false)
    var entries by mutableStateOf<List<HistoryEntry>>(emptyList())
        private set
    var queries by mutableStateOf<List<SavedQuery>>(emptyList())
        private set

    fun load() {
        entries = runCatching { history(200u) }.getOrDefault(emptyList())
        queries = runCatching { savedQueries() }.getOrDefault(emptyList())
    }

    fun delete(entry: HistoryEntry) {
        runCatching { deleteHistory(entry.id) }
        load()
    }

    fun delete(query: SavedQuery) {
        runCatching { deleteSavedQuery(query.sql) }
        load()
    }
}

@Composable
fun HistoryScreen(app: DbyApp) {
    val nav = app.nav
    val model = nav.model(Screen.History) { HistoryModel() }
    LaunchedEffect(Unit) { model.load() }
    val openInQuery = { sql: String, connectionId: String? ->
        val query = nav.rootModel(Tab.QUERY) { QueryModel(app.sessions, app.prefs) }
        if (connectionId != null && app.sessions.saved(connectionId) != null) app.sessions.activeId = connectionId
        query.load(sql)
        nav.select(Tab.QUERY)
    }
    GlassHost {
        val now = System.currentTimeMillis()
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = topSpace(), bottom = bottomSpace())) {
            item { LargeTitle("History", Modifier.padding(top = 52.dp, bottom = 12.dp)) }
            item { Segmented(listOf(false, true), model.saved, { model.saved = it }, { if (it) "Saved" else "Recent" }, Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()) }
            if (!model.saved) {
                if (model.entries.isEmpty()) item { EmptyState("Nothing run yet", "Queries and row edits appear here with their time and result.") }
                else item {
                    GroupCard(Modifier.padding(top = 12.dp)) {
                        model.entries.forEachIndexed { i, e ->
                            if (i > 0) Hairline(36.dp)
                            val what = e.error ?: "${count(e.rows.toLong())} rows"
                            ListRow(
                                e.sql.replace('\n', ' '),
                                subtitle = listOfNotNull(e.connectionName ?: "deleted connection", e.database, "${e.elapsedMs} ms", what, ago(e.atMs, now)).joinToString(" · "),
                                titleStyle = Type.Mono,
                                subtitleColor = if (e.error != null) Dby.Danger else Dby.Secondary,
                                onClick = { openInQuery(e.sql, e.connectionId) },
                                onLongClick = { model.delete(e) },
                                leading = { Box(Modifier.size(8.dp).clip(CircleShape).background(if (e.error == null) Dby.Success else Dby.Danger)) },
                            )
                        }
                    }
                }
            } else {
                if (model.queries.isEmpty()) item { EmptyState("No saved queries", "Save one from the Query tab with the bookmark button.") }
                else item {
                    GroupCard(Modifier.padding(top = 12.dp)) {
                        model.queries.forEachIndexed { i, q ->
                            if (i > 0) Hairline()
                            ListRow(q.title, subtitle = q.sql.replace('\n', ' '), onClick = { openInQuery(q.sql, null) }, onLongClick = { model.delete(q) })
                        }
                    }
                }
            }
        }
    }
}
