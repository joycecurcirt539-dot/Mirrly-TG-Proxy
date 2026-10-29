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

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.mirrly.tgproxy.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.core.ProxyConfig
import com.mirrly.tgproxy.ui.theme.*

/**
 * Bottom sheet for SOCKS5 credential setup when auth is required.
 * Strictly adheres to MirrlyUI (Zero-Popup, Zero-Background, Outline-Only, springPress).
 */
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
            Text(
                text = stringResource(R.string.socks5_auth_dialog_title),
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = TextWhite,
                textAlign = TextAlign.Center,
                letterSpacing = 0.3.sp
            )

            Text(
                text = stringResource(R.string.socks5_auth_dialog_desc),
                fontSize = 12.sp,
                color = TextMuted,
                textAlign = TextAlign.Center,
                lineHeight = 16.5.sp,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.Transparent)
                    .border(1.dp, AmoledBorder, RoundedCornerShape(18.dp))
                    .padding(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Username field
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.socks5_auth_field_username),
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
                            placeholder = { Text(stringResource(R.string.socks5_auth_hint_username), color = TextMuted, fontSize = 13.sp) },
                            shape = RoundedCornerShape(12.dp),
                            supportingText = if (showError && username.trim().isEmpty()) {
                                { Text(stringResource(R.string.socks5_auth_err_username_required), color = Color(0xFFEF4444), fontSize = 11.5.sp) }
                            } else null,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedBorderColor = Socks5Accent,
                                unfocusedBorderColor = AmoledBorder,
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
                            text = stringResource(R.string.socks5_auth_field_password),
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
                            placeholder = { Text(stringResource(R.string.socks5_auth_hint_password), color = TextMuted, fontSize = 13.sp) },
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
                                { Text(stringResource(R.string.socks5_auth_err_password_required), color = Color(0xFFEF4444), fontSize = 11.5.sp) }
                            } else null,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedBorderColor = Socks5Accent,
                                unfocusedBorderColor = AmoledBorder,
                                focusedTextColor = TextWhite,
                                unfocusedTextColor = TextWhite,
                                errorBorderColor = Color(0xFFEF4444),
                                errorTextColor = TextWhite
                            )
                        )
                    }

                    // Generate Random button (MirrlyUI Outline springPress)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, Socks5Accent.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                            .springPress(onClick = {
                                val (u, p) = ProxyConfig.generateRandomSocks5Credentials()
                                username = u
                                password = p
                                showError = false
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.socks5_auth_btn_generate_random),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Socks5Accent
                        )
                    }
                }
            }

            // Action buttons (Confirm / Dismiss)
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, AmoledBorder, RoundedCornerShape(14.dp))
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDismiss()
                        }),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.action_cancel),
                        color = TextWhite,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1.5f)
                        .height(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, Socks5Accent, RoundedCornerShape(14.dp))
                        .springPress(onClick = {
                            val u = username.trim()
                            val p = password.trim()
                            if (u.isEmpty() || p.isEmpty()) {
                                showError = true
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                return@springPress
                            }
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onConfirm(u, p)
                        }),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.socks5_auth_btn_save_and_start),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.5.sp,
                        color = Socks5Accent
                    )
                }
            }
        }
    }
}
