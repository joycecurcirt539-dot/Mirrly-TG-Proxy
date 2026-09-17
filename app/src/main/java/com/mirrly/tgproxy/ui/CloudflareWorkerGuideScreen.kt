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
import androidx.annotation.StringRes
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.core.TgConstants
import com.mirrly.tgproxy.ui.theme.*

private enum class GuideTab(@StringRes val titleRes: Int) {
    PC(R.string.cf_guide_tab_pc),
    PHONE(R.string.cf_guide_tab_phone),
    SCRIPT(R.string.cf_guide_tab_script),
    FAQ(R.string.cf_guide_tab_faq)
}

private data class GuideStepItem(
    val stepNumber: String,
    @StringRes val titleRes: Int,
    @StringRes val descRes: Int,
    @StringRes val actionTextRes: Int? = null,
    val isCopyAction: Boolean = false,
    val isDashAction: Boolean = false
)

private data class FaqItem(
    @StringRes val titleRes: Int,
    @StringRes val descRes: Int
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudflareWorkerGuideScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val protoColors = LocalProtocolColors.current
    val activeProtoColor = protoColors.primary

    var selectedTab by remember { mutableStateOf(GuideTab.PC) }
    var headerHeightDp by remember { mutableStateOf(176.dp) }
    var showDashboardConfirmDialog by remember { mutableStateOf(false) }
    var showDeployScriptConfirmDialog by remember { mutableStateOf(false) }

    fun handleDismiss() {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        onBack()
    }

    val nestedScrollConnection = remember(density) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                val thresholdPx = with(density) { -24.dp.toPx() }
                if (available.y < thresholdPx) {
                    handleDismiss()
                }
                return Offset.Zero
            }
        }
    }

    if (showDashboardConfirmDialog) {
        ExternalLinkConfirmDialog(
            url = "https://dash.cloudflare.com/",
            title = stringResource(R.string.cf_guide_link_dash_title),
            description = stringResource(R.string.cf_guide_link_dash_desc),
            onDismiss = { showDashboardConfirmDialog = false }
        )
    }

    if (showDeployScriptConfirmDialog) {
        ExternalLinkConfirmDialog(
            url = "https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/tree/main/tools/deploy-worker",
            title = stringResource(R.string.cf_guide_link_autodeploy_title),
            description = stringResource(R.string.cf_guide_link_autodeploy_desc),
            onDismiss = { showDeployScriptConfirmDialog = false }
        )
    }

    fun copyDeployCommandToClipboard() {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val cmd = "irm https://raw.githubusercontent.com/joycecurcirt539-dot/Mirrly-TG-Proxy/main/tools/deploy-worker/deploy.ps1 | iex"
        val clip = ClipData.newPlainText("Mirrly Deploy Command", cmd)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, context.getString(R.string.cf_guide_toast_deploy_copied), Toast.LENGTH_SHORT).show()
    }

    fun copyScriptToClipboard() {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Cloudflare Worker Script", TgConstants.CLOUDFLARE_WORKER_JS_CODE)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, context.getString(R.string.cf_guide_toast_script_copied), Toast.LENGTH_SHORT).show()
    }

    val topGuideTabs = remember { listOf(GuideTab.PC, GuideTab.PHONE, GuideTab.SCRIPT) }
    val allGuideTabs = remember { listOf(GuideTab.PC, GuideTab.PHONE, GuideTab.SCRIPT, GuideTab.FAQ) }

    fun switchToNextTab() {
        val currentIndex = allGuideTabs.indexOf(selectedTab)
        if (currentIndex < allGuideTabs.size - 1) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            selectedTab = allGuideTabs[currentIndex + 1]
        }
    }

    fun switchToPreviousTab() {
        val currentIndex = allGuideTabs.indexOf(selectedTab)
        if (currentIndex > 0) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            selectedTab = allGuideTabs[currentIndex - 1]
        }
    }

    fun openCloudflareDashboard() {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        showDashboardConfirmDialog = true
    }

    val pcSteps = remember {
        listOf(
            GuideStepItem(
                stepNumber = "1",
                titleRes = R.string.cf_guide_step_pc_1_title,
                descRes = R.string.cf_guide_step_pc_1_desc,
                actionTextRes = R.string.cf_guide_step_pc_1_action,
                isDashAction = true
            ),
            GuideStepItem(
                stepNumber = "2",
                titleRes = R.string.cf_guide_step_pc_2_title,
                descRes = R.string.cf_guide_step_pc_2_desc
            ),
            GuideStepItem(
                stepNumber = "3",
                titleRes = R.string.cf_guide_step_pc_3_title,
                descRes = R.string.cf_guide_step_pc_3_desc
            ),
            GuideStepItem(
                stepNumber = "4",
                titleRes = R.string.cf_guide_step_pc_4_title,
                descRes = R.string.cf_guide_step_pc_4_desc,
                actionTextRes = R.string.cf_guide_step_pc_4_action,
                isCopyAction = true
            ),
            GuideStepItem(
                stepNumber = "5",
                titleRes = R.string.cf_guide_step_pc_5_title,
                descRes = R.string.cf_guide_step_pc_5_desc
            ),
            GuideStepItem(
                stepNumber = "6",
                titleRes = R.string.cf_guide_step_pc_6_title,
                descRes = R.string.cf_guide_step_pc_6_desc
            )
        )
    }

    val phoneSteps = remember {
        listOf(
            GuideStepItem(
                stepNumber = "1",
                titleRes = R.string.cf_guide_step_ph_1_title,
                descRes = R.string.cf_guide_step_ph_1_desc,
                actionTextRes = R.string.cf_guide_step_ph_1_action,
                isDashAction = true
            ),
            GuideStepItem(
                stepNumber = "2",
                titleRes = R.string.cf_guide_step_ph_2_title,
                descRes = R.string.cf_guide_step_ph_2_desc
            ),
            GuideStepItem(
                stepNumber = "3",
                titleRes = R.string.cf_guide_step_ph_3_title,
                descRes = R.string.cf_guide_step_ph_3_desc
            ),
            GuideStepItem(
                stepNumber = "4",
                titleRes = R.string.cf_guide_step_ph_4_title,
                descRes = R.string.cf_guide_step_ph_4_desc,
                actionTextRes = R.string.cf_guide_step_ph_4_action,
                isCopyAction = true
            ),
            GuideStepItem(
                stepNumber = "5",
                titleRes = R.string.cf_guide_step_ph_5_title,
                descRes = R.string.cf_guide_step_ph_5_desc
            )
        )
    }

    val faqItems = remember {
        listOf(
            FaqItem(
                titleRes = R.string.cf_guide_faq_1_title,
                descRes = R.string.cf_guide_faq_1_desc
            ),
            FaqItem(
                titleRes = R.string.cf_guide_faq_2_title,
                descRes = R.string.cf_guide_faq_2_desc
            ),
            FaqItem(
                titleRes = R.string.cf_guide_faq_3_title,
                descRes = R.string.cf_guide_faq_3_desc
            ),
            FaqItem(
                titleRes = R.string.cf_guide_faq_4_title,
                descRes = R.string.cf_guide_faq_4_desc
            ),
            FaqItem(
                titleRes = R.string.cf_guide_faq_5_title,
                descRes = R.string.cf_guide_faq_5_desc
            ),
            FaqItem(
                titleRes = R.string.cf_guide_faq_6_title,
                descRes = R.string.cf_guide_faq_6_desc
            )
        )
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        // Delicate Cyber Particles floating in background behind guide interface
        CyberParticlesOverlay(
            modifier = Modifier.fillMaxSize(),
            particleCount = 36,
            alphaMultiplier = 0.65f
        )

        // 1. SCROLLABLE GUIDE FEED
        LazyColumn(
            modifier = Modifier
                .adaptiveContainerWidth(600.dp)
                .fillMaxHeight()
                .fadingEdges(topFadeHeight = 24.dp, bottomFadeHeight = 44.dp)
                .nestedScroll(nestedScrollConnection)
                .adaptiveContentPadding(),
            contentPadding = PaddingValues(
                top = headerHeightDp + 8.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            when (selectedTab) {
                GuideTab.PC -> {
                    item(key = "pc_auto_deploy_card") {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = activeProtoColor.copy(alpha = 0.08f),
                            border = BorderStroke(1.dp, activeProtoColor.copy(alpha = 0.40f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 2.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(activeProtoColor)
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(4.5.dp),
                                        color = Color.Transparent,
                                        border = BorderStroke(1.dp, activeProtoColor.copy(alpha = 0.40f))
                                    ) {
                                        Text(
                                            text = stringResource(R.string.cf_guide_badge_one_click),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = activeProtoColor,
                                            modifier = Modifier.padding(horizontal = 4.5.dp, vertical = 1.dp)
                                        )
                                    }
                                    Text(
                                        text = stringResource(R.string.cf_guide_autodeploy_title),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = activeProtoColor
                                    )
                                }
                                Text(
                                    text = stringResource(R.string.cf_guide_autodeploy_desc),
                                    fontSize = 11.5.sp,
                                    color = TextWhite.copy(alpha = 0.9f),
                                    lineHeight = 16.sp
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = activeProtoColor.copy(alpha = 0.16f),
                                        border = BorderStroke(1.dp, activeProtoColor.copy(alpha = 0.55f)),
                                        modifier = Modifier
                                            .weight(1.2f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .springPress(onClick = { copyDeployCommandToClipboard() })
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.Center
                                        ) {
                                            Icon(
                                                painter = painterResource(id = R.drawable.ic_copy),
                                                contentDescription = stringResource(R.string.cf_guide_btn_copy),
                                                tint = activeProtoColor,
                                                modifier = Modifier.size(12.dp)
                                            )
                                            Spacer(modifier = Modifier.width(5.dp))
                                            Text(
                                                text = stringResource(R.string.cf_guide_btn_copy_cmd),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = TextWhite
                                            )
                                        }
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color.Transparent,
                                        border = BorderStroke(1.dp, Color(0xFF1E283D)),
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .springPress(onClick = { showDeployScriptConfirmDialog = true })
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.Center
                                        ) {
                                            Icon(
                                                painter = painterResource(id = R.drawable.ic_github),
                                                contentDescription = "GitHub",
                                                tint = TextMuted,
                                                modifier = Modifier.size(12.dp)
                                            )
                                            Spacer(modifier = Modifier.width(5.dp))
                                            Text(
                                                text = stringResource(R.string.cf_guide_btn_deploy_bat),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = TextMuted
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    itemsIndexed(
                        items = pcSteps,
                        key = { _, item -> "pc_${item.stepNumber}" }
                    ) { _, step ->
                        GlassGuideStepCard(
                            stepNumber = step.stepNumber,
                            title = stringResource(step.titleRes),
                            description = stringResource(step.descRes),
                            activeAccentColor = activeProtoColor,
                            actionText = step.actionTextRes?.let { stringResource(it) },
                            onAction = when {
                                step.isCopyAction -> { { copyScriptToClipboard() } }
                                step.isDashAction -> { { openCloudflareDashboard() } }
                                else -> null
                            }
                        )
                    }
                }
                GuideTab.PHONE -> {
                    item(key = "phone_tip") {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF38BDF8).copy(alpha = 0.08f),
                            border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.35f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 2.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF38BDF8))
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(4.5.dp),
                                        color = Color.Transparent,
                                        border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.35f))
                                    ) {
                                        Text(
                                            text = stringResource(R.string.cf_guide_tip_badge),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF38BDF8),
                                            modifier = Modifier.padding(horizontal = 4.5.dp, vertical = 1.dp)
                                        )
                                    }
                                    Text(
                                        text = stringResource(R.string.cf_guide_tip_title),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = Color(0xFF38BDF8)
                                    )
                                }
                                Text(
                                    text = stringResource(R.string.cf_guide_tip_desc),
                                    fontSize = 11.5.sp,
                                    color = TextWhite.copy(alpha = 0.85f),
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }

                    itemsIndexed(
                        items = phoneSteps,
                        key = { _, item -> "phone_${item.stepNumber}" }
                    ) { _, step ->
                        GlassGuideStepCard(
                            stepNumber = step.stepNumber,
                            title = stringResource(step.titleRes),
                            description = stringResource(step.descRes),
                            activeAccentColor = activeProtoColor,
                            actionText = step.actionTextRes?.let { stringResource(it) },
                            onAction = when {
                                step.isCopyAction -> { { copyScriptToClipboard() } }
                                step.isDashAction -> { { openCloudflareDashboard() } }
                                else -> null
                            }
                        )
                    }
                }
                GuideTab.SCRIPT -> {
                    item(key = "script_header") {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, Color(0xFF181E2E)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
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
                                                .background(activeProtoColor)
                                        )
                                        Text(
                                            text = "cloudflare_worker.js",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextWhite
                                        )
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = activeProtoColor.copy(alpha = 0.12f),
                                        border = BorderStroke(1.dp, activeProtoColor.copy(alpha = 0.45f)),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .springPress(onClick = { copyScriptToClipboard() })
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                painter = painterResource(id = R.drawable.ic_copy),
                                                contentDescription = stringResource(R.string.cf_guide_btn_copy),
                                                tint = activeProtoColor,
                                                modifier = Modifier.size(12.dp)
                                            )
                                            Text(
                                                text = stringResource(R.string.cf_guide_btn_copy),
                                                color = activeProtoColor,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }

                                Text(
                                    text = TgConstants.CLOUDFLARE_WORKER_JS_CODE,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.5.sp,
                                    color = TextWhite.copy(alpha = 0.8f),
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }
                }
                GuideTab.FAQ -> {
                    itemsIndexed(
                        items = faqItems,
                        key = { index, _ -> "faq_item_$index" }
                    ) { _, faq ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, Color(0xFF181E2E)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(activeProtoColor)
                                    )
                                    Text(
                                        text = stringResource(faq.titleRes),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextWhite
                                    )
                                }
                                Text(
                                    text = stringResource(faq.descRes),
                                    fontSize = 11.5.sp,
                                    color = TextMuted,
                                    lineHeight = 16.sp,
                                    modifier = Modifier.padding(start = 12.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. PINNED FROSTED GLASS HEADER (Title Bar + 3 Tabs Filter Chips + Full-Width FAQ Chip)
        Box(
            modifier = Modifier
                .adaptiveContainerWidth(600.dp)
                .onGloballyPositioned { coordinates ->
                    val heightInDp = with(density) { coordinates.size.height.toDp() }
                    if (heightInDp > 0.dp && heightInDp != headerHeightDp) {
                        headerHeightDp = heightInDp
                    }
                }
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.99f),
                            Color.Black.copy(alpha = 0.98f),
                            Color.Black.copy(alpha = 0.96f),
                            Color.Black.copy(alpha = 0.88f),
                            Color.Black.copy(alpha = 0.00f)
                        )
                    )
                )
                .pointerInput(Unit) {
                    var headerDragY = 0f
                    detectVerticalDragGestures(
                        onDragStart = { headerDragY = 0f },
                        onDragEnd = {
                            if (headerDragY < -24.dp.toPx()) {
                                handleDismiss()
                            }
                            headerDragY = 0f
                        },
                        onDragCancel = { headerDragY = 0f },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            headerDragY += dragAmount
                        }
                    )
                }
                .padding(bottom = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Top App Bar
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = stringResource(R.string.cf_guide_screen_title),
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = TextWhite,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = stringResource(R.string.cf_guide_screen_subtitle),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = activeProtoColor
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            handleDismiss()
                        }) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_arrow_left),
                                contentDescription = stringResource(R.string.action_back),
                                tint = TextWhite,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { copyScriptToClipboard() },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_copy),
                                contentDescription = stringResource(R.string.cf_guide_btn_copy),
                                tint = activeProtoColor,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = TextWhite
                    ),
                    modifier = Modifier.statusBarsPadding()
                )

                // 1. Top 3 Filter Chips Row (PC, Phone, Script) - Stretched evenly across width
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    topGuideTabs.forEach { tab ->
                        val isSelected = selectedTab == tab
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = if (isSelected) activeProtoColor.copy(alpha = 0.12f) else Color.Transparent,
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (isSelected) activeProtoColor.copy(alpha = 0.7f) else Color(0xFF1E283D)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(34.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .springPress(onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    selectedTab = tab
                                })
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(tab.titleRes),
                                    color = if (isSelected) activeProtoColor else TextMuted,
                                    fontSize = 11.5.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                // 2. Full-Width 4th Pill Under Top 3 Pills (Benefits and FAQ)
                val isFaqSelected = selectedTab == GuideTab.FAQ
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (isFaqSelected) activeProtoColor.copy(alpha = 0.12f) else Color.Transparent,
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (isFaqSelected) activeProtoColor.copy(alpha = 0.7f) else Color(0xFF1E283D)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            selectedTab = GuideTab.FAQ
                        })
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(GuideTab.FAQ.titleRes),
                            color = if (isFaqSelected) activeProtoColor else TextMuted,
                            fontSize = 11.5.sp,
                            fontWeight = if (isFaqSelected) FontWeight.Bold else FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassGuideStepCard(
    stepNumber: String,
    title: String,
    description: String,
    activeAccentColor: Color,
    actionText: String? = null,
    onAction: (() -> Unit)? = null
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, Color(0xFF181E2E)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Step Number Badge
                Surface(
                    shape = RoundedCornerShape(4.5.dp),
                    color = activeAccentColor.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, activeAccentColor.copy(alpha = 0.45f))
                ) {
                    Text(
                        text = stringResource(R.string.cf_guide_step_label, stepNumber),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = activeAccentColor,
                        modifier = Modifier.padding(horizontal = 4.5.dp, vertical = 1.dp)
                    )
                }

                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Text(
                text = description,
                fontSize = 11.5.sp,
                color = TextMuted,
                lineHeight = 16.sp
            )

            if (actionText != null && onAction != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = activeAccentColor.copy(alpha = 0.08f),
                    border = BorderStroke(1.dp, activeAccentColor.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .springPress(onClick = onAction)
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = actionText,
                            color = activeAccentColor,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}
