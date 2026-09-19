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
import androidx.compose.ui.window.Dialog
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.service.PreferencesManager
import com.mirrly.tgproxy.service.cloudflare.*
import com.mirrly.tgproxy.ui.theme.*
import kotlinx.coroutines.launch

private val CfOrange = Color(0xFFF38020)
private val CardBorderDark = Color(0xFF181E2E)
private val CardBorderFocused = Color(0xFF26324D)
private val DialogBg = Color(0xFF0C101A)

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
    var showAccountManageDialog by remember { mutableStateOf(false) }
    var workerToDelete by remember { mutableStateOf<CloudflareWorkerSummary?>(null) }

    var customWorkerName by remember {
        mutableStateOf(CloudflareWorkerPayload.generateRandomWorkerName())
    }
    var isDeploying by remember { mutableStateOf(false) }
    var deployStepText by remember { mutableStateOf("") }

    var cfWorkers by remember { mutableStateOf<List<CloudflareWorkerSummary>>(emptyList()) }
    var isLoadingWorkers by remember { mutableStateOf(false) }
    var isUpdatingWorkerId by remember { mutableStateOf<String?>(null) }

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
        deployStepText = context.getString(R.string.cf_deploy_progress_uploading)

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

            deployStepText = context.getString(R.string.cf_deploy_progress_routing)
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

            deployStepText = context.getString(R.string.cf_deploy_progress_saving)
            val fullDomain = "$cleanName.$currentSubdomain.workers.dev"
            prefs.addCustomWorker(
                name = cleanName,
                domain = fullDomain,
                isCloudflarePersonal = true,
                scriptVersion = CloudflareWorkerPayload.SCRIPT_VERSION
            )

            isDeploying = false
            Toast.makeText(context, context.getString(R.string.cf_deploy_toast_deployed), Toast.LENGTH_SHORT).show()
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
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)

        scope.launch {
            val res = CloudflareApiClient.deployWorkerScript(token, currentAccountId, worker.id, scriptContent, prefs)
            isUpdatingWorkerId = null

            if (res.isSuccess) {
                val fullDomain = worker.fullDomain ?: "${worker.id}.$subdomain.workers.dev"
                prefs.updateCustomWorkerScriptVersion(fullDomain, CloudflareWorkerPayload.SCRIPT_VERSION)
                Toast.makeText(context, context.getString(R.string.cf_deploy_toast_updated), Toast.LENGTH_SHORT).show()
                refreshWorkersList()
                onWorkerDeployed()
            } else {
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
                                                    try {
                                                        val targetUrl = if (serverState.authUrl.isNotBlank()) serverState.authUrl else serverState.portalUrl
                                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                        }
                                                        context.startActivity(intent)
                                                    } catch (_: Exception) {}
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
                            shape = RoundedCornerShape(6.dp),
                            color = CfOrange.copy(alpha = 0.12f),
                            border = BorderStroke(0.5.dp, CfOrange.copy(alpha = 0.35f)),
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    showAccountManageDialog = true
                                }
                        ) {
                            Text(
                                text = stringResource(R.string.cf_deploy_btn_logout),
                                color = CfOrange,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
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
                                        } else if (needsScriptUpdate) {
                                            val isUpdatingThis = isUpdatingWorkerId == worker.id
                                            Button(
                                                onClick = { updateWorkerScript(worker) },
                                                modifier = Modifier.height(28.dp),
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                                shape = RoundedCornerShape(7.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = CfOrange),
                                                enabled = !isUpdatingThis
                                            ) {
                                                if (isUpdatingThis) {
                                                    CircularProgressIndicator(
                                                        color = Color.White,
                                                        modifier = Modifier.size(12.dp),
                                                        strokeWidth = 1.5.dp
                                                    )
                                                } else {
                                                    Text(
                                                        text = stringResource(R.string.cf_deploy_btn_update_script),
                                                        color = Color.White,
                                                        fontSize = 10.5.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                }
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

    // ── Dialog: Manual API Token ─────────────────────────────────────────────
    if (showManualTokenDialog) {
        var tokenInput by remember { mutableStateOf("") }
        var isVerifying by remember { mutableStateOf(false) }

        Dialog(onDismissRequest = { if (!isVerifying) showManualTokenDialog = false }) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = DialogBg,
                border = BorderStroke(1.dp, CardBorderFocused),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.cf_deploy_token_dialog_title),
                        color = TextWhite,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = stringResource(R.string.cf_deploy_token_dialog_desc),
                        color = TextMuted,
                        fontSize = 11.5.sp,
                        lineHeight = 15.sp
                    )

                    OutlinedTextField(
                        value = tokenInput,
                        onValueChange = { tokenInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(
                                text = stringResource(R.string.cf_deploy_token_dialog_hint),
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
                        )
                    )

                    Text(
                        text = stringResource(R.string.cf_deploy_token_create_link),
                        color = CfOrange,
                        fontSize = 11.5.sp,
                        modifier = Modifier.clickable {
                            val uri = Uri.parse("https://dash.cloudflare.com/profile/api-tokens")
                            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                        }
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showManualTokenDialog = false },
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, CardBorderDark)
                        ) {
                            Text(text = stringResource(R.string.cf_deploy_btn_cancel), color = TextMuted, fontSize = 11.5.sp)
                        }

                        Button(
                            onClick = {
                                val cleanToken = tokenInput.trim()
                                if (cleanToken.isBlank()) return@Button
                                isVerifying = true
                                scope.launch {
                                    val checkRes = CloudflareApiClient.verifyToken(cleanToken)
                                    if (checkRes.isFailure) {
                                        isVerifying = false
                                        val err = checkRes.exceptionOrNull()?.message ?: "Недействительный токен"
                                        Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                                        return@launch
                                    }

                                    val accRes = CloudflareApiClient.getAccounts(cleanToken)
                                    val firstAcc = accRes.getOrNull()?.firstOrNull()
                                    val accId = firstAcc?.id ?: ""
                                    val accLabel = firstAcc?.name ?: "Cloudflare Token"

                                    var sub = ""
                                    if (accId.isNotEmpty()) {
                                        val subRes = CloudflareApiClient.getSubdomain(cleanToken, accId)
                                        sub = subRes.getOrNull() ?: ""
                                    }

                                    prefs.saveCloudflareSession(
                                        accessToken = cleanToken,
                                        refreshToken = null,
                                        accountId = accId,
                                        accountName = accLabel,
                                        subdomain = sub
                                    )

                                    isVerifying = false
                                    showManualTokenDialog = false
                                    isAuthorized = true
                                    accountId = accId
                                    accountName = accLabel
                                    subdomain = sub
                                    Toast.makeText(context, "Токен сохранен", Toast.LENGTH_SHORT).show()

                                    if (sub.isEmpty()) {
                                        showSubdomainDialog = true
                                    } else {
                                        refreshWorkersList()
                                    }
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CfOrange),
                            enabled = !isVerifying
                        ) {
                            if (isVerifying) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            } else {
                                Text(text = stringResource(R.string.cf_deploy_token_dialog_btn_save), color = Color.White, fontSize = 11.5.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Dialog: Setup Subdomain ──────────────────────────────────────────────
    if (showSubdomainDialog) {
        var subInput by remember { mutableStateOf("") }
        var isSavingSub by remember { mutableStateOf(false) }

        Dialog(onDismissRequest = { if (!isSavingSub) showSubdomainDialog = false }) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = DialogBg,
                border = BorderStroke(1.dp, CardBorderFocused),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.cf_deploy_subdomain_dialog_title),
                        color = TextWhite,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = stringResource(R.string.cf_deploy_subdomain_dialog_desc),
                        color = TextMuted,
                        fontSize = 11.5.sp,
                        lineHeight = 15.sp
                    )

                    OutlinedTextField(
                        value = subInput,
                        onValueChange = { subInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(
                                text = stringResource(R.string.cf_deploy_subdomain_dialog_hint),
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
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showSubdomainDialog = false },
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, CardBorderDark)
                        ) {
                            Text(text = stringResource(R.string.cf_deploy_btn_cancel), color = TextMuted, fontSize = 11.5.sp)
                        }

                        Button(
                            onClick = {
                                val token = prefs.getCloudflareToken() ?: return@Button
                                val accId = prefs.getCloudflareAccountId() ?: return@Button
                                val clean = CloudflareWorkerPayload.normalizeWorkerName(subInput)
                                if (clean.isBlank()) return@Button

                                isSavingSub = true
                                scope.launch {
                                    val res = CloudflareApiClient.registerSubdomain(token, accId, clean)
                                    isSavingSub = false
                                    if (res.isSuccess) {
                                        val registered = res.getOrNull() ?: clean
                                        prefs.setCloudflareSubdomain(registered)
                                        subdomain = registered
                                        showSubdomainDialog = false
                                        Toast.makeText(context, "Поддомен $registered.workers.dev активирован", Toast.LENGTH_SHORT).show()
                                        refreshWorkersList()
                                    } else {
                                        val err = res.exceptionOrNull()?.message ?: "Ошибка регистрации поддомена"
                                        Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CfOrange),
                            enabled = !isSavingSub
                        ) {
                            if (isSavingSub) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            } else {
                                Text(text = "Сохранить", color = Color.White, fontSize = 11.5.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Dialog: Confirm Delete Worker ────────────────────────────────────────
    workerToDelete?.let { worker ->
        AlertDialog(
            onDismissRequest = { workerToDelete = null },
            title = {
                Text(
                    text = stringResource(R.string.cf_deploy_delete_confirm_title),
                    color = TextWhite,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.cf_deploy_delete_confirm_desc, worker.id),
                    color = TextMuted,
                    fontSize = 12.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val w = worker
                        workerToDelete = null
                        deleteWorkerFromCf(w)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Text(text = "Удалить", color = Color.White, fontSize = 11.5.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { workerToDelete = null }) {
                    Text(text = stringResource(R.string.cf_deploy_btn_cancel), color = TextMuted, fontSize = 11.5.sp)
                }
            },
            containerColor = DialogBg,
            shape = RoundedCornerShape(14.dp)
        )
    }

    // ── Dialog: Cloudflare Account Management & Switch ─────────────────────
    if (showAccountManageDialog) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        Dialog(
            onDismissRequest = { showAccountManageDialog = false },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = true
            )
        ) {
            DialogBackdropBox(onDismiss = { showAccountManageDialog = false }) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 24.dp)
                        .fillMaxWidth(0.92f)
                        .clickable(remember { MutableInteractionSource() }, null) {}
                ) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = DialogBg,
                        border = BorderStroke(1.dp, CfOrange.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 22.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            // Header: Cloudflare Icon Badge + Title
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(CircleShape)
                                        .background(CfOrange.copy(alpha = 0.12f))
                                        .border(1.dp, CfOrange.copy(alpha = 0.35f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_diag_cloudflare),
                                        contentDescription = null,
                                        tint = CfOrange,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Column {
                                    Text(
                                        text = stringResource(R.string.cf_account_dialog_title),
                                        color = TextWhite,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = stringResource(R.string.cf_account_dialog_subtitle),
                                        color = TextMuted,
                                        fontSize = 11.5.sp
                                    )
                                }
                            }

                            // Details Card
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFF111724),
                                border = BorderStroke(1.dp, CardBorderDark),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // Row 1: Profile / Email
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = stringResource(R.string.cf_account_dialog_label_account),
                                            color = TextMuted,
                                            fontSize = 11.5.sp
                                        )
                                        Text(
                                            text = accountName.ifBlank { "Cloudflare User" },
                                            color = TextWhite,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    // Row 2: Subdomain
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = stringResource(R.string.cf_account_dialog_label_subdomain),
                                            color = TextMuted,
                                            fontSize = 11.5.sp
                                        )
                                        Text(
                                            text = if (subdomain.isNotBlank()) "$subdomain.workers.dev" else stringResource(R.string.cf_deploy_subdomain_missing),
                                            color = if (subdomain.isNotBlank()) activeProtoColor else TextMuted,
                                            fontSize = 11.5.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }

                                    // Row 3: Account ID (with copy)
                                    if (accountId.isNotBlank()) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = stringResource(R.string.cf_account_dialog_label_id),
                                                color = TextMuted,
                                                fontSize = 11.5.sp
                                            )
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                modifier = Modifier.clickable {
                                                    clipboard?.setPrimaryClip(ClipData.newPlainText("Cloudflare Account ID", accountId))
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    Toast.makeText(context, context.getString(R.string.cf_account_dialog_id_copied), Toast.LENGTH_SHORT).show()
                                                }
                                            ) {
                                                Text(
                                                    text = accountId.take(10) + "...",
                                                    color = TextWhite.copy(alpha = 0.85f),
                                                    fontSize = 11.sp,
                                                    fontFamily = FontFamily.Monospace
                                                )
                                                Icon(
                                                    painter = painterResource(id = R.drawable.ic_copy),
                                                    contentDescription = null,
                                                    tint = CfOrange,
                                                    modifier = Modifier.size(13.dp)
                                                )
                                            }
                                        }
                                    }

                                    // Row 4: Quota info
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = stringResource(R.string.cf_account_dialog_label_quota),
                                            color = TextMuted,
                                            fontSize = 11.5.sp
                                        )
                                        Text(
                                            text = stringResource(R.string.cf_account_dialog_quota_val),
                                            color = TextWhite,
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }

                                    // Row 5: Status pill
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Статус сессии",
                                            color = TextMuted,
                                            fontSize = 11.5.sp
                                        )
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(6.dp)
                                                    .clip(CircleShape)
                                                    .background(if (isSessionExpired) Color(0xFFEF4444) else Color(0xFF00E676))
                                            )
                                            Text(
                                                text = if (isSessionExpired) stringResource(R.string.cf_account_dialog_status_expired) else stringResource(R.string.cf_account_dialog_status_active),
                                                color = if (isSessionExpired) Color(0xFFEF4444) else Color(0xFF00E676),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                }
                            }

                            // Action Buttons
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                // 1. Switch Account: Re-triggers browser OAuth
                                Button(
                                    onClick = {
                                        showAccountManageDialog = false
                                        prefs.clearCloudflareSession()
                                        isAuthorized = false
                                        isSessionExpired = false
                                        accountName = ""
                                        accountId = ""
                                        subdomain = ""
                                        cfWorkers = emptyList()
                                        startBrowserAuth()
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(40.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = CfOrange)
                                ) {
                                    Text(
                                        text = stringResource(R.string.cf_account_dialog_btn_switch),
                                        color = Color.White,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                // 2. Log Out: Simply resets session and stays in app
                                OutlinedButton(
                                    onClick = {
                                        showAccountManageDialog = false
                                        prefs.clearCloudflareSession()
                                        isAuthorized = false
                                        isSessionExpired = false
                                        accountName = ""
                                        accountId = ""
                                        subdomain = ""
                                        cfWorkers = emptyList()
                                        Toast.makeText(context, "Сессия Cloudflare сброшена", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(36.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, CardBorderFocused)
                                ) {
                                    Text(
                                        text = stringResource(R.string.cf_account_dialog_btn_logout),
                                        color = TextWhite.copy(alpha = 0.85f),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }

                                // 3. Dismiss
                                TextButton(
                                    onClick = { showAccountManageDialog = false },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(32.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.cf_account_dialog_btn_close),
                                        color = TextMuted,
                                        fontSize = 12.sp
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
