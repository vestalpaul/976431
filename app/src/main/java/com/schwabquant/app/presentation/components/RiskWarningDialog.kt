package com.schwabquant.app.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.schwabquant.app.domain.model.RiskSeverity
import com.schwabquant.app.domain.model.RiskWarning
import com.schwabquant.app.presentation.theme.SchwabQuantTheme

/**
 * Soft-Gate pre-trade risk dialog surfacing every triggered risk rule cleanly with plain-English
 * impact, metric breakdown, and explicit user override acknowledgment (zero hard blocks).
 */
@Composable
fun RiskWarningDialog(
    warnings: List<RiskWarning>,
    isBlocked: Boolean = false,
    blockReason: String? = null,
    onDismiss: () -> Unit,
    onAcknowledgeAndProceed: () -> Unit
) {
    var acknowledged by remember(warnings) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = "Pre-trade risk warning",
                tint = if (isBlocked || warnings.any { it.severity == RiskSeverity.CRITICAL }) {
                    SchwabQuantTheme.colors.loss
                } else {
                    SchwabQuantTheme.colors.warning
                },
                modifier = Modifier.size(28.dp)
            )
        },
        title = {
            Text(
                text = "Pre-Trade Soft-Gate Risk Review (${warnings.size})",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (isBlocked && !blockReason.isNullOrBlank()) {
                    Text(
                        text = blockReason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = SchwabQuantTheme.colors.loss,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                warnings.forEach { warning ->
                    val badgeColor = when (warning.severity) {
                        RiskSeverity.CRITICAL -> SchwabQuantTheme.colors.loss
                        RiskSeverity.WARNING -> SchwabQuantTheme.colors.warning
                        RiskSeverity.INFO -> MaterialTheme.colorScheme.primary
                    }
                    Surface(
                        color = SchwabQuantTheme.colors.surfaceElevated,
                        shape = MaterialTheme.shapes.small,
                        border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.55f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "[${warning.severity.name}] ${warning.title}",
                                style = MaterialTheme.typography.labelMedium,
                                color = badgeColor,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = warning.metricDetail,
                                style = SchwabQuantTheme.numericTypography.badgeMono,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = warning.plainEnglishImpact,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (!isBlocked) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = acknowledged,
                            onCheckedChange = { acknowledged = it }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "I acknowledge these triggered risks and wish to log an explicit override.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (!isBlocked) {
                Button(
                    onClick = onAcknowledgeAndProceed,
                    enabled = acknowledged,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (warnings.any { it.severity == RiskSeverity.CRITICAL }) {
                            SchwabQuantTheme.colors.loss
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                ) {
                    Text("I Understand the Risks — Override & Continue")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (isBlocked) "Close" else "Cancel")
            }
        }
    )
}
