/*
 * Mirrly TG Proxy - Diagnostic Report Screen (Task 05)
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.mirrly.tgproxy.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.ui.theme.*
import com.mirrly.tgproxy.util.DiagnosticReportGenerator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticReportScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    val reportText = remember { DiagnosticReportGenerator.generateReport(context) }
    var pendingIssueUrl by remember { mutableStateOf<String?>(null) }

    if (pendingIssueUrl != null) {
        ExternalLinkConfirmDialog(
            url = pendingIssueUrl ?: "",
            onDismiss = { pendingIssueUrl = null }
        )
    }

    fun copyReportToClipboard() {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(context.getString(R.string.diagnostic_report_title), reportText)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, context.getString(R.string.diagnostic_report_toast_copied), Toast.LENGTH_SHORT).show()
    }

    fun shareReport() {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.diagnostic_report_share_subject))
            putExtra(Intent.EXTRA_TEXT, reportText)
        }
        val chooser = Intent.createChooser(shareIntent, context.getString(R.string.diagnostic_report_title))
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    fun openGitHubIssue() {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        val title = Uri.encode(context.getString(R.string.diagnostic_report_issue_title))
        val body = Uri.encode(context.getString(R.string.diagnostic_report_issue_body, reportText))
        pendingIssueUrl = "https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/issues/new?title=$title&body=$body"
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        // 1. SCROLLABLE CONTENT LAYER
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(top = 64.dp, bottom = 48.dp)
                .adaptiveContainerWidth(600.dp)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Notice card: Zero leak guarantee
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, ActiveGreenLed.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(ActiveGreenLed.copy(alpha = 0.12f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_shield),
                            contentDescription = null,
                            tint = ActiveGreenLed,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.diagnostic_report_subtitle),
                            color = TextWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.5.sp
                        )
                        Text(
                            text = stringResource(R.string.diagnostic_report_sanitized_notice),
                            color = TextMuted,
                            fontSize = 11.5.sp,
                            lineHeight = 15.sp
                        )
                    }
                }
            }

            // Quick Actions: Copy, Share, GitHub Issue
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Copy Button
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, AmoledBorder),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { copyReportToClipboard() }
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_copy),
                            contentDescription = null,
                            tint = TextWhite,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.diagnostic_report_btn_copy),
                            color = TextWhite,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                    }
                }

                // Share Button
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, AmoledBorder),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { shareReport() }
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_send),
                            contentDescription = null,
                            tint = TextWhite,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.diagnostic_report_btn_share),
                            color = TextWhite,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            // GitHub Issue Button
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, ActiveGreenLed.copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { openGitHubIssue() }
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 11.dp, horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_github),
                        contentDescription = null,
                        tint = ActiveGreenLed,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.diagnostic_report_btn_issue),
                        color = ActiveGreenLed,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.5.sp
                    )
                }
            }

            // Monospaced Markdown Report Card
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, AmoledBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = reportText,
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextWhite.copy(alpha = 0.88f),
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(14.dp)
                )
            }
        }

        // 2. FIXED TOP BAR
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(56.dp),
            color = Color(0xFF000000).copy(alpha = 0.85f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onBack()
                    }
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_arrow_left),
                        contentDescription = null,
                        tint = TextWhite,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Text(
                    text = stringResource(R.string.diagnostic_report_title),
                    color = TextWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )

                IconButton(
                    onClick = { copyReportToClipboard() }
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_copy),
                        contentDescription = null,
                        tint = TextWhite,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
