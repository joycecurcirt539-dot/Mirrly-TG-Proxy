/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * GNU GPL v3+ <https://www.gnu.org/licenses/>
 */

package com.mirrly.tgproxy.service

import android.content.Context
import com.mirrly.tgproxy.core.AppLogger
import com.mirrly.tgproxy.core.BuiltinVpnServers
import com.mirrly.tgproxy.core.BuiltinVpnServerItem
import com.mirrly.tgproxy.core.NativeProxy
import com.mirrly.tgproxy.core.WarpImportResult
import com.mirrly.tgproxy.core.WarpProfile
import com.mirrly.tgproxy.core.WarpProfileParser
import com.mirrly.tgproxy.core.WarpKeystoreEncryption
import com.mirrly.tgproxy.core.ImportedProfileType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Сохраненный в локальном менеджере профиль WARP / WireGuard / AmneziaWG.
 */
/**
 * Источник происхождения профиля в менеджере.
 * Профили из конфигуратора и импортированные имеют наивысший приоритет (isPriority = true)
 * при автоподборе системой.
 */
enum class WarpProfileOrigin {
    CONFIGURATOR, // Создан в конфигураторе/генераторе
    IMPORTED,     // Импортирован пользователем
    BUILTIN       // Встроенный эталонный сервер
}

data class WarpSavedProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val format: ImportedProfileType,
    val endpoint: String,
    val dnsLine: String,
    val content: String,
    val isWarpSafe: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false,
    val warnings: List<String> = emptyList(),
    val origin: WarpProfileOrigin = WarpProfileOrigin.IMPORTED
) {
    /**
     * Главные профили: созданные через конфигуратор либо импортированные.
     * Стандартные профили исключены из приложения.
     */
    val isPriority: Boolean get() = true

    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("format", format.name)
        put("endpoint", endpoint)
        put("dnsLine", dnsLine)
        put("content", WarpKeystoreEncryption.encrypt(content))
        put("isWarpSafe", isWarpSafe)
        put("createdAt", createdAt)
        put("isFavorite", isFavorite)
        put("origin", origin.name)
        val wArr = JSONArray()
        warnings.forEach { wArr.put(it) }
        put("warnings", wArr)
    }

    companion object {
        fun fromJsonObject(json: JSONObject): WarpSavedProfile? = try {
            val formatStr = json.optString("format", ImportedProfileType.AMNEZIA_WG_CONF.name)
            val format = when (formatStr) {
                "CLASH_YAML", "CLASH_META", "CLASH_META_YAML" -> ImportedProfileType.CLASH_META_YAML
                "WIREGUARD", "WIREGUARD_CONF", "WIRESOCK" -> ImportedProfileType.WIREGUARD_CONF
                "AWG_UNIVERSAL", "AMNEZIA_WG", "AMNEZIA_WG_CONF", "AWG_1_0", "AWG_2_0", "AWG_3_0", "AWG_3_1" -> ImportedProfileType.AMNEZIA_WG_CONF
                "CLOUDFLARE_JSON" -> ImportedProfileType.CLOUDFLARE_JSON
                else -> try {
                    ImportedProfileType.valueOf(formatStr)
                } catch (_: Exception) {
                    ImportedProfileType.AMNEZIA_WG_CONF
                }
            }
            val originStr = json.optString("origin", WarpProfileOrigin.CONFIGURATOR.name)
            val origin = try {
                WarpProfileOrigin.valueOf(originStr)
            } catch (_: Exception) {
                WarpProfileOrigin.CONFIGURATOR
            }
            val wList = mutableListOf<String>()
            val wArr = json.optJSONArray("warnings")
            if (wArr != null) {
                for (i in 0 until wArr.length()) {
                    wList.add(wArr.getString(i))
                }
            }
            val rawContent = json.getString("content")
            val decryptedContent = WarpKeystoreEncryption.decrypt(rawContent)
            WarpSavedProfile(
                id = json.getString("id"),
                name = json.getString("name"),
                format = format,
                endpoint = json.optString("endpoint", ""),
                dnsLine = json.optString("dnsLine", ""),
                content = decryptedContent,
                isWarpSafe = json.optBoolean("isWarpSafe", true),
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                isFavorite = json.optBoolean("isFavorite", false),
                warnings = wList,
                origin = origin
            )
        } catch (e: Exception) {
            null
        }
    }
}

fun BuiltinVpnServerItem.toWarpSavedProfile(): WarpSavedProfile {
    val app = com.mirrly.tgproxy.MirrlyApplication.instance
    val effectiveDns = app.config.getEffectiveVpnDnsIpv4().joinToString(", ")
    return WarpSavedProfile(
        id = this.id,
        name = this.name,
        format = ImportedProfileType.AMNEZIA_WG_CONF,
        endpoint = this.endpoint,
        dnsLine = effectiveDns.ifBlank { "1.1.1.1, 1.0.0.1" },
        content = this.rawConfig,
        isWarpSafe = true,
        createdAt = 0L,
        isFavorite = false,
        origin = WarpProfileOrigin.BUILTIN
    )
}

/**
 * Менеджер профилей WARP: хранение, пакетный импорт, экспорт в ZIP/файлы,
 * выбор активного профиля и синхронизация с сетевым движком.
 */
class WarpProfileManager private constructor(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _profilesFlow = MutableStateFlow<List<WarpSavedProfile>>(loadProfiles())
    val profilesFlow: StateFlow<List<WarpSavedProfile>> = _profilesFlow.asStateFlow()

    private val _activeProfileIdFlow = MutableStateFlow<String?>(getActiveProfileId())
    val activeProfileIdFlow: StateFlow<String?> = _activeProfileIdFlow.asStateFlow()

    fun getAllProfiles(): List<WarpSavedProfile> = _profilesFlow.value

    fun hasUserProfiles(): Boolean = _profilesFlow.value.any { it.origin == WarpProfileOrigin.IMPORTED || (it.isPriority && it.name != "Auto WARP" && it.name != "Auto WARP (Clash)") }

    fun getActiveProfileId(): String? = prefs.getString(KEY_ACTIVE_PROFILE_ID, null)

    fun getActiveProfile(): WarpSavedProfile? {
        val profiles = _profilesFlow.value
        if (profiles.isEmpty()) return null
        val priorityProfiles = profiles.filter { it.isPriority }
        val pool = if (priorityProfiles.isNotEmpty()) priorityProfiles else profiles
        val activeId = getActiveProfileId()
        val found = if (activeId != null) pool.find { it.id == activeId } else null
        val resolved = found ?: pool.firstOrNull()
        if (resolved != null && activeId != resolved.id && priorityProfiles.isNotEmpty()) {
            prefs.edit().putString(KEY_ACTIVE_PROFILE_ID, resolved.id).apply()
            _activeProfileIdFlow.value = resolved.id
        }
        return resolved
    }

    /**
     * Интеллектуальный автоматический подбор оптимального профиля.
     * Главные профили (isPriority = true: созданные через конфигуратор либо импортированные)
     * имеют безусловный приоритет над стандартными.
     * Если у пользователя есть хотя бы один созданный или импортированный профиль,
     * стандартный профиль и профиль старого регистратора КАТЕГОРИЧЕСКИ НЕ ИСПОЛЬЗУЮТСЯ.
     */
    fun resolveAutoProfile(): WarpSavedProfile? {
        val profiles = _profilesFlow.value
        if (profiles.isEmpty()) return null

        // 1. Фильтруем главные профили (Конфигуратор и Импорт).
        // Если созданы профили пользователем, стандартный профиль исключается полностью.
        val priorityProfiles = profiles.filter { it.isPriority }
        val candidatePool = if (priorityProfiles.isNotEmpty()) priorityProfiles else profiles

        if (candidatePool.size == 1) return candidatePool.first()

        // 2. Внутри пула отдаем предпочтение избранным
        val favoritePool = candidatePool.filter { it.isFavorite }
        val targetPool = if (favoritePool.isNotEmpty()) favoritePool else candidatePool

        // 3. Если текущий активный профиль пользователя уже в целевом пуле, сохраняем стабильность соединения
        val activeId = getActiveProfileId()
        val currentActiveInPool = targetPool.find { it.id == activeId }
        if (currentActiveInPool != null) {
            return currentActiveInPool
        }

        // 4. Иначе возвращаем первый подходящий профиль наивысшего ранга
        return targetPool.firstOrNull() ?: candidatePool.first()
    }

    private val _serverPings = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun recordServerPing(serverId: String, rttMs: Long) {
        if (rttMs > 0L) {
            _serverPings[serverId] = rttMs
        }
    }

    fun getServerPing(serverId: String): Long? = _serverPings[serverId]

    fun getAllServerPings(): Map<String, Long> = _serverPings.toMap()

    fun resolveBestBuiltinServer(): BuiltinVpnServerItem {
        val app = com.mirrly.tgproxy.MirrlyApplication.instance
        val strategy = app.prefsManager.getVpnAutoSelectStrategy()
        val disabledIds = app.prefsManager.getVpnAutoSelectDisabledServers()
        val categoryOrder = app.prefsManager.getVpnAutoSelectCategoryOrder()

        val allServers = BuiltinVpnServers.ALL
        // Отсеиваем отключенные пользователем серверы из пула автовыбора
        val enabledServers = allServers.filter { it.id !in disabledIds }
        val pool = if (enabledServers.isNotEmpty()) enabledServers else allServers

        // Серверы с успешно измеренным пингом
        val measured = pool.filter {
            val ping = _serverPings[it.id]
            ping != null && ping > 0L
        }

        // Если замеры завершены, выбираем из измеренных; иначе из всего разрешенного пула
        val candidates = if (measured.isNotEmpty()) measured else pool

        fun bestByPing(list: List<BuiltinVpnServerItem>): BuiltinVpnServerItem? {
            if (list.isEmpty()) return null
            return list.minByOrNull { _serverPings[it.id] ?: Long.MAX_VALUE } ?: list.first()
        }

        val chosen: BuiltinVpnServerItem? = when (strategy) {
            VpnAutoSelectStrategy.TEL_FIRST -> {
                val telServers = candidates.filter { it.isTel }
                bestByPing(telServers) ?: bestByPing(candidates)
            }
            VpnAutoSelectStrategy.LOWEST_PING -> {
                bestByPing(candidates)
            }
            VpnAutoSelectStrategy.ANYCAST_FIRST -> {
                val anycastServers = candidates.filter { it.isAnycast }
                bestByPing(anycastServers) ?: bestByPing(candidates)
            }
            VpnAutoSelectStrategy.CUSTOM_ORDER -> {
                var found: BuiltinVpnServerItem? = null
                for (cat in categoryOrder) {
                    val matching = when (cat) {
                        "TEL" -> candidates.filter { it.isTel }
                        "ANYCAST" -> candidates.filter { it.isAnycast }
                        "DEDICATED" -> candidates.filter { !it.isTel && !it.isAnycast }
                        else -> emptyList()
                    }
                    val best = bestByPing(matching)
                    if (best != null) {
                        found = best
                        break
                    }
                }
                found ?: bestByPing(candidates)
            }
        }

        return chosen ?: BuiltinVpnServers.SERVER_BUILTIN_TEL_FI
    }

    fun selectAutoServer() {
        val app = com.mirrly.tgproxy.MirrlyApplication.instance
        app.prefsManager.setSelectedBuiltinServerId(BuiltinVpnServers.ID_AUTO)
        app.prefsManager.setVpnUseCustomProfile(false)
        val autoProfile = getActiveBuiltinServer().toWarpSavedProfile()
        prefs.edit().putString(KEY_ACTIVE_PROFILE_ID, autoProfile.id).apply()
        _activeProfileIdFlow.value = autoProfile.id
        if (MirrlyVpnService.isRunning) {
            MirrlyVpnService.restart(context)
        }
    }

    fun getBuiltinServers(): List<BuiltinVpnServerItem> = BuiltinVpnServers.ALL

    fun getActiveBuiltinServer(): BuiltinVpnServerItem {
        val app = com.mirrly.tgproxy.MirrlyApplication.instance
        val selectedId = app.prefsManager.getSelectedBuiltinServerId()
        if (selectedId == BuiltinVpnServers.ID_AUTO) {
            return resolveBestBuiltinServer()
        }
        return BuiltinVpnServers.getById(selectedId)
    }

    fun getCustomProfiles(): List<WarpSavedProfile> = _profilesFlow.value.filter { it.origin != WarpProfileOrigin.BUILTIN }

    fun getActiveCustomProfile(): WarpSavedProfile? {
        val custom = getCustomProfiles()
        val activeId = getActiveProfileId()
        return custom.find { it.id == activeId } ?: custom.firstOrNull()
    }

    fun selectBuiltinServer(server: BuiltinVpnServerItem) {
        val app = com.mirrly.tgproxy.MirrlyApplication.instance
        app.prefsManager.setSelectedBuiltinServerId(server.id)
        app.prefsManager.setVpnUseCustomProfile(false)
        val builtinProfile = server.toWarpSavedProfile()
        prefs.edit().putString(KEY_ACTIVE_PROFILE_ID, builtinProfile.id).apply()
        _activeProfileIdFlow.value = builtinProfile.id
        if (MirrlyVpnService.isRunning) {
            MirrlyVpnService.restart(context)
        }
    }

    fun selectCustomProfile(profile: WarpSavedProfile) {
        val app = com.mirrly.tgproxy.MirrlyApplication.instance
        app.prefsManager.setVpnUseCustomProfile(true)
        setActiveProfile(profile.id)
    }

    /**
     * Возвращает эффективный активный профиль с учетом режима (Простой vs Продвинутый)
     * и разделения (Серверы vs Свои профили).
     */
    fun getEffectiveActiveProfile(): WarpSavedProfile? {
        val app = com.mirrly.tgproxy.MirrlyApplication.instance
        val customProfiles = getCustomProfiles()
        val useCustom = app.prefsManager.isVpnUseCustomProfile()

        // Если пользователь выбрал встроенный сервер (useCustom == false) или список своих профилей пуст
        if (!useCustom || customProfiles.isEmpty()) {
            val builtin = getActiveBuiltinServer()
            return builtin.toWarpSavedProfile()
        }

        // Если пользователь явно переключился на "Свои профили" (useCustom == true)
        val activeId = getActiveProfileId()
        val foundCustom = customProfiles.find { it.id == activeId } ?: customProfiles.firstOrNull()
        return foundCustom ?: getActiveBuiltinServer().toWarpSavedProfile()
    }

    /**
     * Гарантирует наличие хотя бы одного активного профиля WARP (Cloudflare Model).
     * Если база профилей пуста (первый запуск, новое устройство или очистка данных),
     * автоматически инициализирует базовый профиль AmneziaWG на чистом порту (8095)
     * с сохранением в активные, предотвращая отказ запуска VPN на других устройствах.
     */
    fun ensureDefaultProfile(): WarpSavedProfile {
        return getEffectiveActiveProfile() ?: getActiveBuiltinServer().toWarpSavedProfile()
    }

    /**
     * Сохраняет импортированный пользователем профиль со статусом ORIGIN = IMPORTED (приоритетный/главный).
     */
    fun saveImportedProfile(
        name: String,
        content: String,
        warpProfile: WarpProfile,
        type: ImportedProfileType = ImportedProfileType.AMNEZIA_WG_CONF,
        makeActive: Boolean = false
    ): WarpSavedProfile {
        val format = when (type) {
            ImportedProfileType.CLASH_META_YAML -> ImportedProfileType.CLASH_META_YAML
            ImportedProfileType.WIREGUARD_CONF -> ImportedProfileType.WIREGUARD_CONF
            ImportedProfileType.CLOUDFLARE_JSON -> ImportedProfileType.CLOUDFLARE_JSON
            ImportedProfileType.AMNEZIA_WG_CONF -> ImportedProfileType.AMNEZIA_WG_CONF
            else -> ImportedProfileType.AMNEZIA_WG_CONF
        }
        val endpoint = warpProfile.effectivePeerEndpoint
        val dnsLine = warpProfile.dnsServers.ifBlank { "1.1.1.1, 1.0.0.1" }
        val confContent = if (content.isNotBlank()) content else warpProfile.awgRawConfig

        val profile = WarpSavedProfile(
            id = UUID.randomUUID().toString(),
            name = name.take(35),
            format = format,
            endpoint = endpoint,
            dnsLine = dnsLine,
            content = confContent,
            isWarpSafe = true,
            isFavorite = false,
            warnings = emptyList(),
            origin = WarpProfileOrigin.IMPORTED
        )
        val current = _profilesFlow.value.toMutableList()
        current.removeAll { it.id == profile.id || it.name == profile.name }
        current.add(0, profile)
        persistProfiles(current)

        if (makeActive || getActiveProfileId() == null) {
            setActiveProfile(profile.id)
        }
        return profile
    }

    /**
     * Устанавливает профиль как активный для приложения.
     * Немедленно обновляет конфигурацию в PreferencesManager и применяет в mirrlyengine.
     */
    fun setActiveProfile(profileId: String): Boolean {
        val target = _profilesFlow.value.find { it.id == profileId } ?: return false

        // Разбираем конфигурацию через WarpProfileParser
        val parsedResult = WarpProfileParser.parse(target.content)
        val warpProfile = when (parsedResult) {
            is WarpImportResult.Success -> parsedResult.profile
            else -> {
                AppLogger.w(TAG, "Не удалось разобрать профиль $profileId для активации")
                return false
            }
        }

        // Обновляем в PreferencesManager и ProxyConfig (чтобы VpnTunManager и другие службы подхватили)
        val app = com.mirrly.tgproxy.MirrlyApplication.instance
        val currentWarp = app.prefsManager.getWarpProfile()
        val isRegisteredWarp = if (target.name == "Auto WARP") {
            currentWarp != null && currentWarp.isRegistered && currentWarp.isUsable() && !currentWarp.isBootstrap()
        } else {
            warpProfile.isRegistered
        }

        val effectiveWarpProfile = if (target.name == "Auto WARP" && !isRegisteredWarp) {
            warpProfile.copy(
                accountId = currentWarp?.accountId ?: "",
                token = currentWarp?.token ?: "",
                clientId = currentWarp?.clientId ?: "",
                isRegistered = false,
                isActivated = false,
                isWarpEnabled = false,
                credentialsValid = false,
                dataPlaneReady = false,
                isWgValid = false
            )
        } else {
            warpProfile
        }

        app.prefsManager.saveWarpProfile(effectiveWarpProfile)
        app.config.applyWarpProfile(effectiveWarpProfile)
        app.config.isWarpAccountActive = isRegisteredWarp

        val isYamlOrJson = target.format == ImportedProfileType.CLASH_META_YAML || target.format.extension == "yaml" || target.format.extension == "json"
        app.config.vpnUplinkMode = com.mirrly.tgproxy.core.UplinkMode.AWG
        app.prefsManager.setVpnUplinkMode(com.mirrly.tgproxy.core.UplinkMode.AWG)
        if (target.endpoint.isNotBlank()) {
            app.config.warpPeerEndpoint = target.endpoint
            app.config.warpMeasuredWgEndpoint = target.endpoint
        }
        if (target.format.extension == "conf") {
            app.config.awgCustomIni = target.content
            app.config.awgStrategyName = com.mirrly.tgproxy.core.AwgObfuscationStrategy.CUSTOM.name
        }
        app.prefsManager.saveConfig(app.config)

        try {
            if (target.format.extension == "conf") {
                NativeProxy.setAwgConfig(target.content)
            }
            NativeProxy.setVpnUplinkMode(com.mirrly.tgproxy.core.UplinkMode.AWG)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Ошибка применения конфигурации в NativeProxy: ${e.message}")
        }

        if (target.origin == WarpProfileOrigin.BUILTIN) {
            app.prefsManager.setVpnUseCustomProfile(false)
        } else {
            app.prefsManager.setVpnUseCustomProfile(true)
        }

        prefs.edit().putString(KEY_ACTIVE_PROFILE_ID, profileId).apply()
        _activeProfileIdFlow.value = profileId
        AppLogger.i(TAG, "Профиль '${target.name}' (${target.format.title}) установлен как активный")

        // Если VPN активен, мягко перезапускаем его для немедленного перехода на выбранный профиль
        if (MirrlyVpnService.isRunning) {
            MirrlyVpnService.restart(context)
        }

        return true
    }

    /**
     * Сохраняет выигравший в результате сканирования чистый Anycast-эндпоинт напрямую
     * в warpMeasuredWgEndpoint и синхронизирует с активным профилем.
     * Не искажает найденный порт.
     */
    fun saveMeasuredEndpoint(
        endpoint: String,
        protocol: String = "UDP"
    ): Boolean {
        val (ip, port) = com.mirrly.tgproxy.core.ProxyConfig.parseEndpoint(endpoint)
        if (ip.isBlank() || port !in 1..65535) {
            AppLogger.w(TAG, "saveMeasuredEndpoint: некорректный эндпоинт '$endpoint'")
            return false
        }
        val cleanEndpoint = if (ip.contains(":") && !ip.startsWith("[")) "[$ip]:$port" else "$ip:$port"
        val app = com.mirrly.tgproxy.MirrlyApplication.instance
        val config = app.config
        config.warpMeasuredWgEndpoint = cleanEndpoint
        config.warpPeerEndpoint = cleanEndpoint

        val currentWarp = app.prefsManager.getWarpProfile()
        if (currentWarp != null) {
            val updatedWarp = currentWarp.copy(
                measuredWgEndpoint = cleanEndpoint,
                peerEndpoint = cleanEndpoint
            )
            app.prefsManager.saveWarpProfile(updatedWarp)
        }

        // Сохраняем в конфигурацию
        app.prefsManager.saveConfig(config)

        // Обновляем активный профиль в менеджере без искажения порта в файле конфигурации
        val activeId = getActiveProfileId()
        if (activeId != null) {
            val currentList = _profilesFlow.value.toMutableList()
            val idx = currentList.indexOfFirst { it.id == activeId }
            if (idx >= 0) {
                val activeProf = currentList[idx]
                val updatedContent = if (activeProf.format.extension == "conf") {
                    activeProf.content.replace(
                        Regex("""(?mi)^(\s*Endpoint\s*=\s*).*$"""),
                        "$1$cleanEndpoint"
                    )
                } else {
                    activeProf.content
                }
                currentList[idx] = activeProf.copy(
                    endpoint = cleanEndpoint,
                    content = updatedContent
                )
                persistProfiles(currentList)
            }
        }

        AppLogger.i(TAG, "Anycast-эндпоинт зафиксирован: $cleanEndpoint")

        // Если VPN активен, мягко перезапускаем его для немедленного переподключения через найденный узел
        if (MirrlyVpnService.isRunning) {
            MirrlyVpnService.restart(context)
        }

        return true
    }

    /**
     * Удаление профиля по ID.
     */
    fun deleteProfile(profileId: String) {
        val current = _profilesFlow.value.toMutableList()
        current.removeAll { it.id == profileId }
        persistProfiles(current)

        if (getActiveProfileId() == profileId) {
            val nextActive = current.firstOrNull()?.id
            if (nextActive != null) {
                prefs.edit().putString(KEY_ACTIVE_PROFILE_ID, nextActive).apply()
                _activeProfileIdFlow.value = nextActive
            } else {
                prefs.edit().remove(KEY_ACTIVE_PROFILE_ID).apply()
                _activeProfileIdFlow.value = null
                try {
                    val app = com.mirrly.tgproxy.MirrlyApplication.instance
                    app.config.awgCustomIni = ""
                    app.prefsManager.saveConfig(app.config)
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Переименование сохраненного профиля.
     */
    fun renameProfile(profileId: String, newName: String): Boolean {
        val clean = newName.trim().take(35)
        if (clean.isBlank()) return false
        val current = _profilesFlow.value.toMutableList()
        val idx = current.indexOfFirst { it.id == profileId }
        if (idx != -1) {
            val p = current[idx]
            current[idx] = p.copy(name = clean)
            persistProfiles(current)
            return true
        }
        return false
    }

    /**
     * Удаление выбранных профилей по набору ID.
     */
    fun deleteProfiles(profileIds: Set<String>) {
        if (profileIds.isEmpty()) return
        val current = _profilesFlow.value.toMutableList()
        current.removeAll { it.id in profileIds }
        persistProfiles(current)

        val activeId = getActiveProfileId()
        if (activeId != null && activeId in profileIds) {
            val nextActive = current.firstOrNull()?.id
            if (nextActive != null) {
                prefs.edit().putString(KEY_ACTIVE_PROFILE_ID, nextActive).apply()
                _activeProfileIdFlow.value = nextActive
            } else {
                prefs.edit().remove(KEY_ACTIVE_PROFILE_ID).apply()
                _activeProfileIdFlow.value = null
                try {
                    val app = com.mirrly.tgproxy.MirrlyApplication.instance
                    app.config.awgCustomIni = ""
                    app.prefsManager.saveConfig(app.config)
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Удаление всех сохраненных профилей.
     */
    fun deleteAllProfiles() {
        persistProfiles(emptyList())
        prefs.edit().remove(KEY_ACTIVE_PROFILE_ID).apply()
        _activeProfileIdFlow.value = null
        try {
            val app = com.mirrly.tgproxy.MirrlyApplication.instance
            app.config.awgCustomIni = ""
            app.prefsManager.saveConfig(app.config)
        } catch (_: Exception) {}
    }

    /**
     * Переключение статуса «Избранное».
     */
    fun toggleFavorite(profileId: String) {
        val current = _profilesFlow.value.toMutableList()
        val idx = current.indexOfFirst { it.id == profileId }
        if (idx != -1) {
            val p = current[idx]
            current[idx] = p.copy(isFavorite = !p.isFavorite)
            persistProfiles(current)
        }
    }

    /**
     * Экспорт одиночного профиля в выходной поток (файл .conf или .yaml).
     */
    fun exportSingleProfile(profile: WarpSavedProfile, outputStream: OutputStream) {
        outputStream.use { os ->
            os.write(profile.content.toByteArray(StandardCharsets.UTF_8))
            os.flush()
        }
    }

    /**
     * Экспорт пакета профилей в ZIP-архив через Storage Access Framework.
     */
    fun exportBatchToZip(profiles: List<WarpSavedProfile>, outputStream: OutputStream) {
        ZipOutputStream(outputStream).use { zos ->
            val nameCounts = mutableMapOf<String, Int>()
            for (p in profiles) {
                val baseName = p.name.replace("[^a-zA-Z0-9_.-]".toRegex(), "_")
                val count = nameCounts.getOrDefault(baseName, 0)
                nameCounts[baseName] = count + 1
                val suffix = if (count == 0) "" else "_$count"

                val fileName = "$baseName$suffix.${p.format.extension}"
                val entry = ZipEntry(fileName)
                zos.putNextEntry(entry)
                zos.write(p.content.toByteArray(StandardCharsets.UTF_8))
                zos.closeEntry()
            }
            zos.finish()
        }
    }

    fun exportProfilesToZip(profiles: List<WarpSavedProfile>, outputStream: OutputStream) =
        exportBatchToZip(profiles, outputStream)

    fun reloadProfiles(): List<WarpSavedProfile> {
        val loaded = loadProfiles()
        _profilesFlow.value = loaded
        val activeId = getActiveProfileId()
        if (activeId != null && loaded.none { it.id == activeId }) {
            val fallbackId = loaded.firstOrNull()?.id
            if (fallbackId != null) {
                prefs.edit().putString(KEY_ACTIVE_PROFILE_ID, fallbackId).apply()
                _activeProfileIdFlow.value = fallbackId
            } else {
                prefs.edit().remove(KEY_ACTIVE_PROFILE_ID).apply()
                _activeProfileIdFlow.value = null
            }
        } else {
            _activeProfileIdFlow.value = activeId
        }
        return loaded
    }

    private fun loadProfiles(): List<WarpSavedProfile> {
        val jsonStr = prefs.getString(KEY_PROFILES_JSON, null) ?: return emptyList()
        return try {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<WarpSavedProfile>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                WarpSavedProfile.fromJsonObject(obj)?.let { list.add(it) }
            }
            list
        } catch (e: Exception) {
            AppLogger.w(TAG, "Ошибка загрузки списка сохраненных профилей WARP: ${e.message}")
            emptyList()
        }
    }

    private fun persistProfiles(list: List<WarpSavedProfile>) {
        try {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJsonObject()) }
            prefs.edit().putString(KEY_PROFILES_JSON, arr.toString()).apply()
            _profilesFlow.value = list.toList()
        } catch (e: Exception) {
            AppLogger.w(TAG, "Ошибка сохранения списка профилей WARP: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "WarpProfileManager"
        private const val PREFS_NAME = "mirrly_warp_profiles_prefs"
        private const val KEY_PROFILES_JSON = "warp_saved_profiles_list"
        private const val KEY_ACTIVE_PROFILE_ID = "warp_active_saved_profile_id"

        @Volatile
        private var instance: WarpProfileManager? = null

        fun getInstance(context: Context): WarpProfileManager =
            instance ?: synchronized(this) {
                instance ?: WarpProfileManager(context.applicationContext).also { instance = it }
            }
    }
}
