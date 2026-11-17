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
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.core.resolveCountryCodeForEndpoint
import com.mirrly.tgproxy.service.MirrlyVpnService
import com.mirrly.tgproxy.service.WarpProfileOrigin
import com.mirrly.tgproxy.service.WarpSavedProfile
import com.mirrly.tgproxy.ui.icon.MirrlyIcon
import com.mirrly.tgproxy.ui.theme.*

/**
 * Шторка управления и выбора «Своих профилей» (импортированные пользователем конфигурации).
 * Стандарты MirrlyUI:
 * - Zero-Popup: абсолютный запрет вложенных модальных окон, инлайн-подтверждение удаления и редактирования.
 * - Outline-Only: 1.5dp AMOLED-контуры, отсутствие цветных плашек бэкграунда.
 * - Динамические цвета протокола через LocalProtocolColors.
 * - Пружинная кинетика: Modifier.springPress на всех кнопках и карточках.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomProfilesBottomSheet(
    onDismiss: () -> Unit,
    onOpenImportDialog: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val app = com.mirrly.tgproxy.MirrlyApplication.instance
    val profileManager = app.warpProfileManager
    val protocolColors = LocalProtocolColors.current
    val primaryColor = protocolColors.primary

    val allProfiles by profileManager.profilesFlow.collectAsState()
    val activeProfileId by profileManager.activeProfileIdFlow.collectAsState()
    val isCustomActive by app.prefsManager.vpnUseCustomProfileFlow.collectAsState()
    val vpnState by MirrlyVpnService.vpnState.collectAsState()

    val customProfiles = remember(allProfiles) {
        allProfiles.filter { it.origin != WarpProfileOrigin.BUILTIN }
    }

    MirrlyModalBottomSheet(
        onDismissRequest = onDismiss,
        maxTabletWidth = 480.dp,
        containerColor = Color.Black.copy(alpha = 0.50f),
        scrimColor = Color.Black.copy(alpha = 0.32f),
        blurRadiusPx = 60
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = modifier
                .fillMaxWidth()
                .fadingEdges(topFadeHeight = 16.dp, bottomFadeHeight = 24.dp)
                .verticalScroll(rememberScrollState())
                .animateContentSize(
                    animationSpec = spring(
                        dampingRatio = 0.82f,
                        stiffness = 380f
                    )
                )
                .padding(horizontal = 18.dp)
                .padding(top = 4.dp, bottom = 12.dp)
        ) {
            // Category Pill (Outline-Only)
            Box(
                modifier = Modifier
                    .border(1.dp, primaryColor.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                    .background(Color.Transparent)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.vpn_custom_sheet_badge),
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = primaryColor,
                    letterSpacing = 1.sp
                )
            }

            // Заголовок
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.vpn_custom_sheet_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite,
                    letterSpacing = 0.3.sp
                )

                Text(
                    text = stringResource(R.string.vpn_custom_sheet_count, customProfiles.size),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    color = TextMuted
                )
            }

            // Кнопка импорта нового профиля
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, primaryColor.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                    .background(Color.Transparent)
                    .springPress(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onDismiss()
                        onOpenImportDialog()
                    }),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Canvas(modifier = Modifier.size(13.dp)) {
                        val stroke = 1.6.dp.toPx()
                        drawLine(color = primaryColor, start = Offset(size.width / 2f, 0f), end = Offset(size.width / 2f, size.height), strokeWidth = stroke)
                        drawLine(color = primaryColor, start = Offset(0f, size.height / 2f), end = Offset(size.width, size.height / 2f), strokeWidth = stroke)
                    }
                    Text(
                        text = stringResource(R.string.vpn_custom_sheet_import_btn),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = primaryColor,
                        letterSpacing = 0.6.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            // Содержимое: Список или Пустое состояние с плавной пружинной анимацией ресайза
            AnimatedContent(
                targetState = customProfiles.isEmpty(),
                transitionSpec = {
                    (fadeIn(animationSpec = tween(160)))
                        .togetherWith(fadeOut(animationSpec = tween(100)))
                        .using(
                            SizeTransform(clip = false) { _, _ ->
                                spring(dampingRatio = 0.82f, stiffness = 380f)
                            }
                        )
                },
                label = "CustomProfilesContentTransition",
                modifier = Modifier.fillMaxWidth()
            ) { isEmpty ->
                if (isEmpty) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, AmoledBorder, RoundedCornerShape(12.dp))
                            .background(Color.Transparent)
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.vpn_custom_sheet_empty_title),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite
                            )
                            Text(
                                text = stringResource(R.string.vpn_custom_sheet_empty_desc),
                                fontSize = 11.5.sp,
                                color = TextMuted,
                                textAlign = TextAlign.Center,
                                lineHeight = 16.sp
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        customProfiles.forEach { profile ->
                            key(profile.id) {
                                val isSelected = isCustomActive && activeProfileId == profile.id

                                CustomProfileCard(
                                    profile = profile,
                                    isSelected = isSelected,
                                    primaryColor = primaryColor,
                                    onSelect = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        profileManager.selectCustomProfile(profile)

                                        if (vpnState == VpnUiState.CONNECTED) {
                                            Toast.makeText(context, context.getString(R.string.vpn_server_toast_switched, profile.name), Toast.LENGTH_SHORT).show()
                                            MirrlyVpnService.restart(context)
                                        } else {
                                            Toast.makeText(context, context.getString(R.string.vpn_server_toast_selected, profile.name), Toast.LENGTH_SHORT).show()
                                        }
                                        onDismiss()
                                    },
                                    onDeleteConfirmed = {
                                        profileManager.deleteProfile(profile.id)
                                        Toast.makeText(context, context.getString(R.string.vpn_custom_sheet_toast_deleted), Toast.LENGTH_SHORT).show()
                                    },
                                    onRenameConfirmed = { newName ->
                                        profileManager.renameProfile(profile.id, newName)
                                        Toast.makeText(context, context.getString(R.string.vpn_custom_sheet_toast_renamed), Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Карточка пользовательского профиля.
 * Поддерживает инлайн-переименование и инлайн-подтверждение удаления без всплывающих окон.
 */
@Composable
private fun CustomProfileCard(
    profile: WarpSavedProfile,
    isSelected: Boolean,
    primaryColor: Color,
    onSelect: () -> Unit,
    onDeleteConfirmed: () -> Unit,
    onRenameConfirmed: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val borderColor = if (isSelected) primaryColor else AmoledBorder
    val borderWidth = if (isSelected) 1.5.dp else 1.dp
    val countryCode = remember(profile.endpoint) { resolveCountryCodeForEndpoint(profile.endpoint) }

    var isConfirmingDelete by remember { mutableStateOf(false) }
    var isEditingName by remember { mutableStateOf(false) }
    var editedName by remember(profile.name) { mutableStateOf(profile.name) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(borderWidth, borderColor, RoundedCornerShape(12.dp))
            .background(Color.Transparent)
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = 0.82f,
                    stiffness = 380f
                )
            )
            .springPress(onClick = {
                if (!isConfirmingDelete && !isEditingName) {
                    onSelect()
                }
            })
            .padding(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CountryFlagBadge(countryCode = countryCode)

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile.name,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isSelected) primaryColor else TextWhite,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (profile.endpoint.isNotBlank()) profile.endpoint else profile.format.title,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextMuted,
                        maxLines = 1
                    )
                }

                // Кнопка редактирования имени
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .border(0.8.dp, if (isEditingName) primaryColor else AmoledBorder, CircleShape)
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            isConfirmingDelete = false
                            isEditingName = !isEditingName
                        }),
                    contentAlignment = Alignment.Center
                ) {
                    MirrlyIcon(
                        id = R.drawable.ic_edit,
                        contentDescription = stringResource(R.string.vpn_custom_sheet_action_rename),
                        tint = if (isEditingName) primaryColor else TextMuted,
                        modifier = Modifier.size(13.dp)
                    )
                }

                // Кнопка удаления профиля
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .border(0.8.dp, if (isConfirmingDelete) BloodRed else AmoledBorder, CircleShape)
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            isEditingName = false
                            isConfirmingDelete = !isConfirmingDelete
                        }),
                    contentAlignment = Alignment.Center
                ) {
                    MirrlyIcon(
                        id = R.drawable.ic_trash,
                        contentDescription = stringResource(R.string.vpn_custom_sheet_action_delete),
                        tint = if (isConfirmingDelete) BloodRed else TextMuted,
                        modifier = Modifier.size(13.dp)
                    )
                }

                // Радио-метка выбора
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .border(1.dp, if (isSelected) primaryColor else AmoledBorder, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(primaryColor)
                        )
                    }
                }
            }

            // Инлайн-редактор имени профиля (Zero-Popup)
            AnimatedVisibility(
                visible = isEditingName,
                enter = expandVertically(tween(180)) + fadeIn(tween(180)),
                exit = shrinkVertically(tween(160)) + fadeOut(tween(160))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    BasicTextField(
                        value = editedName,
                        onValueChange = { editedName = it.take(35) },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = TextWhite,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        cursorBrush = SolidColor(primaryColor),
                        decorationBox = { inner ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(34.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(1.dp, primaryColor.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                inner()
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )

                    Box(
                        modifier = Modifier
                            .height(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, primaryColor, RoundedCornerShape(8.dp))
                            .springPress(onClick = {
                                if (editedName.isNotBlank()) {
                                    onRenameConfirmed(editedName.trim())
                                    isEditingName = false
                                }
                            })
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "OK", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = primaryColor)
                    }

                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, AmoledBorder, RoundedCornerShape(8.dp))
                            .springPress(onClick = {
                                editedName = profile.name
                                isEditingName = false
                            }),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "✕", fontSize = 11.sp, color = TextMuted)
                    }
                }
            }

            // Инлайн-подтверждение удаления (Zero-Popup)
            AnimatedVisibility(
                visible = isConfirmingDelete,
                enter = expandVertically(tween(180)) + fadeIn(tween(180)),
                exit = shrinkVertically(tween(160)) + fadeOut(tween(160))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, BloodRed.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.vpn_custom_sheet_delete_confirm, profile.name),
                        fontSize = 11.5.sp,
                        color = TextWhite.copy(alpha = 0.9f),
                        lineHeight = 15.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, AmoledBorder, RoundedCornerShape(8.dp))
                                .springPress(onClick = { isConfirmingDelete = false }),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = stringResource(R.string.action_cancel).uppercase(), fontSize = 11.sp, color = TextMuted)
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, BloodRed, RoundedCornerShape(8.dp))
                                .springPress(onClick = {
                                    isConfirmingDelete = false
                                    onDeleteConfirmed()
                                }),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = stringResource(R.string.vpn_custom_sheet_action_delete).uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = BloodRed)
                        }
                    }
                }
            }
        }
    }
}
