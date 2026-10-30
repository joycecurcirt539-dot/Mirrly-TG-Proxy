package com.mirrly.tgproxy.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.core.LogEntry
import com.mirrly.tgproxy.ui.theme.*
import com.mirrly.tgproxy.util.LogExportHelper

private val SheetCardShape = RoundedCornerShape(14.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogExportBottomSheet(
    onDismissRequest: () -> Unit,
    allLogs: List<LogEntry>,
    displayedLogs: List<LogEntry>,
    isFiltered: Boolean,
    onStartSelectionMode: () -> Unit
) {
    val context = LocalContext.current
    val activeProtoColor = ActiveGreenLed

    val onExportLogs: (List<LogEntry>, String) -> Unit = remember(context) {
        { logsToExport, prefix ->
            if (logsToExport.isEmpty()) {
                Toast.makeText(context, context.getString(R.string.logs_no_events_to_copy), Toast.LENGTH_SHORT).show()
            } else {
                val file = LogExportHelper.createLogFile(context, logsToExport, prefix)
                if (file != null) {
                    Toast.makeText(context, context.getString(R.string.logs_export_file_created), Toast.LENGTH_SHORT).show()
                    LogExportHelper.shareLogFile(context, file)
                    onDismissRequest()
                } else {
                    Toast.makeText(context, context.getString(R.string.logs_export_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val onCopyLogsText: (List<LogEntry>) -> Unit = remember(context) {
        { logsToCopy ->
            if (logsToCopy.isEmpty()) {
                Toast.makeText(context, context.getString(R.string.logs_no_events_to_copy), Toast.LENGTH_SHORT).show()
            } else {
                val copiedCount = LogExportHelper.copyToClipboard(context, logsToCopy)
                Toast.makeText(
                    context,
                    context.getString(R.string.logs_copied_count_format, copiedCount),
                    Toast.LENGTH_SHORT
                ).show()
                onDismissRequest()
            }
        }
    }

    MirrlyModalBottomSheet(
        onDismissRequest = onDismissRequest
    ) {
        MirrlyBottomSheetHeader(
            title = stringResource(R.string.logs_export_sheet_title),
            subtitle = stringResource(R.string.logs_export_sheet_subtitle),
            onClose = onDismissRequest
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 1. Export all logs card
            ExportActionCard(
                iconRes = R.drawable.ic_export,
                title = stringResource(R.string.logs_export_all_title),
                subtitle = "${stringResource(R.string.logs_export_all_desc)} · ${allLogs.size}",
                accentColor = activeProtoColor,
                onClick = {
                    HapticHelper.performTapClick(context)
                    onExportLogs(allLogs, "mirrly_logs_all")
                }
            )

            // 2. Export filtered logs card (if search/level filter is active)
            if (isFiltered) {
                ExportActionCard(
                    iconRes = R.drawable.ic_export,
                    title = stringResource(R.string.logs_export_filtered_title),
                    subtitle = "${stringResource(R.string.logs_export_filtered_desc)} · ${displayedLogs.size}",
                    accentColor = Color(0xFF64B5F6),
                    onClick = {
                        HapticHelper.performTapClick(context)
                        onExportLogs(displayedLogs, "mirrly_logs_filtered")
                    }
                )
            }

            // 3. Manual selection mode card
            ExportActionCard(
                iconRes = R.drawable.ic_check_circle,
                title = stringResource(R.string.logs_export_select_manual_title),
                subtitle = stringResource(R.string.logs_export_select_manual_desc),
                accentColor = TextWhite,
                onClick = {
                    HapticHelper.performTapClick(context)
                    onDismissRequest()
                    onStartSelectionMode()
                }
            )

            // 4. Copy as formatted text to clipboard
            ExportActionCard(
                iconRes = R.drawable.ic_copy,
                title = stringResource(R.string.logs_export_copy_text_title),
                subtitle = stringResource(R.string.logs_export_copy_text_desc),
                accentColor = TextMuted,
                onClick = {
                    HapticHelper.performTapClick(context)
                    onCopyLogsText(if (isFiltered) displayedLogs else allLogs)
                }
            )
        }
    }
}

@Composable
private fun ExportActionCard(
    iconRes: Int,
    title: String,
    subtitle: String,
    accentColor: Color,
    onClick: () -> Unit
) {
    Surface(
        shape = SheetCardShape,
        color = Color.White.copy(alpha = 0.02f),
        border = BorderStroke(1.dp, AmoledBorder),
        modifier = Modifier
            .fillMaxWidth()
            .springPress(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, accentColor.copy(alpha = 0.40f)),
                modifier = Modifier.size(38.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(id = iconRes),
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = title,
                    color = TextWhite,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    color = TextMuted,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Normal,
                    lineHeight = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
