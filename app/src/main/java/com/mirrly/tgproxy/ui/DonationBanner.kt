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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun DonationBanner(
    onSupportClicked: () -> Unit,
    onPostponeClicked: () -> Unit,
    onDismissForeverClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val donationUrl = "https://dalink.to/cartneyzix"
    var showConfirmDialog by remember { mutableStateOf(false) }
    var isPermanentDismissVisible by remember { mutableStateOf(false) }

    // Delayed activation of "Больше не показывать" option (5-second delay)
    LaunchedEffect(Unit) {
        delay(5000L)
        isPermanentDismissVisible = true
    }

    if (showConfirmDialog) {
        ExternalLinkConfirmDialog(
            url = donationUrl,
            title = "Поддержать разработку Mirrly",
            description = "Ссылка ведет на сервис DaLink (DonationAlerts) автора R1Xern. Добровольное пожертвование помогает покрывать расходы на хостинг и разработку обновлений.",
            onDismiss = { showConfirmDialog = false },
            onConfirmed = {
                showConfirmDialog = false
                onSupportClicked()
            }
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xF0121826),
                        Color(0xF00B101D)
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        ActiveGreenLed.copy(alpha = 0.5f),
                        Color(0xFFF57D07).copy(alpha = 0.35f),
                        ActiveGreenLed.copy(alpha = 0.4f)
                    )
                ),
                shape = RoundedCornerShape(22.dp)
            )
            .lightSweep(
                isEnabled = true,
                shape = RoundedCornerShape(22.dp),
                borderWidth = 1.dp,
                sweepColor = ActiveGreenLed
            )
            .padding(16.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header with Avatar and Author Info
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1E2638))
                        .border(1.5.dp, ActiveGreenLed.copy(alpha = 0.5f), CircleShape)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.avatar_developer),
                        contentDescription = "R1Xern Avatar",
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Поддержка разработчика",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite
                        )
                        Icon(
                            painter = painterResource(id = R.drawable.ic_dalink),
                            contentDescription = "DaLink",
                            tint = Color.Unspecified,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Если Mirrly обеспечивает стабильный доступ к Telegram, вы можете поддержать автора R1Xern добровольным донатом.",
                        fontSize = 12.sp,
                        color = TextMuted,
                        lineHeight = 16.sp
                    )
                }
            }

            // Action Buttons with strictly NO background fill (transparent container)
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // Support button (Outlined with ActiveGreenLed, transparent background)
                OutlinedButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showConfirmDialog = true
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.Transparent,
                        contentColor = ActiveGreenLed
                    ),
                    border = BorderStroke(1.dp, ActiveGreenLed.copy(alpha = 0.8f)),
                    modifier = Modifier
                        .weight(1.3f)
                        .height(42.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_donate),
                            contentDescription = null,
                            tint = ActiveGreenLed,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Поддержать",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = ActiveGreenLed
                        )
                    }
                }

                // Postpone button (Outlined, transparent background, strictly text "Позже")
                OutlinedButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onPostponeClicked()
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.Transparent,
                        contentColor = TextWhite
                    ),
                    border = BorderStroke(1.dp, Color(0xFF2A3144)),
                    modifier = Modifier
                        .weight(0.9f)
                        .height(42.dp)
                ) {
                    Text(
                        text = "Позже",
                        color = TextMuted,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp
                    )
                }
            }

            // Dismiss Forever button (Appears only after 5 seconds, transparent container)
            AnimatedVisibility(
                visible = isPermanentDismissVisible,
                enter = fadeIn(tween(400)) + expandVertically(tween(400)),
                exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
            ) {
                OutlinedButton(
                    onClick = {
                        if (isPermanentDismissVisible) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDismissForeverClicked()
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.Transparent,
                        contentColor = Color(0xFF8E9BAE)
                    ),
                    border = BorderStroke(1.dp, Color(0x228E9BAE)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                ) {
                    Text(
                        text = "Больше не показывать",
                        color = Color(0xFF7A8699),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal
                    )
                }
            }
        }
    }
}
