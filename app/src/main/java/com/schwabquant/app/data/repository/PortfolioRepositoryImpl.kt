package com.schwabquant.app.data.repository

import com.schwabquant.app.core.quant.BetaWeightingCalculator
import com.schwabquant.app.core.quant.BlackScholesEngine
import com.schwabquant.app.core.quant.OccSymbolFormatter
import com.schwabquant.app.core.security.CredentialVault
import com.schwabquant.app.data.local.dao.ShadowLedgerDao
import com.schwabquant.app.data.local.dao.WashSaleDao
import com.schwabquant.app.data.local.entity.ShadowPositionEntity
import com.schwabquant.app.data.local.entity.WashSaleTradeEntity
import com.schwabquant.app.data.local.prefs.AppPreferencesDataStore
import com.schwabquant.app.data.remote.api.SchwabTraderApi
import com.schwabquant.app.data.remote.dto.FundamentalDto
import com.schwabquant.app.data.remote.dto.PositionDto
import com.schwabquant.app.data.remote.dto.QuoteDetailDto
import com.schwabquant.app.data.remote.dto.QuoteEntryDto
import com.schwabquant.app.data.remote.dto.SchwabQuoteNormalizer
import com.schwabquant.app.data.remote.dto.SchwabSymbolNormalizer
import com.schwabquant.app.data.remote.dto.SecuritiesAccountDto
import com.schwabquant.app.domain.model.AccountSummary
import com.schwabquant.app.domain.model.AggregatedGreeks
import com.schwabquant.app.domain.model.AssetCategory
import com.schwabquant.app.domain.model.ExecutionMode
import com.schwabquant.app.domain.model.PortfolioAnomaly
import com.schwabquant.app.domain.model.PortfolioAnomalyType
import com.schwabquant.app.domain.model.PortfolioDataSource
import com.schwabquant.app.domain.model.PortfolioSnapshot
import com.schwabquant.app.domain.model.Position
import com.schwabquant.app.domain.model.UnderlyingPositionGroup
import com.schwabquant.app.domain.repository.MarketDataRepository
import com.schwabquant.app.domain.repository.PortfolioRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max

@Singleton
class PortfolioRepositoryImpl(
    private val traderApi: SchwabTraderApi,
    private val marketDataRepository: MarketDataRepository,
    private val shadowLedgerDao: ShadowLedgerDao,
    private val washSaleDao: WashSaleDao,
    private val credentialVault: CredentialVault,
    private val preferences: AppPreferencesDataStore,
    private val blackScholesEngine: BlackScholesEngine,
    private val betaWeightingCalculator: BetaWeightingCalculator,
    private val occSymbolFormatter: OccSymbolFormatter,
    private val nowProvider: () -> Long = System::currentTimeMillis
) : PortfolioRepository {

    @Inject
    constructor(
        traderApi: SchwabTraderApi,
        marketDataRepository: MarketDataRepository,
        shadowLedgerDao: ShadowLedgerDao,
        washSaleDao: WashSaleDao,
        credentialVault: CredentialVault,
        preferences: AppPreferencesDataStore,
        blackScholesEngine: BlackScholesEngine,
        betaWeightingCalculator: BetaWeightingCalculator,
        occSymbolFormatter: OccSymbolFormatter
    ) : this(
        traderApi = traderApi,
        marketDataRepository = marketDataRepository,
        shadowLedgerDao = shadowLedgerDao,
        washSaleDao = washSaleDao,
        credentialVault = credentialVault,
        preferences = preferences,
        blackScholesEngine = blackScholesEngine,
        betaWeightingCalculator = betaWeightingCalculator,
        occSymbolFormatter = occSymbolFormatter,
        nowProvider = System::currentTimeMillis
    )

    private val liveSnapshotState = MutableStateFlow<PortfolioSnapshot?>(null)
    private val shadowLiveQuotesState = MutableStateFlow<Map<String, QuoteEntryDto>>(emptyMap())

    override fun observePortfolioSnapshot(source: PortfolioDataSource): Flow<PortfolioSnapshot?> =
        when (source) {
            PortfolioDataSource.LIVE_SCHWAB_READ_ONLY -> liveSnapshotState
            PortfolioDataSource.SHADOW_PAPER_LEDGER -> combine(
                shadowLedgerDao.observePositions(),
                preferences.shadowCashBalance,
                shadowLiveQuotesState
            ) { entities, cashBalance, liveQuotes ->
                buildShadowSnapshot(
                    entities = entities,
                    cashBalance = cashBalance,
                    quotes = liveQuotes
                )
            }
        }

    override fun observeAccountSummary(mode: ExecutionMode): Flow<AccountSummary?> =
        when (mode) {
            ExecutionMode.SHADOW -> observePortfolioSnapshot(PortfolioDataSource.SHADOW_PAPER_LEDGER)
                .map { it?.accountSummary }
            ExecutionMode.LIVE -> liveSnapshotState.map { it?.accountSummary }
        }

    override fun observePositions(mode: ExecutionMode): Flow<List<Position>> =
        when (mode) {
            ExecutionMode.SHADOW -> observePortfolioSnapshot(PortfolioDataSource.SHADOW_PAPER_LEDGER)
                .map { it?.positions.orEmpty() }
            ExecutionMode.LIVE -> liveSnapshotState.map { it?.positions.orEmpty() }
        }

    override suspend fun fetchAccountHashes(): Result<List<String>> = runCatching {
        val accounts = traderApi.getAccountNumbers()
        accounts.firstOrNull()?.let { primary ->
            val masked = maskAccountNumber(primary.accountNumber)
            credentialVault.savePrimaryAccount(primary.hashValue, masked)
        }
        accounts.map { it.hashValue }
    }

    override suspend fun refreshLivePortfolio(accountHash: String?): Result<PortfolioSnapshot> =
        runCatching {
            val resolvedHash = accountHash
                ?: credentialVault.getPrimaryAccountHash()
                ?: fetchAccountHashes().getOrThrow().firstOrNull()
                ?: error("No Schwab brokerage account hash available.")

            val wrapper = traderApi.getAccount(resolvedHash, fields = "positions")
            val account = wrapper.securitiesAccount
                ?: error("Schwab returned empty securitiesAccount payload.")

            val symbols = collectSymbolsForLiveAccount(account)
            val quotes = marketDataRepository.getQuotes(symbols, forceRefresh = true)
                .getOrElse { emptyMap() }

            val snapshot = buildLiveSnapshot(resolvedHash, account, quotes)
            liveSnapshotState.value = snapshot
            snapshot
        }

    override suspend fun refreshLivePositionsOnly(accountHash: String?): Result<PortfolioSnapshot> =
        runCatching {
            val resolvedHash = accountHash
                ?: credentialVault.getPrimaryAccountHash()
                ?: fetchAccountHashes().getOrThrow().firstOrNull()
                ?: error("No Schwab brokerage account hash available.")

            val wrapper = traderApi.getAccount(resolvedHash, fields = "positions")
            val account = wrapper.securitiesAccount
                ?: error("Schwab returned empty securitiesAccount payload.")

            buildLiveSnapshot(resolvedHash, account, quotes = emptyMap())
        }

    override suspend fun refreshShadowPortfolio(): Result<PortfolioSnapshot> = runCatching {
        val entities = shadowLedgerDao.getPositionsSnapshot()
        val cashBalance = preferences.shadowCashBalance.first()

        val rawSymbols = buildList {
            add("SPY")
            entities.forEach { entity ->
                add(entity.symbol)
                add(entity.underlyingSymbol)
            }
        }.distinct()

        val liveQuotes = if (credentialVault.getAccessToken() != null && entities.isNotEmpty()) {
            marketDataRepository.getQuotes(rawSymbols, forceRefresh = true).getOrElse { emptyMap() }
        } else {
            emptyMap()
        }

        shadowLiveQuotesState.value = liveQuotes
        buildShadowSnapshot(
            entities = entities,
            cashBalance = cashBalance,
            quotes = liveQuotes
        )
    }

    override suspend fun seedDemoPortfolio(): Result<Unit> = runCatching {
        val now = nowProvider()
        val demoPositions = listOf(
            ShadowPositionEntity(
                symbol = "IWM   280121C00190000",
                assetType = AssetCategory.OPTION.name,
                underlyingSymbol = "IWM",
                quantity = "1",
                averageCost = "46.50",
                markPrice = "48.20",
                openedAtEpochMs = now - 30L * DAY_MS,
                updatedAtEpochMs = now
            ),
            ShadowPositionEntity(
                symbol = "IWM   261218C00235000",
                assetType = AssetCategory.OPTION.name,
                underlyingSymbol = "IWM",
                quantity = "-1",
                averageCost = "3.10",
                markPrice = "2.45",
                openedAtEpochMs = now - 10L * DAY_MS,
                updatedAtEpochMs = now
            ),
            ShadowPositionEntity(
                symbol = "XLF   280121C00040000",
                assetType = AssetCategory.OPTION.name,
                underlyingSymbol = "XLF",
                quantity = "2",
                averageCost = "11.80",
                markPrice = "12.40",
                openedAtEpochMs = now - 28L * DAY_MS,
                updatedAtEpochMs = now
            ),
            ShadowPositionEntity(
                symbol = "XLF   261218C00049000",
                assetType = AssetCategory.OPTION.name,
                underlyingSymbol = "XLF",
                quantity = "-2",
                averageCost = "1.15",
                markPrice = "0.82",
                openedAtEpochMs = now - 8L * DAY_MS,
                updatedAtEpochMs = now
            ),
            ShadowPositionEntity(
                symbol = "NVDA  280121C00165000",
                assetType = AssetCategory.OPTION.name,
                underlyingSymbol = "NVDA",
                quantity = "1",
                averageCost = "45.00",
                markPrice = "42.50",
                openedAtEpochMs = now - 2L * DAY_MS,
                updatedAtEpochMs = now
            ),
            ShadowPositionEntity(
                symbol = "GOOG",
                assetType = AssetCategory.EQUITY.name,
                underlyingSymbol = "GOOG",
                quantity = "16",
                averageCost = "258.50",
                markPrice = "270.00",
                openedAtEpochMs = now - 45L * DAY_MS,
                updatedAtEpochMs = now
            )
        )

        shadowLedgerDao.clearPositions()
        shadowLedgerDao.upsertPositions(demoPositions)
        preferences.setShadowCashBalance(DEMO_CASH_BALANCE)

        // Seed realistic NVDA stock-to-LEAPS 61-day IRC 1091 wash-sale demo scenario
        washSaleDao.deleteBySource("DEMO")
        washSaleDao.insertAll(
            listOf(
                WashSaleTradeEntity(
                    underlyingSymbol = "NVDA",
                    symbol = "NVDA",
                    assetType = AssetCategory.EQUITY.name,
                    instruction = "SELL",
                    quantity = 22,
                    price = 136.50,
                    realizedPnl = -318.44,
                    tradeTimestampEpochMs = now - 7L * DAY_MS,
                    source = "DEMO",
                    externalOrderId = "DEMO-NVDA-LOSS"
                ),
                WashSaleTradeEntity(
                    underlyingSymbol = "NVDA",
                    symbol = "NVDA  280121C00165000",
                    assetType = AssetCategory.OPTION.name,
                    instruction = "BUY_TO_OPEN",
                    quantity = 1,
                    price = 45.00,
                    realizedPnl = null,
                    tradeTimestampEpochMs = now - 2L * DAY_MS,
                    source = "DEMO",
                    externalOrderId = "DEMO-NVDA-LEAPS"
                )
            )
        )
    }

    private fun collectSymbolsForLiveAccount(account: SecuritiesAccountDto): List<String> =
        buildList {
            add("SPY")
            account.positions.forEach { dto ->
                val inst = dto.instrument ?: return@forEach
                inst.symbol?.takeIf { it.isNotBlank() }?.let { add(it) }
                inst.underlyingSymbol?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }.distinct()

    private fun buildLiveSnapshot(
        accountHash: String,
        account: SecuritiesAccountDto,
        quotes: Map<String, QuoteEntryDto>
    ): PortfolioSnapshot {
        val nowMs = nowProvider()
        val today = Instant.ofEpochMilli(nowMs).atZone(NY_ZONE).toLocalDate()
        val spySpot = resolveSpot(quotes["SPY"], fallback = DEFAULT_SPY_SPOT)

        val positions = account.positions.mapNotNull { dto ->
            mapLivePositionDto(dto, quotes, today)
        }

        val balances = account.currentBalances
        val totalPositionsValue = positions.fold(BigDecimal.ZERO) { acc, p -> acc + p.marketValue }
        val cashBalance = BigDecimal.valueOf(
            balances?.cashBalance ?: balances?.cashAvailableForTrading ?: 0.0
        )
        val settledCash = BigDecimal.valueOf(
            balances?.settledFunds ?: balances?.cashBalance ?: 0.0
        )
        val netLiq = BigDecimal.valueOf(
            balances?.liquidationValue ?: (totalPositionsValue + cashBalance).toDouble()
        )
        val buyingPower = BigDecimal.valueOf(
            balances?.buyingPower ?: balances?.availableFunds ?: 0.0
        )
        val optionBp = BigDecimal.valueOf(
            balances?.availableFunds ?: balances?.buyingPowerNonMarginableTrade ?: 0.0
        )

        val totalOpenPnl = positions.fold(BigDecimal.ZERO) { acc, p -> acc + p.unrealizedPnl }
        val totalDayPnl = positions.fold(BigDecimal.ZERO) { acc, p -> acc + p.dayPnl }
        val totalCostBasis = positions.fold(BigDecimal.ZERO) { acc, p ->
            val mult = if (p.category == AssetCategory.OPTION) OPTION_MULTIPLIER_BD else BigDecimal.ONE
            acc + p.averageCost.multiply(p.quantity.abs()).multiply(mult)
        }
        val openPnlPct = if (totalCostBasis > BigDecimal.ZERO) {
            (totalOpenPnl.toDouble() / totalCostBasis.toDouble()) * 100.0
        } else {
            0.0
        }
        val priorNetLiq = netLiq.toDouble() - totalDayPnl.toDouble()
        val dayPnlPct = if (priorNetLiq > 0.0) (totalDayPnl.toDouble() / priorNetLiq) * 100.0 else 0.0
        val cashBufferPct = if (netLiq > BigDecimal.ZERO) {
            (cashBalance.toDouble() / netLiq.toDouble()) * 100.0
        } else {
            0.0
        }

        return assembleSnapshot(
            dataSource = PortfolioDataSource.LIVE_SCHWAB_READ_ONLY,
            accountHash = accountHash,
            maskedAccount = account.accountNumber?.let(::maskAccountNumber) ?: "LIVE",
            executionMode = ExecutionMode.LIVE,
            netLiq = netLiq,
            cashBalance = cashBalance,
            settledCash = settledCash,
            cashBufferPct = cashBufferPct,
            buyingPower = buyingPower,
            optionBuyingPower = optionBp,
            openPnl = totalOpenPnl,
            openPnlPct = openPnlPct,
            dayPnl = totalDayPnl,
            dayPnlPct = dayPnlPct,
            positions = positions,
            spySpot = spySpot,
            nowMs = nowMs,
            isDemoSeeded = false
        )
    }

    private fun mapLivePositionDto(
        dto: PositionDto,
        quotes: Map<String, QuoteEntryDto>,
        today: LocalDate
    ): Position? {
        val instrument = dto.instrument ?: return null
        val rawSymbol = instrument.symbol?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val netQty = (dto.longQuantity ?: 0.0) - (dto.shortQuantity ?: 0.0)
        if (abs(netQty) < 1e-9) return null

        val isOption = instrument.assetType.equals("OPTION", ignoreCase = true)
        val multiplier = if (isOption) OPTION_MULTIPLIER else 1.0
        val parsedOcc = if (isOption) occSymbolFormatter.parse(rawSymbol) else null

        val rawUnderlying = instrument.underlyingSymbol
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: parsedOcc?.root
            ?: rawSymbol
        val displaySymbol = if (isOption) rawSymbol else SchwabSymbolNormalizer.toDisplaySymbol(rawSymbol)
        val displayUnderlying = SchwabSymbolNormalizer.toDisplaySymbol(rawUnderlying)

        val symbolQuote = quotes[rawSymbol] ?: quotes[displaySymbol]
        val underlyingQuote = quotes[rawUnderlying]
            ?: quotes[displayUnderlying]
            ?: if (!isOption) symbolQuote else null

        val underlyingSpot = resolveSpot(underlyingQuote, fallback = symbolQuote?.quote?.underlyingPrice ?: 0.0)
        val tickerBeta = underlyingQuote?.fundamental?.beta ?: 1.0
        val divYield = SchwabQuoteNormalizer.percentToDecimal(
            underlyingQuote?.fundamental?.divYield ?: underlyingQuote?.fundamental?.dividendYield
        ) ?: 0.0
        val divAmount = underlyingQuote?.fundamental?.divAmount ?: underlyingQuote?.fundamental?.divPayAmount
        val divDate = underlyingQuote?.fundamental?.divDate ?: underlyingQuote?.fundamental?.divPayDate

        val avgPrice = dto.averagePrice
            ?: (if (netQty >= 0.0) dto.averageLongPrice else dto.averageShortPrice)
            ?: 0.0

        val normalizedPerShareMark = resolvePerShareMark(
            quote = symbolQuote?.quote,
            dtoMarketValue = dto.marketValue,
            netQty = netQty,
            multiplier = multiplier,
            fallback = avgPrice
        )

        val marketValue = dto.marketValue ?: (netQty * normalizedPerShareMark * multiplier)
        val openPnl = when {
            dto.longOpenProfitLoss != null || dto.shortOpenProfitLoss != null ->
                (dto.longOpenProfitLoss ?: 0.0) + (dto.shortOpenProfitLoss ?: 0.0)
            else -> (normalizedPerShareMark - avgPrice) * netQty * multiplier
        }
        val dayPnl = dto.currentDayProfitLoss
            ?: ((symbolQuote?.quote?.netChange ?: instrument.netChange ?: 0.0) * netQty * multiplier)

        val costBasis = abs(avgPrice * netQty * multiplier)
        val openPnlPct = if (costBasis > 0.0) (openPnl / costBasis) * 100.0 else 0.0

        if (!isOption) {
            return Position(
                symbol = displaySymbol,
                underlyingSymbol = displayUnderlying,
                category = AssetCategory.EQUITY,
                quantity = BigDecimal.valueOf(netQty),
                averageCost = BigDecimal.valueOf(avgPrice),
                markPrice = BigDecimal.valueOf(normalizedPerShareMark),
                marketValue = BigDecimal.valueOf(marketValue),
                unrealizedPnl = BigDecimal.valueOf(openPnl),
                unrealizedPnlPercent = openPnlPct,
                dayPnl = BigDecimal.valueOf(dayPnl),
                delta = 1.0,
                gamma = 0.0,
                theta = 0.0,
                vega = 0.0,
                betaToSpy = tickerBeta,
                underlyingSpot = normalizedPerShareMark.takeIf { it > 0.0 } ?: underlyingSpot,
                divDate = divDate,
                divAmount = divAmount,
                divYield = divYield,
                isShadowPosition = false
            )
        }

        val isCall = parsedOcc?.isCall
            ?: symbolQuote?.quote?.contractType?.equals("CALL", ignoreCase = true)
            ?: instrument.putCall?.equals("CALL", ignoreCase = true)
            ?: true
        val strike = parsedOcc?.strike?.toDouble()
            ?: symbolQuote?.quote?.strikePrice
            ?: symbolQuote?.reference?.strikePrice
            ?: 0.0
        val expirationStr = parsedOcc?.expiration?.toString()
        val dte = parsedOcc?.let {
            max(0, ChronoUnit.DAYS.between(today, it.expiration).toInt())
        } ?: symbolQuote?.quote?.daysToExpiration?.toInt() ?: 30

        val q = symbolQuote?.quote
        val rawIv = SchwabQuoteNormalizer.percentToDecimal(q?.volatility)
        val resolvedSpot = underlyingSpot.takeIf { it > 0.0 } ?: q?.underlyingPrice ?: 0.0

        val liveDelta = q?.delta
        val liveGamma = q?.gamma
        val liveTheta = q?.theta
        val liveVega = q?.vega
        val missingGreeks = liveDelta == null ||
            (liveDelta == 0.0 && (liveGamma ?: 0.0) == 0.0 && (liveTheta ?: 0.0) == 0.0 && (liveVega ?: 0.0) == 0.0)

        var usedFallback = false
        var effectiveDelta = liveDelta
        var effectiveGamma = liveGamma
        var effectiveTheta = liveTheta
        var effectiveVega = liveVega
        var effectiveIv = rawIv

        if (missingGreeks && resolvedSpot > 0.0 && strike > 0.0) {
            val timeYears = max(0.0, dte.toDouble()) / 365.0
            val solvedIv = if (effectiveIv == null || effectiveIv <= 0.0) {
                blackScholesEngine.impliedVolatility(
                    isCall = isCall,
                    marketPrice = normalizedPerShareMark,
                    spot = resolvedSpot,
                    strike = strike,
                    timeYears = timeYears,
                    divYield = divYield
                )
            } else {
                effectiveIv
            }
            val ivForPricing = (solvedIv ?: 0.35).coerceIn(0.05, 5.0)
            val bs = blackScholesEngine.greeks(
                isCall = isCall,
                spot = resolvedSpot,
                strike = strike,
                timeYears = timeYears,
                divYield = divYield,
                iv = ivForPricing
            )
            effectiveDelta = bs.delta
            effectiveGamma = bs.gamma
            effectiveTheta = bs.thetaPerDay
            effectiveVega = bs.vegaPer1PctIv
            effectiveIv = ivForPricing
            usedFallback = true
        }

        val intrinsic = if (resolvedSpot > 0.0 && strike > 0.0) {
            if (isCall) max(0.0, resolvedSpot - strike) else max(0.0, strike - resolvedSpot)
        } else {
            0.0
        }
        val extrinsic = max(0.0, normalizedPerShareMark - intrinsic)

        return Position(
            symbol = rawSymbol,
            underlyingSymbol = displayUnderlying,
            category = AssetCategory.OPTION,
            quantity = BigDecimal.valueOf(netQty),
            averageCost = BigDecimal.valueOf(avgPrice),
            markPrice = BigDecimal.valueOf(normalizedPerShareMark),
            marketValue = BigDecimal.valueOf(marketValue),
            unrealizedPnl = BigDecimal.valueOf(openPnl),
            unrealizedPnlPercent = openPnlPct,
            dayPnl = BigDecimal.valueOf(dayPnl),
            delta = effectiveDelta,
            gamma = effectiveGamma,
            theta = effectiveTheta,
            vega = effectiveVega,
            betaToSpy = tickerBeta,
            isCall = isCall,
            strike = strike,
            expirationDate = expirationStr,
            daysToExpiration = dte,
            underlyingSpot = resolvedSpot,
            impliedVolatility = effectiveIv,
            extrinsicValue = extrinsic,
            divDate = divDate,
            divAmount = divAmount,
            divYield = divYield,
            greeksFromBlackScholesFallback = usedFallback,
            isShadowPosition = false
        )
    }

    private fun buildShadowSnapshot(
        entities: List<ShadowPositionEntity>,
        cashBalance: Double,
        quotes: Map<String, QuoteEntryDto>
    ): PortfolioSnapshot {
        val nowMs = nowProvider()
        val today = Instant.ofEpochMilli(nowMs).atZone(NY_ZONE).toLocalDate()
        val demoQuotes = buildOfflineDemoQuotes(today)
        val mergedQuotes = demoQuotes + quotes
        val spySpot = resolveSpot(mergedQuotes["SPY"], fallback = DEFAULT_SPY_SPOT)

        val positions = entities.map { entity ->
            mapShadowPositionEntity(entity, mergedQuotes, today)
        }

        val positionsValue = positions.fold(BigDecimal.ZERO) { acc, p -> acc + p.marketValue }
        val cashBd = BigDecimal.valueOf(cashBalance).setScale(2, RoundingMode.HALF_UP)
        val netLiq = (positionsValue + cashBd).setScale(2, RoundingMode.HALF_UP)
        val totalOpenPnl = positions.fold(BigDecimal.ZERO) { acc, p -> acc + p.unrealizedPnl }
        val totalDayPnl = positions.fold(BigDecimal.ZERO) { acc, p -> acc + p.dayPnl }
        val totalCostBasis = positions.fold(BigDecimal.ZERO) { acc, p ->
            val mult = if (p.category == AssetCategory.OPTION) OPTION_MULTIPLIER_BD else BigDecimal.ONE
            acc + p.averageCost.multiply(p.quantity.abs()).multiply(mult)
        }
        val openPnlPct = if (totalCostBasis > BigDecimal.ZERO) {
            (totalOpenPnl.toDouble() / totalCostBasis.toDouble()) * 100.0
        } else {
            0.0
        }
        val cashBufferPct = if (netLiq > BigDecimal.ZERO) {
            (cashBd.toDouble() / netLiq.toDouble()) * 100.0
        } else {
            0.0
        }

        return assembleSnapshot(
            dataSource = PortfolioDataSource.SHADOW_PAPER_LEDGER,
            accountHash = "SHADOW_PAPER",
            maskedAccount = "PAPER-SIM",
            executionMode = ExecutionMode.SHADOW,
            netLiq = netLiq,
            cashBalance = cashBd,
            settledCash = cashBd,
            cashBufferPct = cashBufferPct,
            buyingPower = cashBd,
            optionBuyingPower = cashBd,
            openPnl = totalOpenPnl,
            openPnlPct = openPnlPct,
            dayPnl = totalDayPnl,
            dayPnlPct = 0.0,
            positions = positions,
            spySpot = spySpot,
            nowMs = nowMs,
            isDemoSeeded = entities.isNotEmpty()
        )
    }

    private fun mapShadowPositionEntity(
        entity: ShadowPositionEntity,
        quotes: Map<String, QuoteEntryDto>,
        today: LocalDate
    ): Position {
        val qty = entity.quantity.toDoubleOrNull() ?: 0.0
        val avg = entity.averageCost.toDoubleOrNull() ?: 0.0
        val storedMark = entity.markPrice.toDoubleOrNull() ?: avg
        val category = runCatching { AssetCategory.valueOf(entity.assetType) }
            .getOrDefault(AssetCategory.EQUITY)
        val isOption = category == AssetCategory.OPTION
        val multiplier = if (isOption) OPTION_MULTIPLIER else 1.0

        val parsedOcc = if (isOption) occSymbolFormatter.parse(entity.symbol) else null
        val underlyingSymbol = entity.underlyingSymbol.ifBlank { parsedOcc?.root ?: entity.symbol }
        val symbolQuote = quotes[entity.symbol]
        val underlyingQuote = quotes[underlyingSymbol] ?: if (!isOption) symbolQuote else null

        val perShareMark = symbolQuote?.quote?.mark ?: symbolQuote?.quote?.lastPrice ?: storedMark
        val underlyingSpot = resolveSpot(underlyingQuote, fallback = if (!isOption) perShareMark else 0.0)
        val tickerBeta = underlyingQuote?.fundamental?.beta ?: 1.0
        val divYield = SchwabQuoteNormalizer.percentToDecimal(
            underlyingQuote?.fundamental?.divYield ?: underlyingQuote?.fundamental?.dividendYield
        ) ?: 0.0
        val divAmount = underlyingQuote?.fundamental?.divAmount
        val divDate = underlyingQuote?.fundamental?.divDate

        val marketValue = qty * perShareMark * multiplier
        val openPnl = (perShareMark - avg) * qty * multiplier
        val costBasis = abs(avg * qty * multiplier)
        val openPnlPct = if (costBasis > 0.0) (openPnl / costBasis) * 100.0 else 0.0

        if (!isOption) {
            return Position(
                symbol = entity.symbol,
                underlyingSymbol = underlyingSymbol,
                category = category,
                quantity = BigDecimal.valueOf(qty),
                averageCost = BigDecimal.valueOf(avg),
                markPrice = BigDecimal.valueOf(perShareMark),
                marketValue = BigDecimal.valueOf(marketValue),
                unrealizedPnl = BigDecimal.valueOf(openPnl),
                unrealizedPnlPercent = openPnlPct,
                dayPnl = BigDecimal.valueOf((symbolQuote?.quote?.netChange ?: 0.0) * qty),
                delta = 1.0,
                gamma = 0.0,
                theta = 0.0,
                vega = 0.0,
                betaToSpy = tickerBeta,
                underlyingSpot = perShareMark,
                divDate = divDate,
                divAmount = divAmount,
                divYield = divYield,
                isShadowPosition = true
            )
        }

        val isCall = parsedOcc?.isCall ?: true
        val strike = parsedOcc?.strike?.toDouble() ?: 0.0
        val dte = parsedOcc?.let { max(0, ChronoUnit.DAYS.between(today, it.expiration).toInt()) } ?: 30
        val timeYears = max(0.0, dte.toDouble()) / 365.0

        val q = symbolQuote?.quote
        val rawIv = SchwabQuoteNormalizer.percentToDecimal(q?.volatility)
        val solvedIv = if (rawIv == null || rawIv <= 0.0) {
            blackScholesEngine.impliedVolatility(
                isCall = isCall,
                marketPrice = perShareMark,
                spot = underlyingSpot,
                strike = strike,
                timeYears = timeYears,
                divYield = divYield
            )
        } else {
            rawIv
        }
        val ivForPricing = (solvedIv ?: 0.35).coerceIn(0.05, 5.0)
        val bs = blackScholesEngine.greeks(
            isCall = isCall,
            spot = underlyingSpot,
            strike = strike,
            timeYears = timeYears,
            divYield = divYield,
            iv = ivForPricing
        )

        val delta = q?.delta ?: bs.delta
        val gamma = q?.gamma ?: bs.gamma
        val theta = q?.theta ?: bs.thetaPerDay
        val vega = q?.vega ?: bs.vegaPer1PctIv
        val intrinsic = if (isCall) max(0.0, underlyingSpot - strike) else max(0.0, strike - underlyingSpot)

        return Position(
            symbol = entity.symbol,
            underlyingSymbol = underlyingSymbol,
            category = AssetCategory.OPTION,
            quantity = BigDecimal.valueOf(qty),
            averageCost = BigDecimal.valueOf(avg),
            markPrice = BigDecimal.valueOf(perShareMark),
            marketValue = BigDecimal.valueOf(marketValue),
            unrealizedPnl = BigDecimal.valueOf(openPnl),
            unrealizedPnlPercent = openPnlPct,
            dayPnl = BigDecimal.valueOf((q?.netChange ?: 0.0) * qty * multiplier),
            delta = delta,
            gamma = gamma,
            theta = theta,
            vega = vega,
            betaToSpy = tickerBeta,
            isCall = isCall,
            strike = strike,
            expirationDate = parsedOcc?.expiration?.toString(),
            daysToExpiration = dte,
            underlyingSpot = underlyingSpot,
            impliedVolatility = ivForPricing,
            extrinsicValue = max(0.0, perShareMark - intrinsic),
            divDate = divDate,
            divAmount = divAmount,
            divYield = divYield,
            greeksFromBlackScholesFallback = q?.delta == null,
            isShadowPosition = true
        )
    }

    private fun assembleSnapshot(
        dataSource: PortfolioDataSource,
        accountHash: String,
        maskedAccount: String,
        executionMode: ExecutionMode,
        netLiq: BigDecimal,
        cashBalance: BigDecimal,
        settledCash: BigDecimal,
        cashBufferPct: Double,
        buyingPower: BigDecimal,
        optionBuyingPower: BigDecimal,
        openPnl: BigDecimal,
        openPnlPct: Double,
        dayPnl: BigDecimal,
        dayPnlPct: Double,
        positions: List<Position>,
        spySpot: Double,
        nowMs: Long,
        isDemoSeeded: Boolean
    ): PortfolioSnapshot {
        val spyBetaDelta = betaWeightingCalculator.calculateSpyBetaWeightedDelta(positions, spySpot)
        val netDailyTheta = positions.sumOf { pos ->
            val mult = if (pos.category == AssetCategory.OPTION) OPTION_MULTIPLIER else 1.0
            pos.quantity.toDouble() * (pos.theta ?: 0.0) * mult
        }
        val netGamma = positions.sumOf { pos ->
            val mult = if (pos.category == AssetCategory.OPTION) OPTION_MULTIPLIER else 1.0
            pos.quantity.toDouble() * (pos.gamma ?: 0.0) * mult
        }
        val netVega = positions.sumOf { pos ->
            val mult = if (pos.category == AssetCategory.OPTION) OPTION_MULTIPLIER else 1.0
            pos.quantity.toDouble() * (pos.vega ?: 0.0) * mult
        }

        val portfolioAnomalies = mutableListOf<PortfolioAnomaly>()
        if (netLiq > BigDecimal.ZERO && cashBufferPct < 12.0) {
            portfolioAnomalies += PortfolioAnomaly(
                type = PortfolioAnomalyType.CASH_BUFFER_BELOW_12_PCT,
                symbol = null,
                message = "Cash buffer ${String.format("%.1f", cashBufferPct)}% < 12% target reserve"
            )
        }

        val groups = positions
            .groupBy { it.underlyingSymbol }
            .map { (underlying, legs) ->
                val groupMv = legs.fold(BigDecimal.ZERO) { acc, l -> acc + l.marketValue }
                val groupOpenPnl = legs.fold(BigDecimal.ZERO) { acc, l -> acc + l.unrealizedPnl }
                val groupDayPnl = legs.fold(BigDecimal.ZERO) { acc, l -> acc + l.dayPnl }
                val weightPct = if (netLiq > BigDecimal.ZERO) {
                    (groupMv.toDouble() / netLiq.toDouble()) * 100.0
                } else {
                    0.0
                }
                val groupNetDelta = legs.sumOf { leg ->
                    val mult = if (leg.category == AssetCategory.OPTION) OPTION_MULTIPLIER else 1.0
                    leg.quantity.toDouble() * (leg.delta ?: 1.0) * mult
                }
                val groupNetTheta = legs.sumOf { leg ->
                    val mult = if (leg.category == AssetCategory.OPTION) OPTION_MULTIPLIER else 1.0
                    leg.quantity.toDouble() * (leg.theta ?: 0.0) * mult
                }

                val groupAnomalies = mutableListOf<PortfolioAnomaly>()
                if (weightPct > 25.0) {
                    groupAnomalies += PortfolioAnomaly(
                        type = PortfolioAnomalyType.CONCENTRATION_OVER_25_PCT,
                        symbol = underlying,
                        message = ">25% WT (${String.format("%.1f", weightPct)}%)"
                    )
                }
                legs.forEach { leg ->
                    val dte = leg.daysToExpiration ?: 0
                    val absDelta = abs(leg.delta ?: 0.0)
                    if (leg.category == AssetCategory.OPTION &&
                        leg.quantity > BigDecimal.ZERO &&
                        dte >= 180 &&
                        absDelta < 0.65
                    ) {
                        groupAnomalies += PortfolioAnomaly(
                            type = PortfolioAnomalyType.LEAPS_LOW_DELTA,
                            symbol = leg.symbol,
                            message = "LEAPS < 0.65Δ (${String.format("%.2f", absDelta)})"
                        )
                    }
                }
                portfolioAnomalies += groupAnomalies

                UnderlyingPositionGroup(
                    underlyingSymbol = underlying,
                    underlyingSpot = legs.firstOrNull { (it.underlyingSpot ?: 0.0) > 0.0 }?.underlyingSpot ?: 0.0,
                    betaToSpy = legs.firstOrNull()?.betaToSpy ?: 1.0,
                    totalMarketValue = groupMv,
                    weightPercent = weightPct,
                    combinedOpenPnl = groupOpenPnl,
                    combinedDayPnl = groupDayPnl,
                    groupNetDelta = groupNetDelta,
                    groupNetTheta = groupNetTheta,
                    legs = legs,
                    anomalies = groupAnomalies
                )
            }
            .sortedByDescending { abs(it.totalMarketValue.toDouble()) }

        val summary = AccountSummary(
            accountHash = accountHash,
            maskedAccountNumber = maskedAccount,
            executionMode = executionMode,
            netLiquidationValue = netLiq,
            cashBalance = cashBalance,
            settledCash = settledCash,
            cashBufferPercent = cashBufferPct,
            buyingPower = buyingPower,
            optionBuyingPower = optionBuyingPower,
            openPnl = openPnl,
            openPnlPercent = openPnlPct,
            dayPnl = dayPnl,
            dayPnlPercent = dayPnlPct,
            portfolioSpyBetaWeightedDelta = spyBetaDelta,
            updatedAtEpochMs = nowMs
        )

        return PortfolioSnapshot(
            dataSource = dataSource,
            accountSummary = summary,
            positions = positions,
            groups = groups,
            aggregatedGreeks = AggregatedGreeks(
                spyBetaWeightedDelta = spyBetaDelta,
                netDailyTheta = netDailyTheta,
                netGamma = netGamma,
                netVegaPer1Pct = netVega
            ),
            spySpot = spySpot,
            portfolioAnomalies = portfolioAnomalies,
            isDemoSeeded = isDemoSeeded
        )
    }

    private fun resolvePerShareMark(
        quote: QuoteDetailDto?,
        dtoMarketValue: Double?,
        netQty: Double,
        multiplier: Double,
        fallback: Double
    ): Double {
        val quoteMark = quote?.mark?.takeIf { it > 0.0 }
            ?: quote?.lastPrice?.takeIf { it > 0.0 }
        if (quoteMark != null) return quoteMark
        if (dtoMarketValue != null && abs(netQty) > 1e-9) {
            return abs(dtoMarketValue) / (abs(netQty) * multiplier)
        }
        return fallback
    }

    private fun resolveSpot(entry: QuoteEntryDto?, fallback: Double): Double =
        entry?.quote?.mark?.takeIf { it > 0.0 }
            ?: entry?.quote?.lastPrice?.takeIf { it > 0.0 }
            ?: entry?.quote?.closePrice?.takeIf { it > 0.0 }
            ?: fallback

    private fun maskAccountNumber(rawNumber: String): String {
        val clean = rawNumber.trim()
        return if (clean.length <= 4) "••••$clean" else "••••${clean.takeLast(4)}"
    }

    private fun buildOfflineDemoQuotes(today: LocalDate): Map<String, QuoteEntryDto> = mapOf(
        "SPY" to QuoteEntryDto(
            symbol = "SPY",
            quote = QuoteDetailDto(mark = DEFAULT_SPY_SPOT, lastPrice = DEFAULT_SPY_SPOT),
            fundamental = FundamentalDto(beta = 1.0, divYield = 1.24)
        ),
        "IWM" to QuoteEntryDto(
            symbol = "IWM",
            quote = QuoteDetailDto(mark = 224.0, lastPrice = 224.0, netChange = 1.15),
            fundamental = FundamentalDto(beta = 1.15, divYield = 1.18)
        ),
        "XLF" to QuoteEntryDto(
            symbol = "XLF",
            quote = QuoteDetailDto(mark = 49.60, lastPrice = 49.60, netChange = 0.28),
            fundamental = FundamentalDto(
                beta = 1.05,
                divAmount = 0.24,
                divYield = 1.48,
                divDate = today.plusDays(2).toString()
            )
        ),
        "NVDA" to QuoteEntryDto(
            symbol = "NVDA",
            quote = QuoteDetailDto(mark = 142.0, lastPrice = 142.0, netChange = -1.40),
            fundamental = FundamentalDto(beta = 1.65, divYield = 0.03)
        ),
        "GOOG" to QuoteEntryDto(
            symbol = "GOOG",
            quote = QuoteDetailDto(mark = 270.0, lastPrice = 270.0, netChange = 2.10),
            fundamental = FundamentalDto(beta = 1.08, divYield = 0.30)
        )
    )

    companion object {
        private const val OPTION_MULTIPLIER = 100.0
        private val OPTION_MULTIPLIER_BD = BigDecimal(100)
        private const val DEFAULT_SPY_SPOT = 585.0
        private const val DEMO_CASH_BALANCE = 2125.0
        private const val DAY_MS = 24L * 60L * 60L * 1_000L
        private val NY_ZONE: ZoneId = ZoneId.of("America/New_York")
    }
}
