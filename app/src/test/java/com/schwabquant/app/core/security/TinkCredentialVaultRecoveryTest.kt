package com.schwabquant.app.core.security

import android.content.Context
import android.content.SharedPreferences
import com.google.crypto.tink.Aead
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStoreException
import java.security.ProviderException
import javax.crypto.AEADBadTagException

class TinkCredentialVaultRecoveryTest {

    @Test
    fun `H-4 isTransientKeystoreError walks causal chain safely including cycles`() {
        assertTrue(isTransientKeystoreError(ProviderException("StrongBox busy")))
        assertTrue(isTransientKeystoreError(GeneralSecurityException("wrapped", IOException("keystore binder timeout"))))
        assertTrue(
            isTransientKeystoreError(
                IllegalStateException("outer", GeneralSecurityException("mid", ProviderException("HAL transient")))
            )
        )
        assertFalse(isTransientKeystoreError(GeneralSecurityException("bad tag")))

        // Self-referential causal cycle must terminate cleanly without StackOverflowError
        val cyclicA = object : RuntimeException("cycleA") {
            override var cause: Throwable? = null
        }
        val cyclicB = object : RuntimeException("cycleB", cyclicA) {}
        cyclicA.cause = cyclicB
        assertFalse(isTransientKeystoreError(cyclicA))

        val transientInCycle = object : ProviderException("transientInCycle") {
            override var cause: Throwable? = cyclicA
        }
        cyclicA.cause = transientInCycle
        assertTrue(isTransientKeystoreError(cyclicA))
    }

    @Test
    fun `H-4 wipeAllCredentials rebuilds Aead in-process after startup keystore corruption without app restart`() = runTest {
        val prefs = InMemorySharedPreferences()
        val context = mockContextReturning(prefs)
        val healthyAead = FakeXorAead()

        var resetCount = 0
        val factory = object : TinkAeadFactory {
            override fun loadOrCreateInitialAead(): TinkAeadState = TinkAeadState(
                aead = null,
                keystoreResetOccurred = true,
                vaultUnavailableTransient = false
            )

            override fun resetAndCreateFreshAead(): TinkAeadState {
                resetCount++
                return TinkAeadState(
                    aead = healthyAead,
                    keystoreResetOccurred = true,
                    vaultUnavailableTransient = false
                )
            }
        }

        val vault = TinkCredentialVault(context, factory)
        assertTrue(vault.isVaultCorrupted())
        assertTrue(vault.authSnapshotFlow.first().vaultCorruptionDetected)

        assertThrows(IllegalStateException::class.java) {
            vault.saveAppCredentials("key1", "secret1", CredentialVault.DEFAULT_CALLBACK_URL)
        }

        // Reset in-process (same TinkCredentialVault instance, no restart)
        vault.wipeAllCredentials()

        assertEquals(1, resetCount)
        assertFalse(vault.isVaultCorrupted())
        val recoveredSnapshot = vault.authSnapshotFlow.first()
        assertFalse(recoveredSnapshot.vaultCorruptionDetected)
        assertFalse(recoveredSnapshot.vaultUnavailableTransient)

        vault.saveAppCredentials("appKeyLive", "appSecretLive", "https://127.0.0.1:8182")
        assertEquals("appKeyLive", vault.getAppKey())
        assertEquals("appSecretLive", vault.getAppSecret())
        assertTrue(vault.authSnapshotFlow.first().hasAppCredentials)
    }

    @Test
    fun `H-4 transient keystore fault recovers lazily without wiping encrypted tokens`() = runTest {
        val prefs = InMemorySharedPreferences()
        val context = mockContextReturning(prefs)
        val workingAead = FakeXorAead()

        // Pre-populate valid encrypted credentials
        var aeadAvailable = true
        var resetCalls = 0
        val factory = object : TinkAeadFactory {
            override fun loadOrCreateInitialAead(): TinkAeadState = if (aeadAvailable) {
                TinkAeadState(aead = workingAead, keystoreResetOccurred = false, vaultUnavailableTransient = false)
            } else {
                TinkAeadState(aead = null, keystoreResetOccurred = false, vaultUnavailableTransient = true)
            }

            override fun resetAndCreateFreshAead(): TinkAeadState {
                resetCalls++
                return TinkAeadState(aead = workingAead, keystoreResetOccurred = true, vaultUnavailableTransient = false)
            }
        }

        val seedVault = TinkCredentialVault(context, factory)
        seedVault.saveAppCredentials("preservedKey", "preservedSecret", CredentialVault.DEFAULT_CALLBACK_URL)

        // Simulate reboot while Keystore HAL is temporarily unavailable
        aeadAvailable = false
        val restartedVault = TinkCredentialVault(context, factory)
        assertFalse(restartedVault.isVaultCorrupted())
        assertTrue(restartedVault.authSnapshotFlow.first().vaultUnavailableTransient)
        assertNull(restartedVault.getAppKey())
        assertEquals(0, resetCalls)

        // Keystore HAL becomes available again -> lazy retry restores existing ciphertext!
        aeadAvailable = true
        assertEquals("preservedKey", restartedVault.getAppKey())
        assertEquals("preservedSecret", restartedVault.getAppSecret())
        val postRecovery = restartedVault.authSnapshotFlow.first()
        assertFalse(postRecovery.vaultUnavailableTransient)
        assertFalse(postRecovery.vaultCorruptionDetected)
        assertTrue(postRecovery.hasAppCredentials)
        assertEquals(0, resetCalls)
    }

    @Test
    fun `H-1 and M-4 transient encrypt on saveRefreshedAccessToken uses volatile memory, writes 0 plaintext, and auto-clears on recovery`() = runTest {
        val prefs = InMemorySharedPreferences()
        val context = mockContextReturning(prefs)
        val baseAead = FakeXorAead()
        var failEncryptTransient = false

        val flakeyAead = object : Aead {
            override fun encrypt(plaintext: ByteArray, associatedData: ByteArray?): ByteArray {
                if (failEncryptTransient) {
                    throw ProviderException("Transient KeyMint outage")
                }
                return baseAead.encrypt(plaintext, associatedData)
            }

            override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray?): ByteArray =
                baseAead.decrypt(ciphertext, associatedData)
        }
        val factory = object : TinkAeadFactory {
            override fun loadOrCreateInitialAead() = TinkAeadState(aead = flakeyAead)
            override fun resetAndCreateFreshAead() = TinkAeadState(aead = flakeyAead, keystoreResetOccurred = true)
        }

        val vault = TinkCredentialVault(context, factory)
        vault.saveAppCredentials("appKey1", "appSecret1", CredentialVault.DEFAULT_CALLBACK_URL)
        vault.saveInitialOAuthGrant("initAccess", "initRefresh", 1800L, 1_000L)

        // Keystore encrypt transiently fails during rotated refresh persistence
        failEncryptTransient = true
        vault.saveRefreshedAccessToken(
            accessToken = "volatile-access-99",
            rotatedRefreshToken = "volatile-refresh-99",
            accessExpiresInSeconds = 1800L,
            nowEpochMs = 2_000L
        )

        assertTrue(vault.authSnapshotFlow.first().vaultUnavailableTransient)
        assertFalse(vault.isVaultCorrupted())
        assertEquals("volatile-access-99", vault.getAccessToken())
        assertEquals("volatile-refresh-99", vault.getRefreshToken())
        // Hard security invariant: NEVER persist plaintext rotated tokens to SharedPreferences!
        assertTrue(prefs.all.values.none { it?.toString()?.contains("volatile-access-99") == true })
        assertTrue(prefs.all.values.none { it?.toString()?.contains("volatile-refresh-99") == true })

        // Keystore recovers -> subsequent decrypt() clears fault and flushes encrypted ciphertext to prefs
        failEncryptTransient = false
        assertEquals("appKey1", vault.getAppKey())
        assertFalse(vault.authSnapshotFlow.first().vaultUnavailableTransient)
        assertEquals("volatile-access-99", vault.getAccessToken())
    }

    @Test
    fun `H-1 reading getRefreshToken or getAccessToken immediately on recovery returns rotated volatile token and never stale ciphertext`() = runTest {
        val prefs = InMemorySharedPreferences()
        val context = mockContextReturning(prefs)
        val baseAead = FakeXorAead()
        var failEncryptTransient = false

        val flakeyAead = object : Aead {
            override fun encrypt(plaintext: ByteArray, associatedData: ByteArray?): ByteArray {
                if (failEncryptTransient) {
                    throw ProviderException("Transient KeyMint outage")
                }
                return baseAead.encrypt(plaintext, associatedData)
            }

            override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray?): ByteArray =
                baseAead.decrypt(ciphertext, associatedData)
        }
        val factory = object : TinkAeadFactory {
            override fun loadOrCreateInitialAead() = TinkAeadState(aead = flakeyAead)
            override fun resetAndCreateFreshAead() = TinkAeadState(aead = flakeyAead, keystoreResetOccurred = true)
        }

        val vault = TinkCredentialVault(context, factory)
        vault.saveAppCredentials("appKey1", "appSecret1", CredentialVault.DEFAULT_CALLBACK_URL)
        vault.saveInitialOAuthGrant("T_old", "R_old", 1800L, 1_000L)

        // Rotated refresh fails to encrypt cleanly -> T_new & R_new stay in volatile memory while prefs keep R_old & T_old
        failEncryptTransient = true
        vault.saveRefreshedAccessToken(
            accessToken = "T_new",
            rotatedRefreshToken = "R_new",
            accessExpiresInSeconds = 1800L,
            nowEpochMs = 2_000L
        )
        assertTrue(vault.authSnapshotFlow.first().vaultUnavailableTransient)

        // Keystore HAL recovers: immediate getRefreshToken() FIRST must flush and return R_new (NEVER R_old!)
        failEncryptTransient = false
        assertEquals("R_new", vault.getRefreshToken())
        assertEquals("T_new", vault.getAccessToken())
        assertFalse(vault.authSnapshotFlow.first().vaultUnavailableTransient)

        // Re-reading on a fresh vault backed by same prefs verifies R_new & T_new were durably encrypted
        val reloadedVault = TinkCredentialVault(context, factory)
        assertEquals("R_new", reloadedVault.getRefreshToken())
        assertEquals("T_new", reloadedVault.getAccessToken())
    }

    @Test
    fun `M-1 AndroidTinkAeadFactory skips wipe on generic KeyStoreException and TinkCredentialVault preserves corruptionDetected across lazy recovery`() = runTest {
        val prefs = InMemorySharedPreferences().apply {
            edit().putString("app_key", "preserved-ciphertext").commit()
        }
        val context = mockContextReturning(prefs)
        val healthyAead = FakeXorAead()
        var deletedMasterKey = false

        // Generic KeyStoreException -> transient only, zero prefs wipe, zero master key deletion
        val genericFailFactory = AndroidTinkAeadFactory(
            context = context,
            aeadBuilder = { throw KeyStoreException("HAL binder transient error") },
            masterKeyDeleter = { deletedMasterKey = true }
        )
        val transientState = genericFailFactory.loadOrCreateInitialAead()
        assertFalse(transientState.keystoreResetOccurred)
        assertTrue(transientState.vaultUnavailableTransient)
        assertFalse(deletedMasterKey)
        assertEquals("preserved-ciphertext", prefs.getString("app_key", null))

        // Explicit AEADBadTagException -> permanent corruption -> resets keyset
        val corruptFactory = AndroidTinkAeadFactory(
            context = context,
            aeadBuilder = {
                if (!deletedMasterKey) throw AEADBadTagException("permanent keyset corruption")
                healthyAead
            },
            masterKeyDeleter = { deletedMasterKey = true }
        )
        val resetState = corruptFactory.loadOrCreateInitialAead()
        assertTrue(resetState.keystoreResetOccurred)
        assertTrue(deletedMasterKey)

        // Vault preserves corruptionDetected = true across lazy recovery when startup had reset + transient unavailable
        var startupCalled = false
        val splitRecoveryFactory = object : TinkAeadFactory {
            override fun loadOrCreateInitialAead(): TinkAeadState = if (!startupCalled) {
                startupCalled = true
                TinkAeadState(aead = null, keystoreResetOccurred = true, vaultUnavailableTransient = true)
            } else {
                TinkAeadState(aead = healthyAead, keystoreResetOccurred = false, vaultUnavailableTransient = false)
            }

            override fun resetAndCreateFreshAead(): TinkAeadState =
                TinkAeadState(aead = healthyAead, keystoreResetOccurred = true, vaultUnavailableTransient = false)
        }
        val vault = TinkCredentialVault(context, splitRecoveryFactory)
        assertTrue(vault.isVaultCorrupted())
        assertTrue(vault.authSnapshotFlow.first().vaultCorruptionDetected)

        // Lazy retry recovers AEAD (clears transientFault) while preserving corruptionDetected until user re-saves/wipes
        prefs.edit().putString("app_key", "abc").commit()
        vault.getAppKey()
        assertFalse(vault.authSnapshotFlow.first().vaultUnavailableTransient)
        assertTrue(vault.isVaultCorrupted(), "corruptionDetected must persist after lazy recovery until explicit re-save/wipe")
        assertTrue(vault.authSnapshotFlow.first().vaultCorruptionDetected)
    }

    @Test
    fun `M-3 runtime KeyStoreException is transient, conditional flush keeps transientFault until encrypt recovers, and probeKeystoreRecovery flushes`() = runTest {
        val prefs = InMemorySharedPreferences()
        val context = mockContextReturning(prefs)
        val baseAead = FakeXorAead()
        var failEncryptTransient = false
        var failDecryptTransient = false

        val flakeyAead = object : Aead {
            override fun encrypt(plaintext: ByteArray, associatedData: ByteArray?): ByteArray {
                if (failEncryptTransient) {
                    throw KeyStoreException("KeyMint encrypt busy")
                }
                return baseAead.encrypt(plaintext, associatedData)
            }

            override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray?): ByteArray {
                if (failDecryptTransient) {
                    throw KeyStoreException("KeyMint decrypt locked")
                }
                return baseAead.decrypt(ciphertext, associatedData)
            }
        }
        val factory = object : TinkAeadFactory {
            override fun loadOrCreateInitialAead() = TinkAeadState(aead = flakeyAead)
            override fun resetAndCreateFreshAead() = TinkAeadState(aead = flakeyAead, keystoreResetOccurred = true)
        }

        val vault = TinkCredentialVault(context, factory)
        vault.saveAppCredentials("appKey1", "appSecret1", CredentialVault.DEFAULT_CALLBACK_URL)
        vault.saveInitialOAuthGrant("T_old", "R_old", 1800L, 1_000L)

        // Non-permanent GeneralSecurityException (KeyStoreException) during decrypt sets transientFault, NEVER permanent corruption
        failDecryptTransient = true
        assertNull(vault.getAccessToken())
        assertTrue(vault.authSnapshotFlow.value.vaultUnavailableTransient)
        assertFalse(vault.isVaultCorrupted())

        // Decrypt recovers while encrypt is still failing -> saveRefreshedAccessToken stores T_new & R_new in RAM
        failDecryptTransient = false
        failEncryptTransient = true
        vault.saveRefreshedAccessToken(
            accessToken = "T_new",
            rotatedRefreshToken = "R_new",
            accessExpiresInSeconds = 1800L,
            nowEpochMs = 2_000L
        )
        assertTrue(vault.authSnapshotFlow.value.vaultUnavailableTransient)

        // Successful decrypt (getAppKey) calls onKeystoreOperationSucceededLocked, which MUST NOT clear transientFault while flush fails
        assertEquals("appKey1", vault.getAppKey())
        assertTrue(vault.authSnapshotFlow.value.vaultUnavailableTransient)
        assertFalse(vault.probeKeystoreRecovery())
        assertTrue(vault.authSnapshotFlow.value.vaultUnavailableTransient)

        // Encrypt recovers -> probeKeystoreRecovery flushes R_new & T_new, clears transientFault, and emits snapshot
        failEncryptTransient = false
        assertTrue(vault.probeKeystoreRecovery())
        assertFalse(vault.authSnapshotFlow.value.vaultUnavailableTransient)

        val reloaded = TinkCredentialVault(context, factory)
        assertEquals("R_new", reloaded.getRefreshToken())
        assertEquals("T_new", reloaded.getAccessToken())
    }

    @Test
    fun `HIGH-3 partial encrypt fault preserves pending rotated refresh token and transientFault across subsequent refresh and credential save`() = runTest {
        val prefs = InMemorySharedPreferences()
        val context = mockContextReturning(prefs)
        val baseAead = FakeXorAead()
        var failAllEncrypt = false
        var failRefreshTokenEncrypt = false

        val partialFlakeyAead = object : Aead {
            override fun encrypt(plaintext: ByteArray, associatedData: ByteArray?): ByteArray {
                if (failAllEncrypt) {
                    throw KeyStoreException("KeyMint encrypt busy")
                }
                if (failRefreshTokenEncrypt && plaintext.decodeToString() == "R_new") {
                    throw KeyStoreException("KeyMint refresh slot locked")
                }
                return baseAead.encrypt(plaintext, associatedData)
            }

            override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray?): ByteArray =
                baseAead.decrypt(ciphertext, associatedData)
        }
        val factory = object : TinkAeadFactory {
            override fun loadOrCreateInitialAead() = TinkAeadState(aead = partialFlakeyAead)
            override fun resetAndCreateFreshAead() = TinkAeadState(aead = partialFlakeyAead, keystoreResetOccurred = true)
        }

        val vault = TinkCredentialVault(context, factory)
        vault.saveAppCredentials("appKey1", "appSecret1", CredentialVault.DEFAULT_CALLBACK_URL)
        vault.saveInitialOAuthGrant("T0", "R0", 1800L, 1_000L)

        // Refresh #1: full encrypt failure leaves T1 & R_new volatile
        failAllEncrypt = true
        vault.saveRefreshedAccessToken("T1", "R_new", 1800L, 2_000L)
        assertTrue(vault.authSnapshotFlow.value.vaultUnavailableTransient)
        assertEquals("T1", vault.getAccessToken())
        assertEquals("R_new", vault.getRefreshToken())

        // Refresh #2: access token encrypt recovers, rotated refresh token omitted (null), KEY_REFRESH_TOKEN still fails on R_new
        failAllEncrypt = false
        failRefreshTokenEncrypt = true
        vault.saveRefreshedAccessToken("T2", rotatedRefreshToken = null, accessExpiresInSeconds = 1800L, nowEpochMs = 3_000L)
        assertEquals("T2", vault.getAccessToken())
        assertEquals("R_new", vault.getRefreshToken(), "Unflushed rotated refresh token must stay in volatile RAM")
        assertTrue(vault.authSnapshotFlow.value.vaultUnavailableTransient, "transientFault must remain true while volatile refresh token is unflushed")

        // App credential update succeeds for app keys, yet MUST keep transientFault = true while R_new remains pending
        vault.saveAppCredentials("appKey2", "appSecret2", CredentialVault.DEFAULT_CALLBACK_URL)
        assertEquals("appKey2", vault.getAppKey())
        assertTrue(vault.authSnapshotFlow.value.vaultUnavailableTransient)

        // Keystore refresh slot recovers -> probeKeystoreRecovery flushes R_new & clears transientFault
        failRefreshTokenEncrypt = false
        assertTrue(vault.probeKeystoreRecovery())
        assertFalse(vault.authSnapshotFlow.value.vaultUnavailableTransient)

        val reloaded = TinkCredentialVault(context, factory)
        assertEquals("T2", reloaded.getAccessToken())
        assertEquals("R_new", reloaded.getRefreshToken())
        assertEquals("appKey2", reloaded.getAppKey())
    }

    private fun mockContextReturning(prefs: SharedPreferences): Context = mockk {
        every { getSharedPreferences(any(), any()) } returns prefs
    }

    private class FakeXorAead : Aead {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray?): ByteArray {
            val ad = associatedData ?: ByteArray(0)
            val tag = ad.fold(0x5A) { acc, b -> acc xor b.toInt() }.toByte()
            return byteArrayOf(tag) + plaintext.map { (it.toInt() xor 0x3C).toByte() }
        }

        override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray?): ByteArray {
            require(ciphertext.isNotEmpty()) { "Empty ciphertext" }
            val ad = associatedData ?: ByteArray(0)
            val expectedTag = ad.fold(0x5A) { acc, b -> acc xor b.toInt() }.toByte()
            if (ciphertext[0] != expectedTag) {
                throw AEADBadTagException("Associated data mismatch")
            }
            return ciphertext.drop(1).map { (it.toInt() xor 0x3C).toByte() }.toByteArray()
        }
    }

    private class InMemorySharedPreferences : SharedPreferences {
        private val map = linkedMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(map)
        override fun getString(key: String?, defValue: String?): String? = (map[key] as? String) ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") ((map[key] as? MutableSet<String>) ?: defValues)

        override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = linkedMapOf<String, Any?>()
            private var clearRequested = false

            override fun putString(key: String?, value: String?) = apply { if (key != null) pending[key] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { if (key != null) pending[key] = values }
            override fun putInt(key: String?, value: Int) = apply { if (key != null) pending[key] = value }
            override fun putLong(key: String?, value: Long) = apply { if (key != null) pending[key] = value }
            override fun putFloat(key: String?, value: Float) = apply { if (key != null) pending[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) pending[key] = value }
            override fun remove(key: String?) = apply { if (key != null) pending[key] = null }
            override fun clear() = apply { clearRequested = true }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearRequested) {
                    map.clear()
                }
                pending.forEach { (k, v) ->
                    if (v == null) map.remove(k) else map[k] = v
                }
            }
        }

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    }
}
