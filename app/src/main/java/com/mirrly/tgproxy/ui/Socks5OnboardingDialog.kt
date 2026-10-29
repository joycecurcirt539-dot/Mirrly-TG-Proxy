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

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.service.cloudflare.CloudflareApiClient
import com.mirrly.tgproxy.service.cloudflare.CloudflareWorkerPayload
import com.mirrly.tgproxy.service.cloudflare.CloudflareWorkerSummary
import com.mirrly.tgproxy.ui.theme.*
import kotlinx.coroutines.launch
import java.util.UUID

private val CloudflareOrange = Color(0xFFF38020)
private val CloudflareOrangeDim = Color(0xFFFAAD3F)

@Composable
fun Socks5OnboardingDialog(
    onDismiss: () -> Unit,
    onOpenWorkerManager: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val app = MirrlyApplication.instance
    val scope = rememberCoroutineScope()

    var currentStep by remember { mutableIntStateOf(1) }

    // Worker discovery & deployment state
    var isLoadingWorkers by remember { mutableStateOf(true) }
    var cloudflareWorkers by remember { mutableStateOf<List<CloudflareWorkerSummary>>(emptyList()) }
    var selectedWorkerId by remember { mutableStateOf<String?>(null) }
    var isDeploying by remember { mutableStateOf(false) }
    var deployStepText by remember { mutableStateOf("") }

    val activeWorker = app.prefsManager.getActiveWorker()
    var activeWorkerDomain by remember {
        mutableStateOf(activeWorker.domain.ifBlank { app.config.customCfDomain })
    }
    var currentCfSubdomain by remember {
        mutableStateOf(app.prefsManager.getCloudflareSubdomain().orEmpty())
    }

    var showAuthDialog by remember { mutableStateOf(false) }
    var showSubdomainDialog by remember { mutableStateOf(false) }
    var pendingDeployAfterSubdomain by remember { mutableStateOf(false) }

    fun loadWorkersFromCloudflare() {
        val cfToken = app.prefsManager.getCloudflareToken().orEmpty()
        val cfAccountId = app.prefsManager.getCloudflareAccountId().orEmpty()
        val savedSubdomain = app.prefsManager.getCloudflareSubdomain().orEmpty()
        if (savedSubdomain.isNotBlank() && currentCfSubdomain.isBlank()) {
            currentCfSubdomain = savedSubdomain
        }
        if (cfToken.isBlank() || cfAccountId.isBlank()) {
            isLoadingWorkers = false
            cloudflareWorkers = emptyList()
            return
        }
        isLoadingWorkers = true
        scope.launch {
            if (currentCfSubdomain.isBlank()) {
                val subRes = CloudflareApiClient.getSubdomain(cfToken, cfAccountId, app.prefsManager)
                subRes.getOrNull()?.let { sub ->
                    if (sub.isNotBlank()) {
                        app.prefsManager.setCloudflareSubdomain(sub)
                        currentCfSubdomain = sub
                    }
                }
            }
            val res = CloudflareApiClient.listWorkers(cfToken, cfAccountId, currentCfSubdomain.ifBlank { null }, app.prefsManager)
            isLoadingWorkers = false
            res.onSuccess { list ->
                cloudflareWorkers = list
                if (selectedWorkerId == null && list.isNotEmpty()) {
                    selectedWorkerId = list.first().id
                    // Auto-select first worker if none is active
                    if (activeWorkerDomain.isBlank()) {
                        val firstWorker = list.first()
                        val targetDomain = firstWorker.fullDomain ?: if (currentCfSubdomain.isNotBlank()) "${firstWorker.id}.$currentCfSubdomain.workers.dev" else firstWorker.id
                        val existing = app.prefsManager.getCustomWorkers().find { it.domain.equals(targetDomain, ignoreCase = true) }
                        val finalId = if (existing != null) {
                            existing.id
                        } else {
                            val addRes = app.prefsManager.addCustomWorker(
                                name = firstWorker.id,
                                domain = targetDomain,
                                isCloudflarePersonal = true,
                                scriptVersion = CloudflareWorkerPayload.SCRIPT_VERSION
                            )
                            addRes.getOrNull()?.id ?: UUID.randomUUID().toString()
                        }
                        app.prefsManager.setActiveWorkerId(finalId, fromUserAction = true)
                        app.config.customCfDomain = targetDomain
                        app.config.useDefaultWorkerSocks5 = false
                        app.prefsManager.saveConfig(app.config)
                        activeWorkerDomain = targetDomain
                    }
                }
            }.onFailure {
                cloudflareWorkers = emptyList()
            }
        }
    }

    LaunchedEffect(Unit) {
        loadWorkersFromCloudflare()
    }

    fun applySelectedWorker(worker: CloudflareWorkerSummary) {
        val activeSubdomain = currentCfSubdomain.ifBlank { app.prefsManager.getCloudflareSubdomain().orEmpty() }
        val targetDomain = worker.fullDomain ?: if (activeSubdomain.isNotBlank()) "${worker.id}.$activeSubdomain.workers.dev" else worker.id
        val existing = app.prefsManager.getCustomWorkers().find { it.domain.equals(targetDomain, ignoreCase = true) }
        val finalId = if (existing != null) {
            existing.id
        } else {
            val addRes = app.prefsManager.addCustomWorker(
                name = worker.id,
                domain = targetDomain,
                isCloudflarePersonal = true,
                scriptVersion = CloudflareWorkerPayload.SCRIPT_VERSION
            )
            addRes.getOrNull()?.id ?: UUID.randomUUID().toString()
        }
        app.prefsManager.setActiveWorkerId(finalId, fromUserAction = true)
        app.config.customCfDomain = targetDomain
        app.config.useDefaultWorkerSocks5 = false
        app.prefsManager.saveConfig(app.config)
        activeWorkerDomain = targetDomain
        selectedWorkerId = worker.id
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    fun deployNewWorkerIn1Click() {
        val cfToken = app.prefsManager.getCloudflareToken().orEmpty()
        val cfAccountId = app.prefsManager.getCloudflareAccountId().orEmpty()
        val activeSubdomain = currentCfSubdomain.ifBlank { app.prefsManager.getCloudflareSubdomain().orEmpty() }

        if (cfToken.isBlank() || cfAccountId.isBlank()) {
            showAuthDialog = true
            return
        }

        if (activeSubdomain.isBlank()) {
            pendingDeployAfterSubdomain = true
            showSubdomainDialog = true
            return
        }

        val cleanName = CloudflareWorkerPayload.generateRandomWorkerName()
        val scriptContent = CloudflareWorkerPayload.getWorkerScript(context)
        if (scriptContent.isBlank()) {
            Toast.makeText(context, "Ошибка: исходный скрипт worker.js пуст", Toast.LENGTH_SHORT).show()
            return
        }

        isDeploying = true
        deployStepText = context.getString(R.string.socks5_onboard_step1_deploying_step1)
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)

        scope.launch {
            val uploadRes = CloudflareApiClient.deployWorkerScript(cfToken, cfAccountId, cleanName, scriptContent, app.prefsManager)
            if (uploadRes.isFailure) {
                isDeploying = false
                val err = uploadRes.exceptionOrNull()?.message ?: "Ошибка деплоя"
                Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                return@launch
            }

            deployStepText = context.getString(R.string.socks5_onboard_step1_deploying_step2)
            val subRes = CloudflareApiClient.enableWorkerSubdomain(cfToken, cfAccountId, cleanName, app.prefsManager)
            if (subRes.isFailure) {
                isDeploying = false
                val err = subRes.exceptionOrNull()?.message ?: "Ошибка привязки домена"
                Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                return@launch
            }

            deployStepText = context.getString(R.string.socks5_onboard_step1_deploying_step3)
            val fullDomain = "$cleanName.$activeSubdomain.workers.dev"

            val addRes = app.prefsManager.addCustomWorker(
                name = cleanName,
                domain = fullDomain,
                isCloudflarePersonal = true,
                scriptVersion = CloudflareWorkerPayload.SCRIPT_VERSION
            )
            val newId = addRes.getOrNull()?.id ?: UUID.randomUUID().toString()
            app.prefsManager.setActiveWorkerId(newId, fromUserAction = true)
            app.config.customCfDomain = fullDomain
            app.config.useDefaultWorkerSocks5 = false
            app.prefsManager.saveConfig(app.config)

            activeWorkerDomain = fullDomain
            isDeploying = false
            Toast.makeText(context, context.getString(R.string.socks5_onboard_step1_deploy_success), Toast.LENGTH_SHORT).show()
            loadWorkersFromCloudflare()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        DialogBackdropBox(
            onDismiss = onDismiss,
            blurRadiusPx = 70
        ) {
            // Pinned Top-Left Back Button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopStart)
                    .padding(
                        top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp,
                        start = 8.dp
                    )
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_arrow_left),
                        contentDescription = stringResource(R.string.action_back),
                        tint = TextWhite,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .align(Alignment.Center)
                    .adaptiveContainerWidth(440.dp)
                    .fadingEdges(topFadeHeight = 32.dp, bottomFadeHeight = 44.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(
                        top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 60.dp,
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp
                    )
                    .padding(horizontal = 20.dp)
                    .clickable(enabled = false) {}
            ) {
                // Step Progress Indicators (1 • 2 • 3)
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 4.dp)
                ) {
                    for (step in 1..3) {
                        val isCurrent = step == currentStep
                        val isPassed = step < currentStep
                        Box(
                            modifier = Modifier
                                .size(if (isCurrent) 28.dp else 22.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        isCurrent -> ActiveGreenLed
                                        isPassed -> Color(0xFF059669)
                                        else -> Color.White.copy(alpha = 0.12f)
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "$step",
                                color = if (isCurrent || isPassed) Color.Black else TextMuted,
                                fontSize = if (isCurrent) 12.sp else 10.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (step < 3) {
                            Box(
                                modifier = Modifier
                                    .width(28.dp)
                                    .height(2.dp)
                                    .background(
                                        if (currentStep > step) ActiveGreenLed else Color.White.copy(alpha = 0.10f)
                                    )
                            )
                        }
                    }
                }

                // =========================================================================
                // STEP 1: WORKER SETUP (IMPORT / CREATE WORKER)
                // =========================================================================
                if (currentStep == 1) {
                    // Title & Description
                    Text(
                        text = stringResource(R.string.socks5_onboard_step1_title),
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = stringResource(R.string.socks5_onboard_step1_desc),
                        fontSize = 12.sp,
                        color = TextMuted,
                        textAlign = TextAlign.Center,
                        lineHeight = 16.5.sp
                    )

                    // Discovered Workers List or Loading State
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, AmoledBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (isLoadingWorkers) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.padding(vertical = 12.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = ActiveGreenLed,
                                        strokeWidth = 2.dp
                                    )
                                    Text(
                                        text = stringResource(R.string.socks5_onboard_step1_loading),
                                        fontSize = 12.sp,
                                        color = TextMuted
                                    )
                                }
                            } else if (cloudflareWorkers.isNotEmpty()) {
                                Text(
                                    text = stringResource(R.string.socks5_onboard_step1_found_title, cloudflareWorkers.size),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = ActiveGreenLed
                                )

                                cloudflareWorkers.forEach { w ->
                                    val domain = w.fullDomain ?: if (currentCfSubdomain.isNotBlank()) "${w.id}.$currentCfSubdomain.workers.dev" else w.id
                                    val isSelected = (activeWorkerDomain.equals(domain, ignoreCase = true)) || (selectedWorkerId == w.id)

                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (isSelected) ActiveGreenLed.copy(alpha = 0.12f) else Color.Transparent,
                                        border = BorderStroke(1.dp, if (isSelected) ActiveGreenLed.copy(alpha = 0.50f) else AmoledBorder),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                applySelectedWorker(w)
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = w.id,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = TextWhite,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = domain,
                                                    fontSize = 11.sp,
                                                    color = TextMuted,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            if (isSelected) {
                                                Icon(
                                                    painter = painterResource(R.drawable.ic_check),
                                                    contentDescription = null,
                                                    tint = ActiveGreenLed,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            } else {
                                // Empty state
                                Text(
                                    text = stringResource(R.string.socks5_onboard_step1_empty_title),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = CloudflareOrangeDim
                                )
                                Text(
                                    text = stringResource(R.string.socks5_onboard_step1_empty_desc),
                                    fontSize = 11.5.sp,
                                    color = TextMuted,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }

                    // Deploy Button or Progress
                    if (isDeploying) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, CloudflareOrange.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = CloudflareOrange,
                                    strokeWidth = 2.dp
                                )
                                Text(
                                    text = deployStepText,
                                    fontSize = 12.sp,
                                    color = TextWhite
                                )
                            }
                        }
                    } else {
                        val isAuthed = app.prefsManager.isCloudflareAuthorized()
                        if (!isAuthed) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = CloudflareOrange.copy(alpha = 0.15f),
                                border = BorderStroke(1.dp, CloudflareOrange.copy(alpha = 0.60f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(46.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .springPress(onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        showAuthDialog = true
                                    })
                            ) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = stringResource(R.string.socks5_cf_auth_btn_login),
                                        color = CloudflareOrange,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.5.sp
                                    )
                                }
                            }
                        } else {
                            if (currentCfSubdomain.isBlank()) {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = CloudflareOrange.copy(alpha = 0.08f),
                                    border = BorderStroke(1.dp, CloudflareOrange.copy(alpha = 0.35f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_info_circle),
                                                contentDescription = null,
                                                tint = CloudflareOrangeDim,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text(
                                                text = stringResource(R.string.socks5_onboard_subdomain_needed_title),
                                                fontSize = 12.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = CloudflareOrangeDim
                                            )
                                        }
                                        Text(
                                            text = stringResource(R.string.socks5_onboard_subdomain_needed_desc),
                                            fontSize = 11.5.sp,
                                            color = TextMuted,
                                            lineHeight = 15.5.sp
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = CloudflareOrange.copy(alpha = 0.18f),
                                            border = BorderStroke(1.dp, CloudflareOrange.copy(alpha = 0.50f)),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(38.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .springPress(onClick = {
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    showSubdomainDialog = true
                                                })
                                        ) {
                                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                Text(
                                                    text = stringResource(R.string.socks5_onboard_subdomain_btn_setup),
                                                    color = CloudflareOrange,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 12.5.sp
                                                )
                                            }
                                        }
                                    }
                                }
                            } else {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color.Transparent,
                                    border = BorderStroke(1.dp, AmoledBorder),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            showSubdomainDialog = true
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = stringResource(R.string.socks5_onboard_subdomain_active_label),
                                            fontSize = 11.5.sp,
                                            color = TextMuted
                                        )
                                        Text(
                                            text = "$currentCfSubdomain.workers.dev",
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = CloudflareOrangeDim
                                        )
                                    }
                                }
                            }

                            // Action to deploy fresh worker
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color.Transparent,
                                border = BorderStroke(1.dp, AmoledBorder),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .springPress(onClick = { deployNewWorkerIn1Click() })
                            ) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = stringResource(R.string.socks5_onboard_step1_btn_deploy),
                                        color = TextWhite.copy(alpha = 0.92f),
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                    }

                    // Active Worker Indicator Card
                    if (activeWorkerDomain.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, ActiveGreenLed.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_check),
                                    contentDescription = null,
                                    tint = ActiveGreenLed,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "${stringResource(R.string.socks5_onboard_step1_active_badge)} $activeWorkerDomain",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = TextWhite,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    // Next Step Button
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (activeWorkerDomain.isNotBlank()) ActiveGreenLed.copy(alpha = 0.15f) else Color.Transparent,
                        border = BorderStroke(1.dp, if (activeWorkerDomain.isNotBlank()) ActiveGreenLed.copy(alpha = 0.60f) else AmoledBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .springPress(enabled = activeWorkerDomain.isNotBlank(), onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                currentStep = 2
                            })
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = stringResource(R.string.socks5_onboard_step1_btn_next),
                                color = if (activeWorkerDomain.isNotBlank()) ActiveGreenLed else TextMuted,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    }
                }

                // =========================================================================
                // STEP 2: WHERE IS WORKER MANAGER (NAVIGATION)
                // =========================================================================
                if (currentStep == 2) {
                    // Title
                    Text(
                        text = stringResource(R.string.socks5_onboard_step2_title),
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite,
                        textAlign = TextAlign.Center
                    )

                    // Card 1: Swipe navigation gesture
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, AmoledBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_arrow_left),
                                    contentDescription = null,
                                    tint = Color(0xFF00F5D4),
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = stringResource(R.string.socks5_onboard_step2_swipe_title),
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF00F5D4)
                                )
                            }
                            Text(
                                text = stringResource(R.string.socks5_onboard_step2_swipe_desc),
                                fontSize = 12.sp,
                                color = TextMuted,
                                lineHeight = 16.5.sp
                            )
                        }
                    }

                    // Card 2: Features of the manager
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, AmoledBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.socks5_onboard_step2_features_title),
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextWhite
                            )
                            Text(
                                text = stringResource(R.string.socks5_onboard_step2_features_desc),
                                fontSize = 12.sp,
                                color = TextMuted,
                                lineHeight = 16.5.sp
                            )
                        }
                    }

                    // Buttons Row: Back and Next
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, AmoledBorder),
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .springPress(onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    currentStep = 1
                                })
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = stringResource(R.string.socks5_onboard_step2_btn_back),
                                    color = TextWhite.copy(alpha = 0.85f),
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 13.sp
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = ActiveGreenLed.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, ActiveGreenLed.copy(alpha = 0.60f)),
                            modifier = Modifier
                                .weight(1.5f)
                                .height(46.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .springPress(onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    currentStep = 3
                                })
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = stringResource(R.string.socks5_onboard_step2_btn_next),
                                    color = ActiveGreenLed,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.5.sp
                                )
                            }
                        }
                    }
                }

                // =========================================================================
                // STEP 3: SCRIPT UPDATES & FINALIZE
                // =========================================================================
                if (currentStep == 3) {
                    // Title
                    Text(
                        text = stringResource(R.string.socks5_onboard_step3_title),
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite,
                        textAlign = TextAlign.Center
                    )

                    // Card 1: Where the update button is located
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, AmoledBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_refresh),
                                    contentDescription = null,
                                    tint = CloudflareOrangeDim,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = stringResource(R.string.socks5_onboard_step3_where_title),
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = CloudflareOrangeDim
                                )
                            }
                            Text(
                                text = stringResource(R.string.socks5_onboard_step3_where_desc),
                                fontSize = 12.sp,
                                color = TextMuted,
                                lineHeight = 16.5.sp
                            )
                        }
                    }

                    // Card 2: Why update scripts
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, AmoledBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.socks5_onboard_step3_why_title),
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextWhite
                            )
                            Text(
                                text = stringResource(R.string.socks5_onboard_step3_why_desc),
                                fontSize = 12.sp,
                                color = TextMuted,
                                lineHeight = 16.5.sp
                            )
                        }
                    }

                    // Buttons Row: Back and Finish/Start
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, AmoledBorder),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .springPress(onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    currentStep = 2
                                })
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = stringResource(R.string.socks5_onboard_step2_btn_back),
                                    color = TextWhite.copy(alpha = 0.85f),
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 13.sp
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = ActiveGreenLed.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, ActiveGreenLed.copy(alpha = 0.60f)),
                            modifier = Modifier
                                .weight(2f)
                                .height(48.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .springPress(onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onDismiss()
                                })
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = stringResource(R.string.socks5_onboard_step3_btn_finish),
                                    color = ActiveGreenLed,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }

                    // Secondary: Jump straight to Worker Manager
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, AmoledBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .springPress(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onOpenWorkerManager()
                            })
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = stringResource(R.string.socks5_onboard_btn_manager),
                                color = TextMuted,
                                fontWeight = FontWeight.Medium,
                                fontSize = 12.5.sp
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAuthDialog) {
        Socks5CloudflareAuthDialog(
            onDismiss = { showAuthDialog = false },
            onAuthSuccess = {
                showAuthDialog = false
                currentCfSubdomain = app.prefsManager.getCloudflareSubdomain().orEmpty()
                loadWorkersFromCloudflare()
            },
            onCustomWorkerSaved = { domain ->
                showAuthDialog = false
                activeWorkerDomain = domain
                loadWorkersFromCloudflare()
            },
            onOpenManualGuide = {
                showAuthDialog = false
                onOpenWorkerManager()
            }
        )
    }

    if (showSubdomainDialog) {
        CloudflareSubdomainSetupDialog(
            onDismiss = {
                showSubdomainDialog = false
                pendingDeployAfterSubdomain = false
            },
            onSubdomainConfigured = { registered ->
                showSubdomainDialog = false
                currentCfSubdomain = registered
                if (pendingDeployAfterSubdomain) {
                    pendingDeployAfterSubdomain = false
                    deployNewWorkerIn1Click()
                } else {
                    loadWorkersFromCloudflare()
                }
            },
            initialSubdomain = currentCfSubdomain
        )
    }
}
