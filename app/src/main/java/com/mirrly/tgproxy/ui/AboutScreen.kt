package com.mirrly.tgproxy.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit
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

    Scaffold(
        topBar = {
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = pureBlack)
            )
        },
        containerColor = pureBlack
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(pureBlack)
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {

            // 1. HERO DEVELOPER CARD
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.Transparent)
                    .border(
                        width = 1.5.dp,
                        brush = Brush.linearGradient(
                            colors = listOf(
                                ActiveGreenLed.copy(alpha = glowAlpha),
                                Color(0xFF181E2E)
                            )
                        ),
                        shape = RoundedCornerShape(24.dp)
                    )
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Glowing Avatar Icon Box
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(88.dp)
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
                                    openUrl("https://github.com/joycecurcirt539-dot")
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
                                openUrl("https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/releases")
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
                            text = "Mirrly TG Proxy v1.0.2 (Release)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite
                        )
                    }
                }
            }

            // 2. BIO & MISSION CARD
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "СВЯЗЬ И СОЦИАЛЬНЫЕ СЕТИ",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.3.sp,
                    color = TextMuted
                )

                // Link 1: Telegram Channel
                LinkCardItem(
                    iconRes = R.drawable.ic_telegram,
                    iconTint = Color(0xFF29B6F6),
                    title = "Telegram Канал",
                    subtitle = "Анонсы, обновления и новости: t.me/WhyOKyHb",
                    onClick = { openUrl("https://t.me/WhyOKyHb") }
                )

                // Link 2: GitHub Profile
                LinkCardItem(
                    iconRes = R.drawable.ic_github,
                    iconTint = TextWhite,
                    title = "Профиль GitHub",
                    subtitle = "github.com/joycecurcirt539-dot",
                    onClick = { openUrl("https://github.com/joycecurcirt539-dot") }
                )

                // Link 3: GitHub Repository
                LinkCardItem(
                    iconRes = R.drawable.ic_github,
                    iconTint = ActiveGreenLed,
                    title = "Репозиторий проекта",
                    subtitle = "Исходный код Mirrly TG Proxy на GitHub",
                    onClick = { openUrl("https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy") }
                )
            }

            // 4. SUPPORT AUTHOR & DONATION CARD
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(Color.Transparent)
                                    .border(1.dp, ActiveGreenLed.copy(alpha = 0.4f), CircleShape)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_heart),
                                    contentDescription = null,
                                    tint = ActiveGreenLed,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

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
                        }

                        // Outlined DaLink Support Button
                        Button(
                            onClick = { openUrl("https://dalink.to/cartneyzix") },
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

            // 6. FOOTER COPYRIGHT
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
                    text = "© 2026 Mirrly TG Proxy • MIT License",
                    fontSize = 11.sp,
                    color = TextMuted.copy(alpha = 0.6f)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
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
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Transparent)
            .border(1.dp, Color(0xFF181E2E), RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.weight(1f)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Transparent)
                    .border(1.dp, iconTint.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            ) {
                Icon(
                    painter = painterResource(id = iconRes),
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp)
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
                    fontSize = 12.sp,
                    color = TextMuted
                )
            }
        }

        Icon(
            painter = painterResource(id = R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = TextMuted,
            modifier = Modifier.size(18.dp)
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
