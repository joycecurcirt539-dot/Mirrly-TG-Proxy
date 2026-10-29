package com.mirrly.tgproxy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.ui.theme.ActiveGreenLed
import com.mirrly.tgproxy.ui.theme.AmoledBorder
import com.mirrly.tgproxy.ui.theme.TextWhite
import com.mirrly.tgproxy.ui.theme.springPress

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnofficialBuildDialog(
    onDismiss: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val officialReleasesUrl = "https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/releases/latest"
    var showConfirmLinkDialog by remember { mutableStateOf(false) }

    if (showConfirmLinkDialog) {
        ExternalLinkConfirmDialog(
            url = officialReleasesUrl,
            onDismiss = { showConfirmLinkDialog = false },
            onConfirmed = onDismiss
        )
    }

    val warningAmber = Color(0xFFFF9E00)

    MirrlyModalBottomSheet(
        onDismissRequest = onDismiss,
        maxTabletWidth = 480.dp
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
                    .border(1.dp, warningAmber.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                    .background(Color.Transparent)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.unofficial_dialog_category),
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = warningAmber,
                    letterSpacing = 1.sp
                )
            }

            // Title
            Text(
                text = stringResource(R.string.unofficial_dialog_title),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = TextWhite,
                textAlign = TextAlign.Center
            )

            // Warning Body Card (Outline-Only, Zero-Background)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, warningAmber.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                    .background(Color.Transparent)
                    .padding(14.dp)
            ) {
                Text(
                    text = stringResource(R.string.unofficial_dialog_desc),
                    fontSize = 13.sp,
                    color = TextWhite.copy(alpha = 0.9f),
                    textAlign = TextAlign.Start,
                    lineHeight = 19.sp
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            // Action Buttons (Outline-Only, SpringPress)
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Download Official Release Button
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, ActiveGreenLed.copy(alpha = 0.8f), RoundedCornerShape(14.dp))
                        .background(Color.Transparent)
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            showConfirmLinkDialog = true
                        }),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.unofficial_dialog_btn_download),
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = ActiveGreenLed
                    )
                }

                // Ignore Risk / Dismiss Button
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, AmoledBorder, RoundedCornerShape(14.dp))
                        .background(Color.Transparent)
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDismiss()
                        }),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.unofficial_dialog_btn_dismiss),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextWhite.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}
