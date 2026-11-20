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

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.service.PreferencesManager
import com.mirrly.tgproxy.service.WallpaperPreset
import com.mirrly.tgproxy.ui.icon.MirrlyIcon
import com.mirrly.tgproxy.ui.theme.AmoledBackground
import com.mirrly.tgproxy.ui.theme.AmoledBorder
import com.mirrly.tgproxy.ui.theme.LocalProtocolColors
import com.mirrly.tgproxy.ui.theme.TextMuted
import com.mirrly.tgproxy.ui.theme.TextWhite
import com.mirrly.tgproxy.ui.theme.fadingEdges
import com.mirrly.tgproxy.ui.theme.lightSweep
import com.mirrly.tgproxy.ui.theme.springPress
import kotlin.math.roundToInt

/**
 * One-time bottom-sheet notification shown when a user's previously configured
 * custom photo/video background was automatically preserved as a personal profile
 * before switching the app's default background to Pure Black AMOLED.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomWallpaperMigratedSheet(
    preset: WallpaperPreset,
    onRestoreMyBackground: () -> Unit,
    onOpenWallpaperSettings: () -> Unit,
    onKeepBlackAndDismiss: () -> Unit
) {
    val context = LocalContext.current
    val protoColors = LocalProtocolColors.current
    val primaryAccent = protoColors.primary

    val isVideo = preset.type == PreferencesManager.WALLPAPER_TYPE_VIDEO
    val mediaLabel = stringResource(
        if (isVideo) R.string.wallpaper_migrated_media_video
        else R.string.wallpaper_migrated_media_photo
    )
    val blurInt = preset.blurDp.roundToInt()
    val dimPercent = (preset.dimFactor * 100f).roundToInt()
    val metaText = stringResource(
        R.string.wallpaper_migrated_preset_meta,
        mediaLabel,
        blurInt,
        dimPercent
    )
    val presetDisplayTitle = preset.title.ifBlank {
        stringResource(R.string.wallpaper_migrated_preset_name)
    }

    MirrlyModalBottomSheet(
        onDismissRequest = onKeepBlackAndDismiss,
        maxTabletWidth = 480.dp,
        containerColor = AmoledBackground.copy(alpha = 0.92f),
        scrimColor = AmoledBackground.copy(alpha = 0.60f),
        borderWidth = 0.dp,
        enableBackdropBlur = true,
        blurRadiusPx = 60
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fadingEdges(topFadeHeight = 14.dp, bottomFadeHeight = 20.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Outline badge pill
            Surface(
                shape = CircleShape,
                color = Color.Transparent,
                border = BorderStroke(1.dp, primaryAccent.copy(alpha = 0.45f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MirrlyIcon(
                        id = R.drawable.ic_wallpaper,
                        modifier = Modifier.size(14.dp),
                        tint = primaryAccent
                    )
                    Text(
                        text = stringResource(R.string.wallpaper_migrated_badge),
                        color = primaryAccent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.6.sp
                    )
                }
            }

            // Title & reassuring explanation
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.wallpaper_migrated_title),
                    color = TextWhite,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 24.sp
                )
                Text(
                    text = stringResource(R.string.wallpaper_migrated_message),
                    color = TextMuted,
                    fontSize = 13.5.sp,
                    lineHeight = 19.sp
                )
            }

            // Auto-saved personal profile card with media thumbnail
            val previewShape = RoundedCornerShape(16.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(previewShape)
                    .background(Color.Transparent)
                    .border(1.dp, primaryAccent.copy(alpha = 0.40f), previewShape)
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MigratedWallpaperThumbnail(
                        preset = preset,
                        accentColor = primaryAccent,
                        modifier = Modifier.size(width = 64.dp, height = 84.dp)
                    )

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.wallpaper_migrated_preview_badge),
                            color = primaryAccent,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            text = presetDisplayTitle,
                            color = TextWhite,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = metaText,
                            color = TextMuted,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            // Exact Settings navigation path card
            val pathShape = RoundedCornerShape(14.dp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(pathShape)
                    .background(Color.Transparent)
                    .border(1.dp, AmoledBorder, pathShape)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MirrlyIcon(
                        id = R.drawable.ic_settings,
                        modifier = Modifier.size(14.dp),
                        tint = TextMuted
                    )
                    Text(
                        text = stringResource(R.string.wallpaper_migrated_path_label),
                        color = TextMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Text(
                    text = stringResource(R.string.wallpaper_migrated_path_value),
                    color = TextWhite,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 18.sp
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            // Quick background selection actions
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MigratedSheetActionButton(
                    text = stringResource(R.string.wallpaper_migrated_action_restore),
                    iconRes = R.drawable.ic_check_circle,
                    borderColor = primaryAccent,
                    contentColor = TextWhite,
                    iconTint = primaryAccent,
                    containerTint = primaryAccent.copy(alpha = 0.08f),
                    enableLightSweep = true,
                    sweepColor = primaryAccent,
                    onClick = {
                        HapticHelper.performTapClick(context)
                        onRestoreMyBackground()
                    }
                )

                MigratedSheetActionButton(
                    text = stringResource(R.string.wallpaper_migrated_action_choose),
                    iconRes = R.drawable.ic_wallpaper,
                    borderColor = AmoledBorder,
                    contentColor = TextWhite,
                    iconTint = TextWhite,
                    containerTint = Color.Transparent,
                    enableLightSweep = false,
                    sweepColor = primaryAccent,
                    onClick = {
                        HapticHelper.performTapClick(context)
                        onOpenWallpaperSettings()
                    }
                )

                MigratedSheetActionButton(
                    text = stringResource(R.string.wallpaper_migrated_action_keep_black),
                    iconRes = null,
                    borderColor = AmoledBorder.copy(alpha = 0.65f),
                    contentColor = TextMuted,
                    iconTint = TextMuted,
                    containerTint = Color.Transparent,
                    enableLightSweep = false,
                    sweepColor = primaryAccent,
                    onClick = {
                        HapticHelper.performTapClick(context)
                        onKeepBlackAndDismiss()
                    }
                )
            }
        }
    }
}

@Composable
private fun MigratedWallpaperThumbnail(
    preset: WallpaperPreset,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    val thumbShape = RoundedCornerShape(10.dp)
    val previewImage = rememberWallpaperImage(
        path = preset.previewFilePath ?: preset.filePath,
        revision = preset.createdAt
    )

    Box(
        modifier = modifier
            .clip(thumbShape)
            .background(AmoledBackground)
            .border(1.dp, AmoledBorder, thumbShape),
        contentAlignment = Alignment.Center
    ) {
        when {
            preset.type == PreferencesManager.WALLPAPER_TYPE_VIDEO -> {
                PresetVideoPreview(
                    videoPath = preset.filePath,
                    assetPath = preset.assetPath,
                    previewImage = previewImage,
                    modifier = Modifier.fillMaxSize()
                )
            }
            previewImage != null -> {
                Image(
                    bitmap = previewImage.bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            else -> {
                MirrlyIcon(
                    id = R.drawable.ic_wallpaper,
                    modifier = Modifier.size(26.dp),
                    tint = accentColor
                )
            }
        }
    }
}

@Composable
private fun MigratedSheetActionButton(
    text: String,
    @DrawableRes iconRes: Int?,
    borderColor: Color,
    contentColor: Color,
    iconTint: Color,
    containerTint: Color,
    enableLightSweep: Boolean,
    sweepColor: Color,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(containerTint)
            .border(1.dp, borderColor, shape)
            .then(
                if (enableLightSweep) {
                    Modifier.lightSweep(
                        isEnabled = true,
                        shape = shape,
                        borderWidth = 1.2.dp,
                        sweepColor = sweepColor
                    )
                } else {
                    Modifier
                }
            )
            .springPress(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (iconRes != null) {
            MirrlyIcon(
                id = iconRes,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(18.dp),
                tint = iconTint
            )
        }
        Text(
            text = text,
            color = contentColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
