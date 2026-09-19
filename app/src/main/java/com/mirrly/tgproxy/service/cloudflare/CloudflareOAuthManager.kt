/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.mirrly.tgproxy.service.cloudflare

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit

data class CloudflareOAuthTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresInSeconds: Long,
    val tokenType: String = "Bearer"
)

sealed class OAuthResult {
    data class Success(val tokens: CloudflareOAuthTokens) : OAuthResult()
    data class Error(val message: String) : OAuthResult()
    object Cancelled : OAuthResult()
}

/**
 * Detailed state machine for local HTTP authorization server and web portal.
 */
sealed class ServerAuthState {
    object Idle : ServerAuthState()
    object StartingServer : ServerAuthState()
    object VerifyingHealth : ServerAuthState()
    data class ServerReady(val portalUrl: String) : ServerAuthState()
    data class WaitingCallback(val portalUrl: String, val authUrl: String = "") : ServerAuthState()
    object ExchangingTokens : ServerAuthState()
    data class Success(val tokens: CloudflareOAuthTokens) : ServerAuthState()
    data class Error(val message: String, val isPortBusy: Boolean = false) : ServerAuthState()
}

object CloudflareOAuthManager {

    // Official Wrangler Client ID used by Cloudflare for Workers CLI and SDKs
    const val WRANGLER_CLIENT_ID = "54d11594-84e4-41aa-b438-e81b8fa78ee7"
    const val CALLBACK_PORT = 8976
    const val REDIRECT_URI = "http://localhost:$CALLBACK_PORT/oauth/callback"

    private const val AUTH_URL = "https://dash.cloudflare.com/oauth2/auth"
    private const val TOKEN_URL = "https://dash.cloudflare.com/oauth2/token"

    private const val OAUTH_SCOPES = "account:read user:read workers:write workers_kv:write " +
            "workers_routes:write workers_scripts:write workers_tail:read offline_access"

    // Dedicated supervisor scope ensuring background persistence during browser interactions
    private val authScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _authState = MutableStateFlow<ServerAuthState>(ServerAuthState.Idle)
    val authState: StateFlow<ServerAuthState> = _authState.asStateFlow()

    // HTTP communication handled via CloudflareNetworkClient

    private var activeServerSocket: ServerSocket? = null
    private var serverJob: Job? = null

    // Active session parameters
    @Volatile private var activeCodeVerifier: String? = null
    @Volatile private var activeExpectedState: String? = null
    @Volatile private var activeAuthUrl: String? = null

    /**
     * Prepares PKCE parameters and generates authorization URL to open in browser.
     */
    fun buildAuthorizationData(): Triple<String, String, String> {
        val codeVerifier = generateCodeVerifier()
        val codeChallenge = generateCodeChallenge(codeVerifier)
        val state = UUID.randomUUID().toString()

        val params = listOf(
            "response_type" to "code",
            "client_id" to WRANGLER_CLIENT_ID,
            "redirect_uri" to REDIRECT_URI,
            "scope" to OAUTH_SCOPES,
            "state" to state,
            "code_challenge" to codeChallenge,
            "code_challenge_method" to "S256"
        ).joinToString("&") { (k, v) ->
            "${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}"
        }

        val authUrl = "$AUTH_URL?$params"
        return Triple(authUrl, codeVerifier, state)
    }

    /**
     * Initiates the full authorization workflow:
     * 1. Starts local loopback server with IPv4 + IPv6 dual-stack support on port 8976.
     * 2. Runs loopback self-check ping (/health) to confirm port is listening.
     * 3. Prepares PKCE parameters and opens browser to the local portal http://localhost:8976/.
     */
    fun startAuthSession(context: Context) {
        authScope.launch {
            _authState.value = ServerAuthState.StartingServer

            // Step 1: Clean up any prior socket
            stopServerInternal()

            // Step 2: Bind local dual-stack server socket
            val socketBound = startLocalServer()
            if (!socketBound) {
                // If binding failed, verify if it was already responding on port 8976
                val isAlreadyOurServer = probeHealthCheck()
                if (isAlreadyOurServer) {
                    // Our server is already healthy, continue
                } else {
                    _authState.value = ServerAuthState.Error(
                        "Порт $CALLBACK_PORT занят другим приложением. Освободите порт или перезапустите устройство.",
                        isPortBusy = true
                    )
                    return@launch
                }
            }

            // Step 3: Health check verification
            _authState.value = ServerAuthState.VerifyingHealth
            var isHealthy = false
            for (attempt in 1..8) {
                delay(150)
                if (probeHealthCheck()) {
                    isHealthy = true
                    break
                }
            }

            if (!isHealthy) {
                _authState.value = ServerAuthState.Error(
                    "Локальный шлюз не ответил на проверку готовности (127.0.0.1:$CALLBACK_PORT)."
                )
                stopServerInternal()
                return@launch
            }

            // Step 4: Prepare OAuth session parameters
            val (authUrl, verifier, state) = buildAuthorizationData()
            activeAuthUrl = authUrl
            activeCodeVerifier = verifier
            activeExpectedState = state

            val portalUrl = "http://localhost:$CALLBACK_PORT/"
            _authState.value = ServerAuthState.ServerReady(portalUrl)

            // Step 5: Launch browser directly to Cloudflare authorization page
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                _authState.value = ServerAuthState.WaitingCallback(portalUrl, authUrl)
            } catch (e: Exception) {
                _authState.value = ServerAuthState.Error("Не удалось открыть браузер: ${e.message}")
            }
        }
    }

    /**
     * Binds ServerSocket with SO_REUSEADDR and dual-stack loopback acceptance.
     */
    private fun startLocalServer(): Boolean {
        return try {
            val server = ServerSocket().apply {
                reuseAddress = true
                // Binding to wildcard port 8976 supports both IPv4 (127.0.0.1) and IPv6 (::1) on Android
                bind(InetSocketAddress(CALLBACK_PORT), 50)
                soTimeout = 0 // Infinite accept timeout; controlled via coroutine cancellation
            }
            activeServerSocket = server

            serverJob = authScope.launch {
                while (isActive && !server.isClosed) {
                    val client = try {
                        server.accept()
                    } catch (_: Exception) {
                        break
                    }

                    // Handle each incoming connection concurrently
                    launch {
                        handleClientConnection(client)
                    }
                }
            }
            true
        } catch (e: BindException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Dispatches incoming HTTP requests to handlers with loopback security validation.
     */
    private suspend fun handleClientConnection(client: Socket) = withContext(Dispatchers.IO) {
        try {
            client.soTimeout = 8000
            val clientAddress = client.inetAddress

            // Security: Strictly allow loopback connections only (IPv4, IPv6, and IPv4-mapped IPv6)
            if (!CloudflareNetworkClient.isLoopbackAddress(clientAddress)) {
                sendHttpResponse(client, 403, "Forbidden", "text/plain; charset=utf-8", "Access denied: local loopback only.")
                return@withContext
            }

            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return@withContext

            // Drain headers
            var headerLine: String?
            while (true) {
                headerLine = reader.readLine()
                if (headerLine.isNullOrBlank()) break
            }

            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: "GET"
            val path = parts.getOrNull(1) ?: "/"

            when {
                path == "/health" -> {
                    val json = "{\"status\":\"ok\",\"app\":\"mirrly\",\"port\":$CALLBACK_PORT}"
                    sendHttpResponse(client, 200, "OK", "application/json; charset=utf-8", json)
                }

                path == "/favicon.ico" -> {
                    sendHttpResponse(client, 204, "No Content", "image/x-icon", "")
                }

                path == "/" || path.startsWith("/?") -> {
                    val currentUrl = activeAuthUrl ?: ""
                    val html = createPortalLandingHtml(currentUrl)
                    sendHttpResponse(client, 200, "OK", "text/html; charset=utf-8", html)
                }

                path.startsWith("/oauth/callback") -> {
                    handleOAuthCallback(client, path)
                }

                else -> {
                    sendHttpResponse(client, 404, "Not Found", "text/plain; charset=utf-8", "Endpoint not found.")
                }
            }
        } catch (_: Exception) {
            // Socket or connection error
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    /**
     * Handles the OAuth callback from dash.cloudflare.com.
     */
    private suspend fun handleOAuthCallback(client: Socket, path: String) {
        val queryPart = if (path.contains("?")) path.substringAfter("?") else ""
        val queryParams = queryPart.split("&").mapNotNull { pair ->
            val idx = pair.indexOf("=")
            if (idx != -1) {
                val key = try { java.net.URLDecoder.decode(pair.substring(0, idx), "UTF-8") } catch (_: Exception) { pair.substring(0, idx) }
                val value = try { java.net.URLDecoder.decode(pair.substring(idx + 1), "UTF-8") } catch (_: Exception) { pair.substring(idx + 1) }
                key to value
            } else null
        }.toMap()

        val returnedState = queryParams["state"]
        val authCode = queryParams["code"]
        val errorParam = queryParams["error"]
        val errorDescription = queryParams["error_description"]

        val expectedState = activeExpectedState
        val verifier = activeCodeVerifier

        // Error check from Cloudflare
        if (!errorParam.isNullOrBlank() || authCode.isNullOrBlank()) {
            val errText = errorDescription ?: errorParam ?: "Неизвестная ошибка авторизации Cloudflare"
            _authState.value = ServerAuthState.Error("Cloudflare: $errText")
            val html = createErrorHtml(errText)
            sendHttpResponse(client, 400, "Bad Request", "text/html; charset=utf-8", html)
            return
        }

        // CSRF state validation
        if (expectedState != null && returnedState != expectedState) {
            val errText = "Ошибка валидации state (CSRF проверка не пройдена)"
            _authState.value = ServerAuthState.Error(errText)
            val html = createErrorHtml(errText)
            sendHttpResponse(client, 400, "Bad Request", "text/html; charset=utf-8", html)
            return
        }

        // Token exchange
        _authState.value = ServerAuthState.ExchangingTokens
        val exchangeResult = if (verifier != null) {
            exchangeCodeForTokens(authCode, verifier)
        } else {
            OAuthResult.Error("Утерян верификатор PKCE сессии")
        }

        when (exchangeResult) {
            is OAuthResult.Success -> {
                _authState.value = ServerAuthState.Success(exchangeResult.tokens)
                val html = createSuccessHtml()
                sendHttpResponse(client, 200, "OK", "text/html; charset=utf-8", html)
            }
            is OAuthResult.Error -> {
                _authState.value = ServerAuthState.Error(exchangeResult.message)
                val html = createErrorHtml(exchangeResult.message)
                sendHttpResponse(client, 500, "Internal Server Error", "text/html; charset=utf-8", html)
            }
            OAuthResult.Cancelled -> {
                _authState.value = ServerAuthState.Idle
            }
        }
    }

    /**
     * Sends formatted HTTP response to client socket.
     */
    private fun sendHttpResponse(
        client: Socket,
        statusCode: Int,
        statusText: String,
        contentType: String,
        body: String
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val writer = OutputStreamWriter(client.getOutputStream(), Charsets.UTF_8)
        writer.write("HTTP/1.1 $statusCode $statusText\r\n")
        writer.write("Content-Type: $contentType\r\n")
        writer.write("Content-Length: ${bytes.size}\r\n")
        writer.write("Connection: close\r\n")
        writer.write("Access-Control-Allow-Origin: *\r\n\r\n")
        if (bytes.isNotEmpty()) {
            writer.write(body)
        }
        writer.flush()
    }

    /**
     * Loopback probe to confirm server is alive on port 8976.
     * Tries IPv4 (127.0.0.1) and IPv6 (::1) using zero-proxy client.
     */
    private fun probeHealthCheck(): Boolean {
        val targets = listOf(
            "http://127.0.0.1:$CALLBACK_PORT/health",
            "http://[::1]:$CALLBACK_PORT/health"
        )
        for (targetUrl in targets) {
            try {
                val request = Request.Builder()
                    .url(targetUrl)
                    .get()
                    .build()
                CloudflareNetworkClient.loopbackHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful && response.body?.string()?.contains("mirrly") == true) {
                        return true
                    }
                }
            } catch (_: Exception) {}
        }
        return false
    }

    /**
     * Stops the active local server and cancels coroutine jobs.
     */
    fun cancelActiveListener() {
        stopServerInternal()
        _authState.value = ServerAuthState.Idle
    }

    private fun stopServerInternal() {
        try {
            serverJob?.cancel()
            serverJob = null
            activeServerSocket?.close()
            activeServerSocket = null
        } catch (_: Exception) {}
    }

    /**
     * Legacy callback method kept for backward compatibility with existing tests/callers.
     */
    suspend fun awaitAuthCallbackAndExchange(
        codeVerifier: String,
        expectedState: String,
        timeoutSeconds: Int = 300
    ): OAuthResult = withContext(Dispatchers.IO) {
        activeCodeVerifier = codeVerifier
        activeExpectedState = expectedState

        stopServerInternal()
        if (!startLocalServer()) {
            return@withContext OAuthResult.Error("Порт $CALLBACK_PORT занят другим процессом.")
        }

        val deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L)
        while (isActive && System.currentTimeMillis() < deadline) {
            val state = _authState.value
            if (state is ServerAuthState.Success) {
                return@withContext OAuthResult.Success(state.tokens)
            }
            if (state is ServerAuthState.Error) {
                return@withContext OAuthResult.Error(state.message)
            }
            delay(200)
        }

        OAuthResult.Error("Время ожидания авторизации истекло. Повторите попытку.")
    }

    /**
     * Exchanges authorization code for tokens via Cloudflare OAuth Token endpoint.
     */
    suspend fun exchangeCodeForTokens(
        authCode: String,
        codeVerifier: String
    ): OAuthResult = withContext(Dispatchers.IO) {
        val formBody = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", WRANGLER_CLIENT_ID)
            .add("code_verifier", codeVerifier)
            .add("code", authCode)
            .add("redirect_uri", REDIRECT_URI)
            .build()

        val request = Request.Builder()
            .url(TOKEN_URL)
            .post(formBody)
            .header("Accept", "application/json")
            .build()

        try {
            CloudflareNetworkClient.executeWithFallback(request).use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    val errorMsg = try {
                        val json = JSONObject(bodyStr)
                        json.optString("error_description", json.optString("error", "Ошибка ${response.code}"))
                    } catch (_: Exception) {
                        "Ошибка HTTP ${response.code}"
                    }
                    return@withContext OAuthResult.Error(errorMsg)
                }

                val json = JSONObject(bodyStr)
                val accessToken = json.getString("access_token")
                val refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() }
                val expiresIn = json.optLong("expires_in", 3600L)
                val tokenType = json.optString("token_type", "Bearer")

                OAuthResult.Success(
                    CloudflareOAuthTokens(
                        accessToken = accessToken,
                        refreshToken = refreshToken,
                        expiresInSeconds = expiresIn,
                        tokenType = tokenType
                    )
                )
            }
        } catch (e: Exception) {
            OAuthResult.Error("Не удалось получить токен: ${e.message}")
        }
    }

    /**
     * Refreshes access token using refresh_token.
     */
    suspend fun refreshTokens(refreshToken: String): OAuthResult = withContext(Dispatchers.IO) {
        val formBody = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", WRANGLER_CLIENT_ID)
            .add("refresh_token", refreshToken)
            .build()

        val request = Request.Builder()
            .url(TOKEN_URL)
            .post(formBody)
            .header("Accept", "application/json")
            .build()

        try {
            CloudflareNetworkClient.executeWithFallback(request).use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext OAuthResult.Error("Сессия истекла. Требуется повторный вход.")
                }

                val json = JSONObject(bodyStr)
                val accessToken = json.getString("access_token")
                val newRefreshToken = json.optString("refresh_token", refreshToken)
                val expiresIn = json.optLong("expires_in", 3600L)

                OAuthResult.Success(
                    CloudflareOAuthTokens(
                        accessToken = accessToken,
                        refreshToken = newRefreshToken,
                        expiresInSeconds = expiresIn
                    )
                )
            }
        } catch (e: Exception) {
            OAuthResult.Error("Ошибка обновления токена: ${e.message}")
        }
    }

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    // ============================================================================================
    // AMOLED WEB PORTAL TEMPLATES (HTML / CSS / JS)
    // ============================================================================================

    /**
     * Serves the AMOLED portal landing page on http://localhost:8976/
     */
    private fun createPortalLandingHtml(authUrl: String): String = """
        <!DOCTYPE html>
        <html lang="ru">
        <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
            <meta name="theme-color" content="#000000">
            <title>Mirrly TG Proxy — Авторизация Cloudflare</title>
            <style>
                :root {
                    --bg-amoled: #000000;
                    --card-bg: rgba(14, 16, 22, 0.88);
                    --border-subtle: rgba(255, 255, 255, 0.08);
                    --cf-orange: #F38020;
                    --cf-orange-dark: #D86B12;
                    --accent-green: #00E676;
                    --accent-cyan: #00F5D4;
                    --text-white: #FFFFFF;
                    --text-secondary: #94A3B8;
                    --text-muted: #64748B;
                }
                * {
                    box-sizing: border-box;
                    margin: 0;
                    padding: 0;
                }
                body {
                    background-color: var(--bg-amoled);
                    background-image:
                        radial-gradient(circle at 50% 0%, rgba(243, 128, 32, 0.16) 0%, transparent 60%),
                        radial-gradient(circle at 10% 90%, rgba(0, 230, 118, 0.09) 0%, transparent 50%),
                        radial-gradient(circle at 90% 80%, rgba(0, 245, 212, 0.08) 0%, transparent 50%);
                    color: var(--text-white);
                    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif;
                    min-height: 100vh;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    padding: 24px 16px;
                    overflow-x: hidden;
                }
                .card {
                    position: relative;
                    width: 100%;
                    max-width: 410px;
                    background: var(--card-bg);
                    backdrop-filter: blur(28px) saturate(180%);
                    -webkit-backdrop-filter: blur(28px) saturate(180%);
                    border-radius: 26px;
                    border: 1px solid var(--border-subtle);
                    padding: 32px 24px 26px;
                    box-shadow:
                        0 30px 64px -12px rgba(0, 0, 0, 0.92),
                        0 0 0 1px rgba(255, 255, 255, 0.03),
                        inset 0 1px 0 rgba(255, 255, 255, 0.12);
                    text-align: center;
                    overflow: hidden;
                }
                .card::before {
                    content: '';
                    position: absolute;
                    top: 0;
                    left: 12%;
                    right: 12%;
                    height: 1.5px;
                    background: linear-gradient(90deg, transparent, rgba(243, 128, 32, 0.9), rgba(0, 230, 118, 0.85), transparent);
                }
                .tunnel-graphic {
                    position: relative;
                    width: 100%;
                    height: 64px;
                    margin: 0 auto 16px;
                    display: flex;
                    align-items: center;
                    justify-content: space-between;
                    padding: 0 16px;
                }
                .node-icon {
                    width: 48px;
                    height: 48px;
                    border-radius: 14px;
                    background: rgba(255, 255, 255, 0.04);
                    border: 1px solid rgba(255, 255, 255, 0.08);
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    z-index: 2;
                }
                .node-icon svg {
                    width: 24px;
                    height: 24px;
                }
                .tunnel-line {
                    position: relative;
                    flex: 1;
                    height: 2px;
                    margin: 0 12px;
                    background: linear-gradient(90deg, rgba(0, 245, 212, 0.3), rgba(243, 128, 32, 0.4));
                    overflow: hidden;
                }
                .tunnel-pulse {
                    position: absolute;
                    top: -2px;
                    left: 0;
                    width: 28px;
                    height: 6px;
                    border-radius: 3px;
                    background: linear-gradient(90deg, transparent, #00F5D4, #F38020, transparent);
                    animation: pulse-stream 1.8s cubic-bezier(0.4, 0, 0.2, 1) infinite;
                }
                @keyframes pulse-stream {
                    0% { transform: translateX(-30px); }
                    100% { transform: translateX(200px); }
                }
                .tag {
                    display: inline-flex;
                    align-items: center;
                    gap: 7px;
                    padding: 4px 12px;
                    border-radius: 20px;
                    background: rgba(0, 230, 118, 0.08);
                    border: 1px solid rgba(0, 230, 118, 0.25);
                    color: var(--accent-green);
                    font-size: 10.5px;
                    font-weight: 700;
                    letter-spacing: 0.6px;
                    text-transform: uppercase;
                    margin-bottom: 12px;
                }
                .tag-dot {
                    width: 6px;
                    height: 6px;
                    border-radius: 50%;
                    background: var(--accent-green);
                    box-shadow: 0 0 6px var(--accent-green);
                    animation: blink-led 2s ease-in-out infinite;
                }
                @keyframes blink-led {
                    0%, 100% { opacity: 1; transform: scale(1); }
                    50% { opacity: 0.4; transform: scale(0.85); }
                }
                h1 {
                    font-size: 20px;
                    font-weight: 800;
                    letter-spacing: -0.3px;
                    margin-bottom: 8px;
                    color: var(--text-white);
                }
                .desc {
                    font-size: 13px;
                    line-height: 1.5;
                    color: var(--text-secondary);
                    margin-bottom: 20px;
                }
                .benefits-grid {
                    display: grid;
                    grid-template-columns: 1fr 1fr;
                    gap: 8px;
                    margin-bottom: 22px;
                    text-align: left;
                }
                .benefit-item {
                    background: rgba(255, 255, 255, 0.025);
                    border: 1px solid rgba(255, 255, 255, 0.05);
                    border-radius: 12px;
                    padding: 10px 12px;
                }
                .benefit-title {
                    font-size: 11px;
                    font-weight: 700;
                    color: var(--text-white);
                    margin-bottom: 2px;
                }
                .benefit-desc {
                    font-size: 10px;
                    color: var(--text-muted);
                    line-height: 1.35;
                }
                .btn-connect {
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    gap: 9px;
                    width: 100%;
                    padding: 14px 18px;
                    border-radius: 14px;
                    background: linear-gradient(135deg, var(--cf-orange) 0%, var(--cf-orange-dark) 100%);
                    color: #FFFFFF;
                    font-size: 14px;
                    font-weight: 700;
                    text-decoration: none;
                    box-shadow: 0 8px 24px -4px rgba(243, 128, 32, 0.45);
                    cursor: pointer;
                    border: none;
                    transition: transform 0.12s ease, opacity 0.12s ease;
                    margin-bottom: 12px;
                }
                .btn-connect:active {
                    transform: scale(0.98);
                    opacity: 0.92;
                }
                .btn-connect svg {
                    width: 19px;
                    height: 19px;
                    fill: #FFFFFF;
                }
                .footnote {
                    font-size: 11px;
                    color: var(--text-muted);
                    line-height: 1.4;
                }
            </style>
        </head>
        <body>
            <div class="card">
                <div class="tunnel-graphic">
                    <div class="node-icon" title="Mirrly TG Proxy">
                        <svg viewBox="0 0 24 24" fill="none" stroke="#00F5D4" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                            <rect x="5" y="2" width="14" height="20" rx="3" ry="3"/>
                            <line x1="12" y1="18" x2="12.01" y2="18"/>
                        </svg>
                    </div>
                    <div class="tunnel-line">
                        <div class="tunnel-pulse"></div>
                    </div>
                    <div class="node-icon" title="Cloudflare Edge">
                        <svg viewBox="0 0 24 24" fill="none" stroke="#F38020" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                            <path d="M18 10h-1.26A8 8 0 1 0 9 20h9a5 5 0 0 0 0-10z"/>
                        </svg>
                    </div>
                </div>

                <div class="tag">
                    <span class="tag-dot"></span>
                    <span>Локальный шлюз активен • 127.0.0.1:$CALLBACK_PORT</span>
                </div>

                <h1>Подключение Cloudflare</h1>
                <p class="desc">Свяжите аккаунт Cloudflare с Mirrly TG Proxy для автоматического развертывания личных воркеров и обхода блокировок.</p>

                <div class="benefits-grid">
                    <div class="benefit-item">
                        <div class="benefit-title">100 000 req/day</div>
                        <div class="benefit-desc">Бесплатный суточный лимит запросов</div>
                    </div>
                    <div class="benefit-item">
                        <div class="benefit-title">WSS + SOCKS5</div>
                        <div class="benefit-desc">Звонки и сообщения без задержек</div>
                    </div>
                    <div class="benefit-item">
                        <div class="benefit-title">workers.dev</div>
                        <div class="benefit-desc">Личный домен и изоляция от пулов</div>
                    </div>
                    <div class="benefit-item">
                        <div class="benefit-title">PKCE S256</div>
                        <div class="benefit-desc">Аппаратное сквозное шифрование</div>
                    </div>
                </div>

                <a href="$authUrl" class="btn-connect" id="connectBtn" onclick="onConnectClick()">
                    <svg viewBox="0 0 24 24">
                        <path d="M18.8 11.2c-.3-2.7-2.6-4.8-5.4-4.8-2.2 0-4.1 1.3-4.9 3.2-1.8.2-3.2 1.7-3.2 3.6 0 2 1.6 3.6 3.6 3.6h9.6c1.8 0 3.2-1.4 3.2-3.2 0-1.6-1.2-3-2.9-3.2zm-9.1 4.2c-.8 0-1.4-.6-1.4-1.4 0-.8.6-1.4 1.4-1.4.2 0 .4 0 .5.1.2-.8.9-1.4 1.8-1.4.7 0 1.4.4 1.7 1.1.2-.1.4-.1.7-.1 1.1 0 2 .9 2 2s-.9 2-2 2H9.7z"/>
                    </svg>
                    <span>Связать аккаунт с Mirrly TG Proxy</span>
                </a>

                <div class="footnote">Авторизация через защищенный протокол OAuth 2.0 PKCE. Ваши пароли не сохраняются.</div>
            </div>

            <script>
                function onConnectClick() {
                    var btn = document.getElementById('connectBtn');
                    btn.style.opacity = '0.7';
                    btn.innerHTML = '<span>Перенаправление на Cloudflare...</span>';
                }
            </script>
        </body>
        </html>
    """.trimIndent()

    private fun createSuccessHtml(): String = """
        <!DOCTYPE html>
        <html lang="ru">
        <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
            <meta name="theme-color" content="#000000">
            <title>Mirrly TG Proxy — Авторизация успешна</title>
            <style>
                :root {
                    --bg-amoled: #000000;
                    --card-bg: rgba(14, 16, 22, 0.88);
                    --border-subtle: rgba(255, 255, 255, 0.08);
                    --cf-orange: #F38020;
                    --cf-orange-dark: #D86B12;
                    --accent-green: #00E676;
                    --accent-cyan: #00F5D4;
                    --text-white: #FFFFFF;
                    --text-secondary: #94A3B8;
                    --text-muted: #64748B;
                }
                * {
                    box-sizing: border-box;
                    margin: 0;
                    padding: 0;
                }
                body {
                    background-color: var(--bg-amoled);
                    background-image:
                        radial-gradient(circle at 50% 10%, rgba(0, 230, 118, 0.14) 0%, transparent 55%),
                        radial-gradient(circle at 85% 85%, rgba(243, 128, 32, 0.10) 0%, transparent 50%);
                    color: var(--text-white);
                    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif;
                    min-height: 100vh;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    padding: 24px 16px;
                    overflow-x: hidden;
                }
                .card {
                    position: relative;
                    width: 100%;
                    max-width: 410px;
                    background: var(--card-bg);
                    backdrop-filter: blur(28px) saturate(180%);
                    -webkit-backdrop-filter: blur(28px) saturate(180%);
                    border-radius: 26px;
                    border: 1px solid var(--border-subtle);
                    padding: 34px 24px 26px;
                    box-shadow:
                        0 30px 64px -12px rgba(0, 0, 0, 0.92),
                        0 0 0 1px rgba(255, 255, 255, 0.03),
                        inset 0 1px 0 rgba(255, 255, 255, 0.12);
                    text-align: center;
                    overflow: hidden;
                }
                .card::before {
                    content: '';
                    position: absolute;
                    top: 0;
                    left: 15%;
                    right: 15%;
                    height: 1.5px;
                    background: linear-gradient(90deg, transparent, rgba(0, 230, 118, 0.85), rgba(0, 245, 212, 0.8), transparent);
                }
                .badge-wrapper {
                    position: relative;
                    width: 76px;
                    height: 76px;
                    margin: 0 auto 18px;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                }
                .badge-glow {
                    position: absolute;
                    inset: -8px;
                    border-radius: 50%;
                    background: radial-gradient(circle, rgba(0, 230, 118, 0.35) 0%, transparent 70%);
                    animation: pulse-glow 2.6s ease-in-out infinite;
                }
                @keyframes pulse-glow {
                    0%, 100% { transform: scale(0.95); opacity: 0.6; }
                    50% { transform: scale(1.15); opacity: 1; }
                }
                .checkmark-svg {
                    position: relative;
                    width: 76px;
                    height: 76px;
                    filter: drop-shadow(0 0 10px rgba(0, 230, 118, 0.45));
                }
                .circle-track {
                    stroke: rgba(0, 230, 118, 0.20);
                }
                .circle-anim {
                    stroke-dasharray: 240;
                    stroke-dashoffset: 240;
                    stroke: #00E676;
                    animation: draw-circle 0.75s cubic-bezier(0.65, 0, 0.45, 1) forwards;
                }
                .check-anim {
                    stroke-dasharray: 50;
                    stroke-dashoffset: 50;
                    stroke: #00E676;
                    animation: draw-check 0.45s cubic-bezier(0.65, 0, 0.45, 1) 0.5s forwards;
                }
                @keyframes draw-circle {
                    100% { stroke-dashoffset: 0; }
                }
                @keyframes draw-check {
                    100% { stroke-dashoffset: 0; }
                }
                .tag {
                    display: inline-flex;
                    align-items: center;
                    gap: 6px;
                    padding: 4px 11px;
                    border-radius: 20px;
                    background: rgba(0, 230, 118, 0.10);
                    border: 1px solid rgba(0, 230, 118, 0.28);
                    color: var(--accent-green);
                    font-size: 10.5px;
                    font-weight: 700;
                    letter-spacing: 0.6px;
                    text-transform: uppercase;
                    margin-bottom: 12px;
                }
                .tag-dot {
                    width: 6px;
                    height: 6px;
                    border-radius: 50%;
                    background: var(--accent-green);
                    box-shadow: 0 0 6px var(--accent-green);
                }
                h1 {
                    font-size: 20px;
                    font-weight: 800;
                    letter-spacing: -0.3px;
                    margin-bottom: 8px;
                    color: var(--text-white);
                }
                .desc {
                    font-size: 13px;
                    line-height: 1.5;
                    color: var(--text-secondary);
                    margin-bottom: 20px;
                }
                .stats-grid {
                    display: grid;
                    grid-template-columns: 1fr 1fr;
                    gap: 8px;
                    margin-bottom: 22px;
                    text-align: left;
                }
                .stat-item {
                    background: rgba(255, 255, 255, 0.025);
                    border: 1px solid rgba(255, 255, 255, 0.05);
                    border-radius: 12px;
                    padding: 9px 12px;
                }
                .stat-label {
                    font-size: 10px;
                    color: var(--text-muted);
                    margin-bottom: 3px;
                    font-weight: 500;
                }
                .stat-val {
                    font-size: 12px;
                    font-weight: 600;
                    color: var(--text-white);
                    font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
                }
                .btn-return {
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    gap: 8px;
                    width: 100%;
                    padding: 14px 18px;
                    border-radius: 14px;
                    background: linear-gradient(135deg, var(--cf-orange) 0%, var(--cf-orange-dark) 100%);
                    color: #FFFFFF;
                    font-size: 14px;
                    font-weight: 700;
                    text-decoration: none;
                    box-shadow: 0 8px 24px -4px rgba(243, 128, 32, 0.45);
                    cursor: pointer;
                    border: none;
                    transition: transform 0.12s ease, opacity 0.12s ease;
                    margin-bottom: 12px;
                }
                .btn-return:active {
                    transform: scale(0.98);
                    opacity: 0.92;
                }
                .hint {
                    font-size: 11px;
                    color: var(--text-muted);
                    line-height: 1.45;
                }
            </style>
        </head>
        <body>
            <div class="card">
                <div class="badge-wrapper">
                    <div class="badge-glow"></div>
                    <svg class="checkmark-svg" viewBox="0 0 76 76" fill="none" xmlns="http://www.w3.org/2000/svg">
                        <circle class="circle-track" cx="38" cy="38" r="32" stroke-width="2.5"/>
                        <circle class="circle-anim" cx="38" cy="38" r="32" stroke-width="2.5" stroke-linecap="round" transform="rotate(-90 38 38)"/>
                        <path class="check-anim" d="M25 38.5L34 47.5L52 29" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/>
                    </svg>
                </div>

                <div class="tag">
                    <span class="tag-dot"></span>
                    <span>Cloudflare • OAuth 2.0</span>
                </div>

                <h1>Аккаунт успешно связан</h1>
                <p class="desc">Токен авторизации передан в Mirrly TG Proxy. Теперь вам доступно развертывание личных воркеров.</p>

                <div class="stats-grid">
                    <div class="stat-item">
                        <div class="stat-label">Суточный лимит</div>
                        <div class="stat-val">100 000 req/day</div>
                    </div>
                    <div class="stat-item">
                        <div class="stat-label">Протокол</div>
                        <div class="stat-val">WSS + SOCKS5</div>
                    </div>
                    <div class="stat-item">
                        <div class="stat-label">Шифрование</div>
                        <div class="stat-val">PKCE S256</div>
                    </div>
                    <div class="stat-item">
                        <div class="stat-label">Шлюз</div>
                        <div class="stat-val">Cloudflare Edge</div>
                    </div>
                </div>

                <button class="btn-return" onclick="returnToApp()">
                    <span>Перейти обратно в Mirrly TG Proxy</span>
                </button>
                <div class="hint">Автоматический возврат через <span id="countdown">2</span> сек... Если приложение не открылось, нажмите кнопку выше.</div>
            </div>

            <script>
                function returnToApp() {
                    try {
                        window.location.href = 'mirrly://oauth';
                    } catch (e) {}
                    setTimeout(function() {
                        try { window.close(); } catch (e) {}
                    }, 500);
                }

                var secondsLeft = 2;
                var timerElem = document.getElementById('countdown');
                var timerInterval = setInterval(function() {
                    secondsLeft--;
                    if (timerElem) timerElem.textContent = secondsLeft;
                    if (secondsLeft <= 0) {
                        clearInterval(timerInterval);
                        returnToApp();
                    }
                }, 1000);
            </script>
        </body>
        </html>
    """.trimIndent()

    private fun createErrorHtml(errorMessage: String): String = """
        <!DOCTYPE html>
        <html lang="ru">
        <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
            <meta name="theme-color" content="#000000">
            <title>Mirrly TG Proxy — Ошибка авторизации</title>
            <style>
                :root {
                    --bg-amoled: #000000;
                    --card-bg: rgba(14, 16, 22, 0.88);
                    --border-subtle: rgba(255, 255, 255, 0.08);
                    --accent-red: #EF4444;
                    --text-white: #FFFFFF;
                    --text-secondary: #94A3B8;
                    --text-muted: #64748B;
                }
                * {
                    box-sizing: border-box;
                    margin: 0;
                    padding: 0;
                }
                body {
                    background-color: var(--bg-amoled);
                    background-image:
                        radial-gradient(circle at 50% 12%, rgba(239, 68, 68, 0.16) 0%, transparent 52%),
                        radial-gradient(circle at 20% 85%, rgba(239, 68, 68, 0.08) 0%, transparent 48%);
                    color: var(--text-white);
                    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif;
                    min-height: 100vh;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    padding: 24px 16px;
                }
                .card {
                    position: relative;
                    width: 100%;
                    max-width: 410px;
                    background: var(--card-bg);
                    backdrop-filter: blur(28px) saturate(180%);
                    -webkit-backdrop-filter: blur(28px) saturate(180%);
                    border-radius: 26px;
                    border: 1px solid rgba(239, 68, 68, 0.28);
                    padding: 34px 24px 26px;
                    box-shadow:
                        0 30px 64px -12px rgba(0, 0, 0, 0.92),
                        0 0 0 1px rgba(255, 255, 255, 0.03);
                    text-align: center;
                    overflow: hidden;
                }
                .card::before {
                    content: '';
                    position: absolute;
                    top: 0;
                    left: 20%;
                    right: 20%;
                    height: 1.5px;
                    background: linear-gradient(90deg, transparent, rgba(239, 68, 68, 0.85), transparent);
                }
                .badge-wrapper {
                    position: relative;
                    width: 76px;
                    height: 76px;
                    margin: 0 auto 18px;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                }
                .badge-glow {
                    position: absolute;
                    inset: -8px;
                    border-radius: 50%;
                    background: radial-gradient(circle, rgba(239, 68, 68, 0.35) 0%, transparent 70%);
                }
                .error-svg {
                    position: relative;
                    width: 76px;
                    height: 76px;
                    filter: drop-shadow(0 0 10px rgba(239, 68, 68, 0.45));
                }
                .tag {
                    display: inline-flex;
                    align-items: center;
                    gap: 6px;
                    padding: 4px 11px;
                    border-radius: 20px;
                    background: rgba(239, 68, 68, 0.10);
                    border: 1px solid rgba(239, 68, 68, 0.28);
                    color: var(--accent-red);
                    font-size: 10.5px;
                    font-weight: 700;
                    letter-spacing: 0.6px;
                    text-transform: uppercase;
                    margin-bottom: 12px;
                }
                .tag-dot {
                    width: 6px;
                    height: 6px;
                    border-radius: 50%;
                    background: var(--accent-red);
                    box-shadow: 0 0 6px var(--accent-red);
                }
                h1 {
                    font-size: 20px;
                    font-weight: 800;
                    letter-spacing: -0.3px;
                    margin-bottom: 8px;
                    color: var(--text-white);
                }
                .desc {
                    font-size: 13px;
                    line-height: 1.5;
                    color: #FCA5A5;
                    margin-bottom: 22px;
                    background: rgba(239, 68, 68, 0.08);
                    border: 1px solid rgba(239, 68, 68, 0.20);
                    border-radius: 12px;
                    padding: 12px;
                    word-break: break-word;
                }
                .actions {
                    display: flex;
                    flex-direction: column;
                    gap: 8px;
                }
                .btn-retry {
                    display: block;
                    width: 100%;
                    padding: 13px 18px;
                    border-radius: 13px;
                    background: rgba(255, 255, 255, 0.10);
                    border: 1px solid var(--border-subtle);
                    color: #FFFFFF;
                    font-size: 13.5px;
                    font-weight: 700;
                    text-decoration: none;
                    cursor: pointer;
                    transition: background 0.12s ease;
                }
                .btn-retry:active {
                    background: rgba(255, 255, 255, 0.16);
                }
            </style>
        </head>
        <body>
            <div class="card">
                <div class="badge-wrapper">
                    <div class="badge-glow"></div>
                    <svg class="error-svg" viewBox="0 0 76 76" fill="none" xmlns="http://www.w3.org/2000/svg">
                        <circle cx="38" cy="38" r="32" stroke="rgba(239, 68, 68, 0.3)" stroke-width="2.5"/>
                        <path d="M27 27L49 49M49 27L27 49" stroke="#EF4444" stroke-width="3" stroke-linecap="round"/>
                    </svg>
                </div>

                <div class="tag">
                    <span class="tag-dot"></span>
                    <span>Сбой авторизации</span>
                </div>

                <h1>Ошибка подключения</h1>
                <div class="desc">$errorMessage</div>

                <div class="actions">
                    <a href="/" class="btn-retry">Повторить попытку</a>
                    <button class="btn-retry" onclick="returnToApp()" style="color: var(--text-secondary);">Вернуться в приложение</button>
                </div>
            </div>

            <script>
                function returnToApp() {
                    try {
                        window.location.href = 'mirrly://oauth';
                    } catch (e) {}
                    setTimeout(function() {
                        try { window.close(); } catch (e) {}
                    }, 400);
                }
            </script>
        </body>
        </html>
    """.trimIndent()
}
