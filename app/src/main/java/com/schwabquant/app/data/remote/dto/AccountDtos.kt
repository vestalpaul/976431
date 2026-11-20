package com.schwabquant.app.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class AccountNumberDto(
    val accountNumber: String,
    val hashValue: String
)

@Serializable
data class AccountWrapperDto(
    val securitiesAccount: SecuritiesAccountDto? = null
)

@Serializable
data class SecuritiesAccountDto(
    val accountNumber: String? = null,
    val type: String? = null,
    val roundTrips: Double? = null,
    val isDayTrader: Boolean? = null,
    val positions: List<PositionDto> = emptyList(),
    val currentBalances: BalancesDto? = null,
    val initialBalances: BalancesDto? = null
)

@Serializable
data class BalancesDto(
    val liquidationValue: Double? = null,
    val cashBalance: Double? = null,
    val cashAvailableForTrading: Double? = null,
    val settledFunds: Double? = null,
    val buyingPower: Double? = null,
    val buyingPowerNonMarginableTrade: Double? = null,
    val availableFunds: Double? = null,
    val marginBalance: Double? = null,
    val equity: Double? = null,
    val longMarketValue: Double? = null,
    val shortMarketValue: Double? = null,
    val longOptionMarketValue: Double? = null,
    val shortOptionMarketValue: Double? = null
)

@Serializable
data class PositionDto(
    val shortQuantity: Double? = null,
    val longQuantity: Double? = null,
    val settledLongQuantity: Double? = null,
    val settledShortQuantity: Double? = null,
    val averagePrice: Double? = null,
    val averageLongPrice: Double? = null,
    val averageShortPrice: Double? = null,
    val marketValue: Double? = null,
    val longOpenProfitLoss: Double? = null,
    val shortOpenProfitLoss: Double? = null,
    val currentDayProfitLoss: Double? = null,
    val currentDayProfitLossPercentage: Double? = null,
    val instrument: InstrumentDto? = null
)

@Serializable
data class InstrumentDto(
    val symbol: String? = null,
    val cusip: String? = null,
    val underlyingSymbol: String? = null,
    val assetType: String? = null,
    val description: String? = null,
    val putCall: String? = null,
    val netChange: Double? = null
)

@Serializable
data class UserPreferenceDto(
    val streamerInfo: List<StreamerInfoDto> = emptyList()
)

@Serializable
data class StreamerInfoDto(
    val streamerSocketUrl: String? = null,
    val schwabClientCustomerId: String? = null,
    val schwabClientCorrelId: String? = null,
    val schwabClientChannel: String? = null,
    val schwabClientFunctionId: String? = null
)
