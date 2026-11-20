package com.schwabquant.app.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.schwabquant.app.MainActivity
import com.schwabquant.app.R
import com.schwabquant.app.SentinelDeepLinkActivity
import com.schwabquant.app.core.notifications.NotificationChannels
import com.schwabquant.app.core.security.CredentialVault
import com.schwabquant.app.core.security.StoredAuthSnapshot
import com.schwabquant.app.data.local.prefs.AppPreferencesDataStore
import com.schwabquant.app.data.remote.streamer.SchwabStreamerClient
import com.schwabquant.app.data.remote.streamer.StreamerConnectionState
import com.schwabquant.app.domain.model.ExecutionMode
import com.schwabquant.app.domain.model.OrderDraft
import com.schwabquant.app.domain.model.SentinelRule
import com.schwabquant.app.domain.model.SentinelRuleStatus
import com.schwabquant.app.domain.repository.SentinelRepository
import com.schwabquant.app.domain.usecase.DisconnectWatchdogEvaluator
import com.schwabquant.app.domain.usecase.MarketSessionResolver
import com.schwabquant.app.presentation.navigation.NavRoutes
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/**
 * Standalone 24/5 WebSocket market-monitoring foreground service (`specialUse`).
 * - Recovers orphaned `EXECUTING` rules on startup without blind re-execution (Safeguard #5).
 * - Connects live Schwab Streamer whenever OAuth tokens exist even in Shadow Mode (Safeguard #4).
 * - H-5: Prevents shutdown/restart races with `serviceSessionId` & `stopSelf(startId)`, prevents WakeLock leaks
 *   on async shutdown via `hasActiveModeArmedRules`, wraps lock acquisitions in `runCatching`, and uses `stopService` in `stopSafely()`.
 * - M-3: Disconnects Streamer cleanly when active mode has 0 armed rules.
 */
@AndroidEntryPoint
class SentinelForegroundService : Service() {

    @Inject
    lateinit var streamerClient: SchwabStreamerClient

    @Inject
    lateinit var sentinelRepository: SentinelRepository

    @Inject
    lateinit var runtimeCoordinator: SentinelRuntimeCoordinator

    @Inject
    lateinit var marketSessionResolver: MarketSessionResolver

    @Inject
    lateinit var disconnectWatchdog: DisconnectWatchdogEvaluator

    @Inject
    lateinit var credentialVault: CredentialVault

    @Inject
    lateinit var preferences: AppPreferencesDataStore

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionLock = Any()
    private val lockSync = Any()
    private val serviceSessionId = AtomicLong(0L)
    private val latestStartId = AtomicInteger(-1)

    private var monitoringJob: Job? = null
    private var quotesJob: Job? = null
    private var eventsJob: Job? = null
    private var watchdogJob: Job? = null
    private var startupJob: Job? = null
    private var shutdownJob: Job? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var hasActiveModeArmedRules: Boolean = false

    @Volatile
    private var wakeLockAcquiredAtEpochMs: Long = 0L

    @Volatile
    private var isShuttingDown: Boolean = false

    @Volatile
    private var hadArmedRulesInSession: Boolean = false

    @Volatile
    private var currentActiveRules: List<SentinelRule> = emptyList()

    @Volatile
    private var currentExecutionMode: ExecutionMode = ExecutionMode.SHADOW

    @Volatile
    private var isLiveStreamingActive: Boolean = false

    @Volatile
    private var currentMustRemainLive: Boolean = false

    @Volatile
    private var currentActiveLiveSymbols: Set<String> = emptySet()

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.createAll(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId.set(startId)

        // Always satisfy Android 14+ startForeground() contract immediately on entry
        val initialNotification = buildOngoingNotification(
            connectionState = streamerClient.connectionState.value,
            armedCount = currentActiveRules.count { it.status == SentinelRuleStatus.ARMED },
            sessionLabel = marketSessionResolver.resolve().shortLabel,
            mode = currentExecutionMode
        )
        if (!startInForegroundCompat(initialNotification)) {
            // H-1 + H-4 Safeguard (Path A): Capture BOTH connectionGeneration AND ownerEpoch BEFORE spawning detached disconnect
            val (capturedGen, capturedOwnerEpoch) = synchronized(sessionLock) {
                val gen = streamerClient.currentConnectionGeneration()
                val owner = streamerClient.currentOwnerEpoch()
                serviceSessionId.incrementAndGet()
                isShuttingDown = true
                currentMustRemainLive = false
                currentActiveLiveSymbols = emptySet()
                startupJob?.cancel()
                startupJob = null
                shutdownJob?.cancel()
                shutdownJob = null
                monitoringJob?.cancel()
                monitoringJob = null
                quotesJob?.cancel()
                quotesJob = null
                eventsJob?.cancel()
                eventsJob = null
                watchdogJob?.cancel()
                watchdogJob = null
                synchronized(lockSync) { hasActiveModeArmedRules = false }
                releaseLocks()
                stopSelf(startId)
                gen to owner
            }
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                runCatching {
                    streamerClient.disconnectGracefully(
                        expectedGeneration = capturedGen,
                        expectedOwnerEpoch = capturedOwnerEpoch
                    )
                }
            }
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_STOP -> {
                val expectedSession = synchronized(sessionLock) { serviceSessionId.get() }
                shutdownAndStop(expectedSessionId = expectedSession, stopStartId = startId)
                return START_NOT_STICKY
            }

            ACTION_DISARM_ALL -> {
                synchronized(sessionLock) {
                    val expectedSession = serviceSessionId.get()
                    isShuttingDown = true
                    currentMustRemainLive = false
                    currentActiveLiveSymbols = emptySet()
                    startupJob?.cancel()
                    startupJob = null
                    monitoringJob?.cancel()
                    monitoringJob = null
                    quotesJob?.cancel()
                    quotesJob = null
                    watchdogJob?.cancel()
                    watchdogJob = null
                    synchronized(lockSync) { hasActiveModeArmedRules = false }
                    releaseLocks()
                    shutdownJob?.cancel()
                    shutdownJob = serviceScope.launch {
                        runCatching { sentinelRepository.disarmAllRules() }
                        runtimeCoordinator.clearAllTrackers()
                        val mode = if (preferences.isLiveMode.first()) ExecutionMode.LIVE.name else ExecutionMode.SHADOW.name
                        runCatching {
                            sentinelRepository.recordAuditEvent(
                                category = "SENTINEL_DISARM_ALL",
                                severity = "WARNING",
                                symbol = null,
                                executionMode = mode,
                                message = "All armed Sentinel rules disarmed from notification / user action."
                            )
                        }
                        performShutdownStop(expectedSessionId = expectedSession, stopStartId = startId)
                    }
                }
                return START_NOT_STICKY
            }
        }

        // C-1 + H-1 + H-4 Safeguard: Overlapping ACTION_START preserves serviceSessionId & startupJob;
        // explicit ACTION_START clears Error 12 halt whereas OS null-intent START_STICKY restarts preserve it.
        val isExplicitStart = intent?.action == ACTION_START
        val transition = synchronized(sessionLock) {
            val result = handleActionStartLocked(
                serviceSessionId = serviceSessionId,
                latestStartId = latestStartId,
                startId = startId,
                isShuttingDown = isShuttingDown,
                monitoringJobActive = monitoringJob?.isActive == true,
                startupJobActive = startupJob?.isActive == true,
                isExplicitStart = isExplicitStart,
                streamerClient = streamerClient
            )
            runtimeCoordinator.updateServiceStatus { status ->
                status.copy(
                    isServiceRunning = true,
                    activeSession = marketSessionResolver.resolve()
                )
            }
            if (result.newSessionId != null) {
                shutdownJob?.cancel()
                shutdownJob = null
                isShuttingDown = false
                startMonitoringLoopsLocked(result.newSessionId)
            }
            result
        }
        if (transition.newSessionId == null) {
            publishStatusAndOngoingNotification()
        }
        if (transition.shouldTriggerExplicitError12Reconnect) {
            serviceScope.launch {
                selfHealDisconnectedStreamerIfNeeded(
                    isShuttingDown = isShuttingDown,
                    activeLiveSymbols = currentActiveLiveSymbols,
                    credentialVault = credentialVault,
                    streamerClient = streamerClient,
                    scope = serviceScope
                )
                publishStatusAndOngoingNotification()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        synchronized(sessionLock) {
            serviceSessionId.incrementAndGet()
            isShuttingDown = true
            currentMustRemainLive = false
            currentActiveLiveSymbols = emptySet()
            startupJob?.cancel()
            startupJob = null
            shutdownJob?.cancel()
            shutdownJob = null
            monitoringJob?.cancel()
            monitoringJob = null
            quotesJob?.cancel()
            quotesJob = null
            eventsJob?.cancel()
            eventsJob = null
            watchdogJob?.cancel()
            watchdogJob = null
        }
        streamerClient.disconnect()
        synchronized(lockSync) { hasActiveModeArmedRules = false }
        releaseLocks()
        disconnectWatchdog.reset()
        runtimeCoordinator.updateServiceStatus { status ->
            status.copy(
                isServiceRunning = false,
                connectionState = StreamerConnectionState.DISCONNECTED,
                armedRuleCount = 0
            )
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private data class MonitoringSnapshot(
        val allActive: List<SentinelRule>,
        val mode: ExecutionMode,
        val execCount: Int,
        val authSnapshot: StoredAuthSnapshot
    )

    private fun startMonitoringLoopsLocked(expectedSessionId: Long) {
        if (monitoringJob?.isActive == true || startupJob?.isActive == true) return

        // M-4 Safeguard: Subscribe eventsJob eagerly (UNDISPATCHED) BEFORE startup orphan recovery emits
        if (eventsJob?.isActive != true) {
            eventsJob = serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
                runtimeCoordinator.runtimeEvents.collect { event ->
                    when (event) {
                        is SentinelRuntimeEvent.OrderFilled -> postExecutionFillNotification(
                            title = "Sentinel Exit Filled: ${event.symbol}",
                            body = event.message,
                            notificationId = NOTIFICATION_ID_FILL_BASE + (event.ruleId % 100).toInt()
                        )

                        is SentinelRuntimeEvent.UnfilledEscalated -> postActionableOrderNotification(
                            channelId = NotificationChannels.DISCONNECT_ALARM,
                            title = "URGENT: Unfilled Exit (${event.symbol})",
                            body = event.message,
                            actionLabel = "Re-Price Exit",
                            draft = event.repriceDraft,
                            executionMode = event.executionMode,
                            notificationId = NOTIFICATION_ID_ESCALATED_BASE + (event.ruleId % 100).toInt()
                        )

                        is SentinelRuntimeEvent.OvernightAlert -> postActionableOrderNotification(
                            channelId = NotificationChannels.OVERNIGHT_ALERT,
                            title = event.payload.title,
                            body = event.payload.body,
                            actionLabel = event.payload.actionLabel,
                            draft = event.payload.stagedOrderDraft,
                            executionMode = event.payload.executionMode,
                            notificationId = NOTIFICATION_ID_OVERNIGHT_BASE + (event.payload.ruleId % 100).toInt()
                        )

                        is SentinelRuntimeEvent.DipBuyAlert -> postActionableOrderNotification(
                            channelId = NotificationChannels.OVERNIGHT_ALERT,
                            title = event.payload.title,
                            body = event.payload.body,
                            actionLabel = event.payload.actionLabel,
                            draft = event.payload.stagedOrderDraft,
                            executionMode = event.payload.executionMode,
                            notificationId = NOTIFICATION_ID_DIP_BASE + (event.payload.ruleId % 100).toInt()
                        )

                        is SentinelRuntimeEvent.OrphanedRulesRecovered -> {
                            val syms = event.rules.joinToString(", ") { it.symbol }
                            postDisconnectAlarm(
                                "Sentinel restarted mid-exit on $syms — escalated to UNFILLED_ESCALATED. Verify positions/orders."
                            )
                        }

                        is SentinelRuntimeEvent.InfoMessage -> Unit
                    }
                }
            }
        }

        // M-5 Safeguard: Run startup orphan recovery sequentially BEFORE starting monitoringJob & quotesJob
        startupJob = serviceScope.launch {
            runCatching { runtimeCoordinator.recoverOrphanedExecutingRules() }
            synchronized(sessionLock) {
                if (isShuttingDown || serviceSessionId.get() != expectedSessionId) return@launch
                launchPostRecoveryLoopsLocked()
            }
        }
    }

    private fun launchPostRecoveryLoopsLocked() {
        if (monitoringJob?.isActive == true) return

        // 1. C-1 + H-5 Safeguard: Observe active rules, mode, activeExecutionCount, & Keystore auth state
        monitoringJob = serviceScope.launch {
            combine(
                sentinelRepository.observeActiveRules(),
                preferences.executionMode,
                runtimeCoordinator.activeExecutionCount,
                credentialVault.authSnapshotFlow
            ) { allActive, mode, execCount, authSnapshot ->
                MonitoringSnapshot(allActive, mode, execCount, authSnapshot)
            }.collect { (allActive, mode, execCount, authSnapshot) ->
                if (isShuttingDown) return@collect
                val activeInMode = allActive.filter { it.executionMode == mode }
                val hasActiveWork = activeInMode.isNotEmpty() || execCount > 0
                currentActiveRules = activeInMode
                currentExecutionMode = mode
                synchronized(lockSync) {
                    hasActiveModeArmedRules = hasActiveWork
                }

                if (hasActiveWork) {
                    hadArmedRulesInSession = true
                    acquireLocksIfNeeded()

                    val symbols = activeInMode.map { it.symbol }.toSet()
                    val hasOAuthToken = !credentialVault.getAccessToken().isNullOrBlank()
                    val mustRemainLive = mode == ExecutionMode.LIVE ||
                        authSnapshot.hasRefreshToken ||
                        authSnapshot.vaultUnavailableTransient
                    currentMustRemainLive = mustRemainLive

                    if (hasOAuthToken) {
                        isLiveStreamingActive = true
                        currentActiveLiveSymbols = symbols
                        val conn = streamerClient.connectionState.value
                        if (!streamerClient.isError12Halted() &&
                            (conn == StreamerConnectionState.DISCONNECTED ||
                                conn == StreamerConnectionState.SIMULATED ||
                                conn == StreamerConnectionState.ERROR)
                        ) {
                            streamerClient.connectLive(serviceScope, symbols)
                        } else if (activeInMode.isNotEmpty()) {
                            streamerClient.syncEquitySubscriptions(symbols)
                        }
                    } else if (mustRemainLive) {
                        // H-5 + M-1 + M-3 Safeguard: Never downgrade LIVE mode or transient Keystore faults to SIMULATED,
                        // and keep syncing subscriptions across AUTHENTICATED / CONNECTING / RECONNECTING.
                        isLiveStreamingActive = true
                        currentActiveLiveSymbols = symbols
                        syncMustRemainLiveState(
                            streamerClient = streamerClient,
                            authSnapshot = authSnapshot,
                            activeSymbols = symbols,
                            scope = serviceScope
                        )
                    } else {
                        isLiveStreamingActive = false
                        currentActiveLiveSymbols = emptySet()
                        if (streamerClient.connectionState.value != StreamerConnectionState.SIMULATED) {
                            streamerClient.enterSimulatedMode()
                        }
                    }
                } else {
                    currentMustRemainLive = false
                    currentActiveLiveSymbols = emptySet()
                    releaseLocks()
                    isLiveStreamingActive = false
                    runCatching { streamerClient.disconnectGracefully() }
                    if (hadArmedRulesInSession && allActive.isEmpty() && execCount == 0) {
                        val expectedSession = synchronized(sessionLock) { serviceSessionId.get() }
                        shutdownAndStop(
                            expectedSessionId = expectedSession,
                            stopStartId = latestStartId.get()
                        )
                        return@collect
                    }
                }

                publishStatusAndOngoingNotification()
            }
        }

        // 2. Process incoming quotes through AntiStopHuntEvaluator & State Machine
        if (quotesJob?.isActive != true) {
            quotesJob = serviceScope.launch {
                streamerClient.equityQuotes.collect { quote ->
                    runtimeCoordinator.evaluateIncomingQuote(
                        quote = quote,
                        scope = serviceScope
                    )
                }
            }
        }

        // 3. 5-second Watchdog, WakeLock Renewal & Persistent Notification Refresh loop
        if (watchdogJob?.isActive != true) {
            watchdogJob = serviceScope.launch {
                while (isActive && !isShuttingDown) {
                    val now = System.currentTimeMillis()
                    acquireLocksIfNeeded(now)

                    probeKeystoreRecoveryIfNeeded(
                        mustRemainLive = currentMustRemainLive,
                        credentialVault = credentialVault
                    )

                    selfHealDisconnectedStreamerIfNeeded(
                        isShuttingDown = isShuttingDown,
                        activeLiveSymbols = currentActiveLiveSymbols,
                        credentialVault = credentialVault,
                        streamerClient = streamerClient,
                        scope = serviceScope
                    )

                    publishStatusAndOngoingNotification()

                    val automatedRules = currentActiveRules.filter {
                        it.status == SentinelRuleStatus.ARMED && it.isAutomatedExitRule
                    }
                    val alarm = disconnectWatchdog.evaluate(
                        hasArmedAutomatedRules = automatedRules.isNotEmpty(),
                        isLiveStreamingActive = isLiveStreamingActive,
                        connectionState = streamerClient.connectionState.value,
                        lastHeartbeatEpochMs = streamerClient.lastHeartbeatEpochMs.value,
                        nowEpochMs = now,
                        armedAutomatedRuleCount = automatedRules.size
                    )
                    if (alarm != null) {
                        postDisconnectAlarm(alarm.reason)
                        sentinelRepository.recordAuditEvent(
                            category = "SENTINEL_DISCONNECT_ALARM",
                            severity = "CRITICAL",
                            symbol = null,
                            executionMode = currentExecutionMode.name,
                            message = alarm.reason
                        )
                    }

                    delay(WATCHDOG_INTERVAL_MS)
                }
            }
        }
    }

    private fun shutdownAndStop(
        expectedSessionId: Long,
        stopStartId: Int
    ) {
        synchronized(sessionLock) {
            // H-4 Stale Teardown Guard: Check BEFORE canceling any jobs or disconnecting Streamer
            if (serviceSessionId.get() != expectedSessionId) return
            isShuttingDown = true
            startupJob?.cancel()
            startupJob = null
            monitoringJob?.cancel()
            monitoringJob = null
            quotesJob?.cancel()
            quotesJob = null
            watchdogJob?.cancel()
            watchdogJob = null
            synchronized(lockSync) { hasActiveModeArmedRules = false }
            releaseLocks()

            shutdownJob?.cancel()
            shutdownJob = serviceScope.launch {
                performShutdownStop(expectedSessionId = expectedSessionId, stopStartId = stopStartId)
            }
        }
    }

    private suspend fun performShutdownStop(
        expectedSessionId: Long,
        stopStartId: Int
    ) {
        executeGuardedShutdownStop(
            sessionLock = sessionLock,
            serviceSessionId = serviceSessionId,
            expectedSessionId = expectedSessionId,
            streamerClient = streamerClient
        ) {
            eventsJob?.cancel()
            eventsJob = null
            currentMustRemainLive = false
            currentActiveLiveSymbols = emptySet()
            synchronized(lockSync) { hasActiveModeArmedRules = false }
            releaseLocks()
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            if (stopStartId >= 0) {
                stopSelf(stopStartId)
            } else {
                stopSelf()
            }
        }
    }

    private fun publishStatusAndOngoingNotification() {
        val conn = streamerClient.connectionState.value
        val session = marketSessionResolver.resolve()
        val heartbeat = streamerClient.lastHeartbeatEpochMs.value.takeIf { it > 0L }
        val activeRulesSnapshot = currentActiveRules
        val armedCount = activeRulesSnapshot.count { it.status == SentinelRuleStatus.ARMED }

        runtimeCoordinator.updateServiceStatus {
            it.copy(
                isServiceRunning = true,
                connectionState = conn,
                armedRuleCount = armedCount,
                lastHeartbeatEpochMs = heartbeat,
                activeSession = session,
                disconnectReason = streamerClient.lastErrorMessage.value
            )
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.notify(
            NOTIFICATION_ID_ONGOING,
            buildOngoingNotification(
                connectionState = conn,
                armedCount = armedCount,
                sessionLabel = session.shortLabel,
                mode = currentExecutionMode
            )
        )
    }

    private fun startInForegroundCompat(notification: Notification): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID_ONGOING,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID_ONGOING, notification)
            }
            true
        } catch (_: ForegroundServiceStartNotAllowedException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalStateException) {
            false
        }
    }

    fun postDisconnectAlarm(reason: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        val openIntent = Intent(this, SentinelDeepLinkActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_NAV_DESTINATION, NavRoutes.SENTINEL)
        }
        val contentPi = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID_DISCONNECT,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, NotificationChannels.DISCONNECT_ALARM)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Schwab Sentinel Alert")
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentPi)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID_DISCONNECT, notification)
    }

    private fun postExecutionFillNotification(
        title: String,
        body: String,
        notificationId: Int
    ) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val openIntent = Intent(this, SentinelDeepLinkActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_NAV_DESTINATION, NavRoutes.SENTINEL)
        }
        val pi = PendingIntent.getActivity(
            this,
            notificationId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, NotificationChannels.EXECUTION_FILL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        manager.notify(notificationId, notification)
    }

    private fun postActionableOrderNotification(
        channelId: String,
        title: String,
        body: String,
        actionLabel: String,
        draft: OrderDraft,
        executionMode: ExecutionMode,
        notificationId: Int
    ) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val firstLeg = draft.legs.firstOrNull()
        val stageIntent = Intent(this, SentinelDeepLinkActivity::class.java).apply {
            action = MainActivity.ACTION_STAGE_SENTINEL_ORDER
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_NAV_DESTINATION, NavRoutes.SENTINEL)
            putExtra(MainActivity.EXTRA_STAGE_EXECUTION_MODE, executionMode.name)
            putExtra(MainActivity.EXTRA_STAGE_SYMBOL, draft.underlyingSymbol)
            putExtra(MainActivity.EXTRA_STAGE_SIDE, (firstLeg?.side?.name ?: "SELL"))
            putExtra(MainActivity.EXTRA_STAGE_QTY, firstLeg?.quantity ?: 1)
            putExtra(MainActivity.EXTRA_STAGE_LIMIT_PRICE, draft.limitPrice?.toPlainString().orEmpty())
            putExtra(MainActivity.EXTRA_STAGE_SOURCE_LABEL, draft.sourceLabel)
            putExtra(MainActivity.EXTRA_STAGE_REPLACE_ORDER_ID, draft.replacePriorOrderId.orEmpty())
            putExtra(MainActivity.EXTRA_STAGE_IS_ESCALATED_REPRICE, draft.isEscalatedReprice)
        }
        val stagePi = PendingIntent.getActivity(
            this,
            notificationId,
            stageIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(stagePi)
            .addAction(0, actionLabel, stagePi)
            .setAutoCancel(true)
            .build()
        manager.notify(notificationId, notification)
    }

    private fun buildOngoingNotification(
        connectionState: StreamerConnectionState,
        armedCount: Int,
        sessionLabel: String,
        mode: ExecutionMode
    ): Notification {
        val openIntent = Intent(this, SentinelDeepLinkActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_NAV_DESTINATION, NavRoutes.SENTINEL)
        }
        val openPi = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID_ONGOING,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disarmIntent = Intent(this, SentinelForegroundService::class.java).apply {
            action = ACTION_DISARM_ALL
        }
        val disarmPi = PendingIntent.getForegroundService(
            this,
            NOTIFICATION_ID_DISARM_ACTION,
            disarmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = "${connectionState.name} • $armedCount armed • $sessionLabel • ${mode.name}"
        return NotificationCompat.Builder(this, NotificationChannels.SENTINEL_SERVICE)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Schwab Quant 24/5 Sentinel")
            .setContentText(contentText)
            .setContentIntent(openPi)
            .addAction(0, "Disarm All", disarmPi)
            .setOngoing(true)
            .build()
    }

    private fun acquireLocksIfNeeded(nowEpochMs: Long = System.currentTimeMillis()) = synchronized(lockSync) {
        if (isShuttingDown || !hasActiveModeArmedRules) return@synchronized

        val needsWakeLockRenewal = wakeLock?.isHeld != true ||
            (nowEpochMs - wakeLockAcquiredAtEpochMs) >= WAKELOCK_RENEWAL_INTERVAL_MS
        if (needsWakeLockRenewal) {
            runCatching {
                if (wakeLock?.isHeld == true) wakeLock?.release()
            }
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            runCatching {
                wakeLock = powerManager?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "SchwabQuant::SentinelWakeLock"
                )?.apply {
                    setReferenceCounted(false)
                    acquire(WAKELOCK_TIMEOUT_MS)
                }
                wakeLockAcquiredAtEpochMs = nowEpochMs
            }
        }

        if (wifiLock?.isHeld != true) {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            runCatching {
                wifiLock = wifiManager?.createWifiLock(lockMode, "SchwabQuant::SentinelWifiLock")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        }
    }

    private fun releaseLocks() = synchronized(lockSync) {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        runCatching {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        }
        wakeLock = null
        wifiLock = null
        wakeLockAcquiredAtEpochMs = 0L
    }

    companion object {
        const val ACTION_START = "com.schwabquant.app.service.ACTION_START_SENTINEL"
        const val ACTION_STOP = "com.schwabquant.app.service.ACTION_STOP_SENTINEL"
        const val ACTION_DISARM_ALL = "com.schwabquant.app.service.ACTION_DISARM_ALL_SENTINEL"

        private const val NOTIFICATION_ID_ONGOING = 4001
        private const val NOTIFICATION_ID_DISCONNECT = 4002
        private const val NOTIFICATION_ID_DISARM_ACTION = 4003
        private const val NOTIFICATION_ID_FILL_BASE = 4100
        private const val NOTIFICATION_ID_OVERNIGHT_BASE = 4200
        private const val NOTIFICATION_ID_DIP_BASE = 4300
        private const val NOTIFICATION_ID_ESCALATED_BASE = 4400

        private const val WAKELOCK_TIMEOUT_MS = 12L * 60L * 60L * 1_000L
        private const val WAKELOCK_RENEWAL_INTERVAL_MS = 6L * 60L * 60L * 1_000L
        private const val WATCHDOG_INTERVAL_MS = 5_000L

        internal data class ActionStartTransition(
            val newSessionId: Long?,
            val shouldTriggerExplicitError12Reconnect: Boolean
        )

        internal fun handleActionStartLocked(
            serviceSessionId: AtomicLong,
            latestStartId: AtomicInteger,
            startId: Int,
            isShuttingDown: Boolean,
            monitoringJobActive: Boolean,
            startupJobActive: Boolean,
            isExplicitStart: Boolean,
            streamerClient: SchwabStreamerClient
        ): ActionStartTransition {
            val newSession = onActionStartLocked(
                serviceSessionId = serviceSessionId,
                latestStartId = latestStartId,
                startId = startId,
                isShuttingDown = isShuttingDown,
                monitoringJobActive = monitoringJobActive,
                startupJobActive = startupJobActive
            )
            return if (newSession != null) {
                // Advance ownerEpoch synchronously under sessionLock; clear Error 12 ONLY on explicit ACTION_START
                streamerClient.advanceOwnerEpoch()
                if (isExplicitStart) {
                    streamerClient.clearError12Halt()
                }
                ActionStartTransition(
                    newSessionId = newSession,
                    shouldTriggerExplicitError12Reconnect = false
                )
            } else {
                val shouldReconnect = isExplicitStart &&
                    streamerClient.isError12Halted() &&
                    streamerClient.clearError12Halt()
                ActionStartTransition(
                    newSessionId = null,
                    shouldTriggerExplicitError12Reconnect = shouldReconnect
                )
            }
        }

        internal fun onActionStartLocked(
            serviceSessionId: AtomicLong,
            latestStartId: AtomicInteger,
            startId: Int,
            isShuttingDown: Boolean,
            monitoringJobActive: Boolean,
            startupJobActive: Boolean
        ): Long? {
            latestStartId.set(startId)
            val alreadyRunning = !isShuttingDown && (monitoringJobActive || startupJobActive)
            return if (alreadyRunning) null else serviceSessionId.incrementAndGet()
        }

        internal suspend fun executeGuardedShutdownStop(
            sessionLock: Any,
            serviceSessionId: AtomicLong,
            expectedSessionId: Long,
            streamerClient: SchwabStreamerClient,
            onFinalTeardownLocked: () -> Unit
        ) {
            val (capturedGen, capturedOwnerEpoch) = synchronized(sessionLock) {
                if (serviceSessionId.get() != expectedSessionId) return
                streamerClient.currentConnectionGeneration() to streamerClient.currentOwnerEpoch()
            }
            currentCoroutineContext().ensureActive()
            if (synchronized(sessionLock) { serviceSessionId.get() != expectedSessionId }) return
            runCatching {
                streamerClient.disconnectGracefully(
                    expectedGeneration = capturedGen,
                    expectedOwnerEpoch = capturedOwnerEpoch
                )
            }
            synchronized(sessionLock) {
                // H-1 + H-4 + M-1 Atomic Teardown Guard: Lock/foreground teardown must hold sessionLock with session check
                if (serviceSessionId.get() != expectedSessionId) return
                onFinalTeardownLocked()
            }
        }

        internal suspend fun syncMustRemainLiveState(
            streamerClient: SchwabStreamerClient,
            authSnapshot: StoredAuthSnapshot,
            activeSymbols: Set<String>,
            scope: CoroutineScope
        ) {
            val conn = streamerClient.connectionState.value
            if (conn == StreamerConnectionState.SIMULATED) {
                runCatching { streamerClient.disconnectGracefully() }
            }
            if ((conn == StreamerConnectionState.AUTHENTICATED ||
                    conn == StreamerConnectionState.CONNECTING ||
                    conn == StreamerConnectionState.RECONNECTING) &&
                activeSymbols.isNotEmpty()
            ) {
                streamerClient.syncEquitySubscriptions(activeSymbols)
            } else if (!streamerClient.isError12Halted() &&
                authSnapshot.hasRefreshToken &&
                !authSnapshot.vaultUnavailableTransient &&
                (conn == StreamerConnectionState.DISCONNECTED ||
                    conn == StreamerConnectionState.SIMULATED ||
                    conn == StreamerConnectionState.ERROR)
            ) {
                streamerClient.connectLive(scope, activeSymbols)
            }
        }

        internal suspend fun selfHealDisconnectedStreamerIfNeeded(
            isShuttingDown: Boolean,
            activeLiveSymbols: Set<String>,
            credentialVault: CredentialVault,
            streamerClient: SchwabStreamerClient,
            scope: CoroutineScope
        ): Boolean {
            if (isShuttingDown || activeLiveSymbols.isEmpty() || streamerClient.isError12Halted()) {
                return false
            }
            if (streamerClient.connectionState.value != StreamerConnectionState.DISCONNECTED) {
                return false
            }
            val authSnap = runCatching { credentialVault.authSnapshotFlow.value }.getOrNull()
            val hasOAuthCreds = !credentialVault.getAccessToken().isNullOrBlank() ||
                (authSnap != null && authSnap.hasRefreshToken && !authSnap.vaultUnavailableTransient)
            if (!hasOAuthCreds) {
                return false
            }
            streamerClient.connectLive(scope, activeLiveSymbols)
            return true
        }

        internal fun probeKeystoreRecoveryIfNeeded(
            mustRemainLive: Boolean,
            credentialVault: CredentialVault
        ): Boolean {
            if (!mustRemainLive || !credentialVault.authSnapshotFlow.value.vaultUnavailableTransient) {
                return false
            }
            return runCatching { credentialVault.probeKeystoreRecovery() }.getOrDefault(false)
        }

        /**
         * Android 14+ foreground service start guard catching
         * `ForegroundServiceStartNotAllowedException` & `SecurityException` cleanly.
         */
        fun startSafely(context: Context): Result<Unit> {
            val intent = Intent(context, SentinelForegroundService::class.java).apply {
                action = ACTION_START
            }
            return try {
                ContextCompat.startForegroundService(context, intent)
                Result.success(Unit)
            } catch (e: SecurityException) {
                Result.failure(
                    IllegalStateException(
                        "Cannot start 24/5 Sentinel: notification or foreground service permission denied.",
                        e
                    )
                )
            } catch (e: IllegalStateException) {
                val msg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e is ForegroundServiceStartNotAllowedException
                ) {
                    "Android blocked background Sentinel launch. Open the app in foreground to arm 24/5 Sentinel."
                } else {
                    e.message ?: "Unable to start Sentinel Foreground Service."
                }
                Result.failure(IllegalStateException(msg, e))
            }
        }

        /**
         * H-5 Safeguard: `stopService` is background-safe across Android 12–16 (unlike `startService`).
         */
        fun stopSafely(context: Context) {
            runCatching {
                context.stopService(Intent(context, SentinelForegroundService::class.java))
            }
        }
    }
}
