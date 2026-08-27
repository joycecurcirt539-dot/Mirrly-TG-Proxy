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
    val title: String,
    val badgeLabel: String,
    val primaryColor: Color,
    val secondaryColor: Color,
    val iconRes: Int
) {
    LEGENDARY_PIONEER(
        title = "Архитектурный Первопроходец",
        badgeLabel = "ПЕРВОПРОХОДЕЦ",
        primaryColor = Color(0xFFFFB703),
        secondaryColor = Color(0xFFFF5400),
        iconRes = R.drawable.ic_crown
    ),
    BUG_HUNTER(
        title = "Охотник за багами",
        badgeLabel = "БАГ-ХАНТЕР",
        primaryColor = Color(0xFF00F5D4),
        secondaryColor = Color(0xFF00B4D8),
        iconRes = R.drawable.ic_fingerprint_badge
    ),
    BETA_TESTER(
        title = "Бета-Тестировщик",
        badgeLabel = "БЕТА-ТЕСТЕР",
        primaryColor = Color(0xFFC084FC),
        secondaryColor = Color(0xFF818CF8),
        iconRes = R.drawable.ic_volunteer_badge
    ),
    TG_SUBSCRIBER(
        title = "Участник Telegram-сообщества",
        badgeLabel = "ПОДПИСЧИК TG",
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
    val role: String,
    val contribution: String,
    val tier: ContributorTier,
    val githubUrl: String? = null
) {
    val fingerprint: DigitalFingerprint by lazy {
        computeFingerprint(name, id, role)
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
        role = "Автор проекта tg-ws-proxy-android",
        contribution = "Создатель базового открытого Android-клиента tg-ws-proxy-android и C++ NDK реализации WSS-клиента, послуживших фундаментом для сетевого стека приложения.",
        tier = ContributorTier.LEGENDARY_PIONEER,
        githubUrl = "https://github.com/amurcanov"
    ),
    Contributor(
        id = "flowseal",
        name = "Flowseal",
        handle = "@Flowseal",
        role = "Автор концепции туннелирования Telegram",
        contribution = "Создатель проекта tg-ws-proxy. Разработал архитектурную концепцию проксирования трафика Telegram через Cloudflare WebSocket без необходимости в VPN.",
        tier = ContributorTier.LEGENDARY_PIONEER,
        githubUrl = "https://github.com/Flowseal"
    ),
    Contributor(
        id = "grovymon",
        name = "Grovymon",
        handle = "@Grovymon",
        role = "Охотник за багами & Аудит безопасности",
        contribution = "Автор Issues #3, #4, #5, #7, #8. Обнаружил баги Window Insets под системную панель навигации (Redmi Note 13 Pro+ 5G, Android 16), залипание плашки обновления при актуальной версии, блокировки воркеров на мобильной сети Т-Мобайл и уязвимость отключенной валидации TLS-сертификатов NoServerCertVerifier.",
        tier = ContributorTier.BUG_HUNTER,
        githubUrl = "https://github.com/Grovymon"
    ),
    Contributor(
        id = "zzzxxx888207_design",
        name = "zzzxxx888207-design",
        handle = "@zzzxxx888207-design",
        role = "Охотник за багами & Исследователь воркеров",
        contribution = "Автор Issues #1, #9, #10, #13. Локализовал сброс секретного ключа в памяти на Xiaomi 12T (Android 15) и POCO X8 Pro Max (Android 16), выявил потерю приоритета пользовательских воркеров после перезагрузки и исследовал конфигурации Cloudflare Workers.",
        tier = ContributorTier.BUG_HUNTER,
        githubUrl = "https://github.com/zzzxxx888207-design"
    ),
    Contributor(
        id = "bbibux",
        name = "BbIBux",
        handle = "@BbIBux",
        role = "Охотник за багами & Диагностика MTProto",
        contribution = "Автор Issues #11, #12, #13. Обнаружил сбой загрузки медиафайлов (фото и видео) в MTProto на сетях T2 и Ростелеком после удаления MsgSplitter, сообщил о дефекте прозрачности подложки диалоговых окон и исследовал доменную адресацию воркеров.",
        tier = ContributorTier.BUG_HUNTER,
        githubUrl = "https://github.com/BbIBux"
    ),
    Contributor(
        id = "vikkalm",
        name = "VikKalm",
        handle = "@VikKalm",
        role = "Охотник за багами релизных сборок",
        contribution = "Автор Issue #6. Зафиксировал и предоставил логи полного отказа подключения в релизе v1.1.2 на Android 13 (arm64-v8a) через Wi-Fi/LTE из-за блокировки дефолтных воркеров Cloudflare, что привело к выпуску срочного хотфикса.",
        tier = ContributorTier.BUG_HUNTER,
        githubUrl = "https://github.com/VikKalm"
    ),
    Contributor(
        id = "liveonloan",
        name = "liveonloan",
        handle = "@liveonloan",
        role = "Охотник за багами интерфейса",
        contribution = "Автор Issue #14. Обнаружил визуальный дефект перекрытия кнопок управления таймером сна системной панелью навигации на Realme GT7 (Android 16), послуживший основой для редизайна экрана таймера в v1.1.8.1.",
        tier = ContributorTier.BUG_HUNTER,
        githubUrl = "https://github.com/liveonloan"
    ),
    Contributor(
        id = "shon4k",
        name = "Shon4k",
        handle = "@Shon4k",
        role = "Бета-тестировщик",
        contribution = "Тестирование предварительных сборок приложения, проверка стабильности прокси-соединения и валидация сценариев использования.",
        tier = ContributorTier.BETA_TESTER
    ),
    Contributor(
        id = "linar_s",
        name = "Linar S",
        handle = "Linar S",
        role = "Бета-тестировщик",
        contribution = "Тестирование стабильности сетевых сценариев, проверка совместимости на различных Android-устройствах и сбор обратной связи.",
        tier = ContributorTier.BETA_TESTER
    ),
    Contributor(
        id = "astimir_meikulov",
        name = "Astimir Meikulov",
        handle = "Astimir Meikulov",
        role = "Подписчик Telegram-канала",
        contribution = "Активный подписчик Telegram-канала проекта (@WhyOkyHb), поддержка разработки и участие в жизни сообщества.",
        tier = ContributorTier.TG_SUBSCRIBER
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
    onBack: () -> Unit,
    onOpenVolunteers: () -> Unit = {}
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
                            text = "Зал Славы и Благодарности",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Black,
                            color = TextWhite,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "Выражаю глубокую признательность первопроходцам, контрибьюторам и тестировщикам, чьи наработки и тестирование сделали Mirrly TG Proxy надежным инструментом.",
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
                                text = "УНИКАЛЬНЫЙ ЦИФРОВОЙ СЛЕПОК ДЛЯ КАЖДОГО УЧАСТНИКА",
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
                            text = "АРХИТЕКТУРНЫЕ ПЕРВОПРОХОДЦЫ",
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
                            text = "ОХОТНИКИ ЗА БАГАМИ (BUG HUNTERS)",
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
                            text = "ВОЛОНТЕРЫ БЕТА-ТЕСТИРОВАНИЯ",
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
                            text = "СООБЩЕСТВО TELEGRAM",
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

            // ── 4. CALL TO ACTION: JOIN THE VOLUNTEER TEAM ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .staggeredEntrance(index = 10)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color.Transparent)
                    .border(1.dp, Color(0xFFFF9E00).copy(alpha = 0.5f), RoundedCornerShape(22.dp))
                    .lightSweep(
                        isEnabled = true,
                        shape = RoundedCornerShape(22.dp),
                        sweepColor = Color(0xFFFF9E00)
                    )
                    .padding(20.dp)
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Хотите увидеть свое имя здесь?",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "Присоединяйтесь к программе волонтеров тестирования: тестируйте новые сборки APK, находите баги и получите постоянное место в Зале Славы с персональным цифровым слепком!",
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = TextMuted,
                            textAlign = TextAlign.Center
                        )
                    }

                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onOpenVolunteers()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            contentColor = Color(0xFFFF9E00)
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFFFF9E00)),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_volunteer_badge),
                            contentDescription = null,
                            tint = Color(0xFFFF9E00),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Стать волонтером тестирования",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
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
                        text = "Благодарности",
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
                            contentDescription = "Назад",
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
                            text = tier.badgeLabel,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = tier.primaryColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Text(
                    text = contributor.role,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = tier.primaryColor.copy(alpha = 0.9f)
                )

                Text(
                    text = contributor.contribution,
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
                        text = tier.title.uppercase(),
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
                        text = contributor.role,
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
                            text = "ВКЛАД В ПРОЕКТ",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.1.sp,
                            color = TextMuted
                        )
                        Text(
                            text = contributor.contribution,
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
                                    text = "ВЕРИФИЦИРОВАННЫЙ СЛЕПОК",
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
                                Toast.makeText(context, "Цифровой слепок скопирован", Toast.LENGTH_SHORT).show()
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
                                text = "Скопировать отпечаток",
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
                            text = "Открыть профиль GitHub",
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
                        text = "Закрыть",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = TextMuted
                    )
                }
            }
        }
    }
}
