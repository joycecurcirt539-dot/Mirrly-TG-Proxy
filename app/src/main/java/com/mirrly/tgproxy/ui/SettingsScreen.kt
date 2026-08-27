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

import android.content.Intent
import android.os.Build
import androidx.core.view.WindowCompat
import com.mirrly.tgproxy.core.AppLogger
import com.mirrly.tgproxy.core.NativeProxy
import android.view.WindowManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.core.ProxyConfig
import com.mirrly.tgproxy.core.ProxyMode
import com.mirrly.tgproxy.core.TcpNoDelayMode
import com.mirrly.tgproxy.service.NetworkConditionEvaluator
import com.mirrly.tgproxy.service.ProxyForegroundService
import com.mirrly.tgproxy.ui.theme.*
import com.mirrly.tgproxy.util.shareApp
import android.net.Uri
import android.widget.Toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun InertialSpringSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    activeColor: Color = ActiveGreenLed,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val targetOffset = if (checked) 22.dp else 2.dp
    val animOffset by animateDpAsState(
        targetValue = targetOffset,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "switchInertiaOffset"
    )

    val trackColor by animateColorAsState(
        targetValue = if (checked) activeColor else Color(0xFF181C28),
        animationSpec = tween(180),
        label = "switchTrackColor"
    )

    Box(
        modifier = modifier
            .width(48.dp)
            .height(26.dp)
            .clip(CircleShape)
            .background(trackColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onCheckedChange(!checked)
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .offset(x = animOffset.coerceAtLeast(0.dp))
                .size(22.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

/** Маленькая кнопка-подсказка «ⓘ» рядом с заголовком настройки */
@Composable
fun InfoButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(18.dp)
            .clip(CircleShape)
            .border(1.dp, TextMuted.copy(alpha = 0.55f), CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
    ) {
        Text(
            text = "i",
            color = TextMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontStyle = FontStyle.Normal,
            lineHeight = 10.sp
        )
    }
}

/** Всплывающее диалоговое окно с подробным описанием настройки */
@Composable
fun InfoDialog(title: String, body: String, onDismiss: () -> Unit) {
    val haptic = LocalHapticFeedback.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        DialogBackdropBox(
            onDismiss = onDismiss
        ) {
            // Scrollable Detailed Info Content with Smooth Fading Edges into background blur
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .fadingEdges(topFadeHeight = 32.dp, bottomFadeHeight = 44.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(
                        top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 60.dp,
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp
                    )
                    .padding(horizontal = 24.dp)
                    .clickable(enabled = false) {}
            ) {
                // Category Pill
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = ActiveGreenLed.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, ActiveGreenLed.copy(alpha = 0.35f))
                ) {
                    Text(
                        text = "СПРАВКА И НАСТРОЙКИ",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = ActiveGreenLed,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                }

                // Title
                Text(
                    text = title,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite,
                    textAlign = TextAlign.Center,
                    letterSpacing = 0.3.sp
                )

                // Formatted body content with transparent glass cards & left-aligned text
                FormattedInfoBody(body = body)
            }

            // Top Header with Back Button (pinned at top left over blurred background)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopStart)
                    .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp)
            ) {
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDismiss()
                    }
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_arrow_left),
                        contentDescription = "Назад",
                        tint = TextWhite,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun FormattedInfoBody(body: String) {
    val blocks = remember(body) { body.split("\n\n") }

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        blocks.forEach { block ->
            val trimmed = block.trim()
            if (trimmed.isEmpty()) return@forEach

            val lines = trimmed.lines()
            val firstLine = lines.firstOrNull() ?: ""
            val isHeaderBlock = firstLine.endsWith(":") ||
                                firstLine.contains("БЕЗОПАСНОСТЬ") ||
                                firstLine.contains("КАК РАБОТАЕТ") ||
                                firstLine.contains("ПОЧЕМУ СВОЙ ВОРКЕР")

            if (isHeaderBlock && lines.size > 1) {
                // Render section block in a transparent glass container
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White.copy(alpha = 0.04f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val headerColor = when {
                            firstLine.contains("БЕЗОПАСНОСТЬ") -> ActiveGreenLed
                            firstLine.contains("РАБОТАЕТ") -> Color(0xFF38BDF8)
                            firstLine.contains("ЛУЧШЕ") || firstLine.contains("ПОЧЕМУ") -> Color(0xFFFFC107)
                            else -> ActiveGreenLed
                        }
                        Text(
                            text = firstLine,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = headerColor,
                            letterSpacing = 0.5.sp,
                            textAlign = TextAlign.Start
                        )

                        val contentLines = lines.drop(1)
                        contentLines.forEach { line ->
                            val lineTrimmed = line.trim()
                            if (lineTrimmed.startsWith("• ")) {
                                InfoBulletItem(text = lineTrimmed.removePrefix("• ").trim())
                            } else if (lineTrimmed.isNotEmpty()) {
                                Text(
                                    text = lineTrimmed,
                                    fontSize = 13.sp,
                                    color = TextWhite.copy(alpha = 0.88f),
                                    lineHeight = 19.sp,
                                    textAlign = TextAlign.Start
                                )
                            }
                        }
                    }
                }
            } else if (trimmed.contains("\n• ") || trimmed.startsWith("• ")) {
                // Bullet items block in transparent glass container
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White.copy(alpha = 0.04f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        lines.forEach { line ->
                            val lineTrimmed = line.trim()
                            if (lineTrimmed.startsWith("• ")) {
                                InfoBulletItem(text = lineTrimmed.removePrefix("• ").trim())
                            } else if (lineTrimmed.isNotEmpty()) {
                                Text(
                                    text = lineTrimmed,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = TextWhite.copy(alpha = 0.92f),
                                    lineHeight = 19.sp,
                                    textAlign = TextAlign.Start
                                )
                            }
                        }
                    }
                }
            } else {
                // General text paragraph in transparent glass card
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White.copy(alpha = 0.04f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = trimmed,
                        fontSize = 13.sp,
                        color = TextWhite.copy(alpha = 0.88f),
                        lineHeight = 19.sp,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoBulletItem(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .padding(top = 7.dp)
                .size(5.dp)
                .background(ActiveGreenLed, CircleShape)
        )
        Text(
            text = text,
            fontSize = 13.sp,
            color = TextWhite.copy(alpha = 0.88f),
            lineHeight = 18.5.sp,
            textAlign = TextAlign.Start,
            modifier = Modifier.weight(1f)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAbout: () -> Unit = {},
    onOpenUpdate: () -> Unit = {},
    onOpenWorkerGuide: () -> Unit = {},
    onOpenWorkerManager: () -> Unit = {},
    onOpenVolunteers: () -> Unit = {},
    onOpenHallOfFame: () -> Unit = {}
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val app = MirrlyApplication.instance
    val config = app.config
    val server = app.proxyServer

    var portText by remember { mutableStateOf(config.bindPort.toString()) }
    val isPortError by remember {
        derivedStateOf {
            portText.toIntOrNull()?.let { it < 1 || it > 65535 } ?: portText.isNotEmpty()
        }
    }
    var socks5PortText by remember { mutableStateOf(config.socks5Port.toString()) }
    val isSocks5PortError by remember {
        derivedStateOf {
            socks5PortText.toIntOrNull()?.let { it < 1 || it > 65535 } ?: socks5PortText.isNotEmpty()
        }
    }
    var secretText by remember(config.secretHex) { mutableStateOf(config.secretHex) }
    var showSecret by remember { mutableStateOf(false) }
    val isSocks5 by app.prefsManager.isSocks5Flow.collectAsState()
    val isSwitching by com.mirrly.tgproxy.service.ProtocolSwitchManager.isSwitching.collectAsState()
    val selectedMode = if (isSocks5) ProxyMode.SOCKS5 else ProxyMode.MTPROTO

    var selectedSpeedPresetName by remember { mutableStateOf(config.speedPresetName) }
    var autostart by remember { mutableStateOf(config.autostartOnBoot) }
    var infoKey by remember { mutableStateOf<String?>(null) }
    var pendingIssueRedirectUrl by remember { mutableStateOf<String?>(null) }
    val timerState by com.mirrly.tgproxy.service.SleepTimerManager.timerState.collectAsState()
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var showDonateConfirmDialog by remember { mutableStateOf(false) }

    fun restartProxyIfNeeded() {
        app.saveConfig()
        if (server.isRunning) {
            val serviceIntent = Intent(context, ProxyForegroundService::class.java).apply {
                action = ProxyForegroundService.ACTION_RESTART
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                AppLogger.e("SettingsScreen", "Не удалось перезапустить службу прокси: ${e.message}")
            }
        }
    }

    LaunchedEffect(portText) {
        delay(600)
        val p = portText.toIntOrNull()
        if (p != null && p in 1..65535 && p != config.bindPort) {
            config.bindPort = p
            restartProxyIfNeeded()
        }
    }

    LaunchedEffect(socks5PortText) {
        delay(600)
        val p = socks5PortText.toIntOrNull()
        if (p != null && p in 1..65535 && p != config.socks5Port) {
            config.socks5Port = p
            restartProxyIfNeeded()
        }
    }

    LaunchedEffect(secretText) {
        delay(600)
        val trimmed = secretText.trim()
        if (trimmed.isNotBlank() && trimmed != config.secretHex) {
            config.secretHex = trimmed
            restartProxyIfNeeded()
        }
    }

    infoKey?.let { key ->
        SettingsInfoDialog(infoKey = key, onDismiss = { infoKey = null })
    }

    pendingIssueRedirectUrl?.let { url ->
        ExternalLinkConfirmDialog(
            url = url,
            onDismiss = { pendingIssueRedirectUrl = null }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .fadingEdges(topFadeHeight = 24.dp, bottomFadeHeight = 44.dp)
                .verticalScroll(rememberScrollState())
                .padding(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 20.dp
                )
                .padding(horizontal = 22.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            SettingsProtocolSection(
                selectedMode = selectedMode,
                isSwitching = isSwitching,
                onInfoClick = { infoKey = "protocols_info" },
                onModeSelect = { mode ->
                    com.mirrly.tgproxy.service.ProtocolSwitchManager.switchProtocol(context, mode)
                }
            )

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF161A26)))

            SettingsNetworkSection(
                selectedMode = selectedMode,
                portText = portText,
                onPortChange = { portText = it },
                isPortError = isPortError,
                socks5PortText = socks5PortText,
                onSocks5PortChange = { socks5PortText = it },
                isSocks5PortError = isSocks5PortError,
                secretText = secretText,
                onSecretChange = { secretText = it },
                showSecret = showSecret,
                onToggleShowSecret = { showSecret = !showSecret },
                onRefreshSecret = {
                    val newSecret = ProxyConfig.generateRandomSecret()
                    secretText = newSecret
                    config.secretHex = newSecret
                    restartProxyIfNeeded()
                },
                onInfoClick = { infoKey = it }
            )

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF161A26)))

            SettingsPerformanceSection(
                config = config,
                selectedSpeedPresetName = selectedSpeedPresetName,
                onPresetSelect = { preset ->
                    selectedSpeedPresetName = preset.name
                    config.applyPreset(preset)
                    if (preset != com.mirrly.tgproxy.core.SpeedPreset.AUTO) {
                        server.applyPoolSize(preset.defaultPoolSize)
                    }
                    app.saveConfig()
                },
                onInfoClick = { infoKey = it }
            )

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF161A26)))

            SettingsWorkerSection(
                config = config,
                onOpenWorkerManager = onOpenWorkerManager,
                onOpenWorkerGuide = onOpenWorkerGuide,
                onInfoClick = { infoKey = it }
            )

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF161A26)))

            SettingsSystemSection(
                autostart = autostart,
                onAutostartChange = { newValue ->
                    autostart = newValue
                    config.autostartOnBoot = newValue
                    app.saveConfig()
                },
                timerState = timerState,
                onOpenSleepTimer = { showSleepTimerDialog = true },
                onInfoClick = { infoKey = it }
            )

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF161A26)))

            SettingsAboutSection(
                onOpenAbout = onOpenAbout,
                onDonateClick = { showDonateConfirmDialog = true },
                onOpenUpdate = onOpenUpdate,
                onOpenVolunteers = onOpenVolunteers,
                onOpenHallOfFame = onOpenHallOfFame
            )

            Spacer(modifier = Modifier.height(24.dp))
        }

        SettingsTopBar(onBack = onBack)

        CyberParticlesOverlay(
            modifier = Modifier.fillMaxSize(),
            particleCount = 14,
            alphaMultiplier = 0.50f
        )

        if (showDonateConfirmDialog) {
            ExternalLinkConfirmDialog(
                url = "https://dalink.to/cartneyzix",
                title = "Поддержать разработчика",
                description = "Ссылка ведет на страницу сервиса DaLink для добровольной поддержки автора R1Xern. Mirrly TG Proxy — полностью бесплатный проект с открытым исходным кодом.",
                onDismiss = { showDonateConfirmDialog = false }
            )
        }

        if (showSleepTimerDialog) {
            SleepTimerDialog(onDismiss = { showSleepTimerDialog = false })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTopBar(onBack: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.98f),
                        Color.Black.copy(alpha = 0.94f),
                        Color.Black.copy(alpha = 0.72f),
                        Color.Black.copy(alpha = 0.00f)
                    )
                )
            )
    ) {
        TopAppBar(
            title = {
                Text(
                    text = "Настройки",
                    color = TextWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            },
            navigationIcon = {
                IconButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onBack()
                }) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_arrow_left),
                        contentDescription = "Назад",
                        tint = TextWhite,
                        modifier = Modifier.size(22.dp)
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
        )
    }
}

@Composable
private fun SettingsProtocolSection(
    selectedMode: ProxyMode,
    isSwitching: Boolean,
    onInfoClick: () -> Unit,
    onModeSelect: (ProxyMode) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    Column(
        modifier = Modifier.staggeredEntrance(index = 0),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "ПРОТОКОЛ",
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.3.sp,
                color = TextMuted
            )
            InfoButton { onInfoClick() }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(ProxyMode.MTPROTO, ProxyMode.SOCKS5).forEach { mode ->
                val isSelected = selectedMode == mode
                val modeAccent = if (mode == ProxyMode.SOCKS5) Color(0xFFB388FF) else ActiveGreenLed
                val chipBorder by animateColorAsState(
                    targetValue = if (isSelected) modeAccent else Color(0xFF1E2333),
                    animationSpec = tween(200),
                    label = "modeBorder_${mode.name}"
                )
                val chipTextColor by animateColorAsState(
                    targetValue = if (isSelected) modeAccent else TextWhite,
                    animationSpec = tween(200),
                    label = "modeText_${mode.name}"
                )
                val chipBgColor by animateColorAsState(
                    targetValue = if (isSelected) modeAccent.copy(alpha = 0.08f) else Color.Transparent,
                    animationSpec = tween(200),
                    label = "modeBg_${mode.name}"
                )

                val modeLabel = when (mode) {
                    ProxyMode.MTPROTO -> "MTProto"
                    ProxyMode.SOCKS5  -> "SOCKS5 [БЕТА]"
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(chipBgColor)
                        .border(1.dp, chipBorder, RoundedCornerShape(12.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            if (isSwitching || selectedMode == mode) return@clickable
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onModeSelect(mode)
                        }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = modeLabel,
                        fontSize = 13.5.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = chipTextColor
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsNetworkSection(
    selectedMode: ProxyMode,
    portText: String,
    onPortChange: (String) -> Unit,
    isPortError: Boolean,
    socks5PortText: String,
    onSocks5PortChange: (String) -> Unit,
    isSocks5PortError: Boolean,
    secretText: String,
    onSecretChange: (String) -> Unit,
    showSecret: Boolean,
    onToggleShowSecret: () -> Unit,
    onRefreshSecret: () -> Unit,
    onInfoClick: (String) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val app = MirrlyApplication.instance
    Column(
        modifier = Modifier.staggeredEntrance(index = 1),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "СЕТЬ",
            fontSize = 12.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.3.sp,
            color = TextMuted
        )

        if (selectedMode == ProxyMode.MTPROTO) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Порт MTProto", color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    InfoButton { onInfoClick("port") }
                }
                OutlinedTextField(
                    value = portText,
                    onValueChange = onPortChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = isPortError,
                    shape = RoundedCornerShape(14.dp),
                    supportingText = if (isPortError) {
                        { Text("Введите число от 1 до 65535", color = Color(0xFFEF4444), fontSize = 12.sp) }
                    } else null,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedBorderColor = if (isPortError) Color(0xFFEF4444) else ActiveGreenLed,
                        unfocusedBorderColor = if (isPortError) Color(0xFFEF4444) else Color(0xFF1E2333),
                        focusedTextColor = TextWhite,
                        unfocusedTextColor = TextWhite,
                        errorBorderColor = Color(0xFFEF4444),
                        errorTextColor = TextWhite
                    )
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Порт SOCKS5", color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    InfoButton { onInfoClick("port") }
                }
                OutlinedTextField(
                    value = socks5PortText,
                    onValueChange = onSocks5PortChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = isSocks5PortError,
                    shape = RoundedCornerShape(14.dp),
                    supportingText = if (isSocks5PortError) {
                        { Text("Введите число от 1 до 65535", color = Color(0xFFEF4444), fontSize = 12.sp) }
                    } else null,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedBorderColor = if (isSocks5PortError) Color(0xFFEF4444) else ActiveGreenLed,
                        unfocusedBorderColor = if (isSocks5PortError) Color(0xFFEF4444) else Color(0xFF1E2333),
                        focusedTextColor = TextWhite,
                        unfocusedTextColor = TextWhite,
                        errorBorderColor = Color(0xFFEF4444),
                        errorTextColor = TextWhite
                    )
                )
            }
        }

        if (selectedMode == ProxyMode.MTPROTO) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Секретный ключ (Hex)", color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    InfoButton { onInfoClick("secret") }
                }
                OutlinedTextField(
                    value = secretText,
                    onValueChange = onSecretChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(0.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            IconButton(
                                onClick = {
                                    onToggleShowSecret()
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Crossfade(targetState = showSecret, animationSpec = tween(180), label = "eyeFade") { isVisible ->
                                    Icon(
                                        painter = painterResource(id = if (isVisible) R.drawable.ic_eye_slash else R.drawable.ic_eye),
                                        contentDescription = null,
                                        tint = if (isVisible) ActiveGreenLed else TextMuted,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            IconButton(
                                onClick = {
                                    onRefreshSecret()
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_refresh),
                                    contentDescription = null,
                                    tint = TextWhite,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedBorderColor = ActiveGreenLed,
                        unfocusedBorderColor = Color(0xFF1E2333),
                        focusedTextColor = TextWhite,
                        unfocusedTextColor = TextWhite
                    )
                )
            }
        }
    }
}

@Composable
private fun SettingsPerformanceSection(
    config: ProxyConfig,
    selectedSpeedPresetName: String,
    onPresetSelect: (com.mirrly.tgproxy.core.SpeedPreset) -> Unit,
    onInfoClick: (String) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val app = MirrlyApplication.instance
    val server = app.proxyServer

    Column(
        modifier = Modifier.staggeredEntrance(index = 2),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "ПРОИЗВОДИТЕЛЬНОСТЬ",
            fontSize = 12.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.3.sp,
            color = TextMuted
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Режимы пропускной способности (WsPool)", color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                InfoButton { onInfoClick("preset") }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                com.mirrly.tgproxy.core.SpeedPreset.values().forEach { preset ->
                    val isSelected = selectedSpeedPresetName == preset.name
                    val chipBorder by animateColorAsState(
                        targetValue = if (isSelected) ActiveGreenLed else Color(0xFF1E2333),
                        animationSpec = tween(200),
                        label = "presetBorder_${preset.name}"
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Transparent)
                            .border(1.dp, chipBorder, RoundedCornerShape(12.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onPresetSelect(preset)
                            }
                            .padding(vertical = 9.dp, horizontal = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val iconRes = when (preset) {
                                com.mirrly.tgproxy.core.SpeedPreset.ECO -> R.drawable.ic_speed_eco
                                com.mirrly.tgproxy.core.SpeedPreset.BALANCED -> R.drawable.ic_speed_balanced
                                com.mirrly.tgproxy.core.SpeedPreset.TURBO -> R.drawable.ic_speed_turbo
                                com.mirrly.tgproxy.core.SpeedPreset.ULTRA -> R.drawable.ic_speed_ultra
                                com.mirrly.tgproxy.core.SpeedPreset.AUTO -> R.drawable.ic_speed_auto
                            }
                            val titleText = when (preset) {
                                com.mirrly.tgproxy.core.SpeedPreset.ECO -> "Эко"
                                com.mirrly.tgproxy.core.SpeedPreset.BALANCED -> "Баланс"
                                com.mirrly.tgproxy.core.SpeedPreset.TURBO -> "Турбо"
                                com.mirrly.tgproxy.core.SpeedPreset.ULTRA -> "Ультра"
                                com.mirrly.tgproxy.core.SpeedPreset.AUTO -> "Авто"
                            }

                            Icon(
                                painter = painterResource(id = iconRes),
                                contentDescription = null,
                                tint = if (isSelected) ActiveGreenLed else TextMuted,
                                modifier = Modifier.size(14.dp)
                            )

                            Text(
                                text = titleText,
                                fontSize = 11.5.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) ActiveGreenLed else TextWhite
                            )
                        }
                    }
                }
            }
        }

        var tcpNoDelayModeState by remember { mutableStateOf(config.tcpNoDelayMode) }
        val context = LocalContext.current
        val autoEvaluation by produceState(
            initialValue = NetworkConditionEvaluator.evaluate(
                context = context,
                capabilities = null,
                currentPingMs = server.currentPingMs,
                currentThroughputBps = server.stats.downloadSpeedBps + server.stats.uploadSpeedBps
            ),
            key1 = server.currentPingMs,
            key2 = server.stats.downloadSpeedBps
        ) {
            value = NetworkConditionEvaluator.evaluate(
                context = context,
                capabilities = null,
                currentPingMs = server.currentPingMs,
                currentThroughputBps = server.stats.downloadSpeedBps + server.stats.uploadSpeedBps
            )
        }

        val tcpNoDelayStatusText = when (tcpNoDelayModeState) {
            TcpNoDelayMode.AUTO -> autoEvaluation.statusDescription
            TcpNoDelayMode.ON -> "Включено: Мгновенная отправка (все сети)"
            TcpNoDelayMode.OFF -> "Выключено: Склеивание пакетов Nagle (все сети)"
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
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
                    Text("Мгновенная отдача (TCP_NODELAY)", color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    InfoButton { onInfoClick("tcp_nodelay") }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(TcpNoDelayMode.AUTO, TcpNoDelayMode.ON, TcpNoDelayMode.OFF).forEach { mode ->
                    val isSelected = tcpNoDelayModeState == mode
                    val chipBorder by animateColorAsState(
                        targetValue = if (isSelected) ActiveGreenLed else Color(0xFF1E2333),
                        animationSpec = tween(200),
                        label = "tcpBorder_${mode.name}"
                    )
                    val chipBg by animateColorAsState(
                        targetValue = if (isSelected) ActiveGreenLed.copy(alpha = 0.08f) else Color.Transparent,
                        animationSpec = tween(200),
                        label = "tcpBg_${mode.name}"
                    )
                    val chipTextColor by animateColorAsState(
                        targetValue = if (isSelected) ActiveGreenLed else TextWhite,
                        animationSpec = tween(200),
                        label = "tcpText_${mode.name}"
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(chipBg)
                            .border(1.dp, chipBorder, RoundedCornerShape(12.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                tcpNoDelayModeState = mode
                                config.tcpNoDelayModeName = mode.name
                                val effective = when (mode) {
                                    TcpNoDelayMode.ON -> true
                                    TcpNoDelayMode.OFF -> false
                                    TcpNoDelayMode.AUTO -> autoEvaluation.isInstantSendRecommended
                                }
                                config.tcpNoDelay = effective
                                server.applyTcpNoDelay(effective)
                                app.saveConfig()
                            }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = mode.displayName,
                            fontSize = 12.5.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = chipTextColor
                        )
                    }
                }
            }

            Text(
                text = tcpNoDelayStatusText,
                color = if (tcpNoDelayModeState == TcpNoDelayMode.AUTO && autoEvaluation.isInstantSendRecommended) ActiveGreenLed.copy(alpha = 0.85f) else TextMuted,
                fontSize = 11.5.sp,
                lineHeight = 15.sp
            )
        }
    }
}

@Composable
private fun SettingsWorkerSection(
    config: ProxyConfig,
    onOpenWorkerManager: () -> Unit,
    onOpenWorkerGuide: () -> Unit,
    onInfoClick: (String) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val app = MirrlyApplication.instance
    val activeWorker = remember(app.prefsManager.getActiveWorkerId()) { app.prefsManager.getActiveWorker() }

    Column(
        modifier = Modifier.staggeredEntrance(index = 3),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "ТУННЕЛИРОВАНИЕ CLOUDFLARE",
            fontSize = 12.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.3.sp,
            color = TextMuted
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Transparent)
                .border(1.dp, Color(0xFF1E2333), RoundedCornerShape(20.dp))
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onOpenWorkerManager()
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFFB388FF).copy(alpha = 0.12f))
                                .border(1.dp, Color(0xFFB388FF).copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_settings),
                                contentDescription = null,
                                tint = Color(0xFFB388FF),
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        Column {
                            Text(
                                text = "Менеджер воркеров",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite
                            )
                            Text(
                                text = "Активен: ${activeWorker.name}",
                                fontSize = 11.5.sp,
                                color = TextMuted
                            )
                        }
                    }

                    Icon(
                        painter = painterResource(id = R.drawable.ic_chevron_right),
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF141824)))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onOpenWorkerGuide()
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFFFF9E00).copy(alpha = 0.12f))
                                .border(1.dp, Color(0xFFFF9E00).copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_speed_turbo),
                                contentDescription = null,
                                tint = Color(0xFFFF9E00),
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        Column {
                            Text(
                                text = "Инструкция по развертыванию",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite
                            )
                            Text(
                                text = "Создать личный воркер за 2 минуты",
                                fontSize = 11.5.sp,
                                color = TextMuted
                            )
                        }
                    }

                    Icon(
                        painter = painterResource(id = R.drawable.ic_chevron_right),
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }



        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF0F172A).copy(alpha = 0.6f))
                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(ActiveGreenLed.copy(alpha = 0.12f))
                        .border(1.dp, ActiveGreenLed.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "CDN",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = ActiveGreenLed,
                        letterSpacing = 0.5.sp
                    )
                }
                Text(
                    text = "В режиме MTProto трафик надежно туннелируется через 20 встроенных Cloudflare CDN узлов без прямого подключения.",
                    color = TextMuted,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
private fun SettingsSystemSection(
    autostart: Boolean,
    onAutostartChange: (Boolean) -> Unit,
    timerState: com.mirrly.tgproxy.service.SleepTimerState,
    onOpenSleepTimer: () -> Unit,
    onInfoClick: (String) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val app = MirrlyApplication.instance
    var disableAnimations by remember { mutableStateOf(app.prefsManager.areAnimationsDisabled()) }

    Column(
        modifier = Modifier.staggeredEntrance(index = 4),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "СИСТЕМА И ЭНЕРГОСБЕРЕЖЕНИЕ",
            fontSize = 12.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.3.sp,
            color = TextMuted
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Автозапуск при загрузке", color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    InfoButton { onInfoClick("autostart") }
                }
                Text("Автоматический запуск службы прокси после перезагрузки устройства", color = TextMuted, fontSize = 11.5.sp)
            }
            InertialSpringSwitch(
                checked = autostart,
                onCheckedChange = onAutostartChange
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onOpenSleepTimer()
                }
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text("Таймер сна (автоотключение)", color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    text = if (timerState.isActive) "Активен • Отключение через ${timerState.formatRemainingTime()}" else "Выключен • Нажмите для выбора интервала",
                    color = if (timerState.isActive) ActiveGreenLed else TextMuted,
                    fontSize = 11.5.sp
                )
            }
            Icon(
                painter = painterResource(id = R.drawable.ic_timer),
                contentDescription = null,
                tint = if (timerState.isActive) ActiveGreenLed else TextMuted,
                modifier = Modifier.size(20.dp)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Режим энергосбережения", color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    InfoButton { onInfoClick("disable_animations") }
                }
                Text("Отключение фоновых анимаций и частиц для экономии заряда батареи", color = TextMuted, fontSize = 11.5.sp)
            }
            InertialSpringSwitch(
                checked = disableAnimations,
                onCheckedChange = { newValue ->
                    disableAnimations = newValue
                    app.prefsManager.setAnimationsDisabled(newValue)
                }
            )
        }
    }
}

@Composable
private fun SettingsAboutSection(
    onOpenAbout: () -> Unit,
    onDonateClick: () -> Unit,
    onOpenUpdate: () -> Unit,
    onOpenVolunteers: () -> Unit,
    onOpenHallOfFame: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    var isCheckingUpdate by remember { mutableStateOf(false) }
    val currentUpdateInfo by com.mirrly.tgproxy.service.UpdateManager.updateState.collectAsState()
    val isUpdateAvailable = currentUpdateInfo?.isUpdateAvailable == true
    val yellowAccent = Color(0xFFFFB703)

    Column(
        modifier = Modifier.staggeredEntrance(index = 5),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "О ПРИЛОЖЕНИИ И СООБЩЕСТВЕ",
            fontSize = 12.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.3.sp,
            color = TextMuted
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Transparent)
                .border(1.dp, Color(0xFF181E2E), RoundedCornerShape(18.dp))
        ) {
            // ── 1. STANDOUT VOLUNTEER TESTING BUTTON ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .lightSweep(
                        isEnabled = true,
                        shape = RoundedCornerShape(14.dp),
                        sweepColor = Color(0xFFFFB703)
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onOpenVolunteers()
                    }
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFFFFB703).copy(alpha = 0.15f))
                            .border(1.dp, Color(0xFFFFB703).copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_volunteer_badge),
                            contentDescription = null,
                            tint = Color(0xFFFFB703),
                            modifier = Modifier.size(17.dp)
                        )
                    }

                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Программа тестирования",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFFFFB703).copy(alpha = 0.20f),
                                border = androidx.compose.foundation.BorderStroke(0.8.dp, Color(0xFFFFB703).copy(alpha = 0.5f))
                            ) {
                                Text(
                                    text = "НАБОР",
                                    fontSize = 8.5.sp,
                                    fontWeight = FontWeight.Black,
                                    color = Color(0xFFFFB703),
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                        Text(
                            text = "Ищем волонтеров: ранний доступ к APK и бонусы",
                            fontSize = 11.5.sp,
                            color = TextMuted
                        )
                    }
                }

                Icon(
                    painter = painterResource(id = R.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = Color(0xFFFFB703),
                    modifier = Modifier.size(16.dp)
                )
            }

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF141824)))

            // ── 2. STANDOUT HALL OF FAME BUTTON ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .lightSweep(
                        isEnabled = true,
                        shape = RoundedCornerShape(14.dp),
                        sweepColor = Color(0xFF7C4DFF)
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onOpenHallOfFame()
                    }
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF7C4DFF).copy(alpha = 0.15f))
                            .border(1.dp, Color(0xFF7C4DFF).copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_hall_of_fame),
                            contentDescription = null,
                            tint = Color(0xFFC084FC),
                            modifier = Modifier.size(17.dp)
                        )
                    }

                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Зал Славы и Благодарности",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFF7C4DFF).copy(alpha = 0.20f),
                                border = androidx.compose.foundation.BorderStroke(0.8.dp, Color(0xFF7C4DFF).copy(alpha = 0.5f))
                            ) {
                                Text(
                                    text = "TOP",
                                    fontSize = 8.5.sp,
                                    fontWeight = FontWeight.Black,
                                    color = Color(0xFFC084FC),
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                        Text(
                            text = "Первопроходцы, контрибьюторы и цифровые слепки",
                            fontSize = 11.5.sp,
                            color = TextMuted
                        )
                    }
                }

                Icon(
                    painter = painterResource(id = R.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = Color(0xFFC084FC),
                    modifier = Modifier.size(16.dp)
                )
            }

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF141824)))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onOpenAbout()
                    }
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(ActiveGreenLed.copy(alpha = 0.12f))
                            .border(1.dp, ActiveGreenLed.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_user),
                            contentDescription = null,
                            tint = ActiveGreenLed,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Column {
                        Text(
                            text = "О разработчике & Проекте",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite
                        )
                        Text(
                            text = "Mirrly Dev (R1Xern) • Информация, соцсети и статус сборки",
                            fontSize = 11.5.sp,
                            color = TextMuted
                        )
                    }
                }

                Icon(
                    painter = painterResource(id = R.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(16.dp)
                )
            }

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF141824)))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDonateClick()
                    }
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(ActiveGreenLed.copy(alpha = 0.12f))
                            .border(1.dp, ActiveGreenLed.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_donate),
                            contentDescription = null,
                            tint = ActiveGreenLed,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Column {
                        Text(
                            text = "Поддержать разработчика",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite
                        )
                        Text(
                            text = "Добровольный донат на развитие проекта (DaLink)",
                            fontSize = 11.5.sp,
                            color = TextMuted
                        )
                    }
                }

                Icon(
                    painter = painterResource(id = R.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(16.dp)
                )
            }

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF141824)))

            val itemBgColor = if (isUpdateAvailable) Color(0xFF1F1A0A) else Color.Transparent
            val iconBoxBgColor = if (isUpdateAvailable) yellowAccent.copy(alpha = 0.2f) else ActiveGreenLed.copy(alpha = 0.12f)
            val iconTint = if (isUpdateAvailable) yellowAccent else ActiveGreenLed
            val titleColor = if (isUpdateAvailable) yellowAccent else TextWhite

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(itemBgColor)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        if (isUpdateAvailable) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onOpenUpdate()
                        } else if (!isCheckingUpdate) {
                            isCheckingUpdate = true
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            coroutineScope.launch {
                                val result = com.mirrly.tgproxy.service.UpdateManager.checkForUpdates(context, notifyIfFound = false, forceRefresh = true)
                                isCheckingUpdate = false
                                result.fold(
                                    onSuccess = { info ->
                                        if (info.isUpdateAvailable) {
                                            onOpenUpdate()
                                        } else {
                                            Toast.makeText(
                                                context,
                                                "У вас установлена актуальная версия",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    },
                                    onFailure = { err ->
                                        Toast.makeText(
                                            context,
                                            "Ошибка проверки обновлений: ${err.localizedMessage}",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                )
                            }
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(iconBoxBgColor)
                            .border(1.dp, iconTint.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_refresh),
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Column {
                        Text(
                            text = if (isUpdateAvailable) "Найдено обновление!" else "Проверить обновления",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = titleColor
                        )
                        Text(
                            text = when {
                                isCheckingUpdate -> "Проверка GitHub Releases..."
                                isUpdateAvailable -> "Доступна новая версия • Нажмите для установки"
                                else -> "Поиск новых версий на GitHub"
                            },
                            fontSize = 11.5.sp,
                            color = if (isUpdateAvailable) TextWhite.copy(alpha = 0.9f) else TextMuted
                        )
                    }
                }

                if (isCheckingUpdate) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = ActiveGreenLed,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_chevron_right),
                        contentDescription = null,
                        tint = if (isUpdateAvailable) yellowAccent else TextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsInfoDialog(infoKey: String, onDismiss: () -> Unit) {
    val (dlgTitle, dlgBody) = when (infoKey) {
        "port" -> "Порт подключения" to
            "Локальный TCP-порт, на котором прокси принимает подключения от Telegram.\n\nРекомендованное значение: 1443. Этот порт не требует прав root и обычно не занят другими приложениями.\n\nДопустимый диапазон: 1–65535. Если порт занят — прокси не запустится. После смены порта обновите настройки прокси в Telegram."
        "secret" -> "Секретный ключ (MTProto)" to
            "32-символьный hex-ключ протокола MTProto. Его необходимо указать в настройках прокси Telegram — без него подключение невозможно.\n\nПрефикс dd означает режим Fake TLS: трафик маскируется под обычный HTTPS, что позволяет обходить DPI-фильтрацию.\n\nНажмите кнопку генерации для создания нового случайного ключа. После смены обновите ссылку-приглашение в Telegram."
        "cf_domain" -> "Безопасность & Принцип работы Cloudflare Worker" to
            "БЕЗОПАСНОСТЬ ЛИЧНЫХ ДАННЫХ:\n" +
            "Для 100% защиты вашей конфиденциальности и анонимности я рекомендую развернуть свой личный воркер по встроенной инструкции.\n\n" +
            "При использовании чужого воркера ваш трафик проходит через посторонний узел. Разворачивая собственный бесплатный воркер, вы гарантируете, что логи и ключи доступа принадлежат ТОЛЬКО вам.\n\n" +
            "КАК РАБОТАЕТ CLOUDFLARE WORKER:\n" +
            "• Cloudflare Worker — это бессерверный V8-скрипт на глобальной сети Cloudflare Edge (300+ городов по всему миру).\n" +
            "• Он принимает трафик Telegram через зашифрованные WebSockets (wss://) и создает прямое TCP-подключение к дата-центрам Telegram через серверные каналы Cloudflare.\n" +
            "• Для провайдеров и систем DPI/ТСПУ этот трафик выглядит как абсолютно обычное безопасное посещение любого сайта на Cloudflare, что полностью сводит на нет попытки блокировки.\n\n" +
            "ПОЧЕМУ СВОЙ ВОРКЕР ЛУЧШЕ:\n" +
            "• Бесплатный тариф Cloudflare даёт 100 000 запросов в день лично вам.\n" +
            "• Отсутствие зависимости от сторонних серверов и чужих лимитов.\n" +
            "• Максимальная скорость для звонков, скачивания тяжёлых файлов и видео."

        "autostart" -> "Автозапуск при загрузке" to
            "Прокси автоматически запустится после перезагрузки устройства — не нужно включать вручную.\n\nРаботает через системный сигнал BOOT_COMPLETED.\n\nВажно: на устройствах MIUI, HyperOS и OneUI может потребоваться дополнительное разрешение «Автозапуск» в системных настройках телефона, иначе система заблокирует запуск."
        "preset" -> "Режимы пропускной способности (WsPool)" to
            "Управление размером пула сокетов (WebSocket pre-warming) и глубиной буферов для достижения максимальной скорости и минимальных задержек:\n\n" +
            "• Эко (2 сокета, 128 КБ буфер):\n" +
            "Минимальный расход аккумулятора и оперативной памяти (~100–200 КБ RAM). Идеально для фоновой работы только текстовых чатов.\n\n" +
            "• Баланс (4 сокета, 256 КБ буфер, рекомендуется):\n" +
            "Оптимальный баланс скорости и энергопотребления для повседневного использования Telegram.\n\n" +
            "• Турбо (8 сокетов, 1 МБ буфер):\n" +
            "Мгновенный отклик при открытии диалогов, быстрая предзагрузка фото, видео и голосовых сообщений.\n\n" +
            "• Ультра (16 сокетов, 2 МБ буфер):\n" +
            "Максимальная параллелизация WebSocket-соединений и предельная пропускная способность при скачивании тяжелых файлов на каналах до 1 Гбит/с.\n\n" +
            "• Авто (2–16 сокетов, адаптивный буфер):\n" +
            "Интеллектуальная динамическая адаптация пула соединений в реальном времени под текущую скорость сети, пинг и нагрузку."
        "tcp_nodelay" -> "Мгновенная отдача (TCP_NODELAY)" to
            "Управление алгоритмом Нагла (Nagle's Algorithm) для TCP-сокетов:\n\n" +
            "• Авто (рекомендуется):\n" +
            "Автоматическая адаптация под качество соединения. Мгновенная отправка пакетов (TCP_NODELAY = true) активируется на скоростных каналах (от 50 Мбит/с) с низким пингом (до 140 мс). При слабом сигнале, высокой задержке или перегрузке сети включается склеивание пакетов для стабильности и защиты от потерь.\n\n" +
            "• Включено (Мгновенная отдача):\n" +
            "Пакеты отправляются в сеть немедленно в любых условиях. Минимизирует задержку отклика (минус 40–200 мс), но может увеличивать нагрузку на радиомодем при нестабильной связи.\n\n" +
            "• Выключено (Склеивание пакетов):\n" +
            "Ядро объединяет мелкие порции данных в полные TCP-сегменты перед передачей в сеть. Повышает стабильность на узких каналах связи."
        "disable_animations" -> "Отключение анимаций и частиц" to
            "Оптимизирует энергопотребление и снижает нагрузку на процессор устройства.\n\nПри включении тумблера убираются фоновые визуальные частицы и тяжёлые анимации, что продлевает время автономной работы батареи и обеспечивает максимальную плавность на бюджетных устройствах."
        "protocols_info" -> "Режимы работы прокси" to
            "• MTProto (рекомендуется):\nНативный протокол Telegram с WsPool, Fake-TLS и турбо-буферами. Максимальная скорость. Используй эту ссылку: tg://proxy?...secret=dd...\n\n• SOCKS5 (для звонков и чатов):\nПрозрачный TCP relay. Telegram шифрует данные самостоятельно. Поддерживает чаты, медиа и голосовые/видеозвонки одновременно. Используй tg://socks?...\n\nПримечание: оба режима не работают одновременно — выбери один."
        else -> return
    }
    InfoDialog(title = dlgTitle, body = dlgBody, onDismiss = onDismiss)
}


