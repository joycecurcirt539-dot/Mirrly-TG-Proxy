package com.mirrly.tgproxy.ui

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
import android.os.Build
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.ui.theme.*

@Composable
fun UnofficialBuildDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
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

    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        isVisible = true
    }

    val warningAmber = Color(0xFFFF9E00)
    val warningRed = Color(0xFFFF3B30)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        DialogBackdropBox(
            onDismiss = onDismiss
        ) {
            // Detailed Warning Content with Smooth Fading Edges
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .fadingEdges(topFadeHeight = 32.dp, bottomFadeHeight = 64.dp)
                    .navigationBarsPadding()
                    .padding(bottom = 160.dp, top = 24.dp)
                    .padding(horizontal = 24.dp)
                    .verticalScroll(rememberScrollState())
                    .clickable(enabled = false) {}
            ) {
                // Category Pill
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = warningAmber.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, warningAmber.copy(alpha = 0.40f))
                ) {
                    Text(
                        text = "ПРЕДУПРЕЖДЕНИЕ БЕЗОПАСНОСТИ",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = warningAmber,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                }

                // Title
                Text(
                    text = "Неофициальная или измененная сборка",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite,
                    textAlign = TextAlign.Center,
                    letterSpacing = 0.3.sp
                )

                // Body text in Amber Glass Card
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = warningAmber.copy(alpha = 0.08f),
                    border = BorderStroke(1.dp, warningAmber.copy(alpha = 0.30f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Данная версия приложения была пересобрана или изменена сторонними лицами. Разработчики Mirrly TG Proxy не несут ответственности за безопасность и сохранность данных в сторонних сборках. Настоятельно рекомендуем установить оригинальную версию из репозитория GitHub.",
                        fontSize = 13.sp,
                        color = TextWhite.copy(alpha = 0.90f),
                        textAlign = TextAlign.Start,
                        lineHeight = 19.sp,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }

            // Floating Bottom Action Buttons (Seamless over blurred background)
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 28.dp)
                    .fillMaxWidth()
                    .clickable(enabled = false) {}
            ) {
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showConfirmLinkDialog = true
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ActiveGreenLed.copy(alpha = 0.20f),
                        contentColor = ActiveGreenLed
                    ),
                    border = BorderStroke(1.dp, ActiveGreenLed.copy(alpha = 0.50f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .springPress()
                ) {
                    Text("Скачать с официального GitHub", fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDismiss()
                    },
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = TextWhite.copy(alpha = 0.90f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .springPress()
                ) {
                    Text("Я понимаю риск", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}
