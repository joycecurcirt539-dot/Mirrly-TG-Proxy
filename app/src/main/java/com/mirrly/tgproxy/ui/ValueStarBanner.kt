package com.mirrly.tgproxy.ui
import androidx.compose.ui.res.stringResource

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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

@Composable
fun ValueStarBanner(
    onStarClicked: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val githubUrl = "https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy"
    var showConfirmDialog by remember { mutableStateOf(false) }

    if (showConfirmDialog) {
        ExternalLinkConfirmDialog(
            url = githubUrl,
            title = stringResource(R.string.github_star_dialog_title),
            description = stringResource(R.string.github_star_dialog_desc),
            onDismiss = { showConfirmDialog = false },
            onConfirmed = { onStarClicked() }
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .frostedVignetteCard(
                shape = RoundedCornerShape(22.dp),
                accentColor = ActiveGreenLed,
                vignetteStrength = 0.65f,
                borderBrush = Brush.horizontalGradient(
                    colors = listOf(
                        ActiveGreenLed.copy(alpha = 0.55f),
                        ActiveGreenLed.copy(alpha = 0.30f),
                        ActiveGreenLed.copy(alpha = 0.40f)
                    )
                )
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(ActiveGreenLed.copy(alpha = 0.15f))
                        .border(1.dp, ActiveGreenLed.copy(alpha = 0.35f), CircleShape)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_github),
                        contentDescription = null,
                        tint = ActiveGreenLed,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.value_star_banner_title),
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.value_star_banner_desc),
                        fontSize = 12.sp,
                        color = TextMuted,
                        lineHeight = 16.sp
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showConfirmDialog = true
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ActiveGreenLed,
                        contentColor = Color.Black
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_github),
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stringResource(R.string.action_star_github),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }

                TextButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDismiss()
                    },
                    modifier = Modifier.height(40.dp)
                ) {
                    Text(
                        text = stringResource(R.string.action_close),
                        color = TextMuted,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}
