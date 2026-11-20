package com.schwabquant.app.presentation.market.chart

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.schwabquant.app.core.quant.ChartCandle
import com.schwabquant.app.core.quant.ChartIndicatorBundle
import com.schwabquant.app.core.quant.IndicatorPoint
import com.schwabquant.app.presentation.theme.AppThemeType
import com.schwabquant.app.presentation.theme.SchwabQuantPalettes
import kotlinx.serialization.Serializable

enum class RsiPaneMode(val label: String) {
    OFF("RSI Off"),
    RSI_14("RSI(14)"),
    RSI_2("RSI(2)")
}

data class ChartOverlayToggles(
    val ema9: Boolean = true,
    val ema21: Boolean = true,
    val sma50: Boolean = false,
    val sma200: Boolean = false,
    val rma: Boolean = false,
    val vwap: Boolean = true,
    val rsiMode: RsiPaneMode = RsiPaneMode.RSI_14
)

@Serializable
data class ChartThemePayload(
    val backgroundColor: String,
    val gridColor: String,
    val textColor: String,
    val borderColor: String,
    val upColor: String,
    val downColor: String,
    val accentColor: String
)

@Serializable
data class JsCandleBar(
    val time: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double
)

@Serializable
data class JsVolumeBar(
    val time: Long,
    val value: Double,
    val color: String
)

@Serializable
data class JsLinePoint(
    val time: Long,
    val value: Double
)

@Serializable
data class JsPriceLine(
    val price: Double,
    val color: String,
    val lineWidth: Int = 1,
    val dashed: Boolean = true,
    val title: String
)

@Serializable
data class ChartDataPayload(
    val candles: List<JsCandleBar>,
    val volume: List<JsVolumeBar>,
    val ema9: List<JsLinePoint> = emptyList(),
    val ema21: List<JsLinePoint> = emptyList(),
    val sma50: List<JsLinePoint> = emptyList(),
    val sma200: List<JsLinePoint> = emptyList(),
    val rmaFast: List<JsLinePoint> = emptyList(),
    val rmaSlow: List<JsLinePoint> = emptyList(),
    val vwap: List<JsLinePoint> = emptyList(),
    val rsi: List<JsLinePoint> = emptyList(),
    val rsiMode: String = RsiPaneMode.OFF.name,
    val fitContent: Boolean = true
)

@Serializable
data class JsLiveBarUpdate(
    val candle: JsCandleBar,
    val volume: JsVolumeBar
)

@Serializable
data class CrosshairTelemetry(
    val epochSeconds: Long? = null,
    val open: Double? = null,
    val high: Double? = null,
    val low: Double? = null,
    val close: Double? = null,
    val volume: Long? = null,
    val ema9: Double? = null,
    val ema21: Double? = null,
    val sma50: Double? = null,
    val sma200: Double? = null,
    val rmaFast: Double? = null,
    val rmaSlow: Double? = null,
    val vwap: Double? = null,
    val rsi: Double? = null,
    val active: Boolean = false
)

object ChartThemeMapper {

    fun fromAppTheme(themeType: AppThemeType): ChartThemePayload {
        val scheme = SchwabQuantPalettes.colorSchemeFor(themeType)
        val ext = SchwabQuantPalettes.extendedColorsFor(themeType)
        return ChartThemePayload(
            backgroundColor = scheme.background.toHexRgb(),
            gridColor = scheme.surfaceVariant.toHexRgb(),
            textColor = scheme.onSurfaceVariant.toHexRgb(),
            borderColor = ext.borderSubtle.toHexRgb(),
            upColor = ext.profit.toHexRgb(),
            downColor = ext.loss.toHexRgb(),
            accentColor = scheme.primary.toHexRgb()
        )
    }

    fun buildChartPayload(
        candles: List<ChartCandle>,
        indicators: ChartIndicatorBundle,
        toggles: ChartOverlayToggles,
        theme: ChartThemePayload,
        fitContent: Boolean
    ): ChartDataPayload {
        val upVol = theme.upColor + "66"
        val downVol = theme.downColor + "66"
        val jsCandles = candles.map { bar ->
            JsCandleBar(
                time = bar.epochSeconds,
                open = bar.open,
                high = bar.high,
                low = bar.low,
                close = bar.close
            )
        }
        val jsVolume = candles.map { bar ->
            JsVolumeBar(
                time = bar.epochSeconds,
                value = bar.volume.toDouble(),
                color = if (bar.close >= bar.open) upVol else downVol
            )
        }
        val rsiPoints = when (toggles.rsiMode) {
            RsiPaneMode.OFF -> emptyList()
            RsiPaneMode.RSI_14 -> indicators.rsi14.toJsPoints()
            RsiPaneMode.RSI_2 -> indicators.rsi2.toJsPoints()
        }
        return ChartDataPayload(
            candles = jsCandles,
            volume = jsVolume,
            ema9 = if (toggles.ema9) indicators.ema9.toJsPoints() else emptyList(),
            ema21 = if (toggles.ema21) indicators.ema21.toJsPoints() else emptyList(),
            sma50 = if (toggles.sma50) indicators.sma50.toJsPoints() else emptyList(),
            sma200 = if (toggles.sma200) indicators.sma200.toJsPoints() else emptyList(),
            rmaFast = if (toggles.rma) indicators.rmaFast9.toJsPoints() else emptyList(),
            rmaSlow = if (toggles.rma) indicators.rmaSlow21.toJsPoints() else emptyList(),
            vwap = if (toggles.vwap) indicators.sessionVwap.toJsPoints() else emptyList(),
            rsi = rsiPoints,
            rsiMode = toggles.rsiMode.name,
            fitContent = fitContent
        )
    }

    fun buildLiveBarUpdate(
        candle: ChartCandle,
        theme: ChartThemePayload
    ): JsLiveBarUpdate {
        val color = if (candle.close >= candle.open) theme.upColor + "66" else theme.downColor + "66"
        return JsLiveBarUpdate(
            candle = JsCandleBar(
                time = candle.epochSeconds,
                open = candle.open,
                high = candle.high,
                low = candle.low,
                close = candle.close
            ),
            volume = JsVolumeBar(
                time = candle.epochSeconds,
                value = candle.volume.toDouble(),
                color = color
            )
        )
    }

    private fun List<IndicatorPoint>.toJsPoints(): List<JsLinePoint> =
        map { JsLinePoint(time = it.epochSeconds, value = it.value) }

    fun Color.toHexRgb(): String =
        String.format("#%06X", 0xFFFFFF and toArgb())
}
