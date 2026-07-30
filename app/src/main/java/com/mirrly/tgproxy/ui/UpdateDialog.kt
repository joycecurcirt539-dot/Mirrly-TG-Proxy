package com.mirrly.tgproxy.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.core.ReleaseInfo
import com.mirrly.tgproxy.ui.theme.*

@Composable
fun UpdateDialog(
    releaseInfo: ReleaseInfo,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    val infiniteTransition = rememberInfiniteTransition(label = "updateGlow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.78f))
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .clip(RoundedCornerShape(26.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFF111724),
                                Color(0xFF0A0D15)
                            )
                        )
                    )
                    .border(
                        width = 1.5.dp,
                        brush = Brush.linearGradient(
                            colors = listOf(
                                ActiveGreenLed.copy(alpha = glowAlpha),
                                Color(0xFF1E283D)
                            )
                        ),
                        shape = RoundedCornerShape(26.dp)
                    )
                    .padding(24.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Pulsing Header Icon Badge
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(68.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        ActiveGreenLed.copy(alpha = 0.28f),
                                        Color(0xFF141A28)
                                    )
                                )
                            )
                            .border(2.dp, ActiveGreenLed.copy(alpha = glowAlpha), CircleShape)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_refresh),
                            contentDescription = null,
                            tint = ActiveGreenLed,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    // Title & Version Badge
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Вышло обновление!",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Black,
                            color = TextWhite,
                            textAlign = TextAlign.Center
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(ActiveGreenLed.copy(alpha = 0.15f))
                                .border(1.dp, ActiveGreenLed.copy(alpha = 0.4f), CircleShape)
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(ActiveGreenLed)
                            )
                            Text(
                                text = "Версия v${releaseInfo.versionName}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = ActiveGreenLed
                            )
                        }
                    }

                    // Release Notes (if present)
                    if (releaseInfo.releaseNotes.isNotBlank()) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "ЧТО НОВОГО:",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.2.sp,
                                color = TextMuted
                            )

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 160.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color(0xFF070A10))
                                    .border(1.dp, Color(0xFF161C2A), RoundedCornerShape(14.dp))
                                    .padding(12.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Text(
                                    text = releaseInfo.releaseNotes.trim(),
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp,
                                    color = TextWhite.copy(alpha = 0.85f)
                                )
                            }
                        }
                    } else {
                        Text(
                            text = "Доступна новая официальная версия Mirrly TG Proxy на GitHub. Рекомендуется обновиться для максимальной стабильности.",
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            color = TextWhite.copy(alpha = 0.85f),
                            textAlign = TextAlign.Center
                        )
                    }

                    // Action Buttons
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Download Release Button
                        Button(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(releaseInfo.htmlUrl))
                                    context.startActivity(intent)
                                } catch (_: Exception) {}
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ActiveGreenLed,
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_github),
                                    contentDescription = null,
                                    tint = Color.Black,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Перейти к скачиванию на GitHub",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                        }

                        // Dismiss Button
                        TextButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Позже",
                                color = TextMuted,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
