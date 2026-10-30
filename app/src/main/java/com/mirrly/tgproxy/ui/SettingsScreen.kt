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

package com.mirrly.tgproxy.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.roundToInt
import java.io.File
import com.mirrly.tgproxy.service.PreferencesManager
import com.mirrly.tgproxy.service.WallpaperProcessor
import com.mirrly.tgproxy.service.WallpaperPreset
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.core.view.WindowCompat
import com.mirrly.tgproxy.core.AppLogger
import com.mirrly.tgproxy.core.NativeProxy
import com.mirrly.tgproxy.service.DeveloperModeManager
import android.view.WindowManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.annotation.StringRes
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.core.NagleMode
import com.mirrly.tgproxy.core.ProxyConfig
import com.mirrly.tgproxy.core.ProxyMode
import com.mirrly.tgproxy.core.TcpNoDelayMode
import com.mirrly.tgproxy.service.ProxyForegroundService
import com.mirrly.tgproxy.ui.theme.*
import com.mirrly.tgproxy.util.shareApp
import android.net.Uri
import android.widget.Toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun InertialSpringSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    activeColor: Color = ActiveGreenLed,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val targetOffset = if (checked) 22.dp else 2.dp
    val animOffset by animateDpAsState(
        targetValue = targetOffset,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "switchInertiaOffset"
    )

    val effectiveActive = if (enabled) activeColor else activeColor.copy(alpha = 0.35f)
    val trackColor by animateColorAsState(
        targetValue = if (checked) effectiveActive else Color(0xFF181C28).copy(alpha = if (enabled) 1f else 0.5f),
        animationSpec = tween(180),
        label = "switchTrackColor"
    )

    Box(
        modifier = modifier
            .width(48.dp)
            .height(26.dp)
            .clip(CircleShape)
            .background(trackColor)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onCheckedChange(!checked)
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .offset(x = animOffset.coerceAtLeast(0.dp))
                .size(22.dp)
                .clip(CircleShape)
                .background(if (enabled) Color.White else Color.White.copy(alpha = 0.45f))
        )
    }
}

/** Small button-hint «ⓘ» next to with title configuring */
@Composable
fun InfoButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(18.dp)
            .clip(CircleShape)
            .border(1.dp, TextMuted.copy(alpha = 0.55f), CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
    ) {
        Text(
            text = "i",
            color = TextMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontStyle = FontStyle.Normal,
            lineHeight = 10.sp
        )
    }
}

/** Bottom sheet for detailed settings help descriptions (Zero-Popup dogma) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoDialog(title: String, body: String, onDismiss: () -> Unit) {
    val haptic = LocalHapticFeedback.current

    MirrlyModalBottomSheet(
        onDismissRequest = onDismiss,
        maxTabletWidth = 460.dp
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .fadingEdges(topFadeHeight = 16.dp, bottomFadeHeight = 24.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp, top = 4.dp)
        ) {
            // Title
            Text(
                text = title,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = TextWhite,
                textAlign = TextAlign.Center,
                letterSpacing = 0.3.sp
            )

            // Formatted body content with transparent glass cards & left-aligned text
            FormattedInfoBody(body = body)

            // Footnote section with glossary and simple language explanation of terms
            InfoFootnoteCard(title = title, body = body)

            // Close button (Outline-Only springPress)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, AmoledBorder, RoundedCornerShape(14.dp))
                    .springPress(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDismiss()
                    }),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.action_close),
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextWhite
                )
            }
        }
    }
}

@Composable
private fun FormattedInfoBody(body: String) {
    val sections = remember(body) { parseInfoSections(body) }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        sections.forEachIndexed { index, section ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, AmoledBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    section.heading?.let { heading ->
                        Text(
                            text = heading,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = ActiveGreenLed,
                            letterSpacing = 0.35.sp
                        )
                    }

                    section.lines.forEach { line ->
                        when {
                            isInfoHeading(line) -> Text(
                                text = line.trim().trimEnd(':'),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = ActiveGreenLed.copy(alpha = 0.9f),
                                letterSpacing = 0.3.sp
                            )
                            line.startsWith("• ") || line.startsWith("- ") -> InfoBulletItem(
                                text = line.drop(2).trim()
                            )
                            else -> InfoParagraph(text = line)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun InfoFootnoteCard(title: String, body: String) {
    val combinedText = remember(title, body) { "$title $body".lowercase() }
    val currentLang = remember {
        val app = com.mirrly.tgproxy.MirrlyApplication.instance
        app.prefsManager.getAppLanguage().lowercase().ifEmpty { "ru" }
    }

    val definitions = remember(combinedText, currentLang) {
        getRelevantTermExplanations(combinedText, currentLang)
    }

    if (definitions.isEmpty()) return

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, ActiveGreenLed.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = ActiveGreenLed.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, ActiveGreenLed.copy(alpha = 0.45f))
                ) {
                    Text(
                        text = when (currentLang) {
                            "en" -> "TERM EXPLANATIONS"
                            "fa" -> "توضیح اصطلاحات"
                            else -> "ПОЯСНЕНИЕ ТЕРМИНОВ"
                        },
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp,
                        color = ActiveGreenLed,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                definitions.forEach { item ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = "•",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black,
                            color = ActiveGreenLed,
                            modifier = Modifier.padding(top = 1.dp)
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = item.term,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite
                            )
                            Text(
                                text = item.definition,
                                fontSize = 12.sp,
                                color = TextWhite.copy(alpha = 0.82f),
                                lineHeight = 16.5.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

internal data class TermGlossaryItem(
    val term: String,
    val definition: String,
    val keywords: List<String>
)

internal fun getRelevantTermExplanations(text: String, lang: String): List<TermGlossaryItem> {
    val allTerms = when (lang) {
        "en" -> listOf(
            TermGlossaryItem(
                term = "Proxy Gateway",
                definition = "A secure intermediate server routing Telegram data to bypass network restrictions without affecting other apps.",
                keywords = listOf("proxy", "gateway", "relay", "port")
            ),
            TermGlossaryItem(
                term = "MTProto",
                definition = "Telegram's native encrypted protocol. Ideal for fast chat messaging, channels, and low data usage.",
                keywords = listOf("mtproto", "1080", "1443")
            ),
            TermGlossaryItem(
                term = "SOCKS5",
                definition = "Universal network protocol supporting high-bandwidth audio/video calls and heavy media files.",
                keywords = listOf("socks5", "10808")
            ),
            TermGlossaryItem(
                term = "Cloudflare Worker",
                definition = "A cloud microserver receiving traffic from your phone and securely forwarding it to Telegram data centers.",
                keywords = listOf("worker", "cloudflare", "domain")
            ),
            TermGlossaryItem(
                term = "System VPN",
                definition = "Android system-level network interface routing app traffic through an encrypted tunnel without manual proxy configuration.",
                keywords = listOf("vpn", "tunnel", "vpnservice")
            ),
            TermGlossaryItem(
                term = "Split Tunneling",
                definition = "Selective routing allowing specific applications to pass through VPN while other apps connect directly.",
                keywords = listOf("split", "splittunnel", "apps", "disallow", "allow")
            ),
            TermGlossaryItem(
                term = "WireGuard and AmneziaWG",
                definition = "High-performance encrypted protocols optimized for mobile networks and DPI resistance.",
                keywords = listOf("wireguard", "amnezia", "awg")
            ),
            TermGlossaryItem(
                term = "MASQUE Protocol",
                definition = "Modern IETF tunneling standard over HTTP/3 and QUIC cloaking connection as standard web traffic.",
                keywords = listOf("masque", "quic", "http/3")
            ),
            TermGlossaryItem(
                term = "Uplink Route",
                definition = "The external server route through which the proxy reaches target data centers.",
                keywords = listOf("uplink", "warp", "vless", "route")
            ),
            TermGlossaryItem(
                term = "Network Latency RTT",
                definition = "Round-trip time in milliseconds for a network signal. Lower numbers mean faster app responsiveness.",
                keywords = listOf("ping", "rtt", "latency", "delay")
            ),
            TermGlossaryItem(
                term = "Jitter",
                definition = "Variation in ping delay over cellular networks. High jitter causes audio stutter and dropped calls.",
                keywords = listOf("jitter", "variation")
            ),
            TermGlossaryItem(
                term = "Traffic Cloaking FakeTLS",
                definition = "Disguising proxy data as normal HTTPS website browsing to prevent ISP deep packet inspection from blocking it.",
                keywords = listOf("obfuscation", "faketls", "cloaking", "secret", "tls")
            ),
            TermGlossaryItem(
                term = "Encrypted DNS",
                definition = "Encrypted resolution of server IP addresses, protecting your DNS requests from ISP snooping and tampering.",
                keywords = listOf("dns")
            ),
            TermGlossaryItem(
                term = "Queue Bufferbloat",
                definition = "Sudden network lag spikes caused by overloaded router queues during large background file downloads.",
                keywords = listOf("bufferbloat", "buffer")
            ),
            TermGlossaryItem(
                term = "Immediate Send TCP_NODELAY",
                definition = "Socket setting sending packets immediately instead of waiting to batch them, minimizing chat latency.",
                keywords = listOf("tcp_nodelay", "nodelay")
            ),
            TermGlossaryItem(
                term = "Fast Path Happy Eyeballs",
                definition = "Smart dual-stack algorithm testing IPv6 and IPv4 simultaneously, connecting to whichever responds first.",
                keywords = listOf("happy eyeballs", "eyeballs", "ipv6", "ipv4")
            ),
            TermGlossaryItem(
                term = "Connection Pool WsPool",
                definition = "Pre-warmed open connections maintained in standby, removing cold-start dial delays when opening chats.",
                keywords = listOf("pool", "wspool", "standby")
            ),
            TermGlossaryItem(
                term = "Security Handshake",
                definition = "Initial cryptographic security key exchange between smartphone and proxy before data transfer begins.",
                keywords = listOf("handshake", "agreement")
            ),
            TermGlossaryItem(
                term = "Multiplexing",
                definition = "Streaming multiple data flows through one shared secure tunnel without opening extra connections.",
                keywords = listOf("multiplex", "mux", "stream")
            )
        )
        "fa" -> listOf(
            TermGlossaryItem(
                term = "درگاه پروکسی",
                definition = "سرور واسط امنی که ترافیک تلگرام را بدون درگیر کردن کل سیستم به سرورها می‌رساند.",
                keywords = listOf("proxy", "پروکسی", "درگاه", "پورت")
            ),
            TermGlossaryItem(
                term = "پروتکل MTProto",
                definition = "پروتکل اختصاصی و رمزنگاری‌شده تلگرام، ایده‌آل برای پیام‌های متنی، کانال‌ها و سرعت بالای چت.",
                keywords = listOf("mtproto", "1080", "1443")
            ),
            TermGlossaryItem(
                term = "پروتکل SOCKS5",
                definition = "پروتکل همه‌منظوره شبکه مناسب برای تماس‌های صوتی و تصویری و دانلود بدون قطعی رسانه‌ها.",
                keywords = listOf("socks5", "10808")
            ),
            TermGlossaryItem(
                term = "ورکر کلودفلر",
                definition = "میکروسرور ابری که ترافیک گوشی را دریافت کرده و با امنیت کامل به دیتاسنترهای تلگرام هدایت می‌کند.",
                keywords = listOf("worker", "ورکر", "کلودفلر", "دامنه")
            ),
            TermGlossaryItem(
                term = "وی‌پی‌ان سیستمی",
                definition = "رابط شبکه در سطح سیستم‌عامل اندروید برای هدایت ترافیک برنامه‌ها از طریق تونل رمزگذاری‌شده.",
                keywords = listOf("vpn", "وی‌پی‌ان", "تونل", "vpnservice")
            ),
            TermGlossaryItem(
                term = "تونل‌بندی تفکیکی",
                definition = "امکان عبور برنامه‌های مشخص از وی‌پی‌ان و اتصال مستقیم مابقی برنامه‌ها به اینترنت عادی.",
                keywords = listOf("split", "تفکیک", "برنامه‌ها", "استثنا")
            ),
            TermGlossaryItem(
                term = "پروتکل‌های WireGuard و AmneziaWG",
                definition = "پروتکل‌های پرسرعت رمزنگاری‌شده با مقاومت در برابر فیلترینگ عمیق بسته‌ها.",
                keywords = listOf("wireguard", "amnezia", "awg")
            ),
            TermGlossaryItem(
                term = "پروتکل MASQUE",
                definition = "استاندارد نوین اینترنت بر پایه HTTP/3 و QUIC با استتار به عنوان ترافیک عادی وب.",
                keywords = listOf("masque", "quic", "http/3")
            ),
            TermGlossaryItem(
                term = "مسیر بالادست",
                definition = "مسیر یا سرور خروجی که پروکسی از طریق آن به اینترنت متصل می‌شود.",
                keywords = listOf("uplink", "وارپ", "بالادست", "مسیر")
            ),
            TermGlossaryItem(
                term = "تأخیر شبکه RTT",
                definition = "مدت‌زمان رفت و برگشت داده بر حسب میلی‌ثانیه؛ عدد کمتر نشان‌دهنده سرعت و پاسخ‌دهی بهتر است.",
                keywords = listOf("پینگ", "rtt", "تأخیر", "ping")
            ),
            TermGlossaryItem(
                term = "نوسان پینگ",
                definition = "تغییرات ناگهانی تأخیر در شبکه همراه؛ نوسان بالا باعث لکنت و قطعی صدا در تماس‌ها می‌شود.",
                keywords = listOf("jitter", "نوسان")
            ),
            TermGlossaryItem(
                term = "استتار FakeTLS",
                definition = "شبیه‌سازی ترافیک پروکسی به عنوان وبگردی عادی و امن جهت جلوگیری از شناسایی و فیلترینگ.",
                keywords = listOf("استتار", "faketls", "رمزنگاری", "tls", "مبدل")
            ),
            TermGlossaryItem(
                term = "دی‌ان‌اس رمزنگاری‌شده",
                definition = "یافتن آدرس‌های اینترنتی به صورت رمزنگاری‌شده تا اپراتور نتواند مسیرهای شما را شنود کند.",
                keywords = listOf("dns", "دی‌ان‌اس")
            ),
            TermGlossaryItem(
                term = "صف بافر بلوت",
                definition = "کندی شدید اینترنت ناشی از پر شدن صف‌های داده مودم هنگام دانلود سنگین در پس‌زمینه.",
                keywords = listOf("bufferbloat", "صف", "بافر")
            ),
            TermGlossaryItem(
                term = "ارسال فوری TCP_NODELAY",
                definition = "ارسال لحظه‌ای بسته‌ها بدون معطلی در بافر حافظه، جهت به حداقل رسیدن تأخیر پیام‌ها.",
                keywords = listOf("tcp_nodelay", "nodelay")
            ),
            TermGlossaryItem(
                term = "اتصال سریع Happy Eyeballs",
                definition = "آزمایش هم‌زمان پروتکل‌های IPv6 و IPv4 و برقراری ارتباط با هر کدام که سریع‌تر پاسخ دهد.",
                keywords = listOf("happy eyeballs", "ipv6", "ipv4")
            ),
            TermGlossaryItem(
                term = "استخر اتصالات WsPool",
                definition = "کانال‌های از پیش آماده در پس‌زمینه برای ارسال آنی پیام‌ها بدون تأخیر در اتصال مجدد.",
                keywords = listOf("pool", "استخر", "wspool")
            ),
            TermGlossaryItem(
                term = "توافق کلیدها Handshake",
                definition = "مبادله امن کلیدهای رمزنگاری بین گوشی و سرور پیش از آغاز تبادل داده.",
                keywords = listOf("handshake", "توافق")
            )
        )
        else -> listOf(
            TermGlossaryItem(
                term = "Прокси-шлюз",
                definition = "Промежуточный узел, который безопасно перенаправляет сетевой трафик в обход ограничений, не влияя на другие приложения и экономя батарею.",
                keywords = listOf("прокси", "релей", "proxy", "relay", "шлюз", "порт")
            ),
            TermGlossaryItem(
                term = "MTProto",
                definition = "Официальный защищенный протокол Telegram. Оптимален для мгновенной переписки, чтения каналов и экономии трафика.",
                keywords = listOf("mtproto", "мтпрото", "1080", "1443")
            ),
            TermGlossaryItem(
                term = "SOCKS5",
                definition = "Универсальный сетевой протокол. Необходим для четких аудио и видеозвонков и быстрой загрузки больших медиафайлов.",
                keywords = listOf("socks5", "сокс", "10808")
            ),
            TermGlossaryItem(
                term = "Cloudflare Worker",
                definition = "Облачный микросервер, принимающий трафик от смартфона и безопасно передающий его на целевые дата-центры.",
                keywords = listOf("воркер", "worker", "cloudflare", "домен")
            ),
            TermGlossaryItem(
                term = "Системный VPN",
                definition = "Интерфейс операционной системы Android для перенаправления сетевого трафика через зашифрованный туннель без ручной настройки прокси.",
                keywords = listOf("vpn", "туннел", "tunnel", "vpnservice", "системн")
            ),
            TermGlossaryItem(
                term = "Раздельное туннелирование",
                definition = "Выборочная передача трафика указанных приложений через VPN либо их исключение для прямой работы через оператора связи.",
                keywords = listOf("раздельное", "splittunnel", "split", "исключ", "приложен")
            ),
            TermGlossaryItem(
                term = "Протоколы WireGuard и AmneziaWG",
                definition = "Высокоскоростные криптографические протоколы с защитой от глубокого анализа пакетов ТСПУ.",
                keywords = listOf("wireguard", "amnezia", "awg")
            ),
            TermGlossaryItem(
                term = "Протокол MASQUE",
                definition = "Современный стандарт IETF на базе HTTP/3 и QUIC, маскирующий сетевое соединение под обычный веб-трафик к CDN.",
                keywords = listOf("masque", "quic", "http/3")
            ),
            TermGlossaryItem(
                term = "Сервер подключения",
                definition = "Внешний маршрут через который приложение связывается с целевой сетью.",
                keywords = listOf("аплинк", "uplink", "warp", "vless", "маршрут")
            ),
            TermGlossaryItem(
                term = "Сетевая задержка RTT",
                definition = "Время в миллисекундах за которое сигнал доходит до сервера и возвращается. Чем меньше значение, тем быстрее отклик.",
                keywords = listOf("пинг", "rtt", "задержк", "latency", "ping")
            ),
            TermGlossaryItem(
                term = "Джиттер",
                definition = "Нестабильность сетевой задержки. Высокий джиттер приводит к заиканиям и прерываниям звука при звонках.",
                keywords = listOf("джиттер", "jitter", "разброс", "колебан")
            ),
            TermGlossaryItem(
                term = "Маскировка FakeTLS",
                definition = "Технология сокрытия сетевого трафика под стандартный защищенный веб-сайт для предотвращения блокировок провайдером.",
                keywords = listOf("маскировк", "обфус", "faketls", "tls", "секрет", "secret")
            ),
            TermGlossaryItem(
                term = "Шифрованный DNS",
                definition = "Зашифрованное определение IP-адресов серверов, исключающее перехват и подмену сетевых запросов.",
                keywords = listOf("dns", "днс")
            ),
            TermGlossaryItem(
                term = "Очередь Bufferbloat",
                definition = "Всплеск сетевой задержки из-за переполнения буферных очередей в оборудовании во время фоновой загрузки файлов.",
                keywords = listOf("bufferbloat", "буферблоат", "очеред", "раздуван")
            ),
            TermGlossaryItem(
                term = "Мгновенная отправка TCP_NODELAY",
                definition = "Режим сокета, заставляющий устройство отправлять пакеты сразу без ожидания наполнения буфера.",
                keywords = listOf("tcp_nodelay", "nodelay")
            ),
            TermGlossaryItem(
                term = "Выбор адреса Happy Eyeballs",
                definition = "Алгоритм одновременного тестирования адресов IPv6 и IPv4 с подключением к первому ответившему узлу.",
                keywords = listOf("happy eyeballs", "eyeballs", "ipv6", "ipv4")
            ),
            TermGlossaryItem(
                term = "Пул соединений WsPool",
                definition = "Набор открытых резервных каналов в режиме ожидания, исключающий задержки подключения.",
                keywords = listOf("пул", "wspool", "standby")
            ),
            TermGlossaryItem(
                term = "Рукопожатие Handshake",
                definition = "Начальное согласование криптографических ключей шифрования между клиентом и сервером.",
                keywords = listOf("рукопожат", "handshake", "согласован")
            ),
            TermGlossaryItem(
                term = "Мультиплексирование",
                definition = "Передача нескольких независимых потоков данных через единый защищенный туннель.",
                keywords = listOf("мультиплекс", "mux", "поток")
            )
        )
    }

    val matched = allTerms.filter { item ->
        item.keywords.any { text.contains(it) }
    }

    return if (matched.isNotEmpty()) {
        matched.take(3)
    } else {
        emptyList()
    }
}

internal data class InfoSection(
    val heading: String?,
    val lines: List<String>
)

internal fun parseInfoSections(body: String): List<InfoSection> {
    val normalized = body
        .replace("\\n", "\n")
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .trim()
        .let(::restoreCollapsedInfoMarkup)

    if (normalized.isEmpty()) return emptyList()

    val parsed = mutableListOf<InfoSection>()
    var heading: String? = null
    val content = mutableListOf<String>()

    fun flush() {
        if (heading != null || content.isNotEmpty()) {
            parsed += InfoSection(heading, content.toList())
        }
        heading = null
        content.clear()
    }

    normalized.lines().forEach { rawLine ->
        val line = rawLine.trim()
        when {
            line.isEmpty() -> {
                if (content.isNotEmpty()) flush()
            }
            isInfoHeading(line) -> {
                flush()
                heading = line.trimEnd(':').trim()
            }
            else -> content += line
        }
    }
    flush()

    val nonEmpty = parsed.filter { it.heading != null || it.lines.isNotEmpty() }
    if (nonEmpty.size <= 5) return nonEmpty

    val firstFour = nonEmpty.take(4)
    val tail = nonEmpty.drop(4)
    val tailLines = buildList {
        tail.forEachIndexed { index, section ->
            if (index > 0) section.heading?.let { add("$it:") }
            addAll(section.lines)
        }
    }
    return firstFour + InfoSection(tail.firstOrNull()?.heading, tailLines)
}

/**
 * Older settings resources used visual XML line breaks instead of escaped \n.
 * AAPT collapses those breaks into spaces, so recover only the explicit markup
 * that survives compilation: bullet characters and uppercase section labels.
 */
private fun restoreCollapsedInfoMarkup(value: String): String {
    val withBullets = value.replace(Regex("\\s*•\\s*"), "\n• ")
    val inlineUppercaseHeading = Regex(
        """([.!?])\s+((?:\d+\.\s*)?[\p{Lu}\d][\p{Lu}\p{M}\d\s&+()/.–—-]{2,95}:)(?=\s|\n)"""
    )
    return withBullets
        .replace(inlineUppercaseHeading) { match ->
            "${match.groupValues[1]}\n\n${match.groupValues[2].trim()}\n"
        }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

private fun isInfoHeading(line: String): Boolean {
    val candidate = line.trim()
    if (candidate.isEmpty() || candidate.startsWith("•") || candidate.startsWith("-")) return false
    if (candidate.length > 96) return false
    val letters = candidate.filter { it.isLetter() }
    val isUppercase = letters.length >= 3 && letters == letters.uppercase(java.util.Locale.ROOT)
    return candidate.endsWith(":") || isUppercase
}

@Composable
private fun InfoParagraph(text: String) {
    val colonIndex = text.indexOf(':').takeIf { it in 1..42 }
    val annotated = buildAnnotatedString {
        if (colonIndex != null) {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = TextWhite)) {
                append(text.substring(0, colonIndex + 1))
            }
            append(text.substring(colonIndex + 1))
        } else {
            append(text)
        }
    }
    Text(
        text = annotated,
        fontSize = 13.sp,
        color = TextWhite.copy(alpha = 0.86f),
        lineHeight = 19.sp,
        textAlign = TextAlign.Start
    )
}

@Composable
private fun InfoBulletItem(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .padding(top = 7.dp)
                .size(5.dp)
                .background(ActiveGreenLed, CircleShape)
        )
        Box(modifier = Modifier.weight(1f)) {
            InfoParagraph(text = text)
        }
    }
}

enum class SettingsCategory(@StringRes val titleRes: Int) {
    ALL(R.string.settings_category_all),
    NETWORK(R.string.settings_category_network),
    UPLINK(R.string.settings_category_uplink),
    SYSTEM(R.string.settings_category_system),
    MISC(R.string.settings_category_other)
}

@Composable
private fun SettingsDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(AmoledBorder.copy(alpha = 0.7f))
    )
}

enum class SettingsProtocolMode {
    MTPROTO,
    SOCKS5,
    VPN
}

enum class SettingsSafetyLevel {
    SAFE,
    EXPERT,
    DANGER
}

@Composable
fun SettingsSafetyBadge(
    level: SettingsSafetyLevel,
    customLabel: String? = null,
    modifier: Modifier = Modifier
) {
    val (color, defaultText) = when (level) {
        SettingsSafetyLevel.SAFE -> ActiveGreenLed to stringResource(R.string.settings_safety_safe)
        SettingsSafetyLevel.EXPERT -> Color(0xFFFFB74D) to stringResource(R.string.settings_safety_expert)
        SettingsSafetyLevel.DANGER -> Color(0xFFEF4444) to stringResource(R.string.settings_safety_danger)
    }
    val text = customLabel ?: defaultText

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Transparent)
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            fontSize = 9.sp,
            fontWeight = FontWeight.Black,
            color = color,
            letterSpacing = 0.7.sp
        )
    }
}

@Composable
private fun SettingsVpnDevBanner(
    vpnColors: ProtocolColors,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, Color(0xFFFFB74D).copy(alpha = 0.45f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.settings_vpn_in_dev_title),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.1.sp,
                    color = Color(0xFFFFB74D)
                )
                SettingsSafetyBadge(
                    level = SettingsSafetyLevel.EXPERT,
                    customLabel = stringResource(R.string.settings_vpn_in_dev_badge)
                )
            }
            Text(
                text = stringResource(R.string.settings_vpn_in_dev_desc),
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                color = TextWhite.copy(alpha = 0.85f)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAbout: () -> Unit = {},
    onOpenTelegramChannel: () -> Unit = {},
    onOpenUpdate: () -> Unit = {},
    onOpenWorkerGuide: () -> Unit = {},
    onOpenWorkerManager: (com.mirrly.tgproxy.core.ProxyMode?) -> Unit = {},
    onOpenHallOfFame: () -> Unit = {},
    onOpenOnboarding: () -> Unit = {},
    onOpenVpnOnboarding: (() -> Unit)? = null,
    onOpenDiagnosticReport: () -> Unit = {},
    onOpenWarpGenerator: () -> Unit = {},
    initialIsVpn: Boolean = false
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val app = MirrlyApplication.instance
    val config = app.config
    val server = app.proxyServer
    val systemVpnColors = remember { com.mirrly.tgproxy.ui.theme.VpnThemeManager.getSystemVpnPalette(context) }

    var portText by remember { mutableStateOf(config.bindPort.toString()) }
    val isPortError by remember {
        derivedStateOf {
            portText.toIntOrNull()?.let { it < 1 || it > 65535 } ?: portText.isNotEmpty()
        }
    }
    var socks5PortText by remember { mutableStateOf(config.socks5Port.toString()) }
    val isSocks5PortError by remember {
        derivedStateOf {
            socks5PortText.toIntOrNull()?.let { it < 1 || it > 65535 } ?: socks5PortText.isNotEmpty()
        }
    }
    var socks5UserText by remember(config.socks5Username) { mutableStateOf(config.socks5Username) }
    var socks5PassText by remember(config.socks5Password) { mutableStateOf(config.socks5Password) }
    var showSocks5Pass by remember { mutableStateOf(false) }
    var secretText by remember(config.secretHex) { mutableStateOf(config.secretHex) }
    var showSecret by remember { mutableStateOf(false) }
    var socks5Optimistic by remember { mutableStateOf(app.prefsManager.isSocks5OptimisticResponse()) }
    val proxyMode by app.prefsManager.proxyModeFlow.collectAsState(initial = app.config.proxyMode)
    val isSocks5 by app.prefsManager.isSocks5Flow.collectAsState(initial = app.config.isSocks5Mode)
    val socks5UplinkMode by app.prefsManager.socks5UplinkModeFlow.collectAsState(initial = app.config.socks5UplinkMode)
    val useWorkerMtproto by app.prefsManager.useWorkerMtprotoFlow.collectAsState()
    val isSwitching by com.mirrly.tgproxy.service.ProtocolSwitchManager.isSwitching.collectAsState()

    var activeProtocolMode by remember(proxyMode, initialIsVpn) {
        mutableStateOf(
            if (initialIsVpn) {
                SettingsProtocolMode.VPN
            } else {
                when (proxyMode) {
                    ProxyMode.SOCKS5 -> SettingsProtocolMode.SOCKS5
                    ProxyMode.MTPROTO -> SettingsProtocolMode.MTPROTO
                }
            }
        )
    }
    LaunchedEffect(proxyMode, initialIsVpn) {
        if (!initialIsVpn) {
            activeProtocolMode = when (proxyMode) {
                ProxyMode.SOCKS5 -> SettingsProtocolMode.SOCKS5
                ProxyMode.MTPROTO -> SettingsProtocolMode.MTPROTO
            }
        }
    }

    var selectedSpeedPresetName by remember { mutableStateOf(config.speedPresetName) }
    val isAdvancedMode by app.prefsManager.advancedSettingsEnabledFlow.collectAsState()
    val currentAppLanguage by app.prefsManager.appLanguageFlow.collectAsState()
    var autostart by remember { mutableStateOf(config.autostartOnBoot) }
    var infoKey by remember { mutableStateOf<String?>(null) }
    var pendingIssueRedirectUrl by remember { mutableStateOf<String?>(null) }
    var isProxyRunning by remember { mutableStateOf(server.isRunning) }
    LaunchedEffect(Unit) {
        while (isActive) {
            val running = server.isRunning
            if (isProxyRunning != running) {
                isProxyRunning = running
            }
            delay(300)
        }
    }
    val coroutineScope = rememberCoroutineScope()
    val timerState by com.mirrly.tgproxy.service.SleepTimerManager.timerState.collectAsState()
    val uplinkMode by app.prefsManager.uplinkModeFlow.collectAsState()
    val vpnUplinkMode by app.prefsManager.vpnUplinkModeFlow.collectAsState()
    val vpnState by com.mirrly.tgproxy.service.MirrlyVpnService.vpnState.collectAsState()
    val vpnSplitTunnelEnabled by app.prefsManager.vpnSplitTunnelEnabledFlow.collectAsState()
    val vpnSplitTunnelAllowlist by app.prefsManager.vpnSplitTunnelAllowlistFlow.collectAsState()
    val vpnSplitTunnelPackages by app.prefsManager.vpnSplitTunnelPackagesFlow.collectAsState()
    val isDevVpnUnlocked by DeveloperModeManager.isDevVpnUnlocked.collectAsState()
    var showVpnProtocolDialog by rememberSaveable { mutableStateOf(false) }
    var showSplitTunnelAppsDialog by rememberSaveable { mutableStateOf(false) }
    var warpProfile by remember { mutableStateOf(app.prefsManager.getWarpProfile()) }
    var isRegisteringWarp by remember { mutableStateOf(false) }
    var isRegisterWarpViaWorker by remember { mutableStateOf(config.isRegisterWarpViaWorker) }
    var vlessUuid by remember { mutableStateOf<String>(app.prefsManager.getVlessUuid().ifEmpty { config.vlessUuid }) }
    var sleepTimerInitialTab by remember { mutableStateOf(TimerDialogTab.TIMER) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var showDonationDialog by remember { mutableStateOf(false) }
    var showWarpRegistrationDialog by remember { mutableStateOf(false) }
    var warpDialogStartRegistrationImmediately by remember { mutableStateOf(false) }
    var showCfAuthDialog by remember { mutableStateOf(false) }
    var showOnboardingDialog by remember { mutableStateOf(false) }
    var showVpnOnboardingDialog by remember { mutableStateOf(false) }
    val triggerVpnOnboarding: () -> Unit = onOpenVpnOnboarding ?: { showVpnOnboardingDialog = true }

    val cfAuthState by com.mirrly.tgproxy.service.cloudflare.CloudflareOAuthManager.authState.collectAsState()
    LaunchedEffect(cfAuthState) {
        val state = cfAuthState
        if (state is com.mirrly.tgproxy.service.cloudflare.ServerAuthState.Success && showCfAuthDialog) {
            showCfAuthDialog = false
            com.mirrly.tgproxy.service.cloudflare.CloudflareOAuthManager.finalizeSuccessfulAuth(state.tokens, app.prefsManager)
            com.mirrly.tgproxy.service.cloudflare.CloudflareOAuthManager.cancelActiveListener()
            showOnboardingDialog = true
        }
    }

    val density = LocalDensity.current.density
    val langBlurAnim = remember { Animatable(0f) }
    val langBlurVal = langBlurAnim.value

    val isSheetOpen = BottomSheetStateTracker.isAnySheetOpen
    val effectiveBlurVal = if (isSheetOpen) 1f else langBlurVal

    val langBlurModifier = if (effectiveBlurVal > 0.005f) {
        Modifier.graphicsLayer {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val blurPx = effectiveBlurVal * 20f * density
                if (blurPx > 0.5f) {
                    renderEffect = RenderEffect.createBlurEffect(
                        blurPx,
                        blurPx,
                        Shader.TileMode.CLAMP
                    ).asComposeRenderEffect()
                }
            }
            alpha = (1f - effectiveBlurVal * 0.45f).coerceIn(0.55f, 1f)
        }
    } else {
        Modifier
    }

    val onLanguageChange: (String) -> Unit = { newLang ->
        if (!langBlurAnim.isRunning) {
            coroutineScope.launch {
                langBlurAnim.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 130, easing = FastOutLinearInEasing)
                )
                app.prefsManager.setAppLanguage(newLang)
                langBlurAnim.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 180, easing = LinearOutSlowInEasing)
                )
            }
        }
    }

    fun restartProxyIfNeeded() {
        app.saveConfig()
        if (server.isRunning) {
            val serviceIntent = Intent(context, ProxyForegroundService::class.java).apply {
                action = ProxyForegroundService.ACTION_RESTART
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                AppLogger.e("SettingsScreen", "Failed to restart proxy service: ${e.message}")
            }
        }
    }

    val toggleAdvancedMode: (Boolean) -> Unit = { enabled ->
        app.prefsManager.setAdvancedSettingsEnabled(enabled)
        if (!enabled) {
            app.prefsManager.resetAdvancedSettingsToDefaults(config)
            app.saveConfig()
            selectedSpeedPresetName = config.speedPresetName
            socks5Optimistic = config.socks5OptimisticResponse
            restartProxyIfNeeded()
            Toast.makeText(
                context,
                context.getString(R.string.toast_advanced_mode_reset),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    LaunchedEffect(portText) {
        delay(600)
        val p = portText.toIntOrNull()
        if (p != null && p in 1..65535 && p != config.bindPort) {
            config.bindPort = p
            restartProxyIfNeeded()
        }
    }

    LaunchedEffect(socks5PortText) {
        delay(600)
        val p = socks5PortText.toIntOrNull()
        if (p != null && p in 1..65535 && p != config.socks5Port) {
            config.socks5Port = p
            restartProxyIfNeeded()
        }
    }



    LaunchedEffect(socks5UserText) {
        delay(600)
        val trimmed = socks5UserText.trim()
        if (trimmed != config.socks5Username) {
            config.socks5Username = trimmed
            NativeProxy.setSocks5Auth(config.socks5Username, config.socks5Password)
            restartProxyIfNeeded()
        }
    }

    LaunchedEffect(socks5PassText) {
        delay(600)
        val trimmed = socks5PassText.trim()
        if (trimmed != config.socks5Password) {
            config.socks5Password = trimmed
            NativeProxy.setSocks5Auth(config.socks5Username, config.socks5Password)
            restartProxyIfNeeded()
        }
    }

    LaunchedEffect(secretText) {
        delay(600)
        val trimmed = secretText.trim()
        if (trimmed.isNotBlank() && trimmed != config.secretHex) {
            config.secretHex = trimmed
            restartProxyIfNeeded()
        }
    }

    infoKey?.let { key ->
        SettingsInfoDialog(infoKey = key, onDismiss = { infoKey = null })
    }

    pendingIssueRedirectUrl?.let { url ->
        ExternalLinkConfirmDialog(
            url = url,
            onDismiss = { pendingIssueRedirectUrl = null }
        )
    }

    if (showSplitTunnelAppsDialog) {
        SplitTunnelAppsDialog(
            vpnColors = systemVpnColors,
            selectedPackages = vpnSplitTunnelPackages,
            onSave = { newSelection ->
                app.prefsManager.setVpnSplitTunnelPackages(newSelection)
                showSplitTunnelAppsDialog = false
                if (vpnState == com.mirrly.tgproxy.ui.theme.VpnUiState.CONNECTED) {
                    com.mirrly.tgproxy.service.MirrlyVpnService.restart(context)
                }
            },
            onDismiss = { showSplitTunnelAppsDialog = false }
        )
    }

    if (showVpnProtocolDialog) {
        VpnProtocolSelectorDialog(
            vpnColors = systemVpnColors,
            onDismiss = { showVpnProtocolDialog = false }
        )
    }

    var selectedCategory by rememberSaveable { mutableStateOf(SettingsCategory.ALL) }
    val scrollState = rememberScrollState()

    LaunchedEffect(selectedCategory) {
        scrollState.animateScrollTo(0)
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        val showNetwork = selectedCategory == SettingsCategory.ALL || selectedCategory == SettingsCategory.NETWORK
        val showUplink = selectedCategory == SettingsCategory.ALL || selectedCategory == SettingsCategory.UPLINK
        val showSystem = selectedCategory == SettingsCategory.ALL || selectedCategory == SettingsCategory.SYSTEM
        val showMisc = selectedCategory == SettingsCategory.ALL || selectedCategory == SettingsCategory.MISC

        Column(
            modifier = Modifier
                .adaptiveContainerWidth(600.dp)
                .fillMaxHeight()
                .fadingEdges(topFadeHeight = 24.dp, bottomFadeHeight = 44.dp)
                .then(langBlurModifier)
                .verticalScroll(scrollState)
                .padding(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 110.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 20.dp
                )
                .adaptiveContentPadding(),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            if (showNetwork) {
                SettingsProtocolSection(
                    activeMode = activeProtocolMode,
                    isSwitching = isSwitching,
                    vpnColors = systemVpnColors,
                    onInfoClick = { infoKey = "protocols_info" },
                    onModeSelect = { mode ->
                        activeProtocolMode = mode
                        when (mode) {
                            SettingsProtocolMode.MTPROTO -> {
                                if (proxyMode != ProxyMode.MTPROTO) {
                                    com.mirrly.tgproxy.service.ProtocolSwitchManager.switchProtocol(context, ProxyMode.MTPROTO)
                                }
                            }
                            SettingsProtocolMode.SOCKS5 -> {
                                if (proxyMode != ProxyMode.SOCKS5) {
                                    com.mirrly.tgproxy.service.ProtocolSwitchManager.switchProtocol(context, ProxyMode.SOCKS5)
                                }
                            }
                            SettingsProtocolMode.VPN -> {}
                        }
                    }
                )

                SettingsDivider()

                when (activeProtocolMode) {
                    SettingsProtocolMode.VPN -> {
                        SettingsVpnStatusOverviewSection(
                            vpnColors = systemVpnColors,
                            vpnState = vpnState,
                            onOpenVpnDialog = { showVpnProtocolDialog = true },
                            onInfoClick = { key -> infoKey = key }
                        )

                        SettingsDivider()

                        SettingsVpnSplitTunnelSection(
                            vpnColors = systemVpnColors,
                            isEnabled = vpnSplitTunnelEnabled,
                            onToggleEnabled = { app.prefsManager.setVpnSplitTunnelEnabled(it) },
                            isAllowlist = vpnSplitTunnelAllowlist,
                            onToggleAllowlist = { app.prefsManager.setVpnSplitTunnelAllowlist(it) },
                            packageCount = vpnSplitTunnelPackages.size,
                            onOpenAppPicker = { showSplitTunnelAppsDialog = true },
                            onInfoClick = { key -> infoKey = key }
                        )
                    }
                    SettingsProtocolMode.MTPROTO -> {
                        SettingsNetworkSection(
                            selectedMode = ProxyMode.MTPROTO,
                            isAdvancedMode = isAdvancedMode,
                            onEnableAdvancedMode = { toggleAdvancedMode(true) },
                            portText = portText,
                            onPortChange = { portText = it },
                            isPortError = isPortError,
                            socks5PortText = socks5PortText,
                            onSocks5PortChange = { socks5PortText = it },
                            isSocks5PortError = isSocks5PortError,
                            socks5UserText = socks5UserText,
                            onSocks5UserChange = { socks5UserText = it },
                            socks5PassText = socks5PassText,
                            onSocks5PassChange = { socks5PassText = it },
                            showSocks5Pass = showSocks5Pass,
                            onToggleShowSocks5Pass = { showSocks5Pass = !showSocks5Pass },
                            onGenerateSocks5Auth = {
                                val (u, p) = ProxyConfig.generateRandomSocks5Credentials()
                                socks5UserText = u
                                socks5PassText = p
                                config.socks5Username = u
                                config.socks5Password = p
                                NativeProxy.setSocks5Auth(u, p)
                                restartProxyIfNeeded()
                            },
                            onClearSocks5Auth = {
                                socks5UserText = ""
                                socks5PassText = ""
                                config.socks5Username = ""
                                config.socks5Password = ""
                                NativeProxy.setSocks5Auth("", "")
                                restartProxyIfNeeded()
                            },
                            secretText = secretText,
                            onSecretChange = { secretText = it },
                            showSecret = showSecret,
                            onToggleShowSecret = { showSecret = !showSecret },
                            onRefreshSecret = {
                                val newSecret = ProxyConfig.generateRandomSecret()
                                secretText = newSecret
                                config.secretHex = newSecret
                                restartProxyIfNeeded()
                            },
                            socks5Optimistic = socks5Optimistic,
                            onToggleSocks5Optimistic = { checked ->
                                socks5Optimistic = checked
                                config.socks5OptimisticResponse = checked
                                app.prefsManager.setSocks5OptimisticResponse(checked)
                                app.saveConfig()
                            },
                            onInfoClick = { infoKey = it }
                        )
                    }
                    SettingsProtocolMode.SOCKS5 -> {
                        SettingsNetworkSection(
                            selectedMode = ProxyMode.SOCKS5,
                            isAdvancedMode = isAdvancedMode,
                            onEnableAdvancedMode = { toggleAdvancedMode(true) },
                            portText = portText,
                            onPortChange = { portText = it },
                            isPortError = isPortError,
                            socks5PortText = socks5PortText,
                            onSocks5PortChange = { socks5PortText = it },
                            isSocks5PortError = isSocks5PortError,
                            socks5UserText = socks5UserText,
                            onSocks5UserChange = { socks5UserText = it },
                            socks5PassText = socks5PassText,
                            onSocks5PassChange = { socks5PassText = it },
                            showSocks5Pass = showSocks5Pass,
                            onToggleShowSocks5Pass = { showSocks5Pass = !showSocks5Pass },
                            onGenerateSocks5Auth = {
                                val (u, p) = ProxyConfig.generateRandomSocks5Credentials()
                                socks5UserText = u
                                socks5PassText = p
                                config.socks5Username = u
                                config.socks5Password = p
                                NativeProxy.setSocks5Auth(u, p)
                                restartProxyIfNeeded()
                            },
                            onClearSocks5Auth = {
                                socks5UserText = ""
                                socks5PassText = ""
                                config.socks5Username = ""
                                config.socks5Password = ""
                                NativeProxy.setSocks5Auth("", "")
                                restartProxyIfNeeded()
                            },
                            secretText = secretText,
                            onSecretChange = { secretText = it },
                            showSecret = showSecret,
                            onToggleShowSecret = { showSecret = !showSecret },
                            onRefreshSecret = {
                                val newSecret = ProxyConfig.generateRandomSecret()
                                secretText = newSecret
                                config.secretHex = newSecret
                                restartProxyIfNeeded()
                            },
                            socks5Optimistic = socks5Optimistic,
                            onToggleSocks5Optimistic = { checked ->
                                socks5Optimistic = checked
                                config.socks5OptimisticResponse = checked
                                app.prefsManager.setSocks5OptimisticResponse(checked)
                                app.saveConfig()
                            },
                            onInfoClick = { infoKey = it }
                        )

                    }

                }

                if (showUplink || showSystem || showMisc) {
                    SettingsDivider()
                }
            }

            if (showUplink) {
                if (activeProtocolMode == SettingsProtocolMode.VPN) {
                    SettingsUplinkWarpSection(
                        config = config,
                        uplinkMode = vpnUplinkMode,
                        warpProfile = warpProfile,
                        isRegisteringWarp = isRegisteringWarp,
                        onSelectUplinkMode = { newMode ->
                            app.prefsManager.setVpnUplinkMode(newMode)
                            config.vpnUplinkModeName = newMode.name
                            app.saveConfig()
                            if (vpnState == com.mirrly.tgproxy.ui.theme.VpnUiState.CONNECTED) {
                                com.mirrly.tgproxy.service.MirrlyVpnService.restart(context)
                            }
                        },
                        onRefreshWarpAccount = {
                            warpDialogStartRegistrationImmediately = true
                            showWarpRegistrationDialog = true
                        },
                        onOpenWarpDetails = {
                            warpDialogStartRegistrationImmediately = false
                            showWarpRegistrationDialog = true
                        },
                        onInfoClick = { infoKey = it },
                        vlessUuid = vlessUuid,
                        onRegenerateVlessUuid = {
                            val newUuid = com.mirrly.tgproxy.core.ProxyConfig.generateVlessUuid()
                            vlessUuid = newUuid
                            config.vlessUuid = newUuid
                            app.saveConfig()
                            Toast.makeText(context, context.getString(R.string.toast_vless_uuid_generated), Toast.LENGTH_SHORT).show()
                            if (vpnState == com.mirrly.tgproxy.ui.theme.VpnUiState.CONNECTED) {
                                com.mirrly.tgproxy.service.MirrlyVpnService.restart(context)
                            }
                        },
                        onRestartProxy = {
                            if (vpnState == com.mirrly.tgproxy.ui.theme.VpnUiState.CONNECTED) {
                                com.mirrly.tgproxy.service.MirrlyVpnService.restart(context)
                            }
                        }
                    )

                    SettingsDivider()
                } else if (activeProtocolMode == SettingsProtocolMode.SOCKS5) {
                    SettingsSocks5UplinkCard(
                        uplinkMode = socks5UplinkMode,
                        onSelectUplinkMode = { mode ->
                            app.prefsManager.setSocks5UplinkMode(mode)
                            config.socks5UplinkMode = mode
                            restartProxyIfNeeded()
                        }
                    )

                    SettingsWorkerSection(
                        config = config,
                        onOpenWorkerManager = { onOpenWorkerManager(com.mirrly.tgproxy.core.ProxyMode.SOCKS5) },
                        onInfoClick = { infoKey = it }
                    )

                    SettingsDivider()
                } else {
                    val activeWorker = app.prefsManager.getActiveWorker(com.mirrly.tgproxy.core.ProxyMode.MTPROTO)
                    SettingsMtprotoWorkerCard(
                        useWorker = useWorkerMtproto,
                        activeWorker = activeWorker,
                        onToggleWorker = { enabled ->
                            if (enabled && activeWorker.domain.isBlank()) {
                                showCfAuthDialog = true
                            } else {
                                app.prefsManager.setUseWorkerMtproto(enabled)
                            }
                        },
                        onOpenWorkerManager = { onOpenWorkerManager(com.mirrly.tgproxy.core.ProxyMode.MTPROTO) }
                    )

                    SettingsMtprotoCdnOverviewCard()

                    SettingsDivider()
                }

                if (showSystem || showMisc) {
                    SettingsDivider()
                }
            }

            if (showSystem) {
                SettingsSystemSection(
                    autostart = autostart,
                    onAutostartChange = { newValue ->
                        autostart = newValue
                        config.autostartOnBoot = newValue
                        app.saveConfig()
                    },
                    timerState = timerState,
                    onOpenSleepTimer = {
                        sleepTimerInitialTab = TimerDialogTab.TIMER
                        showSleepTimerDialog = true
                    },
                    onOpenSchedule = {
                        sleepTimerInitialTab = TimerDialogTab.SCHEDULE
                        showSleepTimerDialog = true
                    },
                    onOpenOnboarding = onOpenOnboarding,
                    onOpenVpnOnboarding = triggerVpnOnboarding,
                    onOpenDiagnosticReport = onOpenDiagnosticReport,
                    onInfoClick = { infoKey = it },
                    onLanguageChange = onLanguageChange
                )

                SettingsDivider()

                SettingsAdvancedModeToggleCard(
                    isAdvancedMode = isAdvancedMode,
                    onToggle = toggleAdvancedMode,
                    onInfoClick = { infoKey = it }
                )

                AnimatedVisibility(
                    visible = isAdvancedMode,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        SettingsPerformanceSection(
                            config = config,
                            selectedSpeedPresetName = selectedSpeedPresetName,
                            onPresetSelect = { preset ->
                                selectedSpeedPresetName = preset.name
                                config.speedPresetName = preset.name
                                config.applyPreset(preset)
                                app.saveConfig()
                                restartProxyIfNeeded()
                            },
                            onInfoClick = { infoKey = it }
                        )

                        SettingsDivider()

                        SettingsAdvancedEngineeringSection(
                            config = config,
                            onInfoClick = { infoKey = it },
                            onRestartProxy = { restartProxyIfNeeded() }
                        )
                    }
                }

                if (showMisc) {
                    SettingsDivider()
                }
            }

            if (showMisc) {
                SettingsCustomizationSection(
                    onInfoClick = { infoKey = it }
                )
                SettingsDivider()
                SettingsAboutSection(
                    onOpenAbout = onOpenAbout,
                    onOpenTelegramChannel = onOpenTelegramChannel,
                    onDonateClick = { showDonationDialog = true },
                    onOpenUpdate = onOpenUpdate,
                    onOpenHallOfFame = onOpenHallOfFame
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        SettingsTopBar(
            selectedCategory = selectedCategory,
            onSelectCategory = { selectedCategory = it },
            onBack = onBack,
            isAdvancedMode = isAdvancedMode,
            onToggleAdvancedMode = toggleAdvancedMode,
            modifier = langBlurModifier
        )

        CyberParticlesOverlay(
            modifier = Modifier.fillMaxSize(),
            particleCount = 14,
            alphaMultiplier = 0.50f
        )

        if (showDonationDialog) {
            DonationDialog(
                onDismiss = { showDonationDialog = false }
            )
        }

        if (showSleepTimerDialog) {
            SleepTimerDialog(
                initialTab = sleepTimerInitialTab,
                onDismiss = { showSleepTimerDialog = false }
            )
        }

        if (showWarpRegistrationDialog) {
            WarpRegistrationDialog(
                initialProfile = warpProfile,
                workerDomain = com.mirrly.tgproxy.core.WarpAccountManager.REGISTRATION_WORKER_DOMAIN,
                initialLicenseKey = config.warpLicenseKey,
                startRegistrationImmediately = warpDialogStartRegistrationImmediately,
                onProfileSaved = { newProf ->
                    app.prefsManager.saveWarpProfile(newProf)
                    warpProfile = newProf
                    config.applyWarpProfile(newProf)
                    app.saveConfig()
                    restartProxyIfNeeded()
                },
                onDismiss = {
                    showWarpRegistrationDialog = false
                }
            )
        }

        if (showCfAuthDialog) {
            Socks5CloudflareAuthDialog(
                onDismiss = { showCfAuthDialog = false },
                onAuthSuccess = {
                    showCfAuthDialog = false
                    showOnboardingDialog = true
                },
                onCustomWorkerSaved = { domain ->
                    showCfAuthDialog = false
                    app.config.customCfDomain = domain
                    app.config.useDefaultWorkerSocks5 = false
                    app.prefsManager.saveConfig(app.config)
                    app.prefsManager.setUseWorkerMtproto(true)
                },
                onOpenManualGuide = {
                    showCfAuthDialog = false
                    onOpenWorkerGuide()
                }
            )
        }

        if (showOnboardingDialog) {
            Socks5OnboardingDialog(
                onDismiss = {
                    showOnboardingDialog = false
                    if (app.prefsManager.getActiveWorker(com.mirrly.tgproxy.core.ProxyMode.MTPROTO).domain.isNotBlank()) {
                        app.prefsManager.setUseWorkerMtproto(true)
                    }
                },
                onOpenWorkerManager = {
                    showOnboardingDialog = false
                    app.prefsManager.setSocks5OnboardingCompleted(true)
                    app.prefsManager.setUseWorkerMtproto(true)
                    onOpenWorkerManager(com.mirrly.tgproxy.core.ProxyMode.MTPROTO)
                }
            )
        }

        if (showVpnOnboardingDialog) {
            VpnOnboardingDialog(
                onDismiss = { showVpnOnboardingDialog = false },
                onCreateProfileAuto = {
                    showVpnOnboardingDialog = false
                    onOpenWarpGenerator()
                },
                onOpenImport = {
                    showVpnOnboardingDialog = false
                    warpDialogStartRegistrationImmediately = false
                    showWarpRegistrationDialog = true
                },
                onOpenGenerator = onOpenWarpGenerator
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTopBar(
    selectedCategory: SettingsCategory,
    onSelectCategory: (SettingsCategory) -> Unit,
    onBack: () -> Unit,
    isAdvancedMode: Boolean = false,
    onToggleAdvancedMode: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val accent = ActiveGreenLed

    Column(
        modifier = Modifier
            .then(modifier)
            .fillMaxWidth()
            .background(
                brush = Brush.verticalGradient(
                    0.0f to Color.Black,
                    0.82f to Color.Black,
                    1.0f to Color.Transparent
                )
            )
            .padding(bottom = 14.dp)
    ) {
        TopAppBar(
            title = {
                Text(
                    text = stringResource(R.string.action_settings),
                    color = TextWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            },
            navigationIcon = {
                IconButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onBack()
                }) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_arrow_left),
                        contentDescription = stringResource(R.string.action_back),
                        tint = TextWhite,
                        modifier = Modifier.size(22.dp)
                    )
                }
            },
            actions = {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isAdvancedMode) ActiveGreenLed.copy(alpha = 0.14f) else Color.Transparent,
                    border = BorderStroke(1.dp, if (isAdvancedMode) ActiveGreenLed.copy(alpha = 0.5f) else AmoledBorder),
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onToggleAdvancedMode(!isAdvancedMode)
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (isAdvancedMode) ActiveGreenLed else InactiveGrayLed)
                        )
                        Text(
                            text = if (isAdvancedMode) stringResource(R.string.settings_mode_expert) else stringResource(R.string.settings_mode_simple),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.8.sp,
                            color = if (isAdvancedMode) ActiveGreenLed else TextMuted
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SettingsCategory.values().forEach { category ->
                val isSelected = selectedCategory == category
                val chipBg by animateColorAsState(
                    targetValue = if (isSelected) accent.copy(alpha = 0.14f) else Color.Transparent,
                    animationSpec = tween(180),
                    label = "catBg_${category.name}"
                )
                val chipBorder by animateColorAsState(
                    targetValue = if (isSelected) accent.copy(alpha = 0.65f) else AmoledBorder,
                    animationSpec = tween(180),
                    label = "catBorder_${category.name}"
                )
                val chipText by animateColorAsState(
                    targetValue = if (isSelected) accent else TextMuted,
                    animationSpec = tween(180),
                    label = "catText_${category.name}"
                )

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = chipBg,
                    border = BorderStroke(1.dp, chipBorder),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelectCategory(category)
                        }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 7.dp, horizontal = 1.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(category.titleRes),
                            fontSize = 10.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = chipText,
                            maxLines = 1,
                            softWrap = false,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsProtocolSection(
    activeMode: SettingsProtocolMode,
    isSwitching: Boolean,
    vpnColors: ProtocolColors,
    onInfoClick: () -> Unit,
    onModeSelect: (SettingsProtocolMode) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    Column(
        modifier = Modifier.staggeredEntrance(index = 0),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = stringResource(R.string.settings_protocol_title),
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.3.sp,
                color = TextMuted
            )
            InfoButton { onInfoClick() }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SettingsProtocolMode.values().forEach { mode ->
                val isSelected = activeMode == mode
                val modeAccent = when (mode) {
                    SettingsProtocolMode.MTPROTO -> MtprotoAccent
                    SettingsProtocolMode.SOCKS5  -> Socks5Accent
                    SettingsProtocolMode.VPN     -> vpnColors.primary
                }
                val chipBorder by animateColorAsState(
                    targetValue = if (isSelected) modeAccent else AmoledBorder,
                    animationSpec = tween(200),
                    label = "modeBorder_${mode.name}"
                )
                val chipTextColor by animateColorAsState(
                    targetValue = if (isSelected) modeAccent else TextWhite,
                    animationSpec = tween(200),
                    label = "modeText_${mode.name}"
                )
                val chipBgColor by animateColorAsState(
                    targetValue = if (isSelected) modeAccent.copy(alpha = 0.08f) else Color.Transparent,
                    animationSpec = tween(200),
                    label = "modeBg_${mode.name}"
                )

                val modeLabel = when (mode) {
                    SettingsProtocolMode.MTPROTO -> "MTProto"
                    SettingsProtocolMode.SOCKS5  -> stringResource(R.string.mode_socks5_beta)
                    SettingsProtocolMode.VPN     -> stringResource(R.string.mode_vpn_in_dev)
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(chipBgColor)
                        .border(1.dp, chipBorder, RoundedCornerShape(12.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            if (isSwitching || activeMode == mode) return@clickable
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onModeSelect(mode)
                        }
                        .padding(horizontal = 4.dp, vertical = 11.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = modeLabel,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = chipTextColor,
                        maxLines = 1,
                        softWrap = false,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsVpnStatusOverviewSection(
    vpnColors: ProtocolColors,
    vpnState: com.mirrly.tgproxy.ui.theme.VpnUiState,
    onOpenVpnDialog: () -> Unit,
    onInfoClick: (String) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val vpnStatus by com.mirrly.tgproxy.service.MirrlyVpnService.vpnStatus.collectAsState()

    Column(
        modifier = Modifier.staggeredEntrance(index = 1),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.vpn_system_title_dev),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.3.sp,
                    color = TextMuted
                )
                InfoButton { onInfoClick("vpn_core_info") }
            }
        }

        val vpnWarnColor = Color(0xFFFFB74D)
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = vpnWarnColor.copy(alpha = 0.08f),
            border = BorderStroke(1.dp, vpnWarnColor.copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(vpnWarnColor.copy(alpha = 0.15f))
                        .border(1.dp, vpnWarnColor.copy(alpha = 0.35f), CircleShape)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_vpn),
                        contentDescription = null,
                        tint = vpnWarnColor,
                        modifier = Modifier.size(13.dp)
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    Text(
                        text = stringResource(R.string.vpn_maintenance_notice_title),
                        color = vpnWarnColor,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.vpn_maintenance_notice_desc),
                        color = TextWhite.copy(alpha = 0.85f),
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 13.5.sp
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Transparent)
                .border(
                    1.dp,
                    if (vpnState == com.mirrly.tgproxy.ui.theme.VpnUiState.CONNECTED) ActiveGreenLed.copy(alpha = 0.45f) else AmoledBorder,
                    RoundedCornerShape(18.dp)
                )
        ) {
            Column {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.vpn_tunnel_title),
                        color = TextWhite,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.5.sp
                    )
                    Text(
                        text = stringResource(R.string.vpn_tunnel_desc),
                        color = TextMuted,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp
                    )
                }

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AmoledBorder))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(text = stringResource(R.string.vpn_active_flows_label), color = TextMuted, fontSize = 11.sp)
                        Text(
                            text = "${vpnStatus.activeTcpFlows} TCP  •  ${vpnStatus.activeUdpSessions} UDP",
                            color = TextWhite,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(text = stringResource(R.string.vpn_network_gen_label), color = TextMuted, fontSize = 11.sp)
                        Text(
                            text = stringResource(R.string.vpn_network_gen_format, vpnStatus.generation),
                            color = vpnColors.primary,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AmoledBorder))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = vpnColors.primary.copy(alpha = 0.10f),
                        border = BorderStroke(1.dp, vpnColors.primary.copy(alpha = 0.35f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .springPress(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onOpenVpnDialog()
                                }
                            )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 11.dp, horizontal = 14.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.vpn_select_protocol_btn),
                                color = vpnColors.primary,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsVpnSplitTunnelSection(
    vpnColors: ProtocolColors,
    isEnabled: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    isAllowlist: Boolean,
    onToggleAllowlist: (Boolean) -> Unit,
    packageCount: Int,
    onOpenAppPicker: () -> Unit,
    onInfoClick: (String) -> Unit
) {
    val haptic = LocalHapticFeedback.current

    Column(
        modifier = Modifier.staggeredEntrance(index = 2),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f, fill = false),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.vpn_splittunnel_title).uppercase(),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.3.sp,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                InfoButton { onInfoClick("vpn_splittunnel_info") }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Transparent)
                .border(1.dp, AmoledBorder, RoundedCornerShape(18.dp))
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = stringResource(R.string.vpn_splittunnel_title),
                            color = TextWhite,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.5.sp
                        )
                        Text(
                            text = stringResource(R.string.vpn_splittunnel_desc),
                            color = TextMuted,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                    InertialSpringSwitch(
                        checked = isEnabled,
                        onCheckedChange = onToggleEnabled,
                        activeColor = vpnColors.primary
                    )
                }

                if (isEnabled) {
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AmoledBorder))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.vpn_app_filter_mode_label),
                            color = TextMuted,
                            fontSize = 11.sp
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val isDisallow = !isAllowlist
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isDisallow) vpnColors.primary.copy(alpha = 0.15f) else Color.Transparent,
                                border = BorderStroke(1.dp, if (isDisallow) vpnColors.primary.copy(alpha = 0.5f) else AmoledBorder),
                                modifier = Modifier
                                    .weight(1f)
                                    .springPress(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onToggleAllowlist(false)
                                        }
                                    )
                            ) {
                                Text(
                                    text = stringResource(R.string.vpn_split_tunnel_mode_disallow),
                                    color = if (isDisallow) vpnColors.primary else TextMuted,
                                    fontSize = 11.sp,
                                    fontWeight = if (isDisallow) FontWeight.Bold else FontWeight.Normal,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(vertical = 7.dp)
                                )
                            }

                            val isAllow = isAllowlist
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isAllow) vpnColors.primary.copy(alpha = 0.15f) else Color.Transparent,
                                border = BorderStroke(1.dp, if (isAllow) vpnColors.primary.copy(alpha = 0.5f) else AmoledBorder),
                                modifier = Modifier
                                    .weight(1f)
                                    .springPress(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onToggleAllowlist(true)
                                        }
                                    )
                            ) {
                                Text(
                                    text = stringResource(R.string.vpn_split_tunnel_mode_allow),
                                    color = if (isAllow) vpnColors.primary else TextMuted,
                                    fontSize = 11.sp,
                                    fontWeight = if (isAllow) FontWeight.Bold else FontWeight.Normal,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(vertical = 7.dp)
                                )
                            }
                        }
                    }

                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AmoledBorder))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = vpnColors.primary.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, vpnColors.primary.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .springPress(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onOpenAppPicker()
                                    }
                                )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 11.dp, horizontal = 14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.vpn_split_tunnel_apps_btn, packageCount),
                                    color = TextWhite,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = stringResource(R.string.action_configure_arrow),
                                    color = vpnColors.primary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}


private data class InstalledAppItem(
    val packageName: String,
    val label: String,
    val icon: android.graphics.drawable.Drawable?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitTunnelAppsDialog(
    vpnColors: ProtocolColors,
    selectedPackages: Set<String>,
    onSave: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var currentSelection by remember { mutableStateOf(selectedPackages) }
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var installedApps by remember { mutableStateOf<List<InstalledAppItem>>(emptyList()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(mainIntent, 0)
            }
            val ownPkg = context.packageName
            val list = resolveInfos
                .mapNotNull { it.activityInfo?.applicationInfo }
                .filter { it.packageName != ownPkg }
                .distinctBy { it.packageName }
                .map { appInfo ->
                    InstalledAppItem(
                        packageName = appInfo.packageName,
                        label = appInfo.loadLabel(pm).toString(),
                        icon = try { appInfo.loadIcon(pm) } catch (_: Throwable) { null }
                    )
                }
                .sortedBy { it.label.lowercase() }
            withContext(Dispatchers.Main) {
                installedApps = list
                isLoading = false
            }
        }
    }

    val filteredApps = remember(installedApps, searchQuery) {
        if (searchQuery.isBlank()) installedApps
        else {
            val q = searchQuery.trim().lowercase()
            installedApps.filter {
                it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            }
        }
    }

    MirrlyModalBottomSheet(
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.vpn_split_tunnel_dialog_title),
                        color = TextWhite,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.vpn_selected_count, currentSelection.size, installedApps.size),
                        color = vpnColors.primary,
                        fontSize = 11.5.sp
                    )
                }
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDismiss()
                    }
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_close),
                        contentDescription = stringResource(R.string.action_cancel),
                        tint = TextMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, AmoledBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        textStyle = TextStyle(color = TextWhite, fontSize = 13.sp),
                        modifier = Modifier.weight(1f),
                        decorationBox = { innerTextField ->
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.vpn_split_tunnel_search_hint),
                                    color = TextMuted,
                                    fontSize = 13.sp
                                )
                            }
                            innerTextField()
                        }
                    )
                    if (searchQuery.isNotEmpty()) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_close),
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { searchQuery = "" }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, AmoledBorder),
                    modifier = Modifier
                        .weight(1f)
                        .springPress(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val allPkgs = installedApps.map { it.packageName }.toSet()
                                currentSelection = allPkgs
                            }
                        )
                ) {
                    Text(
                        text = stringResource(R.string.vpn_split_tunnel_select_all),
                        color = TextMuted,
                        fontSize = 11.5.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 7.dp)
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, AmoledBorder),
                    modifier = Modifier
                        .weight(1f)
                        .springPress(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                currentSelection = emptySet()
                            }
                        )
                ) {
                    Text(
                        text = stringResource(R.string.vpn_split_tunnel_deselect_all),
                        color = TextMuted,
                        fontSize = 11.5.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 7.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = vpnColors.primary, strokeWidth = 2.dp)
                }
            } else {
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp)
                        .fadingEdges(topFadeHeight = 12.dp, bottomFadeHeight = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(filteredApps.size, key = { filteredApps[it].packageName }) { idx ->
                        val app = filteredApps[idx]
                        val isChecked = currentSelection.contains(app.packageName)

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isChecked) vpnColors.primary.copy(alpha = 0.08f) else Color.Transparent,
                            border = BorderStroke(1.dp, if (isChecked) vpnColors.primary.copy(alpha = 0.35f) else Color.Transparent),
                            modifier = Modifier
                                .fillMaxWidth()
                                .springPress(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        currentSelection = if (isChecked) {
                                            currentSelection - app.packageName
                                        } else {
                                            currentSelection + app.packageName
                                        }
                                    }
                                )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                val iconBitmap = remember(app.packageName) {
                                    app.icon?.let { d ->
                                        try {
                                            val w = if (d.intrinsicWidth > 0) d.intrinsicWidth else 72
                                            val h = if (d.intrinsicHeight > 0) d.intrinsicHeight else 72
                                            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                                            val c = android.graphics.Canvas(bmp)
                                            d.setBounds(0, 0, w, h)
                                            d.draw(c)
                                            bmp.asImageBitmap()
                                        } catch (_: Throwable) {
                                            null
                                        }
                                    }
                                }

                                if (iconBitmap != null) {
                                    androidx.compose.foundation.Image(
                                        bitmap = iconBitmap,
                                        contentDescription = null,
                                        modifier = Modifier.size(32.dp).clip(RoundedCornerShape(6.dp))
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(AmoledSurfaceHigh),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = app.label.take(1).uppercase(),
                                            color = TextMuted,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = app.label,
                                        color = TextWhite,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = app.packageName,
                                        color = TextMuted,
                                        fontSize = 10.5.sp,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }

                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { checked ->
                                        currentSelection = if (checked) {
                                            currentSelection + app.packageName
                                        } else {
                                            currentSelection - app.packageName
                                        }
                                    },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = vpnColors.primary,
                                        uncheckedColor = AmoledBorder,
                                        checkmarkColor = Color.Black
                                    )
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = vpnColors.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .springPress(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSave(currentSelection)
                        }
                    )
            ) {
                Text(
                    text = stringResource(R.string.vpn_split_tunnel_apply),
                    color = Color.Black,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SettingsNetworkSection(
    selectedMode: ProxyMode,
    isAdvancedMode: Boolean = false,
    onEnableAdvancedMode: () -> Unit = {},
    portText: String,
    onPortChange: (String) -> Unit,
    isPortError: Boolean,
    socks5PortText: String,
    onSocks5PortChange: (String) -> Unit,
    isSocks5PortError: Boolean,
    socks5UserText: String,
    onSocks5UserChange: (String) -> Unit,
    socks5PassText: String,
    onSocks5PassChange: (String) -> Unit,
    showSocks5Pass: Boolean,
    onToggleShowSocks5Pass: () -> Unit,
    onGenerateSocks5Auth: () -> Unit,
    onClearSocks5Auth: () -> Unit,
    secretText: String,
    onSecretChange: (String) -> Unit,
    showSecret: Boolean,
    onToggleShowSecret: () -> Unit,
    onRefreshSecret: () -> Unit,
    socks5Optimistic: Boolean = true,
    onToggleSocks5Optimistic: (Boolean) -> Unit = {},
    onInfoClick: (String) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val app = MirrlyApplication.instance
    Column(
        modifier = Modifier.staggeredEntrance(index = 1),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.settings_network_title),
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.3.sp,
                color = TextMuted
            )
        }

        if (selectedMode == ProxyMode.MTPROTO) {
            if (!isAdvancedMode) {
                // Friendly Safe Summary Card for MTProto
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.Transparent)
                        .border(1.dp, AmoledBorder, RoundedCornerShape(18.dp))
                        .padding(14.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.settings_port_mtproto),
                                        color = TextWhite,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.5.sp
                                    )
                                    InfoButton { onInfoClick("port") }
                                }
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = stringResource(R.string.settings_port_safe_desc, portText.toIntOrNull() ?: 10808),
                                    color = TextMuted,
                                    fontSize = 11.5.sp,
                                    lineHeight = 16.sp
                                )
                            }
                        }

                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AmoledBorder.copy(alpha = 0.5f)))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.settings_secret_hex),
                                color = TextWhite,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.5.sp
                            )
                            InfoButton { onInfoClick("secret") }
                        }
                        Text(
                            text = stringResource(R.string.settings_secret_safe_desc),
                            color = TextMuted,
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            } else {
                // Interactive Fields for Expert Mode
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.Transparent)
                        .border(1.dp, AmoledBorder, RoundedCornerShape(18.dp))
                ) {
                    Column {
                        // 1. PORT MTProto
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(stringResource(R.string.settings_port_mtproto), color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                                    InfoButton { onInfoClick("port") }
                                }
                                Text(
                                    text = if (isPortError) stringResource(R.string.settings_port_range_hint) else stringResource(R.string.settings_port_mtproto_desc),
                                    color = if (isPortError) Color(0xFFEF4444) else TextMuted,
                                    fontSize = 11.5.sp
                                )
                            }

                            BasicTextField(
                                value = portText,
                                onValueChange = onPortChange,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                textStyle = TextStyle(
                                    color = if (isPortError) Color(0xFFEF4444) else TextWhite,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    textAlign = TextAlign.Center
                                ),
                                cursorBrush = SolidColor(if (isPortError) Color(0xFFEF4444) else ActiveGreenLed),
                                decorationBox = { innerTextField ->
                                    Box(
                                        modifier = Modifier
                                            .width(76.dp)
                                            .height(34.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(Color.Transparent)
                                            .border(
                                                width = 1.dp,
                                                color = if (isPortError) Color(0xFFEF4444) else AmoledBorder,
                                                shape = RoundedCornerShape(10.dp)
                                            )
                                            .padding(horizontal = 6.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        innerTextField()
                                    }
                                }
                            )
                        }

                        // Divider
                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AmoledBorder))

                        // 2. SECRET KEY
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(stringResource(R.string.settings_secret_hex), color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                                    InfoButton { onInfoClick("secret") }
                                }

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(30.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color.Transparent)
                                            .border(1.dp, AmoledBorder, RoundedCornerShape(8.dp))
                                            .springPress(onClick = {
                                                onToggleShowSecret()
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Crossfade(targetState = showSecret, animationSpec = tween(180), label = "eyeFade") { isVisible ->
                                            Icon(
                                                painter = painterResource(id = if (isVisible) R.drawable.ic_eye_slash else R.drawable.ic_eye),
                                                contentDescription = null,
                                                tint = if (showSecret) ActiveGreenLed else TextMuted,
                                                modifier = Modifier.size(15.dp)
                                            )
                                        }
                                    }

                                    Box(
                                        modifier = Modifier
                                            .size(30.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color.Transparent)
                                            .border(1.dp, AmoledBorder, RoundedCornerShape(8.dp))
                                            .springPress(onClick = {
                                                onRefreshSecret()
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            }),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_refresh),
                                            contentDescription = stringResource(R.string.settings_btn_change_secret),
                                            tint = TextWhite,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }

                            BasicTextField(
                                value = secretText,
                                onSecretChange,
                                singleLine = true,
                                readOnly = true,
                                visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                                textStyle = TextStyle(
                                    color = TextWhite,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 0.5.sp
                                ),
                                cursorBrush = SolidColor(ActiveGreenLed),
                                decorationBox = { innerTextField ->
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(34.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(Color.Transparent)
                                            .border(1.dp, AmoledBorder, RoundedCornerShape(10.dp))
                                            .padding(horizontal = 10.dp),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        innerTextField()
                                    }
                                }
                            )
                        }
                    }
                }
            }
        } else {
            // SOCKS5 Mode Settings
            if (!isAdvancedMode) {
                // Friendly Safe Summary Card for SOCKS5
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.Transparent)
                        .border(1.dp, AmoledBorder, RoundedCornerShape(18.dp))
                        .padding(14.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.settings_port_socks5),
                                        color = TextWhite,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.5.sp
                                    )
                                    InfoButton { onInfoClick("port") }
                                }
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = stringResource(R.string.settings_socks5_safe_desc, socks5PortText.toIntOrNull() ?: 10808),
                                    color = TextMuted,
                                    fontSize = 11.5.sp,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }
                }
            } else {
                // Interactive Fields for Expert SOCKS5 Mode
                // 1. CARD PORT SOCKS5
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.Transparent)
                        .border(1.dp, AmoledBorder, RoundedCornerShape(18.dp))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(stringResource(R.string.settings_port_socks5), color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                                InfoButton { onInfoClick("port") }
                            }
                            Text(
                                text = if (isSocks5PortError) stringResource(R.string.settings_port_range_hint) else stringResource(R.string.settings_port_socks5_desc),
                                color = if (isSocks5PortError) Color(0xFFEF4444) else TextMuted,
                                fontSize = 11.5.sp
                            )
                        }

                        BasicTextField(
                            value = socks5PortText,
                            onValueChange = onSocks5PortChange,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            textStyle = TextStyle(
                                color = if (isSocks5PortError) Color(0xFFEF4444) else TextWhite,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                textAlign = TextAlign.Center
                            ),
                            cursorBrush = SolidColor(if (isSocks5PortError) Color(0xFFEF4444) else Socks5Accent),
                            decorationBox = { innerTextField ->
                                Box(
                                    modifier = Modifier
                                        .width(76.dp)
                                        .height(34.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.Transparent)
                                        .border(
                                            width = 1.dp,
                                            color = if (isSocks5PortError) Color(0xFFEF4444) else AmoledBorder,
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .padding(horizontal = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    innerTextField()
                                }
                            }
                        )
                    }
                }

                // 2. CARD SOCKS5 AUTH (RFC 1929)
                val hasAuth = socks5UserText.isNotBlank() || socks5PassText.isNotBlank()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.Transparent)
                        .border(BorderStroke(1.dp, AmoledBorder), RoundedCornerShape(18.dp))
                ) {
                    Column {
                        // Header
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(stringResource(R.string.settings_socks5_auth_title), color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                                InfoButton { onInfoClick("socks5_auth") }
                            }
                        }

                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AmoledBorder))

                        // Username Row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.settings_login),
                                color = TextMuted,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.width(62.dp)
                            )
                            BasicTextField(
                                value = socks5UserText,
                                onValueChange = onSocks5UserChange,
                                singleLine = true,
                                textStyle = TextStyle(
                                    color = TextWhite,
                                    fontSize = 12.5.sp,
                                    fontFamily = FontFamily.Monospace
                                ),
                                cursorBrush = SolidColor(Socks5Accent),
                                modifier = Modifier.weight(1f),
                                decorationBox = { innerTextField ->
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(32.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color.Transparent)
                                            .border(1.dp, AmoledBorder, RoundedCornerShape(8.dp))
                                            .padding(horizontal = 9.dp),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        if (socks5UserText.isEmpty()) {
                                            Text(stringResource(R.string.settings_login_open_desc), color = TextMuted.copy(alpha = 0.6f), fontSize = 12.sp)
                                        }
                                        innerTextField()
                                    }
                                }
                            )
                        }

                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AmoledBorder))

                        // Password Row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.settings_password),
                                color = TextMuted,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.width(62.dp)
                            )
                            BasicTextField(
                                value = socks5PassText,
                                onValueChange = onSocks5PassChange,
                                singleLine = true,
                                visualTransformation = if (showSocks5Pass) VisualTransformation.None else PasswordVisualTransformation(),
                                textStyle = TextStyle(
                                    color = TextWhite,
                                    fontSize = 12.5.sp,
                                    fontFamily = FontFamily.Monospace
                                ),
                                cursorBrush = SolidColor(Socks5Accent),
                                modifier = Modifier.weight(1f),
                                decorationBox = { innerTextField ->
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(32.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color.Transparent)
                                            .border(1.dp, AmoledBorder, RoundedCornerShape(8.dp))
                                            .padding(start = 9.dp, end = 4.dp),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Box(modifier = Modifier.weight(1f)) {
                                                if (socks5PassText.isEmpty()) {
                                                    Text(stringResource(R.string.settings_password_open_desc), color = TextMuted.copy(alpha = 0.6f), fontSize = 12.sp)
                                                }
                                                innerTextField()
                                            }
                                            IconButton(
                                                onClick = {
                                                    onToggleShowSocks5Pass()
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Crossfade(targetState = showSocks5Pass, animationSpec = tween(180), label = "socks5EyeFade") { isVisible ->
                                                    Icon(
                                                        painter = painterResource(id = if (isVisible) R.drawable.ic_eye_slash else R.drawable.ic_eye),
                                                        contentDescription = null,
                                                        tint = if (isVisible) Socks5Accent else TextMuted,
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            )
                        }

                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AmoledBorder))

                        // Compact Actions (Outline-Only springPress)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(34.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .border(1.dp, Socks5Accent.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                                    .springPress(onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onGenerateSocks5Auth()
                                    }),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_btn_generate_creds),
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Socks5Accent
                                )
                            }

                            if (hasAuth) {
                                Box(
                                    modifier = Modifier
                                        .height(34.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .border(1.dp, AmoledBorder, RoundedCornerShape(10.dp))
                                        .springPress(onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onClearSocks5Auth()
                                        })
                                        .padding(horizontal = 14.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = stringResource(R.string.settings_btn_clear),
                                        color = TextWhite,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // SOCKS5 Optimistic Early Response Card (Zero-Popup Expandable)
            SettingsSocks5OptimisticCard(
                isOptimistic = socks5Optimistic,
                onToggleOptimistic = onToggleSocks5Optimistic
            )
        }

        val forceMobileMode by app.prefsManager.forceMobileModeFlow.collectAsState()
        SettingsForceMobileCard(
            isForceMobile = forceMobileMode,
            onToggleForceMobile = { checked ->
                app.prefsManager.setForceMobileModeEnabled(checked)
            }
        )

        val dialThrottling by app.prefsManager.dialThrottlingFlow.collectAsState()
        SettingsDialThrottlingCard(
            isThrottling = dialThrottling,
            onToggleThrottling = { checked ->
                app.prefsManager.setDialThrottlingEnabled(checked)
            }
        )
    }
}

/**
 * Zero-Popup Outline Card for selecting SOCKS5 routing uplink.
 * Options:
 * 0 - Cloudflare Worker (Custom or default)
 * 1 - Anycast CDN Flowseal (Direct WSS tunneling)
 */
@Composable
private fun SettingsSocks5UplinkCard(
    uplinkMode: Int,
    onSelectUplinkMode: (Int) -> Unit
) {
    val haptic = LocalHapticFeedback.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Transparent)
            .border(1.dp, AmoledBorder, RoundedCornerShape(18.dp))
            .padding(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.settings_socks5_uplink_title),
                    color = TextWhite,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.5.sp
                )
                Text(
                    text = stringResource(R.string.settings_socks5_uplink_desc),
                    color = TextMuted,
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Option 0: Cloudflare Worker
                val isWorkerSelected = (uplinkMode == 0)
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isWorkerSelected) Socks5Accent.copy(alpha = 0.10f) else Color.Transparent,
                    border = BorderStroke(1.dp, if (isWorkerSelected) Socks5Accent else AmoledBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSelectUplinkMode(0)
                        }
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.settings_socks5_uplink_worker_title),
                            fontSize = 12.5.sp,
                            fontWeight = if (isWorkerSelected) FontWeight.Bold else FontWeight.SemiBold,
                            color = if (isWorkerSelected) TextWhite else TextWhite.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        Text(
                            text = stringResource(R.string.settings_socks5_uplink_worker_subtitle),
                            fontSize = 10.5.sp,
                            color = if (isWorkerSelected) Socks5Accent.copy(alpha = 0.85f) else TextMuted,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }

                // Option 1: Anycast CDN Flowseal
                val isFlowsealSelected = (uplinkMode == 1)
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isFlowsealSelected) Socks5Accent.copy(alpha = 0.10f) else Color.Transparent,
                    border = BorderStroke(1.dp, if (isFlowsealSelected) Socks5Accent else AmoledBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSelectUplinkMode(1)
                        }
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.settings_socks5_uplink_flowseal_title),
                            fontSize = 12.5.sp,
                            fontWeight = if (isFlowsealSelected) FontWeight.Bold else FontWeight.SemiBold,
                            color = if (isFlowsealSelected) TextWhite else TextWhite.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        Text(
                            text = stringResource(R.string.settings_socks5_uplink_flowseal_subtitle),
                            fontSize = 10.5.sp,
                            color = if (isFlowsealSelected) Socks5Accent.copy(alpha = 0.85f) else TextMuted,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Footnote info box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Transparent)
                    .border(1.dp, Socks5Accent.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_socks5_uplink_footnote),
                    color = TextMuted,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        }
    }
}

/**
 * Inline expandable card for SOCKS5 optimistic early response (0x00 Fallback).
 * Adheres strictly to MirrlyUI (Zero-Popup, Outline-Only, AMOLED black, Socks5Accent).
 */
@Composable
private fun SettingsSocks5OptimisticCard(
    isOptimistic: Boolean,
    onToggleOptimistic: (Boolean) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var isExpanded by remember { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "optimisticChevron"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Transparent)
            .border(1.dp, AmoledBorder, RoundedCornerShape(18.dp))
            .padding(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            isExpanded = !isExpanded
                        }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.settings_socks5_optimistic_title),
                            color = TextWhite,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.5.sp
                        )
                        Icon(
                            painter = painterResource(id = R.drawable.ic_chevron_right),
                            contentDescription = null,
                            tint = if (isExpanded) Socks5Accent else TextMuted,
                            modifier = Modifier
                                .size(13.dp)
                                .graphicsLayer { rotationZ = chevronRotation }
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.settings_socks5_optimistic_desc),
                        color = TextMuted,
                        fontSize = 11.5.sp,
                        lineHeight = 15.sp
                    )
                }

                InertialSpringSwitch(
                    checked = isOptimistic,
                    onCheckedChange = { checked ->
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (!checked) {
                            isExpanded = true
                        }
                        onToggleOptimistic(checked)
                    },
                    activeColor = Socks5Accent
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(AmoledBorder)
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Transparent)
                            .border(1.dp, Socks5Accent.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = stringResource(R.string.settings_socks5_optimistic_card_title),
                                color = Socks5Accent,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp
                            )
                            Text(
                                text = stringResource(R.string.settings_socks5_optimistic_card_desc),
                                color = TextMuted,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Inline expandable card for Force Mobile / Hotspot Mode.
 * Adheres strictly to MirrlyUI (Zero-Popup, Outline-Only, AMOLED black).
 */
@Composable
private fun SettingsForceMobileCard(
    isForceMobile: Boolean,
    onToggleForceMobile: (Boolean) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var isExpanded by remember { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "forceMobileChevron"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Transparent)
            .border(1.dp, AmoledBorder, RoundedCornerShape(18.dp))
            .padding(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            isExpanded = !isExpanded
                        }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.settings_force_mobile_title),
                            color = TextWhite,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.5.sp
                        )
                        Icon(
                            painter = painterResource(id = R.drawable.ic_chevron_right),
                            contentDescription = null,
                            tint = if (isExpanded) ActiveGreenLed else TextMuted,
                            modifier = Modifier
                                .size(13.dp)
                                .graphicsLayer { rotationZ = chevronRotation }
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.settings_force_mobile_desc),
                        color = TextMuted,
                        fontSize = 11.5.sp,
                        lineHeight = 15.sp
                    )
                }

                InertialSpringSwitch(
                    checked = isForceMobile,
                    onCheckedChange = { checked ->
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onToggleForceMobile(checked)
                    }
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(animationSpec = tween(200)) + fadeIn(animationSpec = tween(200)),
                exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(animationSpec = tween(200))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Transparent)
                        .border(1.dp, ActiveGreenLed.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = stringResource(R.string.settings_force_mobile_card_title),
                            color = ActiveGreenLed,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                        Text(
                            text = stringResource(R.string.settings_force_mobile_card_desc),
                            color = TextMuted,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                }
            }
        }
    }
}

/**
 * Inline expandable card for cellular dial throttling (bufferbloat mitigation on tethered hotspots).
 * Adheres strictly to MirrlyUI (Zero-Popup, Outline-Only, AMOLED black).
 */
@Composable
private fun SettingsDialThrottlingCard(
    isThrottling: Boolean,
    onToggleThrottling: (Boolean) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var isExpanded by remember { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "dialThrottlingChevron"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Transparent)
            .border(1.dp, AmoledBorder, RoundedCornerShape(18.dp))
            .padding(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            isExpanded = !isExpanded
                        }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.settings_dial_throttling_title),
                            color = TextWhite,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.5.sp
                        )
                        Icon(
                            painter = painterResource(id = R.drawable.ic_chevron_right),
                            contentDescription = null,
                            tint = if (isExpanded) ActiveGreenLed else TextMuted,
                            modifier = Modifier
                                .size(13.dp)
                                .graphicsLayer { rotationZ = chevronRotation }
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.settings_dial_throttling_desc),
                        color = TextMuted,
                        fontSize = 11.5.sp,
                        lineHeight = 15.sp
                    )
                }

                InertialSpringSwitch(
                    checked = isThrottling,
                    onCheckedChange = { checked ->
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onToggleThrottling(checked)
                    }
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(animationSpec = tween(200)) + fadeIn(animationSpec = tween(200)),
                exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(animationSpec = tween(200))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Transparent)
                        .border(1.dp, ActiveGreenLed.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = stringResource(R.string.settings_dial_throttling_card_title),
                            color = ActiveGreenLed,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                        Text(
                            text = stringResource(R.string.settings_dial_throttling_card_desc),
                            color = TextMuted,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsPerformanceSection(
    config: ProxyConfig,
    selectedSpeedPresetName: String,
    onPresetSelect: (com.mirrly.tgproxy.core.SpeedPreset) -> Unit,
    onInfoClick: (String) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val app = MirrlyApplication.instance
    val server = app.proxyServer
    val transportPoolStatus by server.transportPoolStatus.collectAsState()
    val adaptiveDecision by server.adaptiveNetworkDecision.collectAsState()

    Column(
        modifier = Modifier.staggeredEntrance(index = 2),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_perf_title),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.3.sp,
                    color = TextMuted
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(stringResource(R.string.settings_wspool_modes), color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                InfoButton { onInfoClick("preset") }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                com.mirrly.tgproxy.core.SpeedPreset.values().forEach { preset ->
                    val isSelected = selectedSpeedPresetName == preset.name
                    val chipBorder by animateColorAsState(
                        targetValue = if (isSelected) ActiveGreenLed else AmoledBorder,
                        animationSpec = tween(200),
                        label = "presetBorder_${preset.name}"
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Transparent)
                            .border(1.dp, chipBorder, RoundedCornerShape(12.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onPresetSelect(preset)
                            }
                            .padding(vertical = 9.dp, horizontal = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val iconRes = when (preset) {
                                com.mirrly.tgproxy.core.SpeedPreset.ECO -> R.drawable.ic_speed_eco
                                com.mirrly.tgproxy.core.SpeedPreset.BALANCED -> R.drawable.ic_speed_balanced
                                com.mirrly.tgproxy.core.SpeedPreset.TURBO -> R.drawable.ic_speed_turbo
                                com.mirrly.tgproxy.core.SpeedPreset.ULTRA -> R.drawable.ic_speed_ultra
                                com.mirrly.tgproxy.core.SpeedPreset.AUTO -> R.drawable.ic_speed_auto
                            }
                            val titleText = when (preset) {
                                com.mirrly.tgproxy.core.SpeedPreset.ECO -> stringResource(R.string.settings_speed_eco)
                                com.mirrly.tgproxy.core.SpeedPreset.BALANCED -> stringResource(R.string.settings_speed_balance)
                                com.mirrly.tgproxy.core.SpeedPreset.TURBO -> stringResource(R.string.settings_speed_turbo)
                                com.mirrly.tgproxy.core.SpeedPreset.ULTRA -> stringResource(R.string.settings_speed_ultra)
                                com.mirrly.tgproxy.core.SpeedPreset.AUTO -> stringResource(R.string.settings_speed_auto)
                            }

                            Icon(
                                painter = painterResource(id = iconRes),
                                contentDescription = null,
                                tint = if (isSelected) ActiveGreenLed else TextMuted,
                                modifier = Modifier.size(14.dp)
                            )

                            Text(
                                text = titleText,
                                fontSize = 11.5.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) ActiveGreenLed else TextWhite
                            )
                        }
                    }
                }
            }
        }

        var nagleModeState by remember { mutableStateOf(config.nagleMode) }

        val nagleStatusText = when (nagleModeState) {
            NagleMode.ENABLED -> stringResource(R.string.settings_nagle_status_on)
            NagleMode.DISABLED -> stringResource(R.string.settings_nagle_status_off)
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(stringResource(R.string.settings_nagle_title), color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    InfoButton { onInfoClick("tcp_nodelay") }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(NagleMode.ENABLED, NagleMode.DISABLED).forEach { mode ->
                    val isSelected = nagleModeState == mode
                    val isWarning = mode == NagleMode.DISABLED
                    val activeColor = if (isWarning) Color(0xFFFFB300) else ActiveGreenLed
                    val chipBorder by animateColorAsState(
                        targetValue = if (isSelected) activeColor else AmoledBorder,
                        animationSpec = tween(200),
                        label = "nagleBorder_${mode.name}"
                    )
                    val chipBg by animateColorAsState(
                        targetValue = if (isSelected) activeColor.copy(alpha = 0.08f) else Color.Transparent,
                        animationSpec = tween(200),
                        label = "nagleBg_${mode.name}"
                    )
                    val chipTextColor by animateColorAsState(
                        targetValue = if (isSelected) activeColor else TextWhite,
                        animationSpec = tween(200),
                        label = "nagleText_${mode.name}"
                    )

                    val label = when (mode) {
                        NagleMode.ENABLED -> stringResource(R.string.settings_nagle_on)
                        NagleMode.DISABLED -> stringResource(R.string.settings_nagle_off)
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(chipBg)
                            .border(1.dp, chipBorder, RoundedCornerShape(12.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                nagleModeState = mode
                                config.nagleMode = mode
                                server.setNagleMode(mode)
                                app.saveConfig()
                            }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            fontSize = 12.5.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = chipTextColor
                        )
                    }
                }
            }

            Text(
                text = nagleStatusText,
                color = if (nagleModeState == NagleMode.DISABLED) Color(0xFFFFB300).copy(alpha = 0.9f) else TextMuted,
                fontSize = 11.5.sp,
                lineHeight = 15.sp
            )

            if (nagleModeState == NagleMode.DISABLED) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFFFB300).copy(alpha = 0.08f))
                        .border(1.dp, Color(0xFFFFB300).copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_nagle_warning),
                        color = Color(0xFFFFD54F),
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsAdvancedModeToggleCard(
    isAdvancedMode: Boolean,
    onToggle: (Boolean) -> Unit,
    onInfoClick: (String) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val borderAnim by animateColorAsState(
        targetValue = if (isAdvancedMode) ActiveGreenLed.copy(alpha = 0.5f) else AmoledBorder,
        animationSpec = tween(250),
        label = "advModeBorder"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Transparent)
            .border(1.dp, borderAnim, RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_advanced_mode_title),
                    color = TextWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                InfoButton { onInfoClick("advanced_mode") }
            }
            InertialSpringSwitch(
                checked = isAdvancedMode,
                onCheckedChange = { checked ->
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggle(checked)
                }
            )
        }

        AnimatedVisibility(
            visible = isAdvancedMode,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(AmoledBorder)
                )

                // Single compact contour warning block (Dogma 1: Zero-Background / Outline-Only)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Transparent)
                        .border(1.dp, Color(0xFFF59E0B).copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.settings_safety_expert),
                                color = Color(0xFFFFB74D),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                            SettingsSafetyBadge(level = SettingsSafetyLevel.EXPERT)
                        }

                        Text(
                            text = stringResource(R.string.settings_advanced_mode_warning),
                            color = Color(0xFFFFB74D).copy(alpha = 0.95f),
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )

                        Text(
                            text = stringResource(R.string.settings_advanced_mode_rollback_notice),
                            color = TextMuted,
                            fontSize = 10.5.sp,
                            lineHeight = 14.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsAdvancedEngineeringSection(
    config: ProxyConfig,
    onInfoClick: (String) -> Unit,
    onRestartProxy: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val app = MirrlyApplication.instance
    var happyEyeballsDelay by remember { mutableStateOf(config.happyEyeballsDelayMs) }
    var ipFamilyPref by remember { mutableStateOf(config.ipFamilyPreferenceName) }
    var customAnycastEndpoint by remember { mutableStateOf(config.warpUserEndpointOverride) }
    var keepAliveSeconds by remember { mutableStateOf(config.webSocketKeepAliveSeconds) }

    Column(
        modifier = Modifier.staggeredEntrance(index = 3),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.settings_advanced_section_title),
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.3.sp,
                color = TextMuted
            )
        }

        // 1. Happy Eyeballs Delay (RFC 8305)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Transparent)
                .border(1.dp, AmoledBorder, RoundedCornerShape(18.dp))
                .padding(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.settings_happy_eyeballs_title),
                            color = TextWhite,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.5.sp
                        )
                        InfoButton { onInfoClick("happy_eyeballs") }
                    }
                    Text(
                        text = stringResource(R.string.settings_happy_eyeballs_desc),
                        color = TextMuted,
                        fontSize = 11.5.sp,
                        lineHeight = 15.sp
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(100L, 200L, 300L).forEach { delayMs ->
                        val isSelected = happyEyeballsDelay == delayMs
                        val chipBorder by animateColorAsState(
                            targetValue = if (isSelected) ActiveGreenLed else AmoledBorder,
                            animationSpec = tween(200),
                            label = "heBorder_$delayMs"
                        )
                        val chipTextColor by animateColorAsState(
                            targetValue = if (isSelected) ActiveGreenLed else TextWhite,
                            animationSpec = tween(200),
                            label = "heText_$delayMs"
                        )

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.Transparent)
                                .border(1.dp, chipBorder, RoundedCornerShape(10.dp))
                                .springPress(onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    happyEyeballsDelay = delayMs
                                    config.happyEyeballsDelayMs = delayMs
                                    app.prefsManager.setHappyEyeballsDelayMs(delayMs)
                                    app.proxyServer.applyAdvancedNetworkSettings(happyEyeballsDelayMs = delayMs)
                                    app.saveConfig()
                                })
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.unit_ms_format, delayMs),
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = chipTextColor
                            )
                        }
                    }
                }
            }
        }

        // 2. IP Protocol Stack Preference
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Transparent)
                .border(1.dp, AmoledBorder, RoundedCornerShape(18.dp))
                .padding(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.settings_ip_family_title),
                            color = TextWhite,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.5.sp
                        )
                        InfoButton { onInfoClick("ip_family") }
                    }
                    Text(
                        text = stringResource(R.string.settings_ip_family_desc),
                        color = TextMuted,
                        fontSize = 11.5.sp,
                        lineHeight = 15.sp
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        "IPV4_ONLY" to stringResource(R.string.settings_ip_family_v4),
                        "DUAL_STACK" to "Dual-Stack",
                        "IPV6_FIRST" to stringResource(R.string.settings_ip_family_v6)
                    ).forEach { (prefKey, label) ->
                        val isSelected = ipFamilyPref == prefKey
                        val chipBorder by animateColorAsState(
                            targetValue = if (isSelected) ActiveGreenLed else AmoledBorder,
                            animationSpec = tween(200),
                            label = "ipBorder_$prefKey"
                        )
                        val chipTextColor by animateColorAsState(
                            targetValue = if (isSelected) ActiveGreenLed else TextWhite,
                            animationSpec = tween(200),
                            label = "ipText_$prefKey"
                        )

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.Transparent)
                                .border(1.dp, chipBorder, RoundedCornerShape(10.dp))
                                .springPress(onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    ipFamilyPref = prefKey
                                    config.ipFamilyPreferenceName = prefKey
                                    app.prefsManager.setIpFamilyPreference(prefKey)
                                    app.proxyServer.applyAdvancedNetworkSettings(
                                        ipFamilyPreference = config.ipFamilyPreference
                                    )
                                    app.saveConfig()
                                })
                                .padding(vertical = 8.dp, horizontal = 2.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                fontSize = 10.5.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = chipTextColor,
                                textAlign = TextAlign.Center,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        // 3. Custom Anycast Endpoint WARP
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Transparent)
                .border(1.dp, AmoledBorder, RoundedCornerShape(18.dp))
                .padding(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.settings_warp_anycast_title),
                            color = TextWhite,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.5.sp
                        )
                        InfoButton { onInfoClick("warp_anycast") }
                    }
                    Text(
                        text = stringResource(R.string.settings_warp_anycast_desc),
                        color = TextMuted,
                        fontSize = 11.5.sp,
                        lineHeight = 15.sp
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    BasicTextField(
                        value = customAnycastEndpoint,
                        onValueChange = { newValue ->
                            customAnycastEndpoint = newValue
                            config.warpUserEndpointOverride = newValue.trim()
                            if (config.isVpnAnyWarpUplink) {
                                app.proxyServer.applyWarpEndpoint(config.warpUserEndpointOverride)
                            }
                            app.saveConfig()
                        },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = TextWhite,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        cursorBrush = SolidColor(ActiveGreenLed),
                        decorationBox = { inner ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.Transparent)
                                    .border(1.dp, AmoledBorder, RoundedCornerShape(10.dp))
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (customAnycastEndpoint.isEmpty()) {
                                    Text(
                                        text = config.warpPeerEndpoint.ifEmpty { "188.114.96.1:8095" },
                                        color = TextMuted.copy(alpha = 0.5f),
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                inner()
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )

                    if (customAnycastEndpoint.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.Transparent)
                                .border(1.dp, AmoledBorder, RoundedCornerShape(10.dp))
                                .springPress(onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    customAnycastEndpoint = ""
                                    config.warpUserEndpointOverride = ""
                                    if (config.isVpnAnyWarpUplink) {
                                        app.proxyServer.applyWarpEndpoint(config.warpPeerEndpoint)
                                    }
                                    app.saveConfig()
                                })
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.action_reset),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextMuted
                            )
                        }
                    }
                }
            }
        }

        // 4. WebSocket Keep-Alive Interval
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Transparent)
                .border(1.dp, AmoledBorder, RoundedCornerShape(18.dp))
                .padding(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.settings_ws_keepalive_title),
                            color = TextWhite,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.5.sp
                        )
                        InfoButton { onInfoClick("ws_keepalive") }
                    }
                    Text(
                        text = stringResource(R.string.settings_ws_keepalive_desc),
                        color = TextMuted,
                        fontSize = 11.5.sp,
                        lineHeight = 15.sp
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(15, 30, 60).forEach { seconds ->
                        val isSelected = keepAliveSeconds == seconds
                        val chipBorder by animateColorAsState(
                            targetValue = if (isSelected) ActiveGreenLed else AmoledBorder,
                            animationSpec = tween(200),
                            label = "kaBorder_$seconds"
                        )
                        val chipTextColor by animateColorAsState(
                            targetValue = if (isSelected) ActiveGreenLed else TextWhite,
                            animationSpec = tween(200),
                            label = "kaText_$seconds"
                        )

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.Transparent)
                                .border(1.dp, chipBorder, RoundedCornerShape(10.dp))
                                .springPress(onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    keepAliveSeconds = seconds
                                    config.webSocketKeepAliveSeconds = seconds
                                    app.prefsManager.setSocketKeepAliveSeconds(seconds)
                                    app.proxyServer.applyAdvancedNetworkSettings(webSocketKeepAliveSeconds = seconds)
                                    app.saveConfig()
                                })
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.unit_sec_format, seconds),
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = chipTextColor
                            )
                        }
                    }
                }
            }
        }

        // 5. Reset Engineering Defaults
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color.Transparent,
            border = BorderStroke(1.dp, AmoledBorder),
            modifier = Modifier
                .fillMaxWidth()
                .springPress(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    app.prefsManager.resetAdvancedSettingsToDefaults(config)
                    app.saveConfig()
                    app.proxyServer.applyAdvancedNetworkSettings()
                    app.proxyServer.applyBufferSizeBytes(config.bufferSizeBytes)
                    happyEyeballsDelay = config.happyEyeballsDelayMs
                    ipFamilyPref = config.ipFamilyPreferenceName
                    customAnycastEndpoint = config.warpUserEndpointOverride
                    keepAliveSeconds = config.webSocketKeepAliveSeconds
                    onRestartProxy()
                    Toast.makeText(
                        context,
                        context.getString(R.string.toast_advanced_settings_reverted),
                        Toast.LENGTH_SHORT
                    ).show()
                })
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 13.dp, horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.settings_advanced_btn_reset_defaults),
                    color = TextMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.3.sp
                )
            }
        }
    }
}

@Composable
private fun SettingsWorkerSection(
    config: ProxyConfig,
    onOpenWorkerManager: () -> Unit,
    onInfoClick: (String) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val app = MirrlyApplication.instance
    val activeWorker = remember(app.prefsManager.getActiveWorkerId(com.mirrly.tgproxy.core.ProxyMode.SOCKS5)) { app.prefsManager.getActiveWorker(com.mirrly.tgproxy.core.ProxyMode.SOCKS5) }

    Column(
        modifier = Modifier.staggeredEntrance(index = 3),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = stringResource(R.string.settings_cf_title),
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.3.sp,
                color = TextMuted
            )
            InfoButton { onInfoClick("cf_domain") }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Transparent)
                .border(1.dp, AmoledBorder, RoundedCornerShape(20.dp))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .springPress(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onOpenWorkerManager()
                    })
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.Transparent)
                            .border(1.dp, ActiveGreenLed.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_settings),
                            contentDescription = null,
                            tint = ActiveGreenLed,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Column {
                        Text(
                            text = stringResource(R.string.settings_worker_manager),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite
                        )
                        Text(
                            text = stringResource(R.string.settings_worker_active, activeWorker.name),
                            fontSize = 11.5.sp,
                            color = TextMuted
                        )
                    }
                }

                Icon(
                    painter = painterResource(id = R.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun UplinkModeChip(
    mode: com.mirrly.tgproxy.core.UplinkMode,
    displayName: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) ActiveGreenLed else AmoledBorder,
        animationSpec = tween(200),
        label = "uplinkChipBorder_${mode.name}"
    )
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) ActiveGreenLed.copy(alpha = 0.10f) else Color.Transparent,
        animationSpec = tween(200),
        label = "uplinkChipBg_${mode.name}"
    )

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = bgColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = displayName,
                fontSize = 12.5.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                color = if (isSelected) TextWhite else TextWhite.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                fontSize = 10.5.sp,
                color = if (isSelected) ActiveGreenLed.copy(alpha = 0.85f) else TextMuted,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SettingsUplinkWarpSection(
    config: com.mirrly.tgproxy.core.ProxyConfig,
    uplinkMode: com.mirrly.tgproxy.core.UplinkMode,
    warpProfile: com.mirrly.tgproxy.core.WarpProfile?,
    isRegisteringWarp: Boolean,
    onSelectUplinkMode: (com.mirrly.tgproxy.core.UplinkMode) -> Unit,
    onRefreshWarpAccount: () -> Unit,
    onOpenWarpDetails: () -> Unit,
    onInfoClick: (String) -> Unit,
    vlessUuid: String,
    onRegenerateVlessUuid: () -> Unit,
    onRestartProxy: () -> Unit = {}
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val app = MirrlyApplication.instance
    val devWorkers = remember { app.prefsManager.getDeveloperWorkers() }
    val customWorkers = remember { app.prefsManager.getCustomWorkers() }
    val allWorkers = remember(devWorkers, customWorkers) { devWorkers + customWorkers }

    var activeVlessDomain by remember { mutableStateOf(config.getEffectiveVlessDomain()) }
    var activeAwgStrategy by remember { mutableStateOf(config.awgStrategy) }
    var customAwgIniText by remember { mutableStateOf(config.awgCustomIni) }
    var isRegisterWarpViaWorker by remember { mutableStateOf(config.isRegisterWarpViaWorker) }

    Column(
        modifier = Modifier.staggeredEntrance(index = 4),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Section header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_vpn_uplink_section),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.3.sp,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                InfoButton { onInfoClick("uplink_modes_info") }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            UplinkModeChip(
                mode = com.mirrly.tgproxy.core.UplinkMode.MASQUE,
                displayName = "WARP MASQUE",
                subtitle = stringResource(R.string.settings_uplink_masque_sub),
                isSelected = uplinkMode == com.mirrly.tgproxy.core.UplinkMode.MASQUE,
                onClick = { onSelectUplinkMode(com.mirrly.tgproxy.core.UplinkMode.MASQUE) },
                modifier = Modifier.weight(1f)
            )
            UplinkModeChip(
                mode = com.mirrly.tgproxy.core.UplinkMode.AWG,
                displayName = "WARP AWG",
                subtitle = stringResource(R.string.settings_anycast_obfuscation),
                isSelected = uplinkMode == com.mirrly.tgproxy.core.UplinkMode.AWG,
                onClick = { onSelectUplinkMode(com.mirrly.tgproxy.core.UplinkMode.AWG) },
                modifier = Modifier.weight(1f)
            )
        }

        // Profile Cloudflare WARP (MASQUE and AWG)
        AnimatedVisibility(
            visible = uplinkMode == com.mirrly.tgproxy.core.UplinkMode.MASQUE
                || uplinkMode == com.mirrly.tgproxy.core.UplinkMode.AWG,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, AmoledBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    val hasProfile = warpProfile != null && warpProfile.isWarpEnabled
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            val isPlus = config.isWarpPlus || warpProfile?.isWarpPlus == true
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(if (hasProfile) ActiveGreenLed else InactiveGrayLed)
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Text(
                                        text = "Cloudflare WARP",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextWhite
                                    )
                                    InfoButton { onInfoClick("warp_masque_info") }
                                }
                            }
                            Text(
                                text = if (hasProfile) {
                                    val plusText = if (isPlus) " · WARP+" else ""
                                    "${warpProfile?.clientIpv4} · Anycast$plusText"
                                } else stringResource(R.string.settings_unregistered_autoreg),
                                fontSize = 11.sp,
                                color = if (hasProfile) ActiveGreenLed else TextMuted
                            )
                        }

                        Button(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onRefreshWarpAccount()
                            },
                            enabled = !isRegisteringWarp,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ActiveGreenLed.copy(alpha = 0.12f),
                                contentColor = ActiveGreenLed
                            ),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, ActiveGreenLed.copy(alpha = 0.35f)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            if (isRegisteringWarp) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(13.dp),
                                    strokeWidth = 2.dp,
                                    color = ActiveGreenLed
                                )
                            } else {
                                Text(
                                    text = if (warpProfile != null) stringResource(R.string.settings_btn_refresh) else stringResource(R.string.settings_btn_create),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // note note: note profile + AmneziaWG note
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (hasProfile) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color.Transparent,
                                border = BorderStroke(1.dp, AmoledBorder),
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onOpenWarpDetails()
                                    }
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_reg_details),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextWhite,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(vertical = 7.dp)
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, AmoledBorder),
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    val awgConf = config.getAmneziaWgConfig()
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("AmneziaWG Config", awgConf))
                                    Toast.makeText(context, context.getString(R.string.settings_amnezia_copied), Toast.LENGTH_SHORT).show()
                                }
                        ) {
                            Text(
                                text = "AmneziaWG (QUIC I1)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = ActiveGreenLed,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 7.dp)
                            )
                        }
                    }

                    // note note note
                    if (hasProfile) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, AmoledBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(stringResource(R.string.settings_anycast_endpoint), fontSize = 10.5.sp, color = TextMuted)
                                    Text(
                                        "${warpProfile?.peerEndpoint} (${stringResource(R.string.label_tspu_bypass)})",
                                        fontSize = 10.5.sp,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = ActiveGreenLed
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(stringResource(R.string.settings_mtls_cert), fontSize = 10.5.sp, color = TextMuted)
                                    Text(
                                        if (warpProfile?.clientCertBase64?.isNotBlank() == true) stringResource(R.string.settings_cert_active_x509) else stringResource(R.string.settings_mode_basic),
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (warpProfile?.clientCertBase64?.isNotBlank() == true) ActiveGreenLed else TextMuted
                                    )
                                }
                                val activeLic = config.warpLicenseKey.ifBlank { warpProfile?.licenseKey ?: "" }
                                if (activeLic.isNotBlank()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(stringResource(R.string.settings_warp_plus_license), fontSize = 10.5.sp, color = TextMuted)
                                        Text(
                                            if (activeLic.length >= 14) activeLic.take(6) + "..." + activeLic.takeLast(6) else activeLic,
                                            fontSize = 10.5.sp,
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (config.isWarpPlus || warpProfile?.isWarpPlus == true) ActiveGreenLed else TextWhite
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Переключатель регистрации через Worker Relay vs прямой SNI spoofing
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, AmoledBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                                Text(
                                    text = stringResource(R.string.settings_warp_reg_via_worker_title),
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextWhite
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = stringResource(R.string.settings_warp_reg_via_worker_desc),
                                    fontSize = 10.sp,
                                    color = TextMuted,
                                    lineHeight = 13.5.sp
                                )
                            }
                            InertialSpringSwitch(
                                checked = isRegisterWarpViaWorker,
                                onCheckedChange = { checked ->
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    isRegisterWarpViaWorker = checked
                                    app.prefsManager.setWarpRegisterViaWorker(checked)
                                    app.config.isRegisterWarpViaWorker = checked
                                    app.saveConfig()
                                }
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = uplinkMode == com.mirrly.tgproxy.core.UplinkMode.AWG,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, AmoledBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Column {
                                    Text(
                                        text = stringResource(R.string.awg_strategy_title),
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextWhite
                                    )
                                    Text(
                                        text = stringResource(R.string.awg_strategy_desc),
                                        fontSize = 9.5.sp,
                                        color = TextMuted
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    com.mirrly.tgproxy.core.AwgObfuscationStrategy.entries.forEach { strat ->
                                        val isSel = strat == activeAwgStrategy
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (isSel) ActiveGreenLed.copy(alpha = 0.15f) else Color.Transparent,
                                            border = BorderStroke(1.dp, if (isSel) ActiveGreenLed.copy(alpha = 0.6f) else AmoledBorder),
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable {
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    activeAwgStrategy = strat
                                                    config.awgStrategyName = strat.name
                                                    app.saveConfig()
                                                    val awgIni = config.getAmneziaWgConfig(cleanEndpoint = config.effectivePeerEndpoint)
                                                    com.mirrly.tgproxy.core.NativeProxy.setAwgConfig(awgIni)
                                                    onRestartProxy()
                                                }
                                        ) {
                                            Text(
                                                text = strat.displayName,
                                                fontSize = 9.5.sp,
                                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                                color = if (isSel) ActiveGreenLed else TextWhite,
                                                textAlign = TextAlign.Center,
                                                maxLines = 1,
                                                modifier = Modifier.padding(vertical = 6.dp, horizontal = 2.dp)
                                            )
                                        }
                                    }
                                }

                                val descText = when (activeAwgStrategy) {
                                    com.mirrly.tgproxy.core.AwgObfuscationStrategy.FAST -> stringResource(R.string.awg_strategy_fast_desc)
                                    com.mirrly.tgproxy.core.AwgObfuscationStrategy.BALANCED -> stringResource(R.string.awg_strategy_balanced_desc)
                                    com.mirrly.tgproxy.core.AwgObfuscationStrategy.DEEP_STEALTH -> stringResource(R.string.awg_strategy_deep_desc)
                                    com.mirrly.tgproxy.core.AwgObfuscationStrategy.CUSTOM -> stringResource(R.string.awg_strategy_custom_desc)
                                }
                                Text(
                                    text = descText,
                                    fontSize = 9.sp,
                                    color = TextMuted,
                                    lineHeight = 12.sp
                                )

                                if (activeAwgStrategy == com.mirrly.tgproxy.core.AwgObfuscationStrategy.CUSTOM) {
                                    OutlinedTextField(
                                        value = customAwgIniText,
                                        onValueChange = {
                                            customAwgIniText = it
                                            config.awgCustomIni = it
                                            app.saveConfig()
                                            if (it.isNotBlank()) {
                                                com.mirrly.tgproxy.core.NativeProxy.setAwgConfig(it)
                                            }
                                        },
                                        placeholder = { Text(stringResource(R.string.awg_custom_ini_hint), fontSize = 10.sp, color = TextMuted) },
                                        modifier = Modifier.fillMaxWidth().height(100.dp),
                                        textStyle = androidx.compose.ui.text.TextStyle(
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                            fontSize = 9.5.sp,
                                            color = TextWhite
                                        ),
                                        maxLines = 6
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

    }
}

@Composable
private fun SettingsSystemSection(
    autostart: Boolean,
    onAutostartChange: (Boolean) -> Unit,
    timerState: com.mirrly.tgproxy.service.SleepTimerState,
    onOpenSleepTimer: () -> Unit,
    onOpenSchedule: () -> Unit,
    onOpenOnboarding: () -> Unit = {},
    onOpenVpnOnboarding: () -> Unit = {},
    onOpenDiagnosticReport: () -> Unit = {},
    onInfoClick: (String) -> Unit,
    onLanguageChange: (String) -> Unit = {}
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val app = MirrlyApplication.instance
    var disableAnimations by remember { mutableStateOf(app.prefsManager.areAnimationsDisabled()) }
    val scheduleConfig = remember { app.prefsManager.loadScheduleConfig() }

    Column(
        modifier = Modifier.staggeredEntrance(index = 4),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.settings_system_title),
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.3.sp,
                color = TextMuted
            )
        }

        // ── 1. АВТОМАТИЗАЦИЯ И ТАЙМЕРЫ (CARD) ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Transparent)
                .border(1.dp, AmoledBorder, RoundedCornerShape(18.dp))
                .padding(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Автозапуск при старте системы
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.settings_autostart_title),
                                color = TextWhite,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                            InfoButton { onInfoClick("autostart") }
                        }
                        Text(
                            text = stringResource(R.string.settings_autostart_desc),
                            color = TextMuted,
                            fontSize = 11.5.sp
                        )
                    }
                    InertialSpringSwitch(
                        checked = autostart,
                        onCheckedChange = onAutostartChange
                    )
                }

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AmoledBorder.copy(alpha = 0.5f)))

                // Таймер сна
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onOpenSleepTimer()
                        })
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = stringResource(R.string.settings_sleep_timer_title),
                            color = TextWhite,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        if (timerState.isActive) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(top = 2.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(ActiveGreenLed)
                                )
                                Text(
                                    text = stringResource(R.string.settings_sleep_timer_active, timerState.formatRemainingTime()),
                                    color = ActiveGreenLed,
                                    fontSize = 11.5.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        } else {
                            Text(
                                text = stringResource(R.string.settings_sleep_timer_off),
                                color = TextMuted,
                                fontSize = 11.5.sp
                            )
                        }
                    }
                    Icon(
                        painter = painterResource(id = R.drawable.ic_timer),
                        contentDescription = null,
                        tint = if (timerState.isActive) ActiveGreenLed else TextMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AmoledBorder.copy(alpha = 0.5f)))

                // Расписание работы
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onOpenSchedule()
                        })
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = stringResource(R.string.settings_schedule_title),
                            color = TextWhite,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        if (scheduleConfig.isEnabled) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(top = 2.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(ActiveGreenLed)
                                )
                                Text(
                                    text = stringResource(R.string.settings_schedule_active, scheduleConfig.getSummaryText(context)),
                                    color = ActiveGreenLed,
                                    fontSize = 11.5.sp
                                )
                            }
                        } else {
                            Text(
                                text = stringResource(R.string.settings_schedule_off),
                                color = TextMuted,
                                fontSize = 11.5.sp
                            )
                        }
                    }
                    Icon(
                        painter = painterResource(id = R.drawable.ic_chevron_right),
                        contentDescription = null,
                        tint = if (scheduleConfig.isEnabled) ActiveGreenLed else TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // ── 2. ПИТАНИЕ И ФОНОВАЯ РАБОТА (CARD) ──
        var isBatteryGuardEnabled by remember { mutableStateOf(app.config.isBatteryGuardEnabled) }
        var batteryGuardThreshold by remember { mutableStateOf(app.config.batteryGuardThreshold) }
        var batteryGuardStopOnPowerSave by remember { mutableStateOf(app.config.batteryGuardStopOnPowerSave) }
        var isAdaptiveQoSEnabled by remember { mutableStateOf(app.config.isAdaptiveQoSEnabled) }

        val powerManager = remember { context.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager }
        var isIgnoringBatteryOptimizations by remember {
            mutableStateOf(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
                } else {
                    true
                }
            )
        }

        val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        isIgnoringBatteryOptimizations = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
                    }
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        val openBatterySettings: () -> Unit = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {
                    try {
                        val intent = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    } catch (_: Exception) {
                        Toast.makeText(context, context.getString(R.string.settings_err_open_battery_settings), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Transparent)
                .border(1.dp, AmoledBorder, RoundedCornerShape(18.dp))
                .padding(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Блок 2.1: Защита батареи (Battery Guard)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_battery_guard_title),
                                    color = TextWhite,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp
                                )
                                InfoButton { onInfoClick("battery_guard") }
                            }
                            Text(
                                text = if (isBatteryGuardEnabled) stringResource(R.string.settings_battery_guard_active, batteryGuardThreshold.toString()) else stringResource(R.string.settings_battery_guard_off),
                                color = if (isBatteryGuardEnabled) ActiveGreenLed.copy(alpha = 0.85f) else TextMuted,
                                fontSize = 11.5.sp,
                                lineHeight = 15.sp
                            )
                        }
                        InertialSpringSwitch(
                            checked = isBatteryGuardEnabled,
                            onCheckedChange = { checked ->
                                isBatteryGuardEnabled = checked
                                app.config.isBatteryGuardEnabled = checked
                                app.saveConfig()
                            }
                        )
                    }

                    AnimatedVisibility(
                        visible = isBatteryGuardEnabled,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AmoledBorder.copy(alpha = 0.5f)))

                            // Пороги батареи (контурные чипы)
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = stringResource(R.string.settings_battery_threshold),
                                    color = TextWhite.copy(alpha = 0.9f),
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Medium
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    listOf(5, 10, 15, 20, 25).forEach { threshold ->
                                        val isSelected = batteryGuardThreshold == threshold
                                        val chipBorder by animateColorAsState(
                                            targetValue = if (isSelected) ActiveGreenLed else AmoledBorder,
                                            animationSpec = tween(200),
                                            label = "threshBorder_$threshold"
                                        )
                                        val chipTextColor by animateColorAsState(
                                            targetValue = if (isSelected) ActiveGreenLed else TextWhite,
                                            animationSpec = tween(200),
                                            label = "threshText_$threshold"
                                        )

                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(Color.Transparent)
                                                .border(1.dp, chipBorder, RoundedCornerShape(10.dp))
                                                .springPress(onClick = {
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    batteryGuardThreshold = threshold
                                                    app.config.batteryGuardThreshold = threshold
                                                    app.saveConfig()
                                                })
                                                .padding(vertical = 8.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "$threshold%",
                                                fontSize = 12.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                color = chipTextColor
                                            )
                                        }
                                    }
                                }
                            }

                            // Остановка при режиме энергосбережения Android
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                    Text(
                                        text = stringResource(R.string.settings_battery_android_saver),
                                        color = TextWhite.copy(alpha = 0.9f),
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = stringResource(R.string.settings_battery_android_saver_desc),
                                        color = TextMuted,
                                        fontSize = 11.sp
                                    )
                                }
                                InertialSpringSwitch(
                                    checked = batteryGuardStopOnPowerSave,
                                    onCheckedChange = { checked ->
                                        batteryGuardStopOnPowerSave = checked
                                        app.config.batteryGuardStopOnPowerSave = checked
                                        app.saveConfig()
                                    }
                                )
                            }
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AmoledBorder.copy(alpha = 0.5f)))

                // Блок 2.2: Адаптивный QoS
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.settings_qos_title),
                                color = TextWhite,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                            InfoButton { onInfoClick("adaptive_qos") }
                        }
                        Text(
                            text = if (isAdaptiveQoSEnabled) stringResource(R.string.settings_qos_active) else stringResource(R.string.settings_qos_off),
                            color = if (isAdaptiveQoSEnabled) ActiveGreenLed.copy(alpha = 0.85f) else TextMuted,
                            fontSize = 11.5.sp,
                            lineHeight = 15.sp
                        )
                    }
                    InertialSpringSwitch(
                        checked = isAdaptiveQoSEnabled,
                        onCheckedChange = { checked ->
                            isAdaptiveQoSEnabled = checked
                            app.config.isAdaptiveQoSEnabled = checked
                            app.saveConfig()
                            app.proxyServer.setAdaptiveQoSEnabled(checked)
                        }
                    )
                }

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AmoledBorder.copy(alpha = 0.5f)))

                // Блок 2.3: Работа в фоне (Doze Mode)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .springPress(onClick = openBatterySettings)
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.settings_doze_mode_title),
                                color = TextWhite,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                            InfoButton { onInfoClick("doze_mode") }
                        }
                        Text(
                            text = if (isIgnoringBatteryOptimizations) {
                                stringResource(R.string.settings_doze_mode_allowed)
                            } else {
                                stringResource(R.string.settings_doze_mode_optimizing)
                            },
                            color = if (isIgnoringBatteryOptimizations) ActiveGreenLed.copy(alpha = 0.85f) else TextMuted,
                            fontSize = 11.5.sp,
                            lineHeight = 15.sp
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, if (isIgnoringBatteryOptimizations) ActiveGreenLed.copy(alpha = 0.5f) else AmoledBorder)
                    ) {
                        Text(
                            text = if (isIgnoringBatteryOptimizations) stringResource(R.string.settings_doze_status_active) else stringResource(R.string.settings_doze_btn_configure),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isIgnoringBatteryOptimizations) ActiveGreenLed else TextWhite,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                        )
                    }
                }

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AmoledBorder.copy(alpha = 0.5f)))

                // Блок 2.4: Режим энергосбережения (Отключение анимаций)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.settings_power_saver_title),
                                color = TextWhite,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                            InfoButton { onInfoClick("disable_animations") }
                        }
                        Text(
                            text = stringResource(R.string.settings_power_saver_desc),
                            color = TextMuted,
                            fontSize = 11.5.sp
                        )
                    }
                    InertialSpringSwitch(
                        checked = disableAnimations,
                        onCheckedChange = { newValue ->
                            disableAnimations = newValue
                            app.prefsManager.setAnimationsDisabled(newValue)
                        }
                    )
                }
            }
        }

        // ── 3. ЯЗЫК ПРИЛОЖЕНИЯ (CARD) ──
        val currentAppLanguage by app.prefsManager.appLanguageFlow.collectAsState()

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Transparent)
                .border(1.dp, AmoledBorder, RoundedCornerShape(18.dp))
                .padding(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column {
                    Text(
                        text = stringResource(R.string.settings_language_title),
                        color = TextWhite,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = stringResource(R.string.settings_language_desc),
                        color = TextMuted,
                        fontSize = 11.5.sp
                    )
                }

                // Two fixed, equal-width slots per row keep language controls compact on 320–360dp phones.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "system" to stringResource(R.string.settings_language_system),
                        "ru" to stringResource(R.string.settings_language_ru),
                        "en" to stringResource(R.string.settings_language_en),
                        "fa" to stringResource(R.string.settings_language_fa)
                    ).chunked(2).forEach { languageRow ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            languageRow.forEach { (langCode, langLabel) ->
                                val isSelected = currentAppLanguage == langCode
                                val chipBorder by animateColorAsState(
                                    targetValue = if (isSelected) ActiveGreenLed else AmoledBorder,
                                    animationSpec = tween(200),
                                    label = "langBorder_$langCode"
                                )
                                val chipTextColor by animateColorAsState(
                                    targetValue = if (isSelected) ActiveGreenLed else TextWhite,
                                    animationSpec = tween(200),
                                    label = "langText_$langCode"
                                )

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color.Transparent)
                                        .border(1.dp, chipBorder, RoundedCornerShape(10.dp))
                                        .springPress(onClick = {
                                            if (currentAppLanguage == langCode) return@springPress
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            onLanguageChange(langCode)
                                        })
                                        .padding(vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = langLabel,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = chipTextColor,
                                        maxLines = 1,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                            if (languageRow.size == 1) Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // ── 4. СПРАВКА И ДИАГНОСТИКА (CARD) ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Transparent)
                .border(1.dp, AmoledBorder, RoundedCornerShape(18.dp))
                .padding(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Обучение и подсказки
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onOpenOnboarding()
                        })
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = stringResource(R.string.settings_onboarding_replay),
                            color = TextWhite,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.5.sp
                        )
                        Text(
                            text = stringResource(R.string.settings_onboarding_replay_desc),
                            color = TextMuted,
                            fontSize = 11.5.sp
                        )
                    }
                    Icon(
                        painter = painterResource(id = R.drawable.ic_chevron_right),
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AmoledBorder.copy(alpha = 0.5f)))

                // Руководство по VPN
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onOpenVpnOnboarding()
                        })
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = stringResource(R.string.settings_vpn_onboarding_replay),
                            color = TextWhite,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.5.sp
                        )
                        Text(
                            text = stringResource(R.string.settings_vpn_onboarding_replay_desc),
                            color = TextMuted,
                            fontSize = 11.5.sp
                        )
                    }
                    Icon(
                        painter = painterResource(id = R.drawable.ic_chevron_right),
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AmoledBorder.copy(alpha = 0.5f)))

                // Диагностический отчет
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onOpenDiagnosticReport()
                        })
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = stringResource(R.string.settings_tile_diagnostic_report_title),
                            color = TextWhite,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.5.sp
                        )
                        Text(
                            text = stringResource(R.string.settings_tile_diagnostic_report_desc),
                            color = TextMuted,
                            fontSize = 11.5.sp
                        )
                    }
                    Icon(
                        painter = painterResource(id = R.drawable.ic_chevron_right),
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsCustomizationSection(
    onInfoClick: (String) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val prefs = MirrlyApplication.instance.prefsManager

    val accentColor = Color(0xFF3B82F6)
    val activeLedColor = Color(0xFF22C55E)

    val wallpaperUri by prefs.customBackgroundUriFlow.collectAsState()
    val wallpaperRevision by prefs.customBackgroundRevisionFlow.collectAsState()
    val wallpaperType by prefs.customBackgroundTypeFlow.collectAsState()
    val videoPowerSave by prefs.customBackgroundVideoPowerSaveFlow.collectAsState()
    val videoMetadata by prefs.customBackgroundVideoMetadataFlow.collectAsState()
    val blurDp by prefs.customBackgroundBlurFlow.collectAsState()
    val dimFactor by prefs.customBackgroundDimFlow.collectAsState()
    val particlesEnabled by prefs.customBackgroundParticlesFlow.collectAsState()
    val activePresetId by prefs.customBackgroundActivePresetIdFlow.collectAsState()
    val userPresets by prefs.customBackgroundUserPresetsFlow.collectAsState()
    val allPresets = remember(userPresets) { WallpaperPreset.BUILTIN_PRESETS + userPresets }

    var showResetConfirm by remember { mutableStateOf(false) }
    var showSavePresetCard by remember { mutableStateOf(false) }
    var newPresetTitle by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var sliderBlur by remember(blurDp) { mutableFloatStateOf(blurDp) }
    var sliderDim by remember(dimFactor) { mutableFloatStateOf(dimFactor) }

    val mediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { selectedUri ->
        if (selectedUri != null) {
            coroutineScope.launch {
                isProcessing = true
                errorMessage = null
                try {
                    val result = prefs.setCustomMediaWallpaper(selectedUri)
                    if (result.isFailure) {
                        errorMessage = result.exceptionOrNull()?.localizedMessage ?: "Unknown error"
                    } else {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showResetConfirm = false
                    }
                } catch (e: Exception) {
                    errorMessage = e.localizedMessage ?: "Unknown error"
                } finally {
                    isProcessing = false
                }
            }
        }
    }

    val hasCustomWallpaper = !wallpaperUri.isNullOrBlank()
    val isVideo = wallpaperType == PreferencesManager.WALLPAPER_TYPE_VIDEO
    val previewPath = remember(wallpaperUri, wallpaperRevision, wallpaperType) {
        prefs.getWallpaperPreviewFile()?.absolutePath
    }
    val wallpaperImage = rememberWallpaperImage(previewPath, wallpaperRevision)

    Column(
        modifier = Modifier.staggeredEntrance(index = 4),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = stringResource(R.string.settings_customization_category_title),
            fontSize = 12.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.3.sp,
            color = TextMuted
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Transparent)
                .border(1.dp, AmoledBorder, RoundedCornerShape(18.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.Transparent)
                            .border(1.dp, AmoledBorder, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        WallpaperGlyph(
                            color = if (hasCustomWallpaper) accentColor else TextWhite,
                            pulse = if (isProcessing) 1f else 0f,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.settings_customization_wallpaper_title),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite
                            )
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .springPress()
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onInfoClick("custom_wallpaper_info")
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_info_circle),
                                    contentDescription = null,
                                    tint = TextMuted,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }

                        Text(
                            text = if (hasCustomWallpaper) {
                                if (isVideo) {
                                    stringResource(R.string.settings_customization_wallpaper_active_video)
                                } else {
                                    stringResource(R.string.settings_customization_wallpaper_active_image)
                                }
                            } else {
                                stringResource(R.string.settings_customization_wallpaper_default)
                            },
                            fontSize = 11.sp,
                            color = if (hasCustomWallpaper) activeLedColor else TextMuted
                        )
                    }
                }
            }

            Text(
                text = stringResource(R.string.settings_customization_wallpaper_desc),
                fontSize = 12.sp,
                color = TextMuted,
                lineHeight = 16.sp
            )

            if (errorMessage != null) {
                Text(
                    text = errorMessage.orEmpty(),
                    fontSize = 11.sp,
                    color = Color(0xFFEF4444),
                    lineHeight = 14.sp
                )
            }

            if (hasCustomWallpaper && wallpaperImage != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, AmoledBorder, RoundedCornerShape(14.dp))
                ) {
                    WallpaperBlurredImage(
                        image = wallpaperImage,
                        blurDp = sliderBlur,
                        modifier = Modifier.fillMaxSize()
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = sliderDim))
                    )

                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.65f))
                            .border(1.dp, AmoledBorder, RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        if (isVideo && videoMetadata != null) {
                            val sizeMb = String.format(java.util.Locale.US, "%.1f", videoMetadata!!.fileSizeBytes / (1024f * 1024f))
                            Text(
                                text = stringResource(
                                    R.string.settings_customization_wallpaper_video_metrics,
                                    videoMetadata!!.width,
                                    videoMetadata!!.height,
                                    videoMetadata!!.durationSec,
                                    sizeMb
                                ),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextWhite
                            )
                        } else {
                            val sizeKb = (wallpaperImage.fileSizeBytes / 1024L).coerceAtLeast(1L)
                            Text(
                                text = stringResource(
                                    R.string.settings_customization_wallpaper_metrics,
                                    wallpaperImage.width,
                                    wallpaperImage.height,
                                    sizeKb.toString()
                                ),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextWhite
                            )
                        }
                    }
                }
            }

            // ── Presets Section ─────────────────────────────────────
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.settings_customization_presets_title),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite
                    )

                    if (hasCustomWallpaper) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.6f)),
                            modifier = Modifier
                                .springPress()
                                .clickable(enabled = !isProcessing) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    showSavePresetCard = !showSavePresetCard
                                }
                        ) {
                            Text(
                                text = "+ ${stringResource(R.string.settings_customization_preset_save_current)}",
                                color = accentColor,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                AnimatedVisibility(
                    visible = showSavePresetCard,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Transparent)
                            .border(1.dp, accentColor.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.settings_customization_preset_save_title),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite
                            )
                            Text(
                                text = if (isVideo) "Видео • ${sliderBlur.roundToInt()} dp • ${(sliderDim * 100).roundToInt()}%"
                                else "Фото • ${sliderBlur.roundToInt()} dp • ${(sliderDim * 100).roundToInt()}%",
                                fontSize = 10.sp,
                                color = accentColor
                            )
                        }

                        BasicTextField(
                            value = newPresetTitle,
                            onValueChange = { newPresetTitle = it },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = TextWhite,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Default
                            ),
                            cursorBrush = SolidColor(accentColor),
                            decorationBox = { innerTextField ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.Transparent)
                                        .border(1.dp, AmoledBorder, RoundedCornerShape(8.dp))
                                        .padding(horizontal = 10.dp, vertical = 8.dp)
                                ) {
                                    if (newPresetTitle.isEmpty()) {
                                        Text(
                                            text = stringResource(R.string.settings_customization_preset_name_hint),
                                            color = TextMuted,
                                            fontSize = 12.sp
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.Transparent)
                                    .border(1.dp, accentColor, RoundedCornerShape(8.dp))
                                    .springPress()
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        val titleToSave = newPresetTitle.trim().ifEmpty {
                                            if (isVideo) "Видео пресет" else "Фото пресет"
                                        }
                                        coroutineScope.launch {
                                            prefs.saveCurrentAsUserPreset(titleToSave)
                                            newPresetTitle = ""
                                            showSavePresetCard = false
                                        }
                                    }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_customization_preset_save_btn),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = accentColor
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.Transparent)
                                    .border(1.dp, AmoledBorder, RoundedCornerShape(8.dp))
                                    .springPress()
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        showSavePresetCard = false
                                    }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_customization_wallpaper_cancel),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Normal,
                                    color = TextMuted
                                )
                            }
                        }
                    }
                }

                val presetsScrollState = rememberScrollState()
                val startFadeWidth by animateDpAsState(
                    targetValue = if (presetsScrollState.canScrollBackward) 16.dp else 0.dp,
                    animationSpec = tween(180),
                    label = "presetStartFade"
                )
                val endFadeWidth by animateDpAsState(
                    targetValue = if (presetsScrollState.canScrollForward) 24.dp else 0.dp,
                    animationSpec = tween(180),
                    label = "presetEndFade"
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalFadingEdges(
                            startFadeWidth = startFadeWidth,
                            endFadeWidth = endFadeWidth
                        )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(presetsScrollState),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        allPresets.forEach { preset ->
                            val isPresetActive = hasCustomWallpaper && activePresetId == preset.id
                            WallpaperPresetCard(
                                preset = preset,
                                isActive = isPresetActive,
                                accentColor = accentColor,
                                onSelect = {
                                    coroutineScope.launch {
                                        isProcessing = true
                                        errorMessage = null
                                        try {
                                            val res = prefs.applyPreset(preset)
                                            if (res.isFailure) {
                                                errorMessage = res.exceptionOrNull()?.localizedMessage ?: "Unknown error"
                                            } else {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                showResetConfirm = false
                                            }
                                        } catch (e: Exception) {
                                            errorMessage = e.localizedMessage ?: "Unknown error"
                                        } finally {
                                            isProcessing = false
                                        }
                                    }
                                },
                                onDelete = if (!preset.isBuiltIn) {
                                    {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        prefs.deleteUserPreset(preset.id)
                                    }
                                } else null
                            )
                        }
                    }
                }
            }

            if (!hasCustomWallpaper) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Transparent)
                            .border(1.dp, AmoledBorder, RoundedCornerShape(12.dp))
                            .springPress()
                            .clickable(enabled = !isProcessing) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                mediaPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            }
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        WallpaperGlyph(
                            color = accentColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.settings_customization_wallpaper_choose_image),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite
                        )
                    }

                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Transparent)
                            .border(1.dp, AmoledBorder, RoundedCornerShape(12.dp))
                            .springPress()
                            .clickable(enabled = !isProcessing) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                mediaPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                )
                            }
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_video),
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.settings_customization_wallpaper_choose_video),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Transparent)
                            .border(1.dp, AmoledBorder, RoundedCornerShape(12.dp))
                            .springPress()
                            .clickable(enabled = !isProcessing) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                mediaPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                )
                            }
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_refresh),
                            contentDescription = null,
                            tint = TextWhite,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isVideo) {
                                stringResource(R.string.settings_customization_wallpaper_replace_video)
                            } else {
                                stringResource(R.string.settings_customization_wallpaper_replace_image)
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite
                        )
                    }

                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Transparent)
                            .border(1.dp, AmoledBorder, RoundedCornerShape(12.dp))
                            .springPress()
                            .clickable(enabled = !isProcessing) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                showResetConfirm = !showResetConfirm
                            }
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_trash),
                            contentDescription = null,
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.settings_customization_wallpaper_reset),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFEF4444)
                        )
                    }
                }

                AnimatedVisibility(
                    visible = showResetConfirm,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Transparent)
                            .border(1.dp, Color(0xFFEF4444).copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.settings_customization_wallpaper_reset_confirm_title),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFEF4444)
                        )
                        Text(
                            text = stringResource(R.string.settings_customization_wallpaper_reset_confirm_desc),
                            fontSize = 11.sp,
                            color = TextMuted,
                            lineHeight = 15.sp
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.Transparent)
                                    .border(1.dp, Color(0xFFEF4444), RoundedCornerShape(10.dp))
                                    .springPress()
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        prefs.clearCustomWallpaper()
                                        showResetConfirm = false
                                    }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_customization_wallpaper_reset_confirm_btn),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFEF4444)
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.Transparent)
                                    .border(1.dp, AmoledBorder, RoundedCornerShape(10.dp))
                                    .springPress()
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        showResetConfirm = false
                                    }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_customization_wallpaper_cancel),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Normal,
                                    color = TextMuted
                                )
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.settings_customization_wallpaper_blur),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextWhite
                            )
                            Text(
                                text = "${sliderBlur.roundToInt()} dp",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = accentColor
                            )
                        }

                        Slider(
                            value = sliderBlur,
                            onValueChange = {
                                sliderBlur = it
                                prefs.setWallpaperBlur(it, persist = false)
                            },
                            onValueChangeFinished = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                prefs.setWallpaperBlur(sliderBlur, persist = true)
                            },
                            valueRange = 0f..25f,
                            steps = 24,
                            colors = SliderDefaults.colors(
                                thumbColor = accentColor,
                                activeTrackColor = accentColor,
                                inactiveTrackColor = AmoledBorder
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.settings_customization_wallpaper_dim),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextWhite
                            )
                            Text(
                                text = "${(sliderDim * 100f).roundToInt()}%",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = accentColor
                            )
                        }

                        Slider(
                            value = sliderDim,
                            onValueChange = {
                                sliderDim = it
                                prefs.setWallpaperDim(it, persist = false)
                            },
                            onValueChangeFinished = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                prefs.setWallpaperDim(sliderDim, persist = true)
                            },
                            valueRange = 0.10f..0.85f,
                            colors = SliderDefaults.colors(
                                thumbColor = accentColor,
                                activeTrackColor = accentColor,
                                inactiveTrackColor = AmoledBorder
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    if (isVideo) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.Transparent)
                                .border(1.dp, AmoledBorder, RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f, fill = false)) {
                                Text(
                                    text = stringResource(R.string.settings_customization_wallpaper_video_power_save),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextWhite
                                )
                                Text(
                                    text = stringResource(R.string.settings_customization_wallpaper_video_power_save_desc),
                                    fontSize = 11.sp,
                                    color = TextMuted,
                                    lineHeight = 14.sp
                                )
                            }

                            InertialSpringSwitch(
                                checked = videoPowerSave,
                                onCheckedChange = { checked ->
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    prefs.setWallpaperVideoPowerSave(checked)
                                }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Transparent)
                            .border(1.dp, AmoledBorder, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f, fill = false)) {
                            Text(
                                text = stringResource(R.string.settings_customization_wallpaper_particles),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextWhite
                            )
                            Text(
                                text = stringResource(R.string.settings_customization_wallpaper_particles_desc),
                                fontSize = 11.sp,
                                color = TextMuted,
                                lineHeight = 14.sp
                            )
                        }

                        InertialSpringSwitch(
                            checked = particlesEnabled,
                            onCheckedChange = { checked ->
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                prefs.setWallpaperParticlesEnabled(checked)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WallpaperPresetCard(
    preset: WallpaperPreset,
    isActive: Boolean,
    accentColor: Color,
    onSelect: () -> Unit,
    onDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val previewImage = rememberWallpaperImage(
        path = preset.previewAssetPath ?: preset.previewFilePath ?: preset.filePath,
        revision = 0L
    )

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        border = BorderStroke(
            width = if (isActive) 1.5.dp else 1.dp,
            color = if (isActive) accentColor else AmoledBorder
        ),
        modifier = modifier
            .width(112.dp)
            .height(168.dp)
            .clip(RoundedCornerShape(14.dp))
            .springPress()
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onSelect()
            }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(108.dp)
                    .clip(RoundedCornerShape(topStart = 13.dp, topEnd = 13.dp))
                    .background(Color.Black)
            ) {
                if (preset.type == PreferencesManager.WALLPAPER_TYPE_VIDEO) {
                    PresetVideoPreview(
                        videoPath = preset.filePath,
                        assetPath = preset.previewAssetPath?.takeIf { it.endsWith(".mp4") } ?: preset.assetPath,
                        previewImage = previewImage,
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (previewImage != null) {
                    Image(
                        bitmap = previewImage.bitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Delete Button for user custom presets (top end)
                if (onDelete != null && !preset.isBuiltIn) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.70f))
                            .border(1.dp, Color(0xFFEF4444).copy(alpha = 0.6f), CircleShape)
                            .springPress()
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onDelete()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "×",
                            color = Color(0xFFEF4444),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Title & Parameters
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 7.dp, vertical = 5.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                val displayTitle = preset.titleRes?.let { stringResource(it) } ?: preset.title
                Text(
                    text = displayTitle,
                    fontSize = 11.sp,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                    color = if (isActive) TextWhite else TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${preset.blurDp.roundToInt()} dp • ${(preset.dimFactor * 100).roundToInt()}%",
                    fontSize = 9.sp,
                    color = TextMuted,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun SettingsAboutSection(
    onOpenAbout: () -> Unit,
    onOpenTelegramChannel: () -> Unit,
    onDonateClick: () -> Unit,
    onOpenUpdate: () -> Unit,
    onOpenHallOfFame: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    var isCheckingUpdate by remember { mutableStateOf(false) }
    val currentUpdateInfo by com.mirrly.tgproxy.service.UpdateManager.updateState.collectAsState()
    val isUpdateAvailable = currentUpdateInfo?.isUpdateAvailable == true

    Column(
        modifier = Modifier.staggeredEntrance(index = 5),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = stringResource(R.string.settings_about_category_title),
            fontSize = 12.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.3.sp,
            color = TextMuted
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .border(1.dp, AmoledBorder, RoundedCornerShape(18.dp))
        ) {
            // ── 1. STANDOUT HALL OF FAME ROW ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .lightSweep(
                        isEnabled = true,
                        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
                        sweepColor = ActiveGreenLed
                    )
                    .springPress(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onOpenHallOfFame()
                        }
                    )
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, ActiveGreenLed.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_hall_of_fame),
                            contentDescription = null,
                            tint = ActiveGreenLed,
                            modifier = Modifier.size(17.dp)
                        )
                    }

                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.settings_hall_of_fame_title),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color.Transparent,
                                border = BorderStroke(0.8.dp, ActiveGreenLed.copy(alpha = 0.5f))
                            ) {
                                Text(
                                    text = stringResource(R.string.badge_top),
                                    fontSize = 8.5.sp,
                                    fontWeight = FontWeight.Black,
                                    color = ActiveGreenLed,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                        Text(
                            text = stringResource(R.string.settings_hall_of_fame_desc),
                            fontSize = 11.5.sp,
                            color = TextMuted
                        )
                    }
                }

                Icon(
                    painter = painterResource(id = R.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = ActiveGreenLed,
                    modifier = Modifier.size(16.dp)
                )
            }

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AmoledBorder.copy(alpha = 0.5f)))

            // ── 2. TELEGRAM COMMUNITY ROW ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .springPress(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onOpenTelegramChannel()
                        }
                    )
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, AmoledBorder, RoundedCornerShape(10.dp))
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_telegram),
                            contentDescription = null,
                            tint = ActiveGreenLed,
                            modifier = Modifier.size(17.dp)
                        )
                    }

                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.tg_channel_topbar_title),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color.Transparent,
                                border = BorderStroke(0.8.dp, AmoledBorder)
                            ) {
                                Text(
                                    text = stringResource(R.string.tg_channel_tag_handle),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextMuted,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                        Text(
                            text = stringResource(R.string.tg_channel_btn_sub),
                            fontSize = 11.5.sp,
                            color = TextMuted
                        )
                    }
                }

                Icon(
                    painter = painterResource(id = R.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(16.dp)
                )
            }

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AmoledBorder.copy(alpha = 0.5f)))

            // ── 3. ABOUT DEV ROW ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .springPress(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onOpenAbout()
                        }
                    )
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, AmoledBorder, RoundedCornerShape(10.dp))
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_user),
                            contentDescription = null,
                            tint = ActiveGreenLed,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Column {
                        Text(
                            text = stringResource(R.string.settings_about_dev_title),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite
                        )
                        Text(
                            text = stringResource(R.string.settings_about_dev_desc),
                            fontSize = 11.5.sp,
                            color = TextMuted
                        )
                    }
                }

                Icon(
                    painter = painterResource(id = R.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(16.dp)
                )
            }

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AmoledBorder.copy(alpha = 0.5f)))

            // ── 4. DONATION ROW ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .springPress(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDonateClick()
                        }
                    )
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, AmoledBorder, RoundedCornerShape(10.dp))
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_donate),
                            contentDescription = null,
                            tint = ActiveGreenLed,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Column {
                        Text(
                            text = stringResource(R.string.settings_support_dev),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite
                        )
                        Text(
                            text = stringResource(R.string.settings_support_dev_desc),
                            fontSize = 11.5.sp,
                            color = TextMuted
                        )
                    }
                }

                Icon(
                    painter = painterResource(id = R.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(16.dp)
                )
            }

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AmoledBorder.copy(alpha = 0.5f)))

            // ── 5. CHECK UPDATES ROW ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .springPress(
                        onClick = {
                            if (isUpdateAvailable) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onOpenUpdate()
                            } else if (!isCheckingUpdate) {
                                isCheckingUpdate = true
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                coroutineScope.launch {
                                    val result = com.mirrly.tgproxy.service.UpdateManager.checkForUpdates(context, notifyIfFound = false, forceRefresh = true)
                                    isCheckingUpdate = false
                                    result.fold(
                                        onSuccess = { info ->
                                            if (info.isUpdateAvailable) {
                                                onOpenUpdate()
                                            } else {
                                                Toast.makeText(
                                                    context,
                                                    context.getString(R.string.settings_update_actual),
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        },
                                        onFailure = { err ->
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.settings_update_check_err, err.localizedMessage ?: ""),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    )
                                }
                            }
                        }
                    )
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(
                                1.dp,
                                if (isUpdateAvailable) ActiveGreenLed.copy(alpha = 0.6f) else AmoledBorder,
                                RoundedCornerShape(10.dp)
                            )
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_refresh),
                            contentDescription = null,
                            tint = ActiveGreenLed,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Column {
                        Text(
                            text = if (isUpdateAvailable) stringResource(R.string.settings_update_available) else stringResource(R.string.settings_btn_check_updates),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isUpdateAvailable) ActiveGreenLed else TextWhite
                        )
                        Text(
                            text = when {
                                isCheckingUpdate -> stringResource(R.string.settings_update_checking)
                                isUpdateAvailable -> stringResource(R.string.settings_update_available_tap)
                                else -> stringResource(R.string.settings_update_search_github)
                            },
                            fontSize = 11.5.sp,
                            color = if (isUpdateAvailable) TextWhite.copy(alpha = 0.9f) else TextMuted
                        )
                    }
                }

                if (isCheckingUpdate) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = ActiveGreenLed,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_chevron_right),
                        contentDescription = null,
                        tint = if (isUpdateAvailable) ActiveGreenLed else TextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsInfoDialog(infoKey: String, onDismiss: () -> Unit) {
    val (dlgTitleRes, dlgBodyRes) = when (infoKey) {
        "uplink_modes_info" -> R.string.info_uplink_modes_title to R.string.info_uplink_modes_body
        "warp_masque_info" -> R.string.info_warp_masque_title to R.string.info_warp_masque_body
        "vless_info" -> R.string.info_vless_title to R.string.info_vless_body
        "port", "ports_info" -> R.string.info_ports_title to R.string.info_ports_body
        "secret", "secret_info" -> R.string.info_secret_title to R.string.info_secret_body
        "cf_domain", "worker_info" -> R.string.info_worker_title to R.string.info_worker_body
        "autostart", "autostart_info" -> R.string.info_autostart_title to R.string.info_autostart_body
        "preset", "wspool_info" -> R.string.info_wspool_title to R.string.info_wspool_body
        "tcp_nodelay", "tcp_nodelay_info" -> R.string.info_tcp_nodelay_title to R.string.info_tcp_nodelay_body
        "disable_animations", "powersaver_info" -> R.string.info_powersaver_title to R.string.info_powersaver_body
        "protocols_info" -> R.string.info_protocols_title to R.string.info_protocols_body
        "socks5_auth", "socks5_auth_info" -> R.string.info_socks5_auth_title to R.string.info_socks5_auth_body
        "battery_guard", "battery_guard_info" -> R.string.info_battery_guard_title to R.string.info_battery_guard_body
        "adaptive_qos", "qos_info" -> R.string.info_qos_title to R.string.info_qos_body
        "doze_mode", "doze_mode_info" -> R.string.info_doze_mode_title to R.string.info_doze_mode_body
        "advanced_mode" -> R.string.settings_advanced_mode_title to R.string.settings_advanced_mode_desc
        "happy_eyeballs" -> R.string.info_happy_eyeballs_title to R.string.info_happy_eyeballs_body
        "ip_family" -> R.string.info_ip_family_title to R.string.info_ip_family_body
        "warp_anycast" -> R.string.info_warp_anycast_title to R.string.info_warp_anycast_body
        "socket_buffer" -> R.string.info_socket_buffer_title to R.string.info_socket_buffer_body
        "ws_keepalive" -> R.string.info_ws_keepalive_title to R.string.info_ws_keepalive_body
        "vpn_core_info" -> R.string.info_vpn_system_title to R.string.info_vpn_system_body
        "vpn_splittunnel_info" -> R.string.info_vpn_splittunnel_title to R.string.info_vpn_splittunnel_body
        "custom_wallpaper_info", "customization_info" -> R.string.settings_customization_info_title to R.string.settings_customization_info_desc
        else -> return
    }
    val dlgTitle = stringResource(dlgTitleRes)
    val dlgBody = stringResource(dlgBodyRes)
    InfoDialog(title = dlgTitle, body = dlgBody, onDismiss = onDismiss)
}

@Composable
private fun SettingsMtprotoCdnOverviewCard() {
    Column(
        modifier = Modifier.staggeredEntrance(index = 4),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.settings_mtproto_routing_section),
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.3.sp,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color.Transparent,
            border = BorderStroke(1.dp, AmoledBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_mtproto_routing_title),
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite
                )
                Text(
                    text = stringResource(R.string.settings_mtproto_routing_body),
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    color = TextMuted,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}


/**
 * Inline card for MTProto Cloudflare Worker tunneling toggle.
 * Adheres strictly to MirrlyUI (Zero-Popup, Outline-Only, AMOLED black, MtprotoAccent).
 */
@Composable
private fun SettingsMtprotoWorkerCard(
    useWorker: Boolean,
    activeWorker: com.mirrly.tgproxy.core.WorkerProfile,
    onToggleWorker: (Boolean) -> Unit,
    onOpenWorkerManager: () -> Unit
) {
    val haptic = LocalHapticFeedback.current

    Column(
        modifier = Modifier.staggeredEntrance(index = 3),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color.Transparent,
            border = BorderStroke(1.dp, AmoledBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.settings_mtproto_worker_title),
                            color = TextWhite,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.5.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.settings_mtproto_worker_desc),
                            color = TextMuted,
                            fontSize = 11.5.sp,
                            lineHeight = 15.sp
                        )
                    }

                    InertialSpringSwitch(
                        checked = useWorker,
                        onCheckedChange = { checked ->
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onToggleWorker(checked)
                        },
                        activeColor = MtprotoAccent
                    )
                }

                // Active Uplink status chip
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .border(
                            1.dp,
                            if (useWorker && activeWorker.domain.isNotBlank()) MtprotoAccent.copy(alpha = 0.35f) else AmoledBorder,
                            RoundedCornerShape(10.dp)
                        )
                        .springPress(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onOpenWorkerManager()
                        })
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (useWorker && activeWorker.domain.isNotBlank()) MtprotoAccent else TextMuted)
                        )
                        Text(
                            text = if (useWorker && activeWorker.domain.isNotBlank()) {
                                stringResource(R.string.settings_mtproto_worker_active, activeWorker.name)
                            } else {
                                stringResource(R.string.settings_mtproto_worker_cdn)
                            },
                            color = if (useWorker && activeWorker.domain.isNotBlank()) MtprotoAccent else TextMuted,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Icon(
                        painter = painterResource(id = R.drawable.ic_chevron_right),
                        contentDescription = null,
                        tint = if (useWorker && activeWorker.domain.isNotBlank()) MtprotoAccent else TextMuted,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
        }
    }
}
