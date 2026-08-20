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

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.*
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.core.WorkerProfile
import com.mirrly.tgproxy.core.WorkerStatus
import com.mirrly.tgproxy.service.PreferencesManager
import com.mirrly.tgproxy.service.WorkerPingTester
import com.mirrly.tgproxy.ui.theme.TextMuted
import com.mirrly.tgproxy.ui.theme.TextWhite
import kotlinx.coroutines.launch
import java.net.URLEncoder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkerManagerScreen(
    prefs: PreferencesManager,
    onBack: () -> Unit,
    onOpenWorkerGuide: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var activeWorkerId by remember { mutableStateOf(prefs.getActiveWorkerId()) }
    var customWorkers by remember { mutableStateOf(prefs.getCustomWorkers()) }
    val devWorkers = remember { prefs.getDeveloperWorkers() }

    var pingResults by remember { mutableStateOf<Map<String, Pair<WorkerStatus, Long?>>>(emptyMap()) }
    var isPinging by remember { mutableStateOf(false) }

    var showAddDialog by remember { mutableStateOf(false) }
    var workerToDelete by remember { mutableStateOf<WorkerProfile?>(null) }

    fun refreshWorkers() {
        customWorkers = prefs.getCustomWorkers()
        activeWorkerId = prefs.getActiveWorkerId()
    }

    fun startPingAll() {
        if (isPinging) return
        isPinging = true
        scope.launch {
            val all = devWorkers + customWorkers
            val newResults = pingResults.toMutableMap()
            for (w in all) {
                val res = WorkerPingTester.pingWorker(w.domain)
                newResults[w.domain] = res
            }
            pingResults = newResults
            isPinging = false
            Toast.makeText(context, "Замер пинга завершен", Toast.LENGTH_SHORT).show()
        }
    }

    fun shareWorker(worker: WorkerProfile) {
        if (worker.isDeveloperWorker) {
            Toast.makeText(context, "Воркером разработчика нельзя делиться", Toast.LENGTH_SHORT).show()
            return
        }
        val encodedDomain = Uri.encode(worker.domain)
        val encodedName = Uri.encode(worker.name)
        val link = "https://mirrly.app/worker?domain=$encodedDomain&name=$encodedName"

        val shareText = buildString {
            append("Подключи мой Cloudflare Worker в Mirrly TG Proxy в 1 клик:\n\n")
            append("⚡ Ссылка для импорта:\n")
            append("$link\n\n")
            append("• Домен: ${worker.domain}\n\n")
            append("📱 Если приложение еще не установлено:\n")
            append("Скачать APK: https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/releases")
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
            putExtra(Intent.EXTRA_SUBJECT, "Cloudflare Worker для Mirrly TG Proxy")
        }
        context.startActivity(Intent.createChooser(intent, "Поделиться воркером"))
    }

    val hasRateLimitedWorkers = pingResults.values.any { it.first == WorkerStatus.RATE_LIMITED_429 }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Менеджер воркеров",
                            color = TextWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            text = "Управление узлами Cloudflare SOCKS5",
                            color = Color(0xFF00E5FF),
                            fontSize = 11.5.sp
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onBack()
                        },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад",
                            tint = TextWhite,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            startPingAll()
                        },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Пинг всех",
                            tint = if (isPinging) Color(0xFF00E5FF) else TextWhite,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = Color.Transparent
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Action Buttons Card
            item {
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Add custom worker button
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            showAddDialog = true
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00E676).copy(alpha = 0.18f),
                            contentColor = Color(0xFF00E676)
                        ),
                        border = BorderStroke(1.dp, Color(0xFF00E676).copy(alpha = 0.6f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Добавить", fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                    }

                    // Worker Guide button
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onOpenWorkerGuide()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFF38020).copy(alpha = 0.18f),
                            contentColor = Color(0xFFFFA726)
                        ),
                        border = BorderStroke(1.dp, Color(0xFFF38020).copy(alpha = 0.6f))
                    ) {
                        Text("⚡ Инструкция", fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                    }
                }
            }

            // 429 Rate Limit Warning Card
            if (hasRateLimitedWorkers) {
                item {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFFFF5252).copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, Color(0xFFFF5252).copy(alpha = 0.65f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = Color(0xFFFF5252),
                                modifier = Modifier.size(20.dp)
                            )
                            Column {
                                Text(
                                    text = "Исчерпан суточный лимит запросов (429)",
                                    color = Color(0xFFFF5252),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = "На узле со статусом 429 закончилась бесплатная квота Cloudflare (100 000 запросов/день). Лимиты обновляются каждые 24 часа (00:00 UTC). Рекомендуем создать личный воркер по инструкции выше.",
                                    color = TextWhite.copy(alpha = 0.8f),
                                    fontSize = 11.5.sp,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }
                }
            }

            // ── Section 1: Developer Nodes ──
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ОФИЦИАЛЬНЫЕ УЗЛЫ РАЗРАБОТЧИКА",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00E5FF),
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "${devWorkers.size}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00E5FF).copy(alpha = 0.7f)
                    )
                }
            }

            items(devWorkers, key = { it.id }) { worker ->
                WorkerProfileCard(
                    worker = worker,
                    isActive = worker.id == activeWorkerId,
                    pingInfo = pingResults[worker.domain],
                    onSelect = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        prefs.setActiveWorkerId(worker.id)
                        activeWorkerId = worker.id
                        Toast.makeText(context, "Активирован: ${worker.name}", Toast.LENGTH_SHORT).show()
                    },
                    onPing = {
                        scope.launch {
                            val res = WorkerPingTester.pingWorker(worker.domain)
                            pingResults = pingResults + (worker.domain to res)
                        }
                    },
                    onShare = null, // Disabled for Developer worker
                    onDelete = null  // Built-in
                )
            }

            // ── Section 2: Custom User Nodes ──
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ЛИЧНЫЕ И ДОБАВЛЕННЫЕ ВОРКЕРЫ",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFB388FF),
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "${customWorkers.size}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextMuted
                    )
                }
            }

            if (customWorkers.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color.White.copy(alpha = 0.03f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "У вас пока нет добавленных воркеров",
                                color = TextWhite.copy(alpha = 0.8f),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Создайте свой персональный Cloudflare Worker или импортируйте ссылку от друга в 1 клик",
                                color = TextMuted,
                                fontSize = 11.5.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                items(customWorkers, key = { it.id }) { worker ->
                    WorkerProfileCard(
                        worker = worker,
                        isActive = worker.id == activeWorkerId,
                        pingInfo = pingResults[worker.domain],
                        onSelect = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            prefs.setActiveWorkerId(worker.id)
                            activeWorkerId = worker.id
                            Toast.makeText(context, "Активирован: ${worker.name}", Toast.LENGTH_SHORT).show()
                        },
                        onPing = {
                            scope.launch {
                                val res = WorkerPingTester.pingWorker(worker.domain)
                                pingResults = pingResults + (worker.domain to res)
                            }
                        },
                        onShare = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            shareWorker(worker)
                        },
                        onDelete = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            workerToDelete = worker
                        }
                    )
                }
            }

            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White.copy(alpha = 0.03f),
                    border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.25f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "ОТКРЫТИЕ ССЫЛОК В ПРИЛОЖЕНИИ",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00E5FF),
                            letterSpacing = 0.8.sp
                        )
                        Text(
                            text = "Чтобы ссылки https://mirrly.app/worker?... открывались напрямую в Mirrly TG Proxy, разрешите приложению открывать поддерживаемые ссылки в системных настройках Android.",
                            fontSize = 11.5.sp,
                            color = TextMuted,
                            lineHeight = 16.sp
                        )
                        Button(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                try {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        val intent = Intent(
                                            android.provider.Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                        context.startActivity(intent)
                                    } else {
                                        val intent = Intent(
                                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                        context.startActivity(intent)
                                    }
                                } catch (_: Exception) {
                                    val intent = Intent(
                                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                    context.startActivity(intent)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Transparent,
                                contentColor = Color(0xFF00E5FF)
                            ),
                            border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(38.dp)
                        ) {
                            Text("Разрешить открытие ссылок в Настройках", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // Add Worker Dialog
    if (showAddDialog) {
        AddWorkerDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { name, domain ->
                val res = prefs.addCustomWorker(name, domain)
                res.fold(
                    onSuccess = { added ->
                        prefs.setActiveWorkerId(added.id)
                        refreshWorkers()
                        showAddDialog = false
                        Toast.makeText(context, "Воркер «${added.name}» добавлен и активирован ⚡", Toast.LENGTH_SHORT).show()
                    },
                    onFailure = { err ->
                        Toast.makeText(context, err.message ?: "Ошибка добавления", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        )
    }

    // Delete Confirmation Dialog
    workerToDelete?.let { worker ->
        AlertDialog(
            onDismissRequest = { workerToDelete = null },
            containerColor = Color(0xFF0F172A),
            shape = RoundedCornerShape(20.dp),
            title = {
                Text("Удалить воркер?", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            },
            text = {
                Text(
                    "Вы действительно хотите удалить узел «${worker.name}» (${worker.domain}) из списка?",
                    color = TextWhite.copy(alpha = 0.75f),
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        prefs.deleteCustomWorker(worker.id)
                        refreshWorkers()
                        workerToDelete = null
                        Toast.makeText(context, "Воркер удален", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Удалить", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { workerToDelete = null }) {
                    Text("Отмена", color = TextWhite.copy(alpha = 0.7f))
                }
            }
        )
    }
}

@Composable
fun WorkerProfileCard(
    worker: WorkerProfile,
    isActive: Boolean,
    pingInfo: Pair<WorkerStatus, Long?>?,
    onSelect: () -> Unit,
    onPing: () -> Unit,
    onShare: (() -> Unit)?,
    onDelete: (() -> Unit)?
) {
    val haptic = LocalHapticFeedback.current
    val borderColor = if (isActive) {
        if (worker.isDeveloperWorker) Color(0xFF00E5FF) else Color(0xFF00E676)
    } else {
        Color.White.copy(alpha = 0.08f)
    }

    val cardBg = if (isActive) {
        if (worker.isDeveloperWorker) Color(0xFF00E5FF).copy(alpha = 0.07f) else Color(0xFF00E676).copy(alpha = 0.07f)
    } else {
        Color.White.copy(alpha = 0.035f)
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = cardBg,
        border = BorderStroke(if (isActive) 1.5.dp else 1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                onSelect()
            }
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header Row: Active Radio Glow + Name + Badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    // Radio indicator
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(
                                if (isActive) {
                                    if (worker.isDeveloperWorker) Color(0xFF00E5FF) else Color(0xFF00E676)
                                } else {
                                    Color.Transparent
                                }
                            )
                            .border(
                                1.5.dp,
                                if (isActive) {
                                    if (worker.isDeveloperWorker) Color(0xFF00E5FF) else Color(0xFF00E676)
                                } else {
                                    Color.White.copy(alpha = 0.3f)
                                },
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isActive) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = Color(0xFF0A0E1A),
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }

                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = worker.name,
                                color = TextWhite,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            if (isActive) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = (if (worker.isDeveloperWorker) Color(0xFF00E5FF) else Color(0xFF00E676)).copy(alpha = 0.2f)
                                ) {
                                    Text(
                                        text = "АКТИВЕН",
                                        color = if (worker.isDeveloperWorker) Color(0xFF00E5FF) else Color(0xFF00E676),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Black,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = worker.domain,
                            color = TextMuted,
                            fontSize = 11.5.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Footer Row: Ping Status Pill + Action Buttons (Ping, Share, Delete)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Ping status indicator
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val status = pingInfo?.first ?: WorkerStatus.UNKNOWN
                    val pingMs = pingInfo?.second

                    val statusColor = when (status) {
                        WorkerStatus.ONLINE -> Color(0xFF00E676)
                        WorkerStatus.RATE_LIMITED_429 -> Color(0xFFFF5252)
                        WorkerStatus.ERROR_UNREACHABLE -> Color(0xFFFF9100)
                        WorkerStatus.UNKNOWN -> TextMuted
                    }

                    val statusText = when (status) {
                        WorkerStatus.ONLINE -> "${pingMs ?: 0} мс"
                        WorkerStatus.RATE_LIMITED_429 -> "429 Лимит"
                        WorkerStatus.ERROR_UNREACHABLE -> "Недоступен"
                        WorkerStatus.UNKNOWN -> "Не проверен"
                    }

                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Text(
                        text = statusText,
                        color = statusColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // Action buttons row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Manual Ping button
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color.White.copy(alpha = 0.06f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onPing()
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(text = "⚡", fontSize = 10.sp)
                            Text(
                                text = "Пинг",
                                color = TextWhite,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    // Share button (ONLY FOR CUSTOM WORKERS)
                    if (onShare != null && !worker.isDeveloperWorker) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF00E5FF).copy(alpha = 0.1f),
                            border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.35f)),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onShare()
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = "Поделиться",
                                    tint = Color(0xFF00E5FF),
                                    modifier = Modifier.size(11.dp)
                                )
                                Text(
                                    text = "Поделиться",
                                    color = Color(0xFF00E5FF),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    // Delete button (ONLY FOR CUSTOM WORKERS)
                    if (onDelete != null && !worker.isDeveloperWorker) {
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Удалить",
                                tint = Color(0xFFFF5252).copy(alpha = 0.75f),
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AddWorkerDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, domain: String) -> Unit
) {
    var nameText by remember { mutableStateOf("") }
    var domainText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0F172A),
        shape = RoundedCornerShape(20.dp),
        title = {
            Text("Добавить Cloudflare Worker", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Укажите название и домен созданного вами Cloudflare Worker.",
                    color = TextWhite.copy(alpha = 0.7f),
                    fontSize = 12.5.sp
                )

                OutlinedTextField(
                    value = nameText,
                    onValueChange = { nameText = it },
                    label = { Text("Название (например: Мой домашний)") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF00E676),
                        unfocusedBorderColor = Color(0xFF334155),
                        focusedTextColor = TextWhite,
                        unfocusedTextColor = TextWhite
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = domainText,
                    onValueChange = { domainText = it },
                    label = { Text("Домен воркера") },
                    placeholder = { Text("my-proxy.username.workers.dev", color = TextMuted, fontSize = 12.sp) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF00E676),
                        unfocusedBorderColor = Color(0xFF334155),
                        focusedTextColor = TextWhite,
                        unfocusedTextColor = TextWhite
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onAdd(nameText, domainText)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00E676),
                    contentColor = Color(0xFF0A0E1A)
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Сохранить ⚡", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена", color = TextWhite.copy(alpha = 0.7f))
            }
        }
    )
}
