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

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.service.cloudflare.CloudflareApiClient
import com.mirrly.tgproxy.ui.theme.*
import kotlinx.coroutines.launch

private val CloudflareOrange = Color(0xFFF38020)
private val CloudflareOrangeDim = Color(0xFFFAAD3F)
private val ErrorRed = Color(0xFFEF4444)

/**
 * MirrlyUI Modal Bottom Sheet for setting up Cloudflare workers.dev subdomain.
 * Replaces the legacy modal Dialog with a Zero-Popup MirrlyModalBottomSheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudflareSubdomainSetupDialog(
    onDismiss: () -> Unit,
    onSubdomainConfigured: (subdomain: String) -> Unit,
    initialSubdomain: String = ""
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val app = MirrlyApplication.instance
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    var subdomainInput by remember { mutableStateOf(initialSubdomain.trim().lowercase()) }
    var isSaving by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }

    fun cleanInput(raw: String): String {
        return raw.trim()
            .lowercase()
            .removePrefix("https://")
            .removePrefix("http://")
            .removeSuffix(".workers.dev")
            .replace(Regex("[^a-z0-9-]"), "")
    }

    fun validateSubdomain(sub: String): String? {
        if (sub.isBlank()) return context.getString(R.string.cf_subdomain_err_empty)
        if (sub.length < 3) return context.getString(R.string.cf_subdomain_err_too_short)
        if (sub.length > 32) return context.getString(R.string.cf_subdomain_err_too_long)
        if (!sub.matches(Regex("^[a-z0-9-]+$"))) return context.getString(R.string.cf_subdomain_err_invalid_chars)
        if (sub.startsWith("-") || sub.endsWith("-")) return context.getString(R.string.cf_subdomain_err_hyphen_edge)
        return null
    }

    fun generateRandomSubdomain(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        val suffix = (1..5).map { chars.random() }.joinToString("")
        return "tg-node-$suffix"
    }

    fun submitRegistration() {
        keyboard?.hide()
        val token = app.prefsManager.getCloudflareToken().orEmpty()
        val accountId = app.prefsManager.getCloudflareAccountId().orEmpty()

        if (token.isBlank() || accountId.isBlank()) {
            validationError = context.getString(R.string.cf_deploy_subdomain_reg_error)
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            return
        }

        val cleaned = cleanInput(subdomainInput)
        val err = validateSubdomain(cleaned)
        if (err != null) {
            validationError = err
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            return
        }

        isSaving = true
        validationError = null
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)

        scope.launch {
            val res = CloudflareApiClient.registerSubdomain(token, accountId, cleaned, app.prefsManager)
            isSaving = false
            if (res.isSuccess) {
                val registered = res.getOrNull() ?: cleaned
                app.prefsManager.setCloudflareSubdomain(registered)
                Toast.makeText(
                    context,
                    context.getString(R.string.cf_deploy_subdomain_activated, registered),
                    Toast.LENGTH_SHORT
                ).show()
                onSubdomainConfigured(registered)
                onDismiss()
            } else {
                val ex = res.exceptionOrNull()
                val rawMsg = ex?.message.orEmpty()
                validationError = if (rawMsg.contains("10013") ||
                    rawMsg.contains("already exists", ignoreCase = true) ||
                    rawMsg.contains("taken", ignoreCase = true)
                ) {
                    context.getString(R.string.cf_subdomain_err_already_taken)
                } else {
                    rawMsg.ifBlank { context.getString(R.string.cf_deploy_subdomain_reg_error) }
                }
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        }
    }

    MirrlyModalBottomSheet(
        onDismissRequest = {
            if (!isSaving) onDismiss()
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
                title = stringResource(R.string.cf_subdomain_dialog_title),
                subtitle = stringResource(R.string.cf_subdomain_dialog_subtitle),
                onClose = { if (!isSaving) onDismiss() }
            )

            // Info Card: Explanations
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
                        text = stringResource(R.string.cf_subdomain_what_title),
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = CloudflareOrangeDim
                    )
                    Text(
                        text = stringResource(R.string.cf_subdomain_what_desc),
                        fontSize = 11.5.sp,
                        color = TextMuted,
                        lineHeight = 16.sp
                    )

                    HorizontalDivider(color = AmoledBorder, thickness = 0.8.dp)

                    Text(
                        text = stringResource(R.string.cf_subdomain_rules_title),
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = CloudflareOrangeDim
                    )
                    Text(
                        text = stringResource(R.string.cf_subdomain_rules_desc),
                        fontSize = 11.5.sp,
                        color = TextMuted,
                        lineHeight = 16.sp
                    )
                }
            }

            // Input Card
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, if (validationError != null) ErrorRed.copy(alpha = 0.6f) else AmoledBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.cf_subdomain_input_label),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = CloudflareOrange,
                            letterSpacing = 1.sp
                        )

                        // Button: Random Subdomain
                        Text(
                            text = stringResource(R.string.cf_subdomain_btn_random),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = CloudflareOrangeDim,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .border(1.dp, CloudflareOrange.copy(alpha = 0.40f), RoundedCornerShape(6.dp))
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    subdomainInput = generateRandomSubdomain()
                                    validationError = null
                                }
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }

                    OutlinedTextField(
                        value = subdomainInput,
                        onValueChange = { raw ->
                            val cleaned = cleanInput(raw)
                            if (cleaned.length <= 32) {
                                subdomainInput = cleaned
                                validationError = null
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(
                                text = stringResource(R.string.cf_subdomain_input_placeholder),
                                color = TextMuted,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        },
                        textStyle = LocalTextStyle.current.copy(
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextWhite
                        ),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        trailingIcon = {
                            Text(
                                text = "${subdomainInput.length}/32",
                                fontSize = 11.sp,
                                color = TextMuted,
                                modifier = Modifier.padding(end = 12.dp)
                            )
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submitRegistration() }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CloudflareOrange,
                            unfocusedBorderColor = AmoledBorder,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            cursorColor = CloudflareOrange
                        )
                    )

                    if (validationError != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_stat_proxy_error),
                                contentDescription = null,
                                tint = ErrorRed,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = validationError.orEmpty(),
                                color = ErrorRed,
                                fontSize = 11.5.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            }

            // Live Preview Card
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, AmoledBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.cf_subdomain_preview_label),
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                    val previewSubdomain = subdomainInput.ifBlank { "example-relay" }
                    Text(
                        text = "https://tg-proxy.$previewSubdomain.workers.dev",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace,
                        color = CloudflareOrangeDim
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
                // Primary Action Button: Register Subdomain
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = CloudflareOrange.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, CloudflareOrange.copy(alpha = 0.65f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .springPress(onClick = {
                            if (!isSaving) submitRegistration()
                        })
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (isSaving) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = CloudflareOrange,
                                    strokeWidth = 2.dp
                                )
                                Text(
                                    text = "Регистрация поддомена...",
                                    fontSize = 12.5.sp,
                                    color = TextWhite
                                )
                            }
                        } else {
                            Text(
                                text = stringResource(R.string.cf_subdomain_btn_save),
                                color = CloudflareOrange,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.5.sp
                            )
                        }
                    }
                }

                // Cancel Button
                TextButton(
                    onClick = { if (!isSaving) onDismiss() },
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
}
