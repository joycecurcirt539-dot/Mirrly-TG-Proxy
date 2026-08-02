package com.mirrly.tgproxy.ui

import android.os.Build
import androidx.compose.animation.*
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.core.ReleaseInfo
import com.mirrly.tgproxy.service.DownloadStatus
import com.mirrly.tgproxy.service.UpdateDownloader
import com.mirrly.tgproxy.ui.theme.*
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateScreen(
    releaseInfo: ReleaseInfo?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val downloadStatus by UpdateDownloader.status.collectAsState()

    var pendingRedirectUrl by remember { mutableStateOf<String?>(null) }

    if (pendingRedirectUrl != null) {
        ExternalLinkConfirmDialog(
            url = pendingRedirectUrl ?: "",
            onDismiss = { pendingRedirectUrl = null },
            onConfirmed = onBack
        )
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Обновление системы",
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = TextWhite
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(ActiveGreenLed.copy(alpha = 0.2f))
                                .border(1.dp, ActiveGreenLed.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "NEW",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black,
                                color = ActiveGreenLed
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onBack()
                    }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_arrow_left),
                            contentDescription = "Назад",
                            tint = TextWhite,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        pendingRedirectUrl = releaseInfo?.htmlUrl ?: "https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/releases"
                    }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_github),
                            contentDescription = "GitHub",
                            tint = TextWhite,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = Color.Transparent
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // ── 1. CYBER HERO HEADER CARD ───────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color(0xEE0E1624),
                                Color(0xD9090D16)
                            )
                        )
                    )
                    .border(
                        width = 1.dp,
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                ActiveGreenLed.copy(alpha = 0.6f),
                                AmoledBorder,
                                ActiveGreenLed.copy(alpha = 0.4f)
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
                    .padding(20.dp)
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Refresh Badge Icon
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(60.dp)
                            .clip(CircleShape)
                            .background(ActiveGreenLed.copy(alpha = 0.15f))
                            .border(1.5.dp, ActiveGreenLed.copy(alpha = 0.5f), CircleShape)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_refresh),
                            contentDescription = null,
                            tint = ActiveGreenLed,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Версия v${releaseInfo?.versionName ?: "1.0.7"}",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "Официальный релиз приложения Mirrly TG Proxy",
                            fontSize = 12.5.sp,
                            color = TextMuted,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }

                    // Expected SHA-256 Fingerprint Info Badge
                    if (!releaseInfo?.expectedSha256.isNullOrBlank()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF090D14))
                                .border(1.dp, AmoledBorder, RoundedCornerShape(10.dp))
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_settings),
                                contentDescription = null,
                                tint = ActiveGreenLed,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "SHA-256: ${releaseInfo?.expectedSha256}",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = ActiveGreenLed.copy(alpha = 0.9f),
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            // ── 2. IN-APP DOWNLOAD & INSTALL DOCK ──────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xEE0B101A)),
                border = BorderStroke(1.dp, AmoledBorder)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Установка обновления",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = TextWhite
                    )

                    when (val status = downloadStatus) {
                        is DownloadStatus.Downloading -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color(0xFF0D1422))
                                    .border(1.dp, ActiveGreenLed.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Скачивание файла APK...",
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

                                LinearProgressIndicator(
                                    progress = { if (status.progress >= 0f) status.progress else 0f },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp)),
                                    color = ActiveGreenLed,
                                    trackColor = Color.White.copy(alpha = 0.1f)
                                )

                                val downloadedMbStr = String.format(Locale.US, "%.1f", status.downloadedBytes / (1024f * 1024f))
                                val totalMbStr = if (status.totalBytes > 0) {
                                    String.format(Locale.US, "%.1f MB", status.totalBytes / (1024f * 1024f))
                                } else "..."

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
                                    .background(Color(0xFF0D1422))
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
                                    text = "Проверка SHA-256 отпечатка сборки...",
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
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = "Нажмите кнопку ниже для запуска установки Android.",
                                    color = TextWhite.copy(alpha = 0.85f),
                                    fontSize = 12.sp
                                )
                            }
                        }

                        DownloadStatus.Idle -> {
                            // Standard state
                        }
                    }

                    // Main Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        when (val status = downloadStatus) {
                            DownloadStatus.Idle -> {
                                Button(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        val url = releaseInfo?.downloadUrl
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
                                            pendingRedirectUrl = releaseInfo?.htmlUrl
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = ActiveGreenLed,
                                        contentColor = Color.Black
                                    ),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
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
                            }

                            is DownloadStatus.ReadyToInstall -> {
                                val canInstall = remember(context) { UpdateDownloader.canInstallPackages(context) }
                                Button(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        if (UpdateDownloader.canInstallPackages(context)) {
                                            UpdateDownloader.triggerInstall(context, status.file)
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
                                        .weight(1f)
                                        .height(48.dp)
                                ) {
                                    Text(
                                        text = if (canInstall) "Запустить установку" else "Разрешить установку в Настройках",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                }
                            }

                            is DownloadStatus.Error -> {
                                Button(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        val url = releaseInfo?.downloadUrl
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
                                            pendingRedirectUrl = releaseInfo?.htmlUrl
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = ActiveGreenLed,
                                        contentColor = Color.Black
                                    ),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                ) {
                                    Text(
                                        text = "Повторить загрузку",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                }
                            }

                            is DownloadStatus.Downloading, is DownloadStatus.Verifying -> {
                                OutlinedButton(
                                    onClick = { UpdateDownloader.resetStatus() },
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextWhite),
                                    modifier = Modifier.weight(1f).height(48.dp)
                                ) {
                                    Text(text = "Отмена загрузки", fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }

            // ── 3. 1-TO-1 GITHUB MARKDOWN CHANGELOG AREA ─────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xEE0B101A)),
                border = BorderStroke(1.dp, AmoledBorder)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(ActiveGreenLed.copy(alpha = 0.15f))
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_github),
                                contentDescription = null,
                                tint = ActiveGreenLed,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Text(
                            text = "Что нового на GitHub",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = TextWhite
                        )
                    }

                    HorizontalDivider(color = AmoledBorder, thickness = 1.dp)

                    // 1-to-1 GitHub Markdown Content Renderer
                    val changelogMarkdown = releaseInfo?.releaseNotes?.ifBlank {
                        "Официальный список изменений и релизные сборки доступны в репозитории на GitHub."
                    } ?: "Официальный список изменений загружается с GitHub..."

                    GithubMarkdownText(
                        markdownText = changelogMarkdown,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(modifier = Modifier.height(30.dp))
        }
    }
}
