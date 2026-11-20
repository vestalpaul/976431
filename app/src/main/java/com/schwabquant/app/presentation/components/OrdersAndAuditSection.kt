package com.schwabquant.app.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.schwabquant.app.data.local.entity.AuditLogEntity
import com.schwabquant.app.domain.model.ExecutionMode
import com.schwabquant.app.domain.model.WorkingOrder
import com.schwabquant.app.presentation.theme.SchwabQuantTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Shared collapsible Working Orders & Recent Audit Log section rendered on DashboardScreen
 * and SentinelScreen without code duplication.
 */
@Composable
fun WorkingOrdersAndAuditCard(
    workingOrders: List<WorkingOrder>,
    auditLogs: List<AuditLogEntity>,
    executionMode: ExecutionMode,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onRefreshOrders: () -> Unit,
    onCancelOrder: (String) -> Unit,
    onSimulateShadowFill: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = SchwabQuantTheme.colors
    val activeWorkingCount = workingOrders.count {
        it.status.equals("WORKING", ignoreCase = true) || it.status.equals("QUEUED", ignoreCase = true)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, colors.borderSubtle)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Orders & Audit Trail (${workingOrders.size} / ${auditLogs.size})",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = if (activeWorkingCount > 0) {
                            "$activeWorkingCount working order(s) in ${executionMode.name} mode"
                        } else {
                            "Execution firewall logs, risk overrides & simulated fills"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (activeWorkingCount > 0) colors.warning else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onRefreshOrders,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh working orders")
                    }
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand"
                    )
                }
            }

            if (expanded) {
                HorizontalDivider()

                if (workingOrders.isEmpty()) {
                    Text(
                        text = "No submitted orders recorded yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "ORDERS (${executionMode.name})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    workingOrders.take(10).forEach { order ->
                        OrderRowCard(
                            order = order,
                            onCancel = { onCancelOrder(order.orderId) },
                            onSimulateFill = { onSimulateShadowFill(order.orderId) }
                        )
                    }
                }

                if (auditLogs.isNotEmpty()) {
                    HorizontalDivider()
                    Text(
                        text = "RECENT AUDIT LOG (${auditLogs.size})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    auditLogs.take(12).forEach { entry ->
                        AuditLogRow(entry = entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun OrderRowCard(
    order: WorkingOrder,
    onCancel: () -> Unit,
    onSimulateFill: () -> Unit
) {
    val colors = SchwabQuantTheme.colors
    val statusColor = when (order.status.uppercase()) {
        "FILLED" -> colors.profit
        "WORKING", "QUEUED" -> colors.warning
        "CANCELED", "CANCELLED", "REJECTED" -> colors.loss
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val isWorking = order.status.equals("WORKING", ignoreCase = true)

    Card(
        colors = CardDefaults.cardColors(containerColor = colors.surfaceElevated),
        border = BorderStroke(1.dp, colors.borderSubtle),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${order.symbol} (${order.strategyType})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = statusColor.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, statusColor)
                ) {
                    Text(
                        text = order.status,
                        style = SchwabQuantTheme.numericTypography.badgeMono,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Text(
                text = buildString {
                    append("${order.orderType} x${order.quantity} • ${order.session}/${order.duration}")
                    order.fillPrice?.let { append(" • Filled @$it") }
                        ?: order.limitPrice?.let { append(" • Limit @$it") }
                    order.stopPrice?.let { append(" • Stop @$it") }
                },
                style = SchwabQuantTheme.numericTypography.tableCell,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = "ID: ${order.orderId}",
                style = SchwabQuantTheme.numericTypography.badgeMono,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (isWorking) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (order.executionMode == ExecutionMode.SHADOW) {
                        Button(
                            onClick = onSimulateFill,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Simulate Fill")
                        }
                    }
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel Order")
                    }
                }
            }
        }
    }
}

@Composable
private fun AuditLogRow(entry: AuditLogEntity) {
    val colors = SchwabQuantTheme.colors
    val sevColor: Color = when (entry.severity.uppercase()) {
        "CRITICAL" -> colors.loss
        "WARNING" -> colors.warning
        else -> colors.profit
    }
    val timeLabel = runCatching {
        TIME_FORMAT.format(Instant.ofEpochMilli(entry.timestampEpochMs).atZone(NY_ZONE))
    }.getOrDefault("--:--")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = sevColor.copy(alpha = 0.16f)
        ) {
            Text(
                text = entry.category.take(18),
                style = SchwabQuantTheme.numericTypography.badgeMono,
                color = sevColor,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.message,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "${entry.executionMode} • $timeLabel ET",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private val NY_ZONE: ZoneId = ZoneId.of("America/New_York")
private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd HH:mm:ss")
