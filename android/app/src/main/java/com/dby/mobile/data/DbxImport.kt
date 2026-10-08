package com.dby.mobile.data

import com.dby.core.Env
import com.dby.core.TlsMode
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** One connection read from a dbx export; a null password is asked for at the first connect. */
class Imported(val form: ConnectionForm, val password: String?)

class DbxFile(val connections: List<Imported>, val skipped: Int)

class WrongPassphrase : Exception("Wrong passphrase.")

private const val ENCRYPTED = "dbx-encrypted"
private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "::1", "10.0.2.2")

fun dbxNeedsPassphrase(text: String): Boolean = (parse(text) as? JSONObject)?.optString("format") == ENCRYPTED

/**
 * Reads dbx's connection export (t8y2/dbx), plain or passphrase-encrypted. Only MySQL and MariaDB
 * come across, and not ones behind an SSH tunnel, which DBY doesn't do; the rest are counted as
 * skipped. Non-local hosts are tagged PROD, so writes stay guarded until the person retags them.
 */
fun readDbx(text: String, passphrase: String?): DbxFile {
    var root = parse(text)
    if (root is JSONObject && root.optString("format") == ENCRYPTED) root = parse(decrypt(root, passphrase.orEmpty()))
    // Old dbx exports are a bare array of connections.
    val list = (root as? JSONArray) ?: (root as JSONObject).optJSONArray("connections") ?: throw IllegalArgumentException(NOT_DBX)
    val connections = mutableListOf<Imported>()
    var skipped = 0
    for (i in 0 until list.length()) {
        val c = list.optJSONObject(i) ?: continue
        val host = c.text("host").trim()
        val tunnelled = (c.optJSONArray("transport_layers")?.length() ?: 0) > 0
        if (c.text("db_type").lowercase() !in setOf("mysql", "mariadb") || host.isEmpty() || tunnelled) {
            skipped++
            continue
        }
        val form = ConnectionForm(
            name = c.text("name").ifBlank { host },
            host = host,
            port = c.optInt("port", 3306),
            user = c.text("username"),
            database = c.text("database"),
            env = if (host in LOCAL_HOSTS) Env.LOCAL else Env.PROD,
            tls = if (c.optBoolean("ssl")) TlsMode.VERIFY else TlsMode.OFF,
        )
        // dbx blanks every password in an export without a passphrase, so blank means "ask".
        val password = c.text("password").takeIf { it.isNotEmpty() && c.optBoolean("save_password", true) }
        connections += Imported(form, password)
    }
    return DbxFile(connections, skipped)
}

private const val NOT_DBX = "This isn't a dbx connections export."

private fun parse(text: String): Any = try {
    if (text.trimStart().startsWith("[")) JSONArray(text) else JSONObject(text)
} catch (e: JSONException) {
    throw IllegalArgumentException(NOT_DBX)
}

/** org.json's optString turns a JSON null into "null". */
private fun JSONObject.text(key: String) = if (isNull(key)) "" else optString(key)

/** dbx's configCrypto.ts: PBKDF2-SHA256 with 100,000 rounds into an AES-256-GCM key. */
private fun decrypt(payload: JSONObject, passphrase: String): String {
    if (passphrase.isEmpty()) throw WrongPassphrase()
    val b64 = Base64.getDecoder()
    val salt = b64.decode(payload.optString("salt"))
    val spec = PBEKeySpec(passphrase.toCharArray(), salt, 100_000, 256)
    val key = SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, b64.decode(payload.optString("iv"))))
    return try {
        String(cipher.doFinal(b64.decode(payload.optString("data"))), Charsets.UTF_8)
    } catch (e: AEADBadTagException) {
        throw WrongPassphrase()
    }
}
