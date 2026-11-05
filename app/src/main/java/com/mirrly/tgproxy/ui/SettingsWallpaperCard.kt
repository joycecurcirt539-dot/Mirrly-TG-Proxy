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

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.service.AgslShaderPreset
import com.mirrly.tgproxy.service.PreferencesManager
import com.mirrly.tgproxy.service.WallpaperPreset
import com.mirrly.tgproxy.ui.theme.AmoledBorder
import com.mirrly.tgproxy.ui.theme.LocalCardShape
import com.mirrly.tgproxy.ui.theme.MtprotoAccent
import com.mirrly.tgproxy.ui.theme.TextMuted
import com.mirrly.tgproxy.ui.theme.TextWhite
import com.mirrly.tgproxy.ui.theme.horizontalFadingEdges
import com.mirrly.tgproxy.ui.theme.springPress
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/** Supported wallpaper visual media modes in MirrlyUI. */
enum class WallpaperMediaType {
    PHOTO,
    VIDEO,
    SHADER
}

/**
 * Settings Wallpaper Customization Card with 3 Coexisting Media Modes:
 * 1. Photo: Static image wallpapers with GPU blur and dimming.
 * 2. Video: Hardware-accelerated MP4/WEBM looping video wallpapers.
 * 3. Procedural AGSL Shader: Pure GPU SkSL runtime shaders for Android 13+ (Perspective Grid, Digital Stream, Scanlines).
 */
@Composable
fun SettingsWallpaperCard(
    accentColor: Color = MtprotoAccent,
    onInfoClick: (String) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val prefs = MirrlyApplication.instance.prefsManager
    val cardShape = LocalCardShape.current

    var isWallpaperExpanded by rememberSaveable { mutableStateOf(false) }

    val wallpaperUri by prefs.customBackgroundUriFlow.collectAsState()
    val wallpaperRevision by prefs.customBackgroundRevisionFlow.collectAsState()
    val wallpaperType by prefs.customBackgroundTypeFlow.collectAsState()
    val videoPowerSave by prefs.customBackgroundVideoPowerSaveFlow.collectAsState()
    val videoMetadata by prefs.customBackgroundVideoMetadataFlow.collectAsState()
    val blurDp by prefs.customBackgroundBlurFlow.collectAsState()
    val dimFactor by prefs.customBackgroundDimFlow.collectAsState()
    val particlesEnabled by prefs.customBackgroundParticlesFlow.collectAsState()
    val activePresetId by prefs.customBackgroundActivePresetIdFlow.collectAsState()
    val userPresets by prefs.customBackgroundUserPresetsFlow.collectAsState()
    val allPresets = remember(userPresets) { WallpaperPreset.BUILTIN_PRESETS + userPresets }

    val activeShaderPreset by prefs.wallpaperShaderPresetFlow.collectAsState()
    val shaderSpeed by prefs.wallpaperShaderSpeedFlow.collectAsState()
    val parallaxEnabled by prefs.wallpaperParallaxEnabledFlow.collectAsState()
    val parallaxDepth by prefs.wallpaperParallaxDepthFlow.collectAsState()

    var showResetConfirm by remember { mutableStateOf(false) }
    var showSavePresetCard by remember { mutableStateOf(false) }
    var newPresetTitle by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var sliderBlur by remember(blurDp) { mutableFloatStateOf(blurDp) }
    var sliderDim by remember(dimFactor) { mutableFloatStateOf(dimFactor) }
    var sliderShaderSpeed by remember(shaderSpeed) { mutableFloatStateOf(shaderSpeed) }
    var sliderParallaxDepth by remember(parallaxDepth) { mutableFloatStateOf(parallaxDepth) }

    val mediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { selectedUri ->
        if (selectedUri != null) {
            coroutineScope.launch {
                isProcessing = true
                errorMessage = null
                try {
                    val result = prefs.setCustomMediaWallpaper(selectedUri)
                    if (result.isFailure) {
                        errorMessage = result.exceptionOrNull()?.localizedMessage ?: "Unknown error"
                    } else {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showResetConfirm = false
                    }
                } catch (e: Exception) {
                    errorMessage = e.localizedMessage ?: "Unknown error"
                } finally {
                    isProcessing = false
                }
            }
        }
    }

    val hasCustomWallpaper = !wallpaperUri.isNullOrBlank()
    val isVideo = wallpaperType == PreferencesManager.WALLPAPER_TYPE_VIDEO
    val isShader = wallpaperType == PreferencesManager.WALLPAPER_TYPE_SHADER
    val isPhoto = hasCustomWallpaper && !isVideo && !isShader

    val previewPath = remember(wallpaperUri, wallpaperRevision, wallpaperType) {
        prefs.getWallpaperPreviewFile()?.absolutePath
    }
    val wallpaperImage = rememberWallpaperImage(previewPath, wallpaperRevision)

    val activeMode = when {
        isShader -> WallpaperMediaType.SHADER
        isVideo -> WallpaperMediaType.VIDEO
        hasCustomWallpaper -> WallpaperMediaType.PHOTO
        else -> null
    }

    var selectedTab by rememberSaveable(activeMode) {
        mutableStateOf(activeMode ?: WallpaperMediaType.PHOTO)
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
        // ── Card Header ───────────────────────────────────────────────
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
                    WallpaperGlyph(
                        color = if (hasCustomWallpaper) accentColor else TextWhite,
                        pulse = if (isProcessing) 1f else 0f,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.settings_customization_wallpaper_title),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite
                        )
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .springPress()
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onInfoClick("custom_wallpaper_info")
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_info_circle),
                                contentDescription = null,
                                tint = TextMuted,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }

                    if (!isWallpaperExpanded) {
                        val statusText = when {
                            isShader -> {
                                val name = when (activeShaderPreset) {
                                    AgslShaderPreset.PERSPECTIVE_GRID -> stringResource(R.string.settings_customization_wallpaper_shader_grid)
                                    AgslShaderPreset.DIGITAL_STREAM -> stringResource(R.string.settings_customization_wallpaper_shader_stream)
                                    AgslShaderPreset.SCANLINES -> stringResource(R.string.settings_customization_wallpaper_shader_scanlines)
                                }
                                stringResource(R.string.settings_customization_wallpaper_shader_active) + " · " + name
                            }
                            hasCustomWallpaper && isVideo -> stringResource(R.string.settings_customization_wallpaper_active_video)
                            hasCustomWallpaper -> stringResource(R.string.settings_customization_wallpaper_active_image)
                            else -> stringResource(R.string.settings_customization_wallpaper_default)
                        }
                        Text(
                            text = statusText,
                            fontSize = 11.5.sp,
                            color = if (hasCustomWallpaper) accentColor else TextMuted,
                            lineHeight = 14.sp
                        )
                    }
                }
            }

            SettingsCollapseButton(
                isExpanded = isWallpaperExpanded,
                onToggle = { isWallpaperExpanded = !isWallpaperExpanded },
                accentColor = accentColor
            )
        }

        AnimatedVisibility(
            visible = isWallpaperExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = stringResource(R.string.settings_customization_wallpaper_desc),
                    fontSize = 12.sp,
                    color = TextMuted,
                    lineHeight = 16.sp
                )

                if (errorMessage != null) {
                    Text(
                        text = errorMessage.orEmpty(),
                        fontSize = 11.sp,
                        color = Color(0xFFEF4444),
                        lineHeight = 14.sp
                    )
                }

                // ── 3-MODE SEGMENTED CHIP SELECTOR: ФОТО / ВИДЕО / ШЕЙДЕРЫ ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.04f))
                        .border(1.dp, AmoledBorder, RoundedCornerShape(12.dp))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val modes = listOf(
                        WallpaperMediaType.PHOTO to R.string.settings_customization_wallpaper_mode_photo,
                        WallpaperMediaType.VIDEO to R.string.settings_customization_wallpaper_mode_video,
                        WallpaperMediaType.SHADER to R.string.settings_customization_wallpaper_mode_shader
                    )
                    modes.forEach { (mode, labelRes) ->
                        val isTabSelected = selectedTab == mode
                        val tabBgColor by animateColorAsState(
                            targetValue = if (isTabSelected) accentColor.copy(alpha = 0.16f) else Color.Transparent,
                            label = "tab_bg"
                        )
                        val tabBorderColor by animateColorAsState(
                            targetValue = if (isTabSelected) accentColor.copy(alpha = 0.65f) else Color.Transparent,
                            label = "tab_border"
                        )
                        val tabTextColor by animateColorAsState(
                            targetValue = if (isTabSelected) TextWhite else TextMuted,
                            label = "tab_text"
                        )

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(9.dp))
                                .background(tabBgColor)
                                .border(1.dp, tabBorderColor, RoundedCornerShape(9.dp))
                                .springPress(onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    selectedTab = mode
                                })
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(labelRes),
                                fontSize = 11.5.sp,
                                fontWeight = if (isTabSelected) FontWeight.Bold else FontWeight.Medium,
                                color = tabTextColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // ── MODE 1: ФОТО ───────────────────────────────────────
                if (selectedTab == WallpaperMediaType.PHOTO) {
                    if (isPhoto && wallpaperImage != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(130.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .border(1.dp, AmoledBorder, RoundedCornerShape(14.dp))
                        ) {
                            WallpaperBlurredImage(
                                image = wallpaperImage,
                                blurDp = sliderBlur,
                                modifier = Modifier.fillMaxSize()
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = sliderDim))
                            )
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(8.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.Black.copy(alpha = 0.65f))
                                    .border(1.dp, AmoledBorder, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                val sizeKb = (wallpaperImage.fileSizeBytes / 1024L).coerceAtLeast(1L)
                                Text(
                                    text = stringResource(
                                        R.string.settings_customization_wallpaper_metrics,
                                        wallpaperImage.width,
                                        wallpaperImage.height,
                                        sizeKb.toString()
                                    ),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = TextWhite
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.Transparent)
                                    .border(1.dp, AmoledBorder, RoundedCornerShape(12.dp))
                                    .springPress()
                                    .clickable(enabled = !isProcessing) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        mediaPickerLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    }
                                    .padding(vertical = 10.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_refresh),
                                    contentDescription = null,
                                    tint = TextWhite,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.settings_customization_wallpaper_replace_image),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextWhite
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.Transparent)
                                    .border(1.dp, AmoledBorder, RoundedCornerShape(12.dp))
                                    .springPress()
                                    .clickable(enabled = !isProcessing) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        showResetConfirm = !showResetConfirm
                                    }
                                    .padding(vertical = 10.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_trash),
                                    contentDescription = null,
                                    tint = Color(0xFFEF4444),
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.settings_customization_wallpaper_reset),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFEF4444)
                                )
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(R.string.settings_customization_wallpaper_blur),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = TextWhite
                                    )
                                    Text(
                                        text = "${sliderBlur.roundToInt()} dp",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = accentColor
                                    )
                                }
                                Slider(
                                    value = sliderBlur,
                                    onValueChange = {
                                        sliderBlur = it
                                        prefs.setWallpaperBlur(it, persist = false)
                                    },
                                    onValueChangeFinished = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        prefs.setWallpaperBlur(sliderBlur, persist = true)
                                    },
                                    valueRange = 0f..25f,
                                    steps = 24,
                                    colors = SliderDefaults.colors(
                                        thumbColor = accentColor,
                                        activeTrackColor = accentColor,
                                        inactiveTrackColor = AmoledBorder
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(R.string.settings_customization_wallpaper_dim),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = TextWhite
                                    )
                                    Text(
                                        text = "${(sliderDim * 100f).roundToInt()}%",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = accentColor
                                    )
                                }
                                Slider(
                                    value = sliderDim,
                                    onValueChange = {
                                        sliderDim = it
                                        prefs.setWallpaperDim(it, persist = false)
                                    },
                                    onValueChangeFinished = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        prefs.setWallpaperDim(sliderDim, persist = true)
                                    },
                                    valueRange = 0.10f..0.85f,
                                    colors = SliderDefaults.colors(
                                        thumbColor = accentColor,
                                        activeTrackColor = accentColor,
                                        inactiveTrackColor = AmoledBorder
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.Transparent)
                                .border(1.dp, AmoledBorder, RoundedCornerShape(12.dp))
                                .springPress()
                                .clickable(enabled = !isProcessing) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    mediaPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                }
                                .padding(vertical = 14.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            WallpaperGlyph(
                                color = accentColor,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.settings_customization_wallpaper_choose_image),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite
                            )
                        }
                    }
                }

                // ── MODE 2: ВИДЕО ───────────────────────────────────────
                if (selectedTab == WallpaperMediaType.VIDEO) {
                    if (isVideo && wallpaperImage != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(130.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .border(1.dp, AmoledBorder, RoundedCornerShape(14.dp))
                        ) {
                            WallpaperBlurredImage(
                                image = wallpaperImage,
                                blurDp = sliderBlur,
                                modifier = Modifier.fillMaxSize()
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = sliderDim))
                            )
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(8.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.Black.copy(alpha = 0.65f))
                                    .border(1.dp, AmoledBorder, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                if (videoMetadata != null) {
                                    val sizeMb = String.format(Locale.US, "%.1f", videoMetadata!!.fileSizeBytes / (1024f * 1024f))
                                    Text(
                                        text = stringResource(
                                            R.string.settings_customization_wallpaper_video_metrics,
                                            videoMetadata!!.width,
                                            videoMetadata!!.height,
                                            videoMetadata!!.durationSec,
                                            sizeMb
                                        ),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = TextWhite
                                    )
                                } else {
                                    Text(
                                        text = "MP4 · 60 FPS",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = TextWhite
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.Transparent)
                                    .border(1.dp, AmoledBorder, RoundedCornerShape(12.dp))
                                    .springPress()
                                    .clickable(enabled = !isProcessing) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        mediaPickerLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                        )
                                    }
                                    .padding(vertical = 10.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_refresh),
                                    contentDescription = null,
                                    tint = TextWhite,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.settings_customization_wallpaper_replace_video),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextWhite
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.Transparent)
                                    .border(1.dp, AmoledBorder, RoundedCornerShape(12.dp))
                                    .springPress()
                                    .clickable(enabled = !isProcessing) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        showResetConfirm = !showResetConfirm
                                    }
                                    .padding(vertical = 10.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_trash),
                                    contentDescription = null,
                                    tint = Color(0xFFEF4444),
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.settings_customization_wallpaper_reset),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFEF4444)
                                )
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(R.string.settings_customization_wallpaper_blur),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = TextWhite
                                    )
                                    Text(
                                        text = "${sliderBlur.roundToInt()} dp",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = accentColor
                                    )
                                }
                                Slider(
                                    value = sliderBlur,
                                    onValueChange = {
                                        sliderBlur = it
                                        prefs.setWallpaperBlur(it, persist = false)
                                    },
                                    onValueChangeFinished = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        prefs.setWallpaperBlur(sliderBlur, persist = true)
                                    },
                                    valueRange = 0f..25f,
                                    steps = 24,
                                    colors = SliderDefaults.colors(
                                        thumbColor = accentColor,
                                        activeTrackColor = accentColor,
                                        inactiveTrackColor = AmoledBorder
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(R.string.settings_customization_wallpaper_dim),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = TextWhite
                                    )
                                    Text(
                                        text = "${(sliderDim * 100f).roundToInt()}%",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = accentColor
                                    )
                                }
                                Slider(
                                    value = sliderDim,
                                    onValueChange = {
                                        sliderDim = it
                                        prefs.setWallpaperDim(it, persist = false)
                                    },
                                    onValueChangeFinished = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        prefs.setWallpaperDim(sliderDim, persist = true)
                                    },
                                    valueRange = 0.10f..0.85f,
                                    colors = SliderDefaults.colors(
                                        thumbColor = accentColor,
                                        activeTrackColor = accentColor,
                                        inactiveTrackColor = AmoledBorder
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.Transparent)
                                    .border(1.dp, AmoledBorder, RoundedCornerShape(12.dp))
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f, fill = false)) {
                                    Text(
                                        text = stringResource(R.string.settings_customization_wallpaper_video_power_save),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextWhite
                                    )
                                    Text(
                                        text = stringResource(R.string.settings_customization_wallpaper_video_power_save_desc),
                                        fontSize = 11.sp,
                                        color = TextMuted,
                                        lineHeight = 14.sp
                                    )
                                }

                                InertialSpringSwitch(
                                    checked = videoPowerSave,
                                    onCheckedChange = { checked ->
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        prefs.setWallpaperVideoPowerSave(checked)
                                    }
                                )
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.Transparent)
                                .border(1.dp, AmoledBorder, RoundedCornerShape(12.dp))
                                .springPress()
                                .clickable(enabled = !isProcessing) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    mediaPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                    )
                                }
                                .padding(vertical = 14.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_video),
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.settings_customization_wallpaper_choose_video),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite
                            )
                        }
                    }
                }

                // ── MODE 3: ПРОЦЕДУРНЫЙ ШЕЙДЕР (Android 13+ / API 33+) ──
                if (selectedTab == WallpaperMediaType.SHADER) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.White.copy(alpha = 0.03f))
                                .border(1.dp, AmoledBorder, RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.settings_customization_wallpaper_shader_title),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextWhite
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .border(1.dp, (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) accentColor else TextMuted).copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 7.dp, vertical = 2.5.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_customization_wallpaper_shader_notice),
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) accentColor else TextMuted
                                )
                            }
                        }

                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                            Text(
                                text = stringResource(R.string.settings_customization_wallpaper_shader_legacy_unsupported),
                                fontSize = 11.sp,
                                color = TextMuted,
                                lineHeight = 15.sp
                            )
                        }

                        val shaderPresets = listOf(
                            AgslShaderPreset.PERSPECTIVE_GRID to R.string.settings_customization_wallpaper_shader_grid,
                            AgslShaderPreset.DIGITAL_STREAM to R.string.settings_customization_wallpaper_shader_stream,
                            AgslShaderPreset.SCANLINES to R.string.settings_customization_wallpaper_shader_scanlines
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            shaderPresets.forEach { (preset, labelRes) ->
                                val isSelected = isShader && activeShaderPreset == preset
                                val borderColor by animateColorAsState(
                                    targetValue = if (isSelected) accentColor else AmoledBorder,
                                    label = "shader_btn_border"
                                )
                                val textColor by animateColorAsState(
                                    targetValue = if (isSelected) TextWhite else TextMuted,
                                    label = "shader_btn_text"
                                )

                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isSelected) accentColor.copy(alpha = 0.12f) else Color.Transparent,
                                    border = BorderStroke(1.dp, borderColor),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .springPress(onClick = {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                prefs.activateShaderWallpaper(preset)
                                                showResetConfirm = false
                                            } else {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                        })
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 11.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = stringResource(labelRes),
                                            fontSize = 11.5.sp,
                                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                            color = textColor
                                        )
                                    }
                                }
                            }
                        }

                        if (isShader) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(130.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .border(1.dp, AmoledBorder, RoundedCornerShape(14.dp))
                            ) {
                                AgslWallpaperShader(
                                    preset = activeShaderPreset,
                                    accentColor = accentColor,
                                    speed = sliderShaderSpeed,
                                    modifier = Modifier.fillMaxSize()
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = sliderDim))
                                )
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(8.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.Black.copy(alpha = 0.65f))
                                        .border(1.dp, AmoledBorder, RoundedCornerShape(8.dp))
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        text = "AGSL · 120 FPS",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = TextWhite
                                    )
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color.Transparent,
                                border = BorderStroke(1.dp, AmoledBorder),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .springPress(onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        showResetConfirm = !showResetConfirm
                                    })
                            ) {
                                Row(
                                    modifier = Modifier.padding(vertical = 10.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_trash),
                                        contentDescription = null,
                                        tint = Color(0xFFEF4444),
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(R.string.settings_customization_wallpaper_reset),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFEF4444)
                                    )
                                }
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = stringResource(R.string.settings_customization_wallpaper_shader_speed),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = TextWhite
                                        )
                                        Text(
                                            text = String.format(Locale.US, "%.1fx", sliderShaderSpeed),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = accentColor
                                        )
                                    }

                                    Slider(
                                        value = sliderShaderSpeed,
                                        onValueChange = {
                                            sliderShaderSpeed = it
                                            prefs.setWallpaperShaderSpeed(it, persist = false)
                                        },
                                        onValueChangeFinished = {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            prefs.setWallpaperShaderSpeed(sliderShaderSpeed, persist = true)
                                        },
                                        valueRange = PreferencesManager.WALLPAPER_SHADER_SPEED_MIN..PreferencesManager.WALLPAPER_SHADER_SPEED_MAX,
                                        colors = SliderDefaults.colors(
                                            thumbColor = accentColor,
                                            activeTrackColor = accentColor,
                                            inactiveTrackColor = AmoledBorder
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }

                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = stringResource(R.string.settings_customization_wallpaper_dim),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = TextWhite
                                        )
                                        Text(
                                            text = "${(sliderDim * 100f).roundToInt()}%",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = accentColor
                                        )
                                    }

                                    Slider(
                                        value = sliderDim,
                                        onValueChange = {
                                            sliderDim = it
                                            prefs.setWallpaperDim(it, persist = false)
                                        },
                                        onValueChangeFinished = {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            prefs.setWallpaperDim(sliderDim, persist = true)
                                        },
                                        valueRange = 0.10f..0.85f,
                                        colors = SliderDefaults.colors(
                                            thumbColor = accentColor,
                                            activeTrackColor = accentColor,
                                            inactiveTrackColor = AmoledBorder
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                }

                // ── GYROSCOPIC 3D PARALLAX ENGINE ───────────────────
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Transparent)
                        .border(1.dp, AmoledBorder, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_customization_wallpaper_parallax_depth),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextWhite
                            )
                            Text(
                                text = stringResource(R.string.settings_customization_wallpaper_parallax_depth_desc),
                                fontSize = 10.5.sp,
                                color = TextMuted
                            )
                        }
                        InertialSpringSwitch(
                            checked = parallaxEnabled,
                            onCheckedChange = { checked ->
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                prefs.setWallpaperParallaxEnabled(checked)
                            },
                            activeColor = accentColor
                        )
                    }

                    AnimatedVisibility(
                        visible = parallaxEnabled,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Амплитуда параллакса",
                                    fontSize = 11.sp,
                                    color = TextMuted
                                )
                                Text(
                                    text = String.format(Locale.US, "%.1fx", sliderParallaxDepth),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = accentColor
                                )
                            }
                            Slider(
                                value = sliderParallaxDepth,
                                onValueChange = {
                                    sliderParallaxDepth = it
                                    prefs.setWallpaperParallaxDepth(it, persist = false)
                                },
                                onValueChangeFinished = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    prefs.setWallpaperParallaxDepth(sliderParallaxDepth, persist = true)
                                },
                                valueRange = PreferencesManager.WALLPAPER_PARALLAX_DEPTH_MIN..PreferencesManager.WALLPAPER_PARALLAX_DEPTH_MAX,
                                steps = 22,
                                colors = SliderDefaults.colors(
                                    thumbColor = accentColor,
                                    activeTrackColor = accentColor,
                                    inactiveTrackColor = AmoledBorder
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                // ── RESET CONFIRMATION ACCORDION ──────────────────────
                AnimatedVisibility(
                    visible = showResetConfirm,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Transparent)
                            .border(1.dp, Color(0xFFEF4444).copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.settings_customization_wallpaper_reset_confirm_title),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFEF4444)
                        )
                        Text(
                            text = stringResource(R.string.settings_customization_wallpaper_reset_confirm_desc),
                            fontSize = 11.sp,
                            color = TextMuted,
                            lineHeight = 15.sp
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.Transparent)
                                    .border(1.dp, Color(0xFFEF4444), RoundedCornerShape(10.dp))
                                    .springPress()
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        prefs.clearCustomWallpaper()
                                        showResetConfirm = false
                                    }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_customization_wallpaper_reset_confirm_btn),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFEF4444)
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.Transparent)
                                    .border(1.dp, AmoledBorder, RoundedCornerShape(10.dp))
                                    .springPress()
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        showResetConfirm = false
                                    }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_customization_wallpaper_cancel),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Normal,
                                    color = TextMuted
                                )
                            }
                        }
                    }
                }

                // ── PRESETS SECTION ───────────────────────────────────
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.settings_customization_presets_title),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite
                        )

                        if (hasCustomWallpaper) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color.Transparent,
                                border = BorderStroke(1.dp, accentColor.copy(alpha = 0.6f)),
                                modifier = Modifier
                                    .springPress()
                                    .clickable(enabled = !isProcessing) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        showSavePresetCard = !showSavePresetCard
                                    }
                            ) {
                                Text(
                                    text = "+ ${stringResource(R.string.settings_customization_preset_save_current)}",
                                    color = accentColor,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = showSavePresetCard,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.Transparent)
                                .border(1.dp, accentColor.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_customization_preset_save_title),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextWhite
                                )
                                val presetBadgeText = when {
                                    isShader -> "Шейдер • ${(sliderDim * 100).roundToInt()}%"
                                    isVideo -> "Видео • ${sliderBlur.roundToInt()} dp • ${(sliderDim * 100).roundToInt()}%"
                                    else -> "Фото • ${sliderBlur.roundToInt()} dp • ${(sliderDim * 100).roundToInt()}%"
                                }
                                Text(
                                    text = presetBadgeText,
                                    fontSize = 10.sp,
                                    color = accentColor
                                )
                            }

                            BasicTextField(
                                value = newPresetTitle,
                                onValueChange = { newPresetTitle = it },
                                singleLine = true,
                                textStyle = TextStyle(
                                    color = TextWhite,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Default
                                ),
                                cursorBrush = SolidColor(accentColor),
                                decorationBox = { innerTextField ->
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color.Transparent)
                                            .border(1.dp, AmoledBorder, RoundedCornerShape(8.dp))
                                            .padding(horizontal = 10.dp, vertical = 8.dp)
                                    ) {
                                        if (newPresetTitle.isEmpty()) {
                                            Text(
                                                text = stringResource(R.string.settings_customization_preset_name_hint),
                                                color = TextMuted,
                                                fontSize = 12.sp
                                            )
                                        }
                                        innerTextField()
                                    }
                                }
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.Transparent)
                                        .border(1.dp, accentColor, RoundedCornerShape(8.dp))
                                        .springPress()
                                        .clickable {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            val titleToSave = newPresetTitle.trim().ifEmpty {
                                                when {
                                                    isShader -> "Шейдер пресет"
                                                    isVideo -> "Видео пресет"
                                                    else -> "Фото пресет"
                                                }
                                            }
                                            coroutineScope.launch {
                                                prefs.saveCurrentAsUserPreset(titleToSave)
                                                newPresetTitle = ""
                                                showSavePresetCard = false
                                            }
                                        }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = stringResource(R.string.settings_customization_preset_save_btn),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = accentColor
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.Transparent)
                                        .border(1.dp, AmoledBorder, RoundedCornerShape(8.dp))
                                        .springPress()
                                        .clickable {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            showSavePresetCard = false
                                        }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = stringResource(R.string.settings_customization_wallpaper_cancel),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Normal,
                                        color = TextMuted
                                    )
                                }
                            }
                        }
                    }

                    val presetsScrollState = rememberScrollState()
                    val startFadeWidth by animateDpAsState(
                        targetValue = if (presetsScrollState.canScrollBackward) 16.dp else 0.dp,
                        animationSpec = tween(180),
                        label = "presetStartFade"
                    )
                    val endFadeWidth by animateDpAsState(
                        targetValue = if (presetsScrollState.canScrollForward) 24.dp else 0.dp,
                        animationSpec = tween(180),
                        label = "presetEndFade"
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalFadingEdges(
                                startFadeWidth = startFadeWidth,
                                endFadeWidth = endFadeWidth
                            )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(presetsScrollState),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            allPresets.forEach { preset ->
                                val isPresetActive = hasCustomWallpaper && activePresetId == preset.id
                                WallpaperPresetCard(
                                    preset = preset,
                                    isActive = isPresetActive,
                                    accentColor = accentColor,
                                    onSelect = {
                                        coroutineScope.launch {
                                            isProcessing = true
                                            errorMessage = null
                                            try {
                                                val res = prefs.applyPreset(preset)
                                                if (res.isFailure) {
                                                    errorMessage = res.exceptionOrNull()?.localizedMessage ?: "Unknown error"
                                                } else {
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    showResetConfirm = false
                                                }
                                            } catch (e: Exception) {
                                                errorMessage = e.localizedMessage ?: "Unknown error"
                                            } finally {
                                                isProcessing = false
                                            }
                                        }
                                    },
                                    onDelete = if (!preset.isBuiltIn) {
                                        {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            prefs.deleteUserPreset(preset.id)
                                        }
                                    } else null
                                )
                            }
                        }
                    }
                }

                // ── DYNAMIC PARTICLES SWITCH ──────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Transparent)
                        .border(1.dp, AmoledBorder, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        Text(
                            text = stringResource(R.string.settings_customization_wallpaper_particles),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextWhite
                        )
                        Text(
                            text = stringResource(R.string.settings_customization_wallpaper_particles_desc),
                            fontSize = 11.sp,
                            color = TextMuted,
                            lineHeight = 14.sp
                        )
                    }

                    InertialSpringSwitch(
                        checked = particlesEnabled,
                        onCheckedChange = { checked ->
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            prefs.setWallpaperParticlesEnabled(checked)
                        }
                    )
                }
            }
        }
    }
}

@Composable
internal fun WallpaperPresetCard(
    preset: WallpaperPreset,
    isActive: Boolean,
    accentColor: Color,
    onSelect: () -> Unit,
    onDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val previewImage = rememberWallpaperImage(
        path = preset.previewAssetPath ?: preset.previewFilePath ?: preset.filePath,
        revision = 0L
    )

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        border = BorderStroke(
            width = if (isActive) 1.5.dp else 1.dp,
            color = if (isActive) accentColor else AmoledBorder
        ),
        modifier = modifier
            .width(112.dp)
            .height(168.dp)
            .clip(RoundedCornerShape(14.dp))
            .springPress()
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onSelect()
            }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(108.dp)
                    .clip(RoundedCornerShape(topStart = 13.dp, topEnd = 13.dp))
                    .background(Color.Black)
            ) {
                if (preset.type == PreferencesManager.WALLPAPER_TYPE_VIDEO) {
                    PresetVideoPreview(
                        videoPath = preset.filePath,
                        assetPath = preset.previewAssetPath?.takeIf { it.endsWith(".mp4") } ?: preset.assetPath,
                        previewImage = previewImage,
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (previewImage != null) {
                    Image(
                        bitmap = previewImage.bitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Delete Button for user custom presets (top end)
                if (onDelete != null && !preset.isBuiltIn) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.70f))
                            .border(1.dp, Color(0xFFEF4444).copy(alpha = 0.6f), CircleShape)
                            .springPress()
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onDelete()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "×",
                            color = Color(0xFFEF4444),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Title & Parameters
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 7.dp, vertical = 5.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                val displayTitle = preset.titleRes?.let { stringResource(it) } ?: preset.title
                Text(
                    text = displayTitle,
                    fontSize = 11.sp,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                    color = if (isActive) TextWhite else TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${preset.blurDp.roundToInt()} dp • ${(preset.dimFactor * 100).roundToInt()}%",
                    fontSize = 9.sp,
                    color = TextMuted,
                    maxLines = 1
                )
            }
        }
    }
}
