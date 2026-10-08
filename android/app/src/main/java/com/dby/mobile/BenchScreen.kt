package com.dby.mobile

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dby.core.Cell
import com.dby.core.ColumnOut
import com.dby.core.TlsMode
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

// M0 throwaway colours; M2 replaces them with the theme from the design canvas.
internal val Bg = Color(0xFF0B0B0E)
internal val Fg = Color(0xFFF5F5F7)
internal val Muted = Color(0xB3FFFFFF)
internal val Accent = Color(0xFF5AC8FA)
private val HeaderBg = Color(0xFF16161B)
private val StripeBg = Color(0x09FFFFFF)

/** The frosted material from spec §11, used here to measure blur cost over a scrolling grid. */
private val PillStyle = HazeBlurStyle {
    blurRadius(24.dp)
    backgroundColor(Bg)
    colorEffects(listOf(HazeColorEffect.tint(Color(0x801C1C21))))
}

@Composable
fun BenchScreen(vm: BenchViewModel = viewModel()) {
    val hazeState = rememberHazeState()
    Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding().padding(horizontal = 12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Field(vm.host, { vm.host = it }, "Host", Modifier.weight(2f))
            Field(vm.port, { vm.port = it }, "Port", Modifier.weight(1f), KeyboardType.Number)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Field(vm.user, { vm.user = it }, "User", Modifier.weight(1f))
            Field(vm.password, { vm.password = it }, "Password", Modifier.weight(1f), KeyboardType.Password, secret = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Field(vm.database, { vm.database = it }, "Database", Modifier.weight(1f))
            Field(vm.table, { vm.table = it }, "Table", Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TlsMode.entries.forEach { mode ->
                TextButton(onClick = { vm.tls = mode }) {
                    Text(mode.name, color = if (vm.tls == mode) Accent else Muted, fontSize = 12.sp)
                }
            }
            Box(Modifier.weight(1f))
            Button(onClick = vm::connect, enabled = !vm.busy) { Text("Connect") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Field(vm.sql, { vm.sql = it }, "SQL", Modifier.weight(1f))
            Button(onClick = vm::runSql, enabled = !vm.busy) { Text("Run") }
            TextButton(onClick = vm::cancel) { Text("Cancel") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = vm::openTable, enabled = !vm.busy) { Text("Open table") }
            Text(vm.status, color = Muted, fontSize = 13.sp, maxLines = 2, modifier = Modifier.padding(start = 10.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Grid(vm.columns, vm.rows, Modifier.fillMaxSize().hazeSource(hazeState))
            Pill(
                hazeState,
                vm,
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = Fg),
    )
}

@Composable
private fun Grid(columns: List<ColumnOut>, rows: List<List<Cell>>, modifier: Modifier) {
    val hScroll = rememberScrollState()
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 96.dp)) {
        if (columns.isNotEmpty()) {
            item(key = "header") { GridRow(columns.map { it.name }, hScroll, header = true) }
        }
        itemsIndexed(rows) { index, row -> GridRow(row.map { it.display() }, hScroll, stripe = index % 2 == 1) }
    }
}

/** First cell pinned; the rest scroll sideways together through one shared [ScrollState]. */
@Composable
private fun GridRow(cells: List<String>, hScroll: ScrollState, header: Boolean = false, stripe: Boolean = false) {
    val bg = when {
        header -> HeaderBg
        stripe -> StripeBg
        else -> Bg
    }
    Row(Modifier.fillMaxWidth().height(40.dp).background(bg), verticalAlignment = Alignment.CenterVertically) {
        GridCell(cells.firstOrNull().orEmpty(), 96.dp, header)
        Row(Modifier.horizontalScroll(hScroll)) {
            cells.drop(1).forEach { GridCell(it, 160.dp, header) }
        }
    }
}

@Composable
private fun GridCell(text: String, width: Dp, header: Boolean) {
    Text(
        text,
        Modifier.width(width).padding(horizontal = 8.dp),
        color = if (header) Muted else Fg,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun Pill(hazeState: HazeState, vm: BenchViewModel, modifier: Modifier) {
    val shape = RoundedCornerShape(28.dp)
    Row(
        modifier
            .clip(shape)
            .hazeBlur(input = HazeInput.Sources(hazeState), style = PillStyle)
            .border(0.5.dp, Color.White.copy(alpha = 0.14f), shape)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${vm.rows.size} rows", color = Fg, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp))
        TextButton(onClick = vm::nextPage, enabled = vm.hasNext && !vm.busy) { Text("Next page") }
    }
}

private fun Cell.display(): String = when (this) {
    is Cell.Null -> "NULL"
    is Cell.Signed -> v.toString()
    is Cell.Unsigned -> v.toString()
    is Cell.Real -> v.toString()
    is Cell.Exact -> v
    is Cell.Text -> if (fullLen > v.length.toULong()) "$v…" else v
    is Cell.Temporal -> v
    is Cell.Bytes -> "<$len bytes>"
}
