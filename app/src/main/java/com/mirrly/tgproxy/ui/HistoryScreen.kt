package com.mirrly.tgproxy.ui

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.service.SessionHistoryManager
import com.mirrly.tgproxy.service.SessionRecord
import com.mirrly.tgproxy.service.SessionStatus
import com.mirrly.tgproxy.service.humanBytes
import com.mirrly.tgproxy.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val historyList by SessionHistoryManager.historyFlow.collectAsState()

    var showClearDialog by remember { mutableStateOf(false) }

    // Summary metrics calculation
    val totalSessions = historyList.size
    val totalActiveTimeSec = remember(historyList) {
        historyList.sumOf { it.durationSeconds }
    }
    val totalBytesTransferred = remember(historyList) {
        historyList.sumOf { it.totalBytes }
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // 1. SCROLLABLE SESSION LIST LAYER
        if (historyList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 40.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_history),
                        contentDescription = null,
                        tint = TextMuted.copy(alpha = 0.5f),
                        modifier = Modifier.size(56.dp)
                    )
                    Text(
                        text = "История сессий пуста",
                        color = TextWhite,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Запустите прокси, чтобы увидеть статистику подключений",
                        color = TextMuted,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Normal
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .fadingEdges(topFadeHeight = 28.dp, bottomFadeHeight = 44.dp),
                contentPadding = PaddingValues(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp + 110.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 20.dp,
                    start = 16.dp,
                    end = 16.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(
                    items = historyList,
                    key = { it.id }
                ) { session ->
                    SessionCard(session = session)
                }
            }
        }

        // 2. PINNED FROSTED GLASS HEADER PANEL (Title + Summary stats)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.99f),
                            Color.Black.copy(alpha = 0.98f),
                            Color.Black.copy(alpha = 0.95f),
                            Color.Black.copy(alpha = 0.85f),
                            Color.Black.copy(alpha = 0.00f)
                        )
                    )
                )
                .padding(bottom = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TopAppBar(
                    title = {
                        Text(
                            text = "История сессий",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = TextWhite,
                            maxLines = 1,
                            softWrap = false,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onBack()
                        }) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_arrow_left),
                                contentDescription = "Назад",
                                tint = TextWhite,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    },
                    actions = {
                        if (historyList.isNotEmpty()) {
                            IconButton(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                showClearDialog = true
                            }) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_trash),
                                    contentDescription = "Очистить историю",
                                    tint = TextWhite,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )

                // Summary Stats Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF141416).copy(alpha = 0.85f))
                        .border(1.dp, Color(0xFF26262B), RoundedCornerShape(14.dp))
                        .padding(vertical = 10.dp, horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SummaryStatItem(
                        title = "Сессий",
                        value = totalSessions.toString()
                    )
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(28.dp)
                            .background(Color(0xFF26262B))
                    )
                    SummaryStatItem(
                        title = "Время",
                        value = formatDurationShort(totalActiveTimeSec)
                    )
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(28.dp)
                            .background(Color(0xFF26262B))
                    )
                    SummaryStatItem(
                        title = "Трафик",
                        value = humanBytes(totalBytesTransferred)
                    )
                }
            }
        }
    }

    // Confirmation dialog for clearing history
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = {
                Text(
                    text = "Очистить историю?",
                    color = TextWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Text(
                    text = "Вся записанная история сохраненных сессий и передача трафика будут удалены.",
                    color = TextMuted,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        SessionHistoryManager.clearHistory()
                        showClearDialog = false
                    }
                ) {
                    Text("Удалить", color = Color(0xFFFF5252), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearDialog = false }
                ) {
                    Text("Отмена", color = TextWhite)
                }
            },
            containerColor = Color(0xFF1C1C1E),
            shape = RoundedCornerShape(18.dp)
        )
    }
}

@Composable
private fun SummaryStatItem(
    title: String,
    value: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            color = TextWhite,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )
        Text(
            text = title,
            color = TextMuted,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun SessionCard(session: SessionRecord) {
    val isActive = session.status == SessionStatus.ACTIVE
    val cardBg = if (isActive) Color(0xFF121F17) else Color(0xFF141416)
    val borderColor = if (isActive) ActiveGreenLed.copy(alpha = 0.5f) else Color(0xFF26262B)

    val dateFormat = remember { SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()) }
    val startTimeStr = remember(session.startTimeMs) { dateFormat.format(Date(session.startTimeMs)) }
    val endTimeStr = remember(session.endTimeMs, isActive) {
        if (isActive) "сейчас" else dateFormat.format(Date(session.endTimeMs))
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(cardBg)
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header Row: Status badge + Start/End Date
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
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                when (session.status) {
                                    SessionStatus.ACTIVE -> ActiveGreenLed
                                    SessionStatus.COMPLETED -> Color(0xFF00E676)
                                    SessionStatus.INTERRUPTED -> Color(0xFFFFB74D)
                                }
                            )
                    )
                    Text(
                        text = when (session.status) {
                            SessionStatus.ACTIVE -> "АКТИВНАЯ СЕССИЯ"
                            SessionStatus.COMPLETED -> "СЕССИЯ ЗАВЕРШЕНА"
                            SessionStatus.INTERRUPTED -> "ПРЕРВАНА"
                        },
                        color = when (session.status) {
                            SessionStatus.ACTIVE -> ActiveGreenLed
                            SessionStatus.COMPLETED -> TextWhite
                            SessionStatus.INTERRUPTED -> Color(0xFFFFB74D)
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        letterSpacing = 0.6.sp
                    )
                }

                // Preset Tag
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF222226))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = session.presetName,
                        color = TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Time & Duration Info
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$startTimeStr — $endTimeStr",
                    color = TextMuted,
                    fontSize = 12.sp
                )
                Text(
                    text = "Длительность: ${formatDurationFull(session.durationSeconds)}",
                    color = TextWhite,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp
                )
            }

            HorizontalDivider(color = Color(0xFF222226), thickness = 0.8.dp)

            // Metrics Grid (Traffic, Peak Speed, Max Conns)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Трафик (↓ / ↑)",
                        color = TextMuted,
                        fontSize = 10.sp
                    )
                    Text(
                        text = "${humanBytes(session.totalBytes)} (↓ ${humanBytes(session.bytesReceived)}  ↑ ${humanBytes(session.bytesSent)})",
                        color = TextWhite,
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.sp
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Пик / Сокетов",
                        color = TextMuted,
                        fontSize = 10.sp
                    )
                    Text(
                        text = "${humanBytes(session.peakSpeedBps)}/с | ${session.maxConnections}",
                        color = TextWhite,
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

private fun formatDurationShort(seconds: Long): String {
    if (seconds < 60) return "${seconds}с"
    val minutes = seconds / 60
    if (minutes < 60) return "${minutes}м"
    val hours = minutes / 60
    val remMin = minutes % 60
    return if (remMin > 0) "${hours}ч ${remMin}м" else "${hours}ч"
}

private fun formatDurationFull(seconds: Long): String {
    if (seconds < 60) return "$seconds сек"
    val minutes = seconds / 60
    val remainingSec = seconds % 60
    if (minutes < 60) {
        return if (remainingSec > 0) "${minutes}мин ${remainingSec}сек" else "${minutes}мин"
    }
    val hours = minutes / 60
    val remainingMin = minutes % 60
    return if (remainingMin > 0) "${hours}ч ${remainingMin}мин" else "${hours}ч"
}
