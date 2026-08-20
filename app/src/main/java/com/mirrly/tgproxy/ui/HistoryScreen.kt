package com.mirrly.tgproxy.ui

import android.os.Build
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
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
                    .padding(horizontal = 24.dp)
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
                        modifier = Modifier.size(54.dp)
                    )
                    Text(
                        text = "История сессий пуста",
                        color = TextWhite,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Запустите прокси на Главном экране для фиксации сессий, времени работы и трафика.",
                        color = TextMuted,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .fadingEdges(topFadeHeight = 28.dp, bottomFadeHeight = 44.dp),
                contentPadding = PaddingValues(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp + 78.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
                    start = 16.dp,
                    end = 16.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(
                    items = historyList,
                    key = { it.id }
                ) { session ->
                    SessionCard(session = session)
                }
            }
        }

        // 2. PINNED AMOLED HEADER PANEL (Title + Clean Borderless Summary Stats)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.98f),
                            Color.Black.copy(alpha = 0.95f),
                            Color.Black.copy(alpha = 0.88f),
                            Color.Black.copy(alpha = 0.00f)
                        )
                    )
                )
                .padding(bottom = 12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
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
                                    tint = Color(0xFFFF5252).copy(alpha = 0.85f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )

                // Clean Borderless Dashboard Summary Metrics
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SummaryStatItem(
                        title = "СЕССИЙ",
                        value = totalSessions.toString()
                    )
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(24.dp)
                            .background(Color.White.copy(alpha = 0.08f))
                    )
                    SummaryStatItem(
                        title = "ВРЕМЯ",
                        value = formatDurationShort(totalActiveTimeSec)
                    )
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(24.dp)
                            .background(Color.White.copy(alpha = 0.08f))
                    )
                    SummaryStatItem(
                        title = "ТРАФИК",
                        value = humanBytes(totalBytesTransferred)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                HorizontalDivider(
                    color = Color.White.copy(alpha = 0.06f),
                    thickness = 1.dp,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
    }

    // Custom Glass Confirmation dialog for clearing history
    if (showClearDialog) {
        Dialog(
            onDismissRequest = { showClearDialog = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = true
            )
        ) {
            val view = LocalView.current
            LaunchedEffect(Unit) {
                try {
                    val window = (view.parent as? DialogWindowProvider)?.window
                    if (window != null) {
                        WindowCompat.setDecorFitsSystemWindows(window, false)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                            window.attributes = window.attributes.apply {
                                blurBehindRadius = 50
                            }
                        }
                    }
                } catch (_: Exception) {}
            }


            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.15f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { showClearDialog = false }
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = Color.Black.copy(alpha = 0.85f),
                    border = BorderStroke(1.dp, Color(0xFFFF5252).copy(alpha = 0.40f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = false) {}
                ) {
                    Column(
                        modifier = Modifier.padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = Color(0xFFFF5252).copy(alpha = 0.15f),
                                border = BorderStroke(1.dp, Color(0xFFFF5252).copy(alpha = 0.4f)),
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_trash),
                                        contentDescription = null,
                                        tint = Color(0xFFFF5252),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Text(
                                text = "Очистить историю?",
                                color = TextWhite,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                        }

                        Text(
                            text = "Вся сохранённая история подключений и статистика передаваемого трафика будут безвозвратно удалены.",
                            color = TextWhite.copy(alpha = 0.80f),
                            fontSize = 13.5.sp,
                            lineHeight = 19.sp
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedButton(
                                onClick = { showClearDialog = false },
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                            ) {
                                Text("Отмена", color = TextWhite, fontSize = 13.5.sp)
                            }

                            Button(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    SessionHistoryManager.clearHistory()
                                    showClearDialog = false
                                },
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFFF5252).copy(alpha = 0.25f),
                                    contentColor = Color(0xFFFF5252)
                                ),
                                border = BorderStroke(1.dp, Color(0xFFFF5252).copy(alpha = 0.60f)),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                            ) {
                                Text("Удалить", fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryStatItem(
    title: String,
    value: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = value,
            color = TextWhite,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            letterSpacing = (-0.2).sp
        )
        Text(
            text = title,
            color = TextMuted,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp
        )
    }
}

@Composable
private fun SessionCard(session: SessionRecord) {
    val isSocks5 = session.proxyMode.equals("SOCKS5", ignoreCase = true)
    val protoAccent = if (isSocks5) Socks5Accent else MtprotoAccent
    val protoName = if (isSocks5) "SOCKS5" else "MTProto"

    val isActive = session.status == SessionStatus.ACTIVE
    val borderColor = if (isActive) protoAccent.copy(alpha = 0.40f) else AmoledBorder

    val dateFormat = remember { SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()) }
    val startTimeStr = remember(session.startTimeMs) {
        if (session.startTimeMs > 0L) dateFormat.format(Date(session.startTimeMs)) else "—"
    }
    val endTimeStr = remember(session.endTimeMs, isActive) {
        if (isActive) "сейчас" else if (session.endTimeMs > 0L) dateFormat.format(Date(session.endTimeMs)) else "—"
    }

    // Glowing dot animation for active session
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alphaAnim by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Top Row: Protocol Badge + Preset Tag & Status Indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Protocol Pill Badge
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = protoAccent.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, protoAccent.copy(alpha = 0.28f))
                    ) {
                        Text(
                            text = protoName,
                            color = protoAccent,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }

                    // Preset Name
                    Text(
                        text = "•   ${session.presetName}",
                        color = TextMuted,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Status Label / Indicator
                when (session.status) {
                    SessionStatus.ACTIVE -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(protoAccent.copy(alpha = alphaAnim))
                            )
                            Text(
                                text = "АКТИВНА",
                                color = protoAccent,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.5.sp,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                    SessionStatus.COMPLETED -> {
                        Text(
                            text = "ЗАВЕРШЕНА",
                            color = TextMuted,
                            fontWeight = FontWeight.Medium,
                            fontSize = 10.5.sp,
                            letterSpacing = 0.4.sp
                        )
                    }
                    SessionStatus.INTERRUPTED -> {
                        Text(
                            text = "ПРЕРВАНА",
                            color = Color(0xFFE57373),
                            fontWeight = FontWeight.Medium,
                            fontSize = 10.5.sp,
                            letterSpacing = 0.4.sp
                        )
                    }
                }
            }

            // Middle Row: Start/End Time & Total Duration
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$startTimeStr — $endTimeStr",
                    color = TextWhite.copy(alpha = 0.90f),
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = formatDurationFull(session.durationSeconds),
                    color = if (isActive) TextWhite else TextWhite.copy(alpha = 0.70f),
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = 12.sp
                )
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.05f), thickness = 1.dp)

            // Bottom Metrics Row: Traffic (Left) and Speed/Sockets (Right)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: Download & Upload traffic details
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        text = "ТРАФИК",
                        color = TextMuted,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.6.sp
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = humanBytes(session.totalBytes),
                            color = TextWhite,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "(↓${humanBytes(session.bytesReceived)} ↑${humanBytes(session.bytesSent)})",
                            color = TextMuted,
                            fontSize = 10.5.sp
                        )
                    }
                }

                // Right: Peak Speed & Max Sockets
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    Text(
                        text = "ПИК / СОКЕТЫ",
                        color = TextMuted,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.6.sp
                    )
                    Text(
                        text = "${humanBytes(session.peakSpeedBps)}/с  •  ${session.maxConnections} сок.",
                        color = TextWhite.copy(alpha = 0.90f),
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
