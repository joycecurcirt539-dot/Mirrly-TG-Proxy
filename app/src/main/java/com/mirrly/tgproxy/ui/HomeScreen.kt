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
import android.os.Build
import android.widget.Toast
import kotlin.math.cos
import kotlin.math.sin
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

enum class ProxyUiState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenUpdate: () -> Unit = {},
    onUiHiddenChange: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val app = MirrlyApplication.instance
    val server = app.proxyServer

    // ── Stealth Mode State ────────────────────────────────────────────────
    var isUiHidden by remember { mutableStateOf(false) }
    var eyeButtonVisible by remember { mutableStateOf(false) }

    // Animate progress: 0f = shown, 1f = hidden (spring physics)
    val uiAnimProgress by animateFloatAsState(
        targetValue = if (isUiHidden) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "uiHideProgress"
    )

    // Callback propagation & notification toast
    LaunchedEffect(isUiHidden) {
        onUiHiddenChange(isUiHidden)
        if (isUiHidden) {
            Toast.makeText(context, "Удерживайте пальцем экран, чтобы всё вернулось", Toast.LENGTH_LONG).show()
        }
    }

    // Auto-hide the floating eye button after 5 seconds
    LaunchedEffect(isUiHidden, eyeButtonVisible) {
        if (isUiHidden && eyeButtonVisible) {
            delay(5000)
            eyeButtonVisible = false
        }
    }

    var isRunning by remember { mutableStateOf(server.isRunning) }
    var pendingState by remember { mutableStateOf<ProxyUiState?>(null) }

    val currentState = pendingState ?: if (isRunning) ProxyUiState.CONNECTED else ProxyUiState.DISCONNECTED

    var dlSpeed by remember { mutableStateOf("0 Б/с") }
    var ulSpeed by remember { mutableStateOf("0 Б/с") }
    var activeConns by remember { mutableIntStateOf(0) }
    var totalRecv by remember { mutableStateOf("0 Б") }
    var totalSent by remember { mutableStateOf("0 Б") }
    var uptimeSeconds by remember { mutableLongStateOf(0L) }
    var pingMs by remember { mutableLongStateOf(-1L) }

    val updateInfo by com.mirrly.tgproxy.service.UpdateManager.updateState.collectAsState()

    // Execute stats calculation on IO thread off the main looper to eliminate main thread frame delay
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            while (isActive) {
                val running = server.isRunning
                val uptime = server.uptimeSeconds
                val currPing = server.currentPingMs
                var dl = "0 Б/с"
                var ul = "0 Б/с"
                var conns = 0
                var recv = "0 Б"
                var sent = "0 Б"

                if (running) {
                    val stats = server.stats
                    dl = "${humanBytes(stats.downloadSpeedBps)}/с"
                    ul = "${humanBytes(stats.uploadSpeedBps)}/с"
                    conns = stats.activeConnections.get()
                    recv = humanBytes(stats.totalBytesReceived.get())
                    sent = humanBytes(stats.totalBytesSent.get())
                }

                withContext(Dispatchers.Main) {
                    isRunning = running
                    if (pendingState == ProxyUiState.CONNECTING && running) {
                        pendingState = null
                    } else if (pendingState == ProxyUiState.DISCONNECTING && !running) {
                        pendingState = null
                    }

                    uptimeSeconds = uptime
                    dlSpeed = dl
                    ulSpeed = ul
                    activeConns = conns
                    totalRecv = recv
                    totalSent = sent
                    pingMs = currPing
                }
                delay(500)
            }
        }
    }

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
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    // Slow & smooth breathing pulse glow for active power icon
    val infiniteTransition = rememberInfiniteTransition(label = "pulseGlow")
    val pulseScaleState = infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.22f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val pulseAlphaState = infiniteTransition.animateFloat(
        initialValue = 0.20f,
        targetValue = 0.70f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val pureBlack = Color(0xFF000000)

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.graphicsLayer {
                    translationY = -120.dp.toPx() * uiAnimProgress
                    alpha = (1f - uiAnimProgress * 2f).coerceIn(0f, 1f)
                },
                title = {
                    Text(
                        text = "Mirrly - TG Proxy",
                        color = TextWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        letterSpacing = 0.8.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onOpenLogs()
                    }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_logs),
                            contentDescription = "Логи",
                            tint = TextWhite,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                },
                actions = {
                    // Update Tab Button — appears only when update is available
                    if (updateInfo?.isUpdateAvailable == true) {
                        IconButton(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onOpenUpdate()
                        }) {
                            Box(contentAlignment = Alignment.TopEnd) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_refresh),
                                    contentDescription = "Обновления",
                                    tint = ActiveGreenLed,
                                    modifier = Modifier.size(22.dp)
                                )
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(ActiveGreenLed)
                                )
                            }
                        }
                    }
                    // Eye toggle button — hides UI into stealth mode
                    IconButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        isUiHidden = true
                    }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_eye),
                            contentDescription = "Скрыть интерфейс",
                            tint = TextWhite,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    IconButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onOpenSettings()
                    }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_settings),
                            contentDescription = "Настройки",
                            tint = TextWhite,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = Color.Transparent
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (isUiHidden) {
                        Modifier.pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    eyeButtonVisible = true
                                },
                                onLongPress = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    isUiHidden = false
                                    eyeButtonVisible = false
                                }
                            )
                        }
                    } else Modifier
                )
        ) {
            var showValueBanner by remember {
                mutableStateOf(com.mirrly.tgproxy.service.ValueTriggerManager.shouldShowValueBanner(context))
            }

            LaunchedEffect(Unit) {
                while (isActive) {
                    if (!showValueBanner && com.mirrly.tgproxy.service.ValueTriggerManager.shouldShowValueBanner(context)) {
                        showValueBanner = true
                    }
                    delay(15000)
                }
            }

            // ─── 1. TOP SECTION (Update banner) ───
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(top = padding.calculateTopPadding() + 10.dp)
                    .padding(horizontal = 20.dp)
                    .graphicsLayer {
                        // slides up
                        translationY = -120.dp.toPx() * uiAnimProgress
                        alpha = (1f - uiAnimProgress * 2f).coerceIn(0f, 1f)
                    }
            ) {
                // Update Available Banner (if newer version available on GitHub)
                updateInfo?.let { info ->
                    if (info.isUpdateAvailable) {
                        val updateYellow = Color(0xFFFFB703)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    Brush.horizontalGradient(
                                        colors = listOf(
                                            Color(0xFF241E08),
                                            Color(0xFF141005)
                                        )
                                    )
                                )
                                .border(1.dp, updateYellow, RoundedCornerShape(16.dp))
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onOpenUpdate()
                                }
                                .padding(14.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(updateYellow.copy(alpha = 0.2f))
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_refresh),
                                            contentDescription = null,
                                            tint = updateYellow,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }

                                    Column {
                                        Text(
                                            text = "Найдено обновление v${info.versionName}!",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = updateYellow
                                        )
                                        Text(
                                            text = "Нажмите для просмотра и установки",
                                            fontSize = 12.sp,
                                            color = TextWhite.copy(alpha = 0.85f)
                                        )
                                    }
                                }

                                Icon(
                                    painter = painterResource(id = R.drawable.ic_chevron_right),
                                    contentDescription = null,
                                    tint = updateYellow,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            // ─── 2. CENTER SECTION (Power button) ───
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

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center)
                    .graphicsLayer {
                        // Only fades out/in
                        alpha = (1f - uiAnimProgress).coerceIn(0f, 1f)
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .graphicsLayer {
                            scaleX = springScale
                            scaleY = springScale
                        }
                        .clickable(
                            interactionSource = powerInteractionSource,
                            indication = null
                        ) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            val serviceIntent = Intent(context, ProxyForegroundService::class.java)
                            if (currentState == ProxyUiState.CONNECTED || currentState == ProxyUiState.CONNECTING) {
                                pendingState = ProxyUiState.DISCONNECTING
                                serviceIntent.action = ProxyForegroundService.ACTION_STOP
                                context.startService(serviceIntent)
                            } else {
                                pendingState = ProxyUiState.CONNECTING
                                serviceIntent.action = ProxyForegroundService.ACTION_START
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    context.startForegroundService(serviceIntent)
                                } else {
                                    context.startService(serviceIntent)
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    RotatingProxyRing(
                        state = currentState,
                        modifier = Modifier.size(240.dp)
                    )

                    val iconTint = when (currentState) {
                        ProxyUiState.CONNECTED -> ActiveGreenLed
                        ProxyUiState.CONNECTING -> ActiveGreenLed
                        ProxyUiState.DISCONNECTING -> Color(0xFFFF9E00)
                        ProxyUiState.DISCONNECTED -> Color(0xFF353C4F)
                    }
                    val animatedIconTint by animateColorAsState(
                        targetValue = iconTint,
                        animationSpec = tween(500),
                        label = "iconTint"
                    )

                    Icon(
                        painter = painterResource(id = R.drawable.ic_power),
                        contentDescription = "Включение прокси",
                        tint = animatedIconTint,
                        modifier = Modifier.size(170.dp)
                    )
                }
            }

            // ─── 3. BOTTOM SECTION (Lower controls) ───
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(bottom = padding.calculateBottomPadding() + 10.dp)
                    .padding(horizontal = 20.dp)
                    .graphicsLayer {
                        translationY = 160.dp.toPx() * uiAnimProgress
                        alpha = (1f - uiAnimProgress * 1.5f).coerceIn(0f, 1f)
                    },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Status / Timer
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(bottom = 6.dp)
                ) {
                    val dotColor = when (currentState) {
                        ProxyUiState.CONNECTED -> ActiveGreenLed
                        ProxyUiState.CONNECTING -> ActiveGreenLed
                        ProxyUiState.DISCONNECTING -> Color(0xFFFF9E00)
                        ProxyUiState.DISCONNECTED -> Color(0xFF353C4F)
                    }
                    val animatedDotColor by animateColorAsState(
                        targetValue = dotColor,
                        animationSpec = tween(400),
                        label = "dotColor"
                    )

                    Surface(
                        shape = CircleShape,
                        color = animatedDotColor,
                        modifier = Modifier.size(6.dp)
                    ) {}

                    val statusText = when (currentState) {
                        ProxyUiState.CONNECTED -> formatUptime(uptimeSeconds)
                        ProxyUiState.CONNECTING -> "ПОДКЛЮЧЕНИЕ..."
                        ProxyUiState.DISCONNECTING -> "ОТКЛЮЧЕНИЕ..."
                        ProxyUiState.DISCONNECTED -> "00:00:00"
                    }
                    RollingNumberText(
                        text = statusText,
                        color = if (currentState == ProxyUiState.CONNECTED) ActiveGreenLed else if (currentState == ProxyUiState.DISCONNECTING) Color(0xFFFF9E00) else TextMuted,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.1.sp
                    )
                }

                // Compact Network / IP / Socket info line
                val ipPortText = when (currentState) {
                    ProxyUiState.CONNECTED -> "127.0.0.1:${app.config.bindPort} • Сокеты: $activeConns/${app.config.poolSize}"
                    ProxyUiState.CONNECTING -> "Запуск прокси-сервера..."
                    ProxyUiState.DISCONNECTING -> "Остановка прокси-сервера..."
                    ProxyUiState.DISCONNECTED -> "127.0.0.1:${app.config.bindPort} • Не подключено"
                }
                Text(
                    text = ipPortText,
                    color = TextMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )

                if (app.config.cfProxyEnabled) {
                    val cfLabel = if (app.config.customCfDomain.isNotBlank()) {
                        "Cloudflare Active (${app.config.customCfDomain.trim()})"
                    } else {
                        "Cloudflare Proxy Active"
                    }
                    Text(
                        text = cfLabel,
                        color = if (currentState == ProxyUiState.CONNECTED) ActiveGreenLed else TextMuted.copy(alpha = 0.6f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Telemetry Download & Upload speeds (50/50 centered)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Download Speed & Total (Weight 1f)
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_arrow_down),
                                contentDescription = null,
                                tint = if (currentState == ProxyUiState.CONNECTED) ActiveGreenLed else TextMuted,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Входящий", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        RollingNumberText(
                            text = dlSpeed,
                            color = if (currentState == ProxyUiState.CONNECTED) TextWhite else TextMuted,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                        RollingNumberText(
                            text = "Всего: $totalRecv",
                            color = TextMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Normal
                        )
                    }

                    // Divider line (50.0% centered)
                    Box(
                        modifier = Modifier
                            .height(32.dp)
                            .width(1.dp)
                            .background(Color(0xFF1F2433))
                    )

                    // Upload Speed & Total (Weight 1f)
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_arrow_up),
                                contentDescription = null,
                                tint = TextMuted,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Исходящий", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        RollingNumberText(
                            text = ulSpeed,
                            color = if (currentState == ProxyUiState.CONNECTED) TextWhite else TextMuted,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                        RollingNumberText(
                            text = "Всего: $totalSent",
                            color = TextMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Normal
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // WsPool Real-Time Smooth Bezier Socket Stability Graph
                WsPoolStabilityGraph(
                    isProxyActive = currentState == ProxyUiState.CONNECTED,
                    activeConns = activeConns,
                    maxPoolSize = app.config.poolSize,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .padding(horizontal = 12.dp)
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Action Buttons Dock (Always visible, requiring Proxy ON)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Left Action: Copy Link
                    Surface(
                        onClick = {
                            if (currentState != ProxyUiState.CONNECTED) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                Toast.makeText(context, "⚠️ Включите прокси сначала!", Toast.LENGTH_SHORT).show()
                            } else {
                                val tgUrl = server.getTelegramProxyUrl()
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Telegram Proxy", tgUrl)
                                clipboard.setPrimaryClip(clip)
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                Toast.makeText(context, "Ссылка скопирована!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
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
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_copy),
                                contentDescription = "Скопировать",
                                tint = if (currentState == ProxyUiState.CONNECTED) TextWhite else TextMuted.copy(alpha = 0.5f),
                                modifier = Modifier.size(19.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Скопировать",
                                color = if (currentState == ProxyUiState.CONNECTED) TextWhite else TextMuted.copy(alpha = 0.5f),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    }

                    // Right Action: Apply to Telegram
                    Surface(
                        onClick = {
                            if (currentState != ProxyUiState.CONNECTED) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                Toast.makeText(context, "⚠️ Включите прокси сначала!", Toast.LENGTH_SHORT).show()
                            } else {
                                val tgUrl = server.getTelegramProxyUrl()
                                applyToTelegramPackages(context, tgUrl)
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
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
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_send),
                                contentDescription = "В Telegram",
                                tint = if (currentState == ProxyUiState.CONNECTED) ActiveGreenLed else TextMuted.copy(alpha = 0.5f),
                                modifier = Modifier.size(19.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "В Telegram",
                                color = if (currentState == ProxyUiState.CONNECTED) TextWhite else TextMuted.copy(alpha = 0.5f),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
            // ─── Column closed above ───

            if (showValueBanner) {
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

            // ── FLOATING EYE BUTTON (inside Box, outside Column) ──────────
            // Uses fillMaxSize+wrapContentSize trick to reach TopEnd without BoxScope.align
            val eyeAlpha by animateFloatAsState(
                targetValue = if (isUiHidden && eyeButtonVisible) 1f else 0f,
                animationSpec = tween(durationMillis = 350),
                label = "eyeButtonAlpha"
            )
            if (isUiHidden) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .wrapContentSize(Alignment.TopEnd)
                        .padding(top = padding.calculateTopPadding() + 8.dp, end = 16.dp)
                        .size(46.dp)
                        .clip(CircleShape)
                        .graphicsLayer { alpha = eyeAlpha }
                        .background(Color(0xFF141C28).copy(alpha = 0.85f))
                        .border(1.dp, Color(0xFF00FF87).copy(alpha = 0.7f), CircleShape)
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            isUiHidden = false
                            eyeButtonVisible = false
                        }
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_eye_slash),
                        contentDescription = "Показать интерфейс",
                        tint = Color(0xFF00FF87),
                        modifier = Modifier.size(22.dp)
                    )
            }
        }
    }
}
}
// end of HomeScreen

@Composable
fun RotatingProxyRing(
    state: ProxyUiState,
    modifier: Modifier = Modifier
) {
    val timeState = produceState(initialValue = 0L) {
        val startNano = System.nanoTime()
        while (true) {
            withFrameNanos { frameTimeNanos ->
                value = frameTimeNanos - startNano
            }
        }
    }

    val targetSweepAngle = when (state) {
        ProxyUiState.CONNECTING -> 150f
        ProxyUiState.CONNECTED -> 260f
        ProxyUiState.DISCONNECTING -> 130f
        ProxyUiState.DISCONNECTED -> 0f
    }
    val animatedSweepAngle by animateFloatAsState(
        targetValue = targetSweepAngle,
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "sweepAngle"
    )

    val targetAlpha = when (state) {
        ProxyUiState.CONNECTING -> 1.0f
        ProxyUiState.CONNECTED -> 0.88f
        ProxyUiState.DISCONNECTING -> 1.0f
        ProxyUiState.DISCONNECTED -> 0f
    }
    val animatedAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(500),
        label = "ringAlpha"
    )

    val headColor = when (state) {
        ProxyUiState.CONNECTING -> Color(0xFF00F5D4)     // Bright Neon Cyan
        ProxyUiState.CONNECTED -> ActiveGreenLed          // Emerald Accent
        ProxyUiState.DISCONNECTING -> Color(0xFFFF9E00)  // Glowing Amber / Orange
        ProxyUiState.DISCONNECTED -> Color(0xFF353C4F)   // Sleek Dark Gray
    }
    val animatedHeadColor by animateColorAsState(
        targetValue = headColor,
        animationSpec = tween(500),
        label = "headColor"
    )

    val tailColor = when (state) {
        ProxyUiState.CONNECTING -> Color(0xFF0077B6).copy(alpha = 0.15f)
        ProxyUiState.CONNECTED -> Color(0xFF00B4D8).copy(alpha = 0.25f)
        ProxyUiState.DISCONNECTING -> Color(0xFFFF5400).copy(alpha = 0.15f)
        ProxyUiState.DISCONNECTED -> Color.Transparent
    }
    val animatedTailColor by animateColorAsState(
        targetValue = tailColor,
        animationSpec = tween(500),
        label = "tailColor"
    )

    val density = LocalDensity.current
    val targetStrokeWidth = when (state) {
        ProxyUiState.CONNECTING -> 7.dp
        ProxyUiState.CONNECTED -> 5.dp
        ProxyUiState.DISCONNECTING -> 7.dp
        ProxyUiState.DISCONNECTED -> 3.dp
    }
    val animatedStrokeWidth by animateFloatAsState(
        targetValue = with(density) { targetStrokeWidth.toPx() },
        animationSpec = tween(500),
        label = "strokeWidth"
    )

    Canvas(modifier = modifier) {
        if (animatedAlpha <= 0.01f) return@Canvas

        val t = timeState.value / 1_000_000_000f
        val diameter = size.minDimension
        val stroke = animatedStrokeWidth

        // ── SLOW & SMOOTH ELEGANT ROTATION ──────────────────────────────
        // Slow rotation (~18 seconds per full revolution), silky smooth
        val outerAngle = (t * 20f + kotlin.math.sin(t * 0.3f) * 35f) % 360f

        // Inner counter-rotation (~15 seconds per revolution, opposite direction)
        val innerAngle = -(t * 24f + kotlin.math.cos(t * 0.35f) * 28f) % 360f

        // Smooth wave amplitude for organic curved path ("извилистая плавная дуга")
        val waveAmp = 3.2.dp.toPx()

        // Outer Ring Bounds
        val outerInset = stroke / 2f + 8.dp.toPx()
        val outerRadius = (diameter - outerInset * 2) / 2f
        val outerTopLeft = Offset(outerInset, outerInset)
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

        // 2. MAIN OUTER ROTATING RING (Slow, Smooth, Wavy Curvatures)
        rotate(degrees = outerAngle, pivot = center) {
            val steps = 72
            val sweepRad = Math.toRadians(animatedSweepAngle.toDouble()).toFloat()
            val wavyPath = androidx.compose.ui.graphics.Path()

            for (i in 0..steps) {
                val stepFrac = i.toFloat() / steps
                val currentRad = stepFrac * sweepRad

                // Smooth organic sine waves along the path
                val rWave = outerRadius + waveAmp * kotlin.math.sin(4f * currentRad + t * 1.6f)
                val px = center.x + rWave * kotlin.math.cos(currentRad)
                val py = center.y + rWave * kotlin.math.sin(currentRad)

                if (i == 0) wavyPath.moveTo(px, py) else wavyPath.lineTo(px, py)
            }

            // Draw curved ring arc
            drawPath(
                path = wavyPath,
                brush = Brush.sweepGradient(
                    colors = listOf(
                        animatedTailColor.copy(alpha = animatedAlpha * 0.15f),
                        animatedHeadColor.copy(alpha = animatedAlpha),
                        animatedHeadColor.copy(alpha = animatedAlpha),
                        animatedTailColor.copy(alpha = animatedAlpha * 0.1f)
                    )
                ),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )

            // Glowing Particle Head Dot
            val headAngleRad = sweepRad
            val radiusHead = outerRadius + waveAmp * kotlin.math.sin(4f * headAngleRad + t * 1.6f)
            val dotCenterX = center.x + radiusHead * kotlin.math.cos(headAngleRad)
            val dotCenterY = center.y + radiusHead * kotlin.math.sin(headAngleRad)

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

        // 3. INNER ACCENT RING (Slow Counter-Rotation, Non-overlapping)
        val isDualForced = state == ProxyUiState.CONNECTING || state == ProxyUiState.DISCONNECTING
        val organicDualFactor = 0.5f + 0.5f * kotlin.math.sin(t * 0.25f)
        val innerAlphaFactor = if (isDualForced) 1.0f else (organicDualFactor * 0.85f)

        if (innerAlphaFactor > 0.05f) {
            val innerInset = stroke / 2f + 25.dp.toPx()
            val innerRadius = (diameter - innerInset * 2) / 2f
            val innerTopLeft = Offset(innerInset, innerInset)
            val innerSize = Size(innerRadius * 2, innerRadius * 2)
            val innerSweep = (110f + 25f * kotlin.math.sin(t * 0.5f)).coerceIn(80f, 150f)

            rotate(degrees = innerAngle, pivot = center) {
                val innerSteps = 50
                val innerSweepRad = Math.toRadians(innerSweep.toDouble()).toFloat()
                val innerPath = androidx.compose.ui.graphics.Path()

                for (i in 0..innerSteps) {
                    val stepFrac = i.toFloat() / innerSteps
                    val currentRad = stepFrac * innerSweepRad
                    val rWave = innerRadius + (waveAmp * 0.7f) * kotlin.math.sin(3f * currentRad - t * 1.4f)
                    val px = center.x + rWave * kotlin.math.cos(currentRad)
                    val py = center.y + rWave * kotlin.math.sin(currentRad)

                    if (i == 0) innerPath.moveTo(px, py) else innerPath.lineTo(px, py)
                }

                drawPath(
                    path = innerPath,
                    color = animatedHeadColor.copy(alpha = animatedAlpha * 0.55f * innerAlphaFactor),
                    style = Stroke(width = stroke * 0.65f, cap = StrokeCap.Round)
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

private fun applyToTelegramPackages(context: Context, url: String) {
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
 * Animates timer digits, speeds, and data metrics with bouncy spring physics.
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
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        text.forEachIndexed { index, char ->
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
                label = "rollingChar_${index}_$char"
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
    modifier: Modifier = Modifier
) {
    // Continuous nanosecond frame clock for 60Hz/120Hz smooth rendering
    var timeSeconds by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        val startTime = System.nanoTime()
        while (true) {
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

    val infiniteTransition = rememberInfiniteTransition(label = "headPulseTransition")
    val headPulseScale by infiniteTransition.animateFloat(
        initialValue = 4.dp.value,
        targetValue = 7.dp.value,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "headPulseScale"
    )

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

            val steps = 80 // High sub-pixel sampling density for max smoothing
            val stepX = width / steps
            val t = timeSeconds

            val points = List(steps + 1) { i ->
                val u = i.toFloat() / steps // normalized 0..1
                
                // Silky Soft Low-Frequency Harmonic Superposition
                val w1 = sin(u * 4.2f + t * 1.4f)          // Long rolling primary wave
                val w2 = cos(u * 6.8f - t * 1.1f) * 0.35f  // Soft secondary harmonic
                val w3 = sin(u * 2.5f + t * 1.8f) * 0.15f  // Gentle breathing ripple
                
                val combinedWave = (w1 + w2 + w3) / 1.5f
                val waveHeightSpan = animatedAmplitude * height * 0.40f
                val centerY = height - (animatedAmplitude * height * 0.38f) - (height * 0.14f)
                
                val y = (centerY + combinedWave * waveHeightSpan).coerceIn(4f, height - 4f)
                Offset(i * stepX, y)
            }

            // Construct Ultra-Smooth Catmull-Rom C1 Spline Path with Derivative Tangents
            val strokePath = Path().apply {
                moveTo(points.first().x, points.first().y)
                for (i in 0 until points.size - 1) {
                    val p0 = points[if (i > 0) i - 1 else i]
                    val p1 = points[i]
                    val p2 = points[i + 1]
                    val p3 = points[if (i + 2 < points.size) i + 2 else i + 1]

                    // Exact Catmull-Rom tangent derivative control points (Zero-Kink C1 Continuity)
                    val control1 = Offset(
                        p1.x + (p2.x - p0.x) / 6f,
                        p1.y + (p2.y - p0.y) / 6f
                    )
                    val control2 = Offset(
                        p2.x - (p3.x - p1.x) / 6f,
                        p2.y - (p3.y - p1.y) / 6f
                    )
                    cubicTo(control1.x, control1.y, control2.x, control2.y, p2.x, p2.y)
                }
            }

            // Area Fill Path under the Bezier Curve
            val fillPath = Path().apply {
                addPath(strokePath)
                lineTo(width, height)
                lineTo(0f, height)
                close()
            }

            val neonColor = if (isProxyActive) ActiveGreenLed else InactiveGrayLed

            // 1. Draw Gradient Area Fill under Liquid Wave
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        neonColor.copy(alpha = if (isProxyActive) 0.32f else 0.08f),
                        Color.Transparent
                    )
                )
            )

            // 2. Draw Soft Ambient Glow Line Layer
            drawPath(
                path = strokePath,
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        neonColor.copy(alpha = 0.15f),
                        neonColor.copy(alpha = 0.40f),
                        neonColor.copy(alpha = 0.40f)
                    )
                ),
                style = Stroke(
                    width = 5.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )

            // 3. Draw Main Crisp Silky Bezier Line
            drawPath(
                path = strokePath,
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        neonColor.copy(alpha = 0.4f),
                        neonColor,
                        neonColor
                    )
                ),
                style = Stroke(
                    width = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )

            // 3. Draw Head Pulsating Glowing LED Dot at the leading edge
            if (isProxyActive && points.isNotEmpty()) {
                val headPoint = points.last()
                drawCircle(
                    color = ActiveGreenLed.copy(alpha = 0.35f),
                    radius = headPulseScale.dp.toPx(),
                    center = headPoint
                )
                drawCircle(
                    color = ActiveGreenLed,
                    radius = 3.dp.toPx(),
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
