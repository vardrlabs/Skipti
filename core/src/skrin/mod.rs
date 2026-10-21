//! skrin: the encrypted local store (SPEC §3, §16.3 task 8).
//!
//! SQLCipher (AES-256 page-level encryption) holds everything a client must
//! survive a restart with: records, HLC state, sync cursors and settings
//! (D-200: the relay-era group keys and device keys are no longer kept). The database key never touches disk outside
//! the vault; it lives behind [`Keystore`], the boundary iOS Keychain,
//! Android Keystore, and the web shell implement in Phase 7. Biometric-
//! gated release of that key is likewise a shell concern layered on the same
//! boundary.

use crate::straumr::{Hlc, RecordHeader};
use rusqlite::Connection;
use std::fmt;
use std::path::Path;
use zeroize::{Zeroize, Zeroizing};

/// Bumped only by a migration entry below. Row one is versioned because the
/// cheapest moment to change course is before a schema freezes across every
/// installed device (ADR-0018).
pub const SCHEMA_VERSION: i64 = 2;

#[derive(Debug)]
pub enum SkrinError {
    Io(std::io::Error),
    Sqlite(String),
    /// The vault exists but this keystore's key does not open it. Never
    /// re-key on this path: re-keying would silently destroy the data.
    WrongKey,
    NewerSchema {
        found: i64,
        latest: i64,
    },
    Keystore(String),
    InvalidInput(&'static str),
}

impl fmt::Display for SkrinError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            SkrinError::Io(e) => write!(f, "io: {e}"),
            SkrinError::Sqlite(e) => write!(f, "sqlite: {e}"),
            SkrinError::WrongKey => write!(f, "vault key does not open this store"),
            SkrinError::NewerSchema { found, latest } => {
                write!(f, "vault schema v{found} is newer than supported v{latest}")
            }
            SkrinError::Keystore(e) => write!(f, "keystore: {e}"),
            SkrinError::InvalidInput(m) => write!(f, "invalid input: {m}"),
        }
    }
}

impl std::error::Error for SkrinError {}

impl From<rusqlite::Error> for SkrinError {
    fn from(e: rusqlite::Error) -> Self {
        if let rusqlite::Error::SqliteFailure(ffi_err, _) = &e {
            // A wrong key surfaces as SQLITE_NOTADB on the first read.
            if ffi_err.code == rusqlite::ErrorCode::NotADatabase {
                return SkrinError::WrongKey;
            }
        }
        SkrinError::Sqlite(e.to_string())
    }
}

/// D-236 (decision 129, core audit L4): the vault's database key, held on the
/// heap and zeroed when dropped. It is neither `Clone` nor `Copy` and its
/// `Debug` prints no bytes, so every copy is made on purpose (`from_slice`)
/// and is itself zeroed. Boxed so that a move moves a pointer, not the key.
pub struct VaultKey(Box<[u8; 32]>);

impl VaultKey {
    /// A copy of `bytes`, or `None` unless it is exactly 32 bytes long.
    pub fn from_slice(bytes: &[u8]) -> Option<VaultKey> {
        if bytes.len() != 32 {
            return None;
        }
        let mut key = VaultKey(Box::new([0u8; 32]));
        key.0.copy_from_slice(bytes);
        Some(key)
    }

    pub fn as_bytes(&self) -> &[u8; 32] {
        &self.0
    }
}

impl Drop for VaultKey {
    fn drop(&mut self) {
        let bytes: &mut [u8; 32] = &mut self.0;
        bytes.zeroize();
    }
}

impl fmt::Debug for VaultKey {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("VaultKey(..)")
    }
}

/// The platform-keystore boundary. Shells supply real backings (iOS
/// Keychain (`WhenUnlockedThisDeviceOnly`), Android Keystore) per §10.2;
/// the CI backing is [`MemoryKeystore`]. The trait deliberately has no path
/// to export the key anywhere except back into memory: a file-backed
/// implementation would defeat A-17 by construction.
pub trait Keystore {
    fn provision(&self, key: &[u8; 32]) -> Result<(), SkrinError>;
    /// `None` before first provisioning. D-236: the copy handed back is a
    /// [`VaultKey`], zeroed when the caller drops it.
    fn load(&self) -> Result<Option<VaultKey>, SkrinError>;
    fn destroy(&self) -> Result<(), SkrinError>;
}

/// Test/CI backing. The key lives only in memory and is zeroized on drop
/// (D-236: the slot is a [`VaultKey`], so replacing, destroying or dropping
/// the keystore zeroes it).
#[derive(Default)]
pub struct MemoryKeystore {
    key: std::sync::Mutex<Option<VaultKey>>,
}

impl Keystore for MemoryKeystore {
    fn provision(&self, key: &[u8; 32]) -> Result<(), SkrinError> {
        let mut slot = self.key.lock().expect("keystore mutex");
        *slot = VaultKey::from_slice(key);
        Ok(())
    }

    fn load(&self) -> Result<Option<VaultKey>, SkrinError> {
        let slot = self.key.lock().expect("keystore mutex");
        Ok(slot
            .as_ref()
            .and_then(|key| VaultKey::from_slice(key.as_bytes())))
    }

    fn destroy(&self) -> Result<(), SkrinError> {
        let mut slot = self.key.lock().expect("keystore mutex");
        *slot = None;
        Ok(())
    }
}

const MIGRATIONS: &[(i64, &str)] = &[
    (1, include_str!("schema_v1.sql")),
    // ADR-0034: a record's identity IS its HLC (millis, counter, device).
    // Record-level sync re-applies records it has already seen, so the
    // write must be idempotent: re-putting the same identity REPLACES,
    // never duplicates. The unique index makes the identity enforceable
    // by the store, not by caller discipline.
    (2, "CREATE UNIQUE INDEX IF NOT EXISTS idx_records_hlc_identity          ON records (hlc_millis, hlc_counter, hlc_device_id);"),
];

#[cfg(test)]
fn hex_encode(bytes: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut s = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        s.push(HEX[(b >> 4) as usize] as char);
        s.push(HEX[(b & 0xF) as usize] as char);
    }
    s
}

/// SQLCipher's raw-key form, byte for byte what `pragma_update(None, "key",
/// "x'<hex>'")` builds: `PRAGMA key='x''<hex>'''`. D-236: built here instead,
/// in one zeroed buffer of exactly this length (rusqlite's builder grows its
/// string from empty and frees each smaller copy unzeroed).
const PRAGMA_KEY_HEAD: &str = "PRAGMA key='x''";
const PRAGMA_KEY_TAIL: &str = "'''";
const PRAGMA_KEY_SQL_LEN: usize = PRAGMA_KEY_HEAD.len() + 64 + PRAGMA_KEY_TAIL.len();

fn pragma_key_sql(key: &VaultKey) -> Zeroizing<String> {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut sql = Zeroizing::new(String::with_capacity(PRAGMA_KEY_SQL_LEN));
    sql.push_str(PRAGMA_KEY_HEAD);
    for b in key.as_bytes() {
        sql.push(HEX[(b >> 4) as usize] as char);
        sql.push(HEX[(b & 0xF) as usize] as char);
    }
    sql.push_str(PRAGMA_KEY_TAIL);
    sql
}

pub struct Skrin {
    conn: Connection,
}

impl Skrin {
    /// Opens (or creates) the vault at `path`, taking the database key from
    /// `keystore`. First open provisions a fresh random key. An existing
    /// vault that the provided key cannot open is an error, never a re-key.
    pub fn open(path: &Path, keystore: &dyn Keystore) -> Result<Skrin, SkrinError> {
        // D-236: every copy of the key made here is zeroed when it drops, on
        // every path out (each `?` included): the loaded or fresh `VaultKey`,
        // the random draw it came from, and the PRAGMA text.
        let key = match keystore.load()? {
            Some(k) => k,
            None => {
                let random = Zeroizing::new(crate::lykill::random_bytes(32));
                let fresh = VaultKey::from_slice(&random).ok_or(SkrinError::InvalidInput(
                    "key generation produced wrong length",
                ))?;
                keystore.provision(fresh.as_bytes())?;
                fresh
            }
        };
        let conn = Connection::open(path)?;
        Self::apply_key_and_verify(&conn, &key)?;
        Self::migrate(&conn)?;
        Ok(Skrin { conn })
    }

    fn apply_key_and_verify(conn: &Connection, key: &VaultKey) -> Result<(), SkrinError> {
        // SQLCipher raw-key form: PRAGMA key='x''<hex>''' (see PRAGMA_KEY_HEAD).
        let sql = pragma_key_sql(key);
        conn.execute_batch(&sql)?;
        // Forcing a read makes a wrong key fail here, loudly.
        conn.query_row("SELECT count(*) FROM sqlite_master", [], |r| {
            r.get::<_, i64>(0)
        })?;
        Ok(())
    }

    fn migrate(conn: &Connection) -> Result<(), SkrinError> {
        let current: i64 = conn.query_row("PRAGMA user_version", [], |r| r.get(0))?;
        let latest = MIGRATIONS.last().map(|(v, _)| *v).unwrap_or(0);
        if current > latest {
            return Err(SkrinError::NewerSchema {
                found: current,
                latest,
            });
        }
        for (version, sql) in MIGRATIONS {
            if *version > current {
                conn.execute_batch(&format!(
                    "BEGIN IMMEDIATE;\n{sql}\nPRAGMA user_version = {version};\nCOMMIT;"
                ))?;
            }
        }
        Ok(())
    }

    pub fn schema_version(&self) -> Result<i64, SkrinError> {
        Ok(self
            .conn
            .query_row("PRAGMA user_version", [], |r| r.get(0))?)
    }

    pub fn put_record(&self, header: &RecordHeader, blob: &[u8]) -> Result<(), SkrinError> {
        // D-235 (core audit L5): the table has no version column and
        // `all_records` reads every row back as RECORD_HEADER_VERSION, so a
        // record of any other header version would come back silently
        // re-labelled. Refused instead (a column would need a schema
        // migration, and a newer schema locks out an older build).
        if header.version != crate::straumr::RECORD_HEADER_VERSION {
            return Err(SkrinError::InvalidInput(
                "record header version is not one this build stores",
            ));
        }
        let (personal, group_id) = match header.scope {
            crate::straumr::Scope::Personal => (1i64, None::<Vec<u8>>),
            crate::straumr::Scope::Group(g) => (0i64, Some(g.to_vec())),
        };
        self.conn.execute(
            "INSERT OR REPLACE INTO records (record_type, personal, group_id, epoch, \
             hlc_millis, hlc_counter, hlc_device_id, author_device_id, \
             nonce_session, flags, blob) \
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11)",
            rusqlite::params![
                header.record_type.as_u8(),
                personal,
                group_id,
                header.epoch,
                header.hlc.millis as i64,
                header.hlc.counter as i64,
                header.hlc.device_id.as_slice(),
                header.author_device_id.as_slice(),
                header.nonce_session.as_slice(),
                header.flags,
                blob,
            ],
        )?;
        Ok(())
    }

    /// ADR-0034: removes every record row. The lost-device simulation
    /// (and, later, account deletion) needs a clean record path to prove
    /// a restore rebuilds from the relay alone. Key material and the kv
    /// projection are untouched.
    pub fn clear_records(&self) -> Result<(), SkrinError> {
        self.conn.execute("DELETE FROM records", [])?;
        Ok(())
    }

    /// D-210: forgets one group on this phone: every record row scoped to
    /// `group` goes, the personal rows and every other group's stay. Used
    /// when a removed or deleted group's read-only copy is dropped.
    pub fn delete_group_records(&self, group: &[u8; 16]) -> Result<(), SkrinError> {
        self.conn.execute(
            "DELETE FROM records WHERE personal = 0 AND group_id = ?1",
            rusqlite::params![group.as_slice()],
        )?;
        Ok(())
    }

    pub fn all_records(&self) -> Result<Vec<(RecordHeader, Vec<u8>)>, SkrinError> {
        let mut stmt = self.conn.prepare(
            "SELECT record_type, personal, group_id, epoch, hlc_millis, hlc_counter, \
             hlc_device_id, author_device_id, nonce_session, flags, blob \
             FROM records ORDER BY hlc_millis, hlc_counter, hlc_device_id",
        )?;
        let rows = stmt.query_map([], |row| {
            let rt: u8 = row.get(0)?;
            let personal: i64 = row.get(1)?;
            let group_blob: Option<Vec<u8>> = row.get(2)?;
            let scope = match (personal, group_blob) {
                (1, _) => crate::straumr::Scope::Personal,
                (_, Some(g)) => crate::straumr::Scope::Group(g.as_slice().try_into().unwrap()),
                _ => crate::straumr::Scope::Personal,
            };
            let header = RecordHeader {
                version: crate::straumr::RECORD_HEADER_VERSION,
                record_type: crate::straumr::RecordType::from_u8(rt)
                    .expect("stored record types are valid"),
                scope,
                epoch: row.get(3)?,
                hlc: Hlc {
                    millis: row.get::<_, i64>(4)? as u64,
                    counter: row.get::<_, i64>(5)? as u32,
                    device_id: row.get::<_, Vec<u8>>(6)?.as_slice().try_into().unwrap(),
                },
                author_device_id: row.get::<_, Vec<u8>>(7)?.as_slice().try_into().unwrap(),
                nonce_session: row.get::<_, Vec<u8>>(8)?.as_slice().try_into().unwrap(),
                flags: row.get(9)?,
            };
            Ok((header, row.get::<_, Vec<u8>>(10)?))
        })?;
        Ok(rows.collect::<Result<Vec<_>, _>>()?)
    }

    pub fn save_hlc(&self, hlc: &Hlc) -> Result<(), SkrinError> {
        self.conn.execute(
            "INSERT INTO hlc_state (device_id, millis, counter) VALUES (?1, ?2, ?3) \
             ON CONFLICT(device_id) DO UPDATE SET millis = ?2, counter = ?3",
            rusqlite::params![
                hlc.device_id.as_slice(),
                hlc.millis as i64,
                hlc.counter as i64
            ],
        )?;
        Ok(())
    }

    pub fn load_hlc(&self, device_id: &[u8; 16]) -> Result<Option<Hlc>, SkrinError> {
        let mut stmt = self
            .conn
            .prepare("SELECT millis, counter FROM hlc_state WHERE device_id = ?1")?;
        let mut rows = stmt.query(rusqlite::params![device_id.as_slice()])?;
        match rows.next()? {
            Some(row) => Ok(Some(Hlc {
                device_id: *device_id,
                millis: row.get::<_, i64>(0)? as u64,
                counter: row.get::<_, i64>(1)? as u32,
            })),
            None => Ok(None),
        }
    }

    #[cfg(test)]
    fn set_user_version_for_test(&self, v: i64) {
        self.conn.pragma_update(None, "user_version", v).unwrap();
    }

    pub fn kv_set(&self, key: &str, value: &[u8]) -> Result<(), SkrinError> {
        self.conn.execute(
            "INSERT INTO kv (k, v) VALUES (?1, ?2) ON CONFLICT(k) DO UPDATE SET v = ?2",
            rusqlite::params![key, value],
        )?;
        Ok(())
    }

    pub fn kv_get(&self, key: &str) -> Result<Option<Vec<u8>>, SkrinError> {
        let mut stmt = self.conn.prepare("SELECT v FROM kv WHERE k = ?1")?;
        let mut rows = stmt.query(rusqlite::params![key])?;
        match rows.next()? {
            Some(row) => Ok(Some(row.get(0)?)),
            None => Ok(None),
        }
    }
}

/// D-236: a test-only allocator that watches what the Rust heap frees. While
/// armed with a canary key, every block it frees (or moves away from in a
/// realloc) is searched for the canary's 32 raw bytes and for its 64
/// lowercase hex characters, and each block that holds either is counted.
/// A copy of the key that is zeroed before it is freed is never counted; one
/// that is freed as it stands is. It sees only Rust's heap: stack copies,
/// SQLCipher's own memory and anything a shell holds are outside its view.
#[cfg(test)]
pub(crate) mod freed_scan {
    use std::alloc::{GlobalAlloc, Layout, System};
    use std::sync::atomic::{AtomicBool, AtomicU8, AtomicUsize, Ordering};
    use std::sync::{Mutex, MutexGuard};

    struct Scanner;

    #[global_allocator]
    static GLOBAL: Scanner = Scanner;

    static ARMED: AtomicBool = AtomicBool::new(false);
    static HITS: AtomicUsize = AtomicUsize::new(0);
    static RAW: [AtomicU8; 32] = [const { AtomicU8::new(0) }; 32];
    static HEX: [AtomicU8; 64] = [const { AtomicU8::new(0) }; 64];
    static ONE_AT_A_TIME: Mutex<()> = Mutex::new(());

    /// # Safety
    /// `block` must be valid for reads of `len` bytes.
    unsafe fn holds(block: *const u8, len: usize, needle: &[AtomicU8]) -> bool {
        let n = needle.len();
        if len < n {
            return false;
        }
        let first = needle[0].load(Ordering::Relaxed);
        'start: for start in 0..=(len - n) {
            if unsafe { block.add(start).read_volatile() } != first {
                continue;
            }
            for (i, want) in needle.iter().enumerate().skip(1) {
                if unsafe { block.add(start + i).read_volatile() } != want.load(Ordering::Relaxed) {
                    continue 'start;
                }
            }
            return true;
        }
        false
    }

    /// # Safety
    /// `block` must be valid for reads of `len` bytes.
    unsafe fn scan(block: *const u8, len: usize) {
        if ARMED.load(Ordering::SeqCst)
            && unsafe { holds(block, len, &RAW) || holds(block, len, &HEX) }
        {
            HITS.fetch_add(1, Ordering::SeqCst);
        }
    }

    unsafe impl GlobalAlloc for Scanner {
        unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
            unsafe { System.alloc(layout) }
        }
        unsafe fn alloc_zeroed(&self, layout: Layout) -> *mut u8 {
            unsafe { System.alloc_zeroed(layout) }
        }
        unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
            unsafe {
                scan(ptr, layout.size());
                System.dealloc(ptr, layout)
            }
        }
        unsafe fn realloc(&self, ptr: *mut u8, layout: Layout, new_size: usize) -> *mut u8 {
            unsafe {
                scan(ptr, layout.size());
                System.realloc(ptr, layout, new_size)
            }
        }
    }

    /// Held for the whole of a test that arms the scanner: one at a time.
    pub(crate) fn exclusive() -> MutexGuard<'static, ()> {
        ONE_AT_A_TIME.lock().unwrap_or_else(|p| p.into_inner())
    }

    /// Starts counting freed blocks that hold `key` (raw or as hex).
    pub(crate) fn arm(key: &[u8; 32]) {
        const DIGITS: &[u8; 16] = b"0123456789abcdef";
        ARMED.store(false, Ordering::SeqCst);
        for (i, b) in key.iter().enumerate() {
            RAW[i].store(*b, Ordering::Relaxed);
            HEX[2 * i].store(DIGITS[(b >> 4) as usize], Ordering::Relaxed);
            HEX[2 * i + 1].store(DIGITS[(b & 0xF) as usize], Ordering::Relaxed);
        }
        HITS.store(0, Ordering::SeqCst);
        ARMED.store(true, Ordering::SeqCst);
    }

    /// Stops counting; how many freed blocks held the key since `arm`.
    pub(crate) fn disarm() -> usize {
        ARMED.store(false, Ordering::SeqCst);
        HITS.load(Ordering::SeqCst)
    }

    /// How many freed blocks held `key` while `f` ran.
    pub(crate) fn freed_copies_during(key: &[u8; 32], f: impl FnOnce()) -> usize {
        let _one = exclusive();
        arm(key);
        f();
        disarm()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::straumr::{RecordType, Scope};

    /// D-236: a pinned key no other test draws (OsRng keys are random).
    const CANARY: [u8; 32] = [
        0xD2, 0x36, 0x5A, 0xC3, 0x91, 0x0E, 0x7B, 0x44, 0xA8, 0x1F, 0x6D, 0xE2, 0x57, 0xB0, 0x39,
        0xCC, 0x08, 0x9A, 0x73, 0xF5, 0x2E, 0x61, 0xBD, 0x14, 0x86, 0xEF, 0x4B, 0x20, 0xD7, 0x5C,
        0x93, 0x7A,
    ];

    /// D-236: hands its key back as the real keystores do, and arms the
    /// freed-block scanner the moment a fresh key is provisioned, so a copy
    /// of a key nobody knew in advance is watched from then on.
    struct ArmingKeystore {
        inner: MemoryKeystore,
    }

    impl Keystore for ArmingKeystore {
        fn provision(&self, key: &[u8; 32]) -> Result<(), SkrinError> {
            freed_scan::arm(key);
            self.inner.provision(key)
        }
        fn load(&self) -> Result<Option<VaultKey>, SkrinError> {
            self.inner.load()
        }
        fn destroy(&self) -> Result<(), SkrinError> {
            self.inner.destroy()
        }
    }

    #[test]
    fn d236_the_scanner_sees_a_key_freed_as_it_stands() {
        // The detector's positive controls: a plain boxed key and its hex
        // text, freed unzeroed, are each counted. Without these a scanner
        // that never counts anything would pass every test below.
        let raw = freed_scan::freed_copies_during(&CANARY, || {
            drop(std::hint::black_box(Box::new(CANARY)));
        });
        assert_eq!(raw, 1, "a plain Box<[u8; 32]> freed unzeroed");
        let hex = freed_scan::freed_copies_during(&CANARY, || {
            drop(std::hint::black_box(hex_encode(&CANARY)));
        });
        assert_eq!(hex, 1, "the key's hex text freed unzeroed");
        // And rusqlite's own PRAGMA builder (the path D-236 replaced) frees
        // the key's hex in the strings it grows. If a rusqlite update ever
        // zeroes them this fails, which is news worth reading, not a bug.
        let rusqlite_copies = freed_scan::freed_copies_during(&CANARY, || {
            let conn = Connection::open_in_memory().unwrap();
            // `keyed` is zeroed here, so every count is rusqlite's own.
            let mut keyed = Zeroizing::new(String::with_capacity(66));
            keyed.push_str("x'");
            keyed.push_str(&Zeroizing::new(hex_encode(&CANARY)));
            keyed.push('\'');
            conn.pragma_update(None, "key", keyed.as_str()).unwrap();
        });
        assert!(rusqlite_copies >= 1, "{rusqlite_copies}");
    }

    #[test]
    fn d236_a_vault_key_is_zeroed_when_it_drops() {
        let copies = freed_scan::freed_copies_during(&CANARY, || {
            let key = VaultKey::from_slice(&CANARY).unwrap();
            assert_eq!(key.as_bytes(), &CANARY);
            assert_eq!(format!("{key:?}"), "VaultKey(..)", "Debug prints no bytes");
            drop(std::hint::black_box(key));
        });
        assert_eq!(copies, 0);
        assert!(VaultKey::from_slice(&CANARY[..31]).is_none());
        assert!(VaultKey::from_slice(&[0u8; 33]).is_none());
    }

    #[test]
    fn d236_the_pragma_text_is_the_raw_key_form_in_one_exact_buffer() {
        let key = VaultKey::from_slice(&CANARY).unwrap();
        let sql = pragma_key_sql(&key);
        // Re-derived independently of pragma_key_sql: rusqlite's
        // pragma_update(None, "key", "x'<hex>'") doubles each quote.
        let expected = format!("PRAGMA key='x''{}'''", hex_encode(&CANARY));
        assert_eq!(sql.as_str(), expected);
        // Built without a reallocation: a grown String frees its smaller
        // copy unzeroed. Capacity equal to length means it never grew.
        assert_eq!(sql.len(), PRAGMA_KEY_SQL_LEN);
        assert_eq!(sql.capacity(), PRAGMA_KEY_SQL_LEN);
        let copies = freed_scan::freed_copies_during(&CANARY, || {
            drop(std::hint::black_box(pragma_key_sql(&key)));
        });
        assert_eq!(copies, 0, "the PRAGMA text is zeroed when it drops");
    }

    #[test]
    fn d236_a_vault_keyed_the_old_way_still_opens() {
        // Devices hold vaults keyed through rusqlite's pragma_update with
        // the raw-key form. D-236's own PRAGMA text must open them. It frees
        // unzeroed key hex on purpose, so it holds the scan lock: an armed
        // D-236 test running beside it would count those frees.
        let _one = freed_scan::exclusive();
        let vault = TempVault::new();
        {
            let conn = Connection::open(&vault.path).unwrap();
            conn.pragma_update(None, "key", format!("x'{}'", hex_encode(&CANARY)))
                .unwrap();
            conn.execute_batch("CREATE TABLE old_way (x INTEGER); INSERT INTO old_way VALUES (7);")
                .unwrap();
        }
        let ks = MemoryKeystore::default();
        ks.provision(&CANARY).unwrap();
        let skrin = Skrin::open(&vault.path, &ks).expect("the old raw-key form opens");
        assert_eq!(skrin.schema_version().unwrap(), SCHEMA_VERSION);
        let x: i64 = skrin
            .conn
            .query_row("SELECT x FROM old_way", [], |r| r.get(0))
            .unwrap();
        assert_eq!(x, 7);
    }

    #[test]
    fn d236_opening_a_vault_frees_no_unzeroed_copy_of_its_key() {
        let vault = TempVault::new();
        {
            let ks = MemoryKeystore::default();
            ks.provision(&CANARY).unwrap();
            Skrin::open(&vault.path, &ks).unwrap();
        }
        // A vault under another key, for which the canary is the WRONG key.
        let other_vault = TempVault::new();
        {
            let mut other = CANARY;
            other[0] ^= 1;
            let other_ks = MemoryKeystore::default();
            other_ks.provision(&other).unwrap();
            Skrin::open(&other_vault.path, &other_ks).unwrap();
        }
        let ks = MemoryKeystore::default();
        ks.provision(&CANARY).unwrap();
        let copies = freed_scan::freed_copies_during(&CANARY, || {
            let skrin = Skrin::open(&vault.path, &ks).unwrap();
            assert_eq!(skrin.schema_version().unwrap(), SCHEMA_VERSION);
            // The early return on a wrong key frees its copies zeroed too.
            assert!(matches!(
                Skrin::open(&other_vault.path, &ks),
                Err(SkrinError::WrongKey)
            ));
        });
        assert_eq!(copies, 0);
        // The keystore's own copy goes the same way when it is destroyed.
        let copies = freed_scan::freed_copies_during(&CANARY, || {
            ks.provision(&CANARY).unwrap(); // replaces: the old copy is freed
            ks.destroy().unwrap();
        });
        assert_eq!(copies, 0);
    }

    #[test]
    fn d236_a_fresh_vault_frees_no_unzeroed_copy_of_its_new_key() {
        let vault = TempVault::new();
        let ks = ArmingKeystore {
            inner: MemoryKeystore::default(),
        };
        let _one = freed_scan::exclusive();
        let skrin = Skrin::open(&vault.path, &ks).unwrap();
        let copies = freed_scan::disarm();
        assert_eq!(skrin.schema_version().unwrap(), SCHEMA_VERSION);
        assert!(
            ks.load().unwrap().is_some(),
            "the fresh key was provisioned"
        );
        assert_eq!(copies, 0);
    }

    struct TempVault {
        path: std::path::PathBuf,
    }

    impl TempVault {
        fn new() -> Self {
            let mut path = std::env::temp_dir();
            path.push(format!(
                "skrin-test-{}.vault",
                hex_encode(&crate::lykill::random_bytes(8))
            ));
            TempVault { path }
        }
    }

    impl Drop for TempVault {
        fn drop(&mut self) {
            let _ = std::fs::remove_file(&self.path);
        }
    }

    fn sample_header(millis: u64) -> RecordHeader {
        RecordHeader {
            version: crate::straumr::RECORD_HEADER_VERSION,
            record_type: RecordType::Expense,
            scope: Scope::Group([7u8; 16]),
            epoch: 0,
            hlc: Hlc::now([1; 16], millis),
            author_device_id: [1; 16],
            nonce_session: [0x22; 4],
            flags: 0,
        }
    }

    #[test]
    fn record_identity_is_the_hlc_reput_is_idempotent() {
        // ADR-0034: record-level sync re-applies records it has already
        // seen. The store enforces the identity (unique HLC index), so a
        // re-put REPLACES rather than duplicates: the merge primitive the
        // whole engine rests on.
        let vault = TempVault::new();
        let ks = MemoryKeystore::default();
        let skrin = Skrin::open(&vault.path, &ks).unwrap();

        let header = sample_header(5000);
        skrin.put_record(&header, b"first").unwrap();
        // Same identity, different bytes: REPLACE, one row.
        skrin.put_record(&header, b"second").unwrap();
        // Same millis+counter, different device: a DIFFERENT record.
        let mut other_device = sample_header(5000);
        other_device.hlc.device_id = [2; 16];
        skrin.put_record(&other_device, b"other").unwrap();

        let records = skrin.all_records().unwrap();
        assert_eq!(records.len(), 2);
        assert_eq!(records[0].1, b"second"); // same HLC order, replaced bytes
        assert_eq!(records[1].1, b"other");

        // And a vault from schema v1 migrates in place, keeping its rows.
        let vault2 = TempVault::new();
        {
            let old = Skrin::open(&vault2.path, &ks).unwrap();
            old.put_record(&sample_header(1), b"keep").unwrap();
            old.set_user_version_for_test(1);
        }
        let migrated = Skrin::open(&vault2.path, &ks).unwrap();
        assert_eq!(migrated.schema_version().unwrap(), SCHEMA_VERSION);
        assert_eq!(migrated.all_records().unwrap().len(), 1);
    }

    #[test]
    fn d235_a_record_of_another_header_version_is_refused_not_relabelled() {
        let vault = TempVault::new();
        let ks = MemoryKeystore::default();
        let skrin = Skrin::open(&vault.path, &ks).unwrap();
        for version in [0u16, 2, u16::MAX] {
            let mut h = sample_header(5000 + version as u64);
            h.version = version;
            assert!(
                matches!(
                    skrin.put_record(&h, b"later"),
                    Err(SkrinError::InvalidInput(_))
                ),
                "{version}"
            );
        }
        assert!(skrin.all_records().unwrap().is_empty(), "nothing stored");
        let h = sample_header(9000);
        skrin.put_record(&h, b"now").unwrap();
        assert_eq!(skrin.all_records().unwrap(), vec![(h, b"now".to_vec())]);
    }

    #[test]
    fn deleting_a_group_keeps_personal_records_and_other_groups() {
        let vault = TempVault::new();
        let ks = MemoryKeystore::default();
        let skrin = Skrin::open(&vault.path, &ks).unwrap();
        let in_group = |millis: u64, g: u8| {
            let mut h = sample_header(millis);
            h.scope = Scope::Group([g; 16]);
            h
        };
        let mut personal = sample_header(1000);
        personal.scope = Scope::Personal;
        skrin.put_record(&personal, b"personal").unwrap();
        skrin.put_record(&in_group(2000, 0xA), b"a-1").unwrap();
        skrin.put_record(&in_group(2500, 0xA), b"a-2").unwrap();
        skrin.put_record(&in_group(3000, 0xB), b"b-1").unwrap();
        assert_eq!(skrin.all_records().unwrap().len(), 4);

        skrin.delete_group_records(&[0xA; 16]).unwrap();
        let left: Vec<Vec<u8>> = skrin
            .all_records()
            .unwrap()
            .into_iter()
            .map(|(_, blob)| blob)
            .collect();
        assert_eq!(left, vec![b"personal".to_vec(), b"b-1".to_vec()]);

        // A group with no rows is a no-op, not an error.
        skrin.delete_group_records(&[0xC; 16]).unwrap();
        assert_eq!(skrin.all_records().unwrap().len(), 2);
    }

    #[test]
    fn fresh_open_provisions_key_and_reaches_schema_v2() {
        let vault = TempVault::new();
        let ks = MemoryKeystore::default();
        assert!(ks.load().unwrap().is_none());
        let skrin = Skrin::open(&vault.path, &ks).expect("first open");
        assert_eq!(skrin.schema_version().unwrap(), SCHEMA_VERSION);
        assert!(
            ks.load().unwrap().is_some(),
            "key now lives in the keystore"
        );
    }

    #[test]
    fn restart_persists_records_hlc_and_cursors() {
        let vault = TempVault::new();
        let ks = MemoryKeystore::default();

        {
            let skrin = Skrin::open(&vault.path, &ks).unwrap();

            // Records through the real header path.
            let header = sample_header(1000);
            skrin.put_record(&header, b"record-0").unwrap();
            let mut header2 = sample_header(2000);
            header2.record_type = RecordType::Settlement;
            skrin.put_record(&header2, b"record-1").unwrap();

            // HLC state survives ticks.
            let mut hlc = Hlc::now([9; 16], 5000);
            hlc.tick(5000);
            skrin.save_hlc(&hlc).unwrap();

            skrin.kv_set("cursor/pulled", &42u64.to_be_bytes()).unwrap();
        }

        // Restart: everything comes back.
        let skrin = Skrin::open(&vault.path, &ks).unwrap();
        let records = skrin.all_records().unwrap();
        assert_eq!(records.len(), 2);
        assert_eq!(records[0].1, b"record-0");
        assert_eq!(records[1].0.record_type, RecordType::Settlement);
        assert_eq!(records[1].0.scope, Scope::Group([7; 16]));

        let hlc = skrin.load_hlc(&[9; 16]).unwrap().unwrap();
        assert_eq!((hlc.millis, hlc.counter), (5000, 1));

        assert_eq!(
            skrin.kv_get("cursor/pulled").unwrap(),
            Some(42u64.to_be_bytes().to_vec())
        );
    }

    #[test]
    fn wrong_key_is_rejected_never_silently_rekeyed() {
        let vault = TempVault::new();
        let ks1 = MemoryKeystore::default();
        Skrin::open(&vault.path, &ks1).unwrap();

        let ks2 = MemoryKeystore::default();
        match Skrin::open(&vault.path, &ks2) {
            Err(SkrinError::WrongKey) => {}
            other => panic!("expected WrongKey, got {:?}", other.map(|_| ())),
        }
        // The store still opens with its true key afterwards.
        Skrin::open(&vault.path, &ks1).unwrap();
    }

    #[test]
    fn at_rest_vault_holds_no_plaintext_and_no_key_material() {
        // A-17 headless half: keys exist only behind the keystore boundary,
        // and nothing readable lands in the vault file itself. D-200: the
        // record goes in as PLAINTEXT, as the shells store it since D-192,
        // so SQLCipher's page encryption alone must hide it.
        let vault = TempVault::new();
        let ks = MemoryKeystore::default();

        let plaintext = br#"{"note":"CANARY-SKRIN-HOTEL","amount":123400}"#;
        {
            let skrin = Skrin::open(&vault.path, &ks).unwrap();
            let header = sample_header(3000);
            skrin.put_record(&header, plaintext).unwrap();

            // The key's own bytes stored INSIDE the vault must not show either.
            let db_key = ks.load().unwrap().unwrap();
            skrin.kv_set("canary/key", db_key.as_bytes()).unwrap();
        }

        let raw = std::fs::read(&vault.path).unwrap();
        let haystack = String::from_utf8_lossy(&raw);
        assert!(
            !haystack.contains("CANARY-SKRIN"),
            "plaintext leaked into the vault"
        );
        assert!(
            !haystack.contains("123400"),
            "plaintext amount leaked into the vault"
        );
        // The DB key itself must not be recoverable from the file bytes:
        // SQLCipher derives its own page keys from PBKDF2 over the passphrase
        // form, so the raw 32 bytes should appear nowhere verbatim.
        let db_key = ks.load().unwrap().unwrap();
        assert!(
            !raw.windows(32).any(|w| w == db_key.as_bytes()),
            "raw database key found verbatim in the vault file"
        );
        assert!(
            !haystack.contains(&hex_encode(db_key.as_bytes())),
            "hex database key found in the vault file"
        );
    }

    #[test]
    fn newer_schema_refuses_to_open_rather_than_downgrading() {
        let vault = TempVault::new();
        let ks = MemoryKeystore::default();
        let skrin = Skrin::open(&vault.path, &ks).unwrap();

        // Simulate a vault written by a future build, via the keyed handle.
        skrin.set_user_version_for_test(99);
        drop(skrin);

        match Skrin::open(&vault.path, &ks) {
            Err(SkrinError::NewerSchema { found, latest }) => {
                assert_eq!((found, latest), (99, SCHEMA_VERSION));
            }
            other => panic!("expected NewerSchema, got {:?}", other.map(|_| ())),
        }
    }

    #[test]
    fn migration_runner_upgrades_an_empty_pre_schema_database() {
        let vault = TempVault::new();
        let ks = MemoryKeystore::default();

        // A pre-skn file: plain SQLite, no tables, user_version 0.
        Connection::open(&vault.path).unwrap();

        let skrin = Skrin::open(&vault.path, &ks).expect("migrates from v0");
        assert_eq!(skrin.schema_version().unwrap(), SCHEMA_VERSION);
        skrin.put_record(&sample_header(10), b"x").unwrap();
        assert_eq!(skrin.all_records().unwrap().len(), 1);

        // Reopening is idempotent: migrations never run twice.
        let again = Skrin::open(&vault.path, &ks).unwrap();
        assert_eq!(again.schema_version().unwrap(), SCHEMA_VERSION);
        assert_eq!(again.all_records().unwrap().len(), 1);
    }

    #[test]
    fn keystore_destroy_zeroizes_so_a_stolen_reference_opens_nothing() {
        let ks = MemoryKeystore::default();
        let vault = TempVault::new();
        Skrin::open(&vault.path, &ks).unwrap();
        assert!(ks.load().unwrap().is_some());
        ks.destroy().unwrap();
        assert!(ks.load().unwrap().is_none());
    }
}
