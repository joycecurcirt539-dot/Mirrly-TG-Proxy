package com.mirrly.tgproxy.util

import android.content.Context
import android.content.Intent

/**
 * Triggers native Android Share Sheet to let the user share Mirrly TG Proxy with friends.
 */
fun Context.shareApp() {
    val shareText = """
        Попробуй Mirrly TG Proxy для Android — быстрый обход замедлений Telegram без системного VPN!
        Скачать бесплатно с GitHub: https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy
    """.trimIndent()

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, shareText)
    }

    try {
        val chooser = Intent.createChooser(intent, "Рассказать друзьям о Mirrly TG Proxy")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(chooser)
    } catch (_: Exception) {
    }
}

