/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * GNU GPL v3+ <https://www.gnu.org/licenses/>
 */

package com.mirrly.tgproxy.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.ui.theme.*

private data class ExternalGenerator(
    @StringRes val nameRes: Int,
    val url: String,
    @StringRes val noteRes: Int
)

/**
 * Руководство по VPN режиму (VpnOnboarding).
 * Строгий визуальный стиль MirrlyUI: Zero-Popup (MirrlyModalBottomSheet),
 * Zero-Background / Outline-Only, пружинная физика springPress, без эмодзи.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VpnOnboardingDialog(
    onDismiss: () -> Unit,
    onCreateProfileAuto: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenGenerator: (() -> Unit)? = null
) {
    val haptic = LocalHapticFeedback.current
    var pendingConfirmUrl by remember { mutableStateOf<String?>(null) }

    val externalGenerators = remember {
        listOf(
            ExternalGenerator(
                nameRes = R.string.vpn_onboard_gen_warp_reg_name,
                url = "https://warp-reg.surge.sh",
                noteRes = R.string.vpn_onboard_gen_warp_reg_note
            ),
            ExternalGenerator(
                nameRes = R.string.vpn_onboard_gen_wgcf_name,
                url = "https://github.com/ViRb3/wgcf",
                noteRes = R.string.vpn_onboard_gen_wgcf_note
            ),
            ExternalGenerator(
                nameRes = R.string.vpn_onboard_gen_bot1_name,
                url = "https://t.me/generatewarpbot",
                noteRes = R.string.vpn_onboard_gen_bot_note
            ),
            ExternalGenerator(
                nameRes = R.string.vpn_onboard_gen_bot2_name,
                url = "https://t.me/warp_generator_bot",
                noteRes = R.string.vpn_onboard_gen_bot_note
            )
        )
    }

    MirrlyModalBottomSheet(
        onDismissRequest = onDismiss,
        maxTabletWidth = 520.dp
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .fadingEdges(topFadeHeight = 16.dp, bottomFadeHeight = 24.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 14.dp, top = 2.dp)
        ) {
            // Category Pill (Категорийный бейдж)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Transparent)
                    .border(1.dp, ActiveGreenLed.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(
                    text = stringResource(R.string.vpn_onboard_badge),
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = ActiveGreenLed,
                    letterSpacing = 1.sp
                )
            }

            // Title
            Text(
                text = stringResource(R.string.vpn_onboard_title),
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = TextWhite,
                textAlign = TextAlign.Center
            )

            // Subtitle
            Text(
                text = stringResource(R.string.vpn_onboard_subtitle),
                fontSize = 12.sp,
                color = TextMuted,
                textAlign = TextAlign.Center
            )

            // Шаг 01: Системный туннель (Accent: ActiveGreenLed)
            OnboardStepCard(
                stepNumber = "01",
                title = stringResource(R.string.vpn_onboard_step1_title),
                description = stringResource(R.string.vpn_onboard_step1_desc),
                accentColor = ActiveGreenLed
            )

            // Шаг 02: Протоколы туннелирования (Accent: ActiveGreenLed)
            OnboardStepCard(
                stepNumber = "02",
                title = stringResource(R.string.vpn_onboard_step2_title),
                description = stringResource(R.string.vpn_onboard_step2_desc),
                accentColor = ActiveGreenLed
            )

            // Шаг 03: Профиль подключения и генерация (Accent: Color(0xFFF59E0B))
            OnboardStepCard(
                stepNumber = "03",
                title = stringResource(R.string.vpn_onboard_step3_title),
                description = stringResource(R.string.vpn_onboard_step3_desc),
                accentColor = Color(0xFFF59E0B)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Встроенный WARP-генератор
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.Transparent)
                            .border(1.dp, Color(0xFFF59E0B).copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                            .springPress(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onDismiss()
                                if (onOpenGenerator != null) {
                                    onOpenGenerator()
                                } else {
                                    onCreateProfileAuto()
                                }
                            })
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.vpn_onboard_internal_gen_title),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFF59E0B)
                            )
                            Text(
                                text = stringResource(R.string.vpn_onboard_internal_gen_subtitle),
                                fontSize = 10.sp,
                                color = TextMuted
                            )
                        }
                        Icon(
                            painter = painterResource(id = R.drawable.ic_chevron_right),
                            contentDescription = null,
                            tint = Color(0xFFF59E0B),
                            modifier = Modifier.size(14.dp)
                        )
                    }

                    // Список проверенных внешних генераторов
                    Text(
                        text = stringResource(R.string.vpn_onboard_external_gen_title),
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextMuted,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                    )

                    externalGenerators.forEach { generator ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Transparent)
                                .border(1.dp, AmoledBorder, RoundedCornerShape(8.dp))
                                .springPress(onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    pendingConfirmUrl = generator.url
                                })
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(generator.nameRes),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextWhite
                                )
                                Text(
                                    text = stringResource(generator.noteRes),
                                    fontSize = 9.5.sp,
                                    color = TextMuted
                                )
                            }
                            Icon(
                                painter = painterResource(id = R.drawable.ic_dalink),
                                contentDescription = null,
                                tint = TextMuted,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }
                }
            }

            // Блок кнопок действий (Bottom Actions Column)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Основная кнопка: Создать в WARP генераторе
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Transparent)
                        .border(1.dp, ActiveGreenLed.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDismiss()
                            if (onOpenGenerator != null) {
                                onOpenGenerator()
                            } else {
                                onCreateProfileAuto()
                            }
                        })
                ) {
                    Text(
                        text = stringResource(R.string.vpn_onboard_btn_auto_create),
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = ActiveGreenLed
                    )
                }

                // Вторичная кнопка: Импортировать свой профиль
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Transparent)
                        .border(1.dp, AmoledBorder, RoundedCornerShape(12.dp))
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onDismiss()
                            onOpenImport()
                        })
                ) {
                    Text(
                        text = stringResource(R.string.vpn_onboard_btn_import),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextWhite
                    )
                }

                // Текстовая кнопка: Понятно
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onDismiss()
                        })
                ) {
                    Text(
                        text = stringResource(R.string.vpn_onboard_btn_continue),
                        fontSize = 11.5.sp,
                        color = TextMuted
                    )
                }
            }
        }
    }

    // Диалог подтверждения перехода по внешней ссылке
    pendingConfirmUrl?.let { url ->
        ExternalLinkConfirmDialog(
            url = url,
            onDismiss = { pendingConfirmUrl = null }
        )
    }
}

@Composable
private fun OnboardStepCard(
    stepNumber: String,
    title: String,
    description: String,
    accentColor: Color,
    content: (@Composable () -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Transparent)
            .border(1.dp, AmoledBorder, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Transparent)
                        .border(1.dp, accentColor.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = stepNumber,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        color = accentColor
                    )
                }

                Text(
                    text = title,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite
                )
            }

            Text(
                text = description,
                fontSize = 11.5.sp,
                color = TextMuted,
                lineHeight = 16.5.sp
            )

            if (content != null) {
                Spacer(modifier = Modifier.height(4.dp))
                content()
            }
        }
    }
}
