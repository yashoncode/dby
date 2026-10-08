package com.dby.mobile.ui.query

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import com.dby.core.Filter
import com.dby.core.FilterOp
import com.dby.core.QueryResult
import com.dby.core.SavedConnection
import com.dby.core.SavedQuery
import com.dby.core.Schema
import com.dby.core.SelectSpec
import com.dby.core.Session
import com.dby.core.Sort
import com.dby.core.SqlKind
import com.dby.core.TableInfo
import com.dby.core.cachedSchema
import com.dby.core.classifySql
import com.dby.core.deleteSavedQuery
import com.dby.core.saveQuery
import com.dby.core.savedQueries
import com.dby.mobile.data.Prefs
import com.dby.mobile.data.Sessions
import com.dby.mobile.ui.common.Connector
import com.dby.mobile.ui.nav.ScreenModel
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

enum class Mode { BUILDER, SQL }

/** The Query tab (spec §8 "Run SQL"): a builder that writes SQL, the SQL itself, and its result. */
class QueryModel(private val sessions: Sessions, private val prefs: Prefs) : ScreenModel() {
    val connector = Connector(sessions, scope)
    var mode by mutableStateOf(Mode.BUILDER)
    var table by mutableStateOf<String?>(null)
        private set
    val fields = mutableStateListOf<String>()
    val filters = mutableStateListOf<Filter>()
    var matchAll by mutableStateOf(true)
    var sort by mutableStateOf<Sort?>(null)
    var limit by mutableIntStateOf(50)
    var sql by mutableStateOf(TextFieldValue(""))
        private set
    /** Once the SQL is edited by hand, the builder stops rewriting it. */
    var sqlEdited by mutableStateOf(false)
        private set
    var running by mutableStateOf(false)
        private set
    var result by mutableStateOf<QueryResult?>(null)
        private set
    var ranSql by mutableStateOf("")
        private set
    var showResult by mutableStateOf(false)
    var problem by mutableStateOf<Throwable?>(null)
    var confirming by mutableStateOf<String?>(null)
    var saved by mutableStateOf<List<SavedQuery>>(emptyList())
        private set
    private var runId: String? = null

    val connection: SavedConnection? get() = sessions.activeId?.let(sessions::saved)
    val session: Session? get() = sessions.activeId?.let { sessions.open[it] }
    val schema: Schema? get() = session?.let { s -> sessions.activeId?.let { cachedSchema(it, s.database()) } }
    val tableInfo: TableInfo? get() = schema?.tables?.firstOrNull { it.name == table }

    fun choose(target: SavedConnection) {
        connector.open(target) {
            sessions.activeId = target.id
            table = null
            fields.clear()
            filters.clear()
            sort = null
            if (!sqlEdited) sql = TextFieldValue("")
        }
    }

    fun pickTable(name: String) {
        table = name
        fields.clear()
        filters.clear()
        sort = null
        rebuild()
    }

    /** Writes the builder's SQL into the editor, unless the person has edited it. */
    fun rebuild() {
        if (sqlEdited) return
        val t = table ?: return
        val s = session ?: return
        val spec = SelectSpec(t, fields.toList(), filters.filter { it.op == FilterOp.IS_NULL || it.op == FilterOp.IS_NOT_NULL || it.value.isNotEmpty() }, matchAll, sort, limit.toUInt())
        runCatching { s.buildSelect(spec) }.onSuccess { sql = TextFieldValue(it) }
    }

    fun editSql(value: TextFieldValue) {
        if (value.text != sql.text) sqlEdited = true
        sql = value
    }

    fun resetToBuilder() {
        sqlEdited = false
        rebuild()
    }

    /** Runs the SQL; a write first shows its confirm sheet when "Confirm before saving" is on. */
    fun run() {
        val text = sql.text.trim()
        if (text.isEmpty()) return
        if (classifySql(text) == SqlKind.WRITE && prefs.confirmWrites) confirming = text else execute(text)
    }

    fun execute(text: String) {
        confirming = null
        val s = session ?: return
        scope.launch {
            running = true
            problem = null
            val id = UUID.randomUUID().toString()
            runId = id
            val started = SystemClock.elapsedRealtime()
            try {
                val r = s.runSql(id, text, true)
                Log.i("DBYBENCH", "app=dby event=sql ms=${SystemClock.elapsedRealtime() - started} rows=${r.rows.size}")
                result = r
                ranSql = text
                showResult = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = e
            } finally {
                running = false
                runId = null
            }
        }
    }

    fun cancel() {
        val s = session ?: return
        val id = runId ?: return
        scope.launch { runCatching { s.cancel(id) } }
    }

    /** Opens SQL from History or a saved query in the editor. */
    fun load(text: String) {
        mode = Mode.SQL
        sqlEdited = true
        sql = TextFieldValue(text)
        showResult = false
    }

    fun refreshSaved() {
        saved = runCatching { savedQueries() }.getOrDefault(emptyList())
    }

    fun save(title: String) {
        runCatching { saveQuery(title, sql.text.trim()) }.onFailure { problem = it }
        refreshSaved()
    }

    fun deleteSaved(text: String) {
        runCatching { deleteSavedQuery(text) }
        refreshSaved()
    }
}
