package com.mirrly.tgproxy.ui
import androidx.compose.ui.res.stringResource

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import android.os.Build
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.ui.theme.*

@Composable
fun GithubStarDialog(
    onDismiss: () -> Unit,
    onStarClicked: () -> Unit = {},
    onNeverShowAgain: () -> Unit
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
            onConfirmed = {
                onStarClicked()
                onDismiss()
            }
        )
    }

    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        isVisible = true
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        DialogBackdropBox(
            onDismiss = onDismiss,
            blurRadiusPx = 70
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .adaptiveContainerWidth(420.dp)
                    .padding(horizontal = 20.dp, vertical = 24.dp)
                    .frostedVignetteCard(
                        shape = RoundedCornerShape(26.dp),
                        accentColor = ActiveGreenLed,
                        vignetteStrength = 0.65f,
                        borderBrush = Brush.horizontalGradient(
                            colors = listOf(
                                ActiveGreenLed.copy(alpha = 0.50f),
                                ActiveGreenLed.copy(alpha = 0.25f),
                                ActiveGreenLed.copy(alpha = 0.40f)
                            )
                        )
                    )
                    .lightSweep(
                        isEnabled = true,
                        shape = RoundedCornerShape(26.dp),
                        borderWidth = 1.dp,
                        sweepColor = ActiveGreenLed
                    )
                    .padding(22.dp)
                    .clickable(enabled = false) {}
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    // Category Pill
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = ActiveGreenLed.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, ActiveGreenLed.copy(alpha = 0.35f))
                    ) {
                        Text(
                            text = stringResource(R.string.github_star_dialog_category),
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = ActiveGreenLed,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                        )
                    }

                    // Title
                    Text(
                        text = stringResource(R.string.github_star_dialog_sub),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite,
                        textAlign = TextAlign.Center,
                        letterSpacing = 0.3.sp
                    )

                    // Description Body
                    Text(
                        text = stringResource(R.string.github_star_dialog_body),
                        fontSize = 13.sp,
                        color = TextWhite.copy(alpha = 0.88f),
                        textAlign = TextAlign.Center,
                        lineHeight = 19.sp,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // Primary Button: "Star Star"
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            showConfirmDialog = true
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ActiveGreenLed,
                            contentColor = Color.Black
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                    ) {
                        Text(stringResource(R.string.github_star_dialog_btn_star), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }

                    // Secondary Action Buttons: "Later" & "Does not show"
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onDismiss()
                            },
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.20f)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = TextWhite.copy(alpha = 0.85f)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                        ) {
                            Text(stringResource(R.string.action_later), fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                        }

                        TextButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onNeverShowAgain()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                        ) {
                            Text(stringResource(R.string.github_star_dialog_btn_dismiss), fontSize = 12.sp, color = TextWhite.copy(alpha = 0.55f))
                        }
                    }
                }
            }
        }
    }
}
