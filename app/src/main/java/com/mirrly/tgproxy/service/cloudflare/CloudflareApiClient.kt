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

import com.mirrly.tgproxy.service.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class CloudflareAuthException(message: String) : IOException(message)

data class CloudflareAccount(
    val id: String,
    val name: String
)

data class CloudflareWorkerSummary(
    val id: String,
    val createdOn: String?,
    val modifiedOn: String?,
    val fullDomain: String? = null
)

object CloudflareApiClient {

    private const val BASE_URL = "https://api.cloudflare.com/client/v4"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    // Network execution is handled by CloudflareNetworkClient with adaptive SOCKS5/DoH/TLS fallback

    private fun createApiException(bodyStr: String, code: Int): IOException {
        val msg = parseCloudflareError(bodyStr, code)
        return if (code == 401 || isAuthError(msg)) {
            CloudflareAuthException(msg)
        } else {
            IOException(msg)
        }
    }

    /**
     * Attempts to refresh the Cloudflare OAuth access token using saved refresh token.
     */
    suspend fun tryRefreshToken(prefs: PreferencesManager): String? = withContext(Dispatchers.IO) {
        val refreshToken = prefs.getCloudflareRefreshToken() ?: return@withContext null
        val refreshResult = CloudflareOAuthManager.refreshTokens(refreshToken)
        if (refreshResult is OAuthResult.Success) {
            prefs.updateCloudflareAccessToken(refreshResult.tokens.accessToken, refreshResult.tokens.refreshToken)
            return@withContext refreshResult.tokens.accessToken
        }
        null
    }

    /**
     * Checks whether the given error represents an authentication / token expiration failure.
     */
    fun isAuthError(throwable: Throwable?): Boolean {
        if (throwable is CloudflareAuthException) return true
        return isAuthError(throwable?.message.orEmpty())
    }

    fun isAuthError(msg: String): Boolean {
        return msg.contains("истек", ignoreCase = true) ||
                msg.contains("авторизац", ignoreCase = true) ||
                msg.contains("Authentication error", ignoreCase = true) ||
                msg.contains("Token has expired", ignoreCase = true) ||
                msg.contains("10000") ||
                msg.contains("10001") ||
                msg.contains("401")
    }

    /**
     * Verifies that the given token (OAuth or API Token) is active and valid.
     */
    suspend fun verifyToken(token: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$BASE_URL/user/tokens/verify")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .get()
            .build()

        try {
            CloudflareNetworkClient.executeWithFallback(request).use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext Result.failure(createApiException(bodyStr, response.code))
                }
                val json = JSONObject(bodyStr)
                val success = json.optBoolean("success", false)
                if (success) {
                    Result.success(true)
                } else {
                    Result.failure(createApiException(bodyStr, response.code))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Retrieves the list of accounts accessible by this token.
     */
    suspend fun getAccounts(
        token: String,
        prefs: PreferencesManager? = null
    ): Result<List<CloudflareAccount>> = withContext(Dispatchers.IO) {
        val initial = getAccountsInternal(token)
        if (initial.isFailure && prefs != null && isAuthError(initial.exceptionOrNull())) {
            val freshToken = tryRefreshToken(prefs)
            if (freshToken != null) {
                return@withContext getAccountsInternal(freshToken)
            }
        }
        initial
    }

    private fun getAccountsInternal(token: String): Result<List<CloudflareAccount>> {
        val request = Request.Builder()
            .url("$BASE_URL/accounts?page=1&per_page=50")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .get()
            .build()

        try {
            CloudflareNetworkClient.executeWithFallback(request).use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return Result.failure(createApiException(bodyStr, response.code))
                }
                val json = JSONObject(bodyStr)
                val resultArray = json.optJSONArray("result") ?: JSONArray()
                val list = mutableListOf<CloudflareAccount>()
                for (i in 0 until resultArray.length()) {
                    val obj = resultArray.getJSONObject(i)
                    list.add(
                        CloudflareAccount(
                            id = obj.getString("id"),
                            name = obj.optString("name", "Account #${obj.getString("id").take(6)}")
                        )
                    )
                }
                return Result.success(list)
            }
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }

    /**
     * Gets the account's registered workers.dev subdomain (e.g., "my-user").
     */
    suspend fun getSubdomain(
        token: String,
        accountId: String,
        prefs: PreferencesManager? = null
    ): Result<String?> = withContext(Dispatchers.IO) {
        val initial = getSubdomainInternal(token, accountId)
        if (initial.isFailure && prefs != null && isAuthError(initial.exceptionOrNull())) {
            val freshToken = tryRefreshToken(prefs)
            if (freshToken != null) {
                return@withContext getSubdomainInternal(freshToken, accountId)
            }
        }
        initial
    }

    private fun getSubdomainInternal(token: String, accountId: String): Result<String?> {
        val request = Request.Builder()
            .url("$BASE_URL/accounts/$accountId/workers/subdomain")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .get()
            .build()

        try {
            CloudflareNetworkClient.executeWithFallback(request).use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (response.code == 404) {
                    return Result.success(null)
                }
                if (!response.isSuccessful) {
                    return Result.failure(createApiException(bodyStr, response.code))
                }
                val json = JSONObject(bodyStr)
                val resultObj = json.optJSONObject("result")
                val subdomain = resultObj?.optString("subdomain")?.takeIf { it.isNotBlank() }
                return Result.success(subdomain)
            }
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }

    /**
     * Registers a workers.dev subdomain for an account that does not have one yet.
     */
    suspend fun registerSubdomain(
        token: String,
        accountId: String,
        subdomain: String,
        prefs: PreferencesManager? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val initial = registerSubdomainInternal(token, accountId, subdomain)
        if (initial.isFailure && prefs != null && isAuthError(initial.exceptionOrNull())) {
            val freshToken = tryRefreshToken(prefs)
            if (freshToken != null) {
                return@withContext registerSubdomainInternal(freshToken, accountId, subdomain)
            }
        }
        initial
    }

    private fun registerSubdomainInternal(token: String, accountId: String, subdomain: String): Result<String> {
        val payload = JSONObject().put("subdomain", subdomain).toString()
        val request = Request.Builder()
            .url("$BASE_URL/accounts/$accountId/workers/subdomain")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .put(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            CloudflareNetworkClient.executeWithFallback(request).use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return Result.failure(createApiException(bodyStr, response.code))
                }
                val json = JSONObject(bodyStr)
                val resultObj = json.optJSONObject("result")
                val registeredSubdomain = resultObj?.optString("subdomain", subdomain) ?: subdomain
                return Result.success(registeredSubdomain)
            }
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }

    /**
     * Fetches all worker scripts in the account.
     */
    suspend fun listWorkers(
        token: String,
        accountId: String,
        subdomain: String? = null,
        prefs: PreferencesManager? = null
    ): Result<List<CloudflareWorkerSummary>> = withContext(Dispatchers.IO) {
        val initial = listWorkersInternal(token, accountId, subdomain)
        if (initial.isFailure && prefs != null && isAuthError(initial.exceptionOrNull())) {
            val freshToken = tryRefreshToken(prefs)
            if (freshToken != null) {
                return@withContext listWorkersInternal(freshToken, accountId, subdomain)
            }
        }
        initial
    }

    private fun listWorkersInternal(
        token: String,
        accountId: String,
        subdomain: String?
    ): Result<List<CloudflareWorkerSummary>> {
        val request = Request.Builder()
            .url("$BASE_URL/accounts/$accountId/workers/scripts")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .get()
            .build()

        try {
            CloudflareNetworkClient.executeWithFallback(request).use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return Result.failure(createApiException(bodyStr, response.code))
                }
                val json = JSONObject(bodyStr)
                val resultArray = json.optJSONArray("result") ?: JSONArray()
                val list = mutableListOf<CloudflareWorkerSummary>()
                for (i in 0 until resultArray.length()) {
                    val obj = resultArray.getJSONObject(i)
                    val id = obj.getString("id")
                    val createdOn = obj.optString("created_on")
                    val modifiedOn = obj.optString("modified_on")
                    val fullDomain = if (!subdomain.isNullOrBlank()) "$id.$subdomain.workers.dev" else null

                    list.add(
                        CloudflareWorkerSummary(
                            id = id,
                            createdOn = createdOn,
                            modifiedOn = modifiedOn,
                            fullDomain = fullDomain
                        )
                    )
                }
                return Result.success(list)
            }
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }

    /**
     * Deploys (creates or updates) a worker script using multipart/form-data upload.
     */
    suspend fun deployWorkerScript(
        token: String,
        accountId: String,
        scriptName: String,
        scriptJs: String,
        prefs: PreferencesManager? = null
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val initial = deployWorkerScriptInternal(token, accountId, scriptName, scriptJs)
        if (initial.isFailure && prefs != null && isAuthError(initial.exceptionOrNull())) {
            val freshToken = tryRefreshToken(prefs)
            if (freshToken != null) {
                return@withContext deployWorkerScriptInternal(freshToken, accountId, scriptName, scriptJs)
            }
        }
        initial
    }

    private fun deployWorkerScriptInternal(
        token: String,
        accountId: String,
        scriptName: String,
        scriptJs: String
    ): Result<Boolean> {
        val metadataJson = JSONObject()
            .put("main_module", "worker.js")
            .put("compatibility_date", "2025-01-01")
            .toString()

        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "metadata",
                null,
                metadataJson.toRequestBody("application/json".toMediaType())
            )
            .addFormDataPart(
                "worker.js",
                "worker.js",
                scriptJs.toRequestBody("application/javascript+module".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url("$BASE_URL/accounts/$accountId/workers/scripts/$scriptName")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .put(multipartBody)
            .build()

        try {
            CloudflareNetworkClient.executeWithFallback(request).use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return Result.failure(createApiException(bodyStr, response.code))
                }
                val json = JSONObject(bodyStr)
                val success = json.optBoolean("success", false)
                if (success) {
                    return Result.success(true)
                } else {
                    return Result.failure(createApiException(bodyStr, response.code))
                }
            }
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }

    /**
     * Enables workers.dev subdomain routing for the worker script.
     */
    suspend fun enableWorkerSubdomain(
        token: String,
        accountId: String,
        scriptName: String,
        prefs: PreferencesManager? = null
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val initial = enableWorkerSubdomainInternal(token, accountId, scriptName)
        if (initial.isFailure && prefs != null && isAuthError(initial.exceptionOrNull())) {
            val freshToken = tryRefreshToken(prefs)
            if (freshToken != null) {
                return@withContext enableWorkerSubdomainInternal(freshToken, accountId, scriptName)
            }
        }
        initial
    }

    private fun enableWorkerSubdomainInternal(
        token: String,
        accountId: String,
        scriptName: String
    ): Result<Boolean> {
        val payload = JSONObject().put("enabled", true).toString()
        val request = Request.Builder()
            .url("$BASE_URL/accounts/$accountId/workers/scripts/$scriptName/subdomain")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            CloudflareNetworkClient.executeWithFallback(request).use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return Result.failure(createApiException(bodyStr, response.code))
                }
                return Result.success(true)
            }
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }

    /**
     * Deletes a worker script from Cloudflare.
     */
    suspend fun deleteWorker(
        token: String,
        accountId: String,
        scriptName: String,
        prefs: PreferencesManager? = null
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val initial = deleteWorkerInternal(token, accountId, scriptName)
        if (initial.isFailure && prefs != null && isAuthError(initial.exceptionOrNull())) {
            val freshToken = tryRefreshToken(prefs)
            if (freshToken != null) {
                return@withContext deleteWorkerInternal(freshToken, accountId, scriptName)
            }
        }
        initial
    }

    private fun deleteWorkerInternal(
        token: String,
        accountId: String,
        scriptName: String
    ): Result<Boolean> {
        val request = Request.Builder()
            .url("$BASE_URL/accounts/$accountId/workers/scripts/$scriptName")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .delete()
            .build()

        try {
            CloudflareNetworkClient.executeWithFallback(request).use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return Result.failure(createApiException(bodyStr, response.code))
                }
                return Result.success(true)
            }
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }

    /**
     * Diagnostic error formatter with Russian localization of common Cloudflare codes.
     */
    fun parseCloudflareError(rawBody: String, httpCode: Int): String {
        try {
            val json = JSONObject(rawBody)
            val errors = json.optJSONArray("errors")
            if (errors != null && errors.length() > 0) {
                val firstErr = errors.getJSONObject(0)
                val code = firstErr.optInt("code", 0)
                val message = firstErr.optString("message", "")

                return when {
                    // 10000 = Authentication error, 10001 = Token has expired
                    code == 10000 || code == 10001 || message.contains("Authentication error", ignoreCase = true) || message.contains("Token has expired", ignoreCase = true) ->
                        "Сессия Cloudflare истекла или токен недействителен. Требуется повторный вход в аккаунт."
                    code == 10026 || httpCode == 429 || message.contains("limit reached", ignoreCase = true) || message.contains("daily request limit", ignoreCase = true) || message.contains("quota exceeded", ignoreCase = true) ->
                        "Исчерпан суточный лимит вызовов Cloudflare (100 000 req/day). Смените аккаунт или подождите сброса лимита."
                    code == 10013 || message.contains("already exists", ignoreCase = true) ->
                        "Воркер или поддомен с таким именем уже существует. Выберите другое имя."
                    code == 10008 || code == 10014 || message.contains("Forbidden", ignoreCase = true) ->
                        "Доступ запрещен. Проверьте права токена в dash.cloudflare.com."
                    else -> "Cloudflare [$code]: $message"
                }
            }
        } catch (_: Exception) {}

        return when (httpCode) {
            401 -> "Сессия Cloudflare истекла или токен недействителен. Требуется повторный вход в аккаунт."
            403 -> "Отказано в доступе (403 Forbidden). Проверьте права токена в панели Cloudflare."
            404 -> "Запрошенный ресурс Cloudflare не найден."
            429 -> "Превышен лимит обращений к API Cloudflare (Rate Limit). Повторите позже."
            else -> "Ошибка сервера Cloudflare (HTTP $httpCode)."
        }
    }
}
