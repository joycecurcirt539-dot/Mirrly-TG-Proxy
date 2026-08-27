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
import android.widget.Toast
import kotlinx.coroutines.launch
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.os.Build
import android.view.WindowManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.ui.theme.*
import com.mirrly.tgproxy.util.shareApp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onOpenLicense: () -> Unit = {},
    onOpenTerms: () -> Unit = {},
    onOpenUpdate: () -> Unit = {},
    onOpenHallOfFame: () -> Unit = {},
    onOpenVolunteers: () -> Unit = {},
    onOpenChronicle: () -> Unit = {}
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val pureBlack = Color(0xFF000000)
    val ledGreen = ActiveGreenLed

    // Easter egg tap tracker for Secret Chronicle (5 taps on avatar or genesis badge)
    val coroutineScope = rememberCoroutineScope()
    var easterEggTapCount by remember { mutableIntStateOf(0) }
    var lastEasterEggTapTime by remember { mutableLongStateOf(0L) }
    val tapBounceScale = remember { Animatable(1f) }
    var isSupernovaActive by remember { mutableStateOf(false) }
    val supernovaProgress = remember { Animatable(0f) }

    fun onEasterEggTap() {
        val now = System.currentTimeMillis()
        if (now - lastEasterEggTapTime > 2200L) {
            easterEggTapCount = 1
        } else {
            easterEggTapCount++
        }
        lastEasterEggTapTime = now

        coroutineScope.launch {
            tapBounceScale.snapTo(1.18f)
            tapBounceScale.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        }

        if (easterEggTapCount >= 5) {
            easterEggTapCount = 0
            isSupernovaActive = true
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            Toast.makeText(context, "Открыта секретная летопись Mirrly!", Toast.LENGTH_SHORT).show()

            coroutineScope.launch {
                launch {
                    kotlinx.coroutines.delay(120)
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    kotlinx.coroutines.delay(120)
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }

                supernovaProgress.snapTo(0f)
                supernovaProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(480, easing = FastOutSlowInEasing)
                )
                onOpenChronicle()
                kotlinx.coroutines.delay(350)
                isSupernovaActive = false
                supernovaProgress.snapTo(0f)
            }
        } else {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    // Pulsing gradient glow for hero section
    val infiniteTransition = rememberInfiniteTransition(label = "pulseGlow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )

    fun openUrl(url: String) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Не удалось открыть ссылку: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    fun copyToClipboard(label: String, text: String) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "$label скопирован в буфер обмена", Toast.LENGTH_SHORT).show()
    }

    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val maxScrollRange = remember(density) { with(density) { 180.dp.toPx() } }

    // ── SHARED ELEMENT ENTRANCE SPRING SCALE (AVATAR FLIES IN FROM BUTTON) ──
    var isEntered by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        isEntered = true
    }

    val avatarEntranceScale by animateFloatAsState(
        targetValue = if (isEntered) 1.00f else 0.40f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "avatarEntranceScale"
    )

    val avatarEntranceAlpha by animateFloatAsState(
        targetValue = if (isEntered) 1.00f else 0.00f,
        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
        label = "avatarEntranceAlpha"
    )

    // ── CONFIRMATION DIALOG STATE FOR EXTERNAL REDIRECTS ──
    var pendingRedirectUrl by remember { mutableStateOf<String?>(null) }

    if (pendingRedirectUrl != null) {
        val targetUrl = pendingRedirectUrl ?: ""
        ExternalLinkConfirmDialog(
            url = targetUrl,
            onDismiss = { pendingRedirectUrl = null }
        )
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // 1. SCROLLABLE CONTENT LAYER (Scrolls ALL THE WAY to the top under the Frosted Header!)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .fadingEdges(topFadeHeight = 24.dp, bottomFadeHeight = 44.dp)
                .verticalScroll(scrollState)
                .padding(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 20.dp
                )
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {

            // 1. HERO DEVELOPER CARD WITH PARALLAX & SHARED ELEMENT HERO AVATAR
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .staggeredEntrance(index = 0)
                    .graphicsLayer {
                        val rawScroll = scrollState.value.toFloat()
                        val scrollFraction = (rawScroll / maxScrollRange).coerceIn(0f, 1f)
                        val headerScale = 1.0f - (scrollFraction * 0.12f)
                        val headerParallaxY = rawScroll * 0.32f
                        val headerAlpha = (1.0f - (scrollFraction * 0.40f)).coerceIn(0.2f, 1.0f)

                        scaleX = headerScale * avatarEntranceScale
                        scaleY = headerScale * avatarEntranceScale
                        translationY = headerParallaxY
                        alpha = headerAlpha * avatarEntranceAlpha
                    }
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.Transparent)
                    .border(1.dp, Color(0xFF181E2E), RoundedCornerShape(24.dp))
                    .lightSweep(isEnabled = true, shape = RoundedCornerShape(24.dp))
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Glowing Avatar Icon Box (Shared Element Bounce Expansion with Easter Egg 5-Tap Listener)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(96.dp)
                            .graphicsLayer {
                                scaleX = avatarEntranceScale * tapBounceScale.value
                                scaleY = avatarEntranceScale * tapBounceScale.value
                            }
                            .clip(CircleShape)
                            .background(Color.Transparent)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                onEasterEggTap()
                            }
                    ) {
                        // Quantum Charge Ring around Avatar
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val w = size.width
                            val h = size.height
                            val c = Offset(w / 2f, h / 2f)
                            val r = (w.coerceAtMost(h) / 2f) - 3.dp.toPx()

                            // Base Border
                            drawCircle(
                                color = ledGreen.copy(alpha = glowAlpha * 0.6f),
                                radius = r,
                                center = c,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5.dp.toPx())
                            )

                            // Charge Arc for Easter Egg (fills with each tap)
                            if (easterEggTapCount > 0) {
                                val sweep = (easterEggTapCount / 5f) * 360f
                                drawArc(
                                    brush = Brush.sweepGradient(
                                        colors = listOf(Color(0xFFFFB703), Color(0xFF00FF87), Color(0xFFFFB703)),
                                        center = c
                                    ),
                                    startAngle = -90f,
                                    sweepAngle = sweep,
                                    useCenter = false,
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                                        width = 4.dp.toPx(),
                                        cap = androidx.compose.ui.graphics.StrokeCap.Round
                                    )
                                )
                            }
                        }

                        Image(
                            painter = painterResource(id = R.drawable.avatar_developer),
                            contentDescription = "R1Xern Avatar",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(82.dp)
                                .clip(CircleShape)
                        )
                    }

                    // Developer Name & Handles
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "R1Xern",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Black,
                            color = TextWhite,
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                onEasterEggTap()
                            }
                        )

                        // Genesis Milestone Badge (Clickable • 27.07.2026)
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFFFFB703).copy(alpha = 0.12f),
                            border = BorderStroke(0.8.dp, Color(0xFFFFB703).copy(alpha = 0.40f)),
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    onEasterEggTap()
                                }
                        ) {
                            Text(
                                text = "GENESIS • 27.07.2026",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.6.sp,
                                color = Color(0xFFFFB703),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }

                        // Clickable GitHub handle pill
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.Transparent)
                                .border(1.dp, ActiveGreenLed.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    pendingRedirectUrl = "https://github.com/joycecurcirt539-dot"
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_github),
                                contentDescription = null,
                                tint = ActiveGreenLed,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "@joycecurcirt539-dot",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = ActiveGreenLed
                            )
                        }

                        Text(
                            text = "Создатель & Главный разработчик Mirrly TG Proxy",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextMuted,
                            textAlign = TextAlign.Center
                        )
                    }


                }
            }

            // OFFICIAL SOURCE & VERIFICATION CARD
            OfficialSourceCard(
                modifier = Modifier.staggeredEntrance(index = 1)
            )

            // GITHUB TOTAL DOWNLOADS STATS CARD
            DownloadStatsCard(
                modifier = Modifier.staggeredEntrance(index = 2)
            )

            // 2. BIO & MISSION CARD
            Column(
                modifier = Modifier.staggeredEntrance(index = 3),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "О РАЗРАБОТЧИКЕ И ПРОЕКТЕ",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.3.sp,
                    color = TextMuted
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Transparent)
                        .border(1.dp, Color(0xFF181E2E), RoundedCornerShape(20.dp))
                        .padding(18.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Привет! Я R1Xern — разработчик экосистемы Mirrly.",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite
                        )

                        Text(
                            text = "Mirrly TG Proxy создан для полного решения проблем с блокировками, замедлениями и сбоями в работе Telegram на Android. Приложение использует нативное ядро C/Rust, предварительно прогретый пул сокетов WsPool и маскировку трафика под безопасные WebSocket-соединения Cloudflare CDN.\n\nПроект работает исключительно локально на вашем устройстве, обеспечивая максимальную стабильность соединения, оптимизированную скорость и 100% приватность без сторонних VPN.",
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            color = TextWhite.copy(alpha = 0.85f)
                        )
                    }
                }
            }

            // 3. OFFICIAL LINKS
            Column(
                modifier = Modifier.staggeredEntrance(index = 4),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "СВЯЗЬ И ИНФОРМАЦИЯ",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.3.sp,
                    color = TextMuted
                )

                // Single Grouped Container for all Links
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.Transparent)
                        .border(1.dp, Color(0xFF181E2E), RoundedCornerShape(18.dp))
                ) {
                    // Link 1: Telegram Channel
                    LinkCardItem(
                        iconRes = R.drawable.ic_telegram,
                        iconTint = Color(0xFF29B6F6),
                        title = "Telegram Канал",
                        subtitle = "Анонсы, обновления и новости: t.me/WhyOKyHb",
                        onClick = { pendingRedirectUrl = "https://t.me/WhyOKyHb" }
                    )

                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF141824)))

                    // Link 2: GitHub Profile
                    LinkCardItem(
                        iconRes = R.drawable.ic_github,
                        iconTint = TextWhite,
                        title = "Профиль GitHub",
                        subtitle = "github.com/joycecurcirt539-dot",
                        onClick = { pendingRedirectUrl = "https://github.com/joycecurcirt539-dot" }
                    )

                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF141824)))

                    // Link 3: GitHub Repository
                    LinkCardItem(
                        iconRes = R.drawable.ic_github,
                        iconTint = ActiveGreenLed,
                        title = "Репозиторий проекта",
                        subtitle = "Исходный код Mirrly TG Proxy на GitHub",
                        onClick = { pendingRedirectUrl = "https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy" }
                    )

                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF141824)))

                    // Link 4: Share App
                    LinkCardItem(
                        iconRes = R.drawable.ic_send,
                        iconTint = ActiveGreenLed,
                        title = "Поделиться с друзьями",
                        subtitle = "Рассказать о Mirrly TG Proxy в Telegram или соцсетях",
                        onClick = { context.shareApp() }
                    )

                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF141824)))

                    // Link 5: Report Bug or Idea (GitHub Issues)
                    LinkCardItem(
                        iconRes = R.drawable.ic_bug,
                        iconTint = Color(0xFFFF9E00),
                        title = "Нашли баг или есть идея?",
                        subtitle = "Создайте Issue на GitHub — разработчик ответит лично",
                        onClick = { pendingRedirectUrl = "https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/issues/new" }
                    )

                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF141824)))

                    // Link 6: GPLv3 License Screen
                    LinkCardItem(
                        iconRes = R.drawable.ic_license,
                        iconTint = ActiveGreenLed,
                        title = "Лицензия (GPLv3)",
                        subtitle = "Открытый исходный код",
                        onClick = { onOpenLicense() }
                    )
                }
            }

            // 4. STAR ON GITHUB SUPPORT CARD
            Column(
                modifier = Modifier.staggeredEntrance(index = 5),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "ПОДДЕРЖАТЬ ЗВЁЗДОЙ НА GITHUB",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.3.sp,
                    color = TextMuted
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color.Transparent)
                        .border(1.dp, Color(0xFFFFB703).copy(alpha = 0.5f), RoundedCornerShape(22.dp))
                        .padding(18.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Column {
                            Text(
                                text = "Понравился Mirrly TG Proxy?",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Ваша звезда на GitHub помогает проекту развиваться, привлекает новых пользователей и мотивирует на новые обновления.",
                                fontSize = 12.sp,
                                color = TextMuted
                            )
                        }

                        // Star Button
                        Button(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                pendingRedirectUrl = "https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy"
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Transparent,
                                contentColor = Color(0xFFFFB703)
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFFFFB703)),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                        ) {
                            Text(
                                text = "⭐",
                                fontSize = 16.sp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Поставить Star на GitHub",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = Color(0xFFFFB703)
                            )
                        }
                    }
                }
            }

            // 5. SUPPORT AUTHOR & DONATION CARD
            Column(
                modifier = Modifier.staggeredEntrance(index = 6),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "ПОДДЕРЖАТЬ АВТОРА (DONATION)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.3.sp,
                    color = TextMuted
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color.Transparent)
                        .border(1.dp, ActiveGreenLed.copy(alpha = 0.5f), RoundedCornerShape(22.dp))
                        .padding(18.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Column {
                            Text(
                                text = "Поддержать развитие Mirrly",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite
                            )
                            Text(
                                text = "Приложение полностью бесплатное! Донат — исключительно по желанию для поддержки R1Xern.",
                                fontSize = 12.sp,
                                color = TextMuted
                            )
                        }

                        // Outlined DaLink Support Button
                        Button(
                            onClick = { pendingRedirectUrl = "https://dalink.to/cartneyzix" },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Transparent,
                                contentColor = ActiveGreenLed
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.5.dp, ActiveGreenLed),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_donate),
                                contentDescription = null,
                                tint = ActiveGreenLed,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Поддержать автора (DaLink)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }

            // 6. HALL OF FAME & VOLUNTEERS STANDOUT SECTION
            Column(
                modifier = Modifier.staggeredEntrance(index = 7),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "ЗАЛ СЛАВЫ И ТЕСТИРОВАНИЕ",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.3.sp,
                    color = TextMuted
                )

                // Standout Hall of Fame Card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Transparent)
                        .border(1.dp, Color(0xFF7C4DFF).copy(alpha = 0.50f), RoundedCornerShape(20.dp))
                        .lightSweep(
                            isEnabled = true,
                            shape = RoundedCornerShape(20.dp),
                            sweepColor = Color(0xFF7C4DFF)
                        )
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onOpenHallOfFame()
                        })
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF7C4DFF).copy(alpha = 0.15f))
                                .border(1.dp, Color(0xFF7C4DFF).copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_hall_of_fame),
                                contentDescription = null,
                                tint = Color(0xFFC084FC),
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "Зал Славы и Благодарности",
                                    fontSize = 14.5.sp,
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
                                text = "Первопроходцы, контрибьюторы и уникальные цифровые слепки",
                                fontSize = 11.5.sp,
                                color = TextMuted
                            )
                        }

                        Icon(
                            painter = painterResource(id = R.drawable.ic_chevron_right),
                            contentDescription = null,
                            tint = Color(0xFFC084FC),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // Standout Volunteer Program Card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Transparent)
                        .border(1.dp, Color(0xFFFFB703).copy(alpha = 0.50f), RoundedCornerShape(20.dp))
                        .lightSweep(
                            isEnabled = true,
                            shape = RoundedCornerShape(20.dp),
                            sweepColor = Color(0xFFFFB703)
                        )
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onOpenVolunteers()
                        })
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFFFFB703).copy(alpha = 0.15f))
                                .border(1.dp, Color(0xFFFFB703).copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_volunteer_badge),
                                contentDescription = null,
                                tint = Color(0xFFFFB703),
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "Программа тестирования",
                                    fontSize = 14.5.sp,
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
                                text = "Ищем волонтеров тестирования: ранний доступ к APK",
                                fontSize = 11.5.sp,
                                color = TextMuted
                            )
                        }

                        Icon(
                            painter = painterResource(id = R.drawable.ic_chevron_right),
                            contentDescription = null,
                            tint = Color(0xFFFFB703),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // 7. TECH STACK BADGES
            Column(
                modifier = Modifier.staggeredEntrance(index = 8),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "ТЕХНОЛОГИЧЕСКИЙ СТЕК",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.3.sp,
                    color = TextMuted
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TechBadge(text = "Kotlin 1.9", modifier = Modifier.weight(1f))
                    TechBadge(text = "Jetpack Compose", modifier = Modifier.weight(1f))
                    TechBadge(text = "Rust / C Native", modifier = Modifier.weight(1f))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TechBadge(text = "Cloudflare Workers", modifier = Modifier.weight(1f))
                    TechBadge(text = "MTProto FakeTLS", modifier = Modifier.weight(1f))
                    TechBadge(text = "JNA Direct Calls", modifier = Modifier.weight(1f))
                }
            }

            // 8. FOOTER COPYRIGHT
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Разработано с ❤️ Mirrly Dev",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextMuted
                )
                Text(
                    text = "© 2026 Mirrly TG Proxy • GNU GPLv3",
                    fontSize = 11.sp,
                    color = TextMuted.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Условия использования",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextMuted.copy(alpha = 0.85f),
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onOpenTerms()
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // 2. FROSTED GLASS HEADER PANEL (Pinned at Top over scrolling cards!)
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
                        text = "О разработчике",
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

        // Delicate Cyber Particles floating over entire about screen interface
        CyberParticlesOverlay(
            modifier = Modifier.fillMaxSize(),
            particleCount = 42,
            alphaMultiplier = 0.70f
        )

        // ── 4. SUPERNOVA EASTER EGG BLAST OVERLAY (5TH TAP EXPLOSION) ──
        if (isSupernovaActive && supernovaProgress.value > 0f) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val p = supernovaProgress.value
                val w = size.width
                val h = size.height
                val center = Offset(w / 2f, h * 0.28f) // from avatar position
                val maxR = kotlin.math.sqrt((w * w + h * h).toDouble()).toFloat()

                // 1. Expanding Quantum Core Glow
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = (1f - p) * 0.95f),
                            Color(0xFFFFB703).copy(alpha = (1f - p) * 0.85f),
                            Color(0xFF00FF87).copy(alpha = (1f - p) * 0.45f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = maxR * p
                    ),
                    radius = maxR * p,
                    center = center
                )

                // 2. Shockwave Rings
                for (ring in 1..3) {
                    val ringP = ((p * 1.3f) - (ring * 0.15f)).coerceIn(0f, 1f)
                    if (ringP > 0f) {
                        drawCircle(
                            color = Color(0xFFFFB703).copy(alpha = (1f - ringP) * 0.8f),
                            radius = maxR * ringP,
                            center = center,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = (4f * (1f - ringP)).dp.toPx())
                        )
                    }
                }

                // 3. Photon Rays
                for (ray in 0 until 12) {
                    val angle = Math.toRadians((ray * 30.0 + p * 45.0))
                    val rayLen = maxR * p * 0.9f
                    val rx = center.x + (kotlin.math.cos(angle) * rayLen).toFloat()
                    val ry = center.y + (kotlin.math.sin(angle) * rayLen).toFloat()
                    drawLine(
                        color = Color.White.copy(alpha = (1f - p) * 0.7f),
                        start = center,
                        end = Offset(rx, ry),
                        strokeWidth = (2.5f * (1f - p)).dp.toPx()
                    )
                }
            }
        }
    }
}

@Composable
private fun LinkCardItem(
    iconRes: Int,
    iconTint: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
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
                    .background(iconTint.copy(alpha = 0.12f))
                    .border(1.dp, iconTint.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
            ) {
                Icon(
                    painter = painterResource(id = iconRes),
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(16.dp)
                )
            }

            Column {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite
                )
                Text(
                    text = subtitle,
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

@Composable
private fun TechBadge(text: String, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Transparent)
            .border(1.dp, Color(0xFF181E2E), RoundedCornerShape(10.dp))
            .padding(vertical = 8.dp)
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextWhite.copy(alpha = 0.9f),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun DownloadStatsCard(
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var totalDownloads by remember { mutableStateOf<Int?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isError by remember { mutableStateOf(false) }

    fun loadStats() {
        coroutineScope.launch {
            isLoading = true
            isError = false
            val result = com.mirrly.tgproxy.core.UpdateChecker.fetchTotalDownloads(com.mirrly.tgproxy.BuildConfig.VERSION_NAME)
            result.onSuccess { count ->
                totalDownloads = count
                isLoading = false
            }.onFailure {
                isError = true
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        loadStats()
    }

    val cyanGlow = Color(0xFF0088CC)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Transparent)
            .border(1.dp, Color(0xFF181E2E), RoundedCornerShape(16.dp))
            .lightSweep(isEnabled = true, shape = RoundedCornerShape(16.dp), sweepColor = cyanGlow)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(cyanGlow.copy(alpha = 0.12f))
                    .border(1.dp, cyanGlow.copy(alpha = 0.35f), CircleShape)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_arrow_down),
                    contentDescription = null,
                    tint = cyanGlow,
                    modifier = Modifier.size(18.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "ВСЕГО СКАЧИВАНИЙ",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.1.sp,
                        color = TextMuted
                    )

                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (isLoading) Color(0xFFFF9E00) else ActiveGreenLed)
                    )
                }

                if (isLoading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(vertical = 2.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            color = cyanGlow,
                            strokeWidth = 1.8.dp
                        )
                        Text(
                            text = "Получение статистики...",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextMuted
                        )
                    }
                } else if (isError) {
                    Text(
                        text = "Сбой загрузки (нажмите для повтора)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFFF9E00),
                        modifier = Modifier.clickable { loadStats() }
                    )
                } else {
                    val countText = totalDownloads?.let { String.format("%,d", it).replace(',', ' ') } ?: "0"
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = countText,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black,
                            color = TextWhite
                        )
                        Text(
                            text = "скачиваний",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = cyanGlow
                        )
                    }
                }
            }
        }
    }
}
