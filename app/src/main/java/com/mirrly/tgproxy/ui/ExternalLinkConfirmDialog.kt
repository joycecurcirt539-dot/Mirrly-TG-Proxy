package com.mirrly.tgproxy.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.ui.theme.ActiveGreenLed
import com.mirrly.tgproxy.ui.theme.AmoledBorder
import com.mirrly.tgproxy.ui.theme.TextMuted
import com.mirrly.tgproxy.ui.theme.TextWhite
import com.mirrly.tgproxy.ui.theme.springPress

data class LinkDetails(
    val title: String,
    val category: String,
    val description: String
)

fun getLinkDetails(context: Context, url: String, customTitle: String? = null, customDesc: String? = null): LinkDetails {
    val rawUrl = url.trim()
    val lowerUrl = rawUrl.lowercase()

    val defaultDetails = when {
        lowerUrl.contains("github.com") -> {
            val cleanPath = lowerUrl
                .substringAfter("github.com")
                .trim('/')
                .split('?')[0]
                .split('#')[0]

            val segments = cleanPath.split('/').filter { it.isNotEmpty() }

            when {
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
                // 6. Commit Diff / Changes
                segments.contains("commit") || lowerUrl.contains("/commit/") || lowerUrl.contains("/commit") -> LinkDetails(
                    title = context.getString(R.string.ext_link_commit_title),
                    category = context.getString(R.string.ext_link_commit_cat),
                    description = context.getString(R.string.ext_link_commit_desc)
                )
                // 7. User Profile (ONLY 1 segment after github.com, e.g. github.com/joycecurcirt539-dot)
                segments.size == 1 -> {
                    val username = segments.first()
                    LinkDetails(
                        title = context.getString(R.string.ext_link_profile_title, username),
                        category = context.getString(R.string.ext_link_profile_cat),
                        description = context.getString(R.string.ext_link_profile_desc, username)
                    )
                }
                // 8. Repository Main Page (EXACTLY 2 segments after github.com, e.g. github.com/joycecurcirt539-dot/Mirrly-TG-Proxy)
                segments.size == 2 -> {
                    val repoName = segments[1]
                    LinkDetails(
                        title = context.getString(R.string.ext_link_repo_title, repoName),
                        category = context.getString(R.string.ext_link_repo_cat),
                        description = context.getString(R.string.ext_link_repo_desc, repoName)
                    )
                }
                // 9. Subdirectories or files inside repository (> 2 segments)
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
        lowerUrl.contains("t.me") || lowerUrl.contains("telegram.me") || lowerUrl.contains("telegram.dog") || lowerUrl.startsWith("tg://") || lowerUrl.contains("tg://resolve") -> {
            LinkDetails(
                title = context.getString(R.string.ext_link_tg_title),
                category = context.getString(R.string.ext_link_tg_cat),
                description = context.getString(R.string.ext_link_tg_desc)
            )
        }
        lowerUrl.contains("dash.cloudflare.com") || lowerUrl.contains("cloudflare.com") -> {
            LinkDetails(
                title = context.getString(R.string.ext_link_cf_title),
                category = context.getString(R.string.ext_link_cf_cat),
                description = context.getString(R.string.ext_link_cf_desc)
            )
        }
        lowerUrl.contains("dalink.to") || lowerUrl.contains("dalink") -> {
            LinkDetails(
                title = context.getString(R.string.ext_link_dalink_title),
                category = context.getString(R.string.ext_link_dalink_cat),
                description = context.getString(R.string.ext_link_dalink_desc)
            )
        }
        else -> {
            val domain = try {
                Uri.parse(rawUrl).host ?: rawUrl
            } catch (e: Exception) {
                rawUrl
            }
            LinkDetails(
                title = context.getString(R.string.ext_link_website_title, domain),
                category = context.getString(R.string.ext_link_cat_external),
                description = context.getString(R.string.ext_link_website_desc, domain)
            )
        }
    }

    val finalTitle = customTitle ?: defaultDetails.title
    val finalDesc = customDesc ?: defaultDetails.description
    val finalCategory = if (customTitle != null && (customTitle.contains("Star", ignoreCase = true) || customTitle.contains(context.getString(R.string.github_star_dialog_btn_star), ignoreCase = true))) {
        context.getString(R.string.ext_link_cat_star)
    } else {
        defaultDetails.category
    }

    return LinkDetails(
        title = finalTitle,
        category = finalCategory,
        description = finalDesc
    )
}

@OptIn(ExperimentalMaterial3Api::class)
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

    MirrlyModalBottomSheet(
        onDismissRequest = onDismiss,
        maxTabletWidth = 480.dp
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(top = 4.dp, bottom = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Category Pill (Outline-Only, Zero-Background)
            Box(
                modifier = Modifier
                    .border(1.dp, ActiveGreenLed.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                    .background(Color.Transparent)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = details.category,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = ActiveGreenLed,
                    letterSpacing = 1.sp
                )
            }

            // Main Title
            Text(
                text = details.title,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = TextWhite,
                textAlign = TextAlign.Center
            )

            // Detailed Description
            Text(
                text = details.description,
                fontSize = 13.sp,
                color = TextWhite.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
                lineHeight = 19.sp
            )

            // Target URL Preview Card (Outline-Only)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, AmoledBorder, RoundedCornerShape(12.dp))
                    .background(Color.Transparent)
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = url,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.5.sp,
                    color = TextMuted,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            // Action Row: Cancel + Open Link
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Cancel Action Button
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, AmoledBorder, RoundedCornerShape(14.dp))
                        .background(Color.Transparent)
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDismiss()
                        }),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.action_cancel),
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextWhite.copy(alpha = 0.8f)
                    )
                }

                // Primary "Open Link" Action Button
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, ActiveGreenLed.copy(alpha = 0.8f), RoundedCornerShape(14.dp))
                        .background(Color.Transparent)
                        .springPress(onClick = {
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
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.err_open_link, e.localizedMessage ?: ""),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.action_open_link),
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = ActiveGreenLed
                    )
                }
            }
        }
    }
}
