/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.mirrly.tgproxy.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.core.WarpAccountManager
import com.mirrly.tgproxy.core.WarpEndpointScanner
import com.mirrly.tgproxy.core.WarpProfile
import com.mirrly.tgproxy.core.WarpRegistrationStep
import com.mirrly.tgproxy.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Диалог живого отображения процесса регистрации профиля Cloudflare WARP MASQUE
 * и детального просмотра итогового результата регистрации.
 */
@Composable
fun WarpRegistrationDialog(
    initialProfile: WarpProfile?,
    workerDomain: String?,
    initialLicenseKey: String = "",
    startRegistrationImmediately: Boolean = false,
    onProfileSaved: (WarpProfile) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    var isRegistering by remember { mutableStateOf(false) }
    var isScanningPorts by remember { mutableStateOf(false) }
    var isAttachingLicense by remember { mutableStateOf(false) }
    var licenseAttachError by remember { mutableStateOf<String?>(null) }
    var licenseKeyInput by remember {
        mutableStateOf(initialProfile?.licenseKey?.ifBlank { initialLicenseKey } ?: initialLicenseKey)
    }
    var registrationJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var currentStepIndex by remember { mutableStateOf(1) }
    var currentProfile by remember { mutableStateOf(initialProfile) }
    val logSteps = remember { mutableStateListOf<WarpRegistrationStep>() }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val terminalScrollState = rememberScrollState()

    fun runPortScan() {
        if (isScanningPorts || isRegistering) return
        isScanningPorts = true
        coroutineScope.launch {
            Toast.makeText(context, "Подбор живых Anycast портов WARP...", Toast.LENGTH_SHORT).show()
            try {
                val best = WarpEndpointScanner.findBestEndpoint(useFragmentation = true)
                if (best != null) {
                    val base = currentProfile ?: WarpAccountManager.BOOTSTRAP_PROFILE
                    val updated = base.copy(peerEndpoint = best.endpoint)
                    currentProfile = updated
                    onProfileSaved(updated)
                    Toast.makeText(context, "Подобран живой порт: ${best.endpoint} (${best.rttMs}мс)", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, "Открытых портов не найдено (ТСПУ блокирует UDP)", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Сбой подбора: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                isScanningPorts = false
            }
        }
    }

    fun attachLicenseToCurrentProfile() {
        val prof = currentProfile ?: return
        val cleanKey = licenseKeyInput.trim()
        if (cleanKey.isBlank()) {
            Toast.makeText(context, "Введите лицензионный ключ WARP+", Toast.LENGTH_SHORT).show()
            return
        }
        if (!WarpAccountManager.isValidLicenseKey(cleanKey)) {
            Toast.makeText(context, "Неверный формат ключа (ожидается 26 символов)", Toast.LENGTH_SHORT).show()
            return
        }
        if (isAttachingLicense || isRegistering) return
        isAttachingLicense = true
        licenseAttachError = null

        coroutineScope.launch {
            Toast.makeText(context, "Привязка лицензии WARP+...", Toast.LENGTH_SHORT).show()
            val result = WarpAccountManager.attachLicenseKey(
                accountId = prof.accountId,
                token = prof.token,
                licenseKey = cleanKey,
                workerDomain = workerDomain
            )
            isAttachingLicense = false
            result.onSuccess { info ->
                val updated = prof.copy(
                    licenseKey = info.licenseKey,
                    isWarpPlus = info.isWarpPlus
                )
                currentProfile = updated
                licenseKeyInput = info.licenseKey
                onProfileSaved(updated)
                Toast.makeText(
                    context,
                    "Лицензия WARP+ успешно активирована (${info.accountType})",
                    Toast.LENGTH_SHORT
                ).show()
            }.onFailure { err ->
                licenseAttachError = err.message
                Toast.makeText(
                    context,
                    "Ошибка привязки лицензии: ${err.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    fun runRegistration() {
        if (registrationJob?.isActive == true) return
        isRegistering = true
        errorMessage = null
        currentStepIndex = 1
        logSteps.clear()

        registrationJob = coroutineScope.launch {
            val result = WarpAccountManager.registerAndActivate(
                workerDomain = workerDomain,
                licenseKey = licenseKeyInput.trim().ifBlank { null },
                existingProfile = currentProfile,
                fallbackToBootstrap = false,
                onProgress = { step ->
                    coroutineScope.launch {
                        currentStepIndex = step.stepIndex
                        logSteps.add(step)
                        if (step.isError) {
                            errorMessage = step.detail
                        }
                    }
                }
            )

            result.onSuccess { newProf ->
                currentProfile = newProf
                licenseKeyInput = newProf.licenseKey
                isRegistering = false
                registrationJob = null
                currentStepIndex = 4
                onProfileSaved(newProf)
                val toastMsg = if (newProf.isOfflineCached) {
                    "Применен офлайн-профиль (требуется онлайн-проверка): ${newProf.clientIpv4}"
                } else {
                    "WARP зарегистрирован: ${newProf.clientIpv4}"
                }
                Toast.makeText(
                    context,
                    toastMsg,
                    Toast.LENGTH_SHORT
                ).show()
            }.onFailure { err ->
                isRegistering = false
                registrationJob = null
                errorMessage = err.message ?: "Сбой сетевого стека Cloudflare"
                Toast.makeText(
                    context,
                    "Ошибка: ${err.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        if (startRegistrationImmediately) {
            runRegistration()
        }
    }

    LaunchedEffect(logSteps.size) {
        if (logSteps.isNotEmpty()) {
            terminalScrollState.animateScrollTo(terminalScrollState.maxValue)
        }
    }

    Dialog(
        onDismissRequest = {
            if (isRegistering) {
                registrationJob?.cancel()
                registrationJob = null
                isRegistering = false
            }
            onDismiss()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = !isRegistering
        )
    ) {
        DialogBackdropBox(
            onDismiss = {
                if (isRegistering) {
                    registrationJob?.cancel()
                    registrationJob = null
                    isRegistering = false
                }
                onDismiss()
            },
            blurRadiusPx = 70
        ) {
            val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val safeBottomPadding = maxOf(navBarBottom + 64.dp, 110.dp)

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .align(Alignment.Center)
                    .adaptiveContainerWidth(500.dp)
                    .padding(top = statusBarTop + 16.dp, bottom = safeBottomPadding)
                    .padding(horizontal = 18.dp)
                    .verticalScroll(rememberScrollState())
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {}
            ) {
                // Верхняя строка с центрированным бейджем и кнопкой закрытия
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = ActiveGreenLed.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, ActiveGreenLed.copy(alpha = 0.35f))
                    ) {
                        Text(
                            text = "CLOUDFLARE WARP MASQUE",
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = ActiveGreenLed,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(Color.Transparent)
                            .border(1.dp, AmoledBorder, CircleShape)
                            .clickable {
                                if (isRegistering) {
                                    registrationJob?.cancel()
                                    registrationJob = null
                                    isRegistering = false
                                }
                                onDismiss()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "✕",
                            fontSize = 11.sp,
                            color = TextMuted,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Text(
                    text = if (isRegistering) "Регистрация профиля WARP" else "Профиль Cloudflare WARP",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "Автономная регистрация устройства, привязка mTLS P-256 и Anycast-маршрутизация",
                    fontSize = 11.sp,
                    color = TextMuted,
                    textAlign = TextAlign.Center,
                    lineHeight = 15.sp
                )

                // Степпер 4 этапов регистрации
                WarpStepperCard(
                    currentStep = currentStepIndex,
                    isRegistering = isRegistering,
                    hasError = errorMessage != null
                )

                // Терминальный лог событий в реальном времени
                if (isRegistering || logSteps.isNotEmpty()) {
                    WarpLiveTerminalLog(
                        steps = logSteps,
                        isRegistering = isRegistering,
                        errorMessage = errorMessage,
                        scrollState = terminalScrollState
                    )
                }

                // Карточка результата регистрации (если профиль создан)
                val profile = currentProfile
                if (profile != null && !isRegistering) {
                    WarpResultCard(
                        profile = profile,
                        onCopy = { label, value ->
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
                            Toast.makeText(context, "$label скопирован", Toast.LENGTH_SHORT).show()
                        }
                    )
                }

                // Карточка лицензии WARP+ / Zero Trust
                if (!isRegistering) {
                    WarpLicenseCard(
                        licenseKey = licenseKeyInput,
                        onLicenseKeyChange = {
                            licenseKeyInput = it
                            licenseAttachError = null
                        },
                        isWarpPlus = profile?.isWarpPlus == true,
                        hasProfile = profile != null,
                        isAttaching = isAttachingLicense,
                        attachError = licenseAttachError,
                        onAttach = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            attachLicenseToCurrentProfile()
                        }
                    )
                }

                // Блок действий
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (!isRegistering) {
                        if (currentProfile != null) {
                            Button(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    runPortScan()
                                },
                                enabled = !isScanningPorts,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isScanningPorts) ActiveGreenLed.copy(alpha = 0.2f) else ActiveGreenLed.copy(alpha = 0.16f),
                                    contentColor = ActiveGreenLed
                                ),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, ActiveGreenLed.copy(alpha = 0.45f)),
                                modifier = Modifier.fillMaxWidth().height(40.dp)
                            ) {
                                if (isScanningPorts) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        color = ActiveGreenLed,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Сканирование портов...",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                } else {
                                    Text(
                                        text = "Подобрать живой Anycast порт (Auto-Scan)",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Button(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    val conf = currentProfile!!.toAmneziaWgConfig()
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("AmneziaWG Config", conf))
                                    Toast.makeText(context, "Конфиг AmneziaWG (QUIC I1 Госуслуги) скопирован", Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = ActiveGreenLed.copy(alpha = 0.12f),
                                    contentColor = ActiveGreenLed
                                ),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, ActiveGreenLed.copy(alpha = 0.35f)),
                                modifier = Modifier.fillMaxWidth().height(40.dp)
                            ) {
                                Text(
                                    text = "Скопировать конфиг AmneziaWG (QUIC I1)",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                runRegistration()
                            },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = ActiveGreenLed
                            ),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, ActiveGreenLed.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth().height(42.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_refresh),
                                    contentDescription = null,
                                    tint = ActiveGreenLed,
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(
                                    text = if (currentProfile != null) "Зарегистрировать заново" else "Запустить регистрацию",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    } else {
                        // Регистрация в процессе: индикатор ожидания и кнопка прерывания
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color.Transparent,
                                border = BorderStroke(1.dp, ActiveGreenLed.copy(alpha = 0.35f)),
                                modifier = Modifier.fillMaxWidth().height(40.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    CircularProgressIndicator(
                                        strokeWidth = 1.5.dp,
                                        color = ActiveGreenLed,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Выполняется регистрация в Cloudflare...",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = ActiveGreenLed
                                    )
                                }
                            }
                            // Кнопка принудительного прерывания регистрации
                            OutlinedButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    registrationJob?.cancel()
                                    registrationJob = null
                                    isRegistering = false
                                    errorMessage = "Регистрация прервана пользователем"
                                },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = Color(0xFFFF5252)
                                ),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, Color(0xFFFF5252).copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth().height(36.dp)
                            ) {
                                Text(
                                    text = "Прервать регистрацию",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Индикатор 4 этапов регистрации WARP.
 */
@Composable
private fun WarpStepperCard(
    currentStep: Int,
    isRegistering: Boolean,
    hasError: Boolean
) {
    val steps = listOf(
        "WireGuard" to "Ключи Curve25519",
        "MASQUE" to "mTLS P-256 (secp256r1)",
        "Активация" to "warp_enabled: true",
        "Anycast" to "Маршрутизация (8095)"
    )

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, AmoledBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ЭТАПЫ РЕГИСТРАЦИИ",
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                    color = TextMuted
                )
                Text(
                    text = "$currentStep / ${steps.size}",
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (hasError) Color(0xFFEF4444) else ActiveGreenLed
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                steps.forEachIndexed { index, (shortName, _) ->
                    val stepNum = index + 1
                    val isDone = stepNum < currentStep || (stepNum == 4 && !isRegistering && !hasError && currentStep == 4)
                    val isCurrent = stepNum == currentStep && isRegistering

                    val circleColor = when {
                        isDone -> ActiveGreenLed
                        isCurrent -> ActiveGreenLed
                        hasError && stepNum == currentStep -> Color(0xFFEF4444)
                        else -> AmoledBorder
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(circleColor.copy(alpha = if (isCurrent) 0.2f else 1f))
                                .border(1.2.dp, circleColor, CircleShape)
                        ) {
                            if (isCurrent) {
                                CircularProgressIndicator(
                                    strokeWidth = 1.5.dp,
                                    color = ActiveGreenLed,
                                    modifier = Modifier.size(13.dp)
                                )
                            } else {
                                Text(
                                    text = if (isDone) "OK" else "$stepNum",
                                    fontSize = if (isDone) 8.sp else 9.5.sp,
                                    fontWeight = FontWeight.Black,
                                    color = if (isDone) AmoledBackground else TextWhite
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(3.dp))

                        Text(
                            text = shortName,
                            fontSize = 9.sp,
                            fontWeight = if (isCurrent || isDone) FontWeight.Bold else FontWeight.Normal,
                            color = if (isCurrent || isDone) TextWhite else TextMuted,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (index < steps.size - 1) {
                        val lineDone = stepNum < currentStep
                        Box(
                            modifier = Modifier
                                .weight(0.5f)
                                .height(1.5.dp)
                                .background(if (lineDone) ActiveGreenLed.copy(alpha = 0.6f) else AmoledBorder)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Окно технического консольного лога процесса регистрации в реальном времени.
 */
@Composable
private fun WarpLiveTerminalLog(
    steps: List<WarpRegistrationStep>,
    isRegistering: Boolean,
    errorMessage: String?,
    scrollState: androidx.compose.foundation.ScrollState
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, AmoledBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (isRegistering) ActiveGreenLed else if (errorMessage != null) Color(0xFFEF4444) else ActiveGreenLed)
                    )
                    Text(
                        text = "ЖУРНАЛ СЕТЕВЫХ ВЫЗОВОВ",
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp,
                        color = TextMuted
                    )
                }

                Text(
                    text = "${steps.size} записей",
                    fontSize = 9.5.sp,
                    color = TextMuted
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp, max = 125.dp)
                    .verticalScroll(scrollState)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

                    steps.forEach { step ->
                        val timeStr = timeFormat.format(Date(step.timestamp))
                        val textColor = when {
                            step.isError -> Color(0xFFEF4444)
                            step.isWarning -> Color(0xFFF59E0B)
                            step.isComplete -> ActiveGreenLed
                            else -> TextWhite.copy(alpha = 0.85f)
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "[$timeStr]",
                                fontSize = 9.5.sp,
                                fontFamily = FontFamily.Monospace,
                                color = TextMuted
                            )
                            Text(
                                text = "[${step.stepIndex}/4]",
                                fontSize = 9.5.sp,
                                fontFamily = FontFamily.Monospace,
                                color = ActiveGreenLed.copy(alpha = 0.75f)
                            )
                            Text(
                                text = step.detail,
                                fontSize = 9.5.sp,
                                fontFamily = FontFamily.Monospace,
                                color = textColor,
                                lineHeight = 13.sp
                            )
                        }
                    }

                    if (isRegistering) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            CircularProgressIndicator(
                                strokeWidth = 1.dp,
                                color = ActiveGreenLed,
                                modifier = Modifier.size(9.dp)
                            )
                            Text(
                                text = "Обращение к API Cloudflare...",
                                fontSize = 9.5.sp,
                                fontFamily = FontFamily.Monospace,
                                color = ActiveGreenLed
                            )
                        }
                    }

                    if (errorMessage != null && !isRegistering) {
                        Text(
                            text = "ОШИБКА: $errorMessage",
                            fontSize = 9.5.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFEF4444)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Карточка полного результата регистрации WARP со всеми техническими параметрами.
 */
@Composable
private fun WarpResultCard(
    profile: WarpProfile,
    onCopy: (label: String, value: String) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, AmoledBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "РЕЗУЛЬТАТ РЕГИСТРАЦИИ",
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                    color = TextMuted
                )

                val (badgeText, badgeColor) = when {
                    profile.isOfflineCached -> "OFFLINE-КЭШ" to Color(0xFFF59E0B)
                    profile.needsVerification -> "ТРЕБУЕТСЯ ПРОВЕРКА" to Color(0xFFF59E0B)
                    profile.dataPlaneReady -> "ГОТОВ К РАБОТЕ" to ActiveGreenLed
                    profile.isWarpEnabled -> "АКТИВЕН" to ActiveGreenLed
                    else -> "НЕ АКТИВИРОВАН" to TextMuted
                }

                Surface(
                    shape = RoundedCornerShape(5.dp),
                    color = badgeColor.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.35f))
                ) {
                    Text(
                        text = badgeText,
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Black,
                        color = badgeColor,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }
            }

            val entitlementTitle = when (profile.entitlement) {
                com.mirrly.tgproxy.core.WarpEntitlement.PLUS -> "WARP+ (Премиум)"
                com.mirrly.tgproxy.core.WarpEntitlement.TEAM -> "WARP Zero Trust / Teams"
                com.mirrly.tgproxy.core.WarpEntitlement.UNVERIFIED -> "Не подтвержден сервером (Free)"
                com.mirrly.tgproxy.core.WarpEntitlement.FREE -> "Бесплатный профиль (Free)"
            }
            WarpResultParamRow(
                label = "Тип подписки",
                value = entitlementTitle,
                valueColor = if (profile.isWarpPlus) ActiveGreenLed else TextWhite
            )

            val regState = if (profile.isRegistered) "Зарегистрирован" else "Не подтвержден"
            val actState = if (profile.isActivated) "Активирован" else "Не активирован"
            WarpResultParamRow(
                label = "Статус Cloudflare API",
                value = "$regState / $actState",
                valueColor = if (profile.isRegistered && profile.isActivated) ActiveGreenLed else TextMuted
            )

            val dpStatus = when {
                profile.dataPlaneReady -> "Готов к передаче трафика"
                profile.isOfflineCached -> "Офлайн (требуется онлайн-проверка)"
                else -> "Не готов (нет активного туннеля)"
            }
            WarpResultParamRow(
                label = "Готовность Data Plane",
                value = dpStatus,
                valueColor = if (profile.dataPlaneReady) ActiveGreenLed else Color(0xFFF59E0B)
            )

            WarpResultParamRow(
                label = "ID устройства",
                value = profile.accountId.ifBlank { "Н/Д" },
                isCopyable = profile.accountId.isNotBlank(),
                onCopy = { onCopy("ID устройства", profile.accountId) }
            )

            WarpResultParamRow(
                label = "Выделенный IPv4",
                value = profile.clientIpv4,
                isCopyable = true,
                onCopy = { onCopy("Клиентский IPv4", profile.clientIpv4) }
            )

            if (profile.clientIpv6.isNotBlank()) {
                WarpResultParamRow(
                    label = "Выделенный IPv6",
                    value = profile.clientIpv6,
                    isCopyable = true,
                    onCopy = { onCopy("Клиентский IPv6", profile.clientIpv6) }
                )
            }

            WarpResultParamRow(
                label = "Anycast эндпоинт",
                value = "${profile.peerEndpoint} (ТСПУ Bypass)",
                valueColor = ActiveGreenLed,
                isCopyable = true,
                onCopy = { onCopy("Anycast эндпоинт", profile.peerEndpoint) }
            )

            val certStatus = if (profile.clientCertBase64.isNotBlank()) "Серверный X.509 DER подтвержден" else "Bearer-токен (RFC 9298)"
            WarpResultParamRow(
                label = "Аутентификация mTLS",
                value = certStatus,
                valueColor = if (profile.clientCertBase64.isNotBlank()) ActiveGreenLed else TextWhite
            )

            WarpResultParamRow(
                label = "Публичный ключ Cloudflare",
                value = profile.peerPublicKey.ifBlank { "Стандартный Anycast" },
                isCopyable = profile.peerPublicKey.isNotBlank(),
                onCopy = { onCopy("Публичный ключ узла", profile.peerPublicKey) }
            )

            WarpResultParamRow(
                label = "WireGuard Public Key",
                value = profile.publicKeyBase64,
                isCopyable = true,
                onCopy = { onCopy("WireGuard Public Key", profile.publicKeyBase64) }
            )

            if (profile.licenseKey.isNotBlank()) {
                WarpResultParamRow(
                    label = "Лицензия WARP+",
                    value = WarpAccountManager.maskLicenseKey(profile.licenseKey),
                    valueColor = ActiveGreenLed,
                    isCopyable = true,
                    onCopy = { onCopy("Лицензия WARP+", profile.licenseKey) }
                )
            }
        }
    }
}

/**
 * Карточка ввода и привязки лицензионного ключа WARP+ / Zero Trust.
 */
@Composable
private fun WarpLicenseCard(
    licenseKey: String,
    onLicenseKeyChange: (String) -> Unit,
    isWarpPlus: Boolean,
    hasProfile: Boolean,
    isAttaching: Boolean,
    attachError: String?,
    onAttach: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, AmoledBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (isWarpPlus) ActiveGreenLed else TextMuted)
                    )
                    Text(
                        text = "ЛИЦЕНЗИЯ WARP+ / ZERO TRUST",
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp,
                        color = TextMuted
                    )
                }

                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = if (isWarpPlus) ActiveGreenLed.copy(alpha = 0.15f) else Color.Transparent,
                    border = BorderStroke(1.dp, if (isWarpPlus) ActiveGreenLed.copy(alpha = 0.4f) else AmoledBorder)
                ) {
                    Text(
                        text = if (isWarpPlus) "WARP+ АКТИВЕН" else "FREE ТАРИФ",
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Black,
                        color = if (isWarpPlus) ActiveGreenLed else TextMuted,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }
            }

            Text(
                text = "Лицензионный ключ WARP+ (26 символов) для повышенного Anycast-приоритета и неограниченного трафика.",
                fontSize = 10.sp,
                color = TextMuted,
                lineHeight = 13.5.sp
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = licenseKey,
                    onValueChange = onLicenseKeyChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = TextWhite
                    ),
                    cursorBrush = SolidColor(ActiveGreenLed),
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                        .background(Color(0xFF0F0F0F), RoundedCornerShape(8.dp))
                        .border(1.dp, AmoledBorder, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 7.dp),
                    decorationBox = { innerTextField ->
                        if (licenseKey.isEmpty()) {
                            Text(
                                text = "xxxxxxxx-xxxxxxxx-xxxxxxxx",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.5.sp,
                                color = TextMuted.copy(alpha = 0.6f)
                            )
                        }
                        innerTextField()
                    }
                )

                if (hasProfile) {
                    Button(
                        onClick = onAttach,
                        enabled = !isAttaching && licenseKey.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ActiveGreenLed.copy(alpha = 0.16f),
                            contentColor = ActiveGreenLed
                        ),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, ActiveGreenLed.copy(alpha = 0.4f)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        if (isAttaching) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 1.5.dp,
                                color = ActiveGreenLed
                            )
                        } else {
                            Text(
                                text = "Привязать",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            if (attachError != null) {
                Text(
                    text = "Ошибка: $attachError",
                    fontSize = 9.5.sp,
                    color = Color(0xFFEF4444),
                    lineHeight = 12.sp
                )
            }
        }
    }
}

/**
 * Строка параметра в карточке результата с возможностью быстрого копирования.
 */
@Composable
private fun WarpResultParamRow(
    label: String,
    value: String,
    valueColor: Color = TextWhite,
    isCopyable: Boolean = false,
    onCopy: (() -> Unit)? = null
) {
    val haptic = LocalHapticFeedback.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isCopyable && onCopy != null) {
                    Modifier.clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onCopy()
                    }
                } else Modifier
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 10.5.sp,
            color = TextMuted,
            modifier = Modifier.weight(0.9f)
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.weight(1.1f, fill = false)
        ) {
            Text(
                text = value,
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (isCopyable) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_copy),
                    contentDescription = "Копировать",
                    tint = TextMuted,
                    modifier = Modifier.size(11.dp)
                )
            }
        }
    }
}
