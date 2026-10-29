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
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.ui.theme.*

/**
 * Поддерживаемые производители и оболочки Android.
 */
enum class SupportedVendorOs(
    val title: String,
    val badge: String
) {
    XIAOMI(
        title = "Xiaomi / Redmi / POCO",
        badge = "HyperOS / MIUI"
    ),
    SAMSUNG(
        title = "Samsung Galaxy",
        badge = "One UI"
    ),
    HUAWEI(
        title = "Huawei / Honor",
        badge = "HarmonyOS / EMUI"
    ),
    BBK_OPPO_REALME(
        title = "Realme / OPPO / OnePlus",
        badge = "Realme UI / ColorOS / OxygenOS"
    ),
    VIVO(
        title = "Vivo / iQOO",
        badge = "OriginOS / Funtouch OS"
    ),
    PIXEL_STOCK(
        title = "Google Pixel / Чистый Android",
        badge = "Pixel UI / AOSP / Motorola / Nothing"
    )
}

data class VendorGuideStep(
    val number: Int,
    val title: String,
    val description: String,
    val tip: String? = null
)

@Composable
fun AutostartGuideDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val app = MirrlyApplication.instance
    val currentLang = remember { app.prefsManager.getAppLanguage() }

    var selectedOs by remember { mutableStateOf(SupportedVendorOs.XIAOMI) }
    var isDropdownExpanded by remember { mutableStateOf(false) }

    val chevronRotation by animateFloatAsState(
        targetValue = if (isDropdownExpanded) 180f else 0f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f),
        label = "osChevronRotation"
    )

    fun openAppSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val intent = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(context, e.localizedMessage ?: "Failed to open settings", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val steps = remember(selectedOs, currentLang) {
        getGuideStepsForOs(selectedOs, currentLang)
    }

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
            onDismiss = onDismiss,
            blurRadiusPx = 70
        ) {
            // Кнопка «Назад» в верхнем левом углу
            IconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onDismiss()
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(top = 12.dp, start = 12.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_arrow_left),
                    contentDescription = stringResource(R.string.action_back),
                    tint = TextWhite,
                    modifier = Modifier.size(22.dp)
                )
            }

            // Основной прокручиваемый контент
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .align(Alignment.Center)
                    .adaptiveContainerWidth(440.dp)
                    .padding(horizontal = 20.dp)
                    .navigationBarsPadding()
                    .padding(bottom = 32.dp)
                    .verticalScroll(rememberScrollState())
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {}
            ) {
                Spacer(modifier = Modifier.height(28.dp))

                // Категория (Badge)
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = ActiveGreenLed.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, ActiveGreenLed.copy(alpha = 0.40f))
                ) {
                    Text(
                        text = stringResource(R.string.autostart_guide_badge),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = ActiveGreenLed,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
                    )
                }

                // Заголовок
                Text(
                    text = stringResource(R.string.autostart_guide_title),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite,
                    textAlign = TextAlign.Center,
                    letterSpacing = 0.3.sp
                )

                // Описание принципа работы
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.autostart_how_it_works_title),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.5.sp,
                            color = ActiveGreenLed
                        )
                        Text(
                            text = stringResource(R.string.autostart_how_it_works_desc),
                            fontSize = 12.5.sp,
                            lineHeight = 18.sp,
                            color = TextWhite.copy(alpha = 0.88f)
                        )
                    }
                }

                // Предупреждение о версиях Android и прошивок
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, Color(0xFFF5A623).copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_info_circle),
                            contentDescription = null,
                            tint = Color(0xFFF5A623),
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = stringResource(R.string.autostart_version_warning),
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = Color(0xFFF5A623).copy(alpha = 0.95f)
                        )
                    }
                }

                // Заголовок селектора ОС
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.autostart_select_os_label),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextMuted
                    )
                }

                // Выпадающий список выбора ОС (Dropdown Card)
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.2.dp, ActiveGreenLed.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            isDropdownExpanded = !isDropdownExpanded
                        }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 13.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = selectedOs.title,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.5.sp,
                                color = TextWhite
                            )
                            Text(
                                text = selectedOs.badge,
                                fontSize = 12.sp,
                                color = ActiveGreenLed,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Icon(
                            painter = painterResource(id = R.drawable.ic_arrow_down),
                            contentDescription = null,
                            tint = ActiveGreenLed,
                            modifier = Modifier
                                .size(18.dp)
                                .rotate(chevronRotation)
                        )
                    }
                }

                // Список вариантов при раскрытии селектора
                AnimatedVisibility(
                    visible = isDropdownExpanded,
                    enter = expandVertically(tween(250)) + fadeIn(tween(200)),
                    exit = shrinkVertically(tween(200)) + fadeOut(tween(150))
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SupportedVendorOs.values().forEach { os ->
                            val isItemCurrent = os == selectedOs
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isItemCurrent) ActiveGreenLed.copy(alpha = 0.12f) else Color.Transparent,
                                border = BorderStroke(
                                    1.dp,
                                    if (isItemCurrent) ActiveGreenLed.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.08f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        selectedOs = os
                                        isDropdownExpanded = false
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 11.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = os.title,
                                            fontWeight = if (isItemCurrent) FontWeight.Bold else FontWeight.Medium,
                                            fontSize = 13.5.sp,
                                            color = if (isItemCurrent) TextWhite else TextWhite.copy(alpha = 0.8f)
                                        )
                                        Text(
                                            text = os.badge,
                                            fontSize = 11.sp,
                                            color = if (isItemCurrent) ActiveGreenLed else TextMuted
                                        )
                                    }
                                    if (isItemCurrent) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_check),
                                            contentDescription = null,
                                            tint = ActiveGreenLed,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Карточки пошаговой инструкции для выбранной ОС
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    steps.forEach { step ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = ActiveGreenLed.copy(alpha = 0.15f),
                                    border = BorderStroke(1.dp, ActiveGreenLed.copy(alpha = 0.5f)),
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = step.number.toString(),
                                            color = ActiveGreenLed,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                    }
                                }
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = step.title,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = TextWhite
                                    )
                                    Text(
                                        text = step.description,
                                        fontSize = 12.5.sp,
                                        lineHeight = 18.sp,
                                        color = TextWhite.copy(alpha = 0.86f)
                                    )
                                    if (step.tip != null) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = Color.Transparent,
                                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                text = step.tip,
                                                fontSize = 11.5.sp,
                                                lineHeight = 16.sp,
                                                color = TextMuted,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Кнопка быстрого перехода в системные настройки приложения (полупрозрачная на 50%)
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        openAppSettings()
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ActiveGreenLed.copy(alpha = 0.50f),
                        contentColor = TextWhite
                    ),
                    border = BorderStroke(1.dp, ActiveGreenLed.copy(alpha = 0.70f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .springPress()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_settings),
                            contentDescription = null,
                            tint = TextWhite,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = stringResource(R.string.autostart_btn_open_settings),
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

/**
 * Возвращает проверенные и актуальные пошаговые шаги настройки под каждую систему.
 */
private fun getGuideStepsForOs(os: SupportedVendorOs, lang: String): List<VendorGuideStep> {
    val isRu = lang == "ru"
    return when (os) {
        SupportedVendorOs.XIAOMI -> if (isRu) {
            listOf(
                VendorGuideStep(
                    number = 1,
                    title = "Автозапуск в фоновом режиме",
                    description = "Откройте «Настройки» -> «Приложения» -> «Разрешения» -> «Автозапуск в фоновом режиме» (или «Автозапуск») и включите тумблер для Mirrly TG Proxy.",
                    tip = "Быстрый путь: долгое нажатие на иконку Mirrly на рабочем столе -> «О приложении» -> включить «Автозапуск в фоне»."
                ),
                VendorGuideStep(
                    number = 2,
                    title = "Контроль активности (Батарея)",
                    description = "В свойствах приложения («О приложении») найдите пункт «Контроль активности» и переключите с «Умный режим» на «Нет ограничений». Это защитит службу от выгрузки системой при блокировке экрана."
                ),
                VendorGuideStep(
                    number = 3,
                    title = "Закрепление в оперативной памяти",
                    description = "Откройте экран недавних задач (свайп снизу с удержанием). Зажмите карточку Mirrly TG Proxy и нажмите на появившийся значок замочка."
                ),
                VendorGuideStep(
                    number = 4,
                    title = "Отключение гибернации",
                    description = "В «О приложении» -> «Разрешения» в самом низу отключите тумблер «Приостанавливать работу, если не используется»."
                )
            )
        } else {
            listOf(
                VendorGuideStep(
                    number = 1,
                    title = "Background Autostart",
                    description = "Go to Settings -> Apps -> Permissions -> Background autostart (or Autostart) and toggle ON for Mirrly TG Proxy.",
                    tip = "Shortcut: Long-press the Mirrly app icon -> App info -> enable Autostart in background."
                ),
                VendorGuideStep(
                    number = 2,
                    title = "Battery Saver: No Restrictions",
                    description = "In App info, tap Battery saver and select 'No restrictions' instead of 'MIUI/HyperOS battery saver'."
                ),
                VendorGuideStep(
                    number = 3,
                    title = "Lock in Memory (Padlock)",
                    description = "Open the Recent Apps multitasking screen, long-press the Mirrly card, and tap the padlock icon."
                ),
                VendorGuideStep(
                    number = 4,
                    title = "Disable App Hibernation",
                    description = "In App info -> App permissions, scroll down and turn OFF 'Pause app activity if unused'."
                )
            )
        }

        SupportedVendorOs.SAMSUNG -> if (isRu) {
            listOf(
                VendorGuideStep(
                    number = 1,
                    title = "Батарея «Не ограничено» (Unrestricted)",
                    description = "Откройте «Настройки» -> «Приложения» -> «Mirrly TG Proxy» -> «Батарея» -> выберите режим «Не ограничено»."
                ),
                VendorGuideStep(
                    number = 2,
                    title = "Никогда не спящие приложения",
                    description = "Перейдите в «Настройки» -> «Батарея» (или «Обслуживание устройства» -> «Батарея») -> «Ограничения в фоновом режиме» -> «Никогда не спящие приложения» -> нажмите «+» и добавьте Mirrly TG Proxy."
                ),
                VendorGuideStep(
                    number = 3,
                    title = "Фоновые мобильные данные",
                    description = "В «Настройки» -> «Приложения» -> «Mirrly TG Proxy» -> «Мобильные данные» включите «Разрешить фоновые данные» и «Разрешить при экономии трафика»."
                ),
                VendorGuideStep(
                    number = 4,
                    title = "Закрепление в памяти (Keep open)",
                    description = "Откройте меню запущенных приложений, нажмите на круглую иконку Mirrly над карточкой и выберите «Оставить открытым»."
                )
            )
        } else {
            listOf(
                VendorGuideStep(
                    number = 1,
                    title = "Battery: Unrestricted",
                    description = "Go to Settings -> Apps -> Mirrly TG Proxy -> Battery -> select 'Unrestricted'."
                ),
                VendorGuideStep(
                    number = 2,
                    title = "Never Auto Sleeping Apps",
                    description = "Go to Settings -> Battery (or Device Care -> Battery) -> Background usage limits -> Never auto sleeping apps -> tap '+' and add Mirrly TG Proxy."
                ),
                VendorGuideStep(
                    number = 3,
                    title = "Background Mobile Data",
                    description = "In App info -> Mobile data, enable 'Allow background data usage' and 'Allow data usage while Data saver is on'."
                ),
                VendorGuideStep(
                    number = 4,
                    title = "Lock in Task Switcher",
                    description = "In Recent Apps, tap the circular Mirrly icon above its card and select 'Keep open'."
                )
            )
        }

        SupportedVendorOs.HUAWEI -> if (isRu) {
            listOf(
                VendorGuideStep(
                    number = 1,
                    title = "Ручной запуск приложений (App launch)",
                    description = "Откройте «Настройки» -> «Батарея» -> «Запуск приложений» (или через приложение «Оптимизатор» / «Диспетчер телефона»). Найдите Mirrly TG Proxy и отключите тумблер «Автоматическое управление»."
                ),
                VendorGuideStep(
                    number = 2,
                    title = "Активация всех тумблеров запуска",
                    description = "В появившемся окне «Управление вручную» обязательно включите все три пункта: «Автозапуск», «Косвенный запуск» и «Работа в фоновом режиме»."
                ),
                VendorGuideStep(
                    number = 3,
                    title = "Исключение из оптимизации батареи",
                    description = "Откройте «Настройки» -> «Приложения» -> «Приложения» -> меню из трех точек вверху -> «Специальный доступ» -> «Оптимизация батареи» -> переключите на «Все приложения» -> Mirrly TG Proxy -> выберите «Запретить» (Не оптимизировать)."
                ),
                VendorGuideStep(
                    number = 4,
                    title = "Замок в недавних задачах",
                    description = "В меню недавних приложений потяните карточку Mirrly вниз до появления иконки замка."
                )
            )
        } else {
            listOf(
                VendorGuideStep(
                    number = 1,
                    title = "Manage App Launch Manually",
                    description = "Go to Settings -> Battery -> App launch (or Optimizer -> App launch). Find Mirrly TG Proxy and turn off 'Manage automatically'."
                ),
                VendorGuideStep(
                    number = 2,
                    title = "Enable All Startup Toggles",
                    description = "In the pop-up 'Manage manually', toggle ON: 'Auto-launch', 'Secondary launch', and 'Run in background'."
                ),
                VendorGuideStep(
                    number = 3,
                    title = "Battery Optimization: Don't allow",
                    description = "Settings -> Apps -> Apps -> three dots -> Special access -> Battery optimization -> All apps -> Mirrly TG Proxy -> set to 'Don't allow'."
                ),
                VendorGuideStep(
                    number = 4,
                    title = "Lock in Recent Apps",
                    description = "In the multitasking screen, swipe down on the Mirrly card until the padlock icon appears."
                )
            )
        }

        SupportedVendorOs.BBK_OPPO_REALME -> if (isRu) {
            listOf(
                VendorGuideStep(
                    number = 1,
                    title = "Включение автозапуска (Auto launch)",
                    description = "Откройте «Настройки» -> «Приложения» -> «Автозапуск» (Auto-launch). Найдите Mirrly TG Proxy и включите тумблер. Нажмите на карточку приложения и разрешите «Связанный запуск»."
                ),
                VendorGuideStep(
                    number = 2,
                    title = "Разрешение фоновой активности",
                    description = "Перейдите в «Настройки» -> «Приложения» -> «Управление приложениями» -> «Mirrly TG Proxy» -> «Использование батареи» -> включите «Разрешить работу в фоновом режиме» и «Разрешить автозапуск»."
                ),
                VendorGuideStep(
                    number = 3,
                    title = "Отключение оптимизации батареи",
                    description = "Откройте «Настройки» -> «Батарея» -> «Дополнительные настройки» -> «Оптимизация использования батареи» -> найдите Mirrly TG Proxy и выберите «Не оптимизировать»."
                ),
                VendorGuideStep(
                    number = 4,
                    title = "Блокировка от выгрузки в недавних",
                    description = "В окне запущенных приложений нажмите на три точки (⋮) в верхнем углу карточки Mirrly TG Proxy и выберите «Заблокировать» (Lock)."
                )
            )
        } else {
            listOf(
                VendorGuideStep(
                    number = 1,
                    title = "Enable Auto-Launch",
                    description = "Go to Settings -> Apps -> Auto launch. Toggle ON for Mirrly TG Proxy and enable 'Allow associated startup'."
                ),
                VendorGuideStep(
                    number = 2,
                    title = "Allow Background Activity",
                    description = "Settings -> Apps -> App management -> Mirrly TG Proxy -> Battery usage -> turn ON 'Allow background activity' and 'Allow auto-launch'."
                ),
                VendorGuideStep(
                    number = 3,
                    title = "Don't Optimize Battery",
                    description = "Settings -> Battery -> More settings -> Optimize battery usage -> find Mirrly TG Proxy and select 'Don't optimize'."
                ),
                VendorGuideStep(
                    number = 4,
                    title = "Lock in Recent Apps",
                    description = "In the multitasking task switcher, tap the three dots (⋮) on the Mirrly card and select 'Lock'."
                )
            )
        }

        SupportedVendorOs.VIVO -> if (isRu) {
            listOf(
                VendorGuideStep(
                    number = 1,
                    title = "Разрешение автозапуска",
                    description = "Откройте «Настройки» -> «Приложения» -> «Специальный доступ» -> «Автозапуск» (или через встроенный «iManager» -> «Управление приложениями» -> «Менеджер автозапуска») и включите тумблер для Mirrly TG Proxy."
                ),
                VendorGuideStep(
                    number = 2,
                    title = "Высокое энергопотребление в фоне",
                    description = "Откройте «Настройки» -> «Батарея» -> «Управление фоновым энергопотреблением» (или «Высокое энергопотребление в фоне») -> выберите Mirrly TG Proxy -> установите «Разрешить высокое энергопотребление в фоновом режиме»."
                ),
                VendorGuideStep(
                    number = 3,
                    title = "Снятие ограничений батареи",
                    description = "В «Настройки» -> «Приложения» -> «Все приложения» -> «Mirrly TG Proxy» -> «Батарея» выберите вариант «Без ограничений»."
                ),
                VendorGuideStep(
                    number = 4,
                    title = "Закрепление в недавних задачах",
                    description = "В меню запущенных приложений нажмите на три точки или значок над карточкой Mirrly TG Proxy и выберите «Заблокировать» (Lock)."
                )
            )
        } else {
            listOf(
                VendorGuideStep(
                    number = 1,
                    title = "Autostart Permission",
                    description = "Go to Settings -> Apps -> Special app access -> Autostart (or open iManager -> App management -> Autostart manager) and toggle ON for Mirrly TG Proxy."
                ),
                VendorGuideStep(
                    number = 2,
                    title = "High Background Power Consumption",
                    description = "Settings -> Battery -> Background power consumption management -> find Mirrly TG Proxy and select 'Don't restrict background power usage'."
                ),
                VendorGuideStep(
                    number = 3,
                    title = "Battery: Unrestricted",
                    description = "In App info -> Battery, set the preference to 'Unrestricted'."
                ),
                VendorGuideStep(
                    number = 4,
                    title = "Lock in Multitasking",
                    description = "In Recent Apps, tap the three dots above the Mirrly preview card and select 'Lock'."
                )
            )
        }

        SupportedVendorOs.PIXEL_STOCK -> if (isRu) {
            listOf(
                VendorGuideStep(
                    number = 1,
                    title = "Батарея «Без ограничений» (Unrestricted)",
                    description = "Зажмите иконку Mirrly TG Proxy на рабочем столе -> нажмите «О приложении» (значок ⓘ) -> «Использование батареи приложением» -> выберите режим «Без ограничений»."
                ),
                VendorGuideStep(
                    number = 2,
                    title = "Отключение приостановки активности",
                    description = "В меню «О приложении» прокрутите в самый низ и отключите параметр «Приостанавливать работу, если не используется»."
                ),
                VendorGuideStep(
                    number = 3,
                    title = "Неограниченные фоновые данные",
                    description = "В «О приложении» -> «Мобильный интернет и Wi-Fi» включите «Фоновые данные» и «Неограниченный трафик»."
                ),
                VendorGuideStep(
                    number = 4,
                    title = "Разблокировка после перезагрузки",
                    description = "Из-за шифрования хранилища Android (FBE) автоматический запуск службы после перезагрузки смартфона срабатывает сразу после первого ввода пароля или PIN-кода на экране блокировки."
                )
            )
        } else {
            listOf(
                VendorGuideStep(
                    number = 1,
                    title = "Battery: Unrestricted",
                    description = "Long-press the Mirrly icon -> App info (ⓘ) -> App battery usage -> select 'Unrestricted'."
                ),
                VendorGuideStep(
                    number = 2,
                    title = "Disable App Hibernation",
                    description = "In App info, scroll to the bottom and turn OFF 'Pause app activity if unused'."
                ),
                VendorGuideStep(
                    number = 3,
                    title = "Unrestricted Background Data",
                    description = "In App info -> Mobile data & Wi-Fi, enable 'Background data' and 'Unrestricted data usage'."
                ),
                VendorGuideStep(
                    number = 4,
                    title = "First Unlock Requirement",
                    description = "Due to Android File-Based Encryption (Direct Boot), autostart on system boot triggers right after you unlock the screen with your PIN/password for the first time."
                )
            )
        }
    }
}
