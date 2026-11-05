/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.mirrly.tgproxy.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import kotlin.math.roundToInt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.ui.icon.CentralButtonPresets
import com.mirrly.tgproxy.ui.icon.CentralButtonView
import com.mirrly.tgproxy.ui.icon.SvgPathDataParser
import com.mirrly.tgproxy.ui.theme.AmoledBorder
import com.mirrly.tgproxy.ui.theme.LocalProtocolColors
import com.mirrly.tgproxy.ui.theme.MtprotoAccent
import com.mirrly.tgproxy.ui.theme.Socks5Accent
import com.mirrly.tgproxy.ui.theme.TextMuted
import com.mirrly.tgproxy.ui.theme.TextWhite
import com.mirrly.tgproxy.ui.theme.ThemeAccentMode
import com.mirrly.tgproxy.ui.theme.VpnAccent
import com.mirrly.tgproxy.ui.theme.rememberAnimatedProtocolColors
import com.mirrly.tgproxy.ui.theme.springPress

/**
 * Карточка кастомизации центральных кнопок главного экрана в MirrlyUI.
 * Поддерживает раздельный выбор пресетов для MTProto, SOCKS5 и VPN,
 * живой интерактивный предпросмотр с переключением тестовых состояний,
 * и ввод произвольного векторного кода SVG/pathData с интеграцией Mirrly AI.
 */
@Composable
fun SettingsCentralButtonsCard(
    accentColor: Color? = null,
    onInfoClick: (String) -> Unit,
    onOpenAiChat: ((String?) -> Unit)?
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val prefs = MirrlyApplication.instance.prefsManager
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    val mtprotoPreset by prefs.centralButtonIconMtprotoFlow.collectAsState()
    val socks5Preset by prefs.centralButtonIconSocks5Flow.collectAsState()
    val vpnPreset by prefs.centralButtonIconVpnFlow.collectAsState()

    val mtprotoCustomCode by prefs.customIconCodeMtprotoFlow.collectAsState()
    val socks5CustomCode by prefs.customIconCodeSocks5Flow.collectAsState()
    val vpnCustomCode by prefs.customIconCodeVpnFlow.collectAsState()

    val iconScale by prefs.centralButtonScaleFlow.collectAsState()
    val strokeWidth by prefs.centralButtonStrokeWidthFlow.collectAsState()
    val glowIntensity by prefs.centralButtonGlowIntensityFlow.collectAsState()

    var sliderScale by remember(iconScale) { androidx.compose.runtime.mutableFloatStateOf(iconScale) }
    var sliderGlow by remember(glowIntensity) { androidx.compose.runtime.mutableFloatStateOf(glowIntensity) }

    var isExpanded by rememberSaveable { mutableStateOf(false) }
    var selectedProtocol by rememberSaveable { mutableStateOf("mtproto") } // "mtproto", "socks5", "vpn"
    var previewState by rememberSaveable { mutableStateOf(ProxyUiState.CONNECTED) }

    val activePresetId = when (selectedProtocol) {
        "mtproto" -> mtprotoPreset
        "socks5" -> socks5Preset
        "vpn" -> vpnPreset
        else -> mtprotoPreset
    }

    val activeCustomCode = when (selectedProtocol) {
        "mtproto" -> mtprotoCustomCode
        "socks5" -> socks5CustomCode
        "vpn" -> vpnCustomCode
        else -> mtprotoCustomCode
    }

    var codeInput by remember(selectedProtocol, activeCustomCode) { mutableStateOf(activeCustomCode) }
    var isPromptCopied by remember { mutableStateOf(false) }

    val currentThemeMode by prefs.themeAccentModeFlow.collectAsState()

    val protoAccent = when (selectedProtocol) {
        "mtproto" -> MtprotoAccent
        "socks5" -> Socks5Accent
        "vpn" -> VpnAccent
        else -> MtprotoAccent
    }
    val effectiveAccent = remember(currentThemeMode, accentColor, protoAccent) {
        if (currentThemeMode == ThemeAccentMode.PROTOCOL_DEFAULT) {
            protoAccent
        } else {
            accentColor ?: protoAccent
        }
    }

    val availablePresets = remember(selectedProtocol) {
        CentralButtonPresets.getPresetsForProtocol(selectedProtocol)
    }

    val isInputValid = remember(codeInput) {
        if (codeInput.isBlank()) false else SvgPathDataParser.validatePathData(codeInput)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Transparent)
            .border(1.dp, AmoledBorder, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ── 1. Card Header Row ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .weight(1f, fill = false)
                    .springPress(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        isExpanded = !isExpanded
                    })
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Transparent)
                        .border(1.dp, AmoledBorder, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    CentralButtonsGlyph(
                        color = effectiveAccent,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_customization_buttons_title),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite
                    )
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .springPress()
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onInfoClick("buttons_info")
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_info_circle),
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            SettingsCollapseButton(
                isExpanded = isExpanded,
                onToggle = { isExpanded = !isExpanded },
                accentColor = effectiveAccent
            )
        }

        // ── 2. Expanded Content ──
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(tween(260)) + fadeIn(tween(240)),
            exit = shrinkVertically(tween(200)) + fadeOut(tween(180))
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = stringResource(R.string.settings_customization_buttons_desc),
                    fontSize = 12.sp,
                    color = TextMuted,
                    lineHeight = 17.sp
                )

                // ── Mode Switcher Pill (MTProto / SOCKS5 / VPN) ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Transparent)
                        .border(1.dp, AmoledBorder, RoundedCornerShape(12.dp))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val tabs = listOf(
                        "mtproto" to stringResource(R.string.settings_customization_buttons_tab_mtproto),
                        "socks5" to stringResource(R.string.settings_customization_buttons_tab_socks5),
                        "vpn" to stringResource(R.string.settings_customization_buttons_tab_vpn)
                    )

                    tabs.forEach { (tabId, tabTitle) ->
                        val isSelected = selectedProtocol == tabId
                        val tabColor = if (currentThemeMode == ThemeAccentMode.PROTOCOL_DEFAULT) {
                            when (tabId) {
                                "mtproto" -> MtprotoAccent
                                "socks5" -> Socks5Accent
                                "vpn" -> VpnAccent
                                else -> MtprotoAccent
                            }
                        } else {
                            effectiveAccent
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(9.dp))
                                .background(if (isSelected) tabColor.copy(alpha = 0.12f) else Color.Transparent)
                                .border(
                                    1.dp,
                                    if (isSelected) tabColor else Color.Transparent,
                                    RoundedCornerShape(9.dp)
                                )
                                .springPress(onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    selectedProtocol = tabId
                                })
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = tabTitle,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) tabColor else TextMuted
                            )
                        }
                    }
                }

                // ── Interactive Live Preview Stage ──
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Black.copy(alpha = 0.35f))
                        .border(1.dp, AmoledBorder, RoundedCornerShape(14.dp))
                        .padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.settings_customization_buttons_preview_title),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            color = TextMuted
                        )
                        Text(
                            text = getPresetDisplayName(context, selectedProtocol, activePresetId),
                            fontSize = 11.sp,
                            color = effectiveAccent,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    val testProtoColors = rememberAnimatedProtocolColors(
                        proxyMode = when (selectedProtocol) {
                            "socks5" -> com.mirrly.tgproxy.core.ProxyMode.SOCKS5
                            else -> com.mirrly.tgproxy.core.ProxyMode.MTPROTO
                        },
                        isSocks5 = selectedProtocol == "socks5",
                        isVpn = selectedProtocol == "vpn"
                    )

                    // Button preview canvas
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .clip(CircleShape)
                            .background(Color.Transparent)
                            .border(1.dp, effectiveAccent.copy(alpha = 0.35f), CircleShape)
                            .springPress(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                previewState = when (previewState) {
                                    ProxyUiState.DISCONNECTED -> ProxyUiState.CONNECTING
                                    ProxyUiState.CONNECTING -> ProxyUiState.CONNECTED
                                    ProxyUiState.CONNECTED -> ProxyUiState.DISCONNECTED
                                    else -> ProxyUiState.CONNECTED
                                }
                            }),
                        contentAlignment = Alignment.Center
                    ) {
                        CentralButtonView(
                            protocol = selectedProtocol,
                            presetId = activePresetId,
                            customCode = activeCustomCode,
                            state = previewState,
                            isSocks5 = selectedProtocol == "socks5",
                            customColors = testProtoColors,
                            scaleFactor = sliderScale,
                            strokeWidthDp = strokeWidth,
                            glowIntensity = sliderGlow,
                            modifier = Modifier.size(56.dp)
                        )
                    }

                    // Test state toggles: [ Выкл ] [ Подключение ] [ В сети ]
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val testStates = listOf(
                            ProxyUiState.DISCONNECTED to stringResource(R.string.settings_customization_buttons_state_idle),
                            ProxyUiState.CONNECTING to stringResource(R.string.settings_customization_buttons_state_connecting),
                            ProxyUiState.CONNECTED to stringResource(R.string.settings_customization_buttons_state_connected)
                        )

                        testStates.forEach { (st, label) ->
                            val isStSelected = previewState == st
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isStSelected) effectiveAccent.copy(alpha = 0.12f) else Color.Transparent)
                                    .border(
                                        1.dp,
                                        if (isStSelected) effectiveAccent.copy(alpha = 0.8f) else AmoledBorder,
                                        RoundedCornerShape(6.dp)
                                    )
                                    .springPress(onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        previewState = st
                                    })
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 10.5.sp,
                                    fontWeight = if (isStSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isStSelected) effectiveAccent else TextMuted
                                )
                            }
                        }
                    }
                }

                // ── Presets Selector Row ──
                Text(
                    text = stringResource(R.string.settings_customization_buttons_presets_title),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    availablePresets.forEach { preset ->
                        val isPresetSelected = activePresetId == preset.id
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isPresetSelected) effectiveAccent.copy(alpha = 0.12f) else Color.Transparent)
                                .border(
                                    1.dp,
                                    if (isPresetSelected) effectiveAccent else AmoledBorder,
                                    RoundedCornerShape(10.dp)
                                )
                                .springPress(onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    prefs.setCentralButtonIcon(selectedProtocol, preset.id)
                                })
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = stringResource(preset.titleRes),
                                fontSize = 12.sp,
                                fontWeight = if (isPresetSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isPresetSelected) effectiveAccent else TextWhite
                            )
                        }
                    }
                }

                // ── Geometry & Glow Tuning Section ──
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Black.copy(alpha = 0.25f))
                        .border(1.dp, AmoledBorder, RoundedCornerShape(14.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_customization_buttons_geometry_title),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite
                    )

                    // 1. Scale slider
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.settings_customization_buttons_scale_title),
                                fontSize = 11.5.sp,
                                color = TextMuted
                            )
                            Text(
                                text = "${(sliderScale * 100f).roundToInt()}%",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = effectiveAccent
                            )
                        }
                        Slider(
                            value = sliderScale,
                            onValueChange = {
                                sliderScale = it
                                prefs.setCentralButtonScale(it, persist = false)
                            },
                            onValueChangeFinished = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                prefs.setCentralButtonScale(sliderScale, persist = true)
                            },
                            valueRange = 0.70f..1.30f,
                            colors = SliderDefaults.colors(
                                thumbColor = effectiveAccent,
                                activeTrackColor = effectiveAccent,
                                inactiveTrackColor = AmoledBorder
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // 2. Stroke Width selector chips
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = stringResource(R.string.settings_customization_buttons_stroke_title),
                            fontSize = 11.5.sp,
                            color = TextMuted
                        )
                        val strokeOptions = listOf(
                            1.2f to stringResource(R.string.settings_customization_buttons_stroke_thin),
                            1.5f to stringResource(R.string.settings_customization_buttons_stroke_normal),
                            2.0f to stringResource(R.string.settings_customization_buttons_stroke_bold),
                            2.4f to stringResource(R.string.settings_customization_buttons_stroke_cyber)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            strokeOptions.forEach { (stVal, stLabel) ->
                                val isSel = kotlin.math.abs(strokeWidth - stVal) < 0.05f
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSel) effectiveAccent.copy(alpha = 0.12f) else Color.Transparent)
                                        .border(
                                            1.dp,
                                            if (isSel) effectiveAccent else AmoledBorder,
                                            RoundedCornerShape(8.dp)
                                        )
                                        .springPress(onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            prefs.setCentralButtonStrokeWidth(stVal)
                                        })
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = stLabel,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSel) effectiveAccent else TextMuted
                                    )
                                }
                            }
                        }
                    }

                    // 3. Glow Intensity slider
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.settings_customization_buttons_glow_title),
                                fontSize = 11.5.sp,
                                color = TextMuted
                            )
                            Text(
                                text = "${(sliderGlow * 100f).roundToInt()}%",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = effectiveAccent
                            )
                        }
                        Slider(
                            value = sliderGlow,
                            onValueChange = {
                                sliderGlow = it
                                prefs.setCentralButtonGlowIntensity(it, persist = false)
                            },
                            onValueChangeFinished = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                prefs.setCentralButtonGlowIntensity(sliderGlow, persist = true)
                            },
                            valueRange = 0.0f..2.0f,
                            colors = SliderDefaults.colors(
                                thumbColor = effectiveAccent,
                                activeTrackColor = effectiveAccent,
                                inactiveTrackColor = AmoledBorder
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // ── Custom Vector Code Panel (when custom is selected) ──
                if (activePresetId == "custom") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.Black.copy(alpha = 0.25f))
                            .border(1.dp, effectiveAccent.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.settings_customization_buttons_custom_title),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite
                        )
                        Text(
                            text = stringResource(R.string.settings_customization_buttons_custom_desc),
                            fontSize = 11.5.sp,
                            color = TextMuted,
                            lineHeight = 16.sp
                        )

                        // Quick action buttons: Copy prompt, Create in Mirrly AI, Guide
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Copy prompt button
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.Transparent)
                                    .border(1.dp, AmoledBorder, RoundedCornerShape(8.dp))
                                    .springPress(onClick = {
                                        val prompt = CentralButtonPresets.buildAiPromptForProtocol(selectedProtocol)
                                        clipboard.setPrimaryClip(ClipData.newPlainText("AI Prompt", prompt))
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        isPromptCopied = true
                                    })
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_copy),
                                        contentDescription = null,
                                        tint = effectiveAccent,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Text(
                                        text = if (isPromptCopied) stringResource(R.string.settings_customization_buttons_prompt_copied) else stringResource(R.string.settings_customization_buttons_btn_copy_prompt),
                                        fontSize = 11.5.sp,
                                        color = if (isPromptCopied) effectiveAccent else TextWhite
                                    )
                                }
                            }

                            // Create in Mirrly AI button
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(effectiveAccent.copy(alpha = 0.12f))
                                    .border(1.dp, effectiveAccent, RoundedCornerShape(8.dp))
                                    .springPress(onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        val prompt = CentralButtonPresets.buildAiPromptForProtocol(selectedProtocol)
                                        onOpenAiChat?.invoke(prompt)
                                    })
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_ai_chat),
                                        contentDescription = null,
                                        tint = effectiveAccent,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Text(
                                        text = stringResource(R.string.settings_customization_buttons_btn_ai_generate),
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = effectiveAccent
                                    )
                                }
                            }

                            // Guide button
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.Transparent)
                                    .border(1.dp, AmoledBorder, RoundedCornerShape(8.dp))
                                    .springPress(onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onInfoClick("buttons_guide")
                                    })
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_info_circle),
                                        contentDescription = null,
                                        tint = TextMuted,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Text(
                                        text = stringResource(R.string.settings_customization_buttons_btn_guide),
                                        fontSize = 11.5.sp,
                                        color = TextWhite
                                    )
                                }
                            }
                        }

                        // Code Editor Text Field
                        OutlinedTextField(
                            value = codeInput,
                            onValueChange = { codeInput = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(96.dp),
                            placeholder = {
                                Text(
                                    text = stringResource(R.string.settings_customization_buttons_code_hint),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            },
                            textStyle = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.5.sp,
                                color = TextWhite
                            ),
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = if (isInputValid) effectiveAccent else MaterialTheme.colorScheme.error,
                                unfocusedBorderColor = if (codeInput.isBlank()) AmoledBorder else if (isInputValid) effectiveAccent.copy(alpha = 0.5f) else MaterialTheme.colorScheme.error,
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent
                            )
                        )

                        if (!isInputValid && codeInput.isNotBlank()) {
                            Text(
                                text = stringResource(R.string.settings_customization_buttons_code_invalid),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        // Action Buttons: Apply / Clear
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isInputValid) effectiveAccent.copy(alpha = 0.14f) else Color.Transparent)
                                    .border(
                                        1.dp,
                                        if (isInputValid) effectiveAccent else AmoledBorder,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .springPress(onClick = {
                                        if (isInputValid) {
                                            val clean = SvgPathDataParser.extractPathData(codeInput)
                                            prefs.setCustomIconCode(selectedProtocol, clean)
                                            prefs.setCentralButtonIcon(selectedProtocol, "custom")
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        }
                                    })
                                    .padding(vertical = 9.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_customization_buttons_code_apply),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isInputValid) effectiveAccent else TextMuted
                                )
                            }

                            if (codeInput.isNotBlank()) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                    .background(Color.Transparent)
                                    .border(1.dp, AmoledBorder, RoundedCornerShape(8.dp))
                                    .springPress(onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        codeInput = ""
                                        prefs.setCustomIconCode(selectedProtocol, "")
                                    })
                                    .padding(horizontal = 14.dp, vertical = 9.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_customization_buttons_code_clear),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.error
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

/**
 * Векторный глиф для карточки «Центральные кнопки» в стиле MirrlyUI.
 */
@Composable
private fun CentralButtonsGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = 1.5.dp.toPx()
        // Внешнее концентрическое кольцо
        drawCircle(
            color = color.copy(alpha = 0.35f),
            radius = size.minDimension * 0.46f,
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
        // Внутренний кинетический дельта-символ
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = size.minDimension * 0.26f
        val delta = Path().apply {
            moveTo(cx, cy - r * 1.1f)
            lineTo(cx + r, cy + r * 0.8f)
            lineTo(cx, cy + r * 0.4f)
            lineTo(cx - r, cy + r * 0.8f)
            close()
        }
        drawPath(
            path = delta,
            color = color,
            style = Stroke(width = stroke * 1.1f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

private fun getPresetDisplayName(context: Context, protocol: String, presetId: String): String {
    val presets = CentralButtonPresets.getPresetsForProtocol(protocol)
    val found = presets.find { it.id == presetId }
    return if (found != null) context.getString(found.titleRes) else presetId
}
