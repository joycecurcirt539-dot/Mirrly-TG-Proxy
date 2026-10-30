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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.core.LogEntry
import com.mirrly.tgproxy.ui.theme.*
import com.mirrly.tgproxy.util.LogExportHelper

/**
 * Section-specific Log Export Bottom Sheet.
 * Allows instant copying of filtered section logs in Markdown format or exporting as a .md file.
 * Conforms with MirrlyUI Zero-Popup & Outline-Only architecture.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabLogExportBottomSheet(
    levelName: String,
    levelColor: Color,
    logs: List<LogEntry>,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val markdownPreview = remember(logs, levelName) {
        LogExportHelper.formatLogEntriesMarkdown(logs, levelName)
    }

    MirrlyModalBottomSheet(
        onDismissRequest = onDismissRequest,
        maxTabletWidth = 520.dp,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .border(BorderStroke(1.dp, levelColor.copy(alpha = 0.5f)), RoundedCornerShape(12.dp))
                            .background(levelColor.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = levelName.uppercase(),
                            color = levelColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = stringResource(R.string.logs_tab_sheet_title_format, levelName),
                        color = TextWhite,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = stringResource(R.string.logs_tab_sheet_subtitle_format, logs.size),
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .border(BorderStroke(1.dp, AmoledBorder), CircleShape)
                        .clip(CircleShape)
                        .springPress(onClick = {
                            HapticHelper.performTapClick(context)
                            onDismissRequest()
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

            // Visual Monospace Markdown Preview Container
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 250.dp),
                shape = RoundedCornerShape(12.dp),
                color = AmoledSurfaceLow,
                border = BorderStroke(1.dp, AmoledBorder)
            ) {
                SelectionContainer {
                    Text(
                        text = if (logs.isEmpty()) stringResource(R.string.logs_no_events_to_copy) else markdownPreview.trim(),
                        color = TextWhite.copy(alpha = 0.88f),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 15.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                            .padding(12.dp)
                    )
                }
            }

            // Action Dock: Copy Markdown & Export .md file
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Button 1: Copy Markdown
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .springPress(
                            enabled = logs.isNotEmpty(),
                            onClick = {
                                HapticHelper.performTapClick(context)
                                LogExportHelper.copyMarkdownToClipboard(context, logs, levelName)
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.logs_tab_toast_copied_format, levelName),
                                    Toast.LENGTH_SHORT
                                ).show()
                                onDismissRequest()
                            }
                        ),
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, if (logs.isNotEmpty()) levelColor.copy(alpha = 0.7f) else AmoledBorder)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_copy),
                            contentDescription = null,
                            tint = if (logs.isNotEmpty()) levelColor else TextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.logs_tab_sheet_btn_copy_markdown),
                            color = if (logs.isNotEmpty()) TextWhite else TextMuted,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Button 2: Export .md file
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .springPress(
                            enabled = logs.isNotEmpty(),
                            onClick = {
                                HapticHelper.performTapClick(context)
                                val safePrefix = "mirrly_${levelName.lowercase().replace(" ", "_")}"
                                val file = LogExportHelper.createMarkdownLogFile(context, logs, safePrefix, levelName)
                                if (file != null) {
                                    Toast.makeText(context, context.getString(R.string.logs_export_file_created), Toast.LENGTH_SHORT).show()
                                    LogExportHelper.shareLogFile(context, file)
                                    onDismissRequest()
                                } else {
                                    Toast.makeText(context, context.getString(R.string.logs_export_failed), Toast.LENGTH_SHORT).show()
                                }
                            }
                        ),
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, AmoledBorder)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_export),
                            contentDescription = null,
                            tint = TextWhite,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.logs_tab_sheet_btn_export_md_file),
                            color = TextWhite,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}
