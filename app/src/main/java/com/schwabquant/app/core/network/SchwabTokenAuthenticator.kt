package com.schwabquant.app.core.network

import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single-flight OkHttp Authenticator handling 401 responses via SchwabTokenRefreshCoordinator.
 * Allows strictly 1 retry per failed request chain to prevent infinite 401 loops.
 */
@Singleton
class SchwabTokenAuthenticator @Inject constructor(
    private val refreshCoordinator: SchwabTokenRefreshCoordinator
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.request.url.encodedPath.contains(SchwabAuthInterceptor.OAUTH_TOKEN_PATH, ignoreCase = true)) {
            return null
        }
        if (responseCount(response) >= MAX_AUTH_ATTEMPTS) {
            return null
        }

        val failedToken = response.request.header("Authorization")
            ?.removePrefix("Bearer ")
            ?.trim()

        val freshToken = refreshCoordinator.refreshOnUnauthorized(failedToken) ?: return null
        return response.request.newBuilder()
            .header("Authorization", "Bearer $freshToken")
            .build()
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }

    companion object {
        private const val MAX_AUTH_ATTEMPTS = 2
    }
}
