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
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.core.TgConstants
import com.mirrly.tgproxy.ui.theme.*

private enum class GuideTab(val title: String) {
    PC("Компьютер (ПК)"),
    PHONE("Смартфон (Android)"),
    SCRIPT("Скрипт воркера"),
    ADVANTAGES("Преимущества и FAQ")
}

@Composable
fun CloudflareWorkerGuideScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var selectedTab by remember { mutableStateOf(GuideTab.PC) }
    var showDashboardConfirmDialog by remember { mutableStateOf(false) }

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

    fun openCloudflareDashboard() {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        showDashboardConfirmDialog = true
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onBack()
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.06f))
                            .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_arrow_left),
                            contentDescription = "Назад",
                            tint = TextWhite,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = "Личный Cloudflare Worker",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite
                        )
                        Text(
                            text = "Пошаговая инструкция за 2 минуты",
                            fontSize = 12.sp,
                            color = ActiveGreenLed
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Quick Action Buttons Bar
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color.White.copy(alpha = 0.04f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            onClick = { copyScriptToClipboard() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ActiveGreenLed,
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_copy),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Скопировать код",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        OutlinedButton(
                            onClick = { openCloudflareDashboard() },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = TextWhite
                            ),
                            border = BorderStroke(1.dp, Color(0xFFF38020).copy(alpha = 0.8f)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                        ) {
                            Text(
                                text = "Cloudflare Dash ↗",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFFA726)
                            )
                        }
                    }
                }
            }

            // Tab Selector
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GuideTab.values().forEach { tab ->
                    val isSelected = selectedTab == tab
                    val tabBorder by animateColorAsState(
                        targetValue = if (isSelected) ActiveGreenLed else Color(0xFF1E2333),
                        animationSpec = tween(180),
                        label = "tabBorder_${tab.name}"
                    )
                    val tabBg = if (isSelected) ActiveGreenLed.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.03f)

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(tabBg)
                            .border(1.dp, tabBorder, RoundedCornerShape(12.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                selectedTab = tab
                            }
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = tab.title,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) ActiveGreenLed else TextWhite.copy(alpha = 0.8f)
                        )
                    }
                }
            }

            // Animated Tab Content
            Crossfade(targetState = selectedTab, animationSpec = tween(220), label = "tabContent") { tab ->
                when (tab) {
                    GuideTab.PC -> PcGuideContent(
                        onCopyClick = { copyScriptToClipboard() },
                        onOpenDash = { openCloudflareDashboard() }
                    )
                    GuideTab.PHONE -> PhoneGuideContent(
                        onCopyClick = { copyScriptToClipboard() },
                        onOpenDash = { openCloudflareDashboard() }
                    )
                    GuideTab.SCRIPT -> ScriptPreviewContent(
                        onCopyClick = { copyScriptToClipboard() }
                    )
                    GuideTab.ADVANTAGES -> AdvantagesFaqContent()
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun PcGuideContent(
    onCopyClick: () -> Unit,
    onOpenDash: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        StepCard(
            stepNumber = "1",
            title = "Вход в Cloudflare Dashboard",
            description = "Откройте браузер на компьютере и перейдите на dash.cloudflare.com. Авторизуйтесь или создайте бесплатный аккаунт (банковская карта не требуется).",
            actionText = "Открыть Cloudflare Dashboard",
            onAction = onOpenDash
        )

        StepCard(
            stepNumber = "2",
            title = "Создание нового Worker",
            description = "В левом боковом меню выберите раздел «Workers & Pages» (или «Compute (Workers)»). Нажмите синюю кнопку «Create Application», затем вкладку «Create Worker»."
        )

        StepCard(
            stepNumber = "3",
            title = "Базовое развертывание",
            description = "В поле имени укажите любое название (например: my-tg-proxy) и нажмите кнопку «Deploy» внизу страницы."
        )

        StepCard(
            stepNumber = "4",
            title = "Вставка готового скрипта",
            description = "На открывшейся странице созданного воркера нажмите кнопку «Edit Code» (Редактировать код). Полностью удалите стандартный шаблонный код из окна редактора.",
            actionText = "Скопировать скрипт воркера",
            onAction = onCopyClick
        )

        StepCard(
            stepNumber = "5",
            title = "Сохранение и публикация",
            description = "Вставьте скопированный код в редактор Cloudflare и в правом верхнем углу нажмите «Deploy» (или «Save and Deploy»)."
        )

        StepCard(
            stepNumber = "6",
            title = "Копирование адреса и вставка в Mirrly",
            description = "Скопируйте полученный публичный адрес (например: my-tg-proxy.yourname.workers.dev) и вставьте его в приложении Mirrly TG Proxy в поле «Кастомный Cloudflare Worker»."
        )
    }
}

@Composable
private fun PhoneGuideContent(
    onCopyClick: () -> Unit,
    onOpenDash: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF0F172A).copy(alpha = 0.6f),
            border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(text = "💡", fontSize = 16.sp)
                Text(
                    text = "Совет: В мобильном браузере (Chrome / Firefox) включите в меню флажок «Версия для ПК», если интерфейс редактора Cloudflare покажется компактным.",
                    fontSize = 12.sp,
                    color = Color(0xFFBAE6FD),
                    lineHeight = 17.sp
                )
            }
        }

        StepCard(
            stepNumber = "1",
            title = "Откройте сайт Cloudflare на смартфоне",
            description = "Перейдите на dash.cloudflare.com в браузере телефона и войдите в свой аккаунт.",
            actionText = "Перейти на Cloudflare",
            onAction = onOpenDash
        )

        StepCard(
            stepNumber = "2",
            title = "Перейдите в Workers & Pages",
            description = "В боковом меню выберите «Workers & Pages» -> нажмите «Create Application» -> «Create Worker»."
        )

        StepCard(
            stepNumber = "3",
            title = "Нажмите Deploy и Edit Code",
            description = "Нажмите кнопку «Deploy», затем «Edit Code» для открытия онлайн-редактора кода."
        )

        StepCard(
            stepNumber = "4",
            title = "Скопируйте и вставьте скрипт",
            description = "Нажмите кнопку ниже, чтобы скопировать скрипт, выделите весь текст в мобильном редакторе и вставьте скопированный код.",
            actionText = "Скопировать скрипт",
            onAction = onCopyClick
        )

        StepCard(
            stepNumber = "5",
            title = "Сохраните и вставьте домен в Mirrly",
            description = "Нажмите «Deploy». Скопируйте домен *.workers.dev и вставьте в Настройки приложения Mirrly в поле «Кастомный Cloudflare Worker»."
        )
    }
}

@Composable
private fun ScriptPreviewContent(
    onCopyClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "ОПТИМИЗИРОВАННЫЙ СКРИПТ V8",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = ActiveGreenLed,
                letterSpacing = 1.sp
            )

            Button(
                onClick = onCopyClick,
                colors = ButtonDefaults.buttonColors(containerColor = ActiveGreenLed, contentColor = Color.Black),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(painter = painterResource(id = R.drawable.ic_copy), contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Копировать", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF090D16),
            border = BorderStroke(1.dp, Color(0xFF1E293B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "cloudflare_worker.js",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF38020)
                )

                Text(
                    text = TgConstants.CLOUDFLARE_WORKER_JS_CODE,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = TextWhite.copy(alpha = 0.85f),
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
private fun AdvantagesFaqContent() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        AdvantageCard(
            title = "100 000 бесплатных запросов каждый день",
            description = "Бесплатный тариф Cloudflare выделяет 100 000 обращений в сутки лично на ваш аккаунт, чего с избытком хватает для непрерывной переписки, видеозвонков и загрузки медиа."
        )

        AdvantageCard(
            title = "100% Приватность и собственный шлюз",
            description = "Трафик не проходит через чужие прокси-серверы. Ваш личный воркер открывает сокеты напрямую к Telegram DC через глобальную сеть Cloudflare Anycast (300+ дата-центров)."
        )

        AdvantageCard(
            title = "Работа звонков и аудио/видео (SOCKS5)",
            description = "Благодаря API cloudflare:sockets личный воркер поддерживает универсальный TCP-туннель к Telegram VoIP узлам, обеспечивая стабильную работу звонков без системного VPN."
        )

        AdvantageCard(
            title = "Автоматический приоритет в приложении",
            description = "При заполнении поля «Кастомный Cloudflare Worker» приложение автоматически направляет весь трафик SOCKS5 и MTProto через ваш воркер с наивысшим приоритетом."
        )
    }
}

@Composable
private fun StepCard(
    stepNumber: String,
    title: String,
    description: String,
    actionText: String? = null,
    onAction: (() -> Unit)? = null
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color.White.copy(alpha = 0.04f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(ActiveGreenLed.copy(alpha = 0.15f))
                        .border(1.dp, ActiveGreenLed, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stepNumber,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = ActiveGreenLed
                    )
                }

                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite,
                    modifier = Modifier.weight(1f)
                )
            }

            Text(
                text = description,
                fontSize = 13.sp,
                color = TextWhite.copy(alpha = 0.85f),
                lineHeight = 18.5.sp
            )

            if (actionText != null && onAction != null) {
                Button(
                    onClick = onAction,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = 0.08f),
                        contentColor = TextWhite
                    ),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().height(38.dp)
                ) {
                    Text(text = actionText, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun AdvantageCard(
    title: String,
    description: String
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color.White.copy(alpha = 0.04f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(ActiveGreenLed, CircleShape)
                )
                Text(
                    text = title,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite
                )
            }
            Text(
                text = description,
                fontSize = 12.5.sp,
                color = TextWhite.copy(alpha = 0.82f),
                lineHeight = 18.sp,
                modifier = Modifier.padding(start = 14.dp)
            )
        }
    }
}
