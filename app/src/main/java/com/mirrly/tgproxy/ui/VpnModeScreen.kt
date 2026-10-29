/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * GNU GPL v3+  <https://www.gnu.org/licenses/>
 */

package com.mirrly.tgproxy.ui
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.mirrly.tgproxy.core.VpnDnsMode
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.ui.theme.*
import com.mirrly.tgproxy.service.MirrlyVpnService
import com.mirrly.tgproxy.service.humanBytes
import androidx.compose.runtime.withFrameNanos
import kotlin.math.cos
import kotlin.math.sin

// =============================================================================
// RotatingVpnRing — Specialized Kinetic Cyber Orbital Ring for VPN Mode
// Features: dual undulating sine-wave sweep arcs, glowing comet heads,
// counter-rotating satellite orbital nodes, and precision tick marks.
// =============================================================================
@Composable
fun RotatingVpnRing(
    vpnColors: ProtocolColors,
    state: VpnUiState = VpnUiState.CONNECTED,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var isAppResumed by remember { mutableStateOf(true) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> isAppResumed = false
                Lifecycle.Event.ON_RESUME, Lifecycle.Event.ON_START -> isAppResumed = true
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val app = com.mirrly.tgproxy.MirrlyApplication.instance
    val isAnimationsDisabled by app.prefsManager.animationsDisabledFlow.collectAsState()
    val isRunning = state == VpnUiState.CONNECTED || state == VpnUiState.CONNECTING

    val timeState = produceState(initialValue = 0L, isAppResumed, isAnimationsDisabled) {
        if (!isAppResumed || isAnimationsDisabled) return@produceState
        val startNano = System.nanoTime() - value
        while (isAppResumed && !isAnimationsDisabled) {
            withFrameNanos { frameTimeNanos ->
                value = frameTimeNanos - startNano
            }
        }
    }

    val density = LocalDensity.current
    val strokeWidthPx = remember(density) { with(density) { 5.dp.toPx() } }
    val outerStroke = remember(strokeWidthPx) { Stroke(width = strokeWidthPx, cap = StrokeCap.Round) }
    val innerStroke = remember(strokeWidthPx) { Stroke(width = strokeWidthPx * 0.65f, cap = StrokeCap.Round) }

    val sweepColors = remember(vpnColors) {
        listOf(
            Color.Transparent,
            vpnColors.primary.copy(alpha = 0.20f),
            vpnColors.primary.copy(alpha = 0.65f),
            vpnColors.light
        )
    }
    val innerSweepColors = remember(vpnColors) {
        listOf(
            Color.Transparent,
            vpnColors.primary.copy(alpha = 0.15f),
            vpnColors.light.copy(alpha = 0.85f)
        )
    }

    Canvas(modifier = modifier) {
        val t = if (!isAnimationsDisabled) timeState.value / 1_000_000_000f else 0f
        val diameter = size.minDimension
        val center = Offset(size.width / 2f, size.height / 2f)
        val outerRadius = (diameter - strokeWidthPx) / 2f

        // 1. CALIBRATION TRACK (Subtle AMOLED circular guide)
        drawCircle(
            color = vpnColors.primary.copy(alpha = if (isRunning) 0.16f else 0.10f),
            radius = outerRadius,
            center = center,
            style = Stroke(width = with(density) { 1.dp.toPx() })
        )

        // Reticle ticks: idle slow rotation (6 deg/s), locked in position when active
        val reticleAngle = if (isRunning) 0f else (t * 6f) % 360f
        val tickLen = with(density) { 4.5.dp.toPx() }
        rotate(degrees = reticleAngle, pivot = center) {
            for (deg in listOf(0f, 90f, 180f, 270f)) {
                val rad = Math.toRadians(deg.toDouble()).toFloat()
                val rStart = outerRadius - tickLen
                val rEnd = outerRadius + tickLen
                drawLine(
                    color = vpnColors.primary.copy(alpha = if (isRunning) 0.40f else 0.22f),
                    start = Offset(center.x + rStart * cos(rad), center.y + rStart * sin(rad)),
                    end = Offset(center.x + rEnd * cos(rad), center.y + rEnd * sin(rad)),
                    strokeWidth = with(density) { 1.2.dp.toPx() },
                    cap = StrokeCap.Round
                )
            }
        }

        // Idle perimeter beacons (gentle scanning markers)
        if (!isRunning) {
            for (k in 0..1) {
                val beaconAngle = Math.toRadians(((t * 12f + k * 180f) % 360f).toDouble()).toFloat()
                val bx = center.x + outerRadius * cos(beaconAngle)
                val by = center.y + outerRadius * sin(beaconAngle)
                drawCircle(
                    color = vpnColors.primary.copy(alpha = 0.22f),
                    radius = with(density) { 2.dp.toPx() },
                    center = Offset(bx, by)
                )
            }
        }

        // 2. PRIMARY KINETIC ORBITAL RING (Circular sweeping arc + glowing comet head) - Only when running
        if (isRunning) {
            // Synchronized acceleration surge during cloud struggle
            val strugglePeriod = 1.5f
            val cycleProgress = (t % strugglePeriod) / strugglePeriod
            val surgeAngle = if (state == VpnUiState.CONNECTING && cycleProgress < 0.60f) {
                (sin(cycleProgress / 0.60f * Math.PI.toFloat()) * 32f)
            } else {
                0f
            }
            val outerAngle = (t * 36f + surgeAngle) % 360f
            val sweepAngle = 240f
            val sweepRad = Math.toRadians(sweepAngle.toDouble()).toFloat()

            val sweepBrush = Brush.sweepGradient(colors = sweepColors, center = center)

            rotate(degrees = outerAngle, pivot = center) {
                drawArc(
                    brush = sweepBrush,
                    startAngle = 0f,
                    sweepAngle = sweepAngle,
                    useCenter = false,
                    topLeft = Offset(center.x - outerRadius, center.y - outerRadius),
                    size = Size(outerRadius * 2f, outerRadius * 2f),
                    style = outerStroke
                )

                // High-intensity leading comet head
                val headRad = sweepRad
                val headX = center.x + outerRadius * cos(headRad)
                val headY = center.y + outerRadius * sin(headRad)

                drawCircle(
                    color = vpnColors.primary,
                    radius = strokeWidthPx * 0.9f,
                    center = Offset(headX, headY)
                )
                drawCircle(
                    color = Color.White,
                    radius = strokeWidthPx * 0.5f,
                    center = Offset(headX, headY)
                )
            }

            // 3. COUNTER-ROTATING SECONDARY QUANTUM NODES
            val innerAngle = -(t * 24f) % 360f
            val innerRadius = outerRadius - with(density) { 11.dp.toPx() }
            val nodeCount = 3
            val nodeStep = 360f / nodeCount

            rotate(degrees = innerAngle, pivot = center) {
                for (n in 0 until nodeCount) {
                    val nDeg = n * nodeStep
                    val nRad = Math.toRadians(nDeg.toDouble()).toFloat()
                    val nx = center.x + innerRadius * cos(nRad)
                    val ny = center.y + innerRadius * sin(nRad)

                    // Node outer glow
                    drawCircle(
                        color = vpnColors.light.copy(alpha = 0.35f),
                        radius = with(density) { 3.5.dp.toPx() },
                        center = Offset(nx, ny)
                    )
                    // Node core
                    drawCircle(
                        color = Color.White,
                        radius = with(density) { 1.5.dp.toPx() },
                        center = Offset(nx, ny)
                    )
                }
            }

            // 4. TERTIARY HARMONIC SINE-WAVE INTERIOR TRACK
            val innerSweepAngle = 180f
            val innerSweepRad = Math.toRadians(innerSweepAngle.toDouble()).toFloat()
            val innerRotateAngle = (t * 50f) % 360f
            val innerSweepBrush = Brush.sweepGradient(colors = innerSweepColors, center = center)

            rotate(degrees = innerRotateAngle, pivot = center) {
                drawArc(
                    brush = innerSweepBrush,
                    startAngle = 0f,
                    sweepAngle = innerSweepAngle,
                    useCenter = false,
                    topLeft = Offset(center.x - innerRadius, center.y - innerRadius),
                    size = Size(innerRadius * 2f, innerRadius * 2f),
                    style = innerStroke
                )

                // Inner Satellite Comet Head
                val inHeadRad = innerSweepRad
                val inHeadX = center.x + innerRadius * cos(inHeadRad)
                val inHeadY = center.y + innerRadius * sin(inHeadRad)

                drawCircle(
                    color = vpnColors.primary,
                    radius = strokeWidthPx * 0.6f,
                    center = Offset(inHeadX, inHeadY)
                )
                drawCircle(
                    color = Color.White,
                    radius = strokeWidthPx * 0.3f,
                    center = Offset(inHeadX, inHeadY)
                )
            }
        }
    }
}

// =============================================================================
// VpnInfoWidget — Transparent AMOLED Information Row
// Absolutely ZERO background (color = Color.Transparent). Clean 1dp AmoledBorder.
// =============================================================================
@Composable
fun VpnInfoWidget(
    isCompact: Boolean,
    vpnState: VpnUiState,
    vpnColors: ProtocolColors,
    vpnUplinkMode: com.mirrly.tgproxy.core.UplinkMode = com.mirrly.tgproxy.core.UplinkMode.AWG,
    onTap: () -> Unit = {},
    onTapProfile: () -> Unit = onTap,
    onTapDns: () -> Unit = onTap,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val app = com.mirrly.tgproxy.MirrlyApplication.instance
    val profileManager = app.warpProfileManager
    val activeProfileId by profileManager.activeProfileIdFlow.collectAsState()
    val profiles by profileManager.profilesFlow.collectAsState()
    val isAutoProfileEnabled by app.prefsManager.vpnAutoProfileEnabledFlow.collectAsState()
    val effectiveProfile = remember(activeProfileId, profiles, isAutoProfileEnabled) {
        profileManager.getEffectiveActiveProfile()
    }
    val currentDnsMode by app.prefsManager.vpnDnsModeFlow.collectAsState()
    val vpnStatus by MirrlyVpnService.vpnStatus.collectAsState()

    val isActive = vpnState == VpnUiState.CONNECTED
    val profileLabel = stringResource(R.string.vpn_stat_profile).uppercase()
    val profileValue = if (isAutoProfileEnabled && profiles.size > 1 && effectiveProfile != null) {
        "${effectiveProfile.name} (Авто)"
    } else {
        effectiveProfile?.name ?: "Стандартный"
    }

    val dnsLabel = "ДНС"
    val dnsValue = when (currentDnsMode) {
        VpnDnsMode.CLOUDFLARE -> "Cloudflare"
        VpnDnsMode.COMSS_DNS -> "Comss.one"
        VpnDnsMode.CUSTOM -> "Свой"
        else -> "Cloudflare"
    }

    val orbTransition = rememberInfiniteTransition(label = "vpnOrbPulse")
    val orbPulseScale by orbTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1300, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orbPulseScale"
    )

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, if (isActive) vpnColors.primary.copy(alpha = 0.30f) else AmoledBorder),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = if (isCompact) 6.dp else 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left Half: Profile
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .springPress(onClick = onTapProfile)
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    VpnInfoCell(
                        label = profileLabel,
                        value = profileValue,
                        isActive = isActive,
                        isCompact = isCompact
                    )
                }

                // Central VPN live cyber orb
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(if (isActive) vpnColors.primary.copy(alpha = 0.08f) else Color.Transparent)
                        .border(
                            1.dp,
                            if (isActive) vpnColors.primary.copy(alpha = 0.50f) else AmoledBorder,
                            CircleShape
                        )
                ) {
                    if (isActive) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            drawCircle(
                                color = vpnColors.primary.copy(alpha = 0.16f),
                                radius = (size.minDimension / 2f) * orbPulseScale
                            )
                        }
                    }
                    Icon(
                        painter = painterResource(R.drawable.ic_vpn),
                        contentDescription = "VPN",
                        tint = if (isActive) vpnColors.primary else TextMuted.copy(alpha = 0.45f),
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Right Half: DNS
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .springPress(onClick = onTapDns)
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    VpnInfoCell(
                        label = dnsLabel,
                        value = dnsValue,
                        isActive = isActive,
                        isCompact = isCompact
                    )
                }
            }

            // Live Network Telemetry strip when connected
            if (isActive) {
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(0.6.dp)
                        .background(vpnColors.primary.copy(alpha = 0.16f))
                )
                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Download
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "↓",
                            color = vpnColors.primary,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = humanBytes(vpnStatus.bytesIn),
                            color = TextWhite,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Flows
                    val totalFlows = vpnStatus.activeTcpFlows + vpnStatus.activeUdpSessions
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = if (totalFlows > 0) vpnColors.primary else Color(0xFF64748B),
                            modifier = Modifier.size(5.dp)
                        ) {}
                        Text(
                            text = "Потоки: $totalFlows",
                            color = TextMuted,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // Upload
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "↑",
                            color = Color(0xFF81D4FA),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = humanBytes(vpnStatus.bytesOut),
                            color = TextWhite,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VpnInfoCell(
    label: String,
    value: String,
    isActive: Boolean,
    isCompact: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.padding(vertical = 2.dp)
    ) {
        Text(
            text = label,
            color = TextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            color = if (isActive) TextWhite else TextMuted,
            fontWeight = FontWeight.Bold,
            fontSize = if (isCompact) 14.sp else 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// =============================================================================
// VpnActionDock — Transparent AMOLED action buttons
// =============================================================================
@Composable
fun VpnActionDock(
    onTapDns: () -> Unit,
    onTapSplitTunnel: () -> Unit,
    splitTunnelCount: Int = 0,
    onTapImportProfile: (() -> Unit)? = null,
    onTapGuide: (() -> Unit)? = null,
    onTapGenerator: (() -> Unit)? = null,
    onTapProtocol: () -> Unit = onTapDns,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                onClick = onTapDns,
                modifier = Modifier.weight(1f).height(44.dp).springPress(onClick = onTapDns),
                shape = RoundedCornerShape(12.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, AmoledBorder)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_globe),
                        contentDescription = stringResource(R.string.vpn_action_dns),
                        tint = TextMuted.copy(alpha = 0.75f),
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(7.dp))
                    Text(
                        text = stringResource(R.string.vpn_action_dns),
                        color = TextMuted.copy(alpha = 0.75f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }

            Surface(
                onClick = onTapSplitTunnel,
                modifier = Modifier.weight(1f).height(44.dp).springPress(onClick = onTapSplitTunnel),
                shape = RoundedCornerShape(12.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, AmoledBorder)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_split_tunnel),
                        contentDescription = stringResource(R.string.vpn_action_split_tunnel),
                        tint = TextMuted.copy(alpha = 0.75f),
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(7.dp))
                    val splitLabel = if (splitTunnelCount > 0) {
                        "${stringResource(R.string.vpn_action_split_tunnel)} ($splitTunnelCount)"
                    } else {
                        stringResource(R.string.vpn_action_split_tunnel)
                    }
                    Text(
                        text = splitLabel,
                        color = TextMuted.copy(alpha = 0.75f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }
        }

        if (onTapGenerator != null || onTapImportProfile != null || onTapGuide != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (onTapGenerator != null) {
                    Surface(
                        onClick = onTapGenerator,
                        modifier = Modifier.weight(1f).height(40.dp).springPress(onClick = onTapGenerator),
                        shape = RoundedCornerShape(10.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, AmoledBorder)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.warp_gen_btn_short),
                                color = TextMuted.copy(alpha = 0.85f),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center,
                                maxLines = 1
                            )
                        }
                    }
                }

                if (onTapImportProfile != null) {
                    Surface(
                        onClick = onTapImportProfile,
                        modifier = Modifier.weight(1f).height(40.dp).springPress(onClick = onTapImportProfile),
                        shape = RoundedCornerShape(10.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, AmoledBorder)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.settings_vpn_import_custom),
                                color = TextMuted.copy(alpha = 0.85f),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center,
                                maxLines = 1
                            )
                        }
                    }
                }

                if (onTapGuide != null) {
                    Surface(
                        onClick = onTapGuide,
                        modifier = Modifier.weight(1f).height(40.dp).springPress(onClick = onTapGuide),
                        shape = RoundedCornerShape(10.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, AmoledBorder)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.vpn_action_guide),
                                color = TextMuted.copy(alpha = 0.75f),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

// =============================================================================
// VpnProtocolSelectorDialog — Frosted-glass modal dialog (DialogBackdropBox)
// Strict factual text, NO emoji, unified with system-selected VPN accent.
// =============================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VpnProtocolSelectorDialog(
    vpnColors: ProtocolColors,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val app = com.mirrly.tgproxy.MirrlyApplication.instance
    val currentMode by app.prefsManager.vpnUplinkModeFlow.collectAsState()
    var selectedMode by remember(currentMode) { mutableStateOf(currentMode) }
    val currentDnsMode by app.prefsManager.vpnDnsModeFlow.collectAsState()
    var selectedDnsMode by remember(currentDnsMode) { mutableStateOf(currentDnsMode) }
    val vpnState by com.mirrly.tgproxy.service.MirrlyVpnService.vpnState.collectAsState()

    val awgDesc = stringResource(R.string.vpn_protocol_awg_desc)
    val masqueDesc = stringResource(R.string.vpn_protocol_masque_desc)

    val protocols = remember(awgDesc, masqueDesc) {
        listOf(
            Triple(
                com.mirrly.tgproxy.core.UplinkMode.AWG,
                "WARP AWG",
                awgDesc
            ),
            Triple(
                com.mirrly.tgproxy.core.UplinkMode.MASQUE,
                "WARP MASQUE",
                masqueDesc
            )
        )
    }

    val dnsModes = remember {
        listOf(
            Triple(
                VpnDnsMode.CLOUDFLARE,
                "Cloudflare DNS · 1.1.1.1",
                "Anycast по умолчанию"
            ),
            Triple(
                VpnDnsMode.COMSS_DNS,
                "Comss.one DNS",
                "ChatGPT, Claude, Gemini, Copilot"
            ),
            Triple(
                VpnDnsMode.CUSTOM,
                "Свой DNS",
                "Пользовательские IPv4"
            )
        )
    }

    fun applySelection(modeToApply: com.mirrly.tgproxy.core.UplinkMode, dnsToApply: VpnDnsMode) {
        app.prefsManager.setVpnUplinkMode(modeToApply)
        app.config.vpnUplinkModeName = modeToApply.name
        app.prefsManager.setVpnDnsMode(dnsToApply)
        app.config.vpnDnsModeName = dnsToApply.name
        app.saveConfig()
        if (vpnState == VpnUiState.CONNECTED) {
            com.mirrly.tgproxy.service.MirrlyVpnService.restart(context)
        }
        onDismiss()
    }

    var customIpsText by remember { mutableStateOf(app.prefsManager.getVpnCustomDnsIps()) }

    MirrlyModalBottomSheet(
        onDismissRequest = onDismiss
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .fadingEdges(topFadeHeight = 16.dp, bottomFadeHeight = 24.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {

            Text(
                text = stringResource(R.string.vpn_protocol_dialog_title),
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = TextWhite,
                textAlign = TextAlign.Center,
                letterSpacing = 0.3.sp
            )

            Text(
                text = stringResource(R.string.vpn_protocol_dialog_desc),
                fontSize = 12.sp,
                color = TextMuted,
                textAlign = TextAlign.Center,
                lineHeight = 16.sp,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Spacer(modifier = Modifier.height(2.dp))

            if (protocols.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_vpn_uplink_section).uppercase(),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        color = TextMuted,
                        letterSpacing = 1.1.sp
                    )
                }

                protocols.forEachIndexed { index, (mode, name, desc) ->
                    val isSelected = selectedMode == mode
                    val cardAccent = if (isSelected) vpnColors.primary else AmoledBorder

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color.Transparent,
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) cardAccent.copy(alpha = 0.65f) else AmoledBorder
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .springPress(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    selectedMode = mode
                                }
                            )
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(9.dp)
                            ) {
                                Text(
                                    text = name,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) TextWhite else TextWhite.copy(alpha = 0.85f),
                                    modifier = Modifier.weight(1f)
                                )

                                Spacer(modifier = Modifier.width(4.dp))

                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .border(
                                            if (isSelected) 5.dp else 1.5.dp,
                                            if (isSelected) cardAccent else AmoledBorder,
                                            CircleShape
                                        )
                                )
                            }

                            Text(
                                text = desc,
                                color = TextMuted,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.vpn_dns_selector_title).uppercase(),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    color = TextMuted,
                    letterSpacing = 1.1.sp
                )
            }

            dnsModes.forEachIndexed { index, (mode, name, desc) ->
                val isSelected = selectedDnsMode == mode
                val isComss = mode == VpnDnsMode.COMSS_DNS
                val isCustom = mode == VpnDnsMode.CUSTOM
                val dnsAccent = when {
                    isComss && isSelected -> Color(0xFF10B981)
                    isCustom && isSelected -> Color(0xFF3B82F6)
                    isSelected -> vpnColors.primary
                    else -> AmoledBorder
                }

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Transparent,
                    border = BorderStroke(
                        1.dp,
                        if (isSelected) dnsAccent.copy(alpha = 0.65f) else AmoledBorder
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .springPress(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                selectedDnsMode = mode
                            }
                        )
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(9.dp)
                        ) {
                            Text(
                                text = name,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) TextWhite else TextWhite.copy(alpha = 0.85f),
                                modifier = Modifier.weight(1f)
                            )

                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .border(
                                        if (isSelected) 5.dp else 1.5.dp,
                                        if (isSelected) dnsAccent else AmoledBorder,
                                        CircleShape
                                    )
                            )
                        }

                        Text(
                            text = desc,
                            color = TextMuted,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )

                        if (isComss && isSelected) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color.Transparent,
                                border = BorderStroke(1.dp, AmoledBorder),
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.vpn_dns_ipv6_protection_note),
                                    fontSize = 10.5.sp,
                                    color = TextMuted,
                                    lineHeight = 14.sp,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                                )
                            }
                        }

                        if (isCustom && isSelected) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                BasicTextField(
                                    value = customIpsText,
                                    onValueChange = { customIpsText = it },
                                    singleLine = true,
                                    textStyle = TextStyle(
                                        color = TextWhite,
                                        fontSize = 12.5.sp,
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    cursorBrush = SolidColor(Color(0xFF3B82F6)),
                                    modifier = Modifier.fillMaxWidth(),
                                    decorationBox = { innerTextField ->
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(38.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color.Transparent)
                                                .border(1.dp, AmoledBorder, RoundedCornerShape(8.dp))
                                                .padding(horizontal = 10.dp),
                                            contentAlignment = Alignment.CenterStart
                                        ) {
                                            if (customIpsText.isEmpty()) {
                                                Text(
                                                    text = stringResource(R.string.vpn_dns_custom_placeholder),
                                                    color = TextMuted.copy(alpha = 0.6f),
                                                    fontSize = 12.sp
                                                )
                                            }
                                            innerTextField()
                                        }
                                    }
                                )
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color.Transparent,
                                    border = BorderStroke(1.dp, AmoledBorder),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = stringResource(R.string.vpn_dns_ipv6_protection_note),
                                        fontSize = 10.5.sp,
                                        color = TextMuted,
                                        lineHeight = 14.sp,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Surface(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (selectedDnsMode == VpnDnsMode.CUSTOM) {
                        val valid = com.mirrly.tgproxy.core.ProxyConfig.parseCustomDnsIps(customIpsText)
                        if (valid.isEmpty() && customIpsText.isNotBlank()) {
                            android.widget.Toast.makeText(
                                context,
                                context.getString(R.string.vpn_dns_invalid_ip_toast),
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            return@Surface
                        }
                        val normalized = valid.joinToString(", ")
                        customIpsText = normalized
                        app.prefsManager.setVpnCustomDnsIps(normalized)
                        app.config.vpnCustomDnsIps = normalized
                    }
                    applySelection(selectedMode, selectedDnsMode)
                },
                shape = RoundedCornerShape(14.dp),
                color = vpnColors.primary.copy(alpha = 0.15f),
                border = BorderStroke(1.dp, vpnColors.primary.copy(alpha = 0.60f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .springPress()
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Text(
                        text = stringResource(R.string.action_understood_short),
                        color = vpnColors.primary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        letterSpacing = 0.3.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

