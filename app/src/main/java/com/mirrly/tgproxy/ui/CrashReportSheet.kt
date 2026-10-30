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
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.service.crash.CrashReport
import com.mirrly.tgproxy.service.crash.CrashReportManager
import com.mirrly.tgproxy.ui.theme.*
import java.io.File

private val EmergencyCarmine = Color(0xFFEF4444)
private val CarmineBorder = Color(0x66EF4444)
private val CarmineBackground = Color(0x14EF4444)

/**
 * Universal MirrlyUI Crash Report Modal Bottom Sheet.
 * Adheres strictly to Zero-Popup architecture dogma and Outline-Only design:
 * - Rendered inside MirrlyModalBottomSheet with AMOLED alpha and backdrop blur.
 * - Monospace styling for exception signatures and stack traces.
 * - Ready-to-use GitHub Issue integration with pre-filled Markdown report.
 * - Local report file export and sharing via Android FileProvider.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrashReportSheet(
    report: CrashReport,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isStackTraceExpanded by remember { mutableStateOf(false) }
    var isLogsExpanded by remember { mutableStateOf(false) }

    val reportFileName = remember(report.reportFilePath, report.timestampMs) {
        report.reportFilePath?.let { File(it).name } ?: "crash_report_${report.timestampMs}.log"
    }

    MirrlyModalBottomSheet(
        onDismissRequest = onDismiss,
        maxTabletWidth = 520.dp,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Top Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    // Badge: Outline-Only
                    Box(
                        modifier = Modifier
                            .border(BorderStroke(1.dp, CarmineBorder), RoundedCornerShape(12.dp))
                            .background(CarmineBackground, RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.crash_sheet_badge),
                            color = EmergencyCarmine,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = stringResource(R.string.crash_sheet_title),
                        color = TextWhite,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = stringResource(R.string.crash_sheet_subtitle),
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
                            onDismiss()
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

            // 2. Exception Overview Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = CarmineBackground,
                border = BorderStroke(1.dp, CarmineBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .border(BorderStroke(1.dp, CarmineBorder), RoundedCornerShape(10.dp))
                            .clip(RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_bug),
                            contentDescription = null,
                            tint = EmergencyCarmine,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = report.exceptionClass,
                            color = EmergencyCarmine,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        if (report.exceptionMessage.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = report.exceptionMessage,
                                color = TextWhite,
                                fontSize = 12.5.sp,
                                lineHeight = 17.sp
                            )
                        }

                        if (!report.causeMessage.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.crash_report_cause_prefix, report.causeMessage),
                                color = TextMuted,
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 15.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = stringResource(R.string.crash_report_thread_prefix, report.threadName, report.threadId),
                            color = TextMuted,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // 3. Generated Report File Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, AmoledBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .border(BorderStroke(1.dp, AmoledBorder), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_export),
                            contentDescription = null,
                            tint = TextWhite,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.crash_file_generated_title),
                            color = TextWhite,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = reportFileName,
                            color = TextMuted,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Box(
                        modifier = Modifier
                            .border(BorderStroke(1.dp, ActiveGreenLed.copy(alpha = 0.5f)), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.crash_file_ready_badge),
                            color = ActiveGreenLed,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }

            // 4. Device & System Specifications
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, AmoledBorder)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.crash_device_title),
                        color = TextWhite,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Устройство", color = TextMuted, fontSize = 11.5.sp)
                        Text(
                            text = "${report.deviceManufacturer} ${report.deviceModel}".trim(),
                            color = TextWhite,
                            fontSize = 11.5.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Android OS", color = TextMuted, fontSize = 11.5.sp)
                        Text(
                            text = "${report.androidRelease} (API ${report.androidSdk})",
                            color = TextWhite,
                            fontSize = 11.5.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Mirrly TG Proxy", color = TextMuted, fontSize = 11.5.sp)
                        Text(
                            text = "v${report.appVersionName} (${report.buildType})",
                            color = TextWhite,
                            fontSize = 11.5.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Режим прокси", color = TextMuted, fontSize = 11.5.sp)
                        Text(
                            text = "${report.proxyMode} • ${if (report.isProxyRunning) stringResource(R.string.crash_report_status_running) else stringResource(R.string.crash_report_status_stopped)}",
                            color = if (report.isProxyRunning) ActiveGreenLed else TextMuted,
                            fontSize = 11.5.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // 5. Expandable Stack Trace Card
            MirrlyExpandableCard(
                isExpanded = isStackTraceExpanded,
                onToggle = {
                    HapticHelper.performTapClick(context)
                    isStackTraceExpanded = !isStackTraceExpanded
                },
                header = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.crash_stacktrace_title),
                                color = TextWhite,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${report.stackTrace.lines().size} строк",
                                color = TextMuted,
                                fontSize = 11.sp
                            )
                        }

                        val rotation by animateFloatAsState(targetValue = if (isStackTraceExpanded) 180f else 0f, label = "arrow")
                        Icon(
                            painter = painterResource(id = R.drawable.ic_close),
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier
                                .size(14.dp)
                                .rotate(rotation + 45f)
                        )
                    }
                }
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = AmoledSurfaceLow,
                    border = BorderStroke(1.dp, AmoledBorder)
                ) {
                    SelectionContainer {
                        Text(
                            text = report.stackTrace.trim(),
                            color = TextWhite.copy(alpha = 0.9f),
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 15.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState())
                                .padding(10.dp)
                        )
                    }
                }
            }

            // 6. Expandable Recent Logs Card
            if (report.recentLogs.isNotEmpty()) {
                MirrlyExpandableCard(
                    isExpanded = isLogsExpanded,
                    onToggle = {
                        HapticHelper.performTapClick(context)
                        isLogsExpanded = !isLogsExpanded
                    },
                    header = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.crash_recent_logs_title),
                                    color = TextWhite,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "${report.recentLogs.size} событий",
                                    color = TextMuted,
                                    fontSize = 11.sp
                                )
                            }

                            val rotation by animateFloatAsState(targetValue = if (isLogsExpanded) 180f else 0f, label = "arrow")
                            Icon(
                                painter = painterResource(id = R.drawable.ic_close),
                                contentDescription = null,
                                tint = TextMuted,
                                modifier = Modifier
                                    .size(14.dp)
                                    .rotate(rotation + 45f)
                            )
                        }
                    }
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = AmoledSurfaceLow,
                        border = BorderStroke(1.dp, AmoledBorder)
                    ) {
                        SelectionContainer {
                            Text(
                                text = report.recentLogs.joinToString("\n").trim(),
                                color = TextWhite.copy(alpha = 0.85f),
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 15.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                                    .horizontalScroll(rememberScrollState())
                                    .padding(10.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 7. Primary Action: GitHub Issue One-Click
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .springPress(onClick = {
                        HapticHelper.performTapClick(context)
                        CrashReportManager.openGitHubIssue(context, report)
                    }),
                shape = RoundedCornerShape(14.dp),
                color = CarmineBackground,
                border = BorderStroke(1.dp, EmergencyCarmine)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_github),
                        contentDescription = null,
                        tint = EmergencyCarmine,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.crash_btn_create_issue),
                        color = TextWhite,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // 8. Secondary Actions Dock: Share File & Copy
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Share file
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .springPress(onClick = {
                            HapticHelper.performTapClick(context)
                            CrashReportManager.shareCrashReportFile(context, report)
                        }),
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
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.crash_btn_share_file),
                            color = TextWhite,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // Copy report
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .springPress(onClick = {
                            HapticHelper.performTapClick(context)
                            CrashReportManager.copyCrashReport(context, report)
                        }),
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
                            painter = painterResource(id = R.drawable.ic_copy),
                            contentDescription = null,
                            tint = TextWhite,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.crash_btn_copy),
                            color = TextWhite,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // 9. Dismiss Button
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .springPress(onClick = {
                        HapticHelper.performTapClick(context)
                        onDismiss()
                    }),
                shape = RoundedCornerShape(10.dp),
                color = Color.Transparent
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.crash_btn_dismiss),
                        color = TextMuted,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
