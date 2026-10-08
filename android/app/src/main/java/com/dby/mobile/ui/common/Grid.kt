package com.dby.mobile.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dby.core.Cell
import com.dby.core.ColumnOut
import com.dby.mobile.data.display
import com.dby.mobile.ui.DbyIcons
import com.dby.mobile.ui.theme.Dby
import com.dby.mobile.ui.theme.GeistMono
import com.dby.mobile.ui.theme.Type

private val NARROW = setOf("tinyint", "smallint", "mediumint", "int", "integer", "bigint", "year", "bit", "tiny", "short", "long", "longlong", "int24")
private val MEDIUM = setOf("decimal", "newdecimal", "numeric", "float", "double")

/** A grid column's width from its type: numbers narrow, dates and text wide. */
fun columnWidth(type: String): Dp = when {
    type in NARROW -> 96.dp
    type in MEDIUM -> 124.dp
    "date" in type || "time" in type -> 184.dp
    else -> 176.dp
}

/**
 * The grid as lazy items: a header, then one row per item. The first column stays put; the
 * rest scroll sideways together through one shared [hScroll].
 */
fun LazyListScope.gridItems(
    columns: List<ColumnOut>,
    rows: List<List<Cell>>,
    hScroll: ScrollState,
    keyColumns: Set<Int> = emptySet(),
    sorted: Pair<Int, Boolean>? = null,
    onRow: ((Int) -> Unit)? = null,
    onCell: ((row: Int, column: Int) -> Unit)? = null,
) {
    if (columns.isEmpty()) return
    item(key = "grid-header") {
        Row(Modifier.fillMaxWidth().height(40.dp).background(Dby.StripeSolid), verticalAlignment = Alignment.CenterVertically) {
            HeaderCell(columns[0], 0 in keyColumns, sorted?.takeIf { it.first == 0 }?.second)
            Row(Modifier.horizontalScroll(hScroll)) {
                for (i in 1 until columns.size) HeaderCell(columns[i], i in keyColumns, sorted?.takeIf { it.first == i }?.second)
            }
        }
    }
    itemsIndexed(rows, key = { i, _ -> "row-$i" }) { i, row ->
        Row(
            Modifier.fillMaxWidth().height(48.dp).background(if (i % 2 == 1) Dby.StripeSolid else Dby.Bg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GridCell(row.getOrElse(0) { Cell.Null }, columnWidth(columns[0].typeName), { onRow?.invoke(i) }, onCell?.let { { it(i, 0) } })
            Row(Modifier.horizontalScroll(hScroll)) {
                for (c in 1 until columns.size) {
                    GridCell(row.getOrElse(c) { Cell.Null }, columnWidth(columns[c].typeName), { onRow?.invoke(i) }, onCell?.let { { it(i, c) } })
                }
            }
        }
    }
}

@Composable
private fun HeaderCell(column: ColumnOut, key: Boolean, descending: Boolean?) {
    Row(
        Modifier.width(columnWidth(column.typeName)).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            column.name,
            style = Type.Caption.copy(fontFamily = GeistMono, fontWeight = FontWeight.SemiBold),
            color = Dby.Secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (key) Icon(DbyIcons.Key, contentDescription = "Primary key", tint = Dby.Tertiary, modifier = Modifier.size(12.dp))
        if (descending != null) {
            Icon(if (descending) DbyIcons.ArrowDown else DbyIcons.ArrowUp, contentDescription = if (descending) "Sorted descending" else "Sorted ascending", tint = Dby.Secondary, modifier = Modifier.size(12.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridCell(cell: Cell, width: Dp, onTap: () -> Unit, onLong: (() -> Unit)?) {
    Text(
        cell.display(),
        style = Type.Mono.copy(fontSize = 14.sp),
        color = if (cell is Cell.Null) Dby.Faint else Dby.Fg,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .width(width)
            .height(48.dp)
            .combinedClickable(onClick = onTap, onLongClick = onLong)
            .padding(horizontal = 12.dp, vertical = 14.dp),
    )
}
