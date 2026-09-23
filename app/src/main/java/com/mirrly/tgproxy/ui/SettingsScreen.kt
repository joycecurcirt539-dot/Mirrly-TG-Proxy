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
import android.content.pm.PackageManager
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.core.view.WindowCompat
import com.mirrly.tgproxy.core.AppLogger
import com.mirrly.tgproxy.core.NativeProxy
import android.view.WindowManager
import androidx.compose.ui.platform.LocalContext
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
import androidx.annotation.StringRes
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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

/** Small button-hint «ⓘ» next to with title configuring */
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

/** Popup dialog window with detailed description configuring */
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
                        text = stringResource(R.string.settings_help_title),
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

                // Keep the final card clear of the navigation area and fading edge.
                Spacer(modifier = Modifier.height(72.dp))
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
                        contentDescription = stringResource(R.string.action_back),
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
    val sections = remember(body) { parseInfoSections(body) }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        sections.forEachIndexed { index, section ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, AmoledBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = ActiveGreenLed.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, ActiveGreenLed.copy(alpha = 0.35f))
                        ) {
                            Text(
                                text = (index + 1).toString().padStart(2, '0'),
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Black,
                                color = ActiveGreenLed,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }

                        section.heading?.let { heading ->
                            Text(
                                text = heading,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = ActiveGreenLed,
                                letterSpacing = 0.35.sp,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    section.lines.forEach { line ->
                        when {
                            isInfoHeading(line) -> Text(
                                text = line.trim().trimEnd(':'),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = ActiveGreenLed.copy(alpha = 0.9f),
                                letterSpacing = 0.3.sp
                            )
                            line.startsWith("• ") || line.startsWith("- ") -> InfoBulletItem(
                                text = line.drop(2).trim()
                            )
                            else -> InfoParagraph(text = line)
                        }
                    }
                }
            }
        }
    }
}

internal data class InfoSection(
    val heading: String?,
    val lines: List<String>
)

internal fun parseInfoSections(body: String): List<InfoSection> {
    val normalized = body
        .replace("\\n", "\n")
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .trim()
        .let(::restoreCollapsedInfoMarkup)

    if (normalized.isEmpty()) return emptyList()

    val parsed = mutableListOf<InfoSection>()
    var heading: String? = null
    val content = mutableListOf<String>()

    fun flush() {
        if (heading != null || content.isNotEmpty()) {
            parsed += InfoSection(heading, content.toList())
        }
        heading = null
        content.clear()
    }

    normalized.lines().forEach { rawLine ->
        val line = rawLine.trim()
        when {
            line.isEmpty() -> {
                if (content.isNotEmpty()) flush()
            }
            isInfoHeading(line) -> {
                flush()
                heading = line.trimEnd(':').trim()
            }
            else -> content += line
        }
    }
    flush()

    val nonEmpty = parsed.filter { it.heading != null || it.lines.isNotEmpty() }
    if (nonEmpty.size <= 5) return nonEmpty

    val firstFour = nonEmpty.take(4)
    val tail = nonEmpty.drop(4)
    val tailLines = buildList {
        tail.forEachIndexed { index, section ->
            if (index > 0) section.heading?.let { add("$it:") }
            addAll(section.lines)
        }
    }
    return firstFour + InfoSection(tail.firstOrNull()?.heading, tailLines)
}

/**
 * Older settings resources used visual XML line breaks instead of escaped \n.
 * AAPT collapses those breaks into spaces, so recover only the explicit markup
 * that survives compilation: bullet characters and uppercase section labels.
 */
private fun restoreCollapsedInfoMarkup(value: String): String {
    val withBullets = value.replace(Regex("\\s*•\\s*"), "\n• ")
    val inlineUppercaseHeading = Regex(
        """([.!?])\s+((?:\d+\.\s*)?[\p{Lu}\d][\p{Lu}\p{M}\d\s&+()/.–—-]{2,95}:)(?=\s|\n)"""
    )
    return withBullets
        .replace(inlineUppercaseHeading) { match ->
            "${match.groupValues[1]}\n\n${match.groupValues[2].trim()}\n"
        }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

private fun isInfoHeading(line: String): Boolean {
    val candidate = line.trim()
    if (candidate.isEmpty() || candidate.startsWith("•") || candidate.startsWith("-")) return false
    if (candidate.length > 96) return false
    val letters = candidate.filter { it.isLetter() }
    val isUppercase = letters.length >= 3 && letters == letters.uppercase(java.util.Locale.ROOT)
    return candidate.endsWith(":") || isUppercase
}

@Composable
private fun InfoParagraph(text: String) {
    val colonIndex = text.indexOf(':').takeIf { it in 1..42 }
    val annotated = buildAnnotatedString {
        if (colonIndex != null) {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = TextWhite)) {
                append(text.substring(0, colonIndex + 1))
            }
            append(text.substring(colonIndex + 1))
        } else {
            append(text)
        }
    }
    Text(
        text = annotated,
        fontSize = 13.sp,
        color = TextWhite.copy(alpha = 0.86f),
        lineHeight = 19.sp,
        textAlign = TextAlign.Start
    )
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
        Box(modifier = Modifier.weight(1f)) {
            InfoParagraph(text = text)
        }
    }
}

enum class SettingsCategory(@StringRes val titleRes: Int) {
    ALL(R.string.settings_category_all),
    NETWORK(R.string.settings_category_network),
    UPLINK_DOH(R.string.settings_category_uplink),
    SYSTEM(R.string.settings_category_system),
    MISC(R.string.settings_category_other)
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

enum class SettingsProtocolMode {
    MTPROTO,
    SOCKS5,
    VPN
}

enum class SettingsSafetyLevel {
    SAFE,
    EXPERT,
    DANGER
}

@Composable
fun SettingsSafetyBadge(
    level: SettingsSafetyLevel,
    customLabel: String? = null,
    modifier: Modifier = Modifier
) {
    val (color, defaultText) = when (level) {
        SettingsSafetyLevel.SAFE -> ActiveGreenLed to stringResource(R.string.settings_safety_safe)
        SettingsSafetyLevel.EXPERT -> Color(0xFFFFB74D) to stringResource(R.string.settings_safety_expert)
        SettingsSafetyLevel.DANGER -> Color(0xFFEF4444) to stringResource(R.string.settings_safety_danger)
    }
    val text = customLabel ?: defaultText

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            fontSize = 9.sp,
            fontWeight = FontWeight.Black,
            color = color,
            letterSpacing = 0.7.sp
        )
    }
}

@Composable
private fun SettingsVpnDevBanner(
    vpnColors: ProtocolColors,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, Color(0xFFFFB74D).copy(alpha = 0.45f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.settings_vpn_in_dev_title),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.1.sp,
                    color = Color(0xFFFFB74D)
                )
                SettingsSafetyBadge(
                    level = SettingsSafetyLevel.EXPERT,
                    customLabel = stringResource(R.string.settings_vpn_in_dev_badge)
                )
            }
            Text(
                text = stringResource(R.string.settings_vpn_in_dev_desc),
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                color = TextWhite.copy(alpha = 0.85f)
            )
        }
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
    onOpenHallOfFame: () -> Unit = {},
    onOpenOnboarding: () -> Unit = {},
    onOpenDiagnosticReport: () -> Unit = {},
    initialIsVpn: Boolean = false
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val app = MirrlyApplication.instance
    val config = app.config
    val server = app.proxyServer
    val systemVpnColors = remember { com.mirrly.tgproxy.ui.theme.VpnThemeManager.getSystemVpnPalette(context) }

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

    var activeProtocolMode by rememberSaveable {
        mutableStateOf(
            if (isSocks5) SettingsProtocolMode.SOCKS5
            else SettingsProtocolMode.MTPROTO
        )
    }
    var showVpnInDevDialog by remember { mutableStateOf(false) }

    LaunchedEffect(isSocks5) {
        activeProtocolMode = if (isSocks5) SettingsProtocolMode.SOCKS5 else SettingsProtocolMode.MTPROTO
    }

    var selectedSpeedPresetName by remember { mutableStateOf(config.speedPresetName) }
    val isAdvancedMode by app.prefsManager.advancedSettingsEnabledFlow.collectAsState()
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
    val vpnUplinkMode by app.prefsManager.vpnUplinkModeFlow.collectAsState()
    val vpnState by com.mirrly.tgproxy.service.MirrlyVpnService.vpnState.collectAsState()
    val vpnMtu by app.prefsManager.vpnMtuFlow.collectAsState()
    val vpnBlockQuic by app.prefsManager.vpnBlockQuicFlow.collectAsState()
    val vpnBlockIpv6Leaks by app.prefsManager.vpnBlockIpv6LeaksFlow.collectAsState()
    val vpnSplitTunnelEnabled by app.prefsManager.vpnSplitTunnelEnabledFlow.collectAsState()
    val vpnSplitTunnelAllowlist by app.prefsManager.vpnSplitTunnelAllowlistFlow.collectAsState()
    val vpnSplitTunnelPackages by app.prefsManager.vpnSplitTunnelPackagesFlow.collectAsState()
    var showSplitTunnelAppsDialog by rememberSaveable { mutableStateOf(false) }
    var warpProfile by remember { mutableStateOf(app.prefsManager.getWarpProfile()) }
    var isRegisteringWarp by remember { mutableStateOf(false) }
    var vlessUuid by remember { mutableStateOf<String>(app.prefsManager.getVlessUuid().ifEmpty { config.vlessUuid }) }
    var sleepTimerInitialTab by remember { mutableStateOf(TimerDialogTab.TIMER) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var showDonateConfirmDialog by remember { mutableStateOf(false) }
    var showWarpRegistrationDialog by remember { mutableStateOf(false) }
    var warpDialogStartRegistrationImmediately by remember { mutableStateOf(false) }

    val density = LocalDensity.current.density
    val langBlurAnim = remember { Animatable(0f) }
    val langBlurVal = langBlurAnim.value

    val langBlurModifier = if (langBlurVal > 0.005f) {
        Modifier.graphicsLayer {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val blurPx = langBlurVal * 16f * density
                if (blurPx > 0.5f) {
                    renderEffect = RenderEffect.createBlurEffect(
                        blurPx,
                        blurPx,
                        Shader.TileMode.CLAMP
                    ).asComposeRenderEffect()
                }
            }
            alpha = (1f - langBlurVal * 0.45f).coerceIn(0.55f, 1f)
        }
    } else {
        Modifier
    }

    val onLanguageChange: (String) -> Unit = { newLang ->
        if (!langBlurAnim.isRunning) {
            coroutineScope.launch {
                langBlurAnim.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 130, easing = FastOutLinearInEasing)
                )
                app.prefsManager.setAppLanguage(newLang)
                langBlurAnim.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 180, easing = LinearOutSlowInEasing)
                )
            }
        }
    }

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
                AppLogger.e("SettingsScreen", "Failed to restart proxy service: ${e.message}")
            }
        }
    }

    val toggleAdvancedMode: (Boolean) -> Unit = { enabled ->
        app.prefsManager.setAdvancedSettingsEnabled(enabled)
        if (!enabled) {
            app.prefsManager.resetAdvancedSettingsToDefaults(config)
            app.saveConfig()
            selectedSpeedPresetName = config.speedPresetName
            restartProxyIfNeeded()
            Toast.makeText(
                context,
                context.getString(R.string.toast_advanced_mode_reset),
                Toast.LENGTH_SHORT
            ).show()
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

    if (showSplitTunnelAppsDialog) {
        SplitTunnelAppsDialog(
            vpnColors = systemVpnColors,
            selectedPackages = vpnSplitTunnelPackages,
            onSave = { newSelection ->
                app.prefsManager.setVpnSplitTunnelPackages(newSelection)
                showSplitTunnelAppsDialog = false
                if (vpnState == com.mirrly.tgproxy.ui.theme.VpnUiState.CONNECTED) {
                    com.mirrly.tgproxy.service.MirrlyVpnService.restart(context)
                }
            },
            onDismiss = { showSplitTunnelAppsDialog = false }
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
                .then(langBlurModifier)
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
                    activeMode = activeProtocolMode,
                    isSwitching = isSwitching,
                    vpnColors = systemVpnColors,
                    onInfoClick = { infoKey = "protocols_info" },
                    onModeSelect = { mode ->
                        if (mode == SettingsProtocolMode.VPN) {
                            showVpnInDevDialog = true
                            return@SettingsProtocolSection
                        }
                        activeProtocolMode = mode
                        when (mode) {
                            SettingsProtocolMode.MTPROTO -> {
                                if (isSocks5) {
                                    com.mirrly.tgproxy.service.ProtocolSwitchManager.switchProtocol(context, ProxyMode.MTPROTO)
                                }
                            }
                            SettingsProtocolMode.SOCKS5 -> {
                                if (!isSocks5) {
                                    com.mirrly.tgproxy.service.ProtocolSwitchManager.switchProtocol(context, ProxyMode.SOCKS5)
                                }
                            }
                            SettingsProtocolMode.VPN -> {}
                        }
                    }
                )

                SettingsDivider()

                when (activeProtocolMode) {
                    SettingsProtocolMode.VPN -> {
                        Surface(
                            onClick = { showVpnInDevDialog = true },
                            shape = RoundedCornerShape(16.dp),
                            color = Color(0xFF1E293B).copy(alpha = 0.35f),
                            border = BorderStroke(1.dp, Color(0xFFFFB74D).copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
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
                    }
                    SettingsProtocolMode.MTPROTO -> {
                        SettingsNetworkSection(
                            selectedMode = ProxyMode.MTPROTO,
                            isAdvancedMode = isAdvancedMode,
                            onEnableAdvancedMode = { toggleAdvancedMode(true) },
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
                    }
                    SettingsProtocolMode.SOCKS5 -> {
                        SettingsNetworkSection(
                            selectedMode = ProxyMode.SOCKS5,
                            isAdvancedMode = isAdvancedMode,
                            onEnableAdvancedMode = { toggleAdvancedMode(true) },
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

                    }
                }

                if (showUplinkDoh || showSystem || showMisc) {
                    SettingsDivider()
                }
            }

            if (showUplinkDoh) {
                if (activeProtocolMode == SettingsProtocolMode.VPN) {
                    SettingsUplinkWarpSection(
                        config = config,
                        uplinkMode = vpnUplinkMode,
                        warpProfile = warpProfile,
                        isRegisteringWarp = isRegisteringWarp,
                        onSelectUplinkMode = { newMode ->
                            app.prefsManager.setVpnUplinkMode(newMode)
                            config.vpnUplinkModeName = newMode.name
                            app.saveConfig()
                            if (vpnState == com.mirrly.tgproxy.ui.theme.VpnUiState.CONNECTED) {
                                com.mirrly.tgproxy.service.MirrlyVpnService.restart(context)
                            }
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
                            Toast.makeText(context, context.getString(R.string.toast_vless_uuid_generated), Toast.LENGTH_SHORT).show()
                            if (vpnState == com.mirrly.tgproxy.ui.theme.VpnUiState.CONNECTED) {
                                com.mirrly.tgproxy.service.MirrlyVpnService.restart(context)
                            }
                        },
                        onRestartProxy = {
                            if (vpnState == com.mirrly.tgproxy.ui.theme.VpnUiState.CONNECTED) {
                                com.mirrly.tgproxy.service.MirrlyVpnService.restart(context)
                            }
                        }
                    )

                    SettingsDivider()
                } else if (activeProtocolMode == SettingsProtocolMode.SOCKS5) {
                    SettingsWorkerSection(
                        config = config,
                        onOpenWorkerManager = onOpenWorkerManager,
                        onOpenWorkerGuide = onOpenWorkerGuide,
                        onInfoClick = { infoKey = it }
                    )

                    SettingsDivider()
                } else {
                    SettingsMtprotoCdnOverviewCard()

                    SettingsDivider()
                }

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
                            Toast.makeText(context, context.getString(R.string.toast_stop_proxy_change_dns), Toast.LENGTH_SHORT).show()
                            return@SettingsDohSection
                        }
                        val newSet = if (isEnabled) {
                            enabledDohProviderIds + providerId
                        } else {
                            if (enabledDohProviderIds.size <= 1 && enabledDohProviderIds.contains(providerId)) {
                                Toast.makeText(context, context.getString(R.string.toast_doh_keep_at_least_one), Toast.LENGTH_SHORT).show()
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
                            Toast.makeText(context, context.getString(R.string.toast_stop_proxy_reset_dns), Toast.LENGTH_SHORT).show()
                            return@SettingsDohSection
                        }
                        val defaults = com.mirrly.tgproxy.core.DohResolver.DEFAULT_ENABLED_PROVIDER_IDS
                        enabledDohProviderIds = defaults
                        config.enabledDohProviderIds = defaults
                        app.saveConfig()
                        server.applyDohConfig(defaults)
                        benchmarkResults = emptyMap()
                        benchmarkSummary = null
                        Toast.makeText(context, context.getString(R.string.toast_doh_reset_defaults), Toast.LENGTH_SHORT).show()
                    },
                    onSelectAllProviders = {
                        if (isProxyRunning || server.isRunning) {
                            Toast.makeText(context, context.getString(R.string.toast_stop_proxy_change_dns), Toast.LENGTH_SHORT).show()
                            return@SettingsDohSection
                        }
                        val allIds = com.mirrly.tgproxy.core.DohResolver.ALL_PROVIDERS.map { it.id }.toSet()
                        enabledDohProviderIds = allIds
                        config.enabledDohProviderIds = allIds
                        app.saveConfig()
                        server.applyDohConfig(allIds)
                        Toast.makeText(context, context.getString(R.string.toast_doh_all_enabled, allIds.size.toString()), Toast.LENGTH_SHORT).show()
                    },
                    isBenchmarking = isBenchmarkingDoh,
                    benchmarkProgress = benchmarkProgress,
                    benchmarkResults = benchmarkResults,
                    benchmarkSummary = benchmarkSummary,
                    onStartBenchmark = {
                        if (isProxyRunning || server.isRunning) {
                            Toast.makeText(context, context.getString(R.string.toast_stop_proxy_start_benchmark), Toast.LENGTH_SHORT).show()
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
                                    val selected = report.results.filter { it.isRecommended }
                                    val namesWithPing = selected.joinToString(", ") { "${it.providerName} (${it.latencyMs} ms)" }
                                    val toastMsg = context.getString(R.string.toast_doh_selected_best, selected.size, namesWithPing)
                                    Toast.makeText(context, toastMsg, Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(context, context.getString(R.string.toast_doh_all_unreachable), Toast.LENGTH_LONG).show()
                                }
                            } catch (e: Exception) {
                                Toast.makeText(context, context.getString(R.string.toast_doh_benchmark_err, e.message ?: ""), Toast.LENGTH_SHORT).show()
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
                    onOpenOnboarding = onOpenOnboarding,
                    onOpenDiagnosticReport = onOpenDiagnosticReport,
                    onInfoClick = { infoKey = it },
                    onLanguageChange = onLanguageChange
                )

                SettingsDivider()

                AnimatedVisibility(
                    visible = isAdvancedMode,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        SettingsPerformanceSection(
                            config = config,
                            selectedSpeedPresetName = selectedSpeedPresetName,
                            onPresetSelect = { preset ->
                                selectedSpeedPresetName = preset.name
                                config.speedPresetName = preset.name
                                config.applyPreset(preset)
                                app.saveConfig()
                                restartProxyIfNeeded()
                            },
                            onInfoClick = { infoKey = it }
                        )

                        SettingsDivider()

                        SettingsAdvancedEngineeringSection(
                            config = config,
                            onInfoClick = { infoKey = it },
                            onRestartProxy = { restartProxyIfNeeded() }
                        )

                        SettingsDivider()
                    }
                }

                SettingsAdvancedModeToggleCard(
                    isAdvancedMode = isAdvancedMode,
                    onToggle = toggleAdvancedMode,
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
                    onOpenHallOfFame = onOpenHallOfFame
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        SettingsTopBar(
            selectedCategory = selectedCategory,
            onSelectCategory = { selectedCategory = it },
            onBack = onBack,
            isAdvancedMode = isAdvancedMode,
            onToggleAdvancedMode = toggleAdvancedMode,
            modifier = langBlurModifier
        )

        CyberParticlesOverlay(
            modifier = Modifier.fillMaxSize(),
            particleCount = 14,
            alphaMultiplier = 0.50f
        )

        if (showDonateConfirmDialog) {
            ExternalLinkConfirmDialog(
                url = "https://dalink.to/cartneyzix",
                title = stringResource(R.string.settings_support_dev),
                description = stringResource(R.string.settings_support_dev_desc),
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
                workerDomain = com.mirrly.tgproxy.core.WarpAccountManager.REGISTRATION_WORKER_DOMAIN,
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

        if (showVpnInDevDialog) {
            VpnInDevDialog(
                vpnColors = systemVpnColors,
                onDismiss = { showVpnInDevDialog = false }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTopBar(
    selectedCategory: SettingsCategory,
    onSelectCategory: (SettingsCategory) -> Unit,
    onBack: () -> Unit,
    isAdvancedMode: Boolean = false,
    onToggleAdvancedMode: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val accent = ActiveGreenLed

    Column(
        modifier = Modifier
            .then(modifier)
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
                    text = stringResource(R.string.action_settings),
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
                        contentDescription = stringResource(R.string.action_back),
                        tint = TextWhite,
                        modifier = Modifier.size(22.dp)
                    )
                }
            },
            actions = {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isAdvancedMode) ActiveGreenLed.copy(alpha = 0.14f) else Color.Transparent,
                    border = BorderStroke(1.dp, if (isAdvancedMode) ActiveGreenLed.copy(alpha = 0.5f) else AmoledBorder),
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onToggleAdvancedMode(!isAdvancedMode)
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (isAdvancedMode) ActiveGreenLed else InactiveGrayLed)
                        )
                        Text(
                            text = if (isAdvancedMode) stringResource(R.string.settings_mode_expert) else stringResource(R.string.settings_mode_simple),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.8.sp,
                            color = if (isAdvancedMode) ActiveGreenLed else TextMuted
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
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
                            .padding(vertical = 7.dp, horizontal = 1.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(category.titleRes),
                            fontSize = 10.sp,
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
    activeMode: SettingsProtocolMode,
    isSwitching: Boolean,
    vpnColors: ProtocolColors,
    onInfoClick: () -> Unit,
    onModeSelect: (SettingsProtocolMode) -> Unit
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
                text = stringResource(R.string.settings_protocol_title),
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.3.sp,
                color = TextMuted
            )
            InfoButton { onInfoClick() }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SettingsProtocolMode.values().forEach { mode ->
                val isSelected = activeMode == mode
                val modeAccent = when (mode) {
                    SettingsProtocolMode.MTPROTO -> MtprotoAccent
                    SettingsProtocolMode.SOCKS5  -> Socks5Accent
                    SettingsProtocolMode.VPN     -> vpnColors.primary
                }
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
                    SettingsProtocolMode.MTPROTO -> "MTProto"
                    SettingsProtocolMode.SOCKS5  -> stringResource(R.string.mode_socks5_beta)
                    SettingsProtocolMode.VPN     -> stringResource(R.string.mode_vpn_in_dev)
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
                            if (isSwitching || activeMode == mode) return@clickable
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onModeSelect(mode)
                        }
                        .padding(horizontal = 4.dp, vertical = 11.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = modeLabel,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = chipTextColor,
                        maxLines = 1,
                        softWrap = false,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsVpnStatusOverviewSection(
    vpnColors: ProtocolColors,
    vpnState: com.mirrly.tgproxy.ui.theme.VpnUiState,
    onOpenVpnDialog: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val vpnStatus by com.mirrly.tgproxy.service.MirrlyVpnService.vpnStatus.collectAsState()

    Column(
        modifier = Modifier.staggeredEntrance(index = 1),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.vpn_system_title_dev),
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.3.sp,
                color = TextMuted
            )

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (vpnState == com.mirrly.tgproxy.ui.theme.VpnUiState.CONNECTED) ActiveGreenLed.copy(alpha = 0.12f)
                        else vpnColors.primary.copy(alpha = 0.12f)
                    )
                    .border(
                        1.dp,
                        if (vpnState == com.mirrly.tgproxy.ui.theme.VpnUiState.CONNECTED) ActiveGreenLed.copy(alpha = 0.35f)
                        else vpnColors.primary.copy(alpha = 0.35f),
                        RoundedCornerShape(6.dp)
                    )
                    .padding(horizontal = 7.dp, vertical = 2.5.dp)
            ) {
                Text(
                    text = when (vpnState) {
                        com.mirrly.tgproxy.ui.theme.VpnUiState.CONNECTED -> stringResource(R.string.vpn_status_active_badge)
                        com.mirrly.tgproxy.ui.theme.VpnUiState.CONNECTING -> stringResource(R.string.status_connecting_caps)
                        else -> stringResource(R.string.vpn_status_inactive_badge)
                    },
                    color = if (vpnState == com.mirrly.tgproxy.ui.theme.VpnUiState.CONNECTED) ActiveGreenLed else vpnColors.primary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Transparent)
                .border(1.dp, AmoledBorder, RoundedCornerShape(18.dp))
        ) {
            Column {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.vpn_tunnel_title),
                        color = TextWhite,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.5.sp
                    )
                    Text(
                        text = stringResource(R.string.vpn_tunnel_desc),
                        color = TextMuted,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp
                    )
                }

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AmoledBorder))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(text = stringResource(R.string.vpn_active_flows_label), color = TextMuted, fontSize = 11.sp)
                        Text(
                            text = "${vpnStatus.activeTcpFlows} TCP  •  ${vpnStatus.activeUdpSessions} UDP",
                            color = TextWhite,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(text = stringResource(R.string.vpn_network_gen_label), color = TextMuted, fontSize = 11.sp)
                        Text(
                            text = "gen-${vpnStatus.generation}",
                            color = vpnColors.primary,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AmoledBorder))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = vpnColors.primary.copy(alpha = 0.10f),
                        border = BorderStroke(1.dp, vpnColors.primary.copy(alpha = 0.35f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onOpenVpnDialog()
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 11.dp, horizontal = 14.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.vpn_select_protocol_btn),
                                color = vpnColors.primary,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsVpnCoreSection(
    vpnColors: ProtocolColors,
    mtu: Int,
    onMtuSelect: (Int) -> Unit,
    blockQuic: Boolean,
    onToggleBlockQuic: (Boolean) -> Unit,
    blockIpv6Leaks: Boolean,
    onToggleBlockIpv6Leaks: (Boolean) -> Unit,
    onInfoClick: (String) -> Unit
) {
    val haptic = LocalHapticFeedback.current

    Column(
        modifier = Modifier.staggeredEntrance(index = 2),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.vpn_architecture_title).uppercase(),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.3.sp,
                    color = TextMuted
                )
                SettingsSafetyBadge(level = SettingsSafetyLevel.EXPERT)
            }
            InfoButton { onInfoClick("vpn_core_info") }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Transparent)
                .border(1.dp, AmoledBorder, RoundedCornerShape(18.dp))
        ) {
            Column {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = stringResource(R.string.vpn_mtu_title),
                                color = TextWhite,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.5.sp
                            )
                            Text(
                                text = stringResource(R.string.vpn_mtu_desc),
                                color = TextMuted,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(vpnColors.primary.copy(alpha = 0.12f))
                                .border(1.dp, vpnColors.primary.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 7.dp, vertical = 2.5.dp)
                        ) {
                            Text(
                                text = "$mtu B",
                                color = vpnColors.primary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            1280 to "1280 (Safe)",
                            1420 to "1420 (WARP)",
                            1500 to "1500 (Max)"
                        ).forEach { (size, label) ->
                            val isSelected = mtu == size
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) vpnColors.primary.copy(alpha = 0.15f) else Color.Transparent,
                                border = BorderStroke(1.dp, if (isSelected) vpnColors.primary.copy(alpha = 0.5f) else AmoledBorder),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onMtuSelect(size)
                                    }
                            ) {
                                Text(
                                    text = label,
                                    color = if (isSelected) vpnColors.primary else TextMuted,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(vertical = 7.dp)
                                )
                            }
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AmoledBorder))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = stringResource(R.string.vpn_block_quic_title),
                            color = TextWhite,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = stringResource(R.string.vpn_block_quic_desc),
                            color = TextMuted,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                    InertialSpringSwitch(
                        checked = blockQuic,
                        onCheckedChange = onToggleBlockQuic,
                        activeColor = vpnColors.primary
                    )
                }

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AmoledBorder))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = stringResource(R.string.vpn_block_ipv6_title),
                            color = TextWhite,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = stringResource(R.string.vpn_block_ipv6_desc),
                            color = TextMuted,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                    InertialSpringSwitch(
                        checked = blockIpv6Leaks,
                        onCheckedChange = onToggleBlockIpv6Leaks,
                        activeColor = vpnColors.primary
                    )
                }

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AmoledBorder))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = stringResource(R.string.vpn_dns_tunnel_title),
                            color = TextWhite,
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp
                        )
                        Text(
                            text = stringResource(R.string.vpn_dns_tunnel_desc),
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(ActiveGreenLed.copy(alpha = 0.12f))
                            .border(1.dp, ActiveGreenLed.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.badge_secured_short),
                            color = ActiveGreenLed,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsVpnSplitTunnelSection(
    vpnColors: ProtocolColors,
    isEnabled: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    isAllowlist: Boolean,
    onToggleAllowlist: (Boolean) -> Unit,
    packageCount: Int,
    onOpenAppPicker: () -> Unit
) {
    val haptic = LocalHapticFeedback.current

    Column(
        modifier = Modifier.staggeredEntrance(index = 3),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f, fill = false),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.vpn_splittunnel_title).uppercase(),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.3.sp,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                SettingsSafetyBadge(level = SettingsSafetyLevel.SAFE)
            }

            Spacer(modifier = Modifier.width(8.dp))

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (isEnabled) vpnColors.primary.copy(alpha = 0.12f)
                        else AmoledBorder.copy(alpha = 0.4f)
                    )
                    .border(
                        1.dp,
                        if (isEnabled) vpnColors.primary.copy(alpha = 0.35f)
                        else AmoledBorder,
                        RoundedCornerShape(6.dp)
                    )
                    .padding(horizontal = 7.dp, vertical = 2.5.dp)
            ) {
                Text(
                    text = if (isEnabled) stringResource(R.string.vpn_status_active_badge) else stringResource(R.string.badge_disabled_caps),
                    color = if (isEnabled) vpnColors.primary else TextMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Transparent)
                .border(1.dp, AmoledBorder, RoundedCornerShape(18.dp))
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = stringResource(R.string.vpn_splittunnel_title),
                            color = TextWhite,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.5.sp
                        )
                        Text(
                            text = stringResource(R.string.vpn_splittunnel_desc),
                            color = TextMuted,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                    InertialSpringSwitch(
                        checked = isEnabled,
                        onCheckedChange = onToggleEnabled,
                        activeColor = vpnColors.primary
                    )
                }

                if (isEnabled) {
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AmoledBorder))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.vpn_app_filter_mode_label),
                            color = TextMuted,
                            fontSize = 11.sp
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val isDisallow = !isAllowlist
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isDisallow) vpnColors.primary.copy(alpha = 0.15f) else Color.Transparent,
                                border = BorderStroke(1.dp, if (isDisallow) vpnColors.primary.copy(alpha = 0.5f) else AmoledBorder),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onToggleAllowlist(false)
                                    }
                            ) {
                                Text(
                                    text = stringResource(R.string.vpn_split_tunnel_mode_disallow),
                                    color = if (isDisallow) vpnColors.primary else TextMuted,
                                    fontSize = 11.sp,
                                    fontWeight = if (isDisallow) FontWeight.Bold else FontWeight.Normal,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(vertical = 7.dp)
                                )
                            }

                            val isAllow = isAllowlist
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isAllow) vpnColors.primary.copy(alpha = 0.15f) else Color.Transparent,
                                border = BorderStroke(1.dp, if (isAllow) vpnColors.primary.copy(alpha = 0.5f) else AmoledBorder),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onToggleAllowlist(true)
                                    }
                            ) {
                                Text(
                                    text = stringResource(R.string.vpn_split_tunnel_mode_allow),
                                    color = if (isAllow) vpnColors.primary else TextMuted,
                                    fontSize = 11.sp,
                                    fontWeight = if (isAllow) FontWeight.Bold else FontWeight.Normal,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(vertical = 7.dp)
                                )
                            }
                        }
                    }

                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AmoledBorder))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = vpnColors.primary.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, vpnColors.primary.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onOpenAppPicker()
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 11.dp, horizontal = 14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.vpn_split_tunnel_apps_btn, packageCount),
                                    color = TextWhite,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = stringResource(R.string.action_configure_arrow),
                                    color = vpnColors.primary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class InstalledAppItem(
    val packageName: String,
    val label: String,
    val icon: android.graphics.drawable.Drawable?
)

@Composable
fun SplitTunnelAppsDialog(
    vpnColors: ProtocolColors,
    selectedPackages: Set<String>,
    onSave: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var currentSelection by remember { mutableStateOf(selectedPackages) }
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var installedApps by remember { mutableStateOf<List<InstalledAppItem>>(emptyList()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(mainIntent, 0)
            }
            val ownPkg = context.packageName
            val list = resolveInfos
                .mapNotNull { it.activityInfo?.applicationInfo }
                .filter { it.packageName != ownPkg }
                .distinctBy { it.packageName }
                .map { appInfo ->
                    InstalledAppItem(
                        packageName = appInfo.packageName,
                        label = appInfo.loadLabel(pm).toString(),
                        icon = try { appInfo.loadIcon(pm) } catch (_: Throwable) { null }
                    )
                }
                .sortedBy { it.label.lowercase() }
            withContext(Dispatchers.Main) {
                installedApps = list
                isLoading = false
            }
        }
    }

    val filteredApps = remember(installedApps, searchQuery) {
        if (searchQuery.isBlank()) installedApps
        else {
            val q = searchQuery.trim().lowercase()
            installedApps.filter {
                it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        DialogBackdropBox(onDismiss = onDismiss) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 20.dp, vertical = 32.dp)
                    .adaptiveContainerWidth(460.dp)
                    .fillMaxHeight(0.85f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(AmoledSurface)
                    .border(1.dp, AmoledBorder, RoundedCornerShape(24.dp))
                    .clickable(enabled = false) {}
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = stringResource(R.string.vpn_split_tunnel_dialog_title),
                                color = TextWhite,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.vpn_selected_count, currentSelection.size, installedApps.size),
                                color = vpnColors.primary,
                                fontSize = 11.5.sp
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Text("✕", color = TextMuted, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, AmoledBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                singleLine = true,
                                textStyle = TextStyle(color = TextWhite, fontSize = 13.sp),
                                modifier = Modifier.weight(1f),
                                decorationBox = { innerTextField ->
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            text = stringResource(R.string.vpn_split_tunnel_search_hint),
                                            color = TextMuted,
                                            fontSize = 13.sp
                                        )
                                    }
                                    innerTextField()
                                }
                            )
                            if (searchQuery.isNotEmpty()) {
                                Text(
                                    text = "✕",
                                    color = TextMuted,
                                    fontSize = 14.sp,
                                    modifier = Modifier.clickable { searchQuery = "" }.padding(horizontal = 4.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, AmoledBorder),
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    val allPkgs = installedApps.map { it.packageName }.toSet()
                                    currentSelection = allPkgs
                                }
                        ) {
                            Text(
                                text = stringResource(R.string.vpn_split_tunnel_select_all),
                                color = TextMuted,
                                fontSize = 11.5.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 6.dp)
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, AmoledBorder),
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    currentSelection = emptySet()
                                }
                        ) {
                            Text(
                                text = stringResource(R.string.vpn_split_tunnel_deselect_all),
                                color = TextMuted,
                                fontSize = 11.5.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 6.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (isLoading) {
                        Box(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = vpnColors.primary, strokeWidth = 2.dp)
                        }
                    } else {
                        androidx.compose.foundation.lazy.LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(filteredApps.size, key = { filteredApps[it].packageName }) { idx ->
                                val app = filteredApps[idx]
                                val isChecked = currentSelection.contains(app.packageName)

                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isChecked) vpnColors.primary.copy(alpha = 0.08f) else Color.Transparent,
                                    border = BorderStroke(1.dp, if (isChecked) vpnColors.primary.copy(alpha = 0.35f) else Color.Transparent),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            currentSelection = if (isChecked) {
                                                currentSelection - app.packageName
                                            } else {
                                                currentSelection + app.packageName
                                            }
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        val iconBitmap = remember(app.packageName) {
                                            app.icon?.let { d ->
                                                try {
                                                    val w = if (d.intrinsicWidth > 0) d.intrinsicWidth else 72
                                                    val h = if (d.intrinsicHeight > 0) d.intrinsicHeight else 72
                                                    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                                                    val c = android.graphics.Canvas(bmp)
                                                    d.setBounds(0, 0, w, h)
                                                    d.draw(c)
                                                    bmp.asImageBitmap()
                                                } catch (_: Throwable) {
                                                    null
                                                }
                                            }
                                        }

                                        if (iconBitmap != null) {
                                            androidx.compose.foundation.Image(
                                                bitmap = iconBitmap,
                                                contentDescription = null,
                                                modifier = Modifier.size(32.dp).clip(RoundedCornerShape(6.dp))
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .size(32.dp)
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(AmoledSurfaceHigh),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = app.label.take(1).uppercase(),
                                                    color = TextMuted,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = app.label,
                                                color = TextWhite,
                                                fontSize = 12.5.sp,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = app.packageName,
                                                color = TextMuted,
                                                fontSize = 10.5.sp,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                            )
                                        }

                                        Checkbox(
                                            checked = isChecked,
                                            onCheckedChange = { checked ->
                                                currentSelection = if (checked) {
                                                    currentSelection + app.packageName
                                                } else {
                                                    currentSelection - app.packageName
                                                }
                                            },
                                            colors = CheckboxDefaults.colors(
                                                checkedColor = vpnColors.primary,
                                                uncheckedColor = AmoledBorder,
                                                checkmarkColor = Color.Black
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = vpnColors.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onSave(currentSelection)
                            }
                    ) {
                        Text(
                            text = stringResource(R.string.vpn_split_tunnel_apply),
                            color = Color.Black,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 11.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsNetworkSection(
    selectedMode: ProxyMode,
    isAdvancedMode: Boolean = false,
    onEnableAdvancedMode: () -> Unit = {},
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.settings_network_title),
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.3.sp,
                color = TextMuted
            )
            SettingsSafetyBadge(
                level = if (isAdvancedMode) SettingsSafetyLevel.EXPERT else SettingsSafetyLevel.SAFE
            )
        }

        if (selectedMode == ProxyMode.MTPROTO) {
            if (!isAdvancedMode) {
                // Friendly Safe Summary Card for MTProto
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.Transparent)
                        .border(1.dp, AmoledBorder, RoundedCornerShape(18.dp))
                        .padding(14.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.settings_port_mtproto),
                                        color = TextWhite,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.5.sp
                                    )
                                    InfoButton { onInfoClick("port") }
                                    Spacer(modifier = Modifier.weight(1f))
                                    SettingsSafetyBadge(
                                        level = SettingsSafetyLevel.SAFE,
                                        customLabel = stringResource(R.string.settings_port_default_badge)
                                    )
                                }
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = stringResource(R.string.settings_port_safe_desc, portText.toIntOrNull() ?: 10808),
                                    color = TextMuted,
                                    fontSize = 11.5.sp,
                                    lineHeight = 16.sp
                                )
                            }
                        }

                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AmoledBorder.copy(alpha = 0.5f)))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.settings_secret_hex),
                                color = TextWhite,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.5.sp
                            )
                            InfoButton { onInfoClick("secret") }
                            Spacer(modifier = Modifier.weight(1f))
                            SettingsSafetyBadge(
                                level = SettingsSafetyLevel.SAFE,
                                customLabel = stringResource(R.string.vpn_status_active_badge)
                            )
                        }
                        Text(
                            text = stringResource(R.string.settings_secret_safe_desc),
                            color = TextMuted,
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            } else {
                // Interactive Fields for Expert Mode
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.Transparent)
                        .border(1.dp, AmoledBorder, RoundedCornerShape(18.dp))
                ) {
                    Column {
                        // 1. PORT MTProto
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
                                    Text(stringResource(R.string.settings_port_mtproto), color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                                    SettingsSafetyBadge(level = SettingsSafetyLevel.EXPERT)
                                    InfoButton { onInfoClick("port") }
                                }
                                Text(
                                    text = if (isPortError) stringResource(R.string.settings_port_range_hint) else stringResource(R.string.settings_port_mtproto_desc),
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

                        // 2. SECRET KEY
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
                                    Text(stringResource(R.string.settings_secret_hex), color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                                    SettingsSafetyBadge(level = SettingsSafetyLevel.EXPERT)
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
                                            contentDescription = stringResource(R.string.settings_btn_change_secret),
                                            tint = TextWhite,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }

                            BasicTextField(
                                value = secretText,
                                onSecretChange,
                                singleLine = true,
                                readOnly = true,
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
            }
        } else {
            // SOCKS5 Mode Settings
            if (!isAdvancedMode) {
                // Friendly Safe Summary Card for SOCKS5
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.Transparent)
                        .border(1.dp, AmoledBorder, RoundedCornerShape(18.dp))
                        .padding(14.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.settings_port_socks5),
                                        color = TextWhite,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.5.sp
                                    )
                                    InfoButton { onInfoClick("port") }
                                    SettingsSafetyBadge(
                                        level = SettingsSafetyLevel.SAFE,
                                        customLabel = stringResource(R.string.settings_port_default_badge)
                                    )
                                }
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = stringResource(R.string.settings_socks5_safe_desc, socks5PortText.toIntOrNull() ?: 10808),
                                    color = TextMuted,
                                    fontSize = 11.5.sp,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }
                }
            } else {
                // Interactive Fields for Expert SOCKS5 Mode
                // 1. CARD PORT SOCKS5
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
                                Text(stringResource(R.string.settings_port_socks5), color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                                SettingsSafetyBadge(level = SettingsSafetyLevel.EXPERT)
                                InfoButton { onInfoClick("port") }
                            }
                            Text(
                                text = if (isSocks5PortError) stringResource(R.string.settings_port_range_hint) else stringResource(R.string.settings_port_socks5_desc),
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

                // 2. CARD SOCKS5 AUTH (RFC 1929)
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
                                Text(stringResource(R.string.settings_socks5_auth_title), color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                                SettingsSafetyBadge(level = SettingsSafetyLevel.EXPERT)
                                InfoButton { onInfoClick("socks5_auth") }
                            }

                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (hasAuth) Socks5Accent.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f),
                                border = BorderStroke(1.dp, if (hasAuth) Socks5Accent.copy(alpha = 0.4f) else AmoledBorder)
                            ) {
                                Text(
                                    text = if (hasAuth) "RFC 1929" else stringResource(R.string.settings_socks5_auth_open),
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
                                text = stringResource(R.string.settings_login),
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
                                            Text(stringResource(R.string.settings_login_open_desc), color = TextMuted.copy(alpha = 0.6f), fontSize = 12.sp)
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
                                text = stringResource(R.string.settings_password),
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
                                                    Text(stringResource(R.string.settings_password_open_desc), color = TextMuted.copy(alpha = 0.6f), fontSize = 12.sp)
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
                                Text(stringResource(R.string.settings_btn_generate_creds), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
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
                                    Text(stringResource(R.string.settings_btn_clear), color = TextWhite, fontSize = 11.5.sp)
                                }
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
    val transportPoolStatus by server.transportPoolStatus.collectAsState()
    val adaptiveDecision by server.adaptiveNetworkDecision.collectAsState()

    Column(
        modifier = Modifier.staggeredEntrance(index = 2),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_perf_title),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.3.sp,
                    color = TextMuted
                )
                SettingsSafetyBadge(level = SettingsSafetyLevel.SAFE)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(stringResource(R.string.settings_wspool_modes), color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
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
                                com.mirrly.tgproxy.core.SpeedPreset.ECO -> stringResource(R.string.settings_speed_eco)
                                com.mirrly.tgproxy.core.SpeedPreset.BALANCED -> stringResource(R.string.settings_speed_balance)
                                com.mirrly.tgproxy.core.SpeedPreset.TURBO -> stringResource(R.string.settings_speed_turbo)
                                com.mirrly.tgproxy.core.SpeedPreset.ULTRA -> stringResource(R.string.settings_speed_ultra)
                                com.mirrly.tgproxy.core.SpeedPreset.AUTO -> stringResource(R.string.settings_speed_auto)
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

        val tcpNoDelayStatusText = when (tcpNoDelayModeState) {
            TcpNoDelayMode.AUTO -> adaptiveDecision.statusDescription
            TcpNoDelayMode.ON -> stringResource(R.string.settings_tcp_nodelay_on)
            TcpNoDelayMode.OFF -> stringResource(R.string.settings_tcp_nodelay_off)
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
                    Text(stringResource(R.string.settings_tcp_nodelay_title), color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
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
                                    TcpNoDelayMode.AUTO -> adaptiveDecision.recommendedTcpNoDelay
                                }
                                config.tcpNoDelay = effective
                                server.setTcpNoDelayMode(mode)
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
                color = if (tcpNoDelayModeState == TcpNoDelayMode.AUTO && adaptiveDecision.recommendedTcpNoDelay) ActiveGreenLed.copy(alpha = 0.85f) else TextMuted,
                fontSize = 11.5.sp,
                lineHeight = 15.sp
            )
        }
    }
}

@Composable
private fun SettingsAdvancedModeToggleCard(
    isAdvancedMode: Boolean,
    onToggle: (Boolean) -> Unit,
    onInfoClick: (String) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val borderAnim by animateColorAsState(
        targetValue = if (isAdvancedMode) ActiveGreenLed.copy(alpha = 0.40f) else AmoledBorder,
        animationSpec = tween(250),
        label = "advModeBorder"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Transparent)
            .border(1.dp, borderAnim, RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_advanced_mode_title),
                    color = TextWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                InfoButton { onInfoClick("advanced_mode") }
            }
            InertialSpringSwitch(
                checked = isAdvancedMode,
                onCheckedChange = { checked ->
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggle(checked)
                }
            )
        }

        AnimatedVisibility(
            visible = isAdvancedMode,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(AmoledBorder)
                )

                // Warning & Disclaimer
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFF59E0B).copy(alpha = 0.08f))
                        .border(1.dp, Color(0xFFF59E0B).copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_advanced_mode_warning),
                        color = Color(0xFFFBBF24),
                        fontSize = 11.sp,
                        lineHeight = 14.5.sp
                    )
                }

                // Automatic rollback notice
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.03f))
                        .border(1.dp, AmoledBorder, RoundedCornerShape(12.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_advanced_mode_rollback_notice),
                        color = TextMuted,
                        fontSize = 11.sp,
                        lineHeight = 14.5.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsAdvancedEngineeringSection(
    config: ProxyConfig,
    onInfoClick: (String) -> Unit,
    onRestartProxy: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val app = MirrlyApplication.instance
    var happyEyeballsDelay by remember { mutableStateOf(config.happyEyeballsDelayMs) }
    var ipFamilyPref by remember { mutableStateOf(config.ipFamilyPreferenceName) }
    var customAnycastEndpoint by remember { mutableStateOf(config.warpUserEndpointOverride) }
    var bufferSize by remember { mutableStateOf(config.bufferSizeBytes) }
    var keepAliveSeconds by remember { mutableStateOf(config.webSocketKeepAliveSeconds) }

    Column(
        modifier = Modifier.staggeredEntrance(index = 3),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_advanced_section_title),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.3.sp,
                    color = TextMuted
                )
                SettingsSafetyBadge(level = SettingsSafetyLevel.DANGER)
            }
        }

        // 1. Happy Eyeballs Delay (RFC 8305)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Transparent)
                .border(1.dp, AmoledBorder, RoundedCornerShape(18.dp))
                .padding(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.settings_happy_eyeballs_title),
                            color = TextWhite,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.5.sp
                        )
                        InfoButton { onInfoClick("happy_eyeballs") }
                    }
                    Text(
                        text = stringResource(R.string.settings_happy_eyeballs_desc),
                        color = TextMuted,
                        fontSize = 11.5.sp,
                        lineHeight = 15.sp
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(100L, 200L, 300L).forEach { delayMs ->
                        val isSelected = happyEyeballsDelay == delayMs
                        val chipBorder by animateColorAsState(
                            targetValue = if (isSelected) ActiveGreenLed else AmoledBorder,
                            animationSpec = tween(200),
                            label = "heBorder_$delayMs"
                        )
                        val chipBg by animateColorAsState(
                            targetValue = if (isSelected) ActiveGreenLed.copy(alpha = 0.08f) else Color.Transparent,
                            animationSpec = tween(200),
                            label = "heBg_$delayMs"
                        )
                        val chipTextColor by animateColorAsState(
                            targetValue = if (isSelected) ActiveGreenLed else TextWhite,
                            animationSpec = tween(200),
                            label = "heText_$delayMs"
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
                                    happyEyeballsDelay = delayMs
                                    app.proxyServer.applyAdvancedNetworkSettings(happyEyeballsDelayMs = delayMs)
                                    app.saveConfig()
                                }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.unit_ms_format, delayMs),
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = chipTextColor
                            )
                        }
                    }
                }
            }
        }

        // 2. IP Protocol Stack Preference
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Transparent)
                .border(1.dp, AmoledBorder, RoundedCornerShape(18.dp))
                .padding(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.settings_ip_family_title),
                            color = TextWhite,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.5.sp
                        )
                        InfoButton { onInfoClick("ip_family") }
                    }
                    Text(
                        text = stringResource(R.string.settings_ip_family_desc),
                        color = TextMuted,
                        fontSize = 11.5.sp,
                        lineHeight = 15.sp
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        "DUAL_STACK" to stringResource(R.string.settings_ip_family_dual),
                        "IPV4_ONLY" to stringResource(R.string.settings_ip_family_v4),
                        "IPV6_FIRST" to stringResource(R.string.settings_ip_family_v6)
                    ).forEach { (prefKey, label) ->
                        val isSelected = ipFamilyPref == prefKey
                        val chipBorder by animateColorAsState(
                            targetValue = if (isSelected) ActiveGreenLed else AmoledBorder,
                            animationSpec = tween(200),
                            label = "ipBorder_$prefKey"
                        )
                        val chipBg by animateColorAsState(
                            targetValue = if (isSelected) ActiveGreenLed.copy(alpha = 0.08f) else Color.Transparent,
                            animationSpec = tween(200),
                            label = "ipBg_$prefKey"
                        )
                        val chipTextColor by animateColorAsState(
                            targetValue = if (isSelected) ActiveGreenLed else TextWhite,
                            animationSpec = tween(200),
                            label = "ipText_$prefKey"
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
                                    ipFamilyPref = prefKey
                                    config.ipFamilyPreferenceName = prefKey
                                    app.proxyServer.applyAdvancedNetworkSettings(
                                        ipFamilyPreference = config.ipFamilyPreference
                                    )
                                    app.saveConfig()
                                }
                                .padding(vertical = 8.dp, horizontal = 2.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                fontSize = 10.5.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = chipTextColor,
                                textAlign = TextAlign.Center,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        // 3. Custom Anycast Endpoint WARP
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Transparent)
                .border(1.dp, AmoledBorder, RoundedCornerShape(18.dp))
                .padding(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.settings_warp_anycast_title),
                            color = TextWhite,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.5.sp
                        )
                        InfoButton { onInfoClick("warp_anycast") }
                    }
                    Text(
                        text = stringResource(R.string.settings_warp_anycast_desc),
                        color = TextMuted,
                        fontSize = 11.5.sp,
                        lineHeight = 15.sp
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    BasicTextField(
                        value = customAnycastEndpoint,
                        onValueChange = { newValue ->
                            customAnycastEndpoint = newValue
                            config.warpUserEndpointOverride = newValue.trim()
                            if (config.isVpnAnyWarpUplink) {
                                app.proxyServer.applyWarpEndpoint(config.warpUserEndpointOverride)
                            }
                            app.saveConfig()
                        },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = TextWhite,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        cursorBrush = SolidColor(ActiveGreenLed),
                        decorationBox = { inner ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.White.copy(alpha = 0.04f))
                                    .border(1.dp, AmoledBorder, RoundedCornerShape(10.dp))
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (customAnycastEndpoint.isEmpty()) {
                                    Text(
                                        text = config.warpPeerEndpoint.ifEmpty { "188.114.96.1:8095" },
                                        color = TextMuted.copy(alpha = 0.5f),
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                inner()
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )

                    if (customAnycastEndpoint.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.White.copy(alpha = 0.06f))
                                .clickable {
                                    customAnycastEndpoint = ""
                                    config.warpUserEndpointOverride = ""
                                    if (config.isVpnAnyWarpUplink) {
                                        app.proxyServer.applyWarpEndpoint(config.warpPeerEndpoint)
                                    }
                                    app.saveConfig()
                                }
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(stringResource(R.string.action_reset), fontSize = 11.sp, color = TextMuted)
                        }
                    }
                }
            }
        }

        // 4. Socket Buffer Size (TCP RX/TX)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Transparent)
                .border(1.dp, AmoledBorder, RoundedCornerShape(18.dp))
                .padding(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.settings_socket_buffer_title),
                            color = TextWhite,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.5.sp
                        )
                        InfoButton { onInfoClick("socket_buffer") }
                    }
                    Text(
                        text = stringResource(R.string.settings_socket_buffer_desc),
                        color = TextMuted,
                        fontSize = 11.5.sp,
                        lineHeight = 15.sp
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        131072 to "128 KB",
                        262144 to "256 KB",
                        524288 to "512 KB",
                        1048576 to "1 MB",
                        2097152 to "2 MB"
                    ).forEach { (sizeBytes, label) ->
                        val isSelected = bufferSize == sizeBytes
                        val chipBorder by animateColorAsState(
                            targetValue = if (isSelected) ActiveGreenLed else AmoledBorder,
                            animationSpec = tween(200),
                            label = "bufBorder_$sizeBytes"
                        )
                        val chipBg by animateColorAsState(
                            targetValue = if (isSelected) ActiveGreenLed.copy(alpha = 0.08f) else Color.Transparent,
                            animationSpec = tween(200),
                            label = "bufBg_$sizeBytes"
                        )
                        val chipTextColor by animateColorAsState(
                            targetValue = if (isSelected) ActiveGreenLed else TextWhite,
                            animationSpec = tween(200),
                            label = "bufText_$sizeBytes"
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
                                    bufferSize = sizeBytes
                                    app.proxyServer.applyBufferSizeBytes(sizeBytes)
                                    app.saveConfig()
                                }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = chipTextColor,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }

        // 5. WebSocket Keep-Alive Interval
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Transparent)
                .border(1.dp, AmoledBorder, RoundedCornerShape(18.dp))
                .padding(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.settings_ws_keepalive_title),
                            color = TextWhite,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.5.sp
                        )
                        InfoButton { onInfoClick("ws_keepalive") }
                    }
                    Text(
                        text = stringResource(R.string.settings_ws_keepalive_desc),
                        color = TextMuted,
                        fontSize = 11.5.sp,
                        lineHeight = 15.sp
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(15, 30, 60).forEach { seconds ->
                        val isSelected = keepAliveSeconds == seconds
                        val chipBorder by animateColorAsState(
                            targetValue = if (isSelected) ActiveGreenLed else AmoledBorder,
                            animationSpec = tween(200),
                            label = "kaBorder_$seconds"
                        )
                        val chipBg by animateColorAsState(
                            targetValue = if (isSelected) ActiveGreenLed.copy(alpha = 0.08f) else Color.Transparent,
                            animationSpec = tween(200),
                            label = "kaBg_$seconds"
                        )
                        val chipTextColor by animateColorAsState(
                            targetValue = if (isSelected) ActiveGreenLed else TextWhite,
                            animationSpec = tween(200),
                            label = "kaText_$seconds"
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
                                    keepAliveSeconds = seconds
                                    app.proxyServer.applyAdvancedNetworkSettings(webSocketKeepAliveSeconds = seconds)
                                    app.saveConfig()
                                }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.unit_sec_format, seconds),
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = chipTextColor
                            )
                        }
                    }
                }
            }
        }

        // 6. Reset Engineering Defaults
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color.White.copy(alpha = 0.04f),
            border = BorderStroke(1.dp, AmoledBorder),
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    app.prefsManager.resetAdvancedSettingsToDefaults(config)
                    app.saveConfig()
                    app.proxyServer.applyAdvancedNetworkSettings()
                    app.proxyServer.applyBufferSizeBytes(config.bufferSizeBytes)
                    happyEyeballsDelay = config.happyEyeballsDelayMs
                    ipFamilyPref = config.ipFamilyPreferenceName
                    customAnycastEndpoint = config.warpUserEndpointOverride
                    bufferSize = config.bufferSizeBytes
                    keepAliveSeconds = config.webSocketKeepAliveSeconds
                    onRestartProxy()
                    Toast.makeText(
                        context,
                        context.getString(R.string.toast_advanced_settings_reverted),
                        Toast.LENGTH_SHORT
                    ).show()
                }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 13.dp, horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.settings_advanced_btn_reset_defaults),
                    color = TextMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.3.sp
                )
            }
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
                text = stringResource(R.string.settings_cf_title),
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
                                text = stringResource(R.string.settings_worker_manager),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite
                            )
                            Text(
                                text = stringResource(R.string.settings_worker_active, activeWorker.name),
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
                                text = stringResource(R.string.settings_worker_guide),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite
                            )
                            Text(
                                text = stringResource(R.string.settings_worker_guide_desc),
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
                        stringResource(R.string.settings_cf_worker_desc_socks5)
                    } else {
                        stringResource(R.string.settings_cf_worker_desc_mtproto)
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
    var activeAwgStrategy by remember { mutableStateOf(config.awgStrategy) }
    var customAwgIniText by remember { mutableStateOf(config.awgCustomIni) }

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
                    text = stringResource(R.string.settings_vpn_uplink_section),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.3.sp,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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
                    text = "VPN",
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Black,
                    color = ActiveGreenLed,
                    letterSpacing = 0.8.sp
                )
            }
        }

        // note note note
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                UplinkModeChip(
                    mode = com.mirrly.tgproxy.core.UplinkMode.WARP_CASCADE,
                    displayName = "WARP Cascade",
                    badge = "Dual Anycast",
                    subtitle = stringResource(R.string.settings_uplink_cascade_sub),
                    isSelected = uplinkMode == com.mirrly.tgproxy.core.UplinkMode.WARP_CASCADE,
                    onClick = { onSelectUplinkMode(com.mirrly.tgproxy.core.UplinkMode.WARP_CASCADE) },
                    modifier = Modifier.weight(1f)
                )
                UplinkModeChip(
                    mode = com.mirrly.tgproxy.core.UplinkMode.VLESS,
                    displayName = "VLESS over WS",
                    badge = "Anycast CDN",
                    subtitle = "TLS 1.3 Camouflage",
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
                    subtitle = stringResource(R.string.settings_uplink_masque_sub),
                    isSelected = uplinkMode == com.mirrly.tgproxy.core.UplinkMode.MASQUE,
                    onClick = { onSelectUplinkMode(com.mirrly.tgproxy.core.UplinkMode.MASQUE) },
                    modifier = Modifier.weight(1f)
                )
                UplinkModeChip(
                    mode = com.mirrly.tgproxy.core.UplinkMode.AWG,
                    displayName = "WARP AWG",
                    badge = "WireGuard",
                    subtitle = stringResource(R.string.settings_anycast_obfuscation),
                    isSelected = uplinkMode == com.mirrly.tgproxy.core.UplinkMode.AWG,
                    onClick = { onSelectUplinkMode(com.mirrly.tgproxy.core.UplinkMode.AWG) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // note profile Cloudflare WARP MASQUE
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
                                text = if (hasProfile) "${warpProfile?.clientIpv4} • Anycast" else stringResource(R.string.settings_unregistered_autoreg),
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
                                    text = if (warpProfile != null) stringResource(R.string.settings_btn_refresh) else stringResource(R.string.settings_btn_create),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // note note: note profile + AmneziaWG note
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
                                    text = stringResource(R.string.settings_reg_details),
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
                                    Toast.makeText(context, context.getString(R.string.settings_amnezia_copied), Toast.LENGTH_SHORT).show()
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

                    // note note note
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
                                    Text(stringResource(R.string.settings_anycast_endpoint), fontSize = 10.5.sp, color = TextMuted)
                                    Text(
                                        "${warpProfile?.peerEndpoint} (${stringResource(R.string.label_tspu_bypass)})",
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
                                    Text(stringResource(R.string.settings_mtls_cert), fontSize = 10.5.sp, color = TextMuted)
                                    Text(
                                        if (warpProfile?.clientCertBase64?.isNotBlank() == true) stringResource(R.string.settings_cert_active_x509) else stringResource(R.string.settings_mode_basic),
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
                                        Text(stringResource(R.string.settings_warp_plus_license), fontSize = 10.5.sp, color = TextMuted)
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

                    // Блок выбора стратегии обфускации AmneziaWG (AWG & WARP_CASCADE)
                    AnimatedVisibility(
                        visible = uplinkMode == com.mirrly.tgproxy.core.UplinkMode.AWG || uplinkMode == com.mirrly.tgproxy.core.UplinkMode.WARP_CASCADE,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
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
                                Column {
                                    Text(
                                        text = stringResource(R.string.awg_strategy_title),
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextWhite
                                    )
                                    Text(
                                        text = stringResource(R.string.awg_strategy_desc),
                                        fontSize = 9.5.sp,
                                        color = TextMuted
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    com.mirrly.tgproxy.core.AwgObfuscationStrategy.entries.forEach { strat ->
                                        val isSel = strat == activeAwgStrategy
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (isSel) ActiveGreenLed.copy(alpha = 0.15f) else Color.Transparent,
                                            border = BorderStroke(1.dp, if (isSel) ActiveGreenLed.copy(alpha = 0.6f) else AmoledBorder),
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable {
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    activeAwgStrategy = strat
                                                    config.awgStrategyName = strat.name
                                                    app.saveConfig()
                                                    val awgIni = config.getAmneziaWgConfig(cleanEndpoint = config.effectivePeerEndpoint)
                                                    com.mirrly.tgproxy.core.NativeProxy.setAwgConfig(awgIni)
                                                    onRestartProxy()
                                                }
                                        ) {
                                            Text(
                                                text = strat.displayName,
                                                fontSize = 9.5.sp,
                                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                                color = if (isSel) ActiveGreenLed else TextWhite,
                                                textAlign = TextAlign.Center,
                                                maxLines = 1,
                                                modifier = Modifier.padding(vertical = 6.dp, horizontal = 2.dp)
                                            )
                                        }
                                    }
                                }

                                val descText = when (activeAwgStrategy) {
                                    com.mirrly.tgproxy.core.AwgObfuscationStrategy.FAST -> stringResource(R.string.awg_strategy_fast_desc)
                                    com.mirrly.tgproxy.core.AwgObfuscationStrategy.BALANCED -> stringResource(R.string.awg_strategy_balanced_desc)
                                    com.mirrly.tgproxy.core.AwgObfuscationStrategy.DEEP_STEALTH -> stringResource(R.string.awg_strategy_deep_desc)
                                    com.mirrly.tgproxy.core.AwgObfuscationStrategy.CUSTOM -> stringResource(R.string.awg_strategy_custom_desc)
                                }
                                Text(
                                    text = descText,
                                    fontSize = 9.sp,
                                    color = TextMuted,
                                    lineHeight = 12.sp
                                )

                                if (activeAwgStrategy == com.mirrly.tgproxy.core.AwgObfuscationStrategy.CUSTOM) {
                                    OutlinedTextField(
                                        value = customAwgIniText,
                                        onValueChange = {
                                            customAwgIniText = it
                                            config.awgCustomIni = it
                                            app.saveConfig()
                                            if (it.isNotBlank()) {
                                                com.mirrly.tgproxy.core.NativeProxy.setAwgConfig(it)
                                            }
                                        },
                                        placeholder = { Text(stringResource(R.string.awg_custom_ini_hint), fontSize = 10.sp, color = TextMuted) },
                                        modifier = Modifier.fillMaxWidth().height(100.dp),
                                        textStyle = androidx.compose.ui.text.TextStyle(
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                            fontSize = 9.5.sp,
                                            color = TextWhite
                                        ),
                                        maxLines = 6
                                    )
                                }
                            }
                        }
                    }

                    // note WARP note Opera VPN Upstream
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
                                        text = stringResource(R.string.settings_opera_upstream_title),
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextWhite
                                    )
                                    Text(
                                        text = stringResource(R.string.settings_opera_upstream_desc),
                                        fontSize = 9.5.sp,
                                        color = TextMuted
                                    )
                                }
                                InertialSpringSwitch(
                                    checked = useOperaForWarp,
                                    onCheckedChange = { enabled ->
                                        useOperaForWarp = enabled
                                        config.useOperaVpnForWarp = enabled
                                        config.allowOperaDirectExit = enabled
                                        app.saveConfig()
                                        app.proxyServer.applyOperaVpnConfig()
                                        onRestartProxy()
                                    }
                                )
                            }

                            if (useOperaForWarp) {
                                Text(
                                    text = stringResource(R.string.settings_opera_node_select),
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
                                                    Toast.makeText(context, context.getString(R.string.settings_opera_node_selected, node.name), Toast.LENGTH_SHORT).show()
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

        // note note VLESS over WebSocket
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

                    // note note note VLESS
                    Text(
                        text = stringResource(R.string.settings_vless_pages_title),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = TextMuted
                    )

                    // note note note with note
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
                                        Toast.makeText(context, context.getString(R.string.settings_vless_page_selected, preset.name), Toast.LENGTH_SHORT).show()
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

                    // note Cloudflare Worker for VLESS
                    Text(
                        text = stringResource(R.string.settings_vless_cf_workers_title),
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
                                        Toast.makeText(context, context.getString(R.string.settings_vless_worker_selected, worker.name), Toast.LENGTH_SHORT).show()
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

                    // note Opera VPN (VLESS note)
                    Text(
                        text = stringResource(R.string.settings_vless_opera_servers_title),
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
                                        Toast.makeText(context, context.getString(R.string.settings_vless_opera_selected, node.name), Toast.LENGTH_SHORT).show()
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

                    // note UUID note
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
                                Toast.makeText(context, context.getString(R.string.settings_vless_uuid_copied), Toast.LENGTH_SHORT).show()
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 6.dp)) {
                                Text(stringResource(R.string.settings_vless_uuid_client), fontSize = 9.5.sp, color = TextMuted)
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
                                contentDescription = stringResource(R.string.action_copy),
                                tint = ActiveGreenLed,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }

                    // note note note
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
                                    text = stringResource(R.string.settings_btn_new_uuid),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextWhite
                                )
                            }
                        }

                        // note vless:// note note note
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
                                                Toast.makeText(context, context.getString(R.string.settings_vless_node_imported, parsed.name), Toast.LENGTH_SHORT).show()
                                            }
                                            is com.mirrly.tgproxy.core.VlessParseResult.Failure -> {
                                                Toast.makeText(context, context.getString(R.string.settings_vless_err, parseResult.reason), Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    } else {
                                        Toast.makeText(context, context.getString(R.string.settings_vless_no_link_in_clip), Toast.LENGTH_SHORT).show()
                                    }
                                }
                        ) {
                            Text(
                                text = stringResource(R.string.settings_vless_btn_import),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextWhite,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 7.dp)
                            )
                        }

                        // note note note note note note
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
                                        Toast.makeText(context, context.getString(R.string.settings_vless_nodes_available, fresh.size.toString()), Toast.LENGTH_SHORT).show()
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
                                    text = if (isFetchingPresets) stringResource(R.string.state_loading) else stringResource(R.string.settings_btn_refresh),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextWhite
                                )
                            }
                        }

                        // note note
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
                                    Toast.makeText(context, context.getString(R.string.settings_vless_link_copied), Toast.LENGTH_SHORT).show()
                                }
                        ) {
                            Text(
                                text = stringResource(R.string.settings_vless_btn_export),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = ActiveGreenLed,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 7.dp)
                            )
                        }
                    }

                    // note note with note note
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
                                    Text(stringResource(R.string.settings_vless_active_host), fontSize = 10.5.sp, color = TextMuted)
                                    Text(config.getEffectiveVlessDomain(), fontSize = 10.5.sp, color = TextWhite, fontWeight = FontWeight.Medium)
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    val currentPreset = vlessPresets.firstOrNull { it.id == activePresetId }
                                    Text(stringResource(R.string.settings_vless_node_region), fontSize = 10.5.sp, color = TextMuted)
                                    Text(currentPreset?.region ?: stringResource(R.string.vless_preset_default_region), fontSize = 10.5.sp, color = ActiveGreenLed, fontWeight = FontWeight.Medium)
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(stringResource(R.string.settings_vless_ws_path), fontSize = 10.5.sp, color = TextMuted)
                                    Text(config.vlessPath, fontSize = 10.5.sp, color = TextWhite, fontWeight = FontWeight.Medium)
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(stringResource(R.string.settings_vless_transport_port), fontSize = 10.5.sp, color = TextMuted)
                                    Text("443 (HTTPS/WSS)", fontSize = 10.5.sp, color = TextWhite, fontWeight = FontWeight.Medium)
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(stringResource(R.string.settings_vless_browser_mimic), fontSize = 10.5.sp, color = TextMuted)
                                    Text("Chrome 128", fontSize = 10.5.sp, color = ActiveGreenLed, fontWeight = FontWeight.SemiBold)
                                }
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
    onOpenOnboarding: () -> Unit = {},
    onOpenDiagnosticReport: () -> Unit = {},
    onInfoClick: (String) -> Unit,
    onLanguageChange: (String) -> Unit = {}
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val app = MirrlyApplication.instance
    var disableAnimations by remember { mutableStateOf(app.prefsManager.areAnimationsDisabled()) }
    val scheduleConfig = remember { app.prefsManager.loadScheduleConfig() }

    Column(
        modifier = Modifier.staggeredEntrance(index = 4),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_system_title),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.3.sp,
                    color = TextMuted
                )
                SettingsSafetyBadge(level = SettingsSafetyLevel.SAFE)
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
                    Text(stringResource(R.string.settings_autostart_title), color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    InfoButton { onInfoClick("autostart") }
                }
                Text(stringResource(R.string.settings_autostart_desc), color = TextMuted, fontSize = 11.5.sp)
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
                Text(stringResource(R.string.settings_sleep_timer_title), color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    text = if (timerState.isActive) stringResource(R.string.settings_sleep_timer_active, timerState.formatRemainingTime()) else stringResource(R.string.settings_sleep_timer_off),
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
                Text(stringResource(R.string.settings_schedule_title), color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    text = if (scheduleConfig.isEnabled) stringResource(R.string.settings_schedule_active, scheduleConfig.getSummaryText(context)) else stringResource(R.string.settings_schedule_off),
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

        // ── note note (BATTERY SAVER GUARD) ──
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
                        Text(stringResource(R.string.settings_battery_guard_title), color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        InfoButton { onInfoClick("battery_guard") }
                    }
                    Text(
                        text = if (isBatteryGuardEnabled) stringResource(R.string.settings_battery_guard_active, batteryGuardThreshold.toString()) else stringResource(R.string.settings_battery_guard_off),
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
                            text = stringResource(R.string.settings_battery_threshold),
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
                                text = stringResource(R.string.settings_battery_android_saver),
                                color = TextWhite.copy(alpha = 0.9f),
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = stringResource(R.string.settings_battery_android_saver_desc),
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

        // ── note QOS note note ──
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
                    Text(stringResource(R.string.settings_qos_title), color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    InfoButton { onInfoClick("adaptive_qos") }
                }
                Text(
                    text = if (isAdaptiveQoSEnabled) stringResource(R.string.settings_qos_active) else stringResource(R.string.settings_qos_off),
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

        // ── note note note note (DOZE MODE) ──
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
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            try {
                                val intent = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                Toast.makeText(context, context.getString(R.string.settings_err_open_battery_settings), Toast.LENGTH_SHORT).show()
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
                    Text(stringResource(R.string.settings_doze_mode_title), color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    InfoButton { onInfoClick("doze_mode") }
                }
                Text(
                    text = if (isIgnoringBatteryOptimizations) {
                        stringResource(R.string.settings_doze_mode_allowed)
                    } else {
                        stringResource(R.string.settings_doze_mode_optimizing)
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
                    text = if (isIgnoringBatteryOptimizations) stringResource(R.string.settings_doze_status_active) else stringResource(R.string.settings_doze_btn_configure),
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
                    Text(stringResource(R.string.settings_power_saver_title), color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    InfoButton { onInfoClick("disable_animations") }
                }
                Text(stringResource(R.string.settings_power_saver_desc), color = TextMuted, fontSize = 11.5.sp)
            }
            InertialSpringSwitch(
                checked = disableAnimations,
                onCheckedChange = { newValue ->
                    disableAnimations = newValue
                    app.prefsManager.setAnimationsDisabled(newValue)
                }
            )
        }

        // ── note note (APP LANGUAGE) ──
        val currentAppLanguage by app.prefsManager.appLanguageFlow.collectAsState()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Transparent)
                .border(1.dp, AmoledBorder, RoundedCornerShape(18.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column {
                Text(
                    text = stringResource(R.string.settings_language_title),
                    color = TextWhite,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                Text(
                    text = stringResource(R.string.settings_language_desc),
                    color = TextMuted,
                    fontSize = 11.5.sp
                )
            }

            // Two fixed, equal-width slots per row keep language controls compact on 320–360dp phones.
            // A single four-item Row overflows as soon as a locale label becomes longer.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "system" to stringResource(R.string.settings_language_system),
                    "ru" to stringResource(R.string.settings_language_ru),
                    "en" to stringResource(R.string.settings_language_en),
                    "fa" to stringResource(R.string.settings_language_fa)
                ).chunked(2).forEach { languageRow ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        languageRow.forEach { (langCode, langLabel) ->
                    val isSelected = currentAppLanguage == langCode
                    val chipBorder by animateColorAsState(
                        targetValue = if (isSelected) ActiveGreenLed else AmoledBorder,
                        animationSpec = tween(200),
                        label = "langBorder_$langCode"
                    )
                    val chipBg by animateColorAsState(
                        targetValue = if (isSelected) ActiveGreenLed.copy(alpha = 0.12f) else Color.Transparent,
                        animationSpec = tween(200),
                        label = "langBg_$langCode"
                    )
                    val chipTextColor by animateColorAsState(
                        targetValue = if (isSelected) ActiveGreenLed else TextWhite,
                        animationSpec = tween(200),
                        label = "langText_$langCode"
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
                                if (currentAppLanguage == langCode) return@clickable
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onLanguageChange(langCode)
                            }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = langLabel,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = chipTextColor,
                            maxLines = 1,
                            textAlign = TextAlign.Center
                        )
                    }
                        }
                        if (languageRow.size == 1) Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        // ── note note ──
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color.Transparent,
            border = BorderStroke(1.dp, AmoledBorder),
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onOpenOnboarding()
                }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = stringResource(R.string.settings_onboarding_replay),
                        color = TextWhite,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.5.sp
                    )
                    Text(
                        text = stringResource(R.string.settings_onboarding_replay_desc),
                        color = TextMuted,
                        fontSize = 11.5.sp
                    )
                }
                Icon(
                    painter = painterResource(id = R.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(16.dp)
                )
            }
        }


        // ── ДИАГНОСТИЧЕСКИЙ ОТЧЁТ (DIAGNOSTIC REPORT) ──
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color.Transparent,
            border = BorderStroke(1.dp, AmoledBorder),
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onOpenDiagnosticReport()
                }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = stringResource(R.string.settings_tile_diagnostic_report_title),
                        color = TextWhite,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.5.sp
                    )
                    Text(
                        text = stringResource(R.string.settings_tile_diagnostic_report_desc),
                        color = TextMuted,
                        fontSize = 11.5.sp
                    )
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
}

@Composable
private fun SettingsAboutSection(
    onOpenAbout: () -> Unit,
    onDonateClick: () -> Unit,
    onOpenUpdate: () -> Unit,
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
            text = stringResource(R.string.settings_about_category_title),
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
            // ── 1. STANDOUT HALL OF FAME BUTTON ──
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
                                text = stringResource(R.string.settings_hall_of_fame_title),
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
                                    text = stringResource(R.string.badge_top),
                                    fontSize = 8.5.sp,
                                    fontWeight = FontWeight.Black,
                                    color = ActiveGreenLed,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                        Text(
                            text = stringResource(R.string.settings_hall_of_fame_desc),
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
                            text = stringResource(R.string.settings_about_dev_title),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite
                        )
                        Text(
                            text = stringResource(R.string.settings_about_dev_desc),
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
                            text = stringResource(R.string.settings_support_dev),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite
                        )
                        Text(
                            text = stringResource(R.string.settings_support_dev_desc),
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
                                                context.getString(R.string.settings_update_actual),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    },
                                    onFailure = { err ->
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.settings_update_check_err, err.localizedMessage ?: ""),
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
                            text = if (isUpdateAvailable) stringResource(R.string.settings_update_available) else stringResource(R.string.settings_btn_check_updates),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = titleColor
                        )
                        Text(
                            text = when {
                                isCheckingUpdate -> stringResource(R.string.settings_update_checking)
                                isUpdateAvailable -> stringResource(R.string.settings_update_available_tap)
                                else -> stringResource(R.string.settings_update_search_github)
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
    val (dlgTitleRes, dlgBodyRes) = when (infoKey) {
        "uplink_modes_info" -> R.string.info_uplink_modes_title to R.string.info_uplink_modes_body
        "warp_masque_info" -> R.string.info_warp_masque_title to R.string.info_warp_masque_body
        "vless_info" -> R.string.info_vless_title to R.string.info_vless_body
        "port", "ports_info" -> R.string.info_ports_title to R.string.info_ports_body
        "secret", "secret_info" -> R.string.info_secret_title to R.string.info_secret_body
        "cf_domain", "worker_info" -> R.string.info_worker_title to R.string.info_worker_body
        "autostart", "autostart_info" -> R.string.info_autostart_title to R.string.info_autostart_body
        "preset", "wspool_info" -> R.string.info_wspool_title to R.string.info_wspool_body
        "tcp_nodelay", "tcp_nodelay_info" -> R.string.info_tcp_nodelay_title to R.string.info_tcp_nodelay_body
        "disable_animations", "powersaver_info" -> R.string.info_powersaver_title to R.string.info_powersaver_body
        "protocols_info" -> R.string.info_protocols_title to R.string.info_protocols_body
        "socks5_auth", "socks5_auth_info" -> R.string.info_socks5_auth_title to R.string.info_socks5_auth_body
        "battery_guard", "battery_guard_info" -> R.string.info_battery_guard_title to R.string.info_battery_guard_body
        "adaptive_qos", "qos_info" -> R.string.info_qos_title to R.string.info_qos_body
        "doze_mode", "doze_mode_info" -> R.string.info_doze_mode_title to R.string.info_doze_mode_body
        "doh_providers", "doh_info" -> R.string.info_doh_title to R.string.info_doh_body
        "advanced_mode" -> R.string.settings_advanced_mode_title to R.string.settings_advanced_mode_desc
        "happy_eyeballs" -> R.string.info_happy_eyeballs_title to R.string.info_happy_eyeballs_body
        "ip_family" -> R.string.info_ip_family_title to R.string.info_ip_family_body
        "warp_anycast" -> R.string.info_warp_anycast_title to R.string.info_warp_anycast_body
        "socket_buffer" -> R.string.info_socket_buffer_title to R.string.info_socket_buffer_body
        "ws_keepalive" -> R.string.info_ws_keepalive_title to R.string.info_ws_keepalive_body
        "vpn_core_info" -> R.string.vpn_architecture_title to R.string.vpn_splittunnel_desc
        else -> return
    }
    val dlgTitle = stringResource(dlgTitleRes)
    val dlgBody = stringResource(dlgBodyRes)
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
        // note note with note note note
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
                    text = stringResource(R.string.settings_doh_section_header),
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
                // note note note note
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
                            text = stringResource(R.string.doh_btn_enable_all, 14),
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
                            text = stringResource(R.string.doh_btn_enable_all_ok, 14),
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = ActiveGreenLed,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                // note note note by note
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
                        text = stringResource(R.string.doh_btn_reset),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isProxyRunning) TextMuted else TextWhite.copy(alpha = 0.85f),
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
            }
        }

        // note note note note note note note note
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
                        text = stringResource(R.string.doh_notice_proxy_running),
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
                            text = stringResource(R.string.action_stop),
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFEF4444),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        }

        // note note note note
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
                            stringResource(R.string.doh_testing_progress, cur.toString(), tot.toString())
                        } else {
                            stringResource(R.string.doh_smart_selection_title)
                        },
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isProxyRunning) TextMuted else ActiveGreenLed
                    )
                    Text(
                        text = if (isBenchmarking) stringResource(R.string.doh_measuring_rtt)
                               else if (isProxyRunning) stringResource(R.string.doh_stop_to_measure)
                               else stringResource(R.string.doh_auto_pick_fastest),
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
                            text = stringResource(R.string.doh_btn_test),
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isProxyRunning) TextMuted else ActiveGreenLed,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        }

        // note note note note
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

        // note note note note DoH-note
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color.Transparent,
            border = BorderStroke(1.dp, if (isListExpanded) ActiveGreenLed.copy(alpha = 0.35f) else AmoledBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // note title
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
                                    text = stringResource(R.string.doh_list_title),
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
                                        text = stringResource(R.string.doh_list_ratio, enabledProviderIds.size.toString(), allProviders.size.toString()),
                                        fontSize = 8.5.sp,
                                        fontWeight = FontWeight.Black,
                                        color = ActiveGreenLed,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                }
                            }
                            Text(
                                text = if (isListExpanded) stringResource(R.string.doh_hide_list_hint) else stringResource(R.string.doh_active_race_hint, enabledProviderIds.size.toString()),
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

                // note note note note note
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
                                                    stringResource(R.string.unit_ms_val, result.latencyMs.toString())
                                                )
                                                com.mirrly.tgproxy.core.DohHealthStatus.GOOD -> DohBadgeStyle(
                                                    ActiveGreenLed.copy(alpha = 0.10f),
                                                    ActiveGreenLed.copy(alpha = 0.30f),
                                                    ActiveGreenLed,
                                                    stringResource(R.string.unit_ms_val, result.latencyMs.toString())
                                                )
                                                com.mirrly.tgproxy.core.DohHealthStatus.MODERATE,
                                                com.mirrly.tgproxy.core.DohHealthStatus.SLOW -> DohBadgeStyle(
                                                    Color.Transparent,
                                                    AmoledBorder,
                                                    TextWhite.copy(alpha = 0.85f),
                                                    stringResource(R.string.unit_ms_val, result.latencyMs.toString())
                                                )
                                                com.mirrly.tgproxy.core.DohHealthStatus.BLOCKED -> DohBadgeStyle(
                                                    Color(0xFFEF4444).copy(alpha = 0.12f),
                                                    Color(0xFFEF4444).copy(alpha = 0.35f),
                                                    Color(0xFFEF4444),
                                                    stringResource(R.string.doh_status_tspu_block)
                                                )
                                                com.mirrly.tgproxy.core.DohHealthStatus.TIMEOUT -> DohBadgeStyle(
                                                    Color(0xFFEF4444).copy(alpha = 0.12f),
                                                    Color(0xFFEF4444).copy(alpha = 0.35f),
                                                    Color(0xFFEF4444),
                                                    stringResource(R.string.doh_status_timeout)
                                                )
                                                com.mirrly.tgproxy.core.DohHealthStatus.POISONED -> DohBadgeStyle(
                                                    Color(0xFFEF4444).copy(alpha = 0.12f),
                                                    Color(0xFFEF4444).copy(alpha = 0.35f),
                                                    Color(0xFFEF4444),
                                                    stringResource(R.string.doh_status_ip_poison)
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
                                                        text = stringResource(R.string.doh_badge_top),
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
                                                    text = stringResource(R.string.doh_status_pending),
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
                                                        text = stringResource(R.string.doh_status_tspu_ok),
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
                                                        text = stringResource(R.string.doh_status_rkn_block),
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

@Composable
private fun SettingsMtprotoCdnOverviewCard() {
    Column(
        modifier = Modifier.staggeredEntrance(index = 4),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.settings_mtproto_routing_section),
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.3.sp,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF29B6F6).copy(alpha = 0.12f))
                    .border(1.dp, Color(0xFF29B6F6).copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "FLOWSEAL CDN",
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFF29B6F6),
                    letterSpacing = 0.8.sp
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color.Transparent,
            border = BorderStroke(1.dp, AmoledBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_mtproto_routing_title),
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite
                )
                Text(
                    text = stringResource(R.string.settings_mtproto_routing_body),
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    color = TextMuted,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
