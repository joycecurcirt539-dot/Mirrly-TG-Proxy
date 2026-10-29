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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.ui.theme.*
import com.mirrly.tgproxy.util.SignatureStatus
import com.mirrly.tgproxy.util.SignatureVerifier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfficialSourceCard(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val repoUrl = "https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy"

    val signatureStatus = remember { SignatureVerifier.verify(context) }
    val isUnofficial = signatureStatus == SignatureStatus.UNOFFICIAL_MODIFIED
    val statusColor = if (isUnofficial) Color(0xFFFF9E00) else ActiveGreenLed

    var showUnofficialWarningDialog by remember { mutableStateOf(false) }
    var showSecurityDialog by remember { mutableStateOf(false) }
    var showRepoConfirmDialog by remember { mutableStateOf(false) }

    if (showRepoConfirmDialog) {
        ExternalLinkConfirmDialog(
            url = repoUrl,
            onDismiss = { showRepoConfirmDialog = false }
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Build Status Header (Seamless without card background, Zero-Background / Outline-Only)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Color.Transparent)
                    .border(1.dp, statusColor.copy(alpha = 0.5f), CircleShape)
            ) {
                if (isUnofficial) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_stat_proxy_warning),
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_shield),
                        contentDescription = null,
                        tint = ActiveGreenLed,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isUnofficial) {
                        stringResource(R.string.official_source_unofficial_title)
                    } else {
                        stringResource(R.string.official_source_official_title)
                    },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (isUnofficial) {
                        stringResource(R.string.official_source_unofficial_desc)
                    } else {
                        stringResource(R.string.official_source_official_desc)
                    },
                    fontSize = 12.sp,
                    color = TextMuted,
                    lineHeight = 16.sp
                )
            }
        }

        // Action Buttons Row (Outline-Only, Zero-Background, SpringPress)
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            // Button 1: Repository (GitHub)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, ActiveGreenLed.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    .background(Color.Transparent)
                    .springPress(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showRepoConfirmDialog = true
                    }),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_github),
                        contentDescription = null,
                        tint = ActiveGreenLed,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = stringResource(R.string.official_source_btn_repo),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = ActiveGreenLed
                    )
                }
            }

            // Button 2: Check Build Authenticity via Hash
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, statusColor.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    .background(Color.Transparent)
                    .springPress(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (isUnofficial) {
                            showUnofficialWarningDialog = true
                        } else {
                            showSecurityDialog = true
                        }
                    }),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.official_source_btn_check_hash),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = statusColor
                )
            }
        }
    }

    // 1. Unofficial Build Warning Sheet (Opens if signature is modified)
    if (showUnofficialWarningDialog) {
        UnofficialBuildDialog(
            onDismiss = { showUnofficialWarningDialog = false }
        )
    }

    // 2. Security Bottom Sheet (Zero-Popup MirrlyModalBottomSheet)
    if (showSecurityDialog) {
        val currentSha256 = remember { SignatureVerifier.getSignatureSha256(context) }
        val isMatchesOfficial = currentSha256.equals(SignatureVerifier.OFFICIAL_RELEASE_SHA256, ignoreCase = true) ||
            SignatureVerifier.OFFICIAL_RELEASE_SHA256.replace(":", "").equals(currentSha256.replace(":", ""), ignoreCase = true)

        MirrlyModalBottomSheet(
            onDismissRequest = { showSecurityDialog = false },
            maxTabletWidth = 500.dp
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(top = 4.dp, bottom = 8.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Category Pill (Outline-Only, Zero-Background)
                Box(
                    modifier = Modifier
                        .border(1.dp, statusColor.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                        .background(Color.Transparent)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.official_source_dialog_category),
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = statusColor,
                        letterSpacing = 1.sp
                    )
                }

                // Main Title
                Text(
                    text = stringResource(R.string.official_source_dialog_title),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite,
                    textAlign = TextAlign.Center
                )

                // Card 1: Status Explanation Card (Outline-Only, Zero-Background)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, statusColor.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                        .background(Color.Transparent)
                        .padding(14.dp)
                ) {
                    Text(
                        text = when (signatureStatus) {
                            SignatureStatus.OFFICIAL_RELEASE -> stringResource(R.string.official_source_sig_official)
                            SignatureStatus.DEBUG_BUILD -> stringResource(R.string.official_source_sig_debug)
                            else -> stringResource(R.string.official_source_sig_generic)
                        },
                        fontSize = 12.5.sp,
                        color = TextWhite.copy(alpha = 0.9f),
                        textAlign = TextAlign.Start,
                        lineHeight = 18.sp
                    )
                }

                // Card 2: SHA-256 Fingerprint Display Card (Outline-Only, Zero-Background)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, AmoledBorder, RoundedCornerShape(16.dp))
                        .background(Color.Transparent)
                        .padding(14.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.official_source_dialog_fingerprint_title),
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextMuted,
                            letterSpacing = 0.8.sp
                        )
                        Text(
                            text = currentSha256,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Normal,
                            color = if (isMatchesOfficial) ActiveGreenLed else statusColor,
                            textAlign = TextAlign.Start,
                            lineHeight = 16.5.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                // Action Row: Copy Hash + Close
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Copy Hash Button
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .border(1.dp, AmoledBorder, RoundedCornerShape(14.dp))
                            .background(Color.Transparent)
                            .springPress(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                try {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("SHA256 Signature", currentSha256)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.official_source_hash_copied),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } catch (_: Exception) {}
                            }),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.action_copy_alt),
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextWhite
                        )
                    }

                    // Close Button
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .border(1.dp, ActiveGreenLed.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
                            .background(Color.Transparent)
                            .springPress(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                showSecurityDialog = false
                            }),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.action_close),
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = ActiveGreenLed
                        )
                    }
                }
            }
        }
    }
}
