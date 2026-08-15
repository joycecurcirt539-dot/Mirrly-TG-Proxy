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

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.ui.theme.*

@Composable
fun Socks5WarningDialog(
    onOpenGuide: () -> Unit,
    onConfirmSocks5: () -> Unit,
    onRevertToMtproto: () -> Unit
) {
    val haptic = LocalHapticFeedback.current

    Dialog(
        onDismissRequest = onRevertToMtproto,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        val view = LocalView.current
        LaunchedEffect(Unit) {
            try {
                val window = (view.parent as? DialogWindowProvider)?.window
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    window?.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    window?.attributes = window?.attributes?.apply {
                        blurBehindRadius = 50
                    }
                }
            } catch (_: Exception) {}
        }


        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.12f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onRevertToMtproto() }
                .padding(horizontal = 24.dp)
        ) {
            // Detailed Information with Smooth Fading Edges
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .fadingEdges(topFadeHeight = 32.dp, bottomFadeHeight = 64.dp)
                    .padding(bottom = 180.dp, top = 24.dp)
                    .verticalScroll(rememberScrollState())
                    .clickable(enabled = false) {}
            ) {
                // Main Title & Category Badges (Centered)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0xFFFF9E00).copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, Color(0xFFFF9E00).copy(alpha = 0.35f))
                    ) {
                        Text(
                            text = "ТРЕБУЕТСЯ CLOUDFLARE WORKER",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFF9E00),
                            letterSpacing = 0.8.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }

                    // BETA Badge in Corner
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0xFFFF3D00).copy(alpha = 0.18f),
                        border = BorderStroke(1.dp, Color(0xFFFF3D00).copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = "БЕТА",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFFFF3D00),
                            letterSpacing = 0.8.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                // Main Title
                Text(
                    text = "Режим SOCKS5 Proxy [БЕТА]",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite,
                    textAlign = TextAlign.Center,
                    letterSpacing = 0.3.sp
                )

                // Card 1: Main SOCKS5 Overview
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White.copy(alpha = 0.04f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "В режиме SOCKS5 трафик мессенджера не использует стандартные WSS-сервера Telegram. Для обхода блокировок и работы звонков необходимо развернуть собственный Cloudflare Worker.",
                        fontSize = 13.sp,
                        color = TextWhite.copy(alpha = 0.88f),
                        textAlign = TextAlign.Start,
                        lineHeight = 19.sp,
                        modifier = Modifier.padding(14.dp)
                    )
                }

                // Card 2: BETA Warning Card (Amber Glass)
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFFFF9E00).copy(alpha = 0.08f),
                    border = BorderStroke(1.dp, Color(0xFFFF9E00).copy(alpha = 0.30f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "ОБРАТИТЕ ВНИМАНИЕ",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFF9E00),
                            letterSpacing = 0.6.sp
                        )
                        Text(
                            text = "Режим SOCKS5 находится в стадии БЕТА-тестирования. Стабильная работа, высокая скорость и постоянный обход блокировок на данный момент НЕ гарантируются.",
                            fontSize = 12.5.sp,
                            color = TextWhite.copy(alpha = 0.90f),
                            textAlign = TextAlign.Start,
                            lineHeight = 18.5.sp
                        )
                    }
                }

                // Card 3: Recommendation Card (Cyan Glass)
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MtprotoAccent.copy(alpha = 0.06f),
                    border = BorderStroke(1.dp, MtprotoAccent.copy(alpha = 0.25f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "РЕКОМЕНДАЦИЯ",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = MtprotoAccent,
                            letterSpacing = 0.6.sp
                        )
                        Text(
                            text = "Для обычных чатов и медиа рекомендуется использовать MTProto — он работает стабильно и «из коробки».",
                            fontSize = 12.5.sp,
                            color = TextWhite.copy(alpha = 0.90f),
                            textAlign = TextAlign.Start,
                            lineHeight = 18.5.sp
                        )
                    }
                }
            }

            // Bottom Action Buttons Dock (Floating seamlessly over blurred background)
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp)
                    .fillMaxWidth()
                    .clickable(enabled = false) {}
            ) {
                // Primary Action: Open Guide
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onOpenGuide()
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ActiveGreenLed.copy(alpha = 0.20f),
                        contentColor = ActiveGreenLed
                    ),
                    border = BorderStroke(1.dp, ActiveGreenLed.copy(alpha = 0.50f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .springPress()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_settings),
                            contentDescription = null,
                            tint = ActiveGreenLed,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Инструкция по настройке Worker",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Secondary Action: Continue SOCKS5 anyway
                OutlinedButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onConfirmSocks5()
                    },
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = TextWhite.copy(alpha = 0.90f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .springPress()
                ) {
                    Text(
                        text = "Всё равно включить (БЕТА)",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Revert to MTProto Action
                TextButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onRevertToMtproto()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Остаться на MTProto (Рекомендуется)",
                        color = TextMuted,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
