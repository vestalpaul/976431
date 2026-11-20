package com.schwabquant.app.data.repository

import com.schwabquant.app.core.quant.OccSymbolFormatter
import com.schwabquant.app.data.local.dao.AuditLogDao
import com.schwabquant.app.data.local.dao.ShadowLedgerDao
import com.schwabquant.app.data.local.dao.WashSaleDao
import com.schwabquant.app.data.local.entity.AuditLogEntity
import com.schwabquant.app.data.local.entity.ShadowOrderEntity
import com.schwabquant.app.data.local.entity.ShadowPositionEntity
import com.schwabquant.app.data.local.entity.WashSaleTradeEntity
import com.schwabquant.app.data.local.prefs.AppPreferencesDataStore
import com.schwabquant.app.domain.model.AssetCategory
import com.schwabquant.app.domain.model.ExecutionMode
import com.schwabquant.app.domain.model.OrderDuration
import com.schwabquant.app.domain.model.OrderLeg
import com.schwabquant.app.domain.model.OrderSide
import com.schwabquant.app.domain.model.OrderTicket
import com.schwabquant.app.domain.model.OrderType
import com.schwabquant.app.domain.model.TradingSession
import com.schwabquant.app.domain.repository.MarketDataRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ShadowExecutionEngineTest {

    private fun createEngine(initialCash: Double = 25_000.0): TestHarness {
        val cashFlow = MutableStateFlow(initialCash)
        val prefs = mockk<AppPreferencesDataStore>(relaxed = true)
        every { prefs.shadowCashBalance } returns cashFlow
        coEvery { prefs.setShadowCashBalance(any()) } answers {
            cashFlow.value = firstArg()
        }

        val marketRepo = mockk<MarketDataRepository>(relaxed = true)
        coEvery { marketRepo.getQuotes(any(), any()) } returns Result.success(emptyMap())

        val shadowDao = InMemoryShadowLedgerDao()
        val washDao = InMemoryWashSaleDao()
        val auditDao = InMemoryAuditLogDao()
        val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

        val engine = ShadowExecutionEngine(
            shadowLedgerDao = shadowDao,
            washSaleDao = washDao,
            auditLogDao = auditDao,
            preferences = prefs,
            marketDataRepository = marketRepo,
            occSymbolFormatter = OccSymbolFormatter(),
            json = json,
            nowProvider = { 1_795_000_000_000L }
        )
        return TestHarness(engine, shadowDao, washDao, auditDao, cashFlow)
    }

    @Test
    fun `multi leg PMCC fill reconciles leg prices, updates cash with 100x multiplier, and writes WashSale trades`() = runTest {
        val h = createEngine(initialCash = 25_000.0)
        val pmcc = OrderTicket(
            accountHash = "SHADOW_PAPER",
            executionMode = ExecutionMode.SHADOW,
            orderType = OrderType.NET_DEBIT,
            session = TradingSession.NORMAL,
            duration = OrderDuration.DAY,
            strategyType = "SINGLE",
            complexStrategyType = "DIAGONAL",
            limitPrice = BigDecimal("43.80"),
            legs = listOf(
                OrderLeg(
                    symbol = "IWM   280121C00190000",
                    category = AssetCategory.OPTION,
                    side = OrderSide.BUY_TO_OPEN,
                    quantity = 1,
                    underlyingSymbol = "IWM",
                    estimatedUnitPrice = BigDecimal("46.50")
                ),
                OrderLeg(
                    symbol = "IWM   261218C00235000",
                    category = AssetCategory.OPTION,
                    side = OrderSide.SELL_TO_OPEN,
                    quantity = 1,
                    underlyingSymbol = "IWM",
                    estimatedUnitPrice = BigDecimal("2.50")
                )
            )
        )

        val res = h.engine.simulateOrder(pmcc, immediateMidFill = true)
        assertEquals("FILLED", res.status)
        // Cash: 25,000 - (43.80 * 100) = 20,620.00
        assertEquals(20_620.0, h.cashFlow.value, 1e-2)

        val positions = h.shadowDao.getPositionsSnapshot()
        assertEquals(2, positions.size)
        val longPos = positions.first { it.symbol == "IWM   280121C00190000" }
        val shortPos = positions.first { it.symbol == "IWM   261218C00235000" }
        assertEquals("1", longPos.quantity)
        assertEquals("-1", shortPos.quantity)
        // Reconciled net = longCost - shortCost == 43.80
        assertEquals(43.80, longPos.averageCost.toDouble() - shortPos.averageCost.toDouble(), 0.02)

        val washRows = h.washDao.getAll()
        assertEquals(2, washRows.size)
        assertTrue(washRows.all { it.source == "SHADOW" && it.underlyingSymbol == "IWM" })

        // Now close short call via BTC at $1.10 -> removes short position, credits/debits cash cleanly
        val btc = OrderTicket(
            accountHash = "SHADOW_PAPER",
            executionMode = ExecutionMode.SHADOW,
            orderType = OrderType.LIMIT,
            session = TradingSession.NORMAL,
            duration = OrderDuration.DAY,
            limitPrice = BigDecimal("1.10"),
            legs = listOf(
                OrderLeg(
                    symbol = "IWM   261218C00235000",
                    category = AssetCategory.OPTION,
                    side = OrderSide.BUY_TO_CLOSE,
                    quantity = 1,
                    underlyingSymbol = "IWM",
                    estimatedUnitPrice = BigDecimal("1.10")
                )
            )
        )
        val btcRes = h.engine.simulateOrder(btc, immediateMidFill = true)
        assertEquals("FILLED", btcRes.status)
        assertNull(h.shadowDao.getPositionBySymbol("IWM   261218C00235000"))
        assertEquals(20_620.0 - 110.0, h.cashFlow.value, 1e-2)

        val btcWash = h.washDao.getAll().last()
        assertNotNull(btcWash.realizedPnl)
        assertTrue(btcWash.realizedPnl!! > 100.0, "Short call bought back cheaper realizes profit")
    }

    @Test
    fun `working limit order supports manual simulate fill and cancel`() = runTest {
        val h = createEngine(initialCash = 10_000.0)
        val limitBuy = OrderTicket(
            accountHash = "SHADOW_PAPER",
            executionMode = ExecutionMode.SHADOW,
            orderType = OrderType.LIMIT,
            session = TradingSession.SEAMLESS,
            duration = OrderDuration.DAY,
            limitPrice = BigDecimal("250.00"),
            legs = listOf(OrderLeg("GOOG", AssetCategory.EQUITY, OrderSide.BUY, 4, estimatedUnitPrice = BigDecimal("250.00")))
        )

        val working = h.engine.simulateOrder(limitBuy, immediateMidFill = false)
        assertEquals("WORKING", working.status)
        assertEquals(10_000.0, h.cashFlow.value, 1e-2)

        val filled = h.engine.simulateFillOrder(working.orderId).getOrThrow()
        assertEquals("FILLED", filled.status)
        // 4 shares * 250.00 = 1,000 -> remaining cash 9,000
        assertEquals(9_000.0, h.cashFlow.value, 1e-2)

        val secondWorking = h.engine.simulateOrder(limitBuy, immediateMidFill = false)
        h.engine.cancelShadowOrder(secondWorking.orderId).getOrThrow()
        assertEquals("CANCELED", h.engine.getShadowOrderStatus(secondWorking.orderId))
    }

    @Test
    fun `H-6 BUY_TO_COVER closes short equity position, debits cash, and logs WashSale profit`() = runTest {
        val h = createEngine(initialCash = 20_000.0)
        h.shadowDao.upsertPosition(
            ShadowPositionEntity(
                symbol = "GOOG",
                assetType = AssetCategory.EQUITY.name,
                underlyingSymbol = "GOOG",
                quantity = "-10",
                averageCost = "280.00",
                markPrice = "270.00",
                openedAtEpochMs = 1_000L,
                updatedAtEpochMs = 1_000L
            )
        )

        val coverTicket = OrderTicket(
            accountHash = "SHADOW_PAPER",
            executionMode = ExecutionMode.SHADOW,
            orderType = OrderType.LIMIT,
            session = TradingSession.SEAMLESS,
            duration = OrderDuration.DAY,
            limitPrice = BigDecimal("270.00"),
            legs = listOf(
                OrderLeg(
                    symbol = "GOOG",
                    category = AssetCategory.EQUITY,
                    side = OrderSide.BUY_TO_COVER,
                    quantity = 10,
                    underlyingSymbol = "GOOG",
                    estimatedUnitPrice = BigDecimal("270.00")
                )
            )
        )

        val res = h.engine.simulateOrder(coverTicket, immediateMidFill = true)
        assertEquals("FILLED", res.status)
        assertNull(h.shadowDao.getPositionBySymbol("GOOG"))
        // Cash: 20,000 - (10 * 270.00) = 17,300.00
        assertEquals(17_300.0, h.cashFlow.value, 1e-2)

        val wash = h.washDao.getAll().single()
        assertEquals("BUY_TO_COVER", wash.instruction)
        assertEquals(100.0, wash.realizedPnl!!, 1e-2)
    }

    private data class TestHarness(
        val engine: ShadowExecutionEngine,
        val shadowDao: InMemoryShadowLedgerDao,
        val washDao: InMemoryWashSaleDao,
        val auditDao: InMemoryAuditLogDao,
        val cashFlow: MutableStateFlow<Double>
    )

    private class InMemoryShadowLedgerDao : ShadowLedgerDao {
        private val positions = linkedMapOf<String, ShadowPositionEntity>()
        private val orders = linkedMapOf<String, ShadowOrderEntity>()
        private val posFlow = MutableStateFlow<List<ShadowPositionEntity>>(emptyList())
        private val orderFlow = MutableStateFlow<List<ShadowOrderEntity>>(emptyList())

        override fun observePositions(): Flow<List<ShadowPositionEntity>> = posFlow
        override suspend fun getPositionsSnapshot(): List<ShadowPositionEntity> = positions.values.toList()
        override suspend fun getPositionBySymbol(symbol: String): ShadowPositionEntity? = positions[symbol]
        override suspend fun upsertPosition(position: ShadowPositionEntity) {
            positions[position.symbol] = position
            posFlow.value = positions.values.toList()
        }
        override suspend fun upsertPositions(positions: List<ShadowPositionEntity>) {
            positions.forEach { this.positions[it.symbol] = it }
            posFlow.value = this.positions.values.toList()
        }
        override suspend fun deletePosition(symbol: String) {
            positions.remove(symbol)
            posFlow.value = positions.values.toList()
        }
        override suspend fun clearPositions() {
            positions.clear()
            posFlow.value = emptyList()
        }
        override fun observeOrders(): Flow<List<ShadowOrderEntity>> = orderFlow
        override suspend fun getOrdersSnapshot(): List<ShadowOrderEntity> = orders.values.toList()
        override suspend fun getOrderById(orderId: String): ShadowOrderEntity? = orders[orderId]
        override suspend fun upsertOrder(order: ShadowOrderEntity) {
            orders[order.orderId] = order
            orderFlow.value = orders.values.toList()
        }
        override suspend fun cancelOrder(orderId: String) {
            orders[orderId]?.let { orders[orderId] = it.copy(status = "CANCELED") }
            orderFlow.value = orders.values.toList()
        }
    }

    private class InMemoryWashSaleDao : WashSaleDao {
        private val list = mutableListOf<WashSaleTradeEntity>()
        private val flow = MutableStateFlow<List<WashSaleTradeEntity>>(emptyList())
        private var nextId = 1L

        override fun observeAll(): Flow<List<WashSaleTradeEntity>> = flow
        override suspend fun getAll(): List<WashSaleTradeEntity> = list.toList()
        override suspend fun countByExternalOrderId(externalOrderId: String): Int =
            list.count { it.externalOrderId == externalOrderId }
        override suspend fun insert(entity: WashSaleTradeEntity): Long {
            val id = nextId++
            list += entity.copy(id = id)
            flow.value = list.toList()
            return id
        }
        override suspend fun insertAll(entities: List<WashSaleTradeEntity>) {
            entities.forEach { insert(it) }
        }
        override suspend fun deleteById(id: Long) {}
        override suspend fun deleteBySource(source: String) {}
    }

    private class InMemoryAuditLogDao : AuditLogDao {
        val entries = mutableListOf<AuditLogEntity>()
        private val flow = MutableStateFlow<List<AuditLogEntity>>(emptyList())
        override fun observeRecent(limit: Int): Flow<List<AuditLogEntity>> = flow
        override suspend fun insert(entity: AuditLogEntity): Long {
            entries += entity
            flow.value = entries.toList()
            return entries.size.toLong()
        }
    }
}
