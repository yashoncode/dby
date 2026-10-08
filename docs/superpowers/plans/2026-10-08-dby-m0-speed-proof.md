# DBY M0 — Speed Proof Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove, with numbers from a real phone and the real RDS instance, that DBY's Rust core + Compose app is faster than TevelMobile 1.15.5 before any production screen is built.

**Architecture:** A Rust library (`core/`, crate `dby-core`) owns the MySQL wire work through `mysql_async` and is exposed to Kotlin through UniFFI as async functions and a `Session` object. A throwaway Compose screen in `android/` drives it, logs timings to logcat under the tag `DBYBENCH`, and draws a Haze glass pill over a scrolling grid. TevelMobile gets the same `DBYBENCH` timing lines on a local branch, and a small script turns both apps' logcat output into the comparison table in `docs/benchmarks.md`.

**Tech Stack:** Rust 1.98, `mysql_async` 0.37 (rustls + ring), `tokio` 1, UniFFI 0.32 (tokio async), `cargo-ndk`, Android NDK 30.0.16248370, Gradle 9.7.1, AGP 9.3.2 (built-in Kotlin), Kotlin 2.4.10, Compose BOM alpha 2026.08.00, Haze 2.0.1, JNA 5.19.1, Docker (MySQL 8.4, MariaDB 11.4), Python 3.12 (report script only).

**Spec:** `docs/superpowers/specs/2026-10-08-dby-design.md` — read §1, §5, §6, §9 and §13 (M0) before starting.

## Global Constraints

- Android: `minSdk 26`, `compileSdk 36`, `targetSdk 36`; ABIs `arm64-v8a` and `x86_64`; native libraries 16 KB page-aligned.
- Application id and Kotlin package for app code: `com.dby.mobile`. UniFFI-generated Kotlin package: `com.dby.core`.
- Databases: MySQL 5.7 / 8.x and MariaDB 10.x / 11.x only.
- TLS modes: `Verify` (public CAs + bundled Amazon RDS CA), `EncryptOnly` ("Encrypted, not verified"), `Off` ("Not encrypted").
- Row cap for user SQL: 1000 rows. Table page size: 50 rows. Cells in pages are trimmed to 256 characters (text) or 256 bytes (binary) by the server; cells in user SQL results are trimmed to 256 by the client.
- No BIGINT or DECIMAL value may be rounded anywhere: integers travel as `i64`/`u64`, decimals as strings.
- Every table page is exactly one round trip: text protocol, cursor values written as `_utf8mb4 X'<hex>'`, `X'<hex>'` or validated digits — never string concatenation of user data.
- Kotlin-facing names: `Cell` variants `Null, Signed, Unsigned, Real, Exact, Text, Temporal, Bytes`; `DbyError` text fields are named `detail`; the session's teardown method is `disconnect` (UniFFI already generates `close()`).
- Licences: no code is copied from BitChord or Echo-Music (GPL-3.0). Nothing from `t8y2/dbx` is copied in M0.
- Commits use Conventional Commit prefixes and are pushed to `origin main` (`https://github.com/yashoncode/dby`) at the end of each task.

## Review Focus

1. **Text primary keys under a case-insensitive collation with non-ASCII and emoji values** — paging must return every row exactly once in the server's own `ORDER BY` order. Pinned by `text_key_paging_follows_column_collation` (Task 5).
2. **The server or network drops a connection between taps** (phone backgrounded, RDS idle timeout, `KILL`) — the next read must work without user action, and a write must never be silently run twice. Pinned by `ping_reconnects_after_browse_connection_is_killed` (Task 4), `read_is_retried_after_the_server_drops_the_connection` and `write_is_not_retried_after_the_server_drops_the_connection` (Task 6).
3. **User SQL that would return huge or wide results** (`SELECT *` with no `LIMIT`, `LIMIT 100000`, 5000-character text, 1000-byte blobs) — capped at 1000 rows and 256 characters/bytes per cell, and the connection stays usable afterwards. Pinned by `select_without_limit_stops_at_1000_rows` and `explicit_huge_limit_is_cut_and_connection_survives` (Task 6) and `client_cap_trims_text_and_bytes` (Task 2).
4. **Values that don't fit ordinary numbers or strings** — `BIGINT UNSIGNED` max, `BIGINT` min, `DECIMAL(65,30)`, zero dates `0000-00-00 00:00:00`, invalid UTF-8. Pinned by the Task 2 unit tests and `exact_values_survive` (Task 5).
5. **Tables without a primary key, and composite keys** — paging must finish and cover every row. Pinned by `table_without_key_pages_by_offset` and `composite_key_pages_cover_all_pairs` (Task 5).

---

## File Structure

```
.gitignore                                   Task 1
core/
  Cargo.toml                                 Task 1  package dby-core + workspace with uniffi-bindgen
  Cargo.lock                                 Task 1  (generated, committed)
  .cargo/config.toml                         Task 1  16 KB page alignment for Android targets
  uniffi.toml                                Task 1  Kotlin package name
  src/lib.rs                                 Task 1, re-exports grow in Tasks 2–6
  src/value.rs                               Task 2  wire value → Cell
  src/error.rs                               Task 3  DbyError + mysql_async error mapping
  src/paging.rs                              Task 3  schema rows → TableMeta, page SQL, cursor literals
  src/session.rs                             Task 4  open/ping/disconnect; Task 5 table_page; Task 6 run_sql/cancel
  certs/rds-global-bundle.pem                Task 4  Amazon RDS CA bundle (compiled in)
  uniffi-bindgen/Cargo.toml, src/main.rs     Task 1  host tool that writes the Kotlin bindings
  tests/docker-compose.yml, tests/seed.sql   Task 4  local MySQL 8.4 + MariaDB 11.4 with test data
  tests/common/mod.rs                        Task 4  shared test helpers
  tests/open.rs                              Task 4
  tests/pages.rs                             Task 5
  tests/sql.rs                               Task 6
android/
  settings.gradle.kts, build.gradle.kts, gradle.properties, gradlew, gradlew.bat,
  gradle/wrapper/gradle-wrapper.{jar,properties}                 Task 7
  app/build.gradle.kts                       Task 7  builds core/ and generates bindings before preBuild
  app/proguard-rules.pro                     Task 7
  app/src/main/AndroidManifest.xml           Task 7
  app/src/main/java/com/dby/mobile/MainActivity.kt      Task 7, replaced in Task 8
  app/src/main/java/com/dby/mobile/BenchViewModel.kt    Task 8  (throwaway M0 screen state)
  app/src/main/java/com/dby/mobile/BenchScreen.kt       Task 8  (throwaway M0 screen)
scripts/bench_report.py                      Task 9
docs/benchmarks.md                           Task 9
```

---

### Task 1: Toolchain and an empty core that builds for Android

**Files:**
- Create: `.gitignore`
- Create: `core/Cargo.toml`, `core/.cargo/config.toml`, `core/uniffi.toml`, `core/src/lib.rs`
- Create: `core/uniffi-bindgen/Cargo.toml`, `core/uniffi-bindgen/src/main.rs`

**Interfaces:**
- Consumes: nothing.
- Produces: crate `dby-core` (lib name `dby_core`) with `uniffi::setup_scaffolding!()` and `#[uniffi::export] pub fn core_version() -> String`; the bindgen command used by Task 7.

- [ ] **Step 1: Install the NDK and cargo-ndk**

Run:
```bash
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager.bat" --install "ndk;30.0.16248370"
cargo install cargo-ndk --locked
cargo ndk --version
```
Expected: the NDK folder `$ANDROID_HOME/ndk/30.0.16248370` exists and `cargo ndk --version` prints a version.

- [ ] **Step 2: Write `.gitignore`**

```gitignore
# Rust
/core/target/
# Android
/android/.gradle/
/android/.kotlin/
/android/build/
/android/app/build/
/android/local.properties
# Generated from core/ by the Gradle build
/android/app/src/main/jniLibs/
/android/app/src/main/java/com/dby/core/
# Benchmark logcat dumps can contain other apps' logs
/docs/benchmarks/raw/
# Signing keys never go in git
*.jks
*.keystore
keystore.properties
```

- [ ] **Step 3: Write `core/Cargo.toml`**

```toml
[package]
name = "dby-core"
version = "0.1.0"
edition = "2021"
license = "Apache-2.0"
publish = false

[lib]
name = "dby_core"
crate-type = ["cdylib", "lib"]

[dependencies]
uniffi = { version = "0.32", features = ["tokio"] }
# ring, not aws-lc-rs: it cross-compiles for Android with the NDK alone.
mysql_async = { version = "0.37", default-features = false, features = ["default-rustls-ring", "client_ed25519"] }
rustls = { version = "0.23", default-features = false, features = ["ring", "std", "tls12", "logging"] }
tokio = { version = "1", features = ["time", "sync", "macros"] }
futures-util = "0.3"
thiserror = "2"

[dev-dependencies]
tokio = { version = "1", features = ["rt-multi-thread", "macros", "time", "sync"] }

[workspace]
members = ["uniffi-bindgen"]

[profile.release]
lto = true
codegen-units = 1
opt-level = "s"
strip = true
panic = "abort"
```

- [ ] **Step 4: Write `core/.cargo/config.toml`**

```toml
# Google Play requires native libraries aligned to 16 KB pages.
[target.aarch64-linux-android]
rustflags = ["-C", "link-arg=-Wl,-z,max-page-size=16384"]

[target.x86_64-linux-android]
rustflags = ["-C", "link-arg=-Wl,-z,max-page-size=16384"]
```

- [ ] **Step 5: Write `core/uniffi.toml`**

```toml
[bindings.kotlin]
package_name = "com.dby.core"
cdylib_name = "dby_core"
```

- [ ] **Step 6: Write `core/src/lib.rs`**

```rust
//! DBY's core: the MySQL/MariaDB client the Android app calls through UniFFI.

uniffi::setup_scaffolding!();

/// The core's version, shown by the app so a stale native library is easy to spot.
#[uniffi::export]
pub fn core_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}
```

- [ ] **Step 7: Write the bindgen tool**

`core/uniffi-bindgen/Cargo.toml`:
```toml
[package]
name = "uniffi-bindgen"
version = "0.1.0"
edition = "2021"
publish = false

[dependencies]
uniffi = { version = "0.32", features = ["cli"] }
```

`core/uniffi-bindgen/src/main.rs`:
```rust
fn main() {
    uniffi::uniffi_bindgen_main()
}
```

- [ ] **Step 8: Build for the host**

Run: `(cd core && cargo build)`
Expected: `Finished` with no errors. This also proves `ring` builds with the MSVC toolchain, which Tasks 2–6 need for `cargo test`.

- [ ] **Step 9: Build for Android**

Run: `(cd core && cargo ndk -t arm64-v8a -t x86_64 --platform 26 -o target/jniLibs build --release)`
Expected: `core/target/jniLibs/arm64-v8a/libdby_core.so` and `core/target/jniLibs/x86_64/libdby_core.so` exist.

- [ ] **Step 10: Check 16 KB alignment**

Run:
```bash
"$ANDROID_HOME/ndk/30.0.16248370/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe" -lW core/target/jniLibs/arm64-v8a/libdby_core.so | grep LOAD
```
Expected: every `LOAD` line ends with `0x4000`.

- [ ] **Step 11: Generate Kotlin bindings once**

Run: `(cd core && cargo run -p uniffi-bindgen -- generate --library target/jniLibs/arm64-v8a/libdby_core.so --language kotlin --out-dir target/kotlin --no-format)`
Then: `grep -n "fun coreVersion" core/target/kotlin/com/dby/core/dby_core.kt`
Expected: one match, in package `com.dby.core`.

- [ ] **Step 12: Commit and push**

```bash
git add .gitignore core/Cargo.toml core/Cargo.lock core/.cargo/config.toml core/uniffi.toml core/src/lib.rs core/uniffi-bindgen
git commit -m "build(core): Rust core skeleton that cross-compiles for Android with UniFFI bindings"
git push
```

---

### Task 2: Wire values to exact cells

**Files:**
- Create: `core/src/value.rs`
- Modify: `core/src/lib.rs` (add module and re-export)

**Interfaces:**
- Consumes: `mysql_async::{Column, Value}`, `mysql_async::consts::{ColumnType, ColumnFlags}`.
- Produces:
  - `pub enum Cell { Null, Signed { v: i64 }, Unsigned { v: u64 }, Real { v: f64 }, Exact { v: String }, Text { v: String, full_len: u64 }, Temporal { v: String }, Bytes { len: u64, preview: Vec<u8> } }` (derives `Debug, Clone, PartialEq, uniffi::Enum`)
  - `pub enum Kind { Integer, Float, Decimal, Temporal, Text, Binary }`
  - `pub struct ColMeta { pub kind: Kind, pub unsigned: bool }` with `pub const TEXT: ColMeta`
  - `pub fn meta_of(col: &Column) -> ColMeta`
  - `pub fn decode(value: Value, meta: &ColMeta, server_full_len: Option<u64>, client_cap: bool) -> Cell`
  - `pub fn as_len(value: &Value) -> Option<u64>`
  - `pub const CLIENT_CAP: usize = 256`

- [ ] **Step 1: Write the failing tests**

Create `core/src/value.rs` with only the tests module first:

```rust
//! Turns MySQL wire values into `Cell`s without losing precision.

#[cfg(test)]
mod tests {
    use super::*;
    use mysql_async::consts::{ColumnFlags, ColumnType};
    use mysql_async::{Column, Value};

    const UTF8MB4: u16 = 255;
    const BIN: u16 = 63;

    fn meta(t: ColumnType, flags: ColumnFlags, charset: u16) -> ColMeta {
        meta_of(&Column::new(t).with_flags(flags).with_character_set(charset))
    }

    fn text_col() -> ColMeta {
        meta(ColumnType::MYSQL_TYPE_VAR_STRING, ColumnFlags::empty(), UTF8MB4)
    }

    fn bytes(s: &str) -> Value {
        Value::Bytes(s.as_bytes().to_vec())
    }

    #[test]
    fn unsigned_bigint_max_stays_exact() {
        let m = meta(ColumnType::MYSQL_TYPE_LONGLONG, ColumnFlags::UNSIGNED_FLAG, BIN);
        assert_eq!(decode(bytes("18446744073709551615"), &m, None, false), Cell::Unsigned { v: u64::MAX });
    }

    #[test]
    fn signed_bigint_min_stays_exact() {
        let m = meta(ColumnType::MYSQL_TYPE_LONGLONG, ColumnFlags::empty(), BIN);
        assert_eq!(decode(bytes("-9223372036854775808"), &m, None, false), Cell::Signed { v: i64::MIN });
    }

    #[test]
    fn decimal_travels_as_exact_text() {
        let m = meta(ColumnType::MYSQL_TYPE_NEWDECIMAL, ColumnFlags::empty(), BIN);
        let v = "12345678901234567890123456789012345.123456789012345678901234567890";
        assert_eq!(decode(bytes(v), &m, None, false), Cell::Exact { v: v.to_string() });
    }

    #[test]
    fn double_becomes_real() {
        let m = meta(ColumnType::MYSQL_TYPE_DOUBLE, ColumnFlags::empty(), BIN);
        assert_eq!(decode(bytes("1.5"), &m, None, false), Cell::Real { v: 1.5 });
    }

    #[test]
    fn json_is_text_even_with_binary_charset() {
        let m = meta(ColumnType::MYSQL_TYPE_JSON, ColumnFlags::empty(), BIN);
        assert_eq!(m.kind, Kind::Text);
        assert_eq!(decode(bytes("{\"a\":1}"), &m, None, false), Cell::Text { v: "{\"a\":1}".into(), full_len: 7 });
    }

    #[test]
    fn blob_with_binary_charset_is_bytes_and_with_utf8_is_text() {
        assert_eq!(meta(ColumnType::MYSQL_TYPE_BLOB, ColumnFlags::empty(), BIN).kind, Kind::Binary);
        assert_eq!(meta(ColumnType::MYSQL_TYPE_BLOB, ColumnFlags::empty(), UTF8MB4).kind, Kind::Text);
    }

    #[test]
    fn zero_date_passes_through_untouched() {
        let m = meta(ColumnType::MYSQL_TYPE_DATETIME, ColumnFlags::empty(), BIN);
        assert_eq!(decode(bytes("0000-00-00 00:00:00"), &m, None, false), Cell::Temporal { v: "0000-00-00 00:00:00".into() });
    }

    #[test]
    fn invalid_utf8_is_replaced_not_dropped() {
        let v = Value::Bytes(vec![b'a', 0xff, b'b']);
        assert_eq!(decode(v, &text_col(), None, false), Cell::Text { v: "a\u{FFFD}b".into(), full_len: 3 });
    }

    #[test]
    fn client_cap_trims_text_and_bytes() {
        let long = "é".repeat(1000);
        assert_eq!(
            decode(bytes(&long), &text_col(), None, true),
            Cell::Text { v: "é".repeat(CLIENT_CAP), full_len: 1000 }
        );
        let m = meta(ColumnType::MYSQL_TYPE_BLOB, ColumnFlags::empty(), BIN);
        assert_eq!(
            decode(Value::Bytes(vec![7; 1000]), &m, None, true),
            Cell::Bytes { len: 1000, preview: vec![7; CLIENT_CAP] }
        );
    }

    #[test]
    fn server_trimmed_values_keep_their_full_length() {
        assert_eq!(
            decode(bytes(&"x".repeat(256)), &text_col(), Some(5000), false),
            Cell::Text { v: "x".repeat(256), full_len: 5000 }
        );
        let m = meta(ColumnType::MYSQL_TYPE_BLOB, ColumnFlags::empty(), BIN);
        assert_eq!(decode(Value::Bytes(vec![1; 256]), &m, Some(1000), false), Cell::Bytes { len: 1000, preview: vec![1; 256] });
    }

    #[test]
    fn untrimmed_short_values_are_kept_whole() {
        assert_eq!(decode(bytes("abc"), &text_col(), None, false), Cell::Text { v: "abc".into(), full_len: 3 });
    }

    #[test]
    fn null_is_null() {
        assert_eq!(decode(Value::NULL, &text_col(), None, true), Cell::Null);
    }

    #[test]
    fn binary_protocol_values() {
        let m = text_col();
        assert_eq!(decode(Value::Int(-5), &m, None, false), Cell::Signed { v: -5 });
        assert_eq!(decode(Value::UInt(7), &m, None, false), Cell::Unsigned { v: 7 });
        assert_eq!(decode(Value::Double(2.25), &m, None, false), Cell::Real { v: 2.25 });
        assert_eq!(
            decode(Value::Date(2026, 10, 8, 14, 22, 8, 0), &m, None, false),
            Cell::Temporal { v: "2026-10-08 14:22:08".into() }
        );
        assert_eq!(
            decode(Value::Time(true, 1, 2, 3, 4, 500), &m, None, false),
            Cell::Temporal { v: "-26:03:04.000500".into() }
        );
    }

    #[test]
    fn as_len_reads_text_and_numbers() {
        assert_eq!(as_len(&bytes("5000")), Some(5000));
        assert_eq!(as_len(&Value::Int(12)), Some(12));
        assert_eq!(as_len(&Value::NULL), None);
    }
}
```

Add to `core/src/lib.rs` (after `uniffi::setup_scaffolding!();`):
```rust
mod value;

pub use value::Cell;
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `(cd core && cargo test --lib value)`
Expected: compile errors — `cannot find type ColMeta`, `cannot find function decode`, etc.

- [ ] **Step 3: Write the implementation**

Insert above the `#[cfg(test)]` line in `core/src/value.rs`:

```rust
use mysql_async::consts::{ColumnFlags, ColumnType};
use mysql_async::{Column, Value};

/// Characters or bytes a cell keeps when the client trims it (results of the user's own SQL).
pub const CLIENT_CAP: usize = 256;

/// MySQL's charset number for binary strings.
const BINARY_CHARSET: u16 = 63;

#[derive(Debug, Clone, PartialEq, uniffi::Enum)]
pub enum Cell {
    Null,
    Signed { v: i64 },
    Unsigned { v: u64 },
    /// FLOAT and DOUBLE only.
    Real { v: f64 },
    /// DECIMAL, and any number whose text does not parse.
    Exact { v: String },
    /// `full_len` counts characters; more than `v` holds means the value was trimmed.
    Text { v: String, full_len: u64 },
    /// DATE, TIME, DATETIME, TIMESTAMP as the server writes them.
    Temporal { v: String },
    Bytes { len: u64, preview: Vec<u8> },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    Integer,
    Float,
    Decimal,
    Temporal,
    Text,
    Binary,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ColMeta {
    pub kind: Kind,
    pub unsigned: bool,
}

impl ColMeta {
    pub const TEXT: ColMeta = ColMeta { kind: Kind::Text, unsigned: false };
}

pub fn meta_of(col: &Column) -> ColMeta {
    use ColumnType::*;
    let kind = match col.column_type() {
        MYSQL_TYPE_TINY | MYSQL_TYPE_SHORT | MYSQL_TYPE_LONG | MYSQL_TYPE_INT24 | MYSQL_TYPE_LONGLONG
        | MYSQL_TYPE_YEAR => Kind::Integer,
        MYSQL_TYPE_FLOAT | MYSQL_TYPE_DOUBLE => Kind::Float,
        MYSQL_TYPE_DECIMAL | MYSQL_TYPE_NEWDECIMAL => Kind::Decimal,
        MYSQL_TYPE_DATE | MYSQL_TYPE_NEWDATE | MYSQL_TYPE_TIME | MYSQL_TYPE_TIME2 | MYSQL_TYPE_DATETIME
        | MYSQL_TYPE_DATETIME2 | MYSQL_TYPE_TIMESTAMP | MYSQL_TYPE_TIMESTAMP2 => Kind::Temporal,
        // MySQL sends JSON with the binary charset, but it is text.
        MYSQL_TYPE_JSON => Kind::Text,
        MYSQL_TYPE_BIT | MYSQL_TYPE_GEOMETRY => Kind::Binary,
        _ if col.character_set() == BINARY_CHARSET => Kind::Binary,
        _ => Kind::Text,
    };
    ColMeta { kind, unsigned: col.flags().contains(ColumnFlags::UNSIGNED_FLAG) }
}

/// `server_full_len` is the length the server reported for a value it trimmed.
/// `client_cap` trims long text and bytes here instead, for SQL the user wrote.
pub fn decode(value: Value, meta: &ColMeta, server_full_len: Option<u64>, client_cap: bool) -> Cell {
    match value {
        Value::NULL => Cell::Null,
        Value::Int(v) => Cell::Signed { v },
        Value::UInt(v) => Cell::Unsigned { v },
        Value::Float(v) => Cell::Real { v: f64::from(v) },
        Value::Double(v) => Cell::Real { v },
        Value::Date(y, mo, d, h, mi, s, us) => Cell::Temporal { v: date_text(y, mo, d, h, mi, s, us) },
        Value::Time(neg, days, h, mi, s, us) => Cell::Temporal { v: time_text(neg, days, h, mi, s, us) },
        Value::Bytes(b) => from_text(b, meta, server_full_len, client_cap),
    }
}

/// Reads a length the server sent alongside a trimmed value.
pub fn as_len(value: &Value) -> Option<u64> {
    match value {
        Value::Bytes(b) => std::str::from_utf8(b).ok()?.parse().ok(),
        Value::Int(v) => u64::try_from(*v).ok(),
        Value::UInt(v) => Some(*v),
        _ => None,
    }
}

/// The text protocol sends every value as bytes; the column type says what they mean.
fn from_text(b: Vec<u8>, meta: &ColMeta, server_full_len: Option<u64>, client_cap: bool) -> Cell {
    match meta.kind {
        Kind::Integer => {
            let s = lossy(b);
            let parsed = if meta.unsigned {
                s.parse().ok().map(|v| Cell::Unsigned { v })
            } else {
                s.parse().ok().map(|v| Cell::Signed { v })
            };
            parsed.unwrap_or(Cell::Exact { v: s })
        }
        Kind::Float => {
            let s = lossy(b);
            match s.parse::<f64>() {
                Ok(v) if v.is_finite() => Cell::Real { v },
                _ => Cell::Exact { v: s },
            }
        }
        Kind::Decimal => Cell::Exact { v: lossy(b) },
        Kind::Temporal => Cell::Temporal { v: lossy(b) },
        Kind::Binary => {
            let len = server_full_len.unwrap_or(b.len() as u64);
            let mut preview = b;
            if client_cap {
                preview.truncate(CLIENT_CAP);
            }
            Cell::Bytes { len, preview }
        }
        Kind::Text => {
            let s = lossy(b);
            let chars = s.chars().count() as u64;
            if client_cap && chars > CLIENT_CAP as u64 {
                Cell::Text { v: s.chars().take(CLIENT_CAP).collect(), full_len: chars }
            } else {
                Cell::Text { v: s, full_len: server_full_len.unwrap_or(chars) }
            }
        }
    }
}

fn lossy(b: Vec<u8>) -> String {
    String::from_utf8(b).unwrap_or_else(|e| String::from_utf8_lossy(e.as_bytes()).into_owned())
}

fn date_text(y: u16, mo: u8, d: u8, h: u8, mi: u8, s: u8, us: u32) -> String {
    let base = format!("{y:04}-{mo:02}-{d:02} {h:02}:{mi:02}:{s:02}");
    if us == 0 { base } else { format!("{base}.{us:06}") }
}

fn time_text(neg: bool, days: u32, h: u8, mi: u8, s: u8, us: u32) -> String {
    let hours = days * 24 + u32::from(h);
    let sign = if neg { "-" } else { "" };
    let base = format!("{sign}{hours:02}:{mi:02}:{s:02}");
    if us == 0 { base } else { format!("{base}.{us:06}") }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `(cd core && cargo test --lib value)`
Expected: `test result: ok. 14 passed`.

- [ ] **Step 5: Commit and push**

```bash
git add core/src/value.rs core/src/lib.rs
git commit -m "feat(core): decode MySQL wire values into exact cells"
git push
```

---

### Task 3: Errors and page SQL

**Files:**
- Create: `core/src/error.rs`, `core/src/paging.rs`
- Modify: `core/src/lib.rs`

**Interfaces:**
- Consumes: `Cell` (Task 2).
- Produces:
  - `pub enum DbyError { Network { detail }, Tls { detail }, Auth { detail }, UnknownDatabase { detail }, Server { code: u16, detail }, NotFound { detail }, Cancelled, Timeout, Internal { detail } }` (all `detail: String`), `impl From<mysql_async::Error> for DbyError`, `pub(crate) fn DbyError::internal(detail)`, `pub(crate) fn is_connection_lost(&mysql_async::Error) -> bool`
  - `pub struct Cursor { pub after: Vec<Cell>, pub offset: u64 }` (`uniffi::Record`)
  - `pub struct ColumnInfo { name, data_type, max_len: Option<u64> }`, `pub struct TableMeta { columns: Vec<ColumnInfo>, pk: Vec<usize> }`, `pub struct ColumnRow { table, column, data_type, max_len: Option<u64>, pk_seq: Option<u32> }`, `pub struct PageSql { sql: String, trimmed: Vec<bool> }`
  - `pub fn build_tables(rows: Vec<ColumnRow>) -> HashMap<String, Arc<TableMeta>>`
  - `pub fn quote_ident(name: &str) -> String`
  - `pub fn page_sql(table: &str, meta: &TableMeta, cursor: Option<&Cursor>, limit: u32) -> Result<PageSql, DbyError>`
  - `pub fn next_cursor(meta: &TableMeta, current: Option<&Cursor>, rows: &[Vec<Cell>], limit: u32) -> Option<Cursor>`
  - `pub fn literal(cell: &Cell) -> Result<String, DbyError>`

- [ ] **Step 1: Write `core/src/error.rs`**

The mapping is exercised end to end by Task 4's integration tests (wrong password, unknown database, closed port), so this file has no unit tests of its own.

```rust
use mysql_async::{DriverError, Error};

/// Every failure the core reports. Text fields are `detail`, never `message`:
/// UniFFI turns this into a Kotlin exception, where `message` is already taken.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum DbyError {
    #[error("could not reach the server: {detail}")]
    Network { detail: String },
    #[error("TLS failed: {detail}")]
    Tls { detail: String },
    #[error("wrong user or password: {detail}")]
    Auth { detail: String },
    #[error("unknown database: {detail}")]
    UnknownDatabase { detail: String },
    #[error("server error {code}: {detail}")]
    Server { code: u16, detail: String },
    #[error("not found: {detail}")]
    NotFound { detail: String },
    #[error("cancelled")]
    Cancelled,
    #[error("timed out")]
    Timeout,
    #[error("internal error: {detail}")]
    Internal { detail: String },
}

impl DbyError {
    pub(crate) fn internal(detail: impl Into<String>) -> Self {
        DbyError::Internal { detail: detail.into() }
    }
}

impl From<Error> for DbyError {
    fn from(e: Error) -> Self {
        match e {
            Error::Server(s) => match s.code {
                1045 | 1698 => DbyError::Auth { detail: s.message },
                1049 => DbyError::UnknownDatabase { detail: s.message },
                1317 => DbyError::Cancelled,
                code => DbyError::Server { code, detail: s.message },
            },
            Error::Driver(DriverError::NoClientSslFlagFromServer) => {
                DbyError::Tls { detail: "the server does not offer TLS".into() }
            }
            Error::Io(io) => {
                let detail = io.to_string();
                // ponytail: TLS failures are told apart by text, because IoError's TLS variant
                // depends on mysql_async's feature flags; match the variant once it is stable API.
                let lower = detail.to_ascii_lowercase();
                if lower.contains("certificate") || lower.contains("tls") || lower.contains("handshake") {
                    DbyError::Tls { detail }
                } else {
                    DbyError::Network { detail }
                }
            }
            other => DbyError::Internal { detail: other.to_string() },
        }
    }
}

/// True when the connection itself is gone (network drop, server restart, `KILL`, idle
/// timeout), as opposed to an error in the statement. Codes: 1053 server shutdown,
/// 1927 MariaDB connection killed, 3169 MySQL session killed, 4031 MySQL idle disconnect.
pub(crate) fn is_connection_lost(e: &Error) -> bool {
    match e {
        Error::Io(_) => true,
        Error::Driver(DriverError::ConnectionClosed) => true,
        Error::Server(s) => matches!(s.code, 1053 | 1927 | 3169 | 4031),
        _ => false,
    }
}
```

- [ ] **Step 2: Write the failing paging tests**

Create `core/src/paging.rs` with the tests module only:

```rust
//! Builds the SQL for one page of a table. Pure: no I/O, so it is unit-tested directly.

#[cfg(test)]
mod tests {
    use super::*;

    fn col(name: &str, data_type: &str, max_len: Option<u64>) -> ColumnInfo {
        ColumnInfo { name: name.into(), data_type: data_type.into(), max_len }
    }

    fn orders() -> TableMeta {
        TableMeta {
            columns: vec![
                col("id", "bigint", None),
                col("customer", "varchar", Some(80)),
                col("status", "varchar", Some(16)),
                col("total", "decimal", None),
                col("notes", "text", Some(65535)),
                col("created_at", "datetime", None),
            ],
            pk: vec![0],
        }
    }

    #[test]
    fn quote_ident_doubles_backticks() {
        assert_eq!(quote_ident("a`b"), "`a``b`");
    }

    #[test]
    fn first_page_orders_by_key_and_trims_long_text() {
        let built = page_sql("orders", &orders(), None, 50).unwrap();
        assert_eq!(
            built.sql,
            "SELECT `id`, `customer`, `status`, `total`, LEFT(`notes`, 256), CHAR_LENGTH(`notes`), `created_at` \
             FROM `orders` ORDER BY `id` LIMIT 51"
        );
        assert_eq!(built.trimmed, vec![false, false, false, false, true, false]);
    }

    #[test]
    fn next_page_starts_after_the_cursor() {
        let cursor = Cursor { after: vec![Cell::Unsigned { v: 50 }], offset: 0 };
        let built = page_sql("orders", &orders(), Some(&cursor), 50).unwrap();
        assert!(built.sql.contains(" FROM `orders` WHERE `id` > 50 ORDER BY `id` LIMIT 51"), "{}", built.sql);
    }

    #[test]
    fn composite_key_uses_a_row_comparison() {
        let meta = TableMeta { columns: vec![col("a", "int", None), col("b", "int", None), col("v", "varchar", Some(10))], pk: vec![0, 1] };
        let cursor = Cursor { after: vec![Cell::Signed { v: 1 }, Cell::Signed { v: 9 }], offset: 0 };
        let built = page_sql("pairs", &meta, Some(&cursor), 7).unwrap();
        assert_eq!(built.sql, "SELECT `a`, `b`, `v` FROM `pairs` WHERE (`a`, `b`) > (1, 9) ORDER BY `a`, `b` LIMIT 8");
    }

    #[test]
    fn table_without_key_pages_by_offset() {
        let meta = TableMeta { columns: vec![col("v", "int", None)], pk: vec![] };
        let cursor = Cursor { after: vec![], offset: 30 };
        let built = page_sql("nopk", &meta, Some(&cursor), 30).unwrap();
        assert_eq!(built.sql, "SELECT `v` FROM `nopk` LIMIT 31 OFFSET 30");
    }

    #[test]
    fn key_columns_are_never_trimmed() {
        let meta = TableMeta { columns: vec![col("code", "varchar", Some(512)), col("body", "longtext", None)], pk: vec![0] };
        let built = page_sql("docs", &meta, None, 10).unwrap();
        assert_eq!(built.sql, "SELECT `code`, LEFT(`body`, 256), CHAR_LENGTH(`body`) FROM `docs` ORDER BY `code` LIMIT 11");
        assert_eq!(built.trimmed, vec![false, true]);
    }

    #[test]
    fn blobs_are_trimmed_by_byte_length() {
        let meta = TableMeta { columns: vec![col("id", "int", None), col("data", "longblob", None)], pk: vec![0] };
        let built = page_sql("files", &meta, None, 10).unwrap();
        assert!(built.sql.contains("LEFT(`data`, 256), LENGTH(`data`)"), "{}", built.sql);
    }

    #[test]
    fn literals_cannot_carry_sql() {
        assert_eq!(literal(&Cell::Signed { v: -3 }).unwrap(), "-3");
        assert_eq!(literal(&Cell::Unsigned { v: u64::MAX }).unwrap(), "18446744073709551615");
        assert_eq!(literal(&Cell::Real { v: 1.5 }).unwrap(), "1.5");
        assert_eq!(literal(&Cell::Exact { v: "-12.50".into() }).unwrap(), "-12.50");
        assert_eq!(literal(&Cell::Text { v: "Äpfel".into(), full_len: 5 }).unwrap(), "_utf8mb4 X'c3847066656c'");
        assert_eq!(literal(&Cell::Text { v: "".into(), full_len: 0 }).unwrap(), "_utf8mb4 X''");
        assert_eq!(literal(&Cell::Temporal { v: "2026-01-01".into() }).unwrap(), "_utf8mb4 X'323032362d30312d3031'");
        assert_eq!(literal(&Cell::Bytes { len: 2, preview: vec![0x00, 0xff] }).unwrap(), "X'00ff'");
    }

    #[test]
    fn unsafe_or_partial_key_values_are_rejected() {
        assert!(literal(&Cell::Null).is_err());
        assert!(literal(&Cell::Exact { v: "1; DROP TABLE x".into() }).is_err());
        assert!(literal(&Cell::Exact { v: "1e5".into() }).is_err());
        assert!(literal(&Cell::Exact { v: ".".into() }).is_err());
        assert!(literal(&Cell::Text { v: "abc".into(), full_len: 9 }).is_err());
        assert!(literal(&Cell::Bytes { len: 9, preview: vec![1] }).is_err());
        assert!(literal(&Cell::Real { v: f64::NAN }).is_err());
    }

    #[test]
    fn cursor_length_must_match_the_key() {
        let cursor = Cursor { after: vec![Cell::Signed { v: 1 }, Cell::Signed { v: 2 }], offset: 0 };
        assert!(page_sql("orders", &orders(), Some(&cursor), 50).is_err());
    }

    #[test]
    fn build_tables_orders_the_key_by_its_sequence() {
        let rows = vec![
            ColumnRow { table: "t".into(), column: "a".into(), data_type: "INT".into(), max_len: None, pk_seq: Some(2) },
            ColumnRow { table: "t".into(), column: "b".into(), data_type: "VARCHAR".into(), max_len: Some(10), pk_seq: Some(1) },
            ColumnRow { table: "u".into(), column: "x".into(), data_type: "int".into(), max_len: None, pk_seq: None },
        ];
        let tables = build_tables(rows);
        let t = &tables["t"];
        assert_eq!(t.columns.iter().map(|c| c.name.as_str()).collect::<Vec<_>>(), ["a", "b"]);
        assert_eq!(t.columns[1].data_type, "varchar");
        assert_eq!(t.pk, vec![1, 0]);
        assert!(tables["u"].pk.is_empty());
    }

    #[test]
    fn next_cursor_takes_the_last_rows_key_or_advances_the_offset() {
        let rows = vec![
            vec![Cell::Unsigned { v: 99 }, Cell::Null, Cell::Null, Cell::Null, Cell::Null, Cell::Null],
            vec![Cell::Unsigned { v: 100 }, Cell::Null, Cell::Null, Cell::Null, Cell::Null, Cell::Null],
        ];
        assert_eq!(
            next_cursor(&orders(), None, &rows, 2),
            Some(Cursor { after: vec![Cell::Unsigned { v: 100 }], offset: 0 })
        );
        let nopk = TableMeta { columns: vec![col("v", "int", None)], pk: vec![] };
        let current = Cursor { after: vec![], offset: 30 };
        assert_eq!(next_cursor(&nopk, Some(&current), &[vec![Cell::Signed { v: 1 }]], 30), Some(Cursor { after: vec![], offset: 60 }));
    }
}
```

Add to `core/src/lib.rs`:
```rust
mod error;
mod paging;

pub use error::DbyError;
pub use paging::Cursor;
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `(cd core && cargo test --lib paging)`
Expected: compile errors — `cannot find type TableMeta`, `cannot find function page_sql`, etc.

- [ ] **Step 4: Write the implementation**

Insert above the `#[cfg(test)]` line in `core/src/paging.rs`:

```rust
use std::collections::HashMap;
use std::sync::Arc;

use crate::error::DbyError;
use crate::value::Cell;

/// Characters (text) or bytes (binary) a page keeps per cell; the rest stays on the server.
pub const TRIM: u64 = 256;

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Cursor {
    /// Primary-key values of the last row shown, in key order. Empty for tables without a key.
    pub after: Vec<Cell>,
    /// Row offset, used only for tables without a primary key.
    pub offset: u64,
}

#[derive(Debug, Clone, PartialEq)]
pub struct ColumnInfo {
    pub name: String,
    /// Lowercase `information_schema.COLUMNS.DATA_TYPE`, e.g. `varchar`.
    pub data_type: String,
    pub max_len: Option<u64>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct TableMeta {
    pub columns: Vec<ColumnInfo>,
    /// Indexes into `columns`, in primary-key order. Empty when the table has no primary key.
    pub pk: Vec<usize>,
}

/// One row of the schema query: a column, and its position in the primary key if it has one.
#[derive(Debug, Clone, PartialEq)]
pub struct ColumnRow {
    pub table: String,
    pub column: String,
    pub data_type: String,
    pub max_len: Option<u64>,
    pub pk_seq: Option<u32>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct PageSql {
    pub sql: String,
    /// Per table column: true when the query returns it as two values, the trimmed value and
    /// its full length.
    pub trimmed: Vec<bool>,
}

/// Groups schema rows (already in column order) into one `TableMeta` per table.
pub fn build_tables(rows: Vec<ColumnRow>) -> HashMap<String, Arc<TableMeta>> {
    let mut building: HashMap<String, (Vec<ColumnInfo>, Vec<(u32, usize)>)> = HashMap::new();
    for row in rows {
        let (columns, keys) = building.entry(row.table).or_default();
        if let Some(seq) = row.pk_seq {
            keys.push((seq, columns.len()));
        }
        columns.push(ColumnInfo { name: row.column, data_type: row.data_type.to_ascii_lowercase(), max_len: row.max_len });
    }
    building
        .into_iter()
        .map(|(table, (columns, mut keys))| {
            keys.sort_unstable();
            let pk = keys.into_iter().map(|(_, index)| index).collect();
            (table, Arc::new(TableMeta { columns, pk }))
        })
        .collect()
}

pub fn quote_ident(name: &str) -> String {
    format!("`{}`", name.replace('`', "``"))
}

/// The length function for a column the page should trim, or None to send it whole.
fn trim_with(c: &ColumnInfo) -> Option<&'static str> {
    let long = c.max_len.unwrap_or(0) > TRIM;
    match c.data_type.as_str() {
        "tinytext" | "text" | "mediumtext" | "longtext" | "json" => Some("CHAR_LENGTH"),
        "varchar" | "char" if long => Some("CHAR_LENGTH"),
        "tinyblob" | "blob" | "mediumblob" | "longblob" => Some("LENGTH"),
        "varbinary" | "binary" if long => Some("LENGTH"),
        _ => None,
    }
}

/// One page, fetching `limit + 1` rows: the extra row only says whether another page exists.
pub fn page_sql(table: &str, meta: &TableMeta, cursor: Option<&Cursor>, limit: u32) -> Result<PageSql, DbyError> {
    let mut select = Vec::with_capacity(meta.columns.len());
    let mut trimmed = Vec::with_capacity(meta.columns.len());
    for (index, column) in meta.columns.iter().enumerate() {
        let quoted = quote_ident(&column.name);
        // Key columns are sent whole: the next cursor is built from them.
        match trim_with(column).filter(|_| !meta.pk.contains(&index)) {
            Some(length_fn) => {
                select.push(format!("LEFT({quoted}, {TRIM}), {length_fn}({quoted})"));
                trimmed.push(true);
            }
            None => {
                select.push(quoted);
                trimmed.push(false);
            }
        }
    }
    let mut sql = format!("SELECT {} FROM {}", select.join(", "), quote_ident(table));
    let fetch = u64::from(limit) + 1;
    if meta.pk.is_empty() {
        let offset = cursor.map_or(0, |c| c.offset);
        sql.push_str(&format!(" LIMIT {fetch} OFFSET {offset}"));
        return Ok(PageSql { sql, trimmed });
    }
    let keys: Vec<String> = meta.pk.iter().map(|&i| quote_ident(&meta.columns[i].name)).collect();
    if let Some(cursor) = cursor {
        if cursor.after.len() != keys.len() {
            return Err(DbyError::internal("cursor does not match the table's primary key"));
        }
        let values = cursor.after.iter().map(literal).collect::<Result<Vec<_>, _>>()?;
        if keys.len() == 1 {
            sql.push_str(&format!(" WHERE {} > {}", keys[0], values[0]));
        } else {
            sql.push_str(&format!(" WHERE ({}) > ({})", keys.join(", "), values.join(", ")));
        }
    }
    sql.push_str(&format!(" ORDER BY {} LIMIT {fetch}", keys.join(", ")));
    Ok(PageSql { sql, trimmed })
}

/// The cursor for the page after `rows`. Call only when the server returned an extra row.
pub fn next_cursor(meta: &TableMeta, current: Option<&Cursor>, rows: &[Vec<Cell>], limit: u32) -> Option<Cursor> {
    if meta.pk.is_empty() {
        let offset = current.map_or(0, |c| c.offset) + u64::from(limit);
        return Some(Cursor { after: vec![], offset });
    }
    let last = rows.last()?;
    Some(Cursor { after: meta.pk.iter().map(|&i| last[i].clone()).collect(), offset: 0 })
}

/// A SQL literal for a key value that cannot carry SQL and does not depend on `sql_mode`.
/// Strings use an introducer, so the comparison runs under the column's own collation.
pub fn literal(cell: &Cell) -> Result<String, DbyError> {
    match cell {
        Cell::Signed { v } => Ok(v.to_string()),
        Cell::Unsigned { v } => Ok(v.to_string()),
        Cell::Real { v } if v.is_finite() => Ok(format!("{v:?}")),
        Cell::Exact { v } if is_plain_decimal(v) => Ok(v.clone()),
        Cell::Text { v, full_len } if *full_len == v.chars().count() as u64 => Ok(format!("_utf8mb4 X'{}'", hex(v.as_bytes()))),
        Cell::Temporal { v } => Ok(format!("_utf8mb4 X'{}'", hex(v.as_bytes()))),
        Cell::Bytes { len, preview } if *len == preview.len() as u64 => Ok(format!("X'{}'", hex(preview))),
        other => Err(DbyError::internal(format!("cannot page on key value {other:?}"))),
    }
}

fn is_plain_decimal(s: &str) -> bool {
    let unsigned = s.strip_prefix('-').unwrap_or(s);
    let (int, frac) = match unsigned.split_once('.') {
        Some((int, frac)) => (int, Some(frac)),
        None => (unsigned, None),
    };
    let digits = |part: &str| !part.is_empty() && part.bytes().all(|b| b.is_ascii_digit());
    digits(int) && frac.map_or(true, digits)
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}
```

- [ ] **Step 5: Run all unit tests**

Run: `(cd core && cargo test --lib)`
Expected: `test result: ok.` — 14 value tests and 12 paging tests pass.

- [ ] **Step 6: Commit and push**

```bash
git add core/src/error.rs core/src/paging.rs core/src/lib.rs
git commit -m "feat(core): error mapping and injection-proof keyset page SQL"
git push
```

---

### Task 4: Open a session (two connections, schema in one round trip)

**Files:**
- Create: `core/src/session.rs`, `core/certs/rds-global-bundle.pem`
- Create: `core/tests/docker-compose.yml`, `core/tests/seed.sql`, `core/tests/common/mod.rs`, `core/tests/open.rs`
- Modify: `core/src/lib.rs`

**Interfaces:**
- Consumes: `DbyError`, `is_connection_lost` (Task 3); `paging::{build_tables, ColumnRow, TableMeta}` (Task 3).
- Produces:
  - `pub enum TlsMode { Verify, EncryptOnly, Off }` (`uniffi::Enum`)
  - `pub struct ConnectParams { pub host: String, pub port: u16, pub user: String, pub password: String, pub database: String, pub tls: TlsMode }` (`uniffi::Record`)
  - `pub struct ServerInfo { pub version: String, pub connect_ms: u32 }` (`uniffi::Record`)
  - `pub struct Session` (`uniffi::Object`) with private fields `browse_opts, query_opts: Opts`, `browse, query: tokio::sync::Mutex<Option<Conn>>`, `query_conn_id: AtomicU32`, `running: std::sync::Mutex<Option<String>>`, `tables: std::sync::Mutex<HashMap<String, Arc<TableMeta>>>`, `info: ServerInfo`
  - `#[uniffi::export(async_runtime = "tokio")] pub async fn open_session(params: ConnectParams) -> Result<Arc<Session>, DbyError>`
  - exported methods `server_info(&self) -> ServerInfo`, `async ping(&self) -> Result<u32, DbyError>`, `async disconnect(&self)`
  - not exported: `pub async fn debug_connection_ids(&self) -> (u32, u32)` (browse id, query id), and private helpers `connect(&Opts)`, `elapsed_ms(Instant)`, `closed()` used by Tasks 5–6
  - test helpers in `tests/common`: `targets()`, `params(port)`, `open(port)`, `raw(port)`, `kill(port, id)`, `column(port, sql)`

- [ ] **Step 1: Bundle the Amazon RDS CA certificates**

Run: `curl -fsSL -o core/certs/rds-global-bundle.pem https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem && grep -c "BEGIN CERTIFICATE" core/certs/rds-global-bundle.pem`
Expected: a count above 50.

- [ ] **Step 2: Write the test databases**

`core/tests/docker-compose.yml`:
```yaml
# Local test databases for core/tests. From the repo root:
#   docker compose -f core/tests/docker-compose.yml up -d --wait
# then set DBY_TEST_MYSQL_PORT=33084 and DBY_TEST_MARIADB_PORT=33114.
services:
  mysql:
    image: mysql:8.4
    environment:
      MYSQL_ROOT_PASSWORD: dbytest
      MYSQL_DATABASE: shop
    ports: ["33084:3306"]
    volumes: ["./seed.sql:/docker-entrypoint-initdb.d/seed.sql:ro"]
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "127.0.0.1", "-uroot", "-pdbytest"]
      interval: 2s
      timeout: 5s
      retries: 90
  mariadb:
    image: mariadb:11.4
    environment:
      MARIADB_ROOT_PASSWORD: dbytest
      MARIADB_DATABASE: shop
    ports: ["33114:3306"]
    volumes: ["./seed.sql:/docker-entrypoint-initdb.d/seed.sql:ro"]
    healthcheck:
      test: ["CMD", "healthcheck.sh", "--connect", "--innodb_initialized"]
      interval: 2s
      timeout: 5s
      retries: 90
```

`core/tests/seed.sql`:
```sql
-- Test data shared by MySQL 8.4 and MariaDB 11.4. Every table here backs a test in core/tests.
USE shop;

CREATE TABLE digits (d INT PRIMARY KEY);
INSERT INTO digits VALUES (0), (1), (2), (3), (4), (5), (6), (7), (8), (9);

-- 100,000 rows; every 10th row has a 5000-character note.
CREATE TABLE orders (
  id BIGINT UNSIGNED PRIMARY KEY,
  customer VARCHAR(80) NOT NULL,
  status VARCHAR(16) NOT NULL,
  total DECIMAL(12, 2) NOT NULL,
  notes TEXT NULL,
  created_at DATETIME NOT NULL
);
INSERT INTO orders (id, customer, status, total, notes, created_at)
SELECT n,
       CONCAT('Customer ', n % 997),
       ELT(1 + n % 4, 'shipped', 'pending', 'delivered', 'cancelled'),
       (n % 100000) / 100,
       IF(n % 10 = 0, REPEAT('x', 5000), NULL),
       DATE_ADD('2026-01-01', INTERVAL n MINUTE)
FROM (SELECT a.d + 10 * b.d + 100 * c.d + 1000 * e.d + 10000 * f.d + 1 AS n
      FROM digits a, digits b, digits c, digits e, digits f) seq;

-- Text key under a case-insensitive collation, with non-ASCII and emoji values.
CREATE TABLE tags (
  name VARCHAR(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci PRIMARY KEY,
  note VARCHAR(20)
);
INSERT INTO tags (name, note) VALUES
  ('apple', ''), ('Banana', ''), ('cherry', ''), ('Äpfel', ''), ('zebra', ''),
  ('ünder', ''), ('😀smile', ''), ('mango', ''), ('kiwi', ''), ('Date', '');

-- Composite key, 100 rows.
CREATE TABLE pairs (a INT NOT NULL, b INT NOT NULL, v VARCHAR(10), PRIMARY KEY (a, b));
INSERT INTO pairs SELECT x.d, y.d, CONCAT(x.d, '-', y.d) FROM digits x, digits y;

-- No primary key, 100 rows.
CREATE TABLE nopk (v INT);
INSERT INTO nopk SELECT a.d + 10 * b.d FROM digits a, digits b;

-- Values that break naive clients. sql_mode is cleared so the zero date is accepted.
SET SESSION sql_mode = '';
CREATE TABLE exact_values (
  id INT PRIMARY KEY,
  big BIGINT UNSIGNED,
  signed_big BIGINT,
  money DECIMAL(65, 30),
  bin VARBINARY(16),
  blobby LONGBLOB,
  zero_date DATETIME
);
INSERT INTO exact_values VALUES (
  1,
  18446744073709551615,
  -9223372036854775808,
  12345678901234567890123456789012345.123456789012345678901234567890,
  X'00ff',
  REPEAT(X'ab', 1000),
  '0000-00-00 00:00:00'
);
```

- [ ] **Step 3: Start the databases**

Run: `docker compose -f core/tests/docker-compose.yml up -d --wait`
Expected: both services report `Healthy`.

- [ ] **Step 4: Write the test helpers**

`core/tests/common/mod.rs`:
```rust
#![allow(dead_code)] // each test file uses a different subset

use std::sync::Arc;

use dby_core::{open_session, ConnectParams, Session, TlsMode};
use mysql_async::prelude::Queryable;

/// Databases from tests/docker-compose.yml. An unset variable skips that target.
pub fn targets() -> Vec<(&'static str, u16)> {
    let mut found = Vec::new();
    for (name, var) in [("mysql", "DBY_TEST_MYSQL_PORT"), ("mariadb", "DBY_TEST_MARIADB_PORT")] {
        match std::env::var(var) {
            Ok(port) => found.push((name, port.parse().expect("port number"))),
            Err(_) => eprintln!("skipping {name}: {var} is not set"),
        }
    }
    found
}

pub fn params(port: u16) -> ConnectParams {
    ConnectParams {
        host: "127.0.0.1".into(),
        port,
        user: "root".into(),
        password: "dbytest".into(),
        database: "shop".into(),
        // Docker's servers use self-signed certificates.
        tls: TlsMode::EncryptOnly,
    }
}

pub async fn open(port: u16) -> Arc<Session> {
    open_session(params(port)).await.expect("open session")
}

/// A separate plain connection, for doing things behind the session's back.
pub async fn raw(port: u16) -> mysql_async::Conn {
    let _ = rustls::crypto::ring::default_provider().install_default();
    let opts = mysql_async::OptsBuilder::default()
        .ip_or_hostname("127.0.0.1")
        .tcp_port(port)
        .user(Some("root"))
        .pass(Some("dbytest"))
        .db_name(Some("shop"))
        .prefer_socket(false)
        .ssl_opts(
            mysql_async::SslOpts::default()
                .with_danger_accept_invalid_certs(true)
                .with_danger_skip_domain_validation(true),
        );
    mysql_async::Conn::new(opts).await.expect("raw connection")
}

/// Has the server drop one connection, the way an idle timeout or a restart would.
pub async fn kill(port: u16, connection_id: u32) {
    let mut conn = raw(port).await;
    conn.query_drop(format!("KILL {connection_id}")).await.expect("kill");
    conn.disconnect().await.expect("disconnect");
}

/// The first column of `sql`, as text, straight from the server.
pub async fn column(port: u16, sql: &str) -> Vec<String> {
    let mut conn = raw(port).await;
    let values: Vec<String> = conn.query(sql).await.expect("query");
    conn.disconnect().await.expect("disconnect");
    values
}
```

- [ ] **Step 5: Write the failing integration tests**

`core/tests/open.rs`:
```rust
mod common;

use common::{kill, open, params, targets};
use dby_core::{open_session, DbyError};

#[tokio::test]
async fn opens_and_reports_version() {
    for (name, port) in targets() {
        let session = open(port).await;
        let info = session.server_info();
        assert!(!info.version.is_empty(), "{name}: empty version");
        if name == "mariadb" {
            assert!(info.version.contains("MariaDB"), "{name}: {}", info.version);
        }
        session.disconnect().await;
    }
}

#[tokio::test]
async fn wrong_password_is_an_auth_error() {
    for (name, port) in targets() {
        let mut p = params(port);
        p.password = "wrong".into();
        let err = open_session(p).await.err().expect("should fail");
        assert!(matches!(err, DbyError::Auth { .. }), "{name}: {err:?}");
    }
}

#[tokio::test]
async fn unknown_database_is_reported_as_such() {
    for (name, port) in targets() {
        let mut p = params(port);
        p.database = "no_such_db".into();
        let err = open_session(p).await.err().expect("should fail");
        assert!(matches!(err, DbyError::UnknownDatabase { .. }), "{name}: {err:?}");
    }
}

#[tokio::test]
async fn closed_port_is_a_network_error() {
    // Nothing listens on port 1, so this needs no database.
    let err = open_session(params(1)).await.err().expect("should fail");
    assert!(matches!(err, DbyError::Network { .. }), "{err:?}");
}

#[tokio::test]
async fn ping_measures_a_round_trip() {
    for (name, port) in targets() {
        let session = open(port).await;
        let ms = session.ping().await.unwrap_or_else(|e| panic!("{name}: {e:?}"));
        assert!(ms < 5_000, "{name}: ping took {ms} ms");
    }
}

#[tokio::test]
async fn ping_reconnects_after_browse_connection_is_killed() {
    for (name, port) in targets() {
        let session = open(port).await;
        let (browse_id, _) = session.debug_connection_ids().await;
        kill(port, browse_id).await;
        session.ping().await.unwrap_or_else(|e| panic!("{name}: {e:?}"));
        let (new_id, _) = session.debug_connection_ids().await;
        assert_ne!(new_id, browse_id, "{name}: browse connection was not replaced");
    }
}
```

Add to `core/src/lib.rs`:
```rust
mod session;

pub use session::{open_session, ConnectParams, ServerInfo, Session, TlsMode};
```

- [ ] **Step 6: Run the tests to verify they fail**

Run: `(cd core && DBY_TEST_MYSQL_PORT=33084 DBY_TEST_MARIADB_PORT=33114 cargo test --test open -- --test-threads=1)`
Expected: compile error — `file not found for module session` / `unresolved import`.

- [ ] **Step 7: Write `core/src/session.rs`**

```rust
//! One server, two TCP connections: `browse` for metadata and table pages, `query` for the
//! user's own SQL, so a long query never blocks browsing (spec §6).

use std::collections::HashMap;
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{Arc, Mutex as StdMutex};
use std::time::{Duration, Instant};

use mysql_async::prelude::Queryable;
use mysql_async::{Conn, Opts, OptsBuilder, Row, SslOpts, Value};
use tokio::sync::Mutex;

use crate::error::{is_connection_lost, DbyError};
use crate::paging::{self, ColumnRow, TableMeta};

const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);

/// Run once at login on the query connection: the server stops after 1001 rows for any
/// top-level SELECT without its own LIMIT, so `SELECT *` never streams a whole table.
const QUERY_SETUP: &str = "SET SESSION sql_select_limit = 1001";

/// Amazon's RDS CA bundle, trusted in `TlsMode::Verify` alongside the public CAs.
static RDS_CA: &[u8] = include_bytes!("../certs/rds-global-bundle.pem");

/// The server version, then every column of every table in the current database with its
/// position in the primary key. One round trip.
// ponytail: the whole schema loads at open; M1 keeps it in the local cache and refreshes it
// in the background, which also removes this query from the connect time.
const SCHEMA_SQL: &str = "SELECT VERSION(); \
    SELECT c.TABLE_NAME, c.COLUMN_NAME, c.DATA_TYPE, c.CHARACTER_MAXIMUM_LENGTH, s.SEQ_IN_INDEX \
    FROM information_schema.COLUMNS c \
    LEFT JOIN information_schema.STATISTICS s \
      ON s.TABLE_SCHEMA = c.TABLE_SCHEMA AND s.TABLE_NAME = c.TABLE_NAME \
     AND s.COLUMN_NAME = c.COLUMN_NAME AND s.INDEX_NAME = 'PRIMARY' \
    WHERE c.TABLE_SCHEMA = DATABASE() \
    ORDER BY c.TABLE_NAME, c.ORDINAL_POSITION";

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum TlsMode {
    /// Encrypted; certificate checked against public CAs and the Amazon RDS bundle.
    Verify,
    /// Encrypted; certificate not checked. Shown as "Encrypted, not verified".
    EncryptOnly,
    /// No TLS. Shown as "Not encrypted".
    Off,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ConnectParams {
    pub host: String,
    pub port: u16,
    pub user: String,
    pub password: String,
    pub database: String,
    pub tls: TlsMode,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ServerInfo {
    pub version: String,
    /// Both logins plus the schema load, in milliseconds.
    pub connect_ms: u32,
}

#[derive(uniffi::Object)]
pub struct Session {
    browse_opts: Opts,
    query_opts: Opts,
    browse: Mutex<Option<Conn>>,
    query: Mutex<Option<Conn>>,
    /// Server-side id of the query connection, for `KILL QUERY` sent over browse.
    query_conn_id: AtomicU32,
    /// `run_id` of the user SQL in flight, if any.
    running: StdMutex<Option<String>>,
    tables: StdMutex<HashMap<String, Arc<TableMeta>>>,
    info: ServerInfo,
}

/// Logs in on both connections at once and loads the schema on browse while query is still
/// logging in.
#[uniffi::export(async_runtime = "tokio")]
pub async fn open_session(params: ConnectParams) -> Result<Arc<Session>, DbyError> {
    // rustls needs one process-wide crypto provider; ring is the only one compiled in.
    let _ = rustls::crypto::ring::default_provider().install_default();
    let started = Instant::now();
    let browse_opts = build_opts(&params, None);
    let query_opts = build_opts(&params, Some(QUERY_SETUP));
    let ((browse, version, tables), query) = tokio::try_join!(
        async {
            let mut conn = connect(&browse_opts).await?;
            let (version, tables) = load_schema(&mut conn).await?;
            Ok::<_, DbyError>((conn, version, tables))
        },
        connect(&query_opts),
    )?;
    Ok(Arc::new(Session {
        query_conn_id: AtomicU32::new(query.id()),
        browse: Mutex::new(Some(browse)),
        query: Mutex::new(Some(query)),
        running: StdMutex::new(None),
        tables: StdMutex::new(tables),
        info: ServerInfo { version, connect_ms: elapsed_ms(started) },
        browse_opts,
        query_opts,
    }))
}

#[uniffi::export(async_runtime = "tokio")]
impl Session {
    pub fn server_info(&self) -> ServerInfo {
        self.info.clone()
    }

    /// Round trip on browse, in milliseconds. Reconnects first if the connection was dropped,
    /// which is what the app calls on resume.
    pub async fn ping(&self) -> Result<u32, DbyError> {
        let started = Instant::now();
        let mut guard = self.browse.lock().await;
        let lost = match guard.as_mut() {
            Some(conn) => match conn.ping().await {
                Ok(()) => false,
                Err(e) if is_connection_lost(&e) => true,
                Err(e) => return Err(e.into()),
            },
            None => return Err(closed()),
        };
        if lost {
            *guard = Some(connect(&self.browse_opts).await?);
        }
        Ok(elapsed_ms(started))
    }

    pub async fn disconnect(&self) {
        let query = self.query.lock().await.take();
        let browse = self.browse.lock().await.take();
        for conn in [query, browse].into_iter().flatten() {
            let _ = conn.disconnect().await;
        }
    }
}

impl Session {
    /// Server-side ids of (browse, query), for tests that kill connections. Not exported.
    #[doc(hidden)]
    pub async fn debug_connection_ids(&self) -> (u32, u32) {
        let browse = self.browse.lock().await.as_ref().map_or(0, Conn::id);
        (browse, self.query_conn_id.load(Ordering::SeqCst))
    }
}

fn build_opts(p: &ConnectParams, setup: Option<&str>) -> Opts {
    let ssl = match p.tls {
        TlsMode::Off => None,
        TlsMode::Verify => Some(SslOpts::default().with_root_certs(vec![RDS_CA.into()])),
        TlsMode::EncryptOnly => Some(
            SslOpts::default()
                .with_danger_accept_invalid_certs(true)
                .with_danger_skip_domain_validation(true),
        ),
    };
    OptsBuilder::default()
        .ip_or_hostname(p.host.clone())
        .tcp_port(p.port)
        .user(Some(p.user.clone()))
        .pass(Some(p.password.clone()))
        .db_name(Some(p.database.clone()))
        .prefer_socket(false)
        .tcp_nodelay(true)
        .tcp_keepalive(Some(Duration::from_secs(30)))
        .ssl_opts(ssl)
        .init(setup.map(|s| vec![s.to_string()]).unwrap_or_default())
        .into()
}

pub(crate) async fn connect(opts: &Opts) -> Result<Conn, DbyError> {
    match tokio::time::timeout(CONNECT_TIMEOUT, Conn::new(opts.clone())).await {
        Ok(conn) => Ok(conn?),
        Err(_) => Err(DbyError::Timeout),
    }
}

async fn load_schema(conn: &mut Conn) -> Result<(String, HashMap<String, Arc<TableMeta>>), DbyError> {
    let mut result = conn.query_iter(SCHEMA_SQL).await?;
    let version: Vec<Row> = result.collect().await?;
    let columns: Vec<Row> = result.collect().await?;
    result.drop_result().await?;
    let version = version.first().and_then(|r| text_at(r, 0)).unwrap_or_default();
    let rows = columns
        .iter()
        .map(|r| ColumnRow {
            table: text_at(r, 0).unwrap_or_default(),
            column: text_at(r, 1).unwrap_or_default(),
            data_type: text_at(r, 2).unwrap_or_default(),
            max_len: text_at(r, 3).and_then(|s| s.parse().ok()),
            pk_seq: text_at(r, 4).and_then(|s| s.parse().ok()),
        })
        .collect();
    Ok((version, paging::build_tables(rows)))
}

fn text_at(row: &Row, index: usize) -> Option<String> {
    match row.as_ref(index)? {
        Value::Bytes(b) => Some(String::from_utf8_lossy(b).into_owned()),
        _ => None,
    }
}

pub(crate) fn elapsed_ms(started: Instant) -> u32 {
    u32::try_from(started.elapsed().as_millis()).unwrap_or(u32::MAX)
}

pub(crate) fn closed() -> DbyError {
    DbyError::internal("session is disconnected")
}
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `(cd core && DBY_TEST_MYSQL_PORT=33084 DBY_TEST_MARIADB_PORT=33114 cargo test --test open -- --test-threads=1)`
Expected: `test result: ok. 6 passed`, with no `skipping` lines in the output.

- [ ] **Step 9: Confirm the Android build still links**

Run: `(cd core && cargo ndk -t arm64-v8a --platform 26 -o target/jniLibs build --release)`
Expected: `Finished`.

- [ ] **Step 10: Commit and push**

```bash
git add core/src/session.rs core/src/lib.rs core/certs core/tests
git commit -m "feat(core): open a two-connection session and load the schema in one round trip"
git push
```

---

### Task 5: Table pages

**Files:**
- Modify: `core/src/session.rs` (add types, an exported impl block, helpers)
- Modify: `core/src/lib.rs`
- Create: `core/tests/pages.rs`

**Interfaces:**
- Consumes: `paging::{page_sql, next_cursor, Cursor}` (Task 3); `value::{meta_of, decode, as_len, ColMeta, Cell}` (Task 2); `connect`, `closed`, `elapsed_ms` (Task 4).
- Produces:
  - `pub struct ColumnOut { pub name: String, pub type_name: String }` (`uniffi::Record`)
  - `pub struct PageRequest { pub table: String, pub cursor: Option<Cursor>, pub limit: u32 }` (`uniffi::Record`)
  - `pub struct Page { pub columns: Vec<ColumnOut>, pub rows: Vec<Vec<Cell>>, pub next: Option<Cursor>, pub elapsed_ms: u32 }` (`uniffi::Record`)
  - exported `async fn table_page(&self, req: PageRequest) -> Result<Page, DbyError>`
  - private `async fn read_browse(&self, sql: &str)` and `async fn fetch_all(conn, sql)` (Task 6 reuses `fetch_all`'s pattern but not the function)

- [ ] **Step 1: Write the failing tests**

`core/tests/pages.rs`:
```rust
mod common;

use common::{column, open, targets};
use dby_core::{Cell, Cursor, DbyError, PageRequest, Session};

async fn all_rows(session: &Session, table: &str, limit: u32) -> Vec<Vec<Cell>> {
    let mut cursor: Option<Cursor> = None;
    let mut rows = Vec::new();
    for _ in 0..10_000 {
        let page = session
            .table_page(PageRequest { table: table.into(), cursor: cursor.clone(), limit })
            .await
            .expect("page");
        assert!(page.rows.len() <= limit as usize);
        rows.extend(page.rows);
        match page.next {
            Some(next) => cursor = Some(next),
            None => return rows,
        }
    }
    panic!("paging through {table} did not finish");
}

fn text(cell: &Cell) -> String {
    match cell {
        Cell::Text { v, .. } => v.clone(),
        other => panic!("expected text, got {other:?}"),
    }
}

#[tokio::test]
async fn pages_through_every_order_once() {
    for (name, port) in targets() {
        let session = open(port).await;
        let ids: Vec<u64> = all_rows(&session, "orders", 1000)
            .await
            .iter()
            .map(|r| match r[0] {
                Cell::Unsigned { v } => v,
                ref other => panic!("{name}: id was {other:?}"),
            })
            .collect();
        assert_eq!(ids, (1..=100_000).collect::<Vec<u64>>(), "{name}");
    }
}

#[tokio::test]
async fn long_text_is_trimmed_with_its_full_length() {
    for (name, port) in targets() {
        let session = open(port).await;
        let page = session
            .table_page(PageRequest { table: "orders".into(), cursor: None, limit: 50 })
            .await
            .unwrap();
        let names: Vec<&str> = page.columns.iter().map(|c| c.name.as_str()).collect();
        assert_eq!(names, ["id", "customer", "status", "total", "notes", "created_at"], "{name}");
        assert_eq!(page.rows.len(), 50, "{name}");
        assert!(page.next.is_some(), "{name}");
        assert_eq!(page.rows[0][3], Cell::Exact { v: "0.01".into() }, "{name}");
        assert_eq!(page.rows[0][4], Cell::Null, "{name}");
        assert_eq!(page.rows[0][5], Cell::Temporal { v: "2026-01-01 00:01:00".into() }, "{name}");
        // id 10 is the first row with a 5000-character note.
        assert_eq!(page.rows[9][4], Cell::Text { v: "x".repeat(256), full_len: 5000 }, "{name}");
    }
}

#[tokio::test]
async fn composite_key_pages_cover_all_pairs() {
    for (name, port) in targets() {
        let session = open(port).await;
        let keys: Vec<(i64, i64)> = all_rows(&session, "pairs", 7)
            .await
            .iter()
            .map(|r| match (&r[0], &r[1]) {
                (Cell::Signed { v: a }, Cell::Signed { v: b }) => (*a, *b),
                other => panic!("{name}: key was {other:?}"),
            })
            .collect();
        let expected: Vec<(i64, i64)> = (0..10).flat_map(|a| (0..10).map(move |b| (a, b))).collect();
        assert_eq!(keys, expected, "{name}");
    }
}

#[tokio::test]
async fn text_key_paging_follows_column_collation() {
    for (name, port) in targets() {
        let session = open(port).await;
        let expected = column(port, "SELECT name FROM tags ORDER BY name").await;
        let got: Vec<String> = all_rows(&session, "tags", 3).await.iter().map(|r| text(&r[0])).collect();
        assert_eq!(got, expected, "{name}");
    }
}

#[tokio::test]
async fn table_without_key_pages_by_offset() {
    for (name, port) in targets() {
        let session = open(port).await;
        let mut values: Vec<i64> = all_rows(&session, "nopk", 30)
            .await
            .iter()
            .map(|r| match r[0] {
                Cell::Signed { v } => v,
                ref other => panic!("{name}: {other:?}"),
            })
            .collect();
        values.sort_unstable();
        assert_eq!(values, (0..100).collect::<Vec<i64>>(), "{name}");
    }
}

#[tokio::test]
async fn exact_values_survive() {
    for (name, port) in targets() {
        let session = open(port).await;
        let page = session
            .table_page(PageRequest { table: "exact_values".into(), cursor: None, limit: 10 })
            .await
            .unwrap();
        let row = &page.rows[0];
        assert_eq!(row[1], Cell::Unsigned { v: u64::MAX }, "{name}");
        assert_eq!(row[2], Cell::Signed { v: i64::MIN }, "{name}");
        assert_eq!(
            row[3],
            Cell::Exact { v: "12345678901234567890123456789012345.123456789012345678901234567890".into() },
            "{name}"
        );
        assert_eq!(row[4], Cell::Bytes { len: 2, preview: vec![0x00, 0xff] }, "{name}");
        match &row[5] {
            Cell::Bytes { len, preview } => {
                assert_eq!(*len, 1000, "{name}");
                assert_eq!(preview.len(), 256, "{name}");
            }
            other => panic!("{name}: blob was {other:?}"),
        }
        assert_eq!(row[6], Cell::Temporal { v: "0000-00-00 00:00:00".into() }, "{name}");
    }
}

#[tokio::test]
async fn unknown_table_is_not_found() {
    for (name, port) in targets() {
        let session = open(port).await;
        let err = session
            .table_page(PageRequest { table: "no_such_table".into(), cursor: None, limit: 10 })
            .await
            .err()
            .expect("should fail");
        assert!(matches!(err, DbyError::NotFound { .. }), "{name}: {err:?}");
    }
}
```

Change the `session` line in `core/src/lib.rs` to:
```rust
pub use session::{open_session, ColumnOut, ConnectParams, Page, PageRequest, ServerInfo, Session, TlsMode};
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `(cd core && DBY_TEST_MYSQL_PORT=33084 DBY_TEST_MARIADB_PORT=33114 cargo test --test pages -- --test-threads=1)`
Expected: compile errors — `no ColumnOut in session`, `no method named table_page`.

- [ ] **Step 3: Write the implementation**

In `core/src/session.rs`, extend the imports:
```rust
use crate::paging::{self, ColumnRow, Cursor, TableMeta};
use crate::value::{self, Cell, ColMeta};
```

Add after the `ServerInfo` struct:
```rust
/// Largest page the app may ask for.
const MAX_PAGE: u32 = 1000;

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ColumnOut {
    pub name: String,
    pub type_name: String,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct PageRequest {
    pub table: String,
    /// None for the first page; afterwards the previous page's `next`.
    pub cursor: Option<Cursor>,
    pub limit: u32,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Page {
    pub columns: Vec<ColumnOut>,
    pub rows: Vec<Vec<Cell>>,
    /// None on the last page.
    pub next: Option<Cursor>,
    pub elapsed_ms: u32,
}
```

Add a new exported block after the existing `#[uniffi::export(async_runtime = "tokio")] impl Session { ... }`:
```rust
#[uniffi::export(async_runtime = "tokio")]
impl Session {
    /// One page of a table in primary-key order: one round trip, long cells trimmed.
    pub async fn table_page(&self, req: PageRequest) -> Result<Page, DbyError> {
        let started = Instant::now();
        let limit = req.limit.clamp(1, MAX_PAGE);
        let meta = self
            .tables
            .lock()
            .unwrap()
            .get(&req.table)
            .cloned()
            .ok_or_else(|| DbyError::NotFound { detail: format!("table {}", req.table) })?;
        let built = paging::page_sql(&req.table, &meta, req.cursor.as_ref(), limit)?;
        let (columns, raw) = self.read_browse(&built.sql).await?;
        let metas: Vec<ColMeta> = columns.iter().map(value::meta_of).collect();
        let mut rows: Vec<Vec<Cell>> = raw.into_iter().map(|row| decode_page_row(row, &metas, &built.trimmed)).collect();
        let next = if rows.len() > limit as usize {
            rows.truncate(limit as usize);
            paging::next_cursor(&meta, req.cursor.as_ref(), &rows, limit)
        } else {
            None
        };
        Ok(Page {
            columns: meta.columns.iter().map(|c| ColumnOut { name: c.name.clone(), type_name: c.data_type.clone() }).collect(),
            rows,
            next,
            elapsed_ms: elapsed_ms(started),
        })
    }
}
```

Add inside the existing non-exported `impl Session { ... }` block (next to `debug_connection_ids`):
```rust
    /// A read on browse. If the server or network dropped the connection, reconnects once and
    /// retries: every statement browse runs is a read.
    async fn read_browse(&self, sql: &str) -> Result<(Vec<mysql_async::Column>, Vec<Row>), DbyError> {
        let mut guard = self.browse.lock().await;
        let first = match guard.as_mut() {
            Some(conn) => fetch_all(conn, sql).await,
            None => return Err(closed()),
        };
        match first {
            Ok(found) => Ok(found),
            Err(e) if is_connection_lost(&e) => {
                let mut conn = connect(&self.browse_opts).await?;
                let found = fetch_all(&mut conn, sql).await;
                *guard = Some(conn);
                Ok(found?)
            }
            Err(e) => Err(e.into()),
        }
    }
```

Add at the bottom of the file (above `text_at`):
```rust
async fn fetch_all(conn: &mut Conn, sql: &str) -> Result<(Vec<mysql_async::Column>, Vec<Row>), mysql_async::Error> {
    let mut result = conn.query_iter(sql).await?;
    let columns = result.columns().map(|c| c.to_vec()).unwrap_or_default();
    let rows: Vec<Row> = result.collect().await?;
    result.drop_result().await?;
    Ok((columns, rows))
}

/// Folds each trimmed column's (value, full length) pair back into one cell.
fn decode_page_row(row: Row, metas: &[ColMeta], trimmed: &[bool]) -> Vec<Cell> {
    let mut values = row.unwrap().into_iter();
    let mut metas = metas.iter();
    trimmed
        .iter()
        .map(|&is_trimmed| {
            let value = values.next().unwrap_or(Value::NULL);
            let meta = metas.next().copied().unwrap_or(ColMeta::TEXT);
            let full_len = if is_trimmed {
                metas.next();
                values.next().as_ref().and_then(value::as_len)
            } else {
                None
            };
            value::decode(value, &meta, full_len, false)
        })
        .collect()
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `(cd core && DBY_TEST_MYSQL_PORT=33084 DBY_TEST_MARIADB_PORT=33114 cargo test --test pages -- --test-threads=1)`
Expected: `test result: ok. 7 passed`.

- [ ] **Step 5: Run everything so far**

Run: `(cd core && DBY_TEST_MYSQL_PORT=33084 DBY_TEST_MARIADB_PORT=33114 cargo test -- --test-threads=1)`
Expected: all unit, `open` and `pages` tests pass.

- [ ] **Step 6: Commit and push**

```bash
git add core/src/session.rs core/src/lib.rs core/tests/pages.rs
git commit -m "feat(core): one-round-trip keyset table pages with trimmed cells"
git push
```

---

### Task 6: User SQL with a row cap, cancel, and safe reconnect

**Files:**
- Modify: `core/src/session.rs`
- Modify: `core/src/lib.rs`
- Create: `core/tests/sql.rs`

**Interfaces:**
- Consumes: everything in `session.rs` from Tasks 4–5; `value::{meta_of, decode, ColMeta}`.
- Produces:
  - `pub struct QueryResult { pub columns: Vec<ColumnOut>, pub rows: Vec<Vec<Cell>>, pub affected_rows: u64, pub truncated: bool, pub elapsed_ms: u32 }` (`uniffi::Record`)
  - exported `async fn run_sql(&self, run_id: String, sql: String) -> Result<QueryResult, DbyError>`
  - exported `async fn cancel(&self, run_id: String) -> Result<(), DbyError>`
  - private `fn is_read(sql: &str) -> bool`, `async fn kill_query(&self)`, `async fn stream_capped(...)`

- [ ] **Step 1: Write the failing integration tests**

`core/tests/sql.rs`:
```rust
mod common;

use std::time::{Duration, Instant};

use common::{kill, open, raw, targets};
use dby_core::{Cell, DbyError};
use mysql_async::prelude::Queryable;

#[tokio::test]
async fn select_without_limit_stops_at_1000_rows() {
    for (name, port) in targets() {
        let session = open(port).await;
        let r = session.run_sql("r1".into(), "SELECT id FROM orders".into()).await.unwrap();
        assert_eq!(r.rows.len(), 1000, "{name}");
        assert!(r.truncated, "{name}");
        let ok = session.run_sql("r2".into(), "SELECT 1".into()).await.unwrap();
        assert_eq!(ok.rows, vec![vec![Cell::Signed { v: 1 }]], "{name}");
    }
}

#[tokio::test]
async fn explicit_huge_limit_is_cut_and_connection_survives() {
    for (name, port) in targets() {
        let session = open(port).await;
        let r = session
            .run_sql("big".into(), "SELECT id, notes FROM orders ORDER BY id LIMIT 100000".into())
            .await
            .unwrap();
        assert_eq!(r.rows.len(), 1000, "{name}");
        assert!(r.truncated, "{name}");
        assert_eq!(r.rows[9][1], Cell::Text { v: "x".repeat(256), full_len: 5000 }, "{name}");
        let count = session.run_sql("count".into(), "SELECT COUNT(*) FROM orders".into()).await.unwrap();
        assert_eq!(count.rows, vec![vec![Cell::Signed { v: 100_000 }]], "{name}");
    }
}

#[tokio::test]
async fn small_result_is_not_truncated() {
    for (name, port) in targets() {
        let session = open(port).await;
        let r = session.run_sql("s".into(), "SELECT id FROM orders ORDER BY id LIMIT 5".into()).await.unwrap();
        assert_eq!(r.rows.len(), 5, "{name}");
        assert!(!r.truncated, "{name}");
        assert_eq!(r.rows[0], vec![Cell::Unsigned { v: 1 }], "{name}");
        assert_eq!(r.columns[0].name, "id", "{name}");
    }
}

#[tokio::test]
async fn writes_report_affected_rows() {
    for (name, port) in targets() {
        let session = open(port).await;
        session.run_sql("w1".into(), "CREATE TEMPORARY TABLE scratch (x INT)".into()).await.unwrap();
        let r = session.run_sql("w2".into(), "INSERT INTO scratch VALUES (1), (2), (3)".into()).await.unwrap();
        assert_eq!(r.affected_rows, 3, "{name}");
        assert!(r.columns.is_empty(), "{name}");
    }
}

#[tokio::test]
async fn cancel_interrupts_a_long_query() {
    for (name, port) in targets() {
        let session = open(port).await;
        let runner = session.clone();
        let started = Instant::now();
        let task = tokio::spawn(async move {
            runner
                .run_sql("slow".into(), "SELECT COUNT(*) FROM orders a, orders b WHERE a.id < b.id".into())
                .await
        });
        tokio::time::sleep(Duration::from_millis(500)).await;
        session.cancel("slow".into()).await.unwrap();
        let outcome = task.await.unwrap();
        assert!(matches!(outcome, Err(DbyError::Cancelled)), "{name}: {outcome:?}");
        assert!(started.elapsed() < Duration::from_secs(10), "{name}: cancel took {:?}", started.elapsed());
        let ok = session.run_sql("after".into(), "SELECT 1".into()).await.unwrap();
        assert_eq!(ok.rows.len(), 1, "{name}");
    }
}

#[tokio::test]
async fn cancelling_an_unknown_run_is_a_no_op() {
    for (_, port) in targets() {
        let session = open(port).await;
        session.cancel("nothing-running".into()).await.unwrap();
    }
}

#[tokio::test]
async fn read_is_retried_after_the_server_drops_the_connection() {
    for (name, port) in targets() {
        let session = open(port).await;
        let (_, query_id) = session.debug_connection_ids().await;
        kill(port, query_id).await;
        let r = session
            .run_sql("again".into(), "SELECT 2".into())
            .await
            .unwrap_or_else(|e| panic!("{name}: {e:?}"));
        assert_eq!(r.rows, vec![vec![Cell::Signed { v: 2 }]], "{name}");
        let (_, new_id) = session.debug_connection_ids().await;
        assert_ne!(new_id, query_id, "{name}");
    }
}

#[tokio::test]
async fn write_is_not_retried_after_the_server_drops_the_connection() {
    for (name, port) in targets() {
        let session = open(port).await;
        let mut setup = raw(port).await;
        setup.query_drop("CREATE TABLE IF NOT EXISTS write_once (x INT)").await.unwrap();
        setup.query_drop("DELETE FROM write_once").await.unwrap();
        let (_, query_id) = session.debug_connection_ids().await;
        kill(port, query_id).await;
        let failed = session.run_sql("w".into(), "INSERT INTO write_once VALUES (1)".into()).await;
        assert!(failed.is_err(), "{name}: a write after a dropped connection must fail, got {failed:?}");
        let count: Vec<i64> = setup.query("SELECT COUNT(*) FROM write_once").await.unwrap();
        assert_eq!(count, vec![0], "{name}: the write ran anyway");
        let r = session.run_sql("r".into(), "SELECT 3".into()).await.unwrap();
        assert_eq!(r.rows, vec![vec![Cell::Signed { v: 3 }]], "{name}");
    }
}
```

Change the `session` line in `core/src/lib.rs` to:
```rust
pub use session::{
    open_session, ColumnOut, ConnectParams, Page, PageRequest, QueryResult, ServerInfo, Session, TlsMode,
};
```

- [ ] **Step 2: Write the failing unit test for `is_read`**

Append to `core/src/session.rs`:
```rust
#[cfg(test)]
mod tests {
    use super::is_read;

    #[test]
    fn only_plain_reads_are_retryable() {
        for sql in ["SELECT 1", "  select * from t", "SHOW TABLES", "describe t", "DESC t", "EXPLAIN SELECT 1", "SELECT(1)"] {
            assert!(is_read(sql), "{sql}");
        }
        for sql in ["INSERT INTO t VALUES (1)", "UPDATE t SET x = 1", "WITH a AS (SELECT 1) DELETE FROM t", "/* c */ SELECT 1", "CALL p()", ""] {
            assert!(!is_read(sql), "{sql}");
        }
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `(cd core && DBY_TEST_MYSQL_PORT=33084 DBY_TEST_MARIADB_PORT=33114 cargo test --test sql -- --test-threads=1)`
Expected: compile errors — `no QueryResult in session`, `no method named run_sql`.

- [ ] **Step 4: Write the implementation**

In `core/src/session.rs`, add the import:
```rust
use futures_util::StreamExt;
```

Add after the `Page` struct:
```rust
/// Rows the user's own SQL may return before the core stops reading.
const ROW_CAP: usize = 1000;

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct QueryResult {
    pub columns: Vec<ColumnOut>,
    pub rows: Vec<Vec<Cell>>,
    pub affected_rows: u64,
    /// True when the result had more than 1000 rows and the rest were not read.
    pub truncated: bool,
    pub elapsed_ms: u32,
}

struct Capped {
    columns: Vec<ColumnOut>,
    rows: Vec<Vec<Cell>>,
    affected_rows: u64,
    truncated: bool,
}
```

Add a new exported block:
```rust
#[uniffi::export(async_runtime = "tokio")]
impl Session {
    /// Runs the user's SQL on the query connection. Stops at 1000 rows; `truncated` says so.
    pub async fn run_sql(&self, run_id: String, sql: String) -> Result<QueryResult, DbyError> {
        let started = Instant::now();
        *self.running.lock().unwrap() = Some(run_id);
        let outcome = self.run_on_query(&sql).await;
        *self.running.lock().unwrap() = None;
        let capped = outcome?;
        Ok(QueryResult {
            columns: capped.columns,
            rows: capped.rows,
            affected_rows: capped.affected_rows,
            truncated: capped.truncated,
            elapsed_ms: elapsed_ms(started),
        })
    }

    /// Interrupts `run_id` if it is still running. A finished or unknown run is a no-op.
    pub async fn cancel(&self, run_id: String) -> Result<(), DbyError> {
        let is_running = self.running.lock().unwrap().as_deref() == Some(run_id.as_str());
        if is_running {
            self.kill_query().await?;
        }
        Ok(())
    }
}
```

Add inside the non-exported `impl Session { ... }` block:
```rust
    async fn run_on_query(&self, sql: &str) -> Result<Capped, DbyError> {
        let mut guard = self.query.lock().await;
        let first = match guard.as_mut() {
            Some(conn) => self.stream_capped(conn, sql).await,
            None => return Err(closed()),
        };
        match first {
            Ok(capped) => Ok(capped),
            // Only reads are re-run: a write may already have committed when the connection died.
            Err(e) if is_connection_lost(&e) && is_read(sql) => {
                let mut conn = connect(&self.query_opts).await?;
                self.query_conn_id.store(conn.id(), Ordering::SeqCst);
                let capped = self.stream_capped(&mut conn, sql).await;
                *guard = Some(conn);
                Ok(capped?)
            }
            Err(e) => Err(e.into()),
        }
    }

    async fn stream_capped(&self, conn: &mut Conn, sql: &str) -> Result<Capped, mysql_async::Error> {
        let mut result = conn.query_iter(sql).await?;
        let columns = result.columns().map(|c| c.to_vec()).unwrap_or_default();
        let metas: Vec<ColMeta> = columns.iter().map(value::meta_of).collect();
        let mut rows = Vec::new();
        let mut truncated = false;
        if !columns.is_empty() {
            if let Some(mut stream) = result.stream::<Row>().await? {
                while let Some(row) = stream.next().await {
                    let row = row?;
                    if rows.len() == ROW_CAP {
                        truncated = true;
                        break;
                    }
                    rows.push(row.unwrap().into_iter().zip(&metas).map(|(v, m)| value::decode(v, m, None, true)).collect());
                }
            }
        }
        let affected_rows = result.affected_rows();
        if truncated {
            // Stop the server instead of draining the rest of a huge result over mobile data.
            let _ = self.kill_query().await;
        }
        match result.drop_result().await {
            Err(mysql_async::Error::Server(e)) if truncated && e.code == 1317 => {}
            other => other?,
        }
        Ok(Capped { columns: columns.iter().map(column_out).collect(), rows, affected_rows, truncated })
    }

    /// `KILL QUERY` for the query connection, sent over browse.
    async fn kill_query(&self) -> Result<(), DbyError> {
        let id = self.query_conn_id.load(Ordering::SeqCst);
        let mut guard = self.browse.lock().await;
        let conn = guard.as_mut().ok_or_else(closed)?;
        conn.query_drop(format!("KILL QUERY {id}")).await?;
        Ok(())
    }
```

Add at the bottom of the file (above the tests module):
```rust
fn column_out(col: &mysql_async::Column) -> ColumnOut {
    let type_name = format!("{:?}", col.column_type());
    ColumnOut {
        name: col.name_str().into_owned(),
        type_name: type_name.trim_start_matches("MYSQL_TYPE_").to_ascii_lowercase(),
    }
}

/// True only for statements that cannot change data, so re-running one after a dropped
/// connection is safe. `WITH` is excluded because `WITH ... DELETE` exists.
// ponytail: first-keyword check; M1's sqlparser classifier replaces it.
fn is_read(sql: &str) -> bool {
    let first = sql.trim_start().split(|c: char| c.is_whitespace() || c == '(').next().unwrap_or("");
    ["select", "show", "describe", "desc", "explain"].iter().any(|k| first.eq_ignore_ascii_case(k))
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `(cd core && DBY_TEST_MYSQL_PORT=33084 DBY_TEST_MARIADB_PORT=33114 cargo test -- --test-threads=1)`
Expected: every test in `value`, `paging`, `session::tests`, `open`, `pages` and `sql` passes.

- [ ] **Step 6: Lint**

Run: `(cd core && cargo clippy --all-targets -- -D warnings)`
Expected: no warnings. Fix any it reports before committing.

- [ ] **Step 7: Commit and push**

```bash
git add core/src/session.rs core/src/lib.rs core/tests/sql.rs
git commit -m "feat(core): user SQL with a 1000-row cap, cancel, and read-only retry"
git push
```

---

### Task 7: Android project that builds the core and calls it

**Files:**
- Create: `android/settings.gradle.kts`, `android/build.gradle.kts`, `android/gradle.properties`
- Create (copied): `android/gradlew`, `android/gradlew.bat`, `android/gradle/wrapper/gradle-wrapper.jar`, `android/gradle/wrapper/gradle-wrapper.properties`
- Create: `android/app/build.gradle.kts`, `android/app/proguard-rules.pro`, `android/app/src/main/AndroidManifest.xml`, `android/app/src/main/java/com/dby/mobile/MainActivity.kt`

**Interfaces:**
- Consumes: the core crate and bindgen command (Task 1), every exported item (Tasks 2–6).
- Produces: Gradle tasks `buildRustCore` and `generateBindings` (run before `preBuild`); generated Kotlin in package `com.dby.core` with `coreVersion()`, `openSession(ConnectParams)`, class `Session` (`serverInfo()`, `ping()`, `tablePage(PageRequest)`, `runSql(runId, sql)`, `cancel(runId)`, `disconnect()`), records `ConnectParams(host, port: UShort, user, password, database, tls)`, `PageRequest(table, cursor, limit: UInt)`, `Page(columns, rows, next, elapsedMs)`, `QueryResult(columns, rows, affectedRows, truncated, elapsedMs)`, `ColumnOut(name, typeName)`, `Cursor`, enum `TlsMode { VERIFY, ENCRYPT_ONLY, OFF }`, sealed `Cell`, exception `DbyException`.

- [ ] **Step 1: Copy the Gradle 9.7.1 wrapper from the Reyaak project**

```bash
mkdir -p android/gradle/wrapper
cp ../Reyaak/gradlew ../Reyaak/gradlew.bat android/
cp ../Reyaak/gradle/wrapper/gradle-wrapper.jar ../Reyaak/gradle/wrapper/gradle-wrapper.properties android/gradle/wrapper/
grep distributionUrl android/gradle/wrapper/gradle-wrapper.properties
```
Expected: `distributionUrl=https\://services.gradle.org/distributions/gradle-9.7.1-bin.zip`.

- [ ] **Step 2: Write the root build files**

`android/settings.gradle.kts`:
```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "DBY"
include(":app")
```

`android/build.gradle.kts`:
```kotlin
buildscript {
    dependencies {
        // Pins the Kotlin version AGP 9's built-in Kotlin compiles with.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    }
}

plugins {
    id("com.android.application") version "9.3.2" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}
```

`android/gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official
```

- [ ] **Step 3: Write `android/app/build.gradle.kts`**

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.dby.mobile"
    compileSdk = 36
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "com.dby.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1-m0"
        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // M0 benchmark builds only. M3 adds the real release key.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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

val buildRustCore by tasks.registering(Exec::class) {
    description = "Builds core/ for arm64-v8a and x86_64 into src/main/jniLibs."
    workingDir = coreDir
    environment("ANDROID_NDK_HOME", "${System.getenv("ANDROID_HOME")}/ndk/30.0.16248370")
    commandLine(
        "cargo", "ndk", "-t", "arm64-v8a", "-t", "x86_64", "--platform", "26",
        "-o", jniLibsDir.absolutePath, "build", "--release",
    )
}

val generateBindings by tasks.registering(Exec::class) {
    description = "Generates the Kotlin bindings for core/ into src/main/java/com/dby/core."
    dependsOn(buildRustCore)
    workingDir = coreDir
    commandLine(
        "cargo", "run", "-p", "uniffi-bindgen", "--", "generate",
        "--library", jniLibsDir.resolve("arm64-v8a/libdby_core.so").absolutePath,
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
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // UniFFI's Kotlin bindings call the Rust library through JNA.
    implementation("net.java.dev.jna:jna:5.19.1@aar")
    implementation("dev.chrisbanes.haze:haze:2.0.1")
    implementation("dev.chrisbanes.haze:haze-blur:2.0.1")
}
```

- [ ] **Step 4: Write `android/app/proguard-rules.pro`**

```
# JNA reaches its own classes and the generated bindings by reflection.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class com.dby.core.** { *; }
-dontwarn java.awt.**
```

- [ ] **Step 5: Write `android/app/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />

    <application
        android:allowBackup="false"
        android:label="DBY"
        android:theme="@android:style/Theme.Material.NoActionBar">
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

- [ ] **Step 6: Write `android/app/src/main/java/com/dby/mobile/MainActivity.kt`**

```kotlin
package com.dby.mobile

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import com.dby.core.coreVersion

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val version = coreVersion()
        Log.i("DBYBENCH", "app=dby event=boot core=$version")
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Text("DBY core $version") } }
    }
}
```

- [ ] **Step 7: Build the debug APK**

Run: `(cd android && ./gradlew :app:assembleDebug)`
Expected: `BUILD SUCCESSFUL`, and `android/app/src/main/java/com/dby/core/dby_core.kt` now exists.

- [ ] **Step 8: Check the APK carries both native libraries**

Run: `unzip -l android/app/build/outputs/apk/debug/app-debug.apk | grep -E "libdby_core.so|libjnidispatch.so"`
Expected: `lib/arm64-v8a/libdby_core.so`, `lib/x86_64/libdby_core.so` and JNA's `libjnidispatch.so` for both ABIs.

- [ ] **Step 9: Smoke-test on a device, if one is attached**

Run: `adb devices`. If a device or emulator is listed:
```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
adb shell am start -n com.dby.mobile/.MainActivity
sleep 3
adb logcat -d -s DBYBENCH:I
```
Expected: a line containing `app=dby event=boot core=0.1.0`. With no device, skip this step and say so in the task report; Task 9 runs on the phone.

- [ ] **Step 10: Commit and push**

```bash
git add android
git commit -m "build(android): Compose app that builds the Rust core and calls it through UniFFI"
git push
```

---

### Task 8: Throwaway benchmark screen

**Files:**
- Create: `android/app/src/main/java/com/dby/mobile/BenchViewModel.kt`
- Create: `android/app/src/main/java/com/dby/mobile/BenchScreen.kt`
- Modify: `android/app/src/main/java/com/dby/mobile/MainActivity.kt`

**Interfaces:**
- Consumes: the generated `com.dby.core` API listed in Task 7.
- Produces: logcat lines `DBYBENCH app=dby event=<connect|first_page|next_page|sql> ms=<n> rows=<n>` used by Task 9.

- [ ] **Step 1: Write `BenchViewModel.kt`**

```kotlin
package com.dby.mobile

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dby.core.Cell
import com.dby.core.ColumnOut
import com.dby.core.ConnectParams
import com.dby.core.Cursor
import com.dby.core.Page
import com.dby.core.PageRequest
import com.dby.core.Session
import com.dby.core.TlsMode
import com.dby.core.openSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.util.UUID

private const val PAGE_SIZE = 50u

/** M0 only: drives the core and logs timings for the Tevel comparison. M2 replaces it. */
class BenchViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("bench", Context.MODE_PRIVATE)

    var host by mutableStateOf(prefs.getString("host", "").orEmpty())
    var port by mutableStateOf(prefs.getString("port", "3306").orEmpty())
    var user by mutableStateOf(prefs.getString("user", "").orEmpty())
    var password by mutableStateOf("") // never saved
    var database by mutableStateOf(prefs.getString("database", "").orEmpty())
    var table by mutableStateOf(prefs.getString("table", "").orEmpty())
    var tls by mutableStateOf(TlsMode.valueOf(prefs.getString("tls", TlsMode.VERIFY.name) ?: TlsMode.VERIFY.name))
    var sql by mutableStateOf(prefs.getString("sql", "SELECT * FROM ").orEmpty())

    var status by mutableStateOf("Not connected")
    var busy by mutableStateOf(false)
    var columns by mutableStateOf<List<ColumnOut>>(emptyList())
    val rows = mutableStateListOf<List<Cell>>()
    var hasNext by mutableStateOf(false)

    private var session: Session? = null
    private var nextCursor: Cursor? = null
    private var prefetched: Deferred<Page>? = null
    private var runId: String? = null

    fun connect() = timed("connect") {
        prefs.edit()
            .putString("host", host).putString("port", port).putString("user", user)
            .putString("database", database).putString("table", table).putString("tls", tls.name)
            .apply()
        session?.disconnect()
        val opened = openSession(ConnectParams(host.trim(), port.trim().toUShort(), user, password, database.trim(), tls))
        session = opened
        clearGrid()
        "Connected · ${opened.serverInfo().version}"
    }

    fun openTable() = timed("first_page") {
        val s = requireSession()
        val page = s.tablePage(PageRequest(table.trim(), null, PAGE_SIZE))
        show(page)
        prefetch(s)
        "${table.trim()} · page 1"
    }

    fun nextPage() = timed("next_page") {
        val s = requireSession()
        val pending = prefetched ?: return@timed "No more rows"
        show(pending.await())
        prefetch(s)
        "${table.trim()} · next page"
    }

    fun runSql() = timed("sql") {
        val s = requireSession()
        prefs.edit().putString("sql", sql).apply()
        val id = UUID.randomUUID().toString()
        runId = id
        try {
            val result = s.runSql(id, sql)
            columns = result.columns
            rows.clear()
            rows.addAll(result.rows)
            hasNext = false
            prefetched = null
            val capped = if (result.truncated) " (capped)" else ""
            "${result.rows.size} rows$capped · ${result.affectedRows} affected"
        } finally {
            runId = null
        }
    }

    fun cancel() {
        val s = session ?: return
        val id = runId ?: return
        viewModelScope.launch {
            try {
                s.cancel(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "Cancel failed: ${e.message}"
            }
        }
    }

    override fun onCleared() {
        val s = session ?: return
        CoroutineScope(Dispatchers.IO).launch { s.disconnect() }
    }

    /** Runs [block], logs `DBYBENCH` with the time from tap to data in state, shows the result. */
    private fun timed(event: String, block: suspend () -> String) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            val started = SystemClock.elapsedRealtime()
            status = try {
                val note = block()
                val ms = SystemClock.elapsedRealtime() - started
                Log.i("DBYBENCH", "app=dby event=$event ms=$ms rows=${rows.size}")
                "$note · $ms ms"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Failed: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    private fun requireSession(): Session = session ?: error("Connect first")

    private fun clearGrid() {
        columns = emptyList()
        rows.clear()
        hasNext = false
        nextCursor = null
        prefetched = null
    }

    private fun show(page: Page) {
        columns = page.columns
        rows.clear()
        rows.addAll(page.rows)
        nextCursor = page.next
        hasNext = page.next != null
    }

    /** Asks for the next page as soon as one is shown, so "Next page" usually costs no round trip. */
    private fun prefetch(s: Session) {
        val cursor = nextCursor
        val name = table.trim()
        prefetched = if (cursor == null) null else viewModelScope.async { s.tablePage(PageRequest(name, cursor, PAGE_SIZE)) }
    }
}
```

- [ ] **Step 2: Write `BenchScreen.kt`**

```kotlin
package com.dby.mobile

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dby.core.Cell
import com.dby.core.ColumnOut
import com.dby.core.TlsMode
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

// M0 throwaway colours; M2 replaces them with the theme from the design canvas.
internal val Bg = Color(0xFF0B0B0E)
internal val Fg = Color(0xFFF5F5F7)
internal val Muted = Color(0xB3FFFFFF)
internal val Accent = Color(0xFF5AC8FA)
private val HeaderBg = Color(0xFF16161B)
private val StripeBg = Color(0x09FFFFFF)

/** The frosted material from spec §11, used here to measure blur cost over a scrolling grid. */
private val PillStyle = HazeBlurStyle {
    blurRadius(24.dp)
    colorEffects(listOf(HazeColorEffect.tint(Color(0x801C1C21))))
}

@Composable
fun BenchScreen(vm: BenchViewModel = viewModel()) {
    val hazeState = rememberHazeState()
    Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding().padding(horizontal = 12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Field(vm.host, { vm.host = it }, "Host", Modifier.weight(2f))
            Field(vm.port, { vm.port = it }, "Port", Modifier.weight(1f), KeyboardType.Number)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Field(vm.user, { vm.user = it }, "User", Modifier.weight(1f))
            Field(vm.password, { vm.password = it }, "Password", Modifier.weight(1f), KeyboardType.Password, secret = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Field(vm.database, { vm.database = it }, "Database", Modifier.weight(1f))
            Field(vm.table, { vm.table = it }, "Table", Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TlsMode.entries.forEach { mode ->
                TextButton(onClick = { vm.tls = mode }) {
                    Text(mode.name, color = if (vm.tls == mode) Accent else Muted, fontSize = 12.sp)
                }
            }
            Box(Modifier.weight(1f))
            Button(onClick = vm::connect, enabled = !vm.busy) { Text("Connect") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Field(vm.sql, { vm.sql = it }, "SQL", Modifier.weight(1f))
            Button(onClick = vm::runSql, enabled = !vm.busy) { Text("Run") }
            TextButton(onClick = vm::cancel) { Text("Cancel") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = vm::openTable, enabled = !vm.busy) { Text("Open table") }
            Text(vm.status, color = Muted, fontSize = 13.sp, maxLines = 2, modifier = Modifier.padding(start = 10.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Grid(vm.columns, vm.rows, Modifier.fillMaxSize().hazeSource(hazeState))
            Pill(
                hazeState,
                vm,
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = Fg),
    )
}

@Composable
private fun Grid(columns: List<ColumnOut>, rows: List<List<Cell>>, modifier: Modifier) {
    val hScroll = rememberScrollState()
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 96.dp)) {
        if (columns.isNotEmpty()) {
            item(key = "header") { GridRow(columns.map { it.name }, hScroll, header = true) }
        }
        itemsIndexed(rows) { index, row -> GridRow(row.map { it.display() }, hScroll, stripe = index % 2 == 1) }
    }
}

/** First cell pinned; the rest scroll sideways together through one shared [ScrollState]. */
@Composable
private fun GridRow(cells: List<String>, hScroll: ScrollState, header: Boolean = false, stripe: Boolean = false) {
    val bg = when {
        header -> HeaderBg
        stripe -> StripeBg
        else -> Bg
    }
    Row(Modifier.fillMaxWidth().height(40.dp).background(bg), verticalAlignment = Alignment.CenterVertically) {
        GridCell(cells.firstOrNull().orEmpty(), 96.dp, header)
        Row(Modifier.horizontalScroll(hScroll)) {
            cells.drop(1).forEach { GridCell(it, 160.dp, header) }
        }
    }
}

@Composable
private fun GridCell(text: String, width: Dp, header: Boolean) {
    Text(
        text,
        Modifier.width(width).padding(horizontal = 8.dp),
        color = if (header) Muted else Fg,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun Pill(hazeState: HazeState, vm: BenchViewModel, modifier: Modifier) {
    val shape = RoundedCornerShape(28.dp)
    Row(
        modifier
            .clip(shape)
            .hazeBlur(input = HazeInput.Sources(hazeState), style = PillStyle)
            .border(0.5.dp, Color.White.copy(alpha = 0.14f), shape)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${vm.rows.size} rows", color = Fg, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp))
        TextButton(onClick = vm::nextPage, enabled = vm.hasNext && !vm.busy) { Text("Next page") }
    }
}

private fun Cell.display(): String = when (this) {
    is Cell.Null -> "NULL"
    is Cell.Signed -> v.toString()
    is Cell.Unsigned -> v.toString()
    is Cell.Real -> v.toString()
    is Cell.Exact -> v
    is Cell.Text -> if (fullLen > v.length.toULong()) "$v…" else v
    is Cell.Temporal -> v
    is Cell.Bytes -> "<$len bytes>"
}
```

- [ ] **Step 3: Point `MainActivity` at the bench screen**

Replace the `setContent` line and the `Text` import in `MainActivity.kt` so the file reads:
```kotlin
package com.dby.mobile

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import com.dby.core.coreVersion

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Log.i("DBYBENCH", "app=dby event=boot core=${coreVersion()}")
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Bg)) {
                BenchScreen()
            }
        }
    }
}
```

- [ ] **Step 4: Build the release APK (R8 on, debug-signed)**

Run: `(cd android && ./gradlew :app:assembleRelease)`
Expected: `BUILD SUCCESSFUL` and `android/app/build/outputs/apk/release/app-release.apk` exists.

- [ ] **Step 5: Run it against the local test database, if a device is attached**

With an emulator: install the APK, enter host `10.0.2.2`, port `33084`, user `root`, password `dbytest`, database `shop`, table `orders`, TLS `ENCRYPT_ONLY`, tap Connect, then Open table, then Next page twice, then run `SELECT * FROM orders`.
Expected: the grid fills, the status line shows timings, `SELECT *` reports `1000 rows (capped)`, and `adb logcat -d -s DBYBENCH:I` shows `connect`, `first_page`, `next_page` and `sql` events. With no device, skip this step and say so; Task 9 runs on the phone.

- [ ] **Step 6: Commit and push**

```bash
git add android/app/src/main/java/com/dby/mobile
git commit -m "feat(android): M0 benchmark screen with prefetching grid and Haze pill"
git push
```

---

### Task 9: Benchmark against TevelMobile on the phone and decide the gate

This task needs the user's phone over USB (USB debugging on), the RDS endpoint, a MySQL user for it, and a table with at least 100,000 rows. Ask the user for these at the start of the task; do not proceed with placeholder values.

**Files:**
- Create: `scripts/bench_report.py`, `docs/benchmarks.md`
- Modify (local branch `bench-m0` in `C:\Users\Yashwanth\TevelMobile`, never pushed): `src/store.ts`

**Interfaces:**
- Consumes: `DBYBENCH` logcat lines from DBY (Task 8) and from patched Tevel (this task).
- Produces: `docs/benchmarks.md` with the M0 gate verdict.

- [ ] **Step 1: Write the report script with its self-check**

`scripts/bench_report.py`:
```python
"""Summarise DBY vs Tevel timings from a logcat dump into a Markdown table.

Usage:
  python scripts/bench_report.py docs/benchmarks/raw/<scenario>.txt [gfx-dby.txt gfx-tevel.txt]
  python scripts/bench_report.py --self-test
"""
import re
import statistics
import sys

LINE = re.compile(r"DBYBENCH app=(\w+) event=(\w+) ms=(\d+)")
JANK = re.compile(r"Janky frames: (\d+) \(([\d.]+)%\)")
EVENTS = ["connect", "first_page", "next_page", "sql"]


def timings(text):
    found = {}
    for app, event, ms in LINE.findall(text):
        found.setdefault((app, event), []).append(int(ms))
    return found


def jank(text):
    match = JANK.search(text)
    return float(match.group(2)) if match else None


def cell(values):
    return f"{statistics.median(values):.0f} ({len(values)})" if values else "-"


def table(found):
    rows = ["| Event | DBY median ms (runs) | Tevel median ms (runs) | DBY faster? |", "|---|---|---|---|"]
    for event in EVENTS:
        dby = found.get(("dby", event), [])
        tevel = found.get(("tevel", event), [])
        if not (dby or tevel):
            continue
        if dby and tevel:
            verdict = "yes" if statistics.median(dby) < statistics.median(tevel) else "**no**"
        else:
            verdict = "-"
        rows.append(f"| {event} | {cell(dby)} | {cell(tevel)} | {verdict} |")
    return "\n".join(rows)


def self_test():
    sample = (
        "10-08 I DBYBENCH: app=dby event=connect ms=300 rows=0\n"
        "10-08 I DBYBENCH: app=dby event=connect ms=500 rows=0\n"
        "10-08 I Capacitor/Console: File: x - Line 1 - Msg: DBYBENCH app=tevel event=connect ms=900\n"
    )
    found = timings(sample)
    assert found[("dby", "connect")] == [300, 500]
    assert found[("tevel", "connect")] == [900]
    assert "| connect | 400 (2) | 900 (1) | yes |" in table(found)
    assert "first_page" not in table(found)
    assert jank("Janky frames: 12 (3.45%)") == 3.45
    print("self-test ok")


def main(argv):
    if argv[1:] == ["--self-test"]:
        self_test()
        return
    with open(argv[1], encoding="utf-8", errors="replace") as f:
        print(table(timings(f.read())))
    for path in argv[2:]:
        with open(path, encoding="utf-8", errors="replace") as f:
            print(f"\n{path}: janky frames {jank(f.read())}%")


if __name__ == "__main__":
    main(sys.argv)
```

Run: `python scripts/bench_report.py --self-test`
Expected: `self-test ok`.

- [ ] **Step 2: Add the same timing lines to TevelMobile on a local branch**

```bash
git -C ../TevelMobile switch -c bench-m0
```

In `../TevelMobile/src/store.ts`:

In `openConnection`, add as the first line of the function body:
```ts
   const t0 = performance.now();
```
and directly after the existing line `go('schemas', 'ask');` inside its `try` block:
```ts
      console.log(`DBYBENCH app=tevel event=connect ms=${Math.round(performance.now() - t0)} rows=0`);
```

In `openTable`, add as the first line of the function body:
```ts
   const t0 = performance.now();
```
and directly after the existing line `go('table', 'table');` at the end of the function:
```ts
   console.log(`DBYBENCH app=tevel event=${page === 1 ? 'first_page' : 'next_page'} ms=${Math.round(performance.now() - t0)} rows=${state.rows.length}`);
```

Replace the body of `runEditor` with:
```ts
   const sql = state.sqlText.trim();
   if (!sql) return;
   const t0 = performance.now();
   state.sqlResult = await run(sql, false);
   console.log(`DBYBENCH app=tevel event=sql ms=${Math.round(performance.now() - t0)} rows=${state.sqlResult?.rows.length ?? 0}`);
```

- [ ] **Step 3: Build and install patched Tevel, signed with its own release key**

Run from PowerShell (Tevel's script calls `gradlew.bat`):
```powershell
cd C:\Users\Yashwanth\TevelMobile; npm run apk:release
adb install -r C:\Users\Yashwanth\TevelMobile\android\app\build\outputs\apk\release\app-release.apk
```
Expected: `Success`. The install keeps Tevel's saved connections because the signing key matches. In Tevel's Settings, set "Rows per page" to 50 so both apps fetch the same page size.

- [ ] **Step 4: Install DBY's release build**

Run: `adb install -r android/app/build/outputs/apk/release/app-release.apk`
Expected: `Success`. In DBY, enter the RDS endpoint, port, user, password, database and the agreed table, with TLS `VERIFY`.

- [ ] **Step 5: Run the four scenarios**

Use the same network for both apps (note Wi-Fi or mobile data). For each scenario: clear logcat, do 5 runs in DBY and 5 in Tevel, then save the log.

```bash
mkdir -p docs/benchmarks/raw
adb logcat -c
# ... do the runs for one scenario ...
adb logcat -d > docs/benchmarks/raw/<scenario>.txt
python scripts/bench_report.py docs/benchmarks/raw/<scenario>.txt
```

Scenarios (`<scenario>` is the file name):
1. `connect` — DBY: tap Connect. Tevel: tap the RDS connection on its Connections screen. Disconnect between runs (Tevel: back to Connections; DBY: tap Connect again).
2. `pages` — open the agreed table (first page), then tap next page 5 times. Repeat the open 5 times.
3. `select-star` — run `SELECT * FROM <table>` with no LIMIT, 5 times in each app.
4. `thousand` — run `SELECT * FROM <table> LIMIT 1000` 5 times in each app. Then measure scrolling:
   ```bash
   adb shell dumpsys gfxinfo com.dby.mobile reset
   # scroll the 1000-row grid up and down for 10 seconds
   adb shell dumpsys gfxinfo com.dby.mobile > docs/benchmarks/raw/gfx-dby.txt
   adb shell dumpsys gfxinfo com.tevel.mobile reset
   # scroll Tevel's 1000-row grid up and down for 10 seconds
   adb shell dumpsys gfxinfo com.tevel.mobile > docs/benchmarks/raw/gfx-tevel.txt
   python scripts/bench_report.py docs/benchmarks/raw/thousand.txt docs/benchmarks/raw/gfx-dby.txt docs/benchmarks/raw/gfx-tevel.txt
   ```

- [ ] **Step 6: Write `docs/benchmarks.md`**

Write the file with these sections, filled from the runs (the raw logs stay git-ignored):
```markdown
# M0 benchmark: DBY vs TevelMobile 1.15.5

- Date:
- Phone (model, Android version):
- Network (Wi-Fi or mobile data, and where):
- Server: RDS engine and version, region (from the endpoint hostname), instance class
- Table: name and row count
- Builds: DBY 0.0.1-m0 release (R8), Tevel 1.15.5 release + timing lines (branch bench-m0, not pushed)

## Results
<one table per scenario, pasted from bench_report.py>

## Scrolling a 1000-row result
DBY janky frames: x% · Tevel janky frames: y% · target: under 5% for DBY

## Gate (spec §13, M0)
PASS or FAIL. One sentence per scenario that DBY did not win, with the likely cause.
```

- [ ] **Step 7: Decide the gate**

- **PASS**, if DBY is faster on all four scenarios and its janky frames are under 5%: commit, push, and report the numbers to the user. M1 planning can start.
- **FAIL**, otherwise: commit and push the results anyway, then stop and report to the user which scenario lost and why, before any M1 or M2 work. A change to the design goes back through the spec.

```bash
git add scripts/bench_report.py docs/benchmarks.md
git commit -m "docs: M0 benchmark results against TevelMobile"
git push
```

- [ ] **Step 8: Put Tevel back on its main branch**

```bash
git -C ../TevelMobile switch main
```
The `bench-m0` branch stays local and is never pushed. The timed Tevel build can stay installed; it behaves exactly like 1.15.5 apart from the log lines.
