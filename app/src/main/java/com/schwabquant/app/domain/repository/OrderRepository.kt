package com.schwabquant.app.domain.repository

import com.schwabquant.app.data.local.entity.AuditLogEntity
import com.schwabquant.app.domain.model.ExecutionMode
import com.schwabquant.app.domain.model.OrderStatusSnapshot
import com.schwabquant.app.domain.model.OrderSubmissionResult
import com.schwabquant.app.domain.model.OrderTicket
import com.schwabquant.app.domain.model.ReplaceOrderOutcome
import com.schwabquant.app.domain.model.WorkingOrder
import kotlinx.coroutines.flow.Flow

interface OrderRepository {
    fun observeRecentOrders(mode: ExecutionMode): Flow<List<OrderSubmissionResult>>
    fun observeWorkingOrders(): Flow<List<WorkingOrder>>
    fun observeRecentAuditLogs(limit: Int = 50): Flow<List<AuditLogEntity>>

    suspend fun getWorkingOrders(forceRefresh: Boolean = false): Result<List<WorkingOrder>>
    suspend fun getOrderStatus(accountHash: String, orderId: String): Result<OrderStatusSnapshot>

    suspend fun submitOrder(
        ticket: OrderTicket,
        risksAcknowledged: Boolean,
        overriddenRiskCodes: List<String> = emptyList(),
        simulateImmediateMidFill: Boolean = true
    ): Result<OrderSubmissionResult>

    suspend fun cancelOrder(
        accountHash: String,
        orderId: String,
        mode: ExecutionMode = ExecutionMode.SHADOW
    ): Result<Unit>

    suspend fun replaceOrder(
        accountHash: String,
        oldOrderId: String,
        replacementTicket: OrderTicket,
        risksAcknowledged: Boolean,
        overriddenRiskCodes: List<String> = emptyList(),
        cancelToReplaceDelayMs: Long = 1_500L,
        allowTerminalOrPartialReprice: Boolean = false
    ): Result<ReplaceOrderOutcome>

    suspend fun simulateShadowFill(orderId: String): Result<OrderSubmissionResult>
}
