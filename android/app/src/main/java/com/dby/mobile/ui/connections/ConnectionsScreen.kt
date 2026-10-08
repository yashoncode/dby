package com.dby.mobile.ui.connections

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dby.core.Env
import com.dby.core.SavedConnection
import com.dby.mobile.DbyApp
import com.dby.mobile.Logo
import com.dby.mobile.bottomSpace
import com.dby.mobile.data.Sessions
import com.dby.mobile.data.WrongPassphrase
import com.dby.mobile.data.dbxNeedsPassphrase
import com.dby.mobile.data.readDbx
import com.dby.mobile.data.sentence
import com.dby.mobile.ui.common.FieldRow
import com.dby.mobile.ui.common.PrimaryButton
import com.dby.mobile.ui.common.ProblemBanner
import com.dby.mobile.ui.common.Sheet
import com.dby.mobile.ui.glass.lightGlass
import com.kyant.backdrop.Backdrop
import com.dby.mobile.topSpace
import com.dby.mobile.ui.DbyIcons
import com.dby.mobile.ui.common.Action
import com.dby.mobile.ui.common.ActionSheet
import com.dby.mobile.ui.common.Chip
import com.dby.mobile.ui.common.ConfirmSheet
import com.dby.mobile.ui.common.EmptyState
import com.dby.mobile.ui.common.EnvBadge
import com.dby.mobile.ui.common.GroupCard
import com.dby.mobile.ui.common.Hairline
import com.dby.mobile.ui.common.LargeTitle
import com.dby.mobile.ui.common.ListRow
import com.dby.mobile.ui.common.RoundButton
import com.dby.mobile.ui.common.SearchField
import com.dby.mobile.ui.common.SectionHeader
import com.dby.mobile.ui.glass.GlassHost
import com.dby.mobile.ui.nav.Screen
import com.dby.mobile.ui.nav.ScreenModel
import com.dby.mobile.ui.theme.Dby
import com.dby.mobile.ui.theme.Type
import com.dby.mobile.ui.theme.colors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ConnectionsModel(private val sessions: Sessions) : ScreenModel() {
    var query by mutableStateOf("")
    var env by mutableStateOf<Env?>(null)
    var menuFor by mutableStateOf<SavedConnection?>(null)
    var deleting by mutableStateOf<SavedConnection?>(null)

    val shown: List<SavedConnection>
        get() = sessions.connections.filter { c ->
            (env == null || c.env == env) &&
                (query.isBlank() || listOf(c.name, c.host, c.database, c.user).any { it.contains(query.trim(), ignoreCase = true) })
        }

    fun delete(c: SavedConnection) {
        scope.launch {
            sessions.delete(c.id)
            deleting = null
        }
    }

    /** A dbx export waiting for its passphrase. */
    var importText by mutableStateOf<String?>(null)
    var importing by mutableStateOf(false)
    var importProblem by mutableStateOf<Throwable?>(null)

    /** Reads a dbx export and saves its connections; [done] gets the line to show. */
    fun import(text: String, passphrase: String?, done: (String) -> Unit) {
        scope.launch {
            importing = true
            importProblem = null
            try {
                val file = withContext(Dispatchers.Default) { readDbx(text, passphrase) }
                val added = sessions.import(file.connections)
                importText = null
                val same = file.connections.size - added
                done(
                    buildString {
                        append(if (added == 1) "Imported 1 connection" else "Imported $added connections")
                        if (same > 0) append(", $same already here")
                        if (file.skipped > 0) append(", ${file.skipped} skipped (not MySQL or MariaDB, or behind SSH)")
                        append('.')
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: WrongPassphrase) {
                importProblem = e
            } catch (e: Exception) {
                importText = null
                done(e.sentence())
            } finally {
                importing = false
            }
        }
    }

    fun disconnect(c: SavedConnection) {
        scope.launch { sessions.disconnect(c.id) }
    }
}

/** "My Orders DB" → "MO": the avatar's letters. */
fun initials(name: String): String =
    name.split(' ', '-', '_', '.').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "DB" }

@Composable
fun ConnectionsScreen(app: DbyApp) {
    val nav = app.nav
    val sessions = app.sessions
    val model = nav.model(Screen.Connections) { ConnectionsModel(sessions) }
    val context = LocalContext.current
    val toast = { text: String -> Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
    val pickDbx = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching { context.contentResolver.openInputStream(uri)!!.use { it.reader().readText() } }.getOrNull()
        when {
            text == null -> toast("Couldn't read that file.")
            runCatching { dbxNeedsPassphrase(text) }.getOrDefault(false) -> model.importText = text
            else -> model.import(text, null, toast)
        }
    }
    GlassHost(
        overlay = { backdrop ->
            model.menuFor?.let { c ->
                val actions = buildList {
                    add(Action("Edit", DbyIcons.Edit) { model.menuFor = null; nav.push(Screen.EditConnection(c.id)) })
                    add(Action(if (c.pinned) "Unpin" else "Pin", DbyIcons.Pin) { sessions.setPinned(c.id, !c.pinned); model.menuFor = null })
                    if (sessions.open.containsKey(c.id)) add(Action("Disconnect", DbyIcons.Unplug) { model.disconnect(c); model.menuFor = null })
                    add(Action("Delete", DbyIcons.Trash, danger = true) { model.menuFor = null; model.deleting = c })
                }
                ActionSheet(backdrop, c.name, actions) { model.menuFor = null }
            }
            app.whatsNew?.let { (title, notes) ->
                ConfirmSheet(
                    backdrop,
                    title = title,
                    message = notes.ifBlank { null }?.lines()?.joinToString("\n") { "•  $it" },
                    confirm = "Done",
                    onConfirm = { app.whatsNew = null },
                    onDismiss = { app.whatsNew = null },
                )
            }
            model.importText?.let { text ->
                PassphraseSheet(backdrop, model.importing, model.importProblem, { model.import(text, it, toast) }) {
                    model.importText = null
                    model.importProblem = null
                }
            }
            model.deleting?.let { c ->
                ConfirmSheet(
                    backdrop,
                    title = "Delete ${c.name}?",
                    message = "Its saved password and cached schema are removed from this phone. Nothing changes on the server.",
                    confirm = "Delete",
                    danger = true,
                    onConfirm = { model.delete(c) },
                    onDismiss = { model.deleting = null },
                )
            }
        },
    ) {
        val shown = model.shown
        val pinned = shown.filter { it.pinned }
        val rest = shown.filterNot { it.pinned }
        val open = { c: SavedConnection -> nav.push(Screen.Explorer(c.id)) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = topSpace(), bottom = bottomSpace())) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                    Logo()
                    Text("DBY", style = Type.Secondary.copy(fontWeight = FontWeight.SemiBold), color = Dby.Secondary, modifier = Modifier.padding(start = 8.dp))
                    Spacer(Modifier.weight(1f))
                    RoundButton(DbyIcons.Download, "Import from dbx", { pickDbx.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) })
                    Spacer(Modifier.width(8.dp))
                    RoundButton(DbyIcons.Plus, "New connection", { nav.push(Screen.EditConnection(null)) })
                }
            }
            item { LargeTitle("Connections", Modifier.padding(top = 4.dp, bottom = 12.dp)) }
            if (sessions.connections.isNotEmpty()) {
                item { SearchField(model.query, { model.query = it }, "Search connections") }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item { Chip("All", model.env == null, { model.env = null }) }
                        items(Env.entries) { env -> Chip(env.name, model.env == env, { model.env = env }) }
                    }
                }
            }
            when {
                sessions.connections.isEmpty() -> item {
                    EmptyState("No connections yet", "Add a MySQL or MariaDB server to start browsing.", "New connection") {
                        nav.push(Screen.EditConnection(null))
                    }
                }
                shown.isEmpty() -> item { EmptyState("Nothing matches", "Try another name, host or tag.") }
            }
            if (pinned.isNotEmpty()) {
                item { SectionHeader("Pinned") }
                item { ConnectionCard(pinned, sessions, open) { model.menuFor = it } }
            }
            if (rest.isNotEmpty()) {
                item { SectionHeader(if (pinned.isEmpty()) "All connections" else "Others") { Text("Recent", style = Type.Secondary, color = Dby.Secondary, modifier = Modifier.padding(end = 8.dp)) } }
                item { ConnectionCard(rest, sessions, open) { model.menuFor = it } }
            }
        }
    }
}

@Composable
private fun ConnectionCard(list: List<SavedConnection>, sessions: Sessions, onOpen: (SavedConnection) -> Unit, onMenu: (SavedConnection) -> Unit) {
    GroupCard {
        list.forEachIndexed { i, c ->
            if (i > 0) Hairline(68.dp)
            val session = sessions.open[c.id]
            ListRow(
                title = c.name,
                subtitle = if (session != null) "Connected · ${session.serverInfo().version} · ${c.host}" else "${c.user}@${c.host}:${c.port}" + if (c.database.isNotBlank()) " · ${c.database}" else "",
                onClick = { onOpen(c) },
                onLongClick = { onMenu(c) },
                leading = { Avatar(c, connected = session != null) },
                titleExtra = { EnvBadge(c.env) },
            )
        }
    }
}

@Composable
private fun Avatar(c: SavedConnection, connected: Boolean) {
    val (fg, bg) = c.env.colors()
    Box {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(bg), contentAlignment = Alignment.Center) {
            Text(initials(c.name), style = Type.Secondary.copy(fontWeight = FontWeight.Bold), color = fg)
        }
        if (connected) {
            Box(Modifier.align(Alignment.BottomEnd).offset(3.dp, 3.dp).size(12.dp).clip(CircleShape).background(Dby.Bg).padding(2.dp).clip(CircleShape).background(Dby.Success))
        }
    }
}

/** The passphrase an encrypted dbx export was saved with. */
@Composable
private fun PassphraseSheet(backdrop: Backdrop, busy: Boolean, problem: Throwable?, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var passphrase by remember { mutableStateOf("") }
    Sheet(backdrop, onDismiss) {
        Text("Import from dbx", style = Type.Title)
        Text("This export is encrypted. Enter the passphrase it was saved with.", style = Type.Secondary, color = Dby.Secondary)
        Column(Modifier.fillMaxWidth().lightGlass(RoundedCornerShape(22.dp))) {
            FieldRow("Passphrase", passphrase, { passphrase = it }, keyboard = KeyboardType.Password, secret = true)
        }
        if (problem != null) ProblemBanner(problem, modifier = Modifier.padding(horizontal = 0.dp))
        PrimaryButton("Import", { onSubmit(passphrase) }, Modifier.fillMaxWidth(), enabled = passphrase.isNotEmpty(), busy = busy)
    }
}
