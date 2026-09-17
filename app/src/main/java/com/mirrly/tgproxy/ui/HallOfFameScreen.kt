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
import android.widget.Toast
import androidx.compose.animation.*
import androidx.annotation.StringRes
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.ui.theme.*
import java.security.MessageDigest
import kotlin.math.cos
import kotlin.math.sin

enum class ContributorTier(
    @StringRes val titleRes: Int,
    @StringRes val badgeLabelRes: Int,
    val primaryColor: Color,
    val secondaryColor: Color,
    val iconRes: Int
) {
    LEGENDARY_PIONEER(
        titleRes = R.string.fame_tier_pioneer_title,
        badgeLabelRes = R.string.fame_tier_pioneer_badge,
        primaryColor = Color(0xFFFFB703),
        secondaryColor = Color(0xFFFF5400),
        iconRes = R.drawable.ic_crown
    ),
    BUG_HUNTER(
        titleRes = R.string.fame_tier_bughunter_title,
        badgeLabelRes = R.string.fame_tier_bughunter_badge,
        primaryColor = Color(0xFF00F5D4),
        secondaryColor = Color(0xFF00B4D8),
        iconRes = R.drawable.ic_fingerprint_badge
    ),
    BETA_TESTER(
        titleRes = R.string.fame_tier_tester_title,
        badgeLabelRes = R.string.fame_tier_tester_badge,
        primaryColor = Color(0xFFC084FC),
        secondaryColor = Color(0xFF818CF8),
        iconRes = R.drawable.ic_volunteer_badge
    ),
    TG_SUBSCRIBER(
        titleRes = R.string.fame_tier_tg_title,
        badgeLabelRes = R.string.fame_tier_tg_badge,
        primaryColor = Color(0xFF26A5E4),
        secondaryColor = Color(0xFF0088CC),
        iconRes = R.drawable.ic_telegram
    )
}

data class DigitalFingerprint(
    val formattedHash: String,
    val fullHash: String,
    val angles: FloatArray,
    val ringRatios: FloatArray,
    val matrixPattern: List<Boolean>,
    val seedColorOffset: Float
)

data class Contributor(
    val id: String,
    val name: String,
    val handle: String,
    @StringRes val roleRes: Int,
    @StringRes val contributionRes: Int,
    val tier: ContributorTier,
    val githubUrl: String? = null
) {
    val fingerprint: DigitalFingerprint by lazy {
        computeFingerprint(name, id, id)
    }
}

private fun computeFingerprint(name: String, id: String, role: String): DigitalFingerprint {
    val input = "$name::$id::$role::MIRRLY_CORE_2026"
    val md = MessageDigest.getInstance("SHA-256")
    val digest = md.digest(input.toByteArray(Charsets.UTF_8))
    val hex = digest.joinToString("") { "%02X".format(it) }
    val formattedHash = hex.take(16).chunked(4).joinToString("-")
    val fullHash = hex.chunked(8).joinToString(":")

    val angles = FloatArray(6) { i ->
        ((digest[i * 2].toInt() and 0xFF) * 360f / 255f)
    }

    val ringRatios = FloatArray(3) { i ->
        0.35f + (digest[12 + i].toInt() and 0xFF) * 0.20f / 255f
    }

    val matrixPattern = (0 until 9).map { idx ->
        ((digest[16 + (idx % 16)].toInt() and (1 shl (idx % 8))) != 0)
    }

    return DigitalFingerprint(
        formattedHash = formattedHash,
        fullHash = fullHash,
        angles = angles,
        ringRatios = ringRatios,
        matrixPattern = matrixPattern,
        seedColorOffset = (digest[0].toInt() and 0xFF) / 255f
    )
}

private val ContributorsList = listOf(
    Contributor(
        id = "amurcanov",
        name = "amurcanov",
        handle = "@amurcanov",
        roleRes = R.string.fame_c_amurcanov_role,
        contributionRes = R.string.fame_c_amurcanov_contrib,
        tier = ContributorTier.LEGENDARY_PIONEER,
        githubUrl = "https://github.com/amurcanov"
    ),
    Contributor(
        id = "flowseal",
        name = "Flowseal",
        handle = "@Flowseal",
        roleRes = R.string.fame_c_flowseal_role,
        contributionRes = R.string.fame_c_flowseal_contrib,
        tier = ContributorTier.LEGENDARY_PIONEER,
        githubUrl = "https://github.com/Flowseal"
    ),
    Contributor(
        id = "grovymon",
        name = "Grovymon",
        handle = "@Grovymon",
        roleRes = R.string.fame_c_grovymon_role,
        contributionRes = R.string.fame_c_grovymon_contrib,
        tier = ContributorTier.BUG_HUNTER,
        githubUrl = "https://github.com/Grovymon"
    ),
    Contributor(
        id = "zzzxxx888207_design",
        name = "zzzxxx888207-design",
        handle = "@zzzxxx888207-design",
        roleRes = R.string.fame_c_zzzxxx_role,
        contributionRes = R.string.fame_c_zzzxxx_contrib,
        tier = ContributorTier.BUG_HUNTER,
        githubUrl = "https://github.com/zzzxxx888207-design"
    ),
    Contributor(
        id = "bbibux",
        name = "BbIBux",
        handle = "@BbIBux",
        roleRes = R.string.fame_c_bbibux_role,
        contributionRes = R.string.fame_c_bbibux_contrib,
        tier = ContributorTier.BUG_HUNTER,
        githubUrl = "https://github.com/BbIBux"
    ),
    Contributor(
        id = "ustiprog",
        name = "ustiprog",
        handle = "@ustiprog",
        roleRes = R.string.fame_c_ustiprog_role,
        contributionRes = R.string.fame_c_ustiprog_contrib,
        tier = ContributorTier.BUG_HUNTER,
        githubUrl = "https://github.com/ustiprog"
    ),
    Contributor(
        id = "vikkalm",
        name = "VikKalm",
        handle = "@VikKalm",
        roleRes = R.string.fame_c_vikkalm_role,
        contributionRes = R.string.fame_c_vikkalm_contrib,
        tier = ContributorTier.BUG_HUNTER,
        githubUrl = "https://github.com/VikKalm"
    ),
    Contributor(
        id = "liveonloan",
        name = "liveonloan",
        handle = "@liveonloan",
        roleRes = R.string.fame_c_liveonloan_role,
        contributionRes = R.string.fame_c_liveonloan_contrib,
        tier = ContributorTier.BUG_HUNTER,
        githubUrl = "https://github.com/liveonloan"
    ),
    Contributor(
        id = "40oil",
        name = "40OIL",
        handle = "@40OIL",
        roleRes = R.string.fame_c_40oil_role,
        contributionRes = R.string.fame_c_40oil_contrib,
        tier = ContributorTier.BUG_HUNTER,
        githubUrl = "https://github.com/40OIL"
    ),
    Contributor(
        id = "mslight",
        name = "MSLight",
        handle = "@MSLight",
        roleRes = R.string.fame_c_mslight_role,
        contributionRes = R.string.fame_c_mslight_contrib,
        tier = ContributorTier.BETA_TESTER,
        githubUrl = "https://github.com/MSLight"
    ),
    Contributor(
        id = "aseptronn",
        name = "Aseptronn",
        handle = "@Aseptronn",
        roleRes = R.string.fame_c_aseptronn_role,
        contributionRes = R.string.fame_c_aseptronn_contrib,
        tier = ContributorTier.BETA_TESTER,
        githubUrl = "https://github.com/Aseptronn"
    ),
    Contributor(
        id = "shon4k",
        name = "Shon4k",
        handle = "@Shon4k",
        roleRes = R.string.fame_c_shon4k_role,
        contributionRes = R.string.fame_c_shon4k_contrib,
        tier = ContributorTier.BETA_TESTER
    ),
    Contributor(
        id = "linar_s",
        name = "Linar S",
        handle = "Linar S",
        roleRes = R.string.fame_c_linar_role,
        contributionRes = R.string.fame_c_linar_contrib,
        tier = ContributorTier.BETA_TESTER
    ),
    Contributor(
        id = "astimir_meikulov",
        name = "Astimir Meikulov",
        handle = "Astimir Meikulov",
        roleRes = R.string.fame_c_astimir_role,
        contributionRes = R.string.fame_c_astimir_contrib,
        tier = ContributorTier.TG_SUBSCRIBER
    ),
    Contributor(
        id = "dimaakaj",
        name = "Dimaakaj",
        handle = "@Dimaakaj",
        roleRes = R.string.fame_c_dimaakaj_role,
        contributionRes = R.string.fame_c_dimaakaj_contrib,
        tier = ContributorTier.BUG_HUNTER
    )
)

/**
 * Procedural generative canvas that renders an individual high-tech biometric/cryptographic identicon.
 */
@Composable
fun DigitalFingerprintCanvas(
    fingerprint: DigitalFingerprint,
    tier: ContributorTier,
    size: Dp = 48.dp,
    animated: Boolean = true,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "fpRotation")
    val rotation by if (animated) {
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(12000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "fpRot"
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }

    val pulseScale by if (animated) {
        infiniteTransition.animateFloat(
            initialValue = 0.94f,
            targetValue = 1.06f,
            animationSpec = infiniteRepeatable(
                animation = tween(2400, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "fpPulse"
        )
    } else {
        remember { mutableFloatStateOf(1f) }
    }

    Canvas(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
    ) {
        val w = this.size.width
        val h = this.size.height
        val center = Offset(w / 2f, h / 2f)
        val maxRadius = (w.coerceAtMost(h) / 2f) * 0.90f

        // Draw soft outer ambient glow
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    tier.primaryColor.copy(alpha = 0.28f),
                    tier.secondaryColor.copy(alpha = 0.10f),
                    Color.Transparent
                ),
                center = center,
                radius = maxRadius * 1.15f
            ),
            radius = maxRadius * 1.15f,
            center = center
        )

        // Draw Concentric Dashed Radar Arcs
        val r1 = maxRadius * fingerprint.ringRatios[0] * pulseScale
        val r2 = maxRadius * fingerprint.ringRatios[1]
        val r3 = maxRadius * fingerprint.ringRatios[2]

        drawCircle(
            color = tier.primaryColor.copy(alpha = 0.35f),
            radius = r1,
            center = center,
            style = Stroke(width = 1.2.dp.toPx())
        )

        drawArc(
            color = tier.secondaryColor.copy(alpha = 0.75f),
            startAngle = rotation + fingerprint.angles[0],
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(center.x - r2, center.y - r2),
            size = Size(r2 * 2f, r2 * 2f),
            style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
        )

        drawArc(
            color = tier.primaryColor.copy(alpha = 0.85f),
            startAngle = -rotation + fingerprint.angles[1] + 180f,
            sweepAngle = 110f,
            useCenter = false,
            topLeft = Offset(center.x - r3, center.y - r3),
            size = Size(r3 * 2f, r3 * 2f),
            style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round)
        )

        // Draw Constellation Nodes & Connecting Rays
        for (i in 0 until 4) {
            val rad = Math.toRadians((rotation + fingerprint.angles[i + 2]).toDouble())
            val nodeRadius = maxRadius * (0.45f + (i * 0.13f))
            val nx = center.x + (cos(rad) * nodeRadius).toFloat()
            val ny = center.y + (sin(rad) * nodeRadius).toFloat()

            // Ray from center
            drawLine(
                color = tier.primaryColor.copy(alpha = 0.25f),
                start = center,
                end = Offset(nx, ny),
                strokeWidth = 1.dp.toPx()
            )

            // Node Dot
            drawCircle(
                color = tier.primaryColor,
                radius = 2.2.dp.toPx(),
                center = Offset(nx, ny)
            )
        }

        // Draw Central Geometric Identity Core (3x3 Micro-Matrix)
        val matrixSize = maxRadius * 0.32f
        val step = matrixSize / 2f
        val startX = center.x - step
        val startY = center.y - step

        for (row in 0 until 3) {
            for (col in 0 until 3) {
                val idx = row * 3 + col
                val isFilled = fingerprint.matrixPattern.getOrElse(idx) { true }
                val px = startX + col * step
                val py = startY + row * step
                if (isFilled) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.90f),
                        radius = 1.5.dp.toPx(),
                        center = Offset(px, py)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HallOfFameScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scrollState = rememberScrollState()

    var selectedContributor by remember { mutableStateOf<Contributor?>(null) }
    var pendingRedirectUrl by remember { mutableStateOf<String?>(null) }

    if (pendingRedirectUrl != null) {
        val targetUrl = pendingRedirectUrl ?: ""
        ExternalLinkConfirmDialog(
            url = targetUrl,
            onDismiss = { pendingRedirectUrl = null }
        )
    }

    if (selectedContributor != null) {
        val contributor = selectedContributor!!
        ContributorPassportDialog(
            contributor = contributor,
            onDismiss = { selectedContributor = null },
            onOpenGithub = { url ->
                selectedContributor = null
                pendingRedirectUrl = url
            }
        )
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .adaptiveContainerWidth(600.dp)
                .fillMaxHeight()
                .fadingEdges(topFadeHeight = 24.dp, bottomFadeHeight = 44.dp)
                .verticalScroll(scrollState)
                .padding(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp
                )
                .adaptiveContentPadding(),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // ── 1. HERO HALL OF FAME EMBLEM CARD ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .staggeredEntrance(index = 0)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.Transparent)
                    .border(1.dp, Color(0xFF7C4DFF).copy(alpha = 0.45f), RoundedCornerShape(24.dp))
                    .lightSweep(
                        isEnabled = true,
                        shape = RoundedCornerShape(24.dp),
                        sweepColor = Color(0xFF7C4DFF)
                    )
                    .padding(22.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF7C4DFF).copy(alpha = 0.15f))
                            .border(2.dp, Color(0xFF7C4DFF).copy(alpha = 0.6f), CircleShape)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_hall_of_fame),
                            contentDescription = null,
                            tint = Color(0xFFC084FC),
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.fame_title),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Black,
                            color = TextWhite,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = stringResource(R.string.fame_desc),
                            fontSize = 12.5.sp,
                            lineHeight = 18.sp,
                            color = TextMuted,
                            textAlign = TextAlign.Center
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0xFF7C4DFF).copy(alpha = 0.14f),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            Color(0xFF7C4DFF).copy(alpha = 0.40f)
                        )
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_fingerprint_badge),
                                contentDescription = null,
                                tint = Color(0xFFC084FC),
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = stringResource(R.string.fame_fingerprint_badge_header),
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFC084FC)
                            )
                        }
                    }
                }
            }

            // ── 2. LEGENDARY PIONEERS SECTION ──
            val pioneers = ContributorsList.filter { it.tier == ContributorTier.LEGENDARY_PIONEER }
            if (pioneers.isNotEmpty()) {
                Column(
                    modifier = Modifier.staggeredEntrance(index = 1),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_crown),
                            contentDescription = null,
                            tint = Color(0xFFFFB703),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stringResource(R.string.fame_section_pioneers),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.2.sp,
                            color = Color(0xFFFFB703)
                        )
                    }

                    pioneers.forEachIndexed { idx, contributor ->
                        ContributorCard(
                            contributor = contributor,
                            modifier = Modifier.staggeredEntrance(index = 2 + idx),
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                selectedContributor = contributor
                            }
                        )
                    }
                }
            }

            // ── 3. BUG HUNTERS SECTION ──
            val bugHunters = ContributorsList.filter { it.tier == ContributorTier.BUG_HUNTER }
            if (bugHunters.isNotEmpty()) {
                Column(
                    modifier = Modifier.staggeredEntrance(index = 4),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_fingerprint_badge),
                            contentDescription = null,
                            tint = Color(0xFF00F5D4),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stringResource(R.string.fame_section_bughunters),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.2.sp,
                            color = Color(0xFF00F5D4)
                        )
                    }

                    bugHunters.forEachIndexed { idx, contributor ->
                        ContributorCard(
                            contributor = contributor,
                            modifier = Modifier.staggeredEntrance(index = 5 + idx),
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                selectedContributor = contributor
                            }
                        )
                    }
                }
            }

            // ── 4. BETA TESTERS SECTION ──
            val betaTesters = ContributorsList.filter { it.tier == ContributorTier.BETA_TESTER }
            if (betaTesters.isNotEmpty()) {
                Column(
                    modifier = Modifier.staggeredEntrance(index = 7),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_volunteer_badge),
                            contentDescription = null,
                            tint = Color(0xFFC084FC),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stringResource(R.string.fame_section_testers),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.2.sp,
                            color = Color(0xFFC084FC)
                        )
                    }

                    betaTesters.forEachIndexed { idx, contributor ->
                        ContributorCard(
                            contributor = contributor,
                            modifier = Modifier.staggeredEntrance(index = 8 + idx),
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                selectedContributor = contributor
                            }
                        )
                    }
                }
            }

            // ── 5. TELEGRAM COMMUNITY SECTION ──
            val tgSubscribers = ContributorsList.filter { it.tier == ContributorTier.TG_SUBSCRIBER }
            if (tgSubscribers.isNotEmpty()) {
                Column(
                    modifier = Modifier.staggeredEntrance(index = 9),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_telegram),
                            contentDescription = null,
                            tint = Color(0xFF26A5E4),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stringResource(R.string.fame_section_community),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.2.sp,
                            color = Color(0xFF26A5E4)
                        )
                    }

                    tgSubscribers.forEachIndexed { idx, contributor ->
                        ContributorCard(
                            contributor = contributor,
                            modifier = Modifier.staggeredEntrance(index = 10 + idx),
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                selectedContributor = contributor
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Frosted Top App Bar Header
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
                        text = stringResource(R.string.fame_topbar_title),
                        color = TextWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }

        CyberParticlesOverlay(
            modifier = Modifier.fillMaxSize(),
            particleCount = 38,
            alphaMultiplier = 0.65f
        )
    }
}

@Composable
private fun ContributorCard(
    contributor: Contributor,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val tier = contributor.tier
    val fp = contributor.fingerprint

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Transparent)
            .border(1.dp, Color(0xFF181E2E), RoundedCornerShape(18.dp))
            .springPress(onClick = onClick)
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Generative Holographic Identicon Badge
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(tier.primaryColor.copy(alpha = 0.10f))
                    .border(1.dp, tier.primaryColor.copy(alpha = 0.35f), CircleShape)
            ) {
                DigitalFingerprintCanvas(
                    fingerprint = fp,
                    tier = tier,
                    size = 50.dp,
                    animated = true
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = contributor.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite
                    )

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = tier.primaryColor.copy(alpha = 0.15f),
                        border = androidx.compose.foundation.BorderStroke(
                            0.8.dp,
                            tier.primaryColor.copy(alpha = 0.4f)
                        )
                    ) {
                        Text(
                            text = stringResource(tier.badgeLabelRes),
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = tier.primaryColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Text(
                    text = stringResource(contributor.roleRes),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = tier.primaryColor.copy(alpha = 0.9f)
                )

                Text(
                    text = stringResource(contributor.contributionRes),
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    color = TextMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                // Cryptographic Hash Stamp Pill
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_fingerprint_badge),
                        contentDescription = null,
                        tint = TextMuted.copy(alpha = 0.7f),
                        modifier = Modifier.size(11.dp)
                    )
                    Text(
                        text = "HASH: #${fp.formattedHash}",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = TextMuted.copy(alpha = 0.8f)
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

/**
 * Interactive Cyber Passport dialog showing the contributor's full digital footprint.
 */
@Composable
private fun ContributorPassportDialog(
    contributor: Contributor,
    onDismiss: () -> Unit,
    onOpenGithub: (String) -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val tier = contributor.tier
    val fp = contributor.fingerprint

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        DialogBackdropBox(onDismiss = onDismiss) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
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
                // Tier Badge Pill
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = tier.primaryColor.copy(alpha = 0.12f),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        tier.primaryColor.copy(alpha = 0.35f)
                    )
                ) {
                    Text(
                        text = stringResource(tier.titleRes).uppercase(),
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.1.sp,
                        color = tier.primaryColor,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }

                // Generative Holographic Identicon Showcase
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(100.dp)
                        .clip(CircleShape)
                        .background(tier.primaryColor.copy(alpha = 0.12f))
                        .border(2.dp, tier.primaryColor.copy(alpha = 0.55f), CircleShape)
                ) {
                    DigitalFingerprintCanvas(
                        fingerprint = fp,
                        tier = tier,
                        size = 94.dp,
                        animated = true
                    )
                }

                // Name & Handle
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = contributor.name,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black,
                        color = TextWhite,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = stringResource(contributor.roleRes),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = tier.primaryColor,
                        textAlign = TextAlign.Center
                    )
                }

                // Full Contribution Description Box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Transparent)
                        .border(1.dp, Color(0xFF181E2E), RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.fame_dialog_contrib_header),
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.1.sp,
                            color = TextMuted
                        )
                        Text(
                            text = stringResource(contributor.contributionRes),
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            color = TextWhite.copy(alpha = 0.90f)
                        )
                    }
                }

                // Cryptographic Verified Fingerprint Card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Transparent)
                        .border(1.dp, tier.primaryColor.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_fingerprint_badge),
                                    contentDescription = null,
                                    tint = tier.primaryColor,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = stringResource(R.string.fame_dialog_fingerprint_header),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 1.1.sp,
                                    color = tier.primaryColor
                                )
                            }

                            Text(
                                text = "SHA-256",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = TextMuted
                            )
                        }

                        Text(
                            text = fp.fullHash,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextWhite,
                            lineHeight = 16.sp
                        )

                        Button(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cb.setPrimaryClip(ClipData.newPlainText("Digital Fingerprint", fp.fullHash))
                                Toast.makeText(context, context.getString(R.string.fame_dialog_copied_toast), Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Transparent,
                                contentColor = tier.primaryColor
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, tier.primaryColor.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(38.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_copy),
                                contentDescription = null,
                                tint = tier.primaryColor,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.fame_dialog_btn_copy),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // GitHub Profile Link if available
                if (contributor.githubUrl != null) {
                    Button(
                        onClick = { onOpenGithub(contributor.githubUrl) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            contentColor = TextWhite
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2A344A)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_github),
                            contentDescription = null,
                            tint = TextWhite,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.fame_dialog_btn_github),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }

                // Close Button
                OutlinedButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDismiss()
                    },
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E2638)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                ) {
                    Text(
                        text = stringResource(R.string.fame_dialog_btn_close),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = TextMuted
                    )
                }
            }
        }
    }
}
