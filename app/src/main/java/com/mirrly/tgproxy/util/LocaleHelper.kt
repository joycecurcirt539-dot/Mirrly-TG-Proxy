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

package com.mirrly.tgproxy.util

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

object LocaleHelper {

    const val LANG_SYSTEM = "system"
    const val LANG_RU = "ru"
    const val LANG_EN = "en"
    const val LANG_FA = "fa"

    fun getSystemLocale(): Locale {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Resources.getSystem().configuration.locales.get(0)
            } else {
                @Suppress("DEPRECATION")
                Resources.getSystem().configuration.locale
            } ?: Locale.getDefault()
        } catch (_: Throwable) {
            Locale.getDefault()
        }
    }

    fun getTargetLocale(langCode: String): Locale {
        return when (langCode) {
            LANG_RU -> Locale("ru")
            LANG_EN -> Locale("en")
            LANG_FA -> Locale("fa", "IR")
            else -> getSystemLocale()
        }
    }

    fun applyLocale(context: Context, langCode: String) {
        val targetLocale = getTargetLocale(langCode)
        Locale.setDefault(targetLocale)

        try {
            val appRes = context.applicationContext.resources
            val config = Configuration(appRes.configuration)
            config.setLocale(targetLocale)
            @Suppress("DEPRECATION")
            appRes.updateConfiguration(config, appRes.displayMetrics)
        } catch (_: Throwable) {}

        try {
            val resources = context.resources
            val configuration = Configuration(resources.configuration)
            configuration.setLocale(targetLocale)
            @Suppress("DEPRECATION")
            resources.updateConfiguration(configuration, resources.displayMetrics)
        } catch (_: Throwable) {}
    }

    fun wrapContext(context: Context, langCode: String): Context {
        val targetLocale = getTargetLocale(langCode)
        Locale.setDefault(targetLocale)
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(targetLocale)
        return context.createConfigurationContext(configuration)
    }
}
