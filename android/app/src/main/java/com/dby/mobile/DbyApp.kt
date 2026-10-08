package com.dby.mobile

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dby.core.openStore
import com.dby.mobile.data.AppLock
import com.dby.mobile.data.Prefs
import com.dby.mobile.data.Secrets
import com.dby.mobile.data.Sessions
import com.dby.mobile.ui.nav.Navigator
import com.dby.mobile.update.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Process-wide singletons. There is exactly one of each, so plain properties beat a DI framework. */
class DbyApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val nav = Navigator()
    lateinit var prefs: Prefs
        private set
    lateinit var sessions: Sessions
        private set
    lateinit var updater: Updater
        private set

    /** "Updated to X" and its notes, once after an update; the Connections screen shows it. */
    var whatsNew by mutableStateOf<Pair<String, String>?>(null)

    /** True while App lock covers the screen. */
    var locked by mutableStateOf(false)

    /** When the app last left the screen; 0 until it first does. */
    private var backgroundSince = 0L

    override fun onCreate() {
        super.onCreate()
        instance = this
        openStore(filesDir.absolutePath)
        prefs = Prefs(this)
        sessions = Sessions(prefs, Secrets())
        sessions.reload()
        updater = Updater(this, prefs, scope)
        whatsNew = updater.takeWhatsNew()
    }

    /** From Activity.onStart: a cold start, or a return after a minute away, locks the app. */
    fun onForeground() {
        val away = SystemClock.elapsedRealtime() - backgroundSince
        if (prefs.appLock && AppLock.supported && (backgroundSince == 0L || away > LOCK_AFTER_MS)) locked = true
        if (backgroundSince != 0L && prefs.reconnect) sessions.pingAll(scope)
        updater.check(manual = false)
    }

    fun onBackground() {
        backgroundSince = SystemClock.elapsedRealtime()
    }

    companion object {
        private const val LOCK_AFTER_MS = 60_000L

        lateinit var instance: DbyApp
            private set
    }
}
