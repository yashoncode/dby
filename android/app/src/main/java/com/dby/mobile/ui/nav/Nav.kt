package com.dby.mobile.ui.nav

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

enum class Tab { CONNECTIONS, QUERY, HISTORY, SETTINGS }

sealed interface Screen {
    data object Connections : Screen
    data class EditConnection(val id: String?) : Screen
    data class Explorer(val connectionId: String) : Screen
    data class Table(val connectionId: String, val table: String) : Screen
    data object Query : Screen
    data object History : Screen
    data object Settings : Screen
    data object Licences : Screen
}

/** What a screen keeps while it is on a back stack. The navigator closes it when the screen is popped. */
abstract class ScreenModel {
    private val lazyScope = lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    val scope: CoroutineScope by lazyScope

    open fun close() {
        if (lazyScope.isInitialized()) scope.cancel()
    }
}

/** One back stack per tab, so switching tabs keeps each tab's place. Lives as long as the process. */
class Navigator {
    var tab by mutableStateOf(Tab.CONNECTIONS)
        private set

    private var sheets by mutableIntStateOf(0)

    /** True while a sheet covers the screen; the tab pill hides. */
    val sheetOpen: Boolean get() = sheets > 0

    private val stacks: Map<Tab, SnapshotStateList<Screen>> = Tab.entries.associateWith { mutableStateListOf(root(it)) }
    private val models = HashMap<Screen, ScreenModel>()

    val current: Screen get() = stacks.getValue(tab).last()
    val canGoBack: Boolean get() = stacks.getValue(tab).size > 1 || tab != Tab.CONNECTIONS

    /** Tapping the current tab again pops it to its root. */
    fun select(target: Tab) {
        if (target == tab) popTo(1) else tab = target
    }

    fun push(screen: Screen) {
        stacks.getValue(tab).add(screen)
    }

    fun back(): Boolean = when {
        stacks.getValue(tab).size > 1 -> {
            popTo(stacks.getValue(tab).size - 1)
            true
        }
        tab != Tab.CONNECTIONS -> {
            tab = Tab.CONNECTIONS
            true
        }
        else -> false
    }

    fun sheetShown() {
        sheets++
    }

    fun sheetHidden() {
        sheets--
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : ScreenModel> model(screen: Screen, make: () -> T): T = models.getOrPut(screen, make) as T

    /** The model of a tab's root screen, created if that tab was never opened. */
    fun <T : ScreenModel> rootModel(target: Tab, make: () -> T): T = model(root(target), make)

    private fun popTo(size: Int) {
        val stack = stacks.getValue(tab)
        while (stack.size > size) {
            val gone = stack.removeAt(stack.lastIndex)
            if (stacks.values.none { gone in it }) models.remove(gone)?.close()
        }
    }

    private fun root(tab: Tab): Screen = when (tab) {
        Tab.CONNECTIONS -> Screen.Connections
        Tab.QUERY -> Screen.Query
        Tab.HISTORY -> Screen.History
        Tab.SETTINGS -> Screen.Settings
    }
}
