package com.schwabquant.app.core.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OAuthCallbackParserTest {

    @Test
    fun `decodes full 127_0_0_1 callback URL and percent-decodes encoded at-sign`() {
        val rawUrl = "https://127.0.0.1/?code=C0.b2F1dGgyLmJkYy5zY2h3YWI%40&session=xyz-123"
        val result = OAuthCallbackParser.parseAuthorizationCode(rawUrl)

        assertTrue(result.isSuccess)
        assertEquals("C0.b2F1dGgyLmJkYy5zY2h3YWI@", result.getOrThrow())
    }

    @Test
    fun `preserves literal plus signs inside percent-decoded code`() {
        val rawUrl = "https://127.0.0.1/?code=C0.abc+def%40&session=s1"
        val result = OAuthCallbackParser.parseAuthorizationCode(rawUrl)

        assertTrue(result.isSuccess)
        assertEquals("C0.abc+def@", result.getOrThrow())
    }

    @Test
    fun `decodes custom scheme callback URL`() {
        val rawUrl = "schwabquant://oauth/callback?code=C0.custom%40&state=ok"
        val result = OAuthCallbackParser.parseAuthorizationCode(rawUrl)

        assertTrue(result.isSuccess)
        assertEquals("C0.custom@", result.getOrThrow())
    }

    @Test
    fun `accepts raw authorization code directly`() {
        val rawEncoded = OAuthCallbackParser.parseAuthorizationCode("C0.rawcode%40")
        assertTrue(rawEncoded.isSuccess)
        assertEquals("C0.rawcode@", rawEncoded.getOrThrow())

        val rawPlain = OAuthCallbackParser.parseAuthorizationCode("C0.rawcode@")
        assertTrue(rawPlain.isSuccess)
        assertEquals("C0.rawcode@", rawPlain.getOrThrow())
    }

    @Test
    fun `fails cleanly on error redirect, missing code, blank input, and malformed URL`() {
        val denied = OAuthCallbackParser.parseAuthorizationCode(
            "https://127.0.0.1/?error=access_denied&error_description=User+cancelled"
        )
        assertTrue(denied.isFailure)

        val missingCode = OAuthCallbackParser.parseAuthorizationCode("https://127.0.0.1/?session=only")
        assertTrue(missingCode.isFailure)

        val blank = OAuthCallbackParser.parseAuthorizationCode("   ")
        assertTrue(blank.isFailure)

        val malformed = OAuthCallbackParser.parseAuthorizationCode("https://127.0.0.1/?code=bad code")
        assertTrue(malformed.isFailure)

        assertFalse(OAuthCallbackParser.looksLikeSchwabCallback("https://127.0.0.1/?session=only"))
        assertTrue(OAuthCallbackParser.looksLikeSchwabCallback("https://127.0.0.1/?code=C0.abc%40"))
    }
}
