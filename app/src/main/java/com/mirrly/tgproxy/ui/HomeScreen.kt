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
import com.mirrly.tgproxy.util.findActivity
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.res.stringResource
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
    val dlSpeed: String = "0 B/s",
    val ulSpeed: String = "0 B/s",
    val activeConns: Int = 0,
    val totalRecv: String = "0 B",
    val totalSent: String = "0 B",
    val uptimeSeconds: Long = 0L,
    val pingMs: Long = -1L,
    val jitterMs: Long = 0L,
    val healthScore: Int = 100,
    val healthVerdict: String = "Optimal Connection",
    val healthDetail: String = "Minimal latency and stable direct WSS tunnel",
    val healthSuccessRate: Int = 100,
    val effectiveRoute: String = "",
    val operator: String = "",
    val isTrustBoundaryMaintained: Boolean = true,
    val isPrivateNode: Boolean = false
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
    onOpenVpnMode: () -> Unit = {},
    onDragWorkerManager: (Float) -> Unit = {},
    onSettleWorkerManager: (Float) -> Unit = {},
    isVpnTabActive: Boolean = false,
    onVpnTabChange: (Boolean) -> Unit = {},
    onOpenTelegramChannel: () -> Unit = {},
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
    val systemVpnColors = remember { com.mirrly.tgproxy.ui.theme.VpnThemeManager.getSystemVpnPalette(context) }

    var activeTab by rememberSaveable {
        mutableStateOf(
            if (isVpnTabActive) HomeScreenTab.VPN
            else if (isSocks5) HomeScreenTab.SOCKS5
            else HomeScreenTab.MTPROTO
        )
    }
    var showVpnInDevDialog by remember { mutableStateOf(false) }

    val vpnState by com.mirrly.tgproxy.service.MirrlyVpnService.vpnState.collectAsState()
    val vpnLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            com.mirrly.tgproxy.service.MirrlyVpnService.start(context)
        } else {
            Toast.makeText(context, context.getString(R.string.vpn_permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(isVpnTabActive) {
        if (isVpnTabActive && activeTab != HomeScreenTab.VPN) {
            activeTab = HomeScreenTab.VPN
        } else if (!isVpnTabActive && activeTab == HomeScreenTab.VPN) {
            activeTab = if (isSocks5) HomeScreenTab.SOCKS5 else HomeScreenTab.MTPROTO
        }
    }

    LaunchedEffect(isSocks5) {
        if (activeTab != HomeScreenTab.VPN) {
            activeTab = if (isSocks5) HomeScreenTab.SOCKS5 else HomeScreenTab.MTPROTO
        }
    }

    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var isAppResumed by remember { mutableStateOf(true) }

    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE,
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> isAppResumed = false
                androidx.lifecycle.Lifecycle.Event.ON_RESUME,
                androidx.lifecycle.Lifecycle.Event.ON_START -> {
                    isAppResumed = true
                    if (!server.isRunning) {
                        app.prefsManager.restoreUserPrimaryWorkerIfNeeded()
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val uplinkMode by app.prefsManager.uplinkModeFlow.collectAsState()
    val vpnUplinkMode by app.prefsManager.vpnUplinkModeFlow.collectAsState()
    val warpProfile = remember(isAppResumed, isSocks5) { app.prefsManager.getWarpProfile() }
    val vlessUuid = remember(isAppResumed) { app.prefsManager.getVlessUuid().ifEmpty { app.config.vlessUuid } }
    val vlessPath = remember(isAppResumed) { app.prefsManager.getVlessPath().ifEmpty { app.config.vlessPath } }
    var showUplinkStateDialog by remember { mutableStateOf(false) }

    var pendingState by remember { mutableStateOf<ProxyUiState?>(null) }
    var lastPowerClickMs by remember { mutableLongStateOf(0L) }

    val isSwitching by com.mirrly.tgproxy.service.ProtocolSwitchManager.isSwitching.collectAsState()
    val switchPhase by com.mirrly.tgproxy.service.ProtocolSwitchManager.switchPhase.collectAsState()
    val wasProxyRunningDuringSwitch by com.mirrly.tgproxy.service.ProtocolSwitchManager.wasProxyRunningDuringSwitch.collectAsState()
    val preflightStage by com.mirrly.tgproxy.service.PreflightDiagnosticsEngine.preflightStage.collectAsState()
    val preflightMessage by com.mirrly.tgproxy.service.PreflightDiagnosticsEngine.preflightStatusMessage.collectAsState()
    val warpProfilerPhase by com.mirrly.tgproxy.core.WarpPipelineProfiler.currentPhase.collectAsState()

    // ── GROUPED IMMUTABLE TELEMETRY WITH DISTINCT-UNTIL-CHANGED ──
    val telemetry: ProxyLiveTelemetry by remember(isAppResumed) {
        flow<ProxyLiveTelemetry> {
            while (currentCoroutineContext().isActive && isAppResumed) {
                val running = server.isRunning
                val uptime = server.uptimeSeconds
                val currPing = server.currentPingMs
                var dl = context.getString(R.string.telemetry_speed_zero)
                var ul = context.getString(R.string.telemetry_speed_zero)
                var conns = 0
                var recv = context.getString(R.string.telemetry_bytes_zero)
                var sent = context.getString(R.string.telemetry_bytes_zero)
                var jitter = 0L
                var healthScore = 100
                var healthVerdict = context.getString(R.string.verdict_ideal_channel)
                var healthDetail = context.getString(R.string.verdict_ideal_channel_desc)
                var successRate = 100
                var effRoute = ""
                var op = ""
                var trustOk = true
                var isPriv = false

                if (running) {
                    val stats = server.stats
                    dl = context.getString(R.string.telemetry_speed_format, humanBytes(stats.downloadSpeedBps))
                    ul = context.getString(R.string.telemetry_speed_format, humanBytes(stats.uploadSpeedBps))
                    conns = stats.activeConnections.get()
                    recv = humanBytes(stats.totalBytesReceived.get())
                    sent = humanBytes(stats.totalBytesSent.get())
                    jitter = stats.jitterMs
                    healthScore = stats.healthScore
                    healthVerdict = ConnectionHealthFormatter.formatVerdict(context, stats.healthVerdict)
                    healthDetail = ConnectionHealthFormatter.formatDetail(context, stats.healthDetail)
                    successRate = stats.healthSuccessRate
                    effRoute = stats.activeEffectiveRoute
                    op = stats.activeOperator
                    trustOk = stats.isTrustBoundaryMaintained
                    isPriv = stats.isPrivateNode
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
                        healthSuccessRate = successRate,
                        effectiveRoute = effRoute,
                        operator = op,
                        isTrustBoundaryMaintained = trustOk,
                        isPrivateNode = isPriv
                    )
                )
                delay(500)
            }
        }
            .flowOn(Dispatchers.IO)
            .distinctUntilChanged()
    }.collectAsState(initial = ProxyLiveTelemetry(isRunning = server.isRunning))

    val isServerRunning by server.isRunningFlow.collectAsState()
    val isProxyRunning = telemetry.isRunning || isServerRunning || server.isRunning

    val currentState = when (switchPhase) {
        com.mirrly.tgproxy.service.SwitchPhase.DISCONNECTING -> ProxyUiState.DISCONNECTING
        com.mirrly.tgproxy.service.SwitchPhase.PAUSE_DARK -> ProxyUiState.DISCONNECTED
        com.mirrly.tgproxy.service.SwitchPhase.RECONNECTING -> ProxyUiState.CONNECTING
        com.mirrly.tgproxy.service.SwitchPhase.IDLE -> pendingState ?: if (isProxyRunning) ProxyUiState.CONNECTED else ProxyUiState.DISCONNECTED
    }

    LaunchedEffect(isProxyRunning, pendingState) {
        if (pendingState == ProxyUiState.CONNECTING && isProxyRunning) {
            pendingState = null
        } else if (pendingState == ProxyUiState.DISCONNECTING && !isProxyRunning) {
            pendingState = null
        }
    }

    val updateInfo by com.mirrly.tgproxy.service.UpdateManager.updateState.collectAsState()
    val timerState by com.mirrly.tgproxy.service.SleepTimerManager.timerState.collectAsState()
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var showConnectDialog by remember { mutableStateOf(false) }
    var showSocks5AuthRequiredDialog by remember { mutableStateOf(false) }

    // Safety timeout to prevent stuck connecting/disconnecting UI (accommodates pre-flight phase)
    LaunchedEffect(pendingState) {
        if (pendingState != null) {
            delay(5500)
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
                // LEVEL 1: Top row (~38-40 dp, verticalAlignment = CenterVertically)
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
                        // 1. Logs (Diagnostics)
                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onOpenLogs()
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_logs),
                                contentDescription = stringResource(R.string.content_desc_logs),
                                tint = TextWhite,
                                modifier = Modifier.size(19.dp)
                            )
                        }

                        // 2. Session History (Statistics sessions)
                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onOpenHistory()
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_history),
                                contentDescription = stringResource(R.string.content_desc_session_history),
                                tint = TextWhite,
                                modifier = Modifier.size(19.dp)
                            )
                        }

                        // 3. Sleep Timer (Management time note)
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
                                    contentDescription = stringResource(R.string.content_desc_sleep_timer),
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

                    // Strictly by center: title «Mirrly» (to same  height and horizontal axis, as and icons)
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
                        // 1. Update Center (note note)
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
                                    contentDescription = stringResource(R.string.content_desc_updates),
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

                        // 6. Settings (note)
                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onOpenSettings()
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_settings),
                                contentDescription = stringResource(R.string.action_settings),
                                tint = TextWhite,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                TelegramChannelCapsuleButton(
                    onClick = onOpenTelegramChannel
                )

                Spacer(modifier = Modifier.height(3.dp))

                // =========================================================================
                // LEVEL 2: note note note (note MTProto | SOCKS5 | VPN + note)
                // =========================================================================
                ProtocolSwitcherHeader(
                    currentTab = activeTab,
                    onSelectTab = { newTab ->
                        activeTab = newTab
                        onVpnTabChange(newTab == HomeScreenTab.VPN)
                    },
                    activeWorker = activeWorker,
                    protoColors = protoColors,
                    vpnColors = systemVpnColors,
                    isSwitching = isSwitching,
                    onSwitchProtocol = { target ->
                        switchProtocol(target)
                    },
                    onOpenWorkerManager = onOpenWorkerManager,
                    uplinkMode = uplinkMode,
                    vpnUplinkMode = vpnUplinkMode,
                    warpProfile = warpProfile,
                    vlessUuid = vlessUuid,
                    onOpenUplinkState = { showUplinkStateDialog = true },
                    onOpenVpnInfo = { showVpnInDevDialog = true }
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
                isVeryCompactHeight -> 165.dp
                isCompactHeight -> 190.dp
                else -> 215.dp
            }
            val powerIconSize = ringSize * (155f / 220f)

            val testBanner = context.findActivity<MainActivity>()?.testBannerState?.value
            var showDonationBanner by remember(testBanner) {
                mutableStateOf(testBanner == "donation" || com.mirrly.tgproxy.service.DonationManager.shouldShowDonationBanner(context))
            }
            var showValueBanner by remember(testBanner) {
                mutableStateOf(testBanner == "star_banner" || com.mirrly.tgproxy.service.ValueTriggerManager.shouldShowValueBanner(context))
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
                        updateInfo?.let { info ->
                            val updateYellow = Color(0xFFFFB703)
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color.Transparent,
                                border = BorderStroke(1.dp, updateYellow.copy(alpha = 0.50f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
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
                                            text = stringResource(R.string.banner_update_title, info.versionName),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            color = updateYellow
                                        )
                                        Text(
                                            text = stringResource(R.string.banner_update_install),
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
                                            Toast.makeText(context, context.getString(R.string.banner_update_dismissed, info.versionName), Toast.LENGTH_SHORT).show()
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
                val isWarpMissing = false
                val isDeveloperWorkerActive = isSocks5 && activeWorker.isDeveloperWorker && isProxyRunning
                val shouldShowUplinkNotice = isDeveloperWorkerActive
                var isUplinkNoticeVisible by remember { mutableStateOf(false) }

                LaunchedEffect(shouldShowUplinkNotice) {
                    if (shouldShowUplinkNotice) {
                        delay(1200)
                        isUplinkNoticeVisible = true
                    } else {
                        isUplinkNoticeVisible = false
                    }
                }

                val buttonInertiaOffsetY by animateDpAsState(
                    targetValue = if (isUplinkNoticeVisible && shouldShowUplinkNotice) (-4).dp else 0.dp,
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
                                if (activeTab == HomeScreenTab.VPN) {
                                    HapticHelper.performTapClick(context)
                                    showVpnInDevDialog = true
                                    return@clickable
                                }
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
                                    AppLogger.e("HomeScreen", "Proxy service switch error: ${e.message}")
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (activeTab == HomeScreenTab.VPN) {
                            RotatingVpnRing(
                                vpnColors = systemVpnColors,
                                state = vpnState,
                                modifier = Modifier.size(ringSize)
                            )

                            AnimatedVpnShield(
                                vpnColors = systemVpnColors,
                                state = vpnState,
                                modifier = Modifier.size(powerIconSize)
                            )
                        } else {
                            RotatingProxyRing(
                                state = currentState,
                                isSocks5 = isSocks5,
                                modifier = Modifier.size(ringSize)
                            )

                            AnimatedWarpGlider(
                                state = currentState,
                                isSocks5 = isSocks5,
                                modifier = Modifier.size(powerIconSize)
                            )
                        }
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
                    if (activeTab == HomeScreenTab.VPN) {
                        // ─── VPN MODE DASHBOARD (AMOLED Transparent) ───
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(bottom = 4.dp)
                        ) {
                            Surface(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    if (vpnState == VpnUiState.CONNECTED || vpnState == VpnUiState.CONNECTING) {
                                        com.mirrly.tgproxy.service.MirrlyVpnService.stop(context)
                                    } else {
                                        val prepareIntent = com.mirrly.tgproxy.service.MirrlyVpnService.prepare(context)
                                        if (prepareIntent != null) {
                                            vpnLauncher.launch(prepareIntent)
                                        } else {
                                            com.mirrly.tgproxy.service.MirrlyVpnService.start(context)
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(16.dp),
                                color = Color.Transparent,
                                border = BorderStroke(1.dp, if (vpnState == VpnUiState.CONNECTED) systemVpnColors.primary.copy(alpha = 0.40f) else AmoledBorder),
                                modifier = Modifier.springPress(onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    if (vpnState == VpnUiState.CONNECTED || vpnState == VpnUiState.CONNECTING) {
                                        com.mirrly.tgproxy.service.MirrlyVpnService.stop(context)
                                    } else {
                                        val prepareIntent = com.mirrly.tgproxy.service.MirrlyVpnService.prepare(context)
                                        if (prepareIntent != null) {
                                            vpnLauncher.launch(prepareIntent)
                                        } else {
                                            com.mirrly.tgproxy.service.MirrlyVpnService.start(context)
                                        }
                                    }
                                })
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = when (vpnState) {
                                            VpnUiState.CONNECTED -> systemVpnColors.primary
                                            VpnUiState.CONNECTING, VpnUiState.DISCONNECTING -> Color(0xFFFF9E00)
                                            VpnUiState.DISCONNECTED -> Color(0xFF353C4F)
                                        },
                                        modifier = Modifier.size(6.dp)
                                    ) {}

                                    Text(
                                        text = stringResource(R.string.status_vpn_in_dev),
                                        color = if (vpnState == VpnUiState.CONNECTED) TextWhite else TextMuted,
                                        fontSize = if (isCompactHeight) 13.5.sp else 14.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.1.sp
                                    )
                                }
                            }
                        }

                        Text(
                            text = stringResource(R.string.status_vpn_in_dev_desc),
                            color = TextMuted,
                            fontSize = if (isCompactHeight) 11.sp else 11.5.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    showVpnInDevDialog = true
                                }
                        )

                        Spacer(modifier = Modifier.height(if (isCompactHeight) 6.dp else 10.dp))

                        Surface(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                showVpnInDevDialog = true
                            },
                            shape = RoundedCornerShape(16.dp),
                            color = Color(0xFF1E293B).copy(alpha = 0.35f),
                            border = BorderStroke(1.dp, Color(0xFFFFB74D).copy(alpha = 0.35f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFFFB74D).copy(alpha = 0.12f))
                                        .border(1.dp, Color(0xFFFFB74D).copy(alpha = 0.45f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_settings),
                                        contentDescription = null,
                                        tint = Color(0xFFFFB74D),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.vpn_in_dev_card_title),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextWhite
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = Color(0xFFFFB74D).copy(alpha = 0.16f),
                                            border = BorderStroke(0.6.dp, Color(0xFFFFB74D).copy(alpha = 0.50f))
                                        ) {
                                            Text(
                                                text = stringResource(R.string.settings_vpn_in_dev_badge),
                                                fontSize = 8.5.sp,
                                                fontWeight = FontWeight.Black,
                                                color = Color(0xFFFFB74D),
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = stringResource(R.string.vpn_in_dev_card_desc),
                                        fontSize = 11.5.sp,
                                        color = TextMuted,
                                        lineHeight = 15.sp
                                    )
                                }
                            }
                        }
                    } else {
                        // SOCKS5 Uplink / Worker Info Notice (Smooth expanding/shrinking from center with staggered delay)
                        AnimatedVisibility(
                        visible = isUplinkNoticeVisible && shouldShowUplinkNotice,
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
                        if (isWarpMissing) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color.Transparent,
                                border = BorderStroke(1.dp, Color(0xFFFF9E00).copy(alpha = 0.45f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp)
                                    .padding(bottom = if (isCompactHeight) 5.dp else 7.dp)
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onOpenSettings()
                                    }
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = if (uplinkMode == com.mirrly.tgproxy.core.UplinkMode.MASQUE) stringResource(R.string.warp_masque_registration_needed) else stringResource(R.string.warp_hybrid_registration_needed),
                                        color = Color(0xFFFF9E00),
                                        fontSize = if (isCompactHeight) 10.5.sp else 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = stringResource(R.string.warp_tap_settings_create_profile),
                                        color = Color(0xFFFFB74D).copy(alpha = 0.70f),
                                        fontSize = if (isCompactHeight) 9.5.sp else 10.5.sp,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        } else if (isDeveloperWorkerActive) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color.Transparent,
                                border = BorderStroke(1.dp, Color(0xFFFF9E00).copy(alpha = 0.35f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp)
                                    .padding(bottom = if (isCompactHeight) 5.dp else 7.dp)
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onOpenWorkerManager()
                                    }
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.worker_shared_pool_badge, activeWorker.name),
                                        color = Color(0xFFFF9E00).copy(alpha = 0.85f),
                                        fontSize = if (isCompactHeight) 10.5.sp else 11.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = FontFamily.Monospace,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = stringResource(R.string.worker_shared_pool_warning),
                                        color = Color(0xFFFFB74D).copy(alpha = 0.60f),
                                        fontSize = if (isCompactHeight) 9.5.sp else 10.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        textAlign = TextAlign.Center,
                                        lineHeight = if (isCompactHeight) 13.sp else 14.5.sp
                                    )
                                }
                            }
                        }
                    }

                    // Unified Central Time & Sleep Timer Capsule (Cyber aesthetic)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(bottom = 4.dp)
                    ) {
                        Surface(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                showSleepTimerDialog = true
                            },
                            shape = RoundedCornerShape(16.dp),
                            color = Color.Transparent,
                            border = BorderStroke(
                                1.dp,
                                if (currentState == ProxyUiState.CONNECTED) protoColors.primary.copy(alpha = 0.35f) else AmoledBorder
                            ),
                            modifier = Modifier
                                .animateContentSize(
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    )
                                )
                                .springPress()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
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
                                    ProxyUiState.CONNECTING -> {
                                        if (preflightStage == com.mirrly.tgproxy.service.PreflightStage.OPTIMIZING_ROUTE ||
                                            preflightStage == com.mirrly.tgproxy.service.PreflightStage.VALIDATING_DNS ||
                                            preflightStage == com.mirrly.tgproxy.service.PreflightStage.PROBING_CANDIDATES
                                        ) {
                                            stringResource(R.string.status_optimizing_route)
                                        } else {
                                            stringResource(R.string.status_connecting_caps)
                                        }
                                    }
                                    ProxyUiState.DISCONNECTING -> stringResource(R.string.status_disconnecting_caps)
                                    ProxyUiState.DISCONNECTED -> "00:00:00"
                                }
                                RollingNumberText(
                                    text = statusText,
                                    color = if (currentState == ProxyUiState.CONNECTED) protoColors.primary else if (currentState == ProxyUiState.DISCONNECTING) Color(0xFFFF9E00) else TextMuted,
                                    fontSize = if (isCompactHeight) 13.5.sp else 14.5.sp,
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
                                            .background(AmoledBorder)
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
                                            fontSize = if (isCompactHeight) 13.5.sp else 14.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.1.sp
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // User-friendly Status line (Reflects active protocol and uplink mode)
                    val statusSubtitle = when (currentState) {
                        ProxyUiState.CONNECTED -> {
                            if (telemetry.healthVerdict.contains(stringResource(R.string.status_waiting_network), ignoreCase = true) || telemetry.healthVerdict.contains("Waiting for network", ignoreCase = true) || (telemetry.healthScore == 0 && telemetry.pingMs < 0)) {
                                stringResource(R.string.status_offline)
                            } else if (!isSocks5) {
                                if (app.config.cfProxyEnabled) {
                                    stringResource(R.string.uplink_mtproto_flowseal_secured)
                                } else {
                                    stringResource(R.string.uplink_mtproto_direct_secured)
                                }
                            } else {
                                if (telemetry.effectiveRoute.isNotBlank()) {
                                    com.mirrly.tgproxy.core.ProxyDisplayLabels.homeStatusLabel(
                                        effectiveRoute = telemetry.effectiveRoute,
                                        operator = telemetry.operator,
                                        isTrustBoundaryMaintained = telemetry.isTrustBoundaryMaintained,
                                        configuredWorker = true,
                                        securedLabel = stringResource(R.string.status_secured),
                                        publicFallbackLabel = stringResource(R.string.status_public_fallback)
                                    )
                                } else {
                                    stringResource(R.string.uplink_cf_wss_secured)
                                }
                            }
                        }
                        ProxyUiState.CONNECTING -> {
                            if (preflightMessage.isNotBlank()) {
                                preflightMessage
                            } else if (!isSocks5) {
                                stringResource(R.string.status_connecting_tg_mtproto)
                            } else {
                                stringResource(R.string.status_connecting_cf_wss)
                            }
                        }
                        ProxyUiState.DISCONNECTING -> stringResource(R.string.status_stopping)
                        ProxyUiState.DISCONNECTED -> {
                            val lastFail = server.stats.lastFailureType
                            if (lastFail != com.mirrly.tgproxy.core.FailureType.NONE) {
                                val failDesc = ConnectionHealthFormatter.formatFailureForUser(context, lastFail)
                                if (failDesc.isNotBlank()) {
                                    failDesc
                                } else {
                                    stringResource(R.string.status_protection_disabled)
                                }
                            } else {
                                stringResource(R.string.status_protection_disabled)
                            }
                        }
                    }

                    Text(
                        text = statusSubtitle,
                        color = if (currentState == ProxyUiState.CONNECTED) protoColors.primary.copy(alpha = 0.9f)
                            else if (currentState == ProxyUiState.DISCONNECTED && server.stats.lastFailureType != com.mirrly.tgproxy.core.FailureType.NONE) Color(0xFFFF6E6E)
                            else TextMuted,
                        fontSize = if (isCompactHeight) 11.sp else 11.5.sp,
                        fontWeight = if (currentState == ProxyUiState.CONNECTED) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        softWrap = false,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                showUplinkStateDialog = true
                            }
                    )

                    Spacer(modifier = Modifier.height(if (isCompactHeight) 6.dp else 10.dp))

                    // ─── UNIFIED NETWORK DASHBOARD WIDGET (SPEEDS + SQI ORB + WSPOOL GRAPH) ───
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color.Transparent,
                        border = BorderStroke(
                            1.dp,
                            if (currentState == ProxyUiState.CONNECTED) protoColors.primary.copy(alpha = 0.25f) else AmoledBorder
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 2.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Part A: Speeds & SQI Orb
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Download Speed & Total (Weight 1f)
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            onOpenSpeedTest()
                                        }
                                        .padding(vertical = 2.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_arrow_down),
                                            contentDescription = null,
                                            tint = if (currentState == ProxyUiState.CONNECTED) protoColors.primary else TextMuted,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            stringResource(R.string.label_incoming),
                                            color = TextMuted,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    RollingNumberText(
                                        text = telemetry.dlSpeed,
                                        color = if (currentState == ProxyUiState.CONNECTED) TextWhite else TextMuted,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = if (isCompactHeight) 14.sp else 16.sp
                                    )
                                    Text(
                                        text = stringResource(R.string.label_total, telemetry.totalRecv),
                                        color = TextMuted.copy(alpha = 0.70f),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Normal,
                                        maxLines = 1
                                    )
                                }

                                // Center SQI Quality Orb
                                LiquidWaveQualityCircle(
                                    score = if (currentState == ProxyUiState.CONNECTED) telemetry.healthScore else 0,
                                    isProxyActive = currentState == ProxyUiState.CONNECTED,
                                    isSocks5 = isSocks5,
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onOpenDiagnostics()
                                    },
                                    modifier = Modifier
                                        .size(34.dp)
                                        .springPress()
                                )

                                // Upload Speed & Total (Weight 1f)
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            onOpenSpeedTest()
                                        }
                                        .padding(vertical = 2.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_arrow_up),
                                            contentDescription = null,
                                            tint = TextMuted,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            stringResource(R.string.label_outgoing),
                                            color = TextMuted,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    RollingNumberText(
                                        text = telemetry.ulSpeed,
                                        color = if (currentState == ProxyUiState.CONNECTED) TextWhite else TextMuted,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = if (isCompactHeight) 14.sp else 16.sp
                                    )
                                    Text(
                                        text = stringResource(R.string.label_total, telemetry.totalSent),
                                        color = TextMuted.copy(alpha = 0.70f),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Normal,
                                        maxLines = 1
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            // Subtle divider line inside widget
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(0.8.dp)
                                    .background(AmoledBorder.copy(alpha = 0.5f))
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            // Part B: WsPool Smooth Bezier Socket Stability Graph
                            WsPoolStabilityGraph(
                                isProxyActive = currentState == ProxyUiState.CONNECTED,
                                activeConns = telemetry.activeConns,
                                maxPoolSize = app.config.mtprotoStandbyPerActiveSlot,
                                accentColor = protoColors.primary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(if (isCompactHeight) 38.dp else 44.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(if (isCompactHeight) 6.dp else 10.dp))

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
                                    Toast.makeText(context, context.getString(R.string.msg_start_proxy_first), Toast.LENGTH_SHORT).show()
                                } else {
                                    val tgUrl = if (app.config.isSocks5Mode) server.getTelegramSocks5Url() else server.getTelegramProxyUrl()
                                    val label = if (app.config.isSocks5Mode) "SOCKS5" else "MTProto"
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("Telegram Proxy", tgUrl)
                                    clipboard.setPrimaryClip(clip)
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    Toast.makeText(context, context.getString(R.string.msg_link_copied, label), Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .springPress(),
                            shape = RoundedCornerShape(12.dp),
                            color = Color.Transparent,
                            border = BorderStroke(
                                1.dp,
                                if (currentState == ProxyUiState.CONNECTED) protoColors.primary.copy(alpha = 0.35f) else AmoledBorder
                            )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_copy),
                                    contentDescription = stringResource(R.string.action_copy),
                                    tint = if (currentState == ProxyUiState.CONNECTED) TextWhite else TextMuted.copy(alpha = 0.5f),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    stringResource(R.string.action_copy),
                                    color = if (currentState == ProxyUiState.CONNECTED) TextWhite else TextMuted.copy(alpha = 0.5f),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp,
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
                                    Toast.makeText(context, context.getString(R.string.msg_start_proxy_first), Toast.LENGTH_SHORT).show()
                                } else {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    val tgUrl = if (app.config.isSocks5Mode) server.getTelegramSocks5Url() else server.getTelegramProxyUrl()
                                    applyToTelegramPackages(context, tgUrl)
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .springPress(),
                            shape = RoundedCornerShape(12.dp),
                            color = if (currentState == ProxyUiState.CONNECTED) protoColors.primary.copy(alpha = 0.12f) else Color.Transparent,
                            border = BorderStroke(
                                1.dp,
                                if (currentState == ProxyUiState.CONNECTED) protoColors.primary.copy(alpha = 0.70f) else AmoledBorder
                            )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_send),
                                    contentDescription = stringResource(R.string.action_to_telegram),
                                    tint = if (currentState == ProxyUiState.CONNECTED) protoColors.primary else TextMuted.copy(alpha = 0.5f),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    stringResource(R.string.action_to_telegram),
                                    color = if (currentState == ProxyUiState.CONNECTED) TextWhite else TextMuted.copy(alpha = 0.5f),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
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
                            context.findActivity<MainActivity>()?.testBannerState?.value = null
                            showDonationBanner = false
                        },
                        onPostponeClicked = {
                            com.mirrly.tgproxy.service.DonationManager.postpone3Days(context)
                            context.findActivity<MainActivity>()?.testBannerState?.value = null
                            showDonationBanner = false
                        },
                        onDismissForeverClicked = {
                            com.mirrly.tgproxy.service.DonationManager.setDismissedForever(context, true)
                            context.findActivity<MainActivity>()?.testBannerState?.value = null
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
                            context.findActivity<MainActivity>()?.testBannerState?.value = null
                            showValueBanner = false
                        },
                        onDismiss = {
                            com.mirrly.tgproxy.service.ValueTriggerManager.markValuePromptShown(context)
                            context.findActivity<MainActivity>()?.testBannerState?.value = null
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
                            AppLogger.e("HomeScreen", "Failed to start service after SOCKS5 auth update: ${e.message}")
                        }
                    }
                )
            }

            if (showUplinkStateDialog) {
                UplinkStateDialog(
                    uplinkMode = uplinkMode,
                    isSocks5 = isSocks5,
                    activeWorker = activeWorker,
                    warpProfile = warpProfile,
                    vlessUuid = vlessUuid,
                    vlessPath = vlessPath,
                    effectiveRoute = telemetry.effectiveRoute,
                    operator = telemetry.operator,
                    isTrustBoundaryMaintained = telemetry.isTrustBoundaryMaintained,
                    isPrivateNode = telemetry.isPrivateNode,
                    isProxyRunning = currentState == ProxyUiState.CONNECTED,
                    onDismiss = { showUplinkStateDialog = false },
                    onOpenSettings = {
                        showUplinkStateDialog = false
                        onOpenSettings()
                    },
                    onOpenWorkerManager = {
                        showUplinkStateDialog = false
                        onOpenWorkerManager()
                    }
                )
            }

            if (showVpnInDevDialog) {
                VpnInDevDialog(
                    vpnColors = systemVpnColors,
                    onDismiss = { showVpnInDevDialog = false }
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
    customColors: ProtocolColors? = null,
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

    val animatedProtoColors = rememberAnimatedProtocolColors(isSocks5 = isSocks5)
    val protoColors = customColors ?: animatedProtoColors

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

        // Smooth wave amplitude for organic curved path ("note note note")
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

@Composable
fun AnimatedWarpGlider(
    state: ProxyUiState,
    isSocks5: Boolean = false,
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

    val app = MirrlyApplication.instance
    val isAnimationsDisabled by app.prefsManager.animationsDisabledFlow.collectAsState()

    // High performance frame clock
    val timeState = produceState(initialValue = 0L, isAppResumed, isAnimationsDisabled) {
        if (!isAppResumed || isAnimationsDisabled) return@produceState
        val startNano = System.nanoTime() - value
        while (isAppResumed && !isAnimationsDisabled) {
            withFrameNanos { frameTimeNanos ->
                value = frameTimeNanos - startNano
            }
        }
    }

    val protoColors = rememberAnimatedProtocolColors(isSocks5 = isSocks5)

    // Color transition based on proxy state (active glowing standby even when disconnected)
    val targetJetColor = when (state) {
        ProxyUiState.CONNECTING -> protoColors.light
        ProxyUiState.CONNECTED -> protoColors.primary
        ProxyUiState.DISCONNECTING -> protoColors.primary.copy(alpha = 0.65f)
        ProxyUiState.DISCONNECTED -> protoColors.primary.copy(alpha = 0.85f)
    }
    val animatedJetColor by animateColorAsState(
        targetValue = targetJetColor,
        animationSpec = tween(550, easing = FastOutSlowInEasing),
        label = "jetColor"
    )

    // Engine thrust & particle intensity (idle ion glow in standby)
    val targetThrust = when (state) {
        ProxyUiState.CONNECTING -> 0.95f
        ProxyUiState.CONNECTED -> 1.0f
        ProxyUiState.DISCONNECTING -> 0.35f
        ProxyUiState.DISCONNECTED -> 0.45f
    }
    val animatedThrust by animateFloatAsState(
        targetValue = targetThrust,
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "jetThrust"
    )

    // Vertical altitude lift when active
    val targetLiftDp = when (state) {
        ProxyUiState.CONNECTING -> (-4).dp
        ProxyUiState.CONNECTED -> (-2.5).dp
        ProxyUiState.DISCONNECTING -> (-1).dp
        ProxyUiState.DISCONNECTED -> (-1.5).dp
    }
    val animatedLiftDp by animateDpAsState(
        targetValue = targetLiftDp,
        animationSpec = spring(
            dampingRatio = 0.82f,
            stiffness = Spring.StiffnessLow
        ),
        label = "jetLift"
    )

    val density = LocalDensity.current
    val strokeWidthPx = remember(density) { with(density) { 2.dp.toPx() } }
    val spineStrokePx = remember(density) { with(density) { 2.2.dp.toPx() } }
    val dotRadiusPx = remember(density) { with(density) { 2.2.dp.toPx() } }
    val liftPx = with(density) { animatedLiftDp.toPx() }

    // Reusable Path instances to prevent frame GC allocations
    val leftWingPath = remember { Path() }
    val rightWingPath = remember { Path() }
    val innerLeftWingPath = remember { Path() }
    val innerRightWingPath = remember { Path() }
    val cockpitPath = remember { Path() }
    val keelPath = remember { Path() }
    val flameLeftPath = remember { Path() }
    val flameRightPath = remember { Path() }

    Canvas(modifier = modifier) {
        val t = if (!isAnimationsDisabled) timeState.value / 1_000_000_000f else 0f
        val diameter = size.minDimension
        val cx = size.width / 2f
        val cy = size.height / 2f + liftPx

        // Flight dynamics: gentle levitation & aerodynamic pitch
        val isGliderActive = state == ProxyUiState.CONNECTED || state == ProxyUiState.CONNECTING
        val floatAmp = if (isGliderActive) diameter * 0.024f else diameter * 0.014f
        val floatFrequency = if (isGliderActive) 2.2f else 1.4f
        val driftY = sin(t * floatFrequency) * floatAmp

        // High frequency vibration during connecting (micro-thrust rumble)
        val rumbleY = if (state == ProxyUiState.CONNECTING) sin(t * 36f) * (diameter * 0.008f) else 0f
        val effectiveCy = cy + driftY + rumbleY

        // Aerodynamic banking roll
        val rollAngle = if (state == ProxyUiState.CONNECTED) {
            sin(t * 1.6f) * 2.4f
        } else if (state == ProxyUiState.CONNECTING) {
            sin(t * 8f) * 1.4f
        } else {
            sin(t * 1.1f) * 1.2f
        }

        // Interceptor scale factors
        val planeH = diameter * 0.54f
        val planeW = diameter * 0.50f

        // Symmetrical supersonic stealth delta key vertices
        val nose = Offset(cx, effectiveCy - planeH * 0.48f)
        val probeBase = Offset(cx, effectiveCy - planeH * 0.38f)
        val chineLeft = Offset(cx - planeW * 0.16f, effectiveCy - planeH * 0.18f)
        val chineRight = Offset(cx + planeW * 0.16f, effectiveCy - planeH * 0.18f)
        val leftTip = Offset(cx - planeW * 0.50f, effectiveCy + planeH * 0.22f)
        val rightTip = Offset(cx + planeW * 0.50f, effectiveCy + planeH * 0.22f)
        val leftWinglet = Offset(cx - planeW * 0.51f, effectiveCy + planeH * 0.07f)
        val rightWinglet = Offset(cx + planeW * 0.51f, effectiveCy + planeH * 0.07f)
        val leftNotch = Offset(cx - planeW * 0.22f, effectiveCy + planeH * 0.16f)
        val rightNotch = Offset(cx + planeW * 0.22f, effectiveCy + planeH * 0.16f)
        val leftEngine = Offset(cx - planeW * 0.11f, effectiveCy + planeH * 0.26f)
        val rightEngine = Offset(cx + planeW * 0.11f, effectiveCy + planeH * 0.26f)
        val aftCenter = Offset(cx, effectiveCy + planeH * 0.20f)
        val keelTip = Offset(cx, effectiveCy + planeH * 0.36f)

        // Diamond cockpit canopy vertices
        val cockpitApex = Offset(cx, effectiveCy - planeH * 0.26f)
        val cockpitLeft = Offset(cx - planeW * 0.08f, effectiveCy - planeH * 0.05f)
        val cockpitRight = Offset(cx + planeW * 0.08f, effectiveCy - planeH * 0.05f)
        val cockpitBase = Offset(cx, effectiveCy + planeH * 0.10f)
        val cockpitCore = Offset(cx, effectiveCy - planeH * 0.07f)

        rotate(degrees = rollAngle, pivot = Offset(cx, effectiveCy)) {
            // ── 1. SUPERSONIC WARP SLIPSTREAM & SHOCKWAVE RINGS ──
            if (animatedThrust > 0.02f) {
                val ringCount = 3
                for (i in 0 until ringCount) {
                    val ringPhase = (t * 0.85f + i * (1f / ringCount)) % 1.0f
                    val ringCenterY = aftCenter.y + ringPhase * (diameter * 0.26f)
                    val ringHalfW = (planeW * 0.25f) + ringPhase * (planeW * 0.38f)
                    val ringHalfH = (diameter * 0.045f) * (1f + ringPhase * 0.4f)
                    val ringAlpha = (1f - ringPhase) * animatedThrust * 0.50f

                    if (ringAlpha > 0.01f) {
                        drawArc(
                            color = animatedJetColor.copy(alpha = ringAlpha),
                            startAngle = 15f,
                            sweepAngle = 150f,
                            useCenter = false,
                            topLeft = Offset(cx - ringHalfW, ringCenterY - ringHalfH),
                            size = Size(ringHalfW * 2, ringHalfH * 2),
                            style = Stroke(width = strokeWidthPx * 0.8f, cap = StrokeCap.Round)
                        )
                    }
                }
            }

            // ── 2. TWIN PLASMA AFTERBURNER THRUSTERS ──
            if (animatedThrust > 0.02f) {
                val flameFlickerL = 0.88f + 0.24f * sin(t * 24f)
                val flameFlickerR = 0.88f + 0.24f * sin(t * 24f + 1.4f)
                val flameLenL = planeH * 0.24f * flameFlickerL * animatedThrust
                val flameLenR = planeH * 0.24f * flameFlickerR * animatedThrust
                val flameW = planeW * 0.065f * (0.6f + 0.4f * animatedThrust)

                // Left engine plume
                flameLeftPath.reset()
                flameLeftPath.moveTo(leftEngine.x - flameW, leftEngine.y)
                flameLeftPath.lineTo(leftEngine.x, leftEngine.y + flameLenL)
                flameLeftPath.lineTo(leftEngine.x + flameW, leftEngine.y)
                flameLeftPath.close()

                drawPath(
                    path = flameLeftPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = animatedThrust * 0.95f),
                            protoColors.light.copy(alpha = animatedThrust * 0.80f),
                            animatedJetColor.copy(alpha = animatedThrust * 0.45f),
                            Color.Transparent
                        ),
                        startY = leftEngine.y,
                        endY = leftEngine.y + flameLenL
                    )
                )

                // Right engine plume
                flameRightPath.reset()
                flameRightPath.moveTo(rightEngine.x - flameW, rightEngine.y)
                flameRightPath.lineTo(rightEngine.x, rightEngine.y + flameLenR)
                flameRightPath.lineTo(rightEngine.x + flameW, rightEngine.y)
                flameRightPath.close()

                drawPath(
                    path = flameRightPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = animatedThrust * 0.95f),
                            protoColors.light.copy(alpha = animatedThrust * 0.80f),
                            animatedJetColor.copy(alpha = animatedThrust * 0.45f),
                            Color.Transparent
                        ),
                        startY = rightEngine.y,
                        endY = rightEngine.y + flameLenR
                    )
                )

                // Twin engine nozzle glow rings
                drawCircle(
                    color = animatedJetColor.copy(alpha = animatedThrust * 0.9f),
                    radius = strokeWidthPx * 1.4f,
                    center = leftEngine
                )
                drawCircle(
                    color = Color.White.copy(alpha = animatedThrust * 0.9f),
                    radius = strokeWidthPx * 0.6f,
                    center = leftEngine
                )
                drawCircle(
                    color = animatedJetColor.copy(alpha = animatedThrust * 0.9f),
                    radius = strokeWidthPx * 1.4f,
                    center = rightEngine
                )
                drawCircle(
                    color = Color.White.copy(alpha = animatedThrust * 0.9f),
                    radius = strokeWidthPx * 0.6f,
                    center = rightEngine
                )
            }

            // ── 3. VENTRAL KEEL / STABILIZER FLAP ──
            keelPath.reset()
            keelPath.moveTo(aftCenter.x, aftCenter.y)
            keelPath.lineTo(leftEngine.x, leftEngine.y)
            keelPath.lineTo(keelTip.x, keelTip.y)
            keelPath.lineTo(rightEngine.x, rightEngine.y)
            keelPath.close()

            drawPath(
                path = keelPath,
                color = animatedJetColor.copy(alpha = if (isGliderActive) 0.15f else 0.08f)
            )
            drawPath(
                path = keelPath,
                color = animatedJetColor.copy(alpha = if (isGliderActive) 0.50f else 0.30f),
                style = Stroke(width = strokeWidthPx * 0.75f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )

            // ── 4. OUTER SWEPT DELTA WINGS ──
            // Left Wing (Ambient Specular Facet)
            leftWingPath.reset()
            leftWingPath.moveTo(probeBase.x, probeBase.y)
            leftWingPath.lineTo(chineLeft.x, chineLeft.y)
            leftWingPath.lineTo(leftTip.x, leftTip.y)
            leftWingPath.lineTo(leftNotch.x, leftNotch.y)
            leftWingPath.lineTo(leftEngine.x, leftEngine.y)
            leftWingPath.lineTo(aftCenter.x, aftCenter.y)
            leftWingPath.close()

            drawPath(
                path = leftWingPath,
                brush = Brush.linearGradient(
                    colors = listOf(
                        animatedJetColor.copy(alpha = if (isGliderActive) 0.26f else 0.14f),
                        animatedJetColor.copy(alpha = if (isGliderActive) 0.10f else 0.04f)
                    ),
                    start = chineLeft,
                    end = leftTip
                )
            )
            drawPath(
                path = leftWingPath,
                color = animatedJetColor.copy(alpha = if (isGliderActive) 0.95f else 0.70f),
                style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
            // Left winglet
            drawLine(
                color = animatedJetColor.copy(alpha = if (isGliderActive) 0.95f else 0.70f),
                start = leftTip,
                end = leftWinglet,
                strokeWidth = strokeWidthPx * 1.1f,
                cap = StrokeCap.Round
            )

            // Right Wing (High-Gloss Specular Facet)
            rightWingPath.reset()
            rightWingPath.moveTo(probeBase.x, probeBase.y)
            rightWingPath.lineTo(chineRight.x, chineRight.y)
            rightWingPath.lineTo(rightTip.x, rightTip.y)
            rightWingPath.lineTo(rightNotch.x, rightNotch.y)
            rightWingPath.lineTo(rightEngine.x, rightEngine.y)
            rightWingPath.lineTo(aftCenter.x, aftCenter.y)
            rightWingPath.close()

            drawPath(
                path = rightWingPath,
                brush = Brush.linearGradient(
                    colors = listOf(
                        protoColors.light.copy(alpha = if (isGliderActive) 0.38f else 0.20f),
                        animatedJetColor.copy(alpha = if (isGliderActive) 0.14f else 0.06f)
                    ),
                    start = chineRight,
                    end = rightTip
                )
            )
            drawPath(
                path = rightWingPath,
                color = animatedJetColor.copy(alpha = if (isGliderActive) 0.95f else 0.70f),
                style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
            // Right winglet
            drawLine(
                color = animatedJetColor.copy(alpha = if (isGliderActive) 0.95f else 0.70f),
                start = rightTip,
                end = rightWinglet,
                strokeWidth = strokeWidthPx * 1.1f,
                cap = StrokeCap.Round
            )

            // ── 5. INNER COMPOSITE ARMOR PANELS (3D Faceted Detail) ──
            innerLeftWingPath.reset()
            innerLeftWingPath.moveTo(chineLeft.x, chineLeft.y)
            innerLeftWingPath.lineTo(cockpitLeft.x, cockpitLeft.y)
            innerLeftWingPath.lineTo(leftNotch.x, leftNotch.y)
            innerLeftWingPath.close()

            drawPath(
                path = innerLeftWingPath,
                brush = Brush.linearGradient(
                    colors = listOf(
                        animatedJetColor.copy(alpha = if (isGliderActive) 0.18f else 0.10f),
                        Color.Transparent
                    ),
                    start = cockpitLeft,
                    end = leftNotch
                )
            )
            drawPath(
                path = innerLeftWingPath,
                color = animatedJetColor.copy(alpha = if (isGliderActive) 0.40f else 0.25f),
                style = Stroke(width = strokeWidthPx * 0.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )

            innerRightWingPath.reset()
            innerRightWingPath.moveTo(chineRight.x, chineRight.y)
            innerRightWingPath.lineTo(cockpitRight.x, cockpitRight.y)
            innerRightWingPath.lineTo(rightNotch.x, rightNotch.y)
            innerRightWingPath.close()

            drawPath(
                path = innerRightWingPath,
                brush = Brush.linearGradient(
                    colors = listOf(
                        protoColors.light.copy(alpha = if (isGliderActive) 0.24f else 0.14f),
                        Color.Transparent
                    ),
                    start = cockpitRight,
                    end = rightNotch
                )
            )
            drawPath(
                path = innerRightWingPath,
                color = animatedJetColor.copy(alpha = if (isGliderActive) 0.40f else 0.25f),
                style = Stroke(width = strokeWidthPx * 0.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )

            // ── 6. DIAMOND CRYSTALLINE COCKPIT CANOPY ──
            cockpitPath.reset()
            cockpitPath.moveTo(cockpitApex.x, cockpitApex.y)
            cockpitPath.lineTo(cockpitLeft.x, cockpitLeft.y)
            cockpitPath.lineTo(cockpitBase.x, cockpitBase.y)
            cockpitPath.lineTo(cockpitRight.x, cockpitRight.y)
            cockpitPath.close()

            drawPath(
                path = cockpitPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        protoColors.light.copy(alpha = if (isGliderActive) 0.45f else 0.28f),
                        animatedJetColor.copy(alpha = if (isGliderActive) 0.20f else 0.10f)
                    ),
                    startY = cockpitApex.y,
                    endY = cockpitBase.y
                )
            )
            drawPath(
                path = cockpitPath,
                color = protoColors.light.copy(alpha = if (isGliderActive) 0.95f else 0.75f),
                style = Stroke(width = strokeWidthPx * 0.85f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )

            // Internal glowing spark core
            drawCircle(
                color = protoColors.light.copy(alpha = 0.5f),
                radius = dotRadiusPx * 1.8f,
                center = cockpitCore
            )
            drawCircle(
                color = Color.White,
                radius = dotRadiusPx * 0.7f,
                center = cockpitCore
            )

            // ── 7. FORWARD SENSOR PROBE & NEEDLE NOSE ──
            drawLine(
                color = protoColors.light.copy(alpha = if (isGliderActive) 1.0f else 0.80f),
                start = probeBase,
                end = nose,
                strokeWidth = strokeWidthPx * 1.1f,
                cap = StrokeCap.Round
            )

            // ── 8. WINGTIP STROBE LED BEACONS ──
            val strobePhase = (t * 7f) % (2f * Math.PI.toFloat())
            val strobeFlash = (sin(strobePhase).coerceAtLeast(0f)).let { it * it * it }
            val strobeAlpha = 0.35f + 0.65f * strobeFlash

            // Left winglet beacon
            drawCircle(
                color = protoColors.light.copy(alpha = strobeAlpha * 0.5f),
                radius = dotRadiusPx * 1.8f,
                center = leftWinglet
            )
            drawCircle(
                color = Color.White.copy(alpha = strobeAlpha),
                radius = dotRadiusPx * 0.8f,
                center = leftWinglet
            )

            // Right winglet beacon
            drawCircle(
                color = protoColors.light.copy(alpha = strobeAlpha * 0.5f),
                radius = dotRadiusPx * 1.8f,
                center = rightWinglet
            )
            drawCircle(
                color = Color.White.copy(alpha = strobeAlpha),
                radius = dotRadiusPx * 0.8f,
                center = rightWinglet
            )

            // ── 9. SUPERCONDUCTING SPINE & RUNNING DATA PHOTON PACKET ──
            drawLine(
                color = animatedJetColor.copy(alpha = if (isGliderActive) 0.95f else 0.65f),
                start = probeBase,
                end = cockpitApex,
                strokeWidth = spineStrokePx,
                cap = StrokeCap.Round
            )
            drawLine(
                color = animatedJetColor.copy(alpha = if (isGliderActive) 0.95f else 0.65f),
                start = cockpitBase,
                end = aftCenter,
                strokeWidth = spineStrokePx,
                cap = StrokeCap.Round
            )
            drawLine(
                color = animatedJetColor.copy(alpha = if (isGliderActive) 0.60f else 0.35f),
                start = aftCenter,
                end = keelTip,
                strokeWidth = strokeWidthPx * 0.8f,
                cap = StrokeCap.Round
            )

            // Running photon packet
            val packetPhase = (t * 2.4f) % 1.0f
            val packetY = probeBase.y + (aftCenter.y - probeBase.y) * packetPhase
            drawCircle(
                color = animatedJetColor.copy(alpha = 0.55f),
                radius = dotRadiusPx * 2.0f,
                center = Offset(cx, packetY)
            )
            drawCircle(
                color = Color.White,
                radius = dotRadiusPx * 0.9f,
                center = Offset(cx, packetY)
            )

            // ── 10. APEX DIFFRACTION STAR FLARE (QUANTUM SENSOR) ──
            val starRot = t * 15f
            val starPulse = 0.85f + 0.25f * sin(t * 3.5f)
            val starHalfLen = with(density) { 5.dp.toPx() } * starPulse

            rotate(degrees = starRot, pivot = nose) {
                drawLine(
                    color = protoColors.light.copy(alpha = 0.80f),
                    start = Offset(nose.x - starHalfLen, nose.y),
                    end = Offset(nose.x + starHalfLen, nose.y),
                    strokeWidth = with(density) { 0.9.dp.toPx() },
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = protoColors.light.copy(alpha = 0.80f),
                    start = Offset(nose.x, nose.y - starHalfLen),
                    end = Offset(nose.x, nose.y + starHalfLen),
                    strokeWidth = with(density) { 0.9.dp.toPx() },
                    cap = StrokeCap.Round
                )
                drawCircle(
                    color = Color.White,
                    radius = with(density) { 1.3.dp.toPx() },
                    center = nose
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
            Toast.makeText(context, context.getString(R.string.err_tg_client_not_found), Toast.LENGTH_SHORT).show()
        }
    } else if (targetedIntents.size == 1) {
        val intent = targetedIntents.first().apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(context, context.getString(R.string.err_tg_open_client), Toast.LENGTH_SHORT).show()
        }
    } else {
        val baseIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooserIntent = Intent.createChooser(baseIntent, context.getString(R.string.prompt_select_tg_client)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(chooserIntent)
        } catch (_: Exception) {
            Toast.makeText(context, context.getString(R.string.err_select_tg_client), Toast.LENGTH_SHORT).show()
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

    val isSocks5 = app.config.isSocks5Mode
    val transportPoolStatus by app.proxyServer.transportPoolStatus.collectAsState()
    val requestedStandby = transportPoolStatus.mtprotoStandbyPerActiveSlotRequested
    val effectiveStandby = transportPoolStatus.mtprotoStandbyPerActiveSlotEffective

    // Target amplitude smoothly interpolated with spring physics: drops flat to 0.0 when OFF
    val targetAmplitude = if (isProxyActive) {
        if (isSocks5) {
            (activeConns.toFloat() / 16f).coerceIn(0.18f, 0.95f)
        } else if (maxPoolSize > 0) {
            (activeConns.toFloat() / maxPoolSize.toFloat()).coerceIn(0.18f, 0.95f)
        } else {
            0.18f
        }
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

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val flowStoppedLabel = stringResource(R.string.status_flow_stopped)
            val flowActiveConnsTemplate = stringResource(R.string.status_flow_active_conns)
            val flowActiveStandbyTemplate = stringResource(R.string.status_flow_active_standby)
            Text(
                text = com.mirrly.tgproxy.core.ProxyDisplayLabels.transportFlowTitle(
                    isSocks5 = isSocks5,
                    socks5Title = stringResource(R.string.status_flow_socks5_title),
                    wsPoolTitle = stringResource(R.string.status_flow_wspool_title)
                ),
                color = TextMuted.copy(alpha = 0.55f),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.1.sp
            )
            Text(
                text = com.mirrly.tgproxy.core.ProxyDisplayLabels.transportFlowSubtitle(
                    isSocks5 = isSocks5,
                    isProxyActive = isProxyActive,
                    activeConns = activeConns,
                    requestedStandby = requestedStandby,
                    effectiveStandby = effectiveStandby,
                    stoppedLabel = flowStoppedLabel,
                    activeConnsFormat = { active -> flowActiveConnsTemplate.format(active) },
                    activeStandbyFormat = { active, eff, req -> flowActiveStandbyTemplate.format(active, eff, req) }
                ),
                color = if (isProxyActive) accentColor.copy(alpha = 0.85f) else TextMuted.copy(alpha = 0.45f),
                fontSize = 9.5.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

/**
 * Modern tactile Protocol Switcher Header.
 * Supports smooth horizontal drag gestures with physics resistance,
 * instant tap switching, sliding pill indicator, anti-spam locking,
 * static pill position, app title "note", and elegant active worker badge in SOCKS5 mode.
 */
enum class HomeScreenTab {
    MTPROTO,
    SOCKS5,
    VPN
}

/**
 * note note note note note and VPN note:
 * instant tap switching, sliding pill indicator, anti-spam locking,
 * static pill position, app title "note", and note active note.
 */
@Composable
fun ProtocolSwitcherHeader(
    currentTab: HomeScreenTab,
    onSelectTab: (HomeScreenTab) -> Unit,
    activeWorker: com.mirrly.tgproxy.core.WorkerProfile,
    protoColors: ProtocolColors,
    vpnColors: ProtocolColors,
    isSwitching: Boolean,
    onSwitchProtocol: (com.mirrly.tgproxy.core.ProxyMode) -> Unit,
    onOpenWorkerManager: () -> Unit = {},
    uplinkMode: com.mirrly.tgproxy.core.UplinkMode = com.mirrly.tgproxy.core.UplinkMode.WORKER,
    vpnUplinkMode: com.mirrly.tgproxy.core.UplinkMode = com.mirrly.tgproxy.core.UplinkMode.WARP_CASCADE,
    warpProfile: com.mirrly.tgproxy.core.WarpProfile? = null,
    vlessUuid: String = "",
    onOpenUplinkState: () -> Unit = {},
    onOpenVpnInfo: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    val dragOffsetX = remember { Animatable(0f) }
    val badgeInteractionSource = remember { MutableInteractionSource() }
    val capsuleWidth = 246.dp
    val capsuleHeight = 31.dp
    val tabWidth = capsuleWidth / 3

    val targetPillColor = when (currentTab) {
        HomeScreenTab.MTPROTO -> MtprotoAccent
        HomeScreenTab.SOCKS5 -> Socks5Accent
        HomeScreenTab.VPN -> vpnColors.primary
    }

    val switcherAccentColor by animateColorAsState(
        targetValue = targetPillColor,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "switcherAccentColor"
    )

    val targetOffset = when (currentTab) {
        HomeScreenTab.MTPROTO -> 0.dp
        HomeScreenTab.SOCKS5 -> tabWidth
        HomeScreenTab.VPN -> tabWidth * 2
    }

    val animatedPillOffset by animateDpAsState(
        targetValue = targetOffset,
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
        // Switcher Pill Container
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
                                val thresholdPx = with(density) { 18.dp.toPx() }
                                scope.launch {
                                    if (currentDrag > thresholdPx) {
                                        when (currentTab) {
                                            HomeScreenTab.MTPROTO -> {
                                                HapticHelper.performSwipeGlide(context)
                                                onSelectTab(HomeScreenTab.SOCKS5)
                                                onSwitchProtocol(com.mirrly.tgproxy.core.ProxyMode.SOCKS5)
                                            }
                                            HomeScreenTab.SOCKS5 -> {
                                                HapticHelper.performSwipeGlide(context)
                                                onSelectTab(HomeScreenTab.VPN)
                                            }
                                            HomeScreenTab.VPN -> {}
                                        }
                                    } else if (currentDrag < -thresholdPx) {
                                        when (currentTab) {
                                            HomeScreenTab.VPN -> {
                                                HapticHelper.performSwipeGlide(context)
                                                onSelectTab(HomeScreenTab.SOCKS5)
                                                onSwitchProtocol(com.mirrly.tgproxy.core.ProxyMode.SOCKS5)
                                            }
                                            HomeScreenTab.SOCKS5 -> {
                                                HapticHelper.performSwipeGlide(context)
                                                onSelectTab(HomeScreenTab.MTPROTO)
                                                onSwitchProtocol(com.mirrly.tgproxy.core.ProxyMode.MTPROTO)
                                            }
                                            HomeScreenTab.MTPROTO -> {}
                                        }
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
                                val maxDragPx = with(density) { tabWidth.toPx() }
                                scope.launch { dragOffsetX.snapTo((current + damped).coerceIn(-maxDragPx, maxDragPx)) }
                            }
                        )
                    }
            ) {
                // Sliding Glowing Indicator Pill
                Box(
                    modifier = Modifier
                        .offset(x = totalOffsetX.coerceIn(0.dp, tabWidth * 2))
                        .width(tabWidth)
                        .fillMaxHeight()
                        .padding(2.5.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(switcherAccentColor.copy(alpha = 0.16f))
                        .border(
                            1.dp,
                            switcherAccentColor.copy(alpha = 0.55f),
                            RoundedCornerShape(13.dp)
                        )
                )

                // Segment Labels Row (3 tabs: MTProto | SOCKS5 | VPN)
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
                                if (currentTab != HomeScreenTab.MTPROTO) {
                                    HapticHelper.performTapClick(context)
                                    onSelectTab(HomeScreenTab.MTPROTO)
                                    onSwitchProtocol(com.mirrly.tgproxy.core.ProxyMode.MTPROTO)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "MTProto",
                            color = if (currentTab == HomeScreenTab.MTPROTO) switcherAccentColor else TextMuted,
                            fontSize = 11.5.sp,
                            fontWeight = if (currentTab == HomeScreenTab.MTPROTO) FontWeight.Bold else FontWeight.Medium,
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
                                if (currentTab != HomeScreenTab.SOCKS5) {
                                    HapticHelper.performTapClick(context)
                                    onSelectTab(HomeScreenTab.SOCKS5)
                                    onSwitchProtocol(com.mirrly.tgproxy.core.ProxyMode.SOCKS5)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "SOCKS5",
                            color = if (currentTab == HomeScreenTab.SOCKS5) switcherAccentColor else TextMuted,
                            fontSize = 11.5.sp,
                            fontWeight = if (currentTab == HomeScreenTab.SOCKS5) FontWeight.Bold else FontWeight.Medium,
                            letterSpacing = 0.3.sp
                        )
                    }

                    // VPN Tab
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                if (currentTab != HomeScreenTab.VPN) {
                                    HapticHelper.performTapClick(context)
                                    onSelectTab(HomeScreenTab.VPN)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "VPN",
                            color = if (currentTab == HomeScreenTab.VPN) switcherAccentColor else TextMuted,
                            fontSize = 11.5.sp,
                            fontWeight = if (currentTab == HomeScreenTab.VPN) FontWeight.Bold else FontWeight.Medium,
                            letterSpacing = 0.3.sp
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Active Uplink Indicator Badge
        val isWarpActive = warpProfile != null && warpProfile.isWarpEnabled

        val (badgeText, badgeBaseColor, badgeClick) = when (currentTab) {
            HomeScreenTab.MTPROTO -> Triple(
                "Anycast Flowseal",
                MtprotoAccent,
                onOpenUplinkState
            )
            HomeScreenTab.SOCKS5 -> {
                Triple(
                    "Worker WSS • ${activeWorker.name}",
                    Socks5Accent,
                    onOpenWorkerManager
                )
            }
            HomeScreenTab.VPN -> {
                val vpnBadge = when (vpnUplinkMode) {
                    com.mirrly.tgproxy.core.UplinkMode.WARP_CASCADE -> "WARP Cascade • Dual Anycast"
                    com.mirrly.tgproxy.core.UplinkMode.VLESS -> "VLESS over WSS • Anycast CDN"
                    com.mirrly.tgproxy.core.UplinkMode.MASQUE -> "WARP MASQUE • HTTP/3 QUIC"
                    com.mirrly.tgproxy.core.UplinkMode.AWG -> "WARP AWG • AmneziaWG"
                    com.mirrly.tgproxy.core.UplinkMode.WORKER -> "Worker WSS • Anycast"
                    else -> vpnUplinkMode.displayName
                }
                Triple(
                    vpnBadge,
                    vpnColors.primary,
                    onOpenVpnInfo
                )
            }
        }

        val animatedBadgeColor by animateColorAsState(
            targetValue = badgeBaseColor,
            animationSpec = tween(300, easing = FastOutSlowInEasing),
            label = "badgeColor"
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Transparent)
                .border(0.8.dp, animatedBadgeColor.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                .clickable(
                    interactionSource = badgeInteractionSource,
                    indication = null
                ) {
                    HapticHelper.performSoftTick(context)
                    badgeClick()
                }
                .padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(4.5.dp)
                    .clip(CircleShape)
                    .background(animatedBadgeColor)
            )
            Spacer(modifier = Modifier.width(4.5.dp))
            Text(
                text = badgeText,
                color = animatedBadgeColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            if (currentTab == HomeScreenTab.SOCKS5 || currentTab == HomeScreenTab.VPN) {
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    painter = painterResource(id = R.drawable.ic_arrow_down),
                    contentDescription = null,
                    tint = animatedBadgeColor.copy(alpha = 0.70f),
                    modifier = Modifier.size(9.dp)
                )
            }
        }

        // Interactive visual affordance: subtle bouncing pull-down hint for Worker Manager (SOCKS5 only)
        if (currentTab == HomeScreenTab.SOCKS5) {
            val infiniteSwipeTransition = rememberInfiniteTransition(label = "swipeHintBounce")
            val bounceOffsetY by infiniteSwipeTransition.animateFloat(
                initialValue = 0f,
                targetValue = 3.5f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1200, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "bounceOffsetY"
            )
            val hintAlpha by infiniteSwipeTransition.animateFloat(
                initialValue = 0.35f,
                targetValue = 0.75f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1200, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "hintAlpha"
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        HapticHelper.performSoftTick(context)
                        onOpenWorkerManager()
                    }
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_arrow_down),
                    contentDescription = null,
                    tint = TextMuted.copy(alpha = hintAlpha),
                    modifier = Modifier
                        .size(9.5.dp)
                        .offset(y = bounceOffsetY.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = stringResource(R.string.home_swipe_down_workers_hint),
                    color = TextMuted.copy(alpha = hintAlpha),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = 0.1.sp
                )
                Spacer(modifier = Modifier.width(5.dp))
                Icon(
                    painter = painterResource(id = R.drawable.ic_arrow_down),
                    contentDescription = null,
                    tint = TextMuted.copy(alpha = hintAlpha),
                    modifier = Modifier
                        .size(9.5.dp)
                        .offset(y = bounceOffsetY.dp)
                )
            }
        }
    }
}

@Composable
fun TelegramChannelCapsuleButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val capsuleHeight = 24.dp
    val cyanAccent = Color(0xFF26A5E4)

    Box(
        modifier = modifier
            .wrapContentWidth()
            .height(capsuleHeight)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Transparent)
            .border(
                width = 1.dp,
                color = cyanAccent.copy(alpha = 0.55f),
                shape = RoundedCornerShape(12.dp)
            )
            .springPress(onClick = {
                HapticHelper.performTapClick(context)
                onClick()
            })
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_telegram),
                contentDescription = null,
                tint = cyanAccent,
                modifier = Modifier.size(11.5.dp)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = stringResource(R.string.tg_channel_btn_title),
                color = TextWhite,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.2.sp,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                painter = painterResource(id = R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = cyanAccent.copy(alpha = 0.70f),
                modifier = Modifier.size(10.dp)
            )
        }
    }
}


/**
 * note note note note with note note note note (Liquid Wave Orb).
 * note note SQI and note note note with note note.
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
            .background(Color.Transparent)
            .border(1.dp, animatedColor.copy(alpha = 0.45f), CircleShape)
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

@Composable
fun UplinkStateDialog(
    uplinkMode: com.mirrly.tgproxy.core.UplinkMode,
    isSocks5: Boolean,
    activeWorker: com.mirrly.tgproxy.core.WorkerProfile,
    warpProfile: com.mirrly.tgproxy.core.WarpProfile?,
    vlessUuid: String,
    vlessPath: String,
    effectiveRoute: String = "",
    operator: String = "",
    isTrustBoundaryMaintained: Boolean = true,
    isPrivateNode: Boolean = false,
    isProxyRunning: Boolean,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenWorkerManager: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val isWarpActive = warpProfile != null && warpProfile.isWarpEnabled
    var showInfoDialog by remember { mutableStateOf(false) }
    val modeTitle = if (!isSocks5) {
        "MTProto Direct / WSS"
    } else {
        "Cloudflare Worker WSS"
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = Color(0xFF0C0C10),
            border = BorderStroke(1.dp, AmoledBorder),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(vertical = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.uplink_status_title),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite
                            )
                            InfoButton {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                showInfoDialog = true
                            }
                        }
                        Text(
                            text = stringResource(R.string.uplink_status_desc),
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color.Transparent,
                        border = BorderStroke(0.8.dp, ActiveGreenLed.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = modeTitle,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = ActiveGreenLed,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                // Details Card (100% transparent background, borders only)
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, AmoledBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        if (!isSocks5) {
                            UplinkStateRow(stringResource(R.string.uplink_field_protocol), "Telegram MTProto (:1080)")
                            UplinkStateRow(stringResource(R.string.uplink_field_transport), "Anycast CDN Flowseal (kws*.apiws)")
                            UplinkStateRow(stringResource(R.string.uplink_field_encryption), "Fake-TLS 1.3 (dd-secret)")
                            UplinkStateRow(stringResource(R.string.uplink_field_cache_nodes), stringResource(R.string.uplink_field_cache_nodes_val))
                        } else {
                            UplinkStateRow(stringResource(R.string.uplink_field_protocol), "SOCKS5 TCP Relay")
                            UplinkStateRow(stringResource(R.string.uplink_field_transport), "Cloudflare Worker WSS :443")
                            UplinkStateRow(stringResource(R.string.uplink_field_active_worker), activeWorker.name)
                            UplinkStateRow(stringResource(R.string.uplink_field_node_domain), activeWorker.domain)
                            UplinkStateRow(stringResource(R.string.uplink_field_conn_pool), if (activeWorker.isDeveloperWorker) stringResource(R.string.uplink_pool_shared) else stringResource(R.string.uplink_pool_personal))
                        }

                        if (effectiveRoute.isNotBlank()) {
                            UplinkStateRow(stringResource(R.string.uplink_actual_route), effectiveRoute)
                            if (operator.isNotBlank()) {
                                UplinkStateRow(stringResource(R.string.uplink_node_operator), operator)
                            }
                            UplinkStateRow(
                                stringResource(R.string.uplink_trust_boundary),
                                if (isTrustBoundaryMaintained) {
                                    if (isPrivateNode) stringResource(R.string.uplink_trust_private) else stringResource(R.string.uplink_trust_default)
                                } else {
                                    stringResource(R.string.uplink_trust_third_party)
                                },
                                isAlert = !isTrustBoundaryMaintained
                            )
                        }
                        UplinkStateRow(stringResource(R.string.uplink_engine_status), if (isProxyRunning) stringResource(R.string.uplink_engine_running) else stringResource(R.string.uplink_engine_stopped))
                    }
                }

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onOpenSettings()
                        },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = ActiveGreenLed
                        ),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, ActiveGreenLed.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.uplink_settings_action),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onDismiss()
                        },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = TextWhite
                        ),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, AmoledBorder),
                        modifier = Modifier
                            .weight(0.7f)
                            .height(38.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.action_close),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    if (showInfoDialog) {
        SettingsInfoDialog(
            infoKey = "uplink_modes_info",
            onDismiss = { showInfoDialog = false }
        )
    }
}

@Composable
private fun UplinkStateRow(label: String, value: String, isAlert: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 11.5.sp,
            color = TextMuted
        )
        Text(
            text = value,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (isAlert) Color(0xFFFF9E00) else TextWhite,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}



