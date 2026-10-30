package com.mirrly.tgproxy.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.core.WorkerProfile
import com.mirrly.tgproxy.ui.theme.AmoledBorder
import com.mirrly.tgproxy.ui.theme.ProtocolColors
import com.mirrly.tgproxy.ui.theme.TextMuted
import com.mirrly.tgproxy.ui.theme.TextWhite
import com.mirrly.tgproxy.ui.theme.springPress

/**
 * MirrlyUI Modal Bottom Sheet for instant Uplink Routing Mode switching.
 *
 * Implements strict MirrlyUI dogma:
 * - Zero background / 1.dp AmoledBorder outline-only architecture.
 * - Hardware Skia backdrop blur with zero popups (MirrlyModalBottomSheet).
 * - Kinetic tactile spring physics on all selections.
 * - Zero emoji & anti-slop technical precision.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UplinkSwitcherBottomSheet(
    isSocks5: Boolean,
    socks5UplinkMode: Int,
    useWorkerMtproto: Boolean,
    activeWorker: WorkerProfile,
    protoColors: ProtocolColors,
    onSelectSocks5Uplink: (Int) -> Unit,
    onSelectMtprotoWorker: (Boolean) -> Unit,
    onOpenWorkerManager: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val protocolName = if (isSocks5) "SOCKS5" else "MTProto"

    val isWorkerSelected = if (isSocks5) {
        socks5UplinkMode == 0
    } else {
        useWorkerMtproto
    }

    val isCdnSelected = if (isSocks5) {
        socks5UplinkMode == 1
    } else {
        !useWorkerMtproto
    }

    MirrlyModalBottomSheet(
        onDismissRequest = onDismiss,
        maxTabletWidth = 460.dp
    ) {
        MirrlyBottomSheetHeader(
            title = stringResource(R.string.home_uplink_sheet_title),
            subtitle = stringResource(R.string.home_uplink_sheet_desc, protocolName),
            badgeText = protocolName,
            badgeColor = protoColors.primary,
            onClose = onDismiss
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Option 1: Cloudflare Worker
            val workerBorderColor by animateColorAsState(
                targetValue = if (isWorkerSelected) protoColors.primary else AmoledBorder,
                animationSpec = tween(220),
                label = "workerBorder"
            )
            val workerBgColor by animateColorAsState(
                targetValue = if (isWorkerSelected) protoColors.primary.copy(alpha = 0.08f) else Color.Transparent,
                animationSpec = tween(220),
                label = "workerBg"
            )

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = workerBgColor,
                border = BorderStroke(1.dp, workerBorderColor),
                modifier = Modifier
                    .fillMaxWidth()
                    .springPress(onClick = {
                        HapticHelper.performSoftTick(context)
                        if (isSocks5) {
                            onSelectSocks5Uplink(0)
                        } else {
                            onSelectMtprotoWorker(true)
                        }
                    })
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.settings_socks5_uplink_worker_title),
                                fontSize = 13.sp,
                                fontWeight = if (isWorkerSelected) FontWeight.Bold else FontWeight.SemiBold,
                                color = if (isWorkerSelected) TextWhite else TextWhite.copy(alpha = 0.85f)
                            )
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = protoColors.primary.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, protoColors.primary.copy(alpha = 0.35f))
                            ) {
                                Text(
                                    text = "WSS",
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = protoColors.light,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Text(
                            text = if (activeWorker.domain.isNotBlank()) {
                                activeWorker.domain
                            } else {
                                stringResource(R.string.settings_socks5_uplink_worker_subtitle)
                            },
                            fontSize = 11.sp,
                            color = if (isWorkerSelected) protoColors.light.copy(alpha = 0.85f) else TextMuted,
                            fontFamily = if (activeWorker.domain.isNotBlank()) FontFamily.Monospace else FontFamily.Default,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Radio indicator
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .border(
                                width = 1.5.dp,
                                color = if (isWorkerSelected) protoColors.primary else AmoledBorder,
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isWorkerSelected) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(protoColors.primary, CircleShape)
                            )
                        }
                    }
                }
            }

            // Option 2: Anycast CDN Flowseal
            val cdnBorderColor by animateColorAsState(
                targetValue = if (isCdnSelected) protoColors.primary else AmoledBorder,
                animationSpec = tween(220),
                label = "cdnBorder"
            )
            val cdnBgColor by animateColorAsState(
                targetValue = if (isCdnSelected) protoColors.primary.copy(alpha = 0.08f) else Color.Transparent,
                animationSpec = tween(220),
                label = "cdnBg"
            )

            val cdnTitle = if (isSocks5) {
                stringResource(R.string.settings_socks5_uplink_flowseal_title)
            } else {
                "Anycast CDN Flowseal"
            }

            val cdnSubtitle = if (isSocks5) {
                stringResource(R.string.settings_socks5_uplink_flowseal_subtitle)
            } else {
                stringResource(R.string.settings_mtproto_worker_cdn)
            }

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = cdnBgColor,
                border = BorderStroke(1.dp, cdnBorderColor),
                modifier = Modifier
                    .fillMaxWidth()
                    .springPress(onClick = {
                        HapticHelper.performSoftTick(context)
                        if (isSocks5) {
                            onSelectSocks5Uplink(1)
                        } else {
                            onSelectMtprotoWorker(false)
                        }
                    })
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = cdnTitle,
                                fontSize = 13.sp,
                                fontWeight = if (isCdnSelected) FontWeight.Bold else FontWeight.SemiBold,
                                color = if (isCdnSelected) TextWhite else TextWhite.copy(alpha = 0.85f)
                            )
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = protoColors.primary.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, protoColors.primary.copy(alpha = 0.35f))
                            ) {
                                Text(
                                    text = "CDN",
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = protoColors.light,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Text(
                            text = cdnSubtitle,
                            fontSize = 11.sp,
                            color = if (isCdnSelected) protoColors.light.copy(alpha = 0.85f) else TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Radio indicator
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .border(
                                width = 1.5.dp,
                                color = if (isCdnSelected) protoColors.primary else AmoledBorder,
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isCdnSelected) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(protoColors.primary, CircleShape)
                            )
                        }
                    }
                }
            }

            // Quick Worker Management link if worker has no domain
            if (isWorkerSelected && activeWorker.domain.isBlank()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, protoColors.primary.copy(alpha = 0.40f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .springPress(onClick = {
                            onDismiss()
                            onOpenWorkerManager()
                        })
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = stringResource(R.string.wm_title),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = protoColors.light
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Terminology Explanation card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, AmoledBorder, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.home_uplink_terms_title),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite
                    )
                    Text(
                        text = stringResource(R.string.home_uplink_terms_body),
                        fontSize = 10.5.sp,
                        color = TextMuted,
                        lineHeight = 14.5.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}
