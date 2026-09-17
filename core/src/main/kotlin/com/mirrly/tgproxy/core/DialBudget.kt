/*
 * Mirrly TG Proxy - Unified Dial & Probe Budget Telemetry Contract (MOB-003)
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 */

package com.mirrly.tgproxy.core

import org.json.JSONObject

/**
 * Diagnostic snapshot of the native DialBudgetManager state.
 * Reflects active establishment and background quotas for the active network profile.
 */
data class DialBudgetStats(
    val generation: Long,
    val isMobile: Boolean,
    val maxEstablishment: Int,
    val maxBackground: Int,
    val activeUser: Int,
    val activeRecovery: Int,
    val activeBackground: Int,
    val activeTotal: Int,
    val waitingUser: Int,
    val waitingRecovery: Int,
    val waitingBackground: Int
) {
    companion object {
        fun fromJson(jsonStr: String?): DialBudgetStats? {
            if (jsonStr.isNullOrBlank() || jsonStr == "{}") return null
            return try {
                val json = JSONObject(jsonStr)
                DialBudgetStats(
                    generation = json.optLong("generation", 0L),
                    isMobile = json.optBoolean("is_mobile", false),
                    maxEstablishment = json.optInt("max_establishment", 0),
                    maxBackground = json.optInt("max_background", 0),
                    activeUser = json.optInt("active_user", 0),
                    activeRecovery = json.optInt("active_recovery", 0),
                    activeBackground = json.optInt("active_background", 0),
                    activeTotal = json.optInt("active_total", 0),
                    waitingUser = json.optInt("waiting_user", 0),
                    waitingRecovery = json.optInt("waiting_recovery", 0),
                    waitingBackground = json.optInt("waiting_background", 0)
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
