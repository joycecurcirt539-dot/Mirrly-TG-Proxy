/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * GNU GPL v3+ <https://www.gnu.org/licenses/>
 */

package com.mirrly.tgproxy.service

import android.content.Context
import com.mirrly.tgproxy.core.AppLogger
import com.mirrly.tgproxy.core.NativeProxy
import com.mirrly.tgproxy.core.WarpGeneratedProfileItem
import com.mirrly.tgproxy.core.WarpGeneratorFormat
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
    IMPORTED      // Импортирован пользователем
}

data class WarpSavedProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val format: WarpGeneratorFormat,
    val endpoint: String,
    val dnsLine: String,
    val content: String,
    val isWarpSafe: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false,
    val warnings: List<String> = emptyList(),
    val origin: WarpProfileOrigin = WarpProfileOrigin.CONFIGURATOR
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
            val formatStr = json.optString("format", WarpGeneratorFormat.AWG_1_0.name)
            val format = when (formatStr) {
                "AMNEZIA_WG", "AWG_1_0" -> WarpGeneratorFormat.AWG_1_0
                "AWG_2_0" -> WarpGeneratorFormat.AWG_2_0
                "AWG_3_0" -> WarpGeneratorFormat.AWG_3_0
                "AWG_3_1" -> WarpGeneratorFormat.AWG_3_1
                "WIREGUARD" -> WarpGeneratorFormat.WIREGUARD
                "WIRESOCK" -> WarpGeneratorFormat.WIRESOCK
                "CLASH_META" -> WarpGeneratorFormat.CLASH_META
                else -> try {
                    WarpGeneratorFormat.valueOf(formatStr)
                } catch (_: Exception) {
                    WarpGeneratorFormat.AWG_1_0
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

    fun hasUserProfiles(): Boolean = _profilesFlow.value.any { it.isPriority }

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

    /**
     * Возвращает эффективный активный профиль с учетом переключателя автоподбора.
     * Если автоподбор включен в настройках и профилей больше одного, система подбирает узел автоматически.
     * Если выключен ползунком, строго возвращается профиль, выбранный пользователем.
     * Если пользователь создал хотя бы один профиль, стандартный профиль или профиль старого регистратора
     * КАТЕГОРИЧЕСКИ НЕ ДОЛЖНЫ ИСПОЛЬЗОВАТЬСЯ.
     */
    fun getEffectiveActiveProfile(): WarpSavedProfile? {
        val profiles = _profilesFlow.value
        if (profiles.isEmpty()) return null

        val app = com.mirrly.tgproxy.MirrlyApplication.instance
        val isAutoEnabled = app.prefsManager.isVpnAutoProfileEnabled()

        val priorityProfiles = profiles.filter { it.isPriority }
        val effectivePool = if (priorityProfiles.isNotEmpty()) priorityProfiles else profiles

        if (effectivePool.size == 1) return effectivePool.first()

        return if (isAutoEnabled) {
            resolveAutoProfile() ?: effectivePool.find { it.id == getActiveProfileId() } ?: effectivePool.first()
        } else {
            effectivePool.find { it.id == getActiveProfileId() } ?: effectivePool.first()
        }
    }

    /**
     * Гарантирует наличие хотя бы одного активного профиля WARP (Cloudflare Model).
     * Если база профилей пуста (первый запуск, новое устройство или очистка данных),
     * автоматически инициализирует базовый профиль AmneziaWG на чистом порту (8095)
     * с сохранением в активные, предотвращая отказ запуска VPN на других устройствах.
     */
    fun ensureDefaultProfile(): WarpSavedProfile {
        val existing = getEffectiveActiveProfile()
        if (existing != null) return existing

        val app = com.mirrly.tgproxy.MirrlyApplication.instance
        val config = app.config

        // Если в хранилище уже есть валидный аккаунт WARP, применяем его к конфигурации
        val savedWarp = app.prefsManager.getWarpProfile()
        if (savedWarp != null && savedWarp.isUsable() && !savedWarp.isBootstrap()) {
            config.applyWarpProfile(savedWarp)
            app.prefsManager.saveConfig(config)
        } else if (config.warpPrivateKey.isBlank()) {
            // Если приватный ключ еще не сгенерирован, генерируем пару ключей Curve25519
            val (priv, pub) = com.mirrly.tgproxy.core.WarpAccountManager.generateWireGuardKeyPair()
            config.warpPrivateKey = priv
            config.warpPublicKey = pub
            config.warpClientIpv4 = "172.16.0.2"
            config.warpPeerEndpoint = "188.114.96.1:8095"
            config.warpMeasuredWgEndpoint = "188.114.96.1:8095"
            app.prefsManager.saveConfig(config)
        }

        val cleanEp = if (config.warpPeerEndpoint.isNotBlank() && !config.warpPeerEndpoint.endsWith(":2408") && !config.warpPeerEndpoint.endsWith(":0")) {
            config.warpPeerEndpoint
        } else {
            "188.114.96.1:8095"
        }

        val awgContent = config.getAmneziaWgConfig(cleanEndpoint = cleanEp)
        val defaultProfile = WarpSavedProfile(
            name = "Auto WARP",
            format = com.mirrly.tgproxy.core.WarpGeneratorFormat.AWG_1_0,
            endpoint = cleanEp,
            dnsLine = "1.1.1.1, 1.0.0.1",
            content = awgContent,
            origin = WarpProfileOrigin.CONFIGURATOR
        )

        val current = _profilesFlow.value.toMutableList()
        current.add(0, defaultProfile)
        persistProfiles(current)
        setActiveProfile(defaultProfile.id)
        AppLogger.i(TAG, "Автоматически создан и активирован базовый профиль WARP: ${defaultProfile.name} ($cleanEp)")
        return defaultProfile
    }

    /**
     * Обновляет базовый профиль "Auto WARP" зарегистрированными данными учетной записи Cloudflare.
     */
    fun updateDefaultProfileWithRegisteredAccount(profile: WarpProfile): WarpSavedProfile {
        val app = com.mirrly.tgproxy.MirrlyApplication.instance
        val config = app.config
        val cleanEp = if (config.warpPeerEndpoint.isNotBlank() && !config.warpPeerEndpoint.endsWith(":2408") && !config.warpPeerEndpoint.endsWith(":0")) {
            config.warpPeerEndpoint
        } else {
            "188.114.96.1:8095"
        }
        val awgContent = config.getAmneziaWgConfig(cleanEndpoint = cleanEp)
        val updatedProfile = WarpSavedProfile(
            name = "Auto WARP",
            format = com.mirrly.tgproxy.core.WarpGeneratorFormat.AWG_1_0,
            endpoint = cleanEp,
            dnsLine = "1.1.1.1, 1.0.0.1",
            content = awgContent,
            origin = WarpProfileOrigin.CONFIGURATOR
        )
        val current = _profilesFlow.value.toMutableList()
        val idx = current.indexOfFirst { it.name == "Auto WARP" }
        if (idx >= 0) {
            current[idx] = updatedProfile.copy(id = current[idx].id)
        } else {
            current.add(0, updatedProfile)
        }
        persistProfiles(current)
        setActiveProfile(updatedProfile.id)
        AppLogger.i(TAG, "Базовый профиль 'Auto WARP' обновлен зарегистрированными ключами Cloudflare (IPv4: ${profile.clientIpv4})")
        return updatedProfile
    }

    /**
     * Сохраняет один сгенерированный результат в базу профилей.
     */
    fun saveGeneratedProfile(
        item: WarpGeneratedProfileItem,
        makeActive: Boolean = false
    ): WarpSavedProfile {
        val profile = WarpSavedProfile(
            id = item.id,
            name = item.name.take(10),
            format = item.format,
            endpoint = item.endpoint,
            dnsLine = item.dnsLine,
            content = item.content,
            isWarpSafe = item.isWarpSafe,
            warnings = item.warnings,
            origin = WarpProfileOrigin.CONFIGURATOR
        )
        val current = _profilesFlow.value.toMutableList()
        // Удаляем существующий с таким ID или именем при перезаписи
        current.removeAll { it.id == profile.id || it.name == profile.name }
        current.add(0, profile)
        persistProfiles(current)

        if (makeActive || getActiveProfileId() == null) {
            setActiveProfile(profile.id)
        }
        return profile
    }

    /**
     * Пакетное сохранение списка результатов генерации.
     */
    fun saveGeneratedBatch(
        items: List<WarpGeneratedProfileItem>,
        makeFirstActive: Boolean = false
    ): List<WarpSavedProfile> {
        if (items.isEmpty()) return emptyList()
        val saved = mutableListOf<WarpSavedProfile>()
        val current = _profilesFlow.value.toMutableList()

        for (item in items) {
            val prof = WarpSavedProfile(
                id = item.id,
                name = item.name.take(10),
                format = item.format,
                endpoint = item.endpoint,
                dnsLine = item.dnsLine,
                content = item.content,
                isWarpSafe = item.isWarpSafe,
                warnings = item.warnings,
                origin = WarpProfileOrigin.CONFIGURATOR
            )
            current.removeAll { it.id == prof.id || it.name == prof.name }
            saved.add(prof)
        }
        current.addAll(0, saved)
        persistProfiles(current)

        if ((makeFirstActive || getActiveProfileId() == null) && saved.isNotEmpty()) {
            setActiveProfile(saved.first().id)
        }
        return saved
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
            ImportedProfileType.AMNEZIA_WG_CONF -> WarpGeneratorFormat.AWG_1_0
            ImportedProfileType.WIREGUARD_CONF -> WarpGeneratorFormat.WIREGUARD
            ImportedProfileType.CLASH_META_YAML -> WarpGeneratorFormat.CLASH_META
            ImportedProfileType.CLOUDFLARE_JSON -> WarpGeneratorFormat.AWG_1_0
            else -> WarpGeneratorFormat.AWG_1_0
        }
        val endpoint = warpProfile.effectivePeerEndpoint
        val dnsLine = warpProfile.dnsServers.ifBlank { "1.1.1.1, 1.0.0.1" }
        val confContent = if (content.isNotBlank()) content else warpProfile.awgRawConfig

        val profile = WarpSavedProfile(
            id = UUID.randomUUID().toString(),
            name = name.take(15),
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
        app.prefsManager.saveWarpProfile(warpProfile)
        app.config.applyWarpProfile(warpProfile)

        if (target.format.extension == "conf") {
            app.config.awgCustomIni = target.content
            app.config.awgStrategyName = com.mirrly.tgproxy.core.AwgObfuscationStrategy.CUSTOM.name
            app.config.vpnUplinkMode = com.mirrly.tgproxy.core.UplinkMode.AWG
            app.prefsManager.setVpnUplinkMode(com.mirrly.tgproxy.core.UplinkMode.AWG)
            if (target.endpoint.isNotBlank()) {
                app.config.warpPeerEndpoint = target.endpoint
                app.config.warpMeasuredWgEndpoint = target.endpoint
            }
        }
        app.prefsManager.saveConfig(app.config)

        // Загружаем в нативный Rust-движок, если это INI/CONF
        if (target.format.extension == "conf") {
            try {
                NativeProxy.setAwgConfig(target.content)
                NativeProxy.setVpnUplinkMode(com.mirrly.tgproxy.core.UplinkMode.AWG)
            } catch (e: Exception) {
                AppLogger.w(TAG, "Ошибка применения конфигурации в NativeProxy: ${e.message}")
            }
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
                val fileName = if (count == 0) "$baseName.${p.format.extension}" else "${baseName}_$count.${p.format.extension}"

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
