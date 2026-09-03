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
import com.mirrly.tgproxy.core.AppLogger
import android.net.Uri
import android.os.Build
import android.widget.Toast
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.cos
import kotlin.math.sin
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.layout.layout
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.core.ReleaseInfo
import com.mirrly.tgproxy.core.UpdateChecker
import com.mirrly.tgproxy.service.ProxyForegroundService
import com.mirrly.tgproxy.service.humanBytes
import com.mirrly.tgproxy.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.currentCoroutineContext

enum class ProxyUiState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING
}

@Immutable
data class ProxyLiveTelemetry(
    val isRunning: Boolean = false,
    val dlSpeed: String = "0 Б/с",
    val ulSpeed: String = "0 Б/с",
    val activeConns: Int = 0,
    val totalRecv: String = "0 Б",
    val totalSent: String = "0 Б",
    val uptimeSeconds: Long = 0L,
    val pingMs: Long = -1L,
    val jitterMs: Long = 0L,
    val healthScore: Int = 100,
    val healthVerdict: String = "Идеальный канал связи",
    val healthDetail: String = "Минимальная задержка и стабильный прямой WSS-туннель",
    val healthSuccessRate: Int = 100
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenHistory: () -> Unit = {},
    onOpenUpdate: () -> Unit = {},
    onOpenWorkerGuide: () -> Unit = {},
    onOpenWorkerManager: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    onOpenSpeedTest: () -> Unit = {},
    onDragWorkerManager: (Float) -> Unit = {},
    onSettleWorkerManager: (Float) -> Unit = {},
    isInteractive: Boolean = true
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val app = MirrlyApplication.instance
    val server = app.proxyServer

    val isSocks5 by app.prefsManager.isSocks5Flow.collectAsState()
    val isAnimationsDisabled by app.prefsManager.animationsDisabledFlow.collectAsState()
    val activeWorkerId by app.prefsManager.activeWorkerIdFlow.collectAsState()
    val activeWorker = remember(activeWorkerId) { app.prefsManager.getActiveWorker(activeWorkerId) }
    val protoColors = rememberAnimatedProtocolColors(isSocks5 = isSocks5)

    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var isAppResumed by remember { mutableStateOf(true) }

    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE,
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> isAppResumed = false
                androidx.lifecycle.Lifecycle.Event.ON_RESUME,
                androidx.lifecycle.Lifecycle.Event.ON_START -> isAppResumed = true
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var pendingState by remember { mutableStateOf<ProxyUiState?>(null) }
    var lastPowerClickMs by remember { mutableLongStateOf(0L) }

    val isSwitching by com.mirrly.tgproxy.service.ProtocolSwitchManager.isSwitching.collectAsState()
    val switchPhase by com.mirrly.tgproxy.service.ProtocolSwitchManager.switchPhase.collectAsState()
    val wasProxyRunningDuringSwitch by com.mirrly.tgproxy.service.ProtocolSwitchManager.wasProxyRunningDuringSwitch.collectAsState()

    // ── GROUPED IMMUTABLE TELEMETRY WITH DISTINCT-UNTIL-CHANGED ──
    val telemetry: ProxyLiveTelemetry by remember(isAppResumed) {
        flow<ProxyLiveTelemetry> {
            while (currentCoroutineContext().isActive && isAppResumed) {
                val running = server.isRunning
                val uptime = server.uptimeSeconds
                val currPing = server.currentPingMs
                var dl = "0 Б/с"
                var ul = "0 Б/с"
                var conns = 0
                var recv = "0 Б"
                var sent = "0 Б"
                var jitter = 0L
                var healthScore = 100
                var healthVerdict = "Идеальный канал связи"
                var healthDetail = "Минимальная задержка и стабильный прямой WSS-туннель"
                var successRate = 100

                if (running) {
                    val stats = server.stats
                    dl = "${humanBytes(stats.downloadSpeedBps)}/с"
                    ul = "${humanBytes(stats.uploadSpeedBps)}/с"
                    conns = stats.activeConnections.get()
                    recv = humanBytes(stats.totalBytesReceived.get())
                    sent = humanBytes(stats.totalBytesSent.get())
                    jitter = stats.jitterMs
                    healthScore = stats.healthScore
                    healthVerdict = stats.healthVerdict
                    healthDetail = stats.healthDetail
                    successRate = stats.healthSuccessRate
                }

                emit(
                    ProxyLiveTelemetry(
                        isRunning = running,
                        dlSpeed = dl,
                        ulSpeed = ul,
                        activeConns = conns,
                        totalRecv = recv,
                        totalSent = sent,
                        uptimeSeconds = uptime,
                        pingMs = currPing,
                        jitterMs = jitter,
                        healthScore = healthScore,
                        healthVerdict = healthVerdict,
                        healthDetail = healthDetail,
                        healthSuccessRate = successRate
                    )
                )
                delay(500)
            }
        }
            .flowOn(Dispatchers.IO)
            .distinctUntilChanged()
    }.collectAsState(initial = ProxyLiveTelemetry(isRunning = server.isRunning))

    val currentState = when (switchPhase) {
        com.mirrly.tgproxy.service.SwitchPhase.DISCONNECTING -> ProxyUiState.DISCONNECTING
        com.mirrly.tgproxy.service.SwitchPhase.PAUSE_DARK -> ProxyUiState.DISCONNECTED
        com.mirrly.tgproxy.service.SwitchPhase.RECONNECTING -> ProxyUiState.CONNECTING
        com.mirrly.tgproxy.service.SwitchPhase.IDLE -> pendingState ?: if (telemetry.isRunning || server.isRunning) ProxyUiState.CONNECTED else ProxyUiState.DISCONNECTED
    }

    LaunchedEffect(telemetry.isRunning, pendingState) {
        if (pendingState == ProxyUiState.CONNECTING && telemetry.isRunning) {
            pendingState = null
        } else if (pendingState == ProxyUiState.DISCONNECTING && !telemetry.isRunning) {
            pendingState = null
        }
    }

    val updateInfo by com.mirrly.tgproxy.service.UpdateManager.updateState.collectAsState()
    val timerState by com.mirrly.tgproxy.service.SleepTimerManager.timerState.collectAsState()
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var showConnectDialog by remember { mutableStateOf(false) }
    var showSocks5AuthRequiredDialog by remember { mutableStateOf(false) }

    // Safety timeout to prevent stuck connecting/disconnecting UI
    LaunchedEffect(pendingState) {
        if (pendingState != null) {
            delay(3500)
            pendingState = null
        }
    }

    fun formatUptime(secs: Long): String {
        val h = secs / 3600
        val m = (secs % 3600) / 60
        val s = secs % 60
        return if (h > 0) String.format("%02d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
    }

    fun switchProtocol(target: com.mirrly.tgproxy.core.ProxyMode? = null) {
        com.mirrly.tgproxy.service.ProtocolSwitchManager.switchProtocol(context, target)
    }

    val pureBlack = Color(0xFF000000)

    Scaffold(
        topBar = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .adaptiveContainerWidth(600.dp)
                    .statusBarsPadding()
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                // =========================================================================
                // УРОВЕНЬ 1: Верхняя строка (~38-40 dp, verticalAlignment = CenterVertically)
                // =========================================================================
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Navigation Icons (Left) - Logs, History, SleepTimer
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(1.dp),
                        modifier = Modifier.align(Alignment.CenterStart)
                    ) {
                        // 1. Logs (Диагностика)
                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onOpenLogs()
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_logs),
                                contentDescription = "Логи",
                                tint = TextWhite,
                                modifier = Modifier.size(19.dp)
                            )
                        }

                        // 2. Session History (Статистика сессий)
                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onOpenHistory()
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_history),
                                contentDescription = "История сессий",
                                tint = TextWhite,
                                modifier = Modifier.size(19.dp)
                            )
                        }

                        // 3. Sleep Timer (Управление временем сессии)
                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                showSleepTimerDialog = true
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.TopEnd) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_timer),
                                    contentDescription = "Таймер сна",
                                    tint = if (timerState.isActive) protoColors.primary else TextWhite,
                                    modifier = Modifier.size(19.dp)
                                )
                                if (timerState.isActive) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(protoColors.primary)
                                    )
                                }
                            }
                        }
                    }

                    // Строго по центру: заголовок «Mirrly» (на той же высоте и горизонтальной оси, что и иконки)
                    Text(
                        text = "Mirrly",
                        color = TextWhite,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.6.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center)
                    )

                    // Actions (Right) - Update, Settings
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(1.dp),
                        modifier = Modifier.align(Alignment.CenterEnd)
                    ) {
                        // 1. Update Center (Обновления приложения)
                        val hasUpdate = updateInfo?.isUpdateAvailable == true
                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onOpenUpdate()
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.TopEnd) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_refresh),
                                    contentDescription = "Обновления",
                                    tint = if (hasUpdate) Color(0xFFFFB703) else TextWhite,
                                    modifier = Modifier.size(19.dp)
                                )
                                if (hasUpdate) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.5.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFFFFB703))
                                    )
                                }
                            }
                        }

                        // 6. Settings (Настройки)
                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onOpenSettings()
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_settings),
                                contentDescription = "Настройки",
                                tint = TextWhite,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // =========================================================================
                // УРОВЕНЬ 2: Нижний подуровень шапки (Таблетка MTProto | SOCKS5 + Бейдж воркера)
                // =========================================================================
                ProtocolSwitcherHeader(
                    isSocks5 = isSocks5,
                    activeWorker = activeWorker,
                    protoColors = protoColors,
                    isSwitching = isSwitching,
                    onSwitchProtocol = { target ->
                        switchProtocol(target)
                    },
                    onOpenWorkerManager = onOpenWorkerManager
                )
            }
        },
        containerColor = Color.Transparent
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (isInteractive) {
                        Modifier.pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                    var totalDragY = 0f
                                    var isDragging = false
                                    val touchSlop = viewConfiguration.touchSlop

                                    while (true) {
                                        val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) {
                                            if (isDragging) {
                                                onSettleWorkerManager(totalDragY)
                                            }
                                            break
                                        }
                                        val dragAmount = change.position.y - change.previousPosition.y
                                        totalDragY += dragAmount

                                        if (!isDragging && totalDragY > touchSlop) {
                                            isDragging = true
                                        }

                                        if (isDragging) {
                                            if (totalDragY > 0f) {
                                                change.consume()
                                            }
                                            onDragWorkerManager(totalDragY)
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Modifier
                    }
                )
        ) {
            val screenHeight = maxHeight
            val isCompactHeight = screenHeight < 720.dp
            val isVeryCompactHeight = screenHeight < 620.dp

            val ringSize = when {
                isVeryCompactHeight -> 175.dp
                isCompactHeight -> 205.dp
                else -> 235.dp
            }
            val powerIconSize = ringSize * (170f / 240f)

            var showDonationBanner by remember {
                mutableStateOf(com.mirrly.tgproxy.service.DonationManager.shouldShowDonationBanner(context))
            }
            var showValueBanner by remember {
                mutableStateOf(com.mirrly.tgproxy.service.ValueTriggerManager.shouldShowValueBanner(context))
            }

            LaunchedEffect(Unit) {
                while (isActive) {
                    if (!showDonationBanner && com.mirrly.tgproxy.service.DonationManager.shouldShowDonationBanner(context)) {
                        showDonationBanner = true
                    }
                    if (!showValueBanner && com.mirrly.tgproxy.service.ValueTriggerManager.shouldShowValueBanner(context)) {
                        showValueBanner = true
                    }
                    delay(15000)
                }
            }

            val powerInteractionSource = remember { MutableInteractionSource() }
            val isPowerPressed by powerInteractionSource.collectIsPressedAsState()

            val springScale by animateFloatAsState(
                targetValue = if (isPowerPressed) 0.86f else 1.00f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                ),
                label = "powerSpringScale"
            )

            // Dynamic unified vertical Column preventing any overlap across all screen ratios & DPIs
            Column(
                modifier = Modifier
                    .adaptiveContainerWidth(600.dp)
                    .fillMaxHeight()
                    .padding(
                        top = padding.calculateTopPadding(),
                        bottom = padding.calculateBottomPadding() + if (isCompactHeight) 4.dp else 8.dp
                    )
                    .adaptiveContentPadding(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // ─── 1. TOP SECTION (Compact update banner) ───
                val isBannerVisible = (updateInfo?.isUpdateAvailable == true) && (updateInfo?.isIgnored != true)
                AnimatedVisibility(
                    visible = isBannerVisible,
                    enter = fadeIn(tween(250)) + expandVertically(tween(300)),
                    exit = fadeOut(tween(200)) + shrinkVertically(tween(250))
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // Offset matching SOCKS5 active worker badge height (23.dp) + 5.dp safety margin
                        Spacer(modifier = Modifier.height(28.dp))
                        updateInfo?.let { info ->
                            val updateYellow = Color(0xFFFFB703)
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = updateYellow.copy(alpha = 0.08f),
                                border = BorderStroke(1.dp, updateYellow.copy(alpha = 0.55f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .springPress(onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onOpenUpdate()
                                    })
                            ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(22.dp)
                                            .clip(CircleShape)
                                            .background(updateYellow.copy(alpha = 0.20f))
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_refresh),
                                            contentDescription = null,
                                            tint = updateYellow,
                                            modifier = Modifier.size(12.dp)
                                        )
                                    }

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = "Обновление v${info.versionName}",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            color = updateYellow
                                        )
                                        Text(
                                            text = "• Установить",
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = TextWhite.copy(alpha = 0.80f)
                                        )
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    IconButton(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            com.mirrly.tgproxy.service.UpdateManager.ignoreVersion(context, info.versionName)
                                            Toast.makeText(context, "Обновление v${info.versionName} скрыто", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Text(
                                            text = "✕",
                                            color = TextMuted,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_chevron_right),
                                        contentDescription = null,
                                        tint = updateYellow,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

                // ─── 2. CENTER SECTION (Power button) ───
                val isProxyRunning = currentState == ProxyUiState.CONNECTED || currentState == ProxyUiState.CONNECTING
                val shouldShowWorkerNotice = isSocks5 && activeWorker.isDeveloperWorker && isProxyRunning
                var isWorkerNoticeVisible by remember { mutableStateOf(false) }

                LaunchedEffect(shouldShowWorkerNotice) {
                    if (shouldShowWorkerNotice) {
                        delay(1400)
                        isWorkerNoticeVisible = true
                    } else {
                        isWorkerNoticeVisible = false
                    }
                }

                val buttonInertiaOffsetY by animateDpAsState(
                    targetValue = if (isWorkerNoticeVisible && shouldShowWorkerNotice) (-4).dp else 0.dp,
                    animationSpec = spring(
                        dampingRatio = 0.88f,
                        stiffness = Spring.StiffnessLow
                    ),
                    label = "buttonInertiaOffsetY"
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .offset(y = buttonInertiaOffsetY)
                            .size(ringSize)
                            .graphicsLayer {
                                scaleX = springScale
                                scaleY = springScale
                            }
                            .clickable(
                                interactionSource = powerInteractionSource,
                                indication = null
                            ) {
                                if (pendingState != null || isSwitching) return@clickable
                                val now = System.currentTimeMillis()
                                if (now - lastPowerClickMs < 450L) return@clickable
                                lastPowerClickMs = now

                                HapticHelper.performTapClick(context)
                                val serviceIntent = Intent(context, ProxyForegroundService::class.java)
                                try {
                                    if (currentState == ProxyUiState.CONNECTED || currentState == ProxyUiState.CONNECTING) {
                                        pendingState = ProxyUiState.DISCONNECTING
                                        serviceIntent.action = ProxyForegroundService.ACTION_STOP
                                        context.startService(serviceIntent)
                                    } else {
                                        if (app.config.isSocks5Mode && !app.config.hasSocks5Auth) {
                                            showSocks5AuthRequiredDialog = true
                                            return@clickable
                                        }
                                        pendingState = ProxyUiState.CONNECTING
                                        serviceIntent.action = ProxyForegroundService.ACTION_START
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                            context.startForegroundService(serviceIntent)
                                        } else {
                                            context.startService(serviceIntent)
                                        }
                                    }
                                } catch (e: Exception) {
                                    pendingState = null
                                    AppLogger.e("HomeScreen", "Ошибка переключения службы прокси: ${e.message}")
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        RotatingProxyRing(
                            state = currentState,
                            isSocks5 = isSocks5,
                            modifier = Modifier.size(ringSize)
                        )

                        val iconTint = when (currentState) {
                            ProxyUiState.CONNECTED, ProxyUiState.CONNECTING -> protoColors.primary
                            ProxyUiState.DISCONNECTING, ProxyUiState.DISCONNECTED -> Color(0xFF333D4F)
                        }
                        val animatedIconTint by animateColorAsState(
                            targetValue = iconTint,
                            animationSpec = tween(550, easing = FastOutSlowInEasing),
                            label = "iconTint"
                        )

                        Icon(
                            painter = painterResource(id = R.drawable.ic_power),
                            contentDescription = "Включение прокси",
                            tint = animatedIconTint,
                            modifier = Modifier.size(powerIconSize)
                        )
                    }
                }

                // ─── 3. BOTTOM SECTION (Lower controls) ───
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize(
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioLowBouncy,
                                stiffness = Spring.StiffnessLow
                            )
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // SOCKS5 Developer Worker Info Notice (Smooth expanding/shrinking from center with staggered delay)
                    AnimatedVisibility(
                        visible = isWorkerNoticeVisible && shouldShowWorkerNotice,
                        enter = scaleIn(
                            initialScale = 0.92f,
                            animationSpec = spring(
                                dampingRatio = 0.88f,
                                stiffness = Spring.StiffnessLow
                            ),
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin.Center
                        ) + expandVertically(
                            animationSpec = spring(
                                dampingRatio = 0.88f,
                                stiffness = Spring.StiffnessLow
                            ),
                            expandFrom = Alignment.CenterVertically
                        ) + fadeIn(animationSpec = tween(400, easing = LinearOutSlowInEasing)),
                        exit = scaleOut(
                            targetScale = 0.94f,
                            animationSpec = tween(350, easing = FastOutSlowInEasing),
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin.Center
                        ) + shrinkVertically(
                            animationSpec = tween(350, easing = FastOutSlowInEasing),
                            shrinkTowards = Alignment.CenterVertically
                        ) + fadeOut(animationSpec = tween(280, easing = FastOutSlowInEasing))
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .padding(bottom = if (isCompactHeight) 5.dp else 7.dp)
                        ) {
                            Text(
                                text = "${activeWorker.name} (Общий пул)",
                                color = Color(0xFFFF9E00).copy(alpha = 0.75f),
                                fontSize = if (isCompactHeight) 10.5.sp else 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Лимит запросов может исчерпаться. Выберите другой воркер в менеджере или разверните личный.",
                                color = Color(0xFFFFB74D).copy(alpha = 0.50f),
                                fontSize = if (isCompactHeight) 9.5.sp else 10.5.sp,
                                fontFamily = FontFamily.Monospace,
                                textAlign = TextAlign.Center,
                                lineHeight = if (isCompactHeight) 13.sp else 14.5.sp
                            )
                        }
                    }

                    // Unified Central Time & Sleep Timer Capsule (Cyber aesthetic) + Liquid Wave SQI Orb
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(bottom = 6.dp)
                    ) {
                        Surface(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                showSleepTimerDialog = true
                            },
                            shape = RoundedCornerShape(16.dp),
                            color = Color.White.copy(alpha = 0.04f),
                            modifier = Modifier
                                .animateContentSize(
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    )
                                )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                val dotColor = when (currentState) {
                                    ProxyUiState.CONNECTED -> protoColors.primary
                                    ProxyUiState.CONNECTING -> protoColors.primary
                                    ProxyUiState.DISCONNECTING -> Color(0xFFFF9E00)
                                    ProxyUiState.DISCONNECTED -> Color(0xFF353C4F)
                                }
                                val animatedDotColor by animateColorAsState(
                                    targetValue = dotColor,
                                    animationSpec = tween(400),
                                    label = "dotColor"
                                )

                                // Status LED Dot
                                Surface(
                                    shape = CircleShape,
                                    color = animatedDotColor,
                                    modifier = Modifier.size(6.dp)
                                ) {}

                                // Uptime Connection Duration
                                val statusText = when (currentState) {
                                    ProxyUiState.CONNECTED -> formatUptime(telemetry.uptimeSeconds)
                                    ProxyUiState.CONNECTING -> "ПОДКЛЮЧЕНИЕ..."
                                    ProxyUiState.DISCONNECTING -> "ОТКЛЮЧЕНИЕ..."
                                    ProxyUiState.DISCONNECTED -> "00:00:00"
                                }
                                RollingNumberText(
                                    text = statusText,
                                    color = if (currentState == ProxyUiState.CONNECTED) protoColors.primary else if (currentState == ProxyUiState.DISCONNECTING) Color(0xFFFF9E00) else TextMuted,
                                    fontSize = if (isCompactHeight) 14.sp else 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.1.sp
                                )

                                // Sleep Timer Segment (Expands with rolling animation when active)
                                if (timerState.isActive) {
                                    // Subtle Divider
                                    Box(
                                        modifier = Modifier
                                            .width(1.dp)
                                            .height(13.dp)
                                            .background(Color.White.copy(alpha = 0.20f))
                                    )

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.5.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_timer),
                                            contentDescription = null,
                                            tint = Color(0xFFFF9E00),
                                            modifier = Modifier.size(12.dp)
                                        )
                                        RollingNumberText(
                                            text = timerState.formatRemainingTime(),
                                            color = Color(0xFFFF9E00),
                                            fontSize = if (isCompactHeight) 14.sp else 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.1.sp
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // User-friendly Status line (Replaces raw internal IP and socket debug counts)
                    val statusSubtitle = when (currentState) {
                        ProxyUiState.CONNECTED -> {
                            if (telemetry.healthVerdict.contains("Ожидание сети", ignoreCase = true) || (telemetry.healthScore == 0 && telemetry.pingMs < 0)) {
                                "Ожидание сети • Офлайн"
                            } else if (app.config.cfProxyEnabled) {
                                "Cloudflare WSS • Защищено"
                            } else {
                                "Локальный прокси • Защищено"
                            }
                        }
                        ProxyUiState.CONNECTING -> "Установка защищенного соединения..."
                        ProxyUiState.DISCONNECTING -> "Остановка соединения..."
                        ProxyUiState.DISCONNECTED -> "Защита отключена • Нажмите кнопку для старта"
                    }

                    Text(
                        text = statusSubtitle,
                        color = if (currentState == ProxyUiState.CONNECTED) protoColors.primary.copy(alpha = 0.9f) else TextMuted,
                        fontSize = if (isCompactHeight) 11.5.sp else 12.sp,
                        fontWeight = if (currentState == ProxyUiState.CONNECTED) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        softWrap = false,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(if (isCompactHeight) 8.dp else 12.dp))

                    // Telemetry Download & Upload speeds (50/50 centered with Quality Orb divider)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Download Speed & Total (Weight 1f)
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onOpenSpeedTest()
                                }
                                .padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_arrow_down),
                                    contentDescription = null,
                                    tint = if (currentState == ProxyUiState.CONNECTED) protoColors.primary else TextMuted,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Входящий", color = TextMuted, fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            RollingNumberText(
                                text = telemetry.dlSpeed,
                                color = if (currentState == ProxyUiState.CONNECTED) TextWhite else TextMuted,
                                fontWeight = FontWeight.Bold,
                                fontSize = if (isCompactHeight) 15.sp else 17.sp
                            )
                            RollingNumberText(
                                text = "Всего: ${telemetry.totalRecv}",
                                color = TextMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Normal
                            )
                        }

                        // Quality Indicator Orb replacing static divider
                        LiquidWaveQualityCircle(
                            score = if (currentState == ProxyUiState.CONNECTED) telemetry.healthScore else 0,
                            isProxyActive = currentState == ProxyUiState.CONNECTED,
                            isSocks5 = isSocks5,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onOpenDiagnostics()
                            },
                            modifier = Modifier
                                .size(38.dp)
                                .springPress()
                        )

                        // Upload Speed & Total (Weight 1f)
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onOpenSpeedTest()
                                }
                                .padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_arrow_up),
                                    contentDescription = null,
                                    tint = TextMuted,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Исходящий", color = TextMuted, fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            RollingNumberText(
                                text = telemetry.ulSpeed,
                                color = if (currentState == ProxyUiState.CONNECTED) TextWhite else TextMuted,
                                fontWeight = FontWeight.Bold,
                                fontSize = if (isCompactHeight) 15.sp else 17.sp
                            )
                            RollingNumberText(
                                text = "Всего: ${telemetry.totalSent}",
                                color = TextMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Normal
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(if (isCompactHeight) 8.dp else 12.dp))

                    // WsPool Real-Time Smooth Bezier Socket Stability Graph
                    WsPoolStabilityGraph(
                        isProxyActive = currentState == ProxyUiState.CONNECTED,
                        activeConns = telemetry.activeConns,
                        maxPoolSize = app.config.poolSize,
                        accentColor = protoColors.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (isCompactHeight) 48.dp else 58.dp)
                            .padding(horizontal = 12.dp)
                    )

                    Spacer(modifier = Modifier.height(if (isCompactHeight) 8.dp else 12.dp))

                    // Action Buttons Dock (Always visible, requiring Proxy ON)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Left Action: Copy Link
                        Surface(
                            onClick = {
                                if (currentState != ProxyUiState.CONNECTED) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    Toast.makeText(context, "Включите прокси сначала!", Toast.LENGTH_SHORT).show()
                                } else {
                                    val tgUrl = if (app.config.isSocks5Mode) server.getTelegramSocks5Url() else server.getTelegramProxyUrl()
                                    val label = if (app.config.isSocks5Mode) "SOCKS5" else "MTProto"
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("Telegram Proxy", tgUrl)
                                    clipboard.setPrimaryClip(clip)
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    Toast.makeText(context, "Ссылка $label скопирована!", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = if (isCompactHeight) 46.dp else 52.dp)
                                .springPress(),
                            shape = RoundedCornerShape(16.dp),
                            color = Color.Transparent,
                            border = BorderStroke(
                                1.dp,
                                if (currentState == ProxyUiState.CONNECTED) Color(0xFF1F2433) else Color(0xFF161A26)
                            )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_copy),
                                    contentDescription = "Скопировать",
                                    tint = if (currentState == ProxyUiState.CONNECTED) TextWhite else TextMuted.copy(alpha = 0.5f),
                                    modifier = Modifier.size(if (isCompactHeight) 17.dp else 19.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    "Скопировать",
                                    color = if (currentState == ProxyUiState.CONNECTED) TextWhite else TextMuted.copy(alpha = 0.5f),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = if (isCompactHeight) 13.sp else 14.sp,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                        }

                        // Right Action: Apply to Telegram
                        Surface(
                            onClick = {
                                if (currentState != ProxyUiState.CONNECTED) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    Toast.makeText(context, "Включите прокси сначала!", Toast.LENGTH_SHORT).show()
                                } else {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    val tgUrl = if (app.config.isSocks5Mode) server.getTelegramSocks5Url() else server.getTelegramProxyUrl()
                                    applyToTelegramPackages(context, tgUrl)
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = if (isCompactHeight) 46.dp else 52.dp)
                                .springPress(),
                            shape = RoundedCornerShape(16.dp),
                            color = Color.Transparent,
                            border = BorderStroke(
                                1.dp,
                                if (currentState == ProxyUiState.CONNECTED) Color(0xFF1F2433) else Color(0xFF161A26)
                            )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_send),
                                    contentDescription = "В Telegram",
                                    tint = if (currentState == ProxyUiState.CONNECTED) protoColors.primary else TextMuted.copy(alpha = 0.5f),
                                    modifier = Modifier.size(if (isCompactHeight) 17.dp else 19.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    "В Telegram",
                                    color = if (currentState == ProxyUiState.CONNECTED) TextWhite else TextMuted.copy(alpha = 0.5f),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = if (isCompactHeight) 13.sp else 14.sp,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            if (showDonationBanner) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(bottom = padding.calculateBottomPadding() + 8.dp)
                ) {
                    DonationBanner(
                        onSupportClicked = {
                            com.mirrly.tgproxy.service.DonationManager.setDismissedForever(context, true)
                            showDonationBanner = false
                        },
                        onPostponeClicked = {
                            com.mirrly.tgproxy.service.DonationManager.postpone3Days(context)
                            showDonationBanner = false
                        },
                        onDismissForeverClicked = {
                            com.mirrly.tgproxy.service.DonationManager.setDismissedForever(context, true)
                            showDonationBanner = false
                        }
                    )
                }
            } else if (showValueBanner) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(bottom = padding.calculateBottomPadding() + 8.dp)
                ) {
                    ValueStarBanner(
                        onStarClicked = {
                            com.mirrly.tgproxy.service.ValueTriggerManager.markValuePromptShown(context)
                            showValueBanner = false
                        },
                        onDismiss = {
                            com.mirrly.tgproxy.service.ValueTriggerManager.markValuePromptShown(context)
                            showValueBanner = false
                        }
                    )
                }
            }

            if (showSleepTimerDialog) {
                SleepTimerDialog(
                    onDismiss = { showSleepTimerDialog = false }
                )
            }

            if (showConnectDialog) {
                TelegramConnectDialog(
                    onDismiss = { showConnectDialog = false }
                )
            }

            if (showSocks5AuthRequiredDialog) {
                Socks5AuthRequiredDialog(
                    onDismiss = { showSocks5AuthRequiredDialog = false },
                    onConfirm = { user, pass ->
                        showSocks5AuthRequiredDialog = false
                        app.config.socks5Username = user
                        app.config.socks5Password = pass
                        app.prefsManager.saveConfig(app.config)
                        com.mirrly.tgproxy.core.NativeProxy.setSocks5Auth(user, pass)

                        pendingState = ProxyUiState.CONNECTING
                        val serviceIntent = Intent(context, ProxyForegroundService::class.java).apply {
                            action = ProxyForegroundService.ACTION_START
                        }
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                context.startForegroundService(serviceIntent)
                            } else {
                                context.startService(serviceIntent)
                            }
                        } catch (e: Exception) {
                            pendingState = null
                            AppLogger.e("HomeScreen", "Ошибка запуска службы после настройки SOCKS5 auth: ${e.message}")
                        }
                    }
                )
            }
        }
    }
}
// end of HomeScreen

@Composable
fun RotatingProxyRing(
    state: ProxyUiState,
    isSocks5: Boolean = false,
    tiltX: Float = 0f,
    tiltY: Float = 0f,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    // ── BATTERY LIFECYCLE GUARD: MONITOR APP FOREGROUND/BACKGROUND STATE ──
    var isAppResumed by remember { mutableStateOf(true) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP -> {
                    isAppResumed = false
                }
                Lifecycle.Event.ON_RESUME,
                Lifecycle.Event.ON_START -> {
                    isAppResumed = true
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // ── PERFORMANCE GUARD: CHECK IF USER DISABLED ANIMATIONS & FULL REST ON DISCONNECT ──
    val app = MirrlyApplication.instance
    val isAnimationsDisabled by app.prefsManager.animationsDisabledFlow.collectAsState()

    val isRingActive = state != ProxyUiState.DISCONNECTED
    val timeState = produceState(initialValue = 0L, isAppResumed, isAnimationsDisabled, isRingActive) {
        if (!isAppResumed || isAnimationsDisabled || !isRingActive) return@produceState
        val startNano = System.nanoTime() - value
        while (isAppResumed && !isAnimationsDisabled && isRingActive) {
            withFrameNanos { frameTimeNanos ->
                value = frameTimeNanos - startNano
            }
        }
    }

    // Dynamic sweep angle choreography:
    // Connecting: 160° sweeping arc, Connected: 260° stable glowing ring, Disconnecting/Disconnected: 0°
    val targetSweepAngle = when (state) {
        ProxyUiState.CONNECTING -> 160f
        ProxyUiState.CONNECTED -> 260f
        ProxyUiState.DISCONNECTING, ProxyUiState.DISCONNECTED -> 0f
    }
    val animatedSweepAngle by animateFloatAsState(
        targetValue = targetSweepAngle,
        animationSpec = tween(550, easing = FastOutSlowInEasing),
        label = "sweepAngle"
    )

    val targetAlpha = when (state) {
        ProxyUiState.CONNECTING -> 1.0f
        ProxyUiState.CONNECTED -> 0.88f
        ProxyUiState.DISCONNECTING -> 0.40f
        ProxyUiState.DISCONNECTED -> 0f
    }
    val animatedAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(550, easing = FastOutSlowInEasing),
        label = "ringAlpha"
    )

    val protoColors = rememberAnimatedProtocolColors(isSocks5 = isSocks5)

    val headColor = when (state) {
        ProxyUiState.CONNECTING -> protoColors.light
        ProxyUiState.CONNECTED -> protoColors.primary
        ProxyUiState.DISCONNECTING -> protoColors.primary.copy(alpha = 0.5f)
        ProxyUiState.DISCONNECTED -> Color(0xFF353C4F)   // Sleek Dark Gray
    }
    val animatedHeadColor by animateColorAsState(
        targetValue = headColor,
        animationSpec = tween(550, easing = FastOutSlowInEasing),
        label = "headColor"
    )

    val tailColor = when (state) {
        ProxyUiState.CONNECTING -> protoColors.secondary.copy(alpha = 0.18f)
        ProxyUiState.CONNECTED -> protoColors.glow.copy(alpha = 0.28f)
        ProxyUiState.DISCONNECTING -> protoColors.glow.copy(alpha = 0.08f)
        ProxyUiState.DISCONNECTED -> Color.Transparent
    }
    val animatedTailColor by animateColorAsState(
        targetValue = tailColor,
        animationSpec = tween(550, easing = FastOutSlowInEasing),
        label = "tailColor"
    )

    val density = LocalDensity.current
    val targetStrokeWidth = when (state) {
        ProxyUiState.CONNECTING -> 6.5.dp
        ProxyUiState.CONNECTED -> 5.dp
        ProxyUiState.DISCONNECTING -> 6.5.dp
        ProxyUiState.DISCONNECTED -> 3.dp
    }
    val animatedStrokeWidth by animateFloatAsState(
        targetValue = with(density) { targetStrokeWidth.toPx() },
        animationSpec = tween(500, easing = LinearOutSlowInEasing),
        label = "strokeWidth"
    )

    // Reusable Path instances to avoid per-frame allocations
    val wavyPath = remember { Path() }
    val innerPath = remember { Path() }

    // Precalculate density metrics once per density change
    val dp2Px = remember(density) { with(density) { 2.dp.toPx() } }
    val dp3_5Px = remember(density) { with(density) { 3.5.dp.toPx() } }
    val dp4Px = remember(density) { with(density) { 4.dp.toPx() } }
    val dp10Px = remember(density) { with(density) { 10.dp.toPx() } }
    val dp16Px = remember(density) { with(density) { 16.dp.toPx() } }
    val dp26Px = remember(density) { with(density) { 26.dp.toPx() } }

    val sweepColors = remember(animatedTailColor, animatedHeadColor, animatedAlpha) {
        listOf(
            animatedTailColor.copy(alpha = animatedAlpha * 0.15f),
            animatedHeadColor.copy(alpha = animatedAlpha),
            animatedHeadColor.copy(alpha = animatedAlpha),
            animatedTailColor.copy(alpha = animatedAlpha * 0.1f)
        )
    }

    Canvas(modifier = modifier) {
        if (animatedAlpha <= 0.01f) return@Canvas

        val t = timeState.value / 1_000_000_000f
        val diameter = size.minDimension
        val stroke = animatedStrokeWidth

        // Multi-layer gyroscope parallax offsets for 3D depth
        val outerTiltShift = Offset(tiltX * 0.85f, tiltY * 0.85f)
        val innerTiltShift = Offset(tiltX * 1.30f, tiltY * 1.30f)
        val bgTiltShift = Offset(tiltX * 0.40f, tiltY * 0.40f)

        // ── SLOW & SMOOTH ELEGANT ROTATION ──────────────────────────────
        // Slow rotation (~18 seconds per full revolution), silky smooth
        val outerAngle = (t * 20f + kotlin.math.sin(t * 0.3f) * 35f) % 360f

        // Inner counter-rotation (~15 seconds per revolution, opposite direction)
        val innerAngle = -(t * 24f + kotlin.math.cos(t * 0.35f) * 28f) % 360f

        // Smooth wave amplitude for organic curved path ("извилистая плавная дуга")
        val waveAmp = (diameter * 0.013f).coerceIn(dp2Px, dp3_5Px)

        // Outer Ring Bounds
        val outerInset = stroke / 2f + (diameter * 0.035f).coerceIn(dp4Px, dp10Px)
        val outerRadius = (diameter - outerInset * 2) / 2f
        val outerTopLeft = Offset(outerInset + bgTiltShift.x, outerInset + bgTiltShift.y)
        val outerSize = Size(outerRadius * 2, outerRadius * 2)

        // 1. BACKGROUND TRACK RING
        drawArc(
            color = Color(0xFF141824).copy(alpha = 0.5f * animatedAlpha),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = outerTopLeft,
            size = outerSize,
            style = Stroke(width = stroke * 0.65f)
        )

        // 2. MAIN OUTER ROTATING RING (Slow, Smooth, Wavy Curvatures + 3D Parallax)
        val outerCenter = center + outerTiltShift
        rotate(degrees = outerAngle, pivot = outerCenter) {
            val steps = 72
            val sweepRad = Math.toRadians(animatedSweepAngle.toDouble()).toFloat()
            wavyPath.reset()

            for (i in 0..steps) {
                val stepFrac = i.toFloat() / steps
                val currentRad = stepFrac * sweepRad

                // Smooth organic sine waves along the path
                val rWave = outerRadius + waveAmp * kotlin.math.sin(4f * currentRad + t * 1.6f)
                val px = outerCenter.x + rWave * kotlin.math.cos(currentRad)
                val py = outerCenter.y + rWave * kotlin.math.sin(currentRad)

                if (i == 0) wavyPath.moveTo(px, py) else wavyPath.lineTo(px, py)
            }

            // Draw curved ring arc
            drawPath(
                path = wavyPath,
                brush = Brush.sweepGradient(colors = sweepColors, center = outerCenter),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )

            // Glowing Particle Head Dot
            val headAngleRad = sweepRad
            val radiusHead = outerRadius + waveAmp * kotlin.math.sin(4f * headAngleRad + t * 1.6f)
            val dotCenterX = outerCenter.x + radiusHead * kotlin.math.cos(headAngleRad)
            val dotCenterY = outerCenter.y + radiusHead * kotlin.math.sin(headAngleRad)

            drawCircle(
                color = animatedHeadColor.copy(alpha = animatedAlpha),
                radius = stroke * 0.95f,
                center = Offset(dotCenterX, dotCenterY)
            )
            drawCircle(
                color = Color.White.copy(alpha = animatedAlpha * 0.9f),
                radius = stroke * 0.45f,
                center = Offset(dotCenterX, dotCenterY)
            )
        }

        // 3. INNER ACCENT RING (Slow Counter-Rotation + Inner Parallax Layer + Glowing Head)
        val isConnectedRing = state == ProxyUiState.CONNECTED || state == ProxyUiState.CONNECTING
        val isDualForced = state == ProxyUiState.CONNECTING || state == ProxyUiState.DISCONNECTING
        val organicDualFactor = 0.65f + 0.35f * kotlin.math.sin(t * 0.35f)
        val innerAlphaFactor = if (isDualForced) 1.0f else if (isConnectedRing) organicDualFactor else 0.0f

        if (innerAlphaFactor > 0.05f) {
            val innerCenter = center + innerTiltShift
            val innerInset = stroke / 2f + (diameter * 0.105f).coerceIn(dp16Px, dp26Px)
            val innerRadius = (diameter - innerInset * 2) / 2f
            val innerSweep = (110f + 25f * kotlin.math.sin(t * 0.5f)).coerceIn(80f, 150f)

            rotate(degrees = innerAngle, pivot = innerCenter) {
                val innerSteps = 50
                val innerSweepRad = Math.toRadians(innerSweep.toDouble()).toFloat()
                innerPath.reset()

                for (i in 0..innerSteps) {
                    val stepFrac = i.toFloat() / innerSteps
                    val currentRad = stepFrac * innerSweepRad
                    val rWave = innerRadius + (waveAmp * 0.7f) * kotlin.math.sin(3f * currentRad - t * 1.4f)
                    val px = innerCenter.x + rWave * kotlin.math.cos(currentRad)
                    val py = innerCenter.y + rWave * kotlin.math.sin(currentRad)

                    if (i == 0) innerPath.moveTo(px, py) else innerPath.lineTo(px, py)
                }

                drawPath(
                    path = innerPath,
                    color = animatedHeadColor.copy(alpha = animatedAlpha * 0.65f * innerAlphaFactor),
                    style = Stroke(width = stroke * 0.60f, cap = StrokeCap.Round)
                )

                // Inner Counter-Ring Glowing Particle Head
                val innerHeadRad = innerSweepRad
                val innerRadiusHead = innerRadius + (waveAmp * 0.7f) * kotlin.math.sin(3f * innerHeadRad - t * 1.4f)
                val innerDotX = innerCenter.x + innerRadiusHead * kotlin.math.cos(innerHeadRad)
                val innerDotY = innerCenter.y + innerRadiusHead * kotlin.math.sin(innerHeadRad)

                drawCircle(
                    color = animatedHeadColor.copy(alpha = animatedAlpha * 0.85f * innerAlphaFactor),
                    radius = stroke * 0.65f,
                    center = Offset(innerDotX, innerDotY)
                )
                drawCircle(
                    color = Color.White.copy(alpha = animatedAlpha * 0.90f * innerAlphaFactor),
                    radius = stroke * 0.30f,
                    center = Offset(innerDotX, innerDotY)
                )
            }
        }
    }
}


private val telegramPackages = listOf(
    "org.telegram.messenger",
    "com.radolyn.ayugram",
    "com.exteragram.messenger",
    "org.telegram.plus",
    "ir.ilmili.telegraph",
    "org.telegram.BifToGram",
    "tw.nekomimi.nekogram",
    "xyz.nextalone.nagram",
    "uz.unnarsx.cherrygram",
    "org.telegram.mdgram",
    "org.forkclient.messenger.beta",
    "app.nicegram",
    "top.qwq2333.nullgram",
    "com.iMe.android",
    "ru.dahl.messenger",
    "com.scriptsaz.litegram",
    "org.thunderdog.challegram"
)

fun applyToTelegramPackages(context: Context, url: String) {
    val pm = context.packageManager
    val uri = Uri.parse(url)

    val availablePackages = telegramPackages.filter { pkg ->
        try {
            pm.getPackageInfo(pkg, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    val targetedIntents = availablePackages.map { pkg ->
        Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(pkg)
        }
    }

    if (targetedIntents.isEmpty()) {
        val genericIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(genericIntent)
        } catch (_: Exception) {
            Toast.makeText(context, "Telegram клиент не найден", Toast.LENGTH_SHORT).show()
        }
    } else if (targetedIntents.size == 1) {
        val intent = targetedIntents.first().apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(context, "Ошибка открытия клиента", Toast.LENGTH_SHORT).show()
        }
    } else {
        val baseIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooserIntent = Intent.createChooser(baseIntent, "Выберите клиент Telegram").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(chooserIntent)
        } catch (_: Exception) {
            Toast.makeText(context, "Ошибка выбора клиента", Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * Smooth Rolling Numbers composable with Spring Elasticity & Inertia.
 * Animates changing timer digits, speeds, and data metrics with bouncy spring physics,
 * while efficiently skipping animation and subcomposition allocations for static prefixes and non-digit characters.
 */
@Composable
fun RollingNumberText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = TextWhite,
    fontSize: TextUnit = 14.sp,
    fontWeight: FontWeight = FontWeight.Bold,
    fontStyle: FontStyle = FontStyle.Normal,
    letterSpacing: TextUnit = TextUnit.Unspecified
) {
    val app = MirrlyApplication.instance
    val isAnimationsDisabled by app.prefsManager.animationsDisabledFlow.collectAsState()

    if (isAnimationsDisabled) {
        Text(
            text = text,
            modifier = modifier,
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            fontStyle = fontStyle,
            letterSpacing = letterSpacing
        )
        return
    }

    var prevText by remember { mutableStateOf(text) }

    // Calculate length of unchanged prefix between previous and current text
    val commonPrefixLen = remember(text, prevText) {
        var len = 0
        val maxLen = minOf(text.length, prevText.length)
        while (len < maxLen && text[len] == prevText[len]) {
            len++
        }
        len
    }

    SideEffect {
        prevText = text
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        text.forEachIndexed { index, char ->
            val isUnchangedPrefix = index < commonPrefixLen
            if (isUnchangedPrefix || !char.isDigit()) {
                Text(
                    text = char.toString(),
                    color = color,
                    fontSize = fontSize,
                    fontWeight = fontWeight,
                    fontStyle = fontStyle,
                    letterSpacing = letterSpacing
                )
            } else {
                key(index) {
                    AnimatedContent(
                        targetState = char,
                        transitionSpec = {
                            val slideDirection = if (targetState > initialState) 1 else -1
                            (slideInVertically(
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessLow
                                )
                            ) { height -> height * slideDirection } + fadeIn()).togetherWith(
                                slideOutVertically(
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessLow
                                    )
                                ) { height -> -height * slideDirection } + fadeOut()
                            ).using(SizeTransform(clip = false))
                        },
                        label = "rollingChar_$index"
                    ) { targetChar ->
                        Text(
                            text = targetChar.toString(),
                            color = color,
                            fontSize = fontSize,
                            fontWeight = fontWeight,
                            fontStyle = fontStyle,
                            letterSpacing = letterSpacing
                        )
                    }
                }
            }
        }
    }
}

/**
 * Liquid Wave Engine (Continuous Harmonic Superposition Graph).
 * Evaluates a continuous 120 FPS fluid wave equation on GPU canvas per-frame,
 * eliminating discrete array steps and delivering 100% butter-smooth liquid motion.
 */
@Composable
fun WsPoolStabilityGraph(
    isProxyActive: Boolean,
    activeConns: Int,
    maxPoolSize: Int,
    accentColor: Color = ActiveGreenLed,
    modifier: Modifier = Modifier
) {
    // ── PERFORMANCE GUARD: CHECK IF USER DISABLED ANIMATIONS ──
    val app = MirrlyApplication.instance
    val isAnimationsDisabled by app.prefsManager.animationsDisabledFlow.collectAsState()

    // Continuous nanosecond frame clock for 60Hz/120Hz smooth rendering
    var timeSeconds by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(isProxyActive, isAnimationsDisabled) {
        if (!isProxyActive || isAnimationsDisabled) return@LaunchedEffect
        val startTime = System.nanoTime() - (timeSeconds * 1_000_000_000f).toLong()
        while (isProxyActive && !isAnimationsDisabled) {
            withFrameNanos { frameTimeNanos ->
                timeSeconds = (frameTimeNanos - startTime) / 1_000_000_000f
            }
        }
    }

    // Target amplitude smoothly interpolated with spring physics: drops flat to 0.0 when OFF
    val targetAmplitude = if (isProxyActive && maxPoolSize > 0) {
        (activeConns.toFloat() / maxPoolSize.toFloat()).coerceIn(0.18f, 0.95f)
    } else {
        0.00f
    }

    val animatedAmplitude by animateFloatAsState(
        targetValue = targetAmplitude,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "animatedAmplitude"
    )

    val headPulseScale = if (isProxyActive && !isAnimationsDisabled) {
        val infiniteTransition = rememberInfiniteTransition(label = "headPulseTransition")
        val scale by infiniteTransition.animateFloat(
            initialValue = 4.dp.value,
            targetValue = 7.dp.value,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "headPulseScale"
        )
        scale
    } else {
        4.dp.value
    }

    val steps = 48 // Optimal sub-pixel sampling density for max smoothing & low CPU usage
    val yArray = remember { FloatArray(steps + 1) }

    // Reusable Path instances to prevent frame allocations
    val strokePath = remember { Path() }
    val fillPath = remember { Path() }

    val density = LocalDensity.current
    val dp5Px = remember(density) { with(density) { 5.dp.toPx() } }
    val dp2Px = remember(density) { with(density) { 2.dp.toPx() } }
    val dp3Px = remember(density) { with(density) { 3.dp.toPx() } }
    val densityPxRatio = density.density

    val neonColor = if (isProxyActive) accentColor else InactiveGrayLed

    val fillBrush = remember(neonColor, isProxyActive) {
        Brush.verticalGradient(
            colors = listOf(
                neonColor.copy(alpha = if (isProxyActive) 0.32f else 0.08f),
                Color.Transparent
            )
        )
    }

    val ambientGlowBrush = remember(neonColor) {
        Brush.horizontalGradient(
            colors = listOf(
                neonColor.copy(alpha = 0.15f),
                neonColor.copy(alpha = 0.40f),
                neonColor.copy(alpha = 0.40f)
            )
        )
    }

    val mainLineBrush = remember(neonColor) {
        Brush.horizontalGradient(
            colors = listOf(
                neonColor.copy(alpha = 0.4f),
                neonColor,
                neonColor
            )
        )
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val width = size.width
            val height = size.height
            if (width <= 0f || height <= 0f) return@Canvas

            // Fast path for flat/inactive wave: skip trigonometric and cubic spline calculation loops
            if (animatedAmplitude <= 0.001f) {
                val flatY = height - (height * 0.14f)
                strokePath.reset()
                strokePath.moveTo(0f, flatY)
                strokePath.lineTo(width, flatY)

                fillPath.reset()
                fillPath.addPath(strokePath)
                fillPath.lineTo(width, height)
                fillPath.lineTo(0f, height)
                fillPath.close()

                drawPath(path = fillPath, brush = fillBrush)
                drawPath(
                    path = strokePath,
                    brush = mainLineBrush,
                    style = Stroke(
                        width = dp2Px,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
                return@Canvas
            }

            val stepX = width / steps
            val t = timeSeconds

            for (i in 0..steps) {
                val u = i.toFloat() / steps // normalized 0..1
                
                // Silky Soft Low-Frequency Harmonic Superposition
                val w1 = sin(u * 4.2f + t * 1.4f)          // Long rolling primary wave
                val w2 = cos(u * 6.8f - t * 1.1f) * 0.35f  // Soft secondary harmonic
                val w3 = sin(u * 2.5f + t * 1.8f) * 0.15f  // Gentle breathing ripple
                
                val combinedWave = (w1 + w2 + w3) / 1.5f
                val waveHeightSpan = animatedAmplitude * height * 0.40f
                val centerY = height - (animatedAmplitude * height * 0.38f) - (height * 0.14f)
                
                yArray[i] = (centerY + combinedWave * waveHeightSpan).coerceIn(4f, height - 4f)
            }

            // Construct Ultra-Smooth Catmull-Rom C1 Spline Path with Derivative Tangents
            strokePath.reset()
            strokePath.moveTo(0f, yArray[0])
            for (i in 0 until steps) {
                val p0y = yArray[if (i > 0) i - 1 else i]
                val p1y = yArray[i]
                val p2y = yArray[i + 1]
                val p3y = yArray[if (i + 2 <= steps) i + 2 else i + 1]

                val p0x = if (i > 0) (i - 1) * stepX else i * stepX
                val p1x = i * stepX
                val p2x = (i + 1) * stepX
                val p3x = if (i + 2 <= steps) (i + 2) * stepX else (i + 1) * stepX

                val control1X = p1x + (p2x - p0x) / 6f
                val control1Y = p1y + (p2y - p0y) / 6f
                val control2X = p2x - (p3x - p1x) / 6f
                val control2Y = p2y - (p3y - p1y) / 6f

                strokePath.cubicTo(control1X, control1Y, control2X, control2Y, p2x, p2y)
            }

            // Area Fill Path under the Bezier Curve
            fillPath.reset()
            fillPath.addPath(strokePath)
            fillPath.lineTo(width, height)
            fillPath.lineTo(0f, height)
            fillPath.close()

            // 1. Draw Gradient Area Fill under Liquid Wave
            drawPath(
                path = fillPath,
                brush = fillBrush
            )

            // 2. Draw Soft Ambient Glow Line Layer
            drawPath(
                path = strokePath,
                brush = ambientGlowBrush,
                style = Stroke(
                    width = dp5Px,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )

            // 3. Draw Main Crisp Silky Bezier Line
            drawPath(
                path = strokePath,
                brush = mainLineBrush,
                style = Stroke(
                    width = dp2Px,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )

            // 4. Draw Head Pulsating Glowing LED Dot at the leading edge
            if (isProxyActive) {
                val headPoint = Offset(width, yArray[steps])
                val pulseRadius = headPulseScale * densityPxRatio
                drawCircle(
                    color = accentColor.copy(alpha = 0.35f),
                    radius = pulseRadius,
                    center = headPoint
                )
                drawCircle(
                    color = accentColor,
                    radius = dp3Px,
                    center = headPoint
                )
            }
        }

        Spacer(modifier = Modifier.height(3.dp))

        // Subtle dark muted caption under graph
        Text(
            text = "АКТИВНОСТЬ СОКЕТОВ WSPOOL",
            color = TextMuted.copy(alpha = 0.50f),
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp
        )
    }
}

/**
 * Modern tactile Protocol Switcher Header.
 * Supports smooth horizontal drag gestures with physics resistance,
 * instant tap switching, sliding pill indicator, anti-spam locking,
 * static pill position, app title "Мирли", and elegant drop-down active worker badge in SOCKS5 mode.
 */
@Composable
fun ProtocolSwitcherHeader(
    isSocks5: Boolean,
    activeWorker: com.mirrly.tgproxy.core.WorkerProfile,
    protoColors: ProtocolColors,
    isSwitching: Boolean,
    onSwitchProtocol: (com.mirrly.tgproxy.core.ProxyMode) -> Unit,
    onOpenWorkerManager: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // Drag offset for tactile horizontal swipe gesture
    val dragOffsetX = remember { Animatable(0f) }
    val badgeInteractionSource = remember { MutableInteractionSource() }
    val capsuleWidth = 168.dp
    val capsuleHeight = 31.dp
    val tabWidth = capsuleWidth / 2
    val badgeOffsetY = with(density) { (capsuleHeight + 3.dp).roundToPx() }

    // Optimistic UI state for instant 0ms response
    var optimisticIsSocks5 by remember(isSocks5) { mutableStateOf(isSocks5) }

    // Smooth, slow, controlled, and physically natural sliding animation
    val animatedPillOffset by animateDpAsState(
        targetValue = if (optimisticIsSocks5) tabWidth else 0.dp,
        animationSpec = spring(
            dampingRatio = 0.88f,
            stiffness = 380f
        ),
        label = "protoPillOffset"
    )

    val totalOffsetX = animatedPillOffset + with(density) { dragOffsetX.value.toDp() }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        // Switcher Pill and Dropdown Container (anchored so height does NOT shift pill position)
        Box(
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                modifier = Modifier
                    .width(capsuleWidth)
                    .height(capsuleHeight)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Transparent)
                    .border(
                        width = 1.dp,
                        color = Color(0xFF1E283D),
                        shape = RoundedCornerShape(16.dp)
                    )
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = {
                                scope.launch { dragOffsetX.stop() }
                            },
                            onDragEnd = {
                                val currentDrag = dragOffsetX.value
                                val thresholdPx = with(density) { 16.dp.toPx() }
                                scope.launch {
                                    if (!optimisticIsSocks5 && currentDrag > thresholdPx) {
                                        optimisticIsSocks5 = true
                                        HapticHelper.performSwipeGlide(context)
                                        onSwitchProtocol(com.mirrly.tgproxy.core.ProxyMode.SOCKS5)
                                    } else if (optimisticIsSocks5 && currentDrag < -thresholdPx) {
                                        optimisticIsSocks5 = false
                                        HapticHelper.performSwipeGlide(context)
                                        onSwitchProtocol(com.mirrly.tgproxy.core.ProxyMode.MTPROTO)
                                    }
                                    dragOffsetX.animateTo(
                                        0f,
                                        spring(
                                            dampingRatio = 0.88f,
                                            stiffness = 380f
                                        )
                                    )
                                }
                            },
                            onDragCancel = {
                                scope.launch {
                                    dragOffsetX.animateTo(
                                        0f,
                                        spring(
                                            dampingRatio = 0.90f,
                                            stiffness = 380f
                                        )
                                    )
                                }
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                val current = dragOffsetX.value
                                val damped = dragAmount * 0.32f
                                val newOffset = if (!optimisticIsSocks5) {
                                    (current + damped).coerceIn(-4f, with(density) { tabWidth.toPx() } + 4f)
                                } else {
                                    (current + damped).coerceIn(-with(density) { tabWidth.toPx() } - 4f, 4f)
                                }
                                scope.launch { dragOffsetX.snapTo(newOffset) }
                            }
                        )
                    }
            ) {
                // Sliding Glowing Indicator Pill
                Box(
                    modifier = Modifier
                        .offset(x = totalOffsetX.coerceIn(0.dp, tabWidth))
                        .width(tabWidth)
                        .fillMaxHeight()
                        .padding(2.5.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(protoColors.primary.copy(alpha = 0.16f))
                        .border(
                            1.dp,
                            protoColors.primary.copy(alpha = 0.55f),
                            RoundedCornerShape(13.dp)
                        )
                )

                // Segment Labels Row
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // MTProto Tab
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                if (optimisticIsSocks5) {
                                    HapticHelper.performTapClick(context)
                                    optimisticIsSocks5 = false
                                    onSwitchProtocol(com.mirrly.tgproxy.core.ProxyMode.MTPROTO)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "MTProto",
                            color = if (!optimisticIsSocks5) protoColors.primary else TextMuted,
                            fontSize = 12.sp,
                            fontWeight = if (!optimisticIsSocks5) FontWeight.Bold else FontWeight.Medium,
                            letterSpacing = 0.3.sp
                        )
                    }

                    // SOCKS5 Tab
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                if (!optimisticIsSocks5) {
                                    HapticHelper.performTapClick(context)
                                    optimisticIsSocks5 = true
                                    onSwitchProtocol(com.mirrly.tgproxy.core.ProxyMode.SOCKS5)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "SOCKS5",
                            color = if (optimisticIsSocks5) protoColors.primary else TextMuted,
                            fontSize = 12.sp,
                            fontWeight = if (optimisticIsSocks5) FontWeight.Bold else FontWeight.Medium,
                            letterSpacing = 0.3.sp
                        )
                    }
                }
            }

            // SOCKS5 Active Worker Indicator Dropdown Badge
            Box(
                modifier = Modifier
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, 0) {
                            placeable.placeRelative(0, badgeOffsetY)
                        }
                    }
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = optimisticIsSocks5,
                    enter = fadeIn(tween(220)) + expandVertically(tween(220)),
                    exit = fadeOut(tween(160)) + shrinkVertically(tween(160))
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Transparent)
                            .border(0.8.dp, protoColors.primary.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                            .clickable(
                                interactionSource = badgeInteractionSource,
                                indication = null
                            ) {
                                HapticHelper.performSoftTick(context)
                                onOpenWorkerManager()
                            }
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(4.5.dp)
                                .clip(CircleShape)
                                .background(protoColors.primary)
                        )
                        Spacer(modifier = Modifier.width(4.5.dp))
                        Text(
                            text = activeWorker.name,
                            color = protoColors.primary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

/**
 * Круглый виджет качества соединения с эффектом наполнения жидкими волнами (Liquid Wave Orb).
 * Показывает процент SQI и анимирует уровень жидкости с физикой волн.
 */
@Composable
fun LiquidWaveQualityCircle(
    score: Int,
    isProxyActive: Boolean,
    isSocks5: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveTransition")
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavePhase"
    )
    val wavePhase2 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavePhase2"
    )

    val targetFraction = (score / 100f).coerceIn(0f, 1f)
    val animatedFill by animateFloatAsState(
        targetValue = if (isProxyActive) targetFraction else 0f,
        animationSpec = tween(1000, easing = FastOutSlowInEasing),
        label = "liquidFill"
    )

    val baseColor = if (isSocks5) {
        when {
            score >= 90 -> Color(0xFF818CF8) // SOCKS5 Indigo/Purple
            score >= 75 -> Color(0xFFA78BFA) // Vibrant Violet
            score >= 50 -> Color(0xFFFFB703) // Amber warning
            else -> Color(0xFFFF0055)        // Crimson red
        }
    } else {
        when {
            score >= 90 -> Color(0xFF00FF87) // MTProto Emerald Mint
            score >= 75 -> Color(0xFF00E676) // Bright Green
            score >= 50 -> Color(0xFFFFB703) // Amber warning
            else -> Color(0xFFFF0055)        // Crimson red
        }
    }

    val animatedColor by animateColorAsState(
        targetValue = baseColor,
        animationSpec = tween(600),
        label = "liquidColor"
    )

    // Pre-allocated Path objects to prevent GC churn at 120 FPS
    val bgPath = remember { Path() }
    val fgPath = remember { Path() }
    val crestPath = remember { Path() }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(CircleShape)
            .background(Color(0xFF0B101C).copy(alpha = 0.85f))
            .border(1.2.dp, animatedColor.copy(alpha = 0.45f), CircleShape)
            .clickable(onClick = onClick)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            if (animatedFill > 0.005f) {
                val waterY = height * (1f - animatedFill)
                val waveAmp = (height * 0.055f).coerceAtLeast(1.5f)

                // 1. Background Wave (Soft Alpha)
                bgPath.reset()
                bgPath.moveTo(0f, height)
                bgPath.lineTo(0f, waterY)
                val steps = 24
                for (i in 0..steps) {
                    val x = width * (i.toFloat() / steps)
                    val y = waterY + waveAmp * sin((x / width) * 2 * Math.PI + wavePhase2).toFloat()
                    bgPath.lineTo(x, y)
                }
                bgPath.lineTo(width, height)
                bgPath.close()

                drawPath(
                    path = bgPath,
                    color = animatedColor.copy(alpha = 0.35f)
                )

                // 2. Main Foreground Wave
                fgPath.reset()
                fgPath.moveTo(0f, height)
                fgPath.lineTo(0f, waterY)
                for (i in 0..steps) {
                    val x = width * (i.toFloat() / steps)
                    val y = waterY + waveAmp * sin((x / width) * 2 * Math.PI + wavePhase).toFloat()
                    fgPath.lineTo(x, y)
                }
                fgPath.lineTo(width, height)
                fgPath.close()

                drawPath(
                    path = fgPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            animatedColor.copy(alpha = 0.88f),
                            animatedColor.copy(alpha = 0.65f)
                        ),
                        startY = waterY - waveAmp,
                        endY = height
                    )
                )

                // 3. Wave Crest Highlight Line
                crestPath.reset()
                for (i in 0..steps) {
                    val x = width * (i.toFloat() / steps)
                    val y = waterY + waveAmp * sin((x / width) * 2 * Math.PI + wavePhase).toFloat()
                    if (i == 0) crestPath.moveTo(x, y) else crestPath.lineTo(x, y)
                }
                drawPath(
                    path = crestPath,
                    color = Color.White.copy(alpha = 0.60f),
                    style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }

        // Center Percentage Text
        Text(
            text = "$score%",
            color = TextWhite,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace,
            letterSpacing = (-0.5).sp,
            textAlign = TextAlign.Center
        )
    }
}





