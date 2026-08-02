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
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
    onOpenTerms: () -> Unit = {}
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val pureBlack = Color(0xFF000000)

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

    // ── PARALLAX SCROLL MATH: HEADER RECEDES & SCALES ON DOWNWARD SCROLL ──
    val rawScroll = scrollState.value.toFloat()
    val maxScrollRange = with(LocalDensity.current) { 180.dp.toPx() }
    val scrollFraction = (rawScroll / maxScrollRange).coerceIn(0f, 1f)

    val headerScale = 1.0f - (scrollFraction * 0.12f)
    val headerParallaxY = rawScroll * 0.32f
    val headerAlpha = (1.0f - (scrollFraction * 0.40f)).coerceIn(0.2f, 1.0f)

    // ── SHARED ELEMENT ENTRANCE SPRING SCALE (AVATAR FLIES IN FROM BUTTON) ──
    var isEntered by remember { mutableStateOf(false) }
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
        Dialog(
            onDismissRequest = { pendingRedirectUrl = null },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = true
            )
        ) {
            val view = LocalView.current
            SideEffect {
                val window = (view.parent as? DialogWindowProvider)?.window
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    window?.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    window?.attributes = window?.attributes?.apply {
                        blurBehindRadius = 55
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.20f))
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xE60D121C),
                                    Color(0xD9080B12)
                                )
                            )
                        )
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFF00F0FF).copy(alpha = 0.12f),
                                    Color.Transparent
                                )
                            )
                        )
                        .border(
                            border = BorderStroke(
                                width = 1.2.dp,
                                brush = Brush.horizontalGradient(
                                    colors = listOf(
                                        Color(0xFF00F0FF).copy(alpha = 0.65f),
                                        Color(0xFF00F5D4).copy(alpha = 0.35f),
                                        Color(0xFF00F0FF).copy(alpha = 0.45f)
                                    )
                                )
                            ),
                            shape = RoundedCornerShape(24.dp)
                        )
                        .lightSweep(
                            isEnabled = true,
                            shape = RoundedCornerShape(24.dp),
                            borderWidth = 1.2.dp,
                            sweepColor = Color(0xFF00F0FF)
                        )
                        .padding(22.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF00F0FF).copy(alpha = 0.12f))
                                .border(1.dp, Color(0xFF00F0FF).copy(alpha = 0.35f), CircleShape)
                        ) {
                            Text(text = "🌐", fontSize = 24.sp)
                        }

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Внешний переход",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = "Вы будете перенаправлены по внешней ссылке:\n\n$targetUrl",
                                fontSize = 12.5.sp,
                                color = TextWhite.copy(alpha = 0.85f),
                                textAlign = TextAlign.Center,
                                lineHeight = 18.sp
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    pendingRedirectUrl = null
                                },
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, AmoledBorder),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(42.dp)
                            ) {
                                Text("Отклонить", fontSize = 12.5.sp, color = TextMuted)
                            }

                            Button(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    pendingRedirectUrl = null
                                    openUrl(targetUrl)
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF00F0FF),
                                    contentColor = Color.Black
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(42.dp)
                            ) {
                                Text("Перейти", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
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
                    // Glowing Avatar Icon Box (Shared Element Bounce Expansion)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(88.dp)
                            .graphicsLayer {
                                scaleX = avatarEntranceScale
                                scaleY = avatarEntranceScale
                            }
                            .clip(CircleShape)
                            .background(Color.Transparent)
                            .border(2.5.dp, ActiveGreenLed.copy(alpha = glowAlpha), CircleShape)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.avatar_developer),
                            contentDescription = "R1Xern Avatar",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                        )
                    }

                    // Developer Name & Handles
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "R1Xern",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Black,
                            color = TextWhite
                        )

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

                    // Clickable Version Pill Badge -> opens GitHub releases
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.Transparent)
                            .border(1.dp, Color(0xFF1E283D), CircleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                pendingRedirectUrl = "https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/releases"
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(ActiveGreenLed)
                        )
                        Text(
                            text = "Mirrly TG Proxy v${com.mirrly.tgproxy.BuildConfig.VERSION_NAME} (Release)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite
                        )
                    }
                }
            }

            // OFFICIAL SOURCE & VERIFICATION CARD
            OfficialSourceCard()

            // 2. BIO & MISSION CARD
            Column(
                modifier = Modifier.staggeredEntrance(index = 1),
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
                modifier = Modifier.staggeredEntrance(index = 2),
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
                        title = "Лицензия проекта (GNU GPLv3)",
                        subtitle = "Условия использования и открытый исходный код",
                        onClick = { onOpenLicense() }
                    )
                }
            }

            // 4. SUPPORT AUTHOR & DONATION CARD
            Column(
                modifier = Modifier.staggeredEntrance(index = 3),
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
                                text = "Ваша помощь стимулирует развитие новых фич!",
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

            // 5. CREDITS & ACKNOWLEDGEMENTS
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "ОСОБАЯ БЛАГОДАРНОСТЬ (CREDITS)",
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
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(Color.Transparent)
                                    .border(1.dp, ActiveGreenLed.copy(alpha = 0.4f), CircleShape)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_heart),
                                    contentDescription = null,
                                    tint = ActiveGreenLed,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Text(
                                text = "Flowseal (TG WS PROXY)",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = ActiveGreenLed
                            )
                        }

                        Text(
                            text = "Огромная благодарность разработчику Flowseal за создание оригинального проекта TG WS PROXY, концепция и наработки которого вдохновили развитие этого решения.",
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            color = TextWhite.copy(alpha = 0.85f)
                        )
                    }
                }
            }

            // 6. TECH STACK BADGES
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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

            // 7. FOOTER COPYRIGHT
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Сделано с ❤️ разработчиком R1Xern",
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
