// D-200: the known-answer tests for the primitives core still uses. HKDF,
// AES-256-GCM, X25519 and Argon2id left with the relay's sealing; Ed25519
// verifies the signed FX and merchant tables, SHA-256 makes category ids.
use sha2::{Digest, Sha256};

fn hex(s: &str) -> Vec<u8> {
    (0..s.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap())
        .collect()
}

#[test]
fn kat_sha256_fips180_abc() {
    // FIPS 180-2 appendix B.1; re-derived with Python's hashlib (D-200).
    assert_eq!(
        Sha256::digest(b"abc").to_vec(),
        hex("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    );
}

#[test]
fn kat_ed25519_rfc8032_test1_and_test2() {
    use ed25519_dalek::{Signature, SigningKey, Verifier, VerifyingKey};

    let sk1_bytes = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60");
    let sk1 = SigningKey::from_bytes(sk1_bytes.as_slice().try_into().unwrap());
    assert_eq!(
        sk1.verifying_key().to_bytes(),
        hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a").as_slice()
    );
    let sig1: [u8; 64] = hex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b").try_into().unwrap();
    let sig1 = Signature::from_bytes(&sig1);
    assert!(sk1.verifying_key().verify(b"", &sig1).is_ok());

    let sk2_bytes = hex("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb");
    let sk2 = SigningKey::from_bytes(sk2_bytes.as_slice().try_into().unwrap());
    assert_eq!(
        sk2.verifying_key().to_bytes(),
        hex("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c").as_slice()
    );
    let sig2_bytes = hex("92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00");
    let msg2 = hex("72");
    let sig2 = Signature::from_bytes(sig2_bytes.as_slice().try_into().unwrap());
    assert!(sk2.verifying_key().verify(&msg2, &sig2).is_ok());

    let mut raw_sig = sig2.to_bytes();
    raw_sig[63] ^= 1;
    let forged = Signature::from_bytes(&raw_sig);
    let vk = VerifyingKey::from_bytes(&sk2.verifying_key().to_bytes()).unwrap();
    assert!(vk.verify(&msg2, &forged).is_err());
}
