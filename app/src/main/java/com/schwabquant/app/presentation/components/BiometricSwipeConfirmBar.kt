package com.schwabquant.app.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.schwabquant.app.domain.model.ExecutionMode
import com.schwabquant.app.presentation.theme.SchwabQuantTheme
import kotlin.math.roundToInt

/**
 * Interactive slide-to-confirm bar requiring full swipe completion (>= 92% of track width)
 * before invoking BiometricPrompt confirmation. Snaps back to 0% whenever canceled or resetKey changes.
 */
@Composable
fun BiometricSwipeConfirmBar(
    executionMode: ExecutionMode,
    enabled: Boolean = true,
    resetKey: Int = 0,
    label: String = if (executionMode == ExecutionMode.LIVE) {
        "Swipe Full Right — Biometric LIVE Submit"
    } else {
        "Swipe Full Right — Confirm SHADOW Order"
    },
    onConfirmTriggered: () -> Unit,
    modifier: Modifier = Modifier
) {
    val thumbSizeDp = 44.dp
    val density = LocalDensity.current
    val thumbSizePx = with(density) { thumbSizeDp.toPx() }
    var trackWidthPx by remember { mutableFloatStateOf(0f) }
    var dragOffsetPx by remember(resetKey) { mutableFloatStateOf(0f) }
    val maxOffsetPx = (trackWidthPx - thumbSizePx - with(density) { 8.dp.toPx() }).coerceAtLeast(1f)

    LaunchedEffect(resetKey, enabled) {
        dragOffsetPx = 0f
    }

    val progressFraction = (dragOffsetPx / maxOffsetPx).coerceIn(0f, 1f)
    val containerColor = if (executionMode == ExecutionMode.LIVE) {
        SchwabQuantTheme.colors.liveBannerBg
    } else {
        SchwabQuantTheme.colors.shadowBannerBg
    }
    val contentColor = if (executionMode == ExecutionMode.LIVE) {
        SchwabQuantTheme.colors.liveBannerFg
    } else {
        SchwabQuantTheme.colors.shadowBannerFg
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp)
            .onSizeChanged { trackWidthPx = it.width.toFloat() },
        color = containerColor,
        shape = RoundedCornerShape(27.dp),
        border = BorderStroke(1.dp, contentColor.copy(alpha = if (enabled) 0.65f else 0.25f))
    ) {
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(27.dp))
                .padding(horizontal = 4.dp)
        ) {
            if (progressFraction > 0.01f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progressFraction)
                        .fillMaxHeight()
                        .background(contentColor.copy(alpha = 0.18f))
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.align(Alignment.Center)
            ) {
                Spacer(modifier = Modifier.width(36.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = contentColor.copy(alpha = if (enabled) 1f else 0.45f)
                )
            }

            Surface(
                shape = CircleShape,
                color = contentColor.copy(alpha = if (enabled) 1f else 0.4f),
                modifier = Modifier
                    .offset { IntOffset(dragOffsetPx.roundToInt(), 0) }
                    .size(thumbSizeDp)
                    .pointerInput(enabled, maxOffsetPx, resetKey) {
                        if (!enabled) return@pointerInput
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                val completed = dragOffsetPx >= maxOffsetPx * 0.92f
                                dragOffsetPx = 0f
                                if (completed) {
                                    onConfirmTriggered()
                                }
                            },
                            onDragCancel = { dragOffsetPx = 0f },
                            onHorizontalDrag = { _, dragAmount ->
                                dragOffsetPx = (dragOffsetPx + dragAmount).coerceIn(0f, maxOffsetPx)
                            }
                        )
                    }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Fingerprint,
                        contentDescription = "Biometric confirmation handle",
                        tint = containerColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}
