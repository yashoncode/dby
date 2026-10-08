package com.dby.mobile

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dby.core.Cell
import com.dby.core.ColumnOut
import com.dby.core.ConnectParams
import com.dby.core.Cursor
import com.dby.core.Page
import com.dby.core.PageRequest
import com.dby.core.Session
import com.dby.core.TlsMode
import com.dby.core.openSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.util.UUID

private const val PAGE_SIZE = 50u

/** M0 only: drives the core and logs timings for the Tevel comparison. M2 replaces it. */
class BenchViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("bench", Context.MODE_PRIVATE)

    var host by mutableStateOf(prefs.getString("host", "").orEmpty())
    var port by mutableStateOf(prefs.getString("port", "3306").orEmpty())
    var user by mutableStateOf(prefs.getString("user", "").orEmpty())
    var password by mutableStateOf("") // never saved
    var database by mutableStateOf(prefs.getString("database", "").orEmpty())
    var table by mutableStateOf(prefs.getString("table", "").orEmpty())
    var tls by mutableStateOf(TlsMode.valueOf(prefs.getString("tls", TlsMode.VERIFY.name) ?: TlsMode.VERIFY.name))
    var sql by mutableStateOf(prefs.getString("sql", "SELECT * FROM ").orEmpty())

    var status by mutableStateOf("Not connected")
    var busy by mutableStateOf(false)
    var columns by mutableStateOf<List<ColumnOut>>(emptyList())
    val rows = mutableStateListOf<List<Cell>>()
    var hasNext by mutableStateOf(false)

    private var session: Session? = null
    private var nextCursor: Cursor? = null
    private var prefetched: Deferred<Page>? = null
    private var runId: String? = null

    fun connect() = timed("connect") {
        prefs.edit()
            .putString("host", host).putString("port", port).putString("user", user)
            .putString("database", database).putString("table", table).putString("tls", tls.name)
            .apply()
        session?.disconnect()
        val opened = openSession(ConnectParams(host.trim(), port.trim().toUShort(), user, password, database.trim(), tls))
        session = opened
        clearGrid()
        "Connected · ${opened.serverInfo().version}"
    }

    fun openTable() = timed("first_page") {
        val s = requireSession()
        val page = s.tablePage(PageRequest(table.trim(), emptyList(), null, null, PAGE_SIZE))
        show(page)
        prefetch(s)
        "${table.trim()} · page 1"
    }

    fun nextPage() = timed("next_page") {
        val s = requireSession()
        val pending = prefetched ?: return@timed "No more rows"
        show(pending.await())
        prefetch(s)
        "${table.trim()} · next page"
    }

    fun runSql() = timed("sql") {
        val s = requireSession()
        prefs.edit().putString("sql", sql).apply()
        val id = UUID.randomUUID().toString()
        runId = id
        try {
            val result = s.runSql(id, sql, true)
            columns = result.columns
            rows.clear()
            rows.addAll(result.rows)
            hasNext = false
            prefetched = null
            val capped = if (result.truncated) " (capped)" else ""
            "${result.rows.size} rows$capped · ${result.affectedRows} affected"
        } finally {
            runId = null
        }
    }

    fun cancel() {
        val s = session ?: return
        val id = runId ?: return
        viewModelScope.launch {
            try {
                s.cancel(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "Cancel failed: ${e.message}"
            }
        }
    }

    override fun onCleared() {
        val s = session ?: return
        CoroutineScope(Dispatchers.IO).launch { s.disconnect() }
    }

    /** Runs [block], logs `DBYBENCH` with the time from tap to data in state, shows the result. */
    private fun timed(event: String, block: suspend () -> String) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            val started = SystemClock.elapsedRealtime()
            status = try {
                val note = block()
                val ms = SystemClock.elapsedRealtime() - started
                Log.i("DBYBENCH", "app=dby event=$event ms=$ms rows=${rows.size}")
                "$note · $ms ms"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Failed: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    private fun requireSession(): Session = session ?: error("Connect first")

    private fun clearGrid() {
        columns = emptyList()
        rows.clear()
        hasNext = false
        nextCursor = null
        prefetched = null
    }

    private fun show(page: Page) {
        columns = page.columns
        rows.clear()
        rows.addAll(page.rows)
        nextCursor = page.next
        hasNext = page.next != null
    }

    /** Asks for the next page as soon as one is shown, so "Next page" usually costs no round trip. */
    private fun prefetch(s: Session) {
        val cursor = nextCursor
        val name = table.trim()
        prefetched = if (cursor == null) null else viewModelScope.async { s.tablePage(PageRequest(name, emptyList(), null, cursor, PAGE_SIZE)) }
    }
}
