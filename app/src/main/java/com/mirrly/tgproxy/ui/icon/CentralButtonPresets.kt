/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.mirrly.tgproxy.ui.icon

import androidx.annotation.StringRes
import com.mirrly.tgproxy.R

/**
 * Описание пресета кинетической центральной кнопки.
 */
data class CentralButtonPreset(
    val id: String,
    @StringRes val titleRes: Int,
    val isCustom: Boolean = false
)

object CentralButtonPresets {

    val MTPROTO_PRESETS = listOf(
        CentralButtonPreset("default", R.string.settings_customization_buttons_preset_default),
        CentralButtonPreset("warp_glider", R.string.settings_customization_buttons_preset_warp_glider),
        CentralButtonPreset("custom", R.string.settings_customization_buttons_preset_custom, isCustom = true)
    )

    val SOCKS5_PRESETS = listOf(
        CentralButtonPreset("default", R.string.settings_customization_buttons_preset_default),
        CentralButtonPreset("warp_glider", R.string.settings_customization_buttons_preset_warp_glider),
        CentralButtonPreset("custom", R.string.settings_customization_buttons_preset_custom, isCustom = true)
    )

    val VPN_PRESETS = listOf(
        CentralButtonPreset("default", R.string.settings_customization_buttons_preset_default),
        CentralButtonPreset("custom", R.string.settings_customization_buttons_preset_custom, isCustom = true)
    )

    fun getPresetsForProtocol(protocol: String): List<CentralButtonPreset> {
        return when (protocol.lowercase()) {
            "mtproto" -> MTPROTO_PRESETS
            "socks5" -> SOCKS5_PRESETS
            "vpn" -> VPN_PRESETS
            else -> MTPROTO_PRESETS
        }
    }

    /**
     * Формирует системный промпт для нейросети под выбранный протокол с регламентом интервью MirrlyUI.
     */
    fun buildAiPromptForProtocol(protocol: String): String {
        val protoUpper = protocol.uppercase()
        val themeHint = when (protocol.lowercase()) {
            "mtproto" -> "MTProto Telegram Proxy (кибер-оригами, сверхзвуковой самолёт, квантовый луч шифрования, неоновый бирюзовый акцент #00F5D4)"
            "socks5" -> "SOCKS5 TCP/UDP Relay (стелс-перехватчик, сокетный узел, матрица маршрутизации, неоновый фиолетовый акцент #B388FF)"
            "vpn" -> "Cloudflare WARP / WireGuard VPN (кибер-облако, туннельный портал, защитный щит, янтарно-золотой акцент #FFA000)"
            else -> "Сетевой кибер-прокси"
        }

        return """
Я хочу создать новую векторную иконку центральной кнопки для Mirrly TG Proxy в режиме $protoUpper ($themeHint).

Пожалуйста, следуй регламенту создания иконок:
1. Сначала спроси у меня, какой стиль использовать — эталонный референс разработчиков Mirrly TG Proxy или полностью кастомный стиль по моему текстовому описанию.
2. Предупреди, что задашь мне уточняющие вопросы (3 вопроса для референса разработчиков либо 6 вопросов для кастомного стиля), чтобы мы согласовали детали перед созданием SVG.
3. Только после того, как я отвечу на все вопросы, сгенерируй чистый контурный вектор по стандартам MirrlyUI (вьюпорт 100 100, стиль Stroke 1.5-2.0dp, замкнутые сплайны Z, без заливки) и вызови инструмент apply_svg_button_icon(protocol="$protocol", svg_path_data="...", icon_name="...").
""".trimIndent()
    }

    const val MIRRLY_LOGO_VECTOR = "M 385.5 0 L 395.5 1 L 403 7.5 L 410 20.5 L 416 42.5 L 418 60.5 L 419 61.5 L 419 68.5 L 420 69.5 L 420 78.5 L 431 97.5 L 435 110.5 Q 433.9 117.5 436.5 121 Q 441.7 115.7 452.5 116 Q 466.2 118.3 473 127.5 L 477 132.5 L 477 135 L 198.5 242 L 197 240.5 L 197 225.5 L 199 217.5 L 206.5 206 Q 212.8 199.8 225 199.5 Q 214.9 189.6 209 175.5 L 205 160.5 L 204 137.5 L 205 136.5 L 206 121.5 L 210 104.5 L 209 103.5 L 207 76.5 L 206 75.5 L 206 45.5 L 209 33.5 L 215.5 24 Q 219.6 20.1 228.5 21 Q 240.8 23.7 249.5 30 L 272.5 48 Q 284.9 40.4 301.5 37 Q 306.3 38.3 307.5 36 L 337.5 36 Q 339 38.5 344.5 37 L 370.5 9 Q 376.5 3 385.5 0 Z M 367 100 L 360 103 Q 352 108 349 118 Q 347 131 353 137 Q 358 144 369 146 Q 381 146 388 140 Q 393 135 395 126 L 394 116 L 390 109 Q 384 99 367 100 Z M 266 112 L 257 116 Q 249 121 247 131 Q 245 145 252 152 Q 257 158 268 160 Q 278 160 284 156 Q 292 151 294 139 Q 294 128 290 123 Q 286 116 279 113 Q 274 111 266 112 Z M 328 144 L 324 148 Q 322 145 318 146 Q 315 147 316 151 Q 319 156 327 156 L 332 147 Q 331 144 328 144 Z M 604.5 87 Q 623.4 86.6 631 97.5 Q 637.6 105.9 637 121.5 L 626 169.5 L 624 183.5 L 622 188.5 L 619 207.5 L 617 212.5 L 615 226.5 L 613 231.5 L 611 245.5 L 609 250.5 L 607 264.5 L 605 269.5 L 603 283.5 L 601 288.5 L 599 302.5 L 597 307.5 L 595 321.5 L 593 326.5 L 591 340.5 L 589 345.5 L 587 359.5 L 585 364.5 L 583 378.5 L 581 383.5 L 578 402.5 L 576 407.5 L 574 421.5 L 572 426.5 L 570 440.5 L 568 445.5 L 566 459.5 L 564 464.5 L 539 587.5 Q 535.6 604.6 524.5 614 L 516.5 618 L 508.5 619 Q 507 616.5 501.5 618 L 490.5 614 L 350.5 512 L 342.5 507 L 337.5 507 L 252.5 583 L 246.5 584 L 241 579.5 L 240 574.5 L 241 573.5 L 241 562.5 L 242 561.5 L 246 502.5 L 247 501.5 L 251 442.5 L 252 438.5 L 260.5 430 L 432.5 276 L 523 191.5 L 525 185.5 Q 521.9 181.1 512.5 183 L 169.5 397 L 163.5 398 L 29.5 354 L 26.5 354 Q 13.8 349.4 5 342 L 1 334.5 L 1 329.5 Q 3.7 320.2 11.5 316 L 17.5 312 L 185.5 247 L 222.5 234 L 575.5 96 L 597.5 88 Q 603 89.5 604.5 87 Z"
    const val WARP_GLIDER_VECTOR = "M 50 14 L 62 38 L 92 68 L 74 65 L 60 76 L 50 70 L 40 76 L 26 65 L 8 68 L 38 38 Z M 50 32 L 58 48 L 50 60 L 42 48 Z M 50 14 V 70"
    const val WARP_CLOUD_VECTOR = "M 28 66 A 16 16 0 0 1 34 38 A 22 22 0 0 1 70 34 A 18 18 0 0 1 82 52 A 14 14 0 0 1 78 66 Z M 42 66 L 58 66"

    /**
     * Возвращает эталонные векторные сплайны для инструмента Gemini get_reference_icon_code.
     */
    fun getReferenceVectorCode(protocol: String): String {
        return when (protocol.lowercase()) {
            "mtproto" -> """
// Эталонный вектор по умолчанию: Mirrly TG Proxy (Маскот-котик Mirrly и самолётик Telegram)
// Сетка 0 0 100 100, стиль Stroke 1.5-2.0dp:
$MIRRLY_LOGO_VECTOR

// Прежний эталон главного экрана: Сверхзвуковой кибер-глайдер Mirrly:
$WARP_GLIDER_VECTOR
""".trimIndent()
            "socks5" -> """
// Эталонный вектор по умолчанию: Mirrly SOCKS5 (Маскот-котик Mirrly и самолётик Telegram)
// Сетка 0 0 100 100, стиль Stroke 1.5-2.0dp:
$MIRRLY_LOGO_VECTOR

// Прежний эталон главного экрана: Сверхзвуковой кибер-глайдер Mirrly:
$WARP_GLIDER_VECTOR
""".trimIndent()
            "vpn" -> """
// Эталонный вектор: VPN WARP Cloud (Вьюпорт 0 0 100 100)
// Контур 1:1 облака Cloudflare с орбитальным энергетическим разрывом:
$WARP_CLOUD_VECTOR
""".trimIndent()
            else -> MIRRLY_LOGO_VECTOR
        }
    }

    /**
     * Возвращает векторную геометрию для превью встроенных пресетов.
     */
    fun getPresetVectorCode(presetId: String): String? {
        return when (presetId) {
            "default" -> MIRRLY_LOGO_VECTOR
            "warp_glider" -> WARP_GLIDER_VECTOR
            "warp_cloud" -> WARP_CLOUD_VECTOR
            else -> null
        }
    }
}
