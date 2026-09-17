/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.mirrly.tgproxy.ui
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.ui.theme.ActiveGreenLed
import com.mirrly.tgproxy.ui.theme.TextMuted
import com.mirrly.tgproxy.ui.theme.TextWhite
import com.mirrly.tgproxy.ui.theme.adaptiveContainerWidth

/**
 * Modal dialog window «In development» for test speed network.
 * Strict factual style without emoji, in unified visual design with DialogBackdropBox.
 */
@Composable
fun SpeedTestInDevDialog(
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        DialogBackdropBox(onDismiss = onDismiss) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .adaptiveContainerWidth(380.dp)
                    .padding(horizontal = 24.dp)
                    .clickable(remember { MutableInteractionSource() }, null) {}
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFF0C0E14),
                    border = BorderStroke(1.dp, ActiveGreenLed.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(ActiveGreenLed.copy(alpha = 0.12f))
                                .border(1.dp, ActiveGreenLed.copy(alpha = 0.35f), CircleShape)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_speed_turbo),
                                contentDescription = null,
                                tint = ActiveGreenLed,
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = stringResource(R.string.speed_test_dialog_title),
                            color = TextWhite,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = stringResource(R.string.speed_test_dialog_badge),
                            color = ActiveGreenLed,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.8.sp
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = stringResource(R.string.speed_test_dialog_desc),
                            color = TextMuted,
                            fontSize = 12.5.sp,
                            lineHeight = 17.sp,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(ActiveGreenLed.copy(alpha = 0.15f))
                                .border(1.dp, ActiveGreenLed.copy(alpha = 0.40f), RoundedCornerShape(12.dp))
                                .clickable { onDismiss() }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.action_understood),
                                color = ActiveGreenLed,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
