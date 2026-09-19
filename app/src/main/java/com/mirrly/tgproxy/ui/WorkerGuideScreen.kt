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
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.service.cloudflare.CloudflareWorkerPayload
import com.mirrly.tgproxy.ui.theme.*

private enum class GuideCategoryTab(@StringRes val titleRes: Int) {
    PC(R.string.cf_guide_tab_pc),
    PHONE(R.string.cf_guide_tab_phone),
    SCRIPT(R.string.cf_guide_tab_script),
    FAQ(R.string.cf_guide_tab_faq)
}

private data class GuideStepData(
    val stepNumber: String,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    @StringRes val actionTextRes: Int? = null,
    val isCopyAction: Boolean = false,
    val isDashAction: Boolean = false
)

private data class GuideFaqData(
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkerGuideScreen(
    onBack: () -> Unit,
    onOpenWorkerManager: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val protoColors = LocalProtocolColors.current
    val activeProtoColor = protoColors.primary

    var selectedTab by remember { mutableStateOf(GuideCategoryTab.PC) }
    var showDeployConfirmDialog by remember { mutableStateOf(false) }

    val pcSteps = remember {
        listOf(
            GuideStepData(
                stepNumber = "1",
                titleRes = R.string.cf_guide_step_pc_1_title,
                descriptionRes = R.string.cf_guide_step_pc_1_desc,
                actionTextRes = R.string.cf_guide_step_pc_1_action,
                isDashAction = true
            ),
            GuideStepData(
                stepNumber = "2",
                titleRes = R.string.cf_guide_step_pc_2_title,
                descriptionRes = R.string.cf_guide_step_pc_2_desc
            ),
            GuideStepData(
                stepNumber = "3",
                titleRes = R.string.cf_guide_step_pc_3_title,
                descriptionRes = R.string.cf_guide_step_pc_3_desc
            ),
            GuideStepData(
                stepNumber = "4",
                titleRes = R.string.cf_guide_step_pc_4_title,
                descriptionRes = R.string.cf_guide_step_pc_4_desc,
                actionTextRes = R.string.cf_guide_step_pc_4_action,
                isCopyAction = true
            ),
            GuideStepData(
                stepNumber = "5",
                titleRes = R.string.cf_guide_step_pc_5_title,
                descriptionRes = R.string.cf_guide_step_pc_5_desc
            ),
            GuideStepData(
                stepNumber = "6",
                titleRes = R.string.cf_guide_step_pc_6_title,
                descriptionRes = R.string.cf_guide_step_pc_6_desc
            )
        )
    }

    val phoneSteps = remember {
        listOf(
            GuideStepData(
                stepNumber = "1",
                titleRes = R.string.cf_guide_step_ph_1_title,
                descriptionRes = R.string.cf_guide_step_ph_1_desc,
                actionTextRes = R.string.cf_guide_step_ph_1_action,
                isDashAction = true
            ),
            GuideStepData(
                stepNumber = "2",
                titleRes = R.string.cf_guide_step_ph_2_title,
                descriptionRes = R.string.cf_guide_step_ph_2_desc
            ),
            GuideStepData(
                stepNumber = "3",
                titleRes = R.string.cf_guide_step_ph_3_title,
                descriptionRes = R.string.cf_guide_step_ph_3_desc
            ),
            GuideStepData(
                stepNumber = "4",
                titleRes = R.string.cf_guide_step_ph_4_title,
                descriptionRes = R.string.cf_guide_step_ph_4_desc,
                actionTextRes = R.string.cf_guide_step_ph_4_action,
                isCopyAction = true
            ),
            GuideStepData(
                stepNumber = "5",
                titleRes = R.string.cf_guide_step_ph_5_title,
                descriptionRes = R.string.cf_guide_step_ph_5_desc
            )
        )
    }

    val faqItems = remember {
        listOf(
            GuideFaqData(
                titleRes = R.string.cf_guide_faq_1_title,
                descriptionRes = R.string.cf_guide_faq_1_desc
            ),
            GuideFaqData(
                titleRes = R.string.cf_guide_faq_2_title,
                descriptionRes = R.string.cf_guide_faq_2_desc
            ),
            GuideFaqData(
                titleRes = R.string.cf_guide_faq_3_title,
                descriptionRes = R.string.cf_guide_faq_3_desc
            ),
            GuideFaqData(
                titleRes = R.string.cf_guide_faq_4_title,
                descriptionRes = R.string.cf_guide_faq_4_desc
            ),
            GuideFaqData(
                titleRes = R.string.cf_guide_faq_5_title,
                descriptionRes = R.string.cf_guide_faq_5_desc
            ),
            GuideFaqData(
                titleRes = R.string.cf_guide_faq_6_title,
                descriptionRes = R.string.cf_guide_faq_6_desc
            )
        )
    }

    fun copyWorkerScript() {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        val script = CloudflareWorkerPayload.getWorkerScript(context)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText("Worker Script", script)
        clipboard?.setPrimaryClip(clip)
        Toast.makeText(context, context.getString(R.string.cf_guide_toast_script_copied), Toast.LENGTH_SHORT).show()
    }

    fun copyDeployCommand() {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        val cmd = "curl -fsSL https://raw.githubusercontent.com/R1Xern/Mirrly-TG-Proxy/main/tools/deploy-worker/deploy.cmd -o deploy.cmd && deploy.cmd"
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText("Cloudflare Deploy Command", cmd)
        clipboard?.setPrimaryClip(clip)
        Toast.makeText(context, context.getString(R.string.cf_guide_toast_deploy_copied), Toast.LENGTH_SHORT).show()
    }

    fun openDashboard() {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://dash.cloudflare.com/?to=/:account/workers-and-pages/create/worker"))
            context.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(context, "Не удалось открыть браузер", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.wm_sub_guide),
                            color = TextWhite,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Cloudflare Workers",
                            color = TextMuted,
                            fontSize = 12.sp
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_arrow_left),
                            contentDescription = "Back",
                            tint = TextWhite,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                actions = {
                    if (onOpenWorkerManager != null) {
                        TextButton(onClick = onOpenWorkerManager) {
                            Text(
                                text = stringResource(R.string.wm_tab_deploy),
                                color = activeProtoColor,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
            )
        },
        containerColor = Color.Black
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Category Tabs Row (Compact Pill Switcher)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                GuideCategoryTab.values().forEach { tab ->
                    val isSelected = selectedTab == tab
                    val borderColor by animateColorAsState(
                        targetValue = if (isSelected) activeProtoColor.copy(alpha = 0.85f) else Color(0xFF181E2E),
                        animationSpec = tween(180),
                        label = "tabBorder"
                    )
                    val titleColor by animateColorAsState(
                        targetValue = if (isSelected) activeProtoColor else TextWhite.copy(alpha = 0.75f),
                        animationSpec = tween(180),
                        label = "tabTitle"
                    )

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) activeProtoColor.copy(alpha = 0.08f) else Color.Transparent,
                        border = BorderStroke(1.dp, borderColor),
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                selectedTab = tab
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(tab.titleRes),
                                color = titleColor,
                                fontSize = 11.5.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // Guide Content
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (selectedTab) {
                    GuideCategoryTab.PC -> {
                        // Quick 1-click deploy banner
                        item(key = "pc_banner") {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = activeProtoColor.copy(alpha = 0.06f),
                                border = BorderStroke(1.dp, activeProtoColor.copy(alpha = 0.35f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
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
                                        Text(
                                            text = stringResource(R.string.cf_guide_autodeploy_title),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.5.sp,
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
                                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = { copyDeployCommand() },
                                            modifier = Modifier.weight(1f).height(36.dp),
                                            shape = RoundedCornerShape(8.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = activeProtoColor)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Icon(
                                                    painter = painterResource(id = R.drawable.ic_copy),
                                                    contentDescription = null,
                                                    tint = Color.Black,
                                                    modifier = Modifier.size(13.dp)
                                                )
                                                Text(
                                                    text = stringResource(R.string.cf_guide_btn_copy_cmd),
                                                    fontSize = 11.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.Black
                                                )
                                            }
                                        }

                                        OutlinedButton(
                                            onClick = { showDeployConfirmDialog = true },
                                            modifier = Modifier.weight(1f).height(36.dp),
                                            shape = RoundedCornerShape(8.dp),
                                            border = BorderStroke(1.dp, Color(0xFF181E2E))
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Icon(
                                                    painter = painterResource(id = R.drawable.ic_github),
                                                    contentDescription = null,
                                                    tint = TextMuted,
                                                    modifier = Modifier.size(13.dp)
                                                )
                                                Text(
                                                    text = stringResource(R.string.cf_guide_btn_deploy_bat),
                                                    fontSize = 11.5.sp,
                                                    color = TextMuted
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        itemsIndexed(pcSteps) { _, step ->
                            StepItemCard(step = step, activeColor = activeProtoColor, onCopy = { copyWorkerScript() }, onDash = { openDashboard() })
                        }
                    }

                    GuideCategoryTab.PHONE -> {
                        itemsIndexed(phoneSteps) { _, step ->
                            StepItemCard(step = step, activeColor = activeProtoColor, onCopy = { copyWorkerScript() }, onDash = { openDashboard() })
                        }
                    }

                    GuideCategoryTab.SCRIPT -> {
                        item(key = "script_viewer") {
                            val script = remember { CloudflareWorkerPayload.getWorkerScript(context) }

                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "worker.js (ESM Module)",
                                        color = TextWhite,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )

                                    Button(
                                        onClick = { copyWorkerScript() },
                                        modifier = Modifier.height(34.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = activeProtoColor)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                                        ) {
                                            Icon(
                                                painter = painterResource(id = R.drawable.ic_copy),
                                                contentDescription = null,
                                                tint = Color.Black,
                                                modifier = Modifier.size(13.dp)
                                            )
                                            Text(
                                                text = stringResource(R.string.cf_guide_step_ph_4_action),
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.Black
                                            )
                                        }
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color.Transparent,
                                    border = BorderStroke(1.dp, Color(0xFF181E2E)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    SelectionContainer {
                                        Text(
                                            text = script,
                                            color = TextWhite.copy(alpha = 0.85f),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.5.sp,
                                            lineHeight = 15.sp,
                                            modifier = Modifier.padding(12.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    GuideCategoryTab.FAQ -> {
                        itemsIndexed(faqItems) { _, faq ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color.Transparent,
                                border = BorderStroke(1.dp, Color(0xFF181E2E)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = stringResource(faq.titleRes),
                                        color = TextWhite,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = stringResource(faq.descriptionRes),
                                        color = TextMuted,
                                        fontSize = 11.5.sp,
                                        lineHeight = 16.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDeployConfirmDialog) {
        ExternalLinkConfirmDialog(
            url = "https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/tree/main/tools/deploy-worker",
            title = stringResource(R.string.cf_guide_link_autodeploy_title),
            description = stringResource(R.string.cf_guide_link_autodeploy_desc),
            onDismiss = { showDeployConfirmDialog = false }
        )
    }
}

@Composable
private fun StepItemCard(
    step: GuideStepData,
    activeColor: Color,
    onCopy: () -> Unit,
    onDash: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, Color(0xFF181E2E)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(activeColor.copy(alpha = 0.15f))
                        .border(1.dp, activeColor.copy(alpha = 0.4f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = step.stepNumber,
                        color = activeColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = stringResource(step.titleRes),
                    color = TextWhite,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
            }

            Text(
                text = stringResource(step.descriptionRes),
                color = TextMuted,
                fontSize = 11.5.sp,
                lineHeight = 16.sp
            )

            if (step.actionTextRes != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = {
                            if (step.isCopyAction) onCopy() else if (step.isDashAction) onDash()
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = stringResource(step.actionTextRes),
                            color = activeColor,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}
