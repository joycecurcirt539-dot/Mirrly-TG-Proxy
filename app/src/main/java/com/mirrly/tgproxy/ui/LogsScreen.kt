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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import com.mirrly.tgproxy.MirrlyApplication
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.core.AppLogger
import com.mirrly.tgproxy.core.LogEntry
import com.mirrly.tgproxy.core.LogLevel
import com.mirrly.tgproxy.ui.theme.*
import com.mirrly.tgproxy.util.LogExportHelper

// Static UI shapes and borders cached across compositions to eliminate scroll allocation churn
private val LogCardShape = RoundedCornerShape(10.dp)
private val LogTagShape = RoundedCornerShape(4.dp)
private val FilterChipShape = RoundedCornerShape(10.dp)
private val SearchFieldShape = RoundedCornerShape(12.dp)

private val DebugCardBorder = BorderStroke(0.6.dp, Color(0xFF64B5F6).copy(alpha = 0.35f))
private val InfoCardBorder = BorderStroke(0.6.dp, Color.White.copy(alpha = 0.07f))
private val DebugAccentColor = Color(0xFF64B5F6)
private val WarnCardBorder = BorderStroke(0.6.dp, Color(0xFFF59E0B).copy(alpha = 0.35f))
private val ErrorCardBorder = BorderStroke(0.6.dp, Color(0xFFEF4444).copy(alpha = 0.45f))
private val TagBadgeBorder = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.09f))

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

    // Multi-Selection state for specific log operations
    var isSelectionMode by remember { mutableStateOf(false) }
    val selectedLogIds = remember { mutableStateListOf<Long>() }

    // Bottom Sheet visibility states
    var showExportSheet by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showTabExportSheet by remember { mutableStateOf(false) }



    // Initial entrance animation barrier
    var isInitialLoad by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(450)
        isInitialLoad = false
    }

    // AppLogger owns the single bounded history. Re-entering this screen now
    // observes that same snapshot instead of copying and replaying it locally.
    val rawLogs by AppLogger.logsFlow.collectAsState()
    val totalCount = rawLogs.size
    val debugCount = remember(rawLogs) { rawLogs.count { it.level == LogLevel.DEBUG } }
    val infoCount = remember(rawLogs) { rawLogs.count { it.level == LogLevel.INFO } }
    val warnCount = remember(rawLogs) { rawLogs.count { it.level == LogLevel.WARN } }
    val errorCount = remember(rawLogs) { rawLogs.count { it.level == LogLevel.ERROR } }
    val displayedLogs = remember(rawLogs, searchQuery, selectedLevel) {
        rawLogs.asReversed().filter { matchesFilter(it, searchQuery, selectedLevel) }
    }

    var headerHeightDp by remember { mutableStateOf(160.dp) }

    var lastObservedLogId by remember { mutableLongStateOf(rawLogs.lastOrNull()?.id ?: 0L) }
    LaunchedEffect(rawLogs.lastOrNull()?.id) {
        val newest = rawLogs.lastOrNull()
        val isNewEntry = newest != null && newest.id > lastObservedLogId
        lastObservedLogId = newest?.id ?: 0L
        val isAtTop = listState.firstVisibleItemIndex == 0 &&
            listState.firstVisibleItemScrollOffset <= 60
        if (!isSelectionMode && newest != null && isNewEntry && isAtTop && matchesFilter(newest, searchQuery, selectedLevel)) {
            listState.scrollToItem(0)
        }
    }

    LaunchedEffect(searchQuery, selectedLevel) {
        if (displayedLogs.isNotEmpty()) {
            listState.scrollToItem(0)
        }
    }

    LaunchedEffect(isSearchVisible) {
        if (isSearchVisible) {
            searchFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    val activeProtoColor = ActiveGreenLed

    val app = MirrlyApplication.instance
    val isMasterLoggingEnabled by app.prefsManager.isLogsEnabledFlow.collectAsState()
    val isDebugLevelEnabled by app.prefsManager.isLogLevelDebugFlow.collectAsState()
    val isInfoLevelEnabled by app.prefsManager.isLogLevelInfoFlow.collectAsState()
    val isWarnLevelEnabled by app.prefsManager.isLogLevelWarnFlow.collectAsState()
    val isErrorLevelEnabled by app.prefsManager.isLogLevelErrorFlow.collectAsState()

    val activeTabName = when (selectedLevel) {
        LogLevel.DEBUG -> "Debug"
        LogLevel.INFO -> "Info"
        LogLevel.WARN -> "Warn"
        LogLevel.ERROR -> "Error"
        null -> stringResource(R.string.logs_filter_all)
    }

    val activeTabColor = when (selectedLevel) {
        LogLevel.DEBUG -> DebugAccentColor
        LogLevel.INFO -> activeProtoColor
        LogLevel.WARN -> WarnAccentColor
        LogLevel.ERROR -> ErrorAccentColor
        null -> TextWhite
    }

    val isCurrentTabRecordingEnabled = when (selectedLevel) {
        LogLevel.DEBUG -> isDebugLevelEnabled
        LogLevel.INFO -> isInfoLevelEnabled
        LogLevel.WARN -> isWarnLevelEnabled
        LogLevel.ERROR -> isErrorLevelEnabled
        null -> isMasterLoggingEnabled
    }

    val onToggleCurrentTabRecording: () -> Unit = {
        when (selectedLevel) {
            LogLevel.DEBUG -> {
                val next = !isDebugLevelEnabled
                app.prefsManager.setLogLevelEnabled(LogLevel.DEBUG, next)
                Toast.makeText(
                    context,
                    context.getString(if (next) R.string.logs_tab_toast_enabled_format else R.string.logs_tab_toast_disabled_format, "Debug"),
                    Toast.LENGTH_SHORT
                ).show()
            }
            LogLevel.INFO -> {
                val next = !isInfoLevelEnabled
                app.prefsManager.setLogLevelEnabled(LogLevel.INFO, next)
                Toast.makeText(
                    context,
                    context.getString(if (next) R.string.logs_tab_toast_enabled_format else R.string.logs_tab_toast_disabled_format, "Info"),
                    Toast.LENGTH_SHORT
                ).show()
            }
            LogLevel.WARN -> {
                val next = !isWarnLevelEnabled
                app.prefsManager.setLogLevelEnabled(LogLevel.WARN, next)
                Toast.makeText(
                    context,
                    context.getString(if (next) R.string.logs_tab_toast_enabled_format else R.string.logs_tab_toast_disabled_format, "Warn"),
                    Toast.LENGTH_SHORT
                ).show()
            }
            LogLevel.ERROR -> {
                val next = !isErrorLevelEnabled
                app.prefsManager.setLogLevelEnabled(LogLevel.ERROR, next)
                Toast.makeText(
                    context,
                    context.getString(if (next) R.string.logs_tab_toast_enabled_format else R.string.logs_tab_toast_disabled_format, "Error"),
                    Toast.LENGTH_SHORT
                ).show()
            }
            null -> {
                val next = !isMasterLoggingEnabled
                app.prefsManager.setLogsEnabled(next)
                Toast.makeText(
                    context,
                    context.getString(if (next) R.string.logs_tab_toast_enabled_format else R.string.logs_tab_toast_disabled_format, context.getString(R.string.logs_filter_all)),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    val currentTabLogs = remember(rawLogs, selectedLevel) {
        if (selectedLevel == null) rawLogs.toList()
        else rawLogs.filter { it.level == selectedLevel }
    }

    // Derived states to prevent unnecessary recompositions
    val isListEmpty by remember { derivedStateOf { displayedLogs.isEmpty() } }
    val isSearchActive by remember { derivedStateOf { isSearchVisible || searchQuery.isNotEmpty() } }
    val isFiltered by remember { derivedStateOf { isSearchActive || selectedLevel != null } }
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
            Toast.makeText(context, context.getString(R.string.logs_entry_copied), Toast.LENGTH_SHORT).show()
        }
    }

    val onClearLogs: () -> Unit = remember(context, haptic) {
        {
            AppLogger.clear()
            selectedLogIds.clear()
            isSelectionMode = false
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            Toast.makeText(context, context.getString(R.string.logs_cleared), Toast.LENGTH_SHORT).show()
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
                        text = if (searchQuery.isNotEmpty()) stringResource(R.string.logs_events_not_found) else stringResource(R.string.logs_empty),
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
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + if (isSelectionMode) 84.dp else 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                itemsIndexed(
                    items = displayedLogs,
                    key = { _, entry -> entry.id }
                ) { index, entry ->
                    val isSelected = selectedLogIds.contains(entry.id)
                    GlassLogCard(
                        entry = entry,
                        isSelectionMode = isSelectionMode,
                        isSelected = isSelected,
                        onToggleSelect = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            if (isSelected) {
                                selectedLogIds.remove(entry.id)
                            } else {
                                selectedLogIds.add(entry.id)
                            }
                        },
                        onCopy = onCopyLog,
                        onStartSelection = {
                            isSelectionMode = true
                            if (!selectedLogIds.contains(entry.id)) {
                                selectedLogIds.add(entry.id)
                            }
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        modifier = Modifier.logItemCascadeEntrance(
                            index = index,
                            isInitialBatch = isInitialLoad
                        )
                    )
                }
            }
        }

        // 2. PINNED FROSTED GLASS HEADER (Title Bar + Selection State + Filter Chips)
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
                // Top App Bar: switches between standard mode and selection mode
                if (isSelectionMode) {
                    TopAppBar(
                        modifier = Modifier.staggeredEntrance(index = 0),
                        title = {
                            Text(
                                text = stringResource(R.string.logs_selected_count_format, selectedLogIds.size),
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp,
                                color = TextWhite,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                isSelectionMode = false
                                selectedLogIds.clear()
                            }) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_close),
                                    contentDescription = stringResource(R.string.action_cancel),
                                    tint = TextWhite,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        },
                        actions = {
                            // Select all displayed logs
                            TextButton(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                val allDisplayedIds = displayedLogs.map { it.id }
                                selectedLogIds.clear()
                                selectedLogIds.addAll(allDisplayedIds)
                            }) {
                                Text(
                                    text = stringResource(R.string.logs_action_select_all),
                                    color = activeProtoColor,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            // Deselect all
                            TextButton(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                selectedLogIds.clear()
                            }) {
                                Text(
                                    text = stringResource(R.string.logs_action_deselect_all),
                                    color = TextMuted,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }

                            // Invert selection
                            TextButton(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                val currentSet = selectedLogIds.toSet()
                                val inverted = displayedLogs.map { it.id }.filter { it !in currentSet }
                                selectedLogIds.clear()
                                selectedLogIds.addAll(inverted)
                            }) {
                                Text(
                                    text = stringResource(R.string.logs_action_invert_selection),
                                    color = TextWhite,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                    )
                } else {
                    TopAppBar(
                        modifier = Modifier.staggeredEntrance(index = 0),
                        title = {
                            Text(
                                text = stringResource(R.string.logs_title),
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
                                    contentDescription = stringResource(R.string.action_back),
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
                                    contentDescription = stringResource(R.string.action_search),
                                    tint = if (isSearchActive) activeProtoColor else TextWhite,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // Enter Multi-Selection Mode
                            IconButton(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                isSelectionMode = true
                            }) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_check),
                                    contentDescription = stringResource(R.string.logs_action_select),
                                    tint = TextWhite,
                                    modifier = Modifier.size(19.dp)
                                )
                            }

                            // Open Export Bottom Sheet
                            IconButton(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                showExportSheet = true
                            }) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_export),
                                    contentDescription = stringResource(R.string.logs_action_export),
                                    tint = TextWhite,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // Clear Logs
                            IconButton(onClick = onClearLogs) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_trash),
                                    contentDescription = stringResource(R.string.logs_action_clear),
                                    tint = TextWhite,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // Log Journal & Performance Settings
                            IconButton(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                showSettingsSheet = true
                            }) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_settings),
                                    contentDescription = stringResource(R.string.logs_settings_sheet_title),
                                    tint = TextWhite,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                    )
                }

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
                                text = stringResource(R.string.logs_search_placeholder),
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
                        title = stringResource(R.string.logs_filter_all),
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
                        title = stringResource(R.string.logs_filter_debug),
                        count = debugCount,
                        isSelected = selectedLevel == LogLevel.DEBUG,
                        activeColor = DebugAccentColor,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            selectedLevel = if (selectedLevel == LogLevel.DEBUG) null else LogLevel.DEBUG
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    )
                    SegmentedFilterChip(
                        title = stringResource(R.string.logs_filter_info),
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
                        title = stringResource(R.string.logs_filter_warn),
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
                        title = stringResource(R.string.logs_filter_error),
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

                // Section Tab Action & Control Banner ("Плашка")
                if (!isSelectionMode) {
                    TabLogControlBanner(
                        tabName = activeTabName,
                        tabColor = activeTabColor,
                        isRecordingEnabled = isCurrentTabRecordingEnabled,
                        onToggleRecording = onToggleCurrentTabRecording,
                        onOpenExport = { showTabExportSheet = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .staggeredEntrance(index = 2)
                    )
                }
            }
        }

        // 3. MULTI-SELECTION STICKY ACTION DOCK
        AnimatedVisibility(
            visible = isSelectionMode,
            enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it },
            exit = fadeOut(tween(150)) + slideOutVertically(tween(180)) { it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 14.dp,
                    start = 16.dp,
                    end = 16.dp
                )
                .adaptiveContainerWidth(440.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = AmoledBackground.copy(alpha = 0.88f),
                border = BorderStroke(1.dp, activeProtoColor.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Copy Selected Entries Button
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.Transparent,
                        border = BorderStroke(
                            1.dp,
                            if (selectedLogIds.isNotEmpty()) TextWhite.copy(alpha = 0.35f) else AmoledBorder
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                            .springPress(
                                enabled = selectedLogIds.isNotEmpty(),
                                onClick = {
                                    val selectedEntries = rawLogs.filter { selectedLogIds.contains(it.id) }
                                    val count = LogExportHelper.copyToClipboard(context, selectedEntries)
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.logs_copied_count_format, count),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    isSelectionMode = false
                                    selectedLogIds.clear()
                                }
                            )
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_copy),
                                contentDescription = null,
                                tint = if (selectedLogIds.isNotEmpty()) TextWhite else TextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.logs_action_copy_selected_format, selectedLogIds.size),
                                color = if (selectedLogIds.isNotEmpty()) TextWhite else TextMuted,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // Export Selected Entries to File Button
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.Transparent,
                        border = BorderStroke(
                            1.dp,
                            if (selectedLogIds.isNotEmpty()) activeProtoColor.copy(alpha = 0.85f) else AmoledBorder
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                            .springPress(
                                enabled = selectedLogIds.isNotEmpty(),
                                onClick = {
                                    val selectedEntries = rawLogs.filter { selectedLogIds.contains(it.id) }
                                    val file = LogExportHelper.createLogFile(context, selectedEntries, "mirrly_logs_selected")
                                    if (file != null) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        Toast.makeText(context, context.getString(R.string.logs_export_file_created), Toast.LENGTH_SHORT).show()
                                        LogExportHelper.shareLogFile(context, file)
                                        isSelectionMode = false
                                        selectedLogIds.clear()
                                    } else {
                                        Toast.makeText(context, context.getString(R.string.logs_export_failed), Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_export),
                                contentDescription = null,
                                tint = if (selectedLogIds.isNotEmpty()) activeProtoColor else TextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.logs_action_export_selected_format, selectedLogIds.size),
                                color = if (selectedLogIds.isNotEmpty()) activeProtoColor else TextMuted,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // 4. FLOATING "SCROLL TO TOP" PILL BUTTON (Shown when not in selection mode and scrolled down)
        AnimatedVisibility(
            visible = isScrolledDown && !isSelectionMode,
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
                        text = stringResource(R.string.logs_scroll_to_new),
                        color = TextWhite,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // 5. CYBER PARTICLES OVERLAY
        CyberParticlesOverlay(
            modifier = Modifier.fillMaxSize(),
            particleCount = 42,
            alphaMultiplier = 0.70f
        )

        // 6. BOTTOM SHEETS (Zero-Popup architecture)
        if (showExportSheet) {
            LogExportBottomSheet(
                onDismissRequest = { showExportSheet = false },
                allLogs = rawLogs,
                displayedLogs = displayedLogs,
                isFiltered = isFiltered,
                onStartSelectionMode = { isSelectionMode = true }
            )
        }

        if (showSettingsSheet) {
            LogSettingsBottomSheet(
                onDismissRequest = { showSettingsSheet = false }
            )
        }

        if (showTabExportSheet) {
            TabLogExportBottomSheet(
                levelName = activeTabName,
                levelColor = activeTabColor,
                logs = currentTabLogs,
                onDismissRequest = { showTabExportSheet = false }
            )
        }
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
private fun TabLogControlBanner(
    tabName: String,
    tabColor: Color,
    isRecordingEnabled: Boolean,
    onToggleRecording: () -> Unit,
    onOpenExport: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current

    val borderColor by animateColorAsState(
        targetValue = if (isRecordingEnabled) AmoledBorder else WarnAccentColor.copy(alpha = 0.65f),
        animationSpec = tween(200),
        label = "bannerBorder"
    )

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = AmoledBackground.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isRecordingEnabled) tabColor else WarnAccentColor)
                )
                Text(
                    text = if (isRecordingEnabled) {
                        stringResource(R.string.logs_tab_banner_recording_active)
                    } else {
                        stringResource(R.string.logs_tab_banner_recording_disabled)
                    },
                    color = if (isRecordingEnabled) TextWhite else WarnAccentColor,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Toggle Button: "Не формировать" / "Включить"
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isRecordingEnabled) Color.Transparent else tabColor.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, if (isRecordingEnabled) AmoledBorder else tabColor),
                    modifier = Modifier
                        .height(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onToggleRecording()
                        })
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isRecordingEnabled) {
                                stringResource(R.string.logs_tab_banner_btn_disable)
                            } else {
                                stringResource(R.string.logs_tab_banner_btn_enable)
                            },
                            color = if (isRecordingEnabled) TextMuted else tabColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Copy/Export Button
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, tabColor.copy(alpha = 0.65f)),
                    modifier = Modifier
                        .height(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onOpenExport()
                        })
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_export),
                            contentDescription = null,
                            tint = tabColor,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = stringResource(R.string.logs_tab_banner_btn_copy),
                            color = TextWhite,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassLogCard(
    entry: LogEntry,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onCopy: (LogEntry) -> Unit,
    onStartSelection: (LogEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    val levelColor = when (entry.level) {
        LogLevel.DEBUG -> DebugAccentColor
        LogLevel.INFO -> ActiveGreenLed
        LogLevel.WARN -> WarnAccentColor
        LogLevel.ERROR -> ErrorAccentColor
    }

    val cardBorder = when {
        isSelectionMode && isSelected -> BorderStroke(1.dp, ActiveGreenLed.copy(alpha = 0.85f))
        isSelectionMode -> BorderStroke(0.6.dp, AmoledBorder)
        entry.level == LogLevel.DEBUG -> DebugCardBorder
        entry.level == LogLevel.INFO -> InfoCardBorder
        entry.level == LogLevel.WARN -> WarnCardBorder
        entry.level == LogLevel.ERROR -> ErrorCardBorder
        else -> InfoCardBorder
    }

    val cardBackground = if (isSelectionMode && isSelected) {
        ActiveGreenLed.copy(alpha = 0.05f)
    } else {
        Color.White.copy(alpha = 0.02f)
    }

    var isPressed by remember { mutableStateOf(false) }
    val cardScale by animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "logCardScale"
    )

    Surface(
        shape = LogCardShape,
        color = cardBackground,
        border = cardBorder,
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = cardScale
                scaleY = cardScale
            }
            .pointerInput(entry.id, isSelectionMode) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        try {
                            awaitRelease()
                        } finally {
                            isPressed = false
                        }
                    },
                    onTap = {
                        if (isSelectionMode) {
                            onToggleSelect()
                        } else {
                            onCopy(entry)
                        }
                    },
                    onLongPress = {
                        if (!isSelectionMode) {
                            onStartSelection(entry)
                        } else {
                            onToggleSelect()
                        }
                    }
                )
            }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Selection indicator circle when multi-selection mode is active
            if (isSelectionMode) {
                Surface(
                    shape = CircleShape,
                    color = if (isSelected) ActiveGreenLed.copy(alpha = 0.22f) else Color.Transparent,
                    border = BorderStroke(1.dp, if (isSelected) ActiveGreenLed else AmoledBorder),
                    modifier = Modifier.size(18.dp)
                ) {
                    if (isSelected) {
                        Box(contentAlignment = Alignment.Center) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(ActiveGreenLed)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Header Row: Level Dot + Tag Badge & Monospace Time
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        // Level Indicator Dot
                        Box(
                            modifier = Modifier
                                .size(4.5.dp)
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
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextMuted,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }

                    // Monospace Timestamp
                    Text(
                        text = entry.formattedTime,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextMuted
                    )
                }

                // Main Human-Readable Message
                Text(
                    text = entry.humanMessage,
                    color = TextWhite,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Normal,
                    lineHeight = 16.5.sp
                )
            }
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
