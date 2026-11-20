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
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.core.AppAttributionConfidence
import com.mirrly.tgproxy.core.ConflictingAppInfo
import com.mirrly.tgproxy.core.ConflictTrigger
import com.mirrly.tgproxy.core.OccupiedPortInfo
import com.mirrly.tgproxy.core.PortConflictReport
import com.mirrly.tgproxy.core.PortListenerProtocol
import com.mirrly.tgproxy.ui.icon.MirrlyIcon
import com.mirrly.tgproxy.ui.theme.AmoledBackground
import com.mirrly.tgproxy.ui.theme.AmoledBorder
import com.mirrly.tgproxy.ui.theme.LocalProtocolColors
import com.mirrly.tgproxy.ui.theme.TextMuted
import com.mirrly.tgproxy.ui.theme.TextWhite
import com.mirrly.tgproxy.ui.theme.fadingEdges
import com.mirrly.tgproxy.ui.theme.springPress

private val ConflictCarmine = Color(0xFFEF4444)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortConflictSheet(
    report: PortConflictReport,
    isScanning: Boolean,
    isResolved: Boolean,
    onRecheck: () -> Unit,
    onAutoSwitchPorts: () -> Unit,
    onStartProxy: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val protoColors = LocalProtocolColors.current
    val app = MirrlyApplication.instance

    var hasLeftForeground by remember { mutableStateOf(false) }
    var showAllCandidates by remember { mutableStateOf(false) }

    // Auto-recheck ports when returning from another proxy app or Android App Info screen
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> hasLeftForeground = true
                Lifecycle.Event.ON_RESUME -> {
                    if (hasLeftForeground) {
                        hasLeftForeground = false
                        onRecheck()
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val statusAccent by animateColorAsState(
        targetValue = if (isResolved) protoColors.primary else ConflictCarmine,
        label = "portConflictStatusAccent"
    )

    val titleText = when {
        isResolved -> stringResource(R.string.port_conflict_title_resolved)
        report.trigger == ConflictTrigger.START_ERROR -> stringResource(R.string.port_conflict_title_runtime)
        else -> stringResource(R.string.port_conflict_title_startup)
    }

    val subtitleText = when {
        isResolved -> stringResource(R.string.port_conflict_subtitle_resolved)
        report.trigger == ConflictTrigger.START_ERROR -> stringResource(R.string.port_conflict_subtitle_runtime)
        else -> stringResource(R.string.port_conflict_subtitle_startup)
    }

    MirrlyModalBottomSheet(
        onDismissRequest = onDismiss,
        maxTabletWidth = 480.dp,
        containerColor = AmoledBackground.copy(alpha = 0.92f),
        scrimColor = AmoledBackground.copy(alpha = 0.60f),
        enableBackdropBlur = true,
        blurRadiusPx = 60,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fadingEdges(topFadeHeight = 14.dp, bottomFadeHeight = 20.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .border(BorderStroke(1.dp, statusAccent.copy(alpha = 0.55f)), RoundedCornerShape(12.dp))
                        .clip(RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    MirrlyIcon(
                        id = if (isResolved) R.drawable.ic_check_circle else R.drawable.ic_port_conflict,
                        contentDescription = null,
                        tint = statusAccent,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = titleText,
                        color = TextWhite,
                        fontSize = 17.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitleText,
                        color = TextMuted,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .border(BorderStroke(1.dp, AmoledBorder), CircleShape)
                        .clip(CircleShape)
                        .springPress(onClick = {
                            HapticHelper.performTapClick(context)
                            onDismiss()
                        }),
                    contentAlignment = Alignment.Center
                ) {
                    MirrlyIcon(
                        id = R.drawable.ic_close,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            if (isResolved) {
                // Resolved Summary Card
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, protoColors.primary.copy(alpha = 0.55f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        MirrlyIcon(
                            id = R.drawable.ic_check_circle,
                            contentDescription = null,
                            tint = protoColors.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = stringResource(
                                R.string.port_conflict_resolved_ports_summary,
                                app.config.bindPort,
                                app.config.socks5Port
                            ),
                            color = TextWhite,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            } else {
                // 2. Occupied Ports Section
                Text(
                    text = stringResource(R.string.port_conflict_section_ports),
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    report.occupiedPorts.forEach { portInfo ->
                        OccupiedPortCard(portInfo = portInfo)
                    }
                }

                // 3. Conflicting / Candidate Apps Section
                Text(
                    text = stringResource(R.string.port_conflict_section_apps),
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp
                )

                val guidanceText = when {
                    report.hasExactOwner -> stringResource(R.string.port_conflict_ask_disable_exact)
                    report.candidateApps.isNotEmpty() -> stringResource(R.string.port_conflict_ask_disable_candidates)
                    else -> stringResource(R.string.port_conflict_no_apps_found)
                }

                Text(
                    text = guidanceText,
                    color = TextWhite,
                    fontSize = 12.5.sp,
                    lineHeight = 17.5.sp
                )

                if (report.hasActiveSystemVpn) {
                    Text(
                        text = stringResource(R.string.port_conflict_active_vpn_note),
                        color = protoColors.primary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                if (report.candidateApps.isNotEmpty()) {
                    val primaryLimit = 4
                    val visiblePrimary = report.candidateApps.take(primaryLimit)
                    val extraApps = report.candidateApps.drop(primaryLimit)

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        visiblePrimary.forEach { appInfo ->
                            ConflictingAppCard(
                                appInfo = appInfo,
                                accentColor = protoColors.primary,
                                onOpenApp = {
                                    HapticHelper.performTapClick(context)
                                    launchPackageSafely(context, appInfo.packageName)
                                },
                                onOpenSettings = {
                                    HapticHelper.performTapClick(context)
                                    openAppDetailsSettings(context, appInfo.packageName)
                                }
                            )
                        }

                        AnimatedVisibility(
                            visible = showAllCandidates && extraApps.isNotEmpty(),
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                extraApps.forEach { appInfo ->
                                    ConflictingAppCard(
                                        appInfo = appInfo,
                                        accentColor = protoColors.primary,
                                        onOpenApp = {
                                            HapticHelper.performTapClick(context)
                                            launchPackageSafely(context, appInfo.packageName)
                                        },
                                        onOpenSettings = {
                                            HapticHelper.performTapClick(context)
                                            openAppDetailsSettings(context, appInfo.packageName)
                                        }
                                    )
                                }
                            }
                        }

                        if (extraApps.isNotEmpty()) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .springPress(onClick = {
                                        HapticHelper.performTapClick(context)
                                        showAllCandidates = !showAllCandidates
                                    }),
                                shape = RoundedCornerShape(12.dp),
                                color = Color.Transparent,
                                border = BorderStroke(1.dp, AmoledBorder)
                            ) {
                                Text(
                                    text = if (showAllCandidates) {
                                        stringResource(R.string.port_conflict_show_less_apps)
                                    } else {
                                        stringResource(R.string.port_conflict_show_more_apps, extraApps.size)
                                    },
                                    color = TextMuted,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 4. Footer Actions
            Column(
                modifier = Modifier.padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isResolved) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .springPress(onClick = {
                                HapticHelper.performTapClick(context)
                                onStartProxy()
                            }),
                        shape = RoundedCornerShape(14.dp),
                        color = protoColors.primary.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, protoColors.primary)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 13.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            MirrlyIcon(
                                id = R.drawable.ic_power,
                                contentDescription = null,
                                tint = protoColors.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.port_conflict_btn_start_proxy),
                                color = TextWhite,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                } else {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .springPress(enabled = !isScanning, onClick = {
                                HapticHelper.performTapClick(context)
                                onRecheck()
                            }),
                        shape = RoundedCornerShape(14.dp),
                        color = protoColors.primary.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, protoColors.primary)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 13.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            MirrlyIcon(
                                id = R.drawable.ic_refresh,
                                contentDescription = null,
                                tint = protoColors.primary,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isScanning) {
                                    stringResource(R.string.port_conflict_btn_checking)
                                } else {
                                    stringResource(R.string.port_conflict_btn_recheck)
                                },
                                color = TextWhite,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .springPress(enabled = !isScanning, onClick = {
                                HapticHelper.performTapClick(context)
                                onAutoSwitchPorts()
                            }),
                        shape = RoundedCornerShape(14.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, AmoledBorder)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.port_conflict_btn_auto_ports),
                                color = TextWhite,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .springPress(onClick = {
                            HapticHelper.performTapClick(context)
                            onDismiss()
                        }),
                    shape = RoundedCornerShape(14.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, AmoledBorder.copy(alpha = 0.7f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 11.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.action_close),
                            color = TextMuted,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OccupiedPortCard(portInfo: OccupiedPortInfo) {
    val listenerDesc = when {
        portInfo.exactOwnerApp != null -> {
            stringResource(R.string.port_conflict_owner_exact, portInfo.exactOwnerApp!!.appName)
        }
        portInfo.listenerProtocol == PortListenerProtocol.SOCKS5 -> {
            stringResource(R.string.port_conflict_listener_proto_socks5)
        }
        portInfo.listenerProtocol == PortListenerProtocol.HTTP_PROXY -> {
            stringResource(R.string.port_conflict_listener_proto_http)
        }
        portInfo.listenerProtocol == PortListenerProtocol.TCP_ACTIVE -> {
            stringResource(R.string.port_conflict_listener_proto_tcp, portInfo.bindHost)
        }
        else -> {
            stringResource(R.string.port_conflict_listener_proto_unknown, portInfo.bindHost)
        }
    }

    val listenerTag = when (portInfo.listenerProtocol) {
        PortListenerProtocol.SOCKS5 -> "SOCKS5"
        PortListenerProtocol.HTTP_PROXY -> "HTTP"
        PortListenerProtocol.TCP_ACTIVE -> "TCP"
        PortListenerProtocol.UNKNOWN -> "BUSY"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, ConflictCarmine.copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.port_conflict_port_line, portInfo.protocolLabel, portInfo.port),
                    color = TextWhite,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = listenerDesc,
                    color = TextMuted,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }

            Text(
                text = listenerTag,
                color = ConflictCarmine,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun ConflictingAppCard(
    appInfo: ConflictingAppInfo,
    accentColor: Color,
    onOpenApp: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val appIconBitmap = remember(appInfo.packageName) {
        loadAppIconBitmap(context, appInfo.packageName)
    }

    val reasonText = when (appInfo.confidence) {
        AppAttributionConfidence.EXACT_UID -> stringResource(R.string.port_conflict_reason_exact)
        AppAttributionConfidence.PORT_MATCH -> stringResource(
            R.string.port_conflict_reason_port_match,
            appInfo.matchedPorts.joinToString(", ")
        )
        AppAttributionConfidence.INSTALLED_CANDIDATE -> stringResource(R.string.port_conflict_reason_installed)
    }

    val borderColor = when (appInfo.confidence) {
        AppAttributionConfidence.EXACT_UID -> ConflictCarmine.copy(alpha = 0.65f)
        AppAttributionConfidence.PORT_MATCH -> accentColor.copy(alpha = 0.55f)
        AppAttributionConfidence.INSTALLED_CANDIDATE -> AmoledBorder
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (appIconBitmap != null) {
                    Image(
                        bitmap = appIconBitmap,
                        contentDescription = null,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .border(BorderStroke(1.dp, AmoledBorder), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        MirrlyIcon(
                            id = R.drawable.ic_vpn,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = appInfo.appName,
                        color = TextWhite,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = reasonText,
                        color = if (appInfo.confidence == AppAttributionConfidence.EXACT_UID) ConflictCarmine else TextMuted,
                        fontSize = 11.5.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (appInfo.isLaunchable || appInfo.packageName != "android") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (appInfo.isLaunchable) {
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .springPress(onClick = onOpenApp),
                            shape = RoundedCornerShape(10.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.65f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.port_conflict_btn_open_app),
                                    color = TextWhite,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    if (appInfo.packageName != "android") {
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .springPress(onClick = onOpenSettings),
                            shape = RoundedCornerShape(10.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, AmoledBorder)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.port_conflict_btn_app_settings),
                                    color = TextMuted,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun loadAppIconBitmap(context: Context, packageName: String): ImageBitmap? {
    if (packageName.isBlank() || packageName == "android") return null
    return try {
        val drawable = context.packageManager.getApplicationIcon(packageName)
        val width = (if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 72).coerceIn(1, 96)
        val height = (if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 72).coerceIn(1, 96)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(canvas)
        bitmap.asImageBitmap()
    } catch (_: Throwable) {
        null
    }
}

private fun launchPackageSafely(context: Context, packageName: String) {
    try {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
        } else {
            openAppDetailsSettings(context, packageName)
        }
    } catch (_: Throwable) {
        openAppDetailsSettings(context, packageName)
    }
}

private fun openAppDetailsSettings(context: Context, packageName: String) {
    try {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Throwable) {
    }
}
