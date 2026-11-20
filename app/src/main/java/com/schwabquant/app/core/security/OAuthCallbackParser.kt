package com.schwabquant.app.core.security

import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.charset.StandardCharsets

/**
 * Pure-JVM OAuth callback parser accepting either a full Schwab redirect URL
 * (e.g. `https://127.0.0.1/?code=C0.abc%40&session=...`) or a raw authorization code,
 * safely percent-decoding `%40` -> `@` without mangling `+` characters.
 */
object OAuthCallbackParser {

    fun parseAuthorizationCode(rawInput: String): Result<String> {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("Authorization code or redirect URL is empty."))
        }
        if (trimmed.any { it.isWhitespace() }) {
            return Result.failure(IllegalArgumentException("Redirect URL or code cannot contain whitespace."))
        }

        val isUrlOrQuery = trimmed.contains("://") || trimmed.contains("?") || trimmed.startsWith("code=")
        if (!isUrlOrQuery) {
            if (trimmed.startsWith("http", ignoreCase = true) || trimmed.contains("/")) {
                return Result.failure(
                    IllegalArgumentException("Malformed redirect URL: missing query '?code=' parameter.")
                )
            }
            return runCatching { percentDecodeUtf8(trimmed) }
        }

        val query = extractQueryString(trimmed).getOrElse { return Result.failure(it) }
        val params = parseQueryParams(query)

        val error = params["error"]
        if (!error.isNullOrBlank()) {
            val desc = params["error_description"]?.let { ": $it" }.orEmpty()
            return Result.failure(IllegalArgumentException("OAuth error '$error'$desc"))
        }

        val code = params["code"]
        if (code.isNullOrBlank()) {
            return Result.failure(
                IllegalArgumentException("Authorization code ('code=') not found in redirect URL.")
            )
        }
        return Result.success(code)
    }

    fun looksLikeSchwabCallback(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return parseAuthorizationCode(text).isSuccess && text.contains("code=")
    }

    private fun extractQueryString(input: String): Result<String> {
        if (input.startsWith("code=")) {
            return Result.success(input)
        }
        val uri = runCatching { URI.create(input) }.getOrElse { err ->
            return Result.failure(IllegalArgumentException("Malformed redirect URL.", err))
        }
        if (uri.scheme.isNullOrBlank()) {
            return Result.failure(IllegalArgumentException("Redirect URL is missing a valid scheme."))
        }
        val rawQuery = uri.rawQuery
        if (rawQuery.isNullOrBlank()) {
            return Result.failure(
                IllegalArgumentException("Redirect URL is missing '?code=' query parameters.")
            )
        }
        return Result.success(rawQuery)
    }

    private fun parseQueryParams(rawQuery: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        rawQuery.split('&').forEach { pair ->
            if (pair.isEmpty()) return@forEach
            val idx = pair.indexOf('=')
            val key = if (idx >= 0) pair.substring(0, idx) else pair
            val rawVal = if (idx >= 0) pair.substring(idx + 1) else ""
            if (key.isNotEmpty()) {
                result[percentDecodeUtf8(key)] = percentDecodeUtf8(rawVal)
            }
        }
        return result
    }

    private fun percentDecodeUtf8(value: String): String {
        val out = ByteArrayOutputStream(value.length)
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '%') {
                require(i + 2 < value.length) { "Incomplete percent-encoding in OAuth value." }
                val hi = Character.digit(value[i + 1], 16)
                val lo = Character.digit(value[i + 2], 16)
                require(hi >= 0 && lo >= 0) { "Invalid hex escape in OAuth value." }
                out.write((hi shl 4) or lo)
                i += 3
            } else {
                val utf8 = ch.toString().toByteArray(StandardCharsets.UTF_8)
                out.write(utf8, 0, utf8.size)
                i++
            }
        }
        return String(out.toByteArray(), StandardCharsets.UTF_8)
    }
}
