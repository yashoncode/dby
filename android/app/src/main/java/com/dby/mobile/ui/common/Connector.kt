package com.dby.mobile.ui.common

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dby.core.SavedConnection
import com.dby.core.Session
import com.dby.mobile.data.Sessions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Opening a saved connection from a screen (spec §8): the saved password if it can be read,
 * otherwise the screen shows [PasswordSheet] while [asking] is set. Logs the M0 benchmark's
 * `connect` time, from tap until [then] has run (the table list is on screen).
 */
class Connector(private val sessions: Sessions, private val scope: CoroutineScope) {
    var asking by mutableStateOf<SavedConnection?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var problem by mutableStateOf<Throwable?>(null)
        private set

    private var then: (suspend (Session) -> Unit)? = null
    private var started = 0L

    fun open(saved: SavedConnection, then: suspend (Session) -> Unit) {
        this.then = then
        problem = null
        started = SystemClock.elapsedRealtime()
        scope.launch {
            busy = true
            try {
                val session = sessions.open[saved.id]
                if (session != null) {
                    then(session)
                    return@launch
                }
                val password = sessions.savedPassword(saved)
                if (password == null) {
                    asking = saved
                } else {
                    connect(saved, password, remember = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = e
            } finally {
                busy = false
            }
        }
    }

    fun submit(password: String, remember: Boolean) {
        val saved = asking ?: return
        asking = null
        scope.launch {
            busy = true
            try {
                connect(saved, password, remember)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = e
            } finally {
                busy = false
            }
        }
    }

    fun dismiss() {
        asking = null
    }

    private suspend fun connect(saved: SavedConnection, password: String, remember: Boolean) {
        val session = sessions.connect(saved, password)
        if (remember) sessions.rememberPassword(saved, password)
        then?.invoke(session)
        Log.i("DBYBENCH", "app=dby event=connect ms=${SystemClock.elapsedRealtime() - started} rows=0")
    }
}
