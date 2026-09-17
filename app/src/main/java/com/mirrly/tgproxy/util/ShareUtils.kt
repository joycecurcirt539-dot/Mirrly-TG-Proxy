package com.mirrly.tgproxy.util

import android.content.Context
import android.content.Intent
import com.mirrly.tgproxy.R

/**
 * Triggers native Android Share Sheet to let the user share Mirrly TG Proxy with friends.
 */
fun Context.shareApp() {
    val shareText = getString(R.string.share_app_text)

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, shareText)
    }

    try {
        val chooser = Intent.createChooser(intent, getString(R.string.share_app_chooser_title))
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(chooser)
    } catch (_: Exception) {
    }
}

