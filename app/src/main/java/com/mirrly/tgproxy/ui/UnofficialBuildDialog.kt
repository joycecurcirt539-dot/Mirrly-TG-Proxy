package com.mirrly.tgproxy.ui
import com.mirrly.tgproxy.ui.icon.MirrlyIcon

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.service.LaunchCountManager
import com.mirrly.tgproxy.ui.theme.ActiveGreenLed
import com.mirrly.tgproxy.ui.theme.AmoledBackground
import com.mirrly.tgproxy.ui.theme.AmoledBorder
import com.mirrly.tgproxy.ui.theme.TextMuted
import com.mirrly.tgproxy.ui.theme.TextWhite
import com.mirrly.tgproxy.ui.theme.springPress
import com.mirrly.tgproxy.util.SignatureVerifier
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnofficialBuildDialog(
    onDismiss: () -> Unit,
    isUnofficialDetected: Boolean = false,
    isManualOpen: Boolean = false
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    val officialTelegramUrl = "https://t.me/WhyOKyHb"
    val officialReleasesUrl = "https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/releases"

    val isAlreadyPermanentlyDismissed = remember {
        LaunchCountManager.isSecurityNoticeDismissedForever(context)
    }
    var isAcknowledged by remember(isAlreadyPermanentlyDismissed, isManualOpen) {
        mutableStateOf(if (isManualOpen) isAlreadyPermanentlyDismissed else false)
    }

    // Если галочка не прожата, окно нельзя закрыть и кнопка "Понятно" недоступна
    val isDismissEnabled = isAcknowledged

    BackHandler(enabled = !isDismissEnabled) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    val isDismissEnabledState = rememberUpdatedState(isDismissEnabled)

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = remember {
            { targetValue ->
                if (!isDismissEnabledState.value) {
                    targetValue != SheetValue.Hidden
                } else {
                    true
                }
            }
        }
    )

    fun handleDismiss() {
        LaunchCountManager.setSecurityNoticeDismissedForever(context, true)
        LaunchCountManager.onDailySecurityNoticeDismissed(context)
        onDismiss()
    }

    var confirmUrl by remember { mutableStateOf<String?>(null) }
    if (confirmUrl != null) {
        ExternalLinkConfirmDialog(
            url = confirmUrl!!,
            onDismiss = { confirmUrl = null },
            onConfirmed = {
                confirmUrl = null
            }
        )
    }

    val actualSha = remember { SignatureVerifier.getSignatureSha256(context) }
    val reportJsonStr = remember { SignatureVerifier.getSecurityReportJson() }

    val reportObj = remember(reportJsonStr) {
        try {
            if (!reportJsonStr.isNullOrBlank()) JSONObject(reportJsonStr) else null
        } catch (_: Exception) {
            null
        }
    }

    val officialSha = reportObj?.optString("official_fingerprint")?.takeIf { it.isNotBlank() }
        ?: "00:32:69:CB:70:12:DF:37:61:E7:D3:92:79:5C:C3:55:02:43:39:A4:B2:1B:3F:B5:75:A4:C9:2B:CC:DD:25:16"

    val warningAmber = Color(0xFFFF9E00)
    val alertRed = Color(0xFFFF453A)
    val telegramCyan = Color(0xFF26A5E4)

    val categoryColor = if (isUnofficialDetected) alertRed else warningAmber
    val categoryText = if (isUnofficialDetected) {
        stringResource(R.string.security_advisory_category_untrusted)
    } else {
        stringResource(R.string.security_advisory_category_daily)
    }

    MirrlyModalBottomSheet(
        onDismissRequest = {
            if (isDismissEnabled) {
                handleDismiss()
            }
        },
        sheetState = sheetState,
        showDragHandle = false,
        containerColor = AmoledBackground.copy(alpha = 0.88f),
        scrimColor = AmoledBackground.copy(alpha = 0.55f),
        maxTabletWidth = 460.dp
    ) {
        rememberDialogBlurState(blurRadiusPx = 60)

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(top = 14.dp, bottom = 12.dp)
        ) {
            Box(
                modifier = Modifier
                    .border(1.dp, categoryColor.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .background(categoryColor.copy(alpha = 0.08f))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = categoryText,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = categoryColor,
                    letterSpacing = 0.5.sp
                )
            }

            Text(
                text = stringResource(R.string.security_advisory_title),
                fontSize = 15.5.sp,
                fontWeight = FontWeight.Bold,
                color = TextWhite,
                textAlign = TextAlign.Center
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, AmoledBorder, RoundedCornerShape(14.dp))
                    .background(Color.Transparent)
                    .padding(12.dp)
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SecurityBulletItem(
                        icon = { MirrlySecurityShieldIcon(color = telegramCyan) },
                        text = stringResource(R.string.security_advisory_bullet_official),
                        textColor = TextWhite
                    )
                    SecurityBulletItem(
                        icon = { MirrlyGatewayLockIcon(color = warningAmber) },
                        text = stringResource(R.string.security_advisory_bullet_gateway),
                        textColor = warningAmber
                    )
                    SecurityBulletItem(
                        icon = { MirrlyCrystalDonationIcon(color = ActiveGreenLed) },
                        text = stringResource(R.string.security_advisory_bullet_free),
                        textColor = ActiveGreenLed
                    )
                    SecurityBulletItem(
                        icon = { MirrlyCounterfeitRadarIcon(color = alertRed) },
                        text = stringResource(R.string.security_advisory_bullet_patterns),
                        textColor = TextWhite
                    )

                    if (isUnofficialDetected && actualSha.isNotBlank() && actualSha != officialSha) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "SHA-256: $actualSha",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            color = alertRed,
                            lineHeight = 12.sp
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, telegramCyan.copy(alpha = 0.75f), RoundedCornerShape(12.dp))
                        .background(Color.Transparent)
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            confirmUrl = officialTelegramUrl
                        }),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        MirrlyIcon(
                            id = R.drawable.ic_telegram,
                            contentDescription = null,
                            tint = telegramCyan,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = stringResource(R.string.security_advisory_btn_telegram),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = telegramCyan
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, ActiveGreenLed.copy(alpha = 0.75f), RoundedCornerShape(12.dp))
                        .background(Color.Transparent)
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            confirmUrl = officialReleasesUrl
                        }),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        MirrlyIcon(
                            id = R.drawable.ic_github,
                            contentDescription = null,
                            tint = ActiveGreenLed,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = stringResource(R.string.security_advisory_btn_github),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = ActiveGreenLed
                        )
                    }
                }
            }

            // Предупреждение об ответственности и официальных источниках
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, AmoledBorder.copy(alpha = 0.65f), RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.02f))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                val prefix = stringResource(R.string.security_advisory_disclaimer_prefix)
                val andText = stringResource(R.string.security_advisory_disclaimer_and)
                val suffix = stringResource(R.string.security_advisory_disclaimer_suffix)
                val githubColor = ActiveGreenLed

                val prefixClean = prefix.trimEnd()
                val andClean = andText.trim()
                val suffixClean = suffix.trimStart()

                val annotatedDisclaimer = remember(prefixClean, andClean, suffixClean, githubColor) {
                    buildAnnotatedString {
                        append(prefixClean)
                        append(" ")
                        pushStringAnnotation(tag = "URL_GITHUB", annotation = officialReleasesUrl)
                        withStyle(
                            SpanStyle(
                                color = githubColor,
                                fontWeight = FontWeight.Bold,
                                textDecoration = TextDecoration.Underline
                            )
                        ) {
                            append("GitHub Releases")
                        }
                        pop()
                        append(" ")
                        append(andClean)
                        append(" ")
                        pushStringAnnotation(tag = "URL_TG", annotation = officialTelegramUrl)
                        withStyle(
                            SpanStyle(
                                color = telegramCyan,
                                fontWeight = FontWeight.Bold,
                                textDecoration = TextDecoration.Underline
                            )
                        ) {
                            append("Telegram @WhyOKyHb")
                        }
                        pop()
                        if (suffixClean.startsWith(".") || suffixClean.startsWith(",")) {
                            append(suffixClean)
                        } else {
                            append(" ")
                            append(suffixClean)
                        }
                    }
                }

                ClickableText(
                    text = annotatedDisclaimer,
                    style = TextStyle(
                        fontSize = 11.sp,
                        color = TextWhite.copy(alpha = 0.82f),
                        lineHeight = 15.5.sp,
                        textAlign = TextAlign.Start
                    ),
                    onClick = { offset ->
                        annotatedDisclaimer.getStringAnnotations(tag = "URL_GITHUB", start = offset, end = offset).firstOrNull()?.let {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            confirmUrl = it.item
                        }
                        annotatedDisclaimer.getStringAnnotations(tag = "URL_TG", start = offset, end = offset).firstOrNull()?.let {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            confirmUrl = it.item
                        }
                    }
                )
            }

            // Чекбокс MirrlyUI: "Я ознакомлен/на с информацией" (Zero-Card, чистая интерактивная строка)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .springPress(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        isAcknowledged = !isAcknowledged
                    })
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MirrlyVectorCheckbox(
                    checked = isAcknowledged,
                    accentColor = ActiveGreenLed
                )
                Text(
                    text = stringResource(R.string.security_advisory_checkbox_acknowledged),
                    fontSize = 12.sp,
                    fontWeight = if (isAcknowledged) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isAcknowledged) TextWhite else TextWhite.copy(alpha = 0.75f),
                    lineHeight = 16.sp
                )
            }

            // Кнопка действия (доступна только при прожатой галочке)
            val dismissBorderColor = if (isDismissEnabled) ActiveGreenLed.copy(alpha = 0.85f) else AmoledBorder.copy(alpha = 0.45f)
            val dismissTextColor = if (isDismissEnabled) ActiveGreenLed else TextMuted
            val dismissBgColor = if (isDismissEnabled) ActiveGreenLed.copy(alpha = 0.08f) else Color.Transparent

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, dismissBorderColor, RoundedCornerShape(12.dp))
                    .background(dismissBgColor)
                    .springPress(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (isDismissEnabled) {
                            handleDismiss()
                        }
                    }),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (isDismissEnabled) {
                        MirrlyButtonCheckIcon(color = ActiveGreenLed)
                    } else {
                        MirrlyButtonLockIcon(color = TextMuted)
                    }
                    Text(
                        text = if (isDismissEnabled) {
                            stringResource(R.string.security_advisory_btn_dismiss_ready)
                        } else {
                            stringResource(R.string.security_advisory_btn_acknowledge_required)
                        },
                        fontSize = 12.sp,
                        fontWeight = if (isDismissEnabled) FontWeight.Bold else FontWeight.Medium,
                        color = dismissTextColor
                    )
                }
            }
        }
    }
}

/**
 * Процедурный контурный круговой чекбокс по стандартам MirrlyUI (Outline-Only, zero-allocation).
 * Элегантный неоновый кибер-индикатор без тяжелых рамок и фонов.
 */
@Composable
fun MirrlyVectorCheckbox(
    checked: Boolean,
    accentColor: Color = Color(0xFF00FF87),
    modifier: Modifier = Modifier
) {
    val checkProgress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "MirrlyCheckboxTick"
    )
    val indicatorScale by animateFloatAsState(
        targetValue = if (checked) 1.08f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "MirrlyCheckboxScale"
    )

    val tickPath = remember { Path() }

    Box(
        modifier = modifier
            .size(22.dp)
            .scale(indicatorScale),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val center = Offset(w * 0.5f, h * 0.5f)
            val strokeWidth = 1.5.dp.toPx()
            val radius = (w.coerceAtMost(h) - strokeWidth) * 0.5f

            // 1. Неоновая аура свечения при активации
            if (checkProgress > 0.01f) {
                drawCircle(
                    color = accentColor.copy(alpha = 0.20f * checkProgress),
                    radius = radius + 2.5.dp.toPx() * checkProgress,
                    center = center
                )
            }

            // 2. Внутреннее заполнение ядра
            val innerFillColor = if (checkProgress > 0.01f) {
                accentColor.copy(alpha = 0.20f * checkProgress)
            } else {
                Color.White.copy(alpha = 0.04f)
            }
            drawCircle(
                color = innerFillColor,
                radius = radius,
                center = center
            )

            // 3. Контурное кольцо: Outline-Only
            val ringColor = if (checkProgress > 0.01f) {
                accentColor.copy(alpha = 0.40f + 0.60f * checkProgress)
            } else {
                AmoledBorder.copy(alpha = 0.85f)
            }
            drawCircle(
                color = ringColor,
                radius = radius,
                center = center,
                style = Stroke(width = strokeWidth)
            )

            // 4. Процедурная галочка с плавной анимацией прорисовки
            if (checkProgress > 0.01f) {
                tickPath.reset()
                val p1 = Offset(w * 0.28f, h * 0.52f)
                val p2 = Offset(w * 0.44f, h * 0.68f)
                val p3 = Offset(w * 0.72f, h * 0.36f)

                tickPath.moveTo(p1.x, p1.y)
                if (checkProgress <= 0.45f) {
                    val t = checkProgress / 0.45f
                    val curX = p1.x + (p2.x - p1.x) * t
                    val curY = p1.y + (p2.y - p1.y) * t
                    tickPath.lineTo(curX, curY)
                } else {
                    tickPath.lineTo(p2.x, p2.y)
                    val t = (checkProgress - 0.45f) / 0.55f
                    val curX = p2.x + (p3.x - p2.x) * t
                    val curY = p2.y + (p3.y - p2.y) * t
                    tickPath.lineTo(curX, curY)
                }

                drawPath(
                    path = tickPath,
                    color = Color.White,
                    style = Stroke(
                        width = 1.9.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }
        }
    }
}

/**
 * Пункт памятки с процедурной иконкой из скилла /mirrly-icons.
 */
@Composable
private fun SecurityBulletItem(
    icon: @Composable () -> Unit,
    text: String,
    textColor: Color
) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(16.dp),
            contentAlignment = Alignment.Center
        ) {
            icon()
        }
        Text(
            text = text,
            fontSize = 11.5.sp,
            color = textColor.copy(alpha = 0.92f),
            lineHeight = 15.sp
        )
    }
}

/**
 * Иконка 1: Кибер-щит с ядром верификации (Mirrly Icons).
 */
@Composable
fun MirrlySecurityShieldIcon(
    color: Color = Color(0xFF26A5E4),
    modifier: Modifier = Modifier.size(16.dp)
) {
    val path = remember { Path() }
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val strokeWidth = 1.5.dp.toPx()

        path.reset()
        path.moveTo(w * 0.5f, h * 0.12f)
        path.cubicTo(w * 0.68f, h * 0.10f, w * 0.82f, h * 0.16f, w * 0.88f, h * 0.28f)
        path.cubicTo(w * 0.88f, h * 0.55f, w * 0.72f, h * 0.75f, w * 0.5f, h * 0.90f)
        path.cubicTo(w * 0.28f, h * 0.75f, w * 0.12f, h * 0.55f, w * 0.12f, h * 0.28f)
        path.cubicTo(w * 0.18f, h * 0.16f, w * 0.32f, h * 0.10f, w * 0.5f, h * 0.12f)
        path.close()

        drawPath(
            path = path,
            color = color,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // Внутренний вертикальный световой стержень аутентификации
        drawLine(
            color = color.copy(alpha = 0.85f),
            start = Offset(w * 0.5f, h * 0.32f),
            end = Offset(w * 0.5f, h * 0.65f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
    }
}

/**
 * Иконка 2: Сетевой шлюз с блокировкой (Mirrly Icons).
 */
@Composable
fun MirrlyGatewayLockIcon(
    color: Color = Color(0xFFFF9E00),
    modifier: Modifier = Modifier.size(16.dp)
) {
    val shacklePath = remember { Path() }
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val strokeWidth = 1.5.dp.toPx()

        // Дужка замка
        shacklePath.reset()
        shacklePath.moveTo(w * 0.32f, h * 0.44f)
        shacklePath.lineTo(w * 0.32f, h * 0.28f)
        shacklePath.cubicTo(w * 0.32f, h * 0.12f, w * 0.68f, h * 0.12f, w * 0.68f, h * 0.28f)
        shacklePath.lineTo(w * 0.68f, h * 0.44f)

        drawPath(
            path = shacklePath,
            color = color,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // Корпус замка / шлюза
        val bodyRect = Rect(w * 0.18f, h * 0.44f, w * 0.82f, h * 0.88f)
        drawRoundRect(
            color = color,
            topLeft = Offset(bodyRect.left, bodyRect.top),
            size = Size(bodyRect.width, bodyRect.height),
            cornerRadius = CornerRadius(2.5.dp.toPx(), 2.5.dp.toPx()),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // Точка сетевого сокета шлюза
        drawCircle(
            color = color,
            radius = strokeWidth * 0.85f,
            center = Offset(w * 0.5f, h * 0.60f)
        )
        drawLine(
            color = color,
            start = Offset(w * 0.5f, h * 0.65f),
            end = Offset(w * 0.5f, h * 0.76f),
            strokeWidth = strokeWidth * 0.9f,
            cap = StrokeCap.Round
        )
    }
}

/**
 * Иконка 3: Кристалл чистоты / Open Source без рекламы (Mirrly Icons).
 */
@Composable
fun MirrlyCrystalDonationIcon(
    color: Color = Color(0xFF00FF87),
    modifier: Modifier = Modifier.size(16.dp)
) {
    val outerPath = remember { Path() }
    val facetPath = remember { Path() }
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val strokeWidth = 1.5.dp.toPx()

        val leftX = w * 0.14f
        val rightX = w * 0.86f
        val topY = h * 0.30f
        val waistY = h * 0.44f
        val bottomY = h * 0.88f
        val midX = w * 0.5f
        val leftTopX = w * 0.30f
        val rightTopX = w * 0.70f

        // Внешний контур кристалла
        outerPath.reset()
        outerPath.moveTo(leftTopX, topY)
        outerPath.lineTo(rightTopX, topY)
        outerPath.lineTo(rightX, waistY)
        outerPath.lineTo(midX, bottomY)
        outerPath.lineTo(leftX, waistY)
        outerPath.close()

        drawPath(
            path = outerPath,
            color = color,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // Внутренние грани кристалла
        facetPath.reset()
        facetPath.moveTo(leftTopX, topY)
        facetPath.lineTo(midX, waistY)
        facetPath.lineTo(rightTopX, topY)
        facetPath.moveTo(midX, waistY)
        facetPath.lineTo(midX, bottomY)

        drawPath(
            path = facetPath,
            color = color.copy(alpha = 0.75f),
            style = Stroke(width = strokeWidth * 0.85f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

/**
 * Иконка 4: Радар выявления подделок (Mirrly Icons).
 */
@Composable
fun MirrlyCounterfeitRadarIcon(
    color: Color = Color(0xFFFF453A),
    modifier: Modifier = Modifier.size(16.dp)
) {
    val cornerPath = remember { Path() }
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val strokeWidth = 1.5.dp.toPx()

        cornerPath.reset()
        val r = w * 0.42f
        val cx = w * 0.5f
        val cy = h * 0.5f

        // Шестиугольный щит радара
        for (i in 0 until 6) {
            val angleRad = (i * 60.0 - 30.0) * (Math.PI / 180.0)
            val px = cx + (r * cos(angleRad)).toFloat()
            val py = cy + (r * sin(angleRad)).toFloat()
            if (i == 0) cornerPath.moveTo(px, py) else cornerPath.lineTo(px, py)
        }
        cornerPath.close()

        drawPath(
            path = cornerPath,
            color = color,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // Геометрический восклицательный знак
        drawLine(
            color = color,
            start = Offset(cx, cy - h * 0.18f),
            end = Offset(cx, cy + h * 0.05f),
            strokeWidth = strokeWidth * 1.1f,
            cap = StrokeCap.Round
        )
        drawCircle(
            color = color,
            radius = strokeWidth * 0.75f,
            center = Offset(cx, cy + h * 0.20f)
        )
    }
}

/**
 * Иконка замка на кнопке действия.
 */
@Composable
private fun MirrlyButtonLockIcon(
    color: Color = TextMuted,
    modifier: Modifier = Modifier.size(14.dp)
) {
    val shacklePath = remember { Path() }
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val sw = 1.3.dp.toPx()

        shacklePath.reset()
        shacklePath.moveTo(w * 0.30f, h * 0.44f)
        shacklePath.lineTo(w * 0.30f, h * 0.28f)
        shacklePath.cubicTo(w * 0.30f, h * 0.10f, w * 0.70f, h * 0.10f, w * 0.70f, h * 0.28f)
        shacklePath.lineTo(w * 0.70f, h * 0.44f)

        drawPath(
            path = shacklePath,
            color = color,
            style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.18f, h * 0.44f),
            size = Size(w * 0.64f, h * 0.46f),
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
            style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        drawCircle(
            color = color,
            radius = sw * 0.75f,
            center = Offset(w * 0.5f, h * 0.64f)
        )
    }
}

/**
 * Иконка галочки на кнопке действия.
 */
@Composable
private fun MirrlyButtonCheckIcon(
    color: Color = Color(0xFF00FF87),
    modifier: Modifier = Modifier.size(14.dp)
) {
    val path = remember { Path() }
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val sw = 1.6.dp.toPx()

        path.reset()
        path.moveTo(w * 0.18f, h * 0.52f)
        path.lineTo(w * 0.42f, h * 0.74f)
        path.lineTo(w * 0.82f, h * 0.26f)

        drawPath(
            path = path,
            color = color,
            style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}
