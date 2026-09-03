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

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.core.ProxyConfig
import com.mirrly.tgproxy.ui.theme.*

@Composable
fun Socks5AuthRequiredDialog(
    onDismiss: () -> Unit,
    onConfirm: (username: String, password: String) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val app = MirrlyApplication.instance

    var username by remember { mutableStateOf(app.config.socks5Username) }
    var password by remember { mutableStateOf(app.config.socks5Password) }
    var showPassword by remember { mutableStateOf(false) }
    var showError by remember { mutableStateOf(false) }

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
            onDismiss = onDismiss,
            blurRadiusPx = 70
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .align(Alignment.Center)
                    .adaptiveContainerWidth(440.dp)
                    .navigationBarsPadding()
                    .padding(bottom = 110.dp, top = 24.dp)
                    .padding(horizontal = 20.dp)
                    .verticalScroll(rememberScrollState())
                    .clickable(enabled = false) {}
            ) {
                // Category Pill Badge
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Socks5Accent.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, Socks5Accent.copy(alpha = 0.40f))
                ) {
                    Text(
                        text = "SOCKS5 • ТРЕБУЕТСЯ АВТОРИЗАЦИЯ",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Socks5Accent,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                }

                Text(
                    text = "Настройка доступа SOCKS5",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "Для запуска SOCKS5 прокси необходимо указать логин и пароль. Они обеспечат защиту вашего локального прокси-сервера (RFC 1929) и будут автоматически переданы в Telegram.",
                    fontSize = 12.5.sp,
                    color = TextWhite.copy(alpha = 0.82f),
                    textAlign = TextAlign.Center,
                    lineHeight = 17.5.sp
                )

                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = Color.White.copy(alpha = 0.04f),
                    border = BorderStroke(1.dp, Color(0xFF1E2433)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Username field
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "Логин (Username)",
                                color = TextWhite,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            OutlinedTextField(
                                value = username,
                                onValueChange = {
                                    username = it
                                    showError = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                isError = showError && username.trim().isEmpty(),
                                placeholder = { Text("Введите логин", color = TextMuted, fontSize = 13.sp) },
                                shape = RoundedCornerShape(12.dp),
                                supportingText = if (showError && username.trim().isEmpty()) {
                                    { Text("Логин обязателен для SOCKS5", color = Color(0xFFEF4444), fontSize = 11.5.sp) }
                                } else null,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedBorderColor = Socks5Accent,
                                    unfocusedBorderColor = Color(0xFF1E2333),
                                    focusedTextColor = TextWhite,
                                    unfocusedTextColor = TextWhite,
                                    errorBorderColor = Color(0xFFEF4444),
                                    errorTextColor = TextWhite
                                )
                            )
                        }

                        // Password field
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "Пароль (Password)",
                                color = TextWhite,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            OutlinedTextField(
                                value = password,
                                onValueChange = {
                                    password = it
                                    showError = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                isError = showError && password.trim().isEmpty(),
                                placeholder = { Text("Введите пароль", color = TextMuted, fontSize = 13.sp) },
                                shape = RoundedCornerShape(12.dp),
                                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(
                                        onClick = {
                                            showPassword = !showPassword
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Crossfade(targetState = showPassword, animationSpec = tween(180), label = "dlgEyeFade") { isVisible ->
                                            Icon(
                                                painter = painterResource(id = if (isVisible) R.drawable.ic_eye_slash else R.drawable.ic_eye),
                                                contentDescription = null,
                                                tint = if (isVisible) Socks5Accent else TextMuted,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                },
                                supportingText = if (showError && password.trim().isEmpty()) {
                                    { Text("Пароль обязателен для SOCKS5", color = Color(0xFFEF4444), fontSize = 11.5.sp) }
                                } else null,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedBorderColor = Socks5Accent,
                                    unfocusedBorderColor = Color(0xFF1E2333),
                                    focusedTextColor = TextWhite,
                                    unfocusedTextColor = TextWhite,
                                    errorBorderColor = Color(0xFFEF4444),
                                    errorTextColor = TextWhite
                                )
                            )
                        }

                        // Generate Random button
                        Button(
                            onClick = {
                                val (u, p) = ProxyConfig.generateRandomSocks5Credentials()
                                username = u
                                password = p
                                showError = false
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Socks5Accent.copy(alpha = 0.18f),
                                contentColor = Socks5Accent
                            ),
                            border = BorderStroke(1.dp, Socks5Accent.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth().height(40.dp)
                        ) {
                            Text("Сгенерировать случайные", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // Action buttons (Confirm / Dismiss)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDismiss()
                        },
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
                        modifier = Modifier.weight(1f).height(46.dp)
                    ) {
                        Text("Отмена", color = TextWhite, fontSize = 13.sp)
                    }

                    Button(
                        onClick = {
                            val u = username.trim()
                            val p = password.trim()
                            if (u.isEmpty() || p.isEmpty()) {
                                showError = true
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                return@Button
                            }
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onConfirm(u, p)
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Socks5Accent.copy(alpha = 0.25f),
                            contentColor = Socks5Accent
                        ),
                        border = BorderStroke(1.dp, Socks5Accent.copy(alpha = 0.7f)),
                        modifier = Modifier.weight(1.5f).height(46.dp)
                    ) {
                        Text("Сохранить и запустить", fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                    }
                }
            }
        }
    }
}
