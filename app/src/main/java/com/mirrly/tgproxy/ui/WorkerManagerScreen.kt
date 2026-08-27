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

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
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
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.core.DomainFormatStatus
import com.mirrly.tgproxy.core.TgConstants
import com.mirrly.tgproxy.core.WorkerDomainNormalizer
import com.mirrly.tgproxy.core.WorkerProfile
import com.mirrly.tgproxy.core.WorkerStatus
import com.mirrly.tgproxy.service.PreferencesManager
import com.mirrly.tgproxy.service.WorkerPingTester
import com.mirrly.tgproxy.ui.theme.*
import kotlinx.coroutines.launch

enum class ManagerSection(val title: String) {
    WORKERS("Воркеры"),
    SHARE("Поделиться"),
    SCANNER("Сканер"),
    GUIDE("Инструкция")
}

private enum class WmWorkerFilterType {
    ALL,
    DEVELOPER,
    CUSTOM
}

private enum class WmGuideTab(val title: String) {
    PC("Компьютер"),
    PHONE("Андроид"),
    SCRIPT("Скрипт воркера"),
    FAQ("Преимущества и FAQ")
}

private data class WmGuideStepItem(
    val stepNumber: String,
    val title: String,
    val description: String,
    val actionText: String? = null,
    val isCopyAction: Boolean = false,
    val isDashAction: Boolean = false
)

private data class WmFaqItem(
    val title: String,
    val description: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkerManagerScreen(
    prefs: PreferencesManager,
    onBack: () -> Unit,
    initialSection: ManagerSection = ManagerSection.WORKERS,
    onOpenAnalytics: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current

    val isSocks5 by prefs.isSocks5Flow.collectAsState(initial = false)
    val protoColors = LocalProtocolColors.current
    val activeProtoColor = protoColors.primary

    var currentSection by remember(initialSection) { mutableStateOf(initialSection) }

    // Workers State
    val activeWorkerId by prefs.activeWorkerIdFlow.collectAsState()
    var customWorkers by remember { mutableStateOf(prefs.getCustomWorkers()) }
    val devWorkers = remember { prefs.getDeveloperWorkers() }

    LaunchedEffect(Unit) {
        customWorkers = prefs.getCustomWorkers()
    }

    val pingResults = remember { mutableStateMapOf<String, Pair<WorkerStatus, Long?>>() }
    var isPinging by remember { mutableStateOf(false) }

    var selectedFilter by remember { mutableStateOf(WmWorkerFilterType.ALL) }
    var isSearchVisible by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val searchFocusRequester = remember { FocusRequester() }

    var showAddDialog by remember { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }
    var prefillDomain by remember { mutableStateOf("") }
    var prefillName by remember { mutableStateOf("") }
    var selectedShareWorker by remember { mutableStateOf<WorkerProfile?>(null) }
    var workerToDelete by remember { mutableStateOf<WorkerProfile?>(null) }

    // Guide State
    var selectedGuideTab by remember { mutableStateOf(WmGuideTab.PC) }
    var showDashboardConfirmDialog by remember { mutableStateOf(false) }
    var showDeployScriptConfirmDialog by remember { mutableStateOf(false) }

    var headerHeightDp by remember { mutableStateOf(210.dp) }

    val filterTypes = remember { listOf(WmWorkerFilterType.ALL, WmWorkerFilterType.DEVELOPER, WmWorkerFilterType.CUSTOM) }
    val allGuideTabs = remember { listOf(WmGuideTab.PC, WmGuideTab.PHONE, WmGuideTab.SCRIPT, WmGuideTab.FAQ) }

    fun handleDismiss() {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onBack()
    }

    fun copyDeployCommandToClipboard() {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val cmd = "irm https://raw.githubusercontent.com/joycecurcirt539-dot/Mirrly-TG-Proxy/main/tools/deploy-worker/deploy.ps1 | iex"
        val clip = ClipData.newPlainText("Mirrly Deploy Command", cmd)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Команда автодеплоя скопирована в буфер обмена", Toast.LENGTH_SHORT).show()
    }

    fun copyScriptToClipboard() {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Cloudflare Worker Script", TgConstants.CLOUDFLARE_WORKER_JS_CODE)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Скрипт воркера скопирован в буфер обмена", Toast.LENGTH_SHORT).show()
    }

    fun openCloudflareDashboard() {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        showDashboardConfirmDialog = true
    }

    fun switchToNextSubTab() {
        if (currentSection == ManagerSection.WORKERS) {
            val currentIndex = filterTypes.indexOf(selectedFilter)
            if (currentIndex < filterTypes.size - 1) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                selectedFilter = filterTypes[currentIndex + 1]
            } else {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                currentSection = ManagerSection.SHARE
            }
        } else if (currentSection == ManagerSection.SHARE) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            currentSection = ManagerSection.SCANNER
        } else if (currentSection == ManagerSection.SCANNER) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            currentSection = ManagerSection.GUIDE
            selectedGuideTab = WmGuideTab.PC
        } else {
            val currentIndex = allGuideTabs.indexOf(selectedGuideTab)
            if (currentIndex < allGuideTabs.size - 1) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                selectedGuideTab = allGuideTabs[currentIndex + 1]
            }
        }
    }

    fun switchToPreviousSubTab() {
        if (currentSection == ManagerSection.WORKERS) {
            val currentIndex = filterTypes.indexOf(selectedFilter)
            if (currentIndex > 0) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                selectedFilter = filterTypes[currentIndex - 1]
            }
        } else if (currentSection == ManagerSection.SHARE) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            currentSection = ManagerSection.WORKERS
        } else if (currentSection == ManagerSection.SCANNER) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            currentSection = ManagerSection.SHARE
        } else {
            val currentIndex = allGuideTabs.indexOf(selectedGuideTab)
            if (currentIndex > 0) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                selectedGuideTab = allGuideTabs[currentIndex - 1]
            } else {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                currentSection = ManagerSection.SCANNER
            }
        }
    }

    fun refreshWorkers() {
        customWorkers = prefs.getCustomWorkers()
    }

    fun startPingAll() {
        if (isPinging) return
        isPinging = true
        scope.launch {
            val all = devWorkers + customWorkers
            for (w in all) {
                val res = WorkerPingTester.pingWorker(w.domain)
                pingResults[w.domain] = res
            }
            isPinging = false
            Toast.makeText(context, "Замер пинга завершен", Toast.LENGTH_SHORT).show()
        }
    }

    fun shareWorker(worker: WorkerProfile) {
        val encodedDomain = Uri.encode(worker.domain)
        val encodedName = Uri.encode(worker.name)
        val deepLink = "mirrly://worker?domain=$encodedDomain&name=$encodedName"
        val httpsLink = "https://mirrly.app/worker?domain=$encodedDomain&name=$encodedName"

        val shareText = buildString {
            append("С тобой поделились подключением Cloudflare Worker для Mirrly TG Proxy:\n\n")
            append("Имя узла: ${worker.name}\n")
            append("Домен: ${worker.domain}\n\n")
            append("Ссылка для импорта в приложение:\n")
            append("$deepLink\n\n")
            append("Веб-ссылка:\n")
            append("$httpsLink\n\n")
            append("Скачать актуальную сборку Mirrly TG Proxy:\n")
            append("https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/releases")
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
            putExtra(Intent.EXTRA_SUBJECT, "Cloudflare Worker для Mirrly TG Proxy")
        }
        context.startActivity(Intent.createChooser(intent, "Поделиться воркером"))
    }

    val hasRateLimitedWorkers by remember {
        derivedStateOf {
            pingResults.values.any { it.first == WorkerStatus.RATE_LIMITED_429 }
        }
    }

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
                WmWorkerFilterType.ALL -> true
                WmWorkerFilterType.DEVELOPER -> worker.isDeveloperWorker
                WmWorkerFilterType.CUSTOM -> !worker.isDeveloperWorker
            }
            val matchesQuery = if (trimmed.isEmpty()) true else {
                worker.name.contains(trimmed, ignoreCase = true) ||
                        worker.domain.contains(trimmed, ignoreCase = true)
            }
            matchesFilter && matchesQuery
        }
    }

    // Guide Step Items
    val pcSteps = remember {
        listOf(
            WmGuideStepItem(
                stepNumber = "1",
                title = "Вход в Cloudflare Dashboard",
                description = "Откройте браузер на компьютере и перейдите на dash.cloudflare.com. Авторизуйтесь или создайте бесплатный аккаунт (банковская карта не требуется).",
                actionText = "Открыть Cloudflare Dashboard",
                isDashAction = true
            ),
            WmGuideStepItem(
                stepNumber = "2",
                title = "Создание нового Worker",
                description = "В левом боковом меню выберите раздел «Workers & Pages» (или «Compute (Workers)»). Нажмите синюю кнопку «Create Application», затем вкладку «Create Worker»."
            ),
            WmGuideStepItem(
                stepNumber = "3",
                title = "Базовое развертывание",
                description = "В поле имени укажите любое название (например: my-tg-proxy) и нажмите кнопку «Deploy» внизу страницы."
            ),
            WmGuideStepItem(
                stepNumber = "4",
                title = "Вставка готового скрипта",
                description = "На открывшейся странице созданного воркера нажмите кнопку «Edit Code» (Редактировать код). Полностью удалите стандартный шаблонный код из окна редактора.",
                actionText = "Скопировать скрипт воркера",
                isCopyAction = true
            ),
            WmGuideStepItem(
                stepNumber = "5",
                title = "Сохранение и публикация",
                description = "Вставьте скопированный код в редактор Cloudflare и в правом верхнем углу нажмите «Deploy» (или «Save and Deploy»)."
            ),
            WmGuideStepItem(
                stepNumber = "6",
                title = "Копирование адреса и вставка в Mirrly",
                description = "Скопируйте полученный публичный адрес (например: my-tg-proxy.yourname.workers.dev) и добавьте его в Менеджере воркеров приложения Mirrly TG Proxy."
            )
        )
    }

    val phoneSteps = remember {
        listOf(
            WmGuideStepItem(
                stepNumber = "1",
                title = "Откройте сайт Cloudflare на смартфоне",
                description = "Перейдите на dash.cloudflare.com в браузере телефона и войдите в свой аккаунт.",
                actionText = "Перейти на Cloudflare",
                isDashAction = true
            ),
            WmGuideStepItem(
                stepNumber = "2",
                title = "Перейдите в Workers & Pages",
                description = "В боковом меню выберите «Workers & Pages» -> нажмите «Create Application» -> «Create Worker»."
            ),
            WmGuideStepItem(
                stepNumber = "3",
                title = "Нажмите Deploy и Edit Code",
                description = "Нажмите кнопку «Deploy», затем «Edit Code» для открытия онлайн-редактора кода."
            ),
            WmGuideStepItem(
                stepNumber = "4",
                title = "Скопируйте и вставьте скрипт",
                description = "Нажмите кнопку ниже, чтобы скопировать скрипт, выделите весь текст в мобильном редакторе и вставьте скопированный код.",
                actionText = "Скопировать скрипт",
                isCopyAction = true
            ),
            WmGuideStepItem(
                stepNumber = "5",
                title = "Сохраните и вставьте домен в Mirrly",
                description = "Нажмите «Deploy». Скопируйте домен *.workers.dev и добавьте его в Менеджере воркеров приложения Mirrly."
            )
        )
    }

    val faqItems = remember {
        listOf(
            WmFaqItem(
                title = "100 000 бесплатных запросов каждый день",
                description = "Бесплатный тариф Cloudflare выделяет 100 000 обращений в сутки лично на ваш аккаунт, чего с избытком хватает для непрерывной переписки, видеозвонков и загрузки медиа."
            ),
            WmFaqItem(
                title = "Создание нескольких личных воркеров (до 100 узлов)",
                description = "Вы можете бесплатно создать до 100 отдельных воркеров на одном аккаунте (например: для смартфона, ноутбука, планшета или близких), добавить их все в Менеджер воркеров Mirrly и переключаться между ними в 1 клик."
            ),
            WmFaqItem(
                title = "100% Приватность и собственный шлюз",
                description = "Трафик не проходит через чужие прокси-серверы. Ваш личный воркер открывает сокеты напрямую к Telegram DC через глобальную сеть Cloudflare Anycast (300+ дата-центров)."
            ),
            WmFaqItem(
                title = "Работа звонков и аудио/видео (SOCKS5)",
                description = "Благодаря API cloudflare:sockets личный воркер поддерживает универсальный TCP-туннель к Telegram VoIP узлам, обеспечивая стабильную работу звонков без системного VPN."
            ),
            WmFaqItem(
                title = "Что делать, если Telegram долго подключается через воркер?",
                description = "В Cloudflare Dashboard откройте ваш воркер -> Settings -> Runtime. Убедитесь, что Compatibility Date установлена не ранее 2023-05-18 и включена опция Node.js compatibility (флаг nodejs_compat)."
            ),
            WmFaqItem(
                title = "Автоматический приоритет в приложении",
                description = "При добавлении и выборе своего воркера приложение автоматически направляет весь трафик SOCKS5 и MTProto через ваш узел с наивысшим приоритетом."
            )
        )
    }

    val workersListState = rememberLazyListState()
    val guideListState = rememberLazyListState()

    LaunchedEffect(selectedFilter) {
        if (workersListState.firstVisibleItemIndex > 0 || workersListState.firstVisibleItemScrollOffset > 0) {
            workersListState.scrollToItem(0)
        }
    }

    LaunchedEffect(selectedGuideTab) {
        if (guideListState.firstVisibleItemIndex > 0 || guideListState.firstVisibleItemScrollOffset > 0) {
            guideListState.scrollToItem(0)
        }
    }

    val currentOnNextTab by rememberUpdatedState(::switchToNextSubTab)
    val currentOnPrevTab by rememberUpdatedState(::switchToPreviousSubTab)
    val currentHandleDismiss by rememberUpdatedState(::handleDismiss)

    val nestedScrollConnection = remember(density) {
        object : NestedScrollConnection {
            private var overscrollY = 0f
            private val thresholdPx = with(density) { 48.dp.toPx() }

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (overscrollY > 0f && available.y < 0f) {
                    val consumedY = available.y.coerceAtLeast(-overscrollY)
                    overscrollY += consumedY
                    return Offset(0f, consumedY)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (source == NestedScrollSource.Drag) {
                    if (available.y > 0f) {
                        overscrollY += available.y
                        if (overscrollY > thresholdPx) {
                            overscrollY = 0f
                            currentHandleDismiss()
                        }
                        return Offset(0f, available.y)
                    } else if (available.y < -thresholdPx) {
                        overscrollY = 0f
                        currentHandleDismiss()
                    }
                }
                return Offset.Zero
            }
        }
    }

    if (showDashboardConfirmDialog) {
        ExternalLinkConfirmDialog(
            url = "https://dash.cloudflare.com/",
            title = "Панель Cloudflare Dashboard",
            description = "Ссылка ведет на официальную веб-панель управления Cloudflare (dash.cloudflare.com) для создания и редактирования скрипта Worker.",
            onDismiss = { showDashboardConfirmDialog = false }
        )
    }

    if (showDeployScriptConfirmDialog) {
        ExternalLinkConfirmDialog(
            url = "https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/tree/main/tools/deploy-worker",
            title = "Скрипт автодеплоя на GitHub",
            description = "Ссылка ведет на репозиторий со скриптом автоматического создания воркера для PowerShell и Bash.",
            onDismiss = { showDeployScriptConfirmDialog = false }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                val touchSlop = viewConfiguration.touchSlop
                val thresholdPx = 36.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var totalX = 0f
                    var totalY = 0f
                    var isHorizontalLocked = false
                    var isVerticalLocked = false
                    val pointerId = down.id

                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                        if (!change.pressed) break

                        val positionChange = change.positionChange()
                        val dx = positionChange.x
                        val dy = positionChange.y

                        if (!isHorizontalLocked && !isVerticalLocked) {
                            totalX += dx
                            totalY += dy
                            val absX = kotlin.math.abs(totalX)
                            val absY = kotlin.math.abs(totalY)

                            if (absX > touchSlop || absY > touchSlop) {
                                if (absX > absY) {
                                    isHorizontalLocked = true
                                    change.consume()
                                } else {
                                    isVerticalLocked = true
                                    // Lock to vertical - let list scroll / pull down handle this gesture exclusively
                                    break
                                }
                            }
                        } else if (isHorizontalLocked) {
                            totalX += dx
                            change.consume()
                        }
                    }

                    if (isHorizontalLocked) {
                        if (totalX < -thresholdPx) {
                            currentOnNextTab()
                        } else if (totalX > thresholdPx) {
                            currentOnPrevTab()
                        }
                    }
                }
            }
    ) {
        // Delicate Cyber Particles floating in background behind worker manager interface
        CyberParticlesOverlay(
            modifier = Modifier.fillMaxSize(),
            particleCount = 14,
            alphaMultiplier = 0.50f
        )

        // 1. SCROLLABLE BODY CONTENT (AnimatedContent between WORKERS, SHARE, SCANNER and GUIDE)
        AnimatedContent(
            targetState = currentSection,
            transitionSpec = {
                val fromIndex = ManagerSection.values().indexOf(initialState)
                val toIndex = ManagerSection.values().indexOf(targetState)
                if (toIndex >= fromIndex) {
                    (slideInHorizontally(
                        animationSpec = spring(dampingRatio = 0.88f, stiffness = Spring.StiffnessMediumLow),
                        initialOffsetX = { fullWidth -> (fullWidth * 0.28f).toInt() }
                    ) + fadeIn(animationSpec = tween(220))).togetherWith(
                        slideOutHorizontally(
                            animationSpec = spring(dampingRatio = 0.88f, stiffness = Spring.StiffnessMediumLow),
                            targetOffsetX = { fullWidth -> (-fullWidth * 0.28f).toInt() }
                        ) + fadeOut(animationSpec = tween(180))
                    )
                } else {
                    (slideInHorizontally(
                        animationSpec = spring(dampingRatio = 0.88f, stiffness = Spring.StiffnessMediumLow),
                        initialOffsetX = { fullWidth -> (-fullWidth * 0.28f).toInt() }
                    ) + fadeIn(animationSpec = tween(220))).togetherWith(
                        slideOutHorizontally(
                            animationSpec = spring(dampingRatio = 0.88f, stiffness = Spring.StiffnessMediumLow),
                            targetOffsetX = { fullWidth -> (fullWidth * 0.28f).toInt() }
                        ) + fadeOut(animationSpec = tween(180))
                    )
                }
            },
            label = "managerBodySection"
        ) { section ->
            when (section) {
                ManagerSection.WORKERS -> {
                    if (filteredWorkers.isEmpty()) {
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
                                if (selectedFilter == WmWorkerFilterType.CUSTOM && customWorkers.isEmpty()) {
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
                            state = workersListState,
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
                            // Warning Card for 429 Rate Limit
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
                            ) { _, worker ->
                                val isActive = worker.id == activeWorkerId
                                GlassWorkerCard(
                                    worker = worker,
                                    isActive = isActive,
                                    activeAccentColor = activeProtoColor,
                                    pingInfo = pingResults[worker.domain],
                                    onSelect = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        prefs.setActiveWorkerId(worker.id)
                                        Toast.makeText(context, "Активирован: ${worker.name}", Toast.LENGTH_SHORT).show()
                                    },
                                    onShare = if (!worker.isDeveloperWorker) {
                                        {
                                            selectedShareWorker = worker
                                            currentSection = ManagerSection.SHARE
                                        }
                                    } else null,
                                    onDelete = if (!worker.isDeveloperWorker) {
                                        {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            workerToDelete = worker
                                        }
                                    } else null
                                )
                            }

                            // App Links Info Banner
                            item(key = "deep_link_perm_info") {
                                Surface(
                                    shape = RoundedCornerShape(13.dp),
                                    color = Color.White.copy(alpha = 0.03f),
                                    border = BorderStroke(1.dp, Color(0xFF1E283D)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp, bottom = 4.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                painter = painterResource(id = R.drawable.ic_diag_protocol),
                                                contentDescription = null,
                                                tint = activeProtoColor,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text(
                                                text = "Импорт узлов по ссылке",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.5.sp,
                                                color = TextWhite
                                            )
                                        }
                                        Text(
                                            text = "Если вы переходите по ссылке с воркером, но она открывается в браузере, а не в приложении, разрешите Mirrly TG Proxy открывать поддерживаемые ссылки по умолчанию в настройках Android.",
                                            fontSize = 11.5.sp,
                                            color = TextMuted,
                                            lineHeight = 15.5.sp
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(9.dp),
                                            color = activeProtoColor.copy(alpha = 0.12f),
                                            border = BorderStroke(1.dp, activeProtoColor.copy(alpha = 0.40f)),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(32.dp)
                                                .clip(RoundedCornerShape(9.dp))
                                                .springPress(onClick = {
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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
                }
                ManagerSection.SHARE -> {
                    ShareWorkerContent(
                        workers = allWorkers,
                        activeWorkerId = activeWorkerId,
                        activeAccentColor = activeProtoColor,
                        selectedWorker = selectedShareWorker,
                        onSelectWorker = { selectedShareWorker = it },
                        onShare = { shareWorker(it) },
                        onAddWorkerClick = {
                            prefillDomain = ""
                            prefillName = ""
                            showAddDialog = true
                        },
                        headerPadding = headerHeightDp
                    )
                }
                ManagerSection.SCANNER -> {
                    ScannerWorkerContent(
                        activeAccentColor = activeProtoColor,
                        onQrScanned = { rawScanned ->
                            val parsed = WorkerDomainNormalizer.normalize(rawScanned)
                            if (parsed.isValid) {
                                prefillDomain = parsed.cleanDomain
                                prefillName = parsed.suggestedName
                                showAddDialog = true
                                Toast.makeText(context, "QR-код распознан: ${parsed.cleanDomain}", Toast.LENGTH_SHORT).show()
                            } else if (rawScanned.isNotBlank()) {
                                prefillDomain = rawScanned
                                prefillName = ""
                                showAddDialog = true
                            }
                        },
                        onPasteFromClipboard = {
                            val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString().orEmpty().trim()
                            if (clip.isNotBlank()) {
                                val parsed = WorkerDomainNormalizer.normalize(clip)
                                prefillDomain = parsed.cleanDomain
                                prefillName = parsed.suggestedName
                                showAddDialog = true
                                Toast.makeText(context, "Адрес вставлен из буфера", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Буфер обмена пуст", Toast.LENGTH_SHORT).show()
                            }
                        },
                        headerPadding = headerHeightDp
                    )
                }
                ManagerSection.GUIDE -> {
                    LazyColumn(
                        state = guideListState,
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
                        when (selectedGuideTab) {
                            WmGuideTab.PC -> {
                                item(key = "pc_auto_deploy_card") {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = activeProtoColor.copy(alpha = 0.08f),
                                        border = BorderStroke(1.dp, activeProtoColor.copy(alpha = 0.40f)),
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
                                                        .background(activeProtoColor)
                                                )
                                                Surface(
                                                    shape = RoundedCornerShape(4.5.dp),
                                                    color = Color.Transparent,
                                                    border = BorderStroke(1.dp, activeProtoColor.copy(alpha = 0.40f))
                                                ) {
                                                    Text(
                                                        text = "1 Клик",
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = activeProtoColor,
                                                        modifier = Modifier.padding(horizontal = 4.5.dp, vertical = 1.dp)
                                                    )
                                                }
                                                Text(
                                                    text = "Автодеплой (BAT / PowerShell / Bash)",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 12.sp,
                                                    color = activeProtoColor
                                                )
                                            }
                                            Text(
                                                text = "Запустите deploy.bat двойным кликом на Windows или выполните 1 команду в PowerShell/Bash: скрипт откроет вход в Cloudflare, задеплоит воркер и скопирует готовый домен в буфер обмена.",
                                                fontSize = 11.5.sp,
                                                color = TextWhite.copy(alpha = 0.9f),
                                                lineHeight = 16.sp
                                            )
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Surface(
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = activeProtoColor.copy(alpha = 0.16f),
                                                    border = BorderStroke(1.dp, activeProtoColor.copy(alpha = 0.55f)),
                                                    modifier = Modifier
                                                        .weight(1.2f)
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .springPress(onClick = { copyDeployCommandToClipboard() })
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.5.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.Center
                                                    ) {
                                                        Icon(
                                                            painter = painterResource(id = R.drawable.ic_copy),
                                                            contentDescription = "Копировать",
                                                            tint = activeProtoColor,
                                                            modifier = Modifier.size(12.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(5.dp))
                                                        Text(
                                                            text = "Скопировать команду",
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = TextWhite
                                                        )
                                                    }
                                                }

                                                Surface(
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = Color.Transparent,
                                                    border = BorderStroke(1.dp, Color(0xFF1E283D)),
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .springPress(onClick = { showDeployScriptConfirmDialog = true })
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.5.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.Center
                                                    ) {
                                                        Icon(
                                                            painter = painterResource(id = R.drawable.ic_github),
                                                            contentDescription = "GitHub",
                                                            tint = TextMuted,
                                                            modifier = Modifier.size(12.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(5.dp))
                                                        Text(
                                                            text = "deploy.bat на GitHub",
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.Medium,
                                                            color = TextMuted
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                itemsIndexed(
                                    items = pcSteps,
                                    key = { _, item -> "pc_${item.stepNumber}" }
                                ) { _, step ->
                                    GlassGuideStepCard(
                                        stepNumber = step.stepNumber,
                                        title = step.title,
                                        description = step.description,
                                        activeAccentColor = activeProtoColor,
                                        actionText = step.actionText,
                                        onAction = when {
                                            step.isCopyAction -> { { copyScriptToClipboard() } }
                                            step.isDashAction -> { { openCloudflareDashboard() } }
                                            else -> null
                                        }
                                    )
                                }
                            }
                            WmGuideTab.PHONE -> {
                                item(key = "phone_tip") {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = Color(0xFF38BDF8).copy(alpha = 0.08f),
                                        border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.35f)),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 2.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(6.dp)
                                                        .clip(CircleShape)
                                                        .background(Color(0xFF38BDF8))
                                                )
                                                Surface(
                                                    shape = RoundedCornerShape(4.5.dp),
                                                    color = Color.Transparent,
                                                    border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.35f))
                                                ) {
                                                    Text(
                                                        text = "Совет",
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color(0xFF38BDF8),
                                                        modifier = Modifier.padding(horizontal = 4.5.dp, vertical = 1.dp)
                                                    )
                                                }
                                                Text(
                                                    text = "Мобильный браузер",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 12.sp,
                                                    color = Color(0xFF38BDF8)
                                                )
                                            }
                                            Text(
                                                text = "В мобильном браузере (Chrome / Firefox) включите в меню флажок «Версия для ПК», если интерфейс редактора Cloudflare покажется компактным.",
                                                fontSize = 11.5.sp,
                                                color = TextWhite.copy(alpha = 0.85f),
                                                lineHeight = 16.sp
                                            )
                                        }
                                    }
                                }

                                itemsIndexed(
                                    items = phoneSteps,
                                    key = { _, item -> "phone_${item.stepNumber}" }
                                ) { _, step ->
                                    GlassGuideStepCard(
                                        stepNumber = step.stepNumber,
                                        title = step.title,
                                        description = step.description,
                                        activeAccentColor = activeProtoColor,
                                        actionText = step.actionText,
                                        onAction = when {
                                            step.isCopyAction -> { { copyScriptToClipboard() } }
                                            step.isDashAction -> { { openCloudflareDashboard() } }
                                            else -> null
                                        }
                                    )
                                }
                            }
                            WmGuideTab.SCRIPT -> {
                                item(key = "script_header") {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = Color.Transparent,
                                        border = BorderStroke(1.dp, Color(0xFF181E2E)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
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
                                                            .size(6.dp)
                                                            .clip(CircleShape)
                                                            .background(activeProtoColor)
                                                    )
                                                    Text(
                                                        text = "cloudflare_worker.js",
                                                        fontFamily = FontFamily.Monospace,
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = TextWhite
                                                    )
                                                }

                                                Surface(
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = activeProtoColor.copy(alpha = 0.12f),
                                                    border = BorderStroke(1.dp, activeProtoColor.copy(alpha = 0.45f)),
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .springPress(onClick = { copyScriptToClipboard() })
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                    ) {
                                                        Icon(
                                                            painter = painterResource(id = R.drawable.ic_copy),
                                                            contentDescription = "Копировать",
                                                            tint = activeProtoColor,
                                                            modifier = Modifier.size(12.dp)
                                                        )
                                                        Text(
                                                            text = "Копировать",
                                                            color = activeProtoColor,
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }
                                                }
                                            }

                                            Text(
                                                text = TgConstants.CLOUDFLARE_WORKER_JS_CODE,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 10.5.sp,
                                                color = TextWhite.copy(alpha = 0.8f),
                                                lineHeight = 15.sp
                                            )
                                        }
                                    }
                                }
                            }
                            WmGuideTab.FAQ -> {
                                itemsIndexed(
                                    items = faqItems,
                                    key = { index, _ -> "faq_item_$index" }
                                ) { _, faq ->
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = Color.Transparent,
                                        border = BorderStroke(1.dp, Color(0xFF181E2E)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
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
                                                Text(
                                                    text = faq.title,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = TextWhite
                                                )
                                            }
                                            Text(
                                                text = faq.description,
                                                fontSize = 11.5.sp,
                                                color = TextMuted,
                                                lineHeight = 16.sp,
                                                modifier = Modifier.padding(start = 12.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. PINNED FROSTED GLASS HEADER
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
                    val thresholdPx = 28.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var totalDragY = 0f
                        val pointerId = down.id
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                            if (!change.pressed) break
                            val dy = change.positionChange().y
                            totalDragY += dy
                            if (totalDragY > 0f || totalDragY < 0f) {
                                change.consume()
                            }
                        }
                        if (totalDragY > thresholdPx || totalDragY < -thresholdPx) {
                            currentHandleDismiss()
                        }
                    }
                }
                .padding(bottom = 18.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Top Drag Handle Pill (Tap or Swipe up to collapse)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 2.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            handleDismiss()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(36.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.White.copy(alpha = 0.28f))
                    )
                }

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
                                text = if (currentSection == ManagerSection.GUIDE) {
                                    "Инструкция по настройке"
                                } else {
                                    if (isSocks5) "SOCKS5 Cloudflare Туннели" else "MTProto Cloudflare Туннели"
                                },
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
                        if (currentSection == ManagerSection.WORKERS) {
                            // Analytics Chart Button
                            if (onOpenAnalytics != null) {
                                IconButton(onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onOpenAnalytics()
                                }) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_diag_formula),
                                        contentDescription = "Аналитика запросов",
                                        tint = activeProtoColor,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

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
                        } else {
                            // Copy script shortcut in Guide
                            IconButton(onClick = {
                                copyScriptToClipboard()
                            }) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_copy),
                                    contentDescription = "Скопировать скрипт",
                                    tint = activeProtoColor,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )

                // 4-Segment Primary Section Switcher Pill ([ Воркеры ] [ Поделиться ] [ Сканер ] [ Инструкция ])
                val sections = remember { ManagerSection.values() }
                val sectionCapsuleWidth = 348.dp
                val sectionCapsuleHeight = 35.dp
                val sectionInnerPadding = 3.dp
                val sectionTabWidth = (sectionCapsuleWidth - sectionInnerPadding * 2) / sections.size
                val selectedIndex = sections.indexOf(currentSection).coerceAtLeast(0)

                val animatedSectionPillOffset by animateDpAsState(
                    targetValue = sectionTabWidth * selectedIndex,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    ),
                    label = "sectionPillOffset"
                )

                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(sectionCapsuleWidth)
                            .height(sectionCapsuleHeight)
                            .clip(RoundedCornerShape(17.dp))
                            .background(Color.Transparent)
                            .border(1.dp, Color(0xFF1E2434), RoundedCornerShape(17.dp))
                            .padding(sectionInnerPadding)
                    ) {
                        // Sliding Glowing Indicator Pill
                        Box(
                            modifier = Modifier
                                .offset(x = animatedSectionPillOffset)
                                .width(sectionTabWidth)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(14.dp))
                                .background(activeProtoColor.copy(alpha = 0.20f))
                                .border(1.2.dp, activeProtoColor.copy(alpha = 0.85f), RoundedCornerShape(14.dp))
                        )

                        // Segment Labels Row
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            sections.forEach { sec ->
                                val isSelected = currentSection == sec
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null
                                        ) {
                                            if (currentSection != sec) {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                currentSection = sec
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = sec.title,
                                        color = if (isSelected) activeProtoColor else TextMuted,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        letterSpacing = 0.2.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                // Sub-Controls depending on Active Section
                AnimatedContent(
                    targetState = currentSection,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(180)).togetherWith(fadeOut(animationSpec = tween(140)))
                    },
                    label = "managerHeaderSubControls"
                ) { section ->
                    when (section) {
                        ManagerSection.WORKERS -> {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

                                // 3 Fixed-Width Segmented Filter Chips + Add Button
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    SegmentedFilterChip(
                                        title = "Все",
                                        count = allWorkers.size,
                                        isSelected = selectedFilter == WmWorkerFilterType.ALL,
                                        activeColor = activeProtoColor,
                                        modifier = Modifier.weight(1f),
                                        onClick = {
                                            selectedFilter = WmWorkerFilterType.ALL
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                    )
                                    SegmentedFilterChip(
                                        title = "Официальные",
                                        count = devWorkers.size,
                                        isSelected = selectedFilter == WmWorkerFilterType.DEVELOPER,
                                        activeColor = activeProtoColor,
                                        modifier = Modifier.weight(1.3f),
                                        onClick = {
                                            selectedFilter = WmWorkerFilterType.DEVELOPER
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                    )
                                    SegmentedFilterChip(
                                        title = "Личные",
                                        count = customWorkers.size,
                                        isSelected = selectedFilter == WmWorkerFilterType.CUSTOM,
                                        activeColor = activeProtoColor,
                                        modifier = Modifier.weight(1f),
                                        onClick = {
                                            selectedFilter = WmWorkerFilterType.CUSTOM
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                    )

                                    // Add worker button
                                    Surface(
                                        shape = RoundedCornerShape(11.dp),
                                        color = activeProtoColor.copy(alpha = 0.12f),
                                        border = BorderStroke(1.dp, activeProtoColor.copy(alpha = 0.55f)),
                                        modifier = Modifier
                                            .height(34.dp)
                                            .clip(RoundedCornerShape(11.dp))
                                            .springPress(onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                prefillDomain = ""
                                                prefillName = ""
                                                showAddDialog = true
                                            })
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxHeight()
                                                .padding(horizontal = 10.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "+ Добавить",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 11.5.sp,
                                                color = activeProtoColor
                                            )
                                        }
                                    }

                                    // QR Scanner quick button
                                    Surface(
                                        shape = RoundedCornerShape(11.dp),
                                        color = activeProtoColor.copy(alpha = 0.12f),
                                        border = BorderStroke(1.dp, activeProtoColor.copy(alpha = 0.55f)),
                                        modifier = Modifier
                                            .height(34.dp)
                                            .clip(RoundedCornerShape(11.dp))
                                            .springPress(onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                currentSection = ManagerSection.SCANNER
                                            })
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxHeight()
                                                .padding(horizontal = 9.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                painter = painterResource(id = R.drawable.ic_diag_worker),
                                                contentDescription = "QR Сканер",
                                                tint = activeProtoColor,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Text(
                                                text = "QR",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 11.5.sp,
                                                color = activeProtoColor
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        ManagerSection.SHARE -> {
                            Surface(
                                shape = RoundedCornerShape(11.dp),
                                color = AmoledSurfaceLow,
                                border = BorderStroke(1.dp, AmoledBorder),
                                modifier = Modifier.fillMaxWidth().height(34.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                    modifier = Modifier.padding(horizontal = 12.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_send),
                                        contentDescription = null,
                                        tint = activeProtoColor,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "QR-код и быстрый импорт воркера для друзей",
                                        color = TextMuted,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                        ManagerSection.SCANNER -> {
                            Surface(
                                shape = RoundedCornerShape(11.dp),
                                color = AmoledSurfaceLow,
                                border = BorderStroke(1.dp, AmoledBorder),
                                modifier = Modifier.fillMaxWidth().height(34.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                    modifier = Modifier.padding(horizontal = 12.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_diag_worker),
                                        contentDescription = null,
                                        tint = activeProtoColor,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Считывание QR из батника (.bat) и приложения",
                                        color = TextMuted,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                        ManagerSection.GUIDE -> {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                // Top 3 Guide Sub-Chips
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    val topTabs = listOf(WmGuideTab.PC, WmGuideTab.PHONE, WmGuideTab.SCRIPT)
                                    topTabs.forEach { tab ->
                                        val isSelected = selectedGuideTab == tab
                                        val borderColor by animateColorAsState(
                                            targetValue = if (isSelected) activeProtoColor.copy(alpha = 0.85f) else Color(0xFF1E283D),
                                            animationSpec = tween(180),
                                            label = "guideTabBorder"
                                        )
                                        val titleColor by animateColorAsState(
                                            targetValue = if (isSelected) activeProtoColor else TextWhite.copy(alpha = 0.85f),
                                            animationSpec = tween(180),
                                            label = "guideTabTitle"
                                        )

                                        Surface(
                                            shape = RoundedCornerShape(11.dp),
                                            color = Color.Transparent,
                                            border = BorderStroke(1.dp, borderColor),
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(34.dp)
                                                .clip(RoundedCornerShape(11.dp))
                                                .springPress(onClick = {
                                                    selectedGuideTab = tab
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                })
                                        ) {
                                            Box(
                                                modifier = Modifier.fillMaxSize(),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = tab.title,
                                                    color = titleColor,
                                                    fontSize = 11.5.sp,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }

                                // Full-Width FAQ Chip
                                val isFaqSelected = selectedGuideTab == WmGuideTab.FAQ
                                val faqBorderColor by animateColorAsState(
                                    targetValue = if (isFaqSelected) activeProtoColor.copy(alpha = 0.85f) else Color(0xFF1E283D),
                                    animationSpec = tween(180),
                                    label = "faqTabBorder"
                                )
                                val faqTitleColor by animateColorAsState(
                                    targetValue = if (isFaqSelected) activeProtoColor else TextWhite.copy(alpha = 0.85f),
                                    animationSpec = tween(180),
                                    label = "faqTabTitle"
                                )

                                Surface(
                                    shape = RoundedCornerShape(11.dp),
                                    color = Color.Transparent,
                                    border = BorderStroke(1.dp, faqBorderColor),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(32.dp)
                                        .clip(RoundedCornerShape(11.dp))
                                        .springPress(onClick = {
                                            selectedGuideTab = WmGuideTab.FAQ
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        })
                                ) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = WmGuideTab.FAQ.title,
                                            color = faqTitleColor,
                                            fontSize = 11.5.sp,
                                            fontWeight = if (isFaqSelected) FontWeight.Bold else FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddWorkerDialog(
            activeAccentColor = activeProtoColor,
            initialDomain = prefillDomain,
            initialName = prefillName,
            onOpenQrScanner = {
                showAddDialog = false
                showQrScanner = true
            },
            onDismiss = {
                showAddDialog = false
                prefillDomain = ""
                prefillName = ""
            },
            onAdd = { name, domain ->
                val res = prefs.addCustomWorker(name, domain)
                res.fold(
                    onSuccess = { added ->
                        prefs.setActiveWorkerId(added.id)
                        refreshWorkers()
                        showAddDialog = false
                        prefillDomain = ""
                        prefillName = ""
                        Toast.makeText(context, "Воркер «${added.name}» добавлен и активирован", Toast.LENGTH_SHORT).show()
                    },
                    onFailure = { err ->
                        Toast.makeText(context, err.message ?: "Ошибка добавления", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        )
    }

    if (showQrScanner) {
        QrCodeScannerDialog(
            activeAccentColor = activeProtoColor,
            onDismiss = { showQrScanner = false },
            onQrScanned = { rawScanned ->
                showQrScanner = false
                val parsed = WorkerDomainNormalizer.normalize(rawScanned)
                if (parsed.isValid) {
                    prefillDomain = parsed.cleanDomain
                    prefillName = parsed.suggestedName
                    showAddDialog = true
                    Toast.makeText(context, "QR-код распознан: ${parsed.cleanDomain}", Toast.LENGTH_SHORT).show()
                } else if (rawScanned.isNotBlank()) {
                    prefillDomain = rawScanned
                    prefillName = ""
                    showAddDialog = true
                }
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
            .clip(RoundedCornerShape(11.dp))
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
private fun GlassGuideStepCard(
    stepNumber: String,
    title: String,
    description: String,
    activeAccentColor: Color,
    actionText: String? = null,
    onAction: (() -> Unit)? = null
) {
    Surface(
        shape = RoundedCornerShape(13.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, Color(0xFF181E2E)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(4.5.dp),
                    color = activeAccentColor.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, activeAccentColor.copy(alpha = 0.45f))
                ) {
                    Text(
                        text = "Шаг $stepNumber",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = activeAccentColor,
                        modifier = Modifier.padding(horizontal = 4.5.dp, vertical = 1.dp)
                    )
                }

                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Text(
                text = description,
                fontSize = 11.5.sp,
                color = TextMuted,
                lineHeight = 16.sp
            )

            if (actionText != null && onAction != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = activeAccentColor.copy(alpha = 0.08f),
                    border = BorderStroke(1.dp, activeAccentColor.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .springPress(onClick = onAction)
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = actionText,
                            color = activeAccentColor,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

/**
 * Clean Frosted Glass Worker Card (Compact, transparent outline style)
 */
@OptIn(ExperimentalFoundationApi::class)
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

    val circuitRecord = com.mirrly.tgproxy.service.WorkerFailoverManager.getCircuitRecord(worker.id)
    val circuitState = circuitRecord?.state ?: com.mirrly.tgproxy.core.CircuitState.CLOSED

    // Динамический секундный таймер карантина в реальном времени
    var currentRemSeconds by remember(circuitRecord?.cooldownUntilTimestamp, circuitState) {
        mutableStateOf(circuitRecord?.remainingCooldownSeconds ?: 0L)
    }

    LaunchedEffect(circuitRecord?.cooldownUntilTimestamp, circuitState) {
        while (true) {
            val rem = circuitRecord?.remainingCooldownSeconds ?: 0L
            currentRemSeconds = rem
            if (rem <= 0L || circuitRecord?.state != com.mirrly.tgproxy.core.CircuitState.OPEN) {
                break
            }
            kotlinx.coroutines.delay(1000L)
        }
    }

    val isCircuitBroken = circuitState == com.mirrly.tgproxy.core.CircuitState.OPEN && currentRemSeconds > 0L

    val (statusColor, statusText) = when {
        isCircuitBroken -> {
            val text = "Карантин (${currentRemSeconds}с)"
            Pair(Color(0xFFEF4444), text)
        }
        circuitState == com.mirrly.tgproxy.core.CircuitState.HALF_OPEN || (circuitState == com.mirrly.tgproxy.core.CircuitState.OPEN && currentRemSeconds <= 0L) -> {
            Pair(Color(0xFFF59E0B), "Проверка...")
        }
        else -> {
            val color = when (status) {
                WorkerStatus.ONLINE -> if ((pingMs ?: 0) < 250) Color(0xFF00E676) else Color(0xFFF59E0B)
                WorkerStatus.RATE_LIMITED_429 -> Color(0xFFEF4444)
                WorkerStatus.ERROR_UNREACHABLE -> Color(0xFFF59E0B)
                WorkerStatus.UNKNOWN -> TextMuted
            }
            val text = when (status) {
                WorkerStatus.ONLINE -> "${pingMs ?: 0} мс"
                WorkerStatus.RATE_LIMITED_429 -> "429 Лимит"
                WorkerStatus.ERROR_UNREACHABLE -> "Недоступен"
                WorkerStatus.UNKNOWN -> "—"
            }
            Pair(color, text)
        }
    }

    val cardBorderColor = if (isActive) {
        activeAccentColor.copy(alpha = 0.9f)
    } else {
        Color(0xFF181E2E)
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isActive) activeAccentColor.copy(alpha = 0.08f) else Color.Transparent,
        border = BorderStroke(if (isActive) 1.2.dp else 1.dp, cardBorderColor),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    onSelect()
                },
                onLongClick = if (!worker.isDeveloperWorker) {
                    {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val clip = ClipData.newPlainText("Worker Domain", worker.domain)
                        clipboard?.setPrimaryClip(clip)
                        Toast.makeText(context, "Домен скопирован в буфер обмена", Toast.LENGTH_SHORT).show()
                    }
                } else null
            )
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
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Right block: Monospace Ping + Actions (Share / Delete)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
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
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onShare()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Поделиться",
                            tint = activeAccentColor,
                            modifier = Modifier.size(11.5.dp)
                        )
                    }
                }

                if (onDelete != null) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onDelete()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_trash),
                            contentDescription = "Удалить",
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(11.5.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Frosted Glass Add Worker Modal Dialog (Info panels style, top-left back button, no emojis, smart normalizer and pre-flight tester)
 */
@Composable
private fun AddWorkerDialog(
    activeAccentColor: Color,
    initialDomain: String = "",
    initialName: String = "",
    onOpenQrScanner: () -> Unit = {},
    onDismiss: () -> Unit,
    onAdd: (name: String, domain: String) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var domainText by remember { mutableStateOf(initialDomain) }
    var nameText by remember { mutableStateOf(initialName) }
    var isCheckingWorker by remember { mutableStateOf(false) }
    var checkStatusMessage by remember { mutableStateOf<String?>(null) }
    var unreachableWarning by remember { mutableStateOf<String?>(null) }

    val formResult = remember(domainText, nameText) {
        WorkerDomainNormalizer.normalizeForm(nameText, domainText)
    }

    Dialog(
        onDismissRequest = {
            if (!isCheckingWorker) onDismiss()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = !isCheckingWorker,
            dismissOnClickOutside = !isCheckingWorker
        )
    ) {
        DialogBackdropBox(
            onDismiss = {
                if (!isCheckingWorker) onDismiss()
            }
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
                    .padding(horizontal = 24.dp)
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
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = "ПАРАМЕТРЫ ПОДКЛЮЧЕНИЯ:",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = activeAccentColor,
                            letterSpacing = 0.5.sp
                        )

                        Text(
                            text = "Вставьте публичный адрес воркера (или скопируйте ссылку). Система автоматически очистит и нормализует формат.",
                            fontSize = 12.sp,
                            color = TextWhite.copy(alpha = 0.8f),
                            lineHeight = 16.5.sp
                        )

                        // ── 1. PRIMARY INPUT FIELD: WORKER DOMAIN / URL ──
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "АДРЕС ВОРКЕРА (URL / ДОМЕН)",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = activeAccentColor,
                                    letterSpacing = 0.5.sp
                                )

                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color(0xFFE53935).copy(alpha = 0.15f),
                                    border = BorderStroke(1.dp, Color(0xFFE53935).copy(alpha = 0.3f))
                                ) {
                                    Text(
                                        text = "ОБЯЗАТЕЛЬНО",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFFF6B6B),
                                        letterSpacing = 0.5.sp,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            OutlinedTextField(
                                value = domainText,
                                onValueChange = {
                                    domainText = it
                                    unreachableWarning = null
                                },
                                placeholder = {
                                    Text(
                                        "my-proxy.username.workers.dev",
                                        color = TextMuted.copy(alpha = 0.45f),
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                },
                                textStyle = LocalTextStyle.current.copy(
                                    color = TextWhite,
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace
                                ),
                                singleLine = true,
                                trailingIcon = {
                                    if (domainText.isNotBlank()) {
                                        IconButton(onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            domainText = ""
                                            unreachableWarning = null
                                        }) {
                                            Text(
                                                text = "✕",
                                                color = TextMuted,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = activeAccentColor,
                                    unfocusedBorderColor = Color(0xFF223048),
                                    focusedTextColor = TextWhite,
                                    unfocusedTextColor = TextWhite,
                                    focusedLabelColor = activeAccentColor,
                                    unfocusedLabelColor = TextMuted,
                                    focusedContainerColor = Color.White.copy(alpha = 0.02f),
                                    unfocusedContainerColor = Color.Transparent
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )

                            // Dedicated Quick Actions: Paste from Clipboard & Scan QR
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Paste Button
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = activeAccentColor.copy(alpha = 0.08f),
                                    border = BorderStroke(1.dp, activeAccentColor.copy(alpha = 0.3f)),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .springPress(onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            val clip = clipboardManager.getText()?.text.orEmpty().trim()
                                            if (clip.isNotBlank()) {
                                                val parsed = WorkerDomainNormalizer.normalizeForm(nameText, clip)
                                                domainText = parsed.normalizedDomain
                                                if (nameText.isBlank() && parsed.normalizedName.isNotBlank()) {
                                                    nameText = parsed.normalizedName
                                                }
                                                Toast.makeText(context, "Адрес вставлен и нормализован", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(context, "Буфер обмена пуст", Toast.LENGTH_SHORT).show()
                                            }
                                        })
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_copy),
                                            contentDescription = null,
                                            tint = activeAccentColor,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Из буфера",
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = activeAccentColor
                                        )
                                    }
                                }

                                // Scan QR Button
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = activeAccentColor.copy(alpha = 0.12f),
                                    border = BorderStroke(1.dp, activeAccentColor.copy(alpha = 0.45f)),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .springPress(onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onOpenQrScanner()
                                        })
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_diag_worker),
                                            contentDescription = null,
                                            tint = activeAccentColor,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "QR-сканер",
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = activeAccentColor
                                        )
                                    }
                                }
                            }

                            // Live Validation & Predictive Status Badge
                            if (domainText.isNotBlank() || formResult.wasSwapped) {
                                val status = formResult.domainResult.status
                                val isSuccess = status == DomainFormatStatus.VALID || status == DomainFormatStatus.HOMOGLYPHS_FIXED
                                val isWarning = status == DomainFormatStatus.DASHBOARD_URL || status == DomainFormatStatus.NAME_ONLY || formResult.wasSwapped

                                val badgeBg = when {
                                    isSuccess -> ActiveGreenLed.copy(alpha = 0.08f)
                                    isWarning -> Color(0xFFF5A623).copy(alpha = 0.08f)
                                    else -> Color(0xFFE53935).copy(alpha = 0.08f)
                                }
                                val badgeBorder = when {
                                    isSuccess -> ActiveGreenLed.copy(alpha = 0.35f)
                                    isWarning -> Color(0xFFF5A623).copy(alpha = 0.35f)
                                    else -> Color(0xFFE53935).copy(alpha = 0.35f)
                                }

                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = badgeBg,
                                    border = BorderStroke(1.dp, badgeBorder),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(7.dp)
                                                    .clip(CircleShape)
                                                    .background(
                                                        when {
                                                            isSuccess -> ActiveGreenLed
                                                            isWarning -> Color(0xFFFFD166)
                                                            else -> Color(0xFFFF6B6B)
                                                        }
                                                    )
                                            )
                                            Text(
                                                text = when {
                                                    formResult.wasSwapped -> "ПОЛЯ АВТОМАТИЧЕСКИ СОГЛАСОВАНЫ"
                                                    status == DomainFormatStatus.HOMOGLYPHS_FIXED -> "РАСПОЗНАН И ИСПРАВЛЕН АДРЕС УЗЛА:"
                                                    isSuccess -> "РАСПОЗНАН ПУБЛИЧНЫЙ УЗЕЛ:"
                                                    status == DomainFormatStatus.DASHBOARD_URL -> "ОБНАРУЖЕНА ССЫЛКА НА DASHBOARD CLOUDFLARE"
                                                    status == DomainFormatStatus.NAME_ONLY -> "УКАЗАНО ТОЛЬКО ИМЯ ВОРКЕРА"
                                                    else -> "ОШИБКА ФОРМАТА АДРЕСА"
                                                },
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = when {
                                                    isSuccess -> ActiveGreenLed
                                                    isWarning -> Color(0xFFFFD166)
                                                    else -> Color(0xFFFF6B6B)
                                                },
                                                letterSpacing = 0.5.sp
                                            )
                                        }

                                        if (formResult.domainResult.cleanDomain.isNotBlank() && isSuccess) {
                                            Text(
                                                text = formResult.domainResult.cleanDomain,
                                                fontSize = 13.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace,
                                                color = TextWhite
                                            )
                                        }

                                        if (formResult.wasSwapped) {
                                            Text(
                                                text = "Адрес воркера обнаружен в поле названия («$nameText»). Он автоматически используется как адрес узла.",
                                                fontSize = 11.5.sp,
                                                color = TextWhite.copy(alpha = 0.85f),
                                                lineHeight = 15.sp
                                            )
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(Color(0xFFF5A623).copy(alpha = 0.15f))
                                                    .clickable {
                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        domainText = formResult.normalizedDomain
                                                        nameText = formResult.normalizedName
                                                    }
                                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Text(
                                                    text = "⇄ Применить перестановку полей в форме",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFFFFD166)
                                                )
                                            }
                                        } else {
                                            Text(
                                                text = formResult.domainResult.userMessage,
                                                fontSize = 11.5.sp,
                                                color = TextWhite.copy(alpha = 0.8f),
                                                lineHeight = 15.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // ── 2. SECONDARY INPUT FIELD: WORKER NAME ──
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "НАЗВАНИЕ УЗЛА",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextMuted,
                                    letterSpacing = 0.5.sp
                                )

                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color.White.copy(alpha = 0.06f),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
                                ) {
                                    Text(
                                        text = "ОПЦИОНАЛЬНО",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextMuted,
                                        letterSpacing = 0.5.sp,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            val placeholderName = formResult.domainResult.suggestedName.ifBlank { "Мой домашний" }

                            OutlinedTextField(
                                value = nameText,
                                onValueChange = { nameText = it },
                                placeholder = {
                                    Text(
                                        placeholderName,
                                        color = TextMuted.copy(alpha = 0.45f),
                                        fontSize = 12.sp
                                    )
                                },
                                textStyle = LocalTextStyle.current.copy(
                                    color = TextWhite,
                                    fontSize = 13.sp
                                ),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = activeAccentColor,
                                    unfocusedBorderColor = Color(0xFF223048),
                                    focusedTextColor = TextWhite,
                                    unfocusedTextColor = TextWhite,
                                    focusedLabelColor = activeAccentColor,
                                    unfocusedLabelColor = TextMuted,
                                    focusedContainerColor = Color.White.copy(alpha = 0.02f),
                                    unfocusedContainerColor = Color.Transparent
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // ── 3. VISUAL REFERENCE GUIDE / QUICK HINT ──
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF0F172A).copy(alpha = 0.5f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Text(
                                    text = "ГДЕ ВЗЯТЬ АДРЕС В CLOUDFLARE:",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = activeAccentColor,
                                    letterSpacing = 0.5.sp
                                )
                                Text(
                                    text = "1. Перейдите в Cloudflare: Workers & Pages → Ваш воркер.\n2. Скопируйте ссылку под заголовком или со вкладки Deployments.\n3. Публичный адрес оканчивается на .workers.dev.\n4. Ссылка браузера (dash.cloudflare.com) не является адресом воркера.",
                                    fontSize = 11.sp,
                                    color = TextWhite.copy(alpha = 0.72f),
                                    lineHeight = 15.5.sp
                                )
                            }
                        }
                    }
                }

                // ── 4. UNREACHABLE / PRE-FLIGHT WARNING BANNER ──
                if (unreachableWarning != null) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFFE53935).copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, Color(0xFFE53935).copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "ПРЕДУПРЕЖДЕНИЕ СВЯЗИ:",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFF6B6B),
                                letterSpacing = 0.5.sp
                            )
                            Text(
                                text = unreachableWarning ?: "",
                                fontSize = 11.5.sp,
                                color = TextWhite.copy(alpha = 0.9f),
                                lineHeight = 15.sp
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        val cleanDomain = formResult.normalizedDomain
                                        val finalName = formResult.normalizedName.ifBlank {
                                            formResult.domainResult.suggestedName.ifBlank { "Личный воркер" }
                                        }
                                        onAdd(finalName, cleanDomain)
                                    },
                                    border = BorderStroke(1.dp, Color(0xFFFF6B6B).copy(alpha = 0.5f)),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f).height(38.dp)
                                ) {
                                    Text(
                                        text = "Сохранить всё равно",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFFFF6B6B)
                                    )
                                }

                                Button(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        unreachableWarning = null
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = activeAccentColor),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f).height(38.dp)
                                ) {
                                    Text(
                                        text = "Исправить",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF0A0E1A)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                // ── 5. SAVE & PRE-FLIGHT VERIFICATION BUTTON ──
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (isCheckingWorker) activeAccentColor.copy(alpha = 0.7f) else activeAccentColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .springPress(
                            onClick = {
                                if (isCheckingWorker) return@springPress
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)

                                val cleanDomain = formResult.normalizedDomain
                                val finalName = formResult.normalizedName.ifBlank {
                                    formResult.domainResult.suggestedName.ifBlank { "Личный воркер" }
                                }

                                if (cleanDomain.isBlank()) {
                                    Toast.makeText(context, "Укажите корректный адрес воркера", Toast.LENGTH_SHORT).show()
                                    return@springPress
                                }

                                if (formResult.domainResult.status == DomainFormatStatus.DASHBOARD_URL) {
                                    unreachableWarning = formResult.domainResult.userMessage
                                    return@springPress
                                }

                                scope.launch {
                                    isCheckingWorker = true
                                    checkStatusMessage = "Проверка доступности узла..."
                                    unreachableWarning = null

                                    val (status, pingMs) = WorkerPingTester.pingWorker(cleanDomain)
                                    isCheckingWorker = false
                                    checkStatusMessage = null

                                    if (status == WorkerStatus.ONLINE || status == WorkerStatus.RATE_LIMITED_429) {
                                        onAdd(finalName, cleanDomain)
                                    } else {
                                        unreachableWarning = "Воркер «$cleanDomain» не отвечает на проверочный запрос (ERR_UNREACHABLE). Убедитесь, что скрипт развернут в Cloudflare и маршрутизация активна."
                                    }
                                }
                            }
                        )
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (isCheckingWorker) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = Color(0xFF0A0E1A),
                                    strokeWidth = 2.dp
                                )
                                Text(
                                    text = checkStatusMessage ?: "Проверка узла...",
                                    color = Color(0xFF0A0E1A),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.5.sp
                                )
                            }
                        } else {
                            Text(
                                text = "Сохранить и активировать",
                                color = Color(0xFF0A0E1A),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
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
                        if (!isCheckingWorker) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDismiss()
                        }
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
        DialogBackdropBox(
            onDismiss = onDismiss
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
                    .padding(horizontal = 24.dp)
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

@Composable
private fun ShareWorkerContent(
    workers: List<WorkerProfile>,
    activeWorkerId: String,
    activeAccentColor: Color,
    selectedWorker: WorkerProfile?,
    onSelectWorker: (WorkerProfile) -> Unit,
    onShare: (WorkerProfile) -> Unit,
    onAddWorkerClick: () -> Unit,
    headerPadding: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val clipboardManager = LocalClipboardManager.current

    val currentWorker = selectedWorker
        ?: workers.firstOrNull { it.id == activeWorkerId }
        ?: workers.firstOrNull()

    if (currentWorker == null) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(top = headerPadding, bottom = 32.dp, start = 20.dp, end = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.padding(24.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_diag_worker),
                    contentDescription = null,
                    tint = TextMuted.copy(alpha = 0.5f),
                    modifier = Modifier.size(56.dp)
                )
                Text(
                    text = "Нет доступных воркеров",
                    color = TextWhite,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Добавьте или создайте свой воркер, чтобы сгенерировать QR-код и поделиться им с друзьями.",
                    color = TextMuted,
                    fontSize = 12.5.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 17.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    shape = RoundedCornerShape(13.dp),
                    color = activeAccentColor.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, activeAccentColor.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .clip(RoundedCornerShape(13.dp))
                        .springPress(onClick = onAddWorkerClick)
                ) {
                    Text(
                        text = "+ Добавить воркер",
                        color = activeAccentColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                    )
                }
            }
        }
    } else {
        val encodedDomain = Uri.encode(currentWorker.domain)
        val encodedName = Uri.encode(currentWorker.name)
        val deepLink = "mirrly://worker?domain=$encodedDomain&name=$encodedName"

        val qrBitmap = remember(deepLink) {
            QrCodeGenerator.generateQrBitmap(
                content = deepLink,
                sizePx = 600,
                darkColor = android.graphics.Color.WHITE,
                lightColor = android.graphics.Color.TRANSPARENT
            )
        }

        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    top = headerPadding + 8.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
                    start = 16.dp,
                    end = 16.dp
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Worker Selector Horizontal Row
            if (workers.size > 1) {
                Text(
                    text = "Выберите узел для генерации QR-кода:",
                    color = TextMuted,
                    fontSize = 11.5.sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    workers.forEach { w ->
                        val isSelected = w.id == currentWorker.id
                        val isActive = w.id == activeWorkerId
                        val chipBorder = if (isSelected) activeAccentColor else AmoledBorder
                        val chipBg = if (isSelected) activeAccentColor.copy(alpha = 0.15f) else AmoledSurfaceLow

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = chipBg,
                            border = BorderStroke(1.dp, chipBorder),
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .springPress(onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onSelectWorker(w)
                                })
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                            ) {
                                if (isActive) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(activeAccentColor)
                                    )
                                }
                                Text(
                                    text = w.name,
                                    color = if (isSelected) activeAccentColor else TextWhite,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }

            // QR Code Card in Liquid Glass Amoled Style
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = AmoledSurfaceLow.copy(alpha = 0.92f),
                border = BorderStroke(1.2.dp, activeAccentColor.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Header with Worker Name Badge
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = activeAccentColor.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, activeAccentColor.copy(alpha = 0.35f))
                        ) {
                            Text(
                                text = currentWorker.name,
                                color = activeAccentColor,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    // QR Code Frame
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(230.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF0F1522))
                            .border(BorderStroke(1.dp, AmoledBorder.copy(alpha = 0.9f)), RoundedCornerShape(20.dp))
                            .padding(14.dp)
                    ) {
                        if (qrBitmap != null) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = "QR код воркера",
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            CircularProgressIndicator(color = activeAccentColor, modifier = Modifier.size(36.dp))
                        }
                    }

                    // Domain address badge
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.White.copy(alpha = 0.04f),
                        border = BorderStroke(1.dp, Color(0xFF1E283D)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                clipboardManager.setText(AnnotatedString(currentWorker.domain))
                                Toast.makeText(context, "Домен скопирован в буфер обмена", Toast.LENGTH_SHORT).show()
                            }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Домен узла",
                                    color = TextMuted,
                                    fontSize = 10.5.sp
                                )
                                Text(
                                    text = currentWorker.domain,
                                    color = TextWhite,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Icon(
                                painter = painterResource(id = R.drawable.ic_copy),
                                contentDescription = "Копировать",
                                tint = activeAccentColor,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // Quick Actions Buttons Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Share to Telegram / Social Apps
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = activeAccentColor.copy(alpha = 0.16f),
                    border = BorderStroke(1.2.dp, activeAccentColor.copy(alpha = 0.7f)),
                    modifier = Modifier
                        .weight(1.3f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onShare(currentWorker)
                        })
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_send),
                            contentDescription = null,
                            tint = activeAccentColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Отправить другу",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = activeAccentColor
                        )
                    }
                }

                // Copy Link
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = AmoledSurfaceLow,
                    border = BorderStroke(1.dp, AmoledBorder),
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            clipboardManager.setText(AnnotatedString(deepLink))
                            Toast.makeText(context, "Ссылка mirrly:// скопирована", Toast.LENGTH_SHORT).show()
                        })
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(horizontal = 10.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_copy),
                            contentDescription = null,
                            tint = TextWhite,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Ссылка",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextWhite
                        )
                    }
                }
            }

            // Info Card
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.White.copy(alpha = 0.03f),
                border = BorderStroke(1.dp, AmoledBorder.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_info_circle),
                        contentDescription = null,
                        tint = activeAccentColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Ваш друг может отсканировать этот QR-код на соседней вкладке «Сканер» или перейти по скопированной ссылке для автоматического добавления узла.",
                        color = TextMuted,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun ScannerWorkerContent(
    activeAccentColor: Color,
    onQrScanned: (String) -> Unit,
    onPasteFromClipboard: () -> Unit,
    headerPadding: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                top = headerPadding + 8.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
                start = 16.dp,
                end = 16.dp
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Camera Viewport Card
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = AmoledSurfaceLow.copy(alpha = 0.92f),
            border = BorderStroke(1.2.dp, activeAccentColor.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (hasCameraPermission) {
                    CameraQrScannerView(
                        activeAccentColor = activeAccentColor,
                        onScanned = { rawText ->
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onQrScanned(rawText)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(290.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .border(BorderStroke(1.dp, AmoledBorder), RoundedCornerShape(20.dp))
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                            .background(AmoledSurface, RoundedCornerShape(20.dp))
                            .border(BorderStroke(1.dp, AmoledBorder), RoundedCornerShape(20.dp))
                            .padding(20.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_diag_worker),
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Доступ к камере отключен",
                            color = TextWhite,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Разрешите доступ к камере в настройках устройства для работы сканера.",
                            color = TextMuted,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = activeAccentColor.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, activeAccentColor.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .springPress(onClick = {
                                    try {
                                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = Uri.fromParts("package", context.packageName, null)
                                        }
                                        context.startActivity(intent)
                                    } catch (_: Exception) {
                                        permissionLauncher.launch(Manifest.permission.CAMERA)
                                    }
                                })
                        ) {
                            Text(
                                text = "Предоставить доступ",
                                color = activeAccentColor,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                }

                // Subtitle
                Text(
                    text = "Наведите камеру на QR-код из консоли деплоя (.bat) или из приложения друга.",
                    color = TextMuted,
                    fontSize = 11.5.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 16.sp
                )
            }
        }

        // Quick Action: Paste from clipboard
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = AmoledSurfaceLow,
            border = BorderStroke(1.dp, AmoledBorder),
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .springPress(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onPasteFromClipboard()
                })
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 14.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_copy),
                    contentDescription = null,
                    tint = activeAccentColor,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Вставить адрес или ссылку из буфера",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite
                )
            }
        }
    }
}

