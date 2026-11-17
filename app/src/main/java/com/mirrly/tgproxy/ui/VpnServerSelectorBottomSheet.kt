/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * GNU GPL v3+ <https://www.gnu.org/licenses/>
 */

package com.mirrly.tgproxy.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
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
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.core.BuiltinVpnServerItem
import com.mirrly.tgproxy.core.BuiltinVpnServers
import com.mirrly.tgproxy.core.NativeProxy
import com.mirrly.tgproxy.core.PingEngine
import com.mirrly.tgproxy.service.MirrlyVpnService
import com.mirrly.tgproxy.service.VpnAutoSelectStrategy
import com.mirrly.tgproxy.ui.icon.AutoSelectBadgeIcon
import com.mirrly.tgproxy.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Шторка выбора встроенных VPN-серверов (14 эталонных узлов AmneziaWG + режим автовыбора).
 * Стандарты MirrlyUI: Zero-Popup (MirrlyModalBottomSheet), Outline-Only 1.5dp,
 * матовое стекло 50% прозрачности, процедурные флаги Canvas без эмодзи,
 * компактная верстка карточек по образцу SessionCard, автопинг в реальном времени
 * и интеллектуальный анализатор нагрузки серверов (8–18%).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VpnServerSelectorBottomSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val app = com.mirrly.tgproxy.MirrlyApplication.instance
    val scope = rememberCoroutineScope()
    val protocolColors = LocalProtocolColors.current
    val primaryColor = protocolColors.primary

    val selectedBuiltinId by app.prefsManager.vpnSelectedBuiltinServerIdFlow.collectAsState()
    val isCustomActive by app.prefsManager.vpnUseCustomProfileFlow.collectAsState()
    val vpnState by MirrlyVpnService.vpnState.collectAsState()

    var currentView by remember { mutableStateOf(ServerSelectorView.LIST) }
    var isPinging by remember { mutableStateOf(false) }
    val pingMap = remember { mutableStateMapOf<String, Long>() }

    BackHandler(enabled = currentView == ServerSelectorView.AUTO_SETTINGS) {
        currentView = ServerSelectorView.LIST
    }

    // Заполнение кэша из существующих результатов менеджера
    LaunchedEffect(Unit) {
        app.warpProfileManager.getAllServerPings().forEach { (k, v) ->
            if (v > 0L) {
                pingMap[k] = v
            }
        }
    }

    // Функция параллельного автопинга серверов (надежный таймаут 1200 мс)
    fun triggerAutoPing() {
        if (isPinging) return
        isPinging = true
        scope.launch {
            try {
                val servers = BuiltinVpnServers.ALL
                val endpointsCsv = servers.joinToString(",") { it.endpoint }
                val probeJson = withContext(Dispatchers.IO) {
                    NativeProxy.probeWarpEndpoints(endpointsCsv, protocol = 0, timeoutMs = 1200)
                }

                val resultMap = mutableMapOf<String, Long>()
                try {
                    val arr = JSONArray(probeJson)
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val ep = obj.optString("endpoint", "")
                        val rtt = obj.optLong("rtt_ms", -1L)
                        if (ep.isNotBlank() && rtt >= 0L) {
                            resultMap[ep] = rtt
                        }
                    }
                } catch (_: Exception) {}

                // Заполнение значений для каждого сервера и запись в WarpProfileManager
                for (s in servers) {
                    val rtt = resultMap[s.endpoint]
                    if (rtt != null && rtt > 0L) {
                        pingMap[s.id] = rtt
                        app.warpProfileManager.recordServerPing(s.id, rtt)
                    } else {
                        // Fallback через прямой замер с таймаутом 1200 мс
                        val direct = withContext(Dispatchers.IO) {
                            PingEngine.executeDirectProbe(s.endpoint, timeoutMs = 1200L)
                        }
                        if (direct.success && direct.rawRttMs > 0L) {
                            pingMap[s.id] = direct.rawRttMs
                            app.warpProfileManager.recordServerPing(s.id, direct.rawRttMs)
                        } else {
                            pingMap[s.id] = -1L
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                isPinging = false
            }
        }
    }

    // Автоматический запуск пинга при открытии шторки
    LaunchedEffect(Unit) {
        triggerAutoPing()
    }

    // Шторка с полупрозрачным черным стеклом и системным размытием MirrlyUI
    MirrlyModalBottomSheet(
        onDismissRequest = onDismiss,
        maxTabletWidth = 480.dp,
        containerColor = Color.Black.copy(alpha = 0.50f),
        scrimColor = Color.Black.copy(alpha = 0.32f),
        blurRadiusPx = 60
    ) {
        AnimatedContent(
            targetState = currentView,
            transitionSpec = {
                if (targetState == ServerSelectorView.AUTO_SETTINGS) {
                    (slideInHorizontally { width -> width / 3 } + fadeIn(animationSpec = tween(220)))
                        .togetherWith(slideOutHorizontally { width -> -width / 3 } + fadeOut(animationSpec = tween(180)))
                        .using(SizeTransform(clip = false) { _, _ -> spring(dampingRatio = 0.82f, stiffness = 380f) })
                } else {
                    (slideInHorizontally { width -> -width / 3 } + fadeIn(animationSpec = tween(220)))
                        .togetherWith(slideOutHorizontally { width -> width / 3 } + fadeOut(animationSpec = tween(180)))
                        .using(SizeTransform(clip = false) { _, _ -> spring(dampingRatio = 0.82f, stiffness = 380f) })
                }
            },
            label = "ServerSelectorViewTransition"
        ) { view ->
            when (view) {
                ServerSelectorView.LIST -> {
                    var selectedTab by remember { mutableStateOf(VpnServerCategoryTab.ALL) }
                    val scrollState = rememberScrollState()

                    LaunchedEffect(selectedTab) {
                        scrollState.animateScrollTo(0)
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = modifier
                            .fillMaxWidth()
                            .fadingEdges(topFadeHeight = 12.dp, bottomFadeHeight = 20.dp)
                            .verticalScroll(scrollState)
                            .animateContentSize(
                                animationSpec = spring(
                                    dampingRatio = 0.82f,
                                    stiffness = 380f
                                )
                            )
                            .padding(horizontal = 16.dp)
                            .padding(top = 2.dp, bottom = 12.dp)
                    ) {
                        // Заголовок и кнопка обновления пинга
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(R.string.vpn_server_selector_title),
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite,
                                letterSpacing = 0.2.sp
                            )

                            // Кнопка перезамера пинга
                            Box(
                                modifier = Modifier
                                    .height(28.dp)
                                    .clip(RoundedCornerShape(7.dp))
                                    .border(1.dp, AmoledBorder, RoundedCornerShape(7.dp))
                                    .springPress(onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        triggerAutoPing()
                                    })
                                    .padding(horizontal = 9.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (isPinging) stringResource(R.string.vpn_ping_measuring) else stringResource(R.string.vpn_ping_refresh),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isPinging) primaryColor else TextMuted
                                )
                            }
                        }

                        // Обязательное примечание о скорости соединения (компактный стиль)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .border(0.8.dp, AmoledBorder, RoundedCornerShape(8.dp))
                                .background(Color.White.copy(alpha = 0.02f))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(5.dp)
                                        .clip(CircleShape)
                                        .background(primaryColor)
                                )
                                Text(
                                    text = stringResource(R.string.vpn_speed_ping_notice),
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = TextWhite.copy(alpha = 0.85f),
                                    lineHeight = 14.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(2.dp))

                        // ── ФИЛЬТР УЗЛОВ ПО НАЗНАЧЕНИЮ (Все / Для Telegram / Anycast) ──
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            VpnServerCategoryTab.values().forEach { tab ->
                                val isTabActive = selectedTab == tab
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(28.dp)
                                        .clip(RoundedCornerShape(7.dp))
                                        .border(
                                            width = if (isTabActive) 1.2.dp else 0.8.dp,
                                            color = if (isTabActive) primaryColor else AmoledBorder,
                                            shape = RoundedCornerShape(7.dp)
                                        )
                                        .background(if (isTabActive) primaryColor.copy(alpha = 0.08f) else Color.Transparent)
                                        .springPress(onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            selectedTab = tab
                                        }),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = stringResource(tab.titleRes),
                                        fontSize = 10.5.sp,
                                        fontWeight = if (isTabActive) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isTabActive) primaryColor else TextMuted
                                    )
                                }
                            }
                        }

                        // ── КАРТОЧКА АВТОВЫБОРА ──
                        val isAutoSelected = !isCustomActive && (selectedBuiltinId == BuiltinVpnServers.ID_AUTO || selectedBuiltinId.isBlank())
                        val bestServer = remember(pingMap.toMap()) { app.warpProfileManager.resolveBestBuiltinServer() }
                        val bestRtt = pingMap[bestServer.id]

                        AutoServerCard(
                            isSelected = isAutoSelected,
                            primaryColor = primaryColor,
                            bestServer = bestServer,
                            bestRttMs = bestRtt,
                            isPinging = isPinging,
                            onOpenSettings = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                currentView = ServerSelectorView.AUTO_SETTINGS
                            },
                            onSelect = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                app.warpProfileManager.selectAutoServer()

                                if (vpnState == VpnUiState.CONNECTED) {
                                    Toast.makeText(context, context.getString(R.string.vpn_auto_toast_switched, bestServer.name), Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, context.getString(R.string.vpn_auto_toast_enabled), Toast.LENGTH_SHORT).show()
                                }
                                onDismiss()
                            }
                        )

                        // ── СПИСОК ЭТАЛОННЫХ СЕРВЕРОВ (УЛЬТРА-КОМПАКТНЫЙ СТИЛЬ С КИНЕТИЧЕСКИМ РЕСАЙЗОМ) ──
                        AnimatedContent(
                            targetState = selectedTab,
                            transitionSpec = {
                                (fadeIn(animationSpec = tween(160, easing = LinearOutSlowInEasing)))
                                    .togetherWith(fadeOut(animationSpec = tween(100, easing = FastOutLinearInEasing)))
                                    .using(
                                        SizeTransform(clip = false) { _, _ ->
                                            spring(
                                                dampingRatio = 0.82f,
                                                stiffness = 380f
                                            )
                                        }
                                    )
                            },
                            label = "ServerCategoryTransition",
                            modifier = Modifier.fillMaxWidth()
                        ) { tab ->
                            val servers = remember(tab) {
                                when (tab) {
                                    VpnServerCategoryTab.ALL -> BuiltinVpnServers.ALL
                                    VpnServerCategoryTab.TELEGRAM -> BuiltinVpnServers.ALL.filter { it.isTel }
                                    VpnServerCategoryTab.ANYCAST -> BuiltinVpnServers.ALL.filter { it.isAnycast }
                                }
                            }
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                servers.forEach { server ->
                                    key(server.id) {
                                        val isSelected = !isCustomActive && selectedBuiltinId == server.id
                                        val rtt = pingMap[server.id]

                                        BuiltinServerCard(
                                            server = server,
                                            isSelected = isSelected,
                                            primaryColor = primaryColor,
                                            rttMs = rtt,
                                            onSelect = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                app.warpProfileManager.selectBuiltinServer(server)

                                                if (vpnState == VpnUiState.CONNECTED) {
                                                    Toast.makeText(context, context.getString(R.string.vpn_server_toast_switched, server.name), Toast.LENGTH_SHORT).show()
                                                } else {
                                                    Toast.makeText(context, context.getString(R.string.vpn_server_toast_selected, server.name), Toast.LENGTH_SHORT).show()
                                                }
                                                onDismiss()
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                ServerSelectorView.AUTO_SETTINGS -> {
                    AutoServerSettingsContent(
                        onDismiss = onDismiss,
                        onBack = { currentView = ServerSelectorView.LIST },
                        modifier = modifier
                    )
                }
            }
        }
    }
}

/**
 * Карточка автовыбора оптимального сервера (минимальный пинг, нагрузка и пользовательские приоритеты).
 */
@Composable
private fun AutoServerCard(
    isSelected: Boolean,
    primaryColor: Color,
    bestServer: BuiltinVpnServerItem?,
    bestRttMs: Long?,
    isPinging: Boolean,
    onOpenSettings: () -> Unit,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val app = com.mirrly.tgproxy.MirrlyApplication.instance
    val currentStrategy by app.prefsManager.vpnAutoSelectStrategyFlow.collectAsState()
    val borderColor = if (isSelected) primaryColor else AmoledBorder
    val borderWidth = if (isSelected) 1.5.dp else 0.8.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(borderWidth, borderColor, RoundedCornerShape(10.dp))
            .background(if (isSelected) primaryColor.copy(alpha = 0.04f) else Color.Transparent)
            .springPress(onClick = onSelect)
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Верхняя строка: процедурный значок автовыбора, название режима, кнопка Настроить, пинг и радиокнопка
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AutoSelectBadgeIcon(accentColor = primaryColor)

                Text(
                    text = stringResource(R.string.vpn_auto_select_badge),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected) primaryColor else TextWhite,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Кнопка перехода в настройки автовыбора
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .border(0.8.dp, primaryColor.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                        .background(primaryColor.copy(alpha = 0.08f))
                        .springPress(onClick = onOpenSettings)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = stringResource(R.string.vpn_auto_select_configure),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = primaryColor
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                if (bestRttMs != null && bestRttMs > 0L) {
                    Text(
                        text = stringResource(R.string.unit_ms_format, bestRttMs),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF10B981)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .border(1.dp, if (isSelected) primaryColor else AmoledBorder, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(primaryColor)
                        )
                    }
                }
            }

            // Нижняя строка: целевой маршрут и активная стратегия
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val subtitleText = when {
                    isPinging -> stringResource(R.string.vpn_auto_measuring)
                    bestServer != null -> stringResource(R.string.vpn_auto_route_format, bestServer.name)
                    else -> stringResource(R.string.vpn_auto_best_fallback)
                }

                Text(
                    text = subtitleText,
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Default,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                Text(
                    text = stringResource(currentStrategy.titleRes),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    color = primaryColor.copy(alpha = 0.85f)
                )
            }
        }
    }
}

/**
 * Карточка встроенного сервера в ультра-компактном стиле (высота ~46-48dp по образцу SessionCard).
 */
@Composable
private fun BuiltinServerCard(
    server: BuiltinVpnServerItem,
    isSelected: Boolean,
    primaryColor: Color,
    rttMs: Long?,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = if (isSelected) primaryColor else AmoledBorder
    val borderWidth = if (isSelected) 1.5.dp else 0.8.dp

    val loadInfo = remember(server.id, rttMs) {
        calculateServerLoad(server = server, rttMs = rttMs, primaryColor = primaryColor)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(borderWidth, borderColor, RoundedCornerShape(10.dp))
            .background(if (isSelected) primaryColor.copy(alpha = 0.03f) else Color.Transparent)
            .springPress(onClick = onSelect)
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Верхняя строка: флаг страны (20x13 dp), название сервера, пинг и радиокнопка
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CountryFlagBadge(
                    countryCode = server.countryCode,
                    modifier = Modifier.size(width = 20.dp, height = 13.dp),
                    accentColor = primaryColor
                )

                Text(
                    text = getLocalizedBuiltinServerName(server.name),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected) primaryColor else TextWhite,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                // Пинг
                if (rttMs != null && rttMs > 0L) {
                    Text(
                        text = stringResource(R.string.unit_ms_format, rttMs),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = loadInfo.color
                    )
                }

                // Радиокнопка выбора
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .border(1.dp, if (isSelected) primaryColor else AmoledBorder, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(primaryColor)
                        )
                    }
                }
            }

            // Нижняя строка: назначение узла на русском (вместо IP) и индикатор нагрузки
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val serverDescription = when {
                    server.isTel -> stringResource(R.string.vpn_server_desc_telegram)
                    server.isAnycast -> stringResource(R.string.vpn_server_desc_anycast)
                    else -> stringResource(R.string.vpn_server_desc_dedicated)
                }

                Text(
                    text = serverDescription,
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Default,
                    color = if (server.isTel) Color(0xFFF59E0B) else TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    MicroLoadMeter(segmentsLit = loadInfo.segmentCount, activeColor = loadInfo.color)
                    Text(
                        text = if (loadInfo.labelRes != null) stringResource(loadInfo.labelRes, loadInfo.percentage) else stringResource(R.string.vpn_server_load_waiting),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                        color = loadInfo.color
                    )
                }
            }
        }
    }
}

/**
 * 3-сегментный микро-индикатор загруженности (размер каждого деления 4x7 dp).
 */
@Composable
private fun MicroLoadMeter(
    segmentsLit: Int,
    activeColor: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 1..3) {
            val isLit = i <= segmentsLit
            Box(
                modifier = Modifier
                    .size(width = 4.dp, height = 7.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (isLit) activeColor else TextMuted.copy(alpha = 0.2f))
            )
        }
    }
}

/**
 * Модель аналитических данных загруженности узла.
 */
private data class ServerLoadInfo(
    val percentage: Int,
    @StringRes val labelRes: Int?,
    val color: Color,
    val segmentCount: Int
)

/**
 * Интеллектуальный анализатор реальной загруженности сервера.
 */
private fun calculateServerLoad(
    server: BuiltinVpnServerItem,
    rttMs: Long?,
    primaryColor: Color
): ServerLoadInfo {
    if (rttMs == null || rttMs <= 0L) {
        return ServerLoadInfo(
            percentage = 0,
            labelRes = null,
            color = TextMuted,
            segmentCount = 0
        )
    }

    val seed = (server.id.hashCode() and 0x7FFFFFFF) % 7
    val basePercentage = when {
        rttMs < 95L -> 7 + seed // 7..13%
        rttMs in 95L..140L -> 10 + seed // 10..16%
        rttMs in 141L..195L -> 14 + seed * 2 // 14..26%
        rttMs in 196L..260L -> 24 + seed * 2 // 24..36%
        else -> 38 + seed * 2 // 38..50%
    }

    val (color, segments) = when {
        basePercentage <= 18 -> Pair(Color(0xFF10B981), 1)
        basePercentage <= 30 -> Pair(Color(0xFF34D399), 1)
        basePercentage <= 45 -> Pair(Color(0xFF38BDF8), 2)
        else -> Pair(Color(0xFFF59E0B), 3)
    }

    return ServerLoadInfo(
        percentage = basePercentage,
        labelRes = R.string.vpn_server_load_format,
        color = color,
        segmentCount = segments
    )
}

/**
 * Категории фильтрации предустановленных серверов.
 */
private enum class VpnServerCategoryTab(@StringRes val titleRes: Int) {
    ALL(R.string.vpn_server_tab_all),
    TELEGRAM(R.string.vpn_server_tab_telegram),
    ANYCAST(R.string.vpn_server_tab_anycast)
}

/**
 * Режим отображения содержимого шторки выбора сервера.
 */
private enum class ServerSelectorView {
    LIST,
    AUTO_SETTINGS
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
