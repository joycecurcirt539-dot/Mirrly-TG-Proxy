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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.ui.theme.AmoledBackground
import com.mirrly.tgproxy.ui.theme.AmoledBorder
import com.mirrly.tgproxy.ui.theme.AmoledSurfaceLow
import com.mirrly.tgproxy.ui.theme.LocalProtocolColors
import com.mirrly.tgproxy.ui.theme.TextMuted
import com.mirrly.tgproxy.ui.theme.TextWhite
import com.mirrly.tgproxy.ui.theme.adaptiveContainerWidth
import com.mirrly.tgproxy.ui.theme.springPress

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * Global tracker for active bottom sheets in the application.
 * Allows background screens (HomeScreen, SettingsScreen, etc.) to apply
 * hardware-accelerated Skia RenderEffect blur behind the sheet.
 */
object BottomSheetStateTracker {
    var activeSheetCount by mutableIntStateOf(0)
    val isAnySheetOpen: Boolean get() = activeSheetCount > 0
}

/**
 * Universal MirrlyUI Modal Bottom Sheet.
 * Implements the Zero-Popup architecture dogma with Option 2 Vertical Gradient Blur:
 * - 50% translucent AMOLED dark canvas (AmoledBackground.copy(alpha = 0.50f)).
 * - Crystal-clear, un-dimmed upper screen (0..26% height) with zero blur, zero scrim (scrimColor = Color.Transparent).
 * - Vertical gradient backdrop transition (26%..50% height) smoothly fading into 50% dark overlay.
 * - Hardware-accelerated Skia RenderEffect blur applied to underlying screen body content via BottomSheetStateTracker.
 * - No outer border on sheet container (borders applied ONLY to internal cards).
 * - Responsive density constraint for tablets and landscape displays.
 * - Minimalist kinetic drag handle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MirrlyModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    borderColor: Color = Color.Transparent,
    borderWidth: Dp = 0.dp,
    containerColor: Color = AmoledBackground.copy(alpha = 0.50f),
    scrimColor: Color = Color.Transparent,
    maxTabletWidth: Dp = 520.dp,
    windowInsets: WindowInsets = WindowInsets.navigationBars,
    showDragHandle: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    androidx.compose.runtime.DisposableEffect(Unit) {
        BottomSheetStateTracker.activeSheetCount++
        onDispose {
            BottomSheetStateTracker.activeSheetCount = (BottomSheetStateTracker.activeSheetCount - 1).coerceAtLeast(0)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = containerColor,
        contentColor = TextWhite,
        scrimColor = scrimColor,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        windowInsets = windowInsets,
        dragHandle = if (showDragHandle) {
            { MirrlyDragHandle() }
        } else null,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .adaptiveContainerWidth(maxTabletWidth)
                    .padding(bottom = 16.dp),
                content = content
            )
        }
    }
}

/**
 * Minimalist kinetic drag handle for MirrlyUI bottom sheets.
 */
@Composable
fun MirrlyDragHandle(
    modifier: Modifier = Modifier,
    handleColor: Color = AmoledBorder.copy(alpha = 0.85f)
) {
    Box(
        modifier = modifier
            .padding(vertical = 12.dp)
            .size(width = 36.dp, height = 4.dp)
            .background(handleColor, RoundedCornerShape(2.dp))
    )
}

/**
 * Standard MirrlyUI Header for bottom sheets and expandable panels.
 * Contains:
 * - Optional technical badge (outline-only).
 * - Strict typography title and optional subtitle.
 * - Precision vector close button (ic_close) with spring tactile click.
 */
@Composable
fun MirrlyBottomSheetHeader(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    badgeText: String? = null,
    badgeColor: Color = LocalProtocolColors.current.primary
) {
    val context = LocalContext.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = title,
                color = TextWhite,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = TextMuted,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Box(
            modifier = Modifier
                .size(32.dp)
                .border(BorderStroke(1.dp, AmoledBorder), CircleShape)
                .clip(CircleShape)
                .springPress(onClick = {
                    HapticHelper.performTapClick(context)
                    onClose()
                }),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_close),
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

/**
 * Sticky action dock for primary/secondary bottom sheet operations.
 * Adheres to the Outline-Only / Zero-Background doctrine.
 */
@Composable
fun MirrlyActionDock(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/**
 * In-place expandable card for settings and details lists.
 * Smoothly expands in the content flow without popup modals.
 */
@Composable
fun MirrlyExpandableCard(
    isExpanded: Boolean,
    onToggle: () -> Unit,
    header: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    borderColor: Color = AmoledBorder,
    expandedBorderColor: Color = LocalProtocolColors.current.primary.copy(alpha = 0.6f),
    content: @Composable ColumnScope.() -> Unit
) {
    val currentBorder = if (isExpanded) expandedBorderColor else borderColor

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing)),
        color = Color.Transparent,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, currentBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onToggle
                    )
            ) {
                header()
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(animationSpec = tween(240)) + fadeIn(tween(200)),
                exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(tween(160))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    content = content
                )
            }
        }
    }
}
