package com.dby.mobile.ui.settings

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import com.dby.mobile.ui.theme.LocalAccent
import com.dby.mobile.update.Updater
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.dby.core.coreVersion
import com.dby.mobile.BuildConfig
import com.dby.mobile.DbyApp
import com.dby.mobile.Logo
import com.dby.mobile.bottomSpace
import com.dby.mobile.data.AppLock
import com.dby.mobile.topSpace
import com.dby.mobile.ui.common.GroupCard
import com.dby.mobile.ui.common.Hairline
import com.dby.mobile.ui.common.LargeTitle
import com.dby.mobile.ui.common.ListRow
import com.dby.mobile.ui.common.ProblemBanner
import com.dby.mobile.ui.common.SectionHeader
import com.dby.mobile.ui.common.Segmented
import com.dby.mobile.ui.common.ToggleRow
import com.dby.mobile.ui.glass.GlassHost
import com.dby.mobile.ui.nav.Screen
import com.dby.mobile.ui.theme.Dby
import com.dby.mobile.data.Prefs
import com.dby.mobile.ui.theme.Type
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(app: DbyApp) {
    val prefs = app.prefs
    val nav = app.nav
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    var problem by remember { mutableStateOf<Throwable?>(null) }
    var busy by remember { mutableStateOf(false) }

    /** App lock: unlock first (the locked key needs it), move every password to the new key, then flip the setting. */
    fun setAppLock(on: Boolean) {
        val a = activity ?: return
        scope.launch {
            busy = true
            problem = null
            try {
                if (!AppLock.unlock(a, if (on) "Turn on App lock" else "Turn off App lock")) return@launch
                app.sessions.rekey(locked = on)
                prefs.appLock = on
                if (on) a.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else a.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = e
            } finally {
                busy = false
            }
        }
    }

    val updater = app.updater
    var showUpdate by remember { mutableStateOf(false) }
    var asked by remember { mutableStateOf(false) }
    val state = updater.state
    val hasRelease = state is Updater.State.Available || state is Updater.State.Downloading || state is Updater.State.Ready ||
        (state is Updater.State.Failed && state.release != null)
    // A check started from here opens the sheet as soon as it finds something.
    LaunchedEffect(state) {
        if (asked && state !is Updater.State.Checking) {
            asked = false
            if (state is Updater.State.Available) showUpdate = true
        }
    }

    GlassHost(overlay = { backdrop -> if (showUpdate) UpdateSheet(backdrop, updater) { showUpdate = false } }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = topSpace(), bottom = bottomSpace())) {
            Row(Modifier.padding(horizontal = 16.dp).height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                Logo()
                Text("DBY", style = Type.Secondary, color = Dby.Secondary, modifier = Modifier.padding(start = 8.dp))
            }
            LargeTitle("Settings", Modifier.padding(top = 4.dp))
            problem?.let { ProblemBanner(it, modifier = Modifier.padding(top = 12.dp)) }

            SectionHeader("Safety")
            GroupCard {
                ToggleRow("Read-only on PROD", "Block edits on production connections unless you unlock them.", prefs.readOnlyProd, { prefs.readOnlyProd = it })
                Hairline()
                ToggleRow("Confirm before saving", "Show the SQL and ask before any update, insert or delete.", prefs.confirmWrites, { prefs.confirmWrites = it })
                Hairline()
                ToggleRow(
                    "App lock",
                    if (AppLock.supported) "Fingerprint or screen lock to open DBY and use saved passwords." else "Needs Android 9 or newer.",
                    prefs.appLock,
                    { setAppLock(it) },
                    enabled = AppLock.supported && !busy,
                )
            }

            SectionHeader("Reading")
            GroupCard {
                Column(Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Text size", style = Type.Body)
                    Segmented(listOf(Prefs.TEXT_DEFAULT, Prefs.TEXT_LARGE, Prefs.TEXT_LARGER), prefs.textSize, { prefs.textSize = it }, { it.replaceFirstChar(Char::uppercase) }, Modifier.fillMaxWidth())
                }
                Hairline()
                Column(Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Show rows as", style = Type.Body)
                    Segmented(listOf(true, false), prefs.rowsAsCards, { prefs.rowsAsCards = it }, { if (it) "Cards" else "Grid" }, Modifier.fillMaxWidth())
                }
                Hairline()
                Column(Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Rows per page", style = Type.Body)
                    Segmented(listOf(25, 50, 100), prefs.rowsPerPage, { prefs.rowsPerPage = it }, { it.toString() }, Modifier.fillMaxWidth())
                }
                Hairline()
                ToggleRow("Reduce blur", "Solid glass instead of live blur. Saves battery.", prefs.reduceBlur, { prefs.reduceBlur = it })
                Hairline()
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Accent", style = Type.Body, modifier = Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (color in Dby.Accents) {
                            val argb = color.value.toLong().ushr(32).toInt()
                            val on = prefs.accent == argb
                            Box(
                                Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .border(if (on) 3.dp else 0.dp, Dby.Fg, CircleShape)
                                    .clickable(role = Role.RadioButton) { prefs.accent = argb },
                            )
                        }
                    }
                }
            }

            SectionHeader("Connections")
            GroupCard {
                ToggleRow("Reconnect automatically", "Check open connections when DBY comes back to the screen.", prefs.reconnect, { prefs.reconnect = it })
            }

            SectionHeader("About")
            GroupCard {
                ListRow("Version", subtitle = "${BuildConfig.VERSION_NAME} · core ${coreVersion()}", trailing = {})
                if (BuildConfig.IN_APP_UPDATES) {
                    Hairline()
                    ListRow(
                        "Check for updates",
                        subtitle = when (state) {
                            Updater.State.Idle -> null
                            Updater.State.Checking -> "Checking…"
                            Updater.State.UpToDate -> "DBY is up to date."
                            is Updater.State.Available -> "Version ${state.release.versionName} is available."
                            is Updater.State.Downloading -> "Downloading ${state.release.versionName}…"
                            is Updater.State.Ready -> state.message ?: "Version ${state.release.versionName} is ready to install."
                            is Updater.State.Failed -> state.message
                        },
                        onClick = {
                            updater.unseen = false
                            if (hasRelease) {
                                showUpdate = true
                            } else {
                                asked = true
                                updater.check(manual = true)
                            }
                        },
                        titleExtra = {
                            if (updater.unseen) Box(Modifier.padding(start = 8.dp).size(8.dp).clip(CircleShape).background(LocalAccent.current))
                        },
                    )
                    Hairline()
                    ToggleRow("Check automatically", "Looks for a new version on GitHub at most once an hour.", prefs.updateChecks, { prefs.updateChecks = it })
                }
                Hairline()
                ListRow("Licences", onClick = { nav.push(Screen.Licences) })
            }
        }
    }
}
