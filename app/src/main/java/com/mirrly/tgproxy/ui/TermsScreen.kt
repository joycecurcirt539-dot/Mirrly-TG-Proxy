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
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    var selectedLanguage by remember { mutableStateOf("ru") }

    val fullTermsTextEn = remember {
        """
        TERMS OF USE & ADDITIONAL CONDITIONS

        Project: Mirrly TG Proxy
        Author & Copyright: R1Xern (Mirrly Dev)
        Base License: GNU GPLv3

        1. PREAMBLE
        These Terms of Use establish additional conditions under Section 7 of GNU GPLv3. Any use, modification, or distribution of this software constitutes full acceptance of these terms.

        2. BAN ON MALWARE AND FRAUD
        It is strictly prohibited to use this source code or its binaries to build, embed, or distribute malware, viruses, trojans, hidden cryptocurrency miners, spyware, or phishing tools, or to secretly intercept user traffic.

        3. TRADEMARK & BRAND PROTECTION
        The names "Mirrly", "Mirrly Dev", "Mirrly TG Proxy", the author's handle "R1Xern", and all official icons and branding assets are intellectual property of the author. This license grants NO permission to use these trademarks in forks or third-party builds without written consent.

        4. FORK TRANSPARENCY & LABELLING
        Any third-party fork or modified build MUST be released under a distinct name not containing "Mirrly" and MUST display a clear notice stating:
        "This product is an unofficial fork based on Mirrly TG Proxy code and is not affiliated with or endorsed by Mirrly Dev."

        5. DISCLAIMER FOR THIRD-PARTY BUILDS
        The author bears zero liability for the security, integrity, or performance of any third-party APK builds or modifications downloaded outside the official GitHub repository (joycecurcirt539-dot/Mirrly-TG-Proxy).

        6. TERMINATION
        Any breach of these terms automatically terminates all permissions granted under this license.
        """.trimIndent()
    }

    val fullTermsTextRu = remember {
        """
        ПОЛЬЗОВАТЕЛЬСКОЕ СОГЛАШЕНИЕ И ДОПОЛНИТЕЛЬНЫЕ УСЛОВИЯ ИСПОЛЬЗОВАНИЯ

        Проект: Mirrly TG Proxy
        Автор и правообладатель: R1Xern (Mirrly Dev)
        Базовая лицензия: GNU GPLv3

        1. ПРЕАМБУЛА
        Настоящий документ устанавливает дополнительные условия в соответствии с Разделом 7 лицензии GNU GPLv3. Любое использование или форк кода означает согласие с данными правилами.

        2. ЗАПРЕТ НА ВРЕДОНОСНОЕ ПО И МОШЕННИЧЕСТВО
        Категорически запрещено использовать исходный код или сборки для создания, внедрения или распространения вредоносного ПО (малвари), вирусов, скрытых майнеров, фишинга или тайного перехвата трафика пользователей.

        3. ЗАЩИТА ТОВАРНОГО ЗНАКА И БРЕНДА
        Наименования «Mirrly», «Mirrly Dev», «Mirrly TG Proxy», имя автора «R1Xern» и официальная символика являются интеллектуальной собственностью. Запрещено использовать их в форках без письменного согласия правообладателя.

        4. ПРОЗРАЧНОСТЬ И МАРКИРОВКА ФОРКОВ
        Любые третьи форки или сборки ДОЛЖНЫ быть выпущены под собственным именем без слова «Mirrly» и содержать явное уведомление:
        «Данный продукт является неофициальным форком на основе кода Mirrly TG Proxy и не поддерживается оригинальным автором Mirrly Dev.»

        5. ОТКАЗ ОТ ОТВЕТСТВЕННОСТИ ЗА СТОРОННИЕ СБОРКИ
        Правообладатель не несет ответственности за безопасность и работоспособность сторонних APK-сборок, распространенных вне официального репозитория GitHub (joycecurcirt539-dot/Mirrly-TG-Proxy).

        6. АННУЛИРОВАНИЕ ПРАВ
        Нарушение любых условий автоматически аннулирует все права на использование исходного кода проекта.
        """.trimIndent()
    }

    val activeTermsText = if (selectedLanguage == "ru") fullTermsTextRu else fullTermsTextEn

    fun openGitHubTerms() {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        try {
            val url = "https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/blob/main/TERMS_OF_USE.md"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Не удалось открыть ссылку: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    fun copyTermsToClipboard() {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val label = if (selectedLanguage == "ru") "Условия использования (Русская версия)" else "Terms of Use (English)"
        val clip = ClipData.newPlainText(label, activeTermsText)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Текст условий скопирован!", Toast.LENGTH_SHORT).show()
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // 1. SCROLLABLE CONTENT LAYER
        Column(
            modifier = Modifier
                .fillMaxSize()
                .fadingEdges(topFadeHeight = 24.dp, bottomFadeHeight = 44.dp)
                .verticalScroll(rememberScrollState())
                .padding(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 20.dp
                )
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {

            // SECTION 1: HEADER CARD
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .staggeredEntrance(index = 0)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.Transparent)
                    .border(1.dp, Color(0xFF181E2E), RoundedCornerShape(24.dp))
                    .padding(20.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(ActiveGreenLed.copy(alpha = 0.15f))
                                .border(1.5.dp, ActiveGreenLed.copy(alpha = 0.5f), CircleShape)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_license),
                                contentDescription = null,
                                tint = ActiveGreenLed,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Column {
                            Text(
                                text = "Условия использования",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black,
                                color = TextWhite
                            )
                            Text(
                                text = "Дополнительная защита • R1Xern (Mirrly Dev)",
                                fontSize = 12.5.sp,
                                color = TextMuted
                            )
                        }
                    }

                    Text(
                        text = "Дополнительные правила и правила добросовестного использования, действующие в дополнение к базовой лицензии GNU GPLv3 для максимальной защиты авторских прав и пользователей.",
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = TextWhite.copy(alpha = 0.85f)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { openGitHubTerms() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Transparent,
                                contentColor = ActiveGreenLed
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, ActiveGreenLed),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f).height(42.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_github),
                                contentDescription = null,
                                tint = ActiveGreenLed,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("GitHub", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }

                        Button(
                            onClick = { copyTermsToClipboard() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF141A29),
                                contentColor = TextWhite
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1F283D)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f).height(42.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_copy),
                                contentDescription = null,
                                tint = TextWhite,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Скопировать", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }

            // SECTION 2: PROTECTION HIGHLIGHTS
            Column(
                modifier = Modifier.staggeredEntrance(index = 1),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "КЛЮЧЕВЫЕ ПРАВИЛА И ЗАЩИТА",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.3.sp,
                    color = TextMuted
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Transparent)
                        .border(1.dp, Color(0xFF181E2E), RoundedCornerShape(20.dp))
                        .padding(18.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ProtectionRow(title = "Запрет малвари", desc = "Запрещено встраивание вирусов, троянов и скрытых майнеров")
                        ProtectionRow(title = "Защита бренда", desc = "Запрещено использовать имя Mirrly и логотипы в форках")
                        ProtectionRow(title = "Маркировка форков", desc = "Форки обязаны содержать дисклеймер о неофициальном статусе")
                        ProtectionRow(title = "Безопасность", desc = "Официальные релизы распространяются строго на GitHub")
                        HorizontalDivider(color = Color(0xFF161A26), thickness = 1.dp, modifier = Modifier.padding(vertical = 4.dp))
                        Text(
                            text = "Нарушение условий ведет к автоматической аннулиции лицензионных прав на использование кода.",
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = TextMuted
                        )
                    }
                }
            }

            // SECTION 3: FULL TERMS TEXT
            Column(
                modifier = Modifier.staggeredEntrance(index = 2),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ПОЛНЫЙ ТЕКСТ СОГЛАШЕНИЯ",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.3.sp,
                        color = TextMuted
                    )

                    Text(
                        text = if (selectedLanguage == "ru") "Перевод" else "Original",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = ActiveGreenLed
                    )
                }

                // Language Switcher
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF0D111A))
                        .border(1.dp, Color(0xFF181E2E), RoundedCornerShape(14.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val isRu = selectedLanguage == "ru"
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isRu) ActiveGreenLed.copy(alpha = 0.18f) else Color.Transparent)
                            .border(if (isRu) 1.dp else 0.dp, if (isRu) ActiveGreenLed.copy(alpha = 0.6f) else Color.Transparent, RoundedCornerShape(10.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                selectedLanguage = "ru"
                            }
                    ) {
                        Text(
                            text = "🇷🇺 Русский",
                            fontSize = 12.5.sp,
                            fontWeight = if (isRu) FontWeight.Bold else FontWeight.Medium,
                            color = if (isRu) ActiveGreenLed else TextMuted
                        )
                    }

                    val isEn = selectedLanguage == "en"
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isEn) ActiveGreenLed.copy(alpha = 0.18f) else Color.Transparent)
                            .border(if (isEn) 1.dp else 0.dp, if (isEn) ActiveGreenLed.copy(alpha = 0.6f) else Color.Transparent, RoundedCornerShape(10.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                selectedLanguage = "en"
                            }
                    ) {
                        Text(
                            text = "🇬🇧 English",
                            fontSize = 12.5.sp,
                            fontWeight = if (isEn) FontWeight.Bold else FontWeight.Medium,
                            color = if (isEn) ActiveGreenLed else TextMuted
                        )
                    }
                }

                // Terms Text Display Box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF080B12))
                        .border(1.dp, Color(0xFF181E2E), RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    Crossfade(targetState = selectedLanguage, animationSpec = tween(220), label = "termsCrossfade") { lang ->
                        val textToDisplay = if (lang == "ru") fullTermsTextRu else fullTermsTextEn
                        Text(
                            text = textToDisplay,
                            fontSize = 11.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextWhite.copy(alpha = 0.88f),
                            lineHeight = 17.5.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // 2. FROSTED GLASS HEADER PANEL
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.98f),
                            Color.Black.copy(alpha = 0.94f),
                            Color.Black.copy(alpha = 0.72f),
                            Color.Black.copy(alpha = 0.00f)
                        )
                    )
                )
        ) {
            TopAppBar(
                title = {
                    Text(
                        text = "Условия использования",
                        color = TextWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    }
}

@Composable
private fun ProtectionRow(title: String, desc: String) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(ActiveGreenLed.copy(alpha = 0.2f))
        ) {
            Text(
                text = "✓",
                color = ActiveGreenLed,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Column {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = TextWhite
            )
            Text(
                text = desc,
                fontSize = 12.sp,
                color = TextMuted
            )
        }
    }
}
