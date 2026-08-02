package com.mirrly.tgproxy.ui

import android.os.Build
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.core.ReleaseInfo
import com.mirrly.tgproxy.service.DownloadStatus
import com.mirrly.tgproxy.service.UpdateDownloader
import com.mirrly.tgproxy.ui.theme.*
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun UpdateAvailableDialog(
    releaseInfo: ReleaseInfo,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    val downloadStatus by UpdateDownloader.status.collectAsState()

    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        isVisible = true
        UpdateDownloader.resetStatus()
    }

    var pendingRedirectUrl by remember { mutableStateOf<String?>(null) }

    if (pendingRedirectUrl != null) {
        ExternalLinkConfirmDialog(
            url = pendingRedirectUrl ?: "",
            onDismiss = { pendingRedirectUrl = null },
            onConfirmed = onDismiss
        )
    }

    Dialog(
        onDismissRequest = {
            UpdateDownloader.resetStatus()
            onDismiss()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = downloadStatus !is DownloadStatus.Downloading,
            dismissOnClickOutside = downloadStatus !is DownloadStatus.Downloading
        )
    ) {
        val view = LocalView.current
        SideEffect {
            val window = (view.parent as? DialogWindowProvider)?.window
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                window?.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window?.attributes = window?.attributes?.apply {
                    blurBehindRadius = 55
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.20f))
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(250)) + scaleIn(tween(280), initialScale = 0.9f)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xE60D121C),
                                    Color(0xD9080B12)
                                )
                            )
                        )
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    ActiveGreenLed.copy(alpha = 0.12f),
                                    Color.Transparent
                                )
                            )
                        )
                        .border(
                            width = 1.dp,
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    ActiveGreenLed.copy(alpha = 0.6f),
                                    AmoledBorder,
                                    ActiveGreenLed.copy(alpha = 0.35f)
                                )
                            ),
                            shape = RoundedCornerShape(24.dp)
                        )
                        .lightSweep(
                            isEnabled = true,
                            shape = RoundedCornerShape(24.dp),
                            borderWidth = 1.dp,
                            sweepColor = ActiveGreenLed
                        )
                        .padding(22.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Badge Icon
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(ActiveGreenLed.copy(alpha = 0.15f))
                                .border(1.dp, ActiveGreenLed.copy(alpha = 0.4f), CircleShape)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_refresh),
                                contentDescription = null,
                                tint = ActiveGreenLed,
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        // Title
                        Text(
                            text = "Доступно обновление v${releaseInfo.versionName}",
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite,
                            textAlign = TextAlign.Center
                        )

                        // Changelog Container (Scrollable Monospace text block)
                        val changelogText = releaseInfo.releaseNotes.ifBlank {
                            "Официальный список изменений и релизные сборки доступны в репозитории на GitHub."
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 140.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF08090C))
                                .border(1.dp, AmoledBorder, RoundedCornerShape(14.dp))
                                .padding(12.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = changelogText,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = TextWhite.copy(alpha = 0.88f),
                                lineHeight = 18.sp
                            )
                        }

                        // In-App Download Status / Progress Content
                        when (val status = downloadStatus) {
                            is DownloadStatus.Downloading -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Color(0xFF0D131F))
                                        .border(1.dp, ActiveGreenLed.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                                        .padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Загрузка обновления...",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = TextWhite
                                        )
                                        if (status.progress >= 0f) {
                                            Text(
                                                text = "${(status.progress * 100).toInt()}%",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = ActiveGreenLed
                                            )
                                        }
                                    }

                                    if (status.progress >= 0f) {
                                        LinearProgressIndicator(
                                            progress = { status.progress },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(6.dp)
                                                .clip(RoundedCornerShape(3.dp)),
                                            color = ActiveGreenLed,
                                            trackColor = Color.White.copy(alpha = 0.1f)
                                        )
                                    } else {
                                        LinearProgressIndicator(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(6.dp)
                                                .clip(RoundedCornerShape(3.dp)),
                                            color = ActiveGreenLed,
                                            trackColor = Color.White.copy(alpha = 0.1f)
                                        )
                                    }

                                    val downloadedMbStr = String.format(Locale.US, "%.1f", status.downloadedBytes / (1024f * 1024f))
                                    val totalMbStr = if (status.totalBytes > 0) {
                                        String.format(Locale.US, "%.1f MB", status.totalBytes / (1024f * 1024f))
                                    } else {
                                        "..."
                                    }
                                    Text(
                                        text = "$downloadedMbStr MB / $totalMbStr",
                                        fontSize = 11.5.sp,
                                        color = TextMuted
                                    )
                                }
                            }

                            is DownloadStatus.Verifying -> {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Color(0xFF0D131F))
                                        .border(1.dp, ActiveGreenLed.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                                        .padding(14.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = ActiveGreenLed,
                                        strokeWidth = 2.dp
                                    )
                                    Text(
                                        text = "Проверка подлинности SHA-256...",
                                        fontSize = 13.sp,
                                        color = TextWhite,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }

                            is DownloadStatus.Error -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Color(0x33FF5252))
                                        .border(1.dp, Color(0xFFFF5252).copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "Ошибка скачивания",
                                        color = Color(0xFFFF5252),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        text = status.message,
                                        color = TextWhite.copy(alpha = 0.9f),
                                        fontSize = 12.sp
                                    )
                                }
                            }

                            is DownloadStatus.ReadyToInstall -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(ActiveGreenLed.copy(alpha = 0.1f))
                                        .border(1.dp, ActiveGreenLed.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = "✓ Файл верифицирован (SHA-256 OK)",
                                        color = ActiveGreenLed,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        textAlign = TextAlign.Center
                                    )
                                    Text(
                                        text = "Нажмите кнопкy ниже для запуска установки.",
                                        color = TextWhite.copy(alpha = 0.85f),
                                        fontSize = 12.sp,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }

                            DownloadStatus.Idle -> {
                                // Idle state - display normal options
                            }
                        }

                        Spacer(modifier = Modifier.height(2.dp))

                        // Action Buttons
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            when (val status = downloadStatus) {
                                DownloadStatus.Idle -> {
                                    // Primary Direct Download & Install Button
                                    Button(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            val url = releaseInfo.downloadUrl
                                            if (!url.isNullOrBlank()) {
                                                coroutineScope.launch {
                                                    UpdateDownloader.downloadAndVerifyApk(
                                                        context = context,
                                                        downloadUrl = url,
                                                        expectedSha256List = releaseInfo.expectedSha256List,
                                                        versionName = releaseInfo.versionName
                                                    )
                                                }
                                            } else {
                                                pendingRedirectUrl = releaseInfo.htmlUrl
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = ActiveGreenLed,
                                            contentColor = Color.Black
                                        ),
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(46.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                painter = painterResource(id = R.drawable.ic_refresh),
                                                contentDescription = null,
                                                tint = Color.Black,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Text(
                                                text = "Скачать и установить",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp
                                            )
                                        }
                                    }

                                    // Secondary Button - Browser GitHub
                                    OutlinedButton(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            pendingRedirectUrl = releaseInfo.htmlUrl
                                        },
                                        shape = RoundedCornerShape(14.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = TextWhite
                                        ),
                                        border = ButtonDefaults.outlinedButtonBorder.copy(
                                            brush = Brush.horizontalGradient(listOf(AmoledBorder, ActiveGreenLed.copy(alpha = 0.4f)))
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(42.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                painter = painterResource(id = R.drawable.ic_github),
                                                contentDescription = null,
                                                tint = TextWhite,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text(
                                                text = "Открыть на GitHub",
                                                fontWeight = FontWeight.Medium,
                                                fontSize = 13.sp
                                            )
                                        }
                                    }

                                    // Skip Button
                                    TextButton(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            UpdateDownloader.resetStatus()
                                            onDismiss()
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = "Пропустить",
                                            color = TextMuted,
                                            fontWeight = FontWeight.Medium,
                                            fontSize = 13.5.sp
                                        )
                                    }
                                }

                                is DownloadStatus.ReadyToInstall -> {
                                    val canInstall = remember(context) { UpdateDownloader.canInstallPackages(context) }

                                    Button(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            if (UpdateDownloader.canInstallPackages(context)) {
                                                UpdateDownloader.triggerInstall(context, status.file)
                                                onDismiss()
                                            } else {
                                                UpdateDownloader.openInstallPermissionSettings(context)
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = ActiveGreenLed,
                                            contentColor = Color.Black
                                        ),
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(46.dp)
                                    ) {
                                        Text(
                                            text = if (canInstall) "Установить обновление" else "Разрешить установку в Настройках",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        )
                                    }

                                    TextButton(
                                        onClick = {
                                            UpdateDownloader.resetStatus()
                                            onDismiss()
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = "Отмена",
                                            color = TextMuted,
                                            fontSize = 13.sp
                                        )
                                    }
                                }

                                is DownloadStatus.Error -> {
                                    Button(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            val url = releaseInfo.downloadUrl
                                            if (!url.isNullOrBlank()) {
                                                coroutineScope.launch {
                                                    UpdateDownloader.downloadAndVerifyApk(
                                                        context = context,
                                                        downloadUrl = url,
                                                        expectedSha256List = releaseInfo.expectedSha256List,
                                                        versionName = releaseInfo.versionName
                                                    )
                                                }
                                            } else {
                                                pendingRedirectUrl = releaseInfo.htmlUrl
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = ActiveGreenLed,
                                            contentColor = Color.Black
                                        ),
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(44.dp)
                                    ) {
                                        Text(
                                            text = "Повторить загрузку",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        )
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            pendingRedirectUrl = releaseInfo.htmlUrl
                                        },
                                        shape = RoundedCornerShape(14.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextWhite),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(text = "Скачать через браузер", fontSize = 13.sp)
                                    }
                                }

                                is DownloadStatus.Downloading, is DownloadStatus.Verifying -> {
                                    TextButton(
                                        onClick = {
                                            UpdateDownloader.resetStatus()
                                            onDismiss()
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = "Скрыть",
                                            color = TextMuted,
                                            fontSize = 13.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// Backward compatibility alias for existing usage
@Composable
fun UpdateDialog(
    releaseInfo: ReleaseInfo,
    onDismiss: () -> Unit
) {
    UpdateAvailableDialog(releaseInfo = releaseInfo, onDismiss = onDismiss)
}
