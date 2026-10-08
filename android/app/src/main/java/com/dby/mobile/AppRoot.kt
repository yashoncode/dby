package com.dby.mobile

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.dby.mobile.ui.common.rememberHaptics
import com.dby.mobile.ui.history.HistoryScreen
import com.dby.mobile.ui.query.QueryScreen
import com.dby.mobile.ui.settings.LicencesScreen
import com.dby.mobile.ui.settings.SettingsScreen
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dby.mobile.data.AppLock
import com.dby.mobile.ui.connections.ConnectionsScreen
import com.dby.mobile.ui.connections.EditConnectionScreen
import com.dby.mobile.ui.explorer.ExplorerScreen
import com.dby.mobile.ui.table.TableScreen
import com.dby.mobile.ui.DbyIcons
import com.dby.mobile.ui.glass.GlassHost
import com.dby.mobile.ui.glass.liquidGlass
import com.dby.mobile.ui.nav.Navigator
import com.dby.mobile.ui.nav.Screen
import com.dby.mobile.ui.nav.Tab
import com.dby.mobile.ui.theme.Dby
import com.dby.mobile.ui.theme.LocalAccent
import com.dby.mobile.ui.theme.Type
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.launch

@Composable
fun AppRoot(app: DbyApp) {
    val nav = app.nav
    BackHandler(enabled = nav.canGoBack && !app.locked) { nav.back() }
    GlassHost(
        overlay = { backdrop ->
            val screen = nav.current
            if (!nav.sheetOpen && screen !is Screen.EditConnection && screen !is Screen.Licences) {
                TabPill(nav, backdrop, Modifier.align(Alignment.BottomCenter))
            }
            if (app.locked) LockScreen(app)
        },
    ) {
        key(nav.tab, nav.current) {
            when (val screen = nav.current) {
                Screen.Connections -> ConnectionsScreen(app)
                is Screen.EditConnection -> EditConnectionScreen(app, screen.id)
                is Screen.Explorer -> ExplorerScreen(app, screen.connectionId)
                is Screen.Table -> TableScreen(app, screen.connectionId, screen.table)
                Screen.Query -> QueryScreen(app)
                Screen.History -> HistoryScreen(app)
                Screen.Settings -> SettingsScreen(app)
                Screen.Licences -> LicencesScreen(app)
            }
        }
    }
}

/** Space under scrolling content for the floating tab pill and the gesture bar. */
@Composable
fun bottomSpace(extra: Dp = 0.dp): Dp = 112.dp + extra + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

/** Space above content for the status bar. */
@Composable
fun topSpace(): Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp

private fun Tab.label() = when (this) {
    Tab.CONNECTIONS -> "Connections"
    Tab.QUERY -> "Query"
    Tab.HISTORY -> "History"
    Tab.SETTINGS -> "Settings"
}

private fun Tab.icon(): ImageVector = when (this) {
    Tab.CONNECTIONS -> DbyIcons.Database
    Tab.QUERY -> DbyIcons.Query
    Tab.HISTORY -> DbyIcons.History
    Tab.SETTINGS -> DbyIcons.Settings
}

/** The floating liquid-glass tab bar from the canvas. */
@Composable
fun TabPill(nav: Navigator, backdrop: Backdrop, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    Row(
        modifier
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 20.dp)
            .fillMaxWidth()
            .liquidGlass(backdrop)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val haptic = rememberHaptics()
        for (tab in Tab.entries) {
            val selected = tab == nav.tab
            val fill by animateColorAsState(if (selected) Color(0x24FFFFFF) else Color.Transparent, label = "tab")
            val tint = if (selected) accent else Color(0xB8FFFFFF)
            Column(
                Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(fill)
                    .selectable(selected = selected, role = Role.Tab) { if (!selected) haptic(HapticFeedbackType.SegmentTick); nav.select(tab) }
                    .padding(vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Icon(tab.icon(), contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                Text(tab.label(), style = Type.Tab, color = tint, maxLines = 1)
            }
        }
    }
}

/** The DBY mark: three lines on a rounded teal square. */
@Composable
fun Logo(size: Dp = 24.dp) {
    // The launcher icon's mark (res/drawable/ic_launcher_*), drawn from the same 108-unit
    // coordinates, cropped to the glyph so it reads at 24 dp.
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        drawRoundRect(
            Brush.linearGradient(listOf(Color(0xFF2FB4F0), Color(0xFF6E4BFF)), end = Offset(w, w)),
            cornerRadius = CornerRadius(w * 0.3f),
        )
        val d = Path().apply {
            moveTo(38f, 32f)
            lineTo(52f, 32f)
            arcTo(Rect(30f, 32f, 74f, 76f), -90f, 180f, false)
            lineTo(38f, 76f)
            close()
        }
        val rings = Path().apply {
            moveTo(38f, 47f)
            quadraticTo(55.5f, 54f, 72.9f, 47f)
            moveTo(38f, 61f)
            quadraticTo(55.5f, 68f, 72.9f, 61f)
        }
        val scale = w / 72f
        withTransform({
            translate(w / 2 - 55.5f * scale, w / 2 - 54f * scale)
            scale(scale, scale, Offset.Zero)
        }) {
            drawPath(d, Color.White, style = Stroke(6.5f, join = StrokeJoin.Round))
            drawPath(rings, Color.White, style = Stroke(5f, cap = StrokeCap.Round))
        }
    }
}

/** Covers the app until the person unlocks it; asks at once and on tap. */
@Composable
private fun LockScreen(app: DbyApp) {
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    fun unlock() {
        scope.launch { if (activity != null && AppLock.unlock(activity)) app.locked = false }
    }
    LaunchedEffect(Unit) { unlock() }
    Box(
        Modifier.fillMaxSize().background(Dby.Bg).clickable(interactionSource = null, indication = null) { unlock() },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Logo(56.dp)
            Text("DBY is locked", style = Type.Title)
            Text("Tap to unlock", style = Type.Secondary, color = Dby.Secondary)
        }
    }
}
