package com.schwabquant.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.schwabquant.app.core.security.DeepLinkIntentSanitizer
import com.schwabquant.app.core.security.DeepLinkStageResult
import com.schwabquant.app.core.security.RawStageOrderIntentData
import com.schwabquant.app.data.local.prefs.AppPreferencesDataStore
import com.schwabquant.app.domain.model.ExecutionMode
import com.schwabquant.app.domain.model.StagedOrderHolder
import com.schwabquant.app.domain.repository.AuthRepository
import com.schwabquant.app.domain.repository.AuthSessionState
import com.schwabquant.app.presentation.navigation.MainScaffold
import com.schwabquant.app.presentation.navigation.NavRoutes
import com.schwabquant.app.presentation.theme.AppThemeType
import com.schwabquant.app.presentation.theme.SchwabQuantTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var preferencesDataStore: AppPreferencesDataStore

    @Inject
    lateinit var authRepository: AuthRepository

    @Inject
    lateinit var stagedOrderHolder: StagedOrderHolder

    private val pendingNavRoute = MutableStateFlow<String?>(null)
    private val pendingNotice = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            handleIncomingIntent(intent)
        }

        setContent {
            val selectedTheme by preferencesDataStore.selectedTheme.collectAsStateWithLifecycle(
                initialValue = AppThemeType.OLED_NIGHT
            )
            val executionMode by preferencesDataStore.executionMode.collectAsStateWithLifecycle(
                initialValue = ExecutionMode.SHADOW
            )
            val authSession by authRepository.observeAuthState().collectAsStateWithLifecycle(
                initialValue = AuthSessionState()
            )
            val pendingRoute by pendingNavRoute.collectAsStateWithLifecycle()
            val notice by pendingNotice.collectAsStateWithLifecycle()

            SchwabQuantTheme(themeType = selectedTheme) {
                MainScaffold(
                    executionMode = executionMode,
                    authSession = authSession,
                    pendingNavRoute = pendingRoute,
                    onPendingNavRouteConsumed = { pendingNavRoute.value = null },
                    pendingNotice = notice,
                    onPendingNoticeConsumed = { pendingNotice.value = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val consumedIntent = Intent(Intent.ACTION_MAIN)
        if (intent.action == ACTION_STAGE_SENTINEL_ORDER) {
            val rawData = RawStageOrderIntentData(
                internalToken = intent.getStringExtra(EXTRA_INTERNAL_INTENT_TOKEN),
                executionMode = intent.getStringExtra(EXTRA_STAGE_EXECUTION_MODE),
                symbol = intent.getStringExtra(EXTRA_STAGE_SYMBOL),
                side = intent.getStringExtra(EXTRA_STAGE_SIDE),
                quantity = intent.getIntExtra(EXTRA_STAGE_QTY, 1),
                limitPrice = intent.getStringExtra(EXTRA_STAGE_LIMIT_PRICE),
                sourceLabel = intent.getStringExtra(EXTRA_STAGE_SOURCE_LABEL),
                replacePriorOrderId = intent.getStringExtra(EXTRA_STAGE_REPLACE_ORDER_ID),
                isEscalatedReprice = intent.getBooleanExtra(EXTRA_STAGE_IS_ESCALATED_REPRICE, false)
            )
            setIntent(consumedIntent)
            lifecycleScope.launch {
                val activeMode = preferencesDataStore.executionMode.first()
                when (val parsed = DeepLinkIntentSanitizer.parseAndSanitizeStageOrder(rawData, activeMode)) {
                    is DeepLinkStageResult.ValidDraft -> {
                        stagedOrderHolder.stageDraft(parsed.draft)
                        pendingNavRoute.value = NavRoutes.SENTINEL
                    }
                    is DeepLinkStageResult.ModeMismatch -> {
                        pendingNotice.value = parsed.message
                        pendingNavRoute.value = NavRoutes.SENTINEL
                    }
                    is DeepLinkStageResult.Rejected -> Unit
                }
            }
            return
        }

        val safeNavTarget = DeepLinkIntentSanitizer.sanitizeNavDestination(
            intent.getStringExtra(EXTRA_NAV_DESTINATION)
        )
        if (safeNavTarget != null) {
            pendingNavRoute.value = safeNavTarget
        }

        val dataUri = intent.data
        setIntent(consumedIntent)
        if (dataUri != null && dataUri.scheme == "schwabquant" && dataUri.host == "oauth") {
            pendingNavRoute.value = NavRoutes.SCHWAB_AUTH_SETUP
            lifecycleScope.launch {
                val exchanged = authRepository.exchangeAuthorizationCode(dataUri.toString())
                if (exchanged.isSuccess) {
                    authRepository.verifyConnectionAndFetchAccounts()
                }
            }
        }
    }

    companion object {
        const val EXTRA_INTERNAL_INTENT_TOKEN = "extra_internal_intent_token"
        const val EXTRA_NAV_DESTINATION = "extra_nav_destination"
        const val ACTION_STAGE_SENTINEL_ORDER = "com.schwabquant.app.ACTION_STAGE_SENTINEL_ORDER"
        const val EXTRA_STAGE_EXECUTION_MODE = "extra_stage_execution_mode"
        const val EXTRA_STAGE_SYMBOL = "extra_stage_symbol"
        const val EXTRA_STAGE_SIDE = "extra_stage_side"
        const val EXTRA_STAGE_QTY = "extra_stage_qty"
        const val EXTRA_STAGE_LIMIT_PRICE = "extra_stage_limit_price"
        const val EXTRA_STAGE_SOURCE_LABEL = "extra_stage_source_label"
        const val EXTRA_STAGE_REPLACE_ORDER_ID = "extra_stage_replace_order_id"
        const val EXTRA_STAGE_IS_ESCALATED_REPRICE = "extra_stage_is_escalated_reprice"
    }
}
