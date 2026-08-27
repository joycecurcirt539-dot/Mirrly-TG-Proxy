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

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
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
fun VolunteerProgramScreen(
    onBack: () -> Unit,
    onOpenHallOfFame: () -> Unit = {}
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scrollState = rememberScrollState()

    val amberGold = Color(0xFFFFB703)
    val solarOrange = Color(0xFFFF7A00)

    val infiniteTransition = rememberInfiniteTransition(label = "volunteerGlow")
    val badgePulse by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "badgePulseScale"
    )

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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .fadingEdges(topFadeHeight = 24.dp, bottomFadeHeight = 44.dp)
                .verticalScroll(scrollState)
                .padding(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp
                )
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // ── 1. HERO RECRUITMENT BANNER CARD ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .staggeredEntrance(index = 0)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.Transparent)
                    .border(1.dp, amberGold.copy(alpha = 0.55f), RoundedCornerShape(24.dp))
                    .lightSweep(
                        isEnabled = true,
                        shape = RoundedCornerShape(24.dp),
                        sweepColor = amberGold
                    )
                    .padding(22.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Pulsing Volunteer Shield Badge
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(76.dp)
                            .graphicsLayer {
                                scaleX = badgePulse
                                scaleY = badgePulse
                            }
                            .clip(CircleShape)
                            .background(amberGold.copy(alpha = 0.15f))
                            .border(2.dp, amberGold.copy(alpha = 0.65f), CircleShape)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_volunteer_badge),
                            contentDescription = null,
                            tint = amberGold,
                            modifier = Modifier.size(38.dp)
                        )
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Ищем волонтеров тестирования",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Black,
                            color = TextWhite,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "Помогите развивать Mirrly TG Proxy и получите прямой доступ к свежим сборкам обновлений до их публикации!",
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = TextMuted,
                            textAlign = TextAlign.Center
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = amberGold.copy(alpha = 0.14f),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            amberGold.copy(alpha = 0.40f)
                        )
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(amberGold)
                            )
                            Text(
                                text = "ОТКРЫТ НАБОР АЛЬФА И БЕТА-ТЕСТИРОВЩИКОВ",
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = amberGold
                            )
                        }
                    }
                }
            }

            // ── 2. ADVANTAGES & PRIVILEGES FOR VOLUNTEERS ──
            Column(
                modifier = Modifier.staggeredEntrance(index = 1),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "ЧТО ВЫ ПОЛУЧАЕТЕ (ПРИВИЛЕГИИ)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.2.sp,
                    color = amberGold
                )

                BenefitItemCard(
                    iconRes = R.drawable.ic_speed_turbo,
                    iconTint = amberGold,
                    title = "Прямой доступ к свежим сборкам",
                    description = "Вы будете первыми получать тестовые APK с новыми возможностями, оптимизациями и протоколами задолго до публичного релиза.",
                    modifier = Modifier.staggeredEntrance(index = 2)
                )

                BenefitItemCard(
                    iconRes = R.drawable.ic_hall_of_fame,
                    iconTint = Color(0xFFC084FC),
                    title = "Почетное место в Зале Славы",
                    description = "Ваше имя и персональный криптографический цифровой слепок будут увековечены во вкладке благодарностей («О разработчике»).",
                    modifier = Modifier.staggeredEntrance(index = 3)
                )

                BenefitItemCard(
                    iconRes = R.drawable.ic_user,
                    iconTint = ActiveGreenLed,
                    title = "Прямая связь с создателем",
                    description = "Закрытый чат с разработчиком (R1Xern) для обсуждения сетевой архитектуры, предложений и влияния на развитие проекта.",
                    modifier = Modifier.staggeredEntrance(index = 4)
                )

                BenefitItemCard(
                    iconRes = R.drawable.ic_shield,
                    iconTint = Color(0xFF00B4D8),
                    title = "Вклад в свободный интернет",
                    description = "Ваши тесты помогут тысячам людей пользоваться быстрым и стабильным Telegram в условиях жестких блокировок.",
                    modifier = Modifier.staggeredEntrance(index = 5)
                )
            }

            // ── 3. WHAT VOLUNTEERS TEST ──
            Column(
                modifier = Modifier.staggeredEntrance(index = 6),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "ЗАДАЧИ И ОБЛАСТИ ТЕСТИРОВАНИЯ",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.2.sp,
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
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        TaskRow(
                            stepNumber = "01",
                            title = "Разнообразие версий Android & Оболочек",
                            desc = "Проверка работы на разных прошивках: HyperOS/MIUI, OneUI, Pixel, Realme UI, ColorOS, OxygenOS от Android 8.0 до 16+."
                        )
                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF141824)))
                        TaskRow(
                            stepNumber = "02",
                            title = "Переключение сетей & Роуминг",
                            desc = "Тестирование моментального реконнекта сокетов при переходе между Wi-Fi и мобильными сетями (LTE, 5G, 3G) и в метро."
                        )
                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF141824)))
                        TaskRow(
                            stepNumber = "03",
                            title = "Качество звонков & Загрузка медиа",
                            desc = "Оценка задержек и стабильности голосовых и видеозвонков через SOCKS5 и быстрая загрузка тяжелых файлов."
                        )
                    }
                }
            }

            // ── 4. HOW TO JOIN (STEPS & CTA) ──
            Column(
                modifier = Modifier.staggeredEntrance(index = 7),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "КАК СТАТЬ ВОЛОНТЕРОМ ТЕСТИРОВАНИЯ",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.2.sp,
                    color = amberGold
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color.Transparent)
                        .border(1.dp, amberGold.copy(alpha = 0.45f), RoundedCornerShape(22.dp))
                        .padding(18.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "Готовы присоединиться к тестированию?",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite
                            )
                            Text(
                                text = "Нажмите кнопку ниже, чтобы написать разработчику в Telegram или перейти в сообщество. Укажите модель вашего устройства и версию Android — я добавлю вас в закрытую группу тестирования!",
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                                color = TextMuted
                            )
                        }

                        // Apply Button
                        Button(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                pendingRedirectUrl = "https://t.me/WhyOKyHb"
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Transparent,
                                contentColor = amberGold
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.5.dp, amberGold),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_telegram),
                                contentDescription = null,
                                tint = amberGold,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Подать заявку в Telegram",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }

                        // Secondary Link to Hall of Fame
                        OutlinedButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onOpenHallOfFame()
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFC084FC)),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF7C4DFF).copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_hall_of_fame),
                                contentDescription = null,
                                tint = Color(0xFFC084FC),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Посмотреть Зал Славы (Благодарности)",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Frosted Top Header
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
                        text = "Программа тестирования",
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
            particleCount = 36,
            alphaMultiplier = 0.60f
        )
    }
}

@Composable
private fun BenefitItemCard(
    iconRes: Int,
    iconTint: Color,
    title: String,
    description: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Transparent)
            .border(1.dp, Color(0xFF181E2E), RoundedCornerShape(18.dp))
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(iconTint.copy(alpha = 0.12f))
                    .border(1.dp, iconTint.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            ) {
                Icon(
                    painter = painterResource(id = iconRes),
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite
                )
                Text(
                    text = description,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = TextMuted
                )
            }
        }
    }
}

@Composable
private fun TaskRow(
    stepNumber: String,
    title: String,
    desc: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFF161C2C),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF222A40))
        ) {
            Text(
                text = stepNumber,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                color = Color(0xFFFFB703),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Bold,
                color = TextWhite
            )
            Text(
                text = desc,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = TextMuted
            )
        }
    }
}
