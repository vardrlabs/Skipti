//! QR codes (D-159): the invite link as a QR code, drawn by the shells.
//!
//! ADR-0037: "the QR code on the inviter's screen is that link; the joiner's
//! own camera opens it". iOS has a QR generator in CoreImage and Android has
//! none, so the encoder lives here, once, for both (no new dependency). It is
//! deliberately small: byte mode, error-correction level M, versions 1 to 6
//! (up to 106 bytes; an invite link is 77). ISO/IEC 18004 is followed step by
//! step: the bit stream, Reed-Solomon over GF(256) with 0x11D, interleaving,
//! the function patterns, zig-zag placement, the eight masks scored by the
//! four penalty rules, and the BCH-coded format information.

/// A QR code: `size` x `size` modules, `true` = dark, row-major.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct QrCode {
    pub size: usize,
    pub version: u8,
    pub mask: u8,
    pub modules: Vec<bool>,
}

impl QrCode {
    pub fn get(&self, row: usize, col: usize) -> bool {
        self.modules[row * self.size + col]
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum QrError {
    TooLong,
}

/// Level M, versions 1..=6: (data codewords, EC codewords per block, blocks).
const LEVEL_M: [(usize, usize, usize); 6] = [
    (16, 10, 1),
    (28, 16, 1),
    (44, 26, 1),
    (64, 18, 2),
    (86, 24, 2),
    (108, 16, 4),
];

/// The second alignment-pattern coordinate for versions 2..=6 (the first is 6).
const ALIGN: [usize; 6] = [0, 18, 22, 26, 30, 34];

/// Level M's two format bits.
const LEVEL_M_BITS: u32 = 0b00;

/// Encodes `data` as a QR code (byte mode, level M), in the smallest version
/// that holds it.
pub fn encode(data: &[u8]) -> Result<QrCode, QrError> {
    let version = (1..=6u8)
        .find(|v| capacity(*v) >= data.len())
        .ok_or(QrError::TooLong)?;
    let codewords = codewords(data, version);
    let size = 17 + 4 * version as usize;
    let mut best: Option<(u32, QrCode)> = None;
    for mask in 0..8u8 {
        let code = build(&codewords, version, mask);
        let score = penalty(&code);
        if best.as_ref().is_none_or(|(s, _)| score < *s) {
            best = Some((score, code));
        }
    }
    let (_, code) = best.expect("eight masks tried");
    debug_assert_eq!(code.size, size);
    Ok(code)
}

/// Bytes a version holds in byte mode at level M.
fn capacity(version: u8) -> usize {
    let (data, _, _) = LEVEL_M[version as usize - 1];
    (data * 8 - 12) / 8
}

struct Bits(Vec<bool>);

impl Bits {
    fn push(&mut self, value: u32, len: usize) {
        for i in (0..len).rev() {
            self.0.push((value >> i) & 1 == 1);
        }
    }
}

/// The final codeword sequence: data (mode, count, bytes, terminator,
/// padding) split into blocks, each block's Reed-Solomon codewords, all
/// interleaved.
fn codewords(data: &[u8], version: u8) -> Vec<u8> {
    let (total_data, ec_len, blocks) = LEVEL_M[version as usize - 1];
    let mut bits = Bits(Vec::new());
    bits.push(0b0100, 4); // byte mode
    bits.push(data.len() as u32, 8); // count (8 bits below version 10)
    for b in data {
        bits.push(u32::from(*b), 8);
    }
    let room = total_data * 8;
    let terminator = (room - bits.0.len()).min(4);
    bits.push(0, terminator);
    while !bits.0.len().is_multiple_of(8) {
        bits.0.push(false);
    }
    let mut bytes: Vec<u8> = bits
        .0
        .chunks(8)
        .map(|c| c.iter().fold(0u8, |acc, b| (acc << 1) | u8::from(*b)))
        .collect();
    let mut pad = [0xEC, 0x11].iter().cycle();
    while bytes.len() < total_data {
        bytes.push(*pad.next().expect("cycle"));
    }
    // Level M, versions 1..=6: every block holds the same number of data
    // codewords.
    let per_block = total_data / blocks;
    let data_blocks: Vec<&[u8]> = bytes.chunks(per_block).collect();
    let ec_blocks: Vec<Vec<u8>> = data_blocks
        .iter()
        .map(|b| reed_solomon(b, ec_len))
        .collect();
    let mut out = Vec::with_capacity(total_data + ec_len * blocks);
    for i in 0..per_block {
        for b in &data_blocks {
            out.push(b[i]);
        }
    }
    for i in 0..ec_len {
        for b in &ec_blocks {
            out.push(b[i]);
        }
    }
    out
}

fn gf_mul(a: u8, b: u8) -> u8 {
    let (mut a, mut b, mut out) = (u16::from(a), b, 0u16);
    while b != 0 {
        if b & 1 == 1 {
            out ^= a;
        }
        a <<= 1;
        if a & 0x100 != 0 {
            a ^= 0x11D;
        }
        b >>= 1;
    }
    out as u8
}

/// The `len` Reed-Solomon codewords of `data`: the remainder of
/// data(x) * x^len divided by the generator prod (x - 2^i), i < len.
pub fn reed_solomon(data: &[u8], len: usize) -> Vec<u8> {
    let mut generator = vec![1u8];
    let mut root = 1u8;
    for _ in 0..len {
        let mut next = vec![0u8; generator.len() + 1];
        for (i, g) in generator.iter().enumerate() {
            next[i] ^= *g;
            next[i + 1] ^= gf_mul(*g, root);
        }
        generator = next;
        root = gf_mul(root, 2);
    }
    let mut rem = vec![0u8; len];
    for d in data {
        let factor = d ^ rem[0];
        rem.remove(0);
        rem.push(0);
        for (i, g) in generator[1..].iter().enumerate() {
            rem[i] ^= gf_mul(*g, factor);
        }
    }
    rem
}

/// The 15 format bits for level M and `mask`: BCH(15,5) with 0x537, then
/// XOR 0x5412.
pub fn format_bits(mask: u8) -> u32 {
    let data = (LEVEL_M_BITS << 3) | u32::from(mask);
    let mut rem = data << 10;
    for i in (10..15).rev() {
        if rem & (1 << i) != 0 {
            rem ^= 0x537 << (i - 10);
        }
    }
    ((data << 10) | rem) ^ 0x5412
}

fn mask_bit(mask: u8, r: usize, c: usize) -> bool {
    match mask {
        0 => (r + c).is_multiple_of(2),
        1 => r.is_multiple_of(2),
        2 => c.is_multiple_of(3),
        3 => (r + c).is_multiple_of(3),
        4 => (r / 2 + c / 3).is_multiple_of(2),
        5 => (r * c) % 2 + (r * c) % 3 == 0,
        6 => ((r * c) % 2 + (r * c) % 3).is_multiple_of(2),
        _ => ((r + c) % 2 + (r * c) % 3).is_multiple_of(2),
    }
}

fn build(codewords: &[u8], version: u8, mask: u8) -> QrCode {
    let size = 17 + 4 * version as usize;
    let mut dark = vec![false; size * size];
    let mut function = vec![false; size * size];
    let mut set = |dark: &mut Vec<bool>, r: usize, c: usize, v: bool| {
        dark[r * size + c] = v;
        function[r * size + c] = true;
    };
    // Finder patterns with their separators.
    for (fr, fc) in [(0usize, 0usize), (0, size - 7), (size - 7, 0)] {
        for dr in -1i32..=7 {
            for dc in -1i32..=7 {
                let (r, c) = (fr as i32 + dr, fc as i32 + dc);
                if r < 0 || c < 0 || r >= size as i32 || c >= size as i32 {
                    continue;
                }
                let inside = (0..=6).contains(&dr) && (0..=6).contains(&dc);
                let on = inside
                    && (dr == 0
                        || dr == 6
                        || dc == 0
                        || dc == 6
                        || (2..=4).contains(&dr) && (2..=4).contains(&dc));
                set(&mut dark, r as usize, c as usize, on);
            }
        }
    }
    // Timing patterns.
    for i in 8..size - 8 {
        set(&mut dark, 6, i, i % 2 == 0);
        set(&mut dark, i, 6, i % 2 == 0);
    }
    // The alignment pattern (one, below version 7).
    if version >= 2 {
        let at = ALIGN[version as usize - 1];
        for dr in -2i32..=2 {
            for dc in -2i32..=2 {
                let on = dr.abs() == 2 || dc.abs() == 2 || (dr == 0 && dc == 0);
                set(
                    &mut dark,
                    (at as i32 + dr) as usize,
                    (at as i32 + dc) as usize,
                    on,
                );
            }
        }
    }
    // Reserve the format areas and place the dark module.
    let format = format_bits(mask);
    let fbit = |i: usize| (format >> i) & 1 == 1;
    for i in 0..=5 {
        set(&mut dark, i, 8, fbit(i));
    }
    set(&mut dark, 7, 8, fbit(6));
    set(&mut dark, 8, 8, fbit(7));
    set(&mut dark, 8, 7, fbit(8));
    for i in 9..15 {
        set(&mut dark, 8, 14 - i, fbit(i));
    }
    for i in 0..8 {
        set(&mut dark, 8, size - 1 - i, fbit(i));
    }
    for i in 8..15 {
        set(&mut dark, size - 15 + i, 8, fbit(i));
    }
    set(&mut dark, size - 8, 8, true);
    // Data: zig-zag in column pairs from the right, skipping column 6.
    let bits: Vec<bool> = codewords
        .iter()
        .flat_map(|b| (0..8).rev().map(move |i| (b >> i) & 1 == 1))
        .collect();
    let mut next = 0;
    let mut col = size as i32 - 1;
    let mut upward = true;
    while col > 0 {
        if col == 6 {
            col -= 1;
        }
        for step in 0..size {
            let r = if upward { size - 1 - step } else { step };
            for c in [col as usize, col as usize - 1] {
                if function[r * size + c] {
                    continue;
                }
                let bit = bits.get(next).copied().unwrap_or(false);
                next += 1;
                dark[r * size + c] = bit ^ mask_bit(mask, r, c);
            }
        }
        upward = !upward;
        col -= 2;
    }
    QrCode {
        size,
        version,
        mask,
        modules: dark,
    }
}

/// ISO 18004's four penalty rules; the mask with the lowest score is used.
fn penalty(code: &QrCode) -> u32 {
    let n = code.size;
    let at = |r: usize, c: usize| code.get(r, c);
    let mut score = 0u32;
    // Rule 1: runs of five or more in a row or column.
    for horizontal in [true, false] {
        for i in 0..n {
            let mut run = 1;
            for j in 1..n {
                let (a, b) = if horizontal {
                    (at(i, j), at(i, j - 1))
                } else {
                    (at(j, i), at(j - 1, i))
                };
                if a == b {
                    run += 1;
                } else {
                    if run >= 5 {
                        score += 3 + (run - 5);
                    }
                    run = 1;
                }
            }
            if run >= 5 {
                score += 3 + (run - 5);
            }
        }
    }
    // Rule 2: 2x2 blocks of one colour.
    for r in 0..n - 1 {
        for c in 0..n - 1 {
            let v = at(r, c);
            if at(r, c + 1) == v && at(r + 1, c) == v && at(r + 1, c + 1) == v {
                score += 3;
            }
        }
    }
    // Rule 3: finder-like 1:1:3:1:1 with four light modules on a side.
    let pattern = [
        true, false, true, true, true, false, true, false, false, false, false,
    ];
    for horizontal in [true, false] {
        for i in 0..n {
            for j in 0..=n.saturating_sub(11) {
                let line = |k: usize| {
                    if horizontal {
                        at(i, j + k)
                    } else {
                        at(j + k, i)
                    }
                };
                let forward = (0..11).all(|k| line(k) == pattern[k]);
                let backward = (0..11).all(|k| line(k) == pattern[10 - k]);
                if forward {
                    score += 40;
                }
                if backward {
                    score += 40;
                }
            }
        }
    }
    // Rule 4: the share of dark modules away from 50%.
    let darks = code.modules.iter().filter(|m| **m).count();
    let percent = darks * 100 / (n * n);
    let deviation = percent.abs_diff(50) / 5;
    score + 10 * deviation as u32
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The published worked example (thonky.com's QR tutorial, "HELLO
    /// WORLD" at 1-M): these sixteen data codewords have exactly these ten
    /// error-correction codewords.
    #[test]
    fn reed_solomon_matches_the_published_example() {
        let data = [
            32, 91, 11, 120, 209, 114, 220, 77, 67, 64, 236, 17, 236, 17, 236, 17,
        ];
        assert_eq!(
            reed_solomon(&data, 10),
            vec![196, 35, 39, 119, 235, 215, 231, 226, 93, 23]
        );
    }

    /// ISO 18004's format-information table for level M.
    #[test]
    fn format_bits_match_the_published_table() {
        let table = [
            0b101010000010010,
            0b101000100100101,
            0b101111001111100,
            0b101101101001011,
            0b100010111111001,
            0b100000011001110,
            0b100111110010111,
            0b100101010100000,
        ];
        for (mask, expected) in table.iter().enumerate() {
            assert_eq!(format_bits(mask as u8), *expected, "mask {mask}");
        }
    }

    #[test]
    fn an_invite_link_fits_version_five_and_longer_text_is_refused() {
        let link = format!("https://skipti.vardrlabs.com/join#{}", "A".repeat(43));
        assert_eq!(link.len(), 77);
        let code = encode(link.as_bytes()).unwrap();
        assert_eq!((code.version, code.size), (5, 37));
        assert_eq!(code.modules.len(), 37 * 37);
        assert_eq!(encode(&[0u8; 106]).unwrap().version, 6);
        assert_eq!(encode(&[0u8; 107]), Err(QrError::TooLong));
        assert_eq!(encode(b"").unwrap().version, 1);
    }

    /// The fixed patterns sit where every reader looks for them.
    #[test]
    fn finders_timing_and_the_dark_module_are_in_place() {
        let code = encode(b"skipti").unwrap();
        let n = code.size;
        for (r, c) in [(0, 0), (0, n - 7), (n - 7, 0)] {
            for i in 0..7 {
                assert!(code.get(r, c + i) && code.get(r + 6, c + i));
                assert!(code.get(r + i, c) && code.get(r + i, c + 6));
            }
            assert!(code.get(r + 3, c + 3), "finder centre");
            assert!(!code.get(r + 1, c + 1), "finder ring");
        }
        for i in 8..n - 8 {
            assert_eq!(code.get(6, i), i % 2 == 0);
            assert_eq!(code.get(i, 6), i % 2 == 0);
        }
        assert!(code.get(n - 8, 8), "the dark module");
    }
}
