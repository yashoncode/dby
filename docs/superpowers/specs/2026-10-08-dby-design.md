# DBY v1 — Design Spec

Date: 2026-10-08
Status: draft, awaiting review

DBY is a fast, lightweight MySQL / MariaDB client for Android phones. It replaces TevelMobile
(`C:\Users\Yashwanth\TevelMobile`, Capacitor + Vue + JDBC), which works but is slow against AWS RDS.

UI source of truth: the "DBY Mobile — frosted glass" canvas,
https://claude.ai/artifact/DZqvPn6z9CUFZATYMi8YcU (6 artboards, 412×915 dp).
Glass treatment: the way BitChord renders it (https://github.com/kushagrasinghx/BitChord), re-implemented
from its Apache-2.0 libraries, never from BitChord's GPL-3.0 code.

---

## 1. Goals and success criteria

1. **Faster than TevelMobile on the same phone and the same RDS instance**, measured, on all of:
   - connect until the table list is on screen,
   - first page and next page of a large table,
   - `SELECT *` on a large table with no `LIMIT`,
   - a 1000-row result: time to visible and scroll smoothness.
   The numbers go in `docs/benchmarks.md`. Missing any of them blocks the screen work (see §13, M0).
2. Scrolling the grid and lists stays under 5% janky frames (`adb shell dumpsys gfxinfo`) on a
   mid-range phone, glass on.
3. Exact values: no BIGINT or DECIMAL is ever rounded on its way to the screen.
4. The app updates itself from GitHub Releases without the Play Store (§10).

## 2. Scope

**In v1**
- MySQL 5.7 / 8.x and MariaDB 10.x / 11.x, over TCP with TLS.
- Screens from the canvas: Connections, Schema explorer, Table data (cards and grid), Edit row
  sheet, Query (Builder and SQL), Settings. Plus the screens the canvas does not draw yet:
  History, New/Edit connection, Cell viewer, Update sheet (§12).
- Saved connections with PROD / STAGING / DEV / LOCAL tags, pinning, search.
- Read-only guard for PROD, SQL preview before every write, fingerprint app lock.
- In-app updater from GitHub Releases.

**Not in v1** (each one slots into the same core later)
- PostgreSQL, SQLite files, Redis, MongoDB, SQL Server, DuckDB.
- SSH tunnels. v1 assumes the RDS endpoint is reachable directly with TLS. If it turns out to be
  private behind a bastion, `russh` is added to the core in M1 and a tunnel section is added to the
  connection form; nothing else changes.
- AI assistant, import from DBeaver/Navicat, encrypted connection backup, iOS, desktop.

## 3. Architecture

```
┌──────────────────────────── Android app (Kotlin) ────────────────────────────┐
│  Compose UI ── ViewModels (StateFlow) ── Glass system ── Updater ── Keystore │
│                               │ suspend calls                                │
│                     UniFFI-generated Kotlin bindings                          │
└───────────────────────────────┼──────────────────────────────────────────────┘
┌───────────────────────────────┼──────── libdby_core.so (Rust) ───────────────┐
│  api.rs ── session.rs (2 connections per server, reconnect, cancel)          │
│            query.rs (classify, paging SQL, row cap)   schema.rs (load + cache)│
│            value.rs (exact cell encoding)             store.rs (rusqlite)     │
│            mysql_async + rustls(ring) + tokio                                │
└──────────────────────────────────────────────────────────────────────────────┘
```

**Boundary rule:** Rust owns everything about data: the wire, SQL building, caching and local
storage. Kotlin owns everything about the screen: layout, glass, navigation, secrets (Keystore),
UI preferences and the updater. They talk only through the UniFFI API in §5.

### Repository layout

```
DBY/
  core/                    Rust crate `dby-core` (cdylib + uniffi)
    src/{lib.rs, api.rs, session.rs, query.rs, schema.rs, value.rs, store.rs}
    tests/                 integration tests against Docker MySQL 8.4 and MariaDB 11.4
  android/                 Gradle project, single `app` module
    app/src/main/java/com/dby/mobile/{ui/, glass/, data/, update/}
  docs/                    specs, plans, benchmarks
  .github/workflows/release.yml
```

## 4. Tech stack

| Layer | Choice |
|---|---|
| Language / UI | Kotlin 2, Jetpack Compose, Material 3 as a base under a custom theme, Navigation Compose |
| Android levels | minSdk 26, compileSdk 37 (Haze 2 and the Compose BOM require it), targetSdk 36 |
| Glass | Haze 2.0.1 (`dev.chrisbanes.haze`); Kyant0/AndroidLiquidGlass `io.github.kyant0:backdrop` (Apache-2.0) |
| Fonts | Geist, Geist Mono (OFL-1.1), bundled in `res/font` |
| SQL editor | sora-editor (LGPL-2.1, used unmodified as a library dependency) with a TextMate MySQL grammar |
| Bridge | UniFFI, async functions exported with `async_runtime = "tokio"` so they arrive as Kotlin `suspend` functions |
| Driver | `mysql_async` 0.37, `default-features = false`, features `default-rustls`, `client_ed25519` (MariaDB), `rust_decimal`. Type mapping and metadata queries adapted from `t8y2/dbx` `crates/dbx-driver-mysql` (Apache-2.0, notice kept in `core/NOTICE`) |
| TLS | rustls with the `ring` provider, `webpki-roots`, plus the Amazon RDS global CA bundle compiled into the library |
| SQL parsing | `sqlparser` (MySQL dialect), only to classify statements as read or write |
| Local storage | `rusqlite` with the `bundled` feature, one file in the app's private files directory |
| Secrets | Android Keystore AES-256-GCM key; BiometricPrompt when App lock is on |
| UI preferences | `SharedPreferences` (text size, layout, reduce blur, update check toggle) |
| Build | `cargo-ndk`, ABIs `arm64-v8a` (release) and `x86_64` (emulator), 16 KB page-aligned `.so`, R8 on. Rust release profile: `lto = true`, `codegen-units = 1`, `opt-level = "s"`, `strip = true`, `panic = "abort"` |

## 5. Core API (UniFFI surface)

All network calls are `async` in Rust and `suspend` in Kotlin. Errors come back as one
`DbyError` enum (§9).

```rust
// lifecycle
fn init(data_dir: String) -> Result<(), DbyError>;

// saved connections (local, instant)
fn list_connections() -> Vec<SavedConnection>;
fn save_connection(input: ConnectionInput) -> Result<SavedConnection, DbyError>; // password arrives already encrypted
fn delete_connection(id: String) -> Result<(), DbyError>;

// sessions: Kotlin holds the returned object and calls close() when done
async fn connect(id: String, password: String) -> Result<Arc<Session>, DbyError>;
fn cached_schema(id: String, database: String) -> Option<Schema>;    // local, instant

#[derive(uniffi::Object)]
struct Session { /* browse + query connections, see §6 */ }

impl Session {
    fn server_info(&self) -> ServerInfo;
    async fn ping(&self) -> Result<u32, DbyError>;                    // ms, used on app resume
    async fn refresh_schema(&self) -> Result<Schema, DbyError>;
    async fn table_page(&self, req: PageRequest) -> Result<Page, DbyError>;
    async fn count_rows(&self, req: CountRequest) -> Result<Option<u64>, DbyError>; // None = timed out
    async fn run_sql(&self, run_id: String, sql: String, allow_write: bool) -> Result<QueryResult, DbyError>;
    async fn cancel(&self, run_id: String) -> Result<(), DbyError>;
    fn preview_row_edit(&self, edit: RowEdit) -> Result<String, DbyError>; // the exact SQL to show
    async fn apply_row_edit(&self, edit: RowEdit) -> Result<u64, DbyError>;
    async fn disconnect(&self);   // not `close`: UniFFI's Kotlin objects already define close()
}

// M0 only, before the store exists: open a Session straight from parameters.
// M1's connect(id, password) builds the same ConnectParams from the store and calls this.
async fn open_session(params: ConnectParams) -> Result<Arc<Session>, DbyError>;

// history and saved queries (local)
fn history(limit: u32) -> Vec<HistoryEntry>;
fn saved_queries() -> Vec<SavedQuery>;
fn save_query(title: String, sql: String) -> Result<(), DbyError>;
fn delete_saved_query(sql: String) -> Result<(), DbyError>;
```

### Cells

```rust
enum Cell {
    Null,
    Signed { v: i64 },                              // names avoid clashing with Kotlin's Int/UInt/Float
    Unsigned { v: u64 },
    Real { v: f64 },                                // FLOAT / DOUBLE only
    Exact { v: String },                            // DECIMAL, and any integer that does not fit the above
    Text { v: String, full_len: u64 },              // full_len is in characters; more than v's character count means the server trimmed it
    Temporal { v: String },                         // DATE / TIME / DATETIME / TIMESTAMP as ISO-8601 text
    Bytes { len: u64, preview: Vec<u8> },           // first 256 bytes only
}
```

A `Page` is `{ columns: Vec<Column>, rows: Vec<Vec<Cell>>, next: Option<Cursor>, elapsed_ms: u32 }`.
UniFFI records are fast enough for 50-row pages and 1000-row results. A flat binary buffer
is only worth building if profiling in M0 shows the bridge as a measurable cost.

## 6. Driver behaviour (the speed rules)

Every rule exists to remove a round trip or a byte that TevelMobile spends.

1. **No ping before queries.** A query runs on the existing connection. If it fails with a
   connection-level error (`Io`, broken pipe, `2006 server has gone away`, `2013 lost connection`),
   the session reconnects once and retries the statement **only if it is a read**. Writes are never
   retried automatically; the error is shown instead.
2. **Ping on resume.** When the app returns to the foreground, Kotlin calls `ping` in the
   background while the UI is already drawn. A failed ping triggers a reconnect.
3. **Two connections per open server.**
   - *browse*: metadata, table pages, counts, row edits.
   - *query*: user SQL from the Query tab.
   Cancel sends `KILL QUERY <query-connection-id>` over *browse*. A long query never blocks
   browsing.
4. **Session setup in one round trip** right after login, as a single `SET SESSION ...` statement
   per connection. On *query* it includes `sql_select_limit = 1001`, which makes the server stop
   after 1001 rows for any top-level `SELECT` without its own `LIMIT`; no SQL parsing is needed for
   the cap. *browse* keeps the server default, because schema reads on a large database legitimately
   return thousands of `information_schema.COLUMNS` rows.
5. **Explicit huge LIMITs.** If a user's own `LIMIT` returns more than 1000 rows, the core stops
   reading at 1000 and cancels the rest through the same `KILL QUERY` path as rule 3, instead of
   draining the result over mobile data. The result is marked `truncated`.
6. **Schema cache.** `cached_schema` returns the last known schema from rusqlite immediately.
   `refresh_schema` then reads the current database only, in one round trip, as a multi-statement
   query over `information_schema`: `TABLES` (name, type, `TABLE_ROWS` estimate, data + index
   length), `COLUMNS` (name, type, nullability, key, ordinal), `ROUTINES` (count). Row counts shown
   from it are labelled as estimates (`~`).
7. **Keyset paging.** Table pages are
   `SELECT <cols> FROM t WHERE (<sort>, <pk>) > (<v1>, <v2>) ORDER BY <sort>, <pk> LIMIT 51`
   sent as one text-protocol query, so every page is exactly one round trip (a prepared statement
   would cost an extra prepare round trip the first time). Cursor values are written by the core as
   injection-proof literals that do not depend on `sql_mode`: integers and decimals as validated
   digits, strings as `_utf8mb4 X'<hex>'` (compared under the column's own collation), binary
   values as `X'<hex>'`. The 51st row only tells whether a next page exists. Tables without a
   primary key fall back to `LIMIT/OFFSET` and say so in the pager. M0 pages by primary key only;
   the extra sort column arrives in M1.
8. **Prefetch.** When a page is shown, the next page is requested immediately on *browse* and held
   in memory. Going back uses the pages already held.
9. **Trimmed cells.** In page queries, `TEXT`, `JSON` and `VARCHAR` columns longer than 256 are
   selected as `LEFT(col, 256)` with `CHAR_LENGTH(col)`, and `BLOB` / `BINARY` as
   `LEFT(col, 256)` with `LENGTH(col)`. The Cell viewer fetches the full value of one cell by key
   when tapped.
10. **Counts never block.** Pager totals and facet counts come from `count_rows`, run on *browse*
    after the page is on screen. They use `MAX_EXECUTION_TIME(2000)` on MySQL and
    `SET STATEMENT max_statement_time=2 FOR` on MariaDB. On timeout the UI shows "50+ rows".
11. **Exact types.** Decoding follows §5's `Cell`; `rust_decimal` and string transport mean no
    float rounding anywhere.
12. **Socket options.** `TCP_NODELAY` on, TCP keepalive on. Protocol compression is off; M0 measures
    it on mobile data and turns it into a per-connection toggle only if it helps.

## 7. Safety

- **Read-only on PROD** (setting, on by default): connections tagged PROD open both sessions with
  `SET SESSION TRANSACTION READ ONLY`, and `run_sql` refuses statements that `sqlparser`
  classifies as writes or DDL. Statements it cannot parse are treated as writes. Both layers apply;
  the spec does not rely on either alone. The docs also recommend a read-only MySQL user for PROD.
  Unlocking a PROD connection for writes (an explicit switch on its Explorer screen, reset on
  disconnect) runs `SET SESSION TRANSACTION READ WRITE` on both connections.
- **Confirm before saving** (setting, on by default): every write shows the exact SQL first. This
  covers the Edit row sheet and write statements from the Query tab.
- **Row edits** run in a transaction. `UPDATE ... WHERE <pk> = ?` must report exactly one affected row,
  or the core rolls back and reports `RowEditMismatch`. Tables without a primary key cannot be edited
  from the grid.
- **TLS** is on by default and **certificate verification is on by default**. A connection can turn
  verification off; it is then labelled "Encrypted, not verified" everywhere it appears.
  Cleartext connections need an explicit "No TLS" choice and are labelled "Not encrypted".
- **Passwords** are encrypted with a Keystore AES-256-GCM key in Kotlin; Rust stores only the
  ciphertext. With App lock on, the key requires biometric authentication. If the user enrols new
  fingerprints, Android invalidates that key; the app then asks for each connection's password once
  more instead of failing. The plaintext password lives in Rust memory only while a session is open
  and is zeroed on disconnect.
- No `cleartextTrafficPermitted`: the app makes no HTTP connections except HTTPS to GitHub for updates.

## 8. Data flows

**Open a connection.** Connections screen → tap → Explorer is shown immediately with
`cached_schema` (if any) → in parallel: Keystore decrypt (biometric if locked) → `connect` →
`refresh_schema` → Explorer updates in place. First connect ever shows a skeleton list instead of
the cache.

**Browse a table.** Explorer → tap table → `table_page(first)` on *browse* → grid shows rows →
`table_page(next)` prefetched and `count_rows` started in the background → pager updates when they
arrive.

**Run SQL.** Query tab → builder or SQL editor → "Show rows" → `run_sql` on *query* with a fresh
`run_id` → a Cancel button replaces "Show rows" while it runs → the result appears in the same grid
component as table data, marked `truncated` when capped.

**Edit a row.** Grid → tap row → Edit row sheet → change fields → the "SQL to run" box shows
`preview_row_edit` live → "Commit change" → `apply_row_edit` → sheet closes, the row updates in
place.

**App resume.** Lifecycle `ON_START` → `ping` per open session in the background → a failure
reconnects silently; a reconnect failure shows the reconnect banner.

## 9. Errors

`DbyError` variants: `Network` (could not reach host), `Tls` (handshake or verification),
`Auth` (wrong user/password, plugin not supported), `UnknownDatabase`, `Server { code, message }`
(any other MySQL error, code kept), `ReadOnlyBlocked`, `RowEditMismatch { affected }`,
`Cancelled`, `Timeout`, `Storage` (local rusqlite), `Internal`. Text fields on variants are named
`detail`, never `message`, because UniFFI turns `DbyError` into a Kotlin exception and `message`
would clash with `Throwable.message`.

Kotlin maps each to one short sentence plus the raw server message behind a "Details" tap, the way
TevelMobile's `readableError` does, but by variant instead of regex.

## 10. In-app updater

Same flow as MeTube's phone updater, which the user has shipped before, with two parts replaced
by simpler platform features.

**Kept from MeTube**
- The manifest is a small JSON file attached to every GitHub release:
  `https://github.com/yashoncode/dby/releases/latest/download/dby.json`.
- Format: `{"package": {"downloadUrl", "downloadSize", "sha256"}, "<versionName>": {"versionCode", "changelog": [...]}, ...}`.
  The newest `versionCode` wins; release notes for every version newer than the installed one are shown.
- Automatic check at launch and when the app returns to the foreground, only if the last answer is
  older than 1 hour; a failed automatic check is silent and not retried for 5 minutes.
- "Check for updates" in Settings checks immediately and shows failures.
- **Nothing is downloaded until the user taps Update.** Download shows progress and can be cancelled.
- Before installing, the APK is checked with `PackageManager.getPackageArchiveInfo`: same package
  name and the advertised `versionCode`. A truncated or wrong file fails this.
- First launch after an update shows "Updated to X" and a What's new sheet once.
- Debug builds can point at a test manifest through a system property.
- A build flag (`IN_APP_UPDATES`) turns the updater off for any future store build, because Play
  does not allow `REQUEST_INSTALL_PACKAGES` for apps like this one.

**Simpler than MeTube**
- **Download:** Android's `DownloadManager` service into the app's external files directory, instead
  of an app-run download plus a foreground service. The system keeps the download alive in the
  background, so the Android 15 data-sync time budget does not apply.
- **Install:** a `PackageInstaller` session with a user-confirmation intent, instead of
  `ACTION_VIEW` on the APK. This avoids the "Open with" chooser MeTube had to work around and needs
  no `FileProvider`.
- **Integrity:** the manifest's `sha256` is checked before the archive check. Android itself refuses
  an update signed with a different key, which is the real protection.

**Release workflow** (`.github/workflows/release.yml`, on a `v*` tag): build the Rust core for
arm64, build and sign the release APK from repository secrets, compute its SHA-256, write `dby.json`
with the changelog taken from the tag message, create the GitHub release and attach both files.

Updater UI: an Update row in Settings with a dot when an update is unseen, and an Update sheet in
the same glass style as the Edit row sheet: version, size, notes, Update / Cancel / Install.

## 11. Glass system

Three materials. Every surface in the app uses exactly one.

| Material | How | Used for |
|---|---|---|
| **Liquid glass** | `drawBackdrop` from Kyant0 backdrop: saturation ×1.5, blur 8 dp, lens (refraction height 24 dp, amount 24 dp, depth effect, chromatic aberration) on API 33+, highlight and shadow defaults, surface tint `#121212` at 40%, backdrop sampled at 0.33 scale | Bottom tab pill, floating round buttons in top bars (back, actions), Query tab's floating Save button |
| **Frosted glass** | Haze `hazeBlur`: blur 24 dp, background colour `#0B0B0E` (the screen background sits outside the captured source, so without it translucent rows blur to nothing and sharp content shows through), tint `rgba(28,28,33,0.5)`, saturation 1.8, input scale fixed 0.33 | Pager bar on Table data, Edit row sheet, Update sheet, the bounded strip under the top bar where content scrolls |
| **Light glass** | No capture, no blur: fill `rgba(28,28,33,0.5)`, top highlight `rgba(255,255,255,0.10)` 1 dp, 0.5 dp border `rgba(255,255,255,0.14)` | All cards and grouped lists, search fields, chips, segmented controls, the SQL editor box |

The canvas currently draws blur on every card. On the flat `#0B0B0E` background a blur has
nothing to show, so cards use light glass. It looks the same and costs nothing per frame.

**Rules**
- Blur only where content scrolls underneath (BitChord's rule). The grid itself is never glass.
- The bottom fade behind the tab pill is a flat gradient, not blur.
- **Reduce blur** setting (Settings → Reading): liquid and frosted glass become solid
  `#1C1C21` with the same border. Light glass is unchanged.
- API 26–30: no RenderEffect, so the app behaves as if Reduce blur were on. API 31–32: blur
  without the lens. API 33+: everything.
- The tab bar's selected pill animates between tabs with a spring; v1 draws it in light glass on
  top of the liquid pill.

## 12. Visual design

Tokens taken from the canvas:

| Token | Value |
|---|---|
| Background | `#0B0B0E` |
| Text primary / secondary / tertiary | `#F5F5F7`, `rgba(255,255,255,0.70)`, `rgba(255,255,255,0.62)` |
| Accent (setting, 4 options) | `#5AC8FA` default; `#30D158`, `#FF9F0A`, `#BF5AF2` |
| Environment badges | PROD `#FF9A92` on `rgba(255,69,58,0.20)`; STAGING `#FFC56B` on `rgba(255,159,10,0.20)`; DEV `#9BDFFF` on `rgba(90,200,250,0.18)`; LOCAL `#86E8A0` on `rgba(48,209,88,0.18)` |
| SQL colours | keyword `#FF8FB8`, string `#FFC27A`, number `#C9B3FF`, plain `#F5F5F7` |
| Type | Geist for UI (34/41 bold large titles, 20/25 section heads, 17/22 rows, 15/20 secondary, 11/13 tab labels); Geist Mono for identifiers, values and SQL |
| Radii | cards 22 dp, rows inside cards 0, chips 22 dp (pill), sheets 32 dp top corners |
| Touch targets | 44 dp minimum, as drawn |
| Hairlines | 0.5 dp `rgba(255,255,255,0.12)`, inset to the text column |

**Changes the canvas needs for v1** (to be drawn on the canvas before M2):
1. Connections: the type chips (MySQL / PostgreSQL / Redis / MongoDB) become environment chips
   (All / PROD / STAGING / DEV / LOCAL). Sample connections become MySQL and MariaDB only.
2. Settings: add "Reduce blur" under Reading, "Check for updates" and the app version under a new
   About section. Remove "Import from DBeaver or Navicat" and "Back up connections" (not in v1).
3. New artboards: **History** (recent runs with status and time, saved queries, long-press to delete),
   **New/Edit connection** (name, host, port, user, password, database, environment tag,
   TLS mode, verify certificate, read-only, Test connection), **Cell viewer** (full value,
   copy, JSON pretty-print), **Update sheet**.
4. Edge-to-edge: the 52 dp top padding becomes the real status-bar inset; the tab pill sits
   above the gesture-navigation inset.

## 13. Milestones

**M0 — Speed proof (gate, before any screen work).**
Install NDK and `cargo-ndk`. Build `dby-core` with `connect`, session setup, `table_page`,
`run_sql`, `cancel`. One throwaway Compose screen: a connection form, a grid of one table with
prefetch, a SQL box, timings on screen, and a Haze pill over the scrolling grid. Run the four §1
benchmarks against TevelMobile 1.15.5 on the same phone and RDS. Record numbers in
`docs/benchmarks.md`. **Gate:** DBY wins all four and the jank target holds. If not, the design
changes here, before screens are built.

**M1 — Core complete.** Every §5 function, rusqlite store, schema cache, read-only layers, row
edits, error mapping, integration tests (§14) green on MySQL 8.4 and MariaDB 11.4.

**M2 — Screens.** Glass system (§11), theme and fonts, all screens from the canvas plus the four new
artboards, Keystore and App lock.

**M3 — Release.** Updater (§10), release workflow, signing, R8 rules, 16 KB alignment check,
first tagged release, final benchmark run.

## 14. Testing

- **Rust unit tests:** paging SQL builder (single and composite keys, sort column, no-PK fallback),
  statement classifier (reads, writes, DDL, unparseable), cell decoding edge cases
  (`BIGINT UNSIGNED` max, `DECIMAL(65,30)`, zero dates, invalid UTF-8 in `VARCHAR`).
- **Rust integration tests** (`core/tests`, Docker `mysql:8.4` and `mariadb:11.4`, skipped when
  `DBY_TEST_MYSQL_URL` is unset): connect over TLS, session setup, schema refresh in one round trip,
  keyset paging across 10k rows, `sql_select_limit` cap, explicit large LIMIT stops at 1000,
  `KILL QUERY` cancel, reconnect after the server kills the session, read-only session rejects
  `INSERT`, row edit rollback on mismatch, exact decimals and bigints round-trip.
- **Kotlin unit tests:** updater manifest parsing and version comparison, error-to-message mapping.
- **On device:** the §1 benchmarks, `gfxinfo` jank on Table data and Connections, an update from
  one tagged build to the next.

## 15. Licences

| Component | Licence | Obligation |
|---|---|---|
| t8y2/dbx driver code adapted into `core` | Apache-2.0 | keep notice in `core/NOTICE` |
| Haze, Kyant0 backdrop, UniFFI, mysql_async, rustls, rusqlite, sqlparser | Apache-2.0 / MIT | list in an in-app Licences page |
| sora-editor | LGPL-2.1 | use unmodified as a separate library; list it and link its source |
| Geist, Geist Mono | OFL-1.1 | include the licence file |
| BitChord, Echo-Music | GPL-3.0 | **visual reference only; no code copied** |

## 16. Decisions with defaults (change before M0 if wrong)

| Decision | Default |
|---|---|
| Application id | `com.dby.mobile` (cannot change after users install it) |
| GitHub repository for code, releases and the update manifest | `yashoncode/dby` (decided by the user) |
| RDS reachability | Public endpoint with TLS; SSH tunnel only if this is wrong (§2) |
| Row cap for SQL results | 1000 rows |
| Page size for table browsing | 50 rows |
