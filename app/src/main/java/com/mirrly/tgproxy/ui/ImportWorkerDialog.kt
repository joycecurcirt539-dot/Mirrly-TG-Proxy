package com.mirrly.tgproxy.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
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
import com.mirrly.tgproxy.core.WorkerDomainNormalizer
import com.mirrly.tgproxy.core.WorkerStatus
import com.mirrly.tgproxy.service.WorkerPingTester
import com.mirrly.tgproxy.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Frosted Glass Import Cloudflare Worker Dialog (Consistent with AddWorkerDialog and DeleteWorkerConfirmDialog, no emojis).
 */
@Composable
fun ImportWorkerDialog(
    name: String,
    domain: String,
    activeAccentColor: Color = ActiveGreenLed,
    onDismiss: () -> Unit,
    onImport: (name: String, domain: String) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val normalized = remember(name, domain) {
        WorkerDomainNormalizer.normalizeForm(name, domain)
    }

    var editName by remember { mutableStateOf(normalized.normalizedName.ifBlank { normalized.domainResult.suggestedName }) }
    var isCheckingWorker by remember { mutableStateOf(false) }
    var unreachableWarning by remember { mutableStateOf<String?>(null) }

    val cleanDomain = normalized.normalizedDomain

    Dialog(
        onDismissRequest = {
            if (!isCheckingWorker) onDismiss()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = !isCheckingWorker,
            dismissOnClickOutside = !isCheckingWorker
        )
    ) {
        DialogBackdropBox(
            onDismiss = {
                if (!isCheckingWorker) onDismiss()
            }
        ) {
            // Scrollable Content
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .align(Alignment.Center)
                    .adaptiveContainerWidth(440.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(
                        top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 60.dp,
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp
                    )
                    .padding(horizontal = 24.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {}
            ) {
                // Category Pill
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = activeAccentColor.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, activeAccentColor.copy(alpha = 0.35f))
                ) {
                    Text(
                        text = "ИМПОРТ ВОРКЕРА",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = activeAccentColor,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                }

                // Title
                Text(
                    text = "Импорт Cloudflare Worker",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite,
                    textAlign = TextAlign.Center,
                    letterSpacing = 0.3.sp
                )

                // Transparent Glass Container for Input Parameters
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White.copy(alpha = 0.04f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "ПАРАМЕТРЫ ПОДКЛЮЧЕНИЯ:",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = activeAccentColor,
                            letterSpacing = 0.5.sp
                        )

                        Text(
                            text = "Вы открыли ссылку для подключения Cloudflare Worker. Проверьте данные и подтвердите добавление узла в конфигурацию прокси.",
                            fontSize = 12.5.sp,
                            color = TextWhite.copy(alpha = 0.8f),
                            lineHeight = 17.sp
                        )

                        // Domain Box
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF0F172A).copy(alpha = 0.6f),
                            border = BorderStroke(1.dp, activeAccentColor.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "ДОМЕН УЗЛА",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = activeAccentColor,
                                    letterSpacing = 0.8.sp
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = cleanDomain,
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Medium,
                                    color = TextWhite
                                )
                            }
                        }

                        OutlinedTextField(
                            value = editName,
                            onValueChange = { editName = it },
                            label = { Text("Название (опционально)") },
                            placeholder = {
                                Text(
                                    text = "например: От друга",
                                    color = TextMuted.copy(alpha = 0.5f),
                                    fontSize = 12.sp
                                )
                            },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = activeAccentColor,
                                unfocusedBorderColor = Color(0xFF223048),
                                focusedTextColor = TextWhite,
                                unfocusedTextColor = TextWhite,
                                focusedLabelColor = activeAccentColor,
                                unfocusedLabelColor = TextMuted,
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // Pre-flight warning banner
                if (unreachableWarning != null) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFFE53935).copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, Color(0xFFE53935).copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "ПРЕДУПРЕЖДЕНИЕ СВЯЗИ:",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFF6B6B),
                                letterSpacing = 0.5.sp
                            )
                            Text(
                                text = unreachableWarning ?: "",
                                fontSize = 11.5.sp,
                                color = TextWhite.copy(alpha = 0.9f),
                                lineHeight = 15.sp
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        val finalName = editName.trim().ifBlank { normalized.domainResult.suggestedName.ifBlank { "Импортированный воркер" } }
                                        onImport(finalName, cleanDomain)
                                    },
                                    border = BorderStroke(1.dp, Color(0xFFFF6B6B).copy(alpha = 0.5f)),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f).height(38.dp)
                                ) {
                                    Text(
                                        text = "Импортировать",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFFFF6B6B)
                                    )
                                }

                                Button(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        unreachableWarning = null
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = activeAccentColor),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f).height(38.dp)
                                ) {
                                    Text(
                                        text = "Отмена",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF0A0E1A)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Import & Activate Action Button
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (isCheckingWorker) activeAccentColor.copy(alpha = 0.7f) else activeAccentColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .springPress(
                            onClick = {
                                if (isCheckingWorker) return@springPress
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val finalName = editName.trim().ifBlank { normalized.domainResult.suggestedName.ifBlank { "Импортированный воркер" } }

                                scope.launch {
                                    isCheckingWorker = true
                                    unreachableWarning = null

                                    val (status, pingMs) = WorkerPingTester.pingWorker(cleanDomain)
                                    isCheckingWorker = false

                                    if (status == WorkerStatus.ONLINE || status == WorkerStatus.RATE_LIMITED_429) {
                                        onImport(finalName, cleanDomain)
                                    } else {
                                        unreachableWarning = "Воркер «$cleanDomain» не отвечает на проверочный запрос (ERR_UNREACHABLE). Убедитесь, что скрипт развернут в Cloudflare."
                                    }
                                }
                            }
                        )
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (isCheckingWorker) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = Color(0xFF0A0E1A),
                                    strokeWidth = 2.dp
                                )
                                Text(
                                    text = "Проверка узла...",
                                    color = Color(0xFF0A0E1A),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.5.sp
                                )
                            }
                        } else {
                            Text(
                                text = "Импортировать и активировать",
                                color = Color(0xFF0A0E1A),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    }
                }

                // Cancel Button
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .springPress(
                            onClick = {
                                if (isCheckingWorker) return@springPress
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onDismiss()
                            }
                        )
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "Отмена",
                            color = TextWhite.copy(alpha = 0.85f),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.5.sp
                        )
                    }
                }
            }

            // Top Header with Back Button (pinned at top left over blurred background)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopStart)
                    .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp)
            ) {
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDismiss()
                    }
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_arrow_left),
                        contentDescription = "Назад",
                        tint = TextWhite,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}
