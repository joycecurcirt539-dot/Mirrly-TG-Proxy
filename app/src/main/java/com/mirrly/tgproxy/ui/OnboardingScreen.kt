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

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.ui.theme.*
import kotlinx.coroutines.launch

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
@Composable
fun OnboardingScreen(
    initialLanguage: String = "ru",
    onLanguageSelected: (String) -> Unit,
    onComplete: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    var currentLang by remember { mutableStateOf(initialLanguage) }
    var currentStep by remember { mutableIntStateOf(0) } // 0: Язык, 1: О проекте, 2: Быстрый старт
    val totalSteps = 3

    val density = LocalDensity.current.density
    val langBlurAnim = remember { Animatable(0f) }
    val langBlurVal = langBlurAnim.value

    val textBlurModifier = if (langBlurVal > 0.005f) {
        Modifier.graphicsLayer {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val blurPx = langBlurVal * 16f * density
                if (blurPx > 0.5f) {
                    renderEffect = RenderEffect.createBlurEffect(
                        blurPx,
                        blurPx,
                        Shader.TileMode.CLAMP
                    ).asComposeRenderEffect()
                }
            }
            alpha = (1f - langBlurVal * 0.45f).coerceIn(0.55f, 1f)
        }
    } else {
        Modifier
    }

    // Анимационный контроллер плавного выхода на главный экран
    val exitProgress = remember { Animatable(0f) }
    var isExiting by remember { mutableStateOf(false) }

    fun executeExit() {
        if (isExiting) return
        isExiting = true
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        coroutineScope.launch {
            exitProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing)
            )
            onComplete()
        }
    }

    BackHandler {
        if (!isExiting) {
            if (currentStep > 0) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                currentStep -= 1
            } else {
                executeExit()
            }
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
            .background(Color.Transparent)
            .pointerInput(Unit) {
                // Consume after child controls have processed the event. Consuming on the
                // Main pass prevented clickable cards and navigation buttons from receiving
                // their taps on some devices (notably Android 14 / One UI).
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        event.changes.forEach { it.consume() }
                    }
                }
            }
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
                .padding(horizontal = 22.dp)
                .then(textBlurModifier),
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
                            targetValue = if (isSelected) ActiveGreenLed else Color.White.copy(alpha = 0.16f),
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
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        currentStep = i
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
                                        if (selected != currentLang && !isExiting && !langBlurAnim.isRunning) {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            coroutineScope.launch {
                                                langBlurAnim.animateTo(
                                                    targetValue = 1f,
                                                    animationSpec = tween(durationMillis = 130, easing = FastOutLinearInEasing)
                                                )
                                                currentLang = selected
                                                onLanguageSelected(selected)
                                                langBlurAnim.animateTo(
                                                    targetValue = 0f,
                                                    animationSpec = tween(durationMillis = 180, easing = LinearOutSlowInEasing)
                                                )
                                            }
                                        }
                                    }
                                )
                            }
                            1 -> {
                                StepAboutProject()
                            }
                            2 -> {
                                StepQuickStartGuide()
                            }
                        }
                    }
                }
            }

            // ── 3. НИЖНЯЯ ПАНЕЛЬ ДЕЙСТВИЙ (Увеличенный отступ от экранных кнопок) ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = safeBottomMargin, top = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Вторичная кнопка («Назад» или «Пропустить»)
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, AmoledBorder),
                    modifier = Modifier
                        .height(52.dp)
                        .springPress(
                            onClick = {
                                if (!isExiting) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    if (currentStep > 0) {
                                        currentStep -= 1
                                    } else {
                                        executeExit()
                                    }
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
                            color = TextMuted,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Первичная кнопка («Далее» или «Начать работу») с неоновым свечением
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.5.dp, ActiveGreenLed.copy(alpha = 0.75f)),
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .lightSweep(isEnabled = true, shape = RoundedCornerShape(16.dp))
                        .springPress(
                            onClick = {
                                if (!isExiting) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    if (currentStep < totalSteps - 1) {
                                        currentStep += 1
                                    } else {
                                        executeExit()
                                    }
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
                                text = if (currentStep == totalSteps - 1) {
                                    stringResource(R.string.onboarding_btn_start)
                                } else {
                                    stringResource(R.string.onboarding_btn_next)
                                },
                                color = ActiveGreenLed,
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.4.sp
                            )
                            Icon(
                                painter = painterResource(id = R.drawable.ic_chevron_right),
                                contentDescription = null,
                                tint = ActiveGreenLed,
                                modifier = Modifier.size(16.dp)
                            )
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
                color = TextMuted
            )
            Text(
                text = stringResource(R.string.onboarding_step_language_desc),
                fontSize = 13.5.sp,
                color = TextWhite.copy(alpha = 0.88f),
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
                    color = if (isSelected) TextWhite else TextWhite.copy(alpha = 0.88f),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = subtitle,
                    color = if (isSelected) ActiveGreenLed.copy(alpha = 0.85f) else TextMuted,
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
                        .border(1.2.dp, Color.White.copy(alpha = 0.22f), CircleShape)
                )
            }
        }
    }
}

/**
 * Шаг 2: О проекте (Архитектурные преимущества и безопасность).
 */
@Composable
private fun StepAboutProject() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.onboarding_step_about_title),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.3.sp,
                color = TextMuted
            )
            Text(
                text = stringResource(R.string.onboarding_step_about_desc),
                fontSize = 13.5.sp,
                color = TextWhite.copy(alpha = 0.88f),
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
            Icon(
                painter = painterResource(id = iconRes),
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
                color = TextMuted,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        }
    }
}

/**
 * Шаг 3: Быстрый старт (Три простых шага для настройки защищенного соединения).
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
                color = TextMuted
            )
            Text(
                text = stringResource(R.string.onboarding_step_guide_desc),
                fontSize = 13.5.sp,
                color = TextWhite.copy(alpha = 0.88f),
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
                Icon(
                    painter = painterResource(id = iconRes),
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
                color = TextMuted,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        }
    }
}
