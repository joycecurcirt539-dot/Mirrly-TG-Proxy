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
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicenseScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    var selectedLanguage by remember { mutableStateOf("ru") }

    val fullLicenseTextEn = remember {
        context.resources.openRawResource(R.raw.license_gpl_v3_en).bufferedReader().use { it.readText() }
    }

    val fullLicenseTextRu = remember {
        context.resources.openRawResource(R.raw.license_gpl_v3_ru).bufferedReader().use { it.readText() }
    }

    val activeLicenseText = if (selectedLanguage == "ru") fullLicenseTextRu else fullLicenseTextEn
    var pendingRedirectUrl by remember { mutableStateOf<String?>(null) }

    if (pendingRedirectUrl != null) {
        ExternalLinkConfirmDialog(
            url = pendingRedirectUrl ?: "",
            onDismiss = { pendingRedirectUrl = null }
        )
    }

    fun openGitHubLicense() {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        pendingRedirectUrl = "https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/blob/main/LICENSE"
    }

    fun copyLicenseToClipboard() {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val label = if (selectedLanguage == "ru") context.getString(R.string.license_copy_label_ru) else context.getString(R.string.license_copy_label_en)
        val clip = ClipData.newPlainText(label, activeLicenseText)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, context.getString(R.string.license_copied_toast), Toast.LENGTH_SHORT).show()
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
                .verticalScroll(rememberScrollState())
                .padding(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 20.dp
                )
                .adaptiveContentPadding(),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {

            // SECTION 1: HEADER CARD
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .staggeredEntrance(index = 0)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.Transparent)
                    .border(1.dp, Color(0xFF181E2E), RoundedCornerShape(24.dp))
                    .padding(20.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(ActiveGreenLed.copy(alpha = 0.15f))
                                .border(1.5.dp, ActiveGreenLed.copy(alpha = 0.5f), CircleShape)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_license),
                                contentDescription = null,
                                tint = ActiveGreenLed,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Column {
                            Text(
                                text = stringResource(R.string.license_gnu_gpl_title),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black,
                                color = TextWhite
                            )
                            Text(
                                text = "Copyright (c) 2026 R1Xern (Mirrly Dev)",
                                fontSize = 12.5.sp,
                                color = TextMuted
                            )
                        }
                    }

                    Text(
                        text = stringResource(R.string.license_header_desc),
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = TextWhite.copy(alpha = 0.85f)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { openGitHubLicense() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Transparent,
                                contentColor = ActiveGreenLed
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, ActiveGreenLed),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f).height(42.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_github),
                                contentDescription = null,
                                tint = ActiveGreenLed,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("GitHub", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }

                        Button(
                            onClick = { copyLicenseToClipboard() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF141A29),
                                contentColor = TextWhite
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1F283D)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f).height(42.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_copy),
                                contentDescription = null,
                                tint = TextWhite,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.license_btn_copy), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }

            // SECTION 2: PERMISSIONS SUMMARY
            Column(
                modifier = Modifier.staggeredEntrance(index = 1),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.license_section_permissions),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.3.sp,
                    color = TextMuted
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Transparent)
                        .border(1.dp, Color(0xFF181E2E), RoundedCornerShape(20.dp))
                        .padding(18.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        PermissionRow(text = stringResource(R.string.license_perm_free_use))
                        PermissionRow(text = stringResource(R.string.license_perm_modification))
                        PermissionRow(text = stringResource(R.string.license_perm_copyleft))
                        PermissionRow(text = stringResource(R.string.license_perm_patent_protection))
                        HorizontalDivider(color = Color(0xFF161A26), thickness = 1.dp, modifier = Modifier.padding(vertical = 4.dp))
                        Text(
                            text = stringResource(R.string.license_condition_notice),
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = TextMuted
                        )
                    }
                }
            }

            // SECTION 3: FULL LICENSE TEXT CODE BLOCK WITH LANGUAGE TAB SELECTOR
            Column(
                modifier = Modifier.staggeredEntrance(index = 2),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.license_section_text),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.3.sp,
                        color = TextMuted
                    )

                    Text(
                        text = if (selectedLanguage == "ru") stringResource(R.string.license_lang_russian) else stringResource(R.string.license_lang_english),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = ActiveGreenLed
                    )
                }

                // Smooth Segmented Tab Switcher (Russian / English)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF0D111A))
                        .border(1.dp, Color(0xFF181E2E), RoundedCornerShape(14.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val isRu = selectedLanguage == "ru"
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isRu) ActiveGreenLed.copy(alpha = 0.18f) else Color.Transparent)
                            .border(if (isRu) 1.dp else 0.dp, if (isRu) ActiveGreenLed.copy(alpha = 0.6f) else Color.Transparent, RoundedCornerShape(10.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                selectedLanguage = "ru"
                            }
                    ) {
                        Text(
                            text = stringResource(R.string.license_lang_russian),
                            fontSize = 12.5.sp,
                            fontWeight = if (isRu) FontWeight.Bold else FontWeight.Medium,
                            color = if (isRu) ActiveGreenLed else TextMuted
                        )
                    }

                    val isEn = selectedLanguage == "en"
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isEn) ActiveGreenLed.copy(alpha = 0.18f) else Color.Transparent)
                            .border(if (isEn) 1.dp else 0.dp, if (isEn) ActiveGreenLed.copy(alpha = 0.6f) else Color.Transparent, RoundedCornerShape(10.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                selectedLanguage = "en"
                            }
                    ) {
                        Text(
                            text = stringResource(R.string.license_lang_english),
                            fontSize = 12.5.sp,
                            fontWeight = if (isEn) FontWeight.Bold else FontWeight.Medium,
                            color = if (isEn) ActiveGreenLed else TextMuted
                        )
                    }
                }

                // License Text Display Box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF080B12))
                        .border(1.dp, Color(0xFF181E2E), RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    Crossfade(targetState = selectedLanguage, animationSpec = tween(220), label = "licenseCrossfade") { lang ->
                        val textToDisplay = if (lang == "ru") fullLicenseTextRu else fullLicenseTextEn
                        Text(
                            text = textToDisplay,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextWhite.copy(alpha = 0.88f),
                            lineHeight = 17.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // 2. FROSTED GLASS HEADER PANEL
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
                        text = stringResource(R.string.license_screen_title),
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

        // Delicate Cyber Particles floating over entire license interface
        CyberParticlesOverlay(
            modifier = Modifier.fillMaxSize(),
            particleCount = 42,
            alphaMultiplier = 0.70f
        )
    }
}

@Composable
private fun PermissionRow(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(ActiveGreenLed.copy(alpha = 0.2f))
        ) {
            Text(
                text = "✓",
                color = ActiveGreenLed,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = TextWhite
        )
    }
}
