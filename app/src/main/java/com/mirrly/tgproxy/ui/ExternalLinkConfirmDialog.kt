package com.mirrly.tgproxy.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.mirrly.tgproxy.ui.theme.*

data class LinkDetails(
    val title: String,
    val category: String,
    val description: String
)

fun getLinkDetails(url: String, customTitle: String? = null, customDesc: String? = null): LinkDetails {
    if (customTitle != null && customDesc != null) {
        val cat = if (customTitle.contains("звёзд", ignoreCase = true) || customTitle.contains("звезд", ignoreCase = true) || customTitle.contains("Star", ignoreCase = true)) {
            "ОЦЕНКА НА GITHUB (STAR)"
        } else {
            "ВНЕШНИЙ ПЕРЕХОД"
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
                title = "Баг-трекер (Issues)",
                category = "GITHUB ISSUES",
                description = "Ссылка ведет в раздел «Issues» на GitHub. Здесь пользователи и разработчики обсуждают найденные ошибки, предлагаемый функционал и задачи проекта."
            )
            // 2. Releases
            segments.contains("releases") || lowerUrl.contains("/releases") -> LinkDetails(
                title = "Официальные релизы",
                category = "GITHUB RELEASES",
                description = "Ссылка ведет на страницу релизов репозитория на GitHub. Там вы можете скачать официальные проверенные APK-файлы и изучить ченджлог обновлений."
            )
            // 3. License
            segments.any { it.contains("license") } || lowerUrl.contains("license") -> LinkDetails(
                title = "Лицензия проекта",
                category = "ЛИЦЕНЗИОННОЕ СОГЛАШЕНИЕ",
                description = "Ссылка ведет на официальный файл лицензии в репозитории на GitHub, устанавливающий юридические условия использования и распространения кода."
            )
            // 4. Terms of Use
            segments.any { it.contains("terms") } || lowerUrl.contains("terms") -> LinkDetails(
                title = "Пользовательское соглашение",
                category = "ЮРИДИЧЕСКИЙ ДОКУМЕНТ",
                description = "Ссылка ведет на полные правила использования сервиса Mirrly TG Proxy, опубликованные в репозитории проекта на GitHub."
            )
            // 5. Pull Requests
            segments.contains("pulls") || segments.contains("pull") || lowerUrl.contains("/pull") -> LinkDetails(
                title = "Пулл-реквесты (Pull Requests)",
                category = "GITHUB PULL REQUESTS",
                description = "Ссылка ведет в раздел предложений кода и исправлений от участников открытого сообщества на GitHub."
            )
            // 6. User Profile (ONLY 1 segment after github.com, e.g. github.com/joycecurcirt539-dot)
            segments.size == 1 -> {
                val username = segments.first()
                LinkDetails(
                    title = "Профиль автора ($username)",
                    category = "ПРОФИЛЬ НА GITHUB",
                    description = "Ссылка ведет на личный профиль разработчика $username на GitHub. Вы сможете посмотреть другие репозитории, активность и проекты автора."
                )
            }
            // 7. Repository Main Page (EXACTLY 2 segments after github.com, e.g. github.com/joycecurcirt539-dot/Mirrly-TG-Proxy)
            segments.size == 2 -> {
                val repoName = segments[1]
                LinkDetails(
                    title = "Репозиторий проекта ($repoName)",
                    category = "РЕПОЗИТОРИЙ ПРОЕКТА",
                    description = "Ссылка ведет на главную страницу репозитория $repoName на GitHub. Здесь находится открытый исходный код приложения, README и файлы проекта."
                )
            }
            // 8. Subdirectories or files inside repository (> 2 segments)
            segments.size > 2 -> {
                val repoName = segments[1]
                LinkDetails(
                    title = "Файлы репозитория ($repoName)",
                    category = "ФАЙЛЫ ИСХОДНОГО КОДА",
                    description = "Ссылка ведет к конкретным файлам или каталогам в репозитории $repoName на платформе GitHub."
                )
            }
            // Fallback for GitHub
            else -> LinkDetails(
                title = "Страница на GitHub",
                category = "GITHUB",
                description = "Ссылка ведет на страницу сервиса разработки GitHub, связанную с Mirrly TG Proxy."
            )
        }
    }

    if (lowerUrl.contains("t.me") || lowerUrl.contains("telegram.me") || lowerUrl.contains("telegram.dog")) {
        return LinkDetails(
            title = "Официальный Telegram-канал",
            category = "TELEGRAM КАНАЛ",
            description = "Ссылка ведет на публичный канал в мессенджере Telegram с анонсами, важными новостями и оперативной поддержкой."
        )
    }

    if (lowerUrl.contains("dalink.to") || lowerUrl.contains("dalink")) {
        return LinkDetails(
            title = customTitle ?: "Поддержка автора (DaLink)",
            category = "ДОБРОВОЛЬНЫЕ ЧАЕВЫЕ",
            description = customDesc ?: "Приложение Mirrly TG Proxy абсолютно бесплатное! Ссылка ведет на платформу DaLink (СБП, банковские карты) исключительно для добровольной благодарности и чаевых разработчику R1Xern за развитие проекта."
        )
    }

    val domain = try {
        Uri.parse(rawUrl).host ?: rawUrl
    } catch (e: Exception) {
        rawUrl
    }

    return LinkDetails(
        title = customTitle ?: "Внешний веб-сайт ($domain)",
        category = "ВНЕШНИЙ ПЕРЕХОД",
        description = customDesc ?: "Ссылка ведет на внешний веб-сайт ($domain). При клике произойдет переход в вашем системном интернет-браузере."
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
    val details = remember(url, title, description) { getLinkDetails(url, title, description) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        val view = LocalView.current
        SideEffect {
            val window = (view.parent as? DialogWindowProvider)?.window
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                window?.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window?.attributes = window?.attributes?.apply {
                    blurBehindRadius = 70
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.12f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onDismiss() }
                .padding(horizontal = 24.dp)
        ) {
            // Detailed Link Information (Centered)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(bottom = 70.dp)
                    .clickable(enabled = false) {}
            ) {
                // Category Pill
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFF00F0FF).copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, Color(0xFF00F0FF).copy(alpha = 0.35f))
                ) {
                    Text(
                        text = details.category,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00F0FF),
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
            }

            // Floating Bottom Action Buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 36.dp)
                    .fillMaxWidth()
                    .clickable(enabled = false) {}
            ) {
                OutlinedButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDismiss()
                    },
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = TextWhite.copy(alpha = 0.90f)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Text("Отклонить", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }

                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDismiss()
                        onConfirmed?.invoke()
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "Не удалось открыть ссылку: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = 0.20f),
                        contentColor = TextWhite
                    ),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Text("Перейти", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}


