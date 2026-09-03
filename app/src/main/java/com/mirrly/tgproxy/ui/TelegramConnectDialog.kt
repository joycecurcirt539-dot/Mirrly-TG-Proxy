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
import android.os.Build
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.core.ProxyConfig
import com.mirrly.tgproxy.ui.theme.*

@Composable
fun TelegramConnectDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val app = MirrlyApplication.instance
    val server = app.proxyServer

    val mtprotoUrl = remember(app.config.secretHex, app.config.bindHost, app.config.bindPort) { server.getTelegramProxyUrl() }
    val socks5Url = remember(app.config.bindHost, app.config.activePort, app.config.socks5Username, app.config.socks5Password) { server.getTelegramSocks5Url() }

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
                // Category Pill
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = ActiveGreenLed.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, ActiveGreenLed.copy(alpha = 0.35f))
                ) {
                    Text(
                        text = "ВЫБОР ПРОТОКОЛА ДЛЯ TELEGRAM",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = ActiveGreenLed,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                }

                Text(
                    text = "Подключение к Telegram",
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "Выберите подходящий протокол под вашу текущую задачу:",
                    fontSize = 13.sp,
                    color = TextWhite.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )

                // ─── CARD 1: MTProto Proxy (Рекомендуется для чатов) ───
                val isMtActive = !app.config.isSocks5Mode
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = if (isMtActive) MtprotoAccent.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.04f),
                    border = BorderStroke(1.dp, if (isMtActive) MtprotoAccent.copy(alpha = 0.7f) else Color(0xFF1E2433)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = MtprotoAccent,
                                    modifier = Modifier.size(8.dp)
                                ) {}
                                Text(
                                    text = "MTProto Proxy",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = TextWhite
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MtprotoAccent.copy(alpha = 0.15f),
                                border = BorderStroke(1.dp, MtprotoAccent.copy(alpha = 0.4f))
                            ) {
                                Text(
                                    text = if (isMtActive) "АКТИВЕН • РЕКОМЕНДУЕТСЯ" else "РЕКОМЕНДУЕТСЯ",
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Black,
                                    color = MtprotoAccent,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Text(
                            text = "Идеально для чатов, каналов и 4K-медиа. Максимальная скорость, пул WsPool и минимальный расход батареи.",
                            fontSize = 12.5.sp,
                            color = TextWhite.copy(alpha = 0.78f),
                            lineHeight = 17.sp
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Button(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    if (app.config.isSocks5Mode) {
                                        app.config.proxyModeName = com.mirrly.tgproxy.core.ProxyMode.MTPROTO.name
                                        app.prefsManager.saveConfig(app.config)
                                        if (app.proxyServer.isRunning) {
                                            app.proxyServer.restart(context.cacheDir)
                                        }
                                    }
                                    onDismiss()
                                    applyToTelegramPackages(context, mtprotoUrl)
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MtprotoAccent.copy(alpha = 0.22f),
                                    contentColor = MtprotoAccent
                                ),
                                border = BorderStroke(1.dp, MtprotoAccent.copy(alpha = 0.6f)),
                                modifier = Modifier.weight(1.3f).height(42.dp)
                            ) {
                                Text("В Telegram", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }

                            OutlinedButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Telegram MTProto", mtprotoUrl))
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    Toast.makeText(context, "MTProto ссылка скопирована!", Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                                modifier = Modifier.weight(1f).height(42.dp)
                            ) {
                                Text("Копировать", color = TextWhite, fontSize = 12.sp)
                            }
                        }
                    }
                }

                // ─── CARD 2: SOCKS5 Proxy (Для звонков) ───
                val isSocks5Active = app.config.isSocks5Mode
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = if (isSocks5Active) Socks5Accent.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.04f),
                    border = BorderStroke(1.dp, if (isSocks5Active) Socks5Accent.copy(alpha = 0.7f) else Color(0xFF1E2433)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = Socks5Accent,
                                    modifier = Modifier.size(8.dp)
                                ) {}
                                Text(
                                    text = "SOCKS5 Proxy",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = TextWhite
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Socks5Accent.copy(alpha = 0.15f),
                                border = BorderStroke(1.dp, Socks5Accent.copy(alpha = 0.5f))
                            ) {
                                Text(
                                    text = if (isSocks5Active) "АКТИВЕН • БЕТА" else "БЕТА • ДЛЯ ЗВОНКОВ",
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Black,
                                    color = Socks5Accent,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Text(
                            text = "Поддерживает обход блокировок аудио- и видеозвонков через TCP-туннель. Режим находится в стадии БЕТА-тестирования, стабильность работы не гарантируется.",
                            fontSize = 12.5.sp,
                            color = TextWhite.copy(alpha = 0.78f),
                            lineHeight = 17.sp
                        )

                        if (app.config.hasSocks5Auth) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color.White.copy(alpha = 0.04f),
                                border = BorderStroke(1.dp, Color(0xFF1E2433)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Логин: ${app.config.socks5Username.ifEmpty { "—" }}",
                                        fontSize = 11.sp,
                                        color = TextWhite.copy(alpha = 0.85f),
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "Пароль: ${if (app.config.socks5Password.isNotEmpty()) "••••••••" else "—"}",
                                        fontSize = 11.sp,
                                        color = TextWhite.copy(alpha = 0.85f),
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Button(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    if (!app.config.hasSocks5Auth) {
                                        val (u, p) = ProxyConfig.generateRandomSocks5Credentials()
                                        app.config.socks5Username = u
                                        app.config.socks5Password = p
                                        app.prefsManager.saveConfig(app.config)
                                        com.mirrly.tgproxy.core.NativeProxy.setSocks5Auth(u, p)
                                    }
                                    if (!app.config.isSocks5Mode) {
                                        app.config.proxyModeName = com.mirrly.tgproxy.core.ProxyMode.SOCKS5.name
                                        app.prefsManager.saveConfig(app.config)
                                        if (app.proxyServer.isRunning) {
                                            app.proxyServer.restart(context.cacheDir)
                                        }
                                    }
                                    onDismiss()
                                    applyToTelegramPackages(context, server.getTelegramSocks5Url())
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Socks5Accent.copy(alpha = 0.22f),
                                    contentColor = Socks5Accent
                                ),
                                border = BorderStroke(1.dp, Socks5Accent.copy(alpha = 0.6f)),
                                modifier = Modifier.weight(1.3f).height(42.dp)
                            ) {
                                Text("В Telegram", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }

                            OutlinedButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Telegram SOCKS5", socks5Url))
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    Toast.makeText(context, "SOCKS5 ссылка скопирована!", Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                                modifier = Modifier.weight(1f).height(42.dp)
                            ) {
                                Text("Копировать", color = TextWhite, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // Bottom Action Button
            Button(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onDismiss()
                },
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(alpha = 0.20f),
                    contentColor = TextWhite
                ),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.45f)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 32.dp)
                    .fillMaxWidth(0.90f)
                    .height(48.dp)
            ) {
                Text(
                    text = "Закрыть",
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite
                )
            }
        }
    }
}
