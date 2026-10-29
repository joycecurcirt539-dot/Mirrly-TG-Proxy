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

package com.mirrly.tgproxy.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.service.PreferencesManager
import com.mirrly.tgproxy.service.cloudflare.*
import com.mirrly.tgproxy.ui.theme.*
import kotlinx.coroutines.launch

private val CfOrange = Color(0xFFF38020)
private val CardBorderDark = AmoledBorder

@Composable
fun CloudflareDeploySection(
    prefs: PreferencesManager,
    onWorkerDeployed: () -> Unit,
    modifier: Modifier = Modifier,
    activeProtoColor: Color = LocalProtocolColors.current.primary
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val protoColors = LocalProtocolColors.current

    var isAuthorized by remember { mutableStateOf(prefs.isCloudflareAuthorized()) }
    var accountId by remember { mutableStateOf(prefs.getCloudflareAccountId() ?: "") }
    var accountName by remember { mutableStateOf(prefs.getCloudflareAccountName() ?: "") }
    var subdomain by remember { mutableStateOf(prefs.getCloudflareSubdomain() ?: "") }

    var isSessionExpired by remember { mutableStateOf(false) }
    val authServerState by CloudflareOAuthManager.authState.collectAsState()
    var showManualTokenDialog by remember { mutableStateOf(false) }
    var showSubdomainDialog by remember { mutableStateOf(false) }
    var pendingDeployAfterSubdomain by remember { mutableStateOf(false) }
    var showAccountManageDialog by remember { mutableStateOf(false) }
    var workerToDelete by remember { mutableStateOf<CloudflareWorkerSummary?>(null) }
    var isManualCallbackExpandedInDeploy by remember { mutableStateOf(false) }
    var deployCallbackUrlInput by remember { mutableStateOf("") }
    var deployCallbackError by remember { mutableStateOf<String?>(null) }

    var customWorkerName by remember {
        mutableStateOf(CloudflareWorkerPayload.generateRandomWorkerName())
    }
    var isDeploying by remember { mutableStateOf(false) }
    var deployStepText by remember { mutableStateOf("") }

    var cfWorkers by remember { mutableStateOf<List<CloudflareWorkerSummary>>(emptyList()) }
    var isLoadingWorkers by remember { mutableStateOf(false) }
    var isUpdatingWorkerId by remember { mutableStateOf<String?>(null) }
    var updatingStepText by remember { mutableStateOf("") }

    fun refreshWorkersList() {
        val token = prefs.getCloudflareToken() ?: return
        val currentAccountId = prefs.getCloudflareAccountId() ?: return
        val currentSubdomain = prefs.getCloudflareSubdomain()

        isLoadingWorkers = true
        scope.launch {
            val res = CloudflareApiClient.listWorkers(token, currentAccountId, currentSubdomain, prefs)
            isLoadingWorkers = false
            res.onSuccess { list ->
                cfWorkers = list
                isSessionExpired = false
            }.onFailure { err ->
                if (CloudflareApiClient.isAuthError(err)) {
                    isSessionExpired = true
                }
                Toast.makeText(context, err.message ?: "Ошибка получения списка воркеров", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(isAuthorized) {
        if (isAuthorized) {
            refreshWorkersList()
        }
    }

    LaunchedEffect(authServerState) {
        when (val state = authServerState) {
            is ServerAuthState.Success -> {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                val tokens = state.tokens
                val accRes = CloudflareApiClient.getAccounts(tokens.accessToken, prefs)
                val firstAcc = accRes.getOrNull()?.firstOrNull()
                val accId = firstAcc?.id ?: ""
                val accLabel = firstAcc?.name ?: "Мой аккаунт"

                var foundSubdomain = ""
                if (accId.isNotEmpty()) {
                    val subRes = CloudflareApiClient.getSubdomain(tokens.accessToken, accId, prefs)
                    foundSubdomain = subRes.getOrNull() ?: ""
                }

                prefs.saveCloudflareSession(
                    accessToken = tokens.accessToken,
                    refreshToken = tokens.refreshToken,
                    accountId = accId,
                    accountName = accLabel,
                    subdomain = foundSubdomain
                )

                isAuthorized = true
                isSessionExpired = false
                accountId = accId
                accountName = accLabel
                subdomain = foundSubdomain

                Toast.makeText(context, "Cloudflare: Авторизация успешна", Toast.LENGTH_SHORT).show()
                CloudflareOAuthManager.cancelActiveListener()

                if (foundSubdomain.isEmpty()) {
                    showSubdomainDialog = true
                } else {
                    refreshWorkersList()
                }
            }
            is ServerAuthState.Error -> {
                Toast.makeText(context, state.message, Toast.LENGTH_LONG).show()
            }
            else -> {}
        }
    }

    fun startBrowserAuth() {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        CloudflareOAuthManager.startAuthSession(context)
    }

    fun deployNewWorker() {
        val token = prefs.getCloudflareToken() ?: return
        val currentAccountId = prefs.getCloudflareAccountId() ?: return
        val currentSubdomain = prefs.getCloudflareSubdomain() ?: ""

        if (currentSubdomain.isBlank()) {
            pendingDeployAfterSubdomain = true
            showSubdomainDialog = true
            return
        }

        val cleanName = CloudflareWorkerPayload.normalizeWorkerName(customWorkerName)
        val scriptContent = CloudflareWorkerPayload.getWorkerScript(context)
        if (scriptContent.isBlank()) {
            Toast.makeText(context, "Ошибка: исходный скрипт worker.js пуст", Toast.LENGTH_SHORT).show()
            return
        }

        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        isDeploying = true
        deployStepText = "1/3 Загрузка скрипта в Cloudflare..."

        scope.launch {
            val uploadRes = CloudflareApiClient.deployWorkerScript(token, currentAccountId, cleanName, scriptContent, prefs)
            if (uploadRes.isFailure) {
                isDeploying = false
                val err = uploadRes.exceptionOrNull()?.message ?: "Ошибка деплоя"
                if (CloudflareApiClient.isAuthError(uploadRes.exceptionOrNull())) {
                    isSessionExpired = true
                }
                Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                return@launch
            }

            deployStepText = "2/3 Привязка маршрутизации..."
            val subRes = CloudflareApiClient.enableWorkerSubdomain(token, currentAccountId, cleanName, prefs)
            if (subRes.isFailure) {
                isDeploying = false
                val err = subRes.exceptionOrNull()?.message ?: "Ошибка привязки домена"
                if (CloudflareApiClient.isAuthError(subRes.exceptionOrNull())) {
                    isSessionExpired = true
                }
                Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                return@launch
            }

            val fullDomain = "$cleanName.$currentSubdomain.workers.dev"
            deployStepText = "3/3 Проверка доступности узла..."
            val healthRes = CloudflareApiClient.verifyWorkerHealth(fullDomain, maxWaitSeconds = 8)

            prefs.addCustomWorker(
                name = cleanName,
                domain = fullDomain,
                isCloudflarePersonal = true,
                scriptVersion = CloudflareWorkerPayload.SCRIPT_VERSION
            )

            isDeploying = false
            if (healthRes.isSuccess) {
                Toast.makeText(context, context.getString(R.string.cf_deploy_toast_deployed), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Воркер создан! Идет глобальное распространение DNS (1-2 мин).", Toast.LENGTH_LONG).show()
            }
            customWorkerName = CloudflareWorkerPayload.generateRandomWorkerName()
            refreshWorkersList()
            onWorkerDeployed()
        }
    }

    fun updateWorkerScript(worker: CloudflareWorkerSummary) {
        val token = prefs.getCloudflareToken() ?: return
        val currentAccountId = prefs.getCloudflareAccountId() ?: return
        val scriptContent = CloudflareWorkerPayload.getWorkerScript(context)

        isUpdatingWorkerId = worker.id
        updatingStepText = "1/2 Отправка..."
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)

        scope.launch {
            val res = CloudflareApiClient.deployWorkerScript(token, currentAccountId, worker.id, scriptContent, prefs)
            if (res.isSuccess) {
                val fullDomain = worker.fullDomain ?: "${worker.id}.$subdomain.workers.dev"
                updatingStepText = "2/2 Проверка..."
                val healthRes = CloudflareApiClient.verifyWorkerHealth(fullDomain, maxWaitSeconds = 6)
                prefs.updateCustomWorkerScriptVersion(fullDomain, CloudflareWorkerPayload.SCRIPT_VERSION)
                isUpdatingWorkerId = null
                updatingStepText = ""
                if (healthRes.isSuccess) {
                    Toast.makeText(context, context.getString(R.string.cf_deploy_toast_updated), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Скрипт обновлен! Применение на Edge займет до 1 мин.", Toast.LENGTH_SHORT).show()
                }
                refreshWorkersList()
                onWorkerDeployed()
            } else {
                isUpdatingWorkerId = null
                updatingStepText = ""
                val err = res.exceptionOrNull()?.message ?: "Ошибка обновления"
                if (CloudflareApiClient.isAuthError(res.exceptionOrNull())) {
                    isSessionExpired = true
                }
                Toast.makeText(context, err, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun deleteWorkerFromCf(worker: CloudflareWorkerSummary) {
        val token = prefs.getCloudflareToken() ?: return
        val currentAccountId = prefs.getCloudflareAccountId() ?: return

        scope.launch {
            val res = CloudflareApiClient.deleteWorker(token, currentAccountId, worker.id, prefs)
            if (res.isSuccess) {
                val existingCustom = prefs.getCustomWorkers().find {
                    it.domain.contains(worker.id, ignoreCase = true)
                }
                if (existingCustom != null) {
                    prefs.deleteCustomWorker(existingCustom.id)
                }
                Toast.makeText(context, context.getString(R.string.cf_deploy_toast_deleted), Toast.LENGTH_SHORT).show()
                refreshWorkersList()
                onWorkerDeployed()
            } else {
                val err = res.exceptionOrNull()?.message ?: "Ошибка удаления"
                if (CloudflareApiClient.isAuthError(res.exceptionOrNull())) {
                    isSessionExpired = true
                }
                Toast.makeText(context, err, Toast.LENGTH_LONG).show()
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {

        // ── State 1: Not Authorized (Clean Transparent Card) ─────────────────
        if (!isAuthorized) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, CardBorderDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.cf_deploy_auth_title),
                        color = TextWhite,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = stringResource(R.string.cf_deploy_auth_desc),
                        color = TextMuted,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )

                    // Benefits List (No emojis, sleek check icons)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            R.string.cf_deploy_auth_benefit_1,
                            R.string.cf_deploy_auth_benefit_2,
                            R.string.cf_deploy_auth_benefit_3
                        ).forEach { resId ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(activeProtoColor.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_check),
                                        contentDescription = null,
                                        tint = activeProtoColor,
                                        modifier = Modifier.size(10.dp)
                                    )
                                }
                                Text(
                                    text = stringResource(resId),
                                    color = TextWhite.copy(alpha = 0.85f),
                                    fontSize = 11.5.sp
                                )
                            }
                        }
                    }

                    val isServerBusy = authServerState !is ServerAuthState.Idle
                    if (isServerBusy) {
                        val serverState = authServerState
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = when (serverState) {
                                is ServerAuthState.Error -> Color(0xFFEF4444).copy(alpha = 0.08f)
                                is ServerAuthState.ServerReady -> Color(0xFF00E676).copy(alpha = 0.08f)
                                else -> CfOrange.copy(alpha = 0.08f)
                            },
                            border = BorderStroke(
                                1.dp,
                                when (serverState) {
                                    is ServerAuthState.Error -> Color(0xFFEF4444).copy(alpha = 0.45f)
                                    is ServerAuthState.ServerReady -> Color(0xFF00E676).copy(alpha = 0.45f)
                                    else -> CfOrange.copy(alpha = 0.4f)
                                }
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp)
                            ) {
                                when (serverState) {
                                    is ServerAuthState.StartingServer -> {
                                        CircularProgressIndicator(
                                            color = CfOrange,
                                            modifier = Modifier.size(22.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Text(
                                            text = stringResource(R.string.cf_deploy_server_starting),
                                            color = TextWhite,
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            textAlign = TextAlign.Center
                                        )
                                        OutlinedButton(
                                            onClick = { CloudflareOAuthManager.cancelActiveListener() },
                                            border = BorderStroke(1.dp, CardBorderDark),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.height(32.dp)
                                        ) {
                                            Text(
                                                text = stringResource(R.string.cf_deploy_btn_cancel),
                                                color = TextMuted,
                                                fontSize = 11.sp
                                            )
                                        }
                                    }
                                    is ServerAuthState.VerifyingHealth -> {
                                        CircularProgressIndicator(
                                            color = CfOrange,
                                            modifier = Modifier.size(22.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Text(
                                            text = stringResource(R.string.cf_deploy_server_checking),
                                            color = TextWhite,
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            textAlign = TextAlign.Center
                                        )
                                        OutlinedButton(
                                            onClick = { CloudflareOAuthManager.cancelActiveListener() },
                                            border = BorderStroke(1.dp, CardBorderDark),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.height(32.dp)
                                        ) {
                                            Text(
                                                text = stringResource(R.string.cf_deploy_btn_cancel),
                                                color = TextMuted,
                                                fontSize = 11.sp
                                            )
                                        }
                                    }
                                    is ServerAuthState.ServerReady -> {
                                        CircularProgressIndicator(
                                            color = Color(0xFF00E676),
                                            modifier = Modifier.size(22.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Text(
                                            text = stringResource(R.string.cf_deploy_server_ready),
                                            color = TextWhite,
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                    is ServerAuthState.WaitingCallback -> {
                                        CircularProgressIndicator(
                                            color = CfOrange,
                                            modifier = Modifier.size(22.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Text(
                                            text = stringResource(R.string.cf_deploy_server_waiting),
                                            color = TextWhite,
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            textAlign = TextAlign.Center
                                        )
                                        Text(
                                            text = stringResource(R.string.cf_deploy_server_waiting_desc),
                                            color = TextMuted,
                                            fontSize = 11.sp,
                                            textAlign = TextAlign.Center
                                        )
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Button(
                                                onClick = {
                                                    val targetUrl = if (serverState.authUrl.isNotBlank()) serverState.authUrl else serverState.portalUrl
                                                    CloudflareOAuthManager.launchAuthInBrowser(context, targetUrl)
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = CfOrange),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.height(32.dp),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.cf_deploy_btn_open_portal),
                                                    color = Color.White,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                            OutlinedButton(
                                                onClick = { CloudflareOAuthManager.cancelActiveListener() },
                                                border = BorderStroke(1.dp, CardBorderDark),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.height(32.dp),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.cf_deploy_btn_cancel),
                                                    color = TextMuted,
                                                    fontSize = 11.sp
                                                )
                                            }
                                        }

                                        // Fallback manual callback URL entry
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = CfOrange.copy(alpha = 0.10f),
                                            border = BorderStroke(1.dp, CfOrange.copy(alpha = 0.40f)),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(32.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .springPress(onClick = {
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                                    val clipText = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
                                                    if (clipText.contains("code=")) {
                                                        val processed = CloudflareOAuthManager.processCallbackUrl(clipText)
                                                        if (!processed) {
                                                            deployCallbackError = "Неверный формат Callback URL"
                                                            isManualCallbackExpandedInDeploy = true
                                                        }
                                                    } else {
                                                        isManualCallbackExpandedInDeploy = !isManualCallbackExpandedInDeploy
                                                    }
                                                })
                                        ) {
                                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                Text(
                                                    text = "Вставить Callback URL вручную",
                                                    color = CfOrange,
                                                    fontWeight = FontWeight.SemiBold,
                                                    fontSize = 11.sp
                                                )
                                            }
                                        }

                                        AnimatedVisibility(
                                            visible = isManualCallbackExpandedInDeploy,
                                            enter = expandVertically() + fadeIn(),
                                            exit = shrinkVertically() + fadeOut()
                                        ) {
                                            Column(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                OutlinedTextField(
                                                    value = deployCallbackUrlInput,
                                                    onValueChange = {
                                                        deployCallbackUrlInput = it
                                                        deployCallbackError = null
                                                    },
                                                    placeholder = {
                                                        Text(
                                                            text = "http://localhost:8976/oauth/callback?code=...",
                                                            color = TextMuted,
                                                            fontSize = 11.sp,
                                                            fontFamily = FontFamily.Monospace
                                                        )
                                                    },
                                                    textStyle = LocalTextStyle.current.copy(
                                                        fontSize = 11.sp,
                                                        fontFamily = FontFamily.Monospace,
                                                        color = TextWhite
                                                    ),
                                                    singleLine = true,
                                                    modifier = Modifier.fillMaxWidth(),
                                                    shape = RoundedCornerShape(8.dp),
                                                    colors = OutlinedTextFieldDefaults.colors(
                                                        focusedBorderColor = CfOrange,
                                                        unfocusedBorderColor = AmoledBorder,
                                                        focusedContainerColor = Color.Transparent,
                                                        unfocusedContainerColor = Color.Transparent,
                                                        cursorColor = CfOrange
                                                    )
                                                )

                                                if (deployCallbackError != null) {
                                                    Text(
                                                        text = deployCallbackError.orEmpty(),
                                                        color = Color(0xFFEF4444),
                                                        fontSize = 10.5.sp
                                                    )
                                                }

                                                Surface(
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = CfOrange.copy(alpha = 0.16f),
                                                    border = BorderStroke(1.dp, CfOrange.copy(alpha = 0.60f)),
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(32.dp)
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .springPress(onClick = {
                                                            val raw = deployCallbackUrlInput.trim()
                                                            if (!raw.contains("code=")) {
                                                                deployCallbackError = "Ссылка должна содержать параметр code="
                                                                return@springPress
                                                            }
                                                            val ok = CloudflareOAuthManager.processCallbackUrl(raw)
                                                            if (!ok) {
                                                                deployCallbackError = "Ошибка обработки ссылки или несовпадение state"
                                                            }
                                                        })
                                                ) {
                                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                        Text(
                                                            text = "Подтвердить Callback URL",
                                                            color = CfOrange,
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 11.5.sp
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    is ServerAuthState.ExchangingTokens -> {
                                        CircularProgressIndicator(
                                            color = Color(0xFF00E676),
                                            modifier = Modifier.size(22.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Text(
                                            text = "Синхронизация сессии Cloudflare...",
                                            color = TextWhite,
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                    is ServerAuthState.Error -> {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_stat_proxy_error),
                                            contentDescription = null,
                                            tint = Color(0xFFEF4444),
                                            modifier = Modifier.size(22.dp)
                                        )
                                        Text(
                                            text = serverState.message,
                                            color = Color(0xFFFCA5A5),
                                            fontSize = 11.5.sp,
                                            textAlign = TextAlign.Center,
                                            lineHeight = 15.sp
                                        )
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Button(
                                                onClick = { startBrowserAuth() },
                                                colors = ButtonDefaults.buttonColors(containerColor = CfOrange),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.height(32.dp),
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.cf_deploy_btn_retry),
                                                    color = Color.White,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                            OutlinedButton(
                                                onClick = { CloudflareOAuthManager.cancelActiveListener() },
                                                border = BorderStroke(1.dp, CardBorderDark),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.height(32.dp),
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.cf_deploy_btn_cancel),
                                                    color = TextMuted,
                                                    fontSize = 11.sp
                                                )
                                            }
                                        }
                                    }
                                    else -> {}
                                }
                            }
                        }
                    } else {
                        // Main Login Button (Compact 42.dp)
                        Button(
                            onClick = { startBrowserAuth() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(42.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CfOrange)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_diag_cloudflare),
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = stringResource(R.string.cf_deploy_btn_login),
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Manual API Token Fallback
                        TextButton(
                            onClick = { showManualTokenDialog = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(32.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.cf_deploy_btn_manual_token),
                                color = TextMuted,
                                fontSize = 11.5.sp
                            )
                        }
                    }
                }
            }
        } else {
            // ── State 2: Authorized Dashboard ────────────────────────────────

            // Session Expired Warning Banner
            if (isSessionExpired) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFEF4444).copy(alpha = 0.08f),
                    border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFEF4444))
                            )
                            Text(
                                text = stringResource(R.string.cf_deploy_session_expired_title),
                                color = Color(0xFFEF4444),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = stringResource(R.string.cf_deploy_session_expired_desc),
                            color = TextWhite.copy(alpha = 0.85f),
                            fontSize = 11.5.sp,
                            lineHeight = 15.sp
                        )
                        Button(
                            onClick = { startBrowserAuth() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(34.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CfOrange)
                        ) {
                            Text(
                                text = stringResource(R.string.cf_deploy_btn_reauth),
                                color = Color.White,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Account & Subdomain Profile Card (Compact Transparent Surface)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, CardBorderDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (isSessionExpired) Color(0xFFEF4444) else Color(0xFF00E676))
                            )
                            Text(
                                text = accountName.ifBlank { "Cloudflare" },
                                color = TextWhite,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, CfOrange.copy(alpha = 0.50f)),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .springPress(onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    showAccountManageDialog = true
                                })
                        ) {
                            Text(
                                text = stringResource(R.string.cf_deploy_btn_logout),
                                color = CfOrange,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.cf_deploy_subdomain_label),
                            color = TextMuted,
                            fontSize = 11.sp
                        )

                        if (subdomain.isNotBlank()) {
                            Text(
                                text = "$subdomain.workers.dev",
                                color = activeProtoColor,
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.cf_deploy_btn_setup_subdomain),
                                color = CfOrange,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable { showSubdomainDialog = true }
                            )
                        }
                    }
                }
            }

            // ── Create New Worker Card (Styled EXACTLY like GlassWorkerCard) ─────
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, CardBorderDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(CfOrange)
                            )
                            Text(
                                text = stringResource(R.string.cf_deploy_section_create),
                                color = TextWhite,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                customWorkerName = CloudflareWorkerPayload.generateRandomWorkerName()
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_refresh),
                                contentDescription = stringResource(R.string.cf_deploy_btn_random_name),
                                tint = CfOrange,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    // Worker Name Input Field (Slim 38.dp, transparent style)
                    OutlinedTextField(
                        value = customWorkerName,
                        onValueChange = { customWorkerName = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(
                                text = stringResource(R.string.cf_deploy_name_placeholder),
                                color = TextMuted,
                                fontSize = 12.sp
                            )
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CfOrange,
                            unfocusedBorderColor = CardBorderDark,
                            focusedTextColor = TextWhite,
                            unfocusedTextColor = TextWhite,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { deployNewWorker() })
                    )

                    // Resulting Domain Preview
                    if (subdomain.isNotBlank()) {
                        val norm = CloudflareWorkerPayload.normalizeWorkerName(customWorkerName)
                        Text(
                            text = "https://$norm.$subdomain.workers.dev",
                            color = TextMuted,
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = CfOrange.copy(alpha = 0.08f),
                            border = BorderStroke(1.dp, CfOrange.copy(alpha = 0.30f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { showSubdomainDialog = true }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_info_circle),
                                    contentDescription = null,
                                    tint = CfOrange,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = stringResource(R.string.socks5_onboard_subdomain_needed_title),
                                    color = CfOrange,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    // Deploy Action Button (Compact 38.dp, no emojis)
                    if (isDeploying) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = CfOrange.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, CfOrange.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(38.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(
                                    color = CfOrange,
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = deployStepText,
                                    color = TextWhite,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    } else {
                        Button(
                            onClick = { deployNewWorker() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(38.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CfOrange)
                        ) {
                            Text(
                                text = stringResource(R.string.cf_deploy_btn_deploy),
                                color = Color.White,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // ── Existing Cloudflare Workers List ─────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.cf_deploy_section_existing),
                        color = TextWhite,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )

                    IconButton(
                        onClick = { refreshWorkersList() },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_refresh),
                            contentDescription = stringResource(R.string.cf_deploy_refresh_list),
                            tint = if (isLoadingWorkers) CfOrange else TextMuted,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }

                if (isLoadingWorkers && cfWorkers.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = CfOrange, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                } else if (cfWorkers.isEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, CardBorderDark),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.cf_deploy_no_workers),
                            color = TextMuted,
                            fontSize = 11.5.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 14.dp)
                        )
                    }
                } else {
                    val localCustomWorkers = prefs.getCustomWorkers()

                    cfWorkers.forEach { worker ->
                        val fullDomain = worker.fullDomain ?: "${worker.id}.$subdomain.workers.dev"
                        val localWorker = localCustomWorkers.find {
                            it.domain.equals(fullDomain, ignoreCase = true)
                        }
                        val isImported = localWorker != null
                        val needsScriptUpdate = localWorker != null && localWorker.scriptVersion < CloudflareWorkerPayload.SCRIPT_VERSION

                        // Worker Item (1:1 styling with GlassWorkerCard)
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isImported) activeProtoColor.copy(alpha = 0.05f) else Color.Transparent,
                            border = BorderStroke(1.dp, if (isImported) activeProtoColor.copy(alpha = 0.40f) else CardBorderDark),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.5.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        val ledColor = when {
                                            isImported && !needsScriptUpdate -> Color(0xFF00E676)
                                            isImported && needsScriptUpdate -> Color(0xFFF59E0B)
                                            else -> TextMuted
                                        }

                                        Box(
                                            modifier = Modifier
                                                .size(7.dp)
                                                .clip(CircleShape)
                                                .background(ledColor)
                                        )

                                        Column {
                                            Text(
                                                text = worker.id,
                                                color = TextWhite,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = fullDomain,
                                                color = TextMuted,
                                                fontSize = 10.5.sp,
                                                fontFamily = FontFamily.Monospace,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    // Action buttons for worker
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        if (!isImported) {
                                            OutlinedButton(
                                                onClick = {
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    prefs.addCustomWorker(
                                                        name = worker.id,
                                                        domain = fullDomain,
                                                        isCloudflarePersonal = true,
                                                        scriptVersion = CloudflareWorkerPayload.SCRIPT_VERSION
                                                    )
                                                    onWorkerDeployed()
                                                    Toast.makeText(context, "Импортирован в Mirrly", Toast.LENGTH_SHORT).show()
                                                },
                                                modifier = Modifier.height(28.dp),
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                                shape = RoundedCornerShape(7.dp),
                                                border = BorderStroke(1.dp, activeProtoColor.copy(alpha = 0.7f))
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.cf_deploy_btn_import),
                                                    color = activeProtoColor,
                                                    fontSize = 10.5.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                        } else {
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = activeProtoColor.copy(alpha = 0.12f),
                                                border = BorderStroke(1.dp, activeProtoColor.copy(alpha = 0.4f))
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.cf_deploy_worker_added),
                                                    color = activeProtoColor,
                                                    fontSize = 9.5.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                                )
                                            }
                                        }

                                        // Always visible "Обновить скрипт" button for each worker
                                        val isUpdatingThis = isUpdatingWorkerId == worker.id
                                        Button(
                                            onClick = { updateWorkerScript(worker) },
                                            modifier = Modifier.height(28.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                            shape = RoundedCornerShape(7.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (needsScriptUpdate) CfOrange else Color.White.copy(alpha = 0.10f),
                                                contentColor = Color.White
                                            ),
                                            border = BorderStroke(1.dp, if (needsScriptUpdate) CfOrange else Color.White.copy(alpha = 0.22f)),
                                            enabled = !isUpdatingThis
                                        ) {
                                            if (isUpdatingThis) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    CircularProgressIndicator(
                                                        color = Color.White,
                                                        modifier = Modifier.size(11.dp),
                                                        strokeWidth = 1.5.dp
                                                    )
                                                    if (updatingStepText.isNotEmpty()) {
                                                        Text(
                                                            text = updatingStepText,
                                                            color = Color.White,
                                                            fontSize = 9.sp,
                                                            fontWeight = FontWeight.Medium
                                                        )
                                                    }
                                                }
                                            } else {
                                                Text(
                                                    text = stringResource(R.string.cf_deploy_btn_update_script),
                                                    color = Color.White,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                        }

                                        // Copy domain icon button
                                        IconButton(
                                            onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                clipboard.setPrimaryClip(ClipData.newPlainText("Worker Domain", fullDomain))
                                                Toast.makeText(context, "Домен скопирован", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                painter = painterResource(id = R.drawable.ic_copy),
                                                contentDescription = null,
                                                tint = TextMuted,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }

                                        // Delete icon button
                                        IconButton(
                                            onClick = { workerToDelete = worker },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                painter = painterResource(id = R.drawable.ic_trash),
                                                contentDescription = stringResource(R.string.cf_deploy_btn_delete),
                                                tint = Color(0xFFFF5252).copy(alpha = 0.85f),
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Bottom Sheet: Manual API Token ─────────────────────────────────────────────
    if (showManualTokenDialog) {
        CloudflareManualTokenSheet(
            prefs = prefs,
            onDismiss = { showManualTokenDialog = false },
            onTokenSaved = { savedToken, accId, accName, sub ->
                showManualTokenDialog = false
                isAuthorized = true
                isSessionExpired = false
                accountId = accId
                accountName = accName
                subdomain = sub
                if (sub.isEmpty()) {
                    showSubdomainDialog = true
                } else {
                    refreshWorkersList()
                }
            }
        )
    }

    // ── Bottom Sheet: Setup Subdomain ──────────────────────────────────────────────
    if (showSubdomainDialog) {
        CloudflareSubdomainSetupDialog(
            onDismiss = {
                showSubdomainDialog = false
                pendingDeployAfterSubdomain = false
            },
            onSubdomainConfigured = { registered ->
                showSubdomainDialog = false
                subdomain = registered
                if (pendingDeployAfterSubdomain) {
                    pendingDeployAfterSubdomain = false
                    deployNewWorker()
                } else {
                    refreshWorkersList()
                }
            },
            initialSubdomain = subdomain
        )
    }

    // ── Bottom Sheet: Confirm Delete Worker ────────────────────────────────────────
    workerToDelete?.let { worker ->
        CloudflareDeleteConfirmSheet(
            workerName = worker.id,
            onConfirm = {
                val w = worker
                workerToDelete = null
                deleteWorkerFromCf(w)
            },
            onDismiss = { workerToDelete = null }
        )
    }

    // ── Bottom Sheet: Cloudflare Account Management & Switch ─────────────────────
    if (showAccountManageDialog) {
        CloudflareAccountManageSheet(
            prefs = prefs,
            accountName = accountName,
            accountId = accountId,
            subdomain = subdomain,
            isSessionExpired = isSessionExpired,
            onDismiss = { showAccountManageDialog = false },
            onSetupSubdomain = {
                showAccountManageDialog = false
                showSubdomainDialog = true
            },
            onSwitchAccount = {
                showAccountManageDialog = false
                prefs.clearCloudflareSession()
                val app = com.mirrly.tgproxy.MirrlyApplication.instance
                app.config.customCfDomain = ""
                app.prefsManager.saveConfig(app.config)
                isAuthorized = false
                isSessionExpired = false
                accountName = ""
                accountId = ""
                subdomain = ""
                cfWorkers = emptyList()
                startBrowserAuth()
            },
            onLogout = {
                showAccountManageDialog = false
                prefs.clearCloudflareSession()
                val app = com.mirrly.tgproxy.MirrlyApplication.instance
                app.config.customCfDomain = ""
                app.prefsManager.saveConfig(app.config)
                isAuthorized = false
                isSessionExpired = false
                accountName = ""
                accountId = ""
                subdomain = ""
                cfWorkers = emptyList()
                Toast.makeText(context, context.getString(R.string.cf_session_reset), Toast.LENGTH_SHORT).show()
            },
            onSessionRefreshed = { freshToken ->
                isSessionExpired = false
                refreshWorkersList()
            }
        )
    }
}
