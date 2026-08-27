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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.core.ApkType
import com.mirrly.tgproxy.core.ReleaseApkAsset
import com.mirrly.tgproxy.core.ReleaseInfo
import com.mirrly.tgproxy.service.DownloadStatus
import com.mirrly.tgproxy.service.UpdateDownloader
import com.mirrly.tgproxy.service.UpdateManager
import com.mirrly.tgproxy.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private val ALL_APK_TYPES = listOf(
    ApkType.ARM64,
    ApkType.UNIVERSAL,
    ApkType.ARM_V7,
    ApkType.X86_64,
    ApkType.X86
)

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

    // ── Device Architecture Detection & Recommended Package Selection ──
    val supportedAbis = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Build.SUPPORTED_ABIS.toList()
        } else {
            @Suppress("DEPRECATION")
            listOf(Build.CPU_ABI)
        }
    }
    val devicePrimaryType = remember(supportedAbis) { ApkType.fromAbis(supportedAbis) }

    // ── Default Fallback Assets for v1.1.8.1 (Always available for reinstallation) ──
    val defaultReleaseAssets = remember {
        listOf(
            ReleaseApkAsset(
                name = "app-arm64-v8a-release.apk",
                downloadUrl = "https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/releases/download/v1.1.8.1/app-arm64-v8a-release.apk",
                sizeBytes = 6039029L,
                apkType = ApkType.ARM64,
                sha256 = "9811D053882B5C850D9BCF1E9E7E7F6D8650BDB9C30EAC489E6013AA005405FA"
            ),
            ReleaseApkAsset(
                name = "app-universal-release.apk",
                downloadUrl = "https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/releases/download/v1.1.8.1/app-universal-release.apk",
                sizeBytes = 15705021L,
                apkType = ApkType.UNIVERSAL,
                sha256 = "433426768208F55BE59F752D4D421BE906D73D0F1D6507690FCDB0220EAF0324"
            ),
            ReleaseApkAsset(
                name = "app-armeabi-v7a-release.apk",
                downloadUrl = "https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/releases/download/v1.1.8.1/app-armeabi-v7a-release.apk",
                sizeBytes = 4871487L,
                apkType = ApkType.ARM_V7,
                sha256 = "02C7D9882FB2238099E9EFD576005F4EADDC0C4FCFA330E164501EA0A27F46F7"
            ),
            ReleaseApkAsset(
                name = "app-x86_64-release.apk",
                downloadUrl = "https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/releases/download/v1.1.8.1/app-x86_64-release.apk",
                sizeBytes = 6381844L,
                apkType = ApkType.X86_64,
                sha256 = "2C7ECEDECBACF9E90917B1087F04BBD4ECE2AA756E18BADCEC6768B74281B6F4"
            ),
            ReleaseApkAsset(
                name = "app-x86-release.apk",
                downloadUrl = "https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/releases/download/v1.1.8.1/app-x86-release.apk",
                sizeBytes = 6275111L,
                apkType = ApkType.X86,
                sha256 = "BEC86D05116E81AD54086E13D3FBF7D7ADE73A1474600033BCF3F179B60E1426"
            )
        )
    }

    // ── Temporary Update Simulation Toggle (For Testing UI/Multi-APK) ──
    var isSimulatedUpdate by remember { mutableStateOf(false) }

    val activeReleaseInfo = remember(releaseInfo, isSimulatedUpdate, defaultReleaseAssets) {
        if (isSimulatedUpdate) {
            (releaseInfo ?: ReleaseInfo(
                tagName = "v1.1.9",
                versionName = "1.1.9",
                htmlUrl = "https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/releases",
                releaseNotes = "Тестовое обновление v1.1.9 для демонстрации интерфейса мульти-выбора APK и проверки работы алгоритмов.",
                isUpdateAvailable = true
            )).copy(
                isUpdateAvailable = true,
                versionName = if (releaseInfo?.versionName == "1.1.8.1" || releaseInfo?.versionName == null) "1.1.9" else releaseInfo.versionName,
                downloadUrl = releaseInfo?.downloadUrl ?: "https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/releases/download/v1.1.8.1/app-universal-release.apk",
                expectedSha256 = "6AB67F380035761041405B9D133A8CB383B8EF015A7E37AA1412A0323477C70E",
                expectedSha256List = listOf(
                    "6AB67F380035761041405B9D133A8CB383B8EF015A7E37AA1412A0323477C70E",
                    "940FD667930E8E1481B26F7A9B9B449A0F223CC6DF639B2385BDB8B01533DBC7",
                    "A8AD72E5E343541F69B03895E2B2936E5E653FD579994C804FD79D9151B930F1",
                    "17E59C1010CA297BE5F9BF45BAF79477FF9886622522A20D5B473EEA9D2A001E",
                    "869974037B85439A6B2944756A063C5BE57AA026E200C2C3FE7DDD4CFA07A413"
                ),
                apkAssets = if (releaseInfo?.apkAssets.isNullOrEmpty()) defaultReleaseAssets else releaseInfo!!.apkAssets
            )
        } else {
            releaseInfo
        }
    }

    val availableAssets = remember(activeReleaseInfo, defaultReleaseAssets) {
        if (!activeReleaseInfo?.apkAssets.isNullOrEmpty()) {
            activeReleaseInfo!!.apkAssets
        } else {
            defaultReleaseAssets
        }
    }

    var selectedApkType by remember(activeReleaseInfo, devicePrimaryType, availableAssets) {
        val matched = availableAssets.firstOrNull { it.apkType == devicePrimaryType }
            ?: availableAssets.firstOrNull { it.apkType == ApkType.UNIVERSAL }
            ?: availableAssets.firstOrNull()
        mutableStateOf(matched?.apkType ?: devicePrimaryType)
    }

    LaunchedEffect(Unit) {
        if (releaseInfo == null) {
            withContext(Dispatchers.IO) {
                com.mirrly.tgproxy.service.UpdateManager.checkForUpdates(context, notifyIfFound = false)
            }
        }
    }

    var pendingRedirectUrl by remember { mutableStateOf<String?>(null) }
    var showApkTypeDialog by remember { mutableStateOf(false) }

    if (pendingRedirectUrl != null) {
        ExternalLinkConfirmDialog(
            url = pendingRedirectUrl ?: "",
            onDismiss = { pendingRedirectUrl = null },
            onConfirmed = onBack
        )
    }

    if (showApkTypeDialog) {
        SelectApkTypeDialog(
            selectedType = selectedApkType,
            devicePrimaryType = devicePrimaryType,
            supportedAbis = supportedAbis,
            availableAssets = availableAssets,
            accentColor = if (activeReleaseInfo?.isUpdateAvailable == true) Color(0xFFFFB703) else ActiveGreenLed,
            isUpdateAvailable = activeReleaseInfo?.isUpdateAvailable == true,
            onSelect = { selectedApkType = it },
            onDismiss = { showApkTypeDialog = false }
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
                    // Refresh Glowing Badge Icon (Rendered strictly on GPU via graphicsLayer)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(58.dp)
                            .clip(CircleShape)
                            .background(Color.Transparent)
                            .graphicsLayer { alpha = glowAlpha }
                            .border(1.5.dp, ActiveGreenLed, CircleShape)
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
                        val isUpdateAvail = activeReleaseInfo?.isUpdateAvailable == true
                        val displayVer = if (isUpdateAvail) activeReleaseInfo?.versionName ?: currentAppVer else currentAppVer

                        Text(
                            text = "Версия v$displayVer",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Black,
                            color = TextWhite,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                // Secret developer toggle for simulating updates
                            }
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
                            text = if (isUpdateAvail) {
                                "Доступна новая официальная сборка на GitHub"
                            } else {
                                "У вас установлена последняя официальная версия. Если возникли проблемы, ошибки или сбои в работе, вы можете переустановить приложение."
                            },
                            fontSize = 12.sp,
                            color = TextMuted,
                            textAlign = TextAlign.Center,
                            lineHeight = 16.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }

                    // Expected SHA-256 Fingerprint Info Badge (Clickable to copy)
                    val expectedSha = activeReleaseInfo?.expectedSha256
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

            // ── 2. COMPACT APK ARCHITECTURE SELECTOR CARD ─────────────────────
            Column(
                modifier = Modifier.staggeredEntrance(index = 1),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (activeReleaseInfo?.isUpdateAvailable == true) "ВЫБОР ТИПА ПАКЕТА (APK)" else "ПАКЕТ ДЛЯ ПЕРЕУСТАНОВКИ",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.3.sp,
                        color = TextMuted
                    )
                    // Architecture detection tag
                    val primaryAbiStr = supportedAbis.firstOrNull() ?: "universal"
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Transparent)
                            .border(1.dp, ActiveGreenLed.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "CPU: ${primaryAbiStr.uppercase()}",
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = ActiveGreenLed
                        )
                    }
                }

                val currentSelectedAsset = availableAssets.firstOrNull { it.apkType == selectedApkType }
                val isCurrentRecommended = devicePrimaryType == selectedApkType
                val accentColor = if (activeReleaseInfo?.isUpdateAvailable == true) Color(0xFFFFB703) else ActiveGreenLed

                // Clickable Compact Cyber Card
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, accentColor.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            showApkTypeDialog = true
                        })
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Left Icon Badge
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(accentColor.copy(alpha = 0.10f))
                                .border(1.dp, accentColor.copy(alpha = 0.4f), CircleShape)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_shield),
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Center Info
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = selectedApkType.title,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = TextWhite
                                )
                                Text(
                                    text = "(${selectedApkType.shortName})",
                                    fontSize = 11.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = TextMuted
                                )
                                if (isCurrentRecommended) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(5.dp))
                                            .background(ActiveGreenLed.copy(alpha = 0.12f))
                                            .border(1.dp, ActiveGreenLed.copy(alpha = 0.5f), RoundedCornerShape(5.dp))
                                            .padding(horizontal = 5.dp, vertical = 1.dp)
                                    ) {
                                        Text(
                                            text = "РЕКОМЕНДОВАНО",
                                            fontSize = 7.5.sp,
                                            fontWeight = FontWeight.Black,
                                            color = ActiveGreenLed,
                                            letterSpacing = 0.4.sp
                                        )
                                    }
                                }
                            }

                            val sizeStr = if (currentSelectedAsset != null && currentSelectedAsset.sizeBytes > 0) {
                                " • " + String.format(Locale.US, "%.1f MB", currentSelectedAsset.sizeBytes / (1024f * 1024f))
                            } else ""

                            Text(
                                text = selectedApkType.targetDevices + sizeStr,
                                fontSize = 11.sp,
                                color = TextWhite.copy(alpha = 0.75f),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }

                        // Right Chip: "Выбрать ▾"
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color.White.copy(alpha = 0.05f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "Выбрать",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextWhite
                                )
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_arrow_down),
                                    contentDescription = null,
                                    tint = TextMuted,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                    }
                }
            }

            // ── 3. IN-APP DOWNLOAD & INSTALL DOCK ─────────────────────────────
            Column(
                modifier = Modifier.staggeredEntrance(index = 2),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val isAvail = activeReleaseInfo?.isUpdateAvailable == true

                Text(
                    text = if (isAvail) "УСТАНОВКА И ЗАГРУЗКА" else "ПЕРЕУСТАНОВКА И ВОССТАНОВЛЕНИЕ",
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
                        // Network & Reinstall Hint Banner (Transparent with clean border)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.Transparent)
                                .border(
                                    1.dp,
                                    if (isAvail) Color(0xFF1E283D) else ActiveGreenLed.copy(alpha = 0.25f),
                                    RoundedCornerShape(12.dp)
                                )
                                .padding(10.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = if (isAvail) R.drawable.ic_settings else R.drawable.ic_shield),
                                contentDescription = null,
                                tint = if (isAvail) TextMuted else ActiveGreenLed,
                                modifier = Modifier
                                    .size(14.dp)
                                    .padding(top = 2.dp)
                            )
                            Text(
                                text = if (isAvail) {
                                    "Обратите внимание: для скачивания обновления может потребоваться включение VPN или прокси в связи с возможной фильтрацией CDN GitHub (githubusercontent.com) операторами связи."
                                } else {
                                    "Переустановка актуальной версии позволяет восстановить целостность файлов и исправить возможные сбои без потери ваших настроек и воркеров."
                                },
                                fontSize = 11.5.sp,
                                color = if (isAvail) TextMuted else TextWhite.copy(alpha = 0.85f),
                                lineHeight = 15.sp
                            )
                        }

                        // Download Status Box (Stable, Zero-Flicker Layout)
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
                                            text = if (isAvail) "Скачивание файла APK..." else "Скачивание пакета для переустановки...",
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

                                    val speedStr = if (status.speedBytesPerSec > 0) {
                                        if (status.speedBytesPerSec >= 1024 * 1024) {
                                            String.format(Locale.US, "%.1f MB/s", status.speedBytesPerSec / (1024f * 1024f))
                                        } else {
                                            String.format(Locale.US, "%d KB/s", status.speedBytesPerSec / 1024)
                                        }
                                    } else null

                                    val etaStr = if (status.etaSeconds > 0) {
                                        if (status.etaSeconds < 60) "~${status.etaSeconds} сек"
                                        else "~${status.etaSeconds / 60} мин ${status.etaSeconds % 60} сек"
                                    } else null

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "$downloadedMbStr / $totalMbStr",
                                            fontSize = 11.5.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = TextMuted
                                        )

                                        if (speedStr != null || etaStr != null) {
                                            Text(
                                                text = listOfNotNull(speedStr, etaStr).joinToString(" • "),
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Medium,
                                                color = ActiveGreenLed
                                            )
                                        }
                                    }
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
                                    val currentAppVer = com.mirrly.tgproxy.BuildConfig.VERSION_NAME

                                    if (isAvail) {
                                        // Primary Button: Download & Install (Transparent with orange neon outline)
                                        Button(
                                            onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                val targetAsset = availableAssets.firstOrNull { it.apkType == selectedApkType }
                                                val url = targetAsset?.downloadUrl ?: activeReleaseInfo?.downloadUrl
                                                val expectedSha = targetAsset?.sha256 ?: activeReleaseInfo?.expectedSha256
                                                val shaList = if (!expectedSha.isNullOrBlank()) {
                                                    listOf(expectedSha) + activeReleaseInfo?.expectedSha256List.orEmpty()
                                                } else {
                                                    activeReleaseInfo?.expectedSha256List.orEmpty()
                                                }

                                                if (!url.isNullOrBlank()) {
                                                    coroutineScope.launch {
                                                        // Auto Force-Refresh: fetch fresh GitHub release notes without ETag (no-cache)
                                                        val freshCheck = UpdateManager.checkForUpdates(context, notifyIfFound = false, forceRefresh = true)
                                                        val freshInfo = freshCheck.getOrNull()
                                                        val freshAsset = freshInfo?.apkAssets?.firstOrNull { it.apkType == selectedApkType }
                                                        val finalUrl = freshAsset?.downloadUrl ?: url
                                                        val freshSha = freshAsset?.sha256 ?: freshInfo?.expectedSha256
                                                        val finalShaList = if (!freshSha.isNullOrBlank()) {
                                                            listOf(freshSha) + freshInfo?.expectedSha256List.orEmpty()
                                                        } else if (freshInfo != null && freshInfo.expectedSha256List.isNotEmpty()) {
                                                            freshInfo.expectedSha256List
                                                        } else {
                                                            shaList
                                                        }

                                                        UpdateDownloader.downloadAndVerifyApk(
                                                            context = context,
                                                            downloadUrl = finalUrl,
                                                            expectedSha256List = finalShaList,
                                                            versionName = freshInfo?.versionName ?: activeReleaseInfo?.versionName.orEmpty(),
                                                            fileName = freshAsset?.name ?: targetAsset?.name
                                                        )
                                                    }
                                                } else {
                                                    pendingRedirectUrl = activeReleaseInfo?.htmlUrl ?: "https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/releases"
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
                                                    text = "Скачать и установить ${selectedApkType.title} (v${activeReleaseInfo?.versionName ?: ""})",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.5.sp
                                                )
                                            }
                                        }
                                    } else {
                                        // Primary Button for Up-to-Date state: Reinstall Application
                                        Button(
                                            onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                val targetAsset = availableAssets.firstOrNull { it.apkType == selectedApkType }
                                                val url = targetAsset?.downloadUrl ?: activeReleaseInfo?.downloadUrl ?: "https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/releases/download/v1.1.8.1/app-universal-release.apk"
                                                val expectedSha = targetAsset?.sha256 ?: activeReleaseInfo?.expectedSha256
                                                val shaList = if (!expectedSha.isNullOrBlank()) {
                                                    listOf(expectedSha) + activeReleaseInfo?.expectedSha256List.orEmpty()
                                                } else {
                                                    activeReleaseInfo?.expectedSha256List.orEmpty()
                                                }

                                                if (!url.isNullOrBlank()) {
                                                    coroutineScope.launch {
                                                        // Auto Force-Refresh: fetch fresh GitHub release notes without ETag (no-cache)
                                                        val freshCheck = UpdateManager.checkForUpdates(context, notifyIfFound = false, forceRefresh = true)
                                                        val freshInfo = freshCheck.getOrNull()
                                                        val freshAsset = freshInfo?.apkAssets?.firstOrNull { it.apkType == selectedApkType }
                                                        val finalUrl = freshAsset?.downloadUrl ?: url
                                                        val freshSha = freshAsset?.sha256 ?: freshInfo?.expectedSha256
                                                        val finalShaList = if (!freshSha.isNullOrBlank()) {
                                                            listOf(freshSha) + freshInfo?.expectedSha256List.orEmpty()
                                                        } else if (freshInfo != null && freshInfo.expectedSha256List.isNotEmpty()) {
                                                            freshInfo.expectedSha256List
                                                        } else {
                                                            shaList
                                                        }

                                                        UpdateDownloader.downloadAndVerifyApk(
                                                            context = context,
                                                            downloadUrl = finalUrl,
                                                            expectedSha256List = finalShaList,
                                                            versionName = freshInfo?.versionName ?: currentAppVer,
                                                            fileName = freshAsset?.name ?: targetAsset?.name
                                                        )
                                                    }
                                                } else {
                                                    pendingRedirectUrl = activeReleaseInfo?.htmlUrl ?: "https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/releases"
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
                                                Icon(
                                                    painter = painterResource(id = R.drawable.ic_refresh),
                                                    contentDescription = null,
                                                    tint = ActiveGreenLed,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Text(
                                                    text = "Переустановить ${selectedApkType.title} (v$currentAppVer)",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.5.sp
                                                )
                                            }
                                        }

                                        // Secondary Button for Up-to-Date state: Check for Updates
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
                                                if (isManualChecking) {
                                                    CircularProgressIndicator(
                                                        modifier = Modifier.size(16.dp),
                                                        color = ActiveGreenLed,
                                                        strokeWidth = 2.dp
                                                    )
                                                } else {
                                                    Icon(
                                                        painter = painterResource(id = R.drawable.ic_refresh),
                                                        contentDescription = null,
                                                        tint = TextWhite,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                                Text(
                                                    text = if (isManualChecking) "Проверка..." else "Проверить обновления",
                                                    fontWeight = FontWeight.Medium,
                                                    fontSize = 13.sp
                                                )
                                            }
                                        }
                                    }

                                    // Secondary Button: Open on GitHub Releases
                                    Button(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            pendingRedirectUrl = activeReleaseInfo?.htmlUrl ?: "https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/releases"
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

                                    // Tertiary Button: Ignore / Skip this version
                                    val isIgnored = activeReleaseInfo?.isIgnored == true
                                    if (isAvail) {
                                        Button(
                                            onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                val ver = activeReleaseInfo?.versionName ?: ""
                                                if (isIgnored) {
                                                    com.mirrly.tgproxy.service.UpdateManager.unignoreVersion(context, ver)
                                                    Toast.makeText(context, "Напоминания для v$ver включены", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    com.mirrly.tgproxy.service.UpdateManager.ignoreVersion(context, ver)
                                                    Toast.makeText(context, "Версия v$ver скрыта", Toast.LENGTH_SHORT).show()
                                                }
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
                                                .springPress()
                                        ) {
                                            Text(
                                                text = if (isIgnored) "Вернуть напоминание об обновлении" else "Пропустить эту версию",
                                                fontWeight = FontWeight.Medium,
                                                fontSize = 12.5.sp,
                                                color = if (isIgnored) ActiveGreenLed else TextMuted
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
                                            text = if (canInstall) (if (isAvail) "Запустить установку обновления" else "Запустить переустановку приложения") else "Разрешить установку в Настройках",
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
                                            val targetAsset = availableAssets.firstOrNull { it.apkType == selectedApkType }
                                            val validUrl = targetAsset?.downloadUrl ?: activeReleaseInfo?.downloadUrl
                                            val expectedSha = targetAsset?.sha256 ?: activeReleaseInfo?.expectedSha256
                                            val shaList = if (!expectedSha.isNullOrBlank()) {
                                                listOf(expectedSha) + activeReleaseInfo?.expectedSha256List.orEmpty()
                                            } else {
                                                activeReleaseInfo?.expectedSha256List.orEmpty()
                                            }

                                            if (!validUrl.isNullOrBlank()) {
                                                coroutineScope.launch {
                                                    // Auto Force-Refresh on retry: fetch fresh GitHub release notes without ETag (no-cache)
                                                    val freshCheck = UpdateManager.checkForUpdates(context, notifyIfFound = false, forceRefresh = true)
                                                    val freshInfo = freshCheck.getOrNull()
                                                    val freshAsset = freshInfo?.apkAssets?.firstOrNull { it.apkType == selectedApkType }
                                                    val finalUrl = freshAsset?.downloadUrl ?: validUrl
                                                    val freshSha = freshAsset?.sha256 ?: freshInfo?.expectedSha256
                                                    val finalShaList = if (!freshSha.isNullOrBlank()) {
                                                        listOf(freshSha) + freshInfo?.expectedSha256List.orEmpty()
                                                    } else if (freshInfo != null && freshInfo.expectedSha256List.isNotEmpty()) {
                                                        freshInfo.expectedSha256List
                                                    } else {
                                                        shaList
                                                    }

                                                    UpdateDownloader.downloadAndVerifyApk(
                                                        context = context,
                                                        downloadUrl = finalUrl,
                                                        expectedSha256List = finalShaList,
                                                        versionName = freshInfo?.versionName ?: activeReleaseInfo?.versionName.orEmpty(),
                                                        fileName = freshAsset?.name ?: targetAsset?.name
                                                    )
                                                }
                                            } else {
                                                pendingRedirectUrl = activeReleaseInfo?.htmlUrl ?: "https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/releases"
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
                                            text = "Повторить загрузку ${selectedApkType.title}",
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

            // ── 4. 1-TO-1 GITHUB MARKDOWN CHANGELOG AREA ─────────────────────
            Column(
                modifier = Modifier.staggeredEntrance(index = 3),
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
                        val changelogMarkdown = activeReleaseInfo?.releaseNotes?.ifBlank {
                            "Официальный список изменений и релизные сборки доступны в репозитории на GitHub."
                        } ?: "Официальный список изменений загружается с GitHub..."

                        GithubMarkdownText(
                            markdownText = changelogMarkdown,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // ── 5. DISCREET DEVELOPER / DEBUG SIMULATION FOOTER ───────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, if (isSimulatedUpdate) Color(0xFFFFB703).copy(alpha = 0.55f) else Color(0xFF141926)),
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            isSimulatedUpdate = !isSimulatedUpdate
                            Toast.makeText(
                                context,
                                if (isSimulatedUpdate) "Включен тестовый режим: Доступно обновление v1.1.9" else "Тестовый режим отключен",
                                Toast.LENGTH_SHORT
                            ).show()
                        })
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_settings),
                            contentDescription = null,
                            tint = if (isSimulatedUpdate) Color(0xFFFFB703) else TextMuted.copy(alpha = 0.5f),
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = if (isSimulatedUpdate) "Тестовый режим v1.1.9 (нажмите для сброса)" else "Тестирование UI обновления",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (isSimulatedUpdate) Color(0xFFFFB703) else TextMuted.copy(alpha = 0.5f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
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
                        if (activeReleaseInfo?.isUpdateAvailable == true) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.Transparent)
                                    .border(1.dp, Color(0xFFFFB703).copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "NEW",
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Black,
                                    color = Color(0xFFFFB703)
                                )
                            }
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
                        pendingRedirectUrl = activeReleaseInfo?.htmlUrl ?: "https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/releases"
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

        // Delicate Cyber Particles floating over entire update screen interface
        CyberParticlesOverlay(
            modifier = Modifier.fillMaxSize(),
            particleCount = 42,
            alphaMultiplier = 0.70f
        )
    }
}

/**
 * Compact Frosted Dialog for Selecting APK Build Type (Architecture)
 */
@Composable
fun SelectApkTypeDialog(
    selectedType: ApkType,
    devicePrimaryType: ApkType,
    supportedAbis: List<String>,
    availableAssets: List<ReleaseApkAsset>,
    accentColor: Color,
    isUpdateAvailable: Boolean = true,
    onSelect: (ApkType) -> Unit,
    onDismiss: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    var currentChoice by remember(selectedType) { mutableStateOf(selectedType) }
    var expandedDetailsType by remember { mutableStateOf<ApkType?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        DialogBackdropBox(onDismiss = onDismiss) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 40.dp,
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp
                    )
                    .padding(horizontal = 20.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {}
            ) {
                // Category Pill
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = accentColor.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f))
                ) {
                    Text(
                        text = if (isUpdateAvailable) "ВЫБОР АРХИТЕКТУРЫ APK" else "ПАКЕТ ДЛЯ ПЕРЕУСТАНОВКИ",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = accentColor,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                }

                // Title
                Text(
                    text = if (isUpdateAvailable) "Тип установочного пакета" else "Выбор пакета для переустановки",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite,
                    textAlign = TextAlign.Center,
                    letterSpacing = 0.3.sp
                )

                // Architecture Auto-Detection Banner
                val primaryAbi = supportedAbis.firstOrNull() ?: "universal"
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color.White.copy(alpha = 0.04f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_shield),
                            contentDescription = null,
                            tint = ActiveGreenLed,
                            modifier = Modifier.size(18.dp)
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "CPU устройства: ${primaryAbi.uppercase()}",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = ActiveGreenLed
                            )
                            Text(
                                text = "Рекомендуется: ${devicePrimaryType.title} (${devicePrimaryType.abiName})",
                                fontSize = 11.5.sp,
                                color = TextWhite.copy(alpha = 0.85f)
                            )
                        }
                    }
                }

                // List of 5 APK Types (Pre-allocated singleton)
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ALL_APK_TYPES.forEach { type ->
                        val isSelected = currentChoice == type
                        val isRecommended = devicePrimaryType == type
                        val asset = availableAssets.firstOrNull { it.apkType == type }
                        val isExpanded = expandedDetailsType == type

                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (isSelected) accentColor.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.03f),
                            border = BorderStroke(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) accentColor else Color.White.copy(alpha = 0.10f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    currentChoice = type
                                }
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                // Header row: Radio, Title, Badges
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    // Custom Radio Indicator
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clip(CircleShape)
                                            .border(
                                                1.5.dp,
                                                if (isSelected) accentColor else TextMuted.copy(alpha = 0.6f),
                                                CircleShape
                                            )
                                    ) {
                                        if (isSelected) {
                                            Box(
                                                modifier = Modifier
                                                    .size(8.dp)
                                                    .clip(CircleShape)
                                                    .background(accentColor)
                                            )
                                        }
                                    }

                                    Text(
                                        text = type.title,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.5.sp,
                                        color = if (isSelected) TextWhite else TextWhite.copy(alpha = 0.9f)
                                    )

                                    Text(
                                        text = "(${type.abiName})",
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = TextMuted
                                    )

                                    Spacer(modifier = Modifier.weight(1f))

                                    if (isRecommended) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(ActiveGreenLed.copy(alpha = 0.12f))
                                                .border(1.dp, ActiveGreenLed.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = "РЕКОМЕНДОВАНО",
                                                fontSize = 8.5.sp,
                                                fontWeight = FontWeight.Black,
                                                color = ActiveGreenLed,
                                                letterSpacing = 0.4.sp
                                            )
                                        }
                                    }

                                    if (asset != null && asset.sizeBytes > 0) {
                                        val mbSize = String.format(Locale.US, "%.1f MB", asset.sizeBytes / (1024f * 1024f))
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(Color.White.copy(alpha = 0.05f))
                                                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                                                .padding(horizontal = 5.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = mbSize,
                                                fontSize = 9.5.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Medium,
                                                color = TextMuted
                                            )
                                        }
                                    }
                                }

                                // Target devices short summary
                                Text(
                                    text = type.targetDevices,
                                    fontSize = 11.5.sp,
                                    color = TextWhite.copy(alpha = 0.75f),
                                    lineHeight = 15.sp,
                                    modifier = Modifier.padding(start = 24.dp)
                                )

                                // Expandable Details (Description + SHA-256)
                                AnimatedVisibility(visible = isExpanded) {
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.padding(start = 24.dp, top = 4.dp)
                                    ) {
                                        Text(
                                            text = type.description,
                                            fontSize = 10.5.sp,
                                            color = TextMuted,
                                            lineHeight = 14.sp
                                        )

                                        val assetSha = asset?.sha256
                                        if (!assetSha.isNullOrBlank()) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(Color.Black.copy(alpha = 0.3f))
                                                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                                                    .clickable {
                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        try {
                                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                            val clip = ClipData.newPlainText("APK SHA-256", assetSha)
                                                            clipboard.setPrimaryClip(clip)
                                                            Toast.makeText(context, "SHA-256 для ${type.title} скопирован", Toast.LENGTH_SHORT).show()
                                                        } catch (_: Exception) {}
                                                    }
                                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Icon(
                                                    painter = painterResource(id = R.drawable.ic_shield),
                                                    contentDescription = null,
                                                    tint = if (isSelected) accentColor else TextMuted,
                                                    modifier = Modifier.size(12.dp)
                                                )
                                                Text(
                                                    text = "SHA-256:",
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = TextMuted
                                                )
                                                Text(
                                                    text = assetSha,
                                                    fontSize = 9.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = if (isSelected) accentColor else TextMuted,
                                                    maxLines = 1,
                                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f)
                                                )
                                            }
                                        }
                                    }
                                }

                                // Details Toggle Button (Micro chip)
                                Row(
                                    modifier = Modifier
                                        .padding(start = 24.dp)
                                        .clickable {
                                            expandedDetailsType = if (isExpanded) null else type
                                        },
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = if (isExpanded) "Скрыть подробности" else "Подробнее об архитектуре",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = accentColor.copy(alpha = 0.9f)
                                    )
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_arrow_down),
                                        contentDescription = null,
                                        tint = accentColor.copy(alpha = 0.9f),
                                        modifier = Modifier.size(10.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Action Buttons: Apply / Close
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Apply Button
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.5.dp, accentColor),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .springPress(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onSelect(currentChoice)
                                onDismiss()
                            })
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = if (isUpdateAvailable) "Выбрать ${currentChoice.title}" else "Выбрать ${currentChoice.title} для переустановки",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = accentColor
                            )
                        }
                    }

                    // Cancel / Dismiss Button
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, Color(0xFF1E283D)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .springPress(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onDismiss()
                            })
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "Закрыть",
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.sp,
                                color = TextMuted
                            )
                        }
                    }
                }
            }
        }
    }
}
