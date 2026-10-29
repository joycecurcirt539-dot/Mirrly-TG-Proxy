/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * GNU GPL v3+ <https://www.gnu.org/licenses/>
 */

package com.mirrly.tgproxy.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.core.*
import com.mirrly.tgproxy.service.WarpProfileManager
import com.mirrly.tgproxy.service.WarpSavedProfile
import com.mirrly.tgproxy.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.nio.charset.StandardCharsets

private enum class ObfuscationPreset(val label: String, val subText: String, val jc: Int, val jmin: Int, val jmax: Int) {
    ANTI_DPI("Анти-ТСПУ", "Jc=4", 4, 40, 70),
    AGGRESSIVE("Агрессивный", "Jc=8", 8, 60, 120),
    STEALTH("Скрытный CF", "Jc=3", 3, 30, 50),
    CUSTOM("Вручную", "Свои параметры", 4, 40, 70)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WarpGeneratorScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val app = MirrlyApplication.instance
    val profileManager = app.warpProfileManager

    // Dynamic VPN Palette (Warm Electric Amber / system VPN theme, never MTProto cyan!)
    val vpnColors = remember { VpnThemeManager.getSystemVpnPalette(context) }
    val vpnAccent = vpnColors.primary

    val savedProfiles by profileManager.profilesFlow.collectAsState()
    val activeProfileId by profileManager.activeProfileIdFlow.collectAsState()

    var selectedFormats by remember { mutableStateOf<Set<WarpGeneratorFormat>>(setOf(WarpGeneratorFormat.AWG_1_0)) }
    var selectedEndpoints by remember { mutableStateOf(listOf(WarpEndpointPresetOption.FRA_1)) }
    var customEndpointText by remember { mutableStateOf("") }
    var selectedDnsPresets by remember { mutableStateOf(listOf(WarpDnsPresetOption.CLOUDFLARE)) }
    var customDnsText by remember { mutableStateOf("") }
    var includeIPv6 by remember { mutableStateOf(false) }

    val hasExistingProfile = remember(savedProfiles) {
        val existing = app.prefsManager.getWarpProfile()
        (existing != null && existing.isUsable() && !existing.isBootstrap()) || savedProfiles.isNotEmpty()
    }
    var accountMode by remember {
        mutableStateOf(
            if (hasExistingProfile) WarpAccountGenerationMode.REUSE_EXISTING
            else WarpAccountGenerationMode.NEW_ACCOUNT
        )
    }
    var licenseKeyText by remember { mutableStateOf("") }
    var customProfileNameText by remember { mutableStateOf("") }

    // AWG Obfuscation Preset & Custom Values
    var selectedAwgPreset by remember { mutableStateOf(ObfuscationPreset.ANTI_DPI) }
    var jcValue by remember { mutableIntStateOf(4) }
    var jminText by remember { mutableStateOf("40") }
    var jmaxText by remember { mutableStateOf("70") }
    var mtuText by remember { mutableStateOf("1280") }

    // Latency Ping State
    var isPingingEndpoints by remember { mutableStateOf(false) }
    val endpointLatencies = remember { mutableStateMapOf<String, Long>() }

    // Generation Terminal State
    var isGenerating by remember { mutableStateOf(false) }
    val terminalLogs = remember { mutableStateListOf<String>() }
    var generatedResults by remember { mutableStateOf<List<WarpGeneratedProfileItem>>(emptyList()) }

    // Modals & Expanded Configuration State
    var qrDialogProfile by remember { mutableStateOf<WarpGeneratedProfileItem?>(null) }
    var showSavedProfilesDialog by remember { mutableStateOf(false) }
    val revealedSecretProfileIds = remember { mutableStateListOf<String>() }
    var activeHelpTopic by remember { mutableStateOf<WarpHelpTopic?>(null) }

    // File Export Launchers
    var pendingExportItem by remember { mutableStateOf<WarpGeneratedProfileItem?>(null) }
    val singleFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        val item = pendingExportItem
        if (uri != null && item != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { stream: OutputStream ->
                        stream.write(item.content.toByteArray(StandardCharsets.UTF_8))
                        stream.flush()
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, context.getString(R.string.warp_profiles_file_exported_toast), Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Ошибка экспорта: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    pendingExportItem = null
                }
            }
        } else {
            pendingExportItem = null
        }
    }

    val zipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { stream: OutputStream ->
                        val targetProfiles = if (generatedResults.isNotEmpty()) {
                            generatedResults.map { item ->
                                WarpSavedProfile(
                                    id = item.id,
                                    name = item.name,
                                    format = item.format,
                                    endpoint = item.endpoint,
                                    dnsLine = item.dnsLine,
                                    content = item.content,
                                    isWarpSafe = item.isWarpSafe,
                                    warnings = item.warnings
                                )
                            }
                        } else {
                            profileManager.getAllProfiles()
                        }
                        profileManager.exportProfilesToZip(targetProfiles, stream)
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, context.getString(R.string.warp_profiles_zip_exported_toast), Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Ошибка ZIP: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // Ping Probing Execution
    val runPingProbes: () -> Unit = {
        if (!isPingingEndpoints) {
            isPingingEndpoints = true
            scope.launch(Dispatchers.IO) {
                val targets = selectedEndpoints
                for (opt in targets) {
                    val ep = if (opt.isCustom) customEndpointText.trim().ifBlank { WarpConfigGeneratorEngine.DEFAULT_CF_ENDPOINT }
                    else opt.endpoint.ifBlank { WarpConfigGeneratorEngine.DEFAULT_CF_ENDPOINT }
                    val (host, port) = WarpEndpointScanner.parseEndpoint(ep)
                    val rtt = try {
                        val wgCand = WarpEndpointScanner.probeEndpoint(
                            endpoint = ep,
                            timeoutMs = 750,
                            useFragmentation = true,
                            protocol = WarpProbeProtocol.WIREGUARD
                        )
                        if (wgCand.isAlive && wgCand.rttMs > 0) {
                            wgCand.rttMs
                        } else {
                            val tcpStart = System.currentTimeMillis()
                            val tcpSocket = java.net.Socket()
                            try {
                                val targetPort = if (port in listOf(500, 1701, 4500)) port else 443
                                tcpSocket.connect(java.net.InetSocketAddress(host, targetPort), 900)
                                (System.currentTimeMillis() - tcpStart).coerceAtLeast(1L)
                            } finally {
                                try { tcpSocket.close() } catch (_: Exception) {}
                            }
                        }
                    } catch (_: Exception) {
                        -1L
                    }
                    withContext(Dispatchers.Main) {
                        endpointLatencies[opt.id] = rtt
                    }
                }
                withContext(Dispatchers.Main) {
                    isPingingEndpoints = false
                }
            }
        }
    }

    // Generation Execution
    val executeGeneration: () -> Unit = {
        if (!isGenerating) {
            isGenerating = true
            terminalLogs.clear()
            generatedResults = emptyList()

            scope.launch(Dispatchers.IO) {
                fun logTerm(msg: String) {
                    terminalLogs.add(msg)
                }

                try {
                    logTerm("Инициализация сетевого движка WARP...")
                    val effectiveProfile: WarpProfile = if (accountMode == WarpAccountGenerationMode.NEW_ACCOUNT) {
                        logTerm("Регистрация нового аккаунта Cloudflare WARP...")
                        val regResult = WarpAccountManager.registerAndActivate(
                            licenseKey = licenseKeyText.trim().ifBlank { null }
                        )
                        if (regResult.isFailure) {
                            throw regResult.exceptionOrNull() ?: Exception("Регистрация не удалась")
                        }
                        val profile = regResult.getOrThrow()
                        app.prefsManager.saveWarpProfile(profile)
                        profile
                    } else {
                        val existing = app.prefsManager.getWarpProfile()
                        if (existing != null && existing.isUsable() && !existing.isBootstrap()) {
                            logTerm("Используется активный аккаунт (${if (existing.isWarpPlus) "WARP+" else "FREE"})")
                            existing
                        } else {
                            logTerm("Аккаунт не найден. Авторегистрация нового...")
                            val regResult = WarpAccountManager.registerAndActivate(
                                licenseKey = licenseKeyText.trim().ifBlank { null }
                            )
                            if (regResult.isFailure) {
                                throw regResult.exceptionOrNull() ?: Exception("Регистрация не удалась")
                            }
                            val profile = regResult.getOrThrow()
                            app.prefsManager.saveWarpProfile(profile)
                            profile
                        }
                    }

                    val effectiveJc = if (selectedAwgPreset == ObfuscationPreset.CUSTOM) jcValue else selectedAwgPreset.jc
                    val effectiveJmin = if (selectedAwgPreset == ObfuscationPreset.CUSTOM) jminText else selectedAwgPreset.jmin.toString()
                    val effectiveJmax = if (selectedAwgPreset == ObfuscationPreset.CUSTOM) jmaxText else selectedAwgPreset.jmax.toString()

                    val effectiveMtu = mtuText.toIntOrNull()?.coerceIn(1200, 1500) ?: 1280
                    val awgParamsObj = WarpAwgConfigParams(
                        jc = effectiveJc,
                        jmin = effectiveJmin.toIntOrNull() ?: 40,
                        jmax = effectiveJmax.toIntOrNull() ?: 70,
                        s1 = 0,
                        s2 = 0,
                        h1 = 1L,
                        h2 = 2L,
                        h3 = 3L,
                        h4 = 4L
                    )

                    val customNameArg = customProfileNameText.trim().ifBlank { null }
                    val buildSettings = WarpGeneratorBuildSettings(
                        formats = if (selectedFormats.isNotEmpty()) selectedFormats else setOf(WarpGeneratorFormat.AWG_1_0),
                        endpoints = if (selectedEndpoints.isNotEmpty()) selectedEndpoints else listOf(WarpEndpointPresetOption.FRA_1),
                        customEndpoint = customEndpointText,
                        dnsPresets = if (selectedDnsPresets.isNotEmpty()) selectedDnsPresets else listOf(WarpDnsPresetOption.CLOUDFLARE),
                        customDns = customDnsText,
                        mtu = effectiveMtu,
                        awgParams = awgParamsObj,
                        randomizeRanges = true,
                        includeIPv6 = includeIPv6,
                        customProfileName = customNameArg
                    )

                    val generated = WarpConfigGeneratorEngine.generateBatch(effectiveProfile, buildSettings)
                    logTerm(context.getString(R.string.warp_gen_status_success, generated.size))

                    // Сохраняем сгенерированные профили в менеджер профилей
                    profileManager.saveGeneratedBatch(generated, makeFirstActive = (profileManager.getActiveProfileId() == null))

                    withContext(Dispatchers.Main) {
                        generatedResults = generated
                        isGenerating = false
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                } catch (e: Exception) {
                    val errMsg = e.message ?: "Неизвестная ошибка"
                    withContext(Dispatchers.Main) {
                        terminalLogs.add("ОШИБКА: $errMsg")
                        isGenerating = false
                        Toast.makeText(context, context.getString(R.string.warp_gen_status_error, errMsg), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        // ── SCROLLABLE HIGH-DENSITY CONTENT ──
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .fadingEdges(
                    topFadeHeight = 28.dp,
                    bottomFadeHeight = 44.dp
                )
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 68.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp
            )
        ) {
                // ── CONFIGURATION SECTIONS ──
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        // ── FORMATS (Scrollable row) ──
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    CategoryHeader(
                                        text = "// ФОРМАТЫ ПРОФИЛЕЙ",
                                        accentColor = vpnAccent,
                                        onHelpClick = { activeHelpTopic = WarpHelpTopic.FORMATS_OVERVIEW }
                                    )
                                    val formatsScrollState = rememberScrollState()
                                    val fmtStartFade by animateDpAsState(
                                        targetValue = if (formatsScrollState.canScrollBackward) 18.dp else 0.dp,
                                        animationSpec = tween(200),
                                        label = "fmtStartFade"
                                    )
                                    val fmtEndFade by animateDpAsState(
                                        targetValue = if (formatsScrollState.canScrollForward) 26.dp else 0.dp,
                                        animationSpec = tween(200),
                                        label = "fmtEndFade"
                                    )
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalFadingEdges(
                                                startFadeWidth = fmtStartFade,
                                                endFadeWidth = fmtEndFade
                                            )
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(formatsScrollState),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            WarpGeneratorFormat.entries.forEach { fmt ->
                                                val isSelected = selectedFormats.contains(fmt)
                                                CompactSelectableChip(
                                                    label = fmt.title,
                                                    subLabel = fmt.extension.uppercase(),
                                                    isSelected = isSelected,
                                                    accent = vpnAccent,
                                                    onClick = {
                                                        selectedFormats = if (isSelected) {
                                                            if (selectedFormats.size > 1) selectedFormats - fmt else selectedFormats
                                                        } else {
                                                            selectedFormats + fmt
                                                        }
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }

                                // ── ENDPOINTS WITH LIVE PING & COUNTRY FLAGS ──
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CategoryHeader(
                                            text = "// ЭНДПОИНТЫ CLOUDFLARE",
                                            accentColor = vpnAccent,
                                            onHelpClick = { activeHelpTopic = WarpHelpTopic.ENDPOINTS_NETWORK }
                                        )

                                        val pingTransition = rememberInfiniteTransition(label = "pingPulse")
                                        val pingPulseAlpha by pingTransition.animateFloat(
                                            initialValue = 0.35f,
                                            targetValue = 1.0f,
                                            animationSpec = infiniteRepeatable(
                                                animation = tween(650, easing = FastOutSlowInEasing),
                                                repeatMode = RepeatMode.Reverse
                                            ),
                                            label = "pingBorderPulse"
                                        )
                                        val pingBorderColor by animateColorAsState(
                                            targetValue = if (isPingingEndpoints) vpnAccent.copy(alpha = pingPulseAlpha) else AmoledBorder,
                                            animationSpec = tween(200),
                                            label = "pingBorderColor"
                                        )

                                        // Ping All Trigger Pill (Outline-Only)
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .border(1.dp, pingBorderColor, RoundedCornerShape(8.dp))
                                                .background(Color.Transparent)
                                                .springPress(
                                                    enabled = !isPingingEndpoints,
                                                    onClick = { runPingProbes() }
                                                )
                                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                                            ) {
                                                if (isPingingEndpoints) {
                                                    CircularProgressIndicator(
                                                        modifier = Modifier.size(10.dp),
                                                        strokeWidth = 1.5.dp,
                                                        color = vpnAccent
                                                    )
                                                }
                                                Text(
                                                    text = if (isPingingEndpoints) "ЗАМЕР..." else "ТЕСТ ПИНГА",
                                                    color = vpnAccent,
                                                    fontSize = 9.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    fontFamily = FontFamily.Monospace
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(2.dp))

                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        val hasCustomSelected = selectedEndpoints.any { it.isCustom }
                                        WarpEndpointPresetOption.ACTIVE_PRESETS.chunked(2).forEach { pair ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                pair.forEach { opt ->
                                                    val isSelected = selectedEndpoints.any { it.id == opt.id }
                                                    val latency = endpointLatencies[opt.id]

                                                    Box(modifier = Modifier.weight(1f)) {
                                                        CompactEndpointCard(
                                                            option = opt,
                                                            isSelected = isSelected,
                                                            latency = latency,
                                                            accent = vpnAccent,
                                                            customSubtitle = if (opt.isCustom) {
                                                                customEndpointText.trim().ifBlank { "188.114.97.66:4500" }
                                                            } else null,
                                                            onToggle = {
                                                                selectedEndpoints = if (isSelected) {
                                                                    if (selectedEndpoints.size > 1) selectedEndpoints.filter { it.id != opt.id } else selectedEndpoints
                                                                } else {
                                                                    selectedEndpoints + opt
                                                                }
                                                            }
                                                        )
                                                    }
                                                }
                                                if (pair.size == 1) {
                                                    Spacer(modifier = Modifier.weight(1f))
                                                }
                                            }
                                        }

                                        if (hasCustomSelected) {
                                            CompactCyberInput(
                                                label = "// СВОЙ IP:PORT - НАПРИМЕР 188.114.97.66:4500",
                                                value = customEndpointText,
                                                accent = vpnAccent,
                                                onValueChange = { customEndpointText = it },
                                                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 4.dp)
                                            )
                                        }
                                    }
                                }

                                // ── AMNEZIAWG OBFUSCATION PRESETS ──
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    CategoryHeader(
                                        text = "// ОБФУСКАЦИЯ AWG",
                                        accentColor = vpnAccent,
                                        onHelpClick = { activeHelpTopic = WarpHelpTopic.OBFUSCATION_OVERVIEW }
                                    )
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        ObfuscationPreset.entries.chunked(2).forEach { rowPresets ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                rowPresets.forEach { preset ->
                                                    val isSelected = selectedAwgPreset == preset
                                                    CompactSelectableChip(
                                                        label = preset.label,
                                                        subLabel = preset.subText,
                                                        isSelected = isSelected,
                                                        accent = vpnAccent,
                                                        modifier = Modifier.weight(1f),
                                                        onClick = {
                                                            selectedAwgPreset = preset
                                                            if (preset != ObfuscationPreset.CUSTOM) {
                                                                jcValue = preset.jc
                                                                jminText = preset.jmin.toString()
                                                                jmaxText = preset.jmax.toString()
                                                            }
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // Expandable Custom Tuner
                                    AnimatedVisibility(
                                        visible = selectedAwgPreset == ObfuscationPreset.CUSTOM,
                                        enter = expandVertically(spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)) + fadeIn(tween(250)),
                                        exit = shrinkVertically(spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)) + fadeOut(tween(180))
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .padding(top = 8.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .border(1.dp, AmoledBorder, RoundedCornerShape(12.dp))
                                                .background(Color.Transparent)
                                                .padding(10.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                CompactCyberInput(
                                                    label = "Jc - Пакеты",
                                                    value = jcValue.toString(),
                                                    accent = vpnAccent,
                                                    onValueChange = { jcValue = it.toIntOrNull()?.coerceIn(1, 128) ?: 4 },
                                                    modifier = Modifier.weight(1f)
                                                )
                                                CompactCyberInput(
                                                    label = "Jmin - Байты",
                                                    value = jminText,
                                                    accent = vpnAccent,
                                                    onValueChange = { jminText = it },
                                                    modifier = Modifier.weight(1f)
                                                )
                                                CompactCyberInput(
                                                    label = "Jmax - Байты",
                                                    value = jmaxText,
                                                    accent = vpnAccent,
                                                    onValueChange = { jmaxText = it },
                                                    modifier = Modifier.weight(1f)
                                                )
                                            }
                                            CompactCyberInput(
                                                label = "MTU туннеля (по умолчанию 1280)",
                                                value = mtuText,
                                                accent = vpnAccent,
                                                onValueChange = { mtuText = it },
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    }
                                }

                                // ── DNS RESOLVERS (Single row with horizontal fading edges) ──
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    CategoryHeader(
                                        text = "// DNS РЕЗОЛВЕРЫ",
                                        accentColor = vpnAccent,
                                        onHelpClick = { activeHelpTopic = WarpHelpTopic.DNS_RESOLVERS }
                                    )
                                    val dnsScrollState = rememberScrollState()
                                    val dnsStartFade by animateDpAsState(
                                        targetValue = if (dnsScrollState.canScrollBackward) 18.dp else 0.dp,
                                        animationSpec = tween(200),
                                        label = "dnsStartFade"
                                    )
                                    val dnsEndFade by animateDpAsState(
                                        targetValue = if (dnsScrollState.canScrollForward) 26.dp else 0.dp,
                                        animationSpec = tween(200),
                                        label = "dnsEndFade"
                                    )
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalFadingEdges(
                                                startFadeWidth = dnsStartFade,
                                                endFadeWidth = dnsEndFade
                                            )
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(dnsScrollState),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            WarpDnsPresetOption.ALL_PRESETS.forEach { dnsOpt ->
                                                val isSelected = selectedDnsPresets.any { it.id == dnsOpt.id }
                                                CompactSelectableChip(
                                                    label = dnsOpt.label,
                                                    subLabel = dnsOpt.ipv4.firstOrNull() ?: "",
                                                    isSelected = isSelected,
                                                    accent = vpnAccent,
                                                    onClick = {
                                                        selectedDnsPresets = if (isSelected) {
                                                            if (selectedDnsPresets.size > 1) selectedDnsPresets.filter { it.id != dnsOpt.id } else selectedDnsPresets
                                                        } else {
                                                            selectedDnsPresets + dnsOpt
                                                        }
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }

                                // ── ACCOUNT MODE ──
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    CategoryHeader(
                                        text = "// УЧЕТНАЯ ЗАПИСЬ WARP",
                                        accentColor = vpnAccent,
                                        onHelpClick = { activeHelpTopic = WarpHelpTopic.ACCOUNT_WARP }
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        val isReuse = accountMode == WarpAccountGenerationMode.REUSE_EXISTING
                                        CompactSelectableChip(
                                            label = "ТЕКУЩИЙ АККАУНТ",
                                            subLabel = "Использовать профиль",
                                            isSelected = isReuse,
                                            accent = vpnAccent,
                                            modifier = Modifier.weight(1f),
                                            onClick = { accountMode = WarpAccountGenerationMode.REUSE_EXISTING }
                                        )

                                        val isNew = accountMode == WarpAccountGenerationMode.NEW_ACCOUNT
                                        CompactSelectableChip(
                                            label = "НОВЫЙ АККАУНТ",
                                            subLabel = "Свежая регистрация",
                                            isSelected = isNew,
                                            accent = vpnAccent,
                                            modifier = Modifier.weight(1f),
                                            onClick = { accountMode = WarpAccountGenerationMode.NEW_ACCOUNT }
                                        )
                                    }
                                }
                            }
                        }

                // ── CUSTOM PROFILE NAME INPUT (MAX 10 CHARS) ──
                item {
                    CategoryHeader(
                        text = "// ИМЯ ПРОФИЛЯ"
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, AmoledBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            BasicTextField(
                                value = customProfileNameText,
                                onValueChange = { input ->
                                    if (input.length <= 10) {
                                        customProfileNameText = input
                                    }
                                },
                                singleLine = true,
                                textStyle = TextStyle(
                                    color = TextWhite,
                                    fontSize = 12.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Medium
                                ),
                                cursorBrush = SolidColor(vpnAccent),
                                decorationBox = { innerTextField ->
                                    if (customProfileNameText.isEmpty()) {
                                        Text(
                                            text = "Имя профиля (до 10 символов)",
                                            color = TextMuted.copy(alpha = 0.5f),
                                            fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                    innerTextField()
                                },
                                modifier = Modifier.weight(1f)
                            )

                            // 10-char counter badge
                            val counterBorderColor by animateColorAsState(
                                targetValue = if (customProfileNameText.length == 10) vpnAccent else AmoledBorder,
                                animationSpec = tween(200),
                                label = "counterBorder"
                            )
                            val counterTextColor by animateColorAsState(
                                targetValue = if (customProfileNameText.length == 10) vpnAccent else TextMuted,
                                animationSpec = tween(200),
                                label = "counterText"
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color.Transparent,
                                border = BorderStroke(1.dp, counterBorderColor)
                            ) {
                                Text(
                                    text = "${customProfileNameText.length}/10",
                                    color = counterTextColor,
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }

                // ── 4. GENERATE ACTION BUTTON (Electric Amber Accent with Spring Bounce & Laser Glow) ──
                item {
                    val genTransition = rememberInfiniteTransition(label = "genPulse")
                    val genLaserGlowAlpha by genTransition.animateFloat(
                        initialValue = 0.45f,
                        targetValue = 1.0f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(550, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "genLaserGlow"
                    )
                    val genBorderColor = if (isGenerating) vpnAccent.copy(alpha = genLaserGlowAlpha) else vpnAccent

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Transparent)
                            .border(1.dp, genBorderColor, RoundedCornerShape(12.dp))
                            .springPress(
                                enabled = !isGenerating,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    executeGeneration()
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (isGenerating) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = vpnAccent
                                )
                            }
                            Text(
                                text = if (isGenerating) "ГЕНЕРАЦИЯ ПРОФИЛЕЙ..." else "СГЕНЕРИРОВАТЬ ПРОФИЛИ",
                                color = vpnAccent,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.6.sp
                            )
                        }
                    }
                }

                // ── 5. CYBER TERMINAL CONSOLE (ANIMATED EXPANSION & BREATHING LED) ──
                item {
                    AnimatedVisibility(
                        visible = terminalLogs.isNotEmpty() || isGenerating,
                        enter = expandVertically(spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow)) + fadeIn(tween(250)),
                        exit = shrinkVertically(spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow)) + fadeOut(tween(200))
                    ) {
                        val dotTransition = rememberInfiniteTransition(label = "terminalDotPulse")
                        val dotAlpha by dotTransition.animateFloat(
                            initialValue = 0.4f,
                            targetValue = 1.0f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(500, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "dotAlpha"
                        )

                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, AmoledBorder)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (isGenerating) vpnAccent.copy(alpha = dotAlpha)
                                                else Color(0xFF00E676)
                                            )
                                    )
                                    Text(
                                        text = "// ЖУРНАЛ ГЕНЕРАЦИИ",
                                        color = vpnAccent,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                terminalLogs.takeLast(6).forEach { logLine ->
                                    Text(
                                        text = "> $logLine",
                                        color = if (logLine.contains("ОШИБКА") || logLine.contains("ERROR")) Color(0xFFFF5252) else TextWhite.copy(alpha = 0.85f),
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        lineHeight = 14.sp
                                    )
                                }
                            }
                        }
                    }
                }

                // ── 6. RESULTS SECTION (ANIMATED DRAWER EXPANSION) ──
                item {
                    AnimatedVisibility(
                        visible = generatedResults.isNotEmpty(),
                        enter = expandVertically(spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow)) + fadeIn(tween(300)),
                        exit = shrinkVertically(spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow)) + fadeOut(tween(200))
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CategoryHeader("// ГОТОВЫЕ ПРОФИЛИ (${generatedResults.size})")

                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .border(1.dp, vpnAccent, RoundedCornerShape(8.dp))
                                            .springPress(onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                profileManager.saveGeneratedBatch(generatedResults, makeFirstActive = true)
                                                Toast.makeText(context, "Импортировано профилей: ${generatedResults.size}", Toast.LENGTH_SHORT).show()
                                            })
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = "ИМПОРТИРОВАТЬ ВСЕ",
                                            color = vpnAccent,
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }

                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .border(1.dp, AmoledBorder, RoundedCornerShape(8.dp))
                                            .springPress(onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                zipLauncher.launch("warp-profiles-bundle.zip")
                                            })
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                        Text(
                                            text = "ЭКСПОРТ ZIP",
                                            color = TextWhite,
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }

                            generatedResults.forEach { item ->
                                val isSecretRevealed = revealedSecretProfileIds.contains(item.id)
                                CompactProfileCard(
                                    item = item,
                                    isSecretRevealed = isSecretRevealed,
                                    accent = vpnAccent,
                                    onToggleSecret = {
                                        if (isSecretRevealed) revealedSecretProfileIds.remove(item.id)
                                        else revealedSecretProfileIds.add(item.id)
                                    },
                                    onActivate = {
                                        if (item.format.extension != "conf") {
                                            Toast.makeText(context, context.getString(R.string.warp_gen_err_export_only_format), Toast.LENGTH_SHORT).show()
                                        } else {
                                            profileManager.saveGeneratedProfile(item, makeActive = true)
                                            Toast.makeText(context, context.getString(R.string.warp_profiles_imported_toast), Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onShowQr = { qrDialogProfile = item },
                                    onCopy = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText(item.name, item.content))
                                        Toast.makeText(context, "Конфигурация скопирована в буфер", Toast.LENGTH_SHORT).show()
                                    },
                                    onSaveFile = {
                                        pendingExportItem = item
                                        singleFileLauncher.launch("${item.name}.${item.format.extension}")
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── TOP BAR (Pinned at Top with Vertical Gradient Scrim / Затемнение) ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(
                        0.0f to Color.Black,
                        0.80f to Color.Black.copy(alpha = 0.95f),
                        1.0f to Color.Transparent
                    )
                )
                .statusBarsPadding()
                .padding(bottom = 12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Back Button
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, AmoledBorder, RoundedCornerShape(10.dp))
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onBack()
                        }),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_left),
                        contentDescription = "Назад",
                        tint = TextWhite,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Title without subtitle
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.warp_gen_title),
                        color = TextWhite,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.4.sp
                    )
                }

                // Saved Profiles Pill Badge (Transparent with crisp border, centered count)
                val savedBadgeBorder by animateColorAsState(
                    targetValue = if (savedProfiles.isNotEmpty()) vpnAccent.copy(alpha = 0.65f) else AmoledBorder,
                    animationSpec = tween(250),
                    label = "savedBadgeBorder"
                )
                val savedBadgeTextColor by animateColorAsState(
                    targetValue = if (savedProfiles.isNotEmpty()) vpnAccent else TextMuted,
                    animationSpec = tween(250),
                    label = "savedBadgeTextColor"
                )

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, savedBadgeBorder),
                    modifier = Modifier.springPress(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        showSavedProfilesDialog = true
                    })
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "ПРОФИЛИ",
                            color = savedBadgeTextColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        if (savedProfiles.isNotEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color.Transparent,
                                border = BorderStroke(1.dp, vpnAccent.copy(alpha = 0.5f))
                            ) {
                                Text(
                                    text = savedProfiles.size.toString(),
                                    color = vpnAccent,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── QR CODE BOTTOM SHEET (MirrlyModalBottomSheet) ──
        qrDialogProfile?.let { prof ->
            MirrlyModalBottomSheet(
                onDismissRequest = { qrDialogProfile = null },
                maxTabletWidth = 460.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "QR-КОД КОНФИГУРАЦИИ",
                        color = TextWhite,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.5.sp
                    )

                    val qrBitmap = remember(prof.content) {
                        QrCodeGenerator.generateQrBitmap(
                            content = prof.content,
                            sizePx = 600,
                            accentColor = android.graphics.Color.parseColor("#FF6B2B"),
                            darkColor = android.graphics.Color.WHITE,
                            backgroundColor = android.graphics.Color.BLACK
                        )
                    }

                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "QR Code",
                            modifier = Modifier
                                .size(220.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .border(1.dp, AmoledBorder, RoundedCornerShape(10.dp))
                                .background(Color.Black)
                        )
                    }

                    Text(
                        text = prof.name,
                        color = vpnAccent,
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.Transparent)
                            .border(1.dp, AmoledBorder, RoundedCornerShape(10.dp))
                            .springPress(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                qrDialogProfile = null
                            }),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "ЗАКРЫТЬ",
                            color = TextWhite,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }
        }

        // ── SAVED PROFILES DIALOG (Unified Frosted Glass Modal) ──
        if (showSavedProfilesDialog) {
            VpnProfileSelectorDialog(
                vpnColors = vpnColors,
                onDismiss = { showSavedProfilesDialog = false }
            )
        }

    // ── EDUCATIONAL INFO / HELP DIALOG ──
    activeHelpTopic?.let { topic ->
        WarpHelpDialog(
            topic = topic,
            accentColor = vpnAccent,
            onDismiss = { activeHelpTopic = null }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// HIGH RESOLUTION VECTOR COUNTRY FLAGS (NO EMOJIS)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun CountryFlagBadge(
    countryCode: String,
    modifier: Modifier = Modifier
) {
    val roundedShape = RoundedCornerShape(3.dp)
    Box(
        modifier = modifier
            .size(width = 24.dp, height = 16.dp)
            .clip(roundedShape)
            .border(0.8.dp, Color(0xFF33333A), roundedShape)
    ) {
        when (countryCode.uppercase()) {
            "DE" -> {
                // Germany: 3 horizontal stripes (Black, Red, Gold)
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val stripeHeight = size.height / 3f
                    drawRect(color = Color(0xFF1B1B1B), topLeft = Offset(0f, 0f), size = Size(size.width, stripeHeight))
                    drawRect(color = Color(0xFFD00000), topLeft = Offset(0f, stripeHeight), size = Size(size.width, stripeHeight))
                    drawRect(color = Color(0xFFFFCE00), topLeft = Offset(0f, stripeHeight * 2), size = Size(size.width, stripeHeight))
                }
            }
            "NL" -> {
                // Netherlands: 3 horizontal stripes (Red, White, Cobalt Blue)
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val stripeHeight = size.height / 3f
                    drawRect(color = Color(0xFFA81024), topLeft = Offset(0f, 0f), size = Size(size.width, stripeHeight))
                    drawRect(color = Color(0xFFFFFFFF), topLeft = Offset(0f, stripeHeight), size = Size(size.width, stripeHeight))
                    drawRect(color = Color(0xFF1E4287), topLeft = Offset(0f, stripeHeight * 2), size = Size(size.width, stripeHeight))
                }
            }
            "FI" -> {
                // Finland: White background, Blue Nordic cross
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawRect(color = Color(0xFFFFFFFF))
                    val crossW = size.width * 0.20f
                    val crossH = size.height * 0.28f
                    val crossX = size.width * 0.32f
                    drawRect(color = Color(0xFF003580), topLeft = Offset(crossX, 0f), size = Size(crossW, size.height))
                    drawRect(color = Color(0xFF003580), topLeft = Offset(0f, (size.height - crossH) / 2f), size = Size(size.width, crossH))
                }
            }
            "SE" -> {
                // Sweden: Blue background, Yellow Nordic cross
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawRect(color = Color(0xFF005293))
                    val crossW = size.width * 0.20f
                    val crossH = size.height * 0.26f
                    val crossX = size.width * 0.32f
                    drawRect(color = Color(0xFFFECC00), topLeft = Offset(crossX, 0f), size = Size(crossW, size.height))
                    drawRect(color = Color(0xFFFECC00), topLeft = Offset(0f, (size.height - crossH) / 2f), size = Size(size.width, crossH))
                }
            }
            "GB" -> {
                // United Kingdom: Royal Navy, White & Red Saltires, White & Red St George Cross
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawRect(color = Color(0xFF012169))
                    val strokeDiagWhite = size.height * 0.26f
                    val strokeDiagRed = size.height * 0.12f
                    drawLine(color = Color(0xFFFFFFFF), start = Offset(0f, 0f), end = Offset(size.width, size.height), strokeWidth = strokeDiagWhite)
                    drawLine(color = Color(0xFFFFFFFF), start = Offset(0f, size.height), end = Offset(size.width, 0f), strokeWidth = strokeDiagRed)
                    drawLine(color = Color(0xFFC8102E), start = Offset(0f, 0f), end = Offset(size.width, size.height), strokeWidth = strokeDiagRed)
                    drawLine(color = Color(0xFFC8102E), start = Offset(0f, size.height), end = Offset(size.width, 0f), strokeWidth = strokeDiagRed)
                    val crossWhiteW = size.width * 0.28f
                    val crossWhiteH = size.height * 0.38f
                    drawRect(color = Color(0xFFFFFFFF), topLeft = Offset((size.width - crossWhiteW) / 2f, 0f), size = Size(crossWhiteW, size.height))
                    drawRect(color = Color(0xFFFFFFFF), topLeft = Offset(0f, (size.height - crossWhiteH) / 2f), size = Size(size.width, crossWhiteH))
                    val crossRedW = size.width * 0.18f
                    val crossRedH = size.height * 0.22f
                    drawRect(color = Color(0xFFC8102E), topLeft = Offset((size.width - crossRedW) / 2f, 0f), size = Size(crossRedW, size.height))
                    drawRect(color = Color(0xFFC8102E), topLeft = Offset(0f, (size.height - crossRedH) / 2f), size = Size(size.width, crossRedH))
                }
            }
            "US" -> {
                // USA: 7 Red/White stripes + Blue Canton + micro star points
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val stripeHeight = size.height / 7f
                    for (i in 0..6) {
                        drawRect(
                            color = if (i % 2 == 0) Color(0xFFB22234) else Color(0xFFFFFFFF),
                            topLeft = Offset(0f, i * stripeHeight),
                            size = Size(size.width, stripeHeight)
                        )
                    }
                    val cantonW = size.width * 0.44f
                    val cantonH = stripeHeight * 4f
                    drawRect(color = Color(0xFF3C3B6E), topLeft = Offset(0f, 0f), size = Size(cantonW, cantonH))
                    drawCircle(color = Color.White, radius = 0.9f, center = Offset(cantonW * 0.35f, cantonH * 0.35f))
                    drawCircle(color = Color.White, radius = 0.9f, center = Offset(cantonW * 0.65f, cantonH * 0.35f))
                    drawCircle(color = Color.White, radius = 0.9f, center = Offset(cantonW * 0.50f, cantonH * 0.65f))
                }
            }
            "FR" -> {
                // France: 3 vertical stripes (Blue, White, Red)
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val colW = size.width / 3f
                    drawRect(color = Color(0xFF002654), topLeft = Offset(0f, 0f), size = Size(colW, size.height))
                    drawRect(color = Color(0xFFFFFFFF), topLeft = Offset(colW, 0f), size = Size(colW, size.height))
                    drawRect(color = Color(0xFFED2939), topLeft = Offset(colW * 2f, 0f), size = Size(colW, size.height))
                }
            }
            "RANDOM" -> {
                RandomNodeBadgeIcon()
            }
            "CUSTOM" -> {
                CustomNodeBadgeIcon()
            }
            else -> {
                // GLOBAL / Anycast: Cyber globe with equator, meridian and ellipse
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Transparent)
                ) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val r = (size.height / 2f) * 0.78f
                    val amber = Color(0xFFFF6B2B)
                    drawCircle(color = amber, radius = r, style = Stroke(width = 1.1f))
                    drawLine(color = amber.copy(alpha = 0.85f), start = Offset(cx - r, cy), end = Offset(cx + r, cy), strokeWidth = 0.9f)
                    drawLine(color = amber.copy(alpha = 0.85f), start = Offset(cx, cy - r), end = Offset(cx, cy + r), strokeWidth = 0.9f)
                    drawOval(color = amber.copy(alpha = 0.65f), topLeft = Offset(cx - r * 0.55f, cy - r), size = Size(r * 1.1f, r * 2f), style = Stroke(width = 0.8f))
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// HIGH RESOLUTION CYBER CANVAS ICONS FOR ENDPOINTS
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Высокодетализированная векторная кибер-иконка случайного узла WARP (Canvas).
 * Изображает динамическую случайную перемаршрутизацию (Cyber Shuffle Matrix)
 * с пересекающимися траекториями, направляющими стрелками, источниками слева
 * и квантовым ядром в точке пересечения.
 */
@Composable
fun RandomNodeBadgeIcon(
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        val w = size.width
        val h = size.height

        // 1. Мягкое фоновое радиальное свечение в центре
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0x40FF9E0B), Color(0x00FF9E0B)),
                center = Offset(w * 0.5f, h * 0.5f),
                radius = h * 0.85f
            )
        )

        // 2. Микро-риски кибер-сетки сверху и снизу
        val tickColor = Color(0x22FFB703)
        drawLine(color = tickColor, start = Offset(w * 0.5f, h * 0.08f), end = Offset(w * 0.5f, h * 0.20f), strokeWidth = 1f)
        drawLine(color = tickColor, start = Offset(w * 0.5f, h * 0.80f), end = Offset(w * 0.5f, h * 0.92f), strokeWidth = 1f)

        // Координаты пересекающихся потоков случайной маршрутизации
        val yTop = h * 0.32f
        val yBottom = h * 0.68f
        val xStart = w * 0.16f
        val xEnd = w * 0.74f
        val strokeW = 1.35.dp.toPx()

        // Поток сверху вниз (кубическая кривая Безье)
        val pathTopToBottom = Path().apply {
            moveTo(xStart, yTop)
            lineTo(w * 0.28f, yTop)
            cubicTo(
                w * 0.44f, yTop,
                w * 0.56f, yBottom,
                w * 0.68f, yBottom
            )
            lineTo(xEnd, yBottom)
        }

        // Поток снизу вверх (кубическая кривая Безье)
        val pathBottomToTop = Path().apply {
            moveTo(xStart, yBottom)
            lineTo(w * 0.28f, yBottom)
            cubicTo(
                w * 0.44f, yBottom,
                w * 0.56f, yTop,
                w * 0.68f, yTop
            )
            lineTo(xEnd, yTop)
        }

        // Внешнее рассеянное свечение под линиями
        drawPath(
            path = pathTopToBottom,
            color = Color(0x35FF9E0B),
            style = Stroke(width = strokeW * 2.2f, cap = StrokeCap.Round)
        )
        drawPath(
            path = pathBottomToTop,
            color = Color(0x35FFB703),
            style = Stroke(width = strokeW * 2.2f, cap = StrokeCap.Round)
        )

        // Основные градиентные векторные траектории
        val streamBrush = Brush.horizontalGradient(
            colors = listOf(Color(0xFFFFB703), Color(0xFFFF9E0B), Color(0xFFFF6D00)),
            startX = xStart,
            endX = xEnd
        )
        drawPath(
            path = pathTopToBottom,
            brush = streamBrush,
            style = Stroke(width = strokeW, cap = StrokeCap.Round)
        )
        drawPath(
            path = pathBottomToTop,
            brush = streamBrush,
            style = Stroke(width = strokeW, cap = StrokeCap.Round)
        )

        // Стрелочные наконечники справа
        val arrowTipX = w * 0.88f
        val arrowBaseX = w * 0.73f
        val arrowHalfH = 2.4.dp.toPx()

        // Верхний наконечник стрелки
        val topArrow = Path().apply {
            moveTo(arrowTipX, yTop)
            lineTo(arrowBaseX, yTop - arrowHalfH)
            lineTo(arrowBaseX + 1.2.dp.toPx(), yTop)
            lineTo(arrowBaseX, yTop + arrowHalfH)
            close()
        }
        drawPath(path = topArrow, color = Color(0xFFFFD54F))

        // Нижний наконечник стрелки
        val bottomArrow = Path().apply {
            moveTo(arrowTipX, yBottom)
            lineTo(arrowBaseX, yBottom - arrowHalfH)
            lineTo(arrowBaseX + 1.2.dp.toPx(), yBottom)
            lineTo(arrowBaseX, yBottom + arrowHalfH)
            close()
        }
        drawPath(path = bottomArrow, color = Color(0xFFFFD54F))

        // Источники узлов слева (кольца и светящиеся ядра)
        val nodeHaloR = 2.4.dp.toPx()
        val nodeCoreR = 1.3.dp.toPx()
        drawCircle(color = Color(0x44FF9E0B), radius = nodeHaloR, center = Offset(xStart, yTop))
        drawCircle(color = Color(0xFFFFF9C4), radius = nodeCoreR, center = Offset(xStart, yTop))

        drawCircle(color = Color(0x44FF9E0B), radius = nodeHaloR, center = Offset(xStart, yBottom))
        drawCircle(color = Color(0xFFFFF9C4), radius = nodeCoreR, center = Offset(xStart, yBottom))

        // Центральное квантовое ядро перекрестка (Quantum Crossover Core)
        val cx = w * 0.50f
        val cy = h * 0.50f
        val nexusHaloR = 2.8.dp.toPx()
        val nexusCoreR = 1.5.dp.toPx()
        drawCircle(color = Color(0x60FF6D00), radius = nexusHaloR, center = Offset(cx, cy))
        drawCircle(color = Color(0xFFFFD54F), radius = nexusCoreR, center = Offset(cx, cy))
        drawCircle(color = Color(0xFFFFFFFF), radius = nexusCoreR * 0.55f, center = Offset(cx, cy))
    }
}

/**
 * Высокодетализированная векторная кибер-иконка своего эндпоинта (Canvas).
 * Изображает консоль прецизионной калибровки (Cyber Calibration Faders)
 * с металлическими направляющими рельсами, неоновыми активными шкалами,
 * капсульными ползунками с LED-прорезью и калибровочными рисками.
 */
@Composable
fun CustomNodeBadgeIcon(
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        val w = size.width
        val h = size.height

        // 1. Мягкое фоновое неоновое свечение (Циан/Изумруд)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0x3500E5FF), Color(0x0000E5FF)),
                center = Offset(w * 0.5f, h * 0.5f),
                radius = h * 0.85f
            )
        )

        // 2. Калибровочные микро-риски сверху и снизу
        val tickColor = Color(0x3000E5FF)
        drawLine(color = tickColor, start = Offset(w * 0.50f, h * 0.08f), end = Offset(w * 0.50f, h * 0.20f), strokeWidth = 1f)
        drawLine(color = tickColor, start = Offset(w * 0.50f, h * 0.80f), end = Offset(w * 0.50f, h * 0.92f), strokeWidth = 1f)

        val xStart = w * 0.15f
        val xEnd = w * 0.85f
        val yTop = h * 0.35f
        val yBottom = h * 0.65f
        val railStrokeW = 1.6.dp.toPx()

        // 3. Неактивные металлические пазы направляющих
        val railColor = Color(0xFF16252C)
        drawLine(color = railColor, start = Offset(xStart, yTop), end = Offset(xEnd, yTop), strokeWidth = railStrokeW, cap = StrokeCap.Round)
        drawLine(color = railColor, start = Offset(xStart, yBottom), end = Offset(xEnd, yBottom), strokeWidth = railStrokeW, cap = StrokeCap.Round)

        // 4. Верхний активный трек (неоновый градиент Циан)
        val xKnobTop = w * 0.67f
        val topTrailBrush = Brush.horizontalGradient(
            colors = listOf(Color(0xFF00E676), Color(0xFF00E5FF)),
            startX = xStart,
            endX = xKnobTop
        )
        drawLine(brush = topTrailBrush, start = Offset(xStart, yTop), end = Offset(xKnobTop, yTop), strokeWidth = railStrokeW, cap = StrokeCap.Round)

        // 5. Нижний активный трек (неоновый градиент Изумруд)
        val xKnobBottom = w * 0.33f
        val bottomTrailBrush = Brush.horizontalGradient(
            colors = listOf(Color(0xFF00E676), Color(0xFF69F0AE)),
            startX = xStart,
            endX = xKnobBottom
        )
        drawLine(brush = bottomTrailBrush, start = Offset(xStart, yBottom), end = Offset(xKnobBottom, yBottom), strokeWidth = railStrokeW, cap = StrokeCap.Round)

        // 6. Параметры капсульных ползунков (Faders)
        val knobW = 3.6.dp.toPx()
        val knobH = 6.8.dp.toPx()
        val knobCornerR = 1.6.dp.toPx()

        // Верхний ползунок (Cyan Accent)
        drawRoundRect(
            color = Color(0x4000E5FF),
            topLeft = Offset(xKnobTop - (knobW + 2f) / 2f, yTop - (knobH + 2f) / 2f),
            size = Size(knobW + 2f, knobH + 2f),
            cornerRadius = CornerRadius(knobCornerR + 1f)
        )
        drawRoundRect(
            color = Color(0xFF0C1D24),
            topLeft = Offset(xKnobTop - knobW / 2f, yTop - knobH / 2f),
            size = Size(knobW, knobH),
            cornerRadius = CornerRadius(knobCornerR)
        )
        drawRoundRect(
            color = Color(0xFF00E5FF),
            topLeft = Offset(xKnobTop - knobW / 2f, yTop - knobH / 2f),
            size = Size(knobW, knobH),
            cornerRadius = CornerRadius(knobCornerR),
            style = Stroke(width = 0.9.dp.toPx())
        )
        drawLine(
            color = Color(0xFFFFFFFF),
            start = Offset(xKnobTop, yTop - 1.6.dp.toPx()),
            end = Offset(xKnobTop, yTop + 1.6.dp.toPx()),
            strokeWidth = 1.1.dp.toPx(),
            cap = StrokeCap.Round
        )

        // Нижний ползунок (Emerald Accent)
        drawRoundRect(
            color = Color(0x4000E676),
            topLeft = Offset(xKnobBottom - (knobW + 2f) / 2f, yBottom - (knobH + 2f) / 2f),
            size = Size(knobW + 2f, knobH + 2f),
            cornerRadius = CornerRadius(knobCornerR + 1f)
        )
        drawRoundRect(
            color = Color(0xFF0C1D24),
            topLeft = Offset(xKnobBottom - knobW / 2f, yBottom - knobH / 2f),
            size = Size(knobW, knobH),
            cornerRadius = CornerRadius(knobCornerR)
        )
        drawRoundRect(
            color = Color(0xFF00E676),
            topLeft = Offset(xKnobBottom - knobW / 2f, yBottom - knobH / 2f),
            size = Size(knobW, knobH),
            cornerRadius = CornerRadius(knobCornerR),
            style = Stroke(width = 0.9.dp.toPx())
        )
        drawLine(
            color = Color(0xFFFFFFFF),
            start = Offset(xKnobBottom, yBottom - 1.6.dp.toPx()),
            end = Offset(xKnobBottom, yBottom + 1.6.dp.toPx()),
            strokeWidth = 1.1.dp.toPx(),
            cap = StrokeCap.Round
        )

        // Концевые терминальные точки рельс
        val termR = 1.1.dp.toPx()
        drawCircle(color = Color(0x5000E5FF), radius = termR, center = Offset(xStart, yTop))
        drawCircle(color = Color(0x5000E676), radius = termR, center = Offset(xStart, yBottom))
        drawCircle(color = Color(0x3000E5FF), radius = termR, center = Offset(xEnd, yTop))
        drawCircle(color = Color(0x3000E676), radius = termR, center = Offset(xEnd, yBottom))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// COMPACT MINIMALIST REUSABLE SUBCOMPONENTS
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CategoryHeader(
    text: String,
    accentColor: Color = Color(0xFF00E676),
    onHelpClick: (() -> Unit)? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(bottom = 2.dp)
    ) {
        Text(
            text = text,
            color = TextMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.5.sp
        )
        if (onHelpClick != null) {
            Box(
                modifier = Modifier
                    .size(17.dp)
                    .clip(CircleShape)
                    .background(Color.Transparent)
                    .border(1.dp, accentColor.copy(alpha = 0.55f), CircleShape)
                    .springPress(onClick = onHelpClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_question_mark),
                    contentDescription = "Справка",
                    tint = accentColor,
                    modifier = Modifier.size(9.5.dp)
                )
            }
        }
    }
}


@Composable
private fun CompactSelectableChip(
    label: String,
    subLabel: String = "",
    isSelected: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val animatedBorderColor by animateColorAsState(
        targetValue = if (isSelected) accent.copy(alpha = 0.85f) else AmoledBorder,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "chipBorder"
    )
    val animatedTextColor by animateColorAsState(
        targetValue = if (isSelected) accent else TextWhite,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "chipText"
    )
    val animatedSubTextColor by animateColorAsState(
        targetValue = if (isSelected) accent.copy(alpha = 0.75f) else TextMuted,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "chipSubText"
    )
    val animatedScale by animateFloatAsState(
        targetValue = if (isSelected) 1.025f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "chipScale"
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = animatedScale
                scaleY = animatedScale
            }
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, animatedBorderColor, RoundedCornerShape(10.dp))
            .background(Color.Transparent)
            .springPress(onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            })
            .padding(horizontal = 10.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                color = animatedTextColor,
                fontSize = 11.5.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subLabel.isNotBlank()) {
                Text(
                    text = subLabel,
                    color = animatedSubTextColor,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun CompactEndpointCard(
    option: WarpEndpointPresetOption,
    isSelected: Boolean,
    latency: Long?,
    accent: Color,
    customSubtitle: String? = null,
    onToggle: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val animatedBorderColor by animateColorAsState(
        targetValue = if (isSelected) accent.copy(alpha = 0.75f) else AmoledBorder,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "epBorder"
    )
    val animatedScale by animateFloatAsState(
        targetValue = if (isSelected) 1.015f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "epScale"
    )
    val animatedTextColor by animateColorAsState(
        targetValue = if (isSelected) TextWhite else TextWhite.copy(alpha = 0.85f),
        animationSpec = tween(220),
        label = "epText"
    )

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, animatedBorderColor),
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = animatedScale
                scaleY = animatedScale
            }
            .clip(RoundedCornerShape(10.dp))
            .springPress(onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onToggle()
            })
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 9.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CountryFlagBadge(countryCode = option.countryCode)
                    Text(
                        text = option.label,
                        color = animatedTextColor,
                        fontSize = 11.5.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(accent)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val displaySubtitle = customSubtitle ?: option.endpoint
                Text(
                    text = if (displaySubtitle.isNotBlank()) displaySubtitle else if (option.isRandom) "Случайный" else "Эндпоинт",
                    color = TextMuted,
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                if (latency != null) {
                    val badgeColor = when {
                        latency < 0 -> Color(0xFFFF5252)
                        latency < 100 -> Color(0xFF00E676)
                        else -> Color(0xFFFFB703)
                    }
                    val badgeText = if (latency < 0) "ТАЙМАУТ" else "$latency МС"
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.Transparent)
                            .border(1.dp, badgeColor.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = badgeText,
                            color = badgeColor,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactCyberInput(
    label: String,
    value: String,
    accent: Color,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            color = TextMuted,
            fontSize = 9.5.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(bottom = 3.dp)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, AmoledBorder, RoundedCornerShape(8.dp))
                .background(Color.Transparent)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = TextWhite,
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(accent),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun CompactProfileCard(
    item: WarpGeneratedProfileItem,
    isSecretRevealed: Boolean,
    accent: Color,
    onToggleSecret: () -> Unit,
    onActivate: () -> Unit,
    onShowQr: () -> Unit,
    onCopy: () -> Unit,
    onSaveFile: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val animatedBorderColor by animateColorAsState(
        targetValue = if (isSecretRevealed) accent.copy(alpha = 0.55f) else AmoledBorder,
        animationSpec = tween(250),
        label = "cardBorder"
    )
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, animatedBorderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header Row: Country Flag + Name + Format Pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CountryFlagBadge(countryCode = resolveCountryCodeForEndpoint(item.endpoint))
                    Column {
                        Text(
                            text = item.name,
                            color = TextWhite,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${item.endpoint} • DNS: ${item.dnsLine}",
                            color = TextMuted,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color.Transparent)
                        .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(5.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = item.format.title.substringBefore(" ("),
                        color = accent,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Configuration View: Animated expanding drawer
            AnimatedContent(
                targetState = isSecretRevealed,
                transitionSpec = {
                    (expandVertically(spring(dampingRatio = 0.8f, stiffness = 380f)) + fadeIn(tween(220)))
                        .togetherWith(shrinkVertically(spring(dampingRatio = 0.8f, stiffness = 380f)) + fadeOut(tween(160)))
                },
                label = "profileConfigReveal"
            ) { revealed ->
                if (revealed) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "// КОНФИГУРАЦИЯ ${item.format.extension.uppercase()}",
                                    color = accent,
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .springPress(onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                onCopy()
                                            }),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_copy),
                                            contentDescription = "Копировать",
                                            tint = accent,
                                            modifier = Modifier.size(13.dp)
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .springPress(onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                onToggleSecret()
                                            }),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_eye_slash),
                                            contentDescription = "Скрыть",
                                            tint = accent,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 240.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Text(
                                    text = item.content,
                                    color = TextWhite.copy(alpha = 0.95f),
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 14.sp
                                )
                            }
                        }
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, AmoledBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val maskedSnippet = WarpConfigGeneratorEngine.maskPrivateKey(
                                item.content.lines().firstOrNull { it.contains("PrivateKey", ignoreCase = true) } ?: item.content.take(40)
                            )

                            Text(
                                text = maskedSnippet,
                                color = TextMuted,
                                fontSize = 9.5.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )

                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .springPress(onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onToggleSecret()
                                    }),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_eye),
                                    contentDescription = "Показать",
                                    tint = TextMuted,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Action Row: Primary Button + 3 Compact Icons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // PRIMARY ACTIVATE BUTTON (Outline-Only)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Transparent)
                        .border(1.dp, accent, RoundedCornerShape(8.dp))
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onActivate()
                        }),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "ИСПОЛЬЗОВАТЬ В VPN",
                        color = accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                }

                // QR Button
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, AmoledBorder, RoundedCornerShape(8.dp))
                        .background(Color.Transparent)
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onShowQr()
                        }),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "QR",
                        color = TextWhite,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Copy Icon
                CompactIconButton(
                    iconRes = R.drawable.ic_copy,
                    onClick = onCopy
                )

                // Save File Icon
                CompactIconButton(
                    iconRes = R.drawable.ic_arrow_down,
                    onClick = onSaveFile
                )
            }
        }
    }
}

@Composable
private fun CompactIconButton(
    iconRes: Int,
    onClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, AmoledBorder, RoundedCornerShape(8.dp))
            .background(Color.Transparent)
            .springPress(onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = TextWhite,
            modifier = Modifier.size(14.dp)
        )
    }
}
