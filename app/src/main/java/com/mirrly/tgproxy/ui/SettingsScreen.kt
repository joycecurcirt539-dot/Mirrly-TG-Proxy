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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
    enabled: Boolean = true,
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

    val effectiveActive = if (enabled) activeColor else activeColor.copy(alpha = 0.35f)
    val trackColor by animateColorAsState(
        targetValue = if (checked) effectiveActive else Color(0xFF181C28).copy(alpha = if (enabled) 1f else 0.5f),
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
                enabled = enabled,
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
                .background(if (enabled) Color.White else Color.White.copy(alpha = 0.45f))
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
                    .align(Alignment.Center)
                    .adaptiveContainerWidth(440.dp)
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
                // Render section block in a transparent container
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, AmoledBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val headerColor = ActiveGreenLed
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
                // Bullet items block in transparent container
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, AmoledBorder),
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
                // General text paragraph in transparent card
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, AmoledBorder),
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

enum class SettingsCategory(val title: String) {
    ALL("Все"),
    NETWORK("Сеть"),
    UPLINK_DOH("Аплинк"),
    SYSTEM("Система"),
    MISC("Прочее")
}

@Composable
private fun SettingsDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(AmoledBorder.copy(alpha = 0.7f))
    )
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
    var socks5UserText by remember(config.socks5Username) { mutableStateOf(config.socks5Username) }
    var socks5PassText by remember(config.socks5Password) { mutableStateOf(config.socks5Password) }
    var showSocks5Pass by remember { mutableStateOf(false) }
    var secretText by remember(config.secretHex) { mutableStateOf(config.secretHex) }
    var showSecret by remember { mutableStateOf(false) }
    val isSocks5 by app.prefsManager.isSocks5Flow.collectAsState()
    val isSwitching by com.mirrly.tgproxy.service.ProtocolSwitchManager.isSwitching.collectAsState()
    val selectedMode = if (isSocks5) ProxyMode.SOCKS5 else ProxyMode.MTPROTO

    var selectedSpeedPresetName by remember { mutableStateOf(config.speedPresetName) }
    var autostart by remember { mutableStateOf(config.autostartOnBoot) }
    var infoKey by remember { mutableStateOf<String?>(null) }
    var pendingIssueRedirectUrl by remember { mutableStateOf<String?>(null) }
    var enabledDohProviderIds by remember { mutableStateOf(config.enabledDohProviderIds) }
    var isProxyRunning by remember { mutableStateOf(server.isRunning) }
    LaunchedEffect(Unit) {
        while (isActive) {
            val running = server.isRunning
            if (isProxyRunning != running) {
                isProxyRunning = running
            }
            delay(300)
        }
    }
    var isBenchmarkingDoh by remember { mutableStateOf(false) }
    var benchmarkProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var benchmarkResults by remember { mutableStateOf<Map<String, com.mirrly.tgproxy.core.DohBenchmarkResult>>(emptyMap()) }
    var benchmarkSummary by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val timerState by com.mirrly.tgproxy.service.SleepTimerManager.timerState.collectAsState()
    val uplinkMode by app.prefsManager.uplinkModeFlow.collectAsState()
    var warpProfile by remember { mutableStateOf(app.prefsManager.getWarpProfile()) }
    var isRegisteringWarp by remember { mutableStateOf(false) }
    var vlessUuid by remember { mutableStateOf<String>(app.prefsManager.getVlessUuid().ifEmpty { config.vlessUuid }) }
    var sleepTimerInitialTab by remember { mutableStateOf(TimerDialogTab.TIMER) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var showDonateConfirmDialog by remember { mutableStateOf(false) }
    var showWarpRegistrationDialog by remember { mutableStateOf(false) }
    var warpDialogStartRegistrationImmediately by remember { mutableStateOf(false) }

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

    LaunchedEffect(socks5UserText) {
        delay(600)
        val trimmed = socks5UserText.trim()
        if (trimmed != config.socks5Username) {
            config.socks5Username = trimmed
            NativeProxy.setSocks5Auth(config.socks5Username, config.socks5Password)
            restartProxyIfNeeded()
        }
    }

    LaunchedEffect(socks5PassText) {
        delay(600)
        val trimmed = socks5PassText.trim()
        if (trimmed != config.socks5Password) {
            config.socks5Password = trimmed
            NativeProxy.setSocks5Auth(config.socks5Username, config.socks5Password)
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

    var selectedCategory by rememberSaveable { mutableStateOf(SettingsCategory.ALL) }
    val scrollState = rememberScrollState()

    LaunchedEffect(selectedCategory) {
        scrollState.animateScrollTo(0)
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        val showNetwork = selectedCategory == SettingsCategory.ALL || selectedCategory == SettingsCategory.NETWORK
        val showUplinkDoh = selectedCategory == SettingsCategory.ALL || selectedCategory == SettingsCategory.UPLINK_DOH
        val showSystem = selectedCategory == SettingsCategory.ALL || selectedCategory == SettingsCategory.SYSTEM
        val showMisc = selectedCategory == SettingsCategory.ALL || selectedCategory == SettingsCategory.MISC

        Column(
            modifier = Modifier
                .adaptiveContainerWidth(600.dp)
                .fillMaxHeight()
                .fadingEdges(topFadeHeight = 24.dp, bottomFadeHeight = 44.dp)
                .verticalScroll(scrollState)
                .padding(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 110.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 20.dp
                )
                .adaptiveContentPadding(),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            if (showNetwork) {
                SettingsProtocolSection(
                    selectedMode = selectedMode,
                    isSwitching = isSwitching,
                    onInfoClick = { infoKey = "protocols_info" },
                    onModeSelect = { mode ->
                        com.mirrly.tgproxy.service.ProtocolSwitchManager.switchProtocol(context, mode)
                    }
                )

                SettingsDivider()

                SettingsNetworkSection(
                    selectedMode = selectedMode,
                    portText = portText,
                    onPortChange = { portText = it },
                    isPortError = isPortError,
                    socks5PortText = socks5PortText,
                    onSocks5PortChange = { socks5PortText = it },
                    isSocks5PortError = isSocks5PortError,
                    socks5UserText = socks5UserText,
                    onSocks5UserChange = { socks5UserText = it },
                    socks5PassText = socks5PassText,
                    onSocks5PassChange = { socks5PassText = it },
                    showSocks5Pass = showSocks5Pass,
                    onToggleShowSocks5Pass = { showSocks5Pass = !showSocks5Pass },
                    onGenerateSocks5Auth = {
                        val (u, p) = ProxyConfig.generateRandomSocks5Credentials()
                        socks5UserText = u
                        socks5PassText = p
                        config.socks5Username = u
                        config.socks5Password = p
                        NativeProxy.setSocks5Auth(u, p)
                        restartProxyIfNeeded()
                    },
                    onClearSocks5Auth = {
                        socks5UserText = ""
                        socks5PassText = ""
                        config.socks5Username = ""
                        config.socks5Password = ""
                        NativeProxy.setSocks5Auth("", "")
                        restartProxyIfNeeded()
                    },
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

                SettingsDivider()

                SettingsWorkerSection(
                    config = config,
                    onOpenWorkerManager = onOpenWorkerManager,
                    onOpenWorkerGuide = onOpenWorkerGuide,
                    onInfoClick = { infoKey = it }
                )

                if (showUplinkDoh || showSystem || showMisc) {
                    SettingsDivider()
                }
            }

            if (showUplinkDoh) {
                SettingsUplinkWarpSection(
                    config = config,
                    uplinkMode = uplinkMode,
                    warpProfile = warpProfile,
                    isRegisteringWarp = isRegisteringWarp,
                    onSelectUplinkMode = { newMode ->
                        app.prefsManager.setUplinkMode(newMode)
                        config.uplinkModeName = newMode.name
                        app.saveConfig()
                        server.applyUplinkMode(newMode)
                        restartProxyIfNeeded()
                    },
                    onRefreshWarpAccount = {
                        warpDialogStartRegistrationImmediately = true
                        showWarpRegistrationDialog = true
                    },
                    onOpenWarpDetails = {
                        warpDialogStartRegistrationImmediately = false
                        showWarpRegistrationDialog = true
                    },
                    onInfoClick = { infoKey = it },
                    vlessUuid = vlessUuid,
                    onRegenerateVlessUuid = {
                        val newUuid = com.mirrly.tgproxy.core.ProxyConfig.generateVlessUuid()
                        vlessUuid = newUuid
                        config.vlessUuid = newUuid
                        app.prefsManager.setVlessUuid(newUuid)
                        app.saveConfig()
                        server.applyVlessConfig(newUuid, config.vlessPath)
                        Toast.makeText(context, "Сгенерирован новый UUID для VLESS", Toast.LENGTH_SHORT).show()
                        restartProxyIfNeeded()
                    },
                    onRestartProxy = { restartProxyIfNeeded() }
                )

                SettingsDivider()

                SettingsDohSection(
                    enabledProviderIds = enabledDohProviderIds,
                    isProxyRunning = isProxyRunning,
                    onStopProxy = {
                        isProxyRunning = false
                        try {
                            val stopIntent = Intent(context, ProxyForegroundService::class.java).apply {
                                action = ProxyForegroundService.ACTION_STOP
                            }
                            context.startService(stopIntent)
                            server.stop()
                        } catch (e: Exception) {
                            server.stop()
                        }
                    },
                    onToggleProvider = { providerId, isEnabled ->
                        if (isProxyRunning || server.isRunning) {
                            Toast.makeText(context, "Остановите прокси для смены DNS", Toast.LENGTH_SHORT).show()
                            return@SettingsDohSection
                        }
                        val newSet = if (isEnabled) {
                            enabledDohProviderIds + providerId
                        } else {
                            if (enabledDohProviderIds.size <= 1 && enabledDohProviderIds.contains(providerId)) {
                                Toast.makeText(context, "Необходимо оставить хотя бы один DoH-провайдер", Toast.LENGTH_SHORT).show()
                                enabledDohProviderIds
                            } else {
                                enabledDohProviderIds - providerId
                            }
                        }
                        enabledDohProviderIds = newSet
                        config.enabledDohProviderIds = newSet
                        app.saveConfig()
                        server.applyDohConfig(newSet)
                    },
                    onResetDefaults = {
                        if (isProxyRunning || server.isRunning) {
                            Toast.makeText(context, "Остановите прокси для сброса DNS", Toast.LENGTH_SHORT).show()
                            return@SettingsDohSection
                        }
                        val defaults = com.mirrly.tgproxy.core.DohResolver.DEFAULT_ENABLED_PROVIDER_IDS
                        enabledDohProviderIds = defaults
                        config.enabledDohProviderIds = defaults
                        app.saveConfig()
                        server.applyDohConfig(defaults)
                        benchmarkResults = emptyMap()
                        benchmarkSummary = null
                        Toast.makeText(context, "Настройки DoH сброшены по умолчанию", Toast.LENGTH_SHORT).show()
                    },
                    onSelectAllProviders = {
                        if (isProxyRunning || server.isRunning) {
                            Toast.makeText(context, "Остановите прокси для смены DNS", Toast.LENGTH_SHORT).show()
                            return@SettingsDohSection
                        }
                        val allIds = com.mirrly.tgproxy.core.DohResolver.ALL_PROVIDERS.map { it.id }.toSet()
                        enabledDohProviderIds = allIds
                        config.enabledDohProviderIds = allIds
                        app.saveConfig()
                        server.applyDohConfig(allIds)
                        Toast.makeText(context, "Включены все DoH-серверы (${allIds.size})", Toast.LENGTH_SHORT).show()
                    },
                    isBenchmarking = isBenchmarkingDoh,
                    benchmarkProgress = benchmarkProgress,
                    benchmarkResults = benchmarkResults,
                    benchmarkSummary = benchmarkSummary,
                    onStartBenchmark = {
                        if (isProxyRunning || server.isRunning) {
                            Toast.makeText(context, "Остановите прокси для запуска автоподбора", Toast.LENGTH_SHORT).show()
                            return@SettingsDohSection
                        }
                        if (isBenchmarkingDoh) return@SettingsDohSection
                        isBenchmarkingDoh = true
                        benchmarkProgress = Pair(0, com.mirrly.tgproxy.core.DohResolver.ALL_PROVIDERS.size)
                        benchmarkSummary = null
                        coroutineScope.launch {
                            try {
                                val report = com.mirrly.tgproxy.core.DohBenchmarkEngine.benchmarkAll(
                                    providers = com.mirrly.tgproxy.core.DohResolver.ALL_PROVIDERS,
                                    onProgress = { current, total, result ->
                                        benchmarkProgress = Pair(current, total)
                                        benchmarkResults = benchmarkResults + (result.providerId to result)
                                    }
                                )
                                benchmarkSummary = report.summaryText
                                if (report.recommendedProviderIds.isNotEmpty()) {
                                    enabledDohProviderIds = report.recommendedProviderIds
                                    config.enabledDohProviderIds = report.recommendedProviderIds
                                    app.saveConfig()
                                    server.applyDohConfig(report.recommendedProviderIds)
                                    Toast.makeText(context, report.summaryText, Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(context, "Все серверы недоступны. Проверьте интернет-соединение.", Toast.LENGTH_LONG).show()
                                }
                            } catch (e: Exception) {
                                Toast.makeText(context, "Ошибка тестирования: ${e.message}", Toast.LENGTH_SHORT).show()
                            } finally {
                                isBenchmarkingDoh = false
                                benchmarkProgress = null
                            }
                        }
                    },
                    onInfoClick = { infoKey = it }
                )

                if (showSystem || showMisc) {
                    SettingsDivider()
                }
            }

            if (showSystem) {
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

                SettingsDivider()

                SettingsSystemSection(
                    autostart = autostart,
                    onAutostartChange = { newValue ->
                        autostart = newValue
                        config.autostartOnBoot = newValue
                        app.saveConfig()
                    },
                    timerState = timerState,
                    onOpenSleepTimer = {
                        sleepTimerInitialTab = TimerDialogTab.TIMER
                        showSleepTimerDialog = true
                    },
                    onOpenSchedule = {
                        sleepTimerInitialTab = TimerDialogTab.SCHEDULE
                        showSleepTimerDialog = true
                    },
                    onInfoClick = { infoKey = it }
                )

                if (showMisc) {
                    SettingsDivider()
                }
            }

            if (showMisc) {
                SettingsAboutSection(
                    onOpenAbout = onOpenAbout,
                    onDonateClick = { showDonateConfirmDialog = true },
                    onOpenUpdate = onOpenUpdate,
                    onOpenVolunteers = onOpenVolunteers,
                    onOpenHallOfFame = onOpenHallOfFame
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        SettingsTopBar(
            selectedCategory = selectedCategory,
            onSelectCategory = { selectedCategory = it },
            onBack = onBack
        )

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
            SleepTimerDialog(
                initialTab = sleepTimerInitialTab,
                onDismiss = { showSleepTimerDialog = false }
            )
        }

        if (showWarpRegistrationDialog) {
            WarpRegistrationDialog(
                initialProfile = warpProfile,
                workerDomain = config.getEffectiveCfDomain(),
                initialLicenseKey = config.warpLicenseKey,
                startRegistrationImmediately = warpDialogStartRegistrationImmediately,
                onProfileSaved = { newProf ->
                    app.prefsManager.saveWarpProfile(newProf)
                    warpProfile = newProf
                    config.applyWarpProfile(newProf)
                    app.saveConfig()
                    restartProxyIfNeeded()
                },
                onDismiss = {
                    showWarpRegistrationDialog = false
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTopBar(
    selectedCategory: SettingsCategory,
    onSelectCategory: (SettingsCategory) -> Unit,
    onBack: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val accent = ActiveGreenLed

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.98f),
                        Color.Black.copy(alpha = 0.95f),
                        Color.Black.copy(alpha = 0.85f),
                        Color.Black.copy(alpha = 0.00f)
                    )
                )
            )
            .padding(bottom = 12.dp)
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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SettingsCategory.values().forEach { category ->
                val isSelected = selectedCategory == category
                val chipBg by animateColorAsState(
                    targetValue = if (isSelected) accent.copy(alpha = 0.14f) else Color.Transparent,
                    animationSpec = tween(180),
                    label = "catBg_${category.name}"
                )
                val chipBorder by animateColorAsState(
                    targetValue = if (isSelected) accent.copy(alpha = 0.65f) else AmoledBorder,
                    animationSpec = tween(180),
                    label = "catBorder_${category.name}"
                )
                val chipText by animateColorAsState(
                    targetValue = if (isSelected) accent else TextMuted,
                    animationSpec = tween(180),
                    label = "catText_${category.name}"
                )

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = chipBg,
                    border = BorderStroke(1.dp, chipBorder),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelectCategory(category)
                        }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = category.title,
                            fontSize = 11.5.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = chipText,
                            maxLines = 1,
                            softWrap = false,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
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
                val modeAccent = if (mode == ProxyMode.SOCKS5) Socks5Accent else MtprotoAccent
                val chipBorder by animateColorAsState(
                    targetValue = if (isSelected) modeAccent else AmoledBorder,
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
    socks5UserText: String,
    onSocks5UserChange: (String) -> Unit,
    socks5PassText: String,
    onSocks5PassChange: (String) -> Unit,
    showSocks5Pass: Boolean,
    onToggleShowSocks5Pass: () -> Unit,
    onGenerateSocks5Auth: () -> Unit,
    onClearSocks5Auth: () -> Unit,
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
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "СЕТЬ",
            fontSize = 12.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.3.sp,
            color = TextMuted
        )

        if (selectedMode == ProxyMode.MTPROTO) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.Transparent)
                    .border(1.dp, AmoledBorder, RoundedCornerShape(18.dp))
            ) {
                Column {
                    // 1. ПОРТ MTProto
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text("Порт MTProto", color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                                InfoButton { onInfoClick("port") }
                            }
                            Text(
                                text = if (isPortError) "Введите число от 1 до 65535" else "Локальный порт для Telegram",
                                color = if (isPortError) Color(0xFFEF4444) else TextMuted,
                                fontSize = 11.5.sp
                            )
                        }

                        BasicTextField(
                            value = portText,
                            onValueChange = onPortChange,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            textStyle = TextStyle(
                                color = if (isPortError) Color(0xFFEF4444) else TextWhite,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                textAlign = TextAlign.Center
                            ),
                            cursorBrush = SolidColor(if (isPortError) Color(0xFFEF4444) else ActiveGreenLed),
                            decorationBox = { innerTextField ->
                                Box(
                                    modifier = Modifier
                                        .width(76.dp)
                                        .height(34.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.White.copy(alpha = 0.04f))
                                        .border(
                                            width = 1.dp,
                                            color = if (isPortError) Color(0xFFEF4444) else AmoledBorder,
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .padding(horizontal = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    innerTextField()
                                }
                            }
                        )
                    }

                    // Divider
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AmoledBorder))

                    // 2. СЕКРЕТНЫЙ КЛЮЧ
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text("Секретный ключ (Hex)", color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                                InfoButton { onInfoClick("secret") }
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = {
                                        onToggleShowSecret()
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Crossfade(targetState = showSecret, animationSpec = tween(180), label = "eyeFade") { isVisible ->
                                        Icon(
                                            painter = painterResource(id = if (isVisible) R.drawable.ic_eye_slash else R.drawable.ic_eye),
                                            contentDescription = null,
                                            tint = if (showSecret) ActiveGreenLed else TextMuted,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                }

                                IconButton(
                                    onClick = {
                                        onRefreshSecret()
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_refresh),
                                        contentDescription = null,
                                        tint = TextWhite,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }

                        BasicTextField(
                            value = secretText,
                            onValueChange = onSecretChange,
                            singleLine = true,
                            visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                            textStyle = TextStyle(
                                color = TextWhite,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 0.5.sp
                            ),
                            cursorBrush = SolidColor(ActiveGreenLed),
                            decorationBox = { innerTextField ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(34.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.White.copy(alpha = 0.04f))
                                        .border(1.dp, AmoledBorder, RoundedCornerShape(8.dp))
                                        .padding(horizontal = 10.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    innerTextField()
                                }
                            }
                        )
                    }
                }
            }
        } else {
            // SOCKS5 Mode Settings
            // 1. КАРТОЧКА ПОРТА SOCKS5
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.Transparent)
                    .border(1.dp, AmoledBorder, RoundedCornerShape(18.dp))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("Порт SOCKS5", color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                            InfoButton { onInfoClick("port") }
                        }
                        Text(
                            text = if (isSocks5PortError) "Введите число от 1 до 65535" else "Локальный TCP Relay порт",
                            color = if (isSocks5PortError) Color(0xFFEF4444) else TextMuted,
                            fontSize = 11.5.sp
                        )
                    }

                    BasicTextField(
                        value = socks5PortText,
                        onValueChange = onSocks5PortChange,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = TextStyle(
                            color = if (isSocks5PortError) Color(0xFFEF4444) else TextWhite,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.Center
                        ),
                        cursorBrush = SolidColor(if (isSocks5PortError) Color(0xFFEF4444) else Socks5Accent),
                        decorationBox = { innerTextField ->
                            Box(
                                modifier = Modifier
                                    .width(76.dp)
                                    .height(34.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.White.copy(alpha = 0.04f))
                                    .border(
                                        width = 1.dp,
                                        color = if (isSocks5PortError) Color(0xFFEF4444) else AmoledBorder,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .padding(horizontal = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                innerTextField()
                            }
                        }
                    )
                }
            }

            // 2. КАРТОЧКА АВТОРИЗАЦИИ SOCKS5 (RFC 1929)
            val hasAuth = socks5UserText.isNotBlank() || socks5PassText.isNotBlank()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.Transparent)
                    .border(BorderStroke(1.dp, AmoledBorder), RoundedCornerShape(18.dp))
            ) {
                Column {
                    // Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("Авторизация SOCKS5", color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                            InfoButton { onInfoClick("socks5_auth") }
                        }

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (hasAuth) Socks5Accent.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f),
                            border = BorderStroke(1.dp, if (hasAuth) Socks5Accent.copy(alpha = 0.4f) else AmoledBorder)
                        ) {
                            Text(
                                text = if (hasAuth) "RFC 1929" else "ОТКРЫТЫЙ",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (hasAuth) Socks5Accent else TextMuted,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.5.dp)
                            )
                        }
                    }

                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AmoledBorder))

                    // Username Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Логин",
                            color = TextMuted,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.width(62.dp)
                        )
                        BasicTextField(
                            value = socks5UserText,
                            onValueChange = onSocks5UserChange,
                            singleLine = true,
                            textStyle = TextStyle(
                                color = TextWhite,
                                fontSize = 12.5.sp,
                                fontFamily = FontFamily.Monospace
                            ),
                            cursorBrush = SolidColor(Socks5Accent),
                            modifier = Modifier.weight(1f),
                            decorationBox = { innerTextField ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(32.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.White.copy(alpha = 0.03f))
                                        .border(1.dp, AmoledBorder, RoundedCornerShape(8.dp))
                                        .padding(horizontal = 9.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    if (socks5UserText.isEmpty()) {
                                        Text("Без логина (открытый)", color = TextMuted.copy(alpha = 0.6f), fontSize = 12.sp)
                                    }
                                    innerTextField()
                                }
                            }
                        )
                    }

                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AmoledBorder))

                    // Password Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Пароль",
                            color = TextMuted,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.width(62.dp)
                        )
                        BasicTextField(
                            value = socks5PassText,
                            onValueChange = onSocks5PassChange,
                            singleLine = true,
                            visualTransformation = if (showSocks5Pass) VisualTransformation.None else PasswordVisualTransformation(),
                            textStyle = TextStyle(
                                color = TextWhite,
                                fontSize = 12.5.sp,
                                fontFamily = FontFamily.Monospace
                            ),
                            cursorBrush = SolidColor(Socks5Accent),
                            modifier = Modifier.weight(1f),
                            decorationBox = { innerTextField ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(32.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.White.copy(alpha = 0.03f))
                                        .border(1.dp, AmoledBorder, RoundedCornerShape(8.dp))
                                        .padding(start = 9.dp, end = 4.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Box(modifier = Modifier.weight(1f)) {
                                            if (socks5PassText.isEmpty()) {
                                                Text("Без пароля (открытый)", color = TextMuted.copy(alpha = 0.6f), fontSize = 12.sp)
                                            }
                                            innerTextField()
                                        }
                                        IconButton(
                                            onClick = {
                                                onToggleShowSocks5Pass()
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Crossfade(targetState = showSocks5Pass, animationSpec = tween(180), label = "socks5EyeFade") { isVisible ->
                                                Icon(
                                                    painter = painterResource(id = if (isVisible) R.drawable.ic_eye_slash else R.drawable.ic_eye),
                                                    contentDescription = null,
                                                    tint = if (isVisible) Socks5Accent else TextMuted,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        )
                    }

                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AmoledBorder))

                    // Compact Actions
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                onGenerateSocks5Auth()
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Socks5Accent.copy(alpha = 0.16f),
                                contentColor = Socks5Accent
                            ),
                            border = BorderStroke(1.dp, Socks5Accent.copy(alpha = 0.35f)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            modifier = Modifier.weight(1f).height(32.dp)
                        ) {
                            Text("Сгенерировать логин и пароль", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                        }

                        if (hasAuth) {
                            OutlinedButton(
                                onClick = {
                                    onClearSocks5Auth()
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("Очистить", color = TextWhite, fontSize = 11.5.sp)
                            }
                        }
                    }
                }
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
                        targetValue = if (isSelected) ActiveGreenLed else AmoledBorder,
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
                        targetValue = if (isSelected) ActiveGreenLed else AmoledBorder,
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "ТУННЕЛИРОВАНИЕ CLOUDFLARE",
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.3.sp,
                color = TextMuted
            )
            InfoButton { onInfoClick("cf_domain") }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Transparent)
                .border(1.dp, AmoledBorder, RoundedCornerShape(20.dp))
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
                                .background(ActiveGreenLed.copy(alpha = 0.12f))
                                .border(1.dp, ActiveGreenLed.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_settings),
                                contentDescription = null,
                                tint = ActiveGreenLed,
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

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AmoledBorder))

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
                                .background(ActiveGreenLed.copy(alpha = 0.12f))
                                .border(1.dp, ActiveGreenLed.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_speed_turbo),
                                contentDescription = null,
                                tint = ActiveGreenLed,
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
                .background(Color.Transparent)
                .border(1.dp, AmoledBorder, RoundedCornerShape(12.dp))
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
                        .border(1.dp, ActiveGreenLed.copy(alpha = 0.40f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (config.isSocks5Mode) "SOCKS5" else "MTPROTO",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = ActiveGreenLed,
                        letterSpacing = 0.5.sp
                    )
                }
                Text(
                    text = if (config.isSocks5Mode) {
                        "Cloudflare Worker принимает WSS/VLESS соединения и пересылает TCP-трафик SOCKS5 к серверам через cloudflare:sockets."
                    } else {
                        "Cloudflare Worker применяется для аплинка SOCKS5. В текущем режиме MTProto соединение идет напрямую к шлюзам Telegram."
                    },
                    color = TextMuted,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
private fun UplinkModeChip(
    mode: com.mirrly.tgproxy.core.UplinkMode,
    displayName: String,
    badge: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) ActiveGreenLed else AmoledBorder,
        animationSpec = tween(200),
        label = "uplinkChipBorder_${mode.name}"
    )
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) ActiveGreenLed.copy(alpha = 0.10f) else Color.Transparent,
        animationSpec = tween(200),
        label = "uplinkChipBg_${mode.name}"
    )

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = bgColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = displayName,
                    fontSize = 12.5.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (isSelected) TextWhite else TextWhite.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isSelected) ActiveGreenLed.copy(alpha = 0.20f) else Color.Transparent)
                        .border(0.8.dp, if (isSelected) ActiveGreenLed.copy(alpha = 0.40f) else AmoledBorder, RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = badge,
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isSelected) ActiveGreenLed else TextMuted
                    )
                }
            }
            Text(
                text = subtitle,
                fontSize = 10.5.sp,
                color = if (isSelected) ActiveGreenLed.copy(alpha = 0.85f) else TextMuted,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SettingsUplinkWarpSection(
    config: com.mirrly.tgproxy.core.ProxyConfig,
    uplinkMode: com.mirrly.tgproxy.core.UplinkMode,
    warpProfile: com.mirrly.tgproxy.core.WarpProfile?,
    isRegisteringWarp: Boolean,
    onSelectUplinkMode: (com.mirrly.tgproxy.core.UplinkMode) -> Unit,
    onRefreshWarpAccount: () -> Unit,
    onOpenWarpDetails: () -> Unit,
    onInfoClick: (String) -> Unit,
    vlessUuid: String,
    onRegenerateVlessUuid: () -> Unit,
    onRestartProxy: () -> Unit = {}
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val app = MirrlyApplication.instance
    val devWorkers = remember { app.prefsManager.getDeveloperWorkers() }
    val customWorkers = remember { app.prefsManager.getCustomWorkers() }
    val allWorkers = remember(devWorkers, customWorkers) { devWorkers + customWorkers }
    val operaNodes = remember { com.mirrly.tgproxy.core.OperaVpnRepository.NODES }

    var useOperaForVless by remember { mutableStateOf(config.useOperaVpnForVless) }
    var useOperaForWarp by remember { mutableStateOf(config.useOperaVpnForWarp) }
    var activeOperaNodeId by remember { mutableStateOf(config.operaVpnNodeId) }
    var activeVlessDomain by remember { mutableStateOf(config.getEffectiveVlessDomain()) }

    Column(
        modifier = Modifier.staggeredEntrance(index = 4),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Section header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "РЕЖИМ АПЛИНКА (WARP & WORKER)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.3.sp,
                    color = TextMuted
                )
                InfoButton { onInfoClick("uplink_modes_info") }
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(ActiveGreenLed.copy(alpha = 0.12f))
                    .border(1.dp, ActiveGreenLed.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "BETA",
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Black,
                    color = ActiveGreenLed,
                    letterSpacing = 0.8.sp
                )
            }
        }

        if (!config.isSocks5Mode) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, AmoledBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(ActiveGreenLed.copy(alpha = 0.14f))
                            .border(1.dp, ActiveGreenLed.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "SOCKS5",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = ActiveGreenLed
                        )
                    }
                    Text(
                        text = "Аплинк туннелирует SOCKS5. Для MTProto используется прямое подключение.",
                        fontSize = 11.sp,
                        color = TextMuted,
                        lineHeight = 14.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Выбор режима аплинка
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                UplinkModeChip(
                    mode = com.mirrly.tgproxy.core.UplinkMode.WORKER,
                    displayName = "Worker WSS",
                    badge = "Базовый",
                    subtitle = "Cloudflare Edge",
                    isSelected = uplinkMode == com.mirrly.tgproxy.core.UplinkMode.WORKER,
                    onClick = { onSelectUplinkMode(com.mirrly.tgproxy.core.UplinkMode.WORKER) },
                    modifier = Modifier.weight(1f)
                )
                UplinkModeChip(
                    mode = com.mirrly.tgproxy.core.UplinkMode.VLESS,
                    displayName = "VLESS over WS",
                    badge = "WSS",
                    subtitle = "TLS 1.3 :443",
                    isSelected = uplinkMode == com.mirrly.tgproxy.core.UplinkMode.VLESS,
                    onClick = { onSelectUplinkMode(com.mirrly.tgproxy.core.UplinkMode.VLESS) },
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                UplinkModeChip(
                    mode = com.mirrly.tgproxy.core.UplinkMode.MASQUE,
                    displayName = "WARP MASQUE",
                    badge = "QUIC H3",
                    subtitle = "Anycast HTTP/3",
                    isSelected = uplinkMode == com.mirrly.tgproxy.core.UplinkMode.MASQUE,
                    onClick = { onSelectUplinkMode(com.mirrly.tgproxy.core.UplinkMode.MASQUE) },
                    modifier = Modifier.weight(1f)
                )
                UplinkModeChip(
                    mode = com.mirrly.tgproxy.core.UplinkMode.AWG,
                    displayName = "WARP AWG",
                    badge = "WireGuard",
                    subtitle = "Anycast обфускация",
                    isSelected = uplinkMode == com.mirrly.tgproxy.core.UplinkMode.AWG,
                    onClick = { onSelectUplinkMode(com.mirrly.tgproxy.core.UplinkMode.AWG) },
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                UplinkModeChip(
                    mode = com.mirrly.tgproxy.core.UplinkMode.WARP_CASCADE,
                    displayName = "WARP Cascade",
                    badge = "Dual Anycast",
                    subtitle = "MASQUE (HTTP/3) -> AWG",
                    isSelected = uplinkMode == com.mirrly.tgproxy.core.UplinkMode.WARP_CASCADE,
                    onClick = { onSelectUplinkMode(com.mirrly.tgproxy.core.UplinkMode.WARP_CASCADE) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Блок профиля Cloudflare WARP MASQUE
        AnimatedVisibility(
            visible = uplinkMode == com.mirrly.tgproxy.core.UplinkMode.MASQUE
                || uplinkMode == com.mirrly.tgproxy.core.UplinkMode.HYBRID
                || uplinkMode == com.mirrly.tgproxy.core.UplinkMode.AWG
                || uplinkMode == com.mirrly.tgproxy.core.UplinkMode.WARP_CASCADE,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, AmoledBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    val hasProfile = warpProfile != null && warpProfile.isWarpEnabled
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
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(if (hasProfile) ActiveGreenLed else InactiveGrayLed)
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Text(
                                        text = "Cloudflare WARP",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextWhite
                                    )
                                    val isPlus = config.isWarpPlus || warpProfile?.isWarpPlus == true
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = if (isPlus) ActiveGreenLed.copy(alpha = 0.15f) else Color.Transparent,
                                        border = BorderStroke(1.dp, if (isPlus) ActiveGreenLed.copy(alpha = 0.4f) else AmoledBorder)
                                    ) {
                                        Text(
                                            text = if (isPlus) "WARP+" else "FREE",
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Black,
                                            color = if (isPlus) ActiveGreenLed else TextMuted,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                    InfoButton { onInfoClick("warp_masque_info") }
                                }
                            }
                            Text(
                                text = if (hasProfile) "${warpProfile?.clientIpv4} • Anycast" else "Не зарегистрирован (авторегистрация)",
                                fontSize = 11.sp,
                                color = if (hasProfile) ActiveGreenLed else TextMuted
                            )
                        }

                        Button(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onRefreshWarpAccount()
                            },
                            enabled = !isRegisteringWarp,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ActiveGreenLed.copy(alpha = 0.12f),
                                contentColor = ActiveGreenLed
                            ),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, ActiveGreenLed.copy(alpha = 0.35f)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            if (isRegisteringWarp) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(13.dp),
                                    strokeWidth = 2.dp,
                                    color = ActiveGreenLed
                                )
                            } else {
                                Text(
                                    text = if (warpProfile != null) "Обновить" else "Создать",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // Быстрые действия: просмотр профиля + AmneziaWG конфиг
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (hasProfile) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color.Transparent,
                                border = BorderStroke(1.dp, AmoledBorder),
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onOpenWarpDetails()
                                    }
                            ) {
                                Text(
                                    text = "Детали регистрации",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextWhite,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(vertical = 7.dp)
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, AmoledBorder),
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    val awgConf = config.getAmneziaWgConfig()
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("AmneziaWG Config", awgConf))
                                    Toast.makeText(context, "Конфиг AmneziaWG скопирован", Toast.LENGTH_SHORT).show()
                                }
                        ) {
                            Text(
                                text = "AmneziaWG (QUIC I1)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = ActiveGreenLed,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 7.dp)
                            )
                        }
                    }

                    // Сетевые параметры узла
                    if (hasProfile) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, AmoledBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Anycast эндпоинт", fontSize = 10.5.sp, color = TextMuted)
                                    Text(
                                        "${warpProfile?.peerEndpoint} (ТСПУ Bypass)",
                                        fontSize = 10.5.sp,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = ActiveGreenLed
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Сертификат mTLS P-256", fontSize = 10.5.sp, color = TextMuted)
                                    Text(
                                        if (warpProfile?.clientCertBase64?.isNotBlank() == true) "Активен (X.509)" else "Базовый",
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (warpProfile?.clientCertBase64?.isNotBlank() == true) ActiveGreenLed else TextMuted
                                    )
                                }
                                val activeLic = config.warpLicenseKey.ifBlank { warpProfile?.licenseKey ?: "" }
                                if (activeLic.isNotBlank()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Лицензия WARP+", fontSize = 10.5.sp, color = TextMuted)
                                        Text(
                                            if (activeLic.length >= 14) activeLic.take(6) + "..." + activeLic.takeLast(6) else activeLic,
                                            fontSize = 10.5.sp,
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (config.isWarpPlus || warpProfile?.isWarpPlus == true) ActiveGreenLed else TextWhite
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Туннелирование WARP через Opera VPN Upstream
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, AmoledBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                    Text(
                                        text = "Opera VPN Upstream для WARP",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextWhite
                                    )
                                    Text(
                                        text = "Каскад: ТСПУ -> Opera VPN Anycast -> Cloudflare MASQUE",
                                        fontSize = 9.5.sp,
                                        color = TextMuted
                                    )
                                }
                                InertialSpringSwitch(
                                    checked = useOperaForWarp,
                                    onCheckedChange = { enabled ->
                                        useOperaForWarp = enabled
                                        config.useOperaVpnForWarp = enabled
                                        app.saveConfig()
                                        app.proxyServer.applyOperaVpnConfig()
                                        onRestartProxy()
                                    }
                                )
                            }

                            if (useOperaForWarp) {
                                Text(
                                    text = "ВЫБОР УЗЛА OPERA VPN",
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.8.sp,
                                    color = TextMuted
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    operaNodes.forEach { node ->
                                        val isSelected = node.id == activeOperaNodeId
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (isSelected) ActiveGreenLed.copy(alpha = 0.15f) else Color.Transparent,
                                            border = BorderStroke(1.dp, if (isSelected) ActiveGreenLed.copy(alpha = 0.5f) else AmoledBorder),
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable {
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    activeOperaNodeId = node.id
                                                    config.operaVpnNodeId = node.id
                                                    config.operaVpnEndpoint = node.endpoint
                                                    app.saveConfig()
                                                    app.proxyServer.applyOperaVpnConfig()
                                                    onRestartProxy()
                                                    Toast.makeText(context, "Узел Opera VPN: ${node.name}", Toast.LENGTH_SHORT).show()
                                                }
                                        ) {
                                            Column(modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)) {
                                                Text(
                                                    text = "${node.countryCode} (${node.name})",
                                                    fontSize = 10.sp,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                    color = if (isSelected) ActiveGreenLed else TextWhite
                                                )
                                                Text(
                                                    text = node.endpoint,
                                                    fontSize = 8.5.sp,
                                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                                    color = TextMuted
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

        // Блок параметров VLESS over WebSocket
        AnimatedVisibility(
            visible = uplinkMode == com.mirrly.tgproxy.core.UplinkMode.VLESS,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            val app = MirrlyApplication.instance
            val coroutineScope = rememberCoroutineScope()
            var vlessPresets by remember { mutableStateOf(com.mirrly.tgproxy.core.VlessPresetsRepository.getAllPresets()) }
            var activePresetId by remember { mutableStateOf(config.vlessPresetId) }
            var isFetchingPresets by remember { mutableStateOf(false) }
            var isDetailsExpanded by remember { mutableStateOf(false) }
            val chevronRotation by animateFloatAsState(
                targetValue = if (isDetailsExpanded) 90f else 0f,
                animationSpec = tween(durationMillis = 200),
                label = "vlessChevronRotation"
            )

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, AmoledBorder),
                modifier = Modifier.fillMaxWidth().animateContentSize()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                isDetailsExpanded = !isDetailsExpanded
                            },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(ActiveGreenLed.copy(alpha = 0.12f))
                                    .border(1.dp, ActiveGreenLed.copy(alpha = 0.35f), RoundedCornerShape(7.dp))
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_shield),
                                    contentDescription = null,
                                    tint = ActiveGreenLed,
                                    modifier = Modifier.size(15.dp)
                                )
                            }

                            Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "VLESS over WebSocket",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextWhite
                                    )
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(ActiveGreenLed.copy(alpha = 0.15f))
                                            .padding(horizontal = 4.dp, vertical = 1.dp)
                                    ) {
                                        Text(
                                            text = "WSS",
                                            fontSize = 8.5.sp,
                                            fontWeight = FontWeight.Black,
                                            color = ActiveGreenLed
                                        )
                                    }
                                    InfoButton { onInfoClick("vless_info") }
                                }
                                Text(
                                    text = "${config.getEffectiveVlessDomain()} • TLS 1.3",
                                    fontSize = 10.5.sp,
                                    color = ActiveGreenLed
                                )
                            }
                        }

                        Icon(
                            painter = painterResource(id = R.drawable.ic_chevron_right),
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier
                                .size(15.dp)
                                .graphicsLayer { rotationZ = chevronRotation }
                        )
                    }

                    // Выбор узла пресета VLESS
                    Text(
                        text = "ПУБЛИЧНЫЕ УЗЛЫ (PAGES & ANYCAST CDN)",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = TextMuted
                    )

                    // Горизонтальный список чипов с узлами
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        vlessPresets.forEach { preset ->
                            val isSelected = !useOperaForVless && (preset.id == activePresetId || (activePresetId.isEmpty() && preset.id == "cf_pages_global"))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) ActiveGreenLed.copy(alpha = 0.15f) else Color.Transparent,
                                border = BorderStroke(1.dp, if (isSelected) ActiveGreenLed.copy(alpha = 0.5f) else AmoledBorder),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        activePresetId = preset.id
                                        activeVlessDomain = preset.domain
                                        useOperaForVless = false
                                        config.useOperaVpnForVless = false
                                        config.vlessPresetId = preset.id
                                        com.mirrly.tgproxy.core.VlessPresetsRepository.applyPreset(preset, config)
                                        app.prefsManager.setVlessPresetId(preset.id)
                                        app.prefsManager.setVlessDomain(preset.domain)
                                        app.prefsManager.setVlessPath(preset.path)
                                        app.prefsManager.setVlessUuid(preset.uuid)
                                        app.prefsManager.setUseOperaVpnForVless(false)
                                        app.saveConfig()
                                        app.proxyServer.applyOperaVpnConfig()
                                        app.proxyServer.applyVlessPreset(preset)
                                        onRestartProxy()
                                        Toast.makeText(context, "Выбран узел Pages: ${preset.name}", Toast.LENGTH_SHORT).show()
                                    }
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)) {
                                    Text(
                                        text = preset.name,
                                        fontSize = 10.5.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) ActiveGreenLed else TextWhite
                                    )
                                    Text(
                                        text = preset.domain,
                                        fontSize = 9.sp,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                        color = TextMuted
                                    )
                                }
                            }
                        }
                    }

                    // Выбор Cloudflare Worker для VLESS
                    Text(
                        text = "ВОРКЕРЫ CLOUDFLARE ДЛЯ VLESS",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = TextMuted
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        allWorkers.forEach { worker ->
                            val isSelected = !useOperaForVless && (activeVlessDomain == worker.domain || (config.vlessDomain == worker.domain && activePresetId == "worker_${worker.id}"))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) ActiveGreenLed.copy(alpha = 0.15f) else Color.Transparent,
                                border = BorderStroke(1.dp, if (isSelected) ActiveGreenLed.copy(alpha = 0.5f) else AmoledBorder),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        activeVlessDomain = worker.domain
                                        activePresetId = "worker_${worker.id}"
                                        useOperaForVless = false
                                        config.useOperaVpnForVless = false
                                        config.vlessDomain = worker.domain
                                        config.vlessPresetId = "worker_${worker.id}"
                                        app.prefsManager.setVlessDomain(worker.domain)
                                        app.prefsManager.setVlessPresetId("worker_${worker.id}")
                                        app.prefsManager.setUseOperaVpnForVless(false)
                                        app.saveConfig()
                                        app.proxyServer.applyOperaVpnConfig()
                                        app.proxyServer.applyVlessConfig(config.vlessUuid, config.vlessPath, worker.domain)
                                        onRestartProxy()
                                        Toast.makeText(context, "Выбран воркер для VLESS: ${worker.name}", Toast.LENGTH_SHORT).show()
                                    }
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)) {
                                    Text(
                                        text = worker.name,
                                        fontSize = 10.5.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) ActiveGreenLed else TextWhite
                                    )
                                    Text(
                                        text = worker.domain,
                                        fontSize = 9.sp,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                        color = TextMuted
                                    )
                                }
                            }
                        }
                    }

                    // Серверы Opera VPN (VLESS Хостинг)
                    Text(
                        text = "СЕРВЕРЫ OPERA VPN (VLESS ХОСТИНГ)",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = TextMuted
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        operaNodes.forEach { node ->
                            val isSelected = useOperaForVless && (activePresetId == "opera_${node.id}" || activeOperaNodeId == node.id)
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) ActiveGreenLed.copy(alpha = 0.15f) else Color.Transparent,
                                border = BorderStroke(1.dp, if (isSelected) ActiveGreenLed.copy(alpha = 0.5f) else AmoledBorder),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        activeOperaNodeId = node.id
                                        activePresetId = "opera_${node.id}"
                                        activeVlessDomain = node.endpoint
                                        useOperaForVless = true
                                        config.useOperaVpnForVless = true
                                        config.operaVpnNodeId = node.id
                                        config.operaVpnEndpoint = node.endpoint
                                        config.vlessPresetId = "opera_${node.id}"
                                        config.vlessDomain = node.endpoint
                                        app.prefsManager.setUseOperaVpnForVless(true)
                                        app.prefsManager.setOperaVpnNodeId(node.id)
                                        app.prefsManager.setOperaVpnEndpoint(node.endpoint)
                                        app.prefsManager.setVlessPresetId("opera_${node.id}")
                                        app.prefsManager.setVlessDomain(node.endpoint)
                                        app.saveConfig()
                                        app.proxyServer.applyOperaVpnConfig()
                                        app.proxyServer.applyVlessConfig(config.vlessUuid, config.vlessPath, node.endpoint)
                                        onRestartProxy()
                                        Toast.makeText(context, "Выбран сервер Opera VPN: ${node.name}", Toast.LENGTH_SHORT).show()
                                    }
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)) {
                                    Text(
                                        text = "${node.countryCode} (${node.name})",
                                        fontSize = 10.5.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) ActiveGreenLed else TextWhite
                                    )
                                    Text(
                                        text = node.endpoint,
                                        fontSize = 9.sp,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                        color = TextMuted
                                    )
                                }
                            }
                        }
                    }

                    // Чип UUID клиента
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, AmoledBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("VLESS UUID", vlessUuid))
                                Toast.makeText(context, "UUID скопирован в буфер", Toast.LENGTH_SHORT).show()
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 6.dp)) {
                                Text("UUID клиента:", fontSize = 9.5.sp, color = TextMuted)
                                Text(
                                    text = vlessUuid,
                                    fontSize = 10.5.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    color = TextWhite,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                            Icon(
                                painter = painterResource(id = R.drawable.ic_copy),
                                contentDescription = "Копировать",
                                tint = ActiveGreenLed,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }

                    // Кнопки управления узлами
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, AmoledBorder),
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onRegenerateVlessUuid()
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 7.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_refresh),
                                    contentDescription = null,
                                    tint = ActiveGreenLed,
                                    modifier = Modifier.size(11.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "Новый UUID",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextWhite
                                )
                            }
                        }

                        // Импорт vless:// из буфера обмена
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, AmoledBorder),
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                    val clipText = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: ""
                                    if (clipText.startsWith("vless://", ignoreCase = true)) {
                                        val parseResult = com.mirrly.tgproxy.core.VlessPresetsRepository.parseVlessUriResult(clipText)
                                        when (parseResult) {
                                            is com.mirrly.tgproxy.core.VlessParseResult.Success -> {
                                                val parsed = parseResult.preset
                                                com.mirrly.tgproxy.core.VlessPresetsRepository.addCustomPreset(parsed)
                                                com.mirrly.tgproxy.core.VlessPresetsRepository.applyPreset(parsed, config)
                                                activePresetId = parsed.id
                                                config.vlessPresetId = parsed.id
                                                vlessPresets = com.mirrly.tgproxy.core.VlessPresetsRepository.getAllPresets()
                                                app.prefsManager.setVlessPresetId(parsed.id)
                                                app.prefsManager.setVlessDomain(parsed.domain)
                                                app.prefsManager.setVlessPath(parsed.path)
                                                app.prefsManager.setVlessUuid(parsed.uuid)
                                                app.saveConfig()
                                                app.proxyServer.applyVlessPreset(parsed)
                                                onRestartProxy()
                                                Toast.makeText(context, "Импортирован узел: ${parsed.name}", Toast.LENGTH_SHORT).show()
                                            }
                                            is com.mirrly.tgproxy.core.VlessParseResult.Failure -> {
                                                Toast.makeText(context, "Ошибка VLESS: ${parseResult.reason}", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    } else {
                                        Toast.makeText(context, "В буфере нет ссылки vless://", Toast.LENGTH_SHORT).show()
                                    }
                                }
                        ) {
                            Text(
                                text = "Импорт vless",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextWhite,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 7.dp)
                            )
                        }

                        // Обновить базу узлов из публичных репозиториев
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, AmoledBorder),
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = !isFetchingPresets) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    coroutineScope.launch {
                                        isFetchingPresets = true
                                        val fresh = com.mirrly.tgproxy.core.VlessPresetsRepository.fetchFreshPublicPresets(socks5Port = app.config.socks5Port)
                                        vlessPresets = fresh
                                        isFetchingPresets = false
                                        Toast.makeText(context, "Доступно узлов: ${fresh.size}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 7.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isFetchingPresets) {
                                    CircularProgressIndicator(modifier = Modifier.size(11.dp), strokeWidth = 1.5.dp, color = ActiveGreenLed)
                                } else {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_refresh),
                                        contentDescription = null,
                                        tint = ActiveGreenLed,
                                        modifier = Modifier.size(11.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = if (isFetchingPresets) "Загрузка..." else "Обновить",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextWhite
                                )
                            }
                        }

                        // Экспорт ссылки
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = ActiveGreenLed.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, ActiveGreenLed.copy(alpha = 0.35f)),
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    val vlessUrl = config.getVlessShareUrl()
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("VLESS URI", vlessUrl))
                                    Toast.makeText(context, "Ссылка vless:// скопирована в буфер", Toast.LENGTH_SHORT).show()
                                }
                        ) {
                            Text(
                                text = "Экспорт",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = ActiveGreenLed,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 7.dp)
                            )
                        }
                    }

                    // Раскрывающийся блок с техническими параметрами
                    AnimatedVisibility(
                        visible = isDetailsExpanded,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, AmoledBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Активный хост", fontSize = 10.5.sp, color = TextMuted)
                                    Text(config.getEffectiveVlessDomain(), fontSize = 10.5.sp, color = TextWhite, fontWeight = FontWeight.Medium)
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    val currentPreset = vlessPresets.firstOrNull { it.id == activePresetId }
                                    Text("Регион узла", fontSize = 10.5.sp, color = TextMuted)
                                    Text(currentPreset?.region ?: "Global Anycast CDN", fontSize = 10.5.sp, color = ActiveGreenLed, fontWeight = FontWeight.Medium)
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("WebSocket путь", fontSize = 10.5.sp, color = TextMuted)
                                    Text(config.vlessPath, fontSize = 10.5.sp, color = TextWhite, fontWeight = FontWeight.Medium)
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Транспортный порт", fontSize = 10.5.sp, color = TextMuted)
                                    Text("443 (HTTPS/WSS)", fontSize = 10.5.sp, color = TextWhite, fontWeight = FontWeight.Medium)
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Мимикрия браузера", fontSize = 10.5.sp, color = TextMuted)
                                    Text("Chrome 128", fontSize = 10.5.sp, color = ActiveGreenLed, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Блок авто-каскада обхода блокировок ТСПУ (Active Liveness Probe)
        val isLivenessEnabled = remember(config.isLivenessProbeEnabled) { mutableStateOf(config.isLivenessProbeEnabled) }
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color.Transparent,
            border = BorderStroke(1.dp, AmoledBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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
                            Text(
                                text = "Живая проба (Active Liveness)",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite
                            )
                            InfoButton { onInfoClick("liveness_probe_info") }
                        }
                        Text(
                            text = "Контрольный опрос Telegram DC2 (порт 443). При глушении ТСПУ (> 800мс) — мгновенный переход по каскаду",
                            fontSize = 11.sp,
                            color = TextMuted,
                            lineHeight = 14.sp
                        )
                    }

                    InertialSpringSwitch(
                        checked = isLivenessEnabled.value,
                        onCheckedChange = { enabled ->
                            isLivenessEnabled.value = enabled
                            config.isLivenessProbeEnabled = enabled
                            val appInstance = MirrlyApplication.instance
                            appInstance.prefsManager.setLivenessProbeEnabled(enabled)
                            appInstance.saveConfig()
                            appInstance.proxyServer.applyLivenessProbeConfig(enabled)
                        }
                    )
                }

                if (isLivenessEnabled.value) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = AmoledSurface,
                        border = BorderStroke(1.dp, AmoledBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Текущий транспорт каскада:",
                                    fontSize = 11.sp,
                                    color = TextMuted
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(ActiveGreenLed.copy(alpha = 0.12f))
                                        .border(1.dp, ActiveGreenLed.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    val currentStageName = MirrlyApplication.instance.proxyServer.stats.activeCascadeStage.ifEmpty { "Scanned WARP (Frag)" }
                                    Text(
                                        text = currentStageName,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ActiveGreenLed
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Цепочка переключения:",
                                    fontSize = 10.sp,
                                    color = TextMuted
                                )
                                Text(
                                    text = "IPv6 -> WARP (Frag) -> MASQUE -> VLESS",
                                    fontSize = 9.5.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    color = TextWhite
                                )
                            }
                        }
                    }
                }
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
    onOpenSchedule: () -> Unit,
    onInfoClick: (String) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val app = MirrlyApplication.instance
    var disableAnimations by remember { mutableStateOf(app.prefsManager.areAnimationsDisabled()) }
    val scheduleConfig = remember { app.prefsManager.loadScheduleConfig() }

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
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onOpenSchedule()
                }
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text("Расписание работы прокси", color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    text = if (scheduleConfig.isEnabled) "Включено • ${scheduleConfig.getSummaryText()}" else "Выключено • Запуск и остановка по времени",
                    color = if (scheduleConfig.isEnabled) ActiveGreenLed else TextMuted,
                    fontSize = 11.5.sp
                )
            }
            Icon(
                painter = painterResource(id = R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = if (scheduleConfig.isEnabled) ActiveGreenLed else TextMuted,
                modifier = Modifier.size(18.dp)
            )
        }

        // ── ЗАЩИТА АККУМУЛЯТОРА (BATTERY SAVER GUARD) ──
        var isBatteryGuardEnabled by remember { mutableStateOf(app.config.isBatteryGuardEnabled) }
        var batteryGuardThreshold by remember { mutableStateOf(app.config.batteryGuardThreshold) }
        var batteryGuardStopOnPowerSave by remember { mutableStateOf(app.config.batteryGuardStopOnPowerSave) }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Transparent)
                .border(1.dp, if (isBatteryGuardEnabled) ActiveGreenLed.copy(alpha = 0.35f) else AmoledBorder, RoundedCornerShape(18.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
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
                        Text("Защита аккумулятора", color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        InfoButton { onInfoClick("battery_guard") }
                    }
                    Text(
                        text = if (isBatteryGuardEnabled) "Автоотключение при заряде ниже $batteryGuardThreshold% или энергосбережении" else "Выключена • Автоматическое сохранение заряда батареи",
                        color = if (isBatteryGuardEnabled) ActiveGreenLed.copy(alpha = 0.85f) else TextMuted,
                        fontSize = 11.5.sp,
                        lineHeight = 15.sp
                    )
                }
                InertialSpringSwitch(
                    checked = isBatteryGuardEnabled,
                    onCheckedChange = { checked ->
                        isBatteryGuardEnabled = checked
                        app.config.isBatteryGuardEnabled = checked
                        app.saveConfig()
                    }
                )
            }

            AnimatedVisibility(
                visible = isBatteryGuardEnabled,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AmoledBorder))

                    // Threshold selector
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Порог отключения по заряду:",
                            color = TextWhite.copy(alpha = 0.9f),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(5, 10, 15, 20, 25).forEach { threshold ->
                                val isSelected = batteryGuardThreshold == threshold
                                val chipBorder by animateColorAsState(
                                    targetValue = if (isSelected) ActiveGreenLed else AmoledBorder,
                                    animationSpec = tween(200),
                                    label = "threshBorder_$threshold"
                                )
                                val chipBg by animateColorAsState(
                                    targetValue = if (isSelected) ActiveGreenLed.copy(alpha = 0.12f) else Color.Transparent,
                                    animationSpec = tween(200),
                                    label = "threshBg_$threshold"
                                )
                                val chipTextColor by animateColorAsState(
                                    targetValue = if (isSelected) ActiveGreenLed else TextWhite,
                                    animationSpec = tween(200),
                                    label = "threshText_$threshold"
                                )

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(chipBg)
                                        .border(1.dp, chipBorder, RoundedCornerShape(10.dp))
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null
                                        ) {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            batteryGuardThreshold = threshold
                                            app.config.batteryGuardThreshold = threshold
                                            app.saveConfig()
                                        }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "$threshold%",
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = chipTextColor
                                    )
                                }
                            }
                        }
                    }

                    // Power Save Mode trigger switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = "Отключать при энергосбережении Android",
                                color = TextWhite.copy(alpha = 0.9f),
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Срабатывает при активации системного режима экономии энергии",
                                color = TextMuted,
                                fontSize = 11.sp
                            )
                        }
                        InertialSpringSwitch(
                            checked = batteryGuardStopOnPowerSave,
                            onCheckedChange = { checked ->
                                batteryGuardStopOnPowerSave = checked
                                app.config.batteryGuardStopOnPowerSave = checked
                                app.saveConfig()
                            }
                        )
                    }
                }
            }
        }

        // ── АДАПТИВНЫЙ QOS И ТРОТТЛИНГ ──
        var isAdaptiveQoSEnabled by remember { mutableStateOf(app.config.isAdaptiveQoSEnabled) }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Transparent)
                .border(1.dp, if (isAdaptiveQoSEnabled) ActiveGreenLed.copy(alpha = 0.35f) else AmoledBorder, RoundedCornerShape(18.dp))
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Адаптивный QoS и троттлинг", color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    InfoButton { onInfoClick("adaptive_qos") }
                }
                Text(
                    text = if (isAdaptiveQoSEnabled) "Включен • Динамическое урезание пула сокетов и буферов при нагреве и энергосбережении" else "Выключен • Прокси работает на максимальной мощности без троттлинга",
                    color = if (isAdaptiveQoSEnabled) ActiveGreenLed.copy(alpha = 0.85f) else TextMuted,
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp
                )
            }
            InertialSpringSwitch(
                checked = isAdaptiveQoSEnabled,
                onCheckedChange = { checked ->
                    isAdaptiveQoSEnabled = checked
                    app.config.isAdaptiveQoSEnabled = checked
                    app.saveConfig()
                    app.proxyServer.setAdaptiveQoSEnabled(checked)
                }
            )
        }

        // ── ИСКЛЮЧЕНИЕ ИЗ ОПТИМИЗАЦИИ БАТАРЕИ (DOZE MODE) ──
        val context = LocalContext.current
        val powerManager = remember { context.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager }
        var isIgnoringBatteryOptimizations by remember {
            mutableStateOf(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
                } else {
                    true
                }
            )
        }

        val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        isIgnoringBatteryOptimizations = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
                    }
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Transparent)
                .border(
                    1.dp,
                    if (isIgnoringBatteryOptimizations) ActiveGreenLed.copy(alpha = 0.35f) else AmoledBorder,
                    RoundedCornerShape(18.dp)
                )
                .clickable {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        try {
                            val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            try {
                                val intent = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                Toast.makeText(context, "Не удалось открыть настройки энергосбережения", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Исключение из Doze Mode", color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    InfoButton { onInfoClick("doze_mode") }
                }
                Text(
                    text = if (isIgnoringBatteryOptimizations) {
                        "Разрешено • Защита от заморозки сокетов в глубоком сне активна"
                    } else {
                        "Оптимизируется системой • Нажмите для отключения ограничений Doze Mode"
                    },
                    color = if (isIgnoringBatteryOptimizations) ActiveGreenLed.copy(alpha = 0.85f) else TextMuted,
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp
                )
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (isIgnoringBatteryOptimizations) ActiveGreenLed.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.05f),
                border = BorderStroke(1.dp, if (isIgnoringBatteryOptimizations) ActiveGreenLed.copy(alpha = 0.4f) else AmoledBorder)
            ) {
                Text(
                    text = if (isIgnoringBatteryOptimizations) "Активно" else "Настроить",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isIgnoringBatteryOptimizations) ActiveGreenLed else TextWhite,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                )
            }
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
                .border(1.dp, AmoledBorder, RoundedCornerShape(18.dp))
        ) {
            // ── 1. STANDOUT VOLUNTEER TESTING BUTTON ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .lightSweep(
                        isEnabled = true,
                        shape = RoundedCornerShape(14.dp),
                        sweepColor = ActiveGreenLed
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
                            .background(ActiveGreenLed.copy(alpha = 0.12f))
                            .border(1.dp, ActiveGreenLed.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_volunteer_badge),
                            contentDescription = null,
                            tint = ActiveGreenLed,
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
                                color = ActiveGreenLed.copy(alpha = 0.15f),
                                border = androidx.compose.foundation.BorderStroke(0.8.dp, ActiveGreenLed.copy(alpha = 0.4f))
                            ) {
                                Text(
                                    text = "НАБОР",
                                    fontSize = 8.5.sp,
                                    fontWeight = FontWeight.Black,
                                    color = ActiveGreenLed,
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
                    tint = ActiveGreenLed,
                    modifier = Modifier.size(16.dp)
                )
            }

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AmoledBorder))

            // ── 2. STANDOUT HALL OF FAME BUTTON ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .lightSweep(
                        isEnabled = true,
                        shape = RoundedCornerShape(14.dp),
                        sweepColor = ActiveGreenLed
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
                            .background(ActiveGreenLed.copy(alpha = 0.12f))
                            .border(1.dp, ActiveGreenLed.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_hall_of_fame),
                            contentDescription = null,
                            tint = ActiveGreenLed,
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
                                color = ActiveGreenLed.copy(alpha = 0.15f),
                                border = androidx.compose.foundation.BorderStroke(0.8.dp, ActiveGreenLed.copy(alpha = 0.4f))
                            ) {
                                Text(
                                    text = "TOP",
                                    fontSize = 8.5.sp,
                                    fontWeight = FontWeight.Black,
                                    color = ActiveGreenLed,
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
                    tint = ActiveGreenLed,
                    modifier = Modifier.size(16.dp)
                )
            }

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AmoledBorder))
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

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AmoledBorder))

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

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AmoledBorder))

            val itemBgColor = if (isUpdateAvailable) ActiveGreenLed.copy(alpha = 0.08f) else Color.Transparent
            val iconBoxBgColor = if (isUpdateAvailable) ActiveGreenLed.copy(alpha = 0.2f) else ActiveGreenLed.copy(alpha = 0.12f)
            val iconTint = ActiveGreenLed
            val titleColor = if (isUpdateAvailable) ActiveGreenLed else TextWhite

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
                        tint = if (isUpdateAvailable) ActiveGreenLed else TextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsInfoDialog(infoKey: String, onDismiss: () -> Unit) {
    val (dlgTitle, dlgBody) = when (infoKey) {
        "uplink_modes_info" -> "Режимы аплинка (Транспорт и обход ТСПУ)" to
            "АРХИТЕКТУРА АПЛИНКА В MIRRLY:\n" +
            "• Аплинк (Upstream Tunnel) определяет физический и криптографический сетевой маршрут от локального SOCKS5-прокси (127.0.0.1:10808) до серверов Telegram через цензурные шлюзы ТСПУ/DPI.\n" +
            "• Трафик мессенджера инкапсулируется в современные протоколы с маскировкой под обычный веб-серфинг или туннелируется через Anycast-инфраструктуру Cloudflare.\n" +
            "• В отличие от системного VPN, Mirrly работает как локальный transparent TCP-релей без создания TUN-интерфейса, что исключает утечки DNS, батарейный голод и системные ограничения Android.\n\n" +
            "1. CLOUDFLARE WORKER (WEBSOCKET OVER TLS 1.3):\n" +
            "• Принцип работы: трафик передается по защищенному WebSocket-туннелю (порт 443) к бессерверной V8-функции в сети Cloudflare Edge. Воркер использует внутренний сокетный API (cloudflare:sockets) для прямого TCP-подключения к дата-центрам Telegram.\n" +
            "• Обход блокировок: оператор связи и оборудование ТСПУ видят стандартный HTTPS-запрос с доверенным TLS 1.3 сертификатом глобального CDN, что исключает блокировку по протокольным сигнатурам.\n" +
            "• Особенности: бесплатный лимит Cloudflare составляет 100 000 вызовов в сутки. Благодаря мультиплексированию (передаче тысяч пакетов внутри одного открытого сокета) данный лимит практически невозможно исчерпать в повседневном использовании.\n\n" +
            "2. CLOUDFLARE WARP (MASQUE / RFC 9484):\n" +
            "• Принцип работы: передовой стандарт IETF CONNECT-IP / CONNECT-UDP поверх протоколов HTTP/3 и QUIC. Прокси связывается с Anycast-шлюзами Cloudflare (188.114.96.1) через нативные учетные данные WireGuard и сертификаты mTLS (ECDSA P-256).\n" +
            "• Преимущества: полное отсутствие блокировки начала очереди (Head-of-Line Blocking), минимальные задержки (RTT) и высочайшая стабильность при голосовых звонках и видеосвязи.\n" +
            "• Устойчивость к ТСПУ: поддерживает автоматический подбор незаблокированных портов Anycast (500, 8095, 8443, 1074, 443) и фрагментацию первого пакета рукопожатия (Noise Handshake Fragmentation).\n\n" +
            "3. VLESS OVER WEBSOCKET (ANYCAST PAGES CDN & ПОДПИСКИ):\n" +
            "• Принцип работы: компактный бинарный протокол VLESS v0 в связке с транспортом WebSocket (порт 443) и шифрованием TLS 1.3. Соединение мимикрирует под обычный браузерный трафик Google Chrome.\n" +
            "• Доступность без регистрации: не требует создания аккаунта Cloudflare, настройки API-токенов или развертывания личных воркеров.\n" +
            "• Автоматический пул и зеркала: приложение включает пул стабильных Anycast-узлов в Европе (Амстердам, Франкфурт) и Азии (Сингапур), а также потоковый загрузчик публичных зеркал подписок с проверкой валидности и гонкой Happy Eyeballs в нативном Rust-ядре.\n\n" +
            "4. ГИБРИДНЫЙ РЕЖИМ (КАСКАД WORKER + WARP):\n" +
            "• Принцип работы: двухзвенное туннелирование. Смартфон подключается к доверенному Cloudflare Worker по зашифрованному WSS, а воркер перенаправляет поток в Anycast-сеть WARP к целевому Telegram DC.\n" +
            "• Преимущества: решает проблему блокировки UDP-трафика и прямого доступа к IP-диапазонам WARP операторами связи. Узел ТСПУ видит лишь легитимный HTTPS-трафик к Cloudflare Worker.\n\n" +
            "КАСКАДНОЕ ПЕРЕКЛЮЧЕНИЕ (ACTIVE LIVENESS FAILOVER):\n" +
            "• Встроенный модуль активного мониторинга туннеля с интервалом 15 секунд контролирует доступность серверов Telegram.\n" +
            "• В случае деградации сессии или сброса RST со стороны ТСПУ происходит бесшовный автоматический переход:\n" +
            "IPv6 Anycast WARP -> Scanned WARP (Noise Frag) -> MASQUE (HTTP/3) -> VLESS Anycast CDN."

        "warp_masque_info" -> "Туннель Cloudflare WARP & MASQUE" to
            "ПРИНЦИП РАБОТЫ И СТАНДАРТ MASQUE:\n" +
            "• Протокол MASQUE (Multiplexing Application Substrate over QUIC Encryption, RFC 9484 / RFC 9298) позволяет мультиплексировать IP-пакеты и UDP-дейтаграммы поверх защищенного транспортного уровня HTTP/3 и QUIC.\n" +
            "• Трафик маршрутизируется через ближайший по задержке Anycast-сервер Cloudflare Edge.\n\n" +
            "АВТОМАТИЧЕСКАЯ РЕГИСТРАЦИЯ И КРИПТОГРАФИЯ:\n" +
            "• При создании профиля приложение обращается к API регистрации Cloudflare через защищенный обфусцированный HTTP-клиент.\n" +
            "• Генерируется ключевая пара эллиптической кривой Curve25519 / ECDSA P-256 и выпускается персональный клиентский X.509 сертификат взаимной аутентификации (mTLS).\n" +
            "• Устройству выделяется индивидуальный внутренний IPv4 (172.16.0.2) и IPv6 (/128) адрес.\n\n" +
            "ОБХОД БЛОКИРОВОК И ФРАГМЕНТАЦИЯ ПАКЕТОВ:\n" +
            "• Системы ТСПУ фильтруют стандартные заголовки рукопожатия WireGuard. Для обхода блокировок ядро Mirrly применяет фрагментацию первого пакета: разделение инициализирующего сообщения на части менее MTU с задержкой отправки хвоста.\n" +
            "• Встроенный сканер конечных точек автоматически подбирает рабочий Anycast-порт среди 8095, 8443, 500, 1074 и 443.\n\n" +
            "ЭКСПОРТ В AMNEZIAWG:\n" +
            "• При необходимости параметры сгенерированного профиля (включая мусорные заголовки Init Packet Magic Header I1) можно скопировать в буфер обмена для использования в сторонних клиентах AmneziaWG."

        "vless_info" -> "Протокол VLESS & Публичные Anycast-узлы" to
            "ОСОБЕННОСТИ ПРОТОКОЛА VLESS:\n" +
            "• VLESS — это легковесный транспортный протокол нулевой версии (v0) без избыточного внутреннего шифрования (encryption=none).\n" +
            "• Вся криптографическая защита возлагается на внешний слой TLS 1.3 с маскировкой под стандартный браузерный трафик (Chrome utls-mimic) на порту 443.\n\n" +
            "РАБОТА ЧЕРЕЗ CLOUDFLARE PAGES:\n" +
            "• В отличие от воркеров, узлы Cloudflare Pages развернуты на глобальной статической Anycast CDN-инфраструктуре Cloudflare.\n" +
            "• Домены вида *.pages.dev обладают высоким доверием в сетевых фильтрах и не вызывают подозрений у оборудования DPI.\n\n" +
            "ДИНАМИЧЕСКИЙ ПУЛ И ГОНКА HAPPY EYEBALLS:\n" +
            "• В нативном Rust-движке (mirrlyengine) реализован параллельный опрос узлов. При сбое основного сервера прокси мгновенно подбирает альтернативный живой узел из пула без разрыва сессии Telegram.\n" +
            "• Потоковый загрузчик автоматически синхронизирует актуальные узлы из проверенных зеркал подписок GitHub с жесткой фильтрацией (поддерживаются только чистые WSS over TLS узлы; несовместимые с WSS протоколы Reality, gRPC и raw TCP отсекаются при парсинге).\n" +
            "• Все проверенные узлы кэшируются в локальной энергонезависимой памяти устройства."

        "port" -> "Локальные порты прокси" to
            "НАЗНАЧЕНИЕ ПОРТОВ:\n" +
            "• Локальный порт прослушивания — это сетевой сокет (127.0.0.1) на вашем устройстве, к которому подключается клиент Telegram.\n" +
            "• Порт 1080: стандартный порт режима MTProto (нативный протокол Telegram с Fake-TLS).\n" +
            "• Порт 10808: стандартный порт режима SOCKS5 (универсальный прозрачный TCP-релей).\n\n" +
            "РЕКОМЕНДАЦИИ ПО НАСТРОЙКЕ:\n" +
            "• Рекомендованные порты: 1080 (MTProto) и 10808 (SOCKS5). Они не требуют прав root и стандартизированы для прокси-серверов.\n" +
            "• Допустимый диапазон: от 1024 до 65535.\n" +
            "• Если указанный порт занят другой службой на устройстве, ядро прокси не сможет запустить слушатель (Bind Error).\n" +
            "• При изменении порта не забудьте обновить параметры прокси в Telegram по кнопке «Подключить Telegram»."

        "secret" -> "Секретный ключ MTProto (Fake-TLS)" to
            "СТРУКТУРА И КРИПТОГРАФИЯ КЛЮЧА:\n" +
            "• 32-символьный шестнадцатеричный (Hex) ключ используется клиентом Telegram для аутентификации и шифрования заголовков сессии MTProto.\n" +
            "• Префикс «dd»: активирует защитный режим Fake-TLS.\n" +
            "• При Fake-TLS первое сообщение рукопожатия мессенджера маскируется под ClientHello стандартного протокола TLS 1.3 с легитимным доменным именем (SNI).\n\n" +
            "ГЕНЕРАЦИЯ И ПРИМЕНЕНИЕ:\n" +
            "• Нажмите на значок генерации для создания криптографически стойкого случайного ключа.\n" +
            "• После генерации нового ключа обязательно обновите ссылку прокси в Telegram."

        "cf_domain" -> "Cloudflare Worker & Персональный домен" to
            "АРХИТЕКТУРА И БЕЗОПАСНОСТЬ:\n" +
            "• Cloudflare Worker выполняет роль распределенного релея между прокси и серверами Telegram через сокетный интерфейс cloudflare:sockets.\n" +
            "• Весь трафик шифруется на всем пути следования. Провайдер связи видит исключительно обращение к защищенному CDN-домену по порту 443.\n\n" +
            "РАЗВЕРТЫВАНИЕ ЛИЧНОГО ВОРКЕРА:\n" +
            "• Использование личного бесплатного воркера гарантирует максимальную стабильность, отсутствие посторонней нагрузки и полную конфиденциальность.\n" +
            "• Бесплатный план Cloudflare предоставляет 100 000 запросов в день на каждый персональный аккаунт.\n" +
            "• Добавленный личный домен имеет наивысший приоритет в системе маршрутизации Mirrly."

        "autostart" -> "Автозапуск службы при загрузке устройства" to
            "ПРИНЦИП РАБОТЫ:\n" +
            "• Служба регистрирует системный широковещательный ресивер события BOOT_COMPLETED.\n" +
            "• После включения или перезагрузки смартфона прокси автоматически поднимает локальный сокет без необходимости ручного открытия приложения.\n\n" +
            "ОСОБЕННОСТИ НАСТРОЙКИ НА РАЗНЫХ ПРОШИВКАХ:\n" +
            "• Xiaomi (MIUI / HyperOS):\n" +
            "Необходимо открыть «Настройки -> Приложения -> Разрешения -> Автозапуск» и разрешить автозапуск Mirrly TG Proxy, а также перевести контроль фоновой активности в режим «Нет ограничений».\n\n" +
            "• Samsung (One UI):\n" +
            "В разделе «Батарея -> Ограничения в фоновом режиме» добавьте приложение в список «Никогда не спящие приложения».\n\n" +
            "• Huawei / Honor (EMUI / MagicOS):\n" +
            "В настройках «Запуск приложений» переведите управление в ручной режим и отметьте «Автозапуск», «Косвенный запуск» и «Работа в фоновом режиме»."

        "preset" -> "Пропускная способность и пул сокетов (WsPool)" to
            "ТЕХНОЛОГИЯ WEBSOCKET PRE-WARMING:\n" +
            "• Для устранения задержек при открытии чатов и загрузке медиа нативное ядро Mirrly поддерживает предварительно открытый пул сокетов (Pre-Warming Pool) и кольцевых буферов.\n\n" +
            "ДОСТУПНЫЕ ПРОФИЛИ:\n" +
            "• Эко (2 сокета, буфер 128 КБ):\n" +
            "Минимальная нагрузка на процессор, память (~150 КБ RAM) и батарею. Рекомендуется для слабых устройств и фонового чтения текстовых каналов.\n\n" +
            "• Баланс (4 сокета, буфер 256 КБ, стандарт):\n" +
            "Оптимальное соотношение скорости загрузки медиафайлов и энергопотребления для повседневного использования.\n\n" +
            "• Турбо (8 сокетов, буфер 1 МБ):\n" +
            "Ускоренная предзагрузка фотографий, голосовых сообщений и моментальный отклик интерфейса Telegram при быстром скролле ленты.\n\n" +
            "• Ультра (16 сокетов, буфер 2 МБ):\n" +
            "Максимальная параллелизация потоков данных. Обеспечивает предельную скорость при скачивании архивов и просмотре 4K-видео на скоростных Wi-Fi и 5G-сетях.\n\n" +
            "• Авто (2–16 сокетов, адаптивная память):\n" +
            "Интеллектуальная система управления QoS: автоматически расширяет пул при передаче тяжелых файлов и сужает его до 2 сокетов при простое и низкой активности экрана."

        "tcp_nodelay" -> "Управление алгоритмом Нагла (TCP_NODELAY)" to
            "ПРИНЦИП РАБОТЫ АЛГОРИТМА НАГЛА (RFC 896):\n" +
            "• По умолчанию стек TCP объединяет короткие порции данных в более крупные пакеты перед отправкой, ожидая подтверждения (ACK) от удаленного узла.\n" +
            "• Это снижает сетевые накладные расходы, но приводит к искусственной задержке интерактивных данных (до 200–500 мс).\n\n" +
            "РЕЖИМЫ РАБОТЫ В MIRRLY:\n" +
            "• Авто (рекомендуется):\n" +
            "Интеллектуальный контроль задержки. Флаг TCP_NODELAY активируется автоматически при стабильном канале (RTT < 140 мс) для мгновенной реакции чатов. При деградации радиоканала или высоком джиттере включается склеивание пакетов для защиты от перегрузки модема.\n\n" +
            "• Включено (Мгновенная отдача):\n" +
            "Флаг TCP_NODELAY принудительно включен на всех сокетах. Пакеты уходят в сеть немедленно. Минимизирует время отправки сообщений, но может незначительно увеличивать расход энергии модема при слабом сигнале.\n\n" +
            "• Выключено (Экономия и склеивание):\n" +
            "Пакеты группируются операционной системой. Рекомендуется только для крайне медленных 2G/EDGE каналов связи."

        "disable_animations" -> "Энергосбережение и отключение визуальных эффектов" to
            "ОПТИМИЗАЦИЯ ДЛЯ AMOLED И БЮДЖЕТНЫХ УСТРОЙСТВ:\n" +
            "• Полное отключение отрисовки частиц фонового Canvas-оверлея (CyberParticlesOverlay).\n" +
            "• Минимизация количества фаз перерисовки Compose-интерфейса.\n" +
            "• Снижение энергопотребления на высокогерцовых OLED/AMOLED экранах (90–120 Гц) и продление времени автономной работы устройства при открытом приложении."

        "protocols_info" -> "Протоколы подключения Telegram" to
            "СРАВНЕНИЕ РЕЖИМОВ РАБОТЫ:\n" +
            "• Приложение поддерживает два независимых режима работы прокси: MTProto и SOCKS5. Одновременно может быть активен только один из них.\n\n" +
            "1. MTPROTO (ПОРТ 1080):\n" +
            "• Нативный протокол мессенджера Telegram.\n" +
            "• Использует криптографический режим Fake-TLS (префикс dd) со случайным 32-символьным ключом, эмулируя безопасный сеанс HTTPS.\n" +
            "• Трафик маршрутизируется через распределенные Anycast-узлы Flowseal CDN с аппаратным кэшированием медиафайлов.\n" +
            "• Ссылка для подключения: tg://proxy?server=127.0.0.1&port=1080&secret=dd...\n\n" +
            "2. SOCKS5 (ПОРТ 10808):\n" +
            "• Прозрачный TCP-релей по стандарту RFC 1928.\n" +
            "• Мессенджер самостоятельно шифрует весь пользовательский трафик сквозным шифрованием.\n" +
            "• Поддерживает не только текстовые чаты и каналы, но и полноценные голосовые и видеозвонки Telegram без задержек.\n" +
            "• Поддерживает гибкое переключение аплинков (Cloudflare Worker, WARP MASQUE, VLESS over WSS, Гибрид).\n" +
            "• Ссылка для подключения: tg://socks?server=127.0.0.1&port=10808"

        "socks5_auth" -> "Аутентификация SOCKS5 (RFC 1929)" to
            "ЗАЩИТА ЛОКАЛЬНОГО ИНТЕРФЕЙСА:\n" +
            "• По умолчанию локальный сокет 127.0.0.1:10808 доступен всем процессам Android на устройстве.\n" +
            "• Задание логина и пароля активирует обязательную аутентификацию по RFC 1929 (Username/Password Authentication).\n" +
            "• Любое стороннее приложение, попытавшееся отправить трафик через локальный порт Mirrly без знания учетных данных, получит отказ в доступе.\n\n" +
            "УДОБСТВО ИНТЕГРАЦИИ:\n" +
            "• При клике на кнопку «Подключить Telegram» или копировании конфигурации логин и пароль автоматически добавляются в URI формат: tg://socks?server=127.0.0.1&port=10808&user=...&pass=..."

        "battery_guard" -> "Защита батареи и температурный контроль" to
            "МНОГОУРОВНЕВАЯ СИСТЕМА ЭНЕРГОСБЕРЕЖЕНИЯ:\n" +
            "• Контроль минимального заряда:\n" +
            "Вы можете задать порог отключения от 5% до 25%. Если заряд батареи опускается ниже выбранной отметки, служба отправляет предупреждающее уведомление в шторку и запускает 5-минутный таймер до автоотключения сокетов.\n\n" +
            "• Отмена автоотключения в один клик:\n" +
            "В уведомлении доступна кнопка «Отменить», позволяющая продолжить непрерывную работу прокси без отключения.\n\n" +
            "• Интеграция с режимом энергосбережения:\n" +
            "При включении системного режима экономии энергии Android прокси также предупреждает пользователя за 5 минут до плановой остановки.\n\n" +
            "• Защита при зарядке:\n" +
            "При подключении устройства к зарядному устройству таймер отсчета автоматически отменяется, а защитные ограничения снимаются.\n\n" +
            "• Термальный контроль (Thermal QoS):\n" +
            "При нагреве батареи выше 42°C движок снижает размер пула сокетов и уменьшает интенсивность фоновых диагностических замеров."

        "adaptive_qos" -> "Адаптивный QoS и динамический троттлинг" to
            "УПРАВЛЕНИЕ ПРОИЗВОДИТЕЛЬНОСТЬЮ И ТРОТТЛИНГОМ:\n" +
            "• Адаптивное управление качеством обслуживания (QoS):\n" +
            "При включенном режиме движок непрерывно отслеживает температуру устройства и статус энергосбережения. При перегреве или низком заряде автоматически снижается размер пула сокетов и буферов для защиты аккумулятора.\n\n" +
            "• Режим максимальной производительности (Отключено):\n" +
            "При выключении опции любые искусственные замедления, урезания пула сокетов и буферов при разряде батареи и режиме энергосбережения полностью отключаются. Прокси работает на полной мощности с максимальным размером пула (16) и буфера (2 МБ).\n\n" +
            "• Аппаратная безопасность:\n" +
            "Даже при выключенном троттлинге защита от критического перегрева чипа (THERMAL_STATUS_CRITICAL) сохраняется на уровне ядра для предотвращения деградации батареи и повреждения аппаратных компонентов устройства."

        "doze_mode" -> "Исключение из режима энергосбережения (Doze Mode)" to
            "РЕЖИМ ГЛУБОКОГО СНА ANDROID (DOZE MODE):\n" +
            "• Начиная с Android 6.0 (API 23), система погружает устройство в режим глубокого сна (Doze Mode), если оно лежит неподвижно с выключенным экраном.\n" +
            "• В режиме Doze операционная система отключает доступ к сети для фоновых процессов, замораживает сокеты и откладывает сетевые синхронизации.\n\n" +
            "ЗАЧЕМ НУЖНО ИСКЛЮЧЕНИЕ:\n" +
            "• При нахождении в спящем режиме более 1–2 часов Android может разорвать фоновые соединения прокси с серверами Telegram.\n" +
            "• Добавление Mirrly TG Proxy в список исключений из оптимизации батареи позволяет приложению удерживать сетевые сокеты активными для мгновенного приема входящих звонков и уведомлений без задержек.\n\n" +
            "ВЛИЯНИЕ НА АВТОНОМНОСТЬ:\n" +
            "• Нативное ядро прокси оптимизировано на уровне C++/Rust и практически не потребляет процессорное время в режиме ожидания (~0.1% заряда за сутки).\n" +
            "• Исключение из оптимизации не приводит к повышенному разряду аккумулятора."

        "doh_providers" -> "Резолвер DNS-over-HTTPS (Параллельная DoH Гонка)" to
            "ТЕХНОЛОГИЯ ПАРАЛЛЕЛЬНОЙ ГОНКИ (DOH RACE):\n" +
            "• Запросы на определение IP-адресов серверов Telegram и доменов воркеров отправляются параллельно по протоколу HTTP/2 с шифрованием TLS 1.3 всем включенным серверам одновременно.\n" +
            "• Сервер, ответивший первым без ошибок, становится победителем. Все оставшиеся параллельные сокеты немедленно прерываются.\n" +
            "• Результат кэшируется в локальном LRU-кэше на время жизни записи (TTL), снижая задержку повторных обращений до 0 мс.\n\n" +
            "СМАРТ-БЕНЧМАРК И ЗАЩИТА ОТ DNS-ОТРАВЛЕНИЯ:\n" +
            "• Интеллектуальный бенчмарк тестирует каждый сервер на сетевую доступность, задержку RTT и валидность выданных IP-адресов.\n" +
            "• Адреса проверяются на принадлежность официальной автономной системе Telegram (AS44907). Любые попытки подмены адресов на цензурные заглушки РКН отсекаются на корню.\n\n" +
            "РЕКОМЕНДОВАННЫЕ ПРОВАЙДЕРЫ:\n" +
            "• AdGuard, DNS.SB, NextDNS, Control D и Quad9: демонстрируют наивысшую стабильность в сетях операторов РФ и не подвержены фильтрации.\n" +
            "• Серверы Cloudflare и Google по умолчанию отключены, так как их стандартные IP-адреса нередко подвергаются замедлению со стороны мобильных операторов."

        "liveness_probe_info" -> "Активный контроль туннеля (Active Liveness Probe)" to
            "ПРИНЦИП НЕПРЕРЫВНОГО МОНИТОРИНГА:\n" +
            "• Через активный туннель посылаются легковесные контрольные TCP-зонды к серверам Telegram DC (порт 443) каждые 15 секунд при включенном экране и каждые 45 секунд в фоновом режиме.\n" +
            "• Зонд эмулирует проверку физической сквозной доступности канала без создания значимой сетевой нагрузки.\n\n" +
            "ДЕТЕКЦИЯ БЛОКИРОВОК И ПЕРЕХОД ПО КАСКАДУ:\n" +
            "• Если контрольный зонд не получает ответа в течение 800 мс или дважды фиксирует сброс пакетов, система регистрирует блокировку со стороны ТСПУ и немедленно переключает сетевой транспорт:\n" +
            "IPv6 Anycast WARP -> Scanned WARP (Noise Frag) -> MASQUE (HTTP/3) -> VLESS Anycast CDN.\n\n" +
            "ПАССИВНАЯ ЭКОНОМИЯ ЭНЕРГИИ:\n" +
            "• Если скорость пользовательского трафика превышает 2 КБ/с (вы переписываетесь, слушаете аудио или качаете медиа), синтетические контрольные зонды автоматически пропускаются для сбережения аккумулятора."

        else -> return
    }
    InfoDialog(title = dlgTitle, body = dlgBody, onDismiss = onDismiss)
}

private data class DohBadgeStyle(
    val bg: Color,
    val border: Color,
    val text: Color,
    val label: String
)

@Composable
private fun SettingsDohSection(
    enabledProviderIds: Set<String>,
    isProxyRunning: Boolean,
    onStopProxy: () -> Unit,
    onSelectAllProviders: () -> Unit,
    onToggleProvider: (String, Boolean) -> Unit,
    onResetDefaults: () -> Unit,
    isBenchmarking: Boolean,
    benchmarkProgress: Pair<Int, Int>?,
    benchmarkResults: Map<String, com.mirrly.tgproxy.core.DohBenchmarkResult>,
    benchmarkSummary: String?,
    onStartBenchmark: () -> Unit,
    onInfoClick: (String) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val allProviders = remember { com.mirrly.tgproxy.core.DohResolver.ALL_PROVIDERS }
    var isListExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(isBenchmarking) {
        if (isBenchmarking) {
            isListExpanded = true
        }
    }

    Column(
        modifier = Modifier.staggeredEntrance(index = 2),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Заголовок секции с быстрыми кнопками управления
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "DNS-OVER-HTTPS (DOH)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.3.sp,
                    color = TextMuted
                )
                InfoButton { onInfoClick("doh_providers") }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Кнопка включения всех серверов
                if (enabledProviderIds.size < allProviders.size) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = ActiveGreenLed.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, ActiveGreenLed.copy(alpha = 0.35f)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(
                                enabled = !isProxyRunning,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onSelectAllProviders()
                            }
                    ) {
                        Text(
                            text = "Все 14",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isProxyRunning) TextMuted else ActiveGreenLed,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = ActiveGreenLed.copy(alpha = 0.10f),
                        border = BorderStroke(1.dp, ActiveGreenLed.copy(alpha = 0.30f))
                    ) {
                        Text(
                            text = "Все 14 OK",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = ActiveGreenLed,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                // Кнопка сброса настроек по умолчанию
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, AmoledBorder),
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(
                            enabled = !isProxyRunning,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onResetDefaults()
                        }
                ) {
                    Text(
                        text = "Сброс",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isProxyRunning) TextMuted else TextWhite.copy(alpha = 0.85f),
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
            }
        }

        // Предупреждение о блокировке изменения параметров при активном прокси
        if (isProxyRunning) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, AmoledBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Прокси активен • Для смены узлов остановите службу",
                        fontSize = 11.sp,
                        color = TextMuted,
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    )

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFFEF4444).copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.40f)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onStopProxy()
                            }
                    ) {
                        Text(
                            text = "Остановить",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFEF4444),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        }

        // Компактная плашка умного автоподбора
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color.Transparent,
            border = BorderStroke(
                1.dp,
                if (isBenchmarking) ActiveGreenLed.copy(alpha = 0.6f)
                else if (isProxyRunning) AmoledBorder
                else ActiveGreenLed.copy(alpha = 0.35f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(
                    enabled = !isBenchmarking && !isProxyRunning,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onStartBenchmark()
                }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = if (isBenchmarking) {
                            val cur = benchmarkProgress?.first ?: 0
                            val tot = benchmarkProgress?.second ?: allProviders.size
                            "Тестирование: $cur из $tot..."
                        } else {
                            "Умный автоподбор DNS"
                        },
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isProxyRunning) TextMuted else ActiveGreenLed
                    )
                    Text(
                        text = if (isBenchmarking) "Замер RTT и обход ТСПУ"
                               else if (isProxyRunning) "Остановите прокси для замера"
                               else "Автовыбор самых быстрых незаблокированных узлов",
                        fontSize = 10.5.sp,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }

                if (isBenchmarking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = ActiveGreenLed
                    )
                } else {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isProxyRunning) Color.Transparent else ActiveGreenLed.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, if (isProxyRunning) AmoledBorder else ActiveGreenLed.copy(alpha = 0.35f))
                    ) {
                        Text(
                            text = "Тест",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isProxyRunning) TextMuted else ActiveGreenLed,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        }

        // Информационный баннер результатов автоподбора
        AnimatedVisibility(
            visible = benchmarkSummary != null && !isBenchmarking,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            benchmarkSummary?.let { summary ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, ActiveGreenLed.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .background(ActiveGreenLed, CircleShape)
                        )
                        Text(
                            text = summary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextWhite,
                            lineHeight = 14.sp
                        )
                    }
                }
            }
        }

        // Выпадающая карточка со списком DoH-серверов
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color.Transparent,
            border = BorderStroke(1.dp, if (isListExpanded) ActiveGreenLed.copy(alpha = 0.35f) else AmoledBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Компактный заголовок
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            isListExpanded = !isListExpanded
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(ActiveGreenLed.copy(alpha = 0.12f))
                                .border(1.dp, ActiveGreenLed.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_shield),
                                contentDescription = null,
                                tint = ActiveGreenLed,
                                modifier = Modifier.size(15.dp)
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "Список DoH-серверов",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextWhite
                                )
                                Surface(
                                    shape = RoundedCornerShape(5.dp),
                                    color = ActiveGreenLed.copy(alpha = 0.15f),
                                    border = BorderStroke(0.8.dp, ActiveGreenLed.copy(alpha = 0.4f))
                                ) {
                                    Text(
                                        text = "${enabledProviderIds.size} ИЗ ${allProviders.size}",
                                        fontSize = 8.5.sp,
                                        fontWeight = FontWeight.Black,
                                        color = ActiveGreenLed,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                }
                            }
                            Text(
                                text = if (isListExpanded) "Нажмите для скрытия списка узлов" else "${enabledProviderIds.size} активно • Конкурентный Race Resolver",
                                fontSize = 11.sp,
                                color = TextMuted
                            )
                        }
                    }

                    val chevronRotation by animateFloatAsState(
                        targetValue = if (isListExpanded) 90f else 0f,
                        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
                        label = "doh_chevron_rot"
                    )
                    Icon(
                        painter = painterResource(id = R.drawable.ic_chevron_right),
                        contentDescription = null,
                        tint = if (isListExpanded) ActiveGreenLed else TextMuted,
                        modifier = Modifier
                            .size(15.dp)
                            .graphicsLayer { rotationZ = chevronRotation }
                    )
                }

                // Раскрывающийся блок со списком серверов
                AnimatedVisibility(
                    visible = isListExpanded,
                    enter = fadeIn(animationSpec = tween(180)) + expandVertically(animationSpec = tween(220)),
                    exit = fadeOut(animationSpec = tween(140)) + shrinkVertically(animationSpec = tween(180))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(AmoledBorder)
                        )

                        allProviders.forEach { provider ->
                            val isChecked = enabledProviderIds.contains(provider.id)
                            val result = benchmarkResults[provider.id]

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(if (isChecked) ActiveGreenLed.copy(alpha = 0.08f) else Color.Transparent)
                                    .border(
                                        1.dp,
                                        if (isChecked) ActiveGreenLed.copy(alpha = 0.25f) else AmoledBorder,
                                        RoundedCornerShape(9.dp)
                                    )
                                    .clickable(
                                        enabled = !isProxyRunning,
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onToggleProvider(provider.id, !isChecked)
                                    }
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(1.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Text(
                                            text = provider.name,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isChecked) TextWhite else TextMuted
                                        )

                                        if (result != null) {
                                            val style = when (result.status) {
                                                com.mirrly.tgproxy.core.DohHealthStatus.EXCELLENT -> DohBadgeStyle(
                                                    ActiveGreenLed.copy(alpha = 0.12f),
                                                    ActiveGreenLed.copy(alpha = 0.35f),
                                                    ActiveGreenLed,
                                                    "${result.latencyMs} мс"
                                                )
                                                com.mirrly.tgproxy.core.DohHealthStatus.GOOD -> DohBadgeStyle(
                                                    ActiveGreenLed.copy(alpha = 0.10f),
                                                    ActiveGreenLed.copy(alpha = 0.30f),
                                                    ActiveGreenLed,
                                                    "${result.latencyMs} мс"
                                                )
                                                com.mirrly.tgproxy.core.DohHealthStatus.MODERATE,
                                                com.mirrly.tgproxy.core.DohHealthStatus.SLOW -> DohBadgeStyle(
                                                    Color.Transparent,
                                                    AmoledBorder,
                                                    TextWhite.copy(alpha = 0.85f),
                                                    "${result.latencyMs} мс"
                                                )
                                                com.mirrly.tgproxy.core.DohHealthStatus.BLOCKED -> DohBadgeStyle(
                                                    Color(0xFFEF4444).copy(alpha = 0.12f),
                                                    Color(0xFFEF4444).copy(alpha = 0.35f),
                                                    Color(0xFFEF4444),
                                                    "Блок ТСПУ"
                                                )
                                                com.mirrly.tgproxy.core.DohHealthStatus.TIMEOUT -> DohBadgeStyle(
                                                    Color(0xFFEF4444).copy(alpha = 0.12f),
                                                    Color(0xFFEF4444).copy(alpha = 0.35f),
                                                    Color(0xFFEF4444),
                                                    "Таймаут"
                                                )
                                                com.mirrly.tgproxy.core.DohHealthStatus.POISONED -> DohBadgeStyle(
                                                    Color(0xFFEF4444).copy(alpha = 0.12f),
                                                    Color(0xFFEF4444).copy(alpha = 0.35f),
                                                    Color(0xFFEF4444),
                                                    "Подмена IP"
                                                )
                                            }

                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = style.bg,
                                                border = BorderStroke(0.8.dp, style.border)
                                            ) {
                                                Text(
                                                    text = style.label,
                                                    fontSize = 8.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = style.text,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                            }

                                            if (result.isRecommended) {
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = ActiveGreenLed.copy(alpha = 0.15f),
                                                    border = BorderStroke(0.8.dp, ActiveGreenLed.copy(alpha = 0.40f))
                                                ) {
                                                    Text(
                                                        text = "Топ",
                                                        fontSize = 8.5.sp,
                                                        fontWeight = FontWeight.Black,
                                                        color = ActiveGreenLed,
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                        } else if (isBenchmarking) {
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = Color.White.copy(alpha = 0.05f),
                                                border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.1f))
                                            ) {
                                                Text(
                                                    text = "Ожидание...",
                                                    fontSize = 8.5.sp,
                                                    fontWeight = FontWeight.Normal,
                                                    color = TextMuted,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                            }
                                        } else {
                                            if (provider.id == "adguard" || provider.id == "dnssb" || provider.id == "nextdns" || provider.id == "controld" || provider.id == "quad9") {
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = ActiveGreenLed.copy(alpha = 0.10f),
                                                    border = BorderStroke(0.8.dp, ActiveGreenLed.copy(alpha = 0.30f))
                                                ) {
                                                    Text(
                                                        text = "ТСПУ OK",
                                                        fontSize = 8.5.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = ActiveGreenLed,
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                            if (provider.id == "cloudflare") {
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = Color(0xFFEF4444).copy(alpha = 0.10f),
                                                    border = BorderStroke(0.8.dp, Color(0xFFEF4444).copy(alpha = 0.30f))
                                                ) {
                                                    Text(
                                                        text = "РКН блок?",
                                                        fontSize = 8.5.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color(0xFFEF4444),
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    Text(
                                        text = if (result != null && result.statusDetail.isNotEmpty() && !result.isUsable) {
                                            result.statusDetail
                                        } else {
                                            provider.description
                                        },
                                        fontSize = 10.sp,
                                        color = if (result != null && !result.isUsable) Color(0xFFEF4444).copy(alpha = 0.85f) else TextMuted,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }

                                InertialSpringSwitch(
                                    checked = isChecked,
                                    enabled = !isProxyRunning,
                                    activeColor = ActiveGreenLed,
                                    onCheckedChange = { checked ->
                                        onToggleProvider(provider.id, checked)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}



