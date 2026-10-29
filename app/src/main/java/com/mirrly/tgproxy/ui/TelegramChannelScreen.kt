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
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.ui.theme.ActiveGreenLed
import com.mirrly.tgproxy.ui.theme.AmoledBorder
import com.mirrly.tgproxy.ui.theme.TextMuted
import com.mirrly.tgproxy.ui.theme.TextWhite
import com.mirrly.tgproxy.ui.theme.adaptiveContainerWidth
import com.mirrly.tgproxy.ui.theme.fourWayFadingEdges
import com.mirrly.tgproxy.ui.theme.screenPerimeterVignette
import com.mirrly.tgproxy.ui.theme.springPress

private const val TELEGRAM_CHANNEL_URL = "https://t.me/WhyOKyHb"
private const val TELEGRAM_CHANNEL_HANDLE = "@WhyOKyHb"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelegramChannelScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val cyanAccent = Color(0xFF26A5E4)

    val onOpenChannel: () -> Unit = {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=WhyOKyHb")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TELEGRAM_CHANNEL_URL)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        }
    }

    val onCopyLink: () -> Unit = {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Telegram Channel", TELEGRAM_CHANNEL_URL))
        Toast.makeText(context, context.getString(R.string.tg_toast_link_copied), Toast.LENGTH_SHORT).show()
    }

    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .screenPerimeterVignette(
                topHeight = topInset + 48.dp,
                bottomHeight = bottomInset + 36.dp,
                sideWidth = 18.dp,
                topAlpha = 0.65f,
                bottomAlpha = 0.45f,
                sideAlpha = 0.28f
            ),
        contentAlignment = Alignment.Center
    ) {
        // Centered Content (adaptiveContainerWidth 440.dp, fits entirely within single screen without scroll)
        Column(
            modifier = Modifier
                .adaptiveContainerWidth(440.dp)
                .fourWayFadingEdges(
                    topFadeHeight = 16.dp,
                    bottomFadeHeight = 20.dp,
                    startFadeWidth = 10.dp,
                    endFadeWidth = 10.dp
                )
                .padding(horizontal = 20.dp)
                .padding(
                    top = topInset + 56.dp,
                    bottom = bottomInset + 16.dp
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ── 1. CENTRAL BUSINESS CARD ──
            TelegramBusinessCard(
                cyanAccent = cyanAccent,
                onCopyHandle = onCopyLink
            )

            // ── 2. ACTION BUTTONS DOCK ──
            TelegramActionDock(
                cyanAccent = cyanAccent,
                onOpenChannel = onOpenChannel,
                onCopyLink = onCopyLink
            )
        }

        // Top App Bar with transparent container, naturally resting on the smooth top status-bar vignette
        TopAppBar(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth(),
            title = {
                Text(
                    text = stringResource(R.string.tg_channel_topbar_title),
                    color = TextWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            },
            navigationIcon = {
                IconButton(onClick = {
                    HapticHelper.performTapClick(context)
                    onBack()
                }) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_arrow_left),
                        contentDescription = stringResource(R.string.action_back),
                        tint = TextWhite,
                        modifier = Modifier.size(20.dp)
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
        )
    }
}

@Composable
private fun TelegramBusinessCard(
    cyanAccent: Color,
    onCopyHandle: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Transparent)
            .border(1.dp, cyanAccent.copy(alpha = 0.40f), RoundedCornerShape(18.dp))
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Static Vector Emblem in Thin Outline Circle (1.2dp, alpha = 0.5f)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.Transparent)
                    .border(1.2.dp, cyanAccent.copy(alpha = 0.50f), CircleShape)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_telegram),
                    contentDescription = null,
                    tint = cyanAccent,
                    modifier = Modifier.size(22.dp)
                )
            }

            // Channel Title
            Text(
                text = stringResource(R.string.tg_channel_card_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TextWhite,
                textAlign = TextAlign.Center
            )

            // Clickable Handle Capsule with Status Indicator Dot
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Transparent)
                    .border(1.dp, cyanAccent.copy(alpha = 0.40f), RoundedCornerShape(12.dp))
                    .springPress(onClick = onCopyHandle)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(ActiveGreenLed)
                    )
                    Text(
                        text = TELEGRAM_CHANNEL_HANDLE,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = cyanAccent
                    )
                }
            }

            // Brief Description Text
            Text(
                text = stringResource(R.string.tg_channel_hero_desc),
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = TextMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            // Info Footnote: Compact outline badge with info icon and giveaway note
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Transparent)
                    .border(1.dp, AmoledBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_info_circle),
                    contentDescription = null,
                    tint = cyanAccent,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = stringResource(R.string.tg_channel_giveaway_note),
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = TextMuted
                )
            }
        }
    }
}

@Composable
private fun TelegramActionDock(
    cyanAccent: Color,
    onOpenChannel: () -> Unit,
    onCopyLink: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Primary Action: Open in Telegram
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Transparent)
                .border(1.dp, cyanAccent, RoundedCornerShape(12.dp))
                .springPress(onClick = onOpenChannel),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_telegram),
                    contentDescription = null,
                    tint = cyanAccent,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.tg_action_open_channel),
                    color = TextWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.5.sp
                )
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    painter = painterResource(id = R.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = cyanAccent,
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        // Secondary Action: Copy Link
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Transparent)
                .border(1.dp, AmoledBorder, RoundedCornerShape(12.dp))
                .springPress(onClick = onCopyLink),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_copy),
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.tg_action_copy_link),
                    color = TextMuted,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.5.sp
                )
            }
        }
    }
}
