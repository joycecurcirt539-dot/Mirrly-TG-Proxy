package com.mirrly.tgproxy.ui

import android.content.Intent
import android.os.Build
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.core.ProxyConfig
import com.mirrly.tgproxy.service.ProxyForegroundService
import com.mirrly.tgproxy.ui.theme.*
import kotlinx.coroutines.delay
import kotlin.math.abs

@Composable
fun InertialSpringSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val targetOffset = if (checked) 22.dp else 2.dp
    val animOffset by animateDpAsState(
        targetValue = targetOffset,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "switchInertiaOffset"
    )

    val trackColor by animateColorAsState(
        targetValue = if (checked) ActiveGreenLed else Color(0xFF181C28),
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
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                onCheckedChange(!checked)
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .offset(x = animOffset.coerceAtLeast(0.dp))
                .size(22.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val app = MirrlyApplication.instance
    val config = app.config
    val server = app.proxyServer

    var portText by remember { mutableStateOf(config.bindPort.toString()) }
    var secretText by remember { mutableStateOf(config.secretHex) }
    var showSecret by remember { mutableStateOf(false) }
    var customDomainText by remember { mutableStateOf(config.customCfDomain) }

    val poolOptions = remember { listOf(2f, 4f, 8f, 16f) }
    var poolSize by remember { mutableFloatStateOf(config.poolSize.toFloat()) }
    var autostart by remember { mutableStateOf(config.autostartOnBoot) }

    fun snapToNearestPool(valIn: Float): Float {
        return poolOptions.minByOrNull { abs(it - valIn) } ?: valIn
    }

    fun restartProxyIfNeeded() {
        app.saveConfig()
        if (server.isRunning) {
            val serviceIntent = Intent(context, ProxyForegroundService::class.java).apply {
                action = ProxyForegroundService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }

    // Debounced auto-save for text fields to prevent typing lag
    LaunchedEffect(portText) {
        delay(600)
        val p = portText.toIntOrNull()
        if (p != null && p in 1..65535 && p != config.bindPort) {
            config.bindPort = p
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

    LaunchedEffect(customDomainText) {
        delay(600)
        val trimmed = customDomainText.trim()
        if (trimmed != config.customCfDomain) {
            config.customCfDomain = trimmed
            restartProxyIfNeeded()
        }
    }

    val pureBlack = Color(0xFF000000)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Настройки",
                        color = TextWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_arrow_left),
                            contentDescription = "Назад",
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
                .padding(horizontal = 22.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {

            // SECTION 1: Сеть
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = "СЕТЬ",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.3.sp,
                    color = TextMuted
                )

                // Port Input
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Порт подключения", color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = pureBlack,
                            unfocusedContainerColor = pureBlack,
                            focusedBorderColor = ActiveGreenLed,
                            unfocusedBorderColor = Color(0xFF1E2333),
                            focusedTextColor = TextWhite,
                            unfocusedTextColor = TextWhite
                        )
                    )
                }

                // Secret Key Input
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Секретный ключ (Hex)", color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    OutlinedTextField(
                        value = secretText,
                        onValueChange = { secretText = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(0.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(end = 4.dp)
                            ) {
                                IconButton(
                                    onClick = {
                                        showSecret = !showSecret
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Crossfade(targetState = showSecret, animationSpec = tween(180), label = "eyeFade") { isVisible ->
                                        Icon(
                                            painter = painterResource(id = if (isVisible) R.drawable.ic_eye_slash else R.drawable.ic_eye),
                                            contentDescription = null,
                                            tint = if (isVisible) ActiveGreenLed else TextMuted,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        val newSecret = ProxyConfig.generateRandomSecret()
                                        secretText = newSecret
                                        config.secretHex = newSecret
                                        restartProxyIfNeeded()
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_refresh),
                                        contentDescription = null,
                                        tint = TextWhite,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = pureBlack,
                            unfocusedContainerColor = pureBlack,
                            focusedBorderColor = ActiveGreenLed,
                            unfocusedBorderColor = Color(0xFF1E2333),
                            focusedTextColor = TextWhite,
                            unfocusedTextColor = TextWhite
                        )
                    )
                }
            }

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF161A26)))

            // SECTION 2: Cloudflare (Always Enabled)
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = "CLOUDFLARE TUNNEL",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.3.sp,
                    color = TextMuted
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Кастомный домен воркера", color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    OutlinedTextField(
                        value = customDomainText,
                        onValueChange = { customDomainText = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("worker.mydomain.workers.dev (опционально)", color = TextMuted, fontSize = 13.sp) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = pureBlack,
                            unfocusedContainerColor = pureBlack,
                            focusedBorderColor = ActiveGreenLed,
                            unfocusedBorderColor = Color(0xFF1E2333),
                            focusedTextColor = TextWhite,
                            unfocusedTextColor = TextWhite
                        )
                    )
                }
            }

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF161A26)))

            // SECTION 3: Система & Оптимизация
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = "СИСТЕМА",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.3.sp,
                    color = TextMuted
                )

                // Ultra-Smooth Dragging Socket Pool Slider with Snap-on-Release
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Размер пула сокетов", color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)

                        val activeDisplayValue = snapToNearestPool(poolSize).toInt()
                        Text(
                            text = "$activeDisplayValue сокетов",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = ActiveGreenLed
                        )
                    }

                    Slider(
                        value = poolSize,
                        onValueChange = { newValue ->
                            poolSize = newValue
                        },
                        onValueChangeFinished = {
                            val snapped = snapToNearestPool(poolSize)
                            poolSize = snapped
                            if (snapped.toInt() != config.poolSize) {
                                config.poolSize = snapped.toInt()
                                restartProxyIfNeeded()
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        },
                        valueRange = 2f..16f,
                        colors = SliderDefaults.colors(
                            thumbColor = TextWhite,
                            activeTrackColor = ActiveGreenLed,
                            inactiveTrackColor = Color(0xFF1E2333)
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        poolOptions.forEach { option ->
                            val isSelected = snapToNearestPool(poolSize).toInt() == option.toInt()
                            val optionColor by animateColorAsState(
                                targetValue = if (isSelected) ActiveGreenLed else TextMuted,
                                animationSpec = tween(200),
                                label = "optionColor"
                            )
                            Text(
                                text = "${option.toInt()}",
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = optionColor
                            )
                        }
                    }
                }

                // Autostart Switch with Inertia Bounce Physics
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Автозапуск при загрузке", color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    InertialSpringSwitch(
                        checked = autostart,
                        onCheckedChange = { newValue ->
                            autostart = newValue
                            config.autostartOnBoot = newValue
                            app.saveConfig()
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    )
                }

                // Auto Reconnect Switch with Inertia Bounce Physics
                var autoReconnect by remember { mutableStateOf(app.prefsManager.isAutoReconnectEnabled()) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Авто-переподключение (Wi-Fi / LTE)", color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    InertialSpringSwitch(
                        checked = autoReconnect,
                        onCheckedChange = { newValue ->
                            autoReconnect = newValue
                            app.prefsManager.setAutoReconnectEnabled(newValue)
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
