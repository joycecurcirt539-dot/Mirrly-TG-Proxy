package com.mirrly.tgproxy.ui

import android.content.Intent
import android.os.Build
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.core.ProxyConfig
import com.mirrly.tgproxy.service.ProxyForegroundService
import com.mirrly.tgproxy.ui.theme.*
import android.net.Uri
import android.widget.Toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun InertialSpringSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
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
                .background(Color.White)
        )
    }
}

/** Маленькая кнопка-подсказка «ⓘ» рядом с заголовком настройки */
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
            fontWeight = FontWeight.Black,
            fontStyle = FontStyle.Italic,
            lineHeight = 10.sp
        )
    }
}

/** Всплывающее диалоговое окно с подробным описанием настройки */
@Composable
fun InfoDialog(title: String, body: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0D1117),
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                text = title,
                color = ActiveGreenLed,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        },
        text = {
            Text(
                text = body,
                color = TextWhite.copy(alpha = 0.85f),
                fontSize = 14.sp,
                lineHeight = 22.sp
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = "Понятно",
                    color = ActiveGreenLed,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAbout: () -> Unit = {}
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val app = MirrlyApplication.instance
    val config = app.config
    val server = app.proxyServer

    var portText by remember { mutableStateOf(config.bindPort.toString()) }
    val isPortError by remember {
        derivedStateOf {
            portText.toIntOrNull()?.let { it < 1 || it > 65535 } ?: portText.isNotEmpty()
        }
    }
    var secretText by remember { mutableStateOf(config.secretHex) }
    var showSecret by remember { mutableStateOf(false) }
    var customDomainText by remember { mutableStateOf(config.customCfDomain) }

    val poolOptions = remember { listOf(2f, 4f, 8f, 16f) }
    var poolSize by remember { mutableFloatStateOf(config.poolSize.toFloat()) }
    var autostart by remember { mutableStateOf(config.autostartOnBoot) }
    // Key of the setting whose info dialog is currently open (null = closed)
    var infoKey by remember { mutableStateOf<String?>(null) }
    var pendingUpdateRelease by remember { mutableStateOf<com.mirrly.tgproxy.core.ReleaseInfo?>(null) }

    fun snapToNearestPool(valIn: Float): Float {
        return poolOptions.minByOrNull { abs(it - valIn) } ?: valIn
    }

    fun restartProxyIfNeeded() {
        app.saveConfig()
        if (server.isRunning) {
            val serviceIntent = Intent(context, ProxyForegroundService::class.java).apply {
                action = ProxyForegroundService.ACTION_RESTART
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

    // Info dialog: show detailed description for the currently selected setting key
    infoKey?.let { key ->
        val (dlgTitle, dlgBody) = when (key) {
            "port" -> "Порт подключения" to
                "Локальный TCP-порт, на котором прокси принимает подключения от Telegram.\n\nРекомендованное значение: 1443. Этот порт не требует прав root и обычно не занят другими приложениями.\n\nДопустимый диапазон: 1–65535. Если порт занят — прокси не запустится. После смены порта обновите настройки прокси в Telegram."
            "secret" -> "Секретный ключ (MTProto)" to
                "32-символьный hex-ключ протокола MTProto. Его необходимо указать в настройках прокси Telegram — без него подключение невозможно.\n\nПрефикс dd означает режим Fake TLS: трафик маскируется под обычный HTTPS, что позволяет обходить DPI-фильтрацию.\n\nНажмите 🔄 для генерации нового случайного ключа. После смены обновите ссылку-приглашение в Telegram."
            "cf_domain" -> "Кастомный домен Cloudflare Worker" to
                "Адрес вашего Cloudflare Worker, через который проходит весь трафик до серверов Telegram.\n\nЕсли поле пустое — используется встроенный домен Mirrly. Собственный домен обеспечивает максимальную независимость и надёжность.\n\nФормат: worker.mydomain.workers.dev или ваш домен, привязанный к Cloudflare Worker."
            "pool" -> "Размер пула сокетов" to
                "Количество WebSocket-соединений, которые прокси держит открытыми заранее (pre-warming).\n\nБольший пул = меньше задержка при открытии чата:\n• 2 — минимальный расход батареи и RAM\n• 4 — баланс скорости и экономии (рекомендуется)\n• 8 — быстрый отклик, умеренный расход\n• 16 — максимальная скорость, повышенный расход\n\nКаждое соединение занимает ~50–100 КБ RAM и поддерживает фоновое соединение с Cloudflare."
            "autostart" -> "Автозапуск при загрузке" to
                "Прокси автоматически запустится после перезагрузки устройства — не нужно включать вручную.\n\nРаботает через системный сигнал BOOT_COMPLETED.\n\nВажно: на устройствах MIUI, HyperOS и OneUI может потребоваться дополнительное разрешение «Автозапуск» в системных настройках телефона, иначе система заблокирует запуск."
            "reconnect" -> "Авто-переподключение" to
                "При смене сети (Wi-Fi → LTE и обратно) прокси автоматически перезапускает соединения.\n\nБез этой функции Telegram может «зависать» на несколько секунд при переходе между сетями.\n\nФункция отслеживает изменения через NetworkCallback Android и перезапускает прокси только при реальной смене сети, а не временных потерях сигнала."
            else -> return@let
        }
        InfoDialog(title = dlgTitle, body = dlgBody, onDismiss = { infoKey = null })
    }

    // Update Dialog display when release update is found
    pendingUpdateRelease?.let { release ->
        UpdateDialog(
            releaseInfo = release,
            onDismiss = { pendingUpdateRelease = null }
        )
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // 1. SCROLLABLE CONTENT LAYER (Scrolls ALL THE WAY to the top under the Frosted Header!)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .fadingEdges(topFadeHeight = 24.dp, bottomFadeHeight = 44.dp)
                .verticalScroll(rememberScrollState())
                .padding(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 20.dp
                )
                .padding(horizontal = 22.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {

            // SECTION 1: Сеть
            Column(
                modifier = Modifier.staggeredEntrance(index = 0),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "СЕТЬ",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.3.sp,
                    color = TextMuted
                )

                // Port Input
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("Порт подключения", color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        InfoButton { infoKey = "port" }
                    }
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = isPortError,
                        shape = RoundedCornerShape(14.dp),
                        supportingText = if (isPortError) {
                            { Text("Введите число от 1 до 65535", color = Color(0xFFEF4444), fontSize = 12.sp) }
                        } else null,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedBorderColor = if (isPortError) Color(0xFFEF4444) else ActiveGreenLed,
                            unfocusedBorderColor = if (isPortError) Color(0xFFEF4444) else Color(0xFF1E2333),
                            focusedTextColor = TextWhite,
                            unfocusedTextColor = TextWhite,
                            errorBorderColor = Color(0xFFEF4444),
                            errorTextColor = TextWhite
                        )
                    )
                }

                // Secret Key Input
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("Секретный ключ (Hex)", color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        InfoButton { infoKey = "secret" }
                    }
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
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
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
            Column(
                modifier = Modifier.staggeredEntrance(index = 1),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "CLOUDFLARE TUNNEL",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.3.sp,
                    color = TextMuted
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("Кастомный домен воркера", color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        InfoButton { infoKey = "cf_domain" }
                    }
                    OutlinedTextField(
                        value = customDomainText,
                        onValueChange = { customDomainText = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("worker.mydomain.workers.dev (опционально)", color = TextMuted, fontSize = 13.sp) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
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
            Column(
                modifier = Modifier.staggeredEntrance(index = 2),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "СИСТЕМА",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.3.sp,
                    color = TextMuted
                )

                // Ultra-Smooth Dragging Socket Pool Slider with Snap-on-Release & Haptics
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("Размер пула сокетов", color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            InfoButton { infoKey = "pool" }
                        }

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
                            val oldSnap = snapToNearestPool(poolSize).toInt()
                            val newSnap = snapToNearestPool(newValue).toInt()
                            if (oldSnap != newSnap) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            poolSize = newValue
                        },
                        onValueChangeFinished = {
                            val snapped = snapToNearestPool(poolSize)
                            poolSize = snapped
                            val newSize = snapped.toInt()
                            if (newSize != config.poolSize) {
                                server.applyPoolSize(newSize)
                                app.saveConfig()
                            }
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        valueRange = 2f..16f,
                        colors = SliderDefaults.colors(
                            thumbColor = TextWhite,
                            activeTrackColor = ActiveGreenLed,
                            inactiveTrackColor = Color(0xFF1E2333)
                        )
                    )

                    // Pool step labels — positioned exactly under each slider thumb stop
                    // Values [2,4,8,16] are NOT equally spaced on 2..16 scale:
                    //   2→0%, 4→14.3%, 8→42.9%, 16→100%
                    // BoxWithConstraints + layout modifier centers each label at its exact position.
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        // Material3 Slider thumb diameter = 20.dp → half = 10.dp
                        // Thumb center travels from 10.dp to (sliderWidth - 10.dp)
                        val thumbHalfWidth = 10.dp
                        val usableWidth = maxWidth - thumbHalfWidth * 2

                        poolOptions.forEach { option ->
                            val fraction = (option - 2f) / 14f // range is 2..16 = span 14

                            val isSelected = snapToNearestPool(poolSize).toInt() == option.toInt()
                            val optionColor by animateColorAsState(
                                targetValue = if (isSelected) ActiveGreenLed else TextMuted,
                                animationSpec = tween(200),
                                label = "optionColor$option"
                            )

                            Text(
                                text = "${option.toInt()}",
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = optionColor,
                                modifier = Modifier
                                    .layout { measurable, constraints ->
                                        val placeable = measurable.measure(
                                            constraints.copy(minWidth = 0, minHeight = 0)
                                        )
                                        // Center the label under its thumb position in pixels
                                        val centerXPx = (thumbHalfWidth + usableWidth * fraction).toPx()
                                        layout(placeable.width, placeable.height) {
                                            placeable.place(
                                                x = (centerXPx - placeable.width / 2f).toInt(),
                                                y = 0
                                            )
                                        }
                                    }
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        poolSize = option
                                        val newSize = option.toInt()
                                        if (newSize != config.poolSize) {
                                            server.applyPoolSize(newSize)
                                            app.saveConfig()
                                        }
                                    }
                            )
                        }
                    }
                }

                // Autostart Switch with Inertia Bounce Physics & Haptics
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("Автозапуск при загрузке", color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        InfoButton { infoKey = "autostart" }
                    }
                    InertialSpringSwitch(
                        checked = autostart,
                        onCheckedChange = { newValue ->
                            autostart = newValue
                            config.autostartOnBoot = newValue
                            app.saveConfig()
                        }
                    )
                }

                // Auto Reconnect Switch with Inertia Bounce Physics & Haptics
                var autoReconnect by remember { mutableStateOf(app.prefsManager.isAutoReconnectEnabled()) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("Авто-переподключение (Wi-Fi / LTE)", color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        InfoButton { infoKey = "reconnect" }
                    }
                    InertialSpringSwitch(
                        checked = autoReconnect,
                        onCheckedChange = { newValue ->
                            autoReconnect = newValue
                            app.prefsManager.setAutoReconnectEnabled(newValue)
                        }
                    )
                }
            }

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF161A26)))

            // SECTION 4: О Приложении & Разработчике
            Column(
                modifier = Modifier.staggeredEntrance(index = 3),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "О ПРИЛОЖЕНИИ",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.3.sp,
                    color = TextMuted
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Transparent)
                        .border(1.dp, Color(0xFF181E2E), RoundedCornerShape(16.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onOpenAbout()
                        }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.Transparent)
                                .border(1.dp, ActiveGreenLed.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_user),
                                contentDescription = null,
                                tint = ActiveGreenLed,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Column {
                            Text(
                                text = "О разработчике & Проекте",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite
                            )
                            Text(
                                text = "R1Xern • Информация, соцсети и поддержка",
                                fontSize = 12.sp,
                                color = TextMuted
                            )
                        }
                    }

                    Icon(
                        painter = painterResource(id = R.drawable.ic_chevron_right),
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Check for Updates Row Item
                var isCheckingUpdate by remember { mutableStateOf(false) }
                val coroutineScope = rememberCoroutineScope()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Transparent)
                        .border(1.dp, Color(0xFF181E2E), RoundedCornerShape(16.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            if (!isCheckingUpdate) {
                                isCheckingUpdate = true
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                coroutineScope.launch {
                                    val result = com.mirrly.tgproxy.core.UpdateChecker.checkForUpdates()
                                    isCheckingUpdate = false
                                    result.fold(
                                        onSuccess = { info ->
                                            if (info.isUpdateAvailable) {
                                                pendingUpdateRelease = info
                                            } else {
                                                Toast.makeText(
                                                    context,
                                                    "У вас установлена актуальная версия v${com.mirrly.tgproxy.core.UpdateChecker.CURRENT_VERSION_NAME}",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        },
                                        onFailure = { err ->
                                            Toast.makeText(
                                                context,
                                                "Ошибка проверки обновлений: ${err.localizedMessage}",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    )
                                }
                            }
                        }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.Transparent)
                                .border(1.dp, ActiveGreenLed.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_refresh),
                                contentDescription = null,
                                tint = ActiveGreenLed,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Column {
                            Text(
                                text = "Проверить обновления",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite
                            )
                            Text(
                                text = if (isCheckingUpdate) "Проверка GitHub Releases..." else "Текущая версия v1.0.3",
                                fontSize = 12.sp,
                                color = TextMuted
                            )
                        }
                    }

                    if (isCheckingUpdate) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = ActiveGreenLed,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_chevron_right),
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // 2. FROSTED GLASS HEADER PANEL (Pinned at Top over scrolling items!)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.98f), // Pure AMOLED black behind status bar
                            Color.Black.copy(alpha = 0.94f), // Pure AMOLED black behind title
                            Color.Black.copy(alpha = 0.72f), // Pure AMOLED black blur transition
                            Color.Black.copy(alpha = 0.00f)  // Soft fade edge to reveal blurred scrolling items
                        )
                    )
                )
        ) {
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
                    IconButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onBack()
                    }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_arrow_left),
                            contentDescription = "Назад",
                            tint = TextWhite,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    }
}
