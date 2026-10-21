-- skrin schema v1 (SPEC §3, §16.3 task 8).
-- Shapes stay open-ended by design: record payloads are opaque sealed blobs
-- keyed by header fields, so new operation types (including any future
-- reordered-list or collaborative-text shapes flagged in ADR-0018) ride
-- inside `blob` without a destructive migration.

CREATE TABLE hlc_state (
    device_id BLOB PRIMARY KEY,
    millis INTEGER NOT NULL,
    counter INTEGER NOT NULL
);

CREATE TABLE records (
    rid INTEGER PRIMARY KEY AUTOINCREMENT,
    record_type INTEGER NOT NULL,
    personal INTEGER NOT NULL,
    group_id BLOB,
    epoch INTEGER NOT NULL,
    hlc_millis INTEGER NOT NULL,
    hlc_counter INTEGER NOT NULL,
    hlc_device_id BLOB NOT NULL,
    author_device_id BLOB NOT NULL,
    nonce_session BLOB NOT NULL,
    flags INTEGER NOT NULL,
    blob BLOB NOT NULL
);

CREATE INDEX idx_records_scope_time ON records(group_id, epoch, hlc_millis);

CREATE TABLE group_keys (
    group_id BLOB PRIMARY KEY,
    epoch INTEGER NOT NULL,
    group_key BLOB NOT NULL
);

CREATE TABLE group_envelopes (
    group_id BLOB NOT NULL,
    recipient_device_id BLOB NOT NULL,
    wrapped BLOB NOT NULL,
    PRIMARY KEY (group_id, recipient_device_id)
);

CREATE TABLE device_keys (
    slot INTEGER PRIMARY KEY CHECK (slot = 0),
    device_id BLOB NOT NULL,
    signing_seed BLOB NOT NULL,
    agreement_scalar BLOB NOT NULL
);

CREATE TABLE kv (
    k TEXT PRIMARY KEY,
    v BLOB NOT NULL
);
