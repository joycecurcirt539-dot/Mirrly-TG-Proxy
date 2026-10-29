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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.service.PreferencesManager
import com.mirrly.tgproxy.service.cloudflare.CloudflareApiClient
import com.mirrly.tgproxy.ui.theme.*
import kotlinx.coroutines.launch

private val CfOrange = Color(0xFFF38020)

/**
 * MirrlyUI Modal Bottom Sheet for Cloudflare Account & Session Management.
 * Replaces the legacy modal Dialog with a Zero-Popup MirrlyModalBottomSheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudflareAccountManageSheet(
    prefs: PreferencesManager,
    accountName: String,
    accountId: String,
    subdomain: String,
    isSessionExpired: Boolean,
    onDismiss: () -> Unit,
    onSetupSubdomain: () -> Unit,
    onSwitchAccount: () -> Unit,
    onLogout: () -> Unit,
    onSessionRefreshed: (newToken: String) -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var isRefreshing by remember { mutableStateOf(false) }
    var currentExpired by remember(isSessionExpired) { mutableStateOf(isSessionExpired) }

    fun copyAccountId() {
        if (accountId.isNotBlank()) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("Cloudflare Account ID", accountId))
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            Toast.makeText(context, context.getString(R.string.cf_account_dialog_id_copied), Toast.LENGTH_SHORT).show()
        }
    }

    fun refreshSession() {
        isRefreshing = true
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch {
            val freshToken = CloudflareApiClient.tryRefreshToken(prefs)
            isRefreshing = false
            if (freshToken != null) {
                currentExpired = false
                Toast.makeText(context, "Сессия Cloudflare успешно обновлена", Toast.LENGTH_SHORT).show()
                onSessionRefreshed(freshToken)
            } else {
                val currentToken = prefs.getCloudflareToken().orEmpty()
                val verifyRes = CloudflareApiClient.verifyToken(currentToken)
                if (verifyRes.isSuccess) {
                    currentExpired = false
                    Toast.makeText(context, "Токен проверен и активен", Toast.LENGTH_SHORT).show()
                } else {
                    currentExpired = true
                    Toast.makeText(context, "Срок действия токена истек. Требуется повторный вход.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    MirrlyModalBottomSheet(
        onDismissRequest = onDismiss,
        maxTabletWidth = 480.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fadingEdges(topFadeHeight = 16.dp, bottomFadeHeight = 24.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            MirrlyBottomSheetHeader(
                title = stringResource(R.string.cf_account_dialog_title),
                subtitle = stringResource(R.string.cf_account_dialog_subtitle),
                onClose = onDismiss
            )

            // Account Details Card (Transparent Surface with AmoledBorder)
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, AmoledBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Profile / Email Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.cf_account_dialog_label_account),
                            color = TextMuted,
                            fontSize = 12.sp
                        )
                        Text(
                            text = accountName.ifBlank { "Cloudflare User" },
                            color = TextWhite,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    HorizontalDivider(color = AmoledBorder, thickness = 0.8.dp)

                    // Subdomain Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.cf_account_dialog_label_subdomain),
                            color = TextMuted,
                            fontSize = 12.sp
                        )
                        if (subdomain.isNotBlank()) {
                            Text(
                                text = "$subdomain.workers.dev",
                                color = CfOrange,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium
                            )
                        } else {
                            Text(
                                text = "Настроить",
                                color = CfOrange,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .border(BorderStroke(1.dp, CfOrange.copy(alpha = 0.5f)), RoundedCornerShape(6.dp))
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onSetupSubdomain()
                                    }
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }

                    if (accountId.isNotBlank()) {
                        HorizontalDivider(color = AmoledBorder, thickness = 0.8.dp)

                        // Account ID Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.cf_account_dialog_label_id),
                                color = TextMuted,
                                fontSize = 12.sp
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { copyAccountId() }
                            ) {
                                Text(
                                    text = accountId.take(12) + "...",
                                    color = TextWhite.copy(alpha = 0.9f),
                                    fontSize = 11.5.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_copy),
                                    contentDescription = null,
                                    tint = CfOrange,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = AmoledBorder, thickness = 0.8.dp)

                    // Quota Info Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.cf_account_dialog_label_quota),
                            color = TextMuted,
                            fontSize = 12.sp
                        )
                        Text(
                            text = stringResource(R.string.cf_account_dialog_quota_val),
                            color = TextWhite,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    HorizontalDivider(color = AmoledBorder, thickness = 0.8.dp)

                    // Session Status Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Статус сессии",
                            color = TextMuted,
                            fontSize = 12.sp
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(if (currentExpired) Color(0xFFEF4444) else Color(0xFF00E676))
                            )
                            Text(
                                text = if (currentExpired) "Истекла" else "Активна",
                                color = if (currentExpired) Color(0xFFEF4444) else Color(0xFF00E676),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // Action Buttons
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 1. Refresh Session Button (If expired or user requests verification)
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, if (currentExpired) CfOrange.copy(alpha = 0.70f) else AmoledBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .springPress(onClick = { refreshSession() })
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (isRefreshing) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(
                                    color = CfOrange,
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Text(
                                    text = "Проверка сессии...",
                                    fontSize = 12.5.sp,
                                    color = TextWhite
                                )
                            }
                        } else {
                            Text(
                                text = "Обновить статус сессии",
                                color = if (currentExpired) CfOrange else TextWhite,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // 2. Switch Account: Transparent with CfOrange border
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = CfOrange.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, CfOrange.copy(alpha = 0.60f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDismiss()
                            onSwitchAccount()
                        })
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.cf_account_dialog_btn_switch),
                            color = CfOrange,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // 3. Log Out: Transparent surface with red accent border
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.40f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDismiss()
                            onLogout()
                        })
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.cf_account_dialog_btn_logout),
                            color = Color(0xFFEF4444),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // 4. Dismiss button
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                ) {
                    Text(
                        text = stringResource(R.string.cf_account_dialog_btn_close),
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}
