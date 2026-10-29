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

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.ui.theme.ActiveGreenLed
import com.mirrly.tgproxy.ui.theme.AmoledSurface
import com.mirrly.tgproxy.ui.theme.TextMuted
import com.mirrly.tgproxy.ui.theme.TextWhite
import com.mirrly.tgproxy.ui.theme.adaptiveContainerWidth
import com.mirrly.tgproxy.ui.theme.frostedVignetteCard
import com.mirrly.tgproxy.ui.theme.springPress
import kotlinx.coroutines.launch

/**
 * Donation methods supported in Mirrly TG Proxy.
 */
enum class DonationMethod(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val descRes: Int,
    val url: String,
    val displayUrl: String,
    @DrawableRes val iconRes: Int
) {
    CLOUDTIPS(
        id = "cloudtips",
        titleRes = R.string.donate_method_cloudtips_title,
        descRes = R.string.donate_method_cloudtips_desc,
        url = "https://pay.cloudtips.ru/p/e8c24aee",
        displayUrl = "pay.cloudtips.ru/p/e8c24aee",
        iconRes = R.drawable.ic_cloudtips
    ),
    YANDEX_TIPS(
        id = "yandex_tips",
        titleRes = R.string.donate_method_yandex_tips_title,
        descRes = R.string.donate_method_yandex_tips_desc,
        url = "https://tips.yandex.ru/guest/payment/3754149",
        displayUrl = "tips.yandex.ru/guest/payment/3754149",
        iconRes = R.drawable.ic_yandex_tips
    ),
    DALINK(
        id = "dalink",
        titleRes = R.string.donate_method_dalink_title,
        descRes = R.string.donate_method_dalink_desc,
        url = "https://dalink.to/cartneyzix",
        displayUrl = "dalink.to/cartneyzix",
        iconRes = R.drawable.ic_dalink
    )
}

/**
 * Modern donation selection modal with expandable bouncy cards and in-place redirect warning.
 */
@Composable
fun DonationDialog(
    onDismiss: () -> Unit,
    onProceed: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val app = MirrlyApplication.instance

    var selectedMethod by remember { mutableStateOf<DonationMethod?>(null) }
    var showWarningSheet by remember { mutableStateOf(false) }

    fun openExternalUrl(url: String) {
        try {
            val uri = Uri.parse(url.trim())
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(
                context,
                context.getString(R.string.err_open_link, e.localizedMessage ?: ""),
                Toast.LENGTH_SHORT
            ).show()
        }
        onProceed?.invoke()
        onDismiss()
    }

    Dialog(
        onDismissRequest = {
            if (showWarningSheet) {
                showWarningSheet = false
            } else {
                onDismiss()
            }
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        DialogBackdropBox(
            onDismiss = {
                if (showWarningSheet) {
                    showWarningSheet = false
                } else {
                    onDismiss()
                }
            },
            blurRadiusPx = 70
        ) {
            // Top-Left Back Button (closes sheet if open, otherwise closes dialog)
            IconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (showWarningSheet) {
                        showWarningSheet = false
                    } else {
                        onDismiss()
                    }
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(top = 12.dp, start = 12.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_arrow_left),
                    contentDescription = stringResource(R.string.action_back),
                    tint = TextWhite,
                    modifier = Modifier.size(22.dp)
                )
            }

            // Main Content Area
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .align(Alignment.Center)
                    .adaptiveContainerWidth(440.dp)
                    .padding(horizontal = 22.dp)
                    .navigationBarsPadding()
                    .padding(bottom = 60.dp)
                    .verticalScroll(rememberScrollState())
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {}
            ) {
                Spacer(modifier = Modifier.height(28.dp))

                // Category Badge
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = ActiveGreenLed.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, ActiveGreenLed.copy(alpha = 0.40f))
                ) {
                    Text(
                        text = stringResource(R.string.donate_dialog_category),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = ActiveGreenLed,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
                    )
                }

                // Main Title
                Text(
                    text = stringResource(R.string.donate_dialog_title),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite,
                    textAlign = TextAlign.Center,
                    letterSpacing = 0.3.sp
                )

                // Honest Description (no AI buzzwords)
                Text(
                    text = stringResource(R.string.donate_dialog_desc),
                    fontSize = 13.5.sp,
                    color = TextWhite.copy(alpha = 0.86f),
                    textAlign = TextAlign.Center,
                    lineHeight = 19.sp,
                    modifier = Modifier.padding(horizontal = 6.dp)
                )

                Spacer(modifier = Modifier.height(6.dp))

                // 3 Expandable Donation Method Cards (Accordion)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    DonationMethod.values().forEach { method ->
                        val isSelected = selectedMethod == method
                        DonationMethodCard(
                            method = method,
                            isSelected = isSelected,
                            onToggle = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                selectedMethod = if (isSelected) null else method
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Primary Proceed Button
                Button(
                    onClick = {
                        val method = selectedMethod ?: return@Button
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (app.prefsManager.isSkipDonateWarning()) {
                            openExternalUrl(method.url)
                        } else {
                            showWarningSheet = true
                        }
                    },
                    enabled = selectedMethod != null,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ActiveGreenLed,
                        contentColor = Color.Black,
                        disabledContainerColor = Color.White.copy(alpha = 0.08f),
                        disabledContentColor = TextMuted
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .springPress()
                ) {
                    Text(
                        text = if (selectedMethod != null) {
                            stringResource(R.string.donate_action_proceed)
                        } else {
                            stringResource(R.string.donate_action_select_prompt)
                        },
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            // In-Place Dimmed Overlay (intercepts taps outside the warning sheet)
            AnimatedVisibility(
                visible = showWarningSheet && selectedMethod != null,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(200))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            showWarningSheet = false
                        }
                )
            }

            // In-Place Slide-Up Warning Card (NOT in a separate Dialog window!)
            AnimatedVisibility(
                visible = showWarningSheet && selectedMethod != null,
                enter = slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f)
                ) + fadeIn(tween(200)),
                exit = slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = spring(dampingRatio = 0.85f, stiffness = 450f)
                ) + fadeOut(tween(180)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .adaptiveContainerWidth(440.dp)
                    .navigationBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                val method = selectedMethod ?: return@AnimatedVisibility
                DonateWarningSheetContent(
                    method = method,
                    onDismiss = { showWarningSheet = false },
                    onConfirm = { doNotAskAgain ->
                        if (doNotAskAgain) {
                            app.prefsManager.setSkipDonateWarning(true)
                        }
                        showWarningSheet = false
                        openExternalUrl(method.url)
                    }
                )
            }
        }
    }
}

/**
 * Expandable card with tactile bounce animation upon opening.
 */
@Composable
private fun DonationMethodCard(
    method: DonationMethod,
    isSelected: Boolean,
    onToggle: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val bounceScale = remember { Animatable(1f) }
    val bounceOffsetY = remember { Animatable(0f) }

    LaunchedEffect(isSelected) {
        if (isSelected) {
            // Tactile jump/bounce: compress slightly then spring up
            bounceScale.snapTo(0.96f)
            bounceOffsetY.snapTo(5f)
            coroutineScope.launch {
                bounceOffsetY.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = 0.52f, // Bouncy spring feel
                        stiffness = 380f
                    )
                )
            }
            bounceScale.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = 0.52f,
                    stiffness = 380f
                )
            )
        } else {
            // Fluid shrink when collapsing
            bounceOffsetY.snapTo(0f)
            bounceScale.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing)
            )
        }
    }

    val chevronRotation by animateFloatAsState(
        targetValue = if (isSelected) 90f else 0f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f),
        label = "chevronRotation"
    )

    val cardBorder = if (isSelected) {
        BorderStroke(1.2.dp, ActiveGreenLed.copy(alpha = 0.85f))
    } else {
        BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
    }

    val cardBg = if (isSelected) {
        ActiveGreenLed.copy(alpha = 0.08f)
    } else {
        AmoledSurface.copy(alpha = 0.70f)
    }

    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(18.dp),
        color = cardBg,
        border = cardBorder,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = bounceScale.value
                scaleY = bounceScale.value
                translationY = bounceOffsetY.value
            }
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = 0.65f,
                    stiffness = 400f
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 13.dp)
        ) {
            // Header: Animated Logo, Title, Chevron
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    // Animated High-Quality Logo
                    DonationMethodAnimatedIcon(
                        method = method,
                        isSelected = isSelected
                    )

                    // Title
                    Text(
                        text = stringResource(id = method.titleRes),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isSelected) ActiveGreenLed else TextWhite
                    )
                }

                // Smooth Rotating Chevron
                Icon(
                    painter = painterResource(id = R.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = if (isSelected) ActiveGreenLed else TextMuted,
                    modifier = Modifier
                        .size(18.dp)
                        .graphicsLayer { rotationZ = chevronRotation }
                )
            }

            // Expanded Details (Description + Link)
            AnimatedVisibility(
                visible = isSelected,
                enter = fadeIn(tween(180)) + expandVertically(
                    spring(dampingRatio = 0.68f, stiffness = 400f)
                ),
                exit = fadeOut(tween(140)) + shrinkVertically(
                    spring(dampingRatio = 0.8f, stiffness = 450f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HorizontalDivider(
                        color = ActiveGreenLed.copy(alpha = 0.22f),
                        thickness = 1.dp
                    )

                    // Method Description
                    Text(
                        text = stringResource(id = method.descRes),
                        fontSize = 12.5.sp,
                        color = TextWhite.copy(alpha = 0.88f),
                        lineHeight = 17.5.sp
                    )

                    // Link Preview Pill
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color.Black.copy(alpha = 0.35f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = method.displayUrl,
                                fontSize = 11.5.sp,
                                color = ActiveGreenLed,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * In-place warning sheet content rendered directly inside the donation container.
 * Strictly NOT in a separate Dialog window!
 */
@Composable
private fun DonateWarningSheetContent(
    method: DonationMethod,
    onDismiss: () -> Unit,
    onConfirm: (doNotAskAgain: Boolean) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var doNotAskAgain by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .frostedVignetteCard(
                shape = RoundedCornerShape(26.dp),
                accentColor = ActiveGreenLed,
                vignetteStrength = 0.65f,
                borderBrush = Brush.verticalGradient(
                    colors = listOf(
                        ActiveGreenLed.copy(alpha = 0.60f),
                        Color.White.copy(alpha = 0.18f)
                    )
                )
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {}
            .padding(18.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(11.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            // Drag handle pill
            Box(
                modifier = Modifier
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.25f))
            )

            // Category Pill
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = ActiveGreenLed.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, ActiveGreenLed.copy(alpha = 0.35f))
            ) {
                Text(
                    text = stringResource(R.string.donate_warning_category),
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = ActiveGreenLed,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }

            // Title
            Text(
                text = stringResource(R.string.donate_warning_title),
                fontSize = 17.5.sp,
                fontWeight = FontWeight.Bold,
                color = TextWhite,
                textAlign = TextAlign.Center
            )

            // Target Info Pill
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.Black.copy(alpha = 0.45f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    DonationMethodAnimatedIcon(
                        method = method,
                        isSelected = true,
                        modifier = Modifier.size(34.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(id = method.titleRes),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextWhite
                        )
                        Text(
                            text = method.displayUrl,
                            fontSize = 11.sp,
                            color = ActiveGreenLed,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Description
            Text(
                text = stringResource(R.string.donate_warning_desc),
                fontSize = 12.sp,
                color = TextWhite.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
                lineHeight = 16.5.sp
            )

            // Checkbox: "Don't ask again"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        doNotAskAgain = !doNotAskAgain
                    }
                    .background(Color.White.copy(alpha = 0.04f))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Checkbox(
                    checked = doNotAskAgain,
                    onCheckedChange = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        doNotAskAgain = it
                    },
                    colors = CheckboxDefaults.colors(
                        checkedColor = ActiveGreenLed,
                        checkmarkColor = Color.Black,
                        uncheckedColor = Color.White.copy(alpha = 0.40f)
                    )
                )
                Text(
                    text = stringResource(R.string.donate_warning_do_not_ask_again),
                    fontSize = 12.5.sp,
                    color = TextWhite.copy(alpha = 0.90f),
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            // Action Buttons: Cancel & Proceed
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDismiss()
                    },
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = TextWhite
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .springPress()
                ) {
                    Text(
                        text = stringResource(R.string.action_cancel),
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onConfirm(doNotAskAgain)
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ActiveGreenLed,
                        contentColor = Color.Black
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .springPress()
                ) {
                    Text(
                        text = stringResource(R.string.action_open_link),
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * Dynamic animated logo rendered from layered vector drawables.
 */
@Composable
fun DonationMethodAnimatedIcon(
    method: DonationMethod,
    isSelected: Boolean,
    modifier: Modifier = Modifier
) {
    val iconAlpha by animateFloatAsState(
        targetValue = if (isSelected) 1.0f else 0.75f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f),
        label = "donationIconAlpha"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(42.dp)
            .graphicsLayer { alpha = iconAlpha }
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (method == DonationMethod.DALINK) Color(0xFF1B1D24) else Color.Transparent
            )
            .border(
                width = 1.dp,
                color = if (isSelected) ActiveGreenLed.copy(alpha = 0.60f)
                else Color.White.copy(alpha = 0.15f),
                shape = RoundedCornerShape(12.dp)
            )
    ) {
        when (method) {
            DonationMethod.CLOUDTIPS -> {
                // Magnetic snap: Two cards slide toward each other with elastic attraction
                val snapProgress by animateFloatAsState(
                    targetValue = if (isSelected) 1f else 0f,
                    animationSpec = spring(
                        dampingRatio = 0.42f, // Bouncy magnetic snap overshoot
                        stiffness = 340f
                    ),
                    label = "ctMagneticSnap"
                )

                // Cards expand slightly upon docking to emphasize physical contact
                val cardScale by animateFloatAsState(
                    targetValue = if (isSelected) 1.05f else 1.0f,
                    animationSpec = spring(
                        dampingRatio = 0.45f,
                        stiffness = 380f
                    ),
                    label = "ctCardScale"
                )

                // Infinite harmonic magnetic breathing when docked/selected
                val infiniteTransition = rememberInfiniteTransition(label = "ctMagneticHover")
                val hoverPhase by infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = (2 * Math.PI).toFloat(),
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 2400, easing = LinearEasing)
                    ),
                    label = "ctHoverPhase"
                )

                val magneticFloat = if (isSelected) kotlin.math.sin(hoverPhase) * 1.1f else 0f

                // Diagonal trajectory: cards glide toward each other and snap into locked alignment
                val leftX = (-3.2f * (1f - snapProgress)) + (snapProgress * 1.5f) + magneticFloat
                val leftY = (3.2f * (1f - snapProgress)) - (snapProgress * 1.5f) - magneticFloat
                val rightX = (3.2f * (1f - snapProgress)) - (snapProgress * 1.5f) - magneticFloat
                val rightY = (-3.2f * (1f - snapProgress)) + (snapProgress * 1.5f) + magneticFloat

                // Dynamic rotational alignment (parallel docking)
                val leftRot = -3.5f * (1f - snapProgress)
                val rightRot = 3.5f * (1f - snapProgress)

                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_cloudtips_bg),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val bgScale = 1f + snapProgress * 0.04f
                                scaleX = bgScale
                                scaleY = bgScale
                            }
                    )
                    Icon(
                        painter = painterResource(id = R.drawable.ic_cloudtips_left),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                translationX = leftX
                                translationY = leftY
                                rotationZ = leftRot
                                scaleX = cardScale
                                scaleY = cardScale
                            }
                    )
                    Icon(
                        painter = painterResource(id = R.drawable.ic_cloudtips_right),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                translationX = rightX
                                translationY = rightY
                                rotationZ = rightRot
                                scaleX = cardScale
                                scaleY = cardScale
                            }
                    )
                }
            }
            DonationMethod.YANDEX_TIPS -> {
                // Tactile spring overshoot for viewfinder lock and background recoil
                val snapProgress by animateFloatAsState(
                    targetValue = if (isSelected) 1f else 0f,
                    animationSpec = spring(
                        dampingRatio = 0.42f, // Punchy spring overshoot
                        stiffness = 380f
                    ),
                    label = "ytSnap"
                )

                val infiniteTransition = rememberInfiniteTransition(label = "yandexPulsar")

                // Hypnotic spiral rotation: active strictly when selected
                val spiralRotation by infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(
                            durationMillis = 2400,
                            easing = LinearEasing
                        )
                    ),
                    label = "ytSpiralRotation"
                )

                // Living harmonic breath for viewfinder autofocus and pulsar depth
                val breathPhase by infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = (2 * Math.PI).toFloat(),
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 2000, easing = LinearEasing)
                    ),
                    label = "ytBreathPhase"
                )

                // Viewfinder framing brackets: snap expansion + harmonic autofocus pulse (±2.5%)
                val bracketBreath = if (isSelected) kotlin.math.sin(breathPhase) * 0.025f else 0f
                val bracketScale = 1.0f + (snapProgress * 0.05f) + bracketBreath
                val bracketRotation = (1f - snapProgress) * -4f // Tactile aperture shutter twist

                // Spiral pulsar scale: pulsating breathing zoom (0.98f to 1.10f)
                val spiralPulse = if (isSelected) kotlin.math.cos(breathPhase) * 0.06f else 0f
                val spiralScale = 1.0f + (snapProgress * 0.04f) + spiralPulse

                // Yellow card tactile micro-pop
                val bgScale = 1.0f + (snapProgress * 0.03f)

                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    // Layer 1: Yellow squircle tile background
                    Icon(
                        painter = painterResource(id = R.drawable.ic_yandex_tips_bg),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = bgScale
                                scaleY = bgScale
                            }
                    )

                    // Layer 2: 4 Framing corner brackets (Viewfinder reticle with autofocus snap)
                    Icon(
                        painter = painterResource(id = R.drawable.ic_yandex_tips_brackets),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = bracketScale
                                scaleY = bracketScale
                                rotationZ = bracketRotation
                            }
                    )

                    // Layer 3: Central hypnotic spiral pulsar
                    Icon(
                        painter = painterResource(id = R.drawable.ic_yandex_tips_spiral),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                rotationZ = if (isSelected) spiralRotation else 0f
                                scaleX = spiralScale
                                scaleY = spiralScale
                            }
                    )
                }
            }
            DonationMethod.DALINK -> {
                // Elastic snap of the chain link with high-damping spring overshoot
                val snapProgress by animateFloatAsState(
                    targetValue = if (isSelected) 1f else 0f,
                    animationSpec = spring(
                        dampingRatio = 0.40f,
                        stiffness = 350f
                    ),
                    label = "dlSnap"
                )

                // Infinite harmonic pendulum swing for the chain link in active state
                val infiniteTransition = rememberInfiniteTransition(label = "dlPendulum")
                val swingPhase by infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = (2 * Math.PI).toFloat(),
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 2200, easing = LinearEasing)
                    ),
                    label = "dlSwing"
                )

                // Living pendulum swing (±3.5 deg)
                val chainSwing = if (isSelected) kotlin.math.sin(swingPhase) * 3.5f else 0f

                // Squash & stretch physics for the orange speech bubble
                val bubbleSquash = if (snapProgress in 0.01f..0.99f) {
                    kotlin.math.sin(snapProgress * Math.PI.toFloat())
                } else {
                    0f
                }
                val bubbleScaleX = 1f + (bubbleSquash * 0.08f) + (snapProgress * 0.04f)
                val bubbleScaleY = 1f - (bubbleSquash * 0.06f) + (snapProgress * 0.04f)

                // Chain tension and rotational snap into locked position
                val chainScale = 1f + snapProgress * 0.07f
                val chainRotation = (1f - snapProgress) * -8f + chainSwing

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(4.5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_dalink_bubble),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = bubbleScaleX
                                scaleY = bubbleScaleY
                            }
                    )
                    Icon(
                        painter = painterResource(id = R.drawable.ic_dalink_chain),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = chainScale
                                scaleY = chainScale
                                rotationZ = chainRotation
                            }
                    )
                }
            }
        }
    }
}
