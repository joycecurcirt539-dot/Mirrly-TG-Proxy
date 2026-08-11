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
        LaunchedEffect(Unit) {
            val window = (view.parent as? DialogWindowProvider)?.window
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                window?.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window?.attributes = window?.attributes?.apply {
                    blurBehindRadius = 50
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.12f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    if (downloadStatus !is DownloadStatus.Downloading) {
                        UpdateDownloader.cancelDownload()
                        onDismiss()
                    }
                }
                .padding(horizontal = 24.dp)
        ) {
            // Detailed Release & Changelog Info with Smooth Fading Edges
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .fadingEdges(topFadeHeight = 32.dp, bottomFadeHeight = 64.dp)
                    .padding(bottom = 220.dp, top = 24.dp)
                    .verticalScroll(rememberScrollState())
                    .clickable(enabled = false) {}
            ) {
                // Category Pills Row
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0xFF00F0FF).copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, Color(0xFF00F0FF).copy(alpha = 0.35f))
                    ) {
                        Text(
                            text = "ОФИЦИАЛЬНОЕ ОБНОВЛЕНИЕ",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00F0FF),
                            letterSpacing = 0.8.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = ActiveGreenLed.copy(alpha = 0.18f),
                        border = BorderStroke(1.dp, ActiveGreenLed.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = "v${releaseInfo.versionName}",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            color = ActiveGreenLed,
                            letterSpacing = 0.8.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                // Main Title
                Text(
                    text = "Доступна новая версия v${releaseInfo.versionName}",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite,
                    textAlign = TextAlign.Center,
                    letterSpacing = 0.3.sp
                )

                // Card 1: Main Overview Card (Glass)
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White.copy(alpha = 0.04f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Вышло официальное обновление Mirrly TG Proxy. Вы можете скачать и установить новый APK прямо в приложении или ознакомиться со сборкой на GitHub.",
                        fontSize = 13.sp,
                        color = TextWhite.copy(alpha = 0.88f),
                        textAlign = TextAlign.Start,
                        lineHeight = 19.sp,
                        modifier = Modifier.padding(14.dp)
                    )
                }

                // Card 2: Formatted GitHub Markdown Changelog Cards
                val changelogText = releaseInfo.releaseNotes.ifBlank {
                    "Подробный список изменений и официальные релизные сборки доступны в репозитории проекта на GitHub."
                }

                FormattedChangelogBody(markdown = changelogText)

                // Card 3: In-App Download Status / Progress Content (Glass Card)
                when (val status = downloadStatus) {
                    is DownloadStatus.Downloading -> {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = ActiveGreenLed.copy(alpha = 0.08f),
                            border = BorderStroke(1.dp, ActiveGreenLed.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
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
                    }

                    is DownloadStatus.Verifying -> {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Color(0xFF00F0FF).copy(alpha = 0.08f),
                            border = BorderStroke(1.dp, Color(0xFF00F0FF).copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = Color(0xFF00F0FF),
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
                    }

                    is DownloadStatus.Error -> {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Color(0xFFFF3D00).copy(alpha = 0.08f),
                            border = BorderStroke(1.dp, Color(0xFFFF3D00).copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "ОШИБКА ЗАГРУЗКИ",
                                    color = Color(0xFFFF3D00),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                                Text(
                                    text = status.message,
                                    color = TextWhite.copy(alpha = 0.9f),
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp
                                )
                            }
                        }
                    }

                    is DownloadStatus.ReadyToInstall -> {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = ActiveGreenLed.copy(alpha = 0.08f),
                            border = BorderStroke(1.dp, ActiveGreenLed.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "ГОТОВО К УСТАНОВКЕ",
                                    color = ActiveGreenLed,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                                Text(
                                    text = "Нажмите кнопку ниже для запуска установки.",
                                    color = TextWhite.copy(alpha = 0.88f),
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    DownloadStatus.Idle -> {}
                }
            }

            // Bottom Action Buttons Dock (Floating seamlessly over blurred background)
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp)
                    .fillMaxWidth()
                    .clickable(enabled = false) {}
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
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF00F0FF).copy(alpha = 0.20f),
                                contentColor = Color(0xFF00F0FF)
                            ),
                            border = BorderStroke(1.dp, Color(0xFF00F0FF).copy(alpha = 0.50f)),
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
                                    tint = Color(0xFF00F0FF),
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "Скачать и установить",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.5.sp
                                )
                            }
                        }

                        // Secondary Button - Browser GitHub
                        OutlinedButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                pendingRedirectUrl = releaseInfo.htmlUrl
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
                            Text(
                                text = if (canInstall) "Установить обновление" else "Разрешить установку в Настройках",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.5.sp
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
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF00F0FF).copy(alpha = 0.20f),
                                contentColor = Color(0xFF00F0FF)
                            ),
                            border = BorderStroke(1.dp, Color(0xFF00F0FF).copy(alpha = 0.50f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .springPress()
                        ) {
                            Text(
                                text = "Повторить загрузку",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.5.sp
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                pendingRedirectUrl = releaseInfo.htmlUrl
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
                            Text(text = "Скачать через браузер", fontSize = 13.sp)
                        }
                    }

                    is DownloadStatus.Downloading, is DownloadStatus.Verifying -> {
                        Button(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                UpdateDownloader.cancelDownload()
                            },
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFF3D00).copy(alpha = 0.18f),
                                contentColor = Color(0xFFFF3D00)
                            ),
                            border = BorderStroke(1.dp, Color(0xFFFF3D00).copy(alpha = 0.50f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .springPress()
                        ) {
                            Text(
                                text = "Отменить загрузку",
                                color = Color(0xFFFF3D00),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.5.sp
                            )
                        }
                    }
                }
            }
        }
    }
}


/**
 * Rich GitHub Release Markdown Formatter.
 * Parses headers (#, ##, ###), bold titles (**Title**: Description), horizontal dividers (---),
 * bullet lists (*, -, •), and SHA-256 fingerprints into structured glassmorphic cards with cyan/amber accents.
 */
@Composable
fun FormattedChangelogBody(
    markdown: String,
    modifier: Modifier = Modifier
) {
    val parsedBlocks = remember(markdown) { parseGitHubMarkdown(markdown) }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        parsedBlocks.forEach { block ->
            when (block) {
                is ChangelogBlock.Section -> {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = block.bgColor,
                        border = BorderStroke(1.dp, block.borderColor),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (block.title.isNotEmpty()) {
                                Text(
                                    text = block.title,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = block.titleColor,
                                    letterSpacing = 0.5.sp,
                                    textAlign = TextAlign.Start
                                )
                            }
                            block.items.forEach { item ->
                                when (item) {
                                    is ChangelogItem.Bullet -> {
                                        ChangelogBulletRow(bulletTitle = item.title, text = item.text)
                                    }
                                    is ChangelogItem.Paragraph -> {
                                        Text(
                                            text = item.text,
                                            fontSize = 12.5.sp,
                                            color = TextWhite.copy(alpha = 0.88f),
                                            lineHeight = 18.5.sp,
                                            textAlign = TextAlign.Start
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

@Composable
private fun ChangelogBulletRow(bulletTitle: String, text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = "•",
            color = Color(0xFF00F0FF),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.weight(1f)
        ) {
            if (bulletTitle.isNotEmpty()) {
                Text(
                    text = bulletTitle,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite,
                    textAlign = TextAlign.Start,
                    lineHeight = 18.sp
                )
            }
            if (text.isNotEmpty()) {
                Text(
                    text = text,
                    fontSize = 12.sp,
                    color = TextWhite.copy(alpha = 0.85f),
                    textAlign = TextAlign.Start,
                    lineHeight = 17.5.sp
                )
            }
        }
    }
}

private sealed class ChangelogBlock {
    data class Section(
        val title: String,
        val items: List<ChangelogItem>,
        val titleColor: Color = Color(0xFF00F0FF),
        val bgColor: Color = Color(0xFF00F0FF).copy(alpha = 0.06f),
        val borderColor: Color = Color(0xFF00F0FF).copy(alpha = 0.25f)
    ) : ChangelogBlock()
}

private sealed class ChangelogItem {
    data class Bullet(val title: String, val text: String) : ChangelogItem()
    data class Paragraph(val text: String) : ChangelogItem()
}

private fun parseGitHubMarkdown(markdown: String): List<ChangelogBlock> {
    if (markdown.isBlank()) return emptyList()

    val rawLines = markdown.lines()
    val blocks = mutableListOf<ChangelogBlock>()

    var currentTitle = ""
    var currentItems = mutableListOf<ChangelogItem>()

    fun commitCurrentSection() {
        if (currentTitle.isNotEmpty() || currentItems.isNotEmpty()) {
            val isShaBlock = currentTitle.contains("SHA-256") || currentTitle.contains("отпечатки") || currentTitle.contains("подписи")
            val isAmber = isShaBlock || currentTitle.contains("ВНИМАНИЕ")

            val titleCol = if (isAmber) Color(0xFFFF9E00) else Color(0xFF00F0FF)
            val bgCol = if (isAmber) Color(0xFFFF9E00).copy(alpha = 0.08f) else Color(0xFF00F0FF).copy(alpha = 0.06f)
            val borderCol = if (isAmber) Color(0xFFFF9E00).copy(alpha = 0.30f) else Color(0xFF00F0FF).copy(alpha = 0.25f)

            blocks.add(
                ChangelogBlock.Section(
                    title = currentTitle,
                    items = currentItems.toList(),
                    titleColor = titleCol,
                    bgColor = bgCol,
                    borderColor = borderCol
                )
            )
            currentTitle = ""
            currentItems = mutableListOf()
        }
    }

    for (line in rawLines) {
        val trimmed = line.trim()
        if (trimmed == "---" || trimmed == "***") {
            commitCurrentSection()
            continue
        }

        if (trimmed.startsWith("#")) {
            commitCurrentSection()
            var cleanHeader = trimmed
            while (cleanHeader.startsWith("#")) {
                cleanHeader = cleanHeader.removePrefix("#")
            }
            cleanHeader = cleanHeader.trim().removePrefix("**").removeSuffix("**").trim()
            currentTitle = cleanHeader
            continue
        }

        if (trimmed.startsWith("* ") || trimmed.startsWith("- ") || trimmed.startsWith("• ")) {
            val bulletContent = trimmed.substring(2).trim()
            if (bulletContent.contains("**")) {
                val parts = bulletContent.split("**")
                if (parts.size >= 3) {
                    val bTitle = parts[1].trim().removeSuffix(":")
                    val bText = parts.subList(2, parts.size).joinToString("").trim().removePrefix(":").trim()
                    currentItems.add(ChangelogItem.Bullet(title = bTitle, text = bText))
                } else {
                    val cleaned = bulletContent.replace("**", "").replace("`", "")
                    currentItems.add(ChangelogItem.Paragraph(text = cleaned))
                }
            } else {
                currentItems.add(ChangelogItem.Paragraph(text = bulletContent))
            }
        } else if (trimmed.isNotEmpty()) {
            val cleaned = trimmed.replace("**", "").replace("`", "")
            currentItems.add(ChangelogItem.Paragraph(text = cleaned))
        }
    }

    commitCurrentSection()
    return blocks
}
