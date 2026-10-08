package com.dby.mobile.data

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** Settings (spec §4): SharedPreferences, mirrored into Compose state so screens redraw. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var readOnlyProd by stored(true)
    var confirmWrites by stored(true)
    var appLock by stored(false)
    var textSize by stored(TEXT_LARGE)
    var rowsAsCards by stored(true)
    var rowsPerPage by stored(50)
    var reduceBlur by stored(false)
    var accent by stored(0xFF5AC8FA.toInt())
    var reconnect by stored(true)
    var updateChecks by stored(true)
    var lastUpdateCheckMs by stored(0L)
    var seenVersionCode by stored(0)
    /** Notes of the update being installed, shown once after it lands. */
    var whatsNew by stored("")

    /** Tables pinned on the Explorer, per connection and database. */
    fun pinnedTables(connectionId: String, database: String): Set<String> =
        sp.getStringSet("pins:$connectionId:$database", null)?.toSet() ?: emptySet()

    fun setPinnedTables(connectionId: String, database: String, tables: Set<String>) {
        sp.edit().putStringSet("pins:$connectionId:$database", tables).apply()
    }

    private fun <T> stored(default: T) = Stored(default)

    /** One setting: read once, kept as Compose state, written through on change. Its key is the property name. */
    private inner class Stored<T>(private val default: T) : ReadWriteProperty<Any?, T> {
        private var state: MutableState<T>? = null

        private fun state(key: String): MutableState<T> = state ?: mutableStateOf(read(key, default)).also { state = it }

        override fun getValue(thisRef: Any?, property: KProperty<*>): T = state(property.name).value

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            state(property.name).value = value
            val edit = sp.edit()
            when (value) {
                is Boolean -> edit.putBoolean(property.name, value)
                is Int -> edit.putInt(property.name, value)
                is Long -> edit.putLong(property.name, value)
                is String -> edit.putString(property.name, value)
                else -> error("unsupported setting type")
            }
            edit.apply()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> read(key: String, default: T): T = when (default) {
        is Boolean -> sp.getBoolean(key, default)
        is Int -> sp.getInt(key, default)
        is Long -> sp.getLong(key, default)
        is String -> sp.getString(key, default)
        else -> error("unsupported setting type")
    } as T

    companion object {
        const val TEXT_DEFAULT = "default"
        const val TEXT_LARGE = "large"
        const val TEXT_LARGER = "larger"
    }
}
