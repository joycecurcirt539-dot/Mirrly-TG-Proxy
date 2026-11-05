package com.mirrly.tgproxy.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.mirrly.tgproxy.ui.theme.ActiveGreenLed
import com.mirrly.tgproxy.ui.theme.AmoledBackground
import com.mirrly.tgproxy.ui.theme.AmoledBorder
import com.mirrly.tgproxy.ui.theme.TextMuted
import com.mirrly.tgproxy.ui.theme.TextWhite
import com.mirrly.tgproxy.ui.theme.springPress
import com.mirrly.tgproxy.util.SignatureVerifier
import kotlinx.coroutines.delay
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnofficialBuildDialog(
    onDismiss: () -> Unit,
    isUnofficialDetected: Boolean = false
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    val officialTelegramUrl = "https://t.me/WhyOKyHb"
    val officialReleasesUrl = "https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/releases"

    var remainingSeconds by remember { mutableStateOf(20) }
    LaunchedEffect(Unit) {
        while (remainingSeconds > 0) {
            delay(1000)
            remainingSeconds--
        }
    }

    BackHandler(enabled = remainingSeconds > 0) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { false }
    )

    var confirmUrl by remember { mutableStateOf<String?>(null) }
    if (confirmUrl != null) {
        ExternalLinkConfirmDialog(
            url = confirmUrl!!,
            onDismiss = { confirmUrl = null },
            onConfirmed = {
                confirmUrl = null
            }
        )
    }

    val actualSha = remember { SignatureVerifier.getSignatureSha256(context) }
    val reportJsonStr = remember { SignatureVerifier.getSecurityReportJson() }

    val reportObj = remember(reportJsonStr) {
        try {
            if (!reportJsonStr.isNullOrBlank()) JSONObject(reportJsonStr) else null
        } catch (_: Exception) {
            null
        }
    }

    val officialSha = reportObj?.optString("official_fingerprint")?.takeIf { it.isNotBlank() }
        ?: "00:32:69:CB:70:12:DF:37:61:E7:D3:92:79:5C:C3:55:02:43:39:A4:B2:1B:3F:B5:75:A4:C9:2B:CC:DD:25:16"

    val warningAmber = Color(0xFFFF9E00)
    val alertRed = Color(0xFFFF453A)
    val telegramCyan = Color(0xFF26A5E4)

    val categoryColor = if (isUnofficialDetected) alertRed else warningAmber
    val categoryText = if (isUnofficialDetected) {
        stringResource(R.string.security_advisory_category_untrusted)
    } else {
        stringResource(R.string.security_advisory_category_daily)
    }

    MirrlyModalBottomSheet(
        onDismissRequest = {
            if (remainingSeconds == 0) {
                onDismiss()
            }
        },
        sheetState = sheetState,
        showDragHandle = false,
        containerColor = AmoledBackground.copy(alpha = 0.88f),
        scrimColor = AmoledBackground.copy(alpha = 0.55f),
        maxTabletWidth = 460.dp
    ) {
        rememberDialogBlurState(blurRadiusPx = 60)

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(top = 14.dp, bottom = 4.dp)
        ) {
            Box(
                modifier = Modifier
                    .border(1.dp, categoryColor.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .background(categoryColor.copy(alpha = 0.08f))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = categoryText,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = categoryColor,
                    letterSpacing = 0.5.sp
                )
            }

            Text(
                text = stringResource(R.string.security_advisory_title),
                fontSize = 15.5.sp,
                fontWeight = FontWeight.Bold,
                color = TextWhite,
                textAlign = TextAlign.Center
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, AmoledBorder, RoundedCornerShape(14.dp))
                    .background(Color.Transparent)
                    .padding(12.dp)
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SecurityBullet(
                        iconRes = R.drawable.ic_shield,
                        iconTint = telegramCyan,
                        text = stringResource(R.string.security_advisory_bullet_official),
                        textColor = TextWhite
                    )
                    SecurityBullet(
                        iconRes = R.drawable.ic_stat_proxy_warning,
                        iconTint = warningAmber,
                        text = stringResource(R.string.security_advisory_bullet_gateway),
                        textColor = warningAmber
                    )
                    SecurityBullet(
                        iconRes = R.drawable.ic_check_circle,
                        iconTint = ActiveGreenLed,
                        text = stringResource(R.string.security_advisory_bullet_free),
                        textColor = ActiveGreenLed
                    )
                    SecurityBullet(
                        iconRes = R.drawable.ic_info_circle,
                        iconTint = alertRed,
                        text = stringResource(R.string.security_advisory_bullet_patterns),
                        textColor = TextWhite
                    )

                    if (isUnofficialDetected && actualSha.isNotBlank() && actualSha != officialSha) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "SHA-256: $actualSha",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            color = alertRed,
                            lineHeight = 12.sp
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, telegramCyan.copy(alpha = 0.75f), RoundedCornerShape(12.dp))
                        .background(Color.Transparent)
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=WhyOKyHb")).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                confirmUrl = officialTelegramUrl
                            }
                        }),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_telegram),
                            contentDescription = null,
                            tint = telegramCyan,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = stringResource(R.string.security_advisory_btn_telegram),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = telegramCyan
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, ActiveGreenLed.copy(alpha = 0.75f), RoundedCornerShape(12.dp))
                        .background(Color.Transparent)
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            confirmUrl = officialReleasesUrl
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
                            text = stringResource(R.string.security_advisory_btn_github),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = ActiveGreenLed
                        )
                    }
                }
            }

            val isTimerActive = remainingSeconds > 0
            val dismissBorderColor = if (isTimerActive) AmoledBorder.copy(alpha = 0.5f) else ActiveGreenLed.copy(alpha = 0.85f)
            val dismissTextColor = if (isTimerActive) TextMuted else ActiveGreenLed

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, dismissBorderColor, RoundedCornerShape(12.dp))
                    .background(Color.Transparent)
                    .springPress(onClick = {
                        if (isTimerActive) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        } else {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDismiss()
                        }
                    }),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (isTimerActive) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_timer),
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = stringResource(R.string.security_advisory_btn_timer_wait, remainingSeconds),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = dismissTextColor
                        )
                    } else {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_check),
                            contentDescription = null,
                            tint = ActiveGreenLed,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = stringResource(R.string.security_advisory_btn_dismiss_ready),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = dismissTextColor
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SecurityBullet(
    iconRes: Int,
    iconTint: Color,
    text: String,
    textColor: Color
) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(13.dp)
        )
        Text(
            text = text,
            fontSize = 11.5.sp,
            color = textColor.copy(alpha = 0.92f),
            lineHeight = 15.sp
        )
    }
}
