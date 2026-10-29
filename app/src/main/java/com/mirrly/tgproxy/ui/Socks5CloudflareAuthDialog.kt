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
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.core.WorkerDomainNormalizer
import com.mirrly.tgproxy.service.cloudflare.CloudflareApiClient
import com.mirrly.tgproxy.service.cloudflare.CloudflareOAuthManager
import com.mirrly.tgproxy.service.cloudflare.ServerAuthState
import com.mirrly.tgproxy.ui.theme.*
import kotlinx.coroutines.launch

private val CloudflareOrange = Color(0xFFF38020)
private val CloudflareOrangeDim = Color(0xFFFAAD3F)

/**
 * MirrlyUI Modal Bottom Sheet for SOCKS5 Cloudflare Authorization.
 * Adheres strictly to:
 * - Dogma 1: Zero Background / Outline-Only architecture.
 * - Dogma 2: Zero-Popup doctrine (MirrlyModalBottomSheet with 50% AMOLED alpha and Skia backdrop blur).
 * - Kinetic tactile spring physics (Modifier.springPress).
 * - Zero emoji & strict technical clarity.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Socks5CloudflareAuthDialog(
    onDismiss: () -> Unit,
    onAuthSuccess: (() -> Unit)? = null,
    onStartLogin: (() -> Unit)? = null,
    onCustomWorkerSaved: (domain: String) -> Unit,
    onOpenManualGuide: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val app = MirrlyApplication.instance
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    val actualStartLogin = onStartLogin ?: {
        CloudflareOAuthManager.startAuthSession(context)
    }

    val cfAuthState by CloudflareOAuthManager.authState.collectAsState()

    var isManualCallbackExpanded by remember { mutableStateOf(false) }
    var callbackUrlInput by remember { mutableStateOf("") }
    var callbackUrlError by remember { mutableStateOf<String?>(null) }

    var isApiTokenExpanded by remember { mutableStateOf(false) }
    var apiTokenInput by remember { mutableStateOf("") }
    var apiTokenError by remember { mutableStateOf<String?>(null) }
    var isVerifyingToken by remember { mutableStateOf(false) }

    var isCustomInputExpanded by remember { mutableStateOf(false) }
    var customDomainInput by remember { mutableStateOf("") }
    var inputError by remember { mutableStateOf<String?>(null) }

    MirrlyModalBottomSheet(
        onDismissRequest = onDismiss,
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
                title = stringResource(R.string.socks5_cf_auth_title),
                subtitle = stringResource(R.string.socks5_cf_auth_desc),
                onClose = onDismiss
            )

            // Card: Why this is required
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, AmoledBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.socks5_cf_auth_card_why_title),
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = CloudflareOrangeDim
                    )
                    Text(
                        text = stringResource(R.string.socks5_cf_auth_card_why_desc),
                        fontSize = 11.5.sp,
                        color = TextMuted,
                        lineHeight = 16.sp
                    )
                }
            }

            // Primary Button: Login with Cloudflare OAuth
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = CloudflareOrange.copy(alpha = 0.15f),
                border = BorderStroke(1.dp, CloudflareOrange.copy(alpha = 0.60f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .springPress(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        actualStartLogin()
                    })
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.socks5_cf_auth_btn_login),
                        color = CloudflareOrange,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }

            // Dynamic OAuth Status & Fallback Card
            val currentAuthState = cfAuthState
            if (currentAuthState is ServerAuthState.WaitingCallback ||
                currentAuthState is ServerAuthState.ExchangingTokens ||
                currentAuthState is ServerAuthState.Error
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color.Transparent,
                    border = BorderStroke(
                        1.dp,
                        if (currentAuthState is ServerAuthState.Error) Color(0xFFEF4444).copy(alpha = 0.45f)
                        else CloudflareOrange.copy(alpha = 0.40f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        when (currentAuthState) {
                            is ServerAuthState.WaitingCallback -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(CloudflareOrange)
                                    )
                                    Text(
                                        text = stringResource(R.string.socks5_cf_auth_waiting_title),
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = CloudflareOrangeDim
                                    )
                                }
                                Text(
                                    text = stringResource(R.string.socks5_cf_auth_waiting_desc),
                                    fontSize = 11.5.sp,
                                    color = TextMuted,
                                    lineHeight = 15.sp
                                )

                                // Button: Paste URL from browser
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = CloudflareOrange.copy(alpha = 0.12f),
                                    border = BorderStroke(1.dp, CloudflareOrange.copy(alpha = 0.45f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(42.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .springPress(onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                            val clipText = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: ""
                                            if (clipText.contains("code=")) {
                                                val processed = CloudflareOAuthManager.processCallbackUrl(clipText)
                                                if (!processed) {
                                                    callbackUrlError = context.getString(R.string.socks5_cf_auth_url_invalid)
                                                    isManualCallbackExpanded = true
                                                }
                                            } else {
                                                isManualCallbackExpanded = true
                                            }
                                        })
                                ) {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text(
                                            text = stringResource(R.string.socks5_cf_auth_btn_paste_url),
                                            color = CloudflareOrange,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 12.5.sp
                                        )
                                    }
                                }

                                AnimatedVisibility(
                                    visible = isManualCallbackExpanded,
                                    enter = expandVertically() + fadeIn(),
                                    exit = shrinkVertically() + fadeOut()
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = callbackUrlInput,
                                            onValueChange = {
                                                callbackUrlInput = it
                                                callbackUrlError = null
                                            },
                                            placeholder = {
                                                Text(
                                                    stringResource(R.string.socks5_cf_auth_paste_hint),
                                                    color = TextMuted,
                                                    fontSize = 11.5.sp,
                                                    fontFamily = FontFamily.Monospace
                                                )
                                            },
                                            textStyle = LocalTextStyle.current.copy(
                                                fontSize = 12.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = TextWhite
                                            ),
                                            singleLine = true,
                                            isError = callbackUrlError != null,
                                            keyboardOptions = KeyboardOptions(
                                                keyboardType = KeyboardType.Uri,
                                                imeAction = ImeAction.Done
                                            ),
                                            keyboardActions = KeyboardActions(
                                                onDone = { keyboard?.hide() }
                                            ),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = CloudflareOrange,
                                                unfocusedBorderColor = AmoledBorder,
                                                focusedContainerColor = Color.Transparent,
                                                unfocusedContainerColor = Color.Transparent,
                                                cursorColor = CloudflareOrange
                                            ),
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(10.dp)
                                        )

                                        if (callbackUrlError != null) {
                                            Text(
                                                text = callbackUrlError ?: "",
                                                color = Color(0xFFEF4444),
                                                fontSize = 11.sp
                                            )
                                        }

                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = CloudflareOrange.copy(alpha = 0.15f),
                                            border = BorderStroke(1.dp, CloudflareOrange.copy(alpha = 0.60f)),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(40.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .springPress(onClick = {
                                                    val raw = callbackUrlInput.trim()
                                                    if (!raw.contains("code=")) {
                                                        callbackUrlError = context.getString(R.string.socks5_cf_auth_url_invalid)
                                                        return@springPress
                                                    }
                                                    val ok = CloudflareOAuthManager.processCallbackUrl(raw)
                                                    if (!ok) {
                                                        callbackUrlError = context.getString(R.string.socks5_cf_auth_url_invalid)
                                                    }
                                                })
                                        ) {
                                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                Text(
                                                    text = stringResource(R.string.socks5_cf_auth_paste_confirm),
                                                    color = CloudflareOrange,
                                                    fontWeight = FontWeight.SemiBold,
                                                    fontSize = 12.5.sp
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            is ServerAuthState.ExchangingTokens -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        color = CloudflareOrange,
                                        strokeWidth = 2.dp
                                    )
                                    Text(
                                        text = stringResource(R.string.socks5_cf_auth_exchanging),
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextWhite
                                    )
                                }
                            }

                            is ServerAuthState.Error -> {
                                Text(
                                    text = currentAuthState.message,
                                    fontSize = 11.5.sp,
                                    color = Color(0xFFFCA5A5),
                                    lineHeight = 15.sp
                                )
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = Color.Transparent,
                                    border = BorderStroke(1.dp, AmoledBorder),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(40.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .springPress(onClick = { actualStartLogin() })
                                ) {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text(
                                            text = stringResource(R.string.action_retry),
                                            color = TextWhite,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 12.5.sp
                                        )
                                    }
                                }
                            }

                            else -> {}
                        }
                    }
                }
            }

            // Section: API Token Sign In (Accordion card)
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, AmoledBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                isApiTokenExpanded = !isApiTokenExpanded
                                apiTokenError = null
                            },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.socks5_cf_auth_btn_api_token),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextWhite
                            )
                            Text(
                                text = stringResource(R.string.socks5_cf_auth_api_token_desc),
                                fontSize = 11.sp,
                                color = TextMuted
                            )
                        }
                        Icon(
                            painter = painterResource(
                                if (isApiTokenExpanded) R.drawable.ic_arrow_up else R.drawable.ic_arrow_down
                            ),
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    AnimatedVisibility(
                        visible = isApiTokenExpanded,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Step-by-step instructions card
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, AmoledBorder, RoundedCornerShape(10.dp))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.socks5_cf_auth_api_token_how_to_title),
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = CloudflareOrangeDim
                                )
                                Text(
                                    text = stringResource(R.string.socks5_cf_auth_api_token_step1),
                                    fontSize = 11.sp,
                                    color = TextWhite.copy(alpha = 0.85f),
                                    lineHeight = 15.sp
                                )
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = CloudflareOrange.copy(alpha = 0.12f),
                                    border = BorderStroke(1.dp, CloudflareOrange.copy(alpha = 0.40f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(36.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            val uri = Uri.parse("https://dash.cloudflare.com/profile/api-tokens")
                                            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                        }
                                ) {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text(
                                            text = stringResource(R.string.socks5_cf_auth_api_token_open_dash_btn),
                                            color = CloudflareOrange,
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                                Text(
                                    text = stringResource(R.string.socks5_cf_auth_api_token_step2),
                                    fontSize = 11.sp,
                                    color = TextWhite.copy(alpha = 0.85f),
                                    lineHeight = 15.sp
                                )
                                Text(
                                    text = stringResource(R.string.socks5_cf_auth_api_token_step3),
                                    fontSize = 11.sp,
                                    color = TextWhite.copy(alpha = 0.85f),
                                    lineHeight = 15.sp
                                )
                            }

                            OutlinedTextField(
                                value = apiTokenInput,
                                onValueChange = {
                                    apiTokenInput = it
                                    apiTokenError = null
                                },
                                placeholder = {
                                    Text(
                                        stringResource(R.string.socks5_cf_auth_api_token_hint),
                                        color = TextMuted,
                                        fontSize = 11.5.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                },
                                textStyle = LocalTextStyle.current.copy(
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = TextWhite
                                ),
                                singleLine = true,
                                isError = apiTokenError != null,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = { keyboard?.hide() }
                                ),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = CloudflareOrange,
                                    unfocusedBorderColor = AmoledBorder,
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    cursorColor = CloudflareOrange
                                ),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            )

                            if (apiTokenError != null) {
                                Text(
                                    text = apiTokenError ?: "",
                                    color = Color(0xFFEF4444),
                                    fontSize = 11.sp
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = CloudflareOrange.copy(alpha = 0.15f),
                                border = BorderStroke(1.dp, CloudflareOrange.copy(alpha = 0.60f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(42.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .springPress(
                                        enabled = !isVerifyingToken,
                                        onClick = {
                                            val cleanToken = apiTokenInput.trim()
                                            if (cleanToken.isNotBlank()) {
                                                isVerifyingToken = true
                                                apiTokenError = null
                                                scope.launch {
                                                    val checkRes = CloudflareApiClient.verifyToken(cleanToken)
                                                    if (checkRes.isFailure) {
                                                        isVerifyingToken = false
                                                        apiTokenError = checkRes.exceptionOrNull()?.message
                                                            ?: context.getString(R.string.cf_deploy_invalid_token)
                                                        return@launch
                                                    }

                                                    val accRes = CloudflareApiClient.getAccounts(cleanToken)
                                                    val firstAcc = accRes.getOrNull()?.firstOrNull()
                                                    val accId = firstAcc?.id ?: ""
                                                    val accLabel = firstAcc?.name ?: "Cloudflare Token"

                                                    var sub = ""
                                                    if (accId.isNotEmpty()) {
                                                        val subRes = CloudflareApiClient.getSubdomain(cleanToken, accId)
                                                        sub = subRes.getOrNull() ?: ""
                                                    }

                                                    app.prefsManager.saveCloudflareSession(
                                                        accessToken = cleanToken,
                                                        refreshToken = null,
                                                        accountId = accId,
                                                        accountName = accLabel,
                                                        subdomain = sub
                                                    )

                                                    isVerifyingToken = false
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    Toast.makeText(context, context.getString(R.string.cf_deploy_token_saved), Toast.LENGTH_SHORT).show()
                                                    onAuthSuccess?.invoke() ?: onDismiss()
                                                }
                                            }
                                        }
                                    )
                            ) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    if (isVerifyingToken) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            color = CloudflareOrange,
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Text(
                                            text = stringResource(R.string.socks5_cf_auth_api_token_save),
                                            color = CloudflareOrange,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 12.5.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Section: Custom Worker Option (Accordion card)
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, AmoledBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                isCustomInputExpanded = !isCustomInputExpanded
                                inputError = null
                            },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.socks5_cf_auth_btn_custom),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextWhite
                            )
                            Text(
                                text = stringResource(R.string.socks5_cf_auth_custom_title),
                                fontSize = 11.sp,
                                color = TextMuted
                            )
                        }
                        Icon(
                            painter = painterResource(
                                if (isCustomInputExpanded) R.drawable.ic_arrow_up else R.drawable.ic_arrow_down
                            ),
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    AnimatedVisibility(
                        visible = isCustomInputExpanded,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value = customDomainInput,
                                onValueChange = {
                                    customDomainInput = it
                                    inputError = null
                                },
                                placeholder = {
                                    Text(
                                        stringResource(R.string.socks5_cf_auth_custom_hint),
                                        color = TextMuted,
                                        fontSize = 11.5.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                },
                                textStyle = LocalTextStyle.current.copy(
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = TextWhite
                                ),
                                singleLine = true,
                                isError = inputError != null,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Uri,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = { keyboard?.hide() }
                                ),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = CloudflareOrange,
                                    unfocusedBorderColor = AmoledBorder,
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    cursorColor = CloudflareOrange
                                ),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            )

                            if (inputError != null) {
                                Text(
                                    text = inputError ?: "",
                                    color = Color(0xFFEF4444),
                                    fontSize = 11.sp
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = CloudflareOrange.copy(alpha = 0.15f),
                                border = BorderStroke(1.dp, CloudflareOrange.copy(alpha = 0.60f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(42.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .springPress(onClick = {
                                        val formRes = WorkerDomainNormalizer.normalizeForm("", customDomainInput)
                                        val cleanDomain = formRes.normalizedDomain
                                        if (cleanDomain.isBlank()) {
                                            inputError = context.getString(R.string.socks5_cf_auth_custom_invalid)
                                            return@springPress
                                        }

                                        val addRes = app.prefsManager.addCustomWorker(
                                            name = formRes.normalizedName.ifBlank { "Мой воркер" },
                                            domain = cleanDomain,
                                            isCloudflarePersonal = false
                                        )

                                        if (addRes.isSuccess) {
                                            val created = addRes.getOrNull()
                                            if (created != null) {
                                                app.prefsManager.setActiveWorkerId(created.id, fromUserAction = true)
                                            }
                                            app.config.customCfDomain = cleanDomain
                                            app.config.useDefaultWorkerSocks5 = false
                                            app.prefsManager.saveConfig(app.config)
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onCustomWorkerSaved(cleanDomain)
                                        } else {
                                            inputError = addRes.exceptionOrNull()?.message
                                                ?: context.getString(R.string.socks5_cf_auth_custom_invalid)
                                        }
                                    })
                            ) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = stringResource(R.string.socks5_cf_auth_custom_save),
                                        color = CloudflareOrange,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 12.5.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Tertiary Action: Manual Creation Guide
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, AmoledBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .springPress(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onOpenManualGuide()
                    })
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.socks5_cf_auth_btn_manual_guide),
                        color = TextWhite.copy(alpha = 0.9f),
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.5.sp
                    )
                }
            }

            // Dismiss Button
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
            ) {
                Text(
                    text = stringResource(R.string.action_cancel),
                    color = TextMuted,
                    fontSize = 12.sp
                )
            }
        }
    }
}
