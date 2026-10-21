//! lykill: randomness for keys and identifiers.
//!
//! D-200: the relay-era cryptography that lived here (HKDF, AEAD envelopes,
//! X25519 key agreement and wrapping, group-key rotation, escrow, the BIP-39
//! Recovery Kit and the six-word safety phrase) went with the relay (D-192,
//! ADR-0040). What stays is the operating system's random source, used for
//! the vault's database key, group and participant ids and category ids.

/// `n` bytes from the operating system's cryptographic random source.
pub fn random_bytes(n: usize) -> Vec<u8> {
    use rand_core::OsRng;
    use rand_core::RngCore;
    let mut buf = vec![0u8; n];
    OsRng.fill_bytes(&mut buf);
    buf
}

#[cfg(test)]
mod tests {
    use super::random_bytes;

    #[test]
    fn random_bytes_are_the_length_asked_and_not_repeated() {
        assert_eq!(random_bytes(32).len(), 32);
        assert_eq!(random_bytes(0).len(), 0);
        // 256-bit draws: a repeat is ~2^-256, so this negative needs no pinning (AGENTS.md §7).
        assert_ne!(random_bytes(32), random_bytes(32));
    }
}
