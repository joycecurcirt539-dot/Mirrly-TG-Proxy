package com.mirrly.tgproxy.core

import org.json.JSONObject

enum class NetworkTransport {
    NONE,
    CELLULAR,
    WIFI,
    ETHERNET,
    VPN,
    OTHER;

    companion object {
        fun fromName(value: String): NetworkTransport =
            entries.firstOrNull { it.name == value.uppercase() } ?: OTHER
    }
}

enum class NetworkPowerMode {
    ACTIVE,
    SCREEN_OFF,
    POWER_SAVE
}

data class TransportSli(
    val measuredAtMs: Long = 0L,
    val smoothedRttMs: Long = -1L,
    val jitterMs: Long = 0L,
    val successRatePercent: Int = 100,
    val downstreamBps: Long = 0L,
    val upstreamBps: Long = 0L,
    val bufferbloatMs: Long = 0L
) {
    fun normalized(): TransportSli = copy(
        measuredAtMs = measuredAtMs.coerceAtLeast(0L),
        smoothedRttMs = smoothedRttMs.coerceAtLeast(-1L),
        jitterMs = jitterMs.coerceAtLeast(0L),
        successRatePercent = successRatePercent.coerceIn(0, 100),
        downstreamBps = downstreamBps.coerceAtLeast(0L),
        upstreamBps = upstreamBps.coerceAtLeast(0L),
        bufferbloatMs = bufferbloatMs.coerceAtLeast(0L)
    )

    internal fun toJson(): JSONObject = JSONObject().apply {
        put("measured_at_ms", measuredAtMs)
        put("smoothed_rtt_ms", smoothedRttMs)
        put("jitter_ms", jitterMs)
        put("success_rate_percent", successRatePercent)
        put("downstream_bps", downstreamBps)
        put("upstream_bps", upstreamBps)
        put("bufferbloat_ms", bufferbloatMs)
    }

    companion object {
        internal fun fromJson(json: JSONObject): TransportSli = TransportSli(
            measuredAtMs = json.optLong("measured_at_ms", 0L),
            smoothedRttMs = json.optLong("smoothed_rtt_ms", -1L),
            jitterMs = json.optLong("jitter_ms", 0L),
            successRatePercent = json.optInt("success_rate_percent", 100),
            downstreamBps = json.optLong("downstream_bps", 0L),
            upstreamBps = json.optLong("upstream_bps", 0L),
            bufferbloatMs = json.optLong("bufferbloat_ms", 0L)
        ).normalized()
    }
}

data class NetworkEnvironment(
    val validated: Boolean = false,
    val suspended: Boolean = false,
    val metered: Boolean = false,
    val roaming: Boolean = false,
    val congested: Boolean = false,
    val transport: NetworkTransport = NetworkTransport.NONE,
    val estimatedDownKbps: Int = 0,
    val estimatedUpKbps: Int = 0
) {
    companion object {
        fun unavailable(): NetworkEnvironment = NetworkEnvironment(suspended = true)
    }
}

/**
 * Versioned Kotlin → Rust snapshot used by every network-dependent policy.
 *
 * Partial producers update it through [withEnvironment], [withRuntime] and
 * [withTransportSli], so changing one concern cannot zero unrelated fields.
 */
data class NetworkProfile(
    val schemaVersion: Int = 1,
    val generation: Long = 1L,
    val validated: Boolean = false,
    val suspended: Boolean = true,
    val metered: Boolean = false,
    val roaming: Boolean = false,
    val congested: Boolean = false,
    val transport: NetworkTransport = NetworkTransport.NONE,
    val cellular: Boolean = false,
    val wifi: Boolean = false,
    val estimatedDownKbps: Int = 0,
    val estimatedUpKbps: Int = 0,
    val screenOn: Boolean = true,
    val powerSaveMode: Boolean = false,
    val powerMode: NetworkPowerMode = NetworkPowerMode.ACTIVE,
    val transportSli: TransportSli = TransportSli(),
    val happyEyeballsDelayMs: Long = 200L
) {
    val isMobile: Boolean
        get() = cellular || transport == NetworkTransport.CELLULAR

    fun withGeneration(value: Long): NetworkProfile = copy(generation = value).normalized()

    fun withEnvironment(environment: NetworkEnvironment): NetworkProfile = copy(
        validated = environment.validated,
        suspended = environment.suspended,
        metered = environment.metered,
        roaming = environment.roaming,
        congested = environment.congested,
        transport = environment.transport,
        cellular = environment.transport == NetworkTransport.CELLULAR,
        wifi = environment.transport == NetworkTransport.WIFI,
        estimatedDownKbps = environment.estimatedDownKbps,
        estimatedUpKbps = environment.estimatedUpKbps
    ).normalized()

    fun withRuntime(screenOn: Boolean, powerSaveMode: Boolean): NetworkProfile = copy(
        screenOn = screenOn,
        powerSaveMode = powerSaveMode,
        powerMode = when {
            powerSaveMode -> NetworkPowerMode.POWER_SAVE
            !screenOn -> NetworkPowerMode.SCREEN_OFF
            else -> NetworkPowerMode.ACTIVE
        }
    ).normalized()

    fun withTransportSli(value: TransportSli): NetworkProfile =
        copy(transportSli = value.normalized()).normalized()

    fun normalized(): NetworkProfile {
        val normalizedTransport = when {
            cellular -> NetworkTransport.CELLULAR
            wifi -> NetworkTransport.WIFI
            else -> transport
        }
        return copy(
            schemaVersion = 1,
            generation = generation.coerceAtLeast(1L),
            transport = normalizedTransport,
            cellular = normalizedTransport == NetworkTransport.CELLULAR,
            wifi = normalizedTransport == NetworkTransport.WIFI,
            estimatedDownKbps = estimatedDownKbps.coerceAtLeast(0),
            estimatedUpKbps = estimatedUpKbps.coerceAtLeast(0),
            powerMode = when {
                powerSaveMode -> NetworkPowerMode.POWER_SAVE
                !screenOn -> NetworkPowerMode.SCREEN_OFF
                else -> NetworkPowerMode.ACTIVE
            },
            transportSli = transportSli.normalized()
        )
    }

    fun toJsonString(): String = JSONObject().apply {
        val profile = normalized()
        put("schema_version", profile.schemaVersion)
        put("generation", profile.generation)
        put("validated", profile.validated)
        put("suspended", profile.suspended)
        put("metered", profile.metered)
        put("roaming", profile.roaming)
        put("congested", profile.congested)
        put("transport", profile.transport.name)
        put("cellular", profile.cellular)
        put("wifi", profile.wifi)
        put("estimated_down_kbps", profile.estimatedDownKbps)
        put("estimated_up_kbps", profile.estimatedUpKbps)
        put("screen_on", profile.screenOn)
        put("power_save_mode", profile.powerSaveMode)
        put("power_mode", profile.powerMode.name)
        put("transport_sli", profile.transportSli.toJson())
        put("happy_eyeballs_delay_ms", profile.happyEyeballsDelayMs)
    }.toString()

    companion object {
        fun fromJsonString(value: String): NetworkProfile {
            val json = JSONObject(value)
            val transport = NetworkTransport.fromName(json.optString("transport", "NONE"))
            return NetworkProfile(
                schemaVersion = json.optInt("schema_version", 1),
                generation = json.optLong("generation", 1L),
                validated = json.optBoolean("validated", false),
                suspended = json.optBoolean("suspended", true),
                metered = json.optBoolean("metered", false),
                roaming = json.optBoolean("roaming", false),
                congested = json.optBoolean("congested", false),
                transport = transport,
                cellular = json.optBoolean("cellular", transport == NetworkTransport.CELLULAR),
                wifi = json.optBoolean("wifi", transport == NetworkTransport.WIFI),
                estimatedDownKbps = json.optInt("estimated_down_kbps", 0),
                estimatedUpKbps = json.optInt("estimated_up_kbps", 0),
                screenOn = json.optBoolean("screen_on", true),
                powerSaveMode = json.optBoolean("power_save_mode", false),
                powerMode = runCatching {
                    NetworkPowerMode.valueOf(json.optString("power_mode", "ACTIVE"))
                }.getOrDefault(NetworkPowerMode.ACTIVE),
                transportSli = json.optJSONObject("transport_sli")?.let(TransportSli::fromJson)
                    ?: TransportSli(),
                happyEyeballsDelayMs = json.optLong("happy_eyeballs_delay_ms", 200L)
            ).normalized()
        }
    }
}
