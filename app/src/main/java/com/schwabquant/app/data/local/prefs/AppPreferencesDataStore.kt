package com.schwabquant.app.data.local.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.schwabquant.app.core.security.TokenExpiryTier
import com.schwabquant.app.domain.model.ExecutionMode
import com.schwabquant.app.domain.model.PortfolioDataSource
import com.schwabquant.app.domain.model.ScannerFilterConfig
import com.schwabquant.app.presentation.theme.AppThemeType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.appPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "schwab_quant_prefs"
)

@Singleton
class AppPreferencesDataStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val dataStore = context.appPreferencesDataStore

    val selectedTheme: Flow<AppThemeType> = dataStore.data.map { prefs ->
        AppThemeType.fromStorageKey(prefs[KEY_SELECTED_THEME])
    }

    /**
     * Shadow Mode (paper trading) is ALWAYS the default execution mode (`isLiveMode = false`).
     */
    val isLiveMode: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_IS_LIVE_MODE] ?: false
    }

    val executionMode: Flow<ExecutionMode> = isLiveMode.map { live ->
        if (live) ExecutionMode.LIVE else ExecutionMode.SHADOW
    }

    val selectedPortfolioSource: Flow<PortfolioDataSource> = dataStore.data.map { prefs ->
        runCatching {
            PortfolioDataSource.valueOf(prefs[KEY_PORTFOLIO_SOURCE].orEmpty())
        }.getOrDefault(PortfolioDataSource.SHADOW_PAPER_LEDGER)
    }

    val shadowCashBalance: Flow<Double> = dataStore.data.map { prefs ->
        prefs[KEY_SHADOW_CASH_BALANCE] ?: 0.0
    }

    val scannerDefaults: Flow<ScannerFilterConfig> = dataStore.data.map { prefs ->
        ScannerFilterConfig(
            minDte = prefs[KEY_SCANNER_MIN_DTE] ?: 21,
            maxDte = prefs[KEY_SCANNER_MAX_DTE] ?: 60,
            minShortDelta = prefs[KEY_SCANNER_MIN_DELTA] ?: 0.20,
            maxShortDelta = prefs[KEY_SCANNER_MAX_DELTA] ?: 0.35,
            minOpenInterest = prefs[KEY_SCANNER_MIN_OI] ?: 100
        )
    }

    val antiStopHuntDelaySeconds: Flow<Int> = dataStore.data.map { prefs ->
        prefs[KEY_ANTI_STOP_HUNT_SECONDS] ?: 15
    }

    suspend fun setSelectedTheme(theme: AppThemeType) {
        dataStore.edit { prefs ->
            prefs[KEY_SELECTED_THEME] = theme.name
        }
    }

    suspend fun setLiveMode(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_IS_LIVE_MODE] = enabled
        }
    }

    suspend fun setSelectedPortfolioSource(source: PortfolioDataSource) {
        dataStore.edit { prefs ->
            prefs[KEY_PORTFOLIO_SOURCE] = source.name
        }
    }

    suspend fun setShadowCashBalance(amount: Double) {
        dataStore.edit { prefs ->
            prefs[KEY_SHADOW_CASH_BALANCE] = amount
        }
    }

    suspend fun updateScannerDefaults(config: ScannerFilterConfig) {
        dataStore.edit { prefs ->
            prefs[KEY_SCANNER_MIN_DTE] = config.minDte
            prefs[KEY_SCANNER_MAX_DTE] = config.maxDte
            prefs[KEY_SCANNER_MIN_DELTA] = config.minShortDelta
            prefs[KEY_SCANNER_MAX_DELTA] = config.maxShortDelta
            prefs[KEY_SCANNER_MIN_OI] = config.minOpenInterest
        }
    }

    suspend fun setAntiStopHuntDelaySeconds(seconds: Int) {
        dataStore.edit { prefs ->
            prefs[KEY_ANTI_STOP_HUNT_SECONDS] = seconds.coerceIn(0, 300)
        }
    }

    suspend fun hasAlertedForTokenTier(
        refreshTokenIssuedAtEpochMs: Long,
        tier: TokenExpiryTier
    ): Boolean {
        val prefs = dataStore.data.first()
        val lastIssued = prefs[KEY_LAST_ALERTED_REFRESH_ISSUED_AT] ?: 0L
        val lastTier = prefs[KEY_LAST_ALERTED_TOKEN_TIER]
        return lastIssued == refreshTokenIssuedAtEpochMs && lastTier == tier.name
    }

    suspend fun recordTokenCliffAlert(
        refreshTokenIssuedAtEpochMs: Long,
        tier: TokenExpiryTier
    ) {
        dataStore.edit { prefs ->
            prefs[KEY_LAST_ALERTED_REFRESH_ISSUED_AT] = refreshTokenIssuedAtEpochMs
            prefs[KEY_LAST_ALERTED_TOKEN_TIER] = tier.name
        }
    }

    suspend fun clearTokenCliffAlertHistory() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_LAST_ALERTED_REFRESH_ISSUED_AT)
            prefs.remove(KEY_LAST_ALERTED_TOKEN_TIER)
        }
    }

    companion object {
        private val KEY_SELECTED_THEME = stringPreferencesKey("selected_theme")
        private val KEY_IS_LIVE_MODE = booleanPreferencesKey("is_live_mode")
        private val KEY_PORTFOLIO_SOURCE = stringPreferencesKey("selected_portfolio_source")
        private val KEY_SHADOW_CASH_BALANCE = doublePreferencesKey("shadow_cash_balance")
        private val KEY_SCANNER_MIN_DTE = intPreferencesKey("scanner_min_dte")
        private val KEY_SCANNER_MAX_DTE = intPreferencesKey("scanner_max_dte")
        private val KEY_SCANNER_MIN_DELTA = doublePreferencesKey("scanner_min_delta")
        private val KEY_SCANNER_MAX_DELTA = doublePreferencesKey("scanner_max_delta")
        private val KEY_SCANNER_MIN_OI = intPreferencesKey("scanner_min_oi")
        private val KEY_ANTI_STOP_HUNT_SECONDS = intPreferencesKey("anti_stop_hunt_seconds")
        private val KEY_LAST_ALERTED_REFRESH_ISSUED_AT =
            longPreferencesKey("last_alerted_refresh_issued_at_ms")
        private val KEY_LAST_ALERTED_TOKEN_TIER = stringPreferencesKey("last_alerted_token_tier")
    }
}
