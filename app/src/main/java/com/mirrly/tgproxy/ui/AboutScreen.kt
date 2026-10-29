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
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.ui.theme.*
import com.mirrly.tgproxy.util.shareApp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onOpenLicense: () -> Unit = {},
    onOpenTerms: () -> Unit = {},
    onOpenUpdate: () -> Unit = {},
    onOpenHallOfFame: () -> Unit = {}
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val ledGreen = ActiveGreenLed

    // Pulsing gradient glow for hero section
    val infiniteTransition = rememberInfiniteTransition(label = "pulseGlow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )

    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val maxScrollRange = remember(density) { with(density) { 180.dp.toPx() } }

    // Shared element entrance spring scale
    var isEntered by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        isEntered = true
    }

    val avatarEntranceScale by animateFloatAsState(
        targetValue = if (isEntered) 1.00f else 0.40f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "avatarEntranceScale"
    )

    val avatarEntranceAlpha by animateFloatAsState(
        targetValue = if (isEntered) 1.00f else 0.00f,
        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
        label = "avatarEntranceAlpha"
    )

    var pendingRedirectUrl by remember { mutableStateOf<String?>(null) }
    var showDonateDialog by remember { mutableStateOf(false) }

    val devAvatarBounce = remember { Animatable(1f) }

    if (pendingRedirectUrl != null) {
        val targetUrl = pendingRedirectUrl ?: ""
        ExternalLinkConfirmDialog(
            url = targetUrl,
            onDismiss = { pendingRedirectUrl = null }
        )
    }

    if (showDonateDialog) {
        DonationDialog(
            onDismiss = { showDonateDialog = false }
        )
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        // 1. SCROLLABLE CONTENT LAYER
        Column(
            modifier = Modifier
                .adaptiveContainerWidth(600.dp)
                .fillMaxHeight()
                .fadingEdges(topFadeHeight = 24.dp, bottomFadeHeight = 44.dp)
                .verticalScroll(scrollState)
                .padding(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp
                )
                .adaptiveContentPadding(),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            // 1. HERO DEVELOPER CARD WITH PARALLAX & SHARED ELEMENT AVATAR
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .staggeredEntrance(index = 0)
                    .graphicsLayer {
                        val rawScroll = scrollState.value.toFloat()
                        val scrollFraction = (rawScroll / maxScrollRange).coerceIn(0f, 1f)
                        val headerScale = 1.0f - (scrollFraction * 0.12f)
                        val headerParallaxY = rawScroll * 0.32f
                        val headerAlpha = (1.0f - (scrollFraction * 0.40f)).coerceIn(0.2f, 1.0f)

                        scaleX = headerScale * avatarEntranceScale
                        scaleY = headerScale * avatarEntranceScale
                        translationY = headerParallaxY
                        alpha = headerAlpha * avatarEntranceAlpha
                    }
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color.Transparent)
                    .border(1.dp, AmoledBorder, RoundedCornerShape(22.dp))
                    .lightSweep(isEnabled = true, shape = RoundedCornerShape(22.dp))
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Glowing Avatar Icon Box
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(92.dp)
                            .graphicsLayer {
                                scaleX = avatarEntranceScale * devAvatarBounce.value
                                scaleY = avatarEntranceScale * devAvatarBounce.value
                            }
                            .clip(CircleShape)
                            .background(Color.Transparent)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                coroutineScope.launch {
                                    devAvatarBounce.animateTo(0.85f, tween(70))
                                    devAvatarBounce.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                                }
                            }
                    ) {
                        // Quantum Ring around Avatar
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val w = size.width
                            val h = size.height
                            val c = Offset(w / 2f, h / 2f)
                            val r = (w.coerceAtMost(h) / 2f) - 3.dp.toPx()

                            drawCircle(
                                color = ledGreen.copy(alpha = glowAlpha * 0.70f),
                                radius = r,
                                center = c,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                            )
                        }

                        Image(
                            painter = painterResource(id = R.drawable.avatar_developer),
                            contentDescription = "R1Xern Avatar",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(78.dp)
                                .clip(CircleShape)
                        )
                    }

                    // Developer Name & Handles
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "R1Xern",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.5.sp,
                            color = TextWhite
                        )

                        // Clickable GitHub handle pill
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.Transparent)
                                .border(1.dp, ActiveGreenLed.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                                .springPress(onClick = {
                                    pendingRedirectUrl = "https://github.com/joycecurcirt539-dot"
                                })
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_github),
                                contentDescription = null,
                                tint = ActiveGreenLed,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "@joycecurcirt539-dot",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = ActiveGreenLed
                            )
                        }

                        Text(
                            text = stringResource(R.string.about_creator_subtitle),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextMuted,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // 2. OFFICIAL SOURCE & VERIFICATION CARD
            OfficialSourceCard(
                modifier = Modifier.staggeredEntrance(index = 1)
            )

            // 3. ARCHITECTURE & MISSION CARD
            Column(
                modifier = Modifier.staggeredEntrance(index = 2),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.about_section_bio),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.3.sp,
                    color = TextMuted
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.Transparent)
                        .border(1.dp, AmoledBorder, RoundedCornerShape(18.dp))
                        .padding(18.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = stringResource(R.string.about_greeting),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite
                        )

                        Text(
                            text = stringResource(R.string.about_bio_desc),
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            color = TextWhite.copy(alpha = 0.85f)
                        )

                        // Architectural Highlights
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            SpecPill(text = "SOCKS5 :10808", modifier = Modifier.weight(1f))
                            SpecPill(text = "Rust NDK Engine", modifier = Modifier.weight(1f))
                            SpecPill(text = "Cloudflare WSS", modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            // 4. UPDATED ARCHITECTURAL TECH STACK
            TechStackSection(
                modifier = Modifier.staggeredEntrance(index = 3)
            )

            // 5. OFFICIAL RESOURCES & CONTACTS
            Column(
                modifier = Modifier.staggeredEntrance(index = 4),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.about_section_contacts),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.3.sp,
                    color = TextMuted
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.Transparent)
                        .border(1.dp, AmoledBorder, RoundedCornerShape(18.dp))
                ) {
                    // Telegram Channel
                    LinkCardItem(
                        iconRes = R.drawable.ic_telegram,
                        iconTint = Color(0xFF29B6F6),
                        title = stringResource(R.string.about_link_tg_title),
                        subtitle = stringResource(R.string.about_link_tg_sub),
                        onClick = { pendingRedirectUrl = "https://t.me/WhyOKyHb" }
                    )

                    HorizontalDivider(thickness = 1.dp, color = Color(0xFF141824))

                    // GitHub Profile
                    LinkCardItem(
                        iconRes = R.drawable.ic_github,
                        iconTint = TextWhite,
                        title = stringResource(R.string.about_link_github_title),
                        subtitle = "github.com/joycecurcirt539-dot",
                        onClick = { pendingRedirectUrl = "https://github.com/joycecurcirt539-dot" }
                    )

                    HorizontalDivider(thickness = 1.dp, color = Color(0xFF141824))

                    // GitHub Repository
                    LinkCardItem(
                        iconRes = R.drawable.ic_github,
                        iconTint = ActiveGreenLed,
                        title = stringResource(R.string.about_link_repo_title),
                        subtitle = stringResource(R.string.about_link_repo_sub),
                        onClick = { pendingRedirectUrl = "https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy" }
                    )

                    HorizontalDivider(thickness = 1.dp, color = Color(0xFF141824))

                    // Share Application
                    LinkCardItem(
                        iconRes = R.drawable.ic_send,
                        iconTint = ActiveGreenLed,
                        title = stringResource(R.string.about_link_share_title),
                        subtitle = stringResource(R.string.about_link_share_sub),
                        onClick = { context.shareApp() }
                    )

                    HorizontalDivider(thickness = 1.dp, color = Color(0xFF141824))

                    // Report Issue / Bug Tracker
                    LinkCardItem(
                        iconRes = R.drawable.ic_bug,
                        iconTint = Color(0xFFFF9E00),
                        title = stringResource(R.string.about_link_issue_title),
                        subtitle = stringResource(R.string.about_link_issue_sub),
                        onClick = { pendingRedirectUrl = "https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/issues/new" }
                    )

                    HorizontalDivider(thickness = 1.dp, color = Color(0xFF141824))

                    // GNU GPLv3 License
                    LinkCardItem(
                        iconRes = R.drawable.ic_license,
                        iconTint = ActiveGreenLed,
                        title = stringResource(R.string.about_link_license_title),
                        subtitle = stringResource(R.string.about_link_license_sub),
                        onClick = { onOpenLicense() }
                    )
                }
            }

            // 6. HALL OF FAME CARD
            Column(
                modifier = Modifier.staggeredEntrance(index = 5),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.about_section_fame),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.3.sp,
                    color = TextMuted
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.Transparent)
                        .border(1.dp, AmoledBorder, RoundedCornerShape(18.dp))
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onOpenHallOfFame()
                        })
                        .padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.Transparent)
                                .border(1.dp, AmoledBorder, RoundedCornerShape(12.dp))
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_hall_of_fame),
                                contentDescription = null,
                                tint = Color(0xFFFFBD22),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.about_fame_card_title),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextWhite
                                )
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = Color.Transparent,
                                    border = BorderStroke(1.dp, AmoledBorder)
                                ) {
                                    Text(
                                        text = stringResource(R.string.badge_top),
                                        fontSize = 8.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFFFBD22),
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.about_fame_card_desc),
                                fontSize = 11.5.sp,
                                color = TextMuted,
                                lineHeight = 16.sp
                            )
                        }

                        Icon(
                            painter = painterResource(id = R.drawable.ic_chevron_right),
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // 7. GITHUB REPOSITORY RATING CARD
            Column(
                modifier = Modifier.staggeredEntrance(index = 6),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.about_section_star),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.3.sp,
                    color = TextMuted
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.Transparent)
                        .border(1.dp, Color(0xFFFFB703).copy(alpha = 0.35f), RoundedCornerShape(18.dp))
                        .padding(18.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.Transparent)
                                    .border(1.dp, Color(0xFFFFB703).copy(alpha = 0.40f), RoundedCornerShape(10.dp))
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_star),
                                    contentDescription = null,
                                    tint = Color(0xFFFFB703),
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.about_star_title),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextWhite
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = stringResource(R.string.about_star_desc),
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                    color = TextMuted
                                )
                            }
                        }

                        Button(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                pendingRedirectUrl = "https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy"
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Transparent,
                                contentColor = Color(0xFFFFB703)
                            ),
                            border = BorderStroke(1.dp, Color(0xFFFFB703)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .springPress()
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_star),
                                contentDescription = null,
                                tint = Color(0xFFFFB703),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.about_star_btn),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.5.sp,
                                color = Color(0xFFFFB703)
                            )
                        }
                    }
                }
            }

            // 8. PROJECT SUPPORT CARD
            Column(
                modifier = Modifier.staggeredEntrance(index = 7),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.about_section_donation),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.3.sp,
                    color = TextMuted
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.Transparent)
                        .border(1.dp, ActiveGreenLed.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
                        .padding(18.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.Transparent)
                                    .border(1.dp, ActiveGreenLed.copy(alpha = 0.40f), RoundedCornerShape(10.dp))
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_donate),
                                    contentDescription = null,
                                    tint = ActiveGreenLed,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.about_donation_title),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextWhite
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = stringResource(R.string.about_donation_desc),
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                    color = TextMuted
                                )
                            }
                        }

                        Button(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                showDonateDialog = true
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Transparent,
                                contentColor = ActiveGreenLed
                            ),
                            border = BorderStroke(1.dp, ActiveGreenLed),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .springPress()
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_donate),
                                contentDescription = null,
                                tint = ActiveGreenLed,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.about_donation_btn),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.5.sp,
                                color = ActiveGreenLed
                            )
                        }
                    }
                }
            }

            // 9. FOOTER COPYRIGHT & TERMS
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = stringResource(R.string.about_footer_developed_by),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextMuted
                )
                Text(
                    text = "© 2026 Mirrly TG Proxy • GNU GPLv3",
                    fontSize = 11.sp,
                    color = TextMuted.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.about_footer_terms),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextMuted.copy(alpha = 0.85f),
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onOpenTerms()
                        })
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // 2. FROSTED GLASS HEADER PANEL (Pinned at Top)
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
                        text = stringResource(R.string.about_title),
                        color = TextWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        maxLines = 1,
                        softWrap = false,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onBack()
                    }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_arrow_left),
                            contentDescription = stringResource(R.string.action_back),
                            tint = TextWhite,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }

        // Cyber Particles floating over entire about screen interface
        CyberParticlesOverlay(
            modifier = Modifier.fillMaxSize(),
            particleCount = 42,
            alphaMultiplier = 0.70f
        )
    }
}

/**
 * Architectural Tech Stack Section showcasing the actual technical layers of Mirrly TG Proxy:
 * - Native Engine: Rust 2021 (Tokio, smoltcp, Quinn), C++20 NDK
 * - Protocols: MTProto FakeTLS, SOCKS5 Relay, Cloudflare WSS, VpnService
 * - Platform & UI: Kotlin 2.0, Jetpack Compose, MirrlyUI
 * - Diagnostics & AI: TLS Fragmentation, Happy Eyeballs RFC 8305, Gemini BYOK
 */
@Composable
private fun TechStackSection(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = stringResource(R.string.about_section_tech),
            fontSize = 11.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.3.sp,
            color = TextMuted
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Transparent)
                .border(1.dp, AmoledBorder, RoundedCornerShape(18.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Tier 1: Native Engine (Rust & C++)
            TechCategoryGroup(
                title = "Ядро и Native",
                accentColor = ActiveGreenLed,
                items = listOf(
                    TechSpecItem("Rust 2021", "Tokio • mirrlyengine"),
                    TechSpecItem("smoltcp & Quinn", "TCP/IP • QUIC NDK"),
                    TechSpecItem("C++20 NDK", "JNI • Native Sec")
                )
            )

            HorizontalDivider(thickness = 1.dp, color = Color(0xFF141824))

            // Tier 2: Protocols & Networking
            TechCategoryGroup(
                title = "Сетевой стек и протоколы",
                accentColor = Color(0xFF00F5D4),
                items = listOf(
                    TechSpecItem("MTProto FakeTLS", "TLS 1.3 • ee-secret"),
                    TechSpecItem("SOCKS5 Relay", "RFC 1928 • :10808"),
                    TechSpecItem("Cloudflare WSS", "cloudflare:sockets"),
                    TechSpecItem("VpnService TUN", "Socket Protection")
                )
            )

            HorizontalDivider(thickness = 1.dp, color = Color(0xFF141824))

            // Tier 3: Platform & Interface
            TechCategoryGroup(
                title = "Платформа и MirrlyUI",
                accentColor = Color(0xFFB388FF),
                items = listOf(
                    TechSpecItem("Kotlin 2.0", "Coroutines Flow"),
                    TechSpecItem("Compose M3", "120 FPS • Zero-Popup"),
                    TechSpecItem("Android 8.0–16", "16 KB Page Aligned")
                )
            )

            HorizontalDivider(thickness = 1.dp, color = Color(0xFF141824))

            // Tier 4: DPI Bypass & AI
            TechCategoryGroup(
                title = "Обход блокировок и интеллект",
                accentColor = Color(0xFFFFBD22),
                items = listOf(
                    TechSpecItem("TLS Fragmenter", "DPI / ТСПУ Evasion"),
                    TechSpecItem("Happy Eyeballs", "RFC 8305 Dual-Stack"),
                    TechSpecItem("Gemini BYOK", "Function Calling Tools")
                )
            )
        }
    }
}

private data class TechSpecItem(
    val name: String,
    val spec: String
)

@Composable
private fun TechCategoryGroup(
    title: String,
    accentColor: Color,
    items: List<TechSpecItem>
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(accentColor)
            )
            Text(
                text = title,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                color = TextWhite.copy(alpha = 0.9f)
            )
        }

        // Grid of badges
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val half = (items.size + 1) / 2
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (i in 0 until half) {
                    TechBadgeCard(items[i])
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (i in half until items.size) {
                    TechBadgeCard(items[i])
                }
            }
        }
    }
}

@Composable
private fun TechBadgeCard(item: TechSpecItem) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Transparent)
            .border(1.dp, Color(0xFF181E2E), RoundedCornerShape(10.dp))
            .springPress()
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = item.name,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                color = TextWhite
            )
            Text(
                text = item.spec,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Normal,
                color = TextMuted
            )
        }
    }
}

@Composable
private fun SpecPill(text: String, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Transparent)
            .border(1.dp, AmoledBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 4.dp, vertical = 5.dp)
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextMuted,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun LinkCardItem(
    iconRes: Int,
    iconTint: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .springPress(onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            })
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Transparent)
                    .border(1.dp, iconTint.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            ) {
                Icon(
                    painter = painterResource(id = iconRes),
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(16.dp)
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = title,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextWhite
                )
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = TextMuted
                )
            }
        }

        Icon(
            painter = painterResource(id = R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = TextMuted.copy(alpha = 0.7f),
            modifier = Modifier.size(15.dp)
        )
    }
}
