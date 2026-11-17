/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * GNU GPL v3+ <https://www.gnu.org/licenses/>
 */

package com.mirrly.tgproxy.ui

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.core.BuiltinVpnServerItem
import com.mirrly.tgproxy.core.BuiltinVpnServers
import com.mirrly.tgproxy.service.VpnAutoCategory
import com.mirrly.tgproxy.service.VpnAutoSelectStrategy
import com.mirrly.tgproxy.ui.icon.AutoSelectBadgeIcon
import com.mirrly.tgproxy.ui.icon.MirrlyIcon
import com.mirrly.tgproxy.ui.theme.*

/**
 * Шторка детальной настройки автовыбора серверов (MirrlyUI).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoServerSettingsBottomSheet(
    onDismiss: () -> Unit,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    MirrlyModalBottomSheet(
        onDismissRequest = onDismiss,
        maxTabletWidth = 480.dp,
        containerColor = Color.Black.copy(alpha = 0.50f),
        scrimColor = Color.Black.copy(alpha = 0.32f),
        blurRadiusPx = 60
    ) {
        AutoServerSettingsContent(
            onDismiss = onDismiss,
            onBack = onBack,
            modifier = modifier
        )
    }
}

/**
 * Содержимое экрана настроек автовыбора серверов (MirrlyUI).
 * Используется как автономно в AutoServerSettingsBottomSheet,
 * так и в составе единого навигационного контейнера VpnServerSelectorBottomSheet
 * для исключения наложения полупрозрачных слоев и обеспечения максимальной читаемости.
 */
@Composable
fun AutoServerSettingsContent(
    onDismiss: () -> Unit,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val app = com.mirrly.tgproxy.MirrlyApplication.instance
    val protocolColors = LocalProtocolColors.current
    val primaryColor = protocolColors.primary

    val currentStrategy by app.prefsManager.vpnAutoSelectStrategyFlow.collectAsState()
    val categoryOrder by app.prefsManager.vpnAutoSelectCategoryOrderFlow.collectAsState()
    val disabledServerIds by app.prefsManager.vpnAutoSelectDisabledServersFlow.collectAsState()

    var showServerPool by remember { mutableStateOf(false) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .fillMaxWidth()
            .fadingEdges(topFadeHeight = 12.dp, bottomFadeHeight = 20.dp)
            .verticalScroll(rememberScrollState())
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = 0.82f,
                    stiffness = 380f
                )
            )
            .padding(horizontal = 16.dp)
            .padding(top = 2.dp, bottom = 16.dp)
    ) {
        // Верхняя строка: кнопка Назад (если есть), заголовок, бейдж автовыбора и кнопка закрытия
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f, fill = false)
            ) {
                if (onBack != null) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .border(1.dp, AmoledBorder, CircleShape)
                            .springPress(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onBack()
                            }),
                        contentAlignment = Alignment.Center
                    ) {
                        MirrlyIcon(
                            id = R.drawable.ic_arrow_left,
                            modifier = Modifier.size(16.dp),
                            tint = primaryColor
                        )
                    }
                }

                AutoSelectBadgeIcon(accentColor = primaryColor)

                Column {
                    Text(
                        text = stringResource(R.string.vpn_auto_settings_title),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite,
                        letterSpacing = 0.2.sp
                    )
                    Text(
                        text = stringResource(R.string.vpn_auto_settings_sub),
                        fontSize = 9.5.sp,
                        color = TextMuted
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .border(1.dp, AmoledBorder, CircleShape)
                    .springPress(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onDismiss()
                    }),
                contentAlignment = Alignment.Center
            ) {
                MirrlyIcon(
                    id = R.drawable.ic_close,
                    modifier = Modifier.size(16.dp),
                    tint = TextWhite.copy(alpha = 0.8f)
                )
            }
        }

        // Информационный блок с высоким контрастом
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .border(0.8.dp, AmoledBorder, RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.04f))
                .padding(horizontal = 10.dp, vertical = 7.dp)
        ) {
            Text(
                text = stringResource(R.string.vpn_auto_settings_desc),
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Normal,
                color = TextWhite.copy(alpha = 0.88f),
                lineHeight = 14.5.sp
            )
        }

        // ── СЕКЦИЯ 1: СТРАТЕГИЯ АВТОВЫБОРА ──
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = stringResource(R.string.vpn_auto_sec_strategy),
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold,
                color = primaryColor,
                letterSpacing = 0.8.sp
            )

            VpnAutoSelectStrategy.values().forEach { strategy ->
                val isStrategyActive = currentStrategy == strategy
                val borderColor = if (isStrategyActive) primaryColor else AmoledBorder
                val borderWidth = if (isStrategyActive) 1.2.dp else 0.8.dp

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .border(borderWidth, borderColor, RoundedCornerShape(8.dp))
                        .background(if (isStrategyActive) primaryColor.copy(alpha = 0.08f) else Color.Transparent)
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            app.prefsManager.setVpnAutoSelectStrategy(strategy)
                        })
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Радиокнопка
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .border(1.dp, if (isStrategyActive) primaryColor else AmoledBorder, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isStrategyActive) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(primaryColor)
                                )
                            }
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(strategy.titleRes),
                                fontSize = 12.sp,
                                fontWeight = if (isStrategyActive) FontWeight.Bold else FontWeight.SemiBold,
                                color = if (isStrategyActive) primaryColor else TextWhite
                            )
                            Text(
                                text = stringResource(strategy.subtitleRes),
                                fontSize = 9.5.sp,
                                color = TextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // ── СЕКЦИЯ 2: ПРИОРИТЕТ КАТЕГОРИЙ (ВВЕРХ / ВНИЗ) ──
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.vpn_auto_sec_categories),
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = primaryColor,
                    letterSpacing = 0.8.sp
                )

                if (currentStrategy != VpnAutoSelectStrategy.CUSTOM_ORDER) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .border(0.6.dp, primaryColor.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                            .background(primaryColor.copy(alpha = 0.08f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.vpn_auto_custom_active_badge),
                            fontSize = 8.5.sp,
                            color = primaryColor
                        )
                    }
                }
            }

            categoryOrder.forEachIndexed { index, catKey ->
                val cat = when (catKey) {
                    "TEL" -> VpnAutoCategory.TEL
                    "ANYCAST" -> VpnAutoCategory.ANYCAST
                    else -> VpnAutoCategory.DEDICATED
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .border(0.8.dp, AmoledBorder, RoundedCornerShape(8.dp))
                        .background(Color.Transparent)
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Порядковый номер приоритета
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(primaryColor.copy(alpha = 0.15f))
                                .border(0.8.dp, primaryColor.copy(alpha = 0.6f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${index + 1}",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = primaryColor
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(cat.titleRes),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextWhite
                            )
                            Text(
                                text = stringResource(cat.descriptionRes),
                                fontSize = 9.sp,
                                color = TextMuted
                            )
                        }

                        // Кнопки перемещения приоритета ВВЕРХ / ВНИЗ
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            val canMoveUp = index > 0
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .border(0.8.dp, if (canMoveUp) primaryColor.copy(alpha = 0.5f) else AmoledBorder.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                                    .background(if (canMoveUp) primaryColor.copy(alpha = 0.08f) else Color.Transparent)
                                    .clickable(enabled = canMoveUp) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        app.prefsManager.moveAutoSelectCategoryUp(catKey)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                MirrlyIcon(
                                    id = R.drawable.ic_arrow_up,
                                    modifier = Modifier.size(14.dp),
                                    tint = if (canMoveUp) primaryColor else TextMuted.copy(alpha = 0.4f)
                                )
                            }

                            val canMoveDown = index < categoryOrder.size - 1
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .border(0.8.dp, if (canMoveDown) primaryColor.copy(alpha = 0.5f) else AmoledBorder.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                                    .background(if (canMoveDown) primaryColor.copy(alpha = 0.08f) else Color.Transparent)
                                    .clickable(enabled = canMoveDown) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        app.prefsManager.moveAutoSelectCategoryDown(catKey)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                MirrlyIcon(
                                    id = R.drawable.ic_arrow_down,
                                    modifier = Modifier.size(14.dp),
                                    tint = if (canMoveDown) primaryColor else TextMuted.copy(alpha = 0.4f)
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── СЕКЦИЯ 3: ИСКЛЮЧЕНИЕ СЕРВЕРОВ ИЗ ПУЛА ──
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Заголовок аккордеона
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(7.dp))
                    .border(0.8.dp, AmoledBorder, RoundedCornerShape(7.dp))
                    .background(Color.Transparent)
                    .springPress(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        showServerPool = !showServerPool
                    })
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val enabledCount = BuiltinVpnServers.ALL.size - disabledServerIds.size
                Text(
                    text = stringResource(R.string.vpn_auto_sec_pool, enabledCount, BuiltinVpnServers.ALL.size),
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = primaryColor,
                    letterSpacing = 0.6.sp
                )

                MirrlyIcon(
                    id = if (showServerPool) R.drawable.ic_arrow_up else R.drawable.ic_arrow_down,
                    modifier = Modifier.size(16.dp),
                    tint = primaryColor
                )
            }

            AnimatedVisibility(
                visible = showServerPool,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    // Кнопки быстрого включения/отключения
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(26.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .border(0.8.dp, AmoledBorder, RoundedCornerShape(6.dp))
                                .background(Color.White.copy(alpha = 0.05f))
                                .springPress(onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    app.prefsManager.setVpnAutoSelectDisabledServers(emptySet())
                                }),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.vpn_auto_btn_enable_all),
                                fontSize = 10.sp,
                                color = TextWhite
                            )
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(26.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .border(0.8.dp, AmoledBorder, RoundedCornerShape(6.dp))
                                .background(Color.White.copy(alpha = 0.05f))
                                .springPress(onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    val nonTel = BuiltinVpnServers.ALL.filter { !it.isTel }.map { it.id }.toSet()
                                    app.prefsManager.setVpnAutoSelectDisabledServers(nonTel)
                                }),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.vpn_auto_btn_tel_only, 3),
                                fontSize = 10.sp,
                                color = Color(0xFFF59E0B)
                            )
                        }
                    }

                    // Список всех 14 узлов с чекбоксами
                    BuiltinVpnServers.ALL.forEach { server ->
                        val isServerEnabled = server.id !in disabledServerIds
                        val serverPing = app.warpProfileManager.getServerPing(server.id)

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(7.dp))
                                .border(
                                    width = if (isServerEnabled) 0.9.dp else 0.6.dp,
                                    color = if (isServerEnabled) AmoledBorder else AmoledBorder.copy(alpha = 0.35f),
                                    shape = RoundedCornerShape(7.dp)
                                )
                                .background(if (isServerEnabled) primaryColor.copy(alpha = 0.03f) else Color.Transparent)
                                .springPress(onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    app.prefsManager.toggleVpnAutoSelectServer(server.id, !isServerEnabled)
                                })
                                .padding(horizontal = 9.dp, vertical = 6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Чекбокс
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .border(
                                            width = 1.dp,
                                            color = if (isServerEnabled) primaryColor else AmoledBorder,
                                            shape = RoundedCornerShape(4.dp)
                                        )
                                        .background(if (isServerEnabled) primaryColor.copy(alpha = 0.15f) else Color.Transparent),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isServerEnabled) {
                                        MirrlyIcon(
                                            id = R.drawable.ic_check,
                                            modifier = Modifier.size(12.dp),
                                            tint = primaryColor
                                        )
                                    }
                                }

                                CountryFlagBadge(
                                    countryCode = server.countryCode,
                                    modifier = Modifier.size(width = 20.dp, height = 13.dp),
                                    accentColor = primaryColor
                                )

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = getLocalizedBuiltinServerName(server.name),
                                        fontSize = 11.5.sp,
                                        fontWeight = if (isServerEnabled) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isServerEnabled) TextWhite else TextMuted
                                    )
                                    Text(
                                        text = if (server.isTel) stringResource(R.string.vpn_server_desc_telegram) else if (server.isAnycast) stringResource(R.string.vpn_server_desc_anycast) else stringResource(R.string.vpn_server_desc_dedicated),
                                        fontSize = 8.5.sp,
                                        color = if (server.isTel) Color(0xFFF59E0B) else TextMuted
                                    )
                                }

                                if (serverPing != null && serverPing > 0L) {
                                    Text(
                                        text = stringResource(R.string.unit_ms_format, serverPing),
                                        fontSize = 10.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isServerEnabled) Color(0xFF10B981) else TextMuted.copy(alpha = 0.5f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // ── КНОПКИ ДЕЙСТВИЙ (СБРОС И ПРИМЕНИТЬ) ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(0.8.dp, AmoledBorder, RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.04f))
                    .springPress(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        app.prefsManager.resetVpnAutoSelectSettings()
                        Toast.makeText(context, context.getString(R.string.vpn_auto_toast_reset), Toast.LENGTH_SHORT).show()
                    }),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.vpn_auto_btn_reset),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextMuted
                )
            }

            Box(
                modifier = Modifier
                    .weight(1.2f)
                    .height(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.2.dp, primaryColor, RoundedCornerShape(8.dp))
                    .background(primaryColor.copy(alpha = 0.15f))
                    .springPress(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        Toast.makeText(context, context.getString(R.string.vpn_auto_toast_applied), Toast.LENGTH_SHORT).show()
                        if (onBack != null) onBack() else onDismiss()
                    }),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.vpn_auto_btn_apply),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = primaryColor
                )
            }
        }
    }
}

@Composable
private fun getLocalizedBuiltinServerName(name: String): String {
    return when (name) {
        "По умолчанию" -> stringResource(R.string.vpn_server_name_default)
        "Альтернативный" -> stringResource(R.string.vpn_server_name_alt)
        "Германия" -> stringResource(R.string.vpn_country_germany)
        "Нидерланды" -> stringResource(R.string.vpn_country_netherlands)
        "Польша" -> stringResource(R.string.vpn_country_poland)
        "Россия" -> stringResource(R.string.vpn_country_russia)
        "Финляндия" -> stringResource(R.string.vpn_country_finland)
        "Эстония" -> stringResource(R.string.vpn_country_estonia)
        "Латвия" -> stringResource(R.string.vpn_country_latvia)
        "Франкфурт 1" -> stringResource(R.string.vpn_server_frankfurt_1)
        "Франкфурт 2" -> stringResource(R.string.vpn_server_frankfurt_2)
        "TEL Германия" -> stringResource(R.string.vpn_server_tel_germany)
        "TEL Польша" -> stringResource(R.string.vpn_server_tel_poland)
        "TEL Финляндия" -> stringResource(R.string.vpn_server_tel_finland)
        else -> name
    }
}
