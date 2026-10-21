pub mod check;
pub mod groups;
pub mod identity;
pub mod revision;

use crate::money::Money;

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct Hlc {
    pub millis: u64,
    pub counter: u32,
    pub device_id: [u8; 16],
}

impl Hlc {
    pub fn now(device_id: [u8; 16], wall_millis: u64) -> Hlc {
        Hlc {
            millis: wall_millis,
            counter: 0,
            device_id,
        }
    }

    /// The next stamp this device writes (ADR-0034's tick rule).
    ///
    /// D-235 (decision 110 b): a stamp this phone writes is never more than
    /// [`MAX_OWN_LEAD_MS`] ahead of `wall_millis`, so the cloud's rule (a
    /// record at most 24 h past the server's time) never refuses an honest
    /// record. A state already further ahead (a clock that ran fast, or a
    /// 0.4.x phone dragged ahead before the receive bound existed) stamps at
    /// `wall + MAX_OWN_LEAD_MS` instead, in a NEW ERA: the counter's top
    /// eight bits ([`ERA_SHIFT`]) are an era that never goes down along this
    /// device's stamps, and lowering the clock always starts the next one, so
    /// a lowered stamp can never equal one this device wrote before (until
    /// 255 eras are spent). Edits still order correctly after a lowered
    /// stamp: the expense projection is causal (D-235 round 2).
    pub fn tick(&mut self, wall_millis: u64) {
        self.tick_by(wall_millis, 0);
    }

    /// D-237 (the Fable audit, A2): `tick` with a random `step` (at most
    /// [`STEP_MASK`]) added to the counter. Without it a phone's next stamps
    /// were predictable: once it received a record stamped at T (up to 23 h
    /// ahead, which the rules allow), its next ids were (T, c+1), (T, c+2),
    /// ... with its device id in every record, and a co-member could list
    /// them in a revision's `seen` and so pre-empt every edit that phone
    /// made to that expense for a day. The step makes a next id one guess
    /// in about a million. Within a millisecond the step counts in the low
    /// 24 bits; past them it carries into the next millisecond (never into
    /// the era bits), so the same stamp is never written twice, and no
    /// stamp sorts below one this device wrote except at a lowering, which
    /// starts a new era for that reason. `step` 0 is the old tick.
    pub fn tick_by(&mut self, wall_millis: u64, step: u32) {
        let step = step & STEP_MASK;
        let bound = wall_millis.saturating_add(MAX_OWN_LEAD_MS);
        // Round 2 (review M1): a clock pinned at the bound by receives carries
        // into the next millisecond every few stamps now; lowering it at once
        // would spend an era each time, and the eras are 255. The carries may
        // run CARRY_SLACK_MS past the bound before a lowering (receive never
        // moves the state into the slack).
        if self.millis > bound.saturating_add(CARRY_SLACK_MS) {
            let era = (self.counter >> ERA_SHIFT).saturating_add(1).min(ERA_MAX);
            self.millis = bound;
            self.counter = (era << ERA_SHIFT) | step;
        } else if wall_millis > self.millis {
            self.millis = wall_millis;
            self.counter = era_base(self.counter) | step;
        } else {
            self.set_after_by(self.millis, self.counter, step);
        }
    }

    /// Merges a remote stamp into this clock (ADR-0034's receive rule), so
    /// the next local stamp sorts after everything seen. Pure: the wall
    /// clock comes in as an argument.
    ///
    /// D-235 (decision 104, the lost D-158 rule): a remote stamp more than
    /// [`MAX_REMOTE_SKEW_MS`] ahead of `wall_millis` does not move this
    /// clock at all. The record it came on is still stored and shown with
    /// its own stamp; it just cannot drag this phone's clock (and through
    /// it every phone this one writes to) into the future for good.
    ///
    /// D-235 (decision 110 b): nor does any remote stamp move this clock past
    /// `wall + MAX_OWN_LEAD_MS`, the furthest this phone stamps its own
    /// records: the state stops there (never below where it was), so the
    /// next own stamp needs no lowering. A state already past that line is
    /// left for `tick` to bring back.
    pub fn receive(&mut self, other: &Hlc, wall_millis: u64) {
        if other.millis > wall_millis.saturating_add(MAX_REMOTE_SKEW_MS) {
            return;
        }
        let bound = wall_millis.saturating_add(MAX_OWN_LEAD_MS);
        let new_millis = self.millis.max(other.millis).max(wall_millis);
        if new_millis > bound {
            if self.millis < bound {
                self.millis = bound;
                self.counter = era_base(self.counter);
            }
        } else if new_millis == self.millis && new_millis == other.millis {
            self.set_after(new_millis, self.counter.max(other.counter));
        } else if new_millis == self.millis {
            self.set_after(new_millis, self.counter);
        } else if new_millis == other.millis {
            // Never below this phone's own era (another device's counter can
            // be in an earlier one), so a later lowering cannot repeat a stamp.
            self.set_after(new_millis, other.counter.max(era_base(self.counter)));
        } else {
            self.millis = new_millis;
            self.counter = era_base(self.counter);
        }
    }

    /// The smallest stamp after `(millis, counter)` on this device: the
    /// counter plus one, or, when the counter's low bits are full, the next
    /// millisecond with the counter at its era's start (D-235: the counter
    /// never wraps, so a stamp never sorts below one already seen, and never
    /// repeats one).
    fn set_after(&mut self, millis: u64, counter: u32) {
        self.set_after_by(millis, counter, 0);
    }

    /// D-237 (A2): `set_after` by `step + 1`: the low 24 bits count; what
    /// does not fit carries into the next millisecond, the era kept.
    fn set_after_by(&mut self, millis: u64, counter: u32, step: u32) {
        let next = u64::from(counter & LOW_MASK) + 1 + u64::from(step & STEP_MASK);
        if next <= u64::from(LOW_MASK) {
            self.millis = millis;
            self.counter = era_base(counter) | next as u32;
        } else {
            self.millis = millis.saturating_add(1);
            self.counter = era_base(counter) | (next - u64::from(LOW_MASK) - 1) as u32;
        }
    }
}

/// D-235 (decision 110 b): the counter's top eight bits are the era (see
/// [`Hlc::tick`]); the low 24 bits count within a millisecond (16.7 million
/// stamps a millisecond, past any phone). Every phone that never lowered its
/// clock is in era 0, so its counters read exactly as before.
pub const ERA_SHIFT: u32 = 24;
const ERA_MAX: u32 = 0xFF;

/// A new millisecond's first counter: 0 in the same era.
fn era_base(counter: u32) -> u32 {
    (counter >> ERA_SHIFT) << ERA_SHIFT
}

/// D-237 (A2): the counter's low 24 bits, the count within a millisecond.
const LOW_MASK: u32 = (1 << ERA_SHIFT) - 1;

/// D-237 round 2 (review M1): how far past `wall + MAX_OWN_LEAD_MS` this
/// phone's own carries may run before the clock is lowered into a new era:
/// 5 minutes, 300,000 milliseconds, about 4.8 million stamps at 16 a
/// millisecond, so a clock pinned at the bound spends no era on an import.
/// Still 55 minutes inside the cloud's 24-hour rule.
pub const CARRY_SLACK_MS: u64 = 5 * 60 * 1000;

/// D-237 (A2): the random step an own stamp adds to the counter: 20 bits,
/// so a next id is one guess in about a million (a revision's blob of
/// 192 KiB, the rules' cap, holds about 3,900 guessed ids in its `seen`),
/// and the low 24 bits still hold 16 stamps a millisecond before a carry.
pub const STEP_BITS: u32 = 20;
pub const STEP_MASK: u32 = (1 << STEP_BITS) - 1;

/// D-237 (A2): a step for [`Hlc::tick_by`], from the operating system's
/// random source (`ffi_hlc_next` draws one per stamp).
pub fn random_step() -> u32 {
    let b = crate::lykill::random_bytes(4);
    u32::from_be_bytes([b[0], b[1], b[2], b[3]]) & STEP_MASK
}

/// D-235 (decision 110 b): the furthest ahead of this phone's wall clock its
/// own stamps go: 23 hours, an hour inside the cloud's 24-hour rule, so a
/// record this phone writes is never refused for its stamp.
pub const MAX_OWN_LEAD_MS: u64 = 23 * 60 * 60 * 1000;

/// D-235 (decision 104): how far ahead of this phone's wall clock a remote
/// record's stamp may be and still move this phone's clock: 24 hours, far
/// past any honest drift or time zone mistake (a phone's clock is UTC
/// milliseconds), short of a date set years ahead.
pub const MAX_REMOTE_SKEW_MS: u64 = 24 * 60 * 60 * 1000;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RecordType {
    Expense,
    Settlement,
    GroupOperation,
    Envelope,
    /// D-141: an edit or tombstone of an expense (SPEC §5.7). Its own type,
    /// never a flag on Expense: a build that read a revision as a creation
    /// would count the expense twice.
    ExpenseRevision,
    /// D-210: a removed person's balance moved onto chosen members (norn::redistribution). Its own type: an older core skips it rather than misreading it as a settlement.
    Redistribution,
}

impl RecordType {
    pub fn as_u8(self) -> u8 {
        match self {
            RecordType::Expense => 0x01,
            RecordType::Settlement => 0x02,
            RecordType::GroupOperation => 0x03,
            RecordType::Envelope => 0x04,
            RecordType::ExpenseRevision => 0x05,
            RecordType::Redistribution => 0x06,
        }
    }

    pub fn from_u8(v: u8) -> Option<RecordType> {
        match v {
            0x01 => Some(RecordType::Expense),
            0x02 => Some(RecordType::Settlement),
            0x03 => Some(RecordType::GroupOperation),
            0x04 => Some(RecordType::Envelope),
            0x05 => Some(RecordType::ExpenseRevision),
            0x06 => Some(RecordType::Redistribution),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Scope {
    Personal,
    Group([u8; 16]),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RecordHeader {
    pub version: u16,
    pub record_type: RecordType,
    pub scope: Scope,
    pub epoch: u32,
    pub hlc: Hlc,
    pub author_device_id: [u8; 16],
    pub nonce_session: [u8; 4],
    pub flags: u8,
}

pub const RECORD_HEADER_VERSION: u16 = 1;

impl RecordHeader {
    pub fn canonical_bytes(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(64);
        out.extend_from_slice(&self.version.to_be_bytes());
        out.push(self.record_type.as_u8());
        match self.scope {
            Scope::Personal => out.extend_from_slice(&[0u8; 17]),
            Scope::Group(g) => {
                out.push(0xFF);
                out.extend_from_slice(&g);
            }
        }
        out.extend_from_slice(&self.epoch.to_be_bytes());
        out.extend_from_slice(&self.hlc.millis.to_be_bytes());
        out.extend_from_slice(&self.hlc.counter.to_be_bytes());
        out.extend_from_slice(&self.hlc.device_id);
        out.extend_from_slice(&self.author_device_id);
        out.extend_from_slice(&self.nonce_session);
        out.push(self.flags);
        out
    }

    /// The inverse of [RecordHeader::canonical_bytes] (ADR-0034): the sync
    /// wire is exactly `canonical header ‖ blob`, so unpacking must be the
    /// header's own parse, in straumr, next to the layout it mirrors. A
    /// wrong version, unknown type byte, or malformed scope marker is a
    /// typed error, never a silent misparse.
    pub fn from_canonical_bytes(bytes: &[u8]) -> Result<Self, HeaderDecodeError> {
        if bytes.len() != 73 {
            return Err(HeaderDecodeError::Length { got: bytes.len() });
        }
        let version = u16::from_be_bytes([bytes[0], bytes[1]]);
        if version != RECORD_HEADER_VERSION {
            return Err(HeaderDecodeError::Version(version));
        }
        let record_type =
            RecordType::from_u8(bytes[2]).ok_or(HeaderDecodeError::RecordType(bytes[2]))?;
        let scope = match bytes[3] {
            0u8 => {
                if bytes[4..20].iter().any(|&b| b != 0) {
                    return Err(HeaderDecodeError::Scope(bytes[3]));
                }
                Scope::Personal
            }
            0xFF => Scope::Group(
                bytes[4..20]
                    .try_into()
                    .map_err(|_| HeaderDecodeError::Scope(bytes[3]))?,
            ),
            other => return Err(HeaderDecodeError::Scope(other)),
        };
        let epoch = u32::from_be_bytes([bytes[20], bytes[21], bytes[22], bytes[23]]);
        let millis = u64::from_be_bytes([
            bytes[24], bytes[25], bytes[26], bytes[27], bytes[28], bytes[29], bytes[30], bytes[31],
        ]);
        let counter = u32::from_be_bytes([bytes[32], bytes[33], bytes[34], bytes[35]]);
        Ok(RecordHeader {
            version,
            record_type,
            scope,
            epoch,
            hlc: Hlc {
                millis,
                counter,
                device_id: bytes[36..52]
                    .try_into()
                    .map_err(|_| HeaderDecodeError::Length { got: bytes.len() })?,
            },
            author_device_id: bytes[52..68]
                .try_into()
                .map_err(|_| HeaderDecodeError::Length { got: bytes.len() })?,
            nonce_session: bytes[68..72]
                .try_into()
                .map_err(|_| HeaderDecodeError::Length { got: bytes.len() })?,
            flags: bytes[72],
        })
    }
}

/// ADR-0034: why a packed record's header refused to parse. Typed, not a
/// string, so the FFI layer maps each cause honestly.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HeaderDecodeError {
    Length { got: usize },
    Version(u16),
    RecordType(u8),
    Scope(u8),
}

#[derive(Debug, Clone)]
pub struct LogEntry {
    pub index: u64,
    pub blob: Vec<u8>,
}

/// Per-device sync cursor: sync by log index, never a state vector
/// (SPEC §6.3: an index leaks strictly less than per-device op counts).
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct SyncCursor {
    pub last_pulled: u64,
    pub last_pushed: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum MergeOutcome {
    RemoteWins,
    LocalWins,
    SameValue,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AmountConflict {
    pub local: Money,
    pub remote: Money,
    pub local_stamp: Hlc,
    pub remote_stamp: Hlc,
}

/// Field-level last-writer-wins register stamped by a hybrid logical clock.
/// Two devices editing different fields always both survive; the same field
/// resolves deterministically by HLC order.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LwwRegister<T> {
    pub value: T,
    pub stamp: Hlc,
}

impl<T: PartialEq> LwwRegister<T> {
    pub fn new(value: T, stamp: Hlc) -> LwwRegister<T> {
        LwwRegister { value, stamp }
    }

    pub fn merge(&mut self, incoming: LwwRegister<T>) -> MergeOutcome {
        if incoming.value == self.value {
            if incoming.stamp > self.stamp {
                self.stamp = incoming.stamp;
            }
            return MergeOutcome::SameValue;
        }
        if incoming.stamp > self.stamp {
            self.value = incoming.value;
            self.stamp = incoming.stamp;
            MergeOutcome::RemoteWins
        } else {
            MergeOutcome::LocalWins
        }
    }
}

/// Amounts are the one case surfaced to the user instead of auto-resolved
/// (SPEC §6.1): this returns Err(AmountConflict) whenever two devices set
/// different amounts, regardless of which is newer.
pub fn merge_amount_field(
    local: &LwwRegister<Money>,
    remote: &LwwRegister<Money>,
) -> Result<LwwRegister<Money>, Box<AmountConflict>> {
    if local.value == remote.value {
        return Ok(LwwRegister {
            value: local.value,
            stamp: local.stamp.max(remote.stamp),
        });
    }
    Err(Box::new(AmountConflict {
        local: local.value,
        remote: remote.value,
        local_stamp: local.stamp,
        remote_stamp: remote.stamp,
    }))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn dev(n: u8) -> [u8; 16] {
        let mut b = [0u8; 16];
        b[15] = n;
        b
    }

    #[test]
    fn record_types_round_trip_and_unknowns_are_refused() {
        for v in 0x01..=0x06u8 {
            assert_eq!(RecordType::from_u8(v).map(RecordType::as_u8), Some(v));
        }
        assert_eq!(RecordType::from_u8(0x06), Some(RecordType::Redistribution));
        assert_eq!(RecordType::from_u8(0x07), None);
    }

    #[test]
    fn hlc_orders_deterministically_across_devices() {
        let a = Hlc::now(dev(1), 1000);
        let mut b = Hlc::now(dev(2), 999);
        assert!(a > b);
        b.receive(&a, 995);
        assert!(b > a, "receiving advances past the observed clock");
    }

    #[test]
    fn receive_ignores_a_stamp_more_than_a_day_ahead_of_the_wall_clock() {
        // Probe P2's scenario: an honest phone in 2026 pulls a record
        // stamped in 2100. Its clock must not move.
        let wall = 1_790_000_000_000u64; // 2026-09
        let mut honest = Hlc {
            millis: wall,
            counter: 3,
            device_id: dev(1),
        };
        let from_2100 = Hlc {
            millis: 4_102_444_800_000,
            counter: 0,
            device_id: dev(2),
        };
        honest.receive(&from_2100, wall);
        assert_eq!((honest.millis, honest.counter), (wall, 3), "untouched");
        honest.tick(wall);
        assert_eq!((honest.millis, honest.counter), (wall, 4));

        // The bound itself: exactly 24 h ahead is not ignored, one
        // millisecond past it is. D-235 round 3 (decision 110 b): a stamp
        // that is not ignored still moves this clock no further than the
        // 23 h this phone stamps its own records at.
        let day = 24 * 60 * 60 * 1000u64;
        assert_eq!(MAX_REMOTE_SKEW_MS, day);
        let mut a = Hlc::now(dev(1), wall);
        a.receive(
            &Hlc {
                millis: wall + day,
                counter: 7,
                device_id: dev(2),
            },
            wall,
        );
        assert_eq!((a.millis, a.counter), (wall + MAX_OWN_LEAD_MS, 0));
        let mut b = Hlc::now(dev(1), wall);
        b.receive(
            &Hlc {
                millis: wall + day + 1,
                counter: 7,
                device_id: dev(2),
            },
            wall,
        );
        assert_eq!((b.millis, b.counter), (wall, 0));

        // A hostile maximum stamp never panics and never moves the clock.
        let mut c = Hlc::now(dev(1), wall);
        c.receive(
            &Hlc {
                millis: u64::MAX,
                counter: u32::MAX,
                device_id: dev(2),
            },
            wall,
        );
        assert_eq!((c.millis, c.counter), (wall, 0));
        // A wall clock near the end of time cannot overflow the bound.
        let mut d = Hlc::now(dev(1), u64::MAX - 1);
        d.receive(&Hlc::now(dev(2), u64::MAX), u64::MAX - 1);
        assert_eq!((d.millis, d.counter), (u64::MAX, 1));
    }

    #[test]
    fn a_full_counter_carries_into_the_next_millisecond_never_wraps() {
        // Scenario B's counter: u32::MAX + 1 wrapped to 0 in the release
        // core and sorted the next stamp BELOW the one just seen. D-235 round
        // 3: the carry keeps the counter's era (here the last, 255).
        let full_era = 0xFFu32 << ERA_SHIFT;
        let wall = 1_790_000_000_000u64;
        let seen = Hlc {
            millis: wall,
            counter: u32::MAX,
            device_id: dev(2),
        };
        let mut a = Hlc::now(dev(1), wall);
        a.receive(&seen, wall);
        assert_eq!((a.millis, a.counter), (wall + 1, full_era));
        assert!(a > seen);

        // Same from the state's own counter, in receive and in tick.
        let mut b = Hlc {
            millis: wall,
            counter: u32::MAX,
            device_id: dev(1),
        };
        let before = b;
        b.tick(wall);
        assert_eq!((b.millis, b.counter), (wall + 1, full_era));
        assert!(b > before);
        let mut c = Hlc {
            millis: wall,
            counter: u32::MAX,
            device_id: dev(1),
        };
        c.receive(&Hlc::now(dev(2), wall - 5), wall);
        assert_eq!((c.millis, c.counter), (wall + 1, full_era));
        let mut d = Hlc {
            millis: wall,
            counter: u32::MAX,
            device_id: dev(1),
        };
        d.receive(
            &Hlc {
                millis: wall,
                counter: 9,
                device_id: dev(2),
            },
            wall - 5,
        );
        assert_eq!((d.millis, d.counter), (wall + 1, full_era));
    }

    /// D-235 round 3 (decision 110 b), pinned from
    /// `.drafts/d235/a/hlc_goldens.py` (an independent model of the bounded
    /// tick and receive).
    #[test]
    fn d235_own_stamps_stay_within_23_hours_of_the_wall_clock() {
        const W: u64 = 1_790_000_000_000;
        const H: u64 = 3_600_000;
        const D: u64 = 24 * H;
        assert_eq!(MAX_OWN_LEAD_MS, 23 * H);
        let st = |millis: u64, counter: u32| Hlc {
            millis,
            counter,
            device_id: dev(1),
        };
        let other = |millis: u64, counter: u32| Hlc {
            millis,
            counter,
            device_id: dev(2),
        };
        let ticked = |mut h: Hlc, wall: u64| {
            h.tick(wall);
            (h.millis, h.counter)
        };
        let received = |mut h: Hlc, o: Hlc, wall: u64| {
            h.receive(&o, wall);
            (h.millis, h.counter)
        };
        // A stored HLC three days ahead stamps at wall + 23 h, era 1.
        assert_eq!(ticked(st(W + 3 * D, 5), W), (1_790_082_800_000, 16_777_216));
        assert_eq!(
            ticked(st(1_790_082_800_000, 16_777_216), W),
            (1_790_082_800_000, 16_777_217)
        );
        // The era stays on a new millisecond.
        assert_eq!(
            ticked(st(W, (1 << 24) + 7), W + 5),
            (1_790_000_000_005, 16_777_216)
        );
        // A wall clock set two days back: lowered, in the next era.
        assert_eq!(ticked(st(W, 3), W - 2 * D), (1_789_910_000_000, 16_777_216));
        // Receive stops the state at wall + 23 h, never moves it back, and
        // leaves an already-ahead state for tick.
        assert_eq!(
            received(st(W, 3), other(W + 23 * H + 30 * 60_000, 9), W),
            (1_790_082_800_000, 0)
        );
        assert_eq!(
            received(st(W, 0), other(W + D, 7), W),
            (1_790_082_800_000, 0)
        );
        assert_eq!(received(st(W, 0), other(W + D + 1, 7), W), (W, 0));
        assert_eq!(
            received(st(W, 0), other(W + 5_000, 7), W),
            (1_790_000_005_000, 8)
        );
        assert_eq!(
            received(st(W + 3 * D, 5), other(W + 1, 0), W),
            (1_790_259_200_000, 5)
        );
        // Both keep this phone's era.
        assert_eq!(
            received(st(W, (1 << 24) + 3), other(W + 23 * H + 30 * 60_000, 9), W),
            (1_790_082_800_000, 16_777_216)
        );
        assert_eq!(
            received(st(W - 10, (1 << 24) + 3), other(W - 20, 4), W),
            (1_790_000_000_000, 16_777_216)
        );
        assert_eq!(
            received(st(W, (1 << 24) + 3), other(W + 5_000, 7), W),
            (1_790_000_005_000, 16_777_217)
        );
        // The last era saturates (255 lowerings spent).
        assert_eq!(
            ticked(st(W + 3 * D, 255 << 24), W),
            (1_790_082_800_000, 4_278_190_080)
        );
    }

    /// D-235 round 3: a phone whose stored HLC is three days ahead, writing
    /// thousands of stamps while its wall clock runs, jumps back and forth,
    /// and records arrive, never repeats an id it wrote (before or after the
    /// lowering) and never stamps past wall + 23 h.
    #[test]
    fn d235_a_lowered_clock_never_repeats_an_id() {
        const W: u64 = 1_790_000_000_000;
        const D: u64 = 24 * 60 * 60 * 1000;
        let mut written = std::collections::BTreeSet::new();
        // What a 0.4.x phone dragged ahead wrote: era 0, at and before the
        // dragged state, ending at W + 3 days (counter 5), and on its way
        // there exactly where the first lowering lands (W + 23 h).
        let mut h = Hlc {
            millis: W + 3 * D - 40,
            counter: 0,
            device_id: dev(1),
        };
        for c in 0..6 {
            assert!(written.insert((W + MAX_OWN_LEAD_MS, c)));
        }
        for i in 0..40u64 {
            h.millis = W + 3 * D - 40 + i;
            for c in 0..6 {
                h.counter = c;
                assert!(written.insert((h.millis, h.counter)));
            }
        }
        let mut wall = W;
        let mut seed: u64 = 0x5eed;
        for step in 0..20_000u32 {
            seed = seed.wrapping_mul(6_364_136_223_846_793_005).wrapping_add(1);
            let r = seed >> 33;
            match r % 10 {
                0 => wall += r % 5_000,
                1 if step % 500 == 0 => wall = wall.saturating_sub(2 * D),
                2 => h.receive(
                    &Hlc {
                        millis: wall + (r % (25 * 60 * 60 * 1000)),
                        counter: (r % 50) as u32,
                        device_id: dev(2),
                    },
                    wall,
                ),
                _ => {}
            }
            h.tick(wall);
            assert!(h.millis <= wall + MAX_OWN_LEAD_MS, "step {step}");
            assert!(
                written.insert((h.millis, h.counter)),
                "step {step}: {h:?} repeated"
            );
        }
    }

    /// D-237 (the Fable audit, A2): a received stamp used to pin this
    /// phone's next ids ((T, c+1), (T, c+2), ...), so a co-member could list
    /// them in a revision's `seen` and pre-empt every edit for a day. Each
    /// own stamp now steps the counter by its `step`; the carry goes to the
    /// next millisecond and keeps the era.
    #[test]
    fn d237_each_own_stamp_steps_the_counter_and_carries_past_the_low_bits() {
        let wall = 1_790_000_000_000u64;
        let mut a = Hlc {
            millis: wall,
            counter: 5,
            device_id: dev(1),
        };
        a.tick_by(wall, 100);
        assert_eq!((a.millis, a.counter), (wall, 106));
        a.tick_by(wall, 0);
        assert_eq!((a.millis, a.counter), (wall, 107), "step 0 is the old tick");
        a.tick(wall);
        assert_eq!((a.millis, a.counter), (wall, 108), "tick is step 0");
        // A new millisecond starts at the step.
        a.tick_by(wall + 1, 7);
        assert_eq!((a.millis, a.counter), (wall + 1, 7));
        // The low 24 bits full: the carry goes to the next millisecond and
        // keeps the era (never a 25th bit, which would be the next era).
        let era1 = 1u32 << ERA_SHIFT;
        let mut b = Hlc {
            millis: wall,
            counter: era1 | 0x00FF_FFF0,
            device_id: dev(1),
        };
        b.tick_by(wall, 0x20);
        assert_eq!((b.millis, b.counter), (wall + 1, era1 | 0x11));
        let mut b0 = Hlc {
            millis: wall,
            counter: 0x00FF_FFFF,
            device_id: dev(1),
        };
        b0.tick(wall);
        assert_eq!((b0.millis, b0.counter), (wall + 1, 0), "era 0 stays era 0");
        // The step is masked to STEP_BITS.
        let mut c = Hlc {
            millis: wall,
            counter: 0,
            device_id: dev(1),
        };
        c.tick_by(wall, u32::MAX);
        assert_eq!((c.millis, c.counter), (wall, STEP_MASK + 1));
        // A state just past the bound is inside the slack (round 2): no
        // lowering, the counter steps on; far past it (below) it is lowered.
        let mut d = Hlc {
            millis: wall + MAX_OWN_LEAD_MS + 1,
            counter: 0,
            device_id: dev(1),
        };
        d.tick_by(wall, 9);
        assert_eq!((d.millis, d.counter), (wall + MAX_OWN_LEAD_MS + 1, 10));
        // Stamps still never repeat and always rise, whatever the steps
        // (an LCG's, pinned), with receives of a co-member's stamps between.
        let mut h = Hlc::now(dev(1), wall);
        let mut last = h;
        let mut seed: u64 = 0x5eed;
        for i in 0..50_000u32 {
            seed = seed.wrapping_mul(6_364_136_223_846_793_005).wrapping_add(1);
            let r = seed >> 33;
            let now = wall + u64::from(i / 100);
            if r.is_multiple_of(7) {
                h.receive(
                    &Hlc {
                        millis: now + (r % 1000),
                        counter: (r % 50) as u32,
                        device_id: dev(2),
                    },
                    now,
                );
            }
            h.tick_by(now, r as u32);
            assert!(h > last, "step {i}: {h:?} after {last:?}");
            assert!(h.millis <= now + MAX_OWN_LEAD_MS, "step {i}");
            last = h;
        }
        // Round 2 (review M1): pinned at the bound by a co-member's stamps
        // (receive stops there), an import of 100,000 records spends no era:
        // the carries run into the slack, every stamp unique and rising, and
        // the state ends at most CARRY_SLACK_MS past the bound.
        let mut pinned = Hlc::now(dev(1), wall);
        pinned.receive(&Hlc::now(dev(2), wall + MAX_OWN_LEAD_MS + 3_600_000), wall);
        assert_eq!(pinned.millis, wall + MAX_OWN_LEAD_MS);
        let era = pinned.counter >> ERA_SHIFT;
        let mut last = pinned;
        let mut seen: std::collections::BTreeSet<(u64, u32)> = std::collections::BTreeSet::new();
        for i in 0..100_000u32 {
            seed = seed.wrapping_mul(6_364_136_223_846_793_005).wrapping_add(1);
            pinned.tick_by(wall, (seed >> 33) as u32);
            assert!(pinned > last, "pinned step {i}");
            assert!(
                seen.insert((pinned.millis, pinned.counter)),
                "pinned step {i} repeated"
            );
            assert_eq!(
                pinned.counter >> ERA_SHIFT,
                era,
                "pinned step {i}: an era spent"
            );
            assert!(
                pinned.millis <= wall + MAX_OWN_LEAD_MS + CARRY_SLACK_MS,
                "pinned step {i}"
            );
            last = pinned;
        }
        assert!(
            pinned.millis > wall + MAX_OWN_LEAD_MS,
            "the carries ran into the slack"
        );
        // Past the slack the clock is lowered, in the next era (as before).
        let mut far = Hlc {
            millis: wall + MAX_OWN_LEAD_MS + CARRY_SLACK_MS + 1,
            counter: 0,
            device_id: dev(1),
        };
        far.tick_by(wall, 3);
        assert_eq!(
            (far.millis, far.counter),
            (wall + MAX_OWN_LEAD_MS, (1 << ERA_SHIFT) | 3)
        );
        // The draw itself stays inside the mask.
        for _ in 0..64 {
            assert!(random_step() <= STEP_MASK);
        }
    }

    #[test]
    fn lww_different_fields_both_survive_same_field_resolves_by_hlc() {
        let mut note = LwwRegister::new("old note".to_string(), Hlc::now(dev(1), 10));
        let incoming_note = LwwRegister::new("remote note".to_string(), Hlc::now(dev(2), 20));
        assert_eq!(note.merge(incoming_note), MergeOutcome::RemoteWins);
        assert_eq!(note.value, "remote note");
    }
}
