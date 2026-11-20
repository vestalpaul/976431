package com.schwabquant.app.data.repository

import com.schwabquant.app.data.local.dao.AuditLogDao
import com.schwabquant.app.data.local.dao.SentinelRuleDao
import com.schwabquant.app.data.local.entity.AuditLogEntity
import com.schwabquant.app.data.local.entity.SentinelRuleEntity
import com.schwabquant.app.data.remote.dto.SchwabSymbolNormalizer
import com.schwabquant.app.domain.model.AssetCategory
import com.schwabquant.app.domain.model.ExecutionMode
import com.schwabquant.app.domain.model.SentinelRule
import com.schwabquant.app.domain.model.SentinelRuleStatus
import com.schwabquant.app.domain.model.SentinelRuleType
import com.schwabquant.app.domain.model.TradingSession
import com.schwabquant.app.domain.repository.SentinelRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SentinelRepositoryImpl @Inject constructor(
    private val sentinelRuleDao: SentinelRuleDao,
    private val auditLogDao: AuditLogDao
) : SentinelRepository {

    override fun observeRules(): Flow<List<SentinelRule>> =
        sentinelRuleDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override fun observeArmedRules(): Flow<List<SentinelRule>> =
        sentinelRuleDao.observeArmed().map { rows -> rows.map { it.toDomain() } }

    override fun observeActiveRules(): Flow<List<SentinelRule>> =
        sentinelRuleDao.observeActive().map { rows -> rows.map { it.toDomain() } }

    override suspend fun getRuleById(ruleId: Long): SentinelRule? =
        sentinelRuleDao.getById(ruleId)?.toDomain()

    override suspend fun getArmedRulesOnce(): List<SentinelRule> =
        sentinelRuleDao.getArmedOnce().map { it.toDomain() }

    override suspend fun getRulesByStatus(status: SentinelRuleStatus): List<SentinelRule> =
        sentinelRuleDao.getByStatus(status.name).map { it.toDomain() }

    override suspend fun upsertRule(rule: SentinelRule): Long {
        val now = System.currentTimeMillis()
        val displaySym = SchwabSymbolNormalizer.toDisplaySymbol(rule.symbol)
        val entity = SentinelRuleEntity(
            id = rule.id,
            symbol = displaySym,
            assetType = rule.category.name,
            ruleType = rule.ruleType.name,
            triggerPrice = rule.triggerPrice.toPlainString(),
            limitOffsetCents = 0,
            quantity = rule.quantity.coerceAtLeast(1),
            session = rule.session.apiValue,
            holdSeconds = rule.holdSeconds.coerceAtLeast(0),
            status = rule.status.name,
            isShadowMode = rule.executionMode == ExecutionMode.SHADOW,
            createdAtEpochMs = if (rule.createdAtEpochMs == 0L) now else rule.createdAtEpochMs,
            updatedAtEpochMs = now,
            slippageCollarPct = rule.slippageCollarPct.coerceAtLeast(0.0),
            minConsecutiveTicks = rule.minConsecutiveTicks.coerceAtLeast(1),
            maxSpreadPct = rule.maxSpreadPct.coerceAtLeast(0.0),
            sweepBufferPct = rule.sweepBufferPct.coerceAtLeast(0.0),
            antiKnifeLookbackMinutes = rule.antiKnifeLookbackMinutes.coerceAtLeast(1),
            antiKnifeBouncePct = rule.antiKnifeBouncePct.coerceAtLeast(0.0),
            isShortPosition = rule.isShortPosition || rule.ruleType == SentinelRuleType.SHORT_STOP_LOSS,
            lastTriggerNote = rule.lastTriggerNote,
            lastSubmittedOrderId = rule.lastSubmittedOrderId
        )
        return sentinelRuleDao.upsert(entity)
    }

    override suspend fun updateRuleStatus(ruleId: Long, status: SentinelRuleStatus) {
        sentinelRuleDao.updateStatus(
            ruleId = ruleId,
            status = status.name,
            updatedAtEpochMs = System.currentTimeMillis()
        )
    }

    override suspend fun updateRuleStatusWithNote(
        ruleId: Long,
        status: SentinelRuleStatus,
        note: String?,
        lastSubmittedOrderId: String?
    ) {
        sentinelRuleDao.updateStatusWithNote(
            ruleId = ruleId,
            status = status.name,
            note = note,
            updatedAtEpochMs = System.currentTimeMillis(),
            lastSubmittedOrderId = lastSubmittedOrderId
        )
    }

    override suspend fun disarmAllRules() {
        sentinelRuleDao.disarmAll(System.currentTimeMillis())
    }

    override suspend fun deleteRule(ruleId: Long) {
        sentinelRuleDao.deleteById(ruleId)
    }

    override suspend fun recordAuditEvent(
        category: String,
        severity: String,
        symbol: String?,
        executionMode: String,
        message: String,
        metadataJson: String?
    ) {
        auditLogDao.insert(
            AuditLogEntity(
                timestampEpochMs = System.currentTimeMillis(),
                category = category,
                severity = severity,
                symbol = symbol,
                executionMode = executionMode,
                message = message,
                metadataJson = metadataJson
            )
        )
    }

    private fun SentinelRuleEntity.toDomain(): SentinelRule {
        val parsedRuleType = when (ruleType) {
            "SYNTHETIC_STOP_LOSS" -> SentinelRuleType.LONG_STOP_LOSS
            "SYNTHETIC_TAKE_PROFIT" -> SentinelRuleType.TAKE_PROFIT
            "PRICE_ALERT" -> SentinelRuleType.OVERNIGHT_PRICE_ALERT
            else -> runCatching { SentinelRuleType.valueOf(ruleType) }
                .getOrDefault(SentinelRuleType.LONG_STOP_LOSS)
        }
        val parsedStatus = when (status) {
            "TRIGGERED" -> SentinelRuleStatus.TRIGGERED_FILLED
            else -> runCatching { SentinelRuleStatus.valueOf(status) }
                .getOrDefault(SentinelRuleStatus.ARMED)
        }
        return SentinelRule(
            id = id,
            symbol = SchwabSymbolNormalizer.toDisplaySymbol(symbol),
            category = runCatching { AssetCategory.valueOf(assetType) }.getOrDefault(AssetCategory.EQUITY),
            ruleType = parsedRuleType,
            triggerPrice = triggerPrice.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            quantity = quantity.coerceAtLeast(1),
            slippageCollarPct = slippageCollarPct,
            holdSeconds = holdSeconds,
            minConsecutiveTicks = minConsecutiveTicks,
            maxSpreadPct = maxSpreadPct,
            sweepBufferPct = sweepBufferPct,
            antiKnifeLookbackMinutes = antiKnifeLookbackMinutes,
            antiKnifeBouncePct = antiKnifeBouncePct,
            isShortPosition = isShortPosition || parsedRuleType == SentinelRuleType.SHORT_STOP_LOSS,
            session = runCatching { TradingSession.valueOf(session) }.getOrDefault(TradingSession.SEAMLESS),
            status = parsedStatus,
            executionMode = if (isShadowMode) ExecutionMode.SHADOW else ExecutionMode.LIVE,
            lastTriggerNote = lastTriggerNote,
            lastSubmittedOrderId = lastSubmittedOrderId,
            createdAtEpochMs = createdAtEpochMs,
            updatedAtEpochMs = updatedAtEpochMs
        )
    }
}
