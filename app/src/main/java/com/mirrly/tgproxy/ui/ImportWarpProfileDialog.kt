/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * GNU GPL v3+ <https://www.gnu.org/licenses/>
 */

package com.mirrly.tgproxy.ui
import com.mirrly.tgproxy.ui.icon.MirrlyIcon

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.core.*
import com.mirrly.tgproxy.ui.theme.*

/**
 * Шторка импорта сторонних профилей WARP / WireGuard / AmneziaWG и лицензионных ключей по стандартам MirrlyUI:
 * - Zero-Popup: нативный MirrlyModalBottomSheet без Dialog и DialogBackdropBox.
 * - Zero-Background / Outline-Only: 100% AMOLED-контуры, без цветных и серых заливок.
 * - Пружинная кинетика: Modifier.springPress на всех кнопках и карточках.
 * - Anti-Slop: строгая кибернетическая типографика, отсутствие эмодзи.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportWarpProfileDialog(
    initialText: String = "",
    onProfileImported: (WarpProfile) -> Unit,
    onLicenseKeyImported: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val app = com.mirrly.tgproxy.MirrlyApplication.instance
    val protocolColors = LocalProtocolColors.current
    val primaryColor = protocolColors.primary
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    BackHandler(enabled = true) {
        keyboardController?.hide()
        focusManager.clearFocus()
    }

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )

    var inputText by remember { mutableStateOf(initialText) }
    var customProfileName by remember { mutableStateOf("") }
    var isGuideExpanded by remember { mutableStateOf(false) }

    val parsedResult = remember(inputText) {
        if (inputText.isBlank()) null else WarpProfileParser.parse(inputText)
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val text = stream.bufferedReader().use { it.readText() }
                    inputText = text.trim()
                }
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: context.getString(R.string.vpn_import_err_read_file), Toast.LENGTH_SHORT).show()
            }
        }
    }

    MirrlyModalBottomSheet(
        onDismissRequest = {
            // Запрещено закрывать диалог ввода случайным свайпом или кликом вне области
        },
        sheetState = sheetState,
        showDragHandle = false,
        maxTabletWidth = 480.dp,
        containerColor = Color.Black.copy(alpha = 0.50f),
        scrimColor = Color.Black.copy(alpha = 0.32f),
        blurRadiusPx = 60
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(
                    animationSpec = spring(
                        dampingRatio = 0.85f,
                        stiffness = 400f
                    )
                )
                .fadingEdges(topFadeHeight = 16.dp, bottomFadeHeight = 24.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 10.dp, bottom = 8.dp)
        ) {
            // Верхняя панель: Центрированный бейдж категории + Явная кнопка закрытия справа
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                // Category Pill (Outline-Only, Zero-Background)
                Box(
                    modifier = Modifier
                        .border(1.dp, primaryColor.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                        .background(Color.Transparent)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.vpn_import_dialog_badge),
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = primaryColor,
                        letterSpacing = 1.sp
                    )
                }

                // Кнопка закрытия в верхнем правом углу (Outline-Only + SpringPress)
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(30.dp)
                        .clip(CircleShape)
                        .border(1.dp, AmoledBorder, CircleShape)
                        .background(Color.Transparent)
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onDismiss()
                        }),
                    contentAlignment = Alignment.Center
                ) {
                    MirrlyIcon(
                        id = R.drawable.ic_close,
                        contentDescription = stringResource(R.string.action_close),
                        tint = TextMuted,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }

            // Заголовок
            Text(
                text = stringResource(R.string.vpn_import_dialog_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TextWhite,
                textAlign = TextAlign.Center,
                letterSpacing = 0.3.sp
            )

            // Подзаголовок
            Text(
                text = stringResource(R.string.vpn_import_dialog_subtitle),
                fontSize = 11.5.sp,
                color = TextMuted,
                textAlign = TextAlign.Center,
                lineHeight = 16.sp,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            // ── КНОПКИ ДЕЙСТВИЙ: ВСТАВИТЬ ИЗ БУФЕРА, ВЫБРАТЬ ФАЙЛ, ОЧИСТИТЬ ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Вставить из буфера
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, primaryColor.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                        .background(Color.Transparent)
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                            if (clipText.isNotBlank()) {
                                inputText = clipText.trim()
                            } else {
                                Toast.makeText(context, R.string.vpn_import_clipboard_empty, Toast.LENGTH_SHORT).show()
                            }
                        }),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.vpn_import_btn_paste),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = primaryColor
                    )
                }

                // Выбрать файл
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, AmoledBorder, RoundedCornerShape(10.dp))
                        .background(Color.Transparent)
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            filePickerLauncher.launch("*/*")
                        }),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.vpn_import_btn_file),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextWhite
                    )
                }

                // Очистить поле
                if (inputText.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .height(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, AmoledBorder, RoundedCornerShape(10.dp))
                            .background(Color.Transparent)
                            .springPress(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                inputText = ""
                            })
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.vpn_import_btn_clear),
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                    }
                }
            }

            // ── ПОЛЕ ВВОДА ТЕКСТА КОНФИГУРАЦИИ (ZERO-BACKGROUND) ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, AmoledBorder, RoundedCornerShape(12.dp))
                    .background(Color.Transparent)
                    .padding(10.dp)
            ) {
                if (inputText.isEmpty()) {
                    Text(
                        text = stringResource(R.string.vpn_import_input_placeholder),
                        color = TextMuted.copy(alpha = 0.5f),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 15.sp
                    )
                }
                BasicTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    textStyle = TextStyle(
                        color = TextWhite,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 15.sp
                    ),
                    cursorBrush = SolidColor(primaryColor),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 90.dp, max = 150.dp)
                )
            }

            // ── ПАНЕЛЬ ВЕРИФИКАЦИИ РАСПОЗНАННОГО ФОРМАТА (OUTLINE-ONLY) ──
            if (parsedResult != null) {
                when (parsedResult) {
                    is WarpImportResult.Success -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.dp, primaryColor.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                .background(Color.Transparent)
                                .padding(12.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                                                .size(7.dp)
                                                .clip(CircleShape)
                                                .background(primaryColor)
                                        )
                                        Text(
                                            text = parsedResult.type.title,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = primaryColor
                                        )
                                    }

                                    val planText = if (parsedResult.profile.isWarpPlus) "WARP+" else "FREE"
                                    Box(
                                        modifier = Modifier
                                            .border(0.8.dp, primaryColor.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                                            .background(Color.Transparent)
                                            .padding(horizontal = 5.dp, vertical = 1.5.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = planText,
                                            fontSize = 8.5.sp,
                                            fontWeight = FontWeight.Black,
                                            color = primaryColor
                                        )
                                    }
                                }

                                ImportParamRow(label = stringResource(R.string.vpn_import_lbl_client_ip), value = parsedResult.profile.clientIpv4)
                                ImportParamRow(label = stringResource(R.string.vpn_import_lbl_endpoint), value = parsedResult.profile.peerEndpoint)
                                if (parsedResult.profile.peerPublicKey.isNotBlank()) {
                                    ImportParamRow(
                                        label = stringResource(R.string.vpn_import_lbl_peer_key),
                                        value = parsedResult.profile.peerPublicKey.take(12) + "..."
                                    )
                                }
                                if (parsedResult.type == ImportedProfileType.AMNEZIA_WG_CONF) {
                                    ImportParamRow(label = stringResource(R.string.vpn_import_lbl_obfuscation), value = stringResource(R.string.vpn_import_val_obfuscation_active), valueColor = primaryColor)
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                // Поле ввода пользовательского имени профиля
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(1.dp, AmoledBorder, RoundedCornerShape(10.dp))
                                        .padding(horizontal = 10.dp, vertical = 7.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.vpn_import_lbl_profile_name),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextMuted,
                                        letterSpacing = 0.8.sp
                                    )
                                    val defaultPlaceholder = when (parsedResult.type) {
                                        ImportedProfileType.AMNEZIA_WG_CONF -> stringResource(R.string.vpn_import_placeholder_awg)
                                        ImportedProfileType.WIREGUARD_CONF -> stringResource(R.string.vpn_import_placeholder_wg)
                                        ImportedProfileType.CLASH_META_YAML -> stringResource(R.string.vpn_import_placeholder_clash)
                                        ImportedProfileType.CLOUDFLARE_JSON -> stringResource(R.string.vpn_import_placeholder_warp)
                                        else -> stringResource(R.string.vpn_import_placeholder_default)
                                    }
                                    BasicTextField(
                                        value = customProfileName,
                                        onValueChange = { customProfileName = it },
                                        singleLine = true,
                                        textStyle = TextStyle(
                                            color = TextWhite,
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.SemiBold
                                        ),
                                        cursorBrush = SolidColor(primaryColor),
                                        decorationBox = { innerTextField ->
                                            if (customProfileName.isBlank()) {
                                                Text(
                                                    text = defaultPlaceholder,
                                                    color = TextMuted.copy(alpha = 0.45f),
                                                    fontSize = 12.5.sp
                                                )
                                            }
                                            innerTextField()
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }

                    is WarpImportResult.LicenseKeyOnly -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.dp, Color(0xFFF59E0B).copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                .background(Color.Transparent)
                                .padding(12.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFFF59E0B))
                                    )
                                    Text(
                                        text = stringResource(R.string.settings_warp_license_field_title),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFF59E0B)
                                    )
                                }

                                ImportParamRow(
                                    label = stringResource(R.string.vpn_import_lbl_key),
                                    value = maskLicenseKey(parsedResult.licenseKey),
                                    valueColor = Color(0xFFF59E0B)
                                )
                                Text(
                                    text = stringResource(R.string.vpn_import_key_ready_desc),
                                    fontSize = 10.sp,
                                    color = TextMuted
                                )
                            }
                        }
                    }

                    is WarpImportResult.Error -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .border(1.dp, BloodRed.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                .background(Color.Transparent)
                                .padding(10.dp)
                        ) {
                            Text(
                                text = parsedResult.message,
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace,
                                color = BloodRed
                            )
                        }
                    }
                }
            }

            // ── РАСКРЫВАЮЩИЙСЯ СПРАВОЧНЫЙ БЛОК (OUTLINE-ONLY + SPRINGPRESS) ──
            val chevronRotation by animateFloatAsState(
                targetValue = if (isGuideExpanded) 270f else 90f,
                animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
                label = "guideChevronRotation"
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, AmoledBorder, RoundedCornerShape(10.dp))
                    .background(Color.Transparent)
                    .springPress(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        isGuideExpanded = !isGuideExpanded
                    })
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.vpn_import_guide_title),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextWhite
                        )
                        MirrlyIcon(
                            id = R.drawable.ic_chevron_right,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier
                                .size(14.dp)
                                .graphicsLayer { rotationZ = chevronRotation }
                        )
                    }

                    AnimatedVisibility(
                        visible = isGuideExpanded,
                        enter = expandVertically(tween(200)) + fadeIn(tween(200)),
                        exit = shrinkVertically(tween(180)) + fadeOut(tween(180))
                    ) {
                        Column(
                            modifier = Modifier.padding(top = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.vpn_import_guide_p1),
                                fontSize = 10.sp,
                                color = TextMuted,
                                lineHeight = 14.sp
                            )
                            Text(
                                text = stringResource(R.string.vpn_import_guide_p2),
                                fontSize = 10.sp,
                                color = TextMuted,
                                lineHeight = 14.sp
                            )
                            Text(
                                text = stringResource(R.string.vpn_import_guide_p3),
                                fontSize = 10.sp,
                                color = TextMuted,
                                lineHeight = 14.sp
                            )
                            Text(
                                text = stringResource(R.string.vpn_import_guide_p4),
                                fontSize = 10.sp,
                                color = TextMuted,
                                lineHeight = 14.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            // ── ГЛАВНЫЕ КНОПКИ ДЕЙСТВИЙ ВНИЗУ (OUTLINE-ONLY + SPRINGPRESS) ──
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (parsedResult is WarpImportResult.Success) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, primaryColor.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
                            .background(Color.Transparent)
                            .springPress(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val app = com.mirrly.tgproxy.MirrlyApplication.instance
                                val fallbackName = when (parsedResult.type) {
                                    ImportedProfileType.AMNEZIA_WG_CONF -> context.getString(R.string.vpn_import_placeholder_awg)
                                    ImportedProfileType.WIREGUARD_CONF -> context.getString(R.string.vpn_import_placeholder_wg)
                                    ImportedProfileType.CLASH_META_YAML -> context.getString(R.string.vpn_import_placeholder_clash)
                                    ImportedProfileType.CLOUDFLARE_JSON -> context.getString(R.string.vpn_import_placeholder_warp)
                                    else -> context.getString(R.string.vpn_import_placeholder_default)
                                }
                                val profileName = customProfileName.trim().ifBlank { fallbackName }
                                app.warpProfileManager.saveImportedProfile(
                                    name = profileName,
                                    content = inputText.trim(),
                                    warpProfile = parsedResult.profile,
                                    type = parsedResult.type,
                                    makeActive = true
                                )
                                app.prefsManager.setVpnUseCustomProfile(true)
                                onProfileImported(parsedResult.profile)
                                Toast.makeText(context, context.getString(R.string.vpn_import_success_toast), Toast.LENGTH_SHORT).show()
                                onDismiss()
                            }),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.vpn_import_btn_apply),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = primaryColor,
                            letterSpacing = 0.4.sp
                        )
                    }
                } else if (parsedResult is WarpImportResult.LicenseKeyOnly) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, Color(0xFFF59E0B).copy(alpha = 0.8f), RoundedCornerShape(12.dp))
                            .background(Color.Transparent)
                            .springPress(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onLicenseKeyImported(parsedResult.licenseKey)
                                onDismiss()
                            }),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.vpn_import_btn_activate_key),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF59E0B),
                            letterSpacing = 0.4.sp
                        )
                    }
                }

                // Кнопка «ЗАКРЫТЬ»
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
                        text = stringResource(R.string.vpn_import_btn_close),
                        color = TextMuted,
                        fontWeight = FontWeight.Normal,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun ImportParamRow(
    label: String,
    value: String,
    valueColor: Color = TextWhite
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 10.sp, color = TextMuted)
        Text(
            text = value,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
