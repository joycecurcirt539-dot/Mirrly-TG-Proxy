package com.mirrly.tgproxy.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.res.stringResource
import android.os.Build
import android.view.WindowManager
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.ui.theme.*

data class LinkDetails(
    val title: String,
    val category: String,
    val description: String
)

fun getLinkDetails(context: Context, url: String, customTitle: String? = null, customDesc: String? = null): LinkDetails {
    if (customTitle != null && customDesc != null) {
        val cat = if (customTitle.contains("Star", ignoreCase = true) || customTitle.contains(context.getString(R.string.github_star_dialog_btn_star), ignoreCase = true)) {
            context.getString(R.string.ext_link_cat_star)
        } else {
            context.getString(R.string.ext_link_cat_external)
        }
        return LinkDetails(title = customTitle, category = cat, description = customDesc)
    }

    val rawUrl = url.trim()
    val lowerUrl = rawUrl.lowercase()

    if (lowerUrl.contains("github.com")) {
        val cleanPath = lowerUrl
            .substringAfter("github.com")
            .trim('/')
            .split('?')[0]
            .split('#')[0]

        val segments = cleanPath.split('/').filter { it.isNotEmpty() }

        return when {
            // 1. Issues / Bug Tracker
            segments.contains("issues") || lowerUrl.contains("/issues") -> LinkDetails(
                title = context.getString(R.string.ext_link_issues_title),
                category = "GITHUB ISSUES",
                description = context.getString(R.string.ext_link_issues_desc)
            )
            // 2. Releases
            segments.contains("releases") || lowerUrl.contains("/releases") -> LinkDetails(
                title = context.getString(R.string.ext_link_releases_title),
                category = "GITHUB RELEASES",
                description = context.getString(R.string.ext_link_releases_desc)
            )
            // 3. License
            segments.any { it.contains("license") } || lowerUrl.contains("license") -> LinkDetails(
                title = context.getString(R.string.ext_link_license_title),
                category = context.getString(R.string.ext_link_license_cat),
                description = context.getString(R.string.ext_link_license_desc)
            )
            // 4. Terms of Use
            segments.any { it.contains("terms") } || lowerUrl.contains("terms") -> LinkDetails(
                title = context.getString(R.string.ext_link_terms_title),
                category = context.getString(R.string.ext_link_terms_cat),
                description = context.getString(R.string.ext_link_terms_desc)
            )
            // 5. Pull Requests
            segments.contains("pulls") || segments.contains("pull") || lowerUrl.contains("/pull") -> LinkDetails(
                title = context.getString(R.string.ext_link_pulls_title),
                category = "GITHUB PULL REQUESTS",
                description = context.getString(R.string.ext_link_pulls_desc)
            )
            // 6. User Profile (ONLY 1 segment after github.com, e.g. github.com/joycecurcirt539-dot)
            segments.size == 1 -> {
                val username = segments.first()
                LinkDetails(
                    title = context.getString(R.string.ext_link_profile_title, username),
                    category = context.getString(R.string.ext_link_profile_cat),
                    description = context.getString(R.string.ext_link_profile_desc, username)
                )
            }
            // 7. Repository Main Page (EXACTLY 2 segments after github.com, e.g. github.com/joycecurcirt539-dot/Mirrly-TG-Proxy)
            segments.size == 2 -> {
                val repoName = segments[1]
                LinkDetails(
                    title = context.getString(R.string.ext_link_repo_title, repoName),
                    category = context.getString(R.string.ext_link_repo_cat),
                    description = context.getString(R.string.ext_link_repo_desc, repoName)
                )
            }
            // 8. Subdirectories or files inside repository (> 2 segments)
            segments.size > 2 -> {
                val repoName = segments[1]
                LinkDetails(
                    title = context.getString(R.string.ext_link_files_title, repoName),
                    category = context.getString(R.string.ext_link_files_cat),
                    description = context.getString(R.string.ext_link_files_desc, repoName)
                )
            }
            // Fallback for GitHub
            else -> LinkDetails(
                title = context.getString(R.string.ext_link_github_title),
                category = "GITHUB",
                description = context.getString(R.string.ext_link_github_desc)
            )
        }
    }

    if (lowerUrl.contains("t.me") || lowerUrl.contains("telegram.me") || lowerUrl.contains("telegram.dog")) {
        return LinkDetails(
            title = context.getString(R.string.ext_link_tg_title),
            category = context.getString(R.string.ext_link_tg_cat),
            description = context.getString(R.string.ext_link_tg_desc)
        )
    }

    if (lowerUrl.contains("dalink.to") || lowerUrl.contains("dalink")) {
        return LinkDetails(
            title = customTitle ?: context.getString(R.string.ext_link_dalink_title),
            category = context.getString(R.string.ext_link_dalink_cat),
            description = customDesc ?: context.getString(R.string.ext_link_dalink_desc)
        )
    }

    val domain = try {
        Uri.parse(rawUrl).host ?: rawUrl
    } catch (e: Exception) {
        rawUrl
    }

    return LinkDetails(
        title = customTitle ?: context.getString(R.string.ext_link_website_title, domain),
        category = context.getString(R.string.ext_link_cat_external),
        description = customDesc ?: context.getString(R.string.ext_link_website_desc, domain)
    )
}

@Composable
fun ExternalLinkConfirmDialog(
    url: String,
    onDismiss: () -> Unit,
    onConfirmed: (() -> Unit)? = null,
    title: String? = null,
    description: String? = null
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val details = remember(url, title, description, context) { getLinkDetails(context, url, title, description) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        DialogBackdropBox(
            onDismiss = onDismiss,
            blurRadiusPx = 70
        ) {
            // Top-Left Back Navigation Button (Same style as Settings & Screen headers)
            IconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onDismiss()
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(top = 12.dp, start = 12.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_arrow_left),
                    contentDescription = stringResource(R.string.action_back),
                    tint = TextWhite,
                    modifier = Modifier.size(22.dp)
                )
            }

            // Detailed Link Information + Action Button (Centered)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .align(Alignment.Center)
                    .adaptiveContainerWidth(440.dp)
                    .padding(horizontal = 24.dp)
                    .navigationBarsPadding()
                    .padding(bottom = 80.dp)
                    .verticalScroll(rememberScrollState())
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {}
            ) {
                // Category Pill
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = ActiveGreenLed.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, ActiveGreenLed.copy(alpha = 0.35f))
                ) {
                    Text(
                        text = details.category,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = ActiveGreenLed,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                }

                // Main Title
                Text(
                    text = details.title,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite,
                    textAlign = TextAlign.Center,
                    letterSpacing = 0.3.sp
                )

                // Detailed Description
                Text(
                    text = details.description,
                    fontSize = 13.5.sp,
                    color = TextWhite.copy(alpha = 0.88f),
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )

                Spacer(modifier = Modifier.height(2.dp))

                // Target URL Preview
                Text(
                    text = url,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.55f),
                    textAlign = TextAlign.Center,
                    lineHeight = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Primary "Proceed" Action Button placed directly under the link
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDismiss()
                        onConfirmed?.invoke()
                        try {
                            val uri = Uri.parse(url.trim())
                            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, context.getString(R.string.err_open_link, e.localizedMessage ?: ""), Toast.LENGTH_SHORT).show()
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = 0.20f),
                        contentColor = TextWhite
                    ),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .fillMaxWidth(0.70f)
                        .height(48.dp)
                        .springPress()
                ) {
                    Text(stringResource(R.string.action_open_link), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}


