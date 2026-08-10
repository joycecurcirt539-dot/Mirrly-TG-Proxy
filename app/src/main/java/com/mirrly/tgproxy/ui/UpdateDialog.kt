package com.mirrly.tgproxy.ui

import android.os.Build
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
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
        UpdateDownloader.cancelDownload()
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
            UpdateDownloader.cancelDownload()
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
                    blurBehindRadius = 60
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(20.dp),
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
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color(0xE6080B12))
                        .border(1.dp, Color(0xFF181E2E), RoundedCornerShape(22.dp))
                        .lightSweep(
                            isEnabled = true,
                            shape = RoundedCornerShape(22.dp),
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
                        // Badge Icon (Transparent with Neon Outline)
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(54.dp)
                                .clip(CircleShape)
                                .background(Color.Transparent)
                                .border(1.5.dp, ActiveGreenLed.copy(alpha = 0.6f), CircleShape)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_refresh),
                                contentDescription = null,
                                tint = ActiveGreenLed,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        // Title & Subtitle
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = "Доступно обновление v${releaseInfo.versionName}",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = "Официальный релиз Mirrly TG Proxy",
                                fontSize = 12.sp,
                                color = TextMuted,
                                textAlign = TextAlign.Center
                            )
                        }

                        // Changelog Container (Transparent with clean border)
                        val changelogText = releaseInfo.releaseNotes.ifBlank {
                            "Официальный список изменений и релизные сборки доступны в репозитории на GitHub."
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 130.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color.Transparent)
                                .border(1.dp, Color(0xFF181E2E), RoundedCornerShape(14.dp))
                                .padding(12.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = changelogText,
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace,
                                color = TextWhite.copy(alpha = 0.88f),
                                lineHeight = 17.sp
                            )
                        }

                        // In-App Download Status / Progress Content (All Transparent)
                        when (val status = downloadStatus) {
                            is DownloadStatus.Downloading -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Color.Transparent)
                                        .border(1.dp, ActiveGreenLed.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                                        .padding(12.dp),
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
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = TextWhite
                                        )
                                        if (status.progress >= 0f) {
                                            Text(
                                                text = "${(status.progress * 100).toInt()}%",
                                                fontSize = 12.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = ActiveGreenLed
                                            )
                                        }
                                    }

                                    LinearProgressIndicator(
                                        progress = { if (status.progress >= 0f) status.progress else 0f },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(5.dp)
                                            .clip(RoundedCornerShape(3.dp)),
                                        color = ActiveGreenLed,
                                        trackColor = Color.White.copy(alpha = 0.08f)
                                    )

                                    val downloadedMbStr = String.format(Locale.US, "%.1f", status.downloadedBytes / (1024f * 1024f))
                                    val totalMbStr = if (status.totalBytes > 0) {
                                        String.format(Locale.US, "%.1f MB", status.totalBytes / (1024f * 1024f))
                                    } else "..."

                                    Text(
                                        text = "$downloadedMbStr MB / $totalMbStr",
                                        fontSize = 11.sp,
                                        color = TextMuted
                                    )
                                }
                            }

                            is DownloadStatus.Verifying -> {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Color.Transparent)
                                        .border(1.dp, ActiveGreenLed.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        color = ActiveGreenLed,
                                        strokeWidth = 2.dp
                                    )
                                    Text(
                                        text = "Проверка SHA-256 отпечатка...",
                                        fontSize = 12.5.sp,
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
                                        .background(Color.Transparent)
                                        .border(1.dp, Color(0xFFFF5252).copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "Ошибка скачивания",
                                        color = Color(0xFFFF5252),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.5.sp
                                    )
                                    Text(
                                        text = status.message,
                                        color = TextWhite.copy(alpha = 0.9f),
                                        fontSize = 11.5.sp
                                    )
                                }
                            }

                            is DownloadStatus.ReadyToInstall -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Color.Transparent)
                                        .border(1.dp, ActiveGreenLed.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
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
                                        text = "Нажмите кнопку ниже для запуска установки.",
                                        color = TextWhite.copy(alpha = 0.85f),
                                        fontSize = 11.5.sp,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }

                            DownloadStatus.Idle -> {}
                        }

                        // Action Buttons (Transparent outlines, no solid backgrounds)
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
                                            containerColor = Color.Transparent,
                                            contentColor = ActiveGreenLed
                                        ),
                                        border = BorderStroke(1.5.dp, ActiveGreenLed),
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(46.dp)
                                            .springPress()
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                painter = painterResource(id = R.drawable.ic_arrow_down),
                                                contentDescription = null,
                                                tint = ActiveGreenLed,
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
                                    Button(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            pendingRedirectUrl = releaseInfo.htmlUrl
                                        },
                                        shape = RoundedCornerShape(14.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color.Transparent,
                                            contentColor = TextWhite
                                        ),
                                        border = BorderStroke(1.dp, Color(0xFF1E283D)),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(42.dp)
                                            .springPress()
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
                                            UpdateDownloader.cancelDownload()
                                            onDismiss()
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = "Пропустить",
                                            color = TextMuted,
                                            fontWeight = FontWeight.Medium,
                                            fontSize = 13.sp
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
                                            containerColor = Color.Transparent,
                                            contentColor = ActiveGreenLed
                                        ),
                                        border = BorderStroke(1.5.dp, ActiveGreenLed),
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(46.dp)
                                            .springPress()
                                    ) {
                                        Text(
                                            text = if (canInstall) "Установить обновление" else "Разрешить установку в Настройках",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        )
                                    }

                                    TextButton(
                                        onClick = {
                                            UpdateDownloader.cancelDownload()
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
                                            containerColor = Color.Transparent,
                                            contentColor = ActiveGreenLed
                                        ),
                                        border = BorderStroke(1.5.dp, ActiveGreenLed),
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(44.dp)
                                            .springPress()
                                    ) {
                                        Text(
                                            text = "Повторить загрузку",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        )
                                    }

                                    Button(
                                        onClick = {
                                            pendingRedirectUrl = releaseInfo.htmlUrl
                                        },
                                        shape = RoundedCornerShape(14.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color.Transparent,
                                            contentColor = TextWhite
                                        ),
                                        border = BorderStroke(1.dp, Color(0xFF1E283D)),
                                        modifier = Modifier.fillMaxWidth().height(40.dp)
                                    ) {
                                        Text(text = "Скачать через браузер", fontSize = 12.5.sp)
                                    }
                                }

                                is DownloadStatus.Downloading, is DownloadStatus.Verifying -> {
                                    Button(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            UpdateDownloader.cancelDownload()
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color.Transparent,
                                            contentColor = Color(0xFFFF6B6B)
                                        ),
                                        border = BorderStroke(1.dp, Color(0xFFFF5252).copy(alpha = 0.6f)),
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier.fillMaxWidth().height(44.dp).springPress()
                                    ) {
                                        Text(
                                            text = "✕ Отменить загрузку",
                                            color = Color(0xFFFF6B6B),
                                            fontWeight = FontWeight.SemiBold,
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
