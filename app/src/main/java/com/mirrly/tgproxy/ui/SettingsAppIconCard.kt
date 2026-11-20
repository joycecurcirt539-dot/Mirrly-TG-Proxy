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

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.service.AppIconManager
import com.mirrly.tgproxy.service.AppIconPreset
import com.mirrly.tgproxy.ui.icon.MirrlyIcon
import com.mirrly.tgproxy.ui.theme.AmoledBackground
import com.mirrly.tgproxy.ui.theme.AmoledBorder
import com.mirrly.tgproxy.ui.theme.LocalCardShape
import com.mirrly.tgproxy.ui.theme.LocalProtocolColors
import com.mirrly.tgproxy.ui.theme.MtprotoAccent
import com.mirrly.tgproxy.ui.theme.TextMuted
import com.mirrly.tgproxy.ui.theme.TextWhite
import com.mirrly.tgproxy.ui.theme.horizontalFadingEdges
import com.mirrly.tgproxy.ui.theme.springPress

private val SquircleShape: Shape = GenericShape { size, _ ->
    val w = size.width
    val h = size.height
    moveTo(0f, h * 0.36f)
    cubicTo(0f, h * 0.06f, w * 0.06f, 0f, w * 0.36f, 0f)
    lineTo(w * 0.64f, 0f)
    cubicTo(w * 0.94f, 0f, w, h * 0.06f, w, h * 0.36f)
    lineTo(w, h * 0.64f)
    cubicTo(w, h * 0.94f, w * 0.94f, h, w * 0.64f, h)
    lineTo(w * 0.36f, h)
    cubicTo(w * 0.06f, h, 0f, h * 0.94f, 0f, h * 0.64f)
    close()
}

private enum class LauncherPreviewMask(
    @StringRes val labelRes: Int,
    val shape: Shape
) {
    SQUIRCLE(R.string.settings_customization_app_icon_mask_squircle, SquircleShape),
    CIRCLE(R.string.settings_customization_app_icon_mask_circle, CircleShape),
    ROUNDED(R.string.settings_customization_app_icon_mask_rounded, RoundedCornerShape(percent = 24)),
    TEARDROP(
        R.string.settings_customization_app_icon_mask_teardrop,
        RoundedCornerShape(
            topStartPercent = 50,
            topEndPercent = 50,
            bottomStartPercent = 50,
            bottomEndPercent = 16
        )
    )
}

/**
 * Карточка выбора иконки приложения с живым превью формы лаунчера,
 * предварительным просмотром Android 13+ Monochrome и безопасным переключением
 * `<activity-alias>` через шторку подтверждения (Zero-Popup).
 */
@Composable
fun SettingsAppIconCard(
    accentColor: Color = MtprotoAccent,
    onInfoClick: (String) -> Unit
) {
    val context = LocalContext.current
    val prefs = MirrlyApplication.instance.prefsManager
    val savedIconId by prefs.appIconIdFlow.collectAsState()
    val dynamicAccent = LocalProtocolColors.current.primary
    val effectiveAccent = if (accentColor == MtprotoAccent) dynamicAccent else accentColor
    val cardShape = LocalCardShape.current

    val activePreset = remember(savedIconId) {
        AppIconManager.resolveActiveIcon(context, savedIconId)
    }

    var isExpanded by rememberSaveable { mutableStateOf(false) }
    var selectedId by rememberSaveable(activePreset.id) { mutableStateOf(activePreset.id) }
    var previewMask by rememberSaveable { mutableStateOf(LauncherPreviewMask.SQUIRCLE) }
    var previewMonochrome by rememberSaveable { mutableStateOf(false) }
    var showConfirmSheet by rememberSaveable { mutableStateOf(false) }

    val selectedPreset = remember(selectedId) { AppIconPreset.fromStorageKey(selectedId) }
    val hasPendingChange = selectedPreset != activePreset

    val appDisplayLabel = remember(context) {
        try {
            context.applicationInfo.loadLabel(context.packageManager).toString()
        } catch (_: Throwable) {
            context.getString(R.string.app_name)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(Color.Transparent)
            .border(1.dp, AmoledBorder, cardShape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Заголовок карточки-аккордеона
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f, fill = false)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Transparent)
                        .border(1.dp, AmoledBorder, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    MirrlyIcon(
                        id = R.drawable.ic_app_icon_picker,
                        contentDescription = null,
                        tint = effectiveAccent,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f, fill = false)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.settings_customization_app_icon_title),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .springPress()
                                .clickable {
                                    HapticHelper.performTapClick(context)
                                    onInfoClick("app_icon_info")
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            MirrlyIcon(
                                id = R.drawable.ic_info_circle,
                                contentDescription = null,
                                tint = TextMuted,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }

                    if (!isExpanded) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            AdaptiveIconPreview(
                                preset = activePreset,
                                maskShape = previewMask.shape,
                                monochromeMode = false,
                                monochromeTint = effectiveAccent,
                                size = 16.dp
                            )
                            Text(
                                text = "${stringResource(activePreset.titleRes)} · ${stringResource(activePreset.subtitleRes)}",
                                fontSize = 11.5.sp,
                                color = if (activePreset == AppIconPreset.DEFAULT) TextMuted else effectiveAccent,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                lineHeight = 14.sp
                            )
                        }
                    }
                }
            }

            SettingsCollapseButton(
                isExpanded = isExpanded,
                onToggle = { isExpanded = !isExpanded },
                accentColor = effectiveAccent
            )
        }

        // Развёрнутый блок выбора и живого превью
        AnimatedVisibility(
            visible = isExpanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(top = 2.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_customization_app_icon_desc),
                    fontSize = 12.sp,
                    color = TextMuted,
                    lineHeight = 17.sp
                )

                // Живое превью рабочего стола
                LauncherPreviewStage(
                    preset = selectedPreset,
                    appLabel = appDisplayLabel,
                    mask = previewMask,
                    monochromeMode = previewMonochrome,
                    accentColor = effectiveAccent
                )

                // Панель переключения формы лаунчера и режима Android 13+ Monochrome
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalFadingEdges(startFadeWidth = 14.dp, endFadeWidth = 14.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LauncherPreviewMask.entries.forEach { mask ->
                        val isMaskSelected = previewMask == mask
                        FilterChipOutline(
                            label = stringResource(mask.labelRes),
                            selected = isMaskSelected,
                            accentColor = effectiveAccent,
                            onClick = {
                                HapticHelper.performSoftTick(context)
                                previewMask = mask
                            }
                        )
                    }

                    FilterChipOutline(
                        label = stringResource(R.string.settings_customization_app_icon_monochrome_toggle),
                        selected = previewMonochrome,
                        accentColor = effectiveAccent,
                        onClick = {
                            HapticHelper.performSoftTick(context)
                            previewMonochrome = !previewMonochrome
                        }
                    )
                }

                // Сетка 2x4 вариантов иконки приложения
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppIconPreset.entries.chunked(2).forEach { pair ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            pair.forEach { preset ->
                                val isActive = preset == activePreset
                                val isSelected = preset == selectedPreset
                                AppIconGridTile(
                                    preset = preset,
                                    isActive = isActive,
                                    isSelected = isSelected,
                                    maskShape = previewMask.shape,
                                    monochromeMode = previewMonochrome,
                                    accentColor = effectiveAccent,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        HapticHelper.performTapClick(context)
                                        selectedId = preset.id
                                    }
                                )
                            }
                            if (pair.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }

                // Контекстная панель применения при выборе новой иконки
                AnimatedVisibility(
                    visible = hasPendingChange,
                    enter = expandVertically(tween(220)) + fadeIn(tween(200)),
                    exit = shrinkVertically(tween(180)) + fadeOut(tween(150))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .border(1.dp, effectiveAccent.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                            .background(effectiveAccent.copy(alpha = 0.06f))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.settings_customization_app_icon_pending_notice),
                            fontSize = 11.5.sp,
                            color = TextMuted,
                            lineHeight = 16.sp
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color.Transparent,
                                border = BorderStroke(1.dp, AmoledBorder),
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .springPress(onClick = {
                                        HapticHelper.performSoftTick(context)
                                        selectedId = activePreset.id
                                    })
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = stringResource(R.string.settings_customization_app_icon_btn_reset),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = TextMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = effectiveAccent.copy(alpha = 0.14f),
                                border = BorderStroke(1.dp, effectiveAccent),
                                modifier = Modifier
                                    .weight(1.4f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .springPress(onClick = {
                                        HapticHelper.performTapClick(context)
                                        showConfirmSheet = true
                                    })
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = stringResource(R.string.settings_customization_app_icon_btn_apply),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextWhite,
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
    }

    if (showConfirmSheet) {
        AppIconConfirmSheet(
            preset = selectedPreset,
            maskShape = previewMask.shape,
            monochromeMode = previewMonochrome,
            accentColor = effectiveAccent,
            appLabel = appDisplayLabel,
            onDismiss = { showConfirmSheet = false },
            onConfirm = {
                HapticHelper.performTapClick(context)
                showConfirmSheet = false
                val applied = AppIconManager.applyAppIcon(context, selectedPreset, prefs)
                val msgRes = if (applied) {
                    R.string.settings_customization_app_icon_toast_applied
                } else {
                    selectedId = AppIconManager.resolveActiveIcon(context, prefs.getAppIconId()).id
                    R.string.settings_customization_app_icon_toast_failed
                }
                Toast.makeText(context, context.getString(msgRes), Toast.LENGTH_SHORT).show()
            }
        )
    }
}

@Composable
private fun LauncherPreviewStage(
    preset: AppIconPreset,
    appLabel: String,
    mask: LauncherPreviewMask,
    monochromeMode: Boolean,
    accentColor: Color
) {
    val haloColor = if (monochromeMode) accentColor else preset.accentHint
    val animatedHalo by animateColorAsState(
        targetValue = haloColor,
        animationSpec = tween(260),
        label = "app_icon_halo"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Transparent)
            .border(1.dp, AmoledBorder, RoundedCornerShape(16.dp))
            .padding(vertical = 14.dp, horizontal = 12.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.settings_customization_app_icon_preview_label),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextMuted,
                letterSpacing = 0.5.sp
            )

            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                // Фоновое свечение под выбранной иконкой (Zero-Allocation drawWithCache)
                Box(
                    modifier = Modifier
                        .size(width = 160.dp, height = 88.dp)
                        .drawWithCache {
                            val haloCenter = Offset(size.width / 2f, size.height / 2f)
                            val haloRadius = size.minDimension * 0.55f
                            val haloBrush = Brush.radialGradient(
                                colors = listOf(
                                    animatedHalo.copy(alpha = 0.22f),
                                    animatedHalo.copy(alpha = 0.06f),
                                    Color.Transparent
                                ),
                                center = haloCenter,
                                radius = haloRadius
                            )
                            onDrawBehind {
                                drawCircle(
                                    brush = haloBrush,
                                    radius = haloRadius,
                                    center = haloCenter
                                )
                            }
                        }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GhostLauncherSlot(mask.shape)

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AdaptiveIconPreview(
                            preset = preset,
                            maskShape = mask.shape,
                            monochromeMode = monochromeMode,
                            monochromeTint = accentColor,
                            size = 64.dp,
                            borderColor = animatedHalo.copy(alpha = 0.55f)
                        )
                        Text(
                            text = appLabel,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextWhite,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    }

                    GhostLauncherSlot(mask.shape)
                }
            }
        }
    }
}

@Composable
private fun GhostLauncherSlot(maskShape: Shape) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(maskShape)
                .border(1.dp, AmoledBorder.copy(alpha = 0.45f), maskShape)
        )
        Box(
            modifier = Modifier
                .width(36.dp)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .border(1.dp, AmoledBorder.copy(alpha = 0.35f), RoundedCornerShape(3.dp))
        )
    }
}

@Composable
private fun FilterChipOutline(
    label: String,
    selected: Boolean,
    accentColor: Color,
    onClick: () -> Unit
) {
    val borderColor by animateColorAsState(
        targetValue = if (selected) accentColor else AmoledBorder,
        label = "mask_chip_border"
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) TextWhite else TextMuted,
        label = "mask_chip_text"
    )

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) accentColor.copy(alpha = 0.14f) else Color.Transparent,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .springPress(onClick = onClick)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = textColor,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun AppIconGridTile(
    preset: AppIconPreset,
    isActive: Boolean,
    isSelected: Boolean,
    maskShape: Shape,
    monochromeMode: Boolean,
    accentColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val targetBorder = when {
        isSelected -> accentColor
        isActive -> accentColor.copy(alpha = 0.55f)
        else -> AmoledBorder
    }
    val borderColor by animateColorAsState(targetValue = targetBorder, label = "icon_tile_border")

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) accentColor.copy(alpha = 0.10f) else Color.Transparent,
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .springPress(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AdaptiveIconPreview(
                preset = preset,
                maskShape = maskShape,
                monochromeMode = monochromeMode,
                monochromeTint = accentColor,
                size = 40.dp,
                borderColor = if (isSelected) accentColor.copy(alpha = 0.7f) else AmoledBorder
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = stringResource(preset.titleRes),
                    fontSize = 12.5.sp,
                    fontWeight = if (isSelected || isActive) FontWeight.Bold else FontWeight.SemiBold,
                    color = TextWhite,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = when {
                        isActive -> stringResource(R.string.settings_customization_app_icon_state_active)
                        isSelected -> stringResource(R.string.settings_customization_app_icon_state_selected)
                        else -> stringResource(preset.subtitleRes)
                    },
                    fontSize = 10.5.sp,
                    color = when {
                        isActive -> accentColor
                        isSelected -> TextWhite.copy(alpha = 0.80f)
                        else -> TextMuted
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Рендерит точную модель Adaptive Icon (`108dp` -> видимые `72dp`, коэффициент `1.44x`)
 * либо монохромный предпросмотр Material You Android 13+ без использования InsetDrawable.
 */
@Composable
fun AdaptiveIconPreview(
    preset: AppIconPreset,
    maskShape: Shape,
    monochromeMode: Boolean,
    monochromeTint: Color,
    size: Dp,
    borderColor: Color = AmoledBorder
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(maskShape)
            .background(if (monochromeMode) monochromeTint.copy(alpha = 0.16f) else Color.Black)
            .border(1.dp, borderColor, maskShape),
        contentAlignment = Alignment.Center
    ) {
        if (monochromeMode) {
            Image(
                painter = painterResource(id = preset.monochromeMaskRes),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = ColorFilter.tint(monochromeTint),
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.44f
                        scaleY = 1.44f
                    }
            )
        } else {
            Image(
                painter = painterResource(id = preset.bgDrawableRes),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.44f
                        scaleY = 1.44f
                    }
            )
            val fgScale = 1.44f * (1f - preset.fgInsetFraction * 2f)
            Image(
                painter = painterResource(id = preset.fgDrawableRes),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = fgScale
                        scaleY = fgScale
                    }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppIconConfirmSheet(
    preset: AppIconPreset,
    maskShape: Shape,
    monochromeMode: Boolean,
    accentColor: Color,
    appLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val context = LocalContext.current

    MirrlyModalBottomSheet(
        onDismissRequest = onDismiss,
        maxTabletWidth = 460.dp,
        containerColor = AmoledBackground.copy(alpha = 0.92f),
        scrimColor = AmoledBackground.copy(alpha = 0.60f),
        blurRadiusPx = 60
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            AdaptiveIconPreview(
                preset = preset,
                maskShape = maskShape,
                monochromeMode = monochromeMode,
                monochromeTint = accentColor,
                size = 72.dp,
                borderColor = accentColor.copy(alpha = 0.7f)
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_customization_app_icon_sheet_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "$appLabel · ${stringResource(preset.titleRes)}",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = accentColor,
                    textAlign = TextAlign.Center
                )
            }

            Text(
                text = stringResource(R.string.settings_customization_app_icon_sheet_desc),
                fontSize = 13.sp,
                color = TextMuted,
                lineHeight = 19.sp,
                textAlign = TextAlign.Center
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, AmoledBorder, RoundedCornerShape(14.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SheetBulletRow(
                    text = stringResource(R.string.settings_customization_app_icon_sheet_note_bg),
                    accentColor = accentColor
                )
                SheetBulletRow(
                    text = stringResource(R.string.settings_customization_app_icon_sheet_note_shortcut),
                    accentColor = accentColor
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, AmoledBorder),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .springPress(onClick = {
                            HapticHelper.performSoftTick(context)
                            onDismiss()
                        })
                ) {
                    Box(
                        modifier = Modifier.padding(vertical = 13.dp, horizontal = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.settings_customization_app_icon_sheet_btn_cancel),
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextMuted
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = accentColor.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, accentColor),
                    modifier = Modifier
                        .weight(1.2f)
                        .clip(RoundedCornerShape(14.dp))
                        .springPress(onClick = onConfirm)
                ) {
                    Box(
                        modifier = Modifier.padding(vertical = 13.dp, horizontal = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.settings_customization_app_icon_sheet_btn_confirm),
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetBulletRow(
    text: String,
    accentColor: Color
) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(5.dp)
                .clip(CircleShape)
                .background(accentColor)
        )
        Text(
            text = text,
            fontSize = 12.sp,
            color = TextWhite.copy(alpha = 0.85f),
            lineHeight = 17.sp
        )
    }
}
