package com.dby.mobile.update

import android.app.DownloadManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dby.mobile.BuildConfig
import com.dby.mobile.DbyApp
import com.dby.mobile.data.Prefs
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

class Release(val versionName: String, val versionCode: Long, val url: String, val size: Long, val sha256: String, val notes: List<String>)

/**
 * The newest release in a `dby.json` manifest (spec §10) if it is newer than [installed], with
 * the notes of every version newer than [installed], newest first. Null when up to date.
 */
fun parseManifest(json: String, installed: Long): Release? {
    val root = try {
        JSONObject(json)
    } catch (e: JSONException) {
        throw IllegalArgumentException("The update manifest can't be read.")
    }
    val pkg = root.optJSONObject("package") ?: throw IllegalArgumentException("The update manifest has no package.")
    val versions = root.keys().asSequence().filter { it != "package" }
        .mapNotNull { name -> root.optJSONObject(name)?.let { name to it } }
        .map { (name, v) -> Triple(name, v.optLong("versionCode"), v.optJSONArray("changelog")) }
        .filter { it.second > installed }
        .sortedByDescending { it.second }
        .toList()
    val newest = versions.firstOrNull() ?: return null
    val notes = versions.flatMap { (_, _, log) -> (0 until (log?.length() ?: 0)).map { log!!.optString(it) } }
    return Release(newest.first, newest.second, pkg.optString("downloadUrl"), pkg.optLong("downloadSize"), pkg.optString("sha256"), notes)
}

/**
 * Updates from GitHub Releases (spec §10): check the manifest, download with DownloadManager only
 * when asked, check the file, install through a PackageInstaller session.
 */
class Updater(private val context: Context, private val prefs: Prefs, private val scope: CoroutineScope) {
    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val fraction: Float) : State
        /** [message]: why the last install attempt stopped; the file is kept for another try. */
        data class Ready(val release: Release, val file: File, val message: String? = null) : State
        data class Failed(val message: String, val release: Release? = null) : State
    }

    var state by mutableStateOf<State>(State.Idle)
        private set

    /** An update was found that Settings hasn't shown yet: the dot on the Update row. */
    var unseen by mutableStateOf(false)

    private var failedAtMs = 0L
    private var download: Job? = null
    private var downloadId = -1L
    private val downloads get() = context.getSystemService(DownloadManager::class.java)

    /** [manual] from Settings: always checks and shows failures. Otherwise throttled and silent. */
    fun check(manual: Boolean) {
        if (!BuildConfig.IN_APP_UPDATES) return
        val now = System.currentTimeMillis()
        if (!manual) {
            if (!prefs.updateChecks || state is State.Checking || state is State.Downloading || state is State.Ready) return
            if (now - prefs.lastUpdateCheckMs < HOUR_MS || now - failedAtMs < RETRY_MS) return
        }
        scope.launch {
            val before = state
            if (manual) state = State.Checking
            try {
                val json = withContext(Dispatchers.IO) { fetch(MANIFEST_URL) }
                val release = parseManifest(json, BuildConfig.VERSION_CODE.toLong())
                prefs.lastUpdateCheckMs = System.currentTimeMillis()
                state = if (release == null) State.UpToDate else State.Available(release)
                if (release != null && !manual) unseen = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failedAtMs = System.currentTimeMillis()
                state = if (manual) State.Failed("Couldn't check for updates: ${e.message ?: e.javaClass.simpleName}") else before
            }
        }
    }

    fun download(release: Release) {
        download?.cancel()
        val name = "dby-${release.versionName}.apk"
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), name)
        file.delete()
        downloadId = downloads.enqueue(
            DownloadManager.Request(Uri.parse(release.url))
                .setTitle("DBY ${release.versionName}")
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, name),
        )
        state = State.Downloading(release, 0f)
        download = scope.launch {
            try {
                while (true) {
                    val (status, done, total) = withContext(Dispatchers.IO) { progress(downloadId) } ?: break
                    if (status == DownloadManager.STATUS_SUCCESSFUL) break
                    if (status == DownloadManager.STATUS_FAILED) throw IllegalStateException("The download failed.")
                    val size = if (total > 0) total else release.size
                    state = State.Downloading(release, if (size > 0) done.toFloat() / size else 0f)
                    delay(250)
                }
                withContext(Dispatchers.IO) { verify(file, release) }
                prefs.whatsNew = release.notes.joinToString("\n")
                state = State.Ready(release, file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                file.delete()
                state = State.Failed(e.message ?: "The download failed.", release)
            }
        }
    }

    fun cancel() {
        download?.cancel()
        if (downloadId >= 0) downloads.remove(downloadId)
        downloadId = -1
        (state as? State.Downloading)?.let { state = State.Available(it.release) }
    }

    /** Hands the checked file to the system installer, which asks the person to confirm. */
    fun install(file: File) {
        try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(context.packageName)
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("dby.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val intent = Intent(context, InstallReceiver::class.java)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
            }
        } catch (e: Exception) {
            failed(e.message ?: "The install couldn't start.")
        }
    }

    fun failed(message: String) {
        state = when (val s = state) {
            is State.Ready -> s.copy(message = message)
            is State.Available -> State.Failed(message, s.release)
            else -> State.Failed(message)
        }
    }

    /** Once after an update: "Updated to X" and the notes saved before installing. Null otherwise. */
    fun takeWhatsNew(): Pair<String, String>? {
        val seen = prefs.seenVersionCode
        prefs.seenVersionCode = BuildConfig.VERSION_CODE
        if (seen == 0 || seen >= BuildConfig.VERSION_CODE) return null
        val notes = prefs.whatsNew
        prefs.whatsNew = ""
        return "Updated to ${BuildConfig.VERSION_NAME}" to notes
    }

    private fun progress(id: Long): Triple<Int, Long, Long>? =
        downloads.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) return null
            Triple(
                c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
            )
        }

    /** The manifest's SHA-256 first, then the archive itself: this app, the advertised version. */
    private fun verify(file: File, release: Release) {
        if (!file.exists()) throw IllegalStateException("The download was cancelled.")
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (!sha.equals(release.sha256, ignoreCase = true)) throw IllegalStateException("The download is damaged (checksum mismatch).")
        val info = context.packageManager.getPackageArchiveInfo(file.path, 0)
            ?: throw IllegalStateException("The download isn't a valid app.")
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        if (info.packageName != context.packageName || code != release.versionCode) {
            throw IllegalStateException("The download isn't DBY ${release.versionName}.")
        }
    }

    private fun fetch(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        try {
            // After redirects, url is the host that answered: github.com or its download server.
            if (connection.responseCode != 200) throw IllegalStateException("${connection.url.host} answered ${connection.responseCode}.")
            return connection.inputStream.use { it.reader().readText() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val MANIFEST_URL = "https://github.com/yashoncode/dby/releases/latest/download/dby.json"
        private const val HOUR_MS = 3_600_000L
        private const val RETRY_MS = 300_000L
    }
}

/** The installer's answer: open its confirmation screen, or report why it stopped. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_FAILURE_ABORTED -> DbyApp.instance.updater.failed("The install was cancelled.")
            else -> DbyApp.instance.updater.failed(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "The install failed ($status).")
        }
    }
}
