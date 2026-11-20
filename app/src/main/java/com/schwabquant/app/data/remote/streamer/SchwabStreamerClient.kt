package com.schwabquant.app.data.remote.streamer

import com.schwabquant.app.core.network.SchwabTokenRefreshCoordinator
import com.schwabquant.app.core.security.CredentialVault
import com.schwabquant.app.data.remote.api.SchwabTraderApi
import com.schwabquant.app.data.remote.dto.SchwabSymbolNormalizer
import com.schwabquant.app.di.StreamerOkHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production single-connection Schwab WebSocket Streamer v1 client.
 * - Enforces Schwab's strict MAX 1 active WebSocket rule (halts reconnect on Error 12).
 * - C-2: Prevents self-cancellation inside `scheduleReconnect()`, never swallows
 *   `CancellationException`, and retries transient `userPreference`/token/socket errors.
 * - H-3: Synchronizes socket/subscription validation & cache writes under `stateLock` so
 *   unsubscribed/disconnected frames cannot resurrect quotes, and tracks `hasFreshBidAskUpdate`.
 */
@Singleton
class SchwabStreamerClient(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val traderApi: SchwabTraderApi?,
    private val tokenRefreshCoordinator: SchwabTokenRefreshCoordinator?,
    private val credentialVault: CredentialVault?,
    private val nowProvider: () -> Long = System::currentTimeMillis,
    private val backoffDelaysMs: LongArray = DEFAULT_BACKOFF_DELAYS_MS
) {

    @Inject
    constructor(
        @StreamerOkHttp okHttpClient: OkHttpClient,
        json: Json,
        traderApi: SchwabTraderApi,
        tokenRefreshCoordinator: SchwabTokenRefreshCoordinator,
        credentialVault: CredentialVault
    ) : this(
        okHttpClient = okHttpClient,
        json = json,
        traderApi = traderApi,
        tokenRefreshCoordinator = tokenRefreshCoordinator,
        credentialVault = credentialVault,
        nowProvider = System::currentTimeMillis,
        backoffDelaysMs = DEFAULT_BACKOFF_DELAYS_MS
    )

    private val connectionMutex = Mutex()
    private val stateLock = Any()
    private val requestCounter = AtomicLong(1L)
    private val quoteCache = ConcurrentHashMap<String, LiveEquityQuote>()

    private val _connectionState = MutableStateFlow(StreamerConnectionState.DISCONNECTED)
    val connectionState: StateFlow<StreamerConnectionState> = _connectionState.asStateFlow()

    private val _lastHeartbeatEpochMs = MutableStateFlow(0L)
    val lastHeartbeatEpochMs: StateFlow<Long> = _lastHeartbeatEpochMs.asStateFlow()

    private val _lastErrorMessage = MutableStateFlow<String?>(null)
    val lastErrorMessage: StateFlow<String?> = _lastErrorMessage.asStateFlow()

    private val _liveQuotesSnapshot = MutableStateFlow<Map<String, LiveEquityQuote>>(emptyMap())
    val liveQuotesSnapshot: StateFlow<Map<String, LiveEquityQuote>> = _liveQuotesSnapshot.asStateFlow()

    private val _equityQuotes = MutableSharedFlow<LiveEquityQuote>(extraBufferCapacity = 128)
    val equityQuotes: SharedFlow<LiveEquityQuote> = _equityQuotes.asSharedFlow()

    private val _accountEvents = MutableSharedFlow<AccountActivityEvent>(extraBufferCapacity = 64)
    val accountEvents: SharedFlow<AccountActivityEvent> = _accountEvents.asSharedFlow()

    @Volatile
    private var activeSocket: WebSocket? = null

    @Volatile
    private var managedScope: CoroutineScope? = null

    @Volatile
    private var reconnectJob: Job? = null

    @Volatile
    private var reconnectAttempt: Int = 0

    @Volatile
    private var intentionalDisconnect: Boolean = false

    @Volatile
    private var error12Halted: Boolean = false

    @Volatile
    private var connectionGeneration: Long = 0L

    @Volatile
    private var ownerEpoch: Long = 0L

    @Volatile
    private var hasExplicitSubscriptions: Boolean = false

    @Volatile
    private var activeCustomerId: String = ""

    @Volatile
    private var activeCorrelId: String = ""

    private val desiredSentinelSymbols: LinkedHashSet<String> = linkedSetOf()

    @Volatile
    private var desiredChartOverlayDisplaySymbol: String? = null

    @Volatile
    private var desiredChartOverlayApiSymbol: String? = null

    private val subscribedApiSymbols: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun currentConnectionGeneration(): Long = synchronized(stateLock) { connectionGeneration }

    fun advanceOwnerEpoch(): Long = synchronized(stateLock) { ++ownerEpoch }

    fun currentOwnerEpoch(): Long = synchronized(stateLock) { ownerEpoch }

    fun isError12Halted(): Boolean = synchronized(stateLock) { error12Halted }

    fun clearError12Halt(): Boolean = synchronized(stateLock) {
        val wasHalted = error12Halted
        if (!wasHalted) return@synchronized false
        error12Halted = false
        intentionalDisconnect = false
        if (activeSocket == null &&
            (_connectionState.value == StreamerConnectionState.ERROR_CONFLICT_CLOSE_12 ||
                _connectionState.value == StreamerConnectionState.ERROR ||
                _connectionState.value == StreamerConnectionState.DISCONNECTED)
        ) {
            _connectionState.value = StreamerConnectionState.DISCONNECTED
            _lastErrorMessage.value = null
        }
        true
    }

    suspend fun connectLive(
        scope: CoroutineScope,
        initialSymbols: Set<String> = emptySet()
    ): Result<Unit> = connectLiveInternal(
        scope = scope,
        initialSymbols = initialSymbols,
        invokedFromReconnect = false,
        expectedGeneration = null
    )

    private suspend fun connectLiveInternal(
        scope: CoroutineScope,
        initialSymbols: Set<String>,
        invokedFromReconnect: Boolean,
        expectedGeneration: Long?
    ): Result<Unit> {
        var shouldScheduleReconnect = false
        var activeGeneration = -1L
        val callerJob = currentCoroutineContext()[Job]

        val result = connectionMutex.withLock {
            try {
                val api = traderApi ?: error("SchwabTraderApi not configured.")
                val vault = credentialVault ?: error("CredentialVault not configured.")

                val abortedByState = synchronized(stateLock) {
                    if (invokedFromReconnect &&
                        (intentionalDisconnect ||
                            error12Halted ||
                            (expectedGeneration != null && connectionGeneration != expectedGeneration))
                    ) {
                        true
                    } else {
                        connectionGeneration++
                        activeGeneration = connectionGeneration
                        managedScope = scope
                        if (!invokedFromReconnect) {
                            if (reconnectJob !== callerJob) {
                                reconnectJob?.cancel()
                            }
                            reconnectJob = null
                            reconnectAttempt = 0
                            intentionalDisconnect = false
                            error12Halted = false
                        } else {
                            if (reconnectJob === callerJob) {
                                reconnectJob = null
                            }
                        }
                        hasExplicitSubscriptions = true

                        // Close any existing socket before opening a new one (MAX 1 rule)
                        closeActiveSocketInternal(sendLogout = !invokedFromReconnect)

                        desiredSentinelSymbols.clear()
                        initialSymbols.forEach { sym ->
                            val clean = SchwabSymbolNormalizer.toDisplaySymbol(sym)
                            if (clean.isNotBlank()) desiredSentinelSymbols.add(clean)
                        }
                        subscribedApiSymbols.clear()
                        clearQuoteCacheInternal()
                        _lastErrorMessage.value = null
                        _connectionState.value = StreamerConnectionState.CONNECTING
                        false
                    }
                }
                if (abortedByState) {
                    return@withLock Result.success(Unit)
                }

                val prefs = api.getUserPreference()
                val info = prefs.streamerInfo.firstOrNull()
                    ?: error("Schwab userPreference returned empty streamerInfo.")

                val socketUrl = info.streamerSocketUrl?.takeIf { it.isNotBlank() }
                    ?: DEFAULT_STREAMER_URL
                val customerId = info.schwabClientCustomerId?.takeIf { it.isNotBlank() }
                    ?: error("Missing schwabClientCustomerId in userPreference.")
                val correlId = info.schwabClientCorrelId?.takeIf { it.isNotBlank() }
                    ?: error("Missing schwabClientCorrelId in userPreference.")
                val channel = info.schwabClientChannel?.takeIf { it.isNotBlank() } ?: "IO"
                val functionId = info.schwabClientFunctionId?.takeIf { it.isNotBlank() } ?: "APIAPP"

                val accessToken = withContext(Dispatchers.IO) {
                    if (reconnectAttempt > 0) {
                        tokenRefreshCoordinator?.refreshNow()?.getOrNull()
                            ?: tokenRefreshCoordinator?.refreshIfNeededForProactiveWindow()
                            ?: vault.getAccessToken()
                    } else {
                        tokenRefreshCoordinator?.refreshIfNeededForProactiveWindow()
                            ?: vault.getAccessToken()
                    }
                }?.takeIf { it.isNotBlank() }
                    ?: error("No valid OAuth access token available for Schwab Streamer.")

                val stoppedBeforeSocket = synchronized(stateLock) {
                    intentionalDisconnect || error12Halted || connectionGeneration != activeGeneration
                }
                if (stoppedBeforeSocket) {
                    return@withLock Result.success(Unit)
                }

                activeCustomerId = customerId
                activeCorrelId = correlId

                val loginEnvelope = StreamerLoginRequestFactory.create(
                    requestId = nextRequestId(),
                    schwabClientCustomerId = customerId,
                    schwabClientCorrelId = correlId,
                    accessToken = accessToken,
                    schwabClientChannel = channel,
                    schwabClientFunctionId = functionId
                )
                val loginPayload = json.encodeToString(StreamerEnvelopeRequest.serializer(), loginEnvelope)

                val request = Request.Builder().url(socketUrl).build()
                val myGeneration = activeGeneration
                val listener = object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        val isCurrent = synchronized(stateLock) {
                            webSocket === activeSocket &&
                                connectionGeneration == myGeneration &&
                                !intentionalDisconnect &&
                                !error12Halted
                        }
                        if (!isCurrent) return
                        webSocket.send(loginPayload)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val isCurrent = synchronized(stateLock) {
                            webSocket === activeSocket &&
                                connectionGeneration == myGeneration &&
                                !intentionalDisconnect &&
                                !error12Halted
                        }
                        if (!isCurrent) return
                        processIncomingMessageInternal(
                            rawJson = text,
                            nowEpochMs = nowProvider(),
                            sourceSocket = webSocket,
                            sourceGeneration = myGeneration
                        )
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        val isCurrent = synchronized(stateLock) {
                            webSocket === activeSocket && connectionGeneration == myGeneration
                        }
                        if (!isCurrent) return
                        runCatching { webSocket.close(1000, null) }
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        val shouldReconnect = synchronized(stateLock) {
                            if (webSocket !== activeSocket || connectionGeneration != myGeneration) return
                            activeSocket = null
                            subscribedApiSymbols.clear()
                            clearQuoteCacheInternal()
                            val retry = !intentionalDisconnect && !error12Halted
                            if (retry) {
                                _lastErrorMessage.value = "Streamer closed ($code): $reason"
                                _connectionState.value = StreamerConnectionState.RECONNECTING
                            } else if (!error12Halted) {
                                _connectionState.value = StreamerConnectionState.DISCONNECTED
                            }
                            retry
                        }
                        if (shouldReconnect) {
                            scheduleReconnect(scope, myGeneration)
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        val shouldReconnect = synchronized(stateLock) {
                            if (webSocket !== activeSocket || connectionGeneration != myGeneration) return
                            activeSocket = null
                            subscribedApiSymbols.clear()
                            clearQuoteCacheInternal()
                            val retry = !intentionalDisconnect && !error12Halted
                            if (retry) {
                                _lastErrorMessage.value = t.message ?: "WebSocket connection failure"
                                _connectionState.value = StreamerConnectionState.RECONNECTING
                            } else if (!error12Halted) {
                                _connectionState.value = StreamerConnectionState.DISCONNECTED
                            }
                            retry
                        }
                        if (shouldReconnect) {
                            scheduleReconnect(scope, myGeneration)
                        }
                    }
                }

                // Assign activeSocket inside stateLock so onOpen blocks until assignment completes
                synchronized(stateLock) {
                    if (!intentionalDisconnect && !error12Halted && connectionGeneration == myGeneration) {
                        activeSocket = okHttpClient.newWebSocket(request, listener)
                    }
                }
                Result.success(Unit)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                val canRetry = synchronized(stateLock) {
                    if (activeGeneration > 0L && connectionGeneration != activeGeneration) {
                        return@synchronized false
                    }
                    _lastErrorMessage.value = t.message ?: "Schwab Streamer connection failed."
                    val retry = activeGeneration > 0L && !intentionalDisconnect && !error12Halted && managedScope != null
                    if (retry) {
                        _connectionState.value = StreamerConnectionState.RECONNECTING
                    } else if (!error12Halted) {
                        _connectionState.value = StreamerConnectionState.ERROR
                    }
                    retry
                }
                shouldScheduleReconnect = canRetry
                Result.failure(t)
            }
        }

        if (shouldScheduleReconnect && activeGeneration > 0L) {
            scheduleReconnect(scope, activeGeneration)
        }
        return result
    }

    fun enterSimulatedMode() {
        synchronized(stateLock) {
            connectionGeneration++
            intentionalDisconnect = true
            hasExplicitSubscriptions = false
            reconnectJob?.cancel()
            reconnectJob = null
            closeActiveSocketInternal(sendLogout = false)
            subscribedApiSymbols.clear()
            _lastErrorMessage.value = null
            _lastHeartbeatEpochMs.value = nowProvider()
            _connectionState.value = StreamerConnectionState.SIMULATED
        }
    }

    /**
     * Injects a synthetic quote directly into the atomic Delta Merge cache & emission stream
     * for offline Shadow Mode Tick Replay scenarios.
     */
    fun injectSimulatedQuote(quote: LiveEquityQuote) {
        val displaySym = SchwabSymbolNormalizer.toDisplaySymbol(quote.symbol)
        val merged = synchronized(stateLock) {
            val m = quoteCache.compute(displaySym) { _, prev ->
                mergeQuoteSnapshot(displaySym, prev, quote)
            }!!
            _liveQuotesSnapshot.value = quoteCache.toMap()
            m
        }
        _lastHeartbeatEpochMs.value = merged.receivedAtEpochMs.takeIf { it > 0L } ?: nowProvider()
        _equityQuotes.tryEmit(merged)
    }

    /**
     * Incrementally subscribes/unsubscribes LEVELONE_EQUITIES keys (`ADD` / `UNSUBS`) without
     * dropping existing active subscriptions or the separate Chart UI overlay ticker.
     * Normalizes share classes (`BRK.B` -> `BRK/B`).
     */
    suspend fun syncEquitySubscriptions(targetDisplaySymbols: Set<String>) = connectionMutex.withLock {
        val cleanDisplay = targetDisplaySymbols
            .map { SchwabSymbolNormalizer.toDisplaySymbol(it) }
            .filter { it.isNotBlank() }
            .toSet()

        synchronized(stateLock) {
            hasExplicitSubscriptions = true
            desiredSentinelSymbols.clear()
            desiredSentinelSymbols.addAll(cleanDisplay)

            val unionDisplay = effectiveDesiredDisplaySymbolsLocked()
            val removedCached = quoteCache.keys.filter { it !in unionDisplay }
            if (removedCached.isNotEmpty()) {
                removedCached.forEach { quoteCache.remove(it) }
                _liveQuotesSnapshot.value = quoteCache.toMap()
            }

            val socket = activeSocket
            if (socket == null || _connectionState.value != StreamerConnectionState.AUTHENTICATED) {
                return@withLock
            }
            reconcileEquitySubscriptionsLocked(socket)
        }
    }

    /**
     * Chart UI overlay subscription strictly separated from Sentinel's `desiredSentinelSymbols`.
     * Sends `SUBS`/`ADD`/`UNSUBS` against the union (`desiredSentinelSymbols + overlay`) without
     * ever opening/closing Sentinel's WebSocket or altering `connectionGeneration`, `ownerEpoch`,
     * or `error12Halted`.
     */
    fun setChartOverlaySymbol(displaySymbol: String?) {
        val cleanDisplay = displaySymbol
            ?.let { SchwabSymbolNormalizer.toDisplaySymbol(it) }
            ?.takeIf { it.isNotBlank() }
        val cleanApi = cleanDisplay?.let { SchwabSymbolNormalizer.toSchwabMarketDataSymbol(it) }

        synchronized(stateLock) {
            desiredChartOverlayDisplaySymbol = cleanDisplay
            desiredChartOverlayApiSymbol = cleanApi

            val unionDisplay = effectiveDesiredDisplaySymbolsLocked()
            val removedCached = quoteCache.keys.filter { it !in unionDisplay }
            if (removedCached.isNotEmpty()) {
                removedCached.forEach { quoteCache.remove(it) }
                _liveQuotesSnapshot.value = quoteCache.toMap()
            }

            val socket = activeSocket
            if (socket != null && _connectionState.value == StreamerConnectionState.AUTHENTICATED) {
                reconcileEquitySubscriptionsLocked(socket)
            }
        }
    }

    private fun effectiveDesiredDisplaySymbolsLocked(): Set<String> =
        LinkedHashSet<String>(desiredSentinelSymbols.size + 1).apply {
            addAll(desiredSentinelSymbols)
            desiredChartOverlayDisplaySymbol?.let(::add)
        }

    private fun effectiveDesiredApiSymbolsLocked(): Set<String> =
        effectiveDesiredDisplaySymbolsLocked()
            .map { SchwabSymbolNormalizer.toSchwabMarketDataSymbol(it) }
            .toCollection(LinkedHashSet())

    private fun reconcileEquitySubscriptionsLocked(socket: WebSocket) {
        val targetApi = effectiveDesiredApiSymbolsLocked()
        val toAdd = (targetApi - subscribedApiSymbols).toList()
        val toRemove = (subscribedApiSymbols - targetApi).toList()

        if (toAdd.isNotEmpty()) {
            val command = if (subscribedApiSymbols.isEmpty()) StreamerCommand.SUBS else StreamerCommand.ADD
            sendEnvelope(
                socket,
                StreamerRequestFactory.createEquitySubscription(
                    requestId = nextRequestId(),
                    command = command,
                    schwabClientCustomerId = activeCustomerId,
                    schwabClientCorrelId = activeCorrelId,
                    symbols = toAdd
                )
            )
            subscribedApiSymbols.addAll(toAdd)
        }

        if (toRemove.isNotEmpty()) {
            sendEnvelope(
                socket,
                StreamerRequestFactory.createEquitySubscription(
                    requestId = nextRequestId(),
                    command = StreamerCommand.UNSUBS,
                    schwabClientCustomerId = activeCustomerId,
                    schwabClientCorrelId = activeCorrelId,
                    symbols = toRemove
                )
            )
            subscribedApiSymbols.removeAll(toRemove.toSet())
            val unionDisplay = effectiveDesiredDisplaySymbolsLocked()
            var cacheChanged = false
            toRemove.forEach { apiSym ->
                val disp = SchwabSymbolNormalizer.toDisplaySymbol(apiSym)
                if (disp !in unionDisplay && quoteCache.remove(disp) != null) {
                    cacheChanged = true
                }
            }
            if (cacheChanged) {
                _liveQuotesSnapshot.value = quoteCache.toMap()
            }
        }
    }

    suspend fun disconnectGracefully(
        expectedGeneration: Long? = null,
        expectedOwnerEpoch: Long? = null
    ) = connectionMutex.withLock {
        disconnectInternal(expectedGeneration, expectedOwnerEpoch)
    }

    fun disconnect() {
        disconnectInternal(expectedGeneration = null, expectedOwnerEpoch = null)
    }

    private fun disconnectInternal(
        expectedGeneration: Long?,
        expectedOwnerEpoch: Long?
    ) {
        synchronized(stateLock) {
            if (expectedOwnerEpoch != null && ownerEpoch != expectedOwnerEpoch) {
                return
            }
            if (expectedGeneration != null && connectionGeneration != expectedGeneration) {
                return
            }
            connectionGeneration++
            intentionalDisconnect = true
            reconnectJob?.cancel()
            reconnectJob = null
            closeActiveSocketInternal(sendLogout = true)
            desiredSentinelSymbols.clear()
            desiredChartOverlayDisplaySymbol = null
            desiredChartOverlayApiSymbol = null
            subscribedApiSymbols.clear()
            clearQuoteCacheInternal()
            _connectionState.value = StreamerConnectionState.DISCONNECTED
        }
    }

    fun getCachedQuote(symbol: String): LiveEquityQuote? =
        quoteCache[SchwabSymbolNormalizer.toDisplaySymbol(symbol)]

    /**
     * Pure-JVM incoming WebSocket JSON frame processor handling:
     * - `notify` heartbeats & Error 12 `CLOSE_CONNECTION`
     * - `response` ADMIN/LOGIN authentication confirmation
     * - `data` LEVELONE_EQUITIES partial-update Delta Merge (`BRK/B` -> `BRK.B`)
     * - `data` ACCT_ACTIVITY order fills & execution lifecycle events
     */
    fun processIncomingMessage(rawJson: String, nowEpochMs: Long = nowProvider()) {
        processIncomingMessageInternal(rawJson, nowEpochMs, sourceSocket = null, sourceGeneration = null)
    }

    private fun processIncomingMessageInternal(
        rawJson: String,
        nowEpochMs: Long,
        sourceSocket: WebSocket?,
        sourceGeneration: Long?
    ) {
        val root = runCatching { json.parseToJsonElement(rawJson) as? JsonObject }.getOrNull() ?: return

        // 1. Heartbeats & System Notifications
        (root["notify"] as? JsonArray)?.forEach { elem ->
            val obj = elem as? JsonObject ?: return@forEach
            if (obj.containsKey("heartbeat")) {
                _lastHeartbeatEpochMs.value = nowEpochMs
            }
            val code = (obj["code"] as? JsonPrimitive)?.intOrNull
                ?: ((obj["content"] as? JsonObject)?.get("code") as? JsonPrimitive)?.intOrNull
            if (code == StreamerErrorCode.CLOSE_CONNECTION) {
                handleError12CloseConnection(sourceSocket, sourceGeneration)
                return
            }
        }

        // 2. Command Responses (ADMIN / LOGIN)
        (root["response"] as? JsonArray)?.forEach { elem ->
            val obj = elem as? JsonObject ?: return@forEach
            val service = (obj["service"] as? JsonPrimitive)?.content.orEmpty()
            val command = (obj["command"] as? JsonPrimitive)?.content.orEmpty()
            val content = obj["content"] as? JsonObject
            val code = (content?.get("code") as? JsonPrimitive)?.intOrNull ?: -1
            val msg = (content?.get("msg") as? JsonPrimitive)?.content.orEmpty()

            if (code == StreamerErrorCode.CLOSE_CONNECTION) {
                handleError12CloseConnection(sourceSocket, sourceGeneration)
                return
            }

            if (service == StreamerService.ADMIN && command == StreamerCommand.LOGIN) {
                if (code == 0) {
                    val accepted = synchronized(stateLock) {
                        val genValid = sourceGeneration == null || connectionGeneration == sourceGeneration
                        val socketValid = sourceSocket == null ||
                            (sourceSocket === activeSocket && !intentionalDisconnect && !error12Halted)
                        if (!genValid || !socketValid || intentionalDisconnect || error12Halted) {
                            false
                        } else {
                            reconnectAttempt = 0
                            _lastHeartbeatEpochMs.value = nowEpochMs
                            _lastErrorMessage.value = null
                            // H-3: Mutate connectionState to AUTHENTICATED first and flush desired subscriptions atomically under stateLock
                            _connectionState.value = StreamerConnectionState.AUTHENTICATED
                            flushPostLoginSubscriptionsLocked(sourceSocket)
                            true
                        }
                    }
                    if (!accepted) return@forEach
                } else {
                    var reconnectScope: CoroutineScope? = null
                    var reconnectGen = 0L
                    val canRetry = synchronized(stateLock) {
                        if (sourceGeneration != null && connectionGeneration != sourceGeneration) {
                            return@synchronized false
                        }
                        if (sourceSocket != null && (sourceSocket !== activeSocket || intentionalDisconnect || error12Halted)) {
                            return@synchronized false
                        }
                        if (intentionalDisconnect || error12Halted) {
                            return@synchronized false
                        }
                        closeActiveSocketInternal(sendLogout = false)
                        subscribedApiSymbols.clear()
                        clearQuoteCacheInternal()
                        _lastErrorMessage.value = "Streamer LOGIN failed (code=$code): $msg"
                        val sc = managedScope
                        val retry = sc != null
                        if (retry) {
                            reconnectScope = sc
                            reconnectGen = sourceGeneration ?: connectionGeneration
                            _connectionState.value = StreamerConnectionState.RECONNECTING
                        } else {
                            _connectionState.value = StreamerConnectionState.ERROR
                        }
                        retry
                    }
                    if (canRetry) {
                        reconnectScope?.let { scheduleReconnect(it, reconnectGen) }
                    }
                }
            }
        }

        // 3. Streaming Market Data & Account Activity
        (root["data"] as? JsonArray)?.forEach { elem ->
            val dataObj = elem as? JsonObject ?: return@forEach
            val service = (dataObj["service"] as? JsonPrimitive)?.content.orEmpty()
            val contentArray = dataObj["content"] as? JsonArray ?: return@forEach

            when (service) {
                StreamerService.LEVELONE_EQUITIES -> {
                    _lastHeartbeatEpochMs.value = nowEpochMs
                    contentArray.forEach { entry ->
                        val entryObj = entry as? JsonObject ?: return@forEach
                        parseAndMergeEquityContent(entryObj, nowEpochMs, sourceSocket, sourceGeneration)
                    }
                }
                StreamerService.ACCT_ACTIVITY -> {
                    _lastHeartbeatEpochMs.value = nowEpochMs
                    contentArray.forEach { entry ->
                        val entryObj = entry as? JsonObject ?: return@forEach
                        parseAndEmitAccountActivity(entryObj)
                    }
                }
            }
        }
    }

    private fun parseAndMergeEquityContent(
        content: JsonObject,
        nowEpochMs: Long,
        sourceSocket: WebSocket?,
        sourceGeneration: Long?
    ) {
        val rawKey = (content["key"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: (content[LevelOneEquityFields.SYMBOL.toString()] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: return
        val displaySym = SchwabSymbolNormalizer.toDisplaySymbol(rawKey)

        // M-2 Safeguard: Split positive Bid/Ask freshness post-filter so Ask-only ticks never advance LONG stops
        val validIncomingBid = content.optPositiveDouble(LevelOneEquityFields.BID_PRICE)
        val validIncomingAsk = content.optPositiveDouble(LevelOneEquityFields.ASK_PRICE)

        val incoming = LiveEquityQuote(
            symbol = displaySym,
            bidPrice = validIncomingBid,
            askPrice = validIncomingAsk,
            lastPrice = content.optPositiveDouble(LevelOneEquityFields.LAST_PRICE),
            bidSize = content.optLong(LevelOneEquityFields.BID_SIZE),
            askSize = content.optLong(LevelOneEquityFields.ASK_SIZE),
            totalVolume = content.optLong(LevelOneEquityFields.TOTAL_VOLUME),
            lastSize = content.optLong(LevelOneEquityFields.LAST_SIZE),
            securityStatus = content.optString(LevelOneEquityFields.SECURITY_STATUS),
            markPrice = content.optPositiveDouble(LevelOneEquityFields.MARK_PRICE),
            quoteTimeMs = content.optLong(LevelOneEquityFields.QUOTE_TIME),
            tradeTimeMs = content.optLong(LevelOneEquityFields.TRADE_TIME),
            receivedAtEpochMs = nowEpochMs,
            hasFreshBidUpdate = validIncomingBid != null,
            hasFreshAskUpdate = validIncomingAsk != null
        )

        // H-3 Safeguard: Validate active socket/subscription & mutate cache atomically under stateLock
        val merged: LiveEquityQuote = synchronized(stateLock) {
            if (intentionalDisconnect || error12Halted) return
            if (sourceGeneration != null && connectionGeneration != sourceGeneration) return
            if (sourceSocket != null && sourceSocket !== activeSocket) return
            val unionDisplay = effectiveDesiredDisplaySymbolsLocked()
            val apiSym = SchwabSymbolNormalizer.toSchwabMarketDataSymbol(displaySym)
            val subscriptionActive = displaySym in unionDisplay || apiSym in subscribedApiSymbols
            if ((sourceSocket != null || hasExplicitSubscriptions) && !subscriptionActive) {
                return
            }

            val updated = quoteCache.compute(displaySym) { _, prev ->
                mergeQuoteSnapshot(displaySym, prev, incoming)
            }!!
            _liveQuotesSnapshot.value = quoteCache.toMap()
            updated
        }

        _equityQuotes.tryEmit(merged)
    }

    private fun mergeQuoteSnapshot(
        symbol: String,
        prev: LiveEquityQuote?,
        incoming: LiveEquityQuote
    ): LiveEquityQuote {
        val validBid = incoming.bidPrice?.takeIf { it > 0.0 }
        val validAsk = incoming.askPrice?.takeIf { it > 0.0 }
        val validLast = incoming.lastPrice?.takeIf { it > 0.0 }
        val validMark = incoming.markPrice?.takeIf { it > 0.0 }

        if (prev == null) {
            return incoming.copy(
                symbol = symbol,
                bidPrice = validBid,
                askPrice = validAsk,
                lastPrice = validLast,
                markPrice = validMark,
                hasFreshBidUpdate = incoming.hasFreshBidUpdate,
                hasFreshAskUpdate = incoming.hasFreshAskUpdate
            )
        }
        return LiveEquityQuote(
            symbol = symbol,
            bidPrice = validBid ?: prev.bidPrice,
            askPrice = validAsk ?: prev.askPrice,
            lastPrice = validLast ?: prev.lastPrice,
            bidSize = incoming.bidSize ?: prev.bidSize,
            askSize = incoming.askSize ?: prev.askSize,
            totalVolume = incoming.totalVolume ?: prev.totalVolume,
            lastSize = incoming.lastSize ?: prev.lastSize,
            securityStatus = incoming.securityStatus ?: prev.securityStatus,
            markPrice = validMark ?: prev.markPrice,
            quoteTimeMs = incoming.quoteTimeMs ?: prev.quoteTimeMs,
            tradeTimeMs = incoming.tradeTimeMs ?: prev.tradeTimeMs,
            receivedAtEpochMs = incoming.receivedAtEpochMs,
            hasFreshBidUpdate = incoming.hasFreshBidUpdate,
            hasFreshAskUpdate = incoming.hasFreshAskUpdate
        )
    }

    private fun parseAndEmitAccountActivity(content: JsonObject) {
        val subKey = content.optString(AccountActivityFields.SUBSCRIPTION_KEY)
            ?: (content["key"] as? JsonPrimitive)?.content
        val acctNum = content.optString(AccountActivityFields.ACCOUNT_NUMBER)
        val msgType = content.optString(AccountActivityFields.MESSAGE_TYPE)
        val msgData = content.optString(AccountActivityFields.MESSAGE_DATA)
        val orderId = extractOrderIdFromActivityData(msgData)

        _accountEvents.tryEmit(
            AccountActivityEvent(
                subscriptionKey = subKey,
                accountNumber = acctNum,
                messageType = msgType,
                messageDataJson = msgData,
                orderId = orderId
            )
        )
    }

    private fun extractOrderIdFromActivityData(dataJson: String?): String? {
        if (dataJson.isNullOrBlank()) return null
        val parsed = runCatching { json.parseToJsonElement(dataJson) as? JsonObject }.getOrNull()
        if (parsed != null) {
            val direct = listOf("SchwabOrderIDNumber", "orderId", "OrderId", "order_id")
                .firstNotNullOfOrNull { key ->
                    (parsed[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                }
            if (direct != null) return direct
        }
        return ORDER_ID_REGEX.find(dataJson)?.groupValues?.getOrNull(1)
    }

    private fun flushPostLoginSubscriptionsLocked(sourceSocket: WebSocket?) {
        val socket = activeSocket ?: return
        if (sourceSocket != null && sourceSocket !== socket) return
        // Subscribe ACCT_ACTIVITY for live OrderFill events
        sendEnvelope(
            socket,
            StreamerRequestFactory.createAccountActivitySubscription(
                requestId = nextRequestId(),
                schwabClientCustomerId = activeCustomerId,
                schwabClientCorrelId = activeCorrelId
            )
        )
        // Subscribe desired equities (Sentinel + optional Chart overlay, normalized BRK.B -> BRK/B)
        val apiSyms = effectiveDesiredApiSymbolsLocked().toList()
        subscribedApiSymbols.clear()
        if (apiSyms.isNotEmpty()) {
            sendEnvelope(
                socket,
                StreamerRequestFactory.createEquitySubscription(
                    requestId = nextRequestId(),
                    command = StreamerCommand.SUBS,
                    schwabClientCustomerId = activeCustomerId,
                    schwabClientCorrelId = activeCorrelId,
                    symbols = apiSyms
                )
            )
            subscribedApiSymbols.addAll(apiSyms)
        }
    }

    private fun handleError12CloseConnection(
        sourceSocket: WebSocket? = null,
        sourceGeneration: Long? = null
    ) {
        synchronized(stateLock) {
            if (sourceGeneration != null && connectionGeneration != sourceGeneration) {
                return
            }
            if (sourceSocket != null && (sourceSocket !== activeSocket || intentionalDisconnect)) {
                return
            }
            connectionGeneration++
            error12Halted = true
            reconnectJob?.cancel()
            reconnectJob = null
            closeActiveSocketInternal(sendLogout = false)
            desiredSentinelSymbols.clear()
            desiredChartOverlayDisplaySymbol = null
            desiredChartOverlayApiSymbol = null
            subscribedApiSymbols.clear()
            clearQuoteCacheInternal()
            _lastErrorMessage.value =
                "Schwab Error 12 (CLOSE_CONNECTION): Another Streamer WebSocket is active. Auto-reconnect halted."
            _connectionState.value = StreamerConnectionState.ERROR_CONFLICT_CLOSE_12
        }
    }

    internal fun scheduleReconnect(scope: CoroutineScope, expectedGeneration: Long) {
        var launchedJob: Job? = null
        synchronized(stateLock) {
            if (intentionalDisconnect ||
                error12Halted ||
                connectionGeneration != expectedGeneration ||
                activeSocket != null ||
                _connectionState.value == StreamerConnectionState.AUTHENTICATED ||
                _connectionState.value == StreamerConnectionState.CONNECTING
            ) {
                return
            }
            val existing = reconnectJob
            if (existing != null && !existing.isCompleted) return
            val delays = if (backoffDelaysMs.isNotEmpty()) backoffDelaysMs else DEFAULT_BACKOFF_DELAYS_MS
            val d = delays[reconnectAttempt.coerceAtMost(delays.lastIndex)]
            reconnectAttempt++
            val targetSymbols = desiredSentinelSymbols.toSet()

            // Launch lazily so reconnectJob & RECONNECTING state are registered atomically under stateLock BEFORE starting
            val job = scope.launch(start = CoroutineStart.LAZY) {
                delay(d)
                val stillAllowed = synchronized(stateLock) {
                    !intentionalDisconnect && !error12Halted && connectionGeneration == expectedGeneration
                }
                if (stillAllowed) {
                    val latestSymbols = synchronized(stateLock) {
                        desiredSentinelSymbols.toSet().ifEmpty { targetSymbols }
                    }
                    connectLiveInternal(
                        scope = scope,
                        initialSymbols = latestSymbols,
                        invokedFromReconnect = true,
                        expectedGeneration = expectedGeneration
                    )
                }
            }
            reconnectJob = job
            launchedJob = job
            _connectionState.value = StreamerConnectionState.RECONNECTING
        }
        launchedJob?.start()
    }

    private fun clearQuoteCacheInternal() {
        quoteCache.clear()
        _liveQuotesSnapshot.value = emptyMap()
    }

    private fun closeActiveSocketInternal(sendLogout: Boolean) {
        val sock = activeSocket ?: return
        activeSocket = null
        if (sendLogout && activeCustomerId.isNotBlank()) {
            runCatching {
                sendEnvelope(
                    sock,
                    StreamerRequestFactory.createLogout(
                        requestId = nextRequestId(),
                        schwabClientCustomerId = activeCustomerId,
                        schwabClientCorrelId = activeCorrelId
                    )
                )
            }
        }
        runCatching { sock.close(1000, "Client shutdown") }
    }

    private fun sendEnvelope(socket: WebSocket, envelope: StreamerEnvelopeRequest) {
        val raw = json.encodeToString(StreamerEnvelopeRequest.serializer(), envelope)
        socket.send(raw)
    }

    private fun nextRequestId(): String = requestCounter.getAndIncrement().toString()

    private fun JsonObject.optPositiveDouble(fieldIndex: Int): Double? =
        (this[fieldIndex.toString()] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it > 0.0 }

    private fun JsonObject.optLong(fieldIndex: Int): Long? =
        (this[fieldIndex.toString()] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.optString(fieldIndex: Int): String? =
        (this[fieldIndex.toString()] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    companion object {
        private const val DEFAULT_STREAMER_URL = "wss://streamer-api.schwab.com/ws"
        private val DEFAULT_BACKOFF_DELAYS_MS = longArrayOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L)
        private val ORDER_ID_REGEX = Regex("""(?:OrderId|orderId|SchwabOrderIDNumber)"?\s*[:=]\s*"?(\d+)""")
    }
}
