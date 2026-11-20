package com.schwabquant.app.core.security

import android.content.Context
import com.google.crypto.tink.Aead
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.AEADBadTagException
import javax.inject.Inject
import javax.inject.Singleton

data class StoredAuthSnapshot(
    val hasAppCredentials: Boolean,
    val hasAccessToken: Boolean,
    val hasRefreshToken: Boolean,
    val accessTokenExpiresAtEpochMs: Long,
    val refreshTokenIssuedAtEpochMs: Long,
    val primaryAccountHash: String?,
    val maskedAccountNumber: String?,
    val callbackUrl: String,
    val vaultCorruptionDetected: Boolean = false,
    val vaultUnavailableTransient: Boolean = false
)

interface CredentialVault {
    val authSnapshotFlow: StateFlow<StoredAuthSnapshot>

    fun saveAppCredentials(appKey: String, appSecret: String, callbackUrl: String)
    fun getAppKey(): String?
    fun getAppSecret(): String?
    fun getCallbackUrl(): String

    fun saveInitialOAuthGrant(
        accessToken: String,
        refreshToken: String,
        accessExpiresInSeconds: Long,
        nowEpochMs: Long = System.currentTimeMillis()
    )

    fun saveRefreshedAccessToken(
        accessToken: String,
        rotatedRefreshToken: String? = null,
        accessExpiresInSeconds: Long,
        nowEpochMs: Long = System.currentTimeMillis()
    )

    fun getAccessToken(): String?
    fun getRefreshToken(): String?
    fun getAccessTokenExpiresAtEpochMs(): Long
    fun getRefreshTokenIssuedAtEpochMs(): Long

    fun savePrimaryAccount(accountHash: String, maskedAccountNumber: String?)
    fun getPrimaryAccountHash(): String?
    fun getMaskedAccountNumber(): String?

    fun isVaultCorrupted(): Boolean
    fun getVaultGeneration(): Long = 0L
    fun probeKeystoreRecovery(): Boolean = false
    fun clearTokensOnly()
    fun wipeAllCredentials()

    companion object {
        const val DEFAULT_CALLBACK_URL = "https://127.0.0.1"
    }
}

/**
 * Hardware-Keystore-backed encrypted credential and OAuth token vault built on Google Tink AEAD.
 * Backing SharedPreferences file is explicitly excluded from cloud backup & device transfer.
 */
@Singleton
class TinkCredentialVault @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val tinkAeadFactory: TinkAeadFactory
) : CredentialVault {

    private val prefs = context.getSharedPreferences(VAULT_PREFS_NAME, Context.MODE_PRIVATE)
    private val vaultGenerationCounter = AtomicLong(0L)
    private val aeadLock = Any()

    @Volatile
    private var aead: Aead? = null

    @Volatile
    private var corruptionDetected: Boolean = false

    @Volatile
    private var transientFault: Boolean = false

    @Volatile
    private var initialized: Boolean = false

    // H-1 Volatile In-Memory Fallback (never persisted plaintext to disk) when Keystore transiently faults
    @Volatile
    private var volatileAccessToken: String? = null

    @Volatile
    private var volatileRefreshToken: String? = null

    @Volatile
    private var volatileAccessExpiresAtMs: Long? = null

    init {
        val initialState = tinkAeadFactory.loadOrCreateInitialAead()
        aead = initialState.aead
        corruptionDetected = initialState.keystoreResetOccurred
        transientFault = initialState.vaultUnavailableTransient ||
            (initialState.aead == null && !initialState.keystoreResetOccurred)
    }

    private val snapshotState = MutableStateFlow(synchronized(aeadLock) { buildSnapshotLocked() })
    override val authSnapshotFlow: StateFlow<StoredAuthSnapshot> = snapshotState.asStateFlow()

    init {
        initialized = true
    }

    override fun saveAppCredentials(appKey: String, appSecret: String, callbackUrl: String) {
        val updated = synchronized(aeadLock) {
            val cleanCallback = callbackUrl.trim().ifEmpty { CredentialVault.DEFAULT_CALLBACK_URL }
            val encKey = encryptLocked(KEY_APP_KEY, appKey.trim())
            val encSecret = encryptLocked(KEY_APP_SECRET, appSecret.trim())
            val encCallback = encryptLocked(KEY_CALLBACK_URL, cleanCallback)
            if (encKey == null || encSecret == null || encCallback == null) {
                error("Android Keystore encryption unavailable; unable to save Schwab credentials.")
            }
            prefs.edit()
                .putString(KEY_APP_KEY, encKey)
                .putString(KEY_APP_SECRET, encSecret)
                .putString(KEY_CALLBACK_URL, encCallback)
                .apply()
            corruptionDetected = false
            // HIGH-3 Safeguard: Never clear transientFault while an unflushed rotated token remains in volatile RAM
            transientFault = hasPendingVolatileTokensLocked()
            buildSnapshotLocked()
        }
        snapshotState.value = updated
    }

    override fun getAppKey(): String? = decrypt(KEY_APP_KEY)

    override fun getAppSecret(): String? = decrypt(KEY_APP_SECRET)

    override fun getCallbackUrl(): String =
        decrypt(KEY_CALLBACK_URL)?.takeIf { it.isNotBlank() } ?: CredentialVault.DEFAULT_CALLBACK_URL

    override fun saveInitialOAuthGrant(
        accessToken: String,
        refreshToken: String,
        accessExpiresInSeconds: Long,
        nowEpochMs: Long
    ) {
        val updated = synchronized(aeadLock) {
            val encAccess = encryptLocked(KEY_ACCESS_TOKEN, accessToken)
            val encRefresh = encryptLocked(KEY_REFRESH_TOKEN, refreshToken)
            if (encAccess == null || encRefresh == null) {
                error("Android Keystore encryption unavailable; unable to persist OAuth grant.")
            }
            val accessExpiryMs = nowEpochMs + accessExpiresInSeconds.coerceAtLeast(60L) * 1_000L
            vaultGenerationCounter.incrementAndGet()
            clearVolatileTokensLocked()
            prefs.edit()
                .putString(KEY_ACCESS_TOKEN, encAccess)
                .putString(KEY_REFRESH_TOKEN, encRefresh)
                .putLong(KEY_ACCESS_EXPIRES_AT, accessExpiryMs)
                .putLong(KEY_REFRESH_ISSUED_AT, nowEpochMs)
                .apply()
            buildSnapshotLocked()
        }
        snapshotState.value = updated
    }

    /**
     * H-1 + HIGH-3 Safeguard: Never throw Keystore exceptions back into OkHttp Authenticator/Interceptor,
     * and never overwrite or discard an unflushed rotated `volatileRefreshToken` on a subsequent access-only refresh.
     */
    override fun saveRefreshedAccessToken(
        accessToken: String,
        rotatedRefreshToken: String?,
        accessExpiresInSeconds: Long,
        nowEpochMs: Long
    ) {
        val updated = synchronized(aeadLock) {
            val accessExpiryMs = nowEpochMs + accessExpiresInSeconds.coerceAtLeast(60L) * 1_000L
            val effectiveRefreshToPersist = rotatedRefreshToken?.takeIf { it.isNotBlank() }
                ?: volatileRefreshToken

            val encAccess = encryptLocked(KEY_ACCESS_TOKEN, accessToken, flushOnSuccess = false)
            val encRefresh = if (effectiveRefreshToPersist != null) {
                encryptLocked(KEY_REFRESH_TOKEN, effectiveRefreshToPersist, flushOnSuccess = false)
            } else {
                null
            }

            val editor = prefs.edit()
            var wroteAnyPref = false
            if (encAccess != null) {
                editor.putString(KEY_ACCESS_TOKEN, encAccess)
                editor.putLong(KEY_ACCESS_EXPIRES_AT, accessExpiryMs)
                volatileAccessToken = null
                volatileAccessExpiresAtMs = null
                wroteAnyPref = true
            } else {
                volatileAccessToken = accessToken
                volatileAccessExpiresAtMs = accessExpiryMs
            }

            if (effectiveRefreshToPersist != null) {
                if (encRefresh != null) {
                    editor.putString(KEY_REFRESH_TOKEN, encRefresh)
                    volatileRefreshToken = null
                    wroteAnyPref = true
                } else {
                    volatileRefreshToken = effectiveRefreshToPersist
                }
            }

            // Intentionally preserve KEY_REFRESH_ISSUED_AT so the conservative 7-day hard cliff is kept.
            if (wroteAnyPref) {
                editor.apply()
            }

            val pendingVolatile = hasPendingVolatileTokensLocked()
            if (pendingVolatile) {
                if (!corruptionDetected) {
                    transientFault = true
                }
            } else {
                volatileAccessExpiresAtMs = null
                transientFault = false
            }
            buildSnapshotLocked()
        }
        snapshotState.value = updated
    }

    /**
     * H-1 Safeguard: Always read in-memory volatile token BEFORE decrypting or flushing prefs so
     * Keystore recovery can never return revoked R_old / T_old ciphertext stored prior to flush.
     */
    override fun getAccessToken(): String? {
        var updatedSnapshot: StoredAuthSnapshot? = null
        val token = synchronized(aeadLock) {
            val inMem = volatileAccessToken
            if (inMem != null) {
                getOrRetryAeadLocked()?.let { recoveredAead ->
                    if (flushVolatileTokensLocked(recoveredAead)) {
                        transientFault = false
                        updatedSnapshot = buildSnapshotLocked()
                    }
                }
                return@synchronized inMem
            }
            decryptLockedWithSnapshotChange(KEY_ACCESS_TOKEN) { snap -> updatedSnapshot = snap }
        }
        updatedSnapshot?.let { snapshotState.value = it }
        return token
    }

    override fun getRefreshToken(): String? {
        var updatedSnapshot: StoredAuthSnapshot? = null
        val token = synchronized(aeadLock) {
            val inMem = volatileRefreshToken
            if (inMem != null) {
                getOrRetryAeadLocked()?.let { recoveredAead ->
                    if (flushVolatileTokensLocked(recoveredAead)) {
                        transientFault = false
                        updatedSnapshot = buildSnapshotLocked()
                    }
                }
                return@synchronized inMem
            }
            decryptLockedWithSnapshotChange(KEY_REFRESH_TOKEN) { snap -> updatedSnapshot = snap }
        }
        updatedSnapshot?.let { snapshotState.value = it }
        return token
    }

    override fun getAccessTokenExpiresAtEpochMs(): Long = synchronized(aeadLock) {
        volatileAccessExpiresAtMs ?: prefs.getLong(KEY_ACCESS_EXPIRES_AT, 0L)
    }

    override fun getRefreshTokenIssuedAtEpochMs(): Long = prefs.getLong(KEY_REFRESH_ISSUED_AT, 0L)

    override fun savePrimaryAccount(accountHash: String, maskedAccountNumber: String?) {
        val updated = synchronized(aeadLock) {
            val encHash = encryptLocked(KEY_PRIMARY_ACCOUNT_HASH, accountHash)
                ?: error("Android Keystore encryption unavailable; unable to save primary account hash.")
            val editor = prefs.edit()
                .putString(KEY_PRIMARY_ACCOUNT_HASH, encHash)
            if (!maskedAccountNumber.isNullOrBlank()) {
                val encMasked = encryptLocked(KEY_MASKED_ACCOUNT_NUMBER, maskedAccountNumber)
                    ?: error("Android Keystore encryption unavailable; unable to save masked account.")
                editor.putString(KEY_MASKED_ACCOUNT_NUMBER, encMasked)
            }
            editor.apply()
            buildSnapshotLocked()
        }
        snapshotState.value = updated
    }

    override fun getPrimaryAccountHash(): String? = decrypt(KEY_PRIMARY_ACCOUNT_HASH)

    override fun getMaskedAccountNumber(): String? = decrypt(KEY_MASKED_ACCOUNT_NUMBER)

    override fun isVaultCorrupted(): Boolean = corruptionDetected

    override fun getVaultGeneration(): Long = vaultGenerationCounter.get()

    override fun clearTokensOnly() {
        val updated = synchronized(aeadLock) {
            vaultGenerationCounter.incrementAndGet()
            clearVolatileTokensLocked()
            prefs.edit()
                .remove(KEY_ACCESS_TOKEN)
                .remove(KEY_REFRESH_TOKEN)
                .remove(KEY_ACCESS_EXPIRES_AT)
                .remove(KEY_REFRESH_ISSUED_AT)
                .remove(KEY_PRIMARY_ACCOUNT_HASH)
                .remove(KEY_MASKED_ACCOUNT_NUMBER)
                .apply()
            buildSnapshotLocked()
        }
        snapshotState.value = updated
    }

    override fun wipeAllCredentials() {
        val updated = synchronized(aeadLock) {
            vaultGenerationCounter.incrementAndGet()
            clearVolatileTokensLocked()
            prefs.edit().clear().commit()
            val freshState = tinkAeadFactory.resetAndCreateFreshAead()
            aead = freshState.aead
            transientFault = freshState.vaultUnavailableTransient || freshState.aead == null
            corruptionDetected = freshState.aead == null
            if (freshState.aead != null) {
                transientFault = false
                corruptionDetected = false
            }
            buildSnapshotLocked()
        }
        snapshotState.value = updated
    }

    private fun buildSnapshotLocked(): StoredAuthSnapshot {
        val hasAppCreds = !decryptLocked(KEY_APP_KEY, allowRetry = false).isNullOrBlank() &&
            !decryptLocked(KEY_APP_SECRET, allowRetry = false).isNullOrBlank()
        val callback = decryptLocked(KEY_CALLBACK_URL, allowRetry = false)?.takeIf { it.isNotBlank() }
            ?: CredentialVault.DEFAULT_CALLBACK_URL
        val hasAccess = !volatileAccessToken.isNullOrBlank() ||
            !decryptLocked(KEY_ACCESS_TOKEN, allowRetry = false).isNullOrBlank()
        val hasRefresh = !volatileRefreshToken.isNullOrBlank() ||
            !decryptLocked(KEY_REFRESH_TOKEN, allowRetry = false).isNullOrBlank()
        val expiryMs = volatileAccessExpiresAtMs ?: prefs.getLong(KEY_ACCESS_EXPIRES_AT, 0L)
        return StoredAuthSnapshot(
            hasAppCredentials = hasAppCreds,
            hasAccessToken = hasAccess,
            hasRefreshToken = hasRefresh,
            accessTokenExpiresAtEpochMs = expiryMs,
            refreshTokenIssuedAtEpochMs = getRefreshTokenIssuedAtEpochMs(),
            primaryAccountHash = decryptLocked(KEY_PRIMARY_ACCOUNT_HASH, allowRetry = false),
            maskedAccountNumber = decryptLocked(KEY_MASKED_ACCOUNT_NUMBER, allowRetry = false),
            callbackUrl = callback,
            vaultCorruptionDetected = corruptionDetected,
            vaultUnavailableTransient = transientFault
        )
    }

    private fun hasPendingVolatileTokensLocked(): Boolean =
        volatileAccessToken != null || volatileRefreshToken != null

    private fun hasVolatileTokensLocked(): Boolean =
        volatileAccessToken != null || volatileRefreshToken != null || volatileAccessExpiresAtMs != null

    private fun clearVolatileTokensLocked() {
        volatileAccessToken = null
        volatileRefreshToken = null
        volatileAccessExpiresAtMs = null
    }

    private fun flushVolatileTokensLocked(activeAead: Aead): Boolean {
        if (!hasVolatileTokensLocked()) return true
        val vAccess = volatileAccessToken
        val vRefresh = volatileRefreshToken
        val vExp = volatileAccessExpiresAtMs

        val encAccess = vAccess?.let { encryptWithAead(activeAead, KEY_ACCESS_TOKEN, it) }
        val encRefresh = vRefresh?.let { encryptWithAead(activeAead, KEY_REFRESH_TOKEN, it) }
        if (vAccess != null && encAccess == null) return false
        if (vRefresh != null && encRefresh == null) return false

        val editor = prefs.edit()
        if (encAccess != null) editor.putString(KEY_ACCESS_TOKEN, encAccess)
        if (encRefresh != null) editor.putString(KEY_REFRESH_TOKEN, encRefresh)
        if (vExp != null) editor.putLong(KEY_ACCESS_EXPIRES_AT, vExp)
        editor.apply()

        clearVolatileTokensLocked()
        return true
    }

    private fun onKeystoreOperationSucceededLocked(activeAead: Aead): Boolean {
        val hadFault = transientFault || hasVolatileTokensLocked()
        if (!hadFault) return false
        val flushed = flushVolatileTokensLocked(activeAead)
        if (flushed) {
            transientFault = false
        }
        return flushed
    }

    override fun probeKeystoreRecovery(): Boolean {
        var updatedSnapshot: StoredAuthSnapshot? = null
        val recovered = synchronized(aeadLock) {
            val beforeFault = transientFault
            val beforeCorrupt = corruptionDetected
            val hadAead = aead != null
            val hadVolatile = hasVolatileTokensLocked()

            val activeAead = getOrRetryAeadLocked() ?: run {
                if (initialized && (beforeFault != transientFault || beforeCorrupt != corruptionDetected)) {
                    updatedSnapshot = buildSnapshotLocked()
                }
                return@synchronized false
            }

            val ok = if (hasVolatileTokensLocked()) {
                flushVolatileTokensLocked(activeAead)
            } else if (transientFault) {
                encryptWithAead(activeAead, KEY_RECOVERY_PROBE, "ok") != null
            } else {
                true
            }
            if (ok) {
                transientFault = false
            }
            if (initialized && (
                    beforeFault != transientFault ||
                        beforeCorrupt != corruptionDetected ||
                        (!hadAead && aead != null) ||
                        (hadVolatile && !hasVolatileTokensLocked())
                    )
            ) {
                updatedSnapshot = buildSnapshotLocked()
            }
            ok && !transientFault
        }
        updatedSnapshot?.let { snapshotState.value = it }
        return recovered
    }

    private fun encryptWithAead(activeAead: Aead, key: String, plaintext: String): String? = try {
        val ciphertext = activeAead.encrypt(
            plaintext.toByteArray(Charsets.UTF_8),
            key.toByteArray(Charsets.UTF_8)
        )
        Base64.getEncoder().encodeToString(ciphertext)
    } catch (_: Throwable) {
        null
    }

    private fun getOrRetryAeadLocked(): Aead? {
        aead?.let { return it }
        if (!initialized || !transientFault) return null
        val retryState = tinkAeadFactory.loadOrCreateInitialAead()
        aead = retryState.aead
        if (retryState.keystoreResetOccurred) {
            corruptionDetected = true
        }
        val activeAead = retryState.aead
        if (activeAead != null) {
            val flushed = flushVolatileTokensLocked(activeAead)
            // M-1/M-3 Safeguard: Never silently clear corruptionDetected; clear transientFault only if flush succeeded
            transientFault = retryState.vaultUnavailableTransient || !flushed
        } else {
            transientFault = retryState.vaultUnavailableTransient || !retryState.keystoreResetOccurred
        }
        return activeAead
    }

    private fun encryptLocked(
        key: String,
        plaintext: String,
        flushOnSuccess: Boolean = true
    ): String? {
        val activeAead = getOrRetryAeadLocked()
        if (activeAead == null) {
            if (!corruptionDetected) {
                transientFault = true
            }
            return null
        }
        return try {
            val ciphertext = activeAead.encrypt(
                plaintext.toByteArray(Charsets.UTF_8),
                key.toByteArray(Charsets.UTF_8)
            )
            if (flushOnSuccess) {
                onKeystoreOperationSucceededLocked(activeAead)
            }
            Base64.getEncoder().encodeToString(ciphertext)
        } catch (e: GeneralSecurityException) {
            handleCryptoErrorLocked(e)
            null
        } catch (e: ProviderException) {
            handleCryptoErrorLocked(e)
            null
        } catch (e: RuntimeException) {
            handleCryptoErrorLocked(e)
            null
        }
    }

    private fun decrypt(key: String): String? {
        var updatedSnapshot: StoredAuthSnapshot? = null
        val result = synchronized(aeadLock) {
            decryptLockedWithSnapshotChange(key) { snap -> updatedSnapshot = snap }
        }
        updatedSnapshot?.let { snapshotState.value = it }
        return result
    }

    private fun decryptLockedWithSnapshotChange(
        key: String,
        onSnapshotChanged: (StoredAuthSnapshot) -> Unit
    ): String? {
        val beforeFault = transientFault
        val beforeCorrupt = corruptionDetected
        val hadAead = aead != null
        val plaintext = decryptLocked(key, allowRetry = true)
        if (initialized &&
            (beforeFault != transientFault || beforeCorrupt != corruptionDetected || (!hadAead && aead != null))
        ) {
            onSnapshotChanged(buildSnapshotLocked())
        }
        return plaintext
    }

    private fun decryptLocked(key: String, allowRetry: Boolean): String? {
        val encoded = prefs.getString(key, null) ?: return null
        val activeAead = if (allowRetry) getOrRetryAeadLocked() else aead
        if (activeAead == null) {
            if (allowRetry && !corruptionDetected) {
                transientFault = true
            }
            return null
        }
        return try {
            val bytes = Base64.getDecoder().decode(encoded)
            val plaintext = String(activeAead.decrypt(bytes, key.toByteArray(Charsets.UTF_8)), Charsets.UTF_8)
            if (allowRetry) {
                onKeystoreOperationSucceededLocked(activeAead)
            }
            plaintext
        } catch (_: AEADBadTagException) {
            corruptionDetected = true
            null
        } catch (e: GeneralSecurityException) {
            handleCryptoErrorLocked(e)
            null
        } catch (e: ProviderException) {
            handleCryptoErrorLocked(e)
            null
        } catch (_: IllegalArgumentException) {
            corruptionDetected = true
            null
        } catch (e: RuntimeException) {
            handleCryptoErrorLocked(e)
            null
        }
    }

    private fun handleCryptoErrorLocked(t: Throwable) {
        if (isPermanentKeysetCorruptionError(t)) {
            corruptionDetected = true
        } else {
            transientFault = true
        }
    }

    companion object {
        const val VAULT_PREFS_NAME = "schwab_quant_vault_prefs"

        private const val KEY_APP_KEY = "app_key"
        private const val KEY_APP_SECRET = "app_secret"
        private const val KEY_CALLBACK_URL = "callback_url"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_ACCESS_EXPIRES_AT = "access_token_expires_at_ms"
        private const val KEY_REFRESH_ISSUED_AT = "refresh_token_issued_at_ms"
        private const val KEY_PRIMARY_ACCOUNT_HASH = "primary_account_hash"
        private const val KEY_MASKED_ACCOUNT_NUMBER = "masked_account_number"
        private const val KEY_RECOVERY_PROBE = "recovery_probe"
    }
}
