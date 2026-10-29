package com.mirrly.tgproxy.ui

import android.os.Build
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.res.stringResource
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

private data class HistorySummary(
    val totalSessions: Int = 0,
    val totalActiveTimeSec: Long = 0L,
    val totalBytesTransferred: Long = 0L
)

private val sessionDateFormatter = ThreadLocal.withInitial {
    SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
}

private fun formatSessionDate(timestampMs: Long): String {
    if (timestampMs <= 0L) return "—"
    return sessionDateFormatter.get()?.format(Date(timestampMs)) ?: "—"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val historyList by SessionHistoryManager.historyFlow.collectAsState()

    var showClearDialog by remember { mutableStateOf(false) }

    // Summary metrics calculation memoized in a single pass over historyList
    val summary = remember(historyList) {
        var activeTime = 0L
        var totalBytes = 0L
        for (item in historyList) {
            activeTime += item.durationSeconds
            totalBytes += item.totalBytes
        }
        HistorySummary(
            totalSessions = historyList.size,
            totalActiveTimeSec = activeTime,
            totalBytesTransferred = totalBytes
        )
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        // 1. SCROLLABLE SESSION LIST LAYER
        if (historyList.isEmpty()) {
            Box(
                modifier = Modifier
                    .adaptiveContainerWidth(600.dp)
                    .fillMaxHeight()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 40.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.staggeredEntrance(index = 1)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_history),
                        contentDescription = null,
                        tint = TextMuted.copy(alpha = 0.5f),
                        modifier = Modifier.size(54.dp)
                    )
                    Text(
                        text = stringResource(R.string.history_empty_title),
                        color = TextWhite,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.history_empty_desc),
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
                    .adaptiveContainerWidth(600.dp)
                    .fillMaxHeight()
                    .fadingEdges(topFadeHeight = 28.dp, bottomFadeHeight = 44.dp)
                    .adaptiveContentPadding(),
                contentPadding = PaddingValues(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp + 78.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(
                    items = historyList,
                    key = { _, it -> it.id }
                ) { index, session ->
                    SessionCard(
                        session = session,
                        modifier = Modifier.staggeredEntrance(index = (index + 2).coerceAtMost(8))
                    )
                }
            }
        }

        // 2. PINNED AMOLED HEADER PANEL (Title + Clean Borderless Summary Stats)
        Box(
            modifier = Modifier
                .adaptiveContainerWidth(600.dp)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.98f),
                            Color.Black.copy(alpha = 0.95f),
                            Color.Black.copy(alpha = 0.88f),
                            Color.Black.copy(alpha = 0.0f)
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
                    modifier = Modifier.staggeredEntrance(index = 0),
                    title = {
                        Text(
                            text = stringResource(R.string.history_title),
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
                                contentDescription = stringResource(R.string.action_back),
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
                                    contentDescription = stringResource(R.string.history_action_clear),
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
                        .staggeredEntrance(index = 1)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SummaryStatItem(
                        title = stringResource(R.string.history_stat_sessions),
                        value = summary.totalSessions.toString()
                    )
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(24.dp)
                            .background(Color.White.copy(alpha = 0.08f))
                    )
                    SummaryStatItem(
                        title = stringResource(R.string.history_stat_time),
                        value = formatDurationShort(summary.totalActiveTimeSec)
                    )
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(24.dp)
                            .background(Color.White.copy(alpha = 0.08f))
                    )
                    SummaryStatItem(
                        title = stringResource(R.string.history_stat_traffic),
                        value = humanBytes(summary.totalBytesTransferred)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                HorizontalDivider(
                    color = Color.White.copy(alpha = 0.06f),
                    thickness = 1.dp,
                    modifier = Modifier
                        .staggeredEntrance(index = 2)
                        .padding(horizontal = 4.dp)
                )
            }
        }

        // Custom Glass Confirmation dialog for clearing history (DNS selector dialog style)
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
            DialogBackdropBox(
                onDismiss = { showClearDialog = false }
            ) {
                // Scrollable Info Content with Fading Edges into blur
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .adaptiveContainerWidth(440.dp)
                        .fadingEdges(topFadeHeight = 32.dp, bottomFadeHeight = 44.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(
                            top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 60.dp,
                            bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp
                        )
                        .padding(horizontal = 20.dp)
                        .clickable(enabled = false) {}
                ) {
                    // Category Pill
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, BloodRed.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = "ИСТОРИЯ",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = BloodRed,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                        )
                    }

                    // Title
                    Text(
                        text = stringResource(R.string.history_dialog_clear_title),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite,
                        textAlign = TextAlign.Center,
                        letterSpacing = 0.3.sp
                    )

                    // Subtitle
                    Text(
                        text = stringResource(R.string.history_dialog_clear_desc),
                        fontSize = 12.sp,
                        color = TextMuted,
                        textAlign = TextAlign.Center,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    // Warning / Details Card
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, BloodRed.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "ВНИМАНИЕ",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = BloodRed,
                                letterSpacing = 0.8.sp
                            )
                            Text(
                                text = "Все накопленные записи о сессиях, объеме переданного трафика и длительности подключений будут удалены без возможности восстановления.",
                                fontSize = 11.5.sp,
                                color = TextWhite.copy(alpha = 0.85f),
                                lineHeight = 16.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Primary Action Button: Clear History
                    Surface(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            SessionHistoryManager.clearHistory()
                            showClearDialog = false
                        },
                        shape = RoundedCornerShape(14.dp),
                        color = BloodRed.copy(alpha = 0.18f),
                        border = BorderStroke(1.dp, BloodRed.copy(alpha = 0.70f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Text(
                                text = stringResource(R.string.history_btn_delete).uppercase(),
                                color = BloodRed,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                letterSpacing = 0.4.sp
                            )
                        }
                    }

                    // Cancel Button
                    Surface(
                        onClick = { showClearDialog = false },
                        shape = RoundedCornerShape(14.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, AmoledBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Text(
                                text = stringResource(R.string.action_cancel).uppercase(),
                                color = TextMuted,
                                fontWeight = FontWeight.Normal,
                                fontSize = 13.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(32.dp))
                }

                // Top Header with Back Button (pinned at top left over blurred background)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 12.dp,
                            start = 16.dp,
                            end = 16.dp
                        )
                ) {
                    Surface(
                        onClick = { showClearDialog = false },
                        shape = RoundedCornerShape(12.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, AmoledBorder),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_arrow_left),
                                contentDescription = stringResource(R.string.action_back),
                                tint = TextWhite,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }

        // Delicate Cyber Particles floating over entire history screen interface
        CyberParticlesOverlay(
            modifier = Modifier.fillMaxSize(),
            particleCount = 42,
            alphaMultiplier = 0.70f
        )
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
private fun SessionCard(
    session: SessionRecord,
    modifier: Modifier = Modifier
) {
    val isSocks5 = session.proxyMode.equals("SOCKS5", ignoreCase = true)
    val protoAccent = if (isSocks5) Socks5Accent else MtprotoAccent
    val protoName = if (isSocks5) "SOCKS5" else "MTProto"

    val isActive = session.status == SessionStatus.ACTIVE
    val borderColor = if (isActive) protoAccent.copy(alpha = 0.40f) else Color.White.copy(alpha = 0.08f)

    val startTimeStr = remember(session.startTimeMs) {
        formatSessionDate(session.startTimeMs)
    }
    val timeNow = stringResource(R.string.history_time_now)
    val endTimeStr = remember(session.endTimeMs, isActive, timeNow) {
        if (isActive) timeNow else formatSessionDate(session.endTimeMs)
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
        shape = RoundedCornerShape(12.dp),
        color = Color.White.copy(alpha = 0.02f),
        border = BorderStroke(0.6.dp, borderColor),
        modifier = modifier
            .fillMaxWidth()
            .springPress()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Row 1: Protocol pill badge + Preset + Status | Time range + duration
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Protocol Pill Badge
                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = protoAccent.copy(alpha = 0.12f),
                        border = BorderStroke(0.75.dp, protoAccent.copy(alpha = 0.28f))
                    ) {
                        Text(
                            text = protoName,
                            color = protoAccent,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.4.sp,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.5.dp)
                        )
                    }

                    // Preset Name
                    Text(
                        text = "• ${session.presetName}",
                        color = TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )

                    // Active dot
                    if (isActive) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(protoAccent.copy(alpha = alphaAnim))
                        )
                    }
                }

                // Date & Duration on the right
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "$startTimeStr — $endTimeStr",
                        color = TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Normal
                    )
                    Text(
                        text = "• ${formatDurationShort(session.durationSeconds)}",
                        color = if (isActive) protoAccent else TextWhite.copy(alpha = 0.85f),
                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                        fontSize = 11.sp
                    )
                }
            }

            // Row 2: Traffic stats (Left) | Peak Speed & Sockets (Right)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Traffic
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = humanBytes(session.totalBytes),
                        color = TextWhite,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.5.sp
                    )
                    Text(
                        text = "(↓${humanBytes(session.bytesReceived)} ↑${humanBytes(session.bytesSent)})",
                        color = TextMuted,
                        fontSize = 10.5.sp
                    )
                }

                // Peak Speed & Sockets
                Text(
                    text = "${humanBytes(session.peakSpeedBps)}/с • ${session.maxConnections} сок.",
                    color = TextWhite.copy(alpha = 0.80f),
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun formatDurationShort(seconds: Long): String {
    if (seconds < 60) return stringResource(R.string.duration_sec_short, seconds)
    val minutes = seconds / 60
    if (minutes < 60) return stringResource(R.string.duration_min_short, minutes)
    val hours = minutes / 60
    val remMin = minutes % 60
    return if (remMin > 0) stringResource(R.string.duration_hr_min_short, hours, remMin) else stringResource(R.string.duration_hr_short, hours)
}

