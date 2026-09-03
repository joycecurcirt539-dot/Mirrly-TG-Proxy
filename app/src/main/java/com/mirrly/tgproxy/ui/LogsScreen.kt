package com.mirrly.tgproxy.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.core.AppLogger
import com.mirrly.tgproxy.core.LogEntry
import com.mirrly.tgproxy.core.LogEvent
import com.mirrly.tgproxy.core.LogLevel
import com.mirrly.tgproxy.ui.theme.*

// Static UI shapes and borders cached across compositions to eliminate scroll allocation churn
private val LogCardShape = RoundedCornerShape(13.dp)
private val LogTagShape = RoundedCornerShape(5.dp)
private val FilterChipShape = RoundedCornerShape(11.dp)
private val SearchFieldShape = RoundedCornerShape(14.dp)

private val InfoCardBorder = BorderStroke(0.75.dp, Color.White.copy(alpha = 0.08f))
private val WarnCardBorder = BorderStroke(0.75.dp, Color(0xFFF59E0B).copy(alpha = 0.35f))
private val ErrorCardBorder = BorderStroke(0.75.dp, Color(0xFFEF4444).copy(alpha = 0.45f))
private val TagBadgeBorder = BorderStroke(0.75.dp, Color.White.copy(alpha = 0.10f))

private val WarnAccentColor = Color(0xFFF59E0B)
private val ErrorAccentColor = Color(0xFFEF4444)

private val HeaderGradientBrush = Brush.verticalGradient(
    colors = listOf(
        Color.Black.copy(alpha = 0.99f),
        Color.Black.copy(alpha = 0.98f),
        Color.Black.copy(alpha = 0.96f),
        Color.Black.copy(alpha = 0.88f),
        Color.Black.copy(alpha = 0.00f)
    )
)

private fun matchesFilter(entry: LogEntry, query: String, level: LogLevel?): Boolean {
    if (level != null && entry.level != level) return false
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return true
    return entry.humanMessage.contains(trimmed, ignoreCase = true) ||
            entry.rawMessage.contains(trimmed, ignoreCase = true) ||
            entry.tag.contains(trimmed, ignoreCase = true)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    onBack: () -> Unit,
    onOpenSettings: (() -> Unit)? = null,
    isVisible: Boolean = true
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Start logcat reader on demand ONLY when LogsScreen is active and visible
    DisposableEffect(isVisible) {
        if (isVisible) {
            AppLogger.startLogcatReader(android.os.Process.myPid())
        }
        onDispose {
            AppLogger.stopLogcatReader()
        }
    }

    var isSearchVisible by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedLevel by remember { mutableStateOf<LogLevel?>(null) }
    val searchFocusRequester = remember { FocusRequester() }

    // Initial entrance animation barrier
    var isInitialLoad by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(450)
        isInitialLoad = false
    }

    // Internal bounded ring buffer (up to 250 elements)
    val rawLogBuffer = remember { ArrayDeque<LogEntry>(250) }

    // Differential counter states (updated in O(1) on incoming events)
    var totalCount by remember { mutableIntStateOf(0) }
    var infoCount by remember { mutableIntStateOf(0) }
    var warnCount by remember { mutableIntStateOf(0) }
    var errorCount by remember { mutableIntStateOf(0) }

    // Displayed log list for LazyColumn (newest first)
    val displayedLogs = remember { mutableStateListOf<LogEntry>() }

    var headerHeightDp by remember { mutableStateOf(160.dp) }

    // Synchronize initial logs and handle incremental differential events
    LaunchedEffect(Unit) {
        val initialLogs = AppLogger.getLogs()
        rawLogBuffer.clear()
        displayedLogs.clear()
        var iC = 0
        var wC = 0
        var eC = 0
        for (i in initialLogs.indices) {
            val entry = initialLogs[i]
            rawLogBuffer.addLast(entry)
            when (entry.level) {
                LogLevel.INFO -> iC++
                LogLevel.WARN -> wC++
                LogLevel.ERROR -> eC++
            }
            if (matchesFilter(entry, searchQuery, selectedLevel)) {
                displayedLogs.add(0, entry)
            }
        }
        totalCount = rawLogBuffer.size
        infoCount = iC
        warnCount = wC
        errorCount = eC
        listState.scrollToItem(0)

        AppLogger.logEvents.collect { event ->
            when (event) {
                is LogEvent.Added -> {
                    val entry = event.entry

                    // Differential O(1) counter update
                    totalCount++
                    when (entry.level) {
                        LogLevel.INFO -> infoCount++
                        LogLevel.WARN -> warnCount++
                        LogLevel.ERROR -> errorCount++
                    }

                    // Evict oldest if buffer capacity reached
                    if (rawLogBuffer.size >= 250) {
                        val evicted = rawLogBuffer.removeFirst()
                        totalCount--
                        when (evicted.level) {
                            LogLevel.INFO -> infoCount--
                            LogLevel.WARN -> warnCount--
                            LogLevel.ERROR -> errorCount--
                        }
                        if (displayedLogs.isNotEmpty() && displayedLogs.last().id == evicted.id) {
                            displayedLogs.removeAt(displayedLogs.lastIndex)
                        } else {
                            displayedLogs.removeAll { it.id == evicted.id }
                        }
                    }

                    rawLogBuffer.addLast(entry)

                    // Check if user is currently watching the top of the feed
                    val isAtTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset <= 60

                    // Differential addition directly to displayed list
                    if (matchesFilter(entry, searchQuery, selectedLevel)) {
                        displayedLogs.add(0, entry)
                        if (isAtTop) {
                            listState.scrollToItem(0)
                        }
                    }
                }
                is LogEvent.Cleared -> {
                    rawLogBuffer.clear()
                    displayedLogs.clear()
                    totalCount = 0
                    infoCount = 0
                    warnCount = 0
                    errorCount = 0
                    listState.scrollToItem(0)
                }
            }
        }
    }

    // Re-filter displayed list only when filter query or level selection changes
    LaunchedEffect(searchQuery, selectedLevel) {
        displayedLogs.clear()
        for (i in rawLogBuffer.indices.reversed()) {
            val entry = rawLogBuffer[i]
            if (matchesFilter(entry, searchQuery, selectedLevel)) {
                displayedLogs.add(entry)
            }
        }
        listState.scrollToItem(0)
    }

    LaunchedEffect(isSearchVisible) {
        if (isSearchVisible) {
            searchFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    val activeProtoColor = ActiveGreenLed

    // Derived states to prevent unnecessary recompositions
    val isListEmpty by remember { derivedStateOf { displayedLogs.isEmpty() } }
    val isSearchActive by remember { derivedStateOf { isSearchVisible || searchQuery.isNotEmpty() } }
    val isScrolledDown by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 1 || listState.firstVisibleItemScrollOffset > 80
        }
    }

    // Stable copy callback to prevent recreating closures and interaction sources in item rows
    val onCopyLog: (LogEntry) -> Unit = remember(context, haptic) {
        { entry ->
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = "[${entry.formattedTime}] [${entry.tag}] ${entry.humanMessage}"
            clipboard.setPrimaryClip(ClipData.newPlainText("Mirrly Log Entry", text))
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            Toast.makeText(context, "Запись скопирована", Toast.LENGTH_SHORT).show()
        }
    }

    val onCopyAllLogs: () -> Unit = remember(context, haptic, displayedLogs) {
        {
            if (displayedLogs.isEmpty()) {
                Toast.makeText(context, "Нет событий для копирования", Toast.LENGTH_SHORT).show()
            } else {
                val text = displayedLogs.joinToString("\n") { "[${it.formattedTime}] [${it.tag}] ${it.humanMessage}" }
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Mirrly Proxy Logs", text))
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                Toast.makeText(context, "Все записи скопированы", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val onClearLogs: () -> Unit = remember(context, haptic) {
        {
            AppLogger.clear()
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            Toast.makeText(context, "Логи очищены", Toast.LENGTH_SHORT).show()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        // 1. SCROLLABLE LOGS FEED
        if (isListEmpty) {
            Box(
                modifier = Modifier
                    .adaptiveContainerWidth(600.dp)
                    .fillMaxHeight()
                    .padding(bottom = 40.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.staggeredEntrance(index = 1)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_logs),
                        contentDescription = null,
                        tint = TextMuted.copy(alpha = 0.4f),
                        modifier = Modifier.size(52.dp)
                    )
                    Text(
                        text = if (searchQuery.isNotEmpty()) "События не найдены" else "Логи пусты",
                        color = TextMuted,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .adaptiveContainerWidth(600.dp)
                    .fillMaxHeight()
                    .fadingEdges(topFadeHeight = 24.dp, bottomFadeHeight = 44.dp)
                    .adaptiveContentPadding(),
                contentPadding = PaddingValues(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + headerHeightDp + 6.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 20.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(
                    items = displayedLogs,
                    key = { _, entry -> entry.id }
                ) { index, entry ->
                    GlassLogCard(
                        entry = entry,
                        onCopy = onCopyLog,
                        modifier = Modifier.logItemCascadeEntrance(
                            index = index,
                            isInitialBatch = isInitialLoad
                        )
                    )
                }
            }
        }

        // 2. PINNED FROSTED GLASS HEADER (Title Bar + Optional Animated Search + 4 Segmented Chips)
        Box(
            modifier = Modifier
                .adaptiveContainerWidth(600.dp)
                .onGloballyPositioned { coordinates ->
                    val heightInDp = with(density) { coordinates.size.height.toDp() }
                    if (heightInDp > 0.dp && heightInDp != headerHeightDp) {
                        headerHeightDp = heightInDp
                    }
                }
                .background(brush = HeaderGradientBrush)
                .padding(bottom = 22.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .adaptiveContentPadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Top App Bar
                TopAppBar(
                    modifier = Modifier.staggeredEntrance(index = 0),
                    title = {
                        Text(
                            text = "Логи",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = TextWhite,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
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
                                tint = if (isSearchActive) activeProtoColor else TextWhite,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // Copy All Logs
                        IconButton(onClick = onCopyAllLogs) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_copy),
                                contentDescription = "Скопировать все",
                                tint = TextWhite,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // Clear Logs
                        IconButton(onClick = onClearLogs) {
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
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )

                // Animated Dropdown Search Bar
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
                                text = "Поиск по тексту или тегу...",
                                color = TextMuted,
                                fontSize = 13.5.sp
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
                        shape = SearchFieldShape,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedBorderColor = activeProtoColor.copy(alpha = 0.85f),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.08f),
                            focusedTextColor = TextWhite,
                            unfocusedTextColor = TextWhite
                        )
                    )
                }

                // 4 Fixed-Width Segmented Filter Chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .staggeredEntrance(index = 1),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SegmentedFilterChip(
                        title = "Все",
                        count = totalCount,
                        isSelected = selectedLevel == null,
                        activeColor = TextWhite,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            selectedLevel = null
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    )
                    SegmentedFilterChip(
                        title = "Инфо",
                        count = infoCount,
                        isSelected = selectedLevel == LogLevel.INFO,
                        activeColor = activeProtoColor,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            selectedLevel = if (selectedLevel == LogLevel.INFO) null else LogLevel.INFO
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    )
                    SegmentedFilterChip(
                        title = "Варн",
                        count = warnCount,
                        isSelected = selectedLevel == LogLevel.WARN,
                        activeColor = WarnAccentColor,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            selectedLevel = if (selectedLevel == LogLevel.WARN) null else LogLevel.WARN
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    )
                    SegmentedFilterChip(
                        title = "Ошибки",
                        count = errorCount,
                        isSelected = selectedLevel == LogLevel.ERROR,
                        activeColor = ErrorAccentColor,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            selectedLevel = if (selectedLevel == LogLevel.ERROR) null else LogLevel.ERROR
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    )
                }
            }
        }

        // Floating "Scroll to top" pill button when scrolled down
        AnimatedVisibility(
            visible = isScrolledDown,
            enter = fadeIn(animationSpec = tween(180)) + slideInVertically(animationSpec = tween(220)) { it / 2 },
            exit = fadeOut(animationSpec = tween(150)) + slideOutVertically(animationSpec = tween(180)) { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.Black.copy(alpha = 0.88f),
                border = BorderStroke(1.dp, activeProtoColor.copy(alpha = 0.50f)),
                modifier = Modifier.springPress(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    coroutineScope.launch {
                        listState.animateScrollToItem(0)
                    }
                })
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_arrow_left),
                        contentDescription = null,
                        tint = activeProtoColor,
                        modifier = Modifier
                            .size(13.dp)
                            .graphicsLayer { rotationZ = 90f }
                    )
                    Text(
                        text = "К новым логам",
                        color = TextWhite,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Delicate Cyber Particles floating over entire logs screen interface
        CyberParticlesOverlay(
            modifier = Modifier.fillMaxSize(),
            particleCount = 42,
            alphaMultiplier = 0.70f
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
        targetValue = if (isSelected) activeColor.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.08f),
        animationSpec = tween(180),
        label = "chipBorder"
    )
    val titleColor by animateColorAsState(
        targetValue = if (isSelected) activeColor else TextWhite.copy(alpha = 0.85f),
        animationSpec = tween(180),
        label = "chipTitle"
    )

    Surface(
        shape = FilterChipShape,
        color = Color.Transparent,
        border = BorderStroke(0.75.dp, borderColor),
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

@Composable
private fun GlassLogCard(
    entry: LogEntry,
    onCopy: (LogEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    val levelColor = when (entry.level) {
        LogLevel.INFO -> ActiveGreenLed
        LogLevel.WARN -> WarnAccentColor
        LogLevel.ERROR -> ErrorAccentColor
    }

    val cardBorder = when (entry.level) {
        LogLevel.INFO -> InfoCardBorder
        LogLevel.WARN -> WarnCardBorder
        LogLevel.ERROR -> ErrorCardBorder
    }

    Surface(
        shape = LogCardShape,
        color = Color.White.copy(alpha = 0.02f),
        border = cardBorder,
        modifier = modifier
            .fillMaxWidth()
            .springPress(onClick = { onCopy(entry) })
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Header Row: Level Dot + Tag Badge & Monospace Time
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Level Indicator Dot
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(levelColor)
                    )

                    // Tag Badge
                    Surface(
                        shape = LogTagShape,
                        color = Color.Transparent,
                        border = TagBadgeBorder
                    ) {
                        Text(
                            text = entry.tag.ifBlank { "System" },
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextMuted,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.5.dp)
                        )
                    }
                }

                // Monospace Timestamp
                Text(
                    text = entry.formattedTime,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextMuted
                )
            }

            // Main Human-Readable Message
            Text(
                text = entry.humanMessage,
                color = TextWhite,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Normal,
                lineHeight = 18.5.sp
            )
        }
    }
}

@Composable
private fun Modifier.logItemCascadeEntrance(
    index: Int,
    isInitialBatch: Boolean
): Modifier {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val delayMs = if (isInitialBatch) {
            (index.coerceAtMost(8) * 45).toLong()
        } else {
            (index.coerceAtMost(3) * 35).toLong()
        }
        if (delayMs > 0) delay(delayMs)
        visible = true
    }

    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "logAlpha"
    )
    val offsetY by animateDpAsState(
        targetValue = if (visible) 0.dp else if (isInitialBatch) 22.dp else (-18).dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "logOffsetY"
    )
    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.94f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "logScale"
    )

    return this.graphicsLayer {
        this.alpha = alpha
        this.translationY = offsetY.toPx()
        this.scaleX = scale
        this.scaleY = scale
    }
}


