package com.mirrly.tgproxy.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.service.ProxyForegroundService
import com.mirrly.tgproxy.service.humanBytes
import com.mirrly.tgproxy.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenLogs: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val app = MirrlyApplication.instance
    val server = app.proxyServer

    var isRunning by remember { mutableStateOf(server.isRunning) }
    var dlSpeed by remember { mutableStateOf("0 Б/с") }
    var ulSpeed by remember { mutableStateOf("0 Б/с") }
    var activeConns by remember { mutableIntStateOf(0) }
    var totalRecv by remember { mutableStateOf("0 Б") }
    var totalSent by remember { mutableStateOf("0 Б") }
    var uptimeSeconds by remember { mutableLongStateOf(0L) }

    // Execute stats calculation on IO thread off the main looper to eliminate main thread frame delay
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            while (isActive) {
                val running = server.isRunning
                val uptime = server.uptimeSeconds
                var dl = "0 Б/с"
                var ul = "0 Б/с"
                var conns = 0
                var recv = "0 Б"
                var sent = "0 Б"

                if (running) {
                    val stats = server.stats
                    dl = "${humanBytes(stats.downloadSpeedBps)}/с"
                    ul = "${humanBytes(stats.uploadSpeedBps)}/с"
                    conns = stats.activeConnections.get()
                    recv = humanBytes(stats.totalBytesReceived.get())
                    sent = humanBytes(stats.totalBytesSent.get())
                }

                withContext(Dispatchers.Main) {
                    isRunning = running
                    uptimeSeconds = uptime
                    dlSpeed = dl
                    ulSpeed = ul
                    activeConns = conns
                    totalRecv = recv
                    totalSent = sent
                }
                delay(1000)
            }
        }
    }

    fun formatUptime(secs: Long): String {
        val h = secs / 3600
        val m = (secs % 3600) / 60
        val s = secs % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    // Slow & smooth breathing pulse glow for active power icon
    val infiniteTransition = rememberInfiniteTransition(label = "pulseGlow")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.22f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.20f,
        targetValue = 0.70f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val pureBlack = Color(0xFF000000)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Mirrly - TG Proxy",
                        color = TextWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        letterSpacing = 0.8.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onOpenLogs) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_logs),
                            contentDescription = "Логи",
                            tint = TextWhite,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_settings),
                            contentDescription = "Настройки",
                            tint = TextWhite,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = pureBlack)
            )
        },
        containerColor = pureBlack
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(pureBlack)
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {

            // Center Power Icon (Standalone Hero Element)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            val serviceIntent = Intent(context, ProxyForegroundService::class.java)
                            if (isRunning) {
                                serviceIntent.action = ProxyForegroundService.ACTION_STOP
                                context.startService(serviceIntent)
                            } else {
                                serviceIntent.action = ProxyForegroundService.ACTION_START
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    context.startForegroundService(serviceIntent)
                                } else {
                                    context.startService(serviceIntent)
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    // Neon Aura Glow behind power icon when active
                    if (isRunning) {
                        Box(
                            modifier = Modifier
                                .size(220.dp * pulseScale)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(
                                            ActiveGreenLed.copy(alpha = pulseAlpha * 0.38f),
                                            ActiveGreenLed.copy(alpha = pulseAlpha * 0.10f),
                                            Color.Transparent
                                        )
                                    )
                                )
                        )
                    }

                    // Standalone Iconsax Power Icon (200dp)
                    Icon(
                        painter = painterResource(id = R.drawable.ic_power),
                        contentDescription = "Включение прокси",
                        tint = if (isRunning) ActiveGreenLed else Color(0xFF353C4F),
                        modifier = Modifier.size(200.dp)
                    )
                }
            }

            // Lower Section (Always visible, rich layout)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Timer
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(bottom = 6.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (isRunning) ActiveGreenLed else Color(0xFF353C4F),
                        modifier = Modifier.size(6.dp)
                    ) {}
                    Text(
                        text = if (isRunning) formatUptime(uptimeSeconds) else "00:00:00",
                        color = if (isRunning) ActiveGreenLed else TextMuted,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.1.sp
                    )
                }

                // Compact Network / IP / Socket info line
                val ipPortText = if (isRunning) {
                    "127.0.0.1:${app.config.bindPort} • Сокеты: $activeConns/${app.config.poolSize}"
                } else {
                    "127.0.0.1:${app.config.bindPort} • Не подключено"
                }
                Text(
                    text = ipPortText,
                    color = TextMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )

                if (app.config.cfProxyEnabled) {
                    val cfLabel = if (app.config.customCfDomain.isNotBlank()) {
                        "Cloudflare Active (${app.config.customCfDomain.trim()})"
                    } else {
                        "Cloudflare Proxy Active"
                    }
                    Text(
                        text = cfLabel,
                        color = if (isRunning) ActiveGreenLed else TextMuted.copy(alpha = 0.6f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Telemetry Download & Upload speeds (50/50 centered)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Download Speed & Total (Weight 1f)
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_arrow_down),
                                contentDescription = null,
                                tint = if (isRunning) ActiveGreenLed else TextMuted,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Входящий", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = dlSpeed,
                            color = if (isRunning) TextWhite else TextMuted,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                        Text("Всего: $totalRecv", color = TextMuted, fontSize = 11.sp)
                    }

                    // Divider line (50.0% centered)
                    Box(
                        modifier = Modifier
                            .height(32.dp)
                            .width(1.dp)
                            .background(Color(0xFF1F2433))
                    )

                    // Upload Speed & Total (Weight 1f)
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_arrow_up),
                                contentDescription = null,
                                tint = TextMuted,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Исходящий", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = ulSpeed,
                            color = if (isRunning) TextWhite else TextMuted,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                        Text("Всего: $totalSent", color = TextMuted, fontSize = 11.sp)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Action Buttons Dock (Always visible, requiring Proxy ON)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Left Action: Copy Link
                    Surface(
                        onClick = {
                            if (!isRunning) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                Toast.makeText(context, "⚠️ Включите прокси сначала!", Toast.LENGTH_SHORT).show()
                            } else {
                                val tgUrl = server.getTelegramProxyUrl()
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Telegram Proxy", tgUrl)
                                clipboard.setPrimaryClip(clip)
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                Toast.makeText(context, "Ссылка скопирована!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = Color.Transparent,
                        border = BorderStroke(
                            1.dp,
                            if (isRunning) Color(0xFF1F2433) else Color(0xFF161A26)
                        )
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_copy),
                                contentDescription = "Скопировать",
                                tint = if (isRunning) TextWhite else TextMuted.copy(alpha = 0.5f),
                                modifier = Modifier.size(19.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Скопировать",
                                color = if (isRunning) TextWhite else TextMuted.copy(alpha = 0.5f),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    }

                    // Right Action: Apply to Telegram
                    Surface(
                        onClick = {
                            if (!isRunning) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                Toast.makeText(context, "⚠️ Включите прокси сначала!", Toast.LENGTH_SHORT).show()
                            } else {
                                val tgUrl = server.getTelegramProxyUrl()
                                applyToTelegramPackages(context, tgUrl)
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = Color.Transparent,
                        border = BorderStroke(
                            1.dp,
                            if (isRunning) Color(0xFF1F2433) else Color(0xFF161A26)
                        )
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_send),
                                contentDescription = "В Telegram",
                                tint = if (isRunning) ActiveGreenLed else TextMuted.copy(alpha = 0.5f),
                                modifier = Modifier.size(19.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "В Telegram",
                                color = if (isRunning) TextWhite else TextMuted.copy(alpha = 0.5f),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

private val telegramPackages = listOf(
    "org.telegram.messenger",
    "com.radolyn.ayugram",
    "com.exteragram.messenger",
    "org.telegram.plus",
    "ir.ilmili.telegraph",
    "org.telegram.BifToGram",
    "tw.nekomimi.nekogram",
    "xyz.nextalone.nagram",
    "uz.unnarsx.cherrygram",
    "org.telegram.mdgram",
    "org.forkclient.messenger.beta",
    "app.nicegram",
    "top.qwq2333.nullgram",
    "com.iMe.android",
    "ru.dahl.messenger",
    "com.scriptsaz.litegram",
    "org.thunderdog.challegram"
)

private fun applyToTelegramPackages(context: Context, url: String) {
    val pm = context.packageManager
    val availablePackages = telegramPackages.filter { pkg ->
        try {
            pm.getPackageInfo(pkg, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    val targetedIntents = availablePackages.map { pkg ->
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            setPackage(pkg)
        }
    }

    if (targetedIntents.isEmpty()) {
        val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        try {
            context.startActivity(fallbackIntent)
        } catch (_: Exception) {
            Toast.makeText(context, "Telegram клиент не найден", Toast.LENGTH_SHORT).show()
        }
    } else if (targetedIntents.size == 1) {
        val intent = targetedIntents.first().apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(context, "Ошибка открытия клиента", Toast.LENGTH_SHORT).show()
        }
    } else {
        val chooserIntent = Intent.createChooser(targetedIntents.first(), "Выберите клиент")
        chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, targetedIntents.drop(1).toTypedArray())
        chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(chooserIntent)
        } catch (_: Exception) {
            Toast.makeText(context, "Ошибка выбора клиента", Toast.LENGTH_SHORT).show()
        }
    }
}
