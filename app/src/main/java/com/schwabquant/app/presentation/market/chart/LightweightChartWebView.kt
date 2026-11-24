package com.schwabquant.app.presentation.market.chart

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.Keep
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayInputStream

private const val APP_ASSETS_HOST = "appassets.androidplatform.net"
private const val CHART_HOST_URL = "https://appassets.androidplatform.net/assets/charts/chart_host.html"
private const val BRIDGE_NAME = "SchwabQuantChartBridge"

private val ChartBridgeJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

@Keep
class SchwabQuantChartBridge(
    private val onReady: () -> Unit,
    private val onCrosshair: (CrosshairTelemetry) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @Keep
    @JavascriptInterface
    fun onChartReady() {
        mainHandler.post { onReady() }
    }

    @Keep
    @JavascriptInterface
    fun onCrosshairMove(payloadJson: String?) {
        if (payloadJson.isNullOrBlank()) return
        val parsed = runCatching {
            ChartBridgeJson.decodeFromString(CrosshairTelemetry.serializer(), payloadJson)
        }.getOrNull() ?: return
        mainHandler.post { onCrosshair(parsed) }
    }
}

/**
 * Security-hardened AndroidView hosting the completely offline TradingView Lightweight Charts
 * v4.2.3 bundle via WebViewAssetLoader (`appassets.androidplatform.net`).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LightweightChartWebView(
    themePayload: ChartThemePayload,
    chartDataPayload: ChartDataPayload?,
    priceLines: List<JsPriceLine>,
    liveBarUpdate: JsLiveBarUpdate?,
    onCrosshairTelemetry: (CrosshairTelemetry) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isChartReady by remember { mutableStateOf(false) }
    val latestCrosshairCallback by rememberUpdatedState(onCrosshairTelemetry)

    val assetLoader = remember(context) {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
    }

    val bridge = remember {
        SchwabQuantChartBridge(
            onReady = { isChartReady = true },
            onCrosshair = { telemetry -> latestCrosshairCallback(telemetry) }
        )
    }

    val webView = remember(context, assetLoader, bridge) {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false

            settings.apply {
                javaScriptEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                domStorageEnabled = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                builtInZoomControls = false
                displayZoomControls = false
                setSupportZoom(false)
            }

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse {
                    val uri = request?.url ?: return blockedResponse()
                    if (!uri.scheme.equals("https", ignoreCase = true) ||
                        !uri.host.equals(APP_ASSETS_HOST, ignoreCase = true) ||
                        uri.path?.startsWith("/assets/") != true
                    ) {
                        return blockedResponse()
                    }
                    return assetLoader.shouldInterceptRequest(uri) ?: blockedResponse()
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val uri = request?.url ?: return true
                    val allowed = uri.scheme.equals("https", ignoreCase = true) &&
                        uri.host.equals(APP_ASSETS_HOST, ignoreCase = true) &&
                        uri.path?.startsWith("/assets/") == true
                    return !allowed
                }

                private fun blockedResponse(): WebResourceResponse =
                    WebResourceResponse(
                        "text/plain",
                        "utf-8",
                        403,
                        "Forbidden",
                        emptyMap(),
                        ByteArrayInputStream(ByteArray(0))
                    )
            }

            addJavascriptInterface(bridge, BRIDGE_NAME)
            loadUrl(CHART_HOST_URL)
        }
    }

    DisposableEffect(webView) {
        val layoutListener = View.OnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            if (isChartReady && (right - left) > 0 && (bottom - top) > 0) {
                webView.evaluateJavascript("window.dispatchEvent(new Event('resize'));", null)
            }
        }
        webView.addOnLayoutChangeListener(layoutListener)
        onDispose {
            isChartReady = false
            runCatching {
                webView.removeOnLayoutChangeListener(layoutListener)
                webView.removeJavascriptInterface(BRIDGE_NAME)
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.destroy()
            }
        }
    }

    LaunchedEffect(isChartReady) {
        if (isChartReady && webView.width > 0 && webView.height > 0) {
            webView.evaluateJavascript("window.dispatchEvent(new Event('resize'));", null)
        }
    }

    LaunchedEffect(isChartReady, themePayload) {
        if (!isChartReady) return@LaunchedEffect
        val json = ChartBridgeJson.encodeToString(ChartThemePayload.serializer(), themePayload)
        webView.evaluateJavascript("window.sqApplyTheme(${jsStringLiteral(json)});", null)
    }

    LaunchedEffect(isChartReady, chartDataPayload) {
        if (!isChartReady || chartDataPayload == null) return@LaunchedEffect
        val json = ChartBridgeJson.encodeToString(ChartDataPayload.serializer(), chartDataPayload)
        webView.evaluateJavascript("window.sqSetChartData(${jsStringLiteral(json)});", null)
    }

    LaunchedEffect(isChartReady, chartDataPayload, priceLines) {
        if (!isChartReady) return@LaunchedEffect
        val json = ChartBridgeJson.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(JsPriceLine.serializer()),
            priceLines
        )
        webView.evaluateJavascript("window.sqSetPriceLines(${jsStringLiteral(json)});", null)
    }

    LaunchedEffect(isChartReady, liveBarUpdate) {
        if (!isChartReady || liveBarUpdate == null) return@LaunchedEffect
        val json = ChartBridgeJson.encodeToString(JsLiveBarUpdate.serializer(), liveBarUpdate)
        webView.evaluateJavascript("window.sqUpdateLiveBar(${jsStringLiteral(json)});", null)
    }

    AndroidView(
        factory = { webView },
        modifier = modifier
    )
}

private fun jsStringLiteral(rawJson: String): String =
    ChartBridgeJson.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(rawJson))
