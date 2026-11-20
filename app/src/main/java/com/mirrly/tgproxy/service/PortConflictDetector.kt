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

package com.mirrly.tgproxy.service

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.core.AppAttributionConfidence
import com.mirrly.tgproxy.core.AppLogger
import com.mirrly.tgproxy.core.ConflictingAppInfo
import com.mirrly.tgproxy.core.ConflictTrigger
import com.mirrly.tgproxy.core.KnownAppCategory
import com.mirrly.tgproxy.core.OccupiedPortInfo
import com.mirrly.tgproxy.core.PortCheckTarget
import com.mirrly.tgproxy.core.PortConflictCore
import com.mirrly.tgproxy.core.PortConflictReport
import com.mirrly.tgproxy.core.PortListenerProtocol
import com.mirrly.tgproxy.core.ProxyConfig
import com.mirrly.tgproxy.core.ProxyMode
import com.mirrly.tgproxy.service.cloudflare.CloudflareOAuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PortConflictDetector {

    private const val TAG = "PortConflictDetector"

    suspend fun scan(
        context: Context,
        config: ProxyConfig,
        runningMode: ProxyMode?,
        trigger: ConflictTrigger,
        failedMode: ProxyMode? = null,
        failedPort: Int? = null,
        nativeErrorDetail: String = ""
    ): PortConflictReport = withContext(Dispatchers.IO) {
        val excludedSelfPorts = if (CloudflareOAuthManager.isServerListening()) {
            setOf(CloudflareOAuthManager.CALLBACK_PORT)
        } else {
            emptySet()
        }

        val targets = PortConflictCore.buildPortCheckTargets(
            config = config,
            runningMode = runningMode,
            failedMode = failedMode,
            failedPort = failedPort,
            excludedSelfPorts = excludedSelfPorts
        )

        val (procReadable, procListeners) = PortConflictCore.readProcNetTcpSnapshot()
        val myUid = Process.myUid()
        val hasActiveVpn = hasActiveSystemVpn(context)

        val occupiedPorts = mutableListOf<OccupiedPortInfo>()
        for (target in targets) {
            val probe = PortConflictCore.probePortOccupancy(
                host = target.bindHost,
                port = target.port,
                probeProtocol = true
            )

            val uidsOnPort = procListeners[target.port].orEmpty()
            val foreignUids = uidsOnPort.filter { it != myUid }
            val hasForeignProcListener = foreignUids.isNotEmpty()
            val isForcedFailedPort = trigger == ConflictTrigger.START_ERROR && failedPort == target.port

            if (!probe.isOccupied && !hasForeignProcListener && !isForcedFailedPort) {
                continue
            }

            val ownerUid = foreignUids.firstOrNull() ?: uidsOnPort.firstOrNull()
            val exactOwner = if (ownerUid != null && ownerUid != myUid) {
                resolveUidToAppInfo(context, ownerUid, target.port)
            } else {
                null
            }

            val detail = when {
                isForcedFailedPort && nativeErrorDetail.isNotBlank() -> nativeErrorDetail
                probe.bindErrorDetail.isNotBlank() -> probe.bindErrorDetail
                else -> ""
            }

            occupiedPorts += OccupiedPortInfo(
                port = target.port,
                bindHost = target.bindHost,
                protocolLabel = target.protocolLabel,
                listenerProtocol = probe.listenerProtocol,
                isActiveModePort = target.isActiveModePort,
                ownerUid = ownerUid,
                exactOwnerApp = exactOwner,
                diagnosticDetail = detail
            )
        }

        val candidateApps = if (occupiedPorts.isNotEmpty()) {
            buildCandidateApps(
                context = context,
                occupiedPorts = occupiedPorts,
                procReadable = procReadable,
                procListeners = procListeners,
                hasActiveVpn = hasActiveVpn,
                myUid = myUid
            )
        } else {
            emptyList()
        }

        if (occupiedPorts.isNotEmpty()) {
            AppLogger.w(
                TAG,
                "Обнаружен конфликт портов (trigger=$trigger, ports=${occupiedPorts.map { it.port }}, " +
                    "procReadable=$procReadable, vpn=$hasActiveVpn, candidates=${candidateApps.map { it.packageName }})"
            )
        }

        PortConflictReport(
            trigger = trigger,
            occupiedPorts = occupiedPorts,
            candidateApps = candidateApps,
            procNetReadable = procReadable,
            hasActiveSystemVpn = hasActiveVpn,
            failedMode = failedMode,
            failedPort = failedPort,
            nativeErrorDetail = nativeErrorDetail
        )
    }

    fun findFreePortForTarget(
        config: ProxyConfig,
        occupiedPort: Int
    ): Int? {
        val bindHost = config.bindHost.ifBlank { "127.0.0.1" }
        val excluded = buildSet {
            add(config.bindPort)
            add(config.socks5Port)
            add(CloudflareOAuthManager.CALLBACK_PORT)
            add(occupiedPort)
        }
        return PortConflictCore.findNearestFreePort(
            host = bindHost,
            preferredPort = occupiedPort,
            excludedPorts = excluded
        )
    }

    private fun buildCandidateApps(
        context: Context,
        occupiedPorts: List<OccupiedPortInfo>,
        procReadable: Boolean,
        procListeners: Map<Int, Set<Int>>,
        hasActiveVpn: Boolean,
        myUid: Int
    ): List<ConflictingAppInfo> {
        val candidatesByPkg = linkedMapOf<String, ConflictingAppInfo>()

        // 1. Exact UID owners directly from /proc/net/tcp{,6} when kernel allows reading
        for (portInfo in occupiedPorts) {
            val exact = portInfo.exactOwnerApp ?: continue
            val existing = candidatesByPkg[exact.packageName]
            if (existing == null) {
                candidatesByPkg[exact.packageName] = exact
            } else {
                candidatesByPkg[exact.packageName] = existing.copy(
                    matchedPorts = (existing.matchedPorts + exact.matchedPorts).distinct()
                )
            }
        }

        // Also check companion ports in /proc/net/tcp if readable (e.g. an app using both 10808 and 10888)
        if (procReadable) {
            for ((port, uids) in procListeners) {
                if (port !in PortConflictCore.COMPANION_PROXY_PORTS) continue
                for (uid in uids) {
                    if (uid == myUid) continue
                    val info = resolveUidToAppInfo(context, uid, port) ?: continue
                    val existing = candidatesByPkg[info.packageName]
                    if (existing == null && occupiedPorts.any { it.exactOwnerApp == null }) {
                        candidatesByPkg[info.packageName] = info.copy(
                            confidence = AppAttributionConfidence.PORT_MATCH
                        )
                    }
                }
            }
        }

        // 2. When Android SELinux hides /proc/net/tcp or some ports lack exact UID, discover installed proxy/VPN apps
        val needsFallbackAttribution = occupiedPorts.any { it.exactOwnerApp == null }
        if (needsFallbackAttribution) {
            val occupiedPortSet = occupiedPorts.map { it.port }.toMutableSet()
            // Check active loopback companion proxy ports to sharpen candidate ranking (e.g., 10809 alongside 10808)
            for (companionPort in PortConflictCore.COMPANION_PROXY_PORTS) {
                if (companionPort in occupiedPortSet) continue
                if (PortConflictCore.isLoopbackListenerAccepting("127.0.0.1", companionPort, timeoutMs = 55)) {
                    occupiedPortSet.add(companionPort)
                }
            }

            val installedApps = discoverInstalledProxyApps(context)
            for (discovered in installedApps) {
                if (candidatesByPkg.containsKey(discovered.packageName)) continue
                val matched = discovered.typicalPorts.filter { it in occupiedPortSet }.sorted()
                val confidence = if (matched.isNotEmpty()) {
                    AppAttributionConfidence.PORT_MATCH
                } else {
                    AppAttributionConfidence.INSTALLED_CANDIDATE
                }
                candidatesByPkg[discovered.packageName] = ConflictingAppInfo(
                    packageName = discovered.packageName,
                    appName = discovered.appName,
                    confidence = confidence,
                    matchedPorts = matched,
                    category = discovered.category,
                    isLaunchable = discovered.isLaunchable
                )
            }
        }

        return candidatesByPkg.values.sortedWith(
            compareBy<ConflictingAppInfo>(
                {
                    when (it.confidence) {
                        AppAttributionConfidence.EXACT_UID -> 0
                        AppAttributionConfidence.PORT_MATCH -> 1
                        AppAttributionConfidence.INSTALLED_CANDIDATE -> 2
                    }
                },
                { -it.matchedPorts.size },
                {
                    if (hasActiveVpn && it.category == KnownAppCategory.VPN) 0
                    else if (it.category == KnownAppCategory.PROXY) 1
                    else 2
                },
                { it.appName.lowercase() }
            )
        )
    }

    private data class DiscoveredProxyApp(
        val packageName: String,
        val appName: String,
        val typicalPorts: Set<Int>,
        val category: KnownAppCategory,
        val isLaunchable: Boolean
    )

    private fun discoverInstalledProxyApps(context: Context): List<DiscoveredProxyApp> {
        val pm = context.packageManager
        val selfPkg = context.packageName
        val results = linkedMapOf<String, DiscoveredProxyApp>()

        // A. Direct lookup of curated proxy/VPN catalog packages
        for (spec in PortConflictCore.KNOWN_PROXY_APPS) {
            if (spec.packageName.equals(selfPkg, ignoreCase = true)) continue
            val appInfo = getApplicationInfoCompat(pm, spec.packageName) ?: continue
            val label = runCatching { pm.getApplicationLabel(appInfo).toString().trim() }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?: spec.fallbackDisplayName
            val launchable = pm.getLaunchIntentForPackage(spec.packageName) != null
            results[spec.packageName] = DiscoveredProxyApp(
                packageName = spec.packageName,
                appName = label,
                typicalPorts = spec.typicalPorts,
                category = spec.category,
                isLaunchable = launchable
            )
        }

        // B. Scan installed launcher activities via <queries> MAIN/LAUNCHER intent for other proxy tools
        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(mainIntent, 0)
            }
        } catch (_: Throwable) {
            emptyList()
        }

        for (ri in resolved) {
            val pkg = ri.activityInfo?.packageName ?: continue
            if (results.containsKey(pkg)) continue
            val label = runCatching { ri.loadLabel(pm).toString().trim() }.getOrNull().orEmpty()
            if (PortConflictCore.isLikelyProxyPackageOrLabel(pkg, label, selfPkg)) {
                results[pkg] = DiscoveredProxyApp(
                    packageName = pkg,
                    appName = label.ifEmpty { pkg },
                    typicalPorts = emptySet(),
                    category = KnownAppCategory.PROXY,
                    isLaunchable = true
                )
            }
        }

        return results.values.toList()
    }

    private fun resolveUidToAppInfo(context: Context, uid: Int, port: Int): ConflictingAppInfo? {
        if (uid == 0) {
            return ConflictingAppInfo(
                packageName = "android",
                appName = context.getString(R.string.port_conflict_system_owner),
                confidence = AppAttributionConfidence.EXACT_UID,
                matchedPorts = listOf(port),
                category = KnownAppCategory.LOCAL_SERVER,
                isLaunchable = false,
                uid = 0
            )
        }
        val pm = context.packageManager
        val packages = runCatching { pm.getPackagesForUid(uid) }.getOrNull() ?: return null
        val primaryPkg = packages.firstOrNull { !it.equals(context.packageName, ignoreCase = true) }
            ?: packages.firstOrNull()
            ?: return null

        val appInfo = getApplicationInfoCompat(pm, primaryPkg)
        val label = if (appInfo != null) {
            runCatching { pm.getApplicationLabel(appInfo).toString().trim() }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?: primaryPkg
        } else {
            primaryPkg
        }
        val launchable = pm.getLaunchIntentForPackage(primaryPkg) != null

        return ConflictingAppInfo(
            packageName = primaryPkg,
            appName = label,
            confidence = AppAttributionConfidence.EXACT_UID,
            matchedPorts = listOf(port),
            category = KnownAppCategory.PROXY,
            isLaunchable = launchable,
            uid = uid
        )
    }

    private fun getApplicationInfoCompat(pm: PackageManager, packageName: String): ApplicationInfo? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun hasActiveSystemVpn(context: Context): Boolean {
        return try {
            if (MirrlyVpnService.isRunning) return false
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val active = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(active) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } catch (_: Throwable) {
            false
        }
    }
}
