package com.dby.mobile.ui.table

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dby.core.Cell
import com.dby.core.ColumnDef
import com.dby.core.ColumnOut
import com.dby.core.CountRequest
import com.dby.core.FacetCount
import com.dby.core.FacetRequest
import com.dby.core.Filter
import com.dby.core.FilterOp
import com.dby.core.Page
import com.dby.core.PageRequest
import com.dby.core.Session
import com.dby.core.Sort
import com.dby.core.TableInfo
import com.dby.core.cachedSchema
import com.dby.mobile.data.Prefs
import com.dby.mobile.data.Sessions
import com.dby.mobile.data.count
import com.dby.mobile.ui.nav.ScreenModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

sealed interface Total {
    data object Counting : Total
    /** The count took longer than two seconds (spec §6 rule 10). */
    data object Many : Total
    data class Exact(val n: Long) : Total
}

/** One table's pages (spec §8 "Browse a table"): first page, prefetched next page, then the count and facets. */
class TableModel(
    val connectionId: String,
    val table: String,
    private val sessions: Sessions,
    private val prefs: Prefs,
) : ScreenModel() {
    val session: Session? get() = sessions.open[connectionId]
    val database: String = session?.database() ?: sessions.saved(connectionId)?.database.orEmpty()
    val info: TableInfo? = cachedSchema(connectionId, database)?.tables?.firstOrNull { it.name == table }

    var filters by mutableStateOf<List<Filter>>(emptyList())
        private set
    var sort by mutableStateOf<Sort?>(null)
        private set
    var columns by mutableStateOf<List<ColumnOut>>(emptyList())
        private set
    var rows by mutableStateOf<List<List<Cell>>>(emptyList())
        private set
    var pageIndex by mutableIntStateOf(0)
        private set
    var hasNext by mutableStateOf(false)
        private set
    var total by mutableStateOf<Total>(Total.Counting)
        private set
    var facets by mutableStateOf<List<FacetCount>?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var problem by mutableStateOf<Throwable?>(null)
    var cards by mutableStateOf(prefs.rowsAsCards)

    /** Open sheets: the row editor, the cell viewer, the filter and sort pickers. */
    var editor by mutableStateOf<RowEditor?>(null)
    var viewing by mutableStateOf<Pair<Int, Int>?>(null)
    var filtering by mutableStateOf<FilterOp?>(null)
    var sorting by mutableStateOf(false)

    private val pages = mutableListOf<Page>()
    private var prefetched: Deferred<Page>? = null
    private val pageSize: UInt get() = prefs.rowsPerPage.toUInt()

    /** The column the quick-filter chips group by: the first ENUM column (as `status` on the canvas). */
    val facetColumn: ColumnDef? = info?.columns?.firstOrNull { it.enumValues.isNotEmpty() }
    val readOnly: Boolean get() = session?.isReadOnly() ?: true

    /** The schema's definition of each column on screen, by position. */
    val defs: List<ColumnDef?> get() = columns.map { c -> info?.columns?.firstOrNull { it.name == c.name } }
    val keyColumns: Set<Int> get() = defs.withIndex().filter { it.value?.pkSeq != null }.map { it.index }.toSet()

    init {
        reload()
    }

    fun reload() {
        val s = session ?: return gone()
        prefetched?.cancel()
        prefetched = null
        pages.clear()
        pageIndex = 0
        scope.launch {
            loading = true
            problem = null
            val started = SystemClock.elapsedRealtime()
            try {
                val page = s.tablePage(request(null))
                Log.i("DBYBENCH", "app=dby event=first_page ms=${SystemClock.elapsedRealtime() - started} rows=${page.rows.size}")
                show(page)
                count(s)
                facets(s)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = e
            } finally {
                loading = false
            }
        }
    }

    /** After a row change: fetch the page on screen again and recount. */
    fun reloadCurrent() {
        val s = session ?: return gone()
        val cursor = if (pageIndex == 0) null else pages[pageIndex - 1].next
        prefetched?.cancel()
        prefetched = null
        scope.launch {
            try {
                val page = s.tablePage(request(cursor))
                while (pages.size > pageIndex) pages.removeAt(pages.lastIndex)
                show(page)
                count(s)
                facets(s)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = e
            }
        }
    }

    fun next() {
        if (pageIndex < pages.lastIndex) {
            pageIndex++
            display()
            return
        }
        val pending = prefetched ?: return
        scope.launch {
            val started = SystemClock.elapsedRealtime()
            try {
                val page = pending.await()
                Log.i("DBYBENCH", "app=dby event=next_page ms=${SystemClock.elapsedRealtime() - started} rows=${page.rows.size}")
                show(page)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = e
            }
        }
    }

    fun previous() {
        if (pageIndex > 0) {
            pageIndex--
            display()
        }
    }

    fun addFilter(filter: Filter) {
        filters = filters + filter
        reload()
    }

    fun removeFilter(index: Int) {
        filters = filters.filterIndexed { i, _ -> i != index }
        reload()
    }

    /** A facet chip: replaces any filter on the facet column; null shows all. */
    fun pickFacet(value: String?) {
        val column = facetColumn?.name ?: return
        filters = filters.filterNot { it.column == column } + listOfNotNull(value?.let { Filter(column, FilterOp.EQ, it) })
        reload()
    }

    val facetValue: String? get() = filters.firstOrNull { it.column == facetColumn?.name && it.op == FilterOp.EQ }?.value

    fun sortBy(value: Sort?) {
        sort = value
        sorting = false
        reload()
    }

    fun edit(rowIndex: Int) {
        editor = RowEditor(this, rowIndex)
    }

    fun insert() {
        editor = RowEditor(this, null)
    }

    val rangeText: String
        get() {
            if (rows.isEmpty()) return if (loading) "Loading…" else "No rows"
            val start = pageIndex.toLong() * pageSize.toLong() + 1
            val end = start + rows.size - 1
            return when (val t = total) {
                is Total.Exact -> "Rows ${count(start)}–${count(end)} of ${count(t.n)}"
                Total.Many -> "Rows ${count(start)}–${count(end)} of ${count(end)}+"
                Total.Counting -> "Rows ${count(start)}–${count(end)}"
            }
        }

    private fun request(cursor: com.dby.core.Cursor?) = PageRequest(table, filters, sort, cursor, pageSize)

    private fun show(page: Page) {
        pages.add(page)
        pageIndex = pages.lastIndex
        display()
        val cursor = page.next
        val s = session
        // Ask for the next page as soon as this one is shown, so "next" usually costs no round trip.
        prefetched = if (cursor == null || s == null) null else scope.async { s.tablePage(request(cursor)) }
    }

    private fun display() {
        val page = pages[pageIndex]
        columns = page.columns
        rows = page.rows
        hasNext = pageIndex < pages.lastIndex || page.next != null
    }

    private fun count(s: Session) {
        total = Total.Counting
        scope.launch {
            total = try {
                s.countRows(CountRequest(table, filters))?.let { Total.Exact(it.toLong()) } ?: Total.Many
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Total.Many
            }
        }
    }

    private fun facets(s: Session) {
        val column = facetColumn ?: return
        scope.launch {
            facets = try {
                s.facetCounts(FacetRequest(table, column.name, filters.filterNot { it.column == column.name }))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun gone() {
        problem = IllegalStateException("This connection is closed. Open it again from Connections.")
    }
}

/** "status = shipped", "notes is null", "name contains x": a filter chip's text. */
fun Filter.label(): String = when (op) {
    FilterOp.EQ -> "$column = $value"
    FilterOp.NOT_EQ -> "$column ≠ $value"
    FilterOp.GT -> "$column > $value"
    FilterOp.GE -> "$column ≥ $value"
    FilterOp.LT -> "$column < $value"
    FilterOp.LE -> "$column ≤ $value"
    FilterOp.CONTAINS -> "$column contains $value"
    FilterOp.IS_NULL -> "$column is null"
    FilterOp.IS_NOT_NULL -> "$column is not null"
}

fun FilterOp.symbol(): String = when (this) {
    FilterOp.EQ -> "="
    FilterOp.NOT_EQ -> "≠"
    FilterOp.GT -> ">"
    FilterOp.GE -> "≥"
    FilterOp.LT -> "<"
    FilterOp.LE -> "≤"
    FilterOp.CONTAINS -> "contains"
    FilterOp.IS_NULL -> "is null"
    FilterOp.IS_NOT_NULL -> "not null"
}

class RowEditor(val model: TableModel, val rowIndex: Int?)
