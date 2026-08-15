package com.mirrly.tgproxy.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.ui.theme.*
import com.mirrly.tgproxy.util.SignatureStatus
import com.mirrly.tgproxy.util.SignatureVerifier
import kotlinx.coroutines.launch

@Composable
fun OfficialSourceCard(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val repoUrl = "https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy"

    val currentUpdateInfo by com.mirrly.tgproxy.service.UpdateManager.updateState.collectAsState()
    val signatureStatus = remember(currentUpdateInfo) { SignatureVerifier.verify(context, currentUpdateInfo?.expectedSha256List) }
    val isUnofficial = signatureStatus == SignatureStatus.UNOFFICIAL_MODIFIED
    val statusColor = if (isUnofficial) Color(0xFFFF9E00) else ActiveGreenLed

    var showUnofficialWarningDialog by remember { mutableStateOf(false) }
    var showSecurityDialog by remember { mutableStateOf(false) }
    var showRepoConfirmDialog by remember { mutableStateOf(false) }

    if (showRepoConfirmDialog) {
        ExternalLinkConfirmDialog(
            url = repoUrl,
            onDismiss = { showRepoConfirmDialog = false }
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Build Status Header (Seamless without card background)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(statusColor.copy(alpha = 0.12f))
                    .border(1.dp, statusColor.copy(alpha = 0.3f), CircleShape)
            ) {
                if (isUnofficial) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_stat_proxy_warning),
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_shield),
                        contentDescription = null,
                        tint = ActiveGreenLed,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = if (isUnofficial) "Неофициальная сборка" else "Официальная сборка Mirrly TG Proxy",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (isUnofficial) {
                        "Подпись приложения изменена. Будьте осторожны с несертифицированными модами."
                    } else {
                        "Оригинальный релиз с подтверждённым отпечатком подписи разработчика."
                    },
                    fontSize = 12.sp,
                    color = TextMuted,
                    lineHeight = 16.sp
                )
            }
        }

        // 2 Compact Action Buttons Row
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            // Button 1: Repository (GitHub)
            Button(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    showRepoConfirmDialog = true
                },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = ActiveGreenLed
                ),
                border = BorderStroke(1.dp, ActiveGreenLed.copy(alpha = 0.4f)),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_github),
                        contentDescription = null,
                        tint = ActiveGreenLed,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "Репозиторий",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Button 2: Check Build Authenticity via Hashcode
            OutlinedButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (isUnofficial) {
                        showUnofficialWarningDialog = true
                    } else {
                        showSecurityDialog = true
                    }
                },
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, statusColor.copy(alpha = 0.5f)),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
            ) {
                Text(
                    text = "Проверить хеш",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = statusColor
                )
            }
        }
    }

    // 1. Unofficial Build Warning Dialog (Opens if Hash fails / signature is modified)
    if (showUnofficialWarningDialog) {
        UnofficialBuildDialog(
            onDismiss = { showUnofficialWarningDialog = false }
        )
    }

    // 2. Security Dialog (Opens if Hash matches official signature)
    if (showSecurityDialog) {
        val currentSha256 = remember { SignatureVerifier.getSignatureSha256(context) }
        val isOfficial = signatureStatus == SignatureStatus.OFFICIAL_RELEASE || signatureStatus == SignatureStatus.DEBUG_BUILD
        val themeColor = if (isOfficial) ActiveGreenLed else Color(0xFFFF9E00)

        Dialog(
            onDismissRequest = { showSecurityDialog = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = true
            )
        ) {
            val view = LocalView.current
            LaunchedEffect(Unit) {
                try {
                    val window = (view.parent as? DialogWindowProvider)?.window
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        window?.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                        window?.attributes = window?.attributes?.apply {
                            blurBehindRadius = 50
                        }
                    }
                } catch (_: Exception) {}
            }


            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.12f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { showSecurityDialog = false }
                    .padding(horizontal = 24.dp)
            ) {
                // Detailed Security Info with Smooth Fading Edges
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .fadingEdges(topFadeHeight = 32.dp, bottomFadeHeight = 64.dp)
                        .padding(bottom = 120.dp, top = 24.dp)
                        .verticalScroll(rememberScrollState())
                        .clickable(enabled = false) {}
                ) {
                    // Category Pill
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = themeColor.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, themeColor.copy(alpha = 0.35f))
                    ) {
                        Text(
                            text = "ПРОВЕРКА ПОДЛИННОСТИ",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = themeColor,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                        )
                    }

                    // Main Title
                    Text(
                        text = "Безопасность и подпись",
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite,
                        textAlign = TextAlign.Center,
                        letterSpacing = 0.3.sp
                    )

                    // Card 1: Status Explanation (Glass Card)
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = themeColor.copy(alpha = 0.08f),
                        border = BorderStroke(1.dp, themeColor.copy(alpha = 0.30f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = when (signatureStatus) {
                                SignatureStatus.OFFICIAL_RELEASE -> "Официальная цифровая подпись Mirrly TG Proxy подтверждена. Данная сборка выпущена разработчиками в репозитории GitHub."
                                SignatureStatus.DEBUG_BUILD -> "Отладочная версия (Debug). Приложение собрано в среде разработки с тестовым ключом."
                                else -> "Цифровая подпись приложения верифицирована."
                            },
                            fontSize = 13.sp,
                            color = TextWhite.copy(alpha = 0.90f),
                            textAlign = TextAlign.Start,
                            lineHeight = 18.5.sp,
                            modifier = Modifier.padding(14.dp)
                        )
                    }

                    // Card 2: SHA-256 Fingerprint Display (Glass Card)
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color.White.copy(alpha = 0.04f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "ОФИЦИАЛЬНЫЙ ОТПЕЧАТОК SHA-256",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White.copy(alpha = 0.65f),
                                letterSpacing = 0.6.sp
                            )
                            Text(
                                text = currentSha256,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Normal,
                                color = TextWhite.copy(alpha = 0.95f),
                                textAlign = TextAlign.Start,
                                lineHeight = 17.sp
                            )
                        }
                    }
                }

                // Bottom Floating Action Buttons (Seamless over blurred background)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 36.dp)
                        .fillMaxWidth(0.90f)
                        .clickable(enabled = false) {}
                ) {
                    OutlinedButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            try {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("SHA256 Signature", currentSha256)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Хеш подписи скопирован!", Toast.LENGTH_SHORT).show()
                            } catch (_: Exception) {}
                        },
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = TextWhite.copy(alpha = 0.90f)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .springPress()
                    ) {
                        Text("Скопировать", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }

                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            showSecurityDialog = false
                        },
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White.copy(alpha = 0.20f),
                            contentColor = TextWhite
                        ),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.45f)),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .springPress()
                    ) {
                        Text("Закрыть", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
