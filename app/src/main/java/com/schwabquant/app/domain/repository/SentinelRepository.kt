package com.schwabquant.app.domain.repository

import com.schwabquant.app.domain.model.SentinelRule
import com.schwabquant.app.domain.model.SentinelRuleStatus
import kotlinx.coroutines.flow.Flow

interface SentinelRepository {
    fun observeRules(): Flow<List<SentinelRule>>
    fun observeArmedRules(): Flow<List<SentinelRule>>
    fun observeActiveRules(): Flow<List<SentinelRule>> = observeArmedRules()
    suspend fun getRuleById(ruleId: Long): SentinelRule?
    suspend fun getArmedRulesOnce(): List<SentinelRule>
    suspend fun getRulesByStatus(status: SentinelRuleStatus): List<SentinelRule>
    suspend fun upsertRule(rule: SentinelRule): Long
    suspend fun updateRuleStatus(ruleId: Long, status: SentinelRuleStatus)
    suspend fun updateRuleStatusWithNote(
        ruleId: Long,
        status: SentinelRuleStatus,
        note: String?,
        lastSubmittedOrderId: String? = null
    )
    suspend fun disarmAllRules()
    suspend fun deleteRule(ruleId: Long)
    suspend fun recordAuditEvent(
        category: String,
        severity: String,
        symbol: String?,
        executionMode: String,
        message: String,
        metadataJson: String? = null
    )
}
