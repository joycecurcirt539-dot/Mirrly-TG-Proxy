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
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.core.TgConstants
import com.mirrly.tgproxy.ui.theme.*

private enum class GuideTab(val title: String) {
    PC("Компьютер"),
    PHONE("Андроид"),
    SCRIPT("Скрипт воркера"),
    FAQ("Преимущества и FAQ")
}

private data class GuideStepItem(
    val stepNumber: String,
    val title: String,
    val description: String,
    val actionText: String? = null,
    val isCopyAction: Boolean = false,
    val isDashAction: Boolean = false
)

private data class FaqItem(
    val title: String,
    val description: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudflareWorkerGuideScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val protoColors = LocalProtocolColors.current
    val activeProtoColor = protoColors.primary

    var selectedTab by remember { mutableStateOf(GuideTab.PC) }
    var headerHeightDp by remember { mutableStateOf(176.dp) }
    var showDashboardConfirmDialog by remember { mutableStateOf(false) }

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

    if (showDashboardConfirmDialog) {
        ExternalLinkConfirmDialog(
            url = "https://dash.cloudflare.com/",
            title = "Панель Cloudflare Dashboard",
            description = "Ссылка ведет на официальную веб-панель управления Cloudflare (dash.cloudflare.com) для создания и редактирования скрипта Worker.",
            onDismiss = { showDashboardConfirmDialog = false }
        )
    }

    fun copyScriptToClipboard() {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Cloudflare Worker Script", TgConstants.CLOUDFLARE_WORKER_JS_CODE)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Скрипт воркера скопирован в буфер обмена", Toast.LENGTH_SHORT).show()
    }

    val topGuideTabs = remember { listOf(GuideTab.PC, GuideTab.PHONE, GuideTab.SCRIPT) }
    val allGuideTabs = remember { listOf(GuideTab.PC, GuideTab.PHONE, GuideTab.SCRIPT, GuideTab.FAQ) }

    fun switchToNextTab() {
        val currentIndex = allGuideTabs.indexOf(selectedTab)
        if (currentIndex < allGuideTabs.size - 1) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            selectedTab = allGuideTabs[currentIndex + 1]
        }
    }

    fun switchToPreviousTab() {
        val currentIndex = allGuideTabs.indexOf(selectedTab)
        if (currentIndex > 0) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            selectedTab = allGuideTabs[currentIndex - 1]
        }
    }

    fun openCloudflareDashboard() {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        showDashboardConfirmDialog = true
    }

    val pcSteps = remember {
        listOf(
            GuideStepItem(
                stepNumber = "1",
                title = "Вход в Cloudflare Dashboard",
                description = "Откройте браузер на компьютере и перейдите на dash.cloudflare.com. Авторизуйтесь или создайте бесплатный аккаунт (банковская карта не требуется).",
                actionText = "Открыть Cloudflare Dashboard",
                isDashAction = true
            ),
            GuideStepItem(
                stepNumber = "2",
                title = "Создание нового Worker",
                description = "В левом боковом меню выберите раздел «Workers & Pages» (или «Compute (Workers)»). Нажмите синюю кнопку «Create Application», затем вкладку «Create Worker»."
            ),
            GuideStepItem(
                stepNumber = "3",
                title = "Базовое развертывание",
                description = "В поле имени укажите любое название (например: my-tg-proxy) и нажмите кнопку «Deploy» внизу страницы."
            ),
            GuideStepItem(
                stepNumber = "4",
                title = "Вставка готового скрипта",
                description = "На открывшейся странице созданного воркера нажмите кнопку «Edit Code» (Редактировать код). Полностью удалите стандартный шаблонный код из окна редактора.",
                actionText = "Скопировать скрипт воркера",
                isCopyAction = true
            ),
            GuideStepItem(
                stepNumber = "5",
                title = "Сохранение и публикация",
                description = "Вставьте скопированный код в редактор Cloudflare и в правом верхнем углу нажмите «Deploy» (или «Save and Deploy»)."
            ),
            GuideStepItem(
                stepNumber = "6",
                title = "Копирование адреса и вставка в Mirrly",
                description = "Скопируйте полученный публичный адрес (например: my-tg-proxy.yourname.workers.dev) и добавьте его в Менеджере воркеров приложения Mirrly TG Proxy."
            )
        )
    }

    val phoneSteps = remember {
        listOf(
            GuideStepItem(
                stepNumber = "1",
                title = "Откройте сайт Cloudflare на смартфоне",
                description = "Перейдите на dash.cloudflare.com в браузере телефона и войдите в свой аккаунт.",
                actionText = "Перейти на Cloudflare",
                isDashAction = true
            ),
            GuideStepItem(
                stepNumber = "2",
                title = "Перейдите в Workers & Pages",
                description = "В боковом меню выберите «Workers & Pages» -> нажмите «Create Application» -> «Create Worker»."
            ),
            GuideStepItem(
                stepNumber = "3",
                title = "Нажмите Deploy и Edit Code",
                description = "Нажмите кнопку «Deploy», затем «Edit Code» для открытия онлайн-редактора кода."
            ),
            GuideStepItem(
                stepNumber = "4",
                title = "Скопируйте и вставьте скрипт",
                description = "Нажмите кнопку ниже, чтобы скопировать скрипт, выделите весь текст в мобильном редакторе и вставьте скопированный код.",
                actionText = "Скопировать скрипт",
                isCopyAction = true
            ),
            GuideStepItem(
                stepNumber = "5",
                title = "Сохраните и вставьте домен в Mirrly",
                description = "Нажмите «Deploy». Скопируйте домен *.workers.dev и добавьте его в Менеджере воркеров приложения Mirrly."
            )
        )
    }

    val faqItems = remember {
        listOf(
            FaqItem(
                title = "100 000 бесплатных запросов каждый день",
                description = "Бесплатный тариф Cloudflare выделяет 100 000 обращений в сутки лично на ваш аккаунт, чего с избытком хватает для непрерывной переписки, видеозвонков и загрузки медиа."
            ),
            FaqItem(
                title = "Создание нескольких личных воркеров (до 100 узлов)",
                description = "Вы можете бесплатно создать до 100 отдельных воркеров на одном аккаунте (например: для смартфона, ноутбука, планшета или близких), добавить их все в Менеджер воркеров Mirrly и переключаться между ними в 1 клик."
            ),
            FaqItem(
                title = "100% Приватность и собственный шлюз",
                description = "Трафик не проходит через чужие прокси-серверы. Ваш личный воркер открывает сокеты напрямую к Telegram DC через глобальную сеть Cloudflare Anycast (300+ дата-центров)."
            ),
            FaqItem(
                title = "Работа звонков и аудио/видео (SOCKS5)",
                description = "Благодаря API cloudflare:sockets личный воркер поддерживает универсальный TCP-туннель к Telegram VoIP узлам, обеспечивая стабильную работу звонков без системного VPN."
            ),
            FaqItem(
                title = "Что делать, если Telegram долго подключается через воркер?",
                description = "В Cloudflare Dashboard откройте ваш воркер -> Settings -> Runtime. Убедитесь, что Compatibility Date установлена не ранее 2023-05-18 и включена опция Node.js compatibility (флаг nodejs_compat)."
            ),
            FaqItem(
                title = "Автоматический приоритет в приложении",
                description = "При добавлении и выборе своего воркера приложение автоматически направляет весь трафик SOCKS5 и MTProto через ваш узел с наивысшим приоритетом."
            )
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(selectedTab) {
                var horizontalAccumulator = 0f
                detectHorizontalDragGestures(
                    onDragStart = { horizontalAccumulator = 0f },
                    onDragEnd = {
                        if (horizontalAccumulator < -28.dp.toPx()) {
                            switchToNextTab()
                        } else if (horizontalAccumulator > 28.dp.toPx()) {
                            switchToPreviousTab()
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
        // 1. SCROLLABLE GUIDE FEED
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
            when (selectedTab) {
                GuideTab.PC -> {
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
                GuideTab.PHONE -> {
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
                GuideTab.SCRIPT -> {
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
                GuideTab.FAQ -> {
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

        // 3. PINNED FROSTED GLASS HEADER (Title Bar + 3 Tabs Filter Chips + Full-Width FAQ Chip)
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
                .padding(bottom = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Top App Bar
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "Инструкция Cloudflare",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = TextWhite,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Пошаговая настройка за 2 минуты",
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
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { copyScriptToClipboard() },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_copy),
                                contentDescription = "Скопировать код",
                                tint = activeProtoColor,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = TextWhite
                    ),
                    modifier = Modifier.statusBarsPadding()
                )

                // 1. Top 3 Filter Chips Row (PC, Phone, Script) - Stretched evenly across width
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    topGuideTabs.forEach { tab ->
                        val isSelected = selectedTab == tab
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = if (isSelected) activeProtoColor.copy(alpha = 0.12f) else Color.Transparent,
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (isSelected) activeProtoColor.copy(alpha = 0.7f) else Color(0xFF1E283D)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(34.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .springPress(onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    selectedTab = tab
                                })
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = tab.title,
                                    color = if (isSelected) activeProtoColor else TextMuted,
                                    fontSize = 11.5.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                // 2. Full-Width 4th Pill Under Top 3 Pills (Преимущества и FAQ)
                val isFaqSelected = selectedTab == GuideTab.FAQ
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (isFaqSelected) activeProtoColor.copy(alpha = 0.12f) else Color.Transparent,
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (isFaqSelected) activeProtoColor.copy(alpha = 0.7f) else Color(0xFF1E283D)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            selectedTab = GuideTab.FAQ
                        })
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = GuideTab.FAQ.title,
                            color = if (isFaqSelected) activeProtoColor else TextMuted,
                            fontSize = 11.5.sp,
                            fontWeight = if (isFaqSelected) FontWeight.Bold else FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
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
                // Step Number Badge
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
