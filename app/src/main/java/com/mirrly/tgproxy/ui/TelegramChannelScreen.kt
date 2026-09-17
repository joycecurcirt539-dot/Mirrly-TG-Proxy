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
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.ui.theme.*
import kotlin.math.cos
import kotlin.math.sin

private const val TELEGRAM_CHANNEL_URL = "https://t.me/WhyOKyHb"
private const val TELEGRAM_CHANNEL_HANDLE = "@WhyOKyHb"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelegramChannelScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scrollState = rememberScrollState()

    var pendingRedirectUrl by remember { mutableStateOf<String?>(null) }

    if (pendingRedirectUrl != null) {
        val targetUrl = pendingRedirectUrl ?: ""
        ExternalLinkConfirmDialog(
            url = targetUrl,
            onDismiss = { pendingRedirectUrl = null }
        )
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .adaptiveContainerWidth(600.dp)
                .fillMaxHeight()
                .fadingEdges(topFadeHeight = 24.dp, bottomFadeHeight = 44.dp)
                .verticalScroll(scrollState)
                .padding(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 52.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp
                )
                .adaptiveContentPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ── 1. HERO CHANNEL CARD (Shimmer lightSweep & Cyber Ring) ──
            TelegramHeroCard(
                onOpenChannel = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    pendingRedirectUrl = TELEGRAM_CHANNEL_URL
                },
                onCopyHandle = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Telegram Channel", TELEGRAM_CHANNEL_URL))
                    Toast.makeText(context, context.getString(R.string.tg_toast_link_copied), Toast.LENGTH_SHORT).show()
                }
            )

            // ── 2. MONTHLY GIVEAWAYS CARD (Stars 500 & Premium 3 Mo) ──
            TelegramGiveawaysCard(
                onPrizeTap = {
                    HapticHelper.performSoftTick(context)
                }
            )

            // ── 3. WHAT'S INSIDE THE CHANNEL (Bento Features) ──
            TelegramFeaturesSection()

            // ── 4. ACTION BUTTONS DOCK ──
            TelegramActionDock(
                onOpenChannel = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    pendingRedirectUrl = TELEGRAM_CHANNEL_URL
                },
                onCopyLink = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Telegram Channel", TELEGRAM_CHANNEL_URL))
                    Toast.makeText(context, context.getString(R.string.tg_toast_link_copied), Toast.LENGTH_SHORT).show()
                }
            )

            Spacer(modifier = Modifier.height(10.dp))
        }

        // Frosted Top App Bar Header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.98f),
                            Color.Black.copy(alpha = 0.94f),
                            Color.Black.copy(alpha = 0.72f),
                            Color.Black.copy(alpha = 0.00f)
                        )
                    )
                )
        ) {
            TopAppBar(
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

        // Ambient Nanoparticles Layer
        CyberParticlesOverlay(
            modifier = Modifier.fillMaxSize(),
            particleCount = 28,
            alphaMultiplier = 0.50f
        )
    }
}

@Composable
private fun TelegramHeroCard(
    onOpenChannel: () -> Unit,
    onCopyHandle: () -> Unit
) {
    val cyanAccent = Color(0xFF26A5E4)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .staggeredEntrance(index = 0)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Transparent)
            .border(1.dp, cyanAccent.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
            .lightSweep(
                isEnabled = true,
                shape = RoundedCornerShape(18.dp),
                sweepColor = cyanAccent
            )
            .padding(14.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Quantum Energy Ring with Telegram Logo
            TelegramEmblemWithOrb(sizeDp = 56.dp)

            // Header Titles
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.tg_channel_card_title),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Black,
                    color = TextWhite,
                    textAlign = TextAlign.Center
                )

                // Official Developer Channel Status Badge
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, ActiveGreenLed.copy(alpha = 0.40f))
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = ActiveGreenLed,
                            modifier = Modifier.size(5.dp)
                        ) {}
                        Text(
                            text = stringResource(R.string.tg_channel_badge_official),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = ActiveGreenLed,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }

            // Description Text
            Text(
                text = stringResource(R.string.tg_channel_hero_desc),
                fontSize = 11.5.sp,
                lineHeight = 16.5.sp,
                color = TextMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            // Clickable Handle Tag
            Surface(
                onClick = onCopyHandle,
                shape = RoundedCornerShape(12.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, cyanAccent.copy(alpha = 0.40f)),
                modifier = Modifier.springPress(onClick = onCopyHandle)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_telegram),
                        contentDescription = null,
                        tint = cyanAccent,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = TELEGRAM_CHANNEL_HANDLE,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = cyanAccent
                    )
                }
            }
        }
    }
}

@Composable
private fun TelegramEmblemWithOrb(sizeDp: androidx.compose.ui.unit.Dp) {
    val infiniteTransition = rememberInfiniteTransition(label = "emblemRingTransition")
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(9000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "emblemRingAngle"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(sizeDp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = (size.minDimension / 2f) - 2.5.dp.toPx()

            // Outer Orbit Arc
            val radAngle = Math.toRadians(rotationAngle.toDouble()).toFloat()
            val startDeg = (radAngle * 180f / Math.PI.toFloat()) % 360f

            drawArc(
                brush = Brush.sweepGradient(
                    colors = listOf(
                        Color(0xFF0088CC).copy(alpha = 0.1f),
                        Color(0xFF26A5E4).copy(alpha = 0.8f),
                        Color(0xFF00F5D4).copy(alpha = 0.9f),
                        Color(0xFF0088CC).copy(alpha = 0.1f)
                    ),
                    center = center
                ),
                startAngle = startDeg,
                sweepAngle = 260f,
                useCenter = false,
                style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
            )

            // Orbiting particle dot
            val dotX = center.x + radius * cos(radAngle)
            val dotY = center.y + radius * sin(radAngle)
            drawCircle(
                color = Color(0xFF00F5D4),
                radius = 2.5.dp.toPx(),
                center = Offset(dotX, dotY)
            )
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(sizeDp - 12.dp)
                .clip(CircleShape)
                .background(Color.Transparent)
                .border(1.2.dp, Color(0xFF26A5E4).copy(alpha = 0.50f), CircleShape)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_telegram),
                contentDescription = null,
                tint = Color(0xFF29B6F6),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun TelegramGiveawaysCard(
    onPrizeTap: () -> Unit
) {
    val cardAccent = Color(0xFF26A5E4)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .staggeredEntrance(index = 1)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Transparent)
            .border(1.dp, cardAccent.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .lightSweep(
                isEnabled = true,
                shape = RoundedCornerShape(16.dp),
                sweepColor = cardAccent
            )
            .padding(11.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            // Header: Integrated icon, eyebrow title + frequency, and main title
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(cardAccent.copy(alpha = 0.12f))
                        .border(1.dp, cardAccent.copy(alpha = 0.40f), CircleShape)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_crown),
                        contentDescription = null,
                        tint = cardAccent,
                        modifier = Modifier.size(13.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.tg_giveaway_section_title),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.8.sp,
                            color = cardAccent
                        )
                        Text(
                            text = "•",
                            fontSize = 9.sp,
                            color = TextMuted.copy(alpha = 0.5f)
                        )
                        Text(
                            text = stringResource(R.string.tg_giveaway_frequency),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF00F5D4)
                        )
                    }
                    Text(
                        text = stringResource(R.string.tg_giveaway_card_title),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite
                    )
                }
            }

            // Two Prize Pods in Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Prize Pod 1: 500 Stars (MTProto Cyan accent)
                PrizePod(
                    modifier = Modifier.weight(1f),
                    iconRes = R.drawable.ic_crown,
                    accentColor = Color(0xFF00F5D4),
                    prizeValue = stringResource(R.string.tg_giveaway_prize_stars_val),
                    prizeSub = stringResource(R.string.tg_giveaway_prize_stars_sub),
                    onTap = onPrizeTap
                )

                // Prize Pod 2: Telegram Premium (Telegram Blue accent)
                PrizePod(
                    modifier = Modifier.weight(1f),
                    iconRes = R.drawable.ic_hall_of_fame,
                    accentColor = Color(0xFF26A5E4),
                    prizeValue = stringResource(R.string.tg_giveaway_prize_premium_val),
                    prizeSub = stringResource(R.string.tg_giveaway_prize_premium_sub),
                    onTap = onPrizeTap
                )
            }

            // Description / Rules footnote
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_info_circle),
                    contentDescription = null,
                    tint = TextMuted.copy(alpha = 0.65f),
                    modifier = Modifier.size(12.dp)
                )
                Text(
                    text = stringResource(R.string.tg_giveaway_rule_desc),
                    fontSize = 10.sp,
                    lineHeight = 13.5.sp,
                    color = TextMuted.copy(alpha = 0.85f)
                )
            }
        }
    }
}

@Composable
private fun PrizePod(
    modifier: Modifier = Modifier,
    iconRes: Int,
    accentColor: Color,
    prizeValue: String,
    prizeSub: String,
    onTap: () -> Unit
) {
    Surface(
        onClick = onTap,
        modifier = modifier.springPress(onClick = onTap),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF0E1320).copy(alpha = 0.60f),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.12f))
                    .border(1.dp, accentColor.copy(alpha = 0.35f), CircleShape)
            ) {
                Icon(
                    painter = painterResource(id = iconRes),
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(12.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                Text(
                    text = prizeValue,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = prizeSub,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    color = accentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun TelegramFeaturesSection() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .staggeredEntrance(index = 2),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_telegram),
                contentDescription = null,
                tint = Color(0xFF26A5E4),
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = stringResource(R.string.tg_features_section_title),
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.1.sp,
                color = Color(0xFF26A5E4)
            )
        }

        // Grouped Bento Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Transparent)
                .border(1.dp, Color(0xFF181E2E), RoundedCornerShape(16.dp))
        ) {
            // Feature 1: Early Access
            BentoFeatureRow(
                iconRes = R.drawable.ic_refresh,
                iconTint = ActiveGreenLed,
                title = stringResource(R.string.tg_feature_early_access_title),
                desc = stringResource(R.string.tg_feature_early_access_desc)
            )

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF141824)))

            // Feature 2: Behind the scenes
            BentoFeatureRow(
                iconRes = R.drawable.ic_diag_protocol,
                iconTint = Color(0xFF00F5D4),
                title = stringResource(R.string.tg_feature_insider_title),
                desc = stringResource(R.string.tg_feature_insider_desc)
            )

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF141824)))

            // Feature 3: Feature Voting
            BentoFeatureRow(
                iconRes = R.drawable.ic_volunteer_badge,
                iconTint = Color(0xFF29B6F6),
                title = stringResource(R.string.tg_feature_voting_title),
                desc = stringResource(R.string.tg_feature_voting_desc)
            )

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF141824)))

            // Feature 4: Direct Contact
            BentoFeatureRow(
                iconRes = R.drawable.ic_user,
                iconTint = Color(0xFF26A5E4),
                title = stringResource(R.string.tg_feature_direct_contact_title),
                desc = stringResource(R.string.tg_feature_direct_contact_desc)
            )
        }
    }
}

@Composable
private fun BentoFeatureRow(
    iconRes: Int,
    iconTint: Color,
    title: String,
    desc: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(Color.Transparent)
                .border(1.dp, iconTint.copy(alpha = 0.40f), CircleShape)
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(13.dp)
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
                color = TextWhite
            )
            Text(
                text = desc,
                fontSize = 10.5.sp,
                lineHeight = 14.5.sp,
                color = TextMuted
            )
        }
    }
}

@Composable
private fun TelegramActionDock(
    onOpenChannel: () -> Unit,
    onCopyLink: () -> Unit
) {
    val cyanAccent = Color(0xFF26A5E4)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .staggeredEntrance(index = 3),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Main Primary Button: Open Channel
        Surface(
            onClick = onOpenChannel,
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .springPress(onClick = onOpenChannel),
            shape = RoundedCornerShape(12.dp),
            color = Color.Transparent,
            border = BorderStroke(1.dp, cyanAccent.copy(alpha = 0.70f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_telegram),
                    contentDescription = null,
                    tint = cyanAccent,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(7.dp))
                Text(
                    text = stringResource(R.string.tg_action_open_channel),
                    color = TextWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.width(5.dp))
                Icon(
                    painter = painterResource(id = R.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = cyanAccent,
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        // Secondary Button: Copy Link
        Surface(
            onClick = onCopyLink,
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .springPress(onClick = onCopyLink),
            shape = RoundedCornerShape(12.dp),
            color = Color.Transparent,
            border = BorderStroke(1.dp, AmoledBorder)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_copy),
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(7.dp))
                Text(
                    text = stringResource(R.string.tg_action_copy_link),
                    color = TextMuted,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp
                )
            }
        }
    }
}
