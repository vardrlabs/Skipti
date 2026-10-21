package com.vardrlabs.skipti

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import uniffi.skipti_core.SkiptiKeystore
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The Android Keystore implementation of the `SkiptiKeystore` contract
 * D-42 defined and skrin depends on. Rust GENERATES the 32-byte database
 * key and hands it to [provision]; this class wraps it with a
 * hardware-backed AES-256-GCM key under one AndroidKeyStore alias and
 * stores only that ciphertext in app-private prefs. load() unwraps into
 * memory; destroy() wipes both halves. No export path exists anywhere:
 * A-17's "keys not present in backup" by construction, same as iOS's
 * KeychainKeystore.
 */
class AndroidKeystore(private val context: Context) : SkiptiKeystore {

    companion object {
        private const val ALIAS = "com.vardrlabs.skipti.vaultkey"
        private const val PREFS = "skipti-vault-key"
        private const val PREF_WRAPPED = "wrapped-dek"
        private const val GCM_TAG_BITS = 128

        /** Test seam: short-circuit prefs without touching Keystore. */
        internal var injectWrappedForTest: String? = null
    }

    override fun provision(key: ByteArray) {
        require(key.size == 32) { "skrin hands exactly a 32-byte key" }
        check(loadForTestOrReal() == null) { "provision called over an existing key" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey(generate = true))
        val sealed = cipher.doFinal(key)
        val iv = cipher.iv
        prefs().edit()
            .putString(PREF_WRAPPED, b64(iv) + ":" + b64(sealed))
            .apply()
    }

    override fun load(): ByteArray? {
        val stored = loadForTestOrReal() ?: return null
        val parts = stored.split(":")
        require(parts.size == 2) { "corrupt wrapped key record" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey(generate = false), GCMParameterSpec(GCM_TAG_BITS, unb64(parts[0])))
        return try {
            cipher.doFinal(unb64(parts[1]))
        } catch (_: Exception) {
            throw IllegalStateException("vault key unwrap failed: records do not match this device's keystore entry")
        }
    }

    override fun destroy() {
        prefs().edit().clear().apply()
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        ks.deleteEntry(ALIAS)
    }

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun loadForTestOrReal(): String? =
        injectWrappedForTest ?: prefs().getString(PREF_WRAPPED, null)

    private fun keystoreKey(generate: Boolean): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        check(generate) { "AndroidKeyStore has no key under $ALIAS" }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    private fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)
}
