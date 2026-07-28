package com.mirrly.tgproxy.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.core.AppLogger
import com.mirrly.tgproxy.core.LogEntry
import com.mirrly.tgproxy.core.LogLevel
import com.mirrly.tgproxy.ui.theme.*

private data class LogCalcResult(
    val filteredLogs: List<LogEntry>,
    val infoCount: Int,
    val warnCount: Int,
    val errorCount: Int
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    onBack: () -> Unit,
    onOpenSettings: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val logs by AppLogger.logsFlow.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var selectedLevel by remember { mutableStateOf<LogLevel?>(null) }

    val calcResult = remember(logs, selectedLevel, searchQuery) {
        var infoC = 0
        var warnC = 0
        var errorC = 0
        val query = searchQuery.trim()
        val hasQuery = query.isNotEmpty()
        val filtered = ArrayList<LogEntry>(logs.size)

        for (i in logs.indices) {
            val entry = logs[i]
            when (entry.level) {
                LogLevel.INFO -> infoC++
                LogLevel.WARN -> warnC++
                LogLevel.ERROR -> errorC++
            }

            if (selectedLevel != null && entry.level != selectedLevel) continue
            if (hasQuery) {
                val matches = entry.humanMessage.contains(query, ignoreCase = true) ||
                        entry.rawMessage.contains(query, ignoreCase = true) ||
                        entry.tag.contains(query, ignoreCase = true)
                if (!matches) continue
            }
            filtered.add(entry)
        }
        filtered.reverse()
        LogCalcResult(filtered, infoC, warnC, errorC)
    }

    val filteredLogs = calcResult.filteredLogs
    val infoCount = calcResult.infoCount
    val warnCount = calcResult.warnCount
    val errorCount = calcResult.errorCount

    val pureBlack = Color(0xFF000000)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Журнал событий",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = TextWhite
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
                    IconButton(onClick = {
                        val text = filteredLogs.joinToString("\n") { "[${it.formattedTime}] ${it.humanMessage}" }
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Mirrly Proxy Logs", text))
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        Toast.makeText(context, "Логи скопированы!", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_copy),
                            contentDescription = "Скопировать",
                            tint = TextWhite,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(onClick = {
                        AppLogger.clear()
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_trash),
                            contentDescription = "Очистить",
                            tint = TextWhite,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    if (onOpenSettings != null) {
                        IconButton(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onOpenSettings()
                        }) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_settings),
                                contentDescription = "Настройки",
                                tint = TextWhite,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = pureBlack)
            )
        },
        containerColor = pureBlack
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(pureBlack)
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {

            // Minimalist Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Поиск событий...", color = TextMuted, fontSize = 14.sp) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_search),
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = {
                            searchQuery = ""
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }) {
                            Text("✕", color = TextMuted, fontSize = 14.sp)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = pureBlack,
                    unfocusedContainerColor = pureBlack,
                    focusedBorderColor = ActiveGreenLed,
                    unfocusedBorderColor = Color(0xFF1E2333),
                    focusedTextColor = TextWhite,
                    unfocusedTextColor = TextWhite
                )
            )

            // Category Filter Badges
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                LogFilterBadge(
                    text = "Все (${logs.size})",
                    isSelected = selectedLevel == null,
                    activeColor = TextWhite,
                    onClick = {
                        selectedLevel = null
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                )
                LogFilterBadge(
                    text = "Инфо ($infoCount)",
                    isSelected = selectedLevel == LogLevel.INFO,
                    activeColor = ActiveGreenLed,
                    onClick = {
                        selectedLevel = if (selectedLevel == LogLevel.INFO) null else LogLevel.INFO
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                )
                LogFilterBadge(
                    text = "Варн ($warnCount)",
                    isSelected = selectedLevel == LogLevel.WARN,
                    activeColor = Color(0xFFF59E0B),
                    onClick = {
                        selectedLevel = if (selectedLevel == LogLevel.WARN) null else LogLevel.WARN
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                )
                LogFilterBadge(
                    text = "Ошибки ($errorCount)",
                    isSelected = selectedLevel == LogLevel.ERROR,
                    activeColor = Color(0xFFEF4444),
                    onClick = {
                        selectedLevel = if (selectedLevel == LogLevel.ERROR) null else LogLevel.ERROR
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                )
            }

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF161A26)))

            // Log List / Friendly Empty State
            if (filteredLogs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_logs),
                            contentDescription = null,
                            tint = Color(0xFF282E3E),
                            modifier = Modifier.size(54.dp)
                        )
                        Text(
                            text = if (searchQuery.isNotEmpty()) "События не найдены" else "Журнал событий пуст",
                            color = TextMuted,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(
                        items = filteredLogs,
                        key = { "${it.timestamp}_${it.tag}_${it.humanMessage.hashCode()}" }
                    ) { entry ->
                        HumanLogCard(entry)
                    }
                }
            }
        }
    }
}

@Composable
fun LogFilterBadge(
    text: String,
    isSelected: Boolean,
    activeColor: Color,
    onClick: () -> Unit
) {
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) activeColor.copy(alpha = 0.18f) else Color(0xFF141722),
        animationSpec = tween(200),
        label = "badgeBg"
    )
    val textColor by animateColorAsState(
        targetValue = if (isSelected) activeColor else TextMuted,
        animationSpec = tween(200),
        label = "badgeText"
    )

    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(bgColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
fun HumanLogCard(entry: LogEntry) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val levelColor = when (entry.level) {
        LogLevel.INFO -> ActiveGreenLed
        LogLevel.WARN -> Color(0xFFF59E0B)
        LogLevel.ERROR -> Color(0xFFEF4444)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = "[${entry.formattedTime}] ${entry.humanMessage}"
                clipboard.setPrimaryClip(ClipData.newPlainText("Log Entry", text))
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                Toast.makeText(context, "Запись скопирована", Toast.LENGTH_SHORT).show()
            }
            .padding(vertical = 4.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Subtle vertical indicator bar
        Box(
            modifier = Modifier
                .padding(top = 4.dp, end = 12.dp)
                .width(3.dp)
                .height(38.dp)
                .clip(CircleShape)
                .background(levelColor)
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.humanMessage,
                color = TextWhite,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 19.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = entry.formattedTime,
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
                if (entry.tag.isNotBlank() && entry.tag != "System") {
                    Text(
                        text = "•  ${entry.tag}",
                        color = TextMuted.copy(alpha = 0.7f),
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}
