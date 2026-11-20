package com.schwabquant.app.data.remote.streamer

import com.schwabquant.app.data.remote.dto.SchwabSymbolNormalizer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Schwab WebSocket Streamer v1 protocol models and verified field indices
 * strictly matching SCHWAB_API_REFERENCE.md Section 5.
 */
object StreamerService {
    const val ADMIN = "ADMIN"
    const val LEVELONE_EQUITIES = "LEVELONE_EQUITIES"
    const val LEVELONE_OPTIONS = "LEVELONE_OPTIONS"
    const val ACCT_ACTIVITY = "ACCT_ACTIVITY"
}

object StreamerCommand {
    const val LOGIN = "LOGIN"
    const val SUBS = "SUBS"
    const val ADD = "ADD"
    const val UNSUBS = "UNSUBS"
    const val LOGOUT = "LOGOUT"
}

object StreamerErrorCode {
    /** Triggered when a second WebSocket connection is opened concurrently (MAX 1 per user). */
    const val CLOSE_CONNECTION = 12
}

object LevelOneEquityFields {
    const val SYMBOL = 0
    const val BID_PRICE = 1 // Use for Long Stop-Loss evaluation
    const val ASK_PRICE = 2 // Use for Short Cover evaluation
    const val LAST_PRICE = 3
    const val BID_SIZE = 4
    const val ASK_SIZE = 5
    const val TOTAL_VOLUME = 8
    const val LAST_SIZE = 9
    const val SECURITY_STATUS = 32
    const val MARK_PRICE = 33
    const val QUOTE_TIME = 34
    const val TRADE_TIME = 35

    val DEFAULT_FIELDS_CSV: String = listOf(
        SYMBOL,
        BID_PRICE,
        ASK_PRICE,
        LAST_PRICE,
        BID_SIZE,
        ASK_SIZE,
        TOTAL_VOLUME,
        LAST_SIZE,
        SECURITY_STATUS,
        MARK_PRICE,
        QUOTE_TIME,
        TRADE_TIME
    ).joinToString(",")
}

object LevelOneOptionFields {
    // Note: Option field 1 is Description, NOT Bid Price.
    const val SYMBOL = 0
    const val BID_PRICE = 2
    const val ASK_PRICE = 3
    const val LAST_PRICE = 4
    const val OPEN_INTEREST = 9
    const val IMPLIED_VOLATILITY = 10
    const val DELTA = 28
    const val GAMMA = 29
    const val THETA = 30
    const val VEGA = 31
    const val RHO = 32
    const val SECURITY_STATUS = 33
    const val UNDERLYING_PRICE = 35
    const val MARK_PRICE = 37
    const val QUOTE_TIME = 38

    val DEFAULT_FIELDS_CSV: String = listOf(
        SYMBOL,
        BID_PRICE,
        ASK_PRICE,
        LAST_PRICE,
        OPEN_INTEREST,
        IMPLIED_VOLATILITY,
        DELTA,
        GAMMA,
        THETA,
        VEGA,
        RHO,
        SECURITY_STATUS,
        UNDERLYING_PRICE,
        MARK_PRICE,
        QUOTE_TIME
    ).joinToString(",")
}

object AccountActivityFields {
    const val KEYS = "Account Activity"
    const val FIELDS_CSV = "0,1,2,3"

    const val SUBSCRIPTION_KEY = 0
    const val ACCOUNT_NUMBER = 1
    const val MESSAGE_TYPE = 2
    const val MESSAGE_DATA = 3

    const val MSG_ORDER_ACCEPTED = "OrderAccepted"
    const val MSG_ORDER_FILL = "OrderFill"
    const val MSG_ORDER_CANCEL_REQUEST = "OrderCancelRequest"
    const val MSG_UROUT = "UROUT"
    const val MSG_ORDER_REJECTION = "OrderRejection"
}

@Serializable
data class StreamerEnvelopeRequest(
    val requests: List<StreamerRequestItem>
)

@Serializable
data class StreamerRequestItem(
    @SerialName("requestid") val requestId: String,
    val service: String,
    val command: String,
    @SerialName("SchwabClientCustomerId") val schwabClientCustomerId: String,
    @SerialName("SchwabClientCorrelId") val schwabClientCorrelId: String,
    val parameters: Map<String, String> = emptyMap()
)

object StreamerLoginRequestFactory {
    fun create(
        requestId: String = "1",
        schwabClientCustomerId: String,
        schwabClientCorrelId: String,
        accessToken: String,
        schwabClientChannel: String,
        schwabClientFunctionId: String
    ): StreamerEnvelopeRequest = StreamerEnvelopeRequest(
        requests = listOf(
            StreamerRequestItem(
                requestId = requestId,
                service = StreamerService.ADMIN,
                command = StreamerCommand.LOGIN,
                schwabClientCustomerId = schwabClientCustomerId,
                schwabClientCorrelId = schwabClientCorrelId,
                parameters = mapOf(
                    "Authorization" to accessToken,
                    "SchwabClientChannel" to schwabClientChannel,
                    "SchwabClientFunctionId" to schwabClientFunctionId
                )
            )
        )
    )
}

object StreamerRequestFactory {
    fun createLogout(
        requestId: String,
        schwabClientCustomerId: String,
        schwabClientCorrelId: String
    ): StreamerEnvelopeRequest = StreamerEnvelopeRequest(
        requests = listOf(
            StreamerRequestItem(
                requestId = requestId,
                service = StreamerService.ADMIN,
                command = StreamerCommand.LOGOUT,
                schwabClientCustomerId = schwabClientCustomerId,
                schwabClientCorrelId = schwabClientCorrelId,
                parameters = emptyMap()
            )
        )
    )

    /**
     * Normalizes share-class symbols (`BRK.B` -> `BRK/B`) before emitting LEVELONE_EQUITIES frames.
     */
    fun createEquitySubscription(
        requestId: String,
        command: String,
        schwabClientCustomerId: String,
        schwabClientCorrelId: String,
        symbols: List<String>
    ): StreamerEnvelopeRequest {
        val apiKeys = symbols
            .map { SchwabSymbolNormalizer.toSchwabMarketDataSymbol(it) }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(",")
        val params = if (command == StreamerCommand.UNSUBS) {
            mapOf("keys" to apiKeys)
        } else {
            mapOf(
                "keys" to apiKeys,
                "fields" to LevelOneEquityFields.DEFAULT_FIELDS_CSV
            )
        }
        return StreamerEnvelopeRequest(
            requests = listOf(
                StreamerRequestItem(
                    requestId = requestId,
                    service = StreamerService.LEVELONE_EQUITIES,
                    command = command,
                    schwabClientCustomerId = schwabClientCustomerId,
                    schwabClientCorrelId = schwabClientCorrelId,
                    parameters = params
                )
            )
        )
    }

    fun createAccountActivitySubscription(
        requestId: String,
        schwabClientCustomerId: String,
        schwabClientCorrelId: String
    ): StreamerEnvelopeRequest = StreamerEnvelopeRequest(
        requests = listOf(
            StreamerRequestItem(
                requestId = requestId,
                service = StreamerService.ACCT_ACTIVITY,
                command = StreamerCommand.SUBS,
                schwabClientCustomerId = schwabClientCustomerId,
                schwabClientCorrelId = schwabClientCorrelId,
                parameters = mapOf(
                    "keys" to AccountActivityFields.KEYS,
                    "fields" to AccountActivityFields.FIELDS_CSV
                )
            )
        )
    )
}

enum class StreamerConnectionState {
    DISCONNECTED,
    CONNECTING,
    AUTHENTICATED,
    RECONNECTING,
    SIMULATED,
    ERROR_CONFLICT_CLOSE_12,
    ERROR
}

/**
 * Full merged Level-1 Equity quote snapshot maintained across Schwab partial-update delta frames.
 */
data class LiveEquityQuote(
    val symbol: String,
    val bidPrice: Double? = null,
    val askPrice: Double? = null,
    val lastPrice: Double? = null,
    val bidSize: Long? = null,
    val askSize: Long? = null,
    val totalVolume: Long? = null,
    val lastSize: Long? = null,
    val securityStatus: String? = null,
    val markPrice: Double? = null,
    val quoteTimeMs: Long? = null,
    val tradeTimeMs: Long? = null,
    val receivedAtEpochMs: Long = 0L,
    val hasFreshBidUpdate: Boolean = true,
    val hasFreshAskUpdate: Boolean = true
) {
    val hasFreshBidAskUpdate: Boolean
        get() = hasFreshBidUpdate || hasFreshAskUpdate
    val midPrice: Double?
        get() = when {
            bidPrice != null && askPrice != null && bidPrice > 0.0 && askPrice >= bidPrice ->
                (bidPrice + askPrice) * 0.5
            markPrice != null && markPrice > 0.0 -> markPrice
            lastPrice != null && lastPrice > 0.0 -> lastPrice
            else -> null
        }

    val spreadPct: Double?
        get() {
            val bid = bidPrice ?: return null
            val ask = askPrice ?: return null
            if (bid <= 0.0 || ask < bid) return null
            val mid = (bid + ask) * 0.5
            return if (mid > 0.0) ((ask - bid) / mid) * 100.0 else null
        }
}

data class EquityStreamTick(
    val symbol: String,
    val bidPrice: Double? = null,
    val askPrice: Double? = null,
    val lastPrice: Double? = null,
    val markPrice: Double? = null,
    val securityStatus: String? = null,
    val quoteTimeMs: Long? = null
)

data class OptionStreamTick(
    val symbol: String,
    val bidPrice: Double? = null,
    val askPrice: Double? = null,
    val lastPrice: Double? = null,
    val markPrice: Double? = null,
    val delta: Double? = null,
    val gamma: Double? = null,
    val theta: Double? = null,
    val vega: Double? = null,
    val impliedVolatility: Double? = null,
    val quoteTimeMs: Long? = null
)

data class AccountActivityEvent(
    val subscriptionKey: String?,
    val accountNumber: String?,
    val messageType: String?,
    val messageDataJson: String?,
    val orderId: String? = null
) {
    val isOrderFill: Boolean
        get() = messageType.equals(AccountActivityFields.MSG_ORDER_FILL, ignoreCase = true)
}
