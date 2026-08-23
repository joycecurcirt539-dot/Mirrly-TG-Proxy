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
import android.os.Build
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.core.WorkerProfile
import com.mirrly.tgproxy.core.WorkerStatus
import com.mirrly.tgproxy.service.PreferencesManager
import com.mirrly.tgproxy.service.WorkerPingTester
import com.mirrly.tgproxy.ui.theme.LocalProtocolColors
import com.mirrly.tgproxy.ui.theme.TextMuted
import com.mirrly.tgproxy.ui.theme.TextWhite
import com.mirrly.tgproxy.ui.theme.fadingEdges
import com.mirrly.tgproxy.ui.theme.springPress
import com.mirrly.tgproxy.ui.theme.staggeredEntrance
import kotlinx.coroutines.launch

private enum class WorkerFilterType {
    ALL,
    DEVELOPER,
    CUSTOM
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkerManagerScreen(
    prefs: PreferencesManager,
    onBack: () -> Unit,
    onOpenWorkerGuide: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current

    val isSocks5 by prefs.isSocks5Flow.collectAsState(initial = false)
    val protoColors = LocalProtocolColors.current
    val activeProtoColor = protoColors.primary

    var activeWorkerId by remember { mutableStateOf(prefs.getActiveWorkerId()) }
    var customWorkers by remember { mutableStateOf(prefs.getCustomWorkers()) }
    val devWorkers = remember { prefs.getDeveloperWorkers() }

    var pingResults by remember { mutableStateOf<Map<String, Pair<WorkerStatus, Long?>>>(emptyMap()) }
    var isPinging by remember { mutableStateOf(false) }

    var selectedFilter by remember { mutableStateOf(WorkerFilterType.ALL) }
    var isSearchVisible by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val searchFocusRequester = remember { FocusRequester() }

    var headerHeightDp by remember { mutableStateOf(180.dp) }
    var showAddDialog by remember { mutableStateOf(false) }
    var workerToDelete by remember { mutableStateOf<WorkerProfile?>(null) }

    val filterTypes = remember { listOf(WorkerFilterType.ALL, WorkerFilterType.DEVELOPER, WorkerFilterType.CUSTOM) }

    fun switchToNextFilter() {
        val currentIndex = filterTypes.indexOf(selectedFilter)
        if (currentIndex < filterTypes.size - 1) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            selectedFilter = filterTypes[currentIndex + 1]
        }
    }

    fun switchToPreviousFilter() {
        val currentIndex = filterTypes.indexOf(selectedFilter)
        if (currentIndex > 0) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            selectedFilter = filterTypes[currentIndex - 1]
        }
    }

    fun handleDismiss() {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        onBack()
    }

    val nestedScrollConnection = remember(density) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                val thresholdPx = with(density) { -24.dp.toPx() }
                if (available.y < thresholdPx) {
                    handleDismiss()
                }
                return Offset.Zero
            }
        }
    }

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
                pingResults = newResults.toMap()
            }
            isPinging = false
            Toast.makeText(context, "Замер пинга завершен", Toast.LENGTH_SHORT).show()
        }
    }

    fun pingSingleWorker(domain: String) {
        scope.launch {
            val res = WorkerPingTester.pingWorker(domain)
            val newResults = pingResults.toMutableMap()
            newResults[domain] = res
            pingResults = newResults
        }
    }

    fun shareWorker(worker: WorkerProfile) {
        if (worker.isDeveloperWorker) {
            Toast.makeText(context, "Официальным узлом нельзя делиться", Toast.LENGTH_SHORT).show()
            return
        }
        val encodedDomain = Uri.encode(worker.domain)
        val encodedName = Uri.encode(worker.name)
        val link = "https://mirrly.app/worker?domain=$encodedDomain&name=$encodedName"

        val shareText = buildString {
            append("Подключение Cloudflare Worker для Mirrly TG Proxy:\n\n")
            append("Ссылка для импорта:\n")
            append("$link\n\n")
            append("Домен: ${worker.domain}\n\n")
            append("Релизы приложения:\n")
            append("https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/releases")
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
            putExtra(Intent.EXTRA_SUBJECT, "Cloudflare Worker для Mirrly TG Proxy")
        }
        context.startActivity(Intent.createChooser(intent, "Поделиться воркером"))
    }

    val hasRateLimitedWorkers = pingResults.values.any { it.first == WorkerStatus.RATE_LIMITED_429 }

    val pingRotation by animateFloatAsState(
        targetValue = if (isPinging) 360f else 0f,
        animationSpec = if (isPinging) infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ) else tween(300),
        label = "pingRotation"
    )

    LaunchedEffect(isSearchVisible) {
        if (isSearchVisible) {
            searchFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    // Filtered lists calculation
    val allWorkers = remember(devWorkers, customWorkers) { devWorkers + customWorkers }
    val filteredWorkers = remember(allWorkers, selectedFilter, searchQuery) {
        val trimmed = searchQuery.trim()
        allWorkers.filter { worker ->
            val matchesFilter = when (selectedFilter) {
                WorkerFilterType.ALL -> true
                WorkerFilterType.DEVELOPER -> worker.isDeveloperWorker
                WorkerFilterType.CUSTOM -> !worker.isDeveloperWorker
            }
            val matchesQuery = if (trimmed.isEmpty()) true else {
                worker.name.contains(trimmed, ignoreCase = true) ||
                        worker.domain.contains(trimmed, ignoreCase = true)
            }
            matchesFilter && matchesQuery
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(selectedFilter) {
                var horizontalAccumulator = 0f
                detectHorizontalDragGestures(
                    onDragStart = { horizontalAccumulator = 0f },
                    onDragEnd = {
                        if (horizontalAccumulator < -28.dp.toPx()) {
                            switchToNextFilter()
                        } else if (horizontalAccumulator > 28.dp.toPx()) {
                            switchToPreviousFilter()
                        }
                        horizontalAccumulator = 0f
                    },
                    onDragCancel = { horizontalAccumulator = 0f },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        horizontalAccumulator += dragAmount
                    }
                )
            }
    ) {
        // 1. SCROLLABLE WORKERS FEED
        if (filteredWorkers.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        var verticalDragAccumulator = 0f
                        detectVerticalDragGestures(
                            onDragStart = { verticalDragAccumulator = 0f },
                            onDragEnd = {
                                if (verticalDragAccumulator < -28.dp.toPx()) {
                                    handleDismiss()
                                }
                                verticalDragAccumulator = 0f
                            },
                            onDragCancel = { verticalDragAccumulator = 0f },
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                verticalDragAccumulator += dragAmount
                            }
                        )
                    }
                    .padding(bottom = 40.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_shield),
                        contentDescription = null,
                        tint = TextMuted.copy(alpha = 0.4f),
                        modifier = Modifier.size(52.dp)
                    )
                    Text(
                        text = if (searchQuery.isNotEmpty()) "Узлы не найдены" else "Список воркеров пуст",
                        color = TextMuted,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    if (selectedFilter == WorkerFilterType.CUSTOM && customWorkers.isEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = activeProtoColor.copy(alpha = 0.1f),
                            border = BorderStroke(1.dp, activeProtoColor.copy(alpha = 0.35f)),
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .springPress(onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    showAddDialog = true
                                })
                        ) {
                            Text(
                                text = "Добавить",
                                color = activeProtoColor,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(nestedScrollConnection),
                contentPadding = PaddingValues(
                    top = headerHeightDp + 8.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
                    start = 16.dp,
                    end = 16.dp
                ),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Warning Card for 429 Rate Limit (Pinned at top of list if present)
                if (hasRateLimitedWorkers) {
                    item(key = "rate_limit_warning") {
                        Surface(
                            shape = RoundedCornerShape(13.dp),
                            color = Color(0xFFF59E0B).copy(alpha = 0.08f),
                            border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.45f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 2.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
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
                                            .background(Color(0xFFF59E0B))
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(5.dp),
                                        color = Color.Transparent,
                                        border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.35f))
                                    ) {
                                        Text(
                                            text = "Лимит 429",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFF59E0B),
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.5.dp)
                                        )
                                    }
                                    Text(
                                        text = "Cloudflare Rate Limit",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = Color(0xFFF59E0B)
                                    )
                                }
                                Text(
                                    text = "На бесплатном тарифе Cloudflare выделяет 100 000 запросов в сутки на домен. Лимит сбрасывается в 00:00 UTC. Выберите другой узел или подключите персональный воркер.",
                                    fontSize = 12.sp,
                                    color = TextWhite.copy(alpha = 0.85f),
                                    lineHeight = 16.5.sp
                                )
                            }
                        }
                    }
                }

                itemsIndexed(
                    items = filteredWorkers,
                    key = { _, item -> item.id }
                ) { index, worker ->
                    val isActive = worker.id == activeWorkerId
                    GlassWorkerCard(
                        worker = worker,
                        isActive = isActive,
                        activeAccentColor = activeProtoColor,
                        pingInfo = pingResults[worker.domain],
                        onSelect = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            prefs.setActiveWorkerId(worker.id)
                            activeWorkerId = worker.id
                            Toast.makeText(context, "Активирован: ${worker.name}", Toast.LENGTH_SHORT).show()
                        },
                        onShare = if (!worker.isDeveloperWorker) {
                            { shareWorker(worker) }
                        } else null,
                        onDelete = if (!worker.isDeveloperWorker) {
                            {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                workerToDelete = worker
                            }
                        } else null
                    )
                }

                // App Links Integration Card at bottom
                item(key = "app_links_card") {
                    Surface(
                        shape = RoundedCornerShape(13.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, Color(0xFF181E2E)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
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
                                Surface(
                                    shape = RoundedCornerShape(5.dp),
                                    color = Color.Transparent,
                                    border = BorderStroke(1.dp, Color(0xFF1E283D))
                                ) {
                                    Text(
                                        text = "Интеграция",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextMuted,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.5.dp)
                                    )
                                }
                                Text(
                                    text = "Открытие ссылок в приложении",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextWhite
                                )
                            }
                            Text(
                                text = "Чтобы ссылки вида https://mirrly.app/worker?... открывались напрямую в приложении, включите поддержку ссылок в системных параметрах Android.",
                                fontSize = 12.sp,
                                color = TextMuted,
                                lineHeight = 16.sp
                            )
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = activeProtoColor.copy(alpha = 0.08f),
                                border = BorderStroke(1.dp, activeProtoColor.copy(alpha = 0.35f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .springPress(onClick = {
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
                                    })
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Открыть настройки ссылок",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = activeProtoColor
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. PINNED FROSTED GLASS HEADER (Title Bar + Search + Filter Chips + Strict Action Buttons)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coordinates ->
                    val heightInDp = with(density) { coordinates.size.height.toDp() }
                    if (heightInDp > 0.dp && heightInDp != headerHeightDp) {
                        headerHeightDp = heightInDp
                    }
                }
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.99f),
                            Color.Black.copy(alpha = 0.98f),
                            Color.Black.copy(alpha = 0.96f),
                            Color.Black.copy(alpha = 0.88f),
                            Color.Black.copy(alpha = 0.00f)
                        )
                    )
                )
                .pointerInput(Unit) {
                    var headerDragY = 0f
                    detectVerticalDragGestures(
                        onDragStart = { headerDragY = 0f },
                        onDragEnd = {
                            if (headerDragY < -24.dp.toPx()) {
                                handleDismiss()
                            }
                            headerDragY = 0f
                        },
                        onDragCancel = { headerDragY = 0f },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            headerDragY += dragAmount
                        }
                    )
                }
                .padding(bottom = 18.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Top App Bar
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "Менеджер воркеров",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = TextWhite,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (isSocks5) "SOCKS5 Cloudflare Туннели" else "MTProto Cloudflare Туннели",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = activeProtoColor
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            handleDismiss()
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
                        // Toggle Search Bar
                        IconButton(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            if (isSearchVisible && searchQuery.isNotEmpty()) {
                                searchQuery = ""
                            }
                            isSearchVisible = !isSearchVisible
                            if (!isSearchVisible) {
                                keyboardController?.hide()
                            }
                        }) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_search),
                                contentDescription = "Поиск",
                                tint = if (isSearchVisible || searchQuery.isNotEmpty()) activeProtoColor else TextWhite,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // Ping All Workers (ic_refresh with rotation)
                        IconButton(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            startPingAll()
                        }) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_refresh),
                                contentDescription = "Замерить пинг",
                                tint = if (isPinging) activeProtoColor else TextWhite,
                                modifier = Modifier
                                    .size(20.dp)
                                    .rotate(pingRotation)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )

                // Animated Search Bar
                AnimatedVisibility(
                    visible = isSearchVisible,
                    enter = expandVertically(animationSpec = tween(220, easing = FastOutSlowInEasing)) +
                            fadeIn(animationSpec = tween(220)),
                    exit = shrinkVertically(animationSpec = tween(180, easing = FastOutSlowInEasing)) +
                            fadeOut(animationSpec = tween(180))
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(searchFocusRequester),
                        placeholder = {
                            Text(
                                text = "Поиск по названию или домену...",
                                color = TextMuted,
                                fontSize = 13.sp
                            )
                        },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_search),
                                contentDescription = null,
                                tint = if (searchQuery.isNotEmpty()) activeProtoColor else TextMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = {
                                    searchQuery = ""
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }) {
                                    Text("✕", color = TextMuted, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
                        shape = RoundedCornerShape(13.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedBorderColor = activeProtoColor.copy(alpha = 0.85f),
                            unfocusedBorderColor = Color(0xFF1E283D),
                            focusedTextColor = TextWhite,
                            unfocusedTextColor = TextWhite
                        )
                    )
                }

                // 3 Fixed-Width Segmented Filter Chips (Logs style)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SegmentedFilterChip(
                        title = "Все",
                        count = allWorkers.size,
                        isSelected = selectedFilter == WorkerFilterType.ALL,
                        activeColor = activeProtoColor,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            selectedFilter = WorkerFilterType.ALL
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    )
                    SegmentedFilterChip(
                        title = "Официальные",
                        count = devWorkers.size,
                        isSelected = selectedFilter == WorkerFilterType.DEVELOPER,
                        activeColor = activeProtoColor,
                        modifier = Modifier.weight(1.3f),
                        onClick = {
                            selectedFilter = WorkerFilterType.DEVELOPER
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    )
                    SegmentedFilterChip(
                        title = "Личные",
                        count = customWorkers.size,
                        isSelected = selectedFilter == WorkerFilterType.CUSTOM,
                        activeColor = activeProtoColor,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            selectedFilter = WorkerFilterType.CUSTOM
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    )
                }

                // Action Buttons Row ("Добавить" and "Инструкция" - strict, no emojis)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(11.dp),
                        color = activeProtoColor.copy(alpha = 0.08f),
                        border = BorderStroke(1.dp, activeProtoColor.copy(alpha = 0.45f)),
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .springPress(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                showAddDialog = true
                            })
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Добавить",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.5.sp,
                                color = activeProtoColor
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(11.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, Color(0xFF1E283D)),
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .springPress(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onOpenWorkerGuide()
                            })
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Инструкция",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.5.sp,
                                color = TextWhite.copy(alpha = 0.85f)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddWorkerDialog(
            activeAccentColor = activeProtoColor,
            onDismiss = { showAddDialog = false },
            onAdd = { name, domain ->
                val res = prefs.addCustomWorker(name, domain)
                res.fold(
                    onSuccess = { added ->
                        prefs.setActiveWorkerId(added.id)
                        refreshWorkers()
                        showAddDialog = false
                        Toast.makeText(context, "Воркер «${added.name}» добавлен и активирован", Toast.LENGTH_SHORT).show()
                    },
                    onFailure = { err ->
                        Toast.makeText(context, err.message ?: "Ошибка добавления", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        )
    }

    workerToDelete?.let { worker ->
        DeleteWorkerConfirmDialog(
            worker = worker,
            onDismiss = { workerToDelete = null },
            onConfirm = {
                prefs.deleteCustomWorker(worker.id)
                refreshWorkers()
                workerToDelete = null
                Toast.makeText(context, "Воркер удален", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

@Composable
private fun SegmentedFilterChip(
    title: String,
    count: Int,
    isSelected: Boolean,
    activeColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) activeColor.copy(alpha = 0.85f) else Color(0xFF1E283D),
        animationSpec = tween(180),
        label = "chipBorder"
    )
    val titleColor by animateColorAsState(
        targetValue = if (isSelected) activeColor else TextWhite.copy(alpha = 0.85f),
        animationSpec = tween(180),
        label = "chipTitle"
    )

    Surface(
        shape = RoundedCornerShape(11.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier
            .height(34.dp)
            .springPress(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = titleColor,
                fontSize = 11.5.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = count.toString(),
                color = if (isSelected) activeColor else TextMuted,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

/**
 * Clean Frosted Glass Worker Card (Compact, transparent outline style)
 */
@Composable
private fun GlassWorkerCard(
    modifier: Modifier = Modifier,
    worker: WorkerProfile,
    isActive: Boolean,
    activeAccentColor: Color,
    pingInfo: Pair<WorkerStatus, Long?>?,
    onSelect: () -> Unit,
    onShare: (() -> Unit)?,
    onDelete: (() -> Unit)?
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    val status = pingInfo?.first ?: WorkerStatus.UNKNOWN
    val pingMs = pingInfo?.second

    val statusColor = when (status) {
        WorkerStatus.ONLINE -> if ((pingMs ?: 0) < 250) Color(0xFF00E676) else Color(0xFFF59E0B)
        WorkerStatus.RATE_LIMITED_429 -> Color(0xFFEF4444)
        WorkerStatus.ERROR_UNREACHABLE -> Color(0xFFF59E0B)
        WorkerStatus.UNKNOWN -> TextMuted
    }

    val statusText = when (status) {
        WorkerStatus.ONLINE -> "${pingMs ?: 0} мс"
        WorkerStatus.RATE_LIMITED_429 -> "429 Лимит"
        WorkerStatus.ERROR_UNREACHABLE -> "Недоступен"
        WorkerStatus.UNKNOWN -> "—"
    }

    val cardBorderColor = if (isActive) {
        activeAccentColor.copy(alpha = 0.9f)
    } else {
        Color(0xFF181E2E)
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
        border = BorderStroke(if (isActive) 1.2.dp else 1.dp, cardBorderColor),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .springPress(onClick = onSelect)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left block: Status Dot + Info Column
            Row(
                modifier = Modifier.weight(1f).padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                // Status Indicator Dot
                Box(
                    modifier = Modifier
                        .size(6.5.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // Line 1: Worker Name + Badges
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = worker.name,
                            color = TextWhite,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        // Category Badge
                        Surface(
                            shape = RoundedCornerShape(4.5.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, Color(0xFF1E283D))
                        ) {
                            Text(
                                text = if (worker.isDeveloperWorker) "Официальный" else "Личный",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextMuted,
                                modifier = Modifier.padding(horizontal = 4.5.dp, vertical = 1.dp)
                            )
                        }
                    }

                    // Line 2: Subtitle / Domain
                    if (worker.isDeveloperWorker) {
                        Text(
                            text = "Официальный узел Cloudflare",
                            color = TextMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Normal
                        )
                    } else {
                        Text(
                            text = worker.domain,
                            color = TextMuted,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                val clip = ClipData.newPlainText("Worker Domain", worker.domain)
                                clipboard?.setPrimaryClip(clip)
                                Toast.makeText(context, "Домен скопирован", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }

            // Right block: Monospace Ping + Actions (Share / Delete)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Monospace Ping Status
                Text(
                    text = statusText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = statusColor,
                    modifier = Modifier.padding(end = if (onShare != null || onDelete != null) 2.dp else 0.dp)
                )

                if (onShare != null) {
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onShare()
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Поделиться",
                            tint = activeAccentColor,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }

                if (onDelete != null) {
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDelete()
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_trash),
                            contentDescription = "Удалить",
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Frosted Glass Add Worker Modal Dialog (Info panels style, top-left back button, no emojis)
 */
@Composable
private fun AddWorkerDialog(
    activeAccentColor: Color,
    onDismiss: () -> Unit,
    onAdd: (name: String, domain: String) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var nameText by remember { mutableStateOf("") }
    var domainText by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        val view = LocalView.current
        SideEffect {
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
                ) { onDismiss() }
                .padding(horizontal = 24.dp)
        ) {
            // Scrollable Content
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 60.dp,
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {}
            ) {
                // Category Pill
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = activeAccentColor.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, activeAccentColor.copy(alpha = 0.35f))
                ) {
                    Text(
                        text = "ДОБАВЛЕНИЕ ВОРКЕРА",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = activeAccentColor,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                }

                // Title
                Text(
                    text = "Новый Cloudflare Worker",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite,
                    textAlign = TextAlign.Center,
                    letterSpacing = 0.3.sp
                )

                // Transparent Glass Container for Input Parameters
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White.copy(alpha = 0.04f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "ПАРАМЕТРЫ ПОДКЛЮЧЕНИЯ:",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = activeAccentColor,
                            letterSpacing = 0.5.sp
                        )

                        Text(
                            text = "Укажите название и домен созданного вами Cloudflare Worker.",
                            fontSize = 12.5.sp,
                            color = TextWhite.copy(alpha = 0.8f),
                            lineHeight = 17.sp
                        )

                        OutlinedTextField(
                            value = nameText,
                            onValueChange = { nameText = it },
                            label = { Text("Название (например: Мой домашний)") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = activeAccentColor,
                                unfocusedBorderColor = Color(0xFF223048),
                                focusedTextColor = TextWhite,
                                unfocusedTextColor = TextWhite,
                                focusedLabelColor = activeAccentColor,
                                unfocusedLabelColor = TextMuted,
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = domainText,
                            onValueChange = { domainText = it },
                            label = { Text("Домен воркера") },
                            placeholder = { Text("my-proxy.username.workers.dev", color = TextMuted.copy(alpha = 0.5f), fontSize = 12.sp) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = activeAccentColor,
                                unfocusedBorderColor = Color(0xFF223048),
                                focusedTextColor = TextWhite,
                                unfocusedTextColor = TextWhite,
                                focusedLabelColor = activeAccentColor,
                                unfocusedLabelColor = TextMuted,
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Save & Activate Action Button
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = activeAccentColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onAdd(nameText, domainText)
                        })
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "Сохранить и активировать",
                            color = Color(0xFF0A0E1A),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            // Top Header with Back Button (pinned at top left over blurred background)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopStart)
                    .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp)
            ) {
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDismiss()
                    }
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_arrow_left),
                        contentDescription = "Назад",
                        tint = TextWhite,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

/**
 * Frosted Glass Delete Confirmation Modal Dialog (Info panels style, top-left back button, no emojis)
 */
@Composable
private fun DeleteWorkerConfirmDialog(
    worker: WorkerProfile,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val haptic = LocalHapticFeedback.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        val view = LocalView.current
        SideEffect {
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
                ) { onDismiss() }
                .padding(horizontal = 24.dp)
        ) {
            // Scrollable Content
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 60.dp,
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {}
            ) {
                // Category Pill
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFFEF4444).copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.35f))
                ) {
                    Text(
                        text = "УДАЛЕНИЕ ВОРКЕРА",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFEF4444),
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                }

                // Title
                Text(
                    text = "Удалить узел?",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite,
                    textAlign = TextAlign.Center,
                    letterSpacing = 0.3.sp
                )

                // Transparent Glass Container for Details
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White.copy(alpha = 0.04f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "ПОДТВЕРЖДЕНИЕ ДЕЙСТВИЯ:",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFEF4444),
                            letterSpacing = 0.5.sp
                        )

                        Text(
                            text = "Вы действительно хотите удалить узел «${worker.name}» (${worker.domain}) из вашего списка воркеров?",
                            fontSize = 13.sp,
                            color = TextWhite.copy(alpha = 0.85f),
                            lineHeight = 18.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Delete Action Button
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFFEF4444),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onConfirm()
                        })
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "Удалить узел",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            // Top Header with Back Button (pinned at top left over blurred background)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopStart)
                    .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp)
            ) {
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDismiss()
                    }
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_arrow_left),
                        contentDescription = "Назад",
                        tint = TextWhite,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}
