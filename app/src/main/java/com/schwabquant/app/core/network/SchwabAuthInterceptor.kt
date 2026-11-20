package com.schwabquant.app.core.network

import com.schwabquant.app.core.security.CredentialVault
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enforces Schwab's 120 req/min rate limit, triggers proactive 25-minute token refresh,
 * and attaches `Authorization: Bearer` to Trader/MarketData calls while never touching
 * `/v1/oauth/token` Basic Auth requests.
 */
@Singleton
class SchwabAuthInterceptor @Inject constructor(
    private val credentialVault: CredentialVault,
    private val rateLimiter: TokenBucketRateLimiter,
    private val refreshCoordinator: SchwabTokenRefreshCoordinator
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        if (originalRequest.url.encodedPath.contains(OAUTH_TOKEN_PATH, ignoreCase = true)) {
            return chain.proceed(originalRequest)
        }

        rateLimiter.acquireBlocking()

        val accessToken = refreshCoordinator.refreshIfNeededForProactiveWindow()
            ?: credentialVault.getAccessToken()

        val request = if (!accessToken.isNullOrBlank()) {
            originalRequest.newBuilder()
                .header("Authorization", "Bearer $accessToken")
                .build()
        } else {
            originalRequest
        }
        return chain.proceed(request)
    }

    companion object {
        internal const val OAUTH_TOKEN_PATH = "/v1/oauth/token"
    }
}
