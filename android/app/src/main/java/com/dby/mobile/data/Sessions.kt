package com.dby.mobile.data

import android.app.Activity
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dby.core.ConnectionInput
import com.dby.core.DbyException
import com.dby.core.Env
import com.dby.core.SavedConnection
import com.dby.core.Session
import com.dby.core.TlsMode
import com.dby.core.deleteConnection
import com.dby.core.listConnections
import com.dby.core.saveConnection
import java.lang.ref.WeakReference
import javax.crypto.AEADBadTagException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.dby.core.connect as coreConnect
import com.dby.core.setPinned as coreSetPinned

/** What the New/Edit connection form saves. */
data class ConnectionForm(
    val name: String,
    val host: String,
    val port: Int,
    val user: String,
    val database: String,
    val env: Env,
    val tls: TlsMode,
)

/** Saved connections, the sessions open to them, and their passwords. */
class Sessions(private val prefs: Prefs, private val secrets: Secrets) {
    var connections by mutableStateOf<List<SavedConnection>>(emptyList())
        private set

    /** Open sessions by saved-connection id. */
    val open = mutableStateMapOf<String, Session>()

    /** Connections whose resume ping failed; the Explorer offers to reconnect. */
    val lost = mutableStateMapOf<String, Boolean>()

    /** The connection the Query tab runs against. */
    var activeId by mutableStateOf<String?>(null)

    /** For the unlock prompt. MainActivity sets it. */
    var activity: WeakReference<Activity>? = null

    fun reload() {
        connections = try {
            listConnections()
        } catch (e: DbyException) {
            emptyList()
        }
    }

    fun saved(id: String): SavedConnection? = connections.firstOrNull { it.id == id }

    /** Saves a new or changed connection. A null [password] keeps the saved one. */
    suspend fun save(id: String?, form: ConnectionForm, password: String?): SavedConnection {
        val cipher = password?.let { encrypt(it) } ?: id?.let(::saved)?.passwordCipher ?: encrypt("")
        val saved = saveConnection(
            ConnectionInput(id, form.name.trim(), form.host.trim(), form.port.toUShort(), form.user, form.database.trim(), form.env, form.tls, cipher),
        )
        if (id != null) disconnect(id) // so the next open uses the new settings
        reload()
        return saved
    }

    /**
     * Saves imported connections, skipping any already here (same host, port, user and database).
     * Returns how many were added.
     */
    suspend fun import(list: List<Imported>): Int {
        val here = connections.map { listOf(it.host, it.port.toInt(), it.user, it.database) }.toMutableSet()
        var added = 0
        for (c in list) {
            val f = c.form
            if (!here.add(listOf(f.host, f.port, f.user, f.database))) continue
            // An empty cipher means "ask at the first connect".
            val cipher = c.password?.let { encrypt(it) } ?: ByteArray(0)
            saveConnection(ConnectionInput(null, f.name, f.host, f.port.toUShort(), f.user, f.database, f.env, f.tls, cipher))
            added++
        }
        reload()
        return added
    }

    suspend fun delete(id: String) {
        disconnect(id)
        deleteConnection(id)
        if (activeId == id) activeId = null
        reload()
    }

    fun setPinned(id: String, pinned: Boolean) {
        coreSetPinned(id, pinned)
        reload()
    }

    /**
     * The saved password, or null when it must be asked for: none saved, or its key was reset
     * because the screen lock or fingerprints changed. Throws if the person declines the unlock.
     */
    suspend fun savedPassword(saved: SavedConnection): String? = try {
        unlocked { secrets.decrypt(saved.passwordCipher) }
    } catch (e: KeyPermanentlyInvalidatedException) {
        secrets.forget(locked = true)
        null
    } catch (e: AEADBadTagException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    suspend fun connect(saved: SavedConnection, password: String): Session {
        open[saved.id]?.let { return it }
        val session = coreConnect(saved.id, password, saved.env == Env.PROD && prefs.readOnlyProd)
        open[saved.id] = session
        lost.remove(saved.id)
        activeId = saved.id
        reload() // last-used order changed
        return session
    }

    suspend fun rememberPassword(saved: SavedConnection, password: String) {
        saveConnection(saved.input(encrypt(password)))
        reload()
    }

    suspend fun disconnect(id: String) {
        lost.remove(id)
        val session = open.remove(id) ?: return
        session.disconnect()
        session.close()
    }

    /** On resume: each open session pings, which reconnects a dropped connection inside the core. */
    fun pingAll(scope: CoroutineScope) {
        for ((id, session) in open.toMap()) {
            scope.launch {
                try {
                    session.ping()
                    lost.remove(id)
                } catch (e: DbyException) {
                    lost[id] = true
                }
            }
        }
    }

    /**
     * Moves every saved password to the key App lock calls for. Only passwords that provably
     * cannot be read are cleared (and asked for at the next connect); a declined unlock aborts.
     */
    suspend fun rekey(locked: Boolean) {
        for (c in connections) {
            val plain = try {
                unlocked { secrets.decrypt(c.passwordCipher) }
            } catch (e: KeyPermanentlyInvalidatedException) {
                null
            } catch (e: AEADBadTagException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
            val cipher = plain?.let { unlocked { secrets.encrypt(it, locked) } } ?: ByteArray(0)
            saveConnection(c.input(cipher))
        }
        reload()
    }

    private suspend fun encrypt(password: String) = unlocked { secrets.encrypt(password, prefs.appLock) }

    /** Runs [block] off the main thread; if the locked key needs an unlock, prompts once and retries. */
    private suspend fun <T> unlocked(block: () -> T): T = try {
        withContext(Dispatchers.Default) { block() }
    } catch (e: UserNotAuthenticatedException) {
        val activity = activity?.get() ?: throw e
        if (!AppLock.unlock(activity)) throw e
        withContext(Dispatchers.Default) { block() }
    }
}

private fun SavedConnection.input(cipher: ByteArray) =
    ConnectionInput(id, name, host, port, user, database, env, tls, cipher)
