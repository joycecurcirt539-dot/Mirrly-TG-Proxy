package com.mirrly.tgproxy.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
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

    // Infinite breathing animation for cyber glow badge
    val infiniteTransition = rememberInfiniteTransition(label = "updateGlow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // 1. SCROLLABLE CONTENT LAYER (Scrolls all the way under frosted top bar)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .fadingEdges(topFadeHeight = 24.dp, bottomFadeHeight = 44.dp)
                .verticalScroll(scrollState)
                .padding(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 20.dp
                )
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {

            // ── 1. HERO RELEASE CARD (Seamless Transparent Cyber Glass) ───────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .staggeredEntrance(index = 0)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color.Transparent)
                    .border(1.dp, Color(0xFF181E2E), RoundedCornerShape(22.dp))
                    .lightSweep(
                        isEnabled = true,
                        shape = RoundedCornerShape(22.dp),
                        borderWidth = 1.dp,
                        sweepColor = ActiveGreenLed
                    )
                    .padding(22.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Refresh Glowing Badge Icon
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(58.dp)
                            .clip(CircleShape)
                            .background(Color.Transparent)
                            .border(1.5.dp, ActiveGreenLed.copy(alpha = glowAlpha), CircleShape)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_refresh),
                            contentDescription = null,
                            tint = ActiveGreenLed,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val currentAppVer = com.mirrly.tgproxy.BuildConfig.VERSION_NAME
                        val isUpdateAvail = releaseInfo?.isUpdateAvailable == true
                        val displayVer = if (isUpdateAvail) releaseInfo?.versionName ?: currentAppVer else currentAppVer

                        Text(
                            text = "Версия v$displayVer",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Black,
                            color = TextWhite,
                            textAlign = TextAlign.Center
                        )

                        // Release Tag Badge
                        val badgeText = if (isUpdateAvail) "ДОСТУПНО ОБНОВЛЕНИЕ" else "УСТАНОВЛЕНА АКТУАЛЬНАЯ ВЕРСИЯ"
                        val badgeColor = if (isUpdateAvail) Color(0xFFFFB703) else ActiveGreenLed

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Transparent)
                                .border(1.dp, badgeColor.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = badgeText,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Black,
                                color = badgeColor,
                                letterSpacing = 0.8.sp
                            )
                        }

                        Text(
                            text = if (isUpdateAvail) "Доступна новая официальная сборка на GitHub" else "У вас установлена последняя официальная версия Mirrly TG Proxy",
                            fontSize = 12.sp,
                            color = TextMuted,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }

                    // Expected SHA-256 Fingerprint Info Badge (Clickable to copy)
                    val expectedSha = releaseInfo?.expectedSha256
                    if (!expectedSha.isNullOrBlank()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.Transparent)
                                .border(1.dp, ActiveGreenLed.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    try {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = ClipData.newPlainText("SHA256 Fingerprint", expectedSha)
                                        clipboard.setPrimaryClip(clip)
                                        Toast.makeText(context, "SHA-256 скопирован в буфер обмена", Toast.LENGTH_SHORT).show()
                                    } catch (_: Exception) {}
                                }
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_shield),
                                contentDescription = null,
                                tint = ActiveGreenLed,
                                modifier = Modifier.size(16.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Официальный SHA-256 отпечаток:",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = TextMuted
                                )
                                Text(
                                    text = expectedSha,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    color = ActiveGreenLed,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }

            // ── 2. IN-APP DOWNLOAD & INSTALL DOCK ─────────────────────────────
            Column(
                modifier = Modifier.staggeredEntrance(index = 1),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "УСТАНОВКА И ЗАГРУЗКА",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.3.sp,
                    color = TextMuted
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Transparent)
                        .border(1.dp, Color(0xFF181E2E), RoundedCornerShape(20.dp))
                        .padding(18.dp)
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // Network & VPN Hint Banner (Transparent with clean border)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.Transparent)
                                .border(1.dp, Color(0xFF1E283D), RoundedCornerShape(12.dp))
                                .padding(10.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_settings),
                                contentDescription = null,
                                tint = TextMuted,
                                modifier = Modifier
                                    .size(14.dp)
                                    .padding(top = 2.dp)
                            )
                            Text(
                                text = "Обратите внимание: для скачивания обновления может потребоваться включение VPN или прокси в связи с возможной фильтрацией CDN GitHub (githubusercontent.com) операторами связи.",
                                fontSize = 11.5.sp,
                                color = TextMuted,
                                lineHeight = 15.sp
                            )
                        }

                        // Download Status Box (Dynamic State Transitions)
                        when (val status = downloadStatus) {
                            is DownloadStatus.Downloading -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Color.Transparent)
                                        .border(1.dp, ActiveGreenLed.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
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
                                            fontWeight = FontWeight.SemiBold,
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
                                        .background(Color.Transparent)
                                        .border(1.dp, ActiveGreenLed.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                                        .padding(14.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        color = ActiveGreenLed,
                                        strokeWidth = 2.dp
                                    )
                                    Text(
                                        text = "Проверка SHA-256 отпечатка и цифровой подписи...",
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
                                        .border(1.dp, Color(0xFFFF5252).copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                                        .padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
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
                                        fontSize = 12.sp,
                                        lineHeight = 16.sp
                                    )

                                    Button(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            val url = releaseInfo?.downloadUrl ?: "https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/releases/latest"
                                            pendingRedirectUrl = url
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(38.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color.Transparent,
                                            contentColor = TextWhite
                                        ),
                                        border = BorderStroke(1.dp, Color(0xFF1E283D)),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Text(
                                            text = "Скачать через браузер",
                                            color = TextWhite,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }

                            is DownloadStatus.ReadyToInstall -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Color.Transparent)
                                        .border(1.dp, ActiveGreenLed.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                                        .padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = "✓ Файл верифицирован (SHA-256 OK)",
                                        color = ActiveGreenLed,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.5.sp
                                    )
                                    Text(
                                        text = "Нажмите кнопку ниже для запуска установки пакета Android.",
                                        color = TextWhite.copy(alpha = 0.85f),
                                        fontSize = 12.sp,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }

                            DownloadStatus.Idle -> {
                                // Clean Idle State
                            }
                        }

                        // ── Main Action Buttons (All Transparent, No Backgrounds) ──
                        var isManualChecking by remember { mutableStateOf(false) }

                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            when (val status = downloadStatus) {
                                DownloadStatus.Idle -> {
                                    val isAvail = releaseInfo?.isUpdateAvailable == true

                                    if (isAvail) {
                                        // Primary Button: Download & Install (Transparent with neon outline)
                                        Button(
                                            onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                val info = releaseInfo
                                                val rawUrl = info?.downloadUrl
                                                if (!rawUrl.isNullOrBlank() && info != null) {
                                                    val validUrl = rawUrl
                                                    coroutineScope.launch {
                                                        UpdateDownloader.downloadAndVerifyApk(
                                                            context = context,
                                                            downloadUrl = validUrl,
                                                            expectedSha256List = info.expectedSha256List,
                                                            versionName = info.versionName
                                                        )
                                                    }
                                                } else {
                                                    pendingRedirectUrl = info?.htmlUrl ?: "https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/releases"
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = Color.Transparent,
                                                contentColor = Color(0xFFFFB703)
                                            ),
                                            border = BorderStroke(1.5.dp, Color(0xFFFFB703)),
                                            shape = RoundedCornerShape(14.dp),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(48.dp)
                                                .springPress()
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Icon(
                                                    painter = painterResource(id = R.drawable.ic_arrow_down),
                                                    contentDescription = null,
                                                    tint = Color(0xFFFFB703),
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Text(
                                                    text = "Скачать и установить v${releaseInfo?.versionName ?: ""}",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 14.sp
                                                )
                                            }
                                        }
                                    } else {
                                        // Primary Button for Up-to-Date state: Check for Updates
                                        Button(
                                            onClick = {
                                                if (isManualChecking) return@Button
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                isManualChecking = true
                                                coroutineScope.launch {
                                                    val res = com.mirrly.tgproxy.service.UpdateManager.checkForUpdates(
                                                        context,
                                                        notifyIfFound = false,
                                                        forceRefresh = true
                                                    )
                                                    isManualChecking = false
                                                    res.fold(
                                                        onSuccess = { info ->
                                                            if (!info.isUpdateAvailable) {
                                                                Toast.makeText(
                                                                    context,
                                                                    "У вас установлена актуальная версия",
                                                                    Toast.LENGTH_SHORT
                                                                ).show()
                                                            }
                                                        },
                                                        onFailure = { err ->
                                                            Toast.makeText(
                                                                context,
                                                                "Ошибка проверки: ${err.localizedMessage}",
                                                                Toast.LENGTH_SHORT
                                                            ).show()
                                                        }
                                                    )
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
                                                .height(48.dp)
                                                .springPress()
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                if (isManualChecking) {
                                                    CircularProgressIndicator(
                                                        modifier = Modifier.size(18.dp),
                                                        color = ActiveGreenLed,
                                                        strokeWidth = 2.dp
                                                    )
                                                } else {
                                                    Icon(
                                                        painter = painterResource(id = R.drawable.ic_refresh),
                                                        contentDescription = null,
                                                        tint = ActiveGreenLed,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                                Text(
                                                    text = if (isManualChecking) "Проверка..." else "Проверить обновления",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 14.sp
                                                )
                                            }
                                        }
                                    }

                                    // Secondary Button: Open on GitHub Releases
                                    Button(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            pendingRedirectUrl = releaseInfo?.htmlUrl ?: "https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/releases"
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color.Transparent,
                                            contentColor = TextWhite
                                        ),
                                        border = BorderStroke(1.dp, Color(0xFF1E283D)),
                                        shape = RoundedCornerShape(14.dp),
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
                                                text = "Открыть релиз на GitHub",
                                                fontWeight = FontWeight.Medium,
                                                fontSize = 13.sp
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
                                            containerColor = Color.Transparent,
                                            contentColor = ActiveGreenLed
                                        ),
                                        border = BorderStroke(1.5.dp, ActiveGreenLed),
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .springPress()
                                    ) {
                                        Text(
                                            text = if (canInstall) "Запустить установку" else "Разрешить установку в Настройках",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        )
                                    }

                                    Button(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            UpdateDownloader.resetStatus()
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color.Transparent,
                                            contentColor = TextMuted
                                        ),
                                        border = BorderStroke(1.dp, Color(0xFF1E283D)),
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(40.dp)
                                    ) {
                                        Text(
                                            text = "Сбросить статус",
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }

                                is DownloadStatus.Error -> {
                                    Button(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            val info = releaseInfo
                                            val rawUrl = info?.downloadUrl
                                            if (info != null && !rawUrl.isNullOrBlank()) {
                                                val validUrl = rawUrl
                                                coroutineScope.launch {
                                                    UpdateDownloader.downloadAndVerifyApk(
                                                        context = context,
                                                        downloadUrl = validUrl,
                                                        expectedSha256List = info.expectedSha256List,
                                                        versionName = info.versionName
                                                    )
                                                }
                                            } else {
                                                pendingRedirectUrl = info?.htmlUrl ?: "https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/releases"
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
                                            .height(48.dp)
                                            .springPress()
                                    ) {
                                        Text(
                                            text = "Повторить загрузку",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        )
                                    }
                                }

                                is DownloadStatus.Downloading, is DownloadStatus.Verifying -> {
                                    // Cancel Download Button (Reliable Immediate Network Abort)
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
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(46.dp)
                                            .springPress()
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = "✕",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp,
                                                color = Color(0xFFFF6B6B)
                                            )
                                            Text(
                                                text = "Отменить загрузку",
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 13.5.sp,
                                                color = Color(0xFFFF6B6B)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── 3. 1-TO-1 GITHUB MARKDOWN CHANGELOG AREA ─────────────────────
            Column(
                modifier = Modifier.staggeredEntrance(index = 2),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "СПИСОК ИЗМЕНЕНИЙ (CHANGELOG)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.3.sp,
                    color = TextMuted
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Transparent)
                        .border(1.dp, Color(0xFF181E2E), RoundedCornerShape(20.dp))
                        .padding(18.dp)
                ) {
                    Column(
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
                                    .background(Color.Transparent)
                                    .border(1.dp, ActiveGreenLed.copy(alpha = 0.4f), CircleShape)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_github),
                                    contentDescription = null,
                                    tint = ActiveGreenLed,
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            Text(
                                text = "Что нового в этой версии",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = TextWhite
                            )
                        }

                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF161A26)))

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
            }

            Spacer(modifier = Modifier.height(20.dp))
        }

        // 2. FROSTED GLASS HEADER PANEL (Pinned at Top over scrolling cards)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.98f),
                            Color.Black.copy(alpha = 0.94f),
                            Color.Black.copy(alpha = 0.72f),
                            Color.Black.copy(alpha = 0.00f)
                        )
                    )
                )
        ) {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Обновление",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = TextWhite,
                            maxLines = 1,
                            softWrap = false,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.Transparent)
                                .border(1.dp, ActiveGreenLed.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "NEW",
                                fontSize = 9.5.sp,
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
                            modifier = Modifier.size(22.dp)
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    }
}
