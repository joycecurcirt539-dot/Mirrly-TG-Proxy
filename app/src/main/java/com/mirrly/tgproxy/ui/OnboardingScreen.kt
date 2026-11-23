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

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.mirrly.tgproxy.ui.icon.MirrlyIcon
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.service.OemAutostartHelper
import com.mirrly.tgproxy.service.OemFamily
import com.mirrly.tgproxy.service.PreferencesManager
import com.mirrly.tgproxy.service.ProxyForegroundService
import com.mirrly.tgproxy.service.ProxyResurrectionManager
import com.mirrly.tgproxy.service.WallpaperPreset
import com.mirrly.tgproxy.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Премиальный экран первого запуска и приветствия (Onboarding Screen).
 *
 * Архитектура:
 * - Открывается поверх основного дизайна приложения (использует системный CyberEnergyCanvas
 *   и мягкий акриловый темный оверлей, аналогично экрану настроек SettingsScreen).
 * - Парящие фоновые нано-частицы CyberParticlesOverlay.
 * - Увеличенные отступы между карточками и интерактивными элементами (22-26 dp).
 * - Безопасная зона снизу (WindowInsets.navigationBars + 32 dp буфер) для гарантированной
 *   защиты от случайных нажатий кнопок системной 3-кнопочной навигации Android.
 * - Кинематографичные бесшовные переходы между шагами (AnimatedContent с горизонтальным слайдом)
 *   и плавное растворение оверлея при переходе к главному экрану приложения.
 */
const val ONBOARDING_STEP_BACKGROUND = 5

enum class BackgroundGateMissing {
    NONE,
    BATTERY_AND_OEM,
    BATTERY_ONLY,
    OEM_NOT_OPENED,
    OEM_NOT_CONFIRMED
}

data class OnboardingBackgroundGateState(
    val isIgnoringBattery: Boolean,
    val isOemSupported: Boolean,
    val hasOpenedOemSettings: Boolean,
    val isOemConfirmed: Boolean
) {
    val isOemRequirementMet: Boolean
        get() = !isOemSupported || isOemConfirmed

    val isComplete: Boolean
        get() = isIgnoringBattery && isOemRequirementMet

    val missingRequirement: BackgroundGateMissing
        get() = when {
            isComplete -> BackgroundGateMissing.NONE
            !isIgnoringBattery && !isOemRequirementMet -> BackgroundGateMissing.BATTERY_AND_OEM
            !isIgnoringBattery -> BackgroundGateMissing.BATTERY_ONLY
            !hasOpenedOemSettings -> BackgroundGateMissing.OEM_NOT_OPENED
            else -> BackgroundGateMissing.OEM_NOT_CONFIRMED
        }

    fun canNavigateToStep(targetStep: Int): Boolean {
        return targetStep <= ONBOARDING_STEP_BACKGROUND || isComplete
    }

    fun canExit(): Boolean = isComplete
}

@Composable
fun OnboardingScreen(
    initialLanguage: String = "ru",
    prefsManager: PreferencesManager? = null,
    initialStep: Int = 0,
    onLanguageSelected: (String) -> Unit,
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val totalSteps = 7
    var currentLang by remember(initialLanguage) { mutableStateOf(initialLanguage) }
    var currentStep by rememberSaveable(initialStep) { mutableIntStateOf(initialStep.coerceIn(0, totalSteps - 1)) } // 0: Язык, 1: Выбор фона, 2: Кастомизация, 3: Прокси Telegram, 4: VPN и аплинки, 5: Работа в фоне, 6: Быстрый старт

    // Мониторинг исключения из оптимизации батареи (Doze Mode) и OEM-автозапуска
    var isAppResumed by remember { mutableStateOf(true) }
    var isIgnoringBattery by remember {
        mutableStateOf(OemAutostartHelper.isIgnoringBatteryOptimizations(context))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    isAppResumed = true
                    isIgnoringBattery = OemAutostartHelper.isIgnoringBatteryOptimizations(context)
                }
                Lifecycle.Event.ON_PAUSE -> {
                    isAppResumed = false
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(currentStep, isAppResumed, isIgnoringBattery) {
        if (currentStep == ONBOARDING_STEP_BACKGROUND && isAppResumed && !isIgnoringBattery) {
            while (isActive && !isIgnoringBattery) {
                delay(400L)
                val updated = OemAutostartHelper.isIgnoringBatteryOptimizations(context)
                if (updated) {
                    isIgnoringBattery = true
                    HapticHelper.performTapClick(context)
                    break
                }
            }
        }
    }

    val isOemSupported = remember { OemAutostartHelper.isOemAutostartSupported() }
    var hasOpenedOemSettings by rememberSaveable {
        mutableStateOf(prefsManager?.hasConfirmedOemAutostart() == true)
    }
    var isOemConfirmed by rememberSaveable {
        mutableStateOf(prefsManager?.hasConfirmedOemAutostart() == true)
    }

    val gateState = OnboardingBackgroundGateState(
        isIgnoringBattery = isIgnoringBattery,
        isOemSupported = isOemSupported,
        hasOpenedOemSettings = hasOpenedOemSettings,
        isOemConfirmed = isOemConfirmed
    )
    val isBackgroundSetupComplete = gateState.isComplete

    var validationMessage by remember { mutableStateOf<String?>(null) }
    var validationMessageKey by remember { mutableIntStateOf(0) }

    fun missingRequirementMessage(): String {
        return when (gateState.missingRequirement) {
            BackgroundGateMissing.BATTERY_AND_OEM -> context.getString(R.string.onboarding_bg_warn_both)
            BackgroundGateMissing.BATTERY_ONLY -> context.getString(R.string.onboarding_bg_warn_battery)
            BackgroundGateMissing.OEM_NOT_OPENED -> context.getString(R.string.onboarding_bg_warn_oem_open)
            BackgroundGateMissing.OEM_NOT_CONFIRMED -> context.getString(R.string.onboarding_bg_warn_oem_confirm)
            BackgroundGateMissing.NONE -> ""
        }
    }

    fun showValidationWarning(message: String) {
        validationMessage = message
        validationMessageKey += 1
        HapticHelper.performTapClick(context)
    }

    LaunchedEffect(validationMessageKey) {
        if (validationMessage != null) {
            delay(3600L)
            validationMessage = null
        }
    }

    LaunchedEffect(isBackgroundSetupComplete) {
        if (isBackgroundSetupComplete) {
            validationMessage = null
        }
    }

    // Анимационный контроллер плавного выхода на главный экран
    val exitProgress = remember { Animatable(0f) }
    var isExiting by remember { mutableStateOf(false) }

    fun executeExit() {
        if (!gateState.canExit()) {
            currentStep = ONBOARDING_STEP_BACKGROUND
            showValidationWarning(missingRequirementMessage())
            return
        }
        if (isExiting) {
            onComplete()
            return
        }
        isExiting = true
        HapticHelper.performTapClick(context)
        coroutineScope.launch {
            try {
                exitProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing)
                )
            } finally {
                onComplete()
            }
        }
    }

    BackHandler {
        if (currentStep > 0) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            currentStep -= 1
        } else if (isBackgroundSetupComplete) {
            executeExit()
        } else {
            currentStep = ONBOARDING_STEP_BACKGROUND
            showValidationWarning(missingRequirementMessage())
        }
    }

    val exitVal = exitProgress.value
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Гарантированная безопасная высота над 3-кнопочной панелью навигации Android (+32 dp)
    val safeBottomMargin = bottomInset + 32.dp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
            .graphicsLayer {
                alpha = (1f - exitVal).coerceIn(0f, 1f)
                scaleX = 1f + 0.04f * exitVal
                scaleY = 1f + 0.04f * exitVal
                translationY = -28.dp.toPx() * exitVal
            }
    ) {

        Column(
            modifier = Modifier
                .fillMaxSize()
                .adaptiveContainerWidth(520.dp)
                .align(Alignment.Center)
                .padding(horizontal = 22.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // ── 1. ВЕРХНИЙ БЛОК: Заголовок бренда и индикатор шагов ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = topInset + 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Логотип и моноширинный бейдж
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.onboarding_brand_title),
                        color = TextWhite,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.2.sp
                    )

                    // Аккуратный чип шага (1/3, 2/3, 3/3)
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, ActiveGreenLed.copy(alpha = 0.50f))
                    ) {
                        Text(
                            text = "${currentStep + 1} / $totalSteps",
                            color = ActiveGreenLed,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }

                // Интерактивные горизонтальные индикаторы шагов с плавной морфинг-анимацией
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (i in 0 until totalSteps) {
                        val isSelected = i == currentStep
                        val barWidth by animateDpAsState(
                            targetValue = if (isSelected) 36.dp else 12.dp,
                            animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
                            label = "stepBarWidth_$i"
                        )
                        val barColor by animateColorAsState(
                            targetValue = if (isSelected) ActiveGreenLed else Color.White.copy(alpha = 0.32f),
                            animationSpec = tween(durationMillis = 320),
                            label = "stepBarColor_$i"
                        )

                        Box(
                            modifier = Modifier
                                .height(4.dp)
                                .width(barWidth)
                                .clip(RoundedCornerShape(2.dp))
                                .background(barColor)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    if (i != currentStep && !isExiting) {
                                        if (!gateState.canNavigateToStep(i)) {
                                            currentStep = ONBOARDING_STEP_BACKGROUND
                                            showValidationWarning(missingRequirementMessage())
                                        } else {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            currentStep = i
                                        }
                                    }
                                }
                        )
                    }
                }
            }

            // ── 2. СРЕДНИЙ БЛОК: Плавный контент текущего шага ──
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .fadingEdges(topFadeHeight = 20.dp, bottomFadeHeight = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = currentStep,
                    transitionSpec = {
                        if (targetState > initialState) {
                            (slideInHorizontally(
                                initialOffsetX = { fullWidth -> (fullWidth * 0.32f).toInt() },
                                animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing)
                            ) + fadeIn(animationSpec = tween(300))).togetherWith(
                                slideOutHorizontally(
                                    targetOffsetX = { fullWidth -> (-fullWidth * 0.32f).toInt() },
                                    animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing)
                                ) + fadeOut(animationSpec = tween(220))
                            )
                        } else {
                            (slideInHorizontally(
                                initialOffsetX = { fullWidth -> (-fullWidth * 0.32f).toInt() },
                                animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing)
                            ) + fadeIn(animationSpec = tween(300))).togetherWith(
                                slideOutHorizontally(
                                    targetOffsetX = { fullWidth -> (fullWidth * 0.32f).toInt() },
                                    animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing)
                                ) + fadeOut(animationSpec = tween(220))
                            )
                        }
                    },
                    label = "onboardingStepTransition",
                    modifier = Modifier.fillMaxWidth()
                ) { step ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(22.dp)
                    ) {
                        when (step) {
                            0 -> {
                                StepLanguageSelection(
                                    currentLang = currentLang,
                                    onSelect = { selected ->
                                        if (selected != currentLang) {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            currentLang = selected
                                            onLanguageSelected(selected)
                                        }
                                    }
                                )
                            }
                            1 -> {
                                StepWallpaperSelection(
                                    prefsManager = prefsManager,
                                    isPreviewActive = (currentStep == 1)
                                )
                            }
                            2 -> {
                                StepCustomizationGuide()
                            }
                            3 -> {
                                StepTelegramProxy()
                            }
                            4 -> {
                                StepVpnMode()
                            }
                            5 -> {
                                StepBackgroundProtection(
                                    prefsManager = prefsManager,
                                    isIgnoringBattery = isIgnoringBattery,
                                    isOemSupported = isOemSupported,
                                    hasOpenedOemSettings = hasOpenedOemSettings,
                                    isOemConfirmed = isOemConfirmed,
                                    onRequestBatteryExemption = {
                                        val launched = OemAutostartHelper.requestIgnoreBatteryOptimizations(context)
                                        if (!launched) {
                                            showValidationWarning(context.getString(R.string.onboarding_bg_warn_open_failed))
                                        }
                                    },
                                    onOpenOemSettings = {
                                        val launched = OemAutostartHelper.openAutostartSettings(context)
                                        hasOpenedOemSettings = true
                                        if (!launched) {
                                            showValidationWarning(context.getString(R.string.onboarding_bg_warn_open_failed))
                                        }
                                    },
                                    onToggleOemConfirmed = { checked ->
                                        if (!hasOpenedOemSettings) {
                                            showValidationWarning(context.getString(R.string.onboarding_bg_warn_oem_open))
                                        } else {
                                            isOemConfirmed = checked
                                            prefsManager?.setHasConfirmedOemAutostart(checked)
                                            HapticHelper.performTapClick(context)
                                        }
                                    }
                                )
                            }
                            6 -> {
                                StepQuickStartGuide()
                            }
                        }
                    }
                }
            }

            // ── 3. НИЖНЯЯ ПАНЕЛЬ ДЕЙСТВИЙ (Увеличенный отступ от экранных кнопок) ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = safeBottomMargin, top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AnimatedVisibility(
                    visible = validationMessage != null,
                    enter = fadeIn(animationSpec = tween(200)) + expandVertically(),
                    exit = fadeOut(animationSpec = tween(180)) + shrinkVertically()
                ) {
                    validationMessage?.let { msg ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = BloodRed.copy(alpha = 0.08f),
                            border = BorderStroke(1.dp, BloodRed.copy(alpha = 0.72f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(BloodRed)
                                )
                                Text(
                                    text = msg,
                                    color = TextWhite,
                                    fontSize = 11.5.sp,
                                    lineHeight = 15.5.sp,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                val isStepBlocked = (currentStep == ONBOARDING_STEP_BACKGROUND && !isBackgroundSetupComplete) ||
                    (currentStep == totalSteps - 1 && !isBackgroundSetupComplete)
                val primaryBorderColor by animateColorAsState(
                    targetValue = if (isStepBlocked) AmoledBorder else ActiveGreenLed.copy(alpha = 0.75f),
                    animationSpec = tween(220),
                    label = "onboardingPrimaryBorder"
                )
                val primaryContentColor by animateColorAsState(
                    targetValue = if (isStepBlocked) TextMuted else ActiveGreenLed,
                    animationSpec = tween(220),
                    label = "onboardingPrimaryContent"
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Вторичная кнопка («Назад»; «Пропустить» доступна только при уже выполненных обязательных настройках)
                    if (currentStep > 0 || isBackgroundSetupComplete) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, AmoledBorder),
                            modifier = Modifier
                                .height(52.dp)
                                .springPress(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        if (currentStep > 0) {
                                            currentStep -= 1
                                        } else {
                                            executeExit()
                                        }
                                    }
                                )
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .padding(horizontal = 24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (currentStep > 0) {
                                        stringResource(R.string.onboarding_btn_back)
                                    } else {
                                        stringResource(R.string.onboarding_btn_skip)
                                    },
                                    color = Color(0xFFD4D4E0),
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    // Первичная кнопка («Далее» / «Требуется настройка» / «Начать работу») с неоновым свечением
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.5.dp, primaryBorderColor),
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .lightSweep(isEnabled = !isStepBlocked, shape = RoundedCornerShape(16.dp))
                            .springPress(
                                onClick = {
                                    if (isStepBlocked) {
                                        if (currentStep != ONBOARDING_STEP_BACKGROUND) {
                                            currentStep = ONBOARDING_STEP_BACKGROUND
                                        }
                                        showValidationWarning(missingRequirementMessage())
                                        return@springPress
                                    }
                                    HapticHelper.performTapClick(context)
                                    if (currentStep < totalSteps - 1) {
                                        currentStep += 1
                                    } else {
                                        executeExit()
                                    }
                                }
                            )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = when {
                                        isStepBlocked -> stringResource(R.string.onboarding_bg_btn_locked)
                                        currentStep == totalSteps - 1 -> stringResource(R.string.onboarding_btn_start)
                                        else -> stringResource(R.string.onboarding_btn_next)
                                    },
                                    color = primaryContentColor,
                                    fontSize = 14.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.4.sp
                                )
                                if (!isStepBlocked) {
                                    MirrlyIcon(
                                        id = R.drawable.ic_chevron_right,
                                        contentDescription = null,
                                        tint = primaryContentColor,
                                        modifier = Modifier.size(16.dp)
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

/**
 * Шаг 1: Выбор языка интерфейса.
 * Выполнен в виде двух просторных обсидиановых карточек (RU и EN) с микро-фасетом и тактильным откликом.
 */
@Composable
private fun StepLanguageSelection(
    currentLang: String,
    onSelect: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Заголовок и вводный текст шага
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.onboarding_step_language_title),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.3.sp,
                color = ActiveGreenLed.copy(alpha = 0.90f)
            )
            Text(
                text = stringResource(R.string.onboarding_step_language_desc),
                fontSize = 13.5.sp,
                color = TextWhite,
                lineHeight = 19.sp
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // 1. Карточка Русского языка
        LanguageCardItem(
            code = "RU",
            title = stringResource(R.string.onboarding_lang_ru),
            subtitle = stringResource(R.string.onboarding_lang_ru_desc),
            isSelected = (currentLang == "ru"),
            onClick = { onSelect("ru") }
        )

        // 2. Карточка Английского языка
        LanguageCardItem(
            code = "EN",
            title = stringResource(R.string.onboarding_lang_en),
            subtitle = stringResource(R.string.onboarding_lang_en_desc),
            isSelected = (currentLang == "en"),
            onClick = { onSelect("en") }
        )

        LanguageCardItem(
            code = "FA",
            title = stringResource(R.string.onboarding_lang_fa),
            subtitle = stringResource(R.string.onboarding_lang_fa_desc),
            isSelected = (currentLang == "fa"),
            onClick = { onSelect("fa") }
        )
    }
}

/**
 * Просторная интерактивная карточка языка в обсидиановом стиле.
 */
@Composable
private fun LanguageCardItem(
    code: String,
    title: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val activeBorderBrush = Brush.horizontalGradient(
        colors = listOf(
            ActiveGreenLed,
            ActiveGreenLed.copy(alpha = 0.80f),
            ActiveGreenLed
        )
    )

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color.Transparent,
        border = if (isSelected) {
            BorderStroke(1.5.dp, activeBorderBrush)
        } else {
            BorderStroke(1.dp, AmoledBorder)
        },
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .lightSweep(isEnabled = isSelected, shape = RoundedCornerShape(20.dp), borderWidth = 1.5.dp)
            .springPress(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Моноширинный языковой бейдж в неоновой капсуле
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(
                        if (isSelected) ActiveGreenLed.copy(alpha = 0.15f)
                        else Color.White.copy(alpha = 0.05f)
                    )
                    .border(
                        width = 1.dp,
                        color = if (isSelected) ActiveGreenLed.copy(alpha = 0.55f) else AmoledBorder,
                        shape = RoundedCornerShape(13.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = code,
                    color = if (isSelected) ActiveGreenLed else TextWhite,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.5.sp
                )
            }

            // Название языка и описание
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = TextWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = subtitle,
                    color = if (isSelected) ActiveGreenLed else Color(0xFFD4D4E0),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.2.sp
                )
            }

            // Правый статус выбора (Светящийся индикатор)
            if (isSelected) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = ActiveGreenLed.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, ActiveGreenLed.copy(alpha = 0.45f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(ActiveGreenLed)
                        )
                        Text(
                            text = stringResource(R.string.onboarding_lang_badge_selected),
                            color = ActiveGreenLed,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.8.sp
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .border(1.2.dp, Color.White.copy(alpha = 0.45f), CircleShape)
                )
            }
        }
    }
}

/**
 * Шаг 2: Выбор фона интерфейса (Динамические видео-обои, фото-обои или чистый AMOLED).
 */
@Composable
private fun StepWallpaperSelection(
    prefsManager: PreferencesManager?,
    isPreviewActive: Boolean = true
) {
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    val activePresetId by (prefsManager?.customBackgroundActivePresetIdFlow ?: remember { MutableStateFlow<String?>(null) }).collectAsState()
    val wallpaperUri by (prefsManager?.customBackgroundUriFlow ?: remember { MutableStateFlow<String?>(null) }).collectAsState()
    val userPresets by (prefsManager?.customBackgroundUserPresetsFlow ?: remember { MutableStateFlow<List<WallpaperPreset>>(emptyList()) }).collectAsState()
    val hasCustomWallpaper = wallpaperUri != null

    val isSpaceToiletActive = hasCustomWallpaper && activePresetId == WallpaperPreset.PRESET_ID_SPACE_TOILET
    val isGrassActive = hasCustomWallpaper && activePresetId == WallpaperPreset.PRESET_ID_GRASS
    val isBreezeActive = hasCustomWallpaper && activePresetId == WallpaperPreset.PRESET_ID_BREEZE
    val isRanalduActive = hasCustomWallpaper && activePresetId == WallpaperPreset.PRESET_ID_RANALDU
    val isAmoledActive = !hasCustomWallpaper ||
        activePresetId == null ||
        activePresetId == WallpaperPreset.PRESET_ID_DEFAULT_AMOLED

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Заголовок и вводный текст шага
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.onboarding_step_wallpaper_title),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.3.sp,
                color = ActiveGreenLed.copy(alpha = 0.90f)
            )
            Text(
                text = stringResource(R.string.onboarding_step_wallpaper_desc),
                fontSize = 13.5.sp,
                color = TextWhite,
                lineHeight = 19.sp
            )
        }

        Spacer(modifier = Modifier.height(2.dp))

        // 1. Чистый AMOLED (Default pure black AMOLED with cyber particles)
        val defaultAmoledPreset = remember {
            WallpaperPreset.BUILTIN_PRESETS.firstOrNull { it.id == WallpaperPreset.PRESET_ID_DEFAULT_AMOLED }
        }
        WallpaperSelectionCardItem(
            title = stringResource(R.string.onboarding_wallpaper_amoled_title),
            subtitle = stringResource(R.string.onboarding_wallpaper_amoled_desc),
            isSelected = isAmoledActive,
            previewContent = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    MirrlyIcon(
                        id = R.drawable.ic_wallpaper,
                        contentDescription = null,
                        tint = if (isAmoledActive) ActiveGreenLed else Color.White.copy(alpha = 0.50f),
                        modifier = Modifier.size(24.dp)
                    )
                }
            },
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                if (defaultAmoledPreset != null) {
                    coroutineScope.launch {
                        prefsManager?.applyPreset(defaultAmoledPreset)
                    }
                } else {
                    prefsManager?.clearCustomWallpaper()
                }
            }
        )

        // 2. Space Toilet (Dynamic video preset with 70% dimming)
        val spaceToiletPreset = remember {
            WallpaperPreset.BUILTIN_PRESETS.firstOrNull { it.id == WallpaperPreset.PRESET_ID_SPACE_TOILET }
        }
        val spaceToiletThumb = rememberWallpaperImage("wallpapers/space_toilet_preview.jpg", 0L)
        WallpaperSelectionCardItem(
            title = spaceToiletPreset?.title ?: "Space Toilet",
            subtitle = stringResource(R.string.onboarding_wallpaper_space_toilet_desc),
            isSelected = isSpaceToiletActive,
            previewContent = {
                PresetVideoPreview(
                    assetPath = "wallpapers/space_toilet_thumb.mp4",
                    previewImage = spaceToiletThumb,
                    startDelayMs = 380L,
                    isActive = isPreviewActive,
                    modifier = Modifier.fillMaxSize()
                )
            },
            onClick = {
                if (spaceToiletPreset != null) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    coroutineScope.launch {
                        prefsManager?.applyPreset(spaceToiletPreset)
                    }
                }
            }
        )

        // 3. Утренняя роса (Photo wallpaper)
        val grassPreset = remember {
            WallpaperPreset.BUILTIN_PRESETS.firstOrNull { it.id == WallpaperPreset.PRESET_ID_GRASS }
        }
        val grassImage = rememberWallpaperImage("wallpapers/wallpaper_grass_preview.jpg", 0L)
        WallpaperSelectionCardItem(
            title = grassPreset?.titleRes?.let { stringResource(it) } ?: "Утренняя роса",
            subtitle = stringResource(R.string.onboarding_wallpaper_grass_desc),
            isSelected = isGrassActive,
            previewContent = {
                if (grassImage != null) {
                    Image(
                        bitmap = grassImage.bitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            },
            onClick = {
                if (grassPreset != null) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    coroutineScope.launch {
                        prefsManager?.applyPreset(grassPreset)
                    }
                }
            }
        )

        // 4. Морской бриз (Photo wallpaper)
        val breezePreset = remember {
            WallpaperPreset.BUILTIN_PRESETS.firstOrNull { it.id == WallpaperPreset.PRESET_ID_BREEZE }
        }
        val breezeImage = rememberWallpaperImage("wallpapers/wallpaper_breeze_preview.jpg", 0L)
        WallpaperSelectionCardItem(
            title = breezePreset?.titleRes?.let { stringResource(it) } ?: "Морской бриз",
            subtitle = stringResource(R.string.onboarding_wallpaper_breeze_desc),
            isSelected = isBreezeActive,
            previewContent = {
                if (breezeImage != null) {
                    Image(
                        bitmap = breezeImage.bitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            },
            onClick = {
                if (breezePreset != null) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    coroutineScope.launch {
                        prefsManager?.applyPreset(breezePreset)
                    }
                }
            }
        )

        // 5. RANALDU (Photo wallpaper)
        val ranalduPreset = remember {
            WallpaperPreset.BUILTIN_PRESETS.firstOrNull { it.id == WallpaperPreset.PRESET_ID_RANALDU }
        }
        val ranalduImage = rememberWallpaperImage("wallpapers/wallpaper_ranaldu_preview.jpg", 0L)
        WallpaperSelectionCardItem(
            title = ranalduPreset?.titleRes?.let { stringResource(it) } ?: "RANALDU",
            subtitle = stringResource(R.string.onboarding_wallpaper_ranaldu_desc),
            isSelected = isRanalduActive,
            previewContent = {
                if (ranalduImage != null) {
                    Image(
                        bitmap = ranalduImage.bitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            },
            onClick = {
                if (ranalduPreset != null) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    coroutineScope.launch {
                        prefsManager?.applyPreset(ranalduPreset)
                    }
                }
            }
        )

        // 6. User Custom Presets (if any)
        userPresets.forEach { userPreset ->
            val isUserPresetActive = hasCustomWallpaper && activePresetId == userPreset.id
            val userPreviewImage = rememberWallpaperImage(
                path = userPreset.resolveStaticPreviewPath(),
                revision = 0L
            )
            WallpaperSelectionCardItem(
                title = userPreset.title,
                subtitle = "${userPreset.blurDp.roundToInt()} dp · ${(userPreset.dimFactor * 100).roundToInt()}%",
                isSelected = isUserPresetActive,
                previewContent = {
                    if (userPreset.type == PreferencesManager.WALLPAPER_TYPE_VIDEO) {
                        PresetVideoPreview(
                            videoPath = userPreset.filePath,
                            assetPath = userPreset.previewAssetPath?.takeIf { it.endsWith(".mp4") } ?: userPreset.assetPath,
                            previewImage = userPreviewImage,
                            startDelayMs = 380L,
                            isActive = isPreviewActive,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else if (userPreviewImage != null) {
                        Image(
                            bitmap = userPreviewImage.bitmap,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                },
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    coroutineScope.launch {
                        prefsManager?.applyPreset(userPreset)
                    }
                }
            )
        }
    }
}

/**
 * Просторная интерактивная карточка выбора фона в стиле MirrlyUI.
 */
@Composable
private fun WallpaperSelectionCardItem(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    previewContent: @Composable () -> Unit,
    onClick: () -> Unit
) {
    val activeBorderBrush = Brush.horizontalGradient(
        colors = listOf(
            ActiveGreenLed,
            ActiveGreenLed.copy(alpha = 0.80f),
            ActiveGreenLed
        )
    )

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color.Transparent,
        border = if (isSelected) {
            BorderStroke(1.5.dp, activeBorderBrush)
        } else {
            BorderStroke(1.dp, AmoledBorder)
        },
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .lightSweep(isEnabled = isSelected, shape = RoundedCornerShape(20.dp), borderWidth = 1.5.dp)
            .springPress(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Миниатюрное окно превью (54 x 70 dp) с аппаратным рендерингом
            Box(
                modifier = Modifier
                    .size(width = 54.dp, height = 70.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black)
                    .border(
                        width = 1.dp,
                        color = if (isSelected) ActiveGreenLed.copy(alpha = 0.60f) else AmoledBorder,
                        shape = RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                previewContent()
            }

            // Название фона и подпись параметров
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = TextWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    color = if (isSelected) ActiveGreenLed else Color(0xFFD4D4E0),
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }

            // Правый индикатор выбора (Светящийся чип "ВЫБРАНО" или контурный кружок)
            if (isSelected) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = ActiveGreenLed.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, ActiveGreenLed.copy(alpha = 0.45f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(ActiveGreenLed)
                        )
                        Text(
                            text = stringResource(R.string.onboarding_lang_badge_selected),
                            color = ActiveGreenLed,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.8.sp
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .border(1.2.dp, Color.White.copy(alpha = 0.45f), CircleShape)
                )
            }
        }
    }
}

/**
 * Шаг 3: Обзор раздела кастомизации интерфейса.
 */
@Composable
private fun StepCustomizationGuide() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.onboarding_step_custom_title),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.3.sp,
                color = ActiveGreenLed.copy(alpha = 0.90f)
            )
            Text(
                text = stringResource(R.string.onboarding_step_custom_desc),
                fontSize = 13.5.sp,
                color = TextWhite,
                lineHeight = 19.5.sp
            )
        }

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color.Transparent,
            border = BorderStroke(1.dp, ActiveGreenLed.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MirrlyIcon(
                    id = R.drawable.ic_settings,
                    contentDescription = null,
                    tint = ActiveGreenLed,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = stringResource(R.string.onboarding_custom_path_badge),
                    color = ActiveGreenLed,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(22.dp),
            color = Color.Transparent,
            border = BorderStroke(1.dp, AmoledBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                FeatureRowItem(
                    iconRes = R.drawable.ic_wallpaper,
                    title = stringResource(R.string.onboarding_custom_wallpaper_title),
                    detail = stringResource(R.string.onboarding_custom_wallpaper_desc)
                )

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AmoledBorder.copy(alpha = 0.6f)))

                FeatureRowItem(
                    iconRes = R.drawable.ic_flash_on,
                    title = stringResource(R.string.onboarding_custom_theme_title),
                    detail = stringResource(R.string.onboarding_custom_theme_desc)
                )

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AmoledBorder.copy(alpha = 0.6f)))

                FeatureRowItem(
                    iconRes = R.drawable.ic_power,
                    title = stringResource(R.string.onboarding_custom_button_title),
                    detail = stringResource(R.string.onboarding_custom_button_desc)
                )

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AmoledBorder.copy(alpha = 0.6f)))

                FeatureRowItem(
                    iconRes = R.drawable.ic_auto_select,
                    title = stringResource(R.string.onboarding_custom_fx_title),
                    detail = stringResource(R.string.onboarding_custom_fx_desc)
                )
            }
        }
    }
}

/**
 * Шаг 4: Прокси для Telegram (MTProto и SOCKS5).
 */
@Composable
private fun StepTelegramProxy() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.onboarding_step_proxy_title),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.3.sp,
                color = ActiveGreenLed.copy(alpha = 0.90f)
            )
            Text(
                text = stringResource(R.string.onboarding_step_proxy_desc),
                fontSize = 13.5.sp,
                color = TextWhite,
                lineHeight = 19.5.sp
            )
        }

        Spacer(modifier = Modifier.height(2.dp))

        // Основная карточка с преимуществами
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = Color.Transparent,
            border = BorderStroke(1.dp, AmoledBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                FeatureRowItem(
                    iconRes = R.drawable.ic_shield,
                    title = stringResource(R.string.onboarding_feature_mtproto_title),
                    detail = stringResource(R.string.onboarding_feature_mtproto)
                )

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AmoledBorder.copy(alpha = 0.6f)))

                FeatureRowItem(
                    iconRes = R.drawable.ic_diag_media,
                    title = stringResource(R.string.onboarding_feature_socks5_title),
                    detail = stringResource(R.string.onboarding_feature_socks5)
                )

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AmoledBorder.copy(alpha = 0.6f)))

                FeatureRowItem(
                    iconRes = R.drawable.ic_diag_cloudflare,
                    title = stringResource(R.string.onboarding_feature_cf_workers_title),
                    detail = stringResource(R.string.onboarding_feature_cf_workers)
                )

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AmoledBorder.copy(alpha = 0.6f)))

                FeatureRowItem(
                    iconRes = R.drawable.ic_speed_turbo,
                    title = stringResource(R.string.onboarding_feature_rust_title),
                    detail = stringResource(R.string.onboarding_feature_rust_desc)
                )
            }
        }
    }
}

/**
 * Шаг 5: Системный режим VPN и протоколы аплинка.
 */
@Composable
private fun StepVpnMode() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.onboarding_step_vpn_title),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.3.sp,
                color = ActiveGreenLed.copy(alpha = 0.90f)
            )
            Text(
                text = stringResource(R.string.onboarding_step_vpn_desc),
                fontSize = 13.5.sp,
                color = TextWhite,
                lineHeight = 19.5.sp
            )
        }

        Spacer(modifier = Modifier.height(2.dp))

        // Карточка с преимуществами и протоколами VPN
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = Color.Transparent,
            border = BorderStroke(1.dp, AmoledBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                FeatureRowItem(
                    iconRes = R.drawable.ic_diag_protocol,
                    title = stringResource(R.string.onboarding_vpn_feature_independent_title),
                    detail = stringResource(R.string.onboarding_vpn_feature_independent_desc)
                )

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AmoledBorder.copy(alpha = 0.6f)))

                FeatureRowItem(
                    iconRes = R.drawable.ic_speed_turbo,
                    title = stringResource(R.string.onboarding_vpn_feature_awg_title),
                    detail = stringResource(R.string.onboarding_vpn_feature_awg_desc)
                )

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AmoledBorder.copy(alpha = 0.6f)))

                FeatureRowItem(
                    iconRes = R.drawable.ic_settings,
                    title = stringResource(R.string.onboarding_vpn_feature_split_title),
                    detail = stringResource(R.string.onboarding_vpn_feature_split_desc)
                )
            }
        }
    }
}

/**
 * Строка отдельного преимущества с иконкой в капсуле.
 */
@Composable
private fun FeatureRowItem(
    iconRes: Int,
    title: String,
    detail: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(ActiveGreenLed.copy(alpha = 0.12f))
                .border(1.dp, ActiveGreenLed.copy(alpha = 0.45f), RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center
        ) {
            MirrlyIcon(
                id = iconRes,
                contentDescription = null,
                tint = ActiveGreenLed,
                modifier = Modifier.size(19.dp)
            )
        }

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                color = TextWhite,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = detail,
                color = Color(0xFFD4D4E0),
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        }
    }
}

/**
 * Шаг 6: Обязательная настройка работы в фоне, оптимизации батареи и защиты от засыпания.
 */
@Composable
private fun StepBackgroundProtection(
    prefsManager: PreferencesManager?,
    isIgnoringBattery: Boolean,
    isOemSupported: Boolean,
    hasOpenedOemSettings: Boolean,
    isOemConfirmed: Boolean,
    onRequestBatteryExemption: () -> Unit,
    onOpenOemSettings: () -> Unit,
    onToggleOemConfirmed: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val oemFamily = remember { OemAutostartHelper.getOemFamily() }
    val oemBrandName = remember { OemAutostartHelper.getOemBrandName() }

    val appInstance = remember { runCatching { MirrlyApplication.instance }.getOrNull() }
    val isCpuAntiFreeze by (prefsManager?.isCpuAntiFreezeEnabledFlow
        ?: remember { MutableStateFlow(appInstance?.config?.isCpuAntiFreezeEnabled ?: false) }).collectAsState()
    val isAlarmClockWatchdog by (prefsManager?.isAlarmClockWatchdogEnabledFlow
        ?: remember { MutableStateFlow(appInstance?.config?.isAlarmClockWatchdogEnabled ?: true) }).collectAsState()
    val isTrafficPulse by (prefsManager?.isTrafficPulseEnabledFlow
        ?: remember { MutableStateFlow(appInstance?.config?.isTrafficPulseEnabled ?: true) }).collectAsState()

    var isSurvivalExpanded by rememberSaveable { mutableStateOf(true) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (isSurvivalExpanded) 180f else 0f,
        animationSpec = tween(220),
        label = "bgSurvivalChevron"
    )
    val enabledCount = (if (isCpuAntiFreeze) 1 else 0) +
        (if (isAlarmClockWatchdog) 1 else 0) +
        (if (isTrafficPulse) 1 else 0)

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.onboarding_step_background_title),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.3.sp,
                color = ActiveGreenLed.copy(alpha = 0.90f)
            )
            Text(
                text = stringResource(R.string.onboarding_step_background_desc),
                fontSize = 13.5.sp,
                color = TextWhite,
                lineHeight = 19.sp
            )
        }

        // Карточка 1: Исключение из оптимизации батареи (Doze Mode)
        val batteryBorderColor by animateColorAsState(
            targetValue = if (isIgnoringBattery) ActiveGreenLed.copy(alpha = 0.65f) else BloodRed.copy(alpha = 0.55f),
            animationSpec = tween(240),
            label = "bgBatteryBorder"
        )
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color.Transparent,
            border = BorderStroke(if (isIgnoringBattery) 1.5.dp else 1.dp, batteryBorderColor),
            modifier = Modifier
                .fillMaxWidth()
                .lightSweep(isEnabled = isIgnoringBattery, shape = RoundedCornerShape(20.dp), borderWidth = 1.5.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .border(
                                    1.dp,
                                    if (isIgnoringBattery) ActiveGreenLed.copy(alpha = 0.55f) else AmoledBorder,
                                    RoundedCornerShape(11.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            MirrlyIcon(
                                id = R.drawable.ic_shield,
                                contentDescription = null,
                                tint = if (isIgnoringBattery) ActiveGreenLed else TextWhite,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                        Text(
                            text = stringResource(R.string.onboarding_bg_battery_title),
                            color = TextWhite,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    OnboardingStatusBadge(
                        text = if (isIgnoringBattery) {
                            stringResource(R.string.onboarding_bg_status_active)
                        } else {
                            stringResource(R.string.onboarding_bg_status_required)
                        },
                        isSatisfied = isIgnoringBattery
                    )
                }

                Text(
                    text = stringResource(R.string.onboarding_bg_battery_desc),
                    color = Color(0xFFD4D4E0),
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )

                Surface(
                    shape = RoundedCornerShape(13.dp),
                    color = Color.Transparent,
                    border = BorderStroke(
                        1.dp,
                        if (isIgnoringBattery) ActiveGreenLed.copy(alpha = 0.55f) else ActiveGreenLed.copy(alpha = 0.75f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .springPress(onClick = {
                            HapticHelper.performTapClick(context)
                            onRequestBatteryExemption()
                        })
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isIgnoringBattery) {
                            MirrlyIcon(
                                id = R.drawable.ic_check_circle,
                                contentDescription = null,
                                tint = ActiveGreenLed,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = if (isIgnoringBattery) {
                                stringResource(R.string.onboarding_bg_battery_done)
                            } else {
                                stringResource(R.string.onboarding_bg_battery_btn)
                            },
                            color = ActiveGreenLed,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Карточка 2: Автозапуск и ручной режим в OEM-оболочке
        val oemSatisfied = !isOemSupported || isOemConfirmed
        val oemBorderColor by animateColorAsState(
            targetValue = when {
                !isOemSupported -> AmoledBorder
                isOemConfirmed -> ActiveGreenLed.copy(alpha = 0.65f)
                else -> BloodRed.copy(alpha = 0.55f)
            },
            animationSpec = tween(240),
            label = "bgOemBorder"
        )
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color.Transparent,
            border = BorderStroke(if (isOemSupported && isOemConfirmed) 1.5.dp else 1.dp, oemBorderColor),
            modifier = Modifier
                .fillMaxWidth()
                .lightSweep(
                    isEnabled = isOemSupported && isOemConfirmed,
                    shape = RoundedCornerShape(20.dp),
                    borderWidth = 1.5.dp
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .border(
                                    1.dp,
                                    if (isOemSupported && isOemConfirmed) ActiveGreenLed.copy(alpha = 0.55f) else AmoledBorder,
                                    RoundedCornerShape(11.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            MirrlyIcon(
                                id = R.drawable.ic_oem_startup,
                                contentDescription = null,
                                tint = if (isOemSupported && isOemConfirmed) ActiveGreenLed else TextWhite,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = stringResource(R.string.onboarding_bg_oem_title),
                                color = TextWhite,
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = oemBrandName,
                                color = if (isOemSupported) ActiveGreenLed.copy(alpha = 0.85f) else TextMuted,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    OnboardingStatusBadge(
                        text = when {
                            !isOemSupported -> stringResource(R.string.onboarding_bg_status_optional)
                            isOemConfirmed -> stringResource(R.string.onboarding_bg_status_confirmed)
                            else -> stringResource(R.string.onboarding_bg_status_required)
                        },
                        isSatisfied = oemSatisfied,
                        isNeutral = !isOemSupported
                    )
                }

                val oemStepsText = when (oemFamily) {
                    OemFamily.HONOR_HUAWEI -> stringResource(R.string.onboarding_bg_oem_steps_honor)
                    OemFamily.XIAOMI -> stringResource(R.string.onboarding_bg_oem_steps_xiaomi)
                    OemFamily.OPPO_REALME_ONEPLUS -> stringResource(R.string.onboarding_bg_oem_steps_oppo)
                    OemFamily.VIVO -> stringResource(R.string.onboarding_bg_oem_steps_vivo)
                    OemFamily.SAMSUNG,
                    OemFamily.OTHER -> stringResource(R.string.onboarding_bg_oem_steps_generic)
                }

                Text(
                    text = oemStepsText,
                    color = Color(0xFFD4D4E0),
                    fontSize = 12.sp,
                    lineHeight = 17.5.sp
                )

                Surface(
                    shape = RoundedCornerShape(13.dp),
                    color = Color.Transparent,
                    border = BorderStroke(
                        1.dp,
                        if (isOemSupported) ActiveGreenLed.copy(alpha = 0.70f) else AmoledBorder
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .springPress(onClick = {
                            HapticHelper.performTapClick(context)
                            onOpenOemSettings()
                        })
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MirrlyIcon(
                            id = R.drawable.ic_oem_startup,
                            contentDescription = null,
                            tint = if (isOemSupported) ActiveGreenLed else TextWhite,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (hasOpenedOemSettings) {
                                stringResource(R.string.onboarding_bg_oem_btn_reopen)
                            } else {
                                stringResource(R.string.onboarding_bg_oem_btn)
                            },
                            color = if (isOemSupported) ActiveGreenLed else TextWhite,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (isOemSupported) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(AmoledBorder.copy(alpha = 0.55f))
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                onToggleOemConfirmed(!isOemConfirmed)
                            }
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f).padding(end = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.onboarding_bg_oem_confirm_title),
                                color = if (hasOpenedOemSettings) TextWhite else TextMuted,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (!hasOpenedOemSettings) {
                                Text(
                                    text = stringResource(R.string.onboarding_bg_oem_confirm_hint),
                                    color = TextMuted,
                                    fontSize = 11.sp,
                                    lineHeight = 14.5.sp
                                )
                            }
                        }
                        InertialSpringSwitch(
                            checked = isOemConfirmed,
                            enabled = hasOpenedOemSettings,
                            onCheckedChange = { checked ->
                                onToggleOemConfirmed(checked)
                            }
                        )
                    }
                }
            }
        }

        // Карточка 3: Выбор механизмов защиты от засыпания в прошивке (сворачиваемый аккордеон)
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color.Transparent,
            border = BorderStroke(1.dp, AmoledBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            isSurvivalExpanded = !isSurvivalExpanded
                        },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.onboarding_bg_toggles_title),
                            color = TextWhite,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.onboarding_bg_toggles_summary, enabledCount, 3),
                            color = ActiveGreenLed.copy(alpha = 0.85f),
                            fontSize = 11.5.sp
                        )
                    }
                    MirrlyIcon(
                        id = R.drawable.ic_arrow_down,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier
                            .size(18.dp)
                            .rotate(chevronRotation)
                    )
                }

                AnimatedVisibility(
                    visible = isSurvivalExpanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(AmoledBorder.copy(alpha = 0.55f))
                        )

                        OnboardingSurvivalToggleRow(
                            iconRes = R.drawable.ic_cpu_antifreeze,
                            title = stringResource(R.string.settings_cpu_antifreeze_title),
                            description = stringResource(R.string.onboarding_bg_cpu_antifreeze_desc),
                            recommendation = if (isOemSupported) stringResource(R.string.onboarding_bg_recommended_badge) else null,
                            checked = isCpuAntiFreeze,
                            onCheckedChange = { checked ->
                                appInstance?.let { app ->
                                    app.config.isCpuAntiFreezeEnabled = checked
                                    app.saveConfig()
                                    ProxyForegroundService.applyCpuAntiFreezeDirectly(checked)
                                }
                            }
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(AmoledBorder.copy(alpha = 0.45f))
                        )

                        OnboardingSurvivalToggleRow(
                            iconRes = R.drawable.ic_watchdog_clock,
                            title = stringResource(R.string.settings_watchdog_alarm_title),
                            description = stringResource(R.string.onboarding_bg_watchdog_desc),
                            recommendation = null,
                            checked = isAlarmClockWatchdog,
                            onCheckedChange = { checked ->
                                appInstance?.let { app ->
                                    app.config.isAlarmClockWatchdogEnabled = checked
                                    app.saveConfig()
                                    if (app.prefsManager.isProxyActive()) {
                                        ProxyResurrectionManager.startWatchdog(context)
                                    }
                                }
                            }
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(AmoledBorder.copy(alpha = 0.45f))
                        )

                        OnboardingSurvivalToggleRow(
                            iconRes = R.drawable.ic_traffic_pulse,
                            title = stringResource(R.string.settings_traffic_pulse_title),
                            description = stringResource(R.string.onboarding_bg_traffic_pulse_desc),
                            recommendation = null,
                            checked = isTrafficPulse,
                            onCheckedChange = { checked ->
                                appInstance?.let { app ->
                                    app.config.isTrafficPulseEnabled = checked
                                    app.saveConfig()
                                    ProxyForegroundService.applyTrafficPulseDirectly(checked)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OnboardingStatusBadge(
    text: String,
    isSatisfied: Boolean,
    isNeutral: Boolean = false
) {
    val accent = when {
        isNeutral -> TextMuted
        isSatisfied -> ActiveGreenLed
        else -> BloodRed
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, accent.copy(alpha = if (isNeutral) 0.40f else 0.65f))
    ) {
        Text(
            text = text,
            color = accent,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun OnboardingSurvivalToggleRow(
    iconRes: Int,
    title: String,
    description: String,
    recommendation: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f).padding(end = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                MirrlyIcon(
                    id = iconRes,
                    contentDescription = null,
                    tint = if (checked) ActiveGreenLed else TextMuted,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = title,
                    color = TextWhite,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = description,
                color = TextMuted,
                fontSize = 11.5.sp,
                lineHeight = 15.5.sp
            )
            if (recommendation != null) {
                Text(
                    text = recommendation,
                    color = ActiveGreenLed.copy(alpha = 0.85f),
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        InertialSpringSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

/**
 * Шаг 7: Быстрый старт (Три простых шага для настройки защищенного соединения).
 */
@Composable
private fun StepQuickStartGuide() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.onboarding_step_guide_title),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.3.sp,
                color = ActiveGreenLed.copy(alpha = 0.90f)
            )
            Text(
                text = stringResource(R.string.onboarding_step_guide_desc),
                fontSize = 13.5.sp,
                color = TextWhite,
                lineHeight = 19.5.sp
            )
        }

        Spacer(modifier = Modifier.height(2.dp))

        Surface(
            shape = RoundedCornerShape(22.dp),
            color = Color.Transparent,
            border = BorderStroke(1.dp, AmoledBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                QuickStartStepRow(
                    stepNumber = "1",
                    iconRes = R.drawable.ic_diag_protocol,
                    title = stringResource(R.string.onboarding_guide_1_title),
                    description = stringResource(R.string.onboarding_guide_1_desc)
                )

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AmoledBorder.copy(alpha = 0.6f)))

                QuickStartStepRow(
                    stepNumber = "2",
                    iconRes = R.drawable.ic_power,
                    title = stringResource(R.string.onboarding_guide_2_title),
                    description = stringResource(R.string.onboarding_guide_2_desc)
                )

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AmoledBorder.copy(alpha = 0.6f)))

                QuickStartStepRow(
                    stepNumber = "3",
                    iconRes = R.drawable.ic_telegram,
                    title = stringResource(R.string.onboarding_guide_3_title),
                    description = stringResource(R.string.onboarding_guide_3_desc)
                )
            }
        }
    }
}

/**
 * Строка шага руководства с нумерацией и пояснением.
 */
@Composable
private fun QuickStartStepRow(
    stepNumber: String,
    iconRes: Int,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(ActiveGreenLed.copy(alpha = 0.14f))
                .border(1.dp, ActiveGreenLed.copy(alpha = 0.6f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stepNumber,
                color = ActiveGreenLed,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace
            )
        }

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MirrlyIcon(
                    id = iconRes,
                    contentDescription = null,
                    tint = TextWhite,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = title,
                    color = TextWhite,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = description,
                color = Color(0xFFD4D4E0),
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        }
    }
}
