package com.schwabquant.app.data.remote.streamer

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StreamerModelsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    @Test
    fun `streamer field indices match SCHWAB_API_REFERENCE Section 5`() {
        assertEquals(1, LevelOneEquityFields.BID_PRICE)
        assertEquals(2, LevelOneEquityFields.ASK_PRICE)
        assertEquals(33, LevelOneEquityFields.MARK_PRICE)

        assertEquals(2, LevelOneOptionFields.BID_PRICE)
        assertEquals(28, LevelOneOptionFields.DELTA)
        assertEquals(37, LevelOneOptionFields.MARK_PRICE)

        assertEquals("0,1,2,3", AccountActivityFields.FIELDS_CSV)
        assertEquals(12, StreamerErrorCode.CLOSE_CONNECTION)
    }

    @Test
    fun `serializes ADMIN LOGIN request envelope matching Schwab schema`() {
        val envelope = StreamerLoginRequestFactory.create(
            requestId = "1",
            schwabClientCustomerId = "cust-123",
            schwabClientCorrelId = "correl-456",
            accessToken = "token-abc",
            schwabClientChannel = "IO",
            schwabClientFunctionId = "APIAPP"
        )
        val payload = json.encodeToString(envelope)
        assertTrue(payload.contains("\"service\":\"ADMIN\""))
        assertTrue(payload.contains("\"command\":\"LOGIN\""))
        assertTrue(payload.contains("\"SchwabClientCustomerId\":\"cust-123\""))
        assertTrue(payload.contains("\"SchwabClientCorrelId\":\"correl-456\""))
    }
}
