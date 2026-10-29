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

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.core.CircuitState
import com.mirrly.tgproxy.core.DomainFormatStatus
import com.mirrly.tgproxy.core.ProxyMode
import com.mirrly.tgproxy.core.WorkerDomainNormalizer
import com.mirrly.tgproxy.core.WorkerProfile
import com.mirrly.tgproxy.core.WorkerStatus
import com.mirrly.tgproxy.service.PreferencesManager
import com.mirrly.tgproxy.service.WorkerFailoverManager
import com.mirrly.tgproxy.service.WorkerPingTester
import com.mirrly.tgproxy.service.cloudflare.CloudflareApiClient
import com.mirrly.tgproxy.service.cloudflare.CloudflareWorkerPayload
import com.mirrly.tgproxy.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class ManagerSection(@StringRes val titleRes: Int) {
    WORKERS(R.string.wm_tab_workers),
    DEPLOY(R.string.wm_tab_deploy),
    SHARE(R.string.wm_tab_share),
    SCANNER(R.string.wm_tab_scanner),
    GUIDE(R.string.wm_tab_guide)
}

/**
 * MirrlyUI Worker Manager Screen.
 * Strictly adheres to:
 * - Dogma 1: Zero Background / Outline-Only architecture.
 * - Dogma 2: Zero-Popup doctrine (All dialogs rewritten to MirrlyModalBottomSheet).
 * - Kinetic spring physics (Modifier.springPress) on all interactive elements.
 * - Zero emoji & anti-slop technical precision.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkerManagerScreen(
    prefs: PreferencesManager,
    onBack: () -> Unit,
    initialSection: ManagerSection = ManagerSection.WORKERS,
    initialProtocol: ProxyMode? = null,
    onOpenAnalytics: (() -> Unit)? = null,
    onOpenSpeedTest: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current

    val currentProxyMode by prefs.proxyModeFlow.collectAsState(initial = prefs.getEffectiveProxyMode())
    var targetProtocol by remember(initialProtocol) {
        mutableStateOf(initialProtocol ?: currentProxyMode)
    }

    val activeProtoColor = if (targetProtocol == ProxyMode.MTPROTO) MtprotoAccent else Socks5Accent

    val sections = remember { ManagerSection.values().toList() }

    var currentSection by remember(initialSection) {
        mutableStateOf(initialSection)
    }

    // Workers State
    val activeWorkerIdMtproto by prefs.activeWorkerIdMtprotoFlow.collectAsState()
    val activeWorkerIdSocks5 by prefs.activeWorkerIdSocks5Flow.collectAsState()
    val activeWorkerId = if (targetProtocol == ProxyMode.MTPROTO) activeWorkerIdMtproto else activeWorkerIdSocks5

    var customWorkers by remember { mutableStateOf(prefs.getCustomWorkers()) }
    val devWorkers = remember { prefs.getDeveloperWorkers() }

    LaunchedEffect(Unit) {
        customWorkers = prefs.getCustomWorkers()
    }

    val pingResults = remember { mutableStateMapOf<String, Pair<WorkerStatus, Long?>>() }
    var isPinging by remember { mutableStateOf(false) }
    var updatingCardWorkerId by remember { mutableStateOf<String?>(null) }

    var isSearchVisible by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val searchFocusRequester = remember { FocusRequester() }

    var showAddSheet by remember { mutableStateOf(false) }
    var showQrScannerSheet by remember { mutableStateOf(false) }
    var prefillDomain by remember { mutableStateOf("") }
    var prefillName by remember { mutableStateOf("") }
    var selectedShareWorker by remember { mutableStateOf<WorkerProfile?>(null) }
    var workerToDelete by remember { mutableStateOf<WorkerProfile?>(null) }
    var workerNotOwnedForUpdate by remember { mutableStateOf<WorkerProfile?>(null) }

    var headerHeightDp by remember { mutableStateOf(175.dp) }

    fun handleDismiss() {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onBack()
    }

    BackHandler {
        handleDismiss()
    }

    fun refreshWorkers() {
        customWorkers = prefs.getCustomWorkers()
    }

    fun startPingAll() {
        if (isPinging) return
        isPinging = true
        scope.launch {
            val all = devWorkers + customWorkers
            for (w in all) {
                val res = WorkerPingTester.pingWorker(w.domain)
                pingResults[w.domain] = res
            }
            isPinging = false
            Toast.makeText(context, context.getString(R.string.wm_toast_ping_finished), Toast.LENGTH_SHORT).show()
        }
    }

    fun shareWorker(worker: WorkerProfile) {
        if (worker.isDeveloperWorker) {
            Toast.makeText(context, context.getString(R.string.wm_toast_cant_share_official), Toast.LENGTH_SHORT).show()
            return
        }

        val encodedDomain = Uri.encode(worker.domain)
        val encodedName = Uri.encode(worker.name)
        val deepLink = "mirrly://worker?domain=$encodedDomain&name=$encodedName"
        val httpsLink = "https://mirrly.app/worker?domain=$encodedDomain&name=$encodedName"

        val shareText = context.getString(
            R.string.wm_share_template,
            worker.name,
            worker.domain,
            deepLink,
            httpsLink,
            "https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/releases"
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.wm_share_subject))
        }
        val chooser = Intent.createChooser(intent, context.getString(R.string.wm_share_chooser_title)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    val hasRateLimitedWorkers by remember {
        derivedStateOf {
            pingResults.values.any { it.first == WorkerStatus.RATE_LIMITED_429 }
        }
    }

    val pingRotation by animateFloatAsState(
        targetValue = if (isPinging) 360f else 0f,
        animationSpec = if (isPinging) infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ) else tween(300),
        label = "pingRotation"
    )

    LaunchedEffect(isSearchVisible) {
        if (isSearchVisible) {
            searchFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    val allWorkers = remember(customWorkers) { customWorkers }
    val filteredWorkers = remember(allWorkers, searchQuery) {
        val trimmed = searchQuery.trim()
        if (trimmed.isEmpty()) {
            allWorkers
        } else {
            allWorkers.filter { worker ->
                worker.name.contains(trimmed, ignoreCase = true) ||
                        worker.domain.contains(trimmed, ignoreCase = true)
            }
        }
    }

    val isActiveWorkerRateLimited by remember(allWorkers, activeWorkerId) {
        derivedStateOf {
            val activeDomain = allWorkers.firstOrNull { it.id == activeWorkerId }?.domain
            activeDomain != null && pingResults[activeDomain]?.first == WorkerStatus.RATE_LIMITED_429
        }
    }

    val workersListState = rememberLazyListState()

    Box(modifier = Modifier.fillMaxSize()) {
        // Ambient background particles
        CyberParticlesOverlay(
            modifier = Modifier.fillMaxSize(),
            particleCount = 14,
            alphaMultiplier = 0.45f
        )

        // 1. SECTION CONTENT BODY
        AnimatedContent(
            targetState = currentSection,
            modifier = Modifier
                .fillMaxSize()
                .fadingEdges(topFadeHeight = 28.dp, bottomFadeHeight = 44.dp),
            transitionSpec = {
                val fromIndex = sections.indexOf(initialState)
                val toIndex = sections.indexOf(targetState)
                if (toIndex >= fromIndex) {
                    (slideInHorizontally(
                        animationSpec = spring(dampingRatio = 0.88f, stiffness = Spring.StiffnessMediumLow),
                        initialOffsetX = { fullWidth -> (fullWidth * 0.28f).toInt() }
                    ) + fadeIn(animationSpec = tween(220))).togetherWith(
                        slideOutHorizontally(
                            animationSpec = spring(dampingRatio = 0.88f, stiffness = Spring.StiffnessMediumLow),
                            targetOffsetX = { fullWidth -> (-fullWidth * 0.28f).toInt() }
                        ) + fadeOut(animationSpec = tween(180))
                    )
                } else {
                    (slideInHorizontally(
                        animationSpec = spring(dampingRatio = 0.88f, stiffness = Spring.StiffnessMediumLow),
                        initialOffsetX = { fullWidth -> (-fullWidth * 0.28f).toInt() }
                    ) + fadeIn(animationSpec = tween(220))).togetherWith(
                        slideOutHorizontally(
                            animationSpec = spring(dampingRatio = 0.88f, stiffness = Spring.StiffnessMediumLow),
                            targetOffsetX = { fullWidth -> (fullWidth * 0.28f).toInt() }
                        ) + fadeOut(animationSpec = tween(180))
                    )
                }
            },
            label = "managerBodySection"
        ) { section ->
            when (section) {
                ManagerSection.WORKERS -> {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (filteredWorkers.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(
                                        top = headerHeightDp,
                                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 80.dp,
                                        start = 20.dp,
                                        end = 20.dp
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (searchQuery.isNotEmpty()) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_shield),
                                            contentDescription = null,
                                            tint = TextMuted,
                                            modifier = Modifier.size(46.dp)
                                        )
                                        Text(
                                            text = stringResource(R.string.wm_nodes_not_found),
                                            color = TextMuted,
                                            fontSize = 13.5.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                } else {
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = Color.Transparent,
                                        border = BorderStroke(1.dp, AmoledBorder),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(20.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Icon(
                                                painter = painterResource(id = R.drawable.ic_diag_worker),
                                                contentDescription = null,
                                                tint = activeProtoColor,
                                                modifier = Modifier.size(36.dp)
                                            )
                                            Text(
                                                text = "Воркеры не добавлены",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 15.sp,
                                                color = TextWhite,
                                                textAlign = TextAlign.Center
                                            )
                                            Text(
                                                text = "Добавьте собственный Cloudflare Worker узел для WSS туннелирования трафика.",
                                                fontSize = 12.sp,
                                                color = TextMuted,
                                                textAlign = TextAlign.Center,
                                                lineHeight = 16.sp
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Surface(
                                                    shape = RoundedCornerShape(10.dp),
                                                    color = Color.Transparent,
                                                    border = BorderStroke(1.dp, AmoledBorder),
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(10.dp))
                                                        .springPress(onClick = {
                                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                            currentSection = ManagerSection.DEPLOY
                                                        })
                                                ) {
                                                    Text(
                                                        text = "Деплой",
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = Color(0xFFF38020),
                                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                                    )
                                                }
                                                Surface(
                                                    shape = RoundedCornerShape(10.dp),
                                                    color = Color.Transparent,
                                                    border = BorderStroke(1.dp, activeProtoColor),
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(10.dp))
                                                        .springPress(onClick = {
                                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                            prefillDomain = ""
                                                            prefillName = ""
                                                            showAddSheet = true
                                                        })
                                                ) {
                                                    Text(
                                                        text = "Добавить вручную",
                                                        color = activeProtoColor,
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            LazyColumn(
                                state = workersListState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(
                                    top = headerHeightDp + 8.dp,
                                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 90.dp,
                                    start = 16.dp,
                                    end = 16.dp
                               ),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Search bar if visible
                                if (isSearchVisible) {
                                    item(key = "search_field") {
                                        OutlinedTextField(
                                            value = searchQuery,
                                            onValueChange = { searchQuery = it },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .focusRequester(searchFocusRequester),
                                            placeholder = {
                                                Text(
                                                    text = stringResource(R.string.wm_search_placeholder),
                                                    color = TextMuted,
                                                    fontSize = 12.5.sp
                                                )
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    painter = painterResource(id = R.drawable.ic_search),
                                                    contentDescription = null,
                                                    tint = if (searchQuery.isNotEmpty()) activeProtoColor else TextMuted,
                                                    modifier = Modifier.size(17.dp)
                                                )
                                            },
                                            trailingIcon = {
                                                if (searchQuery.isNotEmpty()) {
                                                    IconButton(onClick = {
                                                        searchQuery = ""
                                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    }) {
                                                        Text("✕", color = TextMuted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                                    }
                                                }
                                            },
                                            singleLine = true,
                                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                            keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
                                            shape = RoundedCornerShape(12.dp),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedContainerColor = Color.Transparent,
                                                unfocusedContainerColor = Color.Transparent,
                                                focusedBorderColor = activeProtoColor,
                                                unfocusedBorderColor = AmoledBorder,
                                                focusedTextColor = TextWhite,
                                                unfocusedTextColor = TextWhite,
                                                cursorColor = activeProtoColor
                                            )
                                        )
                                    }
                                }

                                // Rate Limit warning banner if triggered
                                if (hasRateLimitedWorkers) {
                                    item(key = "rate_limit_warning") {
                                        val warningColor = if (isActiveWorkerRateLimited) Color(0xFFFF1744) else Color(0xFFF5A623)
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = Color.Transparent,
                                            border = BorderStroke(1.dp, warningColor),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(12.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(7.dp)
                                                        .clip(CircleShape)
                                                        .background(warningColor)
                                                )
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = stringResource(if (isActiveWorkerRateLimited) R.string.wm_limit_429_active_title else R.string.wm_limit_rate_limit_title),
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 12.sp,
                                                        color = warningColor
                                                    )
                                                    Text(
                                                        text = stringResource(if (isActiveWorkerRateLimited) R.string.wm_limit_429_active_desc else R.string.wm_limit_429_public_desc),
                                                        fontSize = 11.5.sp,
                                                        color = TextWhite,
                                                        lineHeight = 15.sp
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                itemsIndexed(
                                    items = filteredWorkers,
                                    key = { _, item -> item.id }
                                ) { _, worker ->
                                    val isActive = worker.id == activeWorkerId
                                    val isActiveMtproto = worker.id == activeWorkerIdMtproto
                                    val isActiveSocks5 = worker.id == activeWorkerIdSocks5
                                    GlassWorkerCard(
                                        worker = worker,
                                        isActive = isActive,
                                        isActiveMtproto = isActiveMtproto,
                                        isActiveSocks5 = isActiveSocks5,
                                        activeAccentColor = activeProtoColor,
                                        pingInfo = pingResults[worker.domain],
                                        onSelect = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            prefs.setActiveWorkerId(worker.id, mode = targetProtocol)
                                            val protoLabel = if (targetProtocol == ProxyMode.MTPROTO) "MTProto" else "SOCKS5"
                                            Toast.makeText(context, context.getString(R.string.wm_toast_activated, "${worker.name} ($protoLabel)"), Toast.LENGTH_SHORT).show()
                                        },
                                        onVisitSite = {
                                            try {
                                                val url = "https://${worker.domain}"
                                                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                                context.startActivity(browserIntent)
                                            } catch (_: Exception) {
                                                Toast.makeText(context, "Не удалось открыть браузер", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        onUpdateScript = if (!worker.isDeveloperWorker) {
                                            {
                                                val cfToken = prefs.getCloudflareToken()
                                                val cfAccountId = prefs.getCloudflareAccountId()
                                                if (cfToken.isNullOrBlank() || cfAccountId.isNullOrBlank()) {
                                                    Toast.makeText(context, "Авторизуйтесь в Cloudflare (вкладка Деплой)", Toast.LENGTH_LONG).show()
                                                    currentSection = ManagerSection.DEPLOY
                                                } else if (!prefs.isWorkerOwnedByCurrentUser(worker)) {
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    workerNotOwnedForUpdate = worker
                                                } else {
                                                    updatingCardWorkerId = worker.id
                                                    scope.launch {
                                                        val scriptName = if (worker.domain.contains(".workers.dev")) {
                                                            worker.domain.substringBefore(".workers.dev").substringBefore(".")
                                                        } else {
                                                            worker.name.trim().lowercase().replace(Regex("[^a-z0-9_-]"), "-")
                                                        }
                                                        val scriptCode = CloudflareWorkerPayload.getWorkerScript(context)
                                                        Toast.makeText(context, "Загрузка скрипта на Cloudflare...", Toast.LENGTH_SHORT).show()
                                                        val deployRes = CloudflareApiClient.deployWorkerScript(
                                                            cfToken,
                                                            cfAccountId,
                                                            scriptName,
                                                            scriptCode,
                                                            prefs
                                                        )
                                                        if (deployRes.isSuccess) {
                                                            CloudflareApiClient.verifyWorkerHealth(worker.domain, maxWaitSeconds = 6)
                                                            prefs.updateCustomWorkerScriptVersion(worker.domain, CloudflareWorkerPayload.SCRIPT_VERSION)
                                                            refreshWorkers()
                                                            Toast.makeText(context, context.getString(R.string.cf_deploy_toast_updated), Toast.LENGTH_SHORT).show()
                                                        } else {
                                                            val err = deployRes.exceptionOrNull()?.message ?: "Ошибка загрузки"
                                                            Toast.makeText(context, "Ошибка обновления: $err", Toast.LENGTH_LONG).show()
                                                        }
                                                        updatingCardWorkerId = null
                                                    }
                                                }
                                            }
                                        } else null,
                                        isUpdatingScript = updatingCardWorkerId == worker.id,
                                        onShare = if (!worker.isDeveloperWorker) {
                                            {
                                                selectedShareWorker = worker
                                                currentSection = ManagerSection.SHARE
                                            }
                                        } else null,
                                        onDelete = if (!worker.isDeveloperWorker) {
                                            {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                workerToDelete = worker
                                            }
                                        } else null
                                    )
                                }
                            }
                        }

                        // Bottom Floating Add Worker Outline Button
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, activeProtoColor),
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(
                                    start = 20.dp,
                                    end = 20.dp,
                                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp
                                )
                                .height(46.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .springPress(onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    prefillDomain = ""
                                    prefillName = ""
                                    showAddSheet = true
                                })
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_diag_worker),
                                    contentDescription = null,
                                    tint = activeProtoColor,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.wm_btn_add),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = activeProtoColor
                                )
                            }
                        }
                    }
                }
                ManagerSection.DEPLOY -> {
                    CloudflareDeploySection(
                        prefs = prefs,
                        activeProtoColor = activeProtoColor,
                        onWorkerDeployed = { refreshWorkers() },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                top = headerHeightDp,
                                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 30.dp
                            )
                    )
                }
                ManagerSection.SHARE -> {
                    ShareWorkerContent(
                        workers = customWorkers,
                        activeWorkerId = activeWorkerId,
                        activeAccentColor = activeProtoColor,
                        selectedWorker = selectedShareWorker?.takeIf { !it.isDeveloperWorker },
                        onSelectWorker = { selectedShareWorker = it },
                        onShare = { shareWorker(it) },
                        onAddWorkerClick = {
                            prefillDomain = ""
                            prefillName = ""
                            showAddSheet = true
                        },
                        headerPadding = headerHeightDp
                    )
                }
                ManagerSection.SCANNER -> {
                    ScannerWorkerContent(
                        activeAccentColor = activeProtoColor,
                        onQrScanned = { rawScanned ->
                            val parsed = WorkerDomainNormalizer.normalize(rawScanned)
                            if (parsed.isValid) {
                                prefillDomain = parsed.cleanDomain
                                prefillName = parsed.suggestedName
                                showAddSheet = true
                                Toast.makeText(context, context.getString(R.string.wm_toast_qr_recognized, parsed.cleanDomain), Toast.LENGTH_SHORT).show()
                            } else if (rawScanned.isNotBlank()) {
                                prefillDomain = rawScanned
                                prefillName = ""
                                showAddSheet = true
                            }
                        },
                        onPasteFromClipboard = {
                            val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString().orEmpty().trim()
                            if (clip.isNotBlank()) {
                                val parsed = WorkerDomainNormalizer.normalize(clip)
                                prefillDomain = parsed.cleanDomain
                                prefillName = parsed.suggestedName
                                showAddSheet = true
                                Toast.makeText(context, context.getString(R.string.wm_toast_addr_pasted), Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, context.getString(R.string.wm_toast_clipboard_empty), Toast.LENGTH_SHORT).show()
                            }
                        },
                        headerPadding = headerHeightDp
                    )
                }
                ManagerSection.GUIDE -> {
                    ManualWorkerGuideContent(
                        prefs = prefs,
                        activeAccentColor = activeProtoColor,
                        targetProtocol = targetProtocol,
                        onWorkerAdded = {
                            refreshWorkers()
                            currentSection = ManagerSection.WORKERS
                        },
                        headerPadding = headerHeightDp
                    )
                }
            }
        }

        // 2. PINNED MINIMALIST OUTLINE HEADER
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coordinates ->
                    val heightInDp = with(density) { coordinates.size.height.toDp() }
                    if (heightInDp > 0.dp && heightInDp != headerHeightDp) {
                        headerHeightDp = heightInDp
                    }
                }
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.96f),
                            Color.Black.copy(alpha = 0.90f),
                            Color.Black.copy(alpha = 0.00f)
                        )
                    )
                )
                .padding(bottom = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Top App Bar
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = stringResource(R.string.wm_title),
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.5.sp,
                                color = TextWhite,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = when (currentSection) {
                                    ManagerSection.DEPLOY -> "Cloudflare Worker Деплой"
                                    ManagerSection.GUIDE -> "Инструкция по созданию"
                                    ManagerSection.SHARE -> "Поделиться конфигурацией"
                                    ManagerSection.SCANNER -> "Сканирование QR"
                                    else -> if (targetProtocol == ProxyMode.MTPROTO) "Telegram MTProto WSS" else "Telegram SOCKS5 Relay"
                                },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = when (currentSection) {
                                    ManagerSection.DEPLOY -> Color(0xFFF38020)
                                    ManagerSection.GUIDE -> Color(0xFF38BDF8)
                                    else -> activeProtoColor
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = { handleDismiss() },
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_arrow_left),
                                contentDescription = stringResource(R.string.action_back),
                                tint = TextWhite,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    },
                    actions = {
                        if (currentSection == ManagerSection.WORKERS) {
                            if (onOpenAnalytics != null) {
                                IconButton(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onOpenAnalytics()
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_diag_formula),
                                        contentDescription = stringResource(R.string.wm_desc_analytics),
                                        tint = activeProtoColor,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            IconButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    if (isSearchVisible && searchQuery.isNotEmpty()) {
                                        searchQuery = ""
                                    }
                                    isSearchVisible = !isSearchVisible
                                    if (!isSearchVisible) {
                                        keyboardController?.hide()
                                    }
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_search),
                                    contentDescription = stringResource(R.string.action_search),
                                    tint = if (isSearchVisible || searchQuery.isNotEmpty()) activeProtoColor else TextWhite,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            IconButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    startPingAll()
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_refresh),
                                    contentDescription = stringResource(R.string.wm_desc_measure_ping),
                                    tint = if (isPinging) activeProtoColor else TextWhite,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .rotate(pingRotation)
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )

                // Protocol Switcher (MTProto vs SOCKS5)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Transparent)
                        .border(1.dp, AmoledBorder, RoundedCornerShape(10.dp))
                        .padding(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(
                        ProxyMode.MTPROTO to "MTProto",
                        ProxyMode.SOCKS5 to "SOCKS5"
                    ).forEach { (mode, label) ->
                        val isSel = targetProtocol == mode
                        val modeColor = if (mode == ProxyMode.MTPROTO) MtprotoAccent else Socks5Accent
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color.Transparent,
                            border = BorderStroke(if (isSel) 1.dp else 0.dp, if (isSel) modeColor else Color.Transparent),
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(8.dp))
                                .springPress(onClick = {
                                    if (targetProtocol != mode) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        targetProtocol = mode
                                    }
                                })
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = label,
                                    fontSize = 11.5.sp,
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSel) modeColor else TextMuted
                                )
                            }
                        }
                    }
                }

                // Section Tabs Row (Outline only)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    sections.forEach { sec ->
                        val isSelected = currentSection == sec
                        val secColor = when (sec) {
                            ManagerSection.DEPLOY -> Color(0xFFF38020)
                            ManagerSection.GUIDE -> Color(0xFF38BDF8)
                            else -> activeProtoColor
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, if (isSelected) secColor else AmoledBorder),
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .springPress(onClick = {
                                    if (currentSection != sec) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        currentSection = sec
                                    }
                                })
                        ) {
                            Text(
                                text = stringResource(sec.titleRes),
                                color = if (isSelected) secColor else TextMuted,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // ── BOTTOM SHEETS (DOGMA 2 ZERO-POPUP) ──

    if (showAddSheet) {
        AddWorkerSheet(
            prefs = prefs,
            activeAccentColor = activeProtoColor,
            initialDomain = prefillDomain,
            initialName = prefillName,
            onOpenQrScanner = {
                showAddSheet = false
                showQrScannerSheet = true
            },
            onDismiss = {
                showAddSheet = false
                prefillDomain = ""
                prefillName = ""
            },
            onAdd = { name, domain ->
                val res = prefs.addCustomWorker(name, domain)
                res.fold(
                    onSuccess = { added ->
                        prefs.setActiveWorkerId(added.id, mode = targetProtocol)
                        refreshWorkers()
                        showAddSheet = false
                        prefillDomain = ""
                        prefillName = ""
                        Toast.makeText(context, context.getString(R.string.wm_toast_worker_added, added.name), Toast.LENGTH_SHORT).show()
                    },
                    onFailure = { err ->
                        Toast.makeText(context, err.message ?: context.getString(R.string.wm_toast_worker_add_error, ""), Toast.LENGTH_SHORT).show()
                    }
                )
            }
        )
    }

    if (showQrScannerSheet) {
        QrScannerSheet(
            activeAccentColor = activeProtoColor,
            onDismiss = { showQrScannerSheet = false },
            onQrScanned = { rawScanned ->
                showQrScannerSheet = false
                val parsed = WorkerDomainNormalizer.normalize(rawScanned)
                if (parsed.isValid) {
                    prefillDomain = parsed.cleanDomain
                    prefillName = parsed.suggestedName
                    showAddSheet = true
                    Toast.makeText(context, context.getString(R.string.wm_toast_qr_recognized, parsed.cleanDomain), Toast.LENGTH_SHORT).show()
                } else if (rawScanned.isNotBlank()) {
                    prefillDomain = rawScanned
                    prefillName = ""
                    showAddSheet = true
                }
            }
        )
    }

    workerToDelete?.let { worker ->
        DeleteWorkerConfirmSheet(
            worker = worker,
            onDismiss = { workerToDelete = null },
            onConfirm = {
                prefs.deleteCustomWorker(worker.id)
                val remaining = prefs.getCustomWorkers()
                val app = com.mirrly.tgproxy.MirrlyApplication.instance
                if (remaining.isEmpty()) {
                    app.config.customCfDomain = ""
                    app.prefsManager.saveConfig(app.config)
                } else {
                    app.config.customCfDomain = prefs.getActiveWorker().domain
                    app.prefsManager.saveConfig(app.config)
                }
                refreshWorkers()
                workerToDelete = null
                Toast.makeText(context, context.getString(R.string.wm_toast_worker_deleted), Toast.LENGTH_SHORT).show()
            }
        )
    }

    workerNotOwnedForUpdate?.let { worker ->
        WorkerNotOwnedSheet(
            worker = worker,
            onDismiss = { workerNotOwnedForUpdate = null },
            onOpenGuide = {
                workerNotOwnedForUpdate = null
                currentSection = ManagerSection.GUIDE
            }
        )
    }
}

/**
 * Outline-Only Worker Card.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GlassWorkerCard(
    modifier: Modifier = Modifier,
    worker: WorkerProfile,
    isActive: Boolean,
    isActiveMtproto: Boolean = false,
    isActiveSocks5: Boolean = false,
    activeAccentColor: Color,
    pingInfo: Pair<WorkerStatus, Long?>?,
    onSelect: () -> Unit,
    onVisitSite: (() -> Unit)? = null,
    onUpdateScript: (() -> Unit)? = null,
    isUpdatingScript: Boolean = false,
    onShare: (() -> Unit)?,
    onDelete: (() -> Unit)?
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    val status = pingInfo?.first ?: WorkerStatus.UNKNOWN
    val pingMs = pingInfo?.second

    val circuitRecord = WorkerFailoverManager.getCircuitRecord(worker.id)
    val circuitState = circuitRecord?.state ?: CircuitState.CLOSED

    var currentRemSeconds by remember(circuitRecord?.cooldownUntilTimestamp, circuitState) {
        mutableStateOf(circuitRecord?.remainingCooldownSeconds ?: 0L)
    }

    LaunchedEffect(circuitRecord?.cooldownUntilTimestamp, circuitState) {
        while (true) {
            val rem = circuitRecord?.remainingCooldownSeconds ?: 0L
            currentRemSeconds = rem
            if (rem <= 0L || circuitRecord?.state != CircuitState.OPEN) {
                break
            }
            delay(1000L)
        }
    }

    val isCircuitBroken = circuitState == CircuitState.OPEN && currentRemSeconds > 0L

    val (statusColor, statusText) = when {
        isCircuitBroken -> {
            Pair(Color(0xFFFF1744), stringResource(R.string.wm_status_quarantine, currentRemSeconds))
        }
        circuitState == CircuitState.HALF_OPEN || (circuitState == CircuitState.OPEN && currentRemSeconds <= 0L) -> {
            Pair(Color(0xFFF5A623), stringResource(R.string.wm_status_checking))
        }
        else -> {
            val color = when (status) {
                WorkerStatus.ONLINE -> if ((pingMs ?: 0) < 250) ActiveGreenLed else Color(0xFFF5A623)
                WorkerStatus.RATE_LIMITED_429 -> Color(0xFFFF1744)
                WorkerStatus.ERROR_UNREACHABLE -> Color(0xFFF5A623)
                WorkerStatus.UNKNOWN -> TextMuted
            }
            val text = when (status) {
                WorkerStatus.ONLINE -> stringResource(R.string.wm_status_ms, pingMs ?: 0)
                WorkerStatus.RATE_LIMITED_429 -> stringResource(R.string.wm_limit_429)
                WorkerStatus.ERROR_UNREACHABLE -> stringResource(R.string.wm_status_unreachable)
                WorkerStatus.UNKNOWN -> "—"
            }
            Pair(color, text)
        }
    }

    val cardBorderColor = if (isActive) activeAccentColor else AmoledBorder

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        border = BorderStroke(if (isActive) 1.2.dp else 1.dp, cardBorderColor),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onSelect,
                onLongClick = if (!worker.isDeveloperWorker) {
                    {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val clip = ClipData.newPlainText("Worker Domain", worker.domain)
                        clipboard?.setPrimaryClip(clip)
                        Toast.makeText(context, context.getString(R.string.wm_toast_domain_copied), Toast.LENGTH_SHORT).show()
                    }
                } else null
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Top Row: Status Dot + Name + Badges + Ping Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )

                    Text(
                        text = worker.name,
                        color = TextWhite,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (isActiveMtproto) {
                        Surface(
                            shape = RoundedCornerShape(5.dp),
                            color = Color.Transparent,
                            border = BorderStroke(0.8.dp, MtprotoAccent)
                        ) {
                            Text(
                                text = "MTPROTO",
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.4.sp,
                                color = MtprotoAccent,
                                modifier = Modifier.padding(horizontal = 4.5.dp, vertical = 1.5.dp)
                            )
                        }
                    }

                    if (isActiveSocks5) {
                        Surface(
                            shape = RoundedCornerShape(5.dp),
                            color = Color.Transparent,
                            border = BorderStroke(0.8.dp, Socks5Accent)
                        ) {
                            Text(
                                text = "SOCKS5",
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.4.sp,
                                color = Socks5Accent,
                                modifier = Modifier.padding(horizontal = 4.5.dp, vertical = 1.5.dp)
                            )
                        }
                    }
                }

                // Monospace Ping Status Badge (Outline only)
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color.Transparent,
                    border = BorderStroke(0.8.dp, statusColor)
                ) {
                    Text(
                        text = statusText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // Bottom Row: Domain in Monospace + Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (worker.isDeveloperWorker) stringResource(R.string.wm_official_node_desc) else worker.domain,
                    color = TextMuted,
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (onVisitSite != null) {
                        Surface(
                            shape = CircleShape,
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, AmoledBorder),
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .springPress(onClick = onVisitSite)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_globe),
                                    contentDescription = "Открыть сайт",
                                    tint = TextWhite,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                    }

                    if (onUpdateScript != null) {
                        val isOutdated = worker.scriptVersion < CloudflareWorkerPayload.SCRIPT_VERSION
                        val updateBtnColor = if (isOutdated) Color(0xFFF38020) else activeAccentColor
                        Surface(
                            shape = CircleShape,
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, if (isOutdated) Color(0xFFF38020) else AmoledBorder),
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .springPress(onClick = onUpdateScript)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                if (isUpdatingScript) {
                                    CircularProgressIndicator(
                                        color = Color(0xFFF38020),
                                        modifier = Modifier.size(12.dp),
                                        strokeWidth = 1.5.dp
                                    )
                                } else {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_refresh),
                                        contentDescription = stringResource(R.string.cf_deploy_btn_update_script),
                                        tint = updateBtnColor,
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                            }
                        }
                    }

                    if (onShare != null) {
                        Surface(
                            shape = CircleShape,
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, AmoledBorder),
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .springPress(onClick = onShare)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = stringResource(R.string.wm_tab_share),
                                    tint = TextWhite,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                    }

                    if (onDelete != null) {
                        Surface(
                            shape = CircleShape,
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, BloodRed.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .springPress(onClick = onDelete)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_trash),
                                    contentDescription = stringResource(R.string.action_delete),
                                    tint = BloodRed,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Add / Edit Worker Modal Sheet (MirrlyUI Zero-Popup Architecture).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddWorkerSheet(
    prefs: PreferencesManager? = null,
    activeAccentColor: Color,
    initialDomain: String = "",
    initialName: String = "",
    onOpenQrScanner: () -> Unit = {},
    onDismiss: () -> Unit,
    onAdd: (name: String, domain: String) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var domainText by remember { mutableStateOf(initialDomain) }
    var nameText by remember { mutableStateOf(initialName) }
    var isCheckingWorker by remember { mutableStateOf(false) }
    var checkStatusMessage by remember { mutableStateOf<String?>(null) }
    var unreachableWarning by remember { mutableStateOf<String?>(null) }

    val formResult = remember(domainText, nameText) {
        WorkerDomainNormalizer.normalizeForm(nameText, domainText)
    }

    MirrlyModalBottomSheet(onDismissRequest = {
        if (!isCheckingWorker) onDismiss()
    }) {
        MirrlyBottomSheetHeader(
            title = stringResource(R.string.wm_add_dialog_title),
            subtitle = stringResource(R.string.wm_add_dialog_header),
            onClose = { if (!isCheckingWorker) onDismiss() }
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Worker Domain / URL Field
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.wm_add_field_url),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = activeAccentColor,
                        letterSpacing = 0.4.sp
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color.Transparent,
                        border = BorderStroke(0.8.dp, BloodRed)
                    ) {
                        Text(
                            text = stringResource(R.string.wm_badge_required),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = BloodRed,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                OutlinedTextField(
                    value = domainText,
                    onValueChange = {
                        domainText = it
                        unreachableWarning = null
                    },
                    placeholder = {
                        Text(
                            "worker.username.workers.dev",
                            color = TextMuted,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    },
                    textStyle = LocalTextStyle.current.copy(
                        color = TextWhite,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    singleLine = true,
                    trailingIcon = {
                        if (domainText.isNotBlank()) {
                            IconButton(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                domainText = ""
                                unreachableWarning = null
                            }) {
                                Text("✕", color = TextMuted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = activeAccentColor,
                        unfocusedBorderColor = AmoledBorder,
                        focusedTextColor = TextWhite,
                        unfocusedTextColor = TextWhite,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        cursorColor = activeAccentColor
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                // Quick buttons: Clipboard & Scanner
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, AmoledBorder),
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .springPress(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val clip = clipboardManager.getText()?.text.orEmpty().trim()
                                if (clip.isNotBlank()) {
                                    val parsed = WorkerDomainNormalizer.normalizeForm(nameText, clip)
                                    domainText = parsed.normalizedDomain
                                    if (nameText.isBlank() && parsed.normalizedName.isNotBlank()) {
                                        nameText = parsed.normalizedName
                                    }
                                    Toast.makeText(context, context.getString(R.string.wm_toast_addr_normalized), Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, context.getString(R.string.wm_toast_clipboard_empty), Toast.LENGTH_SHORT).show()
                                }
                            })
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_copy),
                                contentDescription = null,
                                tint = activeAccentColor,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.wm_btn_from_clipboard),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextWhite
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, AmoledBorder),
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .springPress(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onOpenQrScanner()
                            })
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_diag_worker),
                                contentDescription = null,
                                tint = activeAccentColor,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.wm_btn_qr_scanner),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextWhite
                            )
                        }
                    }
                }

                // Live Validation Status Badge (Outline only)
                if (domainText.isNotBlank() || formResult.wasSwapped) {
                    val status = formResult.domainResult.status
                    val isSuccess = status == DomainFormatStatus.VALID || status == DomainFormatStatus.HOMOGLYPHS_FIXED
                    val isWarning = status == DomainFormatStatus.DASHBOARD_URL || status == DomainFormatStatus.NAME_ONLY || formResult.wasSwapped
                    val badgeColor = when {
                        isSuccess -> ActiveGreenLed
                        isWarning -> Color(0xFFF5A623)
                        else -> BloodRed
                    }

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, badgeColor),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(badgeColor)
                                )
                                Text(
                                    text = when {
                                        formResult.wasSwapped -> stringResource(R.string.wm_format_swapped)
                                        status == DomainFormatStatus.HOMOGLYPHS_FIXED -> stringResource(R.string.wm_format_homoglyphs)
                                        isSuccess -> stringResource(R.string.wm_format_public)
                                        status == DomainFormatStatus.DASHBOARD_URL -> stringResource(R.string.wm_format_dashboard)
                                        status == DomainFormatStatus.NAME_ONLY -> stringResource(R.string.wm_format_name_only)
                                        else -> stringResource(R.string.wm_format_error)
                                    },
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = badgeColor
                                )
                            }

                            if (formResult.domainResult.cleanDomain.isNotBlank() && isSuccess) {
                                Text(
                                    text = formResult.domainResult.cleanDomain,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = TextWhite
                                )
                            }

                            if (formResult.wasSwapped) {
                                Text(
                                    text = stringResource(R.string.wm_swap_detected_desc, nameText),
                                    fontSize = 11.sp,
                                    color = TextMuted,
                                    lineHeight = 14.sp
                                )
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color.Transparent,
                                    border = BorderStroke(1.dp, Color(0xFFF5A623)),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .springPress(onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            domainText = formResult.normalizedDomain
                                            nameText = formResult.normalizedName
                                        })
                                ) {
                                    Text(
                                        text = stringResource(R.string.wm_btn_apply_swap),
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFF5A623),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            } else {
                                Text(
                                    text = formResult.domainResult.userMessage,
                                    fontSize = 11.sp,
                                    color = TextMuted,
                                    lineHeight = 14.sp
                                )
                            }
                        }
                    }
                }
            }

            // 2. Worker Name Field (Optional)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.wm_field_name),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextMuted,
                        letterSpacing = 0.4.sp
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color.Transparent,
                        border = BorderStroke(0.8.dp, AmoledBorder)
                    ) {
                        Text(
                            text = stringResource(R.string.wm_badge_optional),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextMuted,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                val placeholderName = formResult.domainResult.suggestedName.ifBlank { stringResource(R.string.wm_default_home_name) }

                OutlinedTextField(
                    value = nameText,
                    onValueChange = { nameText = it },
                    placeholder = {
                        Text(placeholderName, color = TextMuted, fontSize = 12.sp)
                    },
                    textStyle = LocalTextStyle.current.copy(
                        color = TextWhite,
                        fontSize = 13.sp
                    ),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = activeAccentColor,
                        unfocusedBorderColor = AmoledBorder,
                        focusedTextColor = TextWhite,
                        unfocusedTextColor = TextWhite,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        cursorColor = activeAccentColor
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Unreachable Warning Banner
            if (unreachableWarning != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, BloodRed),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.wm_conn_warning_header),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = BloodRed
                        )
                        Text(
                            text = unreachableWarning ?: "",
                            fontSize = 11.5.sp,
                            color = TextWhite,
                            lineHeight = 15.sp
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color.Transparent,
                                border = BorderStroke(1.dp, BloodRed),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .springPress(onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        val cleanDomain = formResult.normalizedDomain
                                        val finalName = formResult.normalizedName.ifBlank {
                                            formResult.domainResult.suggestedName.ifBlank { context.getString(R.string.wm_default_custom_name) }
                                        }
                                        onAdd(finalName, cleanDomain)
                                    })
                            ) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = stringResource(R.string.wm_btn_save_anyway),
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = BloodRed
                                    )
                                }
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color.Transparent,
                                border = BorderStroke(1.dp, activeAccentColor),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .springPress(onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        unreachableWarning = null
                                    })
                            ) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = stringResource(R.string.wm_btn_fix),
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = activeAccentColor
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Action Dock Buttons (Outline Only)
            MirrlyActionDock {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, AmoledBorder),
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .springPress(onClick = {
                            if (!isCheckingWorker) onDismiss()
                        })
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.action_cancel),
                            color = TextWhite,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, activeAccentColor),
                    modifier = Modifier
                        .weight(1.3f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .springPress(onClick = {
                            if (isCheckingWorker) return@springPress
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)

                            val cleanDomain = formResult.normalizedDomain
                            val finalName = formResult.normalizedName.ifBlank {
                                formResult.domainResult.suggestedName.ifBlank { context.getString(R.string.wm_default_custom_name) }
                            }

                            if (cleanDomain.isBlank()) {
                                Toast.makeText(context, context.getString(R.string.wm_toast_specify_valid), Toast.LENGTH_SHORT).show()
                                return@springPress
                            }

                            if (formResult.domainResult.status == DomainFormatStatus.DASHBOARD_URL) {
                                unreachableWarning = formResult.domainResult.userMessage
                                return@springPress
                            }

                            scope.launch {
                                isCheckingWorker = true
                                checkStatusMessage = context.getString(R.string.wm_checking_node_avail)
                                unreachableWarning = null

                                val (status, _) = WorkerPingTester.pingWorker(cleanDomain)
                                isCheckingWorker = false
                                checkStatusMessage = null

                                if (status == WorkerStatus.ONLINE || status == WorkerStatus.RATE_LIMITED_429) {
                                    onAdd(finalName, cleanDomain)
                                } else {
                                    unreachableWarning = context.getString(R.string.wm_unreachable_warning, cleanDomain)
                                }
                            }
                        })
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (isCheckingWorker) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    color = activeAccentColor,
                                    strokeWidth = 1.5.dp
                                )
                                Text(
                                    text = checkStatusMessage ?: stringResource(R.string.wm_checking_node),
                                    color = activeAccentColor,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        } else {
                            Text(
                                text = stringResource(R.string.wm_btn_save_activate),
                                color = activeAccentColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Delete Confirmation Bottom Sheet (MirrlyUI Zero-Popup Architecture).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeleteWorkerConfirmSheet(
    worker: WorkerProfile,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val haptic = LocalHapticFeedback.current

    MirrlyModalBottomSheet(onDismissRequest = onDismiss) {
        MirrlyBottomSheetHeader(
            title = stringResource(R.string.wm_delete_title),
            onClose = onDismiss
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Подтвердите удаление Cloudflare Worker узла из конфигурации приложения:",
                fontSize = 12.5.sp,
                color = TextMuted,
                lineHeight = 17.sp
            )

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, AmoledBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = worker.name,
                        color = TextWhite,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = worker.domain,
                        color = TextMuted,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            MirrlyActionDock {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, AmoledBorder),
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onDismiss()
                        })
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.action_cancel),
                            color = TextWhite,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, BloodRed),
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onConfirm()
                        })
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.action_delete),
                            color = BloodRed,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

/**
 * QR Code Scanner Bottom Sheet (MirrlyUI Zero-Popup Architecture).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QrScannerSheet(
    activeAccentColor: Color,
    onDismiss: () -> Unit,
    onQrScanned: (String) -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    MirrlyModalBottomSheet(onDismissRequest = onDismiss) {
        MirrlyBottomSheetHeader(
            title = stringResource(R.string.wm_btn_qr_scanner),
            subtitle = stringResource(R.string.wm_qr_scanner_sub),
            onClose = onDismiss
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (hasCameraPermission) {
                CameraQrScannerView(
                    activeAccentColor = activeAccentColor,
                    onScanned = { rawText ->
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onQrScanned(rawText)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .border(BorderStroke(1.dp, AmoledBorder), RoundedCornerShape(20.dp))
                )
            } else {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, AmoledBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(20.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_diag_worker),
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = stringResource(R.string.wm_camera_disabled_title),
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.wm_camera_disabled_desc),
                            fontSize = 11.5.sp,
                            color = TextMuted,
                            textAlign = TextAlign.Center,
                            lineHeight = 15.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, activeAccentColor),
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .springPress(onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    permissionLauncher.launch(Manifest.permission.CAMERA)
                                })
                        ) {
                            Text(
                                text = stringResource(R.string.wm_btn_grant_access),
                                color = activeAccentColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, AmoledBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .springPress(onClick = {
                        val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString().orEmpty().trim()
                        if (clip.isNotBlank()) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onQrScanned(clip)
                        } else {
                            Toast.makeText(context, context.getString(R.string.wm_toast_clipboard_empty), Toast.LENGTH_SHORT).show()
                        }
                    })
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_copy),
                        contentDescription = null,
                        tint = activeAccentColor,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.wm_btn_paste_address_link),
                        color = TextWhite,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

/**
 * Worker Not Owned Bottom Sheet (MirrlyUI Zero-Popup Architecture).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkerNotOwnedSheet(
    worker: WorkerProfile,
    onDismiss: () -> Unit,
    onOpenGuide: () -> Unit
) {
    val haptic = LocalHapticFeedback.current

    MirrlyModalBottomSheet(onDismissRequest = onDismiss) {
        MirrlyBottomSheetHeader(
            title = stringResource(R.string.wm_update_not_owned_title),
            onClose = onDismiss
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, AmoledBorder)
            ) {
                Text(
                    text = worker.domain,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = TextWhite,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }

            Text(
                text = stringResource(R.string.wm_update_not_owned_desc),
                fontSize = 12.sp,
                color = TextMuted,
                lineHeight = 17.sp,
                textAlign = TextAlign.Center
            )

            MirrlyActionDock {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, Color(0xFF38BDF8)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onOpenGuide()
                        })
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "Открыть инструкцию",
                            color = Color(0xFF38BDF8),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

/**
 * Share Worker Content (QR Code and links).
 */
@Composable
private fun ShareWorkerContent(
    workers: List<WorkerProfile>,
    activeWorkerId: String,
    activeAccentColor: Color,
    selectedWorker: WorkerProfile?,
    onSelectWorker: (WorkerProfile) -> Unit,
    onShare: (WorkerProfile) -> Unit,
    onAddWorkerClick: () -> Unit,
    headerPadding: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val clipboardManager = LocalClipboardManager.current

    val currentWorker = selectedWorker
        ?: workers.firstOrNull { it.id == activeWorkerId }
        ?: workers.firstOrNull()

    if (currentWorker == null) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(top = headerPadding, bottom = 32.dp, start = 20.dp, end = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.padding(24.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_diag_worker),
                    contentDescription = null,
                    tint = TextMuted.copy(alpha = 0.5f),
                    modifier = Modifier.size(56.dp)
                )
                Text(
                    text = stringResource(R.string.wm_no_custom_workers_title),
                    color = TextWhite,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.wm_no_custom_workers_desc),
                    color = TextMuted,
                    fontSize = 12.5.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 17.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, activeAccentColor),
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .springPress(onClick = onAddWorkerClick)
                ) {
                    Text(
                        text = stringResource(R.string.wm_btn_add_worker_cta),
                        color = activeAccentColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                    )
                }
            }
        }
    } else {
        val encodedDomain = Uri.encode(currentWorker.domain)
        val encodedName = Uri.encode(currentWorker.name)
        val deepLink = "mirrly://worker?domain=$encodedDomain&name=$encodedName"

        val logoBitmap = remember {
            try {
                android.graphics.BitmapFactory.decodeResource(context.resources, R.drawable.ic_launcher_mirrly)
            } catch (_: Exception) {
                null
            }
        }

        val qrBitmap = remember(deepLink, activeAccentColor, logoBitmap) {
            QrCodeGenerator.generateQrBitmap(
                content = deepLink,
                sizePx = 720,
                accentColor = activeAccentColor.toArgb(),
                darkColor = android.graphics.Color.WHITE,
                backgroundColor = android.graphics.Color.TRANSPARENT,
                logoBitmap = logoBitmap
            )
        }

        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    top = headerPadding + 8.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 90.dp,
                    start = 16.dp,
                    end = 16.dp
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (workers.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    workers.forEach { w ->
                        val isSelected = w.id == currentWorker.id
                        val chipBorder = if (isSelected) activeAccentColor else AmoledBorder

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, chipBorder),
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .springPress(onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onSelectWorker(w)
                                })
                        ) {
                            Text(
                                text = w.name,
                                color = if (isSelected) activeAccentColor else TextWhite,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                            )
                        }
                    }
                }
            }

            // Outline QR Card
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, AmoledBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, activeAccentColor)
                    ) {
                        Text(
                            text = currentWorker.name,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = activeAccentColor,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }

                    if (qrBitmap != null) {
                        Box(
                            modifier = Modifier
                                .size(230.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .border(1.dp, AmoledBorder, RoundedCornerShape(16.dp))
                                .padding(12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = stringResource(R.string.wm_qr_code_desc),
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    Text(
                        text = currentWorker.domain,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextMuted,
                        textAlign = TextAlign.Center
                    )

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
                                .height(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .springPress(onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cb.setPrimaryClip(ClipData.newPlainText("Worker Link", deepLink))
                                    Toast.makeText(context, context.getString(R.string.wm_toast_mirrly_link_copied), Toast.LENGTH_SHORT).show()
                                })
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_copy),
                                    contentDescription = null,
                                    tint = activeAccentColor,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Скопировать",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextWhite
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, activeAccentColor),
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .springPress(onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onShare(currentWorker)
                                })
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = null,
                                    tint = activeAccentColor,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Поделиться",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = activeAccentColor
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Scanner Worker Content (Camera QR and clipboard paste).
 */
@Composable
private fun ScannerWorkerContent(
    activeAccentColor: Color,
    onQrScanned: (String) -> Unit,
    onPasteFromClipboard: () -> Unit,
    headerPadding: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                top = headerPadding + 8.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 90.dp,
                start = 16.dp,
                end = 16.dp
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color.Transparent,
            border = BorderStroke(1.dp, AmoledBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (hasCameraPermission) {
                    CameraQrScannerView(
                        activeAccentColor = activeAccentColor,
                        onScanned = { rawText ->
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onQrScanned(rawText)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .border(BorderStroke(1.dp, AmoledBorder), RoundedCornerShape(16.dp))
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                            .border(BorderStroke(1.dp, AmoledBorder), RoundedCornerShape(16.dp))
                            .padding(20.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_diag_worker),
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = stringResource(R.string.wm_camera_disabled_title),
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.wm_camera_disabled_desc),
                            fontSize = 11.5.sp,
                            color = TextMuted,
                            textAlign = TextAlign.Center,
                            lineHeight = 15.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, activeAccentColor),
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .springPress(onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    permissionLauncher.launch(Manifest.permission.CAMERA)
                                })
                        ) {
                            Text(
                                text = stringResource(R.string.wm_btn_grant_access),
                                color = activeAccentColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, AmoledBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onPasteFromClipboard()
                        })
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_copy),
                            contentDescription = null,
                            tint = activeAccentColor,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.wm_btn_paste_address_link),
                            color = TextWhite,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

/**
 * Consolidated Interactive Step-by-Step Guide Section.
 */
@Composable
private fun ManualWorkerGuideContent(
    prefs: PreferencesManager,
    activeAccentColor: Color,
    targetProtocol: ProxyMode = ProxyMode.MTPROTO,
    onWorkerAdded: () -> Unit,
    headerPadding: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var inputDomain by remember { mutableStateOf("") }
    var domainError by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                top = headerPadding + 8.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 60.dp,
                start = 16.dp,
                end = 16.dp
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Guide Overview Header
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.Transparent,
            border = BorderStroke(1.dp, AmoledBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, Color(0xFF38BDF8)),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_diag_cloudflare),
                                contentDescription = null,
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Column {
                        Text(
                            text = stringResource(R.string.wm_guide_header_title),
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite
                        )
                        Text(
                            text = "Cloudflare Dashboard",
                            fontSize = 11.sp,
                            color = Color(0xFF38BDF8),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.wm_guide_header_subtitle),
                    fontSize = 12.sp,
                    color = TextMuted,
                    lineHeight = 16.5.sp
                )
            }
        }

        // Step 1: Authorization
        GuideStepCard(
            stepNumber = "1",
            title = stringResource(R.string.wm_guide_step1_title),
            desc = stringResource(R.string.wm_guide_step1_desc),
            accentColor = Color(0xFF38BDF8)
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, Color(0xFFF38020)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .springPress(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://dash.cloudflare.com/"))
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            Toast.makeText(context, "Не удалось открыть браузер", Toast.LENGTH_SHORT).show()
                        }
                    })
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.wm_guide_step1_btn),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF38020)
                    )
                }
            }
        }

        // Step 2: Create Worker
        GuideStepCard(
            stepNumber = "2",
            title = stringResource(R.string.wm_guide_step2_title),
            desc = stringResource(R.string.wm_guide_step2_desc),
            accentColor = Color(0xFF38BDF8)
        )

        // Step 3: Script & Code
        GuideStepCard(
            stepNumber = "3",
            title = stringResource(R.string.wm_guide_step3_title),
            desc = stringResource(R.string.wm_guide_step3_desc),
            accentColor = Color(0xFF38BDF8)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, Color(0xFF38BDF8)),
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            val script = CloudflareWorkerPayload.getWorkerScript(context)
                            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cb.setPrimaryClip(ClipData.newPlainText("worker.js", script))
                            Toast.makeText(context, context.getString(R.string.wm_guide_copied_toast), Toast.LENGTH_SHORT).show()
                        })
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_copy),
                            contentDescription = null,
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.wm_guide_step3_btn_copy),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF38BDF8)
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, AmoledBorder),
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy"))
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                Toast.makeText(context, "Не удалось открыть браузер", Toast.LENGTH_SHORT).show()
                            }
                        })
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_github),
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.wm_guide_step3_btn_github),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextWhite
                        )
                    }
                }
            }
        }

        // Step 4: Connection & Activation
        GuideStepCard(
            stepNumber = "4",
            title = stringResource(R.string.wm_guide_step5_title),
            desc = stringResource(R.string.wm_guide_step5_desc),
            accentColor = Color(0xFF38BDF8)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = inputDomain,
                    onValueChange = {
                        inputDomain = it
                        domainError = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            text = stringResource(R.string.wm_guide_step5_hint),
                            fontSize = 12.sp,
                            color = TextMuted
                        )
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextWhite,
                        unfocusedTextColor = TextWhite,
                        focusedBorderColor = Color(0xFF38BDF8),
                        unfocusedBorderColor = AmoledBorder,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        cursorColor = Color(0xFF38BDF8)
                    ),
                    shape = RoundedCornerShape(10.dp)
                )

                domainError?.let { err ->
                    Text(text = err, color = BloodRed, fontSize = 11.5.sp)
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, activeAccentColor),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .springPress(onClick = {
                            val normalized = WorkerDomainNormalizer.normalize(inputDomain)
                            if (!normalized.isValid) {
                                domainError = "Некорректный адрес домена воркера"
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            } else {
                                val res = prefs.addCustomWorker(normalized.suggestedName, normalized.cleanDomain)
                                res.fold(
                                    onSuccess = { added ->
                                        prefs.setActiveWorkerId(added.id, mode = targetProtocol)
                                        Toast.makeText(context, "Воркер добавлен: ${added.domain}", Toast.LENGTH_SHORT).show()
                                        onWorkerAdded()
                                    },
                                    onFailure = { err ->
                                        domainError = err.message ?: "Ошибка добавления"
                                    }
                                )
                            }
                        })
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.wm_guide_step5_btn_add),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = activeAccentColor
                        )
                    }
                }
            }
        }
    }
}

/**
 * Step Card Helper with outline-only styling.
 */
@Composable
private fun GuideStepCard(
    stepNumber: String,
    title: String,
    desc: String,
    accentColor: Color,
    content: (@Composable () -> Unit)? = null
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, AmoledBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, accentColor),
                    modifier = Modifier.size(24.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = stepNumber,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = accentColor
                        )
                    }
                }
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite
                )
            }
            Text(
                text = desc,
                fontSize = 11.5.sp,
                color = TextMuted,
                lineHeight = 16.sp
            )
            content?.invoke()
        }
    }
}
