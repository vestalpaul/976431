package com.schwabquant.app.core.security

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.util.Collections
import java.util.IdentityHashMap
import javax.crypto.AEADBadTagException
import javax.inject.Inject
import javax.inject.Singleton

data class TinkAeadState(
    val aead: Aead?,
    val keystoreResetOccurred: Boolean = false,
    val vaultUnavailableTransient: Boolean = false
)

/**
 * Cycle-safe inspection of an exception causal chain for transient Keystore HAL / IPC / I/O faults.
 */
fun isTransientKeystoreError(throwable: Throwable): Boolean {
    val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = throwable
    while (current != null && visited.add(current)) {
        if (current is ProviderException || current is IOException) {
            return true
        }
        current = current.cause
    }
    return false
}

/**
 * M-1 Safeguard: Cycle-safe check identifying explicit permanent keyset/master-key corruption
 * where destructive keyset reset is warranted. Startup failures outside this set default to transient.
 */
fun isPermanentKeysetCorruptionError(throwable: Throwable): Boolean {
    val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = throwable
    while (current != null && visited.add(current)) {
        if (current is AEADBadTagException) {
            return true
        }
        val className = current::class.java.name
        val simpleName = current::class.java.simpleName
        if (simpleName == "InvalidProtocolBufferException" ||
            className == "android.security.keystore.KeyPermanentlyInvalidatedException"
        ) {
            return true
        }
        current = current.cause
    }
    return false
}

interface TinkAeadFactory {
    fun loadOrCreateInitialAead(): TinkAeadState
    fun resetAndCreateFreshAead(): TinkAeadState
}

@Singleton
class AndroidTinkAeadFactory internal constructor(
    private val context: Context,
    private val aeadBuilder: () -> Aead,
    private val masterKeyDeleter: () -> Unit = { defaultDeleteMasterKey() }
) : TinkAeadFactory {

    @Inject
    constructor(
        @ApplicationContext context: Context
    ) : this(
        context = context,
        aeadBuilder = { buildAndroidKeysetAead(context) },
        masterKeyDeleter = { defaultDeleteMasterKey() }
    )

    override fun loadOrCreateInitialAead(): TinkAeadState {
        runCatching { AeadConfig.register() }
        return try {
            TinkAeadState(
                aead = aeadBuilder(),
                keystoreResetOccurred = false,
                vaultUnavailableTransient = false
            )
        } catch (t: Throwable) {
            if (isPermanentKeysetCorruptionError(t)) {
                resetAndCreateFreshAead()
            } else {
                // M-1: Treat startup Keystore/KeyMint failures as transient by default (non-destructive).
                val retryAead = runCatching { aeadBuilder() }.getOrNull()
                TinkAeadState(
                    aead = retryAead,
                    keystoreResetOccurred = false,
                    vaultUnavailableTransient = retryAead == null
                )
            }
        }
    }

    override fun resetAndCreateFreshAead(): TinkAeadState {
        runCatching { AeadConfig.register() }
        runCatching {
            context.getSharedPreferences(TINK_PREFS_FILE, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
            context.getSharedPreferences(TinkCredentialVault.VAULT_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }

        runCatching { masterKeyDeleter() }

        val recoveredAead = runCatching { aeadBuilder() }.getOrNull()
        return TinkAeadState(
            aead = recoveredAead,
            keystoreResetOccurred = true,
            vaultUnavailableTransient = recoveredAead == null
        )
    }

    companion object {
        internal const val TINK_KEYSET_NAME = "schwab_quant_tink_keyset"
        internal const val TINK_PREFS_FILE = "schwab_quant_tink_keyset_prefs"
        internal const val MASTER_KEY_ALIAS = "schwab_quant_master_key"
        private const val MASTER_KEY_URI = "android-keystore://$MASTER_KEY_ALIAS"
        private const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val AES256_GCM_TEMPLATE = "AES256_GCM"

        private fun buildAndroidKeysetAead(context: Context): Aead {
            val keysetHandle = AndroidKeysetManager.Builder()
                .withSharedPref(context, TINK_KEYSET_NAME, TINK_PREFS_FILE)
                .withKeyTemplate(KeyTemplates.get(AES256_GCM_TEMPLATE))
                .withMasterKeyUri(MASTER_KEY_URI)
                .build()
                .keysetHandle
            return keysetHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        }

        private fun defaultDeleteMasterKey() {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER)
            keyStore.load(null)
            if (keyStore.containsAlias(MASTER_KEY_ALIAS)) {
                keyStore.deleteEntry(MASTER_KEY_ALIAS)
            }
        }
    }
}
