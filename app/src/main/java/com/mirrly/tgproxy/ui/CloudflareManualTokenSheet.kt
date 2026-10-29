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

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
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
import com.mirrly.tgproxy.service.PreferencesManager
import com.mirrly.tgproxy.service.cloudflare.CloudflareAccount
import com.mirrly.tgproxy.service.cloudflare.CloudflareApiClient
import com.mirrly.tgproxy.ui.theme.*
import kotlinx.coroutines.launch

private val CfOrange = Color(0xFFF38020)

/**
 * MirrlyUI Modal Bottom Sheet for Manual Cloudflare API Token Authorization.
 * Conforms to:
 * - Dogma 1: Zero Background / Outline-Only architecture.
 * - Dogma 2: Zero-Popup doctrine (MirrlyModalBottomSheet with 50% AMOLED alpha and Skia backdrop blur).
 * - Kinetic tactile spring physics (Modifier.springPress).
 * - Multi-account selection support for tokens with access to multiple Cloudflare tenants.
 * - Zero emoji & strict technical clarity.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudflareManualTokenSheet(
    prefs: PreferencesManager,
    onDismiss: () -> Unit,
    onTokenSaved: (token: String, accountId: String, accountName: String, subdomain: String) -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    var tokenInput by remember { mutableStateOf("") }
    var isVerifying by remember { mutableStateOf(false) }
    var verificationError by remember { mutableStateOf<String?>(null) }

    var discoveredAccounts by remember { mutableStateOf<List<CloudflareAccount>>(emptyList()) }
    var selectedAccountId by remember { mutableStateOf<String?>(null) }
    var showExternalLinkConfirm by remember { mutableStateOf(false) }

    if (showExternalLinkConfirm) {
        ExternalLinkConfirmDialog(
            url = "https://dash.cloudflare.com/profile/api-tokens",
            onDismiss = { showExternalLinkConfirm = false }
        )
    }

    fun handlePaste() {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
        if (!clip.isNullOrBlank()) {
            tokenInput = clip
            verificationError = null
            discoveredAccounts = emptyList()
            selectedAccountId = null
        } else {
            Toast.makeText(context, "Буфер обмена пуст", Toast.LENGTH_SHORT).show()
        }
    }

    fun submitToken() {
        val cleanToken = tokenInput.trim()
        if (cleanToken.isBlank()) {
            verificationError = "Введите или вставьте API токен"
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            return
        }

        keyboard?.hide()
        isVerifying = true
        verificationError = null
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)

        scope.launch {
            // 1. Verify token status
            val checkRes = CloudflareApiClient.verifyToken(cleanToken)
            if (checkRes.isFailure) {
                isVerifying = false
                val rawMsg = checkRes.exceptionOrNull()?.message.orEmpty()
                verificationError = if (rawMsg.contains("401") || CloudflareApiClient.isAuthError(rawMsg)) {
                    "Токен недействителен или отозван в панели Cloudflare"
                } else {
                    rawMsg.ifBlank { "Ошибка проверки токена" }
                }
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                return@launch
            }

            // 2. Discover accounts accessible by this token
            val accRes = CloudflareApiClient.getAccounts(cleanToken)
            val accounts = accRes.getOrNull().orEmpty()

            if (accounts.isEmpty()) {
                isVerifying = false
                verificationError = "У токена нет доступа ни к одному аккаунту. Проверьте права Account: Read."
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                return@launch
            }

            // If multiple accounts found and none selected yet, present choice
            if (accounts.size > 1 && selectedAccountId == null) {
                discoveredAccounts = accounts
                selectedAccountId = accounts.first().id
                isVerifying = false
                return@launch
            }

            val targetAccount = accounts.firstOrNull { it.id == selectedAccountId } ?: accounts.first()
            val accId = targetAccount.id
            val accLabel = targetAccount.name

            // 3. Check for existing workers.dev subdomain
            var subdomain = ""
            if (accId.isNotEmpty()) {
                val subRes = CloudflareApiClient.getSubdomain(cleanToken, accId)
                subdomain = subRes.getOrNull().orEmpty()
            }

            // 4. Persist encrypted session
            prefs.saveCloudflareSession(
                accessToken = cleanToken,
                refreshToken = null,
                accountId = accId,
                accountName = accLabel,
                subdomain = subdomain
            )

            isVerifying = false
            Toast.makeText(context, context.getString(R.string.cf_deploy_token_saved), Toast.LENGTH_SHORT).show()
            onTokenSaved(cleanToken, accId, accLabel, subdomain)
            onDismiss()
        }
    }

    MirrlyModalBottomSheet(
        onDismissRequest = {
            if (!isVerifying) onDismiss()
        },
        maxTabletWidth = 480.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fadingEdges(topFadeHeight = 16.dp, bottomFadeHeight = 24.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            MirrlyBottomSheetHeader(
                title = stringResource(R.string.cf_deploy_token_dialog_title),
                subtitle = "Авторизация через персональный API токен",
                onClose = { if (!isVerifying) onDismiss() }
            )

            // Input Card
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, if (verificationError != null) Color(0xFFEF4444).copy(alpha = 0.6f) else AmoledBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "КЛЮЧ ДОСТУПА",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = CfOrange,
                            letterSpacing = 1.sp
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (tokenInput.isNotBlank()) {
                                Text(
                                    text = "Очистить",
                                    fontSize = 11.sp,
                                    color = TextMuted,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable {
                                            tokenInput = ""
                                            verificationError = null
                                            discoveredAccounts = emptyList()
                                            selectedAccountId = null
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Text(
                                text = "Вставить",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = CfOrange,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { handlePaste() }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    OutlinedTextField(
                        value = tokenInput,
                        onValueChange = {
                            tokenInput = it
                            verificationError = null
                        },
                        placeholder = {
                            Text(
                                text = "Вставьте Bearer токен Cloudflare...",
                                color = TextMuted,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        },
                        textStyle = LocalTextStyle.current.copy(
                            fontSize = 12.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextWhite
                        ),
                        singleLine = false,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submitToken() }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CfOrange,
                            unfocusedBorderColor = AmoledBorder,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            cursorColor = CfOrange
                        )
                    )
                }
            }

            // Error Message (If Any)
            if (verificationError != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_stat_proxy_error),
                            contentDescription = null,
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = verificationError.orEmpty(),
                            color = Color(0xFFFCA5A5),
                            fontSize = 11.5.sp,
                            lineHeight = 15.sp
                        )
                    }
                }
            }

            // Multi-Account Selection Card (If more than 1 account found)
            AnimatedVisibility(
                visible = discoveredAccounts.size > 1,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, CfOrange.copy(alpha = 0.40f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "ВЫБЕРИТЕ АККАУНТ CLOUDFLARE",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = CfOrange,
                            letterSpacing = 1.sp
                        )

                        Text(
                            text = "Токен предоставляет доступ к нескольким рабочим пространствам. Выберите аккаунт для деплоя воркеров:",
                            fontSize = 11.5.sp,
                            color = TextMuted,
                            lineHeight = 15.sp
                        )

                        discoveredAccounts.forEach { acc ->
                            val isSelected = acc.id == selectedAccountId
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) CfOrange.copy(alpha = 0.12f) else Color.Transparent,
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) CfOrange.copy(alpha = 0.60f) else AmoledBorder
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        selectedAccountId = acc.id
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = acc.name,
                                            fontSize = 13.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = TextWhite,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = acc.id,
                                            fontSize = 10.5.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = TextMuted
                                        )
                                    }

                                    Box(
                                        modifier = Modifier
                                            .size(18.dp)
                                            .border(
                                                BorderStroke(1.dp, if (isSelected) CfOrange else AmoledBorder),
                                                CircleShape
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isSelected) {
                                            Box(
                                                modifier = Modifier
                                                    .size(10.dp)
                                                    .clip(CircleShape)
                                                    .background(CfOrange)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Permissions Checklist (Outline-Only)
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, AmoledBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "ТРЕБУЕМЫЕ ПРАВА API",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite,
                        letterSpacing = 1.sp
                    )

                    listOf(
                        "Account · Workers Scripts · Edit" to "Создание и публикация кода воркера",
                        "Account · Workers Routes · Edit" to "Привязка субдомена *.workers.dev",
                        "Account · Account Settings · Read" to "Определение ID аккаунта и квот",
                        "User · User Details · Read" to "Проверка валидности сессии"
                    ).forEach { (scopeName, desc) ->
                        Row(
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .size(14.dp)
                                    .border(BorderStroke(1.dp, CfOrange.copy(alpha = 0.5f)), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_check),
                                    contentDescription = null,
                                    tint = CfOrange,
                                    modifier = Modifier.size(8.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = scopeName,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextWhite
                                )
                                Text(
                                    text = desc,
                                    fontSize = 11.sp,
                                    color = TextMuted,
                                    lineHeight = 14.sp
                                )
                            }
                        }
                    }
                }
            }

            // Dashboard link
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, AmoledBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .springPress(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        showExternalLinkConfirm = true
                    })
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Панель создания токенов",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextWhite
                        )
                        Text(
                            text = "dash.cloudflare.com/profile/api-tokens",
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = CfOrange
                        )
                    }

                    Icon(
                        painter = painterResource(id = R.drawable.ic_chevron_right),
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Action Buttons
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Primary: Submit
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (tokenInput.isNotBlank() && !isVerifying) CfOrange.copy(alpha = 0.18f) else Color.Transparent,
                    border = BorderStroke(
                        1.dp,
                        if (tokenInput.isNotBlank() && !isVerifying) CfOrange.copy(alpha = 0.70f) else AmoledBorder
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .springPress(
                            onClick = { submitToken() }
                        )
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (isVerifying) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(
                                    color = CfOrange,
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Text(
                                    text = "Проверка прав доступа...",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextWhite
                                )
                            }
                        } else {
                            Text(
                                text = "Проверить и сохранить токен",
                                color = if (tokenInput.isNotBlank()) CfOrange else TextMuted,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Dismiss
                TextButton(
                    onClick = { if (!isVerifying) onDismiss() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                ) {
                    Text(
                        text = "Отмена",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}
