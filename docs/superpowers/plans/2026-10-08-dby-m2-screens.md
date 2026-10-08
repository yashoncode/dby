# DBY M2 — Screens Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the M0 bench screen with the real app: the six canvas screens plus History, New/Edit connection and the cell viewer, in the canvas's glass style, with saved passwords in the Android Keystore and App lock.

**Architecture:** One activity, Compose only. A process-wide `DbyApp` holds `Prefs`, `Sessions` (open core sessions, saved connections, Keystore secrets) and a `Navigator` with one back stack per tab; each stacked screen owns a small `ScreenModel` (state + coroutine scope) that the navigator closes when the screen is popped. Glass comes from Kyant0's backdrop library: every screen records its content (with the background) as a backdrop, and floating controls draw liquid or frosted glass from it; cards use light glass with no capture. Sheets are drawn inside the screen, not in windows, so they can blur what is under them.

**Tech Stack:** Kotlin 2.4, Jetpack Compose (BOM alpha 2026.08.00), Kyant0 backdrop 2.0.1, Android Keystore (AES-256-GCM), framework `BiometricPrompt` (API 28+), the M1 `com.dby.core` bindings, JUnit 4 for JVM tests.

**Spec:** `docs/superpowers/specs/2026-10-08-dby-design.md` (§2 scope, §7 safety, §8 data flows, §11 glass, §12 visual design). UI source: the "DBY Mobile — frosted glass" canvas (artboards Main, Explorer, Table, Row, Query, Settings).

## Global Constraints

- minSdk 26, compileSdk 37, targetSdk 36; application id `com.dby.mobile`.
- Background `#0B0B0E`; text `#F5F5F7`, `rgba(255,255,255,0.70)`, `rgba(255,255,255,0.62)`; accent `#5AC8FA` (setting: `#30D158`, `#FF9F0A`, `#BF5AF2`).
- Env badges: PROD `#FF9A92` on `rgba(255,69,58,0.20)`; STAGING `#FFC56B` on `rgba(255,159,10,0.20)`; DEV `#9BDFFF` on `rgba(90,200,250,0.18)`; LOCAL `#86E8A0` on `rgba(48,209,88,0.18)`.
- SQL colours: keyword `#FF8FB8`, string `#FFC27A`, number `#C9B3FF`, plain `#F5F5F7`.
- Type: Geist for UI (34/41 bold titles, 20/25 sections, 17/22 rows, 15/20 secondary, 11/13 tab labels); Geist Mono for identifiers, values and SQL. Radii: cards 22 dp, chips pill, sheets 32 dp top. Touch targets ≥ 44 dp. Hairlines 0.5 dp `rgba(255,255,255,0.12)` inset to the text column.
- Glass: liquid (vibrancy ×1.5, blur 8 dp, lens 24/24 dp with depth and chromatic aberration on API 33+, tint `#121212` 40%) for the tab pill and floating buttons; frosted (blur 24 dp, saturation 1.8, tint `rgba(28,28,33,0.5)`) for the pager bar and sheets; light glass (fill `rgba(28,28,33,0.5)`, top highlight, 0.5 dp border `rgba(255,255,255,0.14)`) for cards, lists, fields, chips. Blur only over scrolling content. API 26–30 and "Reduce blur" draw solid `#1C1C21`.
- Read-only on PROD and Confirm before saving are on by default. Every write shows its SQL first. Row edits that match ≠ 1 row are rolled back by the core.
- Passwords: AES-256-GCM Keystore key; with App lock on, the key needs a fingerprint or screen-lock unlock in the last 5 minutes; invalidated keys lead to asking for the password again.
- No HTTP except HTTPS to GitHub (M3); no `cleartextTrafficPermitted`.
- Keep `DBYBENCH app=dby event=<connect|first_page|next_page|sql> ms=<n> rows=<n>` log lines so the M0 gate can run on the real app.

## Review Focus

1. A connection whose saved password can no longer be decrypted (fingerprints changed, App lock toggled, empty blob): connecting must ask for the password, not fail or crash (Task 5 `Connector`, Task 9 rekey).
2. Turning App lock on or off while the unlock prompt is declined must not wipe saved passwords (Task 9 `Sessions.rekey` only clears blobs that are provably unreadable).
3. A trimmed TEXT value or a BLOB in the Edit row sheet must never be written back as its preview (Task 7: such fields are read-only until the full value is loaded; binary is never editable).
4. A PROD connection opened read-only: every write entry point (Edit row commit, insert, delete, Query tab writes) must be refused before reaching the server, with a way to unlock on the Explorer (Tasks 5, 7, 8).
5. Back navigation: system back closes an open sheet first, then pops the tab's stack, then returns to Connections, then leaves the app; it never navigates behind the App lock screen (Tasks 2, 3).

---

## File Structure

```
android/app/
  build.gradle.kts                     Kyant backdrop instead of Haze; buildConfig; JUnit; version 0.1.0
  src/main/AndroidManifest.xml         DbyApp, USE_BIOMETRIC, icon, dark window
  src/main/res/font/geist.ttf, geist_mono.ttf
  src/main/res/drawable/ic_launcher_foreground.xml, res/mipmap-anydpi-v26/ic_launcher*.xml
  src/main/res/values/themes.xml, colors.xml
  src/main/assets/licences/OFL-Geist.txt
  src/main/java/com/dby/mobile/
    DbyApp.kt                          singletons, foreground/background, App lock trigger
    MainActivity.kt                    content root, FLAG_SECURE with App lock
    AppRoot.kt                         screen switch, tab pill, lock screen
    data/Prefs.kt                      settings as Compose state
    data/Secrets.kt                    Keystore encrypt/decrypt
    data/AppLock.kt                    BiometricPrompt wrapper
    data/Sessions.kt                   saved connections, open sessions, passwords, rekey
    data/Format.kt                     cell text, counts, sizes, times, error sentences
    ui/Icons.kt                        stroke icons from the canvas
    ui/Sql.kt                          SQL colouring
    ui/theme/Theme.kt                  tokens, fonts, type, DbyTheme
    ui/glass/Glass.kt                  light, liquid, frosted glass; GlassHost
    ui/nav/Nav.kt                      Tab, Screen, ScreenModel, Navigator
    ui/common/Widgets.kt               buttons, rows, chips, fields, sheets, banners
    ui/common/Connector.kt             connect-with-saved-password-or-ask flow
    ui/common/Grid.kt                  result grid shared by Table and Query
    ui/connections/ConnectionsScreen.kt, EditConnectionScreen.kt
    ui/explorer/ExplorerScreen.kt
    ui/table/TableScreen.kt, TableModel.kt, RowSheet.kt, CellViewer.kt
    ui/query/QueryScreen.kt, QueryModel.kt
    ui/history/HistoryScreen.kt
    ui/settings/SettingsScreen.kt, LicencesScreen.kt
  src/test/java/com/dby/mobile/        FormatTest, SqlTest, NavigatorTest
  (deleted) BenchScreen.kt, BenchViewModel.kt
```

---

### Task 1: Build setup, theme, icons, glass, navigation, formatting, SQL colouring

**Files:**
- Modify: `android/app/build.gradle.kts`
- Create: `android/app/src/main/res/font/geist.ttf`, `geist_mono.ttf`, `android/app/src/main/assets/licences/OFL-Geist.txt`
- Create: `ui/theme/Theme.kt`, `ui/Icons.kt`, `ui/glass/Glass.kt`, `ui/nav/Nav.kt`, `data/Format.kt`, `ui/Sql.kt`, `data/Prefs.kt` (Theme needs it)
- Test: `src/test/java/com/dby/mobile/FormatTest.kt`, `SqlTest.kt`, `NavigatorTest.kt`

(All Kotlin paths below are under `android/app/src/main/java/com/dby/mobile/` unless they start with `android/`.)

**Interfaces:**
- Produces: `Dby` colour tokens, `Env.colors()`, `LocalAccent`, `Geist`, `GeistMono`, `Type.*`, `DbyTheme(prefs, content)`; `DbyIcons.*`; `blurOn()`, `Modifier.lightGlass(shape)`, `Modifier.solidGlass(shape)`, `Modifier.liquidGlass(backdrop, shape)`, `Modifier.frostedGlass(backdrop, shape)`, `GlassHost(modifier, overlay, content)`; `Tab`, `Screen`, `ScreenModel`, `Navigator`; `Cell.display()`, `Cell.rawText()`, `Cell.isTrimmed()`, `count(Long)`, `bytes(Long)`, `ago(atMs, nowMs)`, `Throwable.sentence()`, `Throwable.details()`; `sqlSpans(sql)`, `highlightSql(sql)`, `SqlTransformation`; `Prefs`.

- [ ] **Step 1: Write `android/app/build.gradle.kts`**

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.dby.mobile"
    // 37: the Compose alpha BOM requires it. targetSdk stays 36.
    compileSdk = 37
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "com.dby.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "0.1.0"
        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // M3 replaces this with the release key.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Compose's snapshot state calls android.os.Trace, which the JVM test stubs would throw on.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

// The Rust core is built and its Kotlin bindings generated before every build. Both outputs
// are git-ignored. cargo is incremental, so an unchanged core costs a second or two.
val coreDir = rootDir.resolve("../core")
val jniLibsDir = projectDir.resolve("src/main/jniLibs")
val bindingsDir = projectDir.resolve("src/main/java")
val ndkHome = "${System.getenv("ANDROID_HOME")}/ndk/30.0.16248370"

val buildRustCore by tasks.registering(Exec::class) {
    description = "Builds core/ for arm64-v8a and x86_64 into src/main/jniLibs."
    workingDir = coreDir
    environment("ANDROID_NDK_HOME", ndkHome)
    commandLine(
        "cargo", "ndk", "-t", "arm64-v8a", "-t", "x86_64", "--platform", "26",
        "-o", jniLibsDir.absolutePath, "build", "--release",
    )
}

// The release .so is stripped, which removes the symbols UniFFI reads its interface from, so
// the bindings come from an unstripped host build of the same source.
val buildHostCore by tasks.registering(Exec::class) {
    description = "Builds core/ for this machine, only to read its UniFFI interface."
    workingDir = coreDir
    commandLine("cargo", "build", "--lib")
}

val generateBindings by tasks.registering(Exec::class) {
    description = "Generates the Kotlin bindings for core/ into src/main/java/com/dby/core."
    dependsOn(buildRustCore, buildHostCore)
    workingDir = coreDir
    val os = System.getProperty("os.name")
    val hostLibrary = when {
        os.startsWith("Windows") -> "target/debug/dby_core.dll"
        os.startsWith("Mac") -> "target/debug/libdby_core.dylib"
        else -> "target/debug/libdby_core.so"
    }
    commandLine(
        "cargo", "run", "-p", "uniffi-bindgen", "--", "generate",
        "--library", coreDir.resolve(hostLibrary).absolutePath,
        "--language", "kotlin", "--out-dir", bindingsDir.absolutePath, "--no-format",
    )
}

tasks.named("preBuild") { dependsOn(generateBindings) }

dependencies {
    implementation(platform("androidx.compose:compose-bom-alpha:2026.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // UniFFI's Kotlin bindings call the Rust library through JNA.
    implementation("net.java.dev.jna:jna:5.19.1@aar")
    // Liquid and frosted glass (Apache-2.0). Replaces Haze: one capture per screen serves both.
    implementation("io.github.kyant0:backdrop:2.0.1")
    // backdrop declares its shapes dependency as runtime-only; the glass code names its shapes.
    implementation("io.github.kyant0:shapes:1.2.1")
    testImplementation("junit:junit:4.13.2")
}
```

- [ ] **Step 2: Add the fonts and their licence**

```bash
mkdir -p android/app/src/main/res/font android/app/src/main/assets/licences
curl -sfL -o android/app/src/main/res/font/geist.ttf "https://raw.githubusercontent.com/google/fonts/main/ofl/geist/Geist%5Bwght%5D.ttf"
curl -sfL -o android/app/src/main/res/font/geist_mono.ttf "https://raw.githubusercontent.com/google/fonts/main/ofl/geistmono/GeistMono%5Bwght%5D.ttf"
curl -sfL -o android/app/src/main/assets/licences/OFL-Geist.txt "https://raw.githubusercontent.com/google/fonts/main/ofl/geist/OFL.txt"
```
Expected: three non-empty files (≈170 KB, ≈170 KB, ≈4 KB).

- [ ] **Step 3: Write the failing JVM tests**

`android/app/src/test/java/com/dby/mobile/FormatTest.kt`:
```kotlin
package com.dby.mobile

import com.dby.core.Cell
import com.dby.core.DbyException
import com.dby.mobile.data.ago
import com.dby.mobile.data.bytes
import com.dby.mobile.data.count
import com.dby.mobile.data.display
import com.dby.mobile.data.isTrimmed
import com.dby.mobile.data.rawText
import com.dby.mobile.data.sentence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatTest {
    @Test
    fun cells_display_exactly() {
        assertEquals("NULL", Cell.Null.display())
        assertEquals("18446744073709551615", Cell.Unsigned(ULong.MAX_VALUE).display())
        assertEquals("-9223372036854775808", Cell.Signed(Long.MIN_VALUE).display())
        assertEquals("12345678901234567890.123456789", Cell.Exact("12345678901234567890.123456789").display())
        assertEquals("abc…", Cell.Text("abc", 9u).display())
        assertEquals("<1,000 bytes>", Cell.Bytes(1000u, byteArrayOf(1)).display())
        assertEquals("0000-00-00 00:00:00", Cell.Temporal("0000-00-00 00:00:00").display())
    }

    @Test
    fun trimming_counts_characters_not_utf16_units() {
        assertFalse(Cell.Text("😀x", 2u).isTrimmed())
        assertTrue(Cell.Text("😀x", 3u).isTrimmed())
        assertTrue(Cell.Bytes(9u, byteArrayOf(1)).isTrimmed())
        assertFalse(Cell.Exact("1").isTrimmed())
    }

    @Test
    fun raw_text_is_what_an_edit_field_starts_with() {
        assertEquals("abc", Cell.Text("abc", 9u).rawText())
        assertNull(Cell.Null.rawText())
        assertNull(Cell.Bytes(1u, byteArrayOf(1)).rawText())
        assertEquals("4820.00", Cell.Exact("4820.00").rawText())
    }

    @Test
    fun sizes_and_counts_read_like_the_canvas() {
        assertEquals("1,248,302", count(1_248_302))
        assertEquals("412 MB", bytes(412L * 1024 * 1024))
        assertEquals("9.8 MB", bytes((9.8 * 1024 * 1024).toLong()))
        assertEquals("64 KB", bytes(64L * 1024))
        assertEquals("512 B", bytes(512))
        assertEquals("1 KB", bytes(1024))
    }

    @Test
    fun times_are_relative_then_dated() {
        val now = 1_760_000_000_000L
        assertEquals("just now", ago(now - 10_000, now))
        assertEquals("5 min ago", ago(now - 5 * 60_000, now))
        assertEquals("3 h ago", ago(now - 3 * 3_600_000, now))
        assertEquals("2 d ago", ago(now - 2 * 86_400_000L, now))
    }

    @Test
    fun errors_become_one_sentence() {
        assertEquals("Wrong user or password.", DbyException.Auth("denied").sentence())
        assertEquals(
            "Cannot execute statement in a READ ONLY transaction.",
            DbyException.ReadOnlyBlocked("Cannot execute statement in a READ ONLY transaction.").sentence(),
        )
        assertEquals("This statement changes data; confirm it first.", DbyException.ReadOnlyBlocked("this statement changes data; confirm it first").sentence())
        assertEquals("The server refused it (error 1146).", DbyException.Server(1146u, "Table 'x' doesn't exist").sentence())
        assertEquals("boom", IllegalStateException("boom").sentence())
    }
}
```

`android/app/src/test/java/com/dby/mobile/SqlTest.kt`:
```kotlin
package com.dby.mobile

import com.dby.mobile.ui.Span
import com.dby.mobile.ui.Token
import com.dby.mobile.ui.sqlSpans
import org.junit.Assert.assertEquals
import org.junit.Test

class SqlTest {
    private fun tokens(sql: String) = sqlSpans(sql).map { sql.substring(it.start, it.end) to it.token }

    @Test
    fun keywords_strings_numbers_and_comments() {
        assertEquals(
            listOf(
                "SELECT" to Token.Keyword, "'it''s'" to Token.Text, "FROM" to Token.Keyword,
                "where" to Token.Keyword, "12.5" to Token.Number, "-- note" to Token.Comment,
            ),
            tokens("SELECT 'it''s' FROM t1 where n = 12.5 -- note"),
        )
    }

    @Test
    fun unterminated_string_runs_to_the_end_and_words_with_digits_stay_plain() {
        assertEquals(listOf("SELECT" to Token.Keyword, "'abc" to Token.Text), tokens("SELECT 'abc"))
        assertEquals(emptyList<Pair<String, Token>>(), tokens("t1 x2y `select`"))
    }

    @Test
    fun block_comments_and_backslash_escapes() {
        assertEquals(listOf("/* a */" to Token.Comment, "'a\\'b'" to Token.Text), tokens("/* a */ 'a\\'b'"))
        assertEquals(listOf(Span(0, 6, Token.Keyword)), sqlSpans("UPDATE"))
    }
}
```

`android/app/src/test/java/com/dby/mobile/NavigatorTest.kt`:
```kotlin
package com.dby.mobile

import com.dby.mobile.ui.nav.Navigator
import com.dby.mobile.ui.nav.Screen
import com.dby.mobile.ui.nav.ScreenModel
import com.dby.mobile.ui.nav.Tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigatorTest {
    private class Probe : ScreenModel() {
        var closed = false
        override fun close() {
            closed = true
            super.close()
        }
    }

    @Test
    fun tabs_keep_their_own_stacks() {
        val nav = Navigator()
        nav.push(Screen.Explorer("a"))
        nav.select(Tab.QUERY)
        assertEquals(Screen.Query, nav.current)
        nav.select(Tab.CONNECTIONS)
        assertEquals(Screen.Explorer("a"), nav.current)
    }

    @Test
    fun back_pops_then_returns_to_connections_then_stops() {
        val nav = Navigator()
        nav.select(Tab.SETTINGS)
        nav.push(Screen.Licences)
        assertTrue(nav.back())
        assertEquals(Screen.Settings, nav.current)
        assertTrue(nav.back())
        assertEquals(Tab.CONNECTIONS, nav.tab)
        assertFalse(nav.canGoBack)
        assertFalse(nav.back())
    }

    @Test
    fun models_live_while_their_screen_is_stacked() {
        val nav = Navigator()
        nav.push(Screen.Explorer("a"))
        val probe = nav.model(Screen.Explorer("a")) { Probe() }
        assertSame(probe, nav.model(Screen.Explorer("a")) { Probe() })
        nav.select(Tab.QUERY)
        assertFalse(probe.closed)
        nav.select(Tab.CONNECTIONS)
        nav.select(Tab.CONNECTIONS) // re-tapping the tab pops to its root
        assertTrue(probe.closed)
        assertEquals(Screen.Connections, nav.current)
    }
}
```

- [ ] **Step 4: Run them to see them fail**

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: compilation fails: `Unresolved reference 'display'`, `'sqlSpans'`, `'Navigator'` (the code does not exist yet).

- [ ] **Step 5: Write `data/Prefs.kt`**

```kotlin
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
```

- [ ] **Step 6: Write `data/Format.kt`**

```kotlin
package com.dby.mobile.data

import com.dby.core.Cell
import com.dby.core.DbyException
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

/** A cell as one line of text for lists and grids. A value the server trimmed ends with "…". */
fun Cell.display(): String = when (this) {
    is Cell.Null -> "NULL"
    is Cell.Bytes -> "<${count(len.toLong())} bytes>"
    is Cell.Text -> if (isTrimmed()) "$v…" else v
    else -> rawText().orEmpty()
}

/** The value an edit field starts with, or null for NULL and binary (which fields cannot hold). */
fun Cell.rawText(): String? = when (this) {
    is Cell.Null, is Cell.Bytes -> null
    is Cell.Signed -> v.toString()
    is Cell.Unsigned -> v.toString()
    is Cell.Real -> v.toString()
    is Cell.Exact -> v
    is Cell.Text -> v
    is Cell.Temporal -> v
}

/** True when the page holds only the start of this value. The core counts characters, not UTF-16 units. */
fun Cell.isTrimmed(): Boolean = when (this) {
    is Cell.Text -> fullLen > v.codePointCount(0, v.length).toULong()
    is Cell.Bytes -> len > preview.size.toULong()
    else -> false
}

fun count(n: Long): String = NumberFormat.getIntegerInstance(Locale.US).format(n)

/** "412 MB", "9.8 MB", "64 KB": one decimal below ten, 1024-based. */
fun bytes(n: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = n.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    val text = if (unit > 0 && value < 10) String.format(Locale.US, "%.1f", value).removeSuffix(".0") else value.roundToLong().toString()
    return "$text ${units[unit]}"
}

fun ago(atMs: Long, nowMs: Long): String {
    val s = (nowMs - atMs) / 1000
    return when {
        s < 45 -> "just now"
        s < 3600 -> "${(s + 30) / 60} min ago"
        s < 86_400 -> "${s / 3600} h ago"
        s < 7 * 86_400 -> "${s / 86_400} d ago"
        else -> SimpleDateFormat("MMM d", Locale.US).format(Date(atMs))
    }
}

/** One short sentence for a failure (spec §9); the raw text goes behind "Details". */
fun Throwable.sentence(): String = when (this) {
    is DbyException.Network -> "Can't reach the server. Check the host, the port and your network."
    is DbyException.Tls -> "The secure connection failed."
    is DbyException.Auth -> "Wrong user or password."
    is DbyException.UnknownDatabase -> "That database doesn't exist on this server."
    is DbyException.Server -> "The server refused it (error $code)."
    is DbyException.NotFound -> "It's no longer there."
    is DbyException.ReadOnlyBlocked -> detail.trimEnd('.').replaceFirstChar { it.uppercase() } + "."
    is DbyException.RowEditMismatch -> "The row was changed or deleted elsewhere, so nothing was saved."
    is DbyException.Cancelled -> "Cancelled."
    is DbyException.Timeout -> "The server took too long to answer."
    is DbyException.Storage -> "Couldn't read or save on this phone."
    is DbyException.Internal -> "Something went wrong."
    else -> message ?: "Something went wrong."
}

/** The raw text behind a [sentence]. */
fun Throwable.details(): String = message ?: toString()
```

- [ ] **Step 7: Write `ui/Sql.kt`**

```kotlin
package com.dby.mobile.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

enum class Token { Keyword, Text, Number, Comment }

data class Span(val start: Int, val end: Int, val token: Token)

private val KEYWORDS = setOf(
    "SELECT", "FROM", "WHERE", "AND", "OR", "NOT", "NULL", "IS", "IN", "LIKE", "BETWEEN", "ORDER", "BY", "GROUP",
    "HAVING", "LIMIT", "OFFSET", "ASC", "DESC", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "JOIN", "LEFT",
    "RIGHT", "INNER", "OUTER", "CROSS", "ON", "AS", "DISTINCT", "UNION", "ALL", "CASE", "WHEN", "THEN", "ELSE", "END",
    "CREATE", "TABLE", "ALTER", "DROP", "INDEX", "VIEW", "SHOW", "DESCRIBE", "EXPLAIN", "WITH", "EXISTS", "TRUE",
    "FALSE", "COUNT", "SUM", "AVG", "MIN", "MAX", "USE", "REPLACE", "TRUNCATE", "PRIMARY", "KEY", "ESCAPE", "FOR",
    "CALL", "GRANT", "REVOKE", "BEGIN", "COMMIT", "ROLLBACK", "START", "TRANSACTION", "DATABASE", "DATABASES",
    "TABLES", "COLUMNS", "PROCESSLIST", "STATUS", "VARIABLES", "IF", "INTERVAL", "DEFAULT", "UNIQUE", "FULL",
)

private val COLORS = mapOf(
    Token.Keyword to Color(0xFFFF8FB8),
    Token.Text to Color(0xFFFFC27A),
    Token.Number to Color(0xFFC9B3FF),
    Token.Comment to Color(0x80FFFFFF),
)

/** Splits SQL into coloured spans; everything else is plain. Pure, so it is unit-tested on the JVM. */
fun sqlSpans(sql: String): List<Span> {
    val spans = mutableListOf<Span>()
    var i = 0
    while (i < sql.length) {
        val c = sql[i]
        val start = i
        when {
            c == '#' || sql.startsWith("--", i) -> {
                i = sql.indexOf('\n', i).let { if (it < 0) sql.length else it }
                spans += Span(start, i, Token.Comment)
            }
            sql.startsWith("/*", i) -> {
                i = sql.indexOf("*/", i + 2).let { if (it < 0) sql.length else it + 2 }
                spans += Span(start, i, Token.Comment)
            }
            c == '\'' || c == '"' -> {
                i = endOfQuoted(sql, i, c)
                spans += Span(start, i, Token.Text)
            }
            c == '`' -> i = endOfQuoted(sql, i, '`')
            c.isDigit() -> {
                while (i < sql.length && (sql[i].isLetterOrDigit() || sql[i] == '.')) i++
                spans += Span(start, i, Token.Number)
            }
            isWord(c) -> {
                while (i < sql.length && isWord(sql[i])) i++
                if (sql.substring(start, i).uppercase() in KEYWORDS) spans += Span(start, i, Token.Keyword)
            }
            else -> i++
        }
    }
    return spans
}

private fun isWord(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'

/** The index after the closing quote. A doubled quote or a backslash-escaped one does not close it. */
private fun endOfQuoted(sql: String, open: Int, quote: Char): Int {
    var i = open + 1
    while (i < sql.length) {
        when {
            sql[i] == '\\' && quote != '`' -> i += 2
            sql[i] == quote && i + 1 < sql.length && sql[i + 1] == quote -> i += 2
            sql[i] == quote -> return i + 1
            else -> i++
        }
    }
    return sql.length
}

fun highlightSql(sql: String): AnnotatedString = buildAnnotatedString {
    append(sql)
    for (span in sqlSpans(sql)) addStyle(SpanStyle(color = COLORS.getValue(span.token)), span.start, span.end)
}

/** Colours SQL as it is typed, without changing the text. */
object SqlTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString) = TransformedText(highlightSql(text.text), OffsetMapping.Identity)
}
```

Note: a word that starts with a digit (`2fa`) scans as a number; `t1` and `x2y` start with letters, so they scan as words and stay plain.

- [ ] **Step 8: Write `ui/nav/Nav.kt`**

```kotlin
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
```

- [ ] **Step 9: Run the JVM tests**

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: still fails to compile until Steps 10–12 exist only if Theme/Glass are referenced; they are not referenced by the tests, so expected now: `BUILD SUCCESSFUL`, `FormatTest` 6/6, `SqlTest` 3/3, `NavigatorTest` 3/3. (If `mutableStateListOf` throws on the JVM, the `isReturnDefaultValues` option from Step 1 is missing.)

- [ ] **Step 10: Write `ui/theme/Theme.kt`**

```kotlin
package com.dby.mobile.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.dby.core.Env
import com.dby.mobile.R
import com.dby.mobile.data.Prefs

/** Colour tokens from the design canvas (spec §12). */
object Dby {
    val Bg = Color(0xFF0B0B0E)
    val Fg = Color(0xFFF5F5F7)
    val Secondary = Color(0xB3FFFFFF)
    val Tertiary = Color(0x9EFFFFFF)
    val Faint = Color(0x73FFFFFF)
    val Hairline = Color(0x1FFFFFFF)
    val Fill = Color(0x12FFFFFF)
    val FillStrong = Color(0x1FFFFFFF)
    val Selected = Color(0x2EFFFFFF)
    val Outline = Color(0x29FFFFFF)
    val GlassFill = Color(0x801C1C21)
    val GlassBorder = Color(0x24FFFFFF)
    val Solid = Color(0xFF1C1C21)
    val Stripe = Color(0x09FFFFFF)
    val StripeSolid = Color(0xFF131316)
    val Scrim = Color(0x80000000)
    val Danger = Color(0xFFFF9A92)
    val DangerFill = Color(0x26FF453A)
    val Success = Color(0xFF86E8A0)
    val Accents = listOf(Color(0xFF5AC8FA), Color(0xFF30D158), Color(0xFFFF9F0A), Color(0xFFBF5AF2))
}

/** A PROD / STAGING / DEV / LOCAL badge's text and background colours. */
fun Env.colors(): Pair<Color, Color> = when (this) {
    Env.PROD -> Color(0xFFFF9A92) to Color(0x33FF453A)
    Env.STAGING -> Color(0xFFFFC56B) to Color(0x33FF9F0A)
    Env.DEV -> Color(0xFF9BDFFF) to Color(0x2E5AC8FA)
    Env.LOCAL -> Color(0xFF86E8A0) to Color(0x2E30D158)
}

val LocalAccent = compositionLocalOf { Dby.Accents[0] }

private fun geist(weight: Int, mono: Boolean) = Font(
    if (mono) R.font.geist_mono else R.font.geist,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Geist = FontFamily(geist(400, false), geist(500, false), geist(600, false), geist(700, false))
val GeistMono = FontFamily(geist(400, true), geist(500, true), geist(600, true))

/** The canvas's type scale. */
object Type {
    val LargeTitle = TextStyle(fontFamily = Geist, fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp)
    val Title = TextStyle(fontFamily = Geist, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp)
    val Section = TextStyle(fontFamily = Geist, fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
    val Body = TextStyle(fontFamily = Geist, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
    val Secondary = TextStyle(fontFamily = Geist, fontSize = 15.sp, lineHeight = 20.sp)
    val Caption = TextStyle(fontFamily = Geist, fontSize = 13.sp, lineHeight = 17.sp)
    val Tab = TextStyle(fontFamily = Geist, fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
    val Button = TextStyle(fontFamily = Geist, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold)
    val Mono = TextStyle(fontFamily = GeistMono, fontSize = 15.sp, lineHeight = 20.sp)
    val MonoSmall = TextStyle(fontFamily = GeistMono, fontSize = 13.sp, lineHeight = 18.sp)
    val MonoCode = TextStyle(fontFamily = GeistMono, fontSize = 15.sp, lineHeight = 23.sp)
    val MonoTitle = TextStyle(fontFamily = GeistMono, fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp)
}

/** Accent and text size come from Settings; text size scales every sp in the app. */
@Composable
fun DbyTheme(prefs: Prefs, content: @Composable () -> Unit) {
    val accent = Color(prefs.accent)
    val scale = when (prefs.textSize) {
        Prefs.TEXT_DEFAULT -> 0.875f
        Prefs.TEXT_LARGER -> 1.125f
        else -> 1f
    }
    val density = LocalDensity.current
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = accent, onPrimary = Dby.Bg, background = Dby.Bg, onBackground = Dby.Fg,
            surface = Dby.Solid, onSurface = Dby.Fg, surfaceContainer = Dby.Solid,
        ),
    ) {
        CompositionLocalProvider(
            LocalAccent provides accent,
            LocalDensity provides Density(density.density, density.fontScale * scale),
            LocalContentColor provides Dby.Fg,
            LocalTextStyle provides Type.Body,
            content = content,
        )
    }
}
```

- [ ] **Step 11: Write `ui/Icons.kt`**

```kotlin
package com.dby.mobile.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** The canvas's stroke icons (24 × 24, round caps), as vectors so they tint like text. */
object DbyIcons {
    val Back = icon("m15 6-6 6 6 6")
    val Chevron = icon("m9 6 6 6-6 6")
    val ChevronDown = icon("m7 10 5 5 5-5")
    val Plus = icon("M12 5v14", "M5 12h14")
    val Close = icon("M6 6l12 12", "M18 6 6 18")
    val Check = icon("m5 12.5 4.5 4.5L19 7.5")
    val Search = icon(circle(11f, 11f, 7f), "m20 20-3.5-3.5")
    val More = icon(circle(5f, 12f, 0.8f), circle(12f, 12f, 0.8f), circle(19f, 12f, 0.8f), width = 2.4f)
    val Database = icon(ellipse(12f, 5.5f, 8f, 3f), "M4 5.5v13c0 1.66 3.58 3 8 3s8-1.34 8-3v-13", "M4 12c0 1.66 3.58 3 8 3s8-1.34 8-3")
    val Query = icon(rect(3f, 4f, 18f, 16f, 3f), "m7.5 9 3 3-3 3", "M13 15h4")
    val History = icon("M3 12a9 9 0 1 0 3-6.7L3 8", "M3 3v5h5", "M12 7v5l3 2")
    val Settings = icon("M4 7h9", "M19 7h1", circle(16f, 7f, 2.5f), "M4 17h1", "M11 17h9", circle(8f, 17f, 2.5f))
    val Table = icon(rect(3f, 4f, 18f, 16f, 2.5f), "M3 10h18", "M9 10v10")
    val View = icon(rect(3f, 4f, 18f, 16f, 2.5f), "M3 10h18", "M7 14h10", "M7 17h6")
    val Lock = icon(rect(5f, 11f, 14f, 10f, 2f), "M8 11V7a4 4 0 0 1 8 0v4")
    val Unlock = icon(rect(5f, 11f, 14f, 10f, 2f), "M8 11V7a4 4 0 0 1 7.75-1.4")
    val Bookmark = icon("M6 3h12a1 1 0 0 1 1 1v17l-7-4-7 4V4a1 1 0 0 1 1-1z")
    val Info = icon(circle(12f, 12f, 9f), "M12 11v5", "M12 8h.01")
    val Play = icon("M7 4.5v15a1 1 0 0 0 1.5.86l12.5-7.5a1 1 0 0 0 0-1.72L8.5 3.64A1 1 0 0 0 7 4.5z")
    val Open = icon("M7 17 17 7", "M8 7h9v9")
    val Export = icon("M12 15V3", "m7.5 7.5 4.5-4.5 4.5 4.5", "M4 14v4a3 3 0 0 0 3 3h10a3 3 0 0 0 3-3v-4")
    val Filter = icon("M3 5h18l-7 8v6l-4 2v-8z")
    val Sort = icon("M7 4v16", "m3 16 4 4 4-4", "M14 6h7", "M14 12h5", "M14 18h3")
    val Cards = icon(rect(3f, 4f, 18f, 7f, 2f), rect(3f, 13f, 18f, 7f, 2f))
    val Grid = icon(rect(3f, 4f, 18f, 16f, 2.5f), "M3 10h18", "M3 15h18", "M10 4v16")
    val Key = icon(circle(8f, 15f, 4f), "m11 12 9-9", "m17 6 3 3")
    val ArrowDown = icon("M12 5v14", "m6 13 6 6 6-6")
    val ArrowUp = icon("M12 19V5", "m6 11 6-6 6 6")
    val Pin = icon("M12 17v5", "M9 3h6l-1 7 4 4H6l4-4z")
    val Trash = icon("M4 7h16", "M10 11v6", "M14 11v6", "M6 7l1 13a1 1 0 0 0 1 1h8a1 1 0 0 0 1-1l1-13", "M9 7V4h6v3")
    val Copy = icon(rect(8f, 8f, 12f, 12f, 2f), "M16 8V5a1 1 0 0 0-1-1H5a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h3")
    val Refresh = icon("M20 12a8 8 0 1 1-2.34-5.66", "M20 4v5h-5")
    val Edit = icon("M4 20h4L19 9l-4-4L4 16z", "m13.5 6.5 4 4")
    val Unplug = icon("M9 7V3", "M15 7V3", "M7 7h10v4a5 5 0 0 1-10 0z", "M12 16v5")
    val Download = icon("M12 3v12", "m7.5 10.5 4.5 4.5 4.5-4.5", "M4 15v3a3 3 0 0 0 3 3h10a3 3 0 0 0 3-3v-3")
}

private fun icon(vararg paths: String, width: Float = 1.8f): ImageVector =
    ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
        for (d in paths) {
            addPath(
                pathData = addPathNodes(d),
                stroke = SolidColor(Color.White),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

private fun circle(cx: Float, cy: Float, r: Float) = ellipse(cx, cy, r, r)

private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float) =
    "M${cx - rx} ${cy}a$rx $ry 0 1 0 ${2 * rx} 0a$rx $ry 0 1 0 ${-2 * rx} 0"

private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float) =
    "M${x + r} ${y}h${w - 2 * r}a$r $r 0 0 1 $r ${r}v${h - 2 * r}a$r $r 0 0 1 ${-r} ${r}" +
        "h${-(w - 2 * r)}a$r $r 0 0 1 ${-r} ${-r}v${-(h - 2 * r)}a$r $r 0 0 1 $r ${-r}z"
```

- [ ] **Step 12: Write `ui/glass/Glass.kt`**

```kotlin
package com.dby.mobile.ui.glass

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.dby.mobile.DbyApp
import com.dby.mobile.ui.theme.Dby
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangularShape

/** True where blur materials really blur: API 31+ and Reduce blur off (spec §11). Elsewhere they are solid. */
@Composable
fun blurOn(): Boolean = Build.VERSION.SDK_INT >= 31 && !DbyApp.instance.prefs.reduceBlur

/** Light glass: no capture and no blur, so it costs nothing per frame. Cards, lists, fields, chips. */
fun Modifier.lightGlass(shape: Shape): Modifier = this
    .clip(shape)
    .background(Dby.GlassFill)
    .border(0.5.dp, Brush.verticalGradient(0f to Color(0x33FFFFFF), 0.25f to Dby.GlassBorder), shape)

/** What blur materials become on old phones and with Reduce blur on. */
fun Modifier.solidGlass(shape: Shape): Modifier = this.clip(shape).background(Dby.Solid).border(0.5.dp, Dby.GlassBorder, shape)

/** Liquid glass: refraction, light blur and vibrancy. The tab pill and floating buttons. */
@Composable
fun Modifier.liquidGlass(backdrop: Backdrop, shape: RoundedRectangularShape = Capsule()): Modifier =
    if (!blurOn()) {
        solidGlass(shape)
    } else {
        drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(8.dp.toPx())
                lens(24.dp.toPx(), 24.dp.toPx(), depthEffect = true, chromaticAberration = true)
            },
            onDrawSurface = { drawRect(Color(0x66121212)) },
        )
    }

/** Frosted glass: heavy blur over what scrolls underneath. The pager bar and sheets. */
@Composable
fun Modifier.frostedGlass(backdrop: Backdrop, shape: RoundedRectangularShape): Modifier =
    if (!blurOn()) {
        solidGlass(shape)
    } else {
        drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                colorControls(saturation = 1.8f)
                blur(24.dp.toPx())
            },
            shadow = null,
            onDrawSurface = { drawRect(Dby.GlassFill) },
        ).border(0.5.dp, Dby.GlassBorder, shape)
    }

/**
 * A screen whose content is recorded as the backdrop its floating glass draws from. The
 * background is drawn inside the recording, so blur always has opaque pixels to work with.
 */
@Composable
fun GlassHost(
    modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.(Backdrop) -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    val backdrop = rememberLayerBackdrop()
    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop).background(Dby.Bg), content = content)
        overlay(backdrop)
    }
}
```

- [ ] **Step 13: Run the tests and compile**

Run: `cd android && ./gradlew :app:testDebugUnitTest :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`; FormatTest 6/6, SqlTest 3/3, NavigatorTest 3/3. (BenchScreen still compiles against Haze until Task 2 deletes it; if the Haze imports fail because Step 1 dropped Haze, delete `BenchScreen.kt` and `BenchViewModel.kt` now and point `MainActivity` at an empty `setContent {}` — Task 2 rewrites it.)

- [ ] **Step 14: Commit**

```bash
git add android/app/build.gradle.kts android/app/src/main/res/font android/app/src/main/assets android/app/src/main/java/com/dby/mobile android/app/src/test
git commit -m "feat(android): theme, icons, glass, navigation, formatting and SQL colouring"
```

---

### Task 2: Data layer, app shell, tab pill, lock screen

**Files:**
- Create: `data/Secrets.kt`, `data/AppLock.kt`, `data/Sessions.kt`, `DbyApp.kt`, `AppRoot.kt`
- Modify (rewrite): `MainActivity.kt`, `android/app/src/main/AndroidManifest.xml`
- Create: `android/app/src/main/res/values/themes.xml`, `res/values/colors.xml`, `res/drawable/ic_launcher_foreground.xml`, `res/mipmap-anydpi-v26/ic_launcher.xml`, `res/mipmap-anydpi-v26/ic_launcher_round.xml`
- Delete: `BenchScreen.kt`, `BenchViewModel.kt`

**Interfaces:**
- Consumes: Task 1; core `openStore`, `listConnections`, `saveConnection`, `deleteConnection`, `setPinned`, `connect`, `Session`, `SavedConnection`, `ConnectionInput`, `Env`, `TlsMode`.
- Produces: `Secrets.encrypt(password, locked): ByteArray`, `Secrets.decrypt(blob): String`, `Secrets.forget(locked)`; `AppLock.supported`, `suspend AppLock.unlock(activity, title): Boolean`; `ConnectionForm(name, host, port: Int, user, database, env, tls)`; `Sessions` with `connections`, `open`, `lost`, `activeId`, `activity`, `reload()`, `saved(id)`, `suspend save(id, form, password?)`, `suspend delete(id)`, `setPinned(id, pinned)`, `suspend savedPassword(saved): String?`, `suspend connect(saved, password): Session`, `suspend rememberPassword(saved, password)`, `suspend disconnect(id)`, `pingAll(scope)`, `suspend rekey(locked)`; `DbyApp.instance` with `prefs`, `sessions`, `nav`, `scope`, `locked`; `AppRoot(app)` with a `when` over `Screen` that later tasks fill in; `TabPill`, `LockScreen`, `bottomSpace(extra)`, `topSpace()`.

- [ ] **Step 1: Write `data/Secrets.kt`**

```kotlin
package com.dby.mobile.data

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Saved passwords, encrypted with an AES-256-GCM Android Keystore key (spec §7). A blob is
 * [kind][12-byte IV][ciphertext + tag]. Kind 0 uses the plain key; kind 1 the key that needs a
 * fingerprint or screen-lock unlock in the last five minutes, used while App lock is on.
 */
class Secrets {
    private val keystore = KeyStore.getInstance(STORE).apply { load(null) }

    /** Throws `UserNotAuthenticatedException` when the locked key needs an unlock first. */
    fun encrypt(password: String, locked: Boolean): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key(locked))
        return byteArrayOf(if (locked) 1 else 0) + cipher.iv + cipher.doFinal(password.toByteArray())
    }

    /**
     * Throws `IllegalArgumentException` for an empty blob (no saved password),
     * `UserNotAuthenticatedException` (unlock, then retry) and `KeyPermanentlyInvalidatedException`
     * (the screen lock or fingerprints changed: ask for the password again).
     */
    fun decrypt(blob: ByteArray): String {
        require(blob.size > HEADER) { "no saved password" }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(blob[0] == 1.toByte()), GCMParameterSpec(128, blob, 1, IV))
        return String(cipher.doFinal(blob, HEADER, blob.size - HEADER))
    }

    fun forget(locked: Boolean) = keystore.deleteEntry(alias(locked))

    private fun alias(locked: Boolean) = if (locked) "dby.passwords.locked" else "dby.passwords"

    private fun key(locked: Boolean): SecretKey {
        (keystore.getKey(alias(locked), null) as SecretKey?)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(alias(locked), KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
        if (locked) {
            spec.setUserAuthenticationRequired(true)
            if (Build.VERSION.SDK_INT >= 30) {
                spec.setUserAuthenticationParameters(
                    UNLOCK_SECONDS,
                    KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                )
            } else {
                @Suppress("DEPRECATION")
                spec.setUserAuthenticationValidityDurationSeconds(UNLOCK_SECONDS)
            }
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE).apply { init(spec.build()) }.generateKey()
    }

    private companion object {
        const val STORE = "AndroidKeyStore"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV = 12
        const val HEADER = 1 + IV
        const val UNLOCK_SECONDS = 300
    }
}
```

- [ ] **Step 2: Write `data/AppLock.kt`**

```kotlin
package com.dby.mobile.data

import android.app.Activity
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * App lock's prompt: fingerprint, face or screen lock, API 28+. A successful unlock also
 * opens the locked password key for five minutes (see [Secrets]).
 */
object AppLock {
    val supported: Boolean get() = Build.VERSION.SDK_INT >= 28

    suspend fun unlock(activity: Activity, title: String = "Unlock DBY"): Boolean {
        if (!supported) return true
        return suspendCancellableCoroutine { cont ->
            fun done(ok: Boolean) {
                if (cont.isActive) cont.resume(ok)
            }
            val builder = BiometricPrompt.Builder(activity).setTitle(title)
            when {
                Build.VERSION.SDK_INT >= 30 ->
                    builder.setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)
                Build.VERSION.SDK_INT == 29 -> @Suppress("DEPRECATION") builder.setDeviceCredentialAllowed(true)
                else -> builder.setNegativeButton("Cancel", activity.mainExecutor) { _, _ -> done(false) }
            }
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            builder.build().authenticate(
                signal,
                activity.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = done(true)

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = done(false)
                },
            )
        }
    }
}
```

- [ ] **Step 3: Write `data/Sessions.kt`**

```kotlin
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
```

- [ ] **Step 4: Write `DbyApp.kt`**

```kotlin
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
    }

    /** From Activity.onStart: a cold start, or a return after a minute away, locks the app. */
    fun onForeground() {
        val away = SystemClock.elapsedRealtime() - backgroundSince
        if (prefs.appLock && AppLock.supported && (backgroundSince == 0L || away > LOCK_AFTER_MS)) locked = true
        if (backgroundSince != 0L && prefs.reconnect) sessions.pingAll(scope)
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
```

- [ ] **Step 5: Write `MainActivity.kt`**

```kotlin
package com.dby.mobile

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.dby.core.coreVersion
import com.dby.mobile.ui.theme.DbyTheme
import java.lang.ref.WeakReference

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is always dark, so the system bars keep light icons even when the phone is in light mode.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        Log.i("DBYBENCH", "app=dby event=boot core=${coreVersion()}")
        val app = DbyApp.instance
        // With App lock on, the app's screen is hidden from screenshots and the recents list.
        if (app.prefs.appLock) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent { DbyTheme(app.prefs) { AppRoot(app) } }
    }

    override fun onStart() {
        super.onStart()
        DbyApp.instance.sessions.activity = WeakReference(this)
        DbyApp.instance.onForeground()
    }

    override fun onStop() {
        super.onStop()
        DbyApp.instance.onBackground()
    }
}
```

- [ ] **Step 6: Write `AppRoot.kt`** (screens are placeholders until their tasks)

```kotlin
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dby.mobile.data.AppLock
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
                Screen.Connections -> Placeholder("Connections")
                is Screen.EditConnection -> Placeholder("Edit connection")
                is Screen.Explorer -> Placeholder("Explorer ${screen.connectionId}")
                is Screen.Table -> Placeholder("Table ${screen.table}")
                Screen.Query -> Placeholder("Query")
                Screen.History -> Placeholder("History")
                Screen.Settings -> Placeholder("Settings")
                Screen.Licences -> Placeholder("Licences")
            }
        }
    }
}

@Composable
private fun Placeholder(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(text, style = Type.Title) }
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
        for (tab in Tab.entries) {
            val selected = tab == nav.tab
            val fill by animateColorAsState(if (selected) Color(0x24FFFFFF) else Color.Transparent, label = "tab")
            val tint = if (selected) accent else Color(0xB8FFFFFF)
            Column(
                Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(fill)
                    .selectable(selected = selected, role = Role.Tab) { nav.select(tab) }
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
    Canvas(Modifier.size(size)) {
        val u = this.size.width / 24f
        drawRoundRect(Color(0xFF15435A), cornerRadius = CornerRadius(7 * u))
        for ((y, end) in listOf(8f to 17.5f, 12f to 17.5f, 16f to 13.5f)) {
            drawLine(Color.White, Offset(6.5f * u, y * u), Offset(end * u, y * u), strokeWidth = 2.2f * u, cap = StrokeCap.Round)
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
```

- [ ] **Step 7: Write the manifest, theme, colours and launcher icon**

`android/app/src/main/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.USE_BIOMETRIC" />

    <application
        android:name=".DbyApp"
        android:allowBackup="false"
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:label="DBY"
        android:theme="@style/Theme.Dby">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`android/app/src/main/res/values/themes.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- Dark from the first frame, so a cold start never flashes white. -->
    <style name="Theme.Dby" parent="android:Theme.Material.NoActionBar">
        <item name="android:windowBackground">@color/background</item>
    </style>
</resources>
```

`android/app/src/main/res/values/colors.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="background">#0B0B0E</color>
    <color name="ic_launcher_background">#15435A</color>
</resources>
```

`android/app/src/main/res/drawable/ic_launcher_foreground.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- The DBY mark's three lines, centred in the adaptive icon's 66 dp safe zone. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:pathData="M41.6,45H66.4M41.6,54H66.4M41.6,63H57.4"
        android:strokeWidth="5"
        android:strokeColor="#FFFFFF"
        android:strokeLineCap="round" />
</vector>
```

`android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` (and the same content in `ic_launcher_round.xml`):
```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
```

- [ ] **Step 8: Delete the bench screen and build**

Run: `git rm android/app/src/main/java/com/dby/mobile/BenchScreen.kt android/app/src/main/java/com/dby/mobile/BenchViewModel.kt && cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 9: See it on the emulator**

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.dby.mobile/.MainActivity
adb exec-out screencap -p > "$TEMP/m2-t2.png"
```
Expected: dark screen, "Connections" placeholder, the liquid-glass tab pill at the bottom with Connections highlighted in accent blue; tapping Query switches the placeholder; system back from Query returns to Connections; a second back leaves the app. Check the launcher icon in the app drawer.

- [ ] **Step 10: Commit**

```bash
git add -A android/app/src
git commit -m "feat(android): Keystore secrets, sessions, app shell with liquid tab pill and App lock screen"
```

---

### Task 3: Shared widgets

**Files:**
- Create: `ui/common/Widgets.kt`, `ui/common/Connector.kt`

**Interfaces:**
- Consumes: Tasks 1–2.
- Produces: `RoundButton(icon, label, onClick, modifier, tint, enabled)`, `TopBar(onBack, actions)`, `LargeTitle(text, modifier)`, `SectionHeader(text, modifier, trailing)`, `GroupCard(modifier, content)`, `Hairline(start)`, `ListRow(title, subtitle, onClick, onLongClick, titleStyle, subtitleColor, leading, trailing, titleExtra)`, `Chevron()`, `EnvBadge(env)`, `Chip(text, selected, onClick, count, icon, onClose)`, `Segmented(options, selected, onSelect, label, modifier)`, `Toggle(checked, onChange, enabled)`, `ToggleRow(title, subtitle, checked, onChange, enabled)`, `PrimaryButton(text, onClick, modifier, enabled, icon, busy)`, `SecondaryButton(text, onClick, modifier, enabled, icon, tint, busy)`, `Input(value, onChange, modifier, placeholder, keyboard, secret, mono, enabled, align, singleLine, style)`, `FieldRow(label, value, onChange, placeholder, keyboard, secret, mono, supporting, enabled)`, `SearchField(value, onChange, placeholder, modifier)`, `ProblemBanner(problem, onRetry, modifier)`, `Busy(text)`, `EmptyState(title, body, action, onAction)`, `SqlBox(sql, header, note, modifier)`, `Sheet(backdrop, onDismiss, content)`, `SheetHeader(title, subtitle, onClose, actions)`, `Action(label, icon, danger, onClick)`, `ActionSheet(backdrop, title, actions, onDismiss)`, `ConfirmSheet(backdrop, title, message, sql, confirm, danger, busy, problem, onConfirm, onDismiss)`, `PasswordSheet(backdrop, name, onSubmit(password, remember), onDismiss)`, `copyText(context, label, text)`; `Connector(sessions, scope)` with `asking`, `busy`, `problem`, `open(saved, then)`, `submit(password, remember)`, `dismiss()`.

- [ ] **Step 1: Write `ui/common/Widgets.kt`**

```kotlin
package com.dby.mobile.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dby.core.Env
import com.dby.mobile.DbyApp
import com.dby.mobile.data.details
import com.dby.mobile.data.sentence
import com.dby.mobile.ui.DbyIcons
import com.dby.mobile.ui.glass.frostedGlass
import com.dby.mobile.ui.glass.lightGlass
import com.dby.mobile.ui.highlightSql
import com.dby.mobile.ui.theme.Dby
import com.dby.mobile.ui.theme.LocalAccent
import com.dby.mobile.ui.theme.Type
import com.dby.mobile.ui.theme.colors
import com.kyant.backdrop.Backdrop
import com.kyant.shapes.UnevenRoundedRectangle

/** A 44 dp round icon button with the canvas's light fill. */
@Composable
fun RoundButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Dby.Fg,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Dby.FillStrong)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) tint else Dby.Faint, modifier = Modifier.size(20.dp))
    }
}

/** The header row: back on the left, actions on the right. */
@Composable
fun TopBar(onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (onBack != null) RoundButton(DbyIcons.Back, "Back", onBack)
        Spacer(Modifier.weight(1f))
        actions()
    }
}

@Composable
fun LargeTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Type.LargeTitle, modifier = modifier.padding(horizontal = 16.dp))
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = Type.Section, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** A light-glass card of rows; put a [Hairline] between rows. */
@Composable
fun GroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.padding(horizontal = 16.dp).fillMaxWidth().lightGlass(RoundedCornerShape(22.dp)), content = content)
}

/** The 0.5 dp divider, inset to the text column. */
@Composable
fun Hairline(start: Dp = 16.dp) {
    Box(Modifier.padding(start = start).fillMaxWidth().height(0.5.dp).background(Dby.Hairline))
}

@Composable
fun Chevron() {
    Icon(DbyIcons.Chevron, contentDescription = null, tint = Dby.Faint, modifier = Modifier.size(16.dp))
}

/** A row: leading slot, title (with extras such as a badge) and subtitle, trailing slot. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    titleStyle: TextStyle = Type.Body,
    subtitleColor: Color = Dby.Secondary,
    leading: (@Composable () -> Unit)? = null,
    trailing: @Composable () -> Unit = { if (onClick != null) Chevron() },
    titleExtra: @Composable RowScope.() -> Unit = {},
) {
    val clicks = if (onClick != null || onLongClick != null) {
        Modifier.combinedClickable(onClick = { onClick?.invoke() }, onLongClick = onLongClick)
    } else {
        Modifier
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).then(clicks).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = titleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                titleExtra()
            }
            if (subtitle != null) {
                Text(subtitle, style = Type.Secondary, color = subtitleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing()
    }
}

@Composable
fun EnvBadge(env: Env) {
    val (fg, bg) = env.colors()
    Text(
        env.name,
        style = Type.Caption.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp, fontSize = 11.sp, lineHeight = 14.sp),
        color = fg,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** A pill chip; selected chips invert to light on dark like the canvas. */
@Composable
fun Chip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    count: String? = null,
    icon: ImageVector? = null,
    onClose: (() -> Unit)? = null,
) {
    val fg = if (selected) Dby.Bg else Dby.Fg
    Row(
        Modifier
            .height(36.dp)
            .clip(CircleShape)
            .background(if (selected) Dby.Fg else Dby.Fill)
            .border(0.5.dp, if (selected) Dby.Fg else Dby.Outline, CircleShape)
            .selectable(selected = selected, role = Role.Button, onClick = onClick)
            .padding(start = 14.dp, end = if (onClose != null) 8.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
        Text(text, style = Type.Secondary.copy(fontWeight = FontWeight.SemiBold), color = fg, maxLines = 1)
        if (count != null) Text(count, style = Type.Caption, color = if (selected) Color(0xA80B0B0E) else Dby.Tertiary)
        if (onClose != null) {
            Box(Modifier.size(24.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClose), contentAlignment = Alignment.Center) {
                Icon(DbyIcons.Close, contentDescription = "Remove", tint = fg, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
fun <T> Segmented(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.clip(RoundedCornerShape(22.dp)).background(Dby.Fill).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (option in options) {
            val on = option == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (on) Dby.Selected else Color.Transparent)
                    .selectable(selected = on, role = Role.Tab) { onSelect(option) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    style = Type.Secondary.copy(fontWeight = FontWeight.SemiBold),
                    color = if (on) Dby.Fg else Dby.Secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The canvas's switch: 52 × 32 track, accent when on. */
@Composable
fun Toggle(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val accent = LocalAccent.current
    val knob by animateDpAsState(if (checked) 20.dp else 0.dp, label = "knob")
    Box(
        Modifier.size(64.dp, 44.dp).toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(52.dp, 32.dp)
                .clip(CircleShape)
                .background(if (checked) accent else Color(0x3DFFFFFF))
                .padding(2.dp),
        ) {
            Box(Modifier.offset(x = knob).size(28.dp).shadow(2.dp, CircleShape).background(Color.White, CircleShape))
        }
    }
}

@Composable
fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = Type.Body, color = if (enabled) Dby.Fg else Dby.Tertiary)
            if (subtitle != null) Text(subtitle, style = Type.Secondary.copy(fontSize = 14.sp, lineHeight = 19.sp), color = Dby.Secondary)
        }
        Toggle(checked, onChange, enabled)
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    busy: Boolean = false,
) {
    val accent = LocalAccent.current
    val fg = if (enabled) Dby.Bg else Dby.Faint
    Row(
        modifier
            .height(52.dp)
            .clip(CircleShape)
            .background(if (enabled) accent else Dby.FillStrong)
            .clickable(enabled = enabled && !busy, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), color = fg, strokeWidth = 2.dp)
        } else if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        }
        Text(text, style = Type.Button, color = fg, maxLines = 1)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    tint: Color = Dby.Fg,
    busy: Boolean = false,
) {
    val fg = if (enabled) tint else Dby.Faint
    Row(
        modifier
            .height(52.dp)
            .clip(CircleShape)
            .background(Dby.FillStrong)
            .clickable(enabled = enabled && !busy, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), color = fg, strokeWidth = 2.dp)
        } else if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        }
        Text(text, style = Type.Button.copy(fontWeight = FontWeight.SemiBold), color = fg, maxLines = 1)
    }
}

/** A bare text field in the app's type; the container comes from where it is placed. */
@Composable
fun Input(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    keyboard: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    mono: Boolean = false,
    enabled: Boolean = true,
    align: TextAlign = TextAlign.Start,
    singleLine: Boolean = true,
    style: TextStyle = if (mono) Type.Mono else Type.Body,
) {
    val textStyle = style.copy(color = if (enabled) Dby.Fg else Dby.Tertiary, textAlign = align)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        singleLine = singleLine,
        textStyle = textStyle,
        cursorBrush = SolidColor(LocalAccent.current),
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboard,
            autoCorrectEnabled = false,
            capitalization = KeyboardCapitalization.None,
        ),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = modifier.heightIn(min = 44.dp),
        decorationBox = { inner ->
            Box(contentAlignment = if (align == TextAlign.End) Alignment.CenterEnd else Alignment.CenterStart) {
                if (value.isEmpty()) Text(placeholder, style = textStyle.copy(color = Dby.Faint), maxLines = 1)
                inner()
            }
        },
    )
}

/** A form row: label on the left, value on the right, as in the canvas's Edit row sheet. */
@Composable
fun FieldRow(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String = "",
    keyboard: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    mono: Boolean = true,
    supporting: String? = null,
    enabled: Boolean = true,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.width(112.dp)) {
            Text(label, style = Type.Body.copy(fontSize = 16.sp))
            if (supporting != null) Text(supporting, style = Type.Caption, color = Dby.Tertiary)
        }
        Input(value, onChange, Modifier.weight(1f), placeholder, keyboard, secret, mono, enabled, TextAlign.End)
    }
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .height(44.dp)
            .lightGlass(RoundedCornerShape(22.dp))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(DbyIcons.Search, contentDescription = null, tint = Dby.Tertiary, modifier = Modifier.size(18.dp))
        Input(value, onChange, Modifier.weight(1f), placeholder, style = Type.Body.copy(fontWeight = FontWeight.Normal))
        if (value.isNotEmpty()) {
            Icon(DbyIcons.Close, contentDescription = "Clear", tint = Dby.Tertiary, modifier = Modifier.size(18.dp).clickable { onChange("") })
        }
    }
}

/** A failure: one sentence, the raw text behind "Details", and an optional retry. */
@Composable
fun ProblemBanner(problem: Throwable, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    var details by remember(problem) { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(Dby.DangerFill)
            .border(0.5.dp, Color(0x40FF453A), shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(problem.sentence(), style = Type.Secondary.copy(fontWeight = FontWeight.SemiBold), color = Dby.Danger)
        if (details) Text(problem.details(), style = Type.MonoSmall, color = Dby.Secondary)
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(
                if (details) "Hide details" else "Details",
                style = Type.Caption.copy(fontWeight = FontWeight.SemiBold),
                color = Dby.Secondary,
                modifier = Modifier.clickable { details = !details },
            )
            if (onRetry != null) {
                Text("Retry", style = Type.Caption.copy(fontWeight = FontWeight.SemiBold), color = LocalAccent.current, modifier = Modifier.clickable(onClick = onRetry))
            }
        }
    }
}

@Composable
fun Busy(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(Modifier.size(16.dp), color = Dby.Secondary, strokeWidth = 2.dp)
        Text(text, style = Type.Secondary, color = Dby.Secondary)
    }
}

@Composable
fun EmptyState(title: String, body: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = Type.Section, textAlign = TextAlign.Center)
        Text(body, style = Type.Secondary, color = Dby.Secondary, textAlign = TextAlign.Center)
        if (action != null) PrimaryButton(action, onAction, Modifier.padding(top = 8.dp), icon = DbyIcons.Plus)
    }
}

/** SQL in the canvas's dark box, coloured. */
@Composable
fun SqlBox(sql: String, modifier: Modifier = Modifier, header: String? = null, note: String? = null) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color(0x52000000))
            .border(0.5.dp, Dby.Hairline, shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (header != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(header, style = Type.Secondary.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
                if (note != null) Text(note, style = Type.Caption, color = Dby.Secondary)
            }
        }
        Text(highlightSql(sql), style = Type.MonoCode, softWrap = false, modifier = Modifier.horizontalScroll(rememberScrollState()))
    }
}

/**
 * A bottom sheet drawn inside the screen rather than in its own window, so its frosted glass
 * blurs the screen underneath. Back and a tap on the scrim close it; the tab pill hides meanwhile.
 */
@Composable
fun Sheet(backdrop: Backdrop, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val nav = DbyApp.instance.nav
    DisposableEffect(Unit) {
        nav.sheetShown()
        onDispose { nav.sheetHidden() }
    }
    BackHandler(onBack = onDismiss)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Dby.Scrim).clickable(interactionSource = null, indication = null, onClick = onDismiss))
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = maxHeight * 0.92f)
                .frostedGlass(backdrop, UnevenRoundedRectangle(topStart = 32.dp, topEnd = 32.dp))
                .clickable(interactionSource = null, indication = null) {}
                .navigationBarsPadding()
                .imePadding()
                .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(40.dp, 5.dp).clip(CircleShape).background(Color(0x4DFFFFFF)))
            content()
        }
    }
}

@Composable
fun SheetHeader(title: String, subtitle: String? = null, onClose: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Type.Title)
            if (subtitle != null) Text(subtitle, style = Type.Mono, color = Dby.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
        RoundButton(DbyIcons.Close, "Close", onClose)
    }
}

class Action(val label: String, val icon: ImageVector? = null, val danger: Boolean = false, val onClick: () -> Unit)

/** A list of actions in a sheet: long-press menus and "More" buttons. */
@Composable
fun ActionSheet(backdrop: Backdrop, title: String?, actions: List<Action>, onDismiss: () -> Unit) {
    Sheet(backdrop, onDismiss) {
        if (title != null) Text(title, style = Type.Section, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Column(Modifier.fillMaxWidth().lightGlass(RoundedCornerShape(22.dp))) {
            actions.forEachIndexed { i, action ->
                if (i > 0) Hairline(if (action.icon != null) 52.dp else 16.dp)
                val tint = if (action.danger) Dby.Danger else Dby.Fg
                Row(
                    Modifier.fillMaxWidth().height(56.dp).clickable(role = Role.Button, onClick = action.onClick).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (action.icon != null) Icon(action.icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                    Text(action.label, style = Type.Body, color = tint)
                }
            }
        }
    }
}

/** "Are you sure?" with the SQL that will run, when there is one. */
@Composable
fun ConfirmSheet(
    backdrop: Backdrop,
    title: String,
    message: String? = null,
    sql: String? = null,
    confirm: String,
    danger: Boolean = false,
    busy: Boolean = false,
    problem: Throwable? = null,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Sheet(backdrop, onDismiss) {
        Text(title, style = Type.Title)
        if (message != null) Text(message, style = Type.Secondary, color = Dby.Secondary)
        if (sql != null) SqlBox(sql, header = "SQL to run")
        if (problem != null) ProblemBanner(problem, modifier = Modifier.padding(horizontal = 0.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Cancel", onDismiss, Modifier.weight(1f))
            if (danger) {
                SecondaryButton(confirm, onConfirm, Modifier.weight(1.4f), tint = Dby.Danger, busy = busy)
            } else {
                PrimaryButton(confirm, onConfirm, Modifier.weight(1.4f), busy = busy)
            }
        }
    }
}

/** Asks for a connection's password when none is saved or it can no longer be read. */
@Composable
fun PasswordSheet(backdrop: Backdrop, name: String, onSubmit: (password: String, remember: Boolean) -> Unit, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var remember by remember { mutableStateOf(true) }
    Sheet(backdrop, onDismiss) {
        Text("Password for $name", style = Type.Title)
        Text("The saved password can't be used, so enter it to connect.", style = Type.Secondary, color = Dby.Secondary)
        Column(Modifier.fillMaxWidth().lightGlass(RoundedCornerShape(22.dp))) {
            FieldRow("Password", password, { password = it }, keyboard = KeyboardType.Password, secret = true)
            Hairline()
            ToggleRow("Remember it", "Saved encrypted on this phone.", remember, { remember = it })
        }
        PrimaryButton("Connect", { onSubmit(password, remember) }, Modifier.fillMaxWidth())
    }
}

fun copyText(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}
```

- [ ] **Step 2: Write `ui/common/Connector.kt`**

```kotlin
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
```

- [ ] **Step 3: Build**

Run: `cd android && ./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. Fix any Compose API differences in this BOM by the smallest change, and ledger each as a ruling.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/dby/mobile/ui/common
git commit -m "feat(android): shared widgets, sheets and the connect flow"
```

---

### Task 4: Connections and New/Edit connection

**Files:**
- Create: `ui/connections/ConnectionsScreen.kt`, `ui/connections/EditConnectionScreen.kt`
- Modify: `AppRoot.kt` (two `when` branches)

**Interfaces:**
- Consumes: Tasks 1–3; core `openSession`, `ConnectParams`.
- Produces: `ConnectionsScreen(app)`, `EditConnectionScreen(app, id)`, `initials(name)`.

- [ ] **Step 1: Write `ui/connections/ConnectionsScreen.kt`**

```kotlin
package com.dby.mobile.ui.connections

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dby.core.Env
import com.dby.core.SavedConnection
import com.dby.mobile.DbyApp
import com.dby.mobile.Logo
import com.dby.mobile.bottomSpace
import com.dby.mobile.data.Sessions
import com.dby.mobile.topSpace
import com.dby.mobile.ui.DbyIcons
import com.dby.mobile.ui.common.Action
import com.dby.mobile.ui.common.ActionSheet
import com.dby.mobile.ui.common.Chip
import com.dby.mobile.ui.common.ConfirmSheet
import com.dby.mobile.ui.common.EmptyState
import com.dby.mobile.ui.common.EnvBadge
import com.dby.mobile.ui.common.GroupCard
import com.dby.mobile.ui.common.Hairline
import com.dby.mobile.ui.common.LargeTitle
import com.dby.mobile.ui.common.ListRow
import com.dby.mobile.ui.common.RoundButton
import com.dby.mobile.ui.common.SearchField
import com.dby.mobile.ui.common.SectionHeader
import com.dby.mobile.ui.glass.GlassHost
import com.dby.mobile.ui.nav.Screen
import com.dby.mobile.ui.nav.ScreenModel
import com.dby.mobile.ui.theme.Dby
import com.dby.mobile.ui.theme.Type
import com.dby.mobile.ui.theme.colors
import kotlinx.coroutines.launch

class ConnectionsModel(private val sessions: Sessions) : ScreenModel() {
    var query by mutableStateOf("")
    var env by mutableStateOf<Env?>(null)
    var menuFor by mutableStateOf<SavedConnection?>(null)
    var deleting by mutableStateOf<SavedConnection?>(null)

    val shown: List<SavedConnection>
        get() = sessions.connections.filter { c ->
            (env == null || c.env == env) &&
                (query.isBlank() || listOf(c.name, c.host, c.database, c.user).any { it.contains(query.trim(), ignoreCase = true) })
        }

    fun delete(c: SavedConnection) {
        scope.launch {
            sessions.delete(c.id)
            deleting = null
        }
    }

    fun disconnect(c: SavedConnection) {
        scope.launch { sessions.disconnect(c.id) }
    }
}

/** "My Orders DB" → "MO": the avatar's letters. */
fun initials(name: String): String =
    name.split(' ', '-', '_', '.').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "DB" }

@Composable
fun ConnectionsScreen(app: DbyApp) {
    val nav = app.nav
    val sessions = app.sessions
    val model = nav.model(Screen.Connections) { ConnectionsModel(sessions) }
    GlassHost(
        overlay = { backdrop ->
            model.menuFor?.let { c ->
                val actions = buildList {
                    add(Action("Edit", DbyIcons.Edit) { model.menuFor = null; nav.push(Screen.EditConnection(c.id)) })
                    add(Action(if (c.pinned) "Unpin" else "Pin", DbyIcons.Pin) { sessions.setPinned(c.id, !c.pinned); model.menuFor = null })
                    if (sessions.open.containsKey(c.id)) add(Action("Disconnect", DbyIcons.Unplug) { model.disconnect(c); model.menuFor = null })
                    add(Action("Delete", DbyIcons.Trash, danger = true) { model.menuFor = null; model.deleting = c })
                }
                ActionSheet(backdrop, c.name, actions) { model.menuFor = null }
            }
            model.deleting?.let { c ->
                ConfirmSheet(
                    backdrop,
                    title = "Delete ${c.name}?",
                    message = "Its saved password and cached schema are removed from this phone. Nothing changes on the server.",
                    confirm = "Delete",
                    danger = true,
                    onConfirm = { model.delete(c) },
                    onDismiss = { model.deleting = null },
                )
            }
        },
    ) {
        val shown = model.shown
        val pinned = shown.filter { it.pinned }
        val rest = shown.filterNot { it.pinned }
        val open = { c: SavedConnection -> nav.push(Screen.Explorer(c.id)) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = topSpace(), bottom = bottomSpace())) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                    Logo()
                    Text("DBY", style = Type.Secondary.copy(fontWeight = FontWeight.SemiBold), color = Dby.Secondary, modifier = Modifier.padding(start = 8.dp))
                    Spacer(Modifier.weight(1f))
                    RoundButton(DbyIcons.Plus, "New connection", { nav.push(Screen.EditConnection(null)) })
                }
            }
            item { LargeTitle("Connections", Modifier.padding(top = 4.dp, bottom = 12.dp)) }
            if (sessions.connections.isNotEmpty()) {
                item { SearchField(model.query, { model.query = it }, "Search connections") }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item { Chip("All", model.env == null, { model.env = null }) }
                        items(Env.entries) { env -> Chip(env.name, model.env == env, { model.env = env }) }
                    }
                }
            }
            when {
                sessions.connections.isEmpty() -> item {
                    EmptyState("No connections yet", "Add a MySQL or MariaDB server to start browsing.", "New connection") {
                        nav.push(Screen.EditConnection(null))
                    }
                }
                shown.isEmpty() -> item { EmptyState("Nothing matches", "Try another name, host or tag.") }
            }
            if (pinned.isNotEmpty()) {
                item { SectionHeader("Pinned") }
                item { ConnectionCard(pinned, sessions, open) { model.menuFor = it } }
            }
            if (rest.isNotEmpty()) {
                item { SectionHeader(if (pinned.isEmpty()) "All connections" else "Others") { Text("Recent", style = Type.Secondary, color = Dby.Secondary, modifier = Modifier.padding(end = 8.dp)) } }
                item { ConnectionCard(rest, sessions, open) { model.menuFor = it } }
            }
        }
    }
}

@Composable
private fun ConnectionCard(list: List<SavedConnection>, sessions: Sessions, onOpen: (SavedConnection) -> Unit, onMenu: (SavedConnection) -> Unit) {
    GroupCard {
        list.forEachIndexed { i, c ->
            if (i > 0) Hairline(68.dp)
            val session = sessions.open[c.id]
            ListRow(
                title = c.name,
                subtitle = if (session != null) "Connected · ${session.serverInfo().version} · ${c.host}" else "${c.user}@${c.host}:${c.port} · ${c.database}",
                onClick = { onOpen(c) },
                onLongClick = { onMenu(c) },
                leading = { Avatar(c, connected = session != null) },
                titleExtra = { EnvBadge(c.env) },
            )
        }
    }
}

@Composable
private fun Avatar(c: SavedConnection, connected: Boolean) {
    val (fg, bg) = c.env.colors()
    Box {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(bg), contentAlignment = Alignment.Center) {
            Text(initials(c.name), style = Type.Secondary.copy(fontWeight = FontWeight.Bold), color = fg)
        }
        if (connected) {
            Box(Modifier.align(Alignment.BottomEnd).offset(3.dp, 3.dp).size(12.dp).clip(CircleShape).background(Dby.Bg).padding(2.dp).clip(CircleShape).background(Dby.Success))
        }
    }
}
```

- [ ] **Step 2: Write `ui/connections/EditConnectionScreen.kt`**

```kotlin
package com.dby.mobile.ui.connections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dby.core.ConnectParams
import com.dby.core.Env
import com.dby.core.TlsMode
import com.dby.core.openSession
import com.dby.mobile.DbyApp
import com.dby.mobile.data.ConnectionForm
import com.dby.mobile.data.Sessions
import com.dby.mobile.topSpace
import com.dby.mobile.ui.common.ConfirmSheet
import com.dby.mobile.ui.common.FieldRow
import com.dby.mobile.ui.common.GroupCard
import com.dby.mobile.ui.common.Hairline
import com.dby.mobile.ui.common.LargeTitle
import com.dby.mobile.ui.common.PrimaryButton
import com.dby.mobile.ui.common.ProblemBanner
import com.dby.mobile.ui.common.SecondaryButton
import com.dby.mobile.ui.common.SectionHeader
import com.dby.mobile.ui.common.Segmented
import com.dby.mobile.ui.common.TopBar
import com.dby.mobile.ui.glass.GlassHost
import com.dby.mobile.ui.nav.Screen
import com.dby.mobile.ui.nav.ScreenModel
import com.dby.mobile.ui.theme.Dby
import com.dby.mobile.ui.theme.Type
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class EditConnectionModel(private val id: String?, private val sessions: Sessions) : ScreenModel() {
    private val existing = id?.let(sessions::saved)
    val isNew = existing == null
    var name by mutableStateOf(existing?.name ?: "")
    var host by mutableStateOf(existing?.host ?: "")
    var port by mutableStateOf(existing?.port?.toString() ?: "3306")
    var user by mutableStateOf(existing?.user ?: "")
    var password by mutableStateOf("")
    var database by mutableStateOf(existing?.database ?: "")
    var env by mutableStateOf(existing?.env ?: Env.DEV)
    var tls by mutableStateOf(existing?.tls ?: TlsMode.VERIFY)
    var busy by mutableStateOf(false)
    var tested by mutableStateOf<String?>(null)
    var problem by mutableStateOf<Throwable?>(null)
    var deleting by mutableStateOf(false)

    val valid: Boolean
        get() = name.isNotBlank() && host.isNotBlank() && user.isNotBlank() && database.isNotBlank() && port.toIntOrNull() in 1..65535

    fun test() {
        scope.launch {
            busy = true
            tested = null
            problem = null
            try {
                val pw = password.ifEmpty { existing?.let { sessions.savedPassword(it) } ?: "" }
                val session = openSession(ConnectParams(host.trim(), port.toInt().toUShort(), user, pw, database.trim(), tls))
                val info = session.serverInfo()
                tested = "Connected · ${info.version} · ${info.connectMs} ms"
                session.disconnect()
                session.close()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = e
            } finally {
                busy = false
            }
        }
    }

    fun save(done: () -> Unit) {
        scope.launch {
            busy = true
            problem = null
            try {
                val form = ConnectionForm(name, host, port.toInt(), user, database, env, tls)
                sessions.save(id, form, password.takeIf { it.isNotEmpty() || isNew })
                done()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = e
            } finally {
                busy = false
            }
        }
    }

    fun delete(done: () -> Unit) {
        val target = id ?: return
        scope.launch {
            sessions.delete(target)
            done()
        }
    }
}

private fun TlsMode.label() = when (this) {
    TlsMode.VERIFY -> "Verify"
    TlsMode.ENCRYPT_ONLY -> "Encrypt only"
    TlsMode.OFF -> "Off"
}

private fun TlsMode.explain() = when (this) {
    TlsMode.VERIFY -> "Encrypted, and the server's certificate is checked (Amazon RDS certificates included)."
    TlsMode.ENCRYPT_ONLY -> "Encrypted, not verified: anyone on the network path could pretend to be the server."
    TlsMode.OFF -> "Not encrypted: the password and data cross the network in the clear."
}

@Composable
fun EditConnectionScreen(app: DbyApp, id: String?) {
    val nav = app.nav
    val model = nav.model(Screen.EditConnection(id)) { EditConnectionModel(id, app.sessions) }
    GlassHost(
        overlay = { backdrop ->
            if (model.deleting) {
                ConfirmSheet(
                    backdrop,
                    title = "Delete ${model.name}?",
                    message = "Its saved password and cached schema are removed from this phone.",
                    confirm = "Delete",
                    danger = true,
                    onConfirm = { model.delete { model.deleting = false; nav.back(); nav.back() } },
                    onDismiss = { model.deleting = false },
                )
            }
        },
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = topSpace()).navigationBarsPadding().imePadding().padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            TopBar(onBack = { nav.back() })
            LargeTitle(if (model.isNew) "New connection" else "Edit connection", Modifier.padding(top = 8.dp, bottom = 16.dp))
            GroupCard {
                FieldRow("Name", model.name, { model.name = it }, "Orders — Production", mono = false)
                Hairline()
                FieldRow("Host", model.host, { model.host = it }, "db.example.com", KeyboardType.Uri)
                Hairline()
                FieldRow("Port", model.port, { model.port = it.filter(Char::isDigit) }, "3306", KeyboardType.Number)
                Hairline()
                FieldRow("User", model.user, { model.user = it }, "app_reader")
                Hairline()
                FieldRow("Password", model.password, { model.password = it }, if (model.isNew) "" else "Unchanged", KeyboardType.Password, secret = true)
                Hairline()
                FieldRow("Database", model.database, { model.database = it }, "shop")
            }
            SectionHeader("Environment")
            Segmented(Env.entries, model.env, { model.env = it }, { it.name }, Modifier.padding(horizontal = 16.dp).fillMaxWidth())
            Text(
                if (model.env == Env.PROD) "Opens read-only while “Read-only on PROD” is on in Settings." else "The tag colours this connection everywhere.",
                style = Type.Caption, color = Dby.Secondary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            SectionHeader("Encryption")
            Segmented(TlsMode.entries, model.tls, { model.tls = it }, { it.label() }, Modifier.padding(horizontal = 16.dp).fillMaxWidth())
            Text(
                model.tls.explain(),
                style = Type.Caption,
                color = if (model.tls == TlsMode.VERIFY) Dby.Secondary else Dby.Danger,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            model.tested?.let { Text(it, style = Type.Secondary.copy(fontWeight = FontWeight.SemiBold), color = Dby.Success, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
            model.problem?.let { ProblemBanner(it, modifier = Modifier.padding(vertical = 8.dp)) }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Test", model::test, Modifier.weight(1f), enabled = model.valid, busy = model.busy)
                PrimaryButton("Save", { model.save { nav.back() } }, Modifier.weight(1.4f), enabled = model.valid && !model.busy)
            }
            if (!model.isNew) {
                Text(
                    "Delete connection",
                    style = Type.Body,
                    color = Dby.Danger,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(12.dp).clickable { model.deleting = true },
                )
            }
        }
    }
}
```

`onConfirm` in the delete sheet pops twice only when the edit screen was opened from an Explorer; from Connections, the second `back()` is a no-op on the root. Use `nav.back()` once if the stack under it is Connections. Simplest correct behaviour: pop the edit screen only — replace `nav.back(); nav.back()` with `nav.back()` — a deleted connection's Explorer is never under the edit screen, because Edit is only reachable from Connections.

- [ ] **Step 3: Wire them into `AppRoot.kt`**

Replace `Screen.Connections -> Placeholder("Connections")` with `Screen.Connections -> ConnectionsScreen(app)` and `is Screen.EditConnection -> Placeholder("Edit connection")` with `is Screen.EditConnection -> EditConnectionScreen(app, screen.id)`, adding the imports `com.dby.mobile.ui.connections.ConnectionsScreen` and `com.dby.mobile.ui.connections.EditConnectionScreen`. In `EditConnectionScreen`'s delete `onConfirm`, use a single `nav.back()` (see the note above).

- [ ] **Step 4: Build, then add a connection on the emulator**

Run: `cd android && ./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk`
On the emulator: tap +, enter Name `Local MySQL`, Host `10.0.2.2`, Port `33084`, User `root`, Password `dbytest`, Database `shop`, Env LOCAL, Encryption `Encrypt only`; tap Test.
Expected: "Connected · MySQL 8.4.x · N ms" in green. Tap Save: back on Connections, the row shows the LOCAL badge and `root@10.0.2.2:33084 · shop`. Long-press shows Edit / Pin / Delete. Add a second connection `Local MariaDB` (port 33114, DEV) and a third with a wrong password, tap Test: "Wrong user or password." with Details. Screenshot each state and compare against the canvas's Connections artboard (spacing, badge colours, card radius, tab pill).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/dby/mobile
git commit -m "feat(android): Connections list and New/Edit connection with Test"
```

---

### Task 5: Explorer

**Files:**
- Create: `ui/explorer/ExplorerScreen.kt`
- Modify: `AppRoot.kt` (Explorer branch)

**Interfaces:**
- Consumes: Tasks 1–4; core `cachedSchema`, `Session.refreshSchema/databases/useDatabase/setReadOnly/isReadOnly/database/serverInfo`, `Schema`, `TableInfo`.
- Produces: `ExplorerScreen(app, connectionId)`; navigation to `Screen.Table(connectionId, table)`; sets `sessions.activeId` and switches to the Query tab for "New query".

- [ ] **Step 1: Write `ui/explorer/ExplorerScreen.kt`**

```kotlin
package com.dby.mobile.ui.explorer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dby.core.Env
import com.dby.core.Schema
import com.dby.core.Session
import com.dby.core.TableInfo
import com.dby.core.cachedSchema
import com.dby.mobile.DbyApp
import com.dby.mobile.bottomSpace
import com.dby.mobile.data.Prefs
import com.dby.mobile.data.Sessions
import com.dby.mobile.data.bytes
import com.dby.mobile.data.count
import com.dby.mobile.topSpace
import com.dby.mobile.ui.DbyIcons
import com.dby.mobile.ui.common.Action
import com.dby.mobile.ui.common.ActionSheet
import com.dby.mobile.ui.common.Busy
import com.dby.mobile.ui.common.ConfirmSheet
import com.dby.mobile.ui.common.Connector
import com.dby.mobile.ui.common.EmptyState
import com.dby.mobile.ui.common.EnvBadge
import com.dby.mobile.ui.common.GroupCard
import com.dby.mobile.ui.common.Hairline
import com.dby.mobile.ui.common.ListRow
import com.dby.mobile.ui.common.PasswordSheet
import com.dby.mobile.ui.common.ProblemBanner
import com.dby.mobile.ui.common.RoundButton
import com.dby.mobile.ui.common.SearchField
import com.dby.mobile.ui.common.SectionHeader
import com.dby.mobile.ui.common.Segmented
import com.dby.mobile.ui.common.Sheet
import com.dby.mobile.ui.common.TopBar
import com.dby.mobile.ui.glass.GlassHost
import com.dby.mobile.ui.glass.lightGlass
import com.dby.mobile.ui.nav.Screen
import com.dby.mobile.ui.nav.ScreenModel
import com.dby.mobile.ui.nav.Tab
import com.dby.mobile.ui.theme.Dby
import com.dby.mobile.ui.theme.LocalAccent
import com.dby.mobile.ui.theme.Type
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

enum class Kind { TABLES, VIEWS, ROUTINES }

class ExplorerModel(val id: String, private val sessions: Sessions, private val prefs: Prefs) : ScreenModel() {
    val connector = Connector(sessions, scope)
    val saved get() = sessions.saved(id)
    val session: Session? get() = sessions.open[id]
    var database by mutableStateOf(saved?.database ?: "")
        private set
    var schema by mutableStateOf<Schema?>(saved?.let { cachedSchema(id, it.database) })
        private set
    var loading by mutableStateOf(false)
        private set
    var problem by mutableStateOf<Throwable?>(null)
        private set
    var kind by mutableStateOf(Kind.TABLES)
    var filter by mutableStateOf("")
    var bySize by mutableStateOf(true)
    var pins by mutableStateOf(prefs.pinnedTables(id, database))
        private set
    var databases by mutableStateOf<List<String>?>(null)
        private set
    var readOnly by mutableStateOf(false)
        private set
    var menuOpen by mutableStateOf(false)
    var pickingDatabase by mutableStateOf(false)
    var unlocking by mutableStateOf(false)

    init {
        open()
    }

    /** Shows the cached schema at once, then connects (or reuses the open session) and refreshes it. */
    fun open() {
        val s = saved ?: return
        problem = null
        connector.open(s) { session -> refresh(session) }
    }

    private suspend fun refresh(session: Session) {
        loading = true
        try {
            readOnly = session.isReadOnly()
            database = session.database()
            schema = session.refreshSchema()
            pins = prefs.pinnedTables(id, database)
        } finally {
            loading = false
        }
    }

    fun reload() {
        val session = session ?: return open()
        launchGuarded { refresh(session) }
    }

    fun loadDatabases() {
        val session = session ?: return
        pickingDatabase = true
        launchGuarded { databases = session.databases() }
    }

    fun useDatabase(name: String) {
        val session = session ?: return
        pickingDatabase = false
        schema = cachedSchema(id, name)
        database = name
        pins = prefs.pinnedTables(id, name)
        launchGuarded {
            loading = true
            try {
                schema = session.useDatabase(name)
            } finally {
                loading = false
            }
        }
    }

    fun setWritable(writable: Boolean) {
        val session = session ?: return
        launchGuarded {
            session.setReadOnly(!writable)
            readOnly = !writable
            unlocking = false
        }
    }

    fun togglePin(table: String) {
        pins = if (table in pins) pins - table else pins + table
        prefs.setPinnedTables(id, database, pins)
    }

    fun disconnect(done: () -> Unit) {
        scope.launch {
            sessions.disconnect(id)
            done()
        }
    }

    val tables: List<TableInfo>
        get() {
            val all = schema?.tables.orEmpty().filter { (kind == Kind.VIEWS) == it.isView }
            val needle = filter.trim()
            val matched = if (needle.isEmpty()) all else all.filter { t -> t.name.contains(needle, true) || t.columns.any { it.name.contains(needle, true) } }
            return if (bySize) matched.sortedByDescending { it.bytes ?: 0u } else matched.sortedBy { it.name.lowercase() }
        }

    private fun launchGuarded(block: suspend () -> Unit) {
        scope.launch {
            problem = null
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = e
            }
        }
    }
}

private fun TableInfo.summary(): String {
    if (isView) return "View · ${columns.size} columns"
    val rows = rowsEstimate?.let { "~${count(it.toLong())} rows" } ?: "${columns.size} columns"
    return bytes?.let { "$rows · ${bytes(it.toLong())}" } ?: rows
}

@Composable
fun ExplorerScreen(app: DbyApp, connectionId: String) {
    val nav = app.nav
    val sessions = app.sessions
    val model = nav.model(Screen.Explorer(connectionId)) { ExplorerModel(connectionId, sessions, app.prefs) }
    val saved = model.saved ?: return
    GlassHost(
        overlay = { backdrop ->
            model.connector.asking?.let { c ->
                PasswordSheet(backdrop, c.name, onSubmit = { pw, remember -> model.connector.submit(pw, remember) }, onDismiss = { model.connector.dismiss(); nav.back() })
            }
            if (model.menuOpen) {
                val actions = buildList {
                    add(Action("Refresh schema", DbyIcons.Refresh) { model.menuOpen = false; model.reload() })
                    if (model.session != null && (model.readOnly || saved.env == Env.PROD)) {
                        add(
                            if (model.readOnly) Action("Unlock writes", DbyIcons.Unlock) { model.menuOpen = false; model.unlocking = true }
                            else Action("Lock writes", DbyIcons.Lock) { model.menuOpen = false; model.setWritable(false) },
                        )
                    }
                    add(Action("Edit connection", DbyIcons.Edit) { model.menuOpen = false; nav.push(Screen.EditConnection(connectionId)) })
                    if (model.session != null) add(Action("Disconnect", DbyIcons.Unplug) { model.menuOpen = false; model.disconnect { nav.back() } })
                }
                ActionSheet(backdrop, saved.name, actions) { model.menuOpen = false }
            }
            if (model.unlocking) {
                ConfirmSheet(
                    backdrop,
                    title = "Unlock writes on ${saved.name}?",
                    message = "This is a ${saved.env.name} connection. Edits and write statements will reach the server until you lock it again or disconnect.",
                    confirm = "Unlock",
                    danger = saved.env == Env.PROD,
                    onConfirm = { model.setWritable(true) },
                    onDismiss = { model.unlocking = false },
                )
            }
            if (model.pickingDatabase) {
                Sheet(backdrop, { model.pickingDatabase = false }) {
                    Text("Databases", style = Type.Title)
                    val list = model.databases
                    if (list == null) {
                        Busy("Loading databases…")
                    } else {
                        Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).lightGlass(RoundedCornerShape(22.dp)).verticalScroll(rememberScrollState())) {
                            list.forEachIndexed { i, name ->
                                if (i > 0) Hairline()
                                ListRow(
                                    title = name,
                                    titleStyle = Type.Mono,
                                    onClick = { model.useDatabase(name) },
                                    trailing = { if (name == model.database) Icon(DbyIcons.Check, null, tint = LocalAccent.current, modifier = Modifier.size(18.dp)) },
                                )
                            }
                        }
                    }
                }
            }
        },
    ) {
        val tables = model.tables
        val pinned = tables.filter { it.name in model.pins }
        val rest = tables.filterNot { it.name in model.pins }
        val schema = model.schema
        val openTable = { t: TableInfo -> nav.push(Screen.Table(connectionId, t.name)) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = topSpace(), bottom = bottomSpace())) {
            item {
                TopBar(onBack = { nav.back() }) {
                    RoundButton(DbyIcons.Query, "New query", {
                        sessions.activeId = connectionId
                        nav.select(Tab.QUERY)
                    }, enabled = model.session != null)
                    RoundButton(DbyIcons.More, "More options", { model.menuOpen = true })
                }
            }
            item {
                Row(
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    EnvBadge(saved.env)
                    val version = model.session?.serverInfo()?.version
                    Text(
                        listOfNotNull(saved.name, version).joinToString(" · "),
                        style = Type.Secondary,
                        color = Dby.Secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            item {
                Row(
                    Modifier
                        .padding(horizontal = 12.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = model.session != null, role = Role.Button) { model.loadDatabases() }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(model.database, style = Type.MonoTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Icon(DbyIcons.ChevronDown, contentDescription = "Switch database", tint = Dby.Secondary, modifier = Modifier.size(22.dp))
                }
            }
            if (model.readOnly) {
                item {
                    Row(
                        Modifier
                            .padding(start = 16.dp, top = 8.dp)
                            .clip(CircleShape)
                            .background(Color(0x33FF453A))
                            .clickable { model.unlocking = true }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(DbyIcons.Lock, contentDescription = null, tint = Dby.Danger, modifier = Modifier.size(14.dp))
                        Text("Read-only · Tap to unlock", style = Type.Caption.copy(fontWeight = FontWeight.SemiBold), color = Dby.Danger)
                    }
                }
            }
            if (model.connector.busy || model.loading) item { Busy(if (model.session == null) "Connecting…" else "Reading the schema…") }
            (model.connector.problem ?: model.problem)?.let { p -> item { ProblemBanner(p, onRetry = model::open, modifier = Modifier.padding(vertical = 8.dp)) } }
            if (sessions.lost[connectionId] == true) {
                item { ProblemBanner(IllegalStateException("The connection dropped while the app was away."), onRetry = model::reload, modifier = Modifier.padding(vertical = 8.dp)) }
            }
            if (schema != null) {
                item {
                    val tableCount = schema.tables.count { !it.isView }
                    val viewCount = schema.tables.count { it.isView }
                    Segmented(
                        Kind.entries,
                        model.kind,
                        { model.kind = it },
                        {
                            when (it) {
                                Kind.TABLES -> "Tables  $tableCount"
                                Kind.VIEWS -> "Views  $viewCount"
                                Kind.ROUTINES -> "Routines  ${schema.routines}"
                            }
                        },
                        Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp).fillMaxWidth(),
                    )
                }
                if (model.kind == Kind.ROUTINES) {
                    item {
                        EmptyState(
                            "${schema.routines} routines",
                            "Routines aren't browsable in this version. In Query, run SHOW PROCEDURE STATUS WHERE Db = DATABASE() to list them.",
                        )
                    }
                } else {
                    item { SearchField(model.filter, { model.filter = it }, "Filter tables and columns") }
                    if (pinned.isNotEmpty()) {
                        item { SectionHeader("Pinned") }
                        item { TableCard(pinned, openTable, model::togglePin) }
                    }
                    item {
                        SectionHeader(if (model.kind == Kind.VIEWS) "All views" else "All tables") {
                            Text(
                                if (model.bySize) "By size" else "By name",
                                style = Type.Secondary,
                                color = LocalAccent.current,
                                modifier = Modifier.clip(CircleShape).clickable { model.bySize = !model.bySize }.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                    if (rest.isEmpty()) {
                        item { Text("Nothing here.", style = Type.Secondary, color = Dby.Secondary, modifier = Modifier.padding(horizontal = 20.dp)) }
                    } else {
                        item { TableCard(rest, openTable, model::togglePin) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TableCard(tables: List<TableInfo>, onOpen: (TableInfo) -> Unit, onPin: (String) -> Unit) {
    GroupCard {
        tables.forEachIndexed { i, t ->
            if (i > 0) Hairline(60.dp)
            ListRow(
                title = t.name,
                subtitle = t.summary(),
                titleStyle = Type.Mono.copy(fontSize = 17.sp, fontWeight = FontWeight.Medium),
                onClick = { onOpen(t) },
                onLongClick = { onPin(t.name) },
                leading = {
                    Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(Dby.FillStrong), contentAlignment = Alignment.Center) {
                        Icon(if (t.isView) DbyIcons.View else DbyIcons.Table, contentDescription = null, tint = Dby.Secondary, modifier = Modifier.size(18.dp))
                    }
                },
            )
        }
    }
}
```

Add the missing imports this file needs: `androidx.compose.ui.graphics.Color` and `androidx.compose.ui.unit.sp`.

- [ ] **Step 2: Wire it into `AppRoot.kt`**

Replace `is Screen.Explorer -> Placeholder("Explorer ${screen.connectionId}")` with `is Screen.Explorer -> ExplorerScreen(app, screen.connectionId)` and import `com.dby.mobile.ui.explorer.ExplorerScreen`.

- [ ] **Step 3: Build and check against the local databases**

Run: `cd android && ./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk && adb logcat -c`
On the emulator: tap `Local MySQL`.
Expected: "Connecting…" then the Explorer: LOCAL badge, "Local MySQL · MySQL 8.4.x", `shop` with a chevron, Tables 8 / Views 1 / Routines 1, tables sorted by size with `orders` first and `~100,000 rows · N MB`. `adb logcat -d -s DBYBENCH:I` shows `event=connect`. Tap `shop` → database sheet lists `archive` among others; picking it shows table `old`; pick `shop` again. Filter `created` finds `orders`. Long-press `tags` pins it under "Pinned". Back, reopen: the schema appears at once from the cache. Open the PROD-tagged connection (edit `Local MariaDB` to PROD first): the red "Read-only · Tap to unlock" chip shows; unlocking asks for confirmation. Compare with the canvas's Explorer artboard.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/dby/mobile
git commit -m "feat(android): Explorer with cached schema, database switch, pins and PROD unlock"
```

---

### Task 6: Table data (grid, cards, pager, filters, facets, sort, export)

**Files:**
- Create: `ui/common/Grid.kt`, `ui/table/TableModel.kt`, `ui/table/TableScreen.kt`
- Modify: `data/Format.kt` (`csv`), `src/test/.../FormatTest.kt` (csv test), `AppRoot.kt` (Table branch)

**Interfaces:**
- Consumes: Tasks 1–5; core `Session.tablePage/countRows/facetCounts`, `PageRequest`, `CountRequest`, `FacetRequest`, `Filter`, `FilterOp`, `Sort`, `Page`, `ColumnOut`, `ColumnDef`, `TableInfo`.
- Produces: `columnWidth(type)`, `LazyListScope.gridItems(columns, rows, hScroll, keyColumns, sorted, onRow, onCell)`; `csv(columns, rows)`; `TableModel` with `session`, `info`, `defs`, `filters`, `sort`, `columns`, `rows`, `pageIndex`, `hasNext`, `total`, `facets`, `facetColumn`, `loading`, `problem`, `cards`, `readOnly`, `editor`, `viewing`, `filtering`, `sorting`, `reload()`, `reloadCurrent()`, `next()`, `previous()`, `addFilter(f)`, `removeFilter(i)`, `pickFacet(value)`, `setSort(s)`, `edit(rowIndex)`, `insert()`, `view(row, column)`, `rangeText`; `TableScreen(app, connectionId, table)`; `Filter.label()`.

- [ ] **Step 1: Write the failing CSV test**

Append to `FormatTest`:
```kotlin
    @Test
    fun csv_quotes_only_when_it_must() {
        val columns = listOf(com.dby.core.ColumnOut("id", "int"), com.dby.core.ColumnOut("note", "varchar"))
        val rows = listOf(listOf(Cell.Signed(1), Cell.Text("a,b \"c\"", 9u)), listOf(Cell.Signed(2), Cell.Null))
        assertEquals("id,note\n1,\"a,b \"\"c\"\"\"\n2,\n", com.dby.mobile.data.csv(columns, rows))
    }
```
Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: fails to compile: `Unresolved reference 'csv'`.

- [ ] **Step 2: Add `csv` to `data/Format.kt`**

```kotlin
/** The rows as CSV for sharing. Values trimmed by the page stay trimmed; NULL is an empty field. */
fun csv(columns: List<com.dby.core.ColumnOut>, rows: List<List<Cell>>): String = buildString {
    append(columns.joinToString(",") { csvField(it.name) }).append('\n')
    for (row in rows) append(row.joinToString(",") { if (it is Cell.Null) "" else csvField(it.rawText() ?: it.display()) }).append('\n')
}

private fun csvField(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
```
Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: `csv_quotes_only_when_it_must` passes (a trimmed text exports its 256-character start, as `rawText` gives).

- [ ] **Step 3: Write `ui/common/Grid.kt`**

```kotlin
package com.dby.mobile.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dby.core.Cell
import com.dby.core.ColumnOut
import com.dby.mobile.data.display
import com.dby.mobile.ui.DbyIcons
import com.dby.mobile.ui.theme.Dby
import com.dby.mobile.ui.theme.GeistMono
import com.dby.mobile.ui.theme.Type

private val NARROW = setOf("tinyint", "smallint", "mediumint", "int", "integer", "bigint", "year", "bit", "tiny", "short", "long", "longlong", "int24")
private val MEDIUM = setOf("decimal", "newdecimal", "numeric", "float", "double")

/** A grid column's width from its type: numbers narrow, dates and text wide. */
fun columnWidth(type: String): Dp = when {
    type in NARROW -> 96.dp
    type in MEDIUM -> 124.dp
    "date" in type || "time" in type -> 184.dp
    else -> 176.dp
}

/**
 * The grid as lazy items: a header, then one row per item. The first column stays put; the
 * rest scroll sideways together through one shared [hScroll].
 */
fun LazyListScope.gridItems(
    columns: List<ColumnOut>,
    rows: List<List<Cell>>,
    hScroll: ScrollState,
    keyColumns: Set<Int> = emptySet(),
    sorted: Pair<Int, Boolean>? = null,
    onRow: ((Int) -> Unit)? = null,
    onCell: ((row: Int, column: Int) -> Unit)? = null,
) {
    if (columns.isEmpty()) return
    item(key = "grid-header") {
        Row(Modifier.fillMaxWidth().height(40.dp).background(Dby.StripeSolid), verticalAlignment = Alignment.CenterVertically) {
            HeaderCell(columns[0], 0 in keyColumns, sorted?.takeIf { it.first == 0 }?.second)
            Row(Modifier.horizontalScroll(hScroll)) {
                for (i in 1 until columns.size) HeaderCell(columns[i], i in keyColumns, sorted?.takeIf { it.first == i }?.second)
            }
        }
    }
    itemsIndexed(rows, key = { i, _ -> "row-$i" }) { i, row ->
        Row(
            Modifier.fillMaxWidth().height(48.dp).background(if (i % 2 == 1) Dby.StripeSolid else Dby.Bg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GridCell(row.getOrElse(0) { Cell.Null }, columnWidth(columns[0].typeName), { onRow?.invoke(i) }, onCell?.let { { it(i, 0) } })
            Row(Modifier.horizontalScroll(hScroll)) {
                for (c in 1 until columns.size) {
                    GridCell(row.getOrElse(c) { Cell.Null }, columnWidth(columns[c].typeName), { onRow?.invoke(i) }, onCell?.let { { it(i, c) } })
                }
            }
        }
    }
}

@Composable
private fun HeaderCell(column: ColumnOut, key: Boolean, descending: Boolean?) {
    Row(
        Modifier.width(columnWidth(column.typeName)).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            column.name,
            style = Type.Caption.copy(fontFamily = GeistMono, fontWeight = FontWeight.SemiBold),
            color = Dby.Secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (key) Icon(DbyIcons.Key, contentDescription = "Primary key", tint = Dby.Tertiary, modifier = Modifier.size(12.dp))
        if (descending != null) {
            Icon(if (descending) DbyIcons.ArrowDown else DbyIcons.ArrowUp, contentDescription = if (descending) "Sorted descending" else "Sorted ascending", tint = Dby.Secondary, modifier = Modifier.size(12.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridCell(cell: Cell, width: Dp, onTap: () -> Unit, onLong: (() -> Unit)?) {
    Text(
        cell.display(),
        style = Type.Mono.copy(fontSize = 14.sp),
        color = if (cell is Cell.Null) Dby.Faint else Dby.Fg,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .width(width)
            .height(48.dp)
            .combinedClickable(onClick = onTap, onLongClick = onLong)
            .padding(horizontal = 12.dp, vertical = 14.dp),
    )
}
```

- [ ] **Step 4: Write `ui/table/TableModel.kt`**

```kotlin
package com.dby.mobile.ui.table

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dby.core.Cell
import com.dby.core.ColumnDef
import com.dby.core.ColumnOut
import com.dby.core.CountRequest
import com.dby.core.FacetCount
import com.dby.core.FacetRequest
import com.dby.core.Filter
import com.dby.core.FilterOp
import com.dby.core.Page
import com.dby.core.PageRequest
import com.dby.core.Session
import com.dby.core.Sort
import com.dby.core.TableInfo
import com.dby.core.cachedSchema
import com.dby.mobile.data.Prefs
import com.dby.mobile.data.Sessions
import com.dby.mobile.data.count
import com.dby.mobile.ui.nav.ScreenModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

sealed interface Total {
    data object Counting : Total
    /** The count took longer than two seconds (spec §6 rule 10). */
    data object Many : Total
    data class Exact(val n: Long) : Total
}

/** One table's pages (spec §8 "Browse a table"): first page, prefetched next page, then the count and facets. */
class TableModel(
    val connectionId: String,
    val table: String,
    private val sessions: Sessions,
    private val prefs: Prefs,
) : ScreenModel() {
    val session: Session? get() = sessions.open[connectionId]
    val database: String = session?.database() ?: sessions.saved(connectionId)?.database.orEmpty()
    val info: TableInfo? = cachedSchema(connectionId, database)?.tables?.firstOrNull { it.name == table }

    var filters by mutableStateOf<List<Filter>>(emptyList())
        private set
    var sort by mutableStateOf<Sort?>(null)
        private set
    var columns by mutableStateOf<List<ColumnOut>>(emptyList())
        private set
    var rows by mutableStateOf<List<List<Cell>>>(emptyList())
        private set
    var pageIndex by mutableIntStateOf(0)
        private set
    var hasNext by mutableStateOf(false)
        private set
    var total by mutableStateOf<Total>(Total.Counting)
        private set
    var facets by mutableStateOf<List<FacetCount>?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var problem by mutableStateOf<Throwable?>(null)
    var cards by mutableStateOf(prefs.rowsAsCards)

    /** Open sheets: the row editor, the cell viewer, the filter and sort pickers. */
    var editor by mutableStateOf<RowEditor?>(null)
    var viewing by mutableStateOf<Pair<Int, Int>?>(null)
    var filtering by mutableStateOf<FilterOp?>(null)
    var sorting by mutableStateOf(false)

    private val pages = mutableListOf<Page>()
    private var prefetched: Deferred<Page>? = null
    private val pageSize: UInt get() = prefs.rowsPerPage.toUInt()

    /** The column the quick-filter chips group by: the first ENUM column (as `status` on the canvas). */
    val facetColumn: ColumnDef? = info?.columns?.firstOrNull { it.enumValues.isNotEmpty() }
    val readOnly: Boolean get() = session?.isReadOnly() ?: true

    /** The schema's definition of each column on screen, by position. */
    val defs: List<ColumnDef?> get() = columns.map { c -> info?.columns?.firstOrNull { it.name == c.name } }
    val keyColumns: Set<Int> get() = defs.withIndex().filter { it.value?.pkSeq != null }.map { it.index }.toSet()

    init {
        reload()
    }

    fun reload() {
        val s = session ?: return gone()
        prefetched?.cancel()
        prefetched = null
        pages.clear()
        pageIndex = 0
        scope.launch {
            loading = true
            problem = null
            val started = SystemClock.elapsedRealtime()
            try {
                val page = s.tablePage(request(null))
                Log.i("DBYBENCH", "app=dby event=first_page ms=${SystemClock.elapsedRealtime() - started} rows=${page.rows.size}")
                show(page)
                count(s)
                facets(s)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = e
            } finally {
                loading = false
            }
        }
    }

    /** After a row change: fetch the page on screen again and recount. */
    fun reloadCurrent() {
        val s = session ?: return gone()
        val cursor = if (pageIndex == 0) null else pages[pageIndex - 1].next
        prefetched?.cancel()
        prefetched = null
        scope.launch {
            try {
                val page = s.tablePage(request(cursor))
                while (pages.size > pageIndex) pages.removeAt(pages.lastIndex)
                show(page)
                count(s)
                facets(s)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = e
            }
        }
    }

    fun next() {
        if (pageIndex < pages.lastIndex) {
            pageIndex++
            display()
            return
        }
        val pending = prefetched ?: return
        scope.launch {
            val started = SystemClock.elapsedRealtime()
            try {
                val page = pending.await()
                Log.i("DBYBENCH", "app=dby event=next_page ms=${SystemClock.elapsedRealtime() - started} rows=${page.rows.size}")
                show(page)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = e
            }
        }
    }

    fun previous() {
        if (pageIndex > 0) {
            pageIndex--
            display()
        }
    }

    fun addFilter(filter: Filter) {
        filters = filters + filter
        reload()
    }

    fun removeFilter(index: Int) {
        filters = filters.filterIndexed { i, _ -> i != index }
        reload()
    }

    /** A facet chip: replaces any filter on the facet column; null shows all. */
    fun pickFacet(value: String?) {
        val column = facetColumn?.name ?: return
        filters = filters.filterNot { it.column == column } + listOfNotNull(value?.let { Filter(column, FilterOp.EQ, it) })
        reload()
    }

    val facetValue: String? get() = filters.firstOrNull { it.column == facetColumn?.name && it.op == FilterOp.EQ }?.value

    fun setSort(value: Sort?) {
        sort = value
        sorting = false
        reload()
    }

    fun edit(rowIndex: Int) {
        editor = RowEditor(this, rowIndex)
    }

    fun insert() {
        editor = RowEditor(this, null)
    }

    val rangeText: String
        get() {
            if (rows.isEmpty()) return if (loading) "Loading…" else "No rows"
            val start = pageIndex.toLong() * pageSize.toLong() + 1
            val end = start + rows.size - 1
            return when (val t = total) {
                is Total.Exact -> "Rows ${count(start)}–${count(end)} of ${count(t.n)}"
                Total.Many -> "Rows ${count(start)}–${count(end)} of ${count(end)}+"
                Total.Counting -> "Rows ${count(start)}–${count(end)}"
            }
        }

    private fun request(cursor: com.dby.core.Cursor?) = PageRequest(table, filters, sort, cursor, pageSize)

    private fun show(page: Page) {
        pages.add(page)
        pageIndex = pages.lastIndex
        display()
        val cursor = page.next
        val s = session
        // Ask for the next page as soon as this one is shown, so "next" usually costs no round trip.
        prefetched = if (cursor == null || s == null) null else scope.async { s.tablePage(request(cursor)) }
    }

    private fun display() {
        val page = pages[pageIndex]
        columns = page.columns
        rows = page.rows
        hasNext = pageIndex < pages.lastIndex || page.next != null
    }

    private fun count(s: Session) {
        total = Total.Counting
        scope.launch {
            total = try {
                s.countRows(CountRequest(table, filters))?.let { Total.Exact(it.toLong()) } ?: Total.Many
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Total.Many
            }
        }
    }

    private fun facets(s: Session) {
        val column = facetColumn ?: return
        scope.launch {
            facets = try {
                s.facetCounts(FacetRequest(table, column.name, filters.filterNot { it.column == column.name }))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun gone() {
        problem = IllegalStateException("This connection is closed. Open it again from Connections.")
    }
}

/** "status = shipped", "notes is null", "name contains x": a filter chip's text. */
fun Filter.label(): String = when (op) {
    FilterOp.EQ -> "$column = $value"
    FilterOp.NOT_EQ -> "$column ≠ $value"
    FilterOp.GT -> "$column > $value"
    FilterOp.GE -> "$column ≥ $value"
    FilterOp.LT -> "$column < $value"
    FilterOp.LE -> "$column ≤ $value"
    FilterOp.CONTAINS -> "$column contains $value"
    FilterOp.IS_NULL -> "$column is null"
    FilterOp.IS_NOT_NULL -> "$column is not null"
}

fun FilterOp.symbol(): String = when (this) {
    FilterOp.EQ -> "="
    FilterOp.NOT_EQ -> "≠"
    FilterOp.GT -> ">"
    FilterOp.GE -> "≥"
    FilterOp.LT -> "<"
    FilterOp.LE -> "≤"
    FilterOp.CONTAINS -> "contains"
    FilterOp.IS_NULL -> "is null"
    FilterOp.IS_NOT_NULL -> "not null"
}
```

`RowEditor` arrives in Task 7; until then add this stub at the bottom of `TableModel.kt` so the task compiles, and delete it in Task 7 Step 1:
```kotlin
class RowEditor(val model: TableModel, val rowIndex: Int?)
```

- [ ] **Step 5: Write `ui/table/TableScreen.kt`**

```kotlin
package com.dby.mobile.ui.table

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dby.core.Cell
import com.dby.core.ColumnDef
import com.dby.core.ColumnOut
import com.dby.core.Filter
import com.dby.core.FilterOp
import com.dby.core.Sort
import com.dby.mobile.DbyApp
import com.dby.mobile.bottomSpace
import com.dby.mobile.data.bytes
import com.dby.mobile.data.count
import com.dby.mobile.data.csv
import com.dby.mobile.data.display
import com.dby.mobile.topSpace
import com.dby.mobile.ui.DbyIcons
import com.dby.mobile.ui.common.Busy
import com.dby.mobile.ui.common.Chip
import com.dby.mobile.ui.common.EmptyState
import com.dby.mobile.ui.common.Hairline
import com.dby.mobile.ui.common.Input
import com.dby.mobile.ui.common.ListRow
import com.dby.mobile.ui.common.PrimaryButton
import com.dby.mobile.ui.common.ProblemBanner
import com.dby.mobile.ui.common.RoundButton
import com.dby.mobile.ui.common.Segmented
import com.dby.mobile.ui.common.Sheet
import com.dby.mobile.ui.common.TopBar
import com.dby.mobile.ui.common.gridItems
import com.dby.mobile.ui.glass.GlassHost
import com.dby.mobile.ui.glass.frostedGlass
import com.dby.mobile.ui.glass.lightGlass
import com.dby.mobile.ui.nav.Screen
import com.dby.mobile.ui.theme.Dby
import com.dby.mobile.ui.theme.LocalAccent
import com.dby.mobile.ui.theme.Type
import com.kyant.backdrop.Backdrop
import com.kyant.shapes.RoundedRectangle

private val TEMPORAL = setOf("date", "datetime", "timestamp", "time", "year")
private val AMOUNT = setOf("decimal", "numeric", "float", "double")
private val TEXT = setOf("char", "varchar", "tinytext", "text", "mediumtext", "longtext")

/** Which columns a row card shows, picked from the schema: a title, an amount, a time, a status pill. */
private data class CardLayout(val title: Int, val amount: Int?, val time: Int?, val pill: Int?, val key: Int?)

private fun cardLayout(defs: List<ColumnDef?>, keys: Set<Int>): CardLayout {
    fun first(test: (ColumnDef) -> Boolean) = defs.indices.firstOrNull { it !in keys && defs[it]?.let(test) == true }
    return CardLayout(
        title = first { it.dataType in TEXT } ?: 0,
        amount = first { it.dataType in AMOUNT },
        time = first { it.dataType in TEMPORAL },
        pill = first { it.enumValues.isNotEmpty() },
        key = keys.minOrNull(),
    )
}

/** Status pills cycle through the canvas's four tints by value. */
private val PILLS = listOf(
    Color(0x295AC8FA) to Color(0xFF9BDFFF),
    Color(0x2EFF9F0A) to Color(0xFFFFC56B),
    Color(0x2930D158) to Color(0xFF86E8A0),
    Color(0x1AFFFFFF) to Color(0xB8FFFFFF),
)

@Composable
fun TableScreen(app: DbyApp, connectionId: String, table: String) {
    val nav = app.nav
    val model = nav.model(Screen.Table(connectionId, table)) { TableModel(connectionId, table, app.sessions, app.prefs) }
    val context = LocalContext.current
    GlassHost(
        overlay = { backdrop ->
            if (!nav.sheetOpen) PagerBar(model, backdrop, Modifier.align(Alignment.BottomCenter))
            model.filtering?.let { op -> FilterSheet(model, op, backdrop) { model.filtering = null } }
            if (model.sorting) SortSheet(model, backdrop)
            model.editor?.let { RowSheet(it, backdrop) }
            model.viewing?.let { (row, column) -> CellViewer(model, row, column, backdrop) { model.viewing = null } }
        },
    ) {
        val hScroll = rememberScrollState()
        val defs = model.defs
        val keys = model.keyColumns
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = topSpace(), bottom = bottomSpace(80.dp))) {
            item {
                TopBar(onBack = { nav.back() }) {
                    RoundButton(DbyIcons.Search, "Search in table", { model.filtering = FilterOp.CONTAINS })
                    RoundButton(DbyIcons.Export, "Export rows", { shareCsv(context, table, csv(model.columns, model.rows)) }, enabled = model.rows.isNotEmpty())
                    RoundButton(DbyIcons.Plus, "Insert row", model::insert, enabled = !model.readOnly && model.info?.isView == false)
                }
            }
            item {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(table, style = Type.MonoTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val info = model.info
                    val details = listOfNotNull(
                        model.database,
                        info?.rowsEstimate?.let { "~${count(it.toLong())} rows" },
                        info?.bytes?.let { bytes(it.toLong()) },
                        if (model.readOnly) "read-only" else null,
                    )
                    Text(details.joinToString(" · "), style = Type.Secondary, color = Dby.Secondary)
                }
            }
            item {
                LazyRow(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(model.filters) { i, f ->
                        if (f.column != model.facetColumn?.name || f.op != FilterOp.EQ) {
                            Chip(f.label(), selected = false, onClick = { model.removeFilter(i) }, icon = DbyIcons.Filter, onClose = { model.removeFilter(i) })
                        }
                    }
                    item { Chip("Filter", selected = false, onClick = { model.filtering = FilterOp.EQ }, icon = DbyIcons.Plus) }
                }
            }
            model.facetColumn?.let { facet ->
                item {
                    val counts = model.facets?.associate { it.value to it.count }
                    LazyRow(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            val all = counts?.values?.sum()?.toLong()
                            Chip("All", model.facetValue == null, { model.pickFacet(null) }, count = all?.let { count(it) })
                        }
                        items(facet.enumValues) { value ->
                            Chip(value, model.facetValue == value, { model.pickFacet(value) }, count = counts?.get(value)?.let { count(it.toLong()) })
                        }
                    }
                }
            }
            item {
                Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.clip(CircleShape).clickable(role = Role.Button) { model.sorting = true }.padding(horizontal = 4.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(DbyIcons.Sort, contentDescription = null, tint = LocalAccent.current, modifier = Modifier.size(18.dp))
                        val sort = model.sort
                        Text(
                            if (sort == null) "Key order" else "${sort.column} ${if (sort.descending) "↓" else "↑"}",
                            style = Type.Secondary.copy(fontWeight = FontWeight.SemiBold),
                            color = LocalAccent.current,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Segmented(listOf(true, false), model.cards, { model.cards = it }, { if (it) "Cards" else "Grid" }, Modifier.size(width = 168.dp, height = 44.dp))
                }
            }
            model.problem?.let { p -> item { ProblemBanner(p, onRetry = model::reload, modifier = Modifier.padding(vertical = 8.dp)) } }
            if (model.loading && model.rows.isEmpty()) item { Busy("Loading rows…") }
            if (!model.loading && model.problem == null && model.rows.isEmpty()) {
                item { EmptyState("No rows", if (model.filters.isEmpty()) "This table is empty." else "Nothing matches these filters.") }
            }
            if (model.cards) {
                cardItems(model, cardLayout(defs, keys))
            } else {
                val sorted = model.sort?.let { s -> model.columns.indexOfFirst { it.name == s.column }.takeIf { it >= 0 }?.let { it to s.descending } }
                gridItems(model.columns, model.rows, hScroll, keys, sorted, onRow = model::edit, onCell = { r, c -> model.viewing = r to c })
            }
        }
    }
}

private fun LazyListScope.cardItems(model: TableModel, layout: CardLayout) {
    itemsIndexed(model.rows, key = { i, _ -> "card-$i" }) { i, row ->
        Column(
            Modifier
                .padding(horizontal = 16.dp, vertical = 5.dp)
                .fillMaxWidth()
                .lightGlass(RoundedCornerShape(22.dp))
                .clickable { model.edit(i) }
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(row.getOrElse(layout.title) { Cell.Null }.display(), style = Type.Body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                layout.amount?.let { Text(row[it].display(), style = Type.Mono.copy(fontWeight = FontWeight.SemiBold)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val sub = listOfNotNull(layout.key?.let { "#${row[it].display()}" }, layout.time?.let { row[it].display() })
                Text(sub.joinToString(" · "), style = Type.Secondary, color = Dby.Secondary, maxLines = 1, modifier = Modifier.weight(1f))
                layout.pill?.let { p ->
                    val value = row[p].display()
                    val (bg, fg) = PILLS[Math.floorMod(value.hashCode(), PILLS.size)]
                    Text(value, style = Type.Caption.copy(fontWeight = FontWeight.SemiBold), color = fg, modifier = Modifier.clip(CircleShape).background(bg).padding(horizontal = 10.dp, vertical = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun PagerBar(model: TableModel, backdrop: Backdrop, modifier: Modifier) {
    Row(
        modifier
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 100.dp)
            .fillMaxWidth()
            .frostedGlass(backdrop, RoundedRectangle(28.dp))
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundButton(DbyIcons.Back, "Previous page", model::previous, enabled = model.pageIndex > 0)
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(model.rangeText, style = Type.Secondary.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
            Text("Tap a row to view or edit", style = Type.Caption, color = Dby.Secondary)
        }
        RoundButton(DbyIcons.Chevron, "Next page", model::next, enabled = model.hasNext)
    }
}

@Composable
private fun FilterSheet(model: TableModel, initial: FilterOp, backdrop: Backdrop, onDismiss: () -> Unit) {
    val columns = model.columns.map(ColumnOut::name)
    val firstText = model.defs.indexOfFirst { it?.dataType in TEXT }.takeIf { it >= 0 }
    var column by remember { mutableStateOf(columns.getOrElse(if (initial == FilterOp.CONTAINS) firstText ?: 0 else 0) { "" }) }
    var op by remember { mutableStateOf(initial) }
    var value by remember { mutableStateOf("") }
    val needsValue = op != FilterOp.IS_NULL && op != FilterOp.IS_NOT_NULL
    val def = model.info?.columns?.firstOrNull { it.name == column }
    Sheet(backdrop, onDismiss) {
        Text(if (initial == FilterOp.CONTAINS) "Search in ${model.table}" else "Add filter", style = Type.Title)
        Text("Column", style = Type.Caption, color = Dby.Secondary)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(columns) { name -> Chip(name, name == column, { column = name }) }
        }
        Text("Condition", style = Type.Caption, color = Dby.Secondary)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(FilterOp.entries) { o -> Chip(o.symbol(), o == op, { op = o }) }
        }
        if (needsValue) {
            if (def != null && def.enumValues.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(def.enumValues) { v -> Chip(v, v == value, { value = v }) }
                }
            } else {
                Input(
                    value,
                    { value = it },
                    Modifier.fillMaxWidth().lightGlass(RoundedCornerShape(18.dp)).padding(horizontal = 16.dp),
                    placeholder = def?.columnType ?: "Value",
                    mono = true,
                )
            }
        }
        PrimaryButton(
            "Apply",
            {
                model.addFilter(Filter(column, op, if (needsValue) value else ""))
                onDismiss()
            },
            Modifier.fillMaxWidth(),
            enabled = column.isNotEmpty() && (!needsValue || value.isNotEmpty()),
        )
    }
}

@Composable
private fun SortSheet(model: TableModel, backdrop: Backdrop) {
    var descending by remember { mutableStateOf(model.sort?.descending ?: true) }
    Sheet(backdrop, { model.sorting = false }) {
        Text("Sort by", style = Type.Title)
        Segmented(listOf(true, false), descending, { descending = it }, { if (it) "Descending" else "Ascending" }, Modifier.fillMaxWidth())
        Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).lightGlass(RoundedCornerShape(22.dp)).verticalScroll(rememberScrollState())) {
            ListRow("Key order", onClick = { model.setSort(null) }, trailing = { if (model.sort == null) Icon(DbyIcons.Check, null, tint = LocalAccent.current) })
            for (c in model.columns) {
                Hairline()
                ListRow(
                    c.name,
                    subtitle = c.typeName,
                    titleStyle = Type.Mono,
                    onClick = { model.setSort(Sort(c.name, descending)) },
                    trailing = { if (model.sort?.column == c.name) Icon(DbyIcons.Check, null, tint = LocalAccent.current) },
                )
            }
        }
    }
}

private fun shareCsv(context: Context, table: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_SUBJECT, "$table.csv").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, "Export $table"))
}
```

`RowSheet` and `CellViewer` are Task 7; until then add to the bottom of `TableScreen.kt`:
```kotlin
@Composable
private fun RowSheet(editor: RowEditor, backdrop: Backdrop) = Sheet(backdrop, { editor.model.editor = null }) { Text("Row ${editor.rowIndex}") }

@Composable
private fun CellViewer(model: TableModel, row: Int, column: Int, backdrop: Backdrop, onDismiss: () -> Unit) = Sheet(backdrop, onDismiss) { Text(model.rows[row][column].display()) }
```
(Task 7 deletes both stubs.)

- [ ] **Step 6: Wire into `AppRoot.kt` and check on the emulator**

Replace the Table placeholder with `is Screen.Table -> TableScreen(app, screen.connectionId, screen.table)` and import `com.dby.mobile.ui.table.TableScreen`.
Run: `cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest && adb install -r app/build/outputs/apk/debug/app-debug.apk && adb logcat -c`
On the emulator: Local MySQL → `orders`. Expected: cards with `Customer N`, the total on the right, `#id · created_at`; no status pill (orders.status is VARCHAR); pager "Rows 1–50 of 100,000"; Next shows "Rows 51–100" at once (prefetched); Grid toggle shows the pinned `id` column with sideways scrolling of the rest; Sort → created_at Descending shows id 100000 first; Filter → status = shipped shows "Rows 1–50 of 25,000". Open `items`: facet chips All 100 · new 34 · used 33 · broken 33; tapping `used` filters. Export opens the share sheet. `adb logcat -d -s DBYBENCH:I` shows first_page and next_page lines. Compare cards, chips and pager with the canvas's Table data artboard.

- [ ] **Step 7: Commit**

```bash
git add android/app/src
git commit -m "feat(android): Table data with cards and grid, prefetching pager, filters, facets, sort and CSV export"
```

---

### Task 7: Edit row sheet and cell viewer

**Files:**
- Create: `ui/table/RowSheet.kt`, `ui/table/CellViewer.kt`
- Modify: `ui/table/TableModel.kt` (delete the `RowEditor` stub), `ui/table/TableScreen.kt` (delete the two stubs)

**Interfaces:**
- Consumes: Task 6 `TableModel`; core `RowChange`, `ChangeKind`, `FieldValue`, `CellRequest`, `Session.previewRowChange/applyRowChange/fullCell`.
- Produces: `RowEditor(model, rowIndex)` with `texts`, `nulls`, `values`, `editable(i)`, `changed(i)`, `change()`, `preview`, `busy`, `problem`, `deleting`, `loadFull(i)`, `commit()`, `delete()`, `json()`, `duplicate()`; `RowSheet(editor, backdrop)`; `CellViewer(model, row, column, backdrop, onDismiss)`.

- [ ] **Step 1: Write `ui/table/RowSheet.kt`**

```kotlin
package com.dby.mobile.ui.table

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dby.core.Cell
import com.dby.core.CellRequest
import com.dby.core.ChangeKind
import com.dby.core.ColumnDef
import com.dby.core.FieldValue
import com.dby.core.RowChange
import com.dby.mobile.data.display
import com.dby.mobile.data.isTrimmed
import com.dby.mobile.data.rawText
import com.dby.mobile.ui.DbyIcons
import com.dby.mobile.ui.common.Action
import com.dby.mobile.ui.common.ActionSheet
import com.dby.mobile.ui.common.ConfirmSheet
import com.dby.mobile.ui.common.Hairline
import com.dby.mobile.ui.common.Input
import com.dby.mobile.ui.common.PrimaryButton
import com.dby.mobile.ui.common.ProblemBanner
import com.dby.mobile.ui.common.RoundButton
import com.dby.mobile.ui.common.SecondaryButton
import com.dby.mobile.ui.common.Sheet
import com.dby.mobile.ui.common.SheetHeader
import com.dby.mobile.ui.common.SqlBox
import com.dby.mobile.ui.common.copyText
import com.dby.mobile.ui.glass.lightGlass
import com.dby.mobile.ui.theme.Dby
import com.dby.mobile.ui.theme.LocalAccent
import com.dby.mobile.ui.theme.Type
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Types an edit field never writes: binary values only reach the phone as a preview. */
private val BINARY = setOf("binary", "varbinary", "tinyblob", "blob", "mediumblob", "longblob", "geometry", "point", "linestring", "polygon")

/**
 * The Edit row sheet's state (spec §7, §8): one text and one NULL flag per column. The change
 * sent is only the columns that differ; a value the page trimmed stays read-only until its
 * full value is loaded, so a preview is never written back.
 */
class RowEditor(val model: TableModel, val rowIndex: Int?) {
    val defs: List<ColumnDef?> = model.defs
    private val original: List<Cell>? = rowIndex?.let { model.rows[it] }
    val values = mutableStateListOf<Cell?>().apply { addAll(original ?: model.columns.map { null }) }
    val texts = mutableStateListOf<String>().apply { addAll(original?.map { it.rawText().orEmpty() } ?: model.columns.map { "" }) }
    val nulls = mutableStateListOf<Boolean>().apply { addAll(original?.map { it is Cell.Null } ?: model.columns.map { false }) }
    var busy by mutableStateOf(false)
    var problem by mutableStateOf<Throwable?>(null)
    var deleting by mutableStateOf(false)
    var menuOpen by mutableStateOf(false)
    val isInsert = rowIndex == null

    fun editable(i: Int): Boolean {
        val def = defs[i] ?: return false
        if (def.dataType in BINARY || values[i] is Cell.Bytes) return false
        if (!isInsert && def.pkSeq != null) return false
        return values[i]?.isTrimmed() != true
    }

    fun changed(i: Int): Boolean {
        val before = original?.get(i) ?: return nulls[i] || texts[i].isNotEmpty()
        return nulls[i] != (before is Cell.Null) || (!nulls[i] && texts[i] != before.rawText().orEmpty())
    }

    /** The primary-key values of the row being edited, in key order. */
    fun key(): List<Cell> {
        val row = original ?: return emptyList()
        return defs.withIndex().filter { it.value?.pkSeq != null }.sortedBy { it.value!!.pkSeq }.map { row[it.index] }
    }

    fun change(): RowChange? {
        val fields = model.columns.indices
            .filter { editable(it) && changed(it) }
            .map { FieldValue(model.columns[it].name, if (nulls[it]) null else texts[it]) }
        if (fields.isEmpty()) return null
        return RowChange(model.table, if (isInsert) ChangeKind.INSERT else ChangeKind.UPDATE, key(), fields)
    }

    fun preview(change: RowChange?): String? = change?.let { runCatching { model.session?.previewRowChange(it) }.getOrNull() }

    fun loadFull(i: Int) {
        val session = model.session ?: return
        model.scope.launch {
            try {
                val cell = session.fullCell(CellRequest(model.table, model.columns[i].name, key()))
                values[i] = cell
                texts[i] = cell.rawText().orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = e
            }
        }
    }

    fun commit() = apply(change())

    fun delete() = apply(RowChange(model.table, ChangeKind.DELETE, key(), emptyList()))

    private fun apply(change: RowChange?) {
        val session = model.session ?: return
        if (change == null) return
        model.scope.launch {
            busy = true
            problem = null
            try {
                session.applyRowChange(change)
                model.editor = null
                model.reloadCurrent()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = e
            } finally {
                busy = false
            }
        }
    }

    /** The row as JSON; exact numbers stay strings so nothing is rounded. */
    fun json(): String {
        val row = original ?: return "{}"
        val obj = JSONObject()
        model.columns.forEachIndexed { i, c ->
            obj.put(
                c.name,
                when (val cell = row[i]) {
                    is Cell.Null -> JSONObject.NULL
                    is Cell.Signed -> cell.v
                    is Cell.Real -> cell.v
                    else -> cell.display()
                },
            )
        }
        return obj.toString(2)
    }

    /** A new-row editor holding this row's values, without its key. */
    fun duplicate(): RowEditor = RowEditor(model, null).also { copy ->
        for (i in model.columns.indices) {
            if (defs[i]?.pkSeq == null && editable(i)) {
                copy.texts[i] = texts[i]
                copy.nulls[i] = nulls[i]
            }
        }
    }
}

@Composable
fun RowSheet(editor: RowEditor, backdrop: Backdrop) {
    val model = editor.model
    val context = LocalContext.current
    val close = { model.editor = null }
    val change by remember { derivedStateOf { editor.change() } }
    val sql = editor.preview(change)
    Sheet(backdrop, close) {
        val keyText = editor.key().joinToString(", ") { it.display() }
        SheetHeader(
            if (editor.isInsert) "New row" else "Edit row",
            subtitle = if (editor.isInsert) model.table else "${model.table} · ${keyText.ifEmpty { "row ${editor.rowIndex!! + 1}" }}",
            onClose = close,
        ) {
            if (!editor.isInsert) RoundButton(DbyIcons.More, "Row actions: copy as JSON, duplicate, delete", { editor.menuOpen = true })
        }
        Column(
            Modifier.weight(1f, fill = false).fillMaxWidth().lightGlass(RoundedCornerShape(20.dp)).verticalScroll(rememberScrollState()),
        ) {
            model.columns.forEachIndexed { i, column ->
                if (i > 0) Hairline()
                FieldLine(editor, i, column.name)
            }
        }
        if (sql != null) SqlBox(sql, header = "SQL to run", note = if (editor.isInsert) "1 row will be added" else "1 row will change")
        if (model.readOnly) {
            Text("This connection is read-only. Unlock writes on its Explorer screen to save.", style = Type.Secondary, color = Dby.Danger)
        }
        editor.problem?.let { ProblemBanner(it, modifier = Modifier.padding(0.dp)) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Discard", close, Modifier.weight(1f))
            PrimaryButton(
                if (editor.isInsert) "Add row" else "Commit change",
                editor::commit,
                Modifier.weight(1.4f),
                enabled = change != null && !model.readOnly,
                icon = DbyIcons.Check,
                busy = editor.busy,
            )
        }
    }
    if (editor.menuOpen) {
        ActionSheet(
            backdrop,
            null,
            listOf(
                Action("Copy as JSON", DbyIcons.Copy) { editor.menuOpen = false; copyText(context, model.table, editor.json()) },
                Action("Duplicate", DbyIcons.Plus) { editor.menuOpen = false; model.editor = editor.duplicate() },
                Action("Delete row", DbyIcons.Trash, danger = true) { editor.menuOpen = false; editor.deleting = true },
            ),
        ) { editor.menuOpen = false }
    }
    if (editor.deleting) {
        val delete = RowChange(model.table, ChangeKind.DELETE, editor.key(), emptyList())
        ConfirmSheet(
            backdrop,
            title = "Delete this row?",
            sql = editor.preview(delete),
            confirm = "Delete",
            danger = true,
            busy = editor.busy,
            problem = editor.problem,
            onConfirm = editor::delete,
            onDismiss = { editor.deleting = false },
        )
    }
}

@Composable
private fun FieldLine(editor: RowEditor, i: Int, name: String) {
    val def = editor.defs[i]
    val accent = LocalAccent.current
    val editable = editor.editable(i) && !editor.model.readOnly
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.width(128.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(name, style = Type.Mono.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (editor.changed(i) && !editor.isInsert) {
                val was = editor.values[i]?.display() ?: ""
                Text("Edited · was $was", style = Type.Caption.copy(fontWeight = FontWeight.SemiBold), color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                val type = listOfNotNull(def?.columnType ?: def?.dataType, if (def?.pkSeq != null) "key" else null, if (def?.nullable == true) "nullable" else null)
                Text(type.joinToString(" · "), style = Type.Caption, color = Dby.Tertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        val cell = editor.values[i]
        when {
            cell != null && cell.isTrimmed() && cell !is Cell.Bytes -> Text(
                "Load full value",
                style = Type.Secondary.copy(fontWeight = FontWeight.SemiBold),
                color = accent,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f).clickable { editor.loadFull(i) }.padding(vertical = 12.dp, horizontal = 8.dp),
            )
            !editable -> Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                Icon(DbyIcons.Lock, contentDescription = "Read only", tint = Dby.Faint, modifier = Modifier.size(14.dp))
                Text(cell?.display() ?: "", style = Type.Mono, color = Dby.Tertiary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 8.dp))
            }
            def != null && def.enumValues.isNotEmpty() -> EnumPicker(editor, i, def.enumValues, Modifier.weight(1f))
            editor.nulls[i] -> Text(
                "NULL",
                style = Type.Mono.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                color = Dby.Faint,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f).clickable { editor.nulls[i] = false }.padding(vertical = 12.dp, horizontal = 8.dp),
            )
            else -> Input(
                editor.texts[i],
                { editor.texts[i] = it },
                Modifier.weight(1f).padding(end = 8.dp),
                placeholder = if (editor.isInsert) "default" else "",
                mono = true,
                align = TextAlign.End,
            )
        }
        if (editable && def?.nullable == true) {
            Text(
                "NULL",
                style = Type.Caption.copy(fontWeight = FontWeight.Bold),
                color = if (editor.nulls[i]) Dby.Bg else Dby.Tertiary,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (editor.nulls[i]) Dby.Fg else Dby.Fill)
                    .clickable { editor.nulls[i] = !editor.nulls[i] }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun EnumPicker(editor: RowEditor, i: Int, options: List<String>, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    val accent = LocalAccent.current
    Box(modifier, contentAlignment = Alignment.CenterEnd) {
        Row(
            Modifier.clip(RoundedCornerShape(12.dp)).clickable { open = true }.padding(horizontal = 10.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(editor.texts[i].ifEmpty { "choose" }, style = Type.Mono.copy(fontWeight = FontWeight.SemiBold), color = if (editor.changed(i)) accent else Dby.Fg)
            Icon(DbyIcons.ChevronDown, contentDescription = null, tint = Dby.Secondary, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(open, { open = false }, containerColor = Dby.Solid, shape = RoundedCornerShape(16.dp)) {
            for (option in options) {
                DropdownMenuItem(
                    text = { Text(option, style = Type.Mono) },
                    onClick = {
                        editor.texts[i] = option
                        editor.nulls[i] = false
                        open = false
                    },
                )
            }
        }
    }
}
```

- [ ] **Step 2: Write `ui/table/CellViewer.kt`**

```kotlin
package com.dby.mobile.ui.table

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dby.core.Cell
import com.dby.core.CellRequest
import com.dby.mobile.data.count
import com.dby.mobile.data.display
import com.dby.mobile.data.isTrimmed
import com.dby.mobile.data.rawText
import com.dby.mobile.ui.DbyIcons
import com.dby.mobile.ui.common.Busy
import com.dby.mobile.ui.common.ProblemBanner
import com.dby.mobile.ui.common.RoundButton
import com.dby.mobile.ui.common.Segmented
import com.dby.mobile.ui.common.Sheet
import com.dby.mobile.ui.common.SheetHeader
import com.dby.mobile.ui.common.copyText
import com.dby.mobile.ui.glass.lightGlass
import com.dby.mobile.ui.theme.Dby
import com.dby.mobile.ui.theme.Type
import com.kyant.backdrop.Backdrop
import org.json.JSONArray
import org.json.JSONObject

/** Pretty-printed JSON, or null when the text is not a JSON object or array. */
private fun prettyJson(text: String): String? = runCatching {
    val t = text.trim()
    when {
        t.startsWith("{") -> JSONObject(t).toString(2)
        t.startsWith("[") -> JSONArray(t).toString(2)
        else -> null
    }
}.getOrNull()

/** Bytes as an offset / hex / text dump, 16 per line. */
private fun hexDump(bytes: ByteArray): String = bytes.toList().chunked(16).withIndex().joinToString("\n") { (line, chunk) ->
    val hex = chunk.joinToString(" ") { "%02x".format(it) }.padEnd(47)
    val text = chunk.map { b -> val c = b.toInt() and 0xff; if (c in 32..126) c.toChar() else '.' }.joinToString("")
    "%06x  %s  %s".format(line * 16, hex, text)
}

/** One cell in full (spec §6 rule 9): loads what the page trimmed, pretty-prints JSON, dumps bytes. */
@Composable
fun CellViewer(model: TableModel, row: Int, column: Int, backdrop: Backdrop, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val name = model.columns[column].name
    val shown = model.rows[row][column]
    var cell by remember { mutableStateOf(shown) }
    var problem by remember { mutableStateOf<Throwable?>(null) }
    var pretty by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        val session = model.session
        val keys = model.defs.withIndex().filter { it.value?.pkSeq != null }.sortedBy { it.value!!.pkSeq }.map { model.rows[row][it.index] }
        if (shown.isTrimmed() && session != null && keys.isNotEmpty()) {
            problem = runCatching { cell = session.fullCell(CellRequest(model.table, name, keys)) }.exceptionOrNull()
        }
    }
    val text = when (val c = cell) {
        is Cell.Bytes -> hexDump(c.preview)
        else -> c.rawText() ?: c.display()
    }
    val json = remember(text) { prettyJson(text) }
    Sheet(backdrop, onDismiss) {
        val size = when (val c = cell) {
            is Cell.Bytes -> "${count(c.len.toLong())} bytes"
            is Cell.Text -> "${count(c.fullLen.toLong())} characters"
            else -> model.columns[column].typeName
        }
        SheetHeader(name, subtitle = size, onClose = onDismiss) {
            RoundButton(DbyIcons.Copy, "Copy value", { copyText(context, name, json?.takeIf { pretty } ?: text) })
        }
        if (cell.isTrimmed()) {
            if (problem == null) Busy("Loading the full value…")
            Text(
                "Showing the first ${if (cell is Cell.Bytes) "bytes" else "characters"} only.",
                style = Type.Caption,
                color = Dby.Secondary,
            )
        }
        problem?.let { ProblemBanner(it, modifier = Modifier.padding(0.dp)) }
        if (json != null) Segmented(listOf(true, false), pretty, { pretty = it }, { if (it) "Formatted" else "Raw" }, Modifier.fillMaxWidth())
        SelectionContainer(
            Modifier.fillMaxWidth().heightIn(max = 520.dp).lightGlass(RoundedCornerShape(18.dp)).verticalScroll(rememberScrollState()),
        ) {
            Text(
                if (pretty && json != null) json else text,
                style = Type.MonoSmall,
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(16.dp),
            )
        }
    }
}
```

- [ ] **Step 3: Remove the Task 6 stubs and build**

Delete `class RowEditor(val model: TableModel, val rowIndex: Int?)` from `TableModel.kt` and the stub `RowSheet` and `CellViewer` composables from `TableScreen.kt`.
Run: `cd android && ./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Check edits on the emulator**

Seed a row first: `docker exec tests-mysql-1 mysql -uroot -pdbytest shop -e "INSERT IGNORE INTO edits VALUES (500, 'phone test', 3)"`.
On the emulator: Local MySQL → `edits` → tap the row. Expected: "Edit row · edits · 500", `id` locked, `name` and `qty` editable, `qty` has a NULL chip. Change qty to 4: the field label turns accent "Edited · was 3", the "SQL to run" box shows `UPDATE edits SET qty = 4 WHERE id = 500;` coloured, "1 row will change"; Commit closes the sheet and the card shows 4. More → Copy as JSON copies; Duplicate opens "New row" without id; set id 501, Add row; More → Delete row → confirm sheet with the DELETE → the row disappears. Open `orders`, Grid, long-press a `notes` cell of id 10: the viewer loads all 5,000 characters. Open `exact_values`, long-press `blobby`: hex dump of 1,000 bytes. Edit the PROD (read-only) connection's `edits`: Commit is disabled with the read-only note. Compare with the canvas's Edit row artboard.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/dby/mobile/ui/table
git commit -m "feat(android): Edit row sheet with live SQL preview, insert, duplicate, delete, and the cell viewer"
```

---

### Task 8: Query (builder, SQL editor, run, confirm, results, saved queries)

**Files:**
- Create: `ui/query/QueryModel.kt`, `ui/query/QueryScreen.kt`
- Modify: `AppRoot.kt` (Query branch)

**Interfaces:**
- Consumes: Tasks 1–7; core `classifySql`, `SqlKind`, `SelectSpec`, `Session.buildSelect/runSql/cancel`, `QueryResult`, `saveQuery`, `savedQueries`, `deleteSavedQuery`, `SavedQuery`.
- Produces: `QueryModel` with `connector`, `connection`, `session`, `mode`, `table`, `fields`, `filters`, `matchAll`, `sort`, `limit`, `sql`, `sqlEdited`, `running`, `result`, `ranSql`, `problem`, `confirming`, `showResult`, `pickTable(name)`, `rebuild()`, `editSql(value)`, `resetToBuilder()`, `run()`, `execute(sql)`, `cancel()`, `choose(saved)`, `load(sql)`, `save(title)`, `saved`, `refreshSaved()`, `deleteSaved(sql)`; `QueryScreen(app)`.

- [ ] **Step 1: Write `ui/query/QueryModel.kt`**

```kotlin
package com.dby.mobile.ui.query

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import com.dby.core.Filter
import com.dby.core.FilterOp
import com.dby.core.QueryResult
import com.dby.core.SavedConnection
import com.dby.core.SavedQuery
import com.dby.core.Schema
import com.dby.core.SelectSpec
import com.dby.core.Session
import com.dby.core.Sort
import com.dby.core.SqlKind
import com.dby.core.TableInfo
import com.dby.core.cachedSchema
import com.dby.core.classifySql
import com.dby.core.deleteSavedQuery
import com.dby.core.saveQuery
import com.dby.core.savedQueries
import com.dby.mobile.data.Prefs
import com.dby.mobile.data.Sessions
import com.dby.mobile.ui.common.Connector
import com.dby.mobile.ui.nav.ScreenModel
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

enum class Mode { BUILDER, SQL }

/** The Query tab (spec §8 "Run SQL"): a builder that writes SQL, the SQL itself, and its result. */
class QueryModel(private val sessions: Sessions, private val prefs: Prefs) : ScreenModel() {
    val connector = Connector(sessions, scope)
    var mode by mutableStateOf(Mode.BUILDER)
    var table by mutableStateOf<String?>(null)
        private set
    val fields = mutableStateListOf<String>()
    val filters = mutableStateListOf<Filter>()
    var matchAll by mutableStateOf(true)
    var sort by mutableStateOf<Sort?>(null)
    var limit by mutableIntStateOf(50)
    var sql by mutableStateOf(TextFieldValue(""))
        private set
    /** Once the SQL is edited by hand, the builder stops rewriting it. */
    var sqlEdited by mutableStateOf(false)
        private set
    var running by mutableStateOf(false)
        private set
    var result by mutableStateOf<QueryResult?>(null)
        private set
    var ranSql by mutableStateOf("")
        private set
    var showResult by mutableStateOf(false)
    var problem by mutableStateOf<Throwable?>(null)
    var confirming by mutableStateOf<String?>(null)
    var saved by mutableStateOf<List<SavedQuery>>(emptyList())
        private set
    private var runId: String? = null

    val connection: SavedConnection? get() = sessions.activeId?.let(sessions::saved)
    val session: Session? get() = sessions.activeId?.let { sessions.open[it] }
    val schema: Schema? get() = session?.let { s -> sessions.activeId?.let { cachedSchema(it, s.database()) } }
    val tableInfo: TableInfo? get() = schema?.tables?.firstOrNull { it.name == table }

    fun choose(target: SavedConnection) {
        connector.open(target) {
            sessions.activeId = target.id
            table = null
            fields.clear()
            filters.clear()
            sort = null
            if (!sqlEdited) sql = TextFieldValue("")
        }
    }

    fun pickTable(name: String) {
        table = name
        fields.clear()
        filters.clear()
        sort = null
        rebuild()
    }

    /** Writes the builder's SQL into the editor, unless the person has edited it. */
    fun rebuild() {
        if (sqlEdited) return
        val t = table ?: return
        val s = session ?: return
        val spec = SelectSpec(t, fields.toList(), filters.filter { it.op == FilterOp.IS_NULL || it.op == FilterOp.IS_NOT_NULL || it.value.isNotEmpty() }, matchAll, sort, limit.toUInt())
        runCatching { s.buildSelect(spec) }.onSuccess { sql = TextFieldValue(it) }
    }

    fun editSql(value: TextFieldValue) {
        if (value.text != sql.text) sqlEdited = true
        sql = value
    }

    fun resetToBuilder() {
        sqlEdited = false
        rebuild()
    }

    /** Runs the SQL; a write first shows its confirm sheet when "Confirm before saving" is on. */
    fun run() {
        val text = sql.text.trim()
        if (text.isEmpty()) return
        if (classifySql(text) == SqlKind.WRITE && prefs.confirmWrites) confirming = text else execute(text)
    }

    fun execute(text: String) {
        confirming = null
        val s = session ?: return
        scope.launch {
            running = true
            problem = null
            val id = UUID.randomUUID().toString()
            runId = id
            val started = SystemClock.elapsedRealtime()
            try {
                val r = s.runSql(id, text, true)
                Log.i("DBYBENCH", "app=dby event=sql ms=${SystemClock.elapsedRealtime() - started} rows=${r.rows.size}")
                result = r
                ranSql = text
                showResult = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = e
            } finally {
                running = false
                runId = null
            }
        }
    }

    fun cancel() {
        val s = session ?: return
        val id = runId ?: return
        scope.launch { runCatching { s.cancel(id) } }
    }

    /** Opens SQL from History or a saved query in the editor. */
    fun load(text: String) {
        mode = Mode.SQL
        sqlEdited = true
        sql = TextFieldValue(text)
        showResult = false
    }

    fun refreshSaved() {
        saved = runCatching { savedQueries() }.getOrDefault(emptyList())
    }

    fun save(title: String) {
        runCatching { saveQuery(title, sql.text.trim()) }.onFailure { problem = it }
        refreshSaved()
    }

    fun deleteSaved(text: String) {
        runCatching { deleteSavedQuery(text) }
        refreshSaved()
    }
}
```

- [ ] **Step 2: Write `ui/query/QueryScreen.kt`**

```kotlin
package com.dby.mobile.ui.query

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dby.core.Filter
import com.dby.core.FilterOp
import com.dby.core.Sort
import com.dby.mobile.DbyApp
import com.dby.mobile.bottomSpace
import com.dby.mobile.data.count
import com.dby.mobile.topSpace
import com.dby.mobile.ui.DbyIcons
import com.dby.mobile.ui.SqlTransformation
import com.dby.mobile.ui.common.Busy
import com.dby.mobile.ui.common.Chip
import com.dby.mobile.ui.common.ConfirmSheet
import com.dby.mobile.ui.common.EmptyState
import com.dby.mobile.ui.common.EnvBadge
import com.dby.mobile.ui.common.FieldRow
import com.dby.mobile.ui.common.Hairline
import com.dby.mobile.ui.common.Input
import com.dby.mobile.ui.common.LargeTitle
import com.dby.mobile.ui.common.ListRow
import com.dby.mobile.ui.common.PasswordSheet
import com.dby.mobile.ui.common.PrimaryButton
import com.dby.mobile.ui.common.ProblemBanner
import com.dby.mobile.ui.common.RoundButton
import com.dby.mobile.ui.common.SecondaryButton
import com.dby.mobile.ui.common.SectionHeader
import com.dby.mobile.ui.common.Segmented
import com.dby.mobile.ui.common.Sheet
import com.dby.mobile.ui.common.TopBar
import com.dby.mobile.ui.common.gridItems
import com.dby.mobile.ui.glass.GlassHost
import com.dby.mobile.ui.glass.lightGlass
import com.dby.mobile.ui.glass.liquidGlass
import com.dby.mobile.ui.nav.Screen
import com.dby.mobile.ui.table.symbol
import com.dby.mobile.ui.theme.Dby
import com.dby.mobile.ui.theme.LocalAccent
import com.dby.mobile.ui.theme.Type
import com.dby.mobile.ui.theme.colors
import com.kyant.backdrop.Backdrop

@Composable
fun QueryScreen(app: DbyApp) {
    val nav = app.nav
    val sessions = app.sessions
    val model = nav.model(Screen.Query) { QueryModel(sessions, app.prefs) }
    var picking by remember { mutableStateOf(false) }
    var pickingTable by remember { mutableStateOf(false) }
    var savedOpen by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(sessions.activeId, model.session) { model.rebuild() }
    BackHandler(enabled = model.showResult) { model.showResult = false }
    GlassHost(
        overlay = { backdrop ->
            if (!model.showResult) RunBar(model, backdrop, { saving = true }, Modifier.align(Alignment.BottomCenter))
            model.connector.asking?.let { c -> PasswordSheet(backdrop, c.name, model.connector::submit, model.connector::dismiss) }
            model.confirming?.let { text ->
                ConfirmSheet(
                    backdrop,
                    title = "Run this change?",
                    message = "It changes data on ${model.connection?.name ?: "the server"}.",
                    sql = text,
                    confirm = "Run it",
                    danger = model.connection?.env == com.dby.core.Env.PROD,
                    onConfirm = { model.execute(text) },
                    onDismiss = { model.confirming = null },
                )
            }
            if (picking) ConnectionPicker(app, model, backdrop) { picking = false }
            if (pickingTable) TablePicker(model, backdrop) { pickingTable = false }
            if (savedOpen) SavedSheet(model, backdrop) { savedOpen = false }
            if (saving) SaveSheet(model, backdrop) { saving = false }
        },
    ) {
        if (model.showResult) {
            ResultView(model)
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = topSpace(), bottom = bottomSpace(84.dp)),
            ) {
                TopBar {
                    ConnectionButton(model) { picking = true }
                    Box(Modifier.weight(1f))
                    RoundButton(DbyIcons.Bookmark, "Saved queries", { model.refreshSaved(); savedOpen = true })
                }
                LargeTitle("Query", Modifier.padding(top = 8.dp, bottom = 12.dp))
                Segmented(Mode.entries, model.mode, { model.mode = it; model.rebuild() }, { if (it == Mode.BUILDER) "Builder" else "SQL" }, Modifier.padding(horizontal = 16.dp).fillMaxWidth())
                (model.connector.problem ?: model.problem)?.let { ProblemBanner(it, modifier = Modifier.padding(vertical = 10.dp)) }
                if (model.connector.busy) Busy("Connecting…")
                when {
                    model.connection == null -> EmptyState("Pick a connection", "Queries run against the connection you choose here.", "Choose connection") { picking = true }
                    model.session == null -> EmptyState("Not connected", "Connect to ${model.connection?.name} to run queries.", "Connect") { model.connection?.let(model::choose) }
                    model.mode == Mode.BUILDER -> Builder(model) { pickingTable = true }
                    else -> SqlEditor(model)
                }
            }
        }
    }
}

@Composable
private fun ConnectionButton(model: QueryModel, onClick: () -> Unit) {
    val c = model.connection
    Row(
        Modifier.clip(CircleShape).background(Dby.FillStrong).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (c != null) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (model.session != null) Dby.Success else Dby.Faint))
            Text(c.name, style = Type.Secondary.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(150.dp))
            EnvBadge(c.env)
        } else {
            Text("Choose connection", style = Type.Secondary.copy(fontWeight = FontWeight.SemiBold))
        }
        Icon(DbyIcons.ChevronDown, contentDescription = null, tint = Dby.Secondary, modifier = Modifier.size(16.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Builder(model: QueryModel, onPickTable: () -> Unit) {
    val info = model.tableInfo
    Column(Modifier.padding(top = 16.dp)) {
        Row(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().lightGlass(RoundedCornerShape(22.dp)).clickable(onClick = onPickTable).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Table", style = Type.Body, modifier = Modifier.weight(1f))
            Text(model.table ?: "Choose", style = Type.Mono.copy(fontWeight = FontWeight.SemiBold), color = if (model.table == null) LocalAccent.current else Dby.Fg)
            Icon(DbyIcons.ChevronDown, contentDescription = null, tint = Dby.Secondary, modifier = Modifier.padding(start = 6.dp).size(16.dp))
        }
        if (info == null) return
        val names = info.columns.map { it.name }
        SectionHeader("Fields") { Text("${model.fields.size.takeIf { it > 0 } ?: names.size} of ${names.size} shown", style = Type.Secondary, color = Dby.Secondary, modifier = Modifier.padding(end = 8.dp)) }
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (name in names) {
                val on = model.fields.isEmpty() || name in model.fields
                Chip(name, on, {
                    if (model.fields.isEmpty()) model.fields.addAll(names)
                    if (name in model.fields) model.fields.remove(name) else model.fields.add(name)
                    if (model.fields.size == names.size) model.fields.clear()
                    model.rebuild()
                })
            }
        }
        SectionHeader("Filters") {
            Text(
                if (model.matchAll) "Match all" else "Match any",
                style = Type.Secondary,
                color = LocalAccent.current,
                modifier = Modifier.clip(CircleShape).clickable { model.matchAll = !model.matchAll; model.rebuild() }.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            model.filters.forEachIndexed { i, f -> FilterLine(model, i, f, names) }
            SecondaryButton("Add filter", {
                model.filters.add(Filter(names.first(), FilterOp.EQ, ""))
            }, Modifier.fillMaxWidth(), icon = DbyIcons.Plus)
        }
        SectionHeader("Sort and limit")
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Picker(
                label = "Sort by",
                value = model.sort?.let { "${it.column} · ${if (it.descending) "newest" else "oldest"}" } ?: "Key order",
                options = listOf("Key order") + names.flatMap { listOf("$it ↓", "$it ↑") },
                modifier = Modifier.weight(1.4f),
            ) { choice ->
                model.sort = if (choice == "Key order") null else Sort(choice.dropLast(2), choice.endsWith("↓"))
                model.rebuild()
            }
            Picker("Limit", "${model.limit} rows", listOf("50", "100", "500", "1000"), Modifier.weight(1f)) {
                model.limit = it.toInt()
                model.rebuild()
            }
        }
    }
}

@Composable
private fun FilterLine(model: QueryModel, index: Int, filter: Filter, names: List<String>) {
    Row(
        Modifier.fillMaxWidth().lightGlass(RoundedCornerShape(18.dp)).padding(start = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Dropdown(filter.column, names, Modifier.width(120.dp)) { model.filters[index] = filter.copy(column = it); model.rebuild() }
        Dropdown(filter.op.symbol(), FilterOp.entries.map { it.symbol() }, Modifier.width(76.dp)) { s ->
            model.filters[index] = filter.copy(op = FilterOp.entries.first { it.symbol() == s })
            model.rebuild()
        }
        if (filter.op != FilterOp.IS_NULL && filter.op != FilterOp.IS_NOT_NULL) {
            Input(filter.value, { model.filters[index] = filter.copy(value = it); model.rebuild() }, Modifier.weight(1f), placeholder = "value", mono = true)
        } else {
            Box(Modifier.weight(1f))
        }
        RoundButton(DbyIcons.Close, "Remove ${filter.column} filter", { model.filters.removeAt(index); model.rebuild() })
    }
}

@Composable
private fun Dropdown(value: String, options: List<String>, modifier: Modifier, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.clip(RoundedCornerShape(12.dp)).clickable { open = true }.padding(horizontal = 10.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(value, style = Type.Mono.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Icon(DbyIcons.ChevronDown, contentDescription = null, tint = Dby.Secondary, modifier = Modifier.size(14.dp))
        }
        DropdownMenu(open, { open = false }, containerColor = Dby.Solid, shape = RoundedCornerShape(16.dp)) {
            for (option in options) DropdownMenuItem(text = { Text(option, style = Type.Mono) }, onClick = { open = false; onPick(option) })
        }
    }
}

@Composable
private fun Picker(label: String, value: String, options: List<String>, modifier: Modifier, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Column(
            Modifier.fillMaxWidth().lightGlass(RoundedCornerShape(18.dp)).clickable { open = true }.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(label, style = Type.Caption, color = Dby.Secondary)
            Text(value, style = Type.Mono.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(open, { open = false }, containerColor = Dby.Solid, shape = RoundedCornerShape(16.dp)) {
            for (option in options) DropdownMenuItem(text = { Text(option, style = Type.Mono) }, onClick = { open = false; onPick(option) })
        }
    }
}

/** The SQL editor: coloured as it is typed, with line numbers. */
@Composable
private fun SqlEditor(model: QueryModel) {
    val lines = model.sql.text.count { it == '\n' } + 1
    Column(Modifier.padding(top = 16.dp)) {
        Row(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().heightIn(min = 260.dp).lightGlass(RoundedCornerShape(22.dp)).padding(vertical = 14.dp),
        ) {
            Text(
                (1..lines).joinToString("\n"),
                style = Type.MonoCode,
                color = Dby.Faint,
                modifier = Modifier.padding(start = 14.dp, end = 10.dp),
            )
            BasicTextField(
                value = model.sql,
                onValueChange = model::editSql,
                textStyle = Type.MonoCode.copy(color = Dby.Fg),
                cursorBrush = SolidColor(LocalAccent.current),
                visualTransformation = SqlTransformation,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(end = 14.dp),
                decorationBox = { inner ->
                    Box {
                        if (model.sql.text.isEmpty()) Text("SELECT * FROM …", style = Type.MonoCode, color = Dby.Faint)
                        inner()
                    }
                },
            )
        }
        Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(DbyIcons.Info, contentDescription = null, tint = Dby.Secondary, modifier = Modifier.size(16.dp))
            Text(
                if (model.sqlEdited) "Edited by hand." else "Written from your fields and filters. Edit it here for anything the builder can't do.",
                style = Type.Caption,
                color = Dby.Secondary,
                modifier = Modifier.weight(1f),
            )
            if (model.sqlEdited && model.table != null) {
                Text("Reset to builder", style = Type.Caption.copy(fontWeight = FontWeight.SemiBold), color = LocalAccent.current, modifier = Modifier.clickable { model.resetToBuilder() })
            }
        }
    }
}

/** The floating bar above the tab pill: save on the left, run (or cancel) on the right. */
@Composable
private fun RunBar(model: QueryModel, backdrop: Backdrop, onSave: () -> Unit, modifier: Modifier) {
    if (model.session == null) return
    Row(
        modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 100.dp).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(52.dp).liquidGlass(backdrop).clickable(role = Role.Button, onClick = onSave),
            contentAlignment = Alignment.Center,
        ) { Icon(DbyIcons.Bookmark, contentDescription = "Save query", tint = Dby.Fg, modifier = Modifier.size(20.dp)) }
        if (model.running) {
            SecondaryButton("Cancel", model::cancel, Modifier.weight(1f), icon = DbyIcons.Close, tint = Dby.Danger)
        } else {
            PrimaryButton(if (model.mode == Mode.BUILDER) "Show rows" else "Run", model::run, Modifier.weight(1f), enabled = model.sql.text.isNotBlank(), icon = DbyIcons.Play)
        }
    }
}

@Composable
private fun ResultView(model: QueryModel) {
    val result = model.result ?: return
    val hScroll = rememberScrollState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = topSpace(), bottom = bottomSpace())) {
        item { TopBar(onBack = { model.showResult = false }) }
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Result", style = Type.LargeTitle)
                val summary = if (result.columns.isEmpty()) {
                    "${count(result.affectedRows.toLong())} rows changed"
                } else {
                    "${count(result.rows.size.toLong())} rows" + if (result.truncated) " (stopped at 1,000)" else ""
                }
                Text("$summary · ${result.elapsedMs} ms", style = Type.Secondary, color = Dby.Secondary)
                Text(model.ranSql, style = Type.MonoSmall, color = Dby.Tertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (result.columns.isNotEmpty()) gridItems(result.columns, result.rows, hScroll)
    }
}

@Composable
private fun ConnectionPicker(app: DbyApp, model: QueryModel, backdrop: Backdrop, onDismiss: () -> Unit) {
    Sheet(backdrop, onDismiss) {
        Text("Run against", style = Type.Title)
        Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).lightGlass(RoundedCornerShape(22.dp)).verticalScroll(rememberScrollState())) {
            app.sessions.connections.forEachIndexed { i, c ->
                if (i > 0) Hairline()
                val (fg, _) = c.env.colors()
                ListRow(
                    c.name,
                    subtitle = if (app.sessions.open.containsKey(c.id)) "Connected" else "${c.host} · ${c.database}",
                    onClick = { onDismiss(); model.choose(c) },
                    titleExtra = { EnvBadge(c.env) },
                    trailing = { if (c.id == app.sessions.activeId) Icon(DbyIcons.Check, null, tint = fg) },
                )
            }
        }
    }
}

@Composable
private fun TablePicker(model: QueryModel, backdrop: Backdrop, onDismiss: () -> Unit) {
    Sheet(backdrop, onDismiss) {
        Text("Table", style = Type.Title)
        val tables = model.schema?.tables.orEmpty()
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).lightGlass(RoundedCornerShape(22.dp)).verticalScroll(rememberScrollState())) {
            tables.forEachIndexed { i, t ->
                if (i > 0) Hairline()
                ListRow(t.name, subtitle = if (t.isView) "view" else "${t.columns.size} columns", titleStyle = Type.Mono, onClick = { model.pickTable(t.name); onDismiss() })
            }
        }
    }
}

@Composable
private fun SavedSheet(model: QueryModel, backdrop: Backdrop, onDismiss: () -> Unit) {
    Sheet(backdrop, onDismiss) {
        Text("Saved queries", style = Type.Title)
        if (model.saved.isEmpty()) {
            Text("Nothing saved yet. Use the bookmark button next to Run.", style = Type.Secondary, color = Dby.Secondary)
        } else {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).lightGlass(RoundedCornerShape(22.dp)).verticalScroll(rememberScrollState())) {
                model.saved.forEachIndexed { i, q ->
                    if (i > 0) Hairline()
                    ListRow(
                        q.title,
                        subtitle = q.sql.replace('\n', ' '),
                        onClick = { model.load(q.sql); onDismiss() },
                        onLongClick = { model.deleteSaved(q.sql) },
                    )
                }
            }
            Text("Long-press a query to delete it.", style = Type.Caption, color = Dby.Secondary)
        }
    }
}

@Composable
private fun SaveSheet(model: QueryModel, backdrop: Backdrop, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf(model.table?.let { "$it query" } ?: "") }
    Sheet(backdrop, onDismiss) {
        Text("Save query", style = Type.Title)
        Column(Modifier.fillMaxWidth().lightGlass(RoundedCornerShape(22.dp))) {
            FieldRow("Title", title, { title = it }, "Late shipments", mono = false)
        }
        PrimaryButton("Save", { model.save(title.trim()); onDismiss() }, Modifier.fillMaxWidth(), enabled = title.isNotBlank() && model.sql.text.isNotBlank())
    }
}
```

- [ ] **Step 3: Wire into `AppRoot.kt` and check on the emulator**

Replace the Query placeholder with `Screen.Query -> QueryScreen(app)` and import `com.dby.mobile.ui.query.QueryScreen`. Make `symbol()` in `TableModel.kt` public (it is) so the Query screen can use it.
Run: `cd android && ./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk && adb logcat -c`
On the emulator: open Local MySQL's Explorer, tap the Query button. Expected: Query tab with the connection chip (green dot, LOCAL). Builder: Table → orders; untoggle `notes`; add filter `total > 500`; sort `created_at ↓`; switch to SQL: `SELECT id, customer, status, total, created_at\nFROM orders\nWHERE total > 500\nORDER BY created_at DESC\nLIMIT 50;` coloured with line numbers. Show rows → Result "50 rows · N ms" with the grid. Back → editor. Type `SELECT * FROM orders` → Run → "1,000 rows (stopped at 1,000)". Type `UPDATE edits SET qty = 9 WHERE id = 500` → Run → confirm sheet with the SQL → Run it → "1 rows changed". On the PROD read-only connection the same UPDATE shows "This connection is read-only; unlock it to change data." Save the query; Saved queries lists it. `adb logcat -d -s DBYBENCH:I` shows `event=sql`. Compare with the canvas's Query artboard (Builder and SQL).

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/dby/mobile
git commit -m "feat(android): Query tab with builder, coloured SQL editor, confirmed writes, results and saved queries"
```

---

### Task 9: History, Settings, Licences, App lock

**Files:**
- Create: `ui/history/HistoryScreen.kt`, `ui/settings/SettingsScreen.kt`, `ui/settings/LicencesScreen.kt`
- Modify: `AppRoot.kt` (three branches)

**Interfaces:**
- Consumes: Tasks 1–8; core `history`, `deleteHistory`, `HistoryEntry`, `coreVersion`; `Sessions.rekey`; `AppLock`.
- Produces: `HistoryScreen(app)`, `SettingsScreen(app)`, `LicencesScreen(app)`; Settings has an About section with a `Check for updates` row that M3 wires to the updater (this task shows the version only).

- [ ] **Step 1: Write `ui/history/HistoryScreen.kt`**

```kotlin
package com.dby.mobile.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.dby.core.HistoryEntry
import com.dby.core.SavedQuery
import com.dby.core.deleteHistory
import com.dby.core.deleteSavedQuery
import com.dby.core.history
import com.dby.core.savedQueries
import com.dby.mobile.DbyApp
import com.dby.mobile.bottomSpace
import com.dby.mobile.data.ago
import com.dby.mobile.data.count
import com.dby.mobile.topSpace
import com.dby.mobile.ui.common.EmptyState
import com.dby.mobile.ui.common.GroupCard
import com.dby.mobile.ui.common.Hairline
import com.dby.mobile.ui.common.LargeTitle
import com.dby.mobile.ui.common.ListRow
import com.dby.mobile.ui.common.Segmented
import com.dby.mobile.ui.glass.GlassHost
import com.dby.mobile.ui.nav.Screen
import com.dby.mobile.ui.nav.ScreenModel
import com.dby.mobile.ui.nav.Tab
import com.dby.mobile.ui.query.QueryModel
import com.dby.mobile.ui.theme.Dby
import com.dby.mobile.ui.theme.Type

class HistoryModel : ScreenModel() {
    var saved by mutableStateOf(false)
    var entries by mutableStateOf<List<HistoryEntry>>(emptyList())
        private set
    var queries by mutableStateOf<List<SavedQuery>>(emptyList())
        private set

    fun load() {
        entries = runCatching { history(200u) }.getOrDefault(emptyList())
        queries = runCatching { savedQueries() }.getOrDefault(emptyList())
    }

    fun delete(entry: HistoryEntry) {
        runCatching { deleteHistory(entry.id) }
        load()
    }

    fun delete(query: SavedQuery) {
        runCatching { deleteSavedQuery(query.sql) }
        load()
    }
}

@Composable
fun HistoryScreen(app: DbyApp) {
    val nav = app.nav
    val model = nav.model(Screen.History) { HistoryModel() }
    LaunchedEffect(Unit) { model.load() }
    val openInQuery = { sql: String, connectionId: String? ->
        val query = nav.rootModel(Tab.QUERY) { QueryModel(app.sessions, app.prefs) }
        if (connectionId != null && app.sessions.saved(connectionId) != null) app.sessions.activeId = connectionId
        query.load(sql)
        nav.select(Tab.QUERY)
    }
    GlassHost {
        val now = System.currentTimeMillis()
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = topSpace(), bottom = bottomSpace())) {
            item { LargeTitle("History", Modifier.padding(top = 52.dp, bottom = 12.dp)) }
            item { Segmented(listOf(false, true), model.saved, { model.saved = it }, { if (it) "Saved" else "Recent" }, Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()) }
            if (!model.saved) {
                if (model.entries.isEmpty()) item { EmptyState("Nothing run yet", "Queries and row edits appear here with their time and result.") }
                else item {
                    GroupCard(Modifier.padding(top = 12.dp)) {
                        model.entries.forEachIndexed { i, e ->
                            if (i > 0) Hairline(36.dp)
                            val what = e.error ?: "${count(e.rows.toLong())} rows"
                            ListRow(
                                e.sql.replace('\n', ' '),
                                subtitle = listOfNotNull(e.connectionName ?: "deleted connection", e.database, "${e.elapsedMs} ms", what, ago(e.atMs, now)).joinToString(" · "),
                                titleStyle = Type.Mono,
                                subtitleColor = if (e.error != null) Dby.Danger else Dby.Secondary,
                                onClick = { openInQuery(e.sql, e.connectionId) },
                                onLongClick = { model.delete(e) },
                                leading = { Box(Modifier.size(8.dp).clip(CircleShape).background(if (e.error == null) Dby.Success else Dby.Danger)) },
                            )
                        }
                    }
                }
            } else {
                if (model.queries.isEmpty()) item { EmptyState("No saved queries", "Save one from the Query tab with the bookmark button.") }
                else item {
                    GroupCard(Modifier.padding(top = 12.dp)) {
                        model.queries.forEachIndexed { i, q ->
                            if (i > 0) Hairline()
                            ListRow(q.title, subtitle = q.sql.replace('\n', ' '), onClick = { openInQuery(q.sql, null) }, onLongClick = { model.delete(q) })
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Write `ui/settings/SettingsScreen.kt`**

```kotlin
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

    GlassHost {
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
                Hairline()
                ListRow("Licences", onClick = { nav.push(Screen.Licences) })
            }
        }
    }
}
```

- [ ] **Step 3: Write `ui/settings/LicencesScreen.kt`**

```kotlin
package com.dby.mobile.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dby.mobile.DbyApp
import com.dby.mobile.topSpace
import com.dby.mobile.ui.common.GroupCard
import com.dby.mobile.ui.common.Hairline
import com.dby.mobile.ui.common.LargeTitle
import com.dby.mobile.ui.common.ListRow
import com.dby.mobile.ui.common.SectionHeader
import com.dby.mobile.ui.common.TopBar
import com.dby.mobile.ui.glass.GlassHost
import com.dby.mobile.ui.theme.Dby
import com.dby.mobile.ui.theme.Type

/** Third-party components and their licences (spec §15). */
private val COMPONENTS = listOf(
    "Jetpack Compose, AndroidX" to "Apache-2.0",
    "Kotlin coroutines" to "Apache-2.0",
    "Kyant0 backdrop and shapes" to "Apache-2.0",
    "JNA" to "Apache-2.0",
    "UniFFI" to "MPL-2.0",
    "mysql_async, mysql_common" to "MIT / Apache-2.0",
    "rustls, ring, webpki-roots" to "Apache-2.0 / ISC / MPL-2.0",
    "tokio, futures" to "MIT",
    "rusqlite, SQLite" to "MIT / public domain",
    "sqlparser" to "Apache-2.0",
    "serde, serde_json, thiserror" to "MIT / Apache-2.0",
    "Amazon RDS CA bundle" to "Amazon",
    "t8y2/dbx (type mapping ideas)" to "Apache-2.0",
    "Geist, Geist Mono" to "SIL Open Font License 1.1",
)

@Composable
fun LicencesScreen(app: DbyApp) {
    val context = LocalContext.current
    val ofl = remember { context.assets.open("licences/OFL-Geist.txt").bufferedReader().use { it.readText() } }
    GlassHost {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = topSpace()).navigationBarsPadding().padding(bottom = 24.dp)) {
            TopBar(onBack = { app.nav.back() })
            LargeTitle("Licences", Modifier.padding(top = 8.dp))
            SectionHeader("Components")
            GroupCard {
                COMPONENTS.forEachIndexed { i, (name, licence) ->
                    if (i > 0) Hairline()
                    ListRow(name, subtitle = licence, trailing = {})
                }
            }
            SectionHeader("Geist fonts")
            Text(ofl, style = Type.MonoSmall, color = Dby.Secondary, modifier = Modifier.padding(horizontal = 20.dp))
        }
    }
}
```

- [ ] **Step 4: Wire into `AppRoot.kt`, build, check on the emulator**

Replace the History, Settings and Licences placeholders with `HistoryScreen(app)`, `SettingsScreen(app)` and `LicencesScreen(app)` (imports from `ui.history` and `ui.settings`), and delete the now-unused `Placeholder` composable.
Run: `cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest && adb install -r app/build/outputs/apk/debug/app-debug.apk`
Expected on the emulator: History lists the queries and row edits from Tasks 7–8, newest first, green or red dots; tapping one opens it in Query's SQL mode on its connection. Settings: toggles flip and persist across a restart (`adb shell am force-stop com.dby.mobile` then relaunch); Text size Larger enlarges every screen; Accent green recolours the tab pill and buttons; Reduce blur turns the tab pill solid. App lock on the emulator (set a PIN under the emulator's Settings → Security first): turning it on asks for the PIN; relaunch shows "DBY is locked" and the PIN prompt; after unlocking, opening a connection still connects (its password was re-encrypted). Licences lists components and the OFL text. Compare Settings with the canvas's Settings artboard.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/dby/mobile
git commit -m "feat(android): History, Settings with App lock and accent, Licences"
```

---

### Task 10: Release build, smoothness check, canvas comparison

**Files:**
- Modify: `android/app/proguard-rules.pro` only if R8 strips something the bindings need.

- [ ] **Step 1: Release build**

Run: `cd android && ./gradlew :app:assembleRelease`
Expected: `BUILD SUCCESSFUL`; `app/build/outputs/apk/release/app-release.apk` exists; size under 25 MB.

- [ ] **Step 2: Smoke-test the release build**

Install the release APK (debug-signed for now: `adb uninstall com.dby.mobile` first if signatures differ), repeat Task 6 Step 6's checks briefly: connect, open `orders`, page, filter, edit a row, run SQL. A crash on launch or a `NoSuchMethodError`/`UnsatisfiedLinkError` in `adb logcat -d -b crash` means R8 removed JNA or binding classes: keep them with `-keep class com.dby.core.** { *; }` and `-keep class com.sun.jna.** { *; }` in `proguard-rules.pro` (check whether M0's rules already do).

- [ ] **Step 3: Measure scroll smoothness**

```bash
adb shell dumpsys gfxinfo com.dby.mobile reset
# On the emulator: Table data of orders, Grid, flick up and down for 10 seconds
adb shell dumpsys gfxinfo com.dby.mobile | grep -E "Janky frames|Total frames"
```
Expected: janky frames under 5% (spec §1 goal 2). The emulator is not the phone; record the number and repeat on the phone in the M0 benchmark.

- [ ] **Step 4: Compare with the canvas**

Screenshot Connections, Explorer, Table data (cards and grid), Edit row, Query (builder and SQL) and Settings on the emulator and compare each with its canvas artboard: colours, type sizes, radii, spacing, the glass materials. Fix visible differences that are one-line changes (padding, colour, size); list larger ones in the ledger as deferred.

- [ ] **Step 5: Commit and push**

```bash
git add -A android
git commit -m "build(android): release build checks and canvas fixes"
git push
```
