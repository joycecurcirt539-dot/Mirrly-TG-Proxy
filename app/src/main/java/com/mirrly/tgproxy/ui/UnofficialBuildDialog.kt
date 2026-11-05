package com.mirrly.tgproxy.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
        confirmValueChange = { remainingSeconds == 0 }
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
        maxTabletWidth = 490.dp
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .padding(top = 4.dp, bottom = 12.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Box(
                modifier = Modifier
                    .border(1.dp, categoryColor.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                    .background(Color.Transparent)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = categoryText,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = categoryColor,
                    letterSpacing = 0.8.sp
                )
            }

            Text(
                text = stringResource(R.string.security_advisory_title),
                fontSize = 18.5.sp,
                fontWeight = FontWeight.Bold,
                color = TextWhite,
                textAlign = TextAlign.Center
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, warningAmber.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                    .background(Color.Transparent)
                    .padding(14.dp)
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_shield),
                            contentDescription = null,
                            tint = warningAmber,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stringResource(R.string.security_advisory_gateway_warn_title),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = warningAmber
                        )
                    }
                    Text(
                        text = stringResource(R.string.security_advisory_gateway_warn_desc),
                        fontSize = 12.sp,
                        color = TextWhite.copy(alpha = 0.9f),
                        textAlign = TextAlign.Start,
                        lineHeight = 17.5.sp
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, ActiveGreenLed.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                    .background(Color.Transparent)
                    .padding(14.dp)
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_check_circle),
                            contentDescription = null,
                            tint = ActiveGreenLed,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stringResource(R.string.security_advisory_free_title),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = ActiveGreenLed
                        )
                    }
                    Text(
                        text = stringResource(R.string.security_advisory_free_desc),
                        fontSize = 12.sp,
                        color = TextWhite.copy(alpha = 0.9f),
                        textAlign = TextAlign.Start,
                        lineHeight = 17.5.sp
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, AmoledBorder, RoundedCornerShape(16.dp))
                    .background(Color.Transparent)
                    .padding(14.dp)
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_info_circle),
                            contentDescription = null,
                            tint = telegramCyan,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stringResource(R.string.security_advisory_patterns_title),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.security_advisory_pattern_popup_title),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = alertRed
                        )
                        Text(
                            text = stringResource(R.string.security_advisory_pattern_popup_desc),
                            fontSize = 11.5.sp,
                            color = TextMuted,
                            lineHeight = 16.5.sp
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.security_advisory_pattern_vpn_title),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = alertRed
                        )
                        Text(
                            text = stringResource(R.string.security_advisory_pattern_vpn_desc),
                            fontSize = 11.5.sp,
                            color = TextMuted,
                            lineHeight = 16.5.sp
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.security_advisory_pattern_blocking_title),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = warningAmber
                        )
                        Text(
                            text = stringResource(R.string.security_advisory_pattern_blocking_desc),
                            fontSize = 11.5.sp,
                            color = TextMuted,
                            lineHeight = 16.5.sp
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.security_advisory_pattern_signature_title),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = ActiveGreenLed
                        )
                        Text(
                            text = stringResource(R.string.security_advisory_pattern_signature_desc),
                            fontSize = 11.5.sp,
                            color = TextMuted,
                            lineHeight = 16.5.sp
                        )
                        Text(
                            text = officialSha,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = ActiveGreenLed.copy(alpha = 0.9f),
                            lineHeight = 14.5.sp
                        )

                        if (isUnofficialDetected && actualSha.isNotBlank() && actualSha != officialSha) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Обнаруженный отпечаток сборки:",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = alertRed
                            )
                            Text(
                                text = actualSha,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = alertRed.copy(alpha = 0.9f),
                                lineHeight = 14.5.sp
                            )
                        }
                    }
                }
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, telegramCyan.copy(alpha = 0.85f), RoundedCornerShape(14.dp))
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
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_telegram),
                            contentDescription = null,
                            tint = telegramCyan,
                            modifier = Modifier.size(17.dp)
                        )
                        Text(
                            text = stringResource(R.string.security_advisory_btn_telegram),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = telegramCyan
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, ActiveGreenLed.copy(alpha = 0.8f), RoundedCornerShape(14.dp))
                        .background(Color.Transparent)
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            confirmUrl = officialReleasesUrl
                        }),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_github),
                            contentDescription = null,
                            tint = ActiveGreenLed,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stringResource(R.string.security_advisory_btn_github),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = ActiveGreenLed
                        )
                    }
                }

                val isTimerActive = remainingSeconds > 0
                val dismissBorderColor = if (isTimerActive) AmoledBorder.copy(alpha = 0.6f) else ActiveGreenLed.copy(alpha = 0.85f)
                val dismissTextColor = if (isTimerActive) TextMuted else ActiveGreenLed

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, dismissBorderColor, RoundedCornerShape(14.dp))
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
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (isTimerActive) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_timer),
                                contentDescription = null,
                                tint = TextMuted,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = stringResource(R.string.security_advisory_btn_timer_wait, remainingSeconds),
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = dismissTextColor
                            )
                        } else {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_check),
                                contentDescription = null,
                                tint = ActiveGreenLed,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = stringResource(R.string.security_advisory_btn_dismiss_ready),
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = dismissTextColor
                            )
                        }
                    }
                }
            }
        }
    }
}
