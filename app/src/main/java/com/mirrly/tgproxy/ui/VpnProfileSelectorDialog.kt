/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * GNU GPL v3+ <https://www.gnu.org/licenses/>
 */

package com.mirrly.tgproxy.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.core.WarpGeneratorFormat
import com.mirrly.tgproxy.core.resolveCountryCodeForEndpoint
import com.mirrly.tgproxy.service.MirrlyVpnService
import com.mirrly.tgproxy.service.WarpSavedProfile
import com.mirrly.tgproxy.ui.theme.*

/**
 * Экран выбора и управления VPN-профилями по стандартам MirrlyUI:
 * - Zero-Popup: нативный MirrlyModalBottomSheet без устаревших диалогов и DialogBackdropBox.
 * - Zero-Background / Outline-Only: 100% AMOLED-контуры, без полупрозрачных и цветных заливок.
 * - Пружинная кинетика: Modifier.springPress на всех карточках, чипах и кнопках действий.
 * - Anti-Slop: строгая кибернетическая типографика, отсутствие эмодзи и маркетингового пафоса.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun VpnProfileSelectorDialog(
    vpnColors: ProtocolColors,
    onDismiss: () -> Unit,
    onOpenGenerator: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val app = com.mirrly.tgproxy.MirrlyApplication.instance
    val profileManager = app.warpProfileManager
    val savedProfiles by profileManager.profilesFlow.collectAsState()
    val activeProfileId by profileManager.activeProfileIdFlow.collectAsState()
    val vpnState by MirrlyVpnService.vpnState.collectAsState()

    val isAutoProfileEnabled by app.prefsManager.vpnAutoProfileEnabledFlow.collectAsState()
    val effectiveAutoProfile = remember(savedProfiles, isAutoProfileEnabled, activeProfileId) {
        profileManager.getEffectiveActiveProfile()
    }

    // ── Поиск и фильтры ──
    var isSearchVisible by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedFormatFilter by remember { mutableStateOf<String?>(null) }
    val searchFocusRequester = remember { FocusRequester() }

    // ── Пакетное удаление (Batch Deletion Mode) ──
    var isBatchDeleteMode by remember { mutableStateOf(false) }
    val selectedForDeleteIds = remember { mutableStateListOf<String>() }

    // ── Состояния подтверждения удаления (MirrlyModalBottomSheet) ──
    var profileToDelete by remember { mutableStateOf<WarpSavedProfile?>(null) }
    var showDeleteAllConfirmDialog by remember { mutableStateOf(false) }
    var showBatchDeleteConfirmDialog by remember { mutableStateOf(false) }

    val revealedConfigIds = remember { mutableStateListOf<String>() }

    val awgCount = remember(savedProfiles) { savedProfiles.count { it.format.isAwg } }
    val wgCount = remember(savedProfiles) { savedProfiles.count { it.format == WarpGeneratorFormat.WIREGUARD } }
    val otherCount = remember(savedProfiles) { savedProfiles.count { !it.format.isAwg && it.format != WarpGeneratorFormat.WIREGUARD } }

    val filteredProfiles = remember(savedProfiles, searchQuery, selectedFormatFilter) {
        savedProfiles.filter { sp ->
            val matchesSearch = if (searchQuery.isBlank()) true else {
                val q = searchQuery.trim().lowercase()
                sp.name.lowercase().contains(q) ||
                sp.endpoint.lowercase().contains(q) ||
                sp.format.title.lowercase().contains(q) ||
                resolveCountryCodeForEndpoint(sp.endpoint).lowercase().contains(q)
            }
            val matchesFormat = when (selectedFormatFilter) {
                "AWG" -> sp.format.isAwg
                "WG" -> sp.format == WarpGeneratorFormat.WIREGUARD
                "OTHER" -> !sp.format.isAwg && sp.format != WarpGeneratorFormat.WIREGUARD
                else -> true
            }
            matchesSearch && matchesFormat
        }
    }

    LaunchedEffect(isSearchVisible) {
        if (isSearchVisible) {
            searchFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    fun activateProfile(profileId: String) {
        val target = savedProfiles.find { it.id == profileId } ?: return
        if (target.format.extension != "conf") {
            Toast.makeText(context, context.getString(R.string.warp_gen_err_export_only_format), Toast.LENGTH_SHORT).show()
            return
        }
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        profileManager.setActiveProfile(profileId)
        if (vpnState == VpnUiState.CONNECTED) {
            MirrlyVpnService.restart(context)
        }
        Toast.makeText(context, "Профиль ${target.name} активирован", Toast.LENGTH_SHORT).show()
        onDismiss()
    }

    // ── КОРНЕВАЯ ШТОРКА ВЫБОРА ПРОФИЛЕЙ (ZERO-POPUP) ──
    MirrlyModalBottomSheet(
        onDismissRequest = onDismiss,
        maxTabletWidth = 520.dp
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .fadingEdges(topFadeHeight = 16.dp, bottomFadeHeight = 24.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 4.dp, bottom = 8.dp)
        ) {
            // Category Pill (Outline-Only, Zero-Background)
            Box(
                modifier = Modifier
                    .border(1.dp, vpnColors.primary.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                    .background(Color.Transparent)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "VPN ПРОФИЛИ",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = vpnColors.primary,
                    letterSpacing = 1.sp
                )
            }

            // Заголовок
            Text(
                text = "ВЫБОР ПРОФИЛЯ",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = TextWhite,
                textAlign = TextAlign.Center,
                letterSpacing = 0.3.sp
            )

            // Подзаголовок
            Text(
                text = if (isBatchDeleteMode) "Удерживайте или нажимайте профили для выбора на удаление"
                       else "Выберите активный профиль для туннелирования трафика",
                fontSize = 12.sp,
                color = if (isBatchDeleteMode) BloodRed else TextMuted,
                textAlign = TextAlign.Center,
                lineHeight = 16.sp,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Spacer(modifier = Modifier.height(2.dp))

            // ── РЕЖИМ ПАКЕТНОГО УДАЛЕНИЯ (OUTLINE-ONLY BLOODRED) ──
            if (isBatchDeleteMode) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.2.dp, BloodRed.copy(alpha = 0.85f), RoundedCornerShape(14.dp))
                        .background(Color.Transparent)
                        .padding(12.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "ВЫБЕРИТЕ ПРОФИЛИ ДЛЯ УДАЛЕНИЯ",
                                    color = BloodRed,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 0.5.sp
                                )
                                Text(
                                    text = "Выбрано: ${selectedForDeleteIds.size} из ${filteredProfiles.size}",
                                    color = TextWhite.copy(alpha = 0.85f),
                                    fontSize = 10.sp
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .border(1.dp, BloodRed.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                                        .background(Color.Transparent)
                                        .springPress(onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            if (selectedForDeleteIds.size == filteredProfiles.size) {
                                                selectedForDeleteIds.clear()
                                            } else {
                                                selectedForDeleteIds.clear()
                                                selectedForDeleteIds.addAll(filteredProfiles.map { it.id })
                                            }
                                        })
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (selectedForDeleteIds.size == filteredProfiles.size) "СБРОСИТЬ" else "ВЫБРАТЬ ВСЕ",
                                        color = TextWhite,
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .border(1.dp, AmoledBorder, RoundedCornerShape(6.dp))
                                        .background(Color.Transparent)
                                        .springPress(onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            isBatchDeleteMode = false
                                            selectedForDeleteIds.clear()
                                        })
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "ОТМЕНА",
                                        color = TextMuted,
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        // Кнопка подтверждения удаления
                        val canDeleteBatch = selectedForDeleteIds.isNotEmpty()
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .border(
                                    1.dp,
                                    if (canDeleteBatch) BloodRed.copy(alpha = 0.9f) else AmoledBorder,
                                    RoundedCornerShape(10.dp)
                                )
                                .background(Color.Transparent)
                                .springPress(
                                    enabled = canDeleteBatch,
                                    onClick = {
                                        if (canDeleteBatch) {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            showBatchDeleteConfirmDialog = true
                                        }
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "УДАЛИТЬ ВЫБРАННЫЕ (${selectedForDeleteIds.size})",
                                color = if (canDeleteBatch) BloodRed else TextMuted.copy(alpha = 0.35f),
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.5.sp,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                }
            }

            // ── КАРТОЧКА АВТОПОДБОРА ПРОФИЛЯ ──
            if (savedProfiles.size > 1 && !isBatchDeleteMode) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            1.dp,
                            if (isAutoProfileEnabled) vpnColors.primary.copy(alpha = 0.5f) else AmoledBorder,
                            RoundedCornerShape(14.dp)
                        )
                        .background(Color.Transparent)
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.vpn_auto_profile_title),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextWhite
                                )
                                Box(
                                    modifier = Modifier
                                        .border(
                                            0.8.dp,
                                            if (isAutoProfileEnabled) ActiveGreenLed.copy(alpha = 0.7f) else TextMuted.copy(alpha = 0.4f),
                                            RoundedCornerShape(4.dp)
                                        )
                                        .background(Color.Transparent)
                                        .padding(horizontal = 5.dp, vertical = 1.5.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (isAutoProfileEnabled) stringResource(R.string.vpn_auto_profile_badge) else "ВЫКЛ",
                                        fontSize = 8.5.sp,
                                        fontWeight = FontWeight.Black,
                                        color = if (isAutoProfileEnabled) ActiveGreenLed else TextMuted
                                    )
                                }
                            }
                            Text(
                                text = if (isAutoProfileEnabled) stringResource(R.string.vpn_auto_profile_active_hint)
                                       else stringResource(R.string.vpn_auto_profile_manual_hint),
                                fontSize = 11.sp,
                                color = TextMuted,
                                lineHeight = 15.sp,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }

                        InertialSpringSwitch(
                            checked = isAutoProfileEnabled,
                            onCheckedChange = { enabled ->
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                val appInstance = com.mirrly.tgproxy.MirrlyApplication.instance
                                appInstance.prefsManager.setVpnAutoProfileEnabled(enabled)
                                if (vpnState == VpnUiState.CONNECTED) {
                                    MirrlyVpnService.restart(context)
                                }
                            },
                            activeColor = vpnColors.primary
                        )
                    }
                }
            }

            // ── ЗАГОЛОВОК СЕКЦИИ И КНОПКИ ДЕЙСТВИЙ (ПОИСК, УДАЛИТЬ ВСЕ) ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "СОХРАНЕННЫЕ ПРОФИЛИ",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    color = TextMuted,
                    letterSpacing = 1.1.sp
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (savedProfiles.isNotEmpty()) {
                        // Кнопка открытия поиска
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .border(
                                    1.dp,
                                    if (isSearchVisible || searchQuery.isNotEmpty()) vpnColors.primary else AmoledBorder,
                                    RoundedCornerShape(6.dp)
                                )
                                .background(Color.Transparent)
                                .springPress(onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    isSearchVisible = !isSearchVisible
                                    if (!isSearchVisible) {
                                        searchQuery = ""
                                        keyboardController?.hide()
                                    }
                                }),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_search),
                                contentDescription = "Поиск",
                                tint = if (isSearchVisible || searchQuery.isNotEmpty()) vpnColors.primary else TextMuted,
                                modifier = Modifier.size(13.dp)
                            )
                        }

                        // Кнопка «Удалить все профили»
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .border(1.dp, BloodRed.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                .background(Color.Transparent)
                                .springPress(onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    showDeleteAllConfirmDialog = true
                                }),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_trash),
                                contentDescription = "Удалить все",
                                tint = BloodRed,
                                modifier = Modifier.size(13.dp)
                            )
                        }

                        Text(
                            text = "${savedProfiles.size} ВСЕГО",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = vpnColors.primary,
                            modifier = Modifier.padding(start = 2.dp)
                        )
                    }
                }
            }

            // ── СТРОКА ПОИСКА (OUTLINE-ONLY) ──
            AnimatedVisibility(
                visible = isSearchVisible || searchQuery.isNotEmpty(),
                enter = expandVertically(tween(200)) + fadeIn(tween(200)),
                exit = shrinkVertically(tween(180)) + fadeOut(tween(180))
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(searchFocusRequester),
                    placeholder = {
                        Text(
                            text = "Поиск по имени, эндпоинту, типу...",
                            color = TextMuted,
                            fontSize = 12.5.sp
                        )
                    },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_search),
                            contentDescription = null,
                            tint = if (searchQuery.isNotEmpty()) vpnColors.primary else TextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .springPress(onClick = {
                                        searchQuery = ""
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_close),
                                    contentDescription = "Очистить",
                                    tint = TextMuted,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedBorderColor = vpnColors.primary.copy(alpha = 0.85f),
                        unfocusedBorderColor = AmoledBorder,
                        focusedTextColor = TextWhite,
                        unfocusedTextColor = TextWhite
                    )
                )
            }

            // ── СЕГМЕНТИРОВАННЫЕ ФИЛЬТРЫ ФОРМАТОВ (PROFILE FILTER CHIPS) ──
            if (savedProfiles.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ProfileFilterChip(
                        title = "ВСЕ",
                        count = savedProfiles.size,
                        isSelected = selectedFormatFilter == null,
                        activeColor = TextWhite,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            selectedFormatFilter = null
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    )
                    ProfileFilterChip(
                        title = "AWG",
                        count = awgCount,
                        isSelected = selectedFormatFilter == "AWG",
                        activeColor = vpnColors.primary,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            selectedFormatFilter = if (selectedFormatFilter == "AWG") null else "AWG"
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    )
                    ProfileFilterChip(
                        title = "WG",
                        count = wgCount,
                        isSelected = selectedFormatFilter == "WG",
                        activeColor = Color(0xFF10B981),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            selectedFormatFilter = if (selectedFormatFilter == "WG") null else "WG"
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    )
                    ProfileFilterChip(
                        title = "ДРУГИЕ",
                        count = otherCount,
                        isSelected = selectedFormatFilter == "OTHER",
                        activeColor = Color(0xFF3B82F6),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            selectedFormatFilter = if (selectedFormatFilter == "OTHER") null else "OTHER"
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    )
                }
            }

            // ── СОДЕРЖИМОЕ СПИСКА ПРОФИЛЕЙ ──
            if (savedProfiles.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, AmoledBorder, RoundedCornerShape(14.dp))
                        .background(Color.Transparent)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Нет сохраненных профилей",
                            color = TextWhite,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Сгенерируйте профиль Cloudflare через встроенный генератор или импортируйте .conf файл",
                            color = TextMuted,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                            lineHeight = 15.sp
                        )
                    }
                }
            } else if (filteredProfiles.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, AmoledBorder, RoundedCornerShape(14.dp))
                        .background(Color.Transparent)
                        .padding(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Профили не найдены",
                            color = TextWhite,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Попробуйте изменить запрос поиска или параметры фильтра",
                            color = TextMuted,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                // Список карточек профилей (100% контурный AMOLED, zero-background)
                filteredProfiles.forEachIndexed { index, sp ->
                    val isSelected = sp.id == activeProfileId
                    val isConfigExpanded = revealedConfigIds.contains(sp.id)
                    val isItemMarkedForDelete = selectedForDeleteIds.contains(sp.id)

                    val targetBorderColor = when {
                        isBatchDeleteMode && isItemMarkedForDelete -> BloodRed
                        isBatchDeleteMode -> BloodRed.copy(alpha = 0.35f)
                        isSelected -> vpnColors.primary.copy(alpha = 0.7f)
                        else -> AmoledBorder
                    }
                    val animatedCardBorderColor by animateColorAsState(targetBorderColor, tween(200), label = "profileCardBorder")
                    val animatedCardScale by animateFloatAsState(
                        targetValue = if (isBatchDeleteMode && isItemMarkedForDelete) 1.012f else 1.0f,
                        animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium),
                        label = "profileCardScale"
                    )
                    val cardAccent = when {
                        isBatchDeleteMode -> BloodRed
                        isSelected -> vpnColors.primary
                        else -> AmoledBorder
                    }

                    val cardInteractionSource = remember { MutableInteractionSource() }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                scaleX = animatedCardScale
                                scaleY = animatedCardScale
                            }
                            .clip(RoundedCornerShape(14.dp))
                            .border(
                                width = if (isBatchDeleteMode && isItemMarkedForDelete) 1.2.dp else 1.dp,
                                color = animatedCardBorderColor,
                                shape = RoundedCornerShape(14.dp)
                            )
                            .background(Color.Transparent)
                            .springPress(interactionSource = cardInteractionSource)
                            .combinedClickable(
                                interactionSource = cardInteractionSource,
                                indication = null,
                                onClick = {
                                    if (isBatchDeleteMode) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        if (isItemMarkedForDelete) {
                                            selectedForDeleteIds.remove(sp.id)
                                        } else {
                                            selectedForDeleteIds.add(sp.id)
                                        }
                                    } else {
                                        activateProfile(sp.id)
                                    }
                                },
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    if (!isBatchDeleteMode) {
                                        isBatchDeleteMode = true
                                        selectedForDeleteIds.clear()
                                        selectedForDeleteIds.add(sp.id)
                                    } else {
                                        if (isItemMarkedForDelete) {
                                            selectedForDeleteIds.remove(sp.id)
                                        } else {
                                            selectedForDeleteIds.add(sp.id)
                                        }
                                    }
                                }
                            )
                            .padding(14.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(9.dp)
                            ) {
                                // Номер карточки (01, 02...) — без фона, только обводка
                                Box(
                                    modifier = Modifier
                                        .border(
                                            1.dp,
                                            (if (isBatchDeleteMode || isSelected) cardAccent else TextMuted).copy(alpha = 0.45f),
                                            RoundedCornerShape(6.dp)
                                        )
                                        .background(Color.Transparent)
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = (index + 1).toString().padStart(2, '0'),
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.Black,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (isBatchDeleteMode || isSelected) cardAccent else TextMuted
                                    )
                                }

                                CountryFlagBadge(countryCode = resolveCountryCodeForEndpoint(sp.endpoint))

                                Text(
                                    text = sp.name,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextWhite,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )

                                if (sp.isPriority && !isBatchDeleteMode) {
                                    Box(
                                        modifier = Modifier
                                            .border(0.8.dp, Color(0xFFFFB74D).copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                                            .background(Color.Transparent)
                                            .padding(horizontal = 5.dp, vertical = 2.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = stringResource(R.string.vpn_priority_badge),
                                            color = Color(0xFFFFB74D),
                                            fontSize = 8.5.sp,
                                            fontWeight = FontWeight.Black,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }

                                if (isAutoProfileEnabled && effectiveAutoProfile?.id == sp.id && !isBatchDeleteMode) {
                                    Box(
                                        modifier = Modifier
                                            .border(0.8.dp, ActiveGreenLed.copy(alpha = 0.8f), RoundedCornerShape(4.dp))
                                            .background(Color.Transparent)
                                            .padding(horizontal = 5.dp, vertical = 2.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = stringResource(R.string.vpn_auto_profile_badge),
                                            color = ActiveGreenLed,
                                            fontSize = 8.5.sp,
                                            fontWeight = FontWeight.Black,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }

                                if (isSelected && !isBatchDeleteMode) {
                                    Box(
                                        modifier = Modifier
                                            .border(0.8.dp, vpnColors.primary.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                                            .background(Color.Transparent)
                                            .padding(horizontal = 5.dp, vertical = 2.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "АКТИВЕН",
                                            color = vpnColors.primary,
                                            fontSize = 8.5.sp,
                                            fontWeight = FontWeight.Black,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }

                                // Чекбокс режима удаления или радио-метка
                                if (isBatchDeleteMode) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(20.dp)
                                            .clip(CircleShape)
                                            .background(Color.Transparent)
                                            .border(
                                                1.5.dp,
                                                if (isItemMarkedForDelete) BloodRed else BloodRed.copy(alpha = 0.6f),
                                                CircleShape
                                            )
                                    ) {
                                        if (isItemMarkedForDelete) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_check),
                                                contentDescription = null,
                                                tint = BloodRed,
                                                modifier = Modifier.size(12.dp)
                                            )
                                        }
                                    }
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clip(CircleShape)
                                            .background(Color.Transparent)
                                            .border(
                                                if (isSelected) 5.dp else 1.5.dp,
                                                if (isSelected) cardAccent else AmoledBorder,
                                                CircleShape
                                            )
                                    )
                                }
                            }

                            Text(
                                text = "${sp.format.title} • ${sp.endpoint.ifBlank { "Anycast :8095" }}",
                                color = if (isBatchDeleteMode && isItemMarkedForDelete) BloodRed.copy(alpha = 0.9f) else TextMuted,
                                fontSize = 10.5.sp,
                                lineHeight = 15.sp,
                                fontFamily = FontFamily.Monospace
                            )

                            // Панель быстрых действий карточки
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (!isSelected && !isBatchDeleteMode) {
                                    Box(
                                        modifier = Modifier
                                            .height(30.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .border(1.dp, vpnColors.primary.copy(alpha = 0.75f), RoundedCornerShape(8.dp))
                                            .background(Color.Transparent)
                                            .springPress(onClick = { activateProfile(sp.id) })
                                            .padding(horizontal = 10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "ВЫБРАТЬ",
                                            color = vpnColors.primary,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }

                                // Показать/скрыть конфиг
                                Box(
                                    modifier = Modifier
                                        .height(30.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .border(1.dp, AmoledBorder, RoundedCornerShape(8.dp))
                                        .background(Color.Transparent)
                                        .springPress(onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            if (isConfigExpanded) revealedConfigIds.remove(sp.id)
                                            else revealedConfigIds.add(sp.id)
                                        })
                                        .padding(horizontal = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (isConfigExpanded) "СКРЫТЬ КОНФИГ" else "КОНФИГ",
                                        color = TextMuted,
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }

                                // Скопировать
                                Box(
                                    modifier = Modifier
                                        .height(30.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .border(1.dp, AmoledBorder, RoundedCornerShape(8.dp))
                                        .background(Color.Transparent)
                                        .springPress(onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            clipboard.setPrimaryClip(ClipData.newPlainText(sp.name, sp.content))
                                            Toast.makeText(context, "Конфигурация ${sp.name} скопирована", Toast.LENGTH_SHORT).show()
                                        })
                                        .padding(horizontal = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "КОПИРОВАТЬ",
                                        color = TextMuted,
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }

                                Spacer(modifier = Modifier.weight(1f))

                                // Одиночное удаление
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .border(1.dp, BloodRed.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                        .background(Color.Transparent)
                                        .springPress(onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            profileToDelete = sp
                                        }),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_close),
                                        contentDescription = "Удалить",
                                        tint = BloodRed,
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                            }

                            // Развернутый текст конфига
                            AnimatedVisibility(
                                visible = isConfigExpanded,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut()
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .border(1.dp, AmoledBorder, RoundedCornerShape(8.dp))
                                        .background(Color.Transparent)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 160.dp)
                                            .verticalScroll(rememberScrollState())
                                            .padding(10.dp)
                                    ) {
                                        Text(
                                            text = sp.content,
                                            color = TextWhite.copy(alpha = 0.90f),
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            lineHeight = 14.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // ── КНОПКИ УПРАВЛЕНИЯ ВНИЗУ (OUTLINE-ONLY) ──
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (onOpenGenerator != null && !isBatchDeleteMode) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, vpnColors.primary.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
                            .background(Color.Transparent)
                            .springPress(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onDismiss()
                                onOpenGenerator()
                            }),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "СОЗДАТЬ В ГЕНЕРАТОРЕ",
                            color = vpnColors.primary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.5.sp,
                            letterSpacing = 0.4.sp
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, AmoledBorder, RoundedCornerShape(12.dp))
                        .background(Color.Transparent)
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onDismiss()
                        }),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "ЗАКРЫТЬ",
                        color = TextMuted,
                        fontWeight = FontWeight.Normal,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }

    // ── ШТОРКА ПОДТВЕРЖДЕНИЯ УДАЛЕНИЯ ОДНОГО ПРОФИЛЯ ──
    profileToDelete?.let { prof ->
        ProfileDeleteConfirmSheet(
            profile = prof,
            onDismiss = { profileToDelete = null },
            onConfirm = {
                profileManager.deleteProfile(prof.id)
                profileToDelete = null
                Toast.makeText(context, "Профиль удален", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // ── ШТОРКА ПОДТВЕРЖДЕНИЯ «УДАЛИТЬ ВСЕ ПРОФИЛИ» ──
    if (showDeleteAllConfirmDialog) {
        DeleteAllProfilesConfirmSheet(
            count = savedProfiles.size,
            onDismiss = { showDeleteAllConfirmDialog = false },
            onConfirm = {
                profileManager.deleteAllProfiles()
                showDeleteAllConfirmDialog = false
                isBatchDeleteMode = false
                selectedForDeleteIds.clear()
                selectedFormatFilter = null
                searchQuery = ""
                Toast.makeText(context, "Все профили удалены", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // ── ШТОРКА ПОДТВЕРЖДЕНИЯ ПАКЕТНОГО УДАЛЕНИЯ ──
    if (showBatchDeleteConfirmDialog) {
        DeleteBatchProfilesConfirmSheet(
            count = selectedForDeleteIds.size,
            onDismiss = { showBatchDeleteConfirmDialog = false },
            onConfirm = {
                val count = selectedForDeleteIds.size
                profileManager.deleteProfiles(selectedForDeleteIds.toSet())
                showBatchDeleteConfirmDialog = false
                isBatchDeleteMode = false
                selectedForDeleteIds.clear()
                if (profileManager.getAllProfiles().isEmpty()) {
                    selectedFormatFilter = null
                    searchQuery = ""
                }
                Toast.makeText(context, "Удалено профилей: $count", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

/**
 * Сегментированный чип фильтра формата профилей (Outline-Only, springPress).
 */
@Composable
private fun ProfileFilterChip(
    title: String,
    count: Int,
    isSelected: Boolean,
    activeColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(
                if (isSelected) 1.dp else 0.8.dp,
                if (isSelected) activeColor.copy(alpha = 0.85f) else AmoledBorder,
                RoundedCornerShape(8.dp)
            )
            .background(Color.Transparent)
            .springPress(onClick = onClick)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = if (isSelected) activeColor else TextWhite.copy(alpha = 0.85f),
                fontSize = 10.5.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = count.toString(),
                color = if (isSelected) activeColor else TextMuted,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1
            )
        }
    }
}

/**
 * Шторка подтверждения удаления одного профиля (MirrlyModalBottomSheet).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileDeleteConfirmSheet(
    profile: WarpSavedProfile,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val haptic = LocalHapticFeedback.current

    MirrlyModalBottomSheet(
        onDismissRequest = onDismiss,
        maxTabletWidth = 480.dp
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 4.dp, bottom = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Category Pill (Outline-Only, Zero-Background)
            Box(
                modifier = Modifier
                    .border(1.dp, BloodRed.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                    .background(Color.Transparent)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "УДАЛЕНИЕ ПРОФИЛЯ",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = BloodRed,
                    letterSpacing = 1.sp
                )
            }

            // Заголовок
            Text(
                text = "Удалить профиль?",
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = TextWhite,
                textAlign = TextAlign.Center,
                letterSpacing = 0.3.sp
            )

            // Подзаголовок
            Text(
                text = "Выбранная конфигурация будет безвозвратно удалена из хранилища приложения:",
                fontSize = 12.sp,
                color = TextMuted,
                textAlign = TextAlign.Center,
                lineHeight = 16.sp,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            // Контурная карточка деталей
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BloodRed.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                    .background(Color.Transparent)
                    .padding(14.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        CountryFlagBadge(countryCode = resolveCountryCodeForEndpoint(profile.endpoint))
                        Text(
                            text = profile.name,
                            color = TextWhite,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Box(
                            modifier = Modifier
                                .border(1.dp, AmoledBorder, RoundedCornerShape(5.dp))
                                .background(Color.Transparent)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = profile.format.title.substringBefore(" ("),
                                fontSize = 9.sp,
                                color = TextMuted
                            )
                        }
                    }
                    Text(
                        text = "${profile.endpoint} • DNS: ${profile.dnsLine}",
                        color = TextMuted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            // Кнопки действий: Отмена + Удалить
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, AmoledBorder, RoundedCornerShape(12.dp))
                        .background(Color.Transparent)
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onDismiss()
                        }),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "ОТМЕНА",
                        color = TextMuted,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, BloodRed.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
                        .background(Color.Transparent)
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onConfirm()
                        }),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "УДАЛИТЬ",
                        color = BloodRed,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.4.sp
                    )
                }
            }
        }
    }
}

/**
 * Шторка подтверждения «Удалить все профили» (MirrlyModalBottomSheet).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeleteAllProfilesConfirmSheet(
    count: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val haptic = LocalHapticFeedback.current

    MirrlyModalBottomSheet(
        onDismissRequest = onDismiss,
        maxTabletWidth = 480.dp
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 4.dp, bottom = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Category Pill (Outline-Only, Zero-Background)
            Box(
                modifier = Modifier
                    .border(1.dp, BloodRed.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                    .background(Color.Transparent)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "УДАЛЕНИЕ ВСЕХ",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = BloodRed,
                    letterSpacing = 1.sp
                )
            }

            // Заголовок
            Text(
                text = "Удалить все профили?",
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = TextWhite,
                textAlign = TextAlign.Center,
                letterSpacing = 0.3.sp
            )

            // Подзаголовок
            Text(
                text = "Все сохраненные конфигурации будут безвозвратно удалены из приложения:",
                fontSize = 12.sp,
                color = TextMuted,
                textAlign = TextAlign.Center,
                lineHeight = 16.sp,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            // Контурная карточка предупреждения
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BloodRed.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                    .background(Color.Transparent)
                    .padding(14.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "ВНИМАНИЕ",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = BloodRed,
                        letterSpacing = 0.8.sp
                    )
                    Text(
                        text = "Будет удалено профилей: $count. Восстановление конфигураций после подтверждения невозможно.",
                        fontSize = 11.5.sp,
                        color = TextWhite.copy(alpha = 0.85f),
                        lineHeight = 16.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            // Кнопки действий: Отмена + Удалить все
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, AmoledBorder, RoundedCornerShape(12.dp))
                        .background(Color.Transparent)
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onDismiss()
                        }),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "ОТМЕНА",
                        color = TextMuted,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, BloodRed.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
                        .background(Color.Transparent)
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onConfirm()
                        }),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "УДАЛИТЬ ВСЕ",
                        color = BloodRed,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.4.sp
                    )
                }
            }
        }
    }
}

/**
 * Шторка подтверждения пакетного удаления выбранных профилей (MirrlyModalBottomSheet).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeleteBatchProfilesConfirmSheet(
    count: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val haptic = LocalHapticFeedback.current

    MirrlyModalBottomSheet(
        onDismissRequest = onDismiss,
        maxTabletWidth = 480.dp
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 4.dp, bottom = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Category Pill (Outline-Only, Zero-Background)
            Box(
                modifier = Modifier
                    .border(1.dp, BloodRed.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                    .background(Color.Transparent)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "ПАКЕТНОЕ УДАЛЕНИЕ",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = BloodRed,
                    letterSpacing = 1.sp
                )
            }

            // Заголовок
            Text(
                text = "Удалить выбранные?",
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = TextWhite,
                textAlign = TextAlign.Center,
                letterSpacing = 0.3.sp
            )

            // Подзаголовок
            Text(
                text = "Выбранные профили будут безвозвратно удалены из приложения:",
                fontSize = 12.sp,
                color = TextMuted,
                textAlign = TextAlign.Center,
                lineHeight = 16.sp,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            // Контурная карточка информации
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BloodRed.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                    .background(Color.Transparent)
                    .padding(14.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "ВЫБРАННЫЕ ПРОФИЛИ",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = BloodRed,
                        letterSpacing = 0.8.sp
                    )
                    Text(
                        text = "Количество выбранных для удаления профилей: $count. Выбранные конфигурации будут стерты безвозвратно.",
                        fontSize = 11.5.sp,
                        color = TextWhite.copy(alpha = 0.85f),
                        lineHeight = 16.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            // Кнопки действий: Отмена + Удалить выбранные
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, AmoledBorder, RoundedCornerShape(12.dp))
                        .background(Color.Transparent)
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onDismiss()
                        }),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "ОТМЕНА",
                        color = TextMuted,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, BloodRed.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
                        .background(Color.Transparent)
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onConfirm()
                        }),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "УДАЛИТЬ ($count)",
                        color = BloodRed,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.4.sp
                    )
                }
            }
        }
    }
}
