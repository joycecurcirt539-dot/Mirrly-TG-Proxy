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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.ui.theme.*

/**
 * Bottom sheet for connecting Telegram to local MTProto / SOCKS5 proxy relay.
 * Strictly adheres to MirrlyUI: Zero-Popup, Zero-Background, Outline-Only, springPress.
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    val isMtActive = app.config.proxyMode == com.mirrly.tgproxy.core.ProxyMode.MTPROTO
    val isSocks5Active = app.config.proxyMode == com.mirrly.tgproxy.core.ProxyMode.SOCKS5

    MirrlyModalBottomSheet(
        onDismissRequest = onDismiss,
        maxTabletWidth = 460.dp
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .fadingEdges(topFadeHeight = 16.dp, bottomFadeHeight = 24.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp, top = 4.dp)
        ) {
            // Title
            Text(
                text = stringResource(R.string.tg_connect_dialog_title),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = TextWhite,
                textAlign = TextAlign.Center,
                letterSpacing = 0.3.sp
            )

            // Subtitle
            Text(
                text = stringResource(R.string.tg_connect_dialog_subtitle),
                fontSize = 12.5.sp,
                color = TextMuted,
                textAlign = TextAlign.Center,
                lineHeight = 17.sp,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            // ─── CARD 1: MTProto Proxy (Recommended for chats) ───
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Transparent)
                    .border(
                        1.dp,
                        if (isMtActive) MtprotoAccent.copy(alpha = 0.65f) else AmoledBorder,
                        RoundedCornerShape(16.dp)
                    )
                    .padding(14.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(MtprotoAccent)
                        )
                        Text(
                            text = stringResource(R.string.tg_connect_proto_mtproto),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.5.sp,
                            color = TextWhite
                        )
                    }

                    Text(
                        text = stringResource(R.string.tg_connect_mtproto_desc),
                        fontSize = 12.sp,
                        color = TextMuted,
                        lineHeight = 16.5.sp
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Open in Telegram
                        Box(
                            modifier = Modifier
                                .weight(1.3f)
                                .height(42.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.dp, MtprotoAccent, RoundedCornerShape(12.dp))
                                .springPress(onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    if (app.config.proxyMode != com.mirrly.tgproxy.core.ProxyMode.MTPROTO) {
                                        app.config.proxyModeName = com.mirrly.tgproxy.core.ProxyMode.MTPROTO.name
                                        app.prefsManager.saveConfig(app.config)
                                        com.mirrly.tgproxy.service.ProxyForegroundService.restartIfRunning(context)
                                    }
                                    onDismiss()
                                    applyToTelegramPackages(context, mtprotoUrl)
                                }),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.tg_connect_btn_open_tg),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = MtprotoAccent
                            )
                        }

                        // Copy link
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.dp, AmoledBorder, RoundedCornerShape(12.dp))
                                .springPress(onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Telegram MTProto", mtprotoUrl))
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    Toast.makeText(context, context.getString(R.string.tg_connect_toast_mtproto_copied), Toast.LENGTH_SHORT).show()
                                }),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.action_copy),
                                color = TextWhite,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // ─── CARD 2: SOCKS5 Proxy (For calls & general routing) ───
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Transparent)
                    .border(
                        1.dp,
                        if (isSocks5Active) Socks5Accent.copy(alpha = 0.65f) else AmoledBorder,
                        RoundedCornerShape(16.dp)
                    )
                    .padding(14.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Socks5Accent)
                        )
                        Text(
                            text = stringResource(R.string.tg_connect_proto_socks5),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.5.sp,
                            color = TextWhite
                        )
                    }

                    Text(
                        text = stringResource(R.string.tg_connect_socks5_desc),
                        fontSize = 12.sp,
                        color = TextMuted,
                        lineHeight = 16.5.sp
                    )

                    if (app.config.hasSocks5Auth) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.Transparent)
                                .border(1.dp, AmoledBorder, RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 7.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.tg_connect_socks5_login_prefix, app.config.socks5Username.ifEmpty { "—" }),
                                    fontSize = 11.5.sp,
                                    color = TextWhite,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = stringResource(R.string.tg_connect_socks5_pass_prefix, if (app.config.socks5Password.isNotEmpty()) "••••••••" else "—"),
                                    fontSize = 11.5.sp,
                                    color = TextWhite,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Open in Telegram
                        Box(
                            modifier = Modifier
                                .weight(1.3f)
                                .height(42.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.dp, Socks5Accent, RoundedCornerShape(12.dp))
                                .springPress(onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    if (!app.config.isSocks5Mode) {
                                        app.config.proxyModeName = com.mirrly.tgproxy.core.ProxyMode.SOCKS5.name
                                        app.prefsManager.saveConfig(app.config)
                                        com.mirrly.tgproxy.service.ProxyForegroundService.restartIfRunning(context)
                                    }
                                    onDismiss()
                                    applyToTelegramPackages(context, server.getTelegramSocks5Url())
                                }),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.tg_connect_btn_open_tg),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = Socks5Accent
                            )
                        }

                        // Copy link
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.dp, AmoledBorder, RoundedCornerShape(12.dp))
                                .springPress(onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Telegram SOCKS5", socks5Url))
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    Toast.makeText(context, context.getString(R.string.tg_connect_toast_socks5_copied), Toast.LENGTH_SHORT).show()
                                }),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.action_copy),
                                color = TextWhite,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // ─── CARD 3: Footnote / Note ───
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Transparent)
                    .border(1.dp, AmoledBorder, RoundedCornerShape(14.dp))
                    .padding(12.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.tg_connect_footnote_title),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite
                    )
                    Text(
                        text = stringResource(R.string.tg_connect_footnote_desc),
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                        color = TextMuted
                    )
                }
            }

            // Bottom Dismiss Button (Outline-Only springPress)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, AmoledBorder, RoundedCornerShape(14.dp))
                    .springPress(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDismiss()
                    }),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.action_close),
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextWhite
                )
            }
        }
    }
}
