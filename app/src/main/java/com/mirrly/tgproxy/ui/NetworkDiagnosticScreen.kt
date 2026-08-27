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

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.isActive
import com.mirrly.tgproxy.MirrlyApplication
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.core.FailureType
import com.mirrly.tgproxy.core.PingHistoryPoint
import com.mirrly.tgproxy.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkDiagnosticScreen(
    onBack: () -> Unit,
    onOpenAnalytics: (() -> Unit)? = null
) {
    val haptic = LocalHapticFeedback.current
    val app = MirrlyApplication.instance
    val server = app.proxyServer

    val isSocks5 by app.prefsManager.isSocks5Flow.collectAsState()
    val activeWorker = remember(app.prefsManager.getActiveWorkerId()) { app.prefsManager.getActiveWorker() }

    val isProxyActive = server.isRunning

    var pingMs by remember { mutableLongStateOf(server.currentPingMs) }
    var jitterMs by remember { mutableLongStateOf(if (server.isRunning) server.stats.jitterMs else 0L) }
    var healthScore by remember { mutableIntStateOf(if (server.isRunning) server.stats.healthScore else 0) }
    var chatScore by remember { mutableIntStateOf(if (server.isRunning) server.stats.chatScore else 0) }
    var chatVerdict by remember { mutableStateOf(if (server.isRunning) server.stats.chatVerdict else "Прокси остановлен") }
    var callScore by remember { mutableIntStateOf(if (server.isRunning) server.stats.callScore else 0) }
    var verdict by remember { mutableStateOf(if (server.isRunning) server.stats.healthVerdict else "Прокси остановлен") }
    var verdictDetail by remember { mutableStateOf(if (server.isRunning) server.stats.healthDetail else "Запустите службу для автоматической оценки качества") }
    var successRate by remember { mutableIntStateOf(if (server.isRunning) server.stats.healthSuccessRate else 100) }
    var mosScore by remember { mutableDoubleStateOf(if (server.isRunning) server.stats.mosScore else 1.0) }
    var mosGrade by remember { mutableStateOf(if (server.isRunning) server.stats.mosGrade else "Нет связи") }
    var isCallRecommended by remember { mutableStateOf(if (server.isRunning) server.stats.isCallRecommended else false) }
    var minRttMs by remember { mutableLongStateOf(if (server.isRunning) server.stats.minRttMs else -1L) }
    var bufferbloatMs by remember { mutableLongStateOf(if (server.isRunning) server.stats.bufferbloatMs else 0L) }
    var bufferbloatGrade by remember { mutableStateOf(if (server.isRunning) server.stats.bufferbloatGrade else "—") }
    var currentAlpha by remember { mutableDoubleStateOf(if (server.isRunning) server.stats.currentAlpha else 0.25) }
    var rttHistory by remember { mutableStateOf<List<PingHistoryPoint>>(if (server.isRunning) server.stats.rttHistory else emptyList()) }
    var poolSize by remember { mutableIntStateOf(if (server.isRunning) server.config.poolSize else 2) }
    var lastFailureType by remember { mutableStateOf(if (server.isRunning) server.stats.lastFailureType else FailureType.NONE) }

    LaunchedEffect(isProxyActive) {
        while (isActive) {
            if (server.isRunning) {
                pingMs = server.currentPingMs
                jitterMs = server.stats.jitterMs
                healthScore = server.stats.healthScore
                chatScore = server.stats.chatScore
                chatVerdict = server.stats.chatVerdict
                callScore = server.stats.callScore
                verdict = server.stats.healthVerdict
                verdictDetail = server.stats.healthDetail
                successRate = server.stats.healthSuccessRate
                mosScore = server.stats.mosScore
                mosGrade = server.stats.mosGrade
                isCallRecommended = server.stats.isCallRecommended
                minRttMs = server.stats.minRttMs
                bufferbloatMs = server.stats.bufferbloatMs
                bufferbloatGrade = server.stats.bufferbloatGrade
                currentAlpha = server.stats.currentAlpha
                rttHistory = server.stats.rttHistory
                poolSize = server.config.poolSize
                lastFailureType = server.stats.lastFailureType
            } else {
                healthScore = 0
                chatScore = 0
                chatVerdict = "Прокси остановлен"
                callScore = 0
                verdict = "Прокси остановлен"
                verdictDetail = "Запустите службу для автоматической оценки качества"
                mosScore = 1.0
                mosGrade = "Прокси остановлен"
                isCallRecommended = false
                minRttMs = -1L
                bufferbloatMs = 0L
                bufferbloatGrade = "—"
                currentAlpha = 0.25
                rttHistory = emptyList()
                poolSize = 2
                lastFailureType = FailureType.NONE
            }
            kotlinx.coroutines.delay(500)
        }
    }

    var infoKey by remember { mutableStateOf<String?>(null) }

    val infoData = remember {
        mapOf(
            "sqi" to Pair(
                "Индекс качества сети (SQI)",
                """
                КАК РАБОТАЕТ:
                • Комплексный интегральный скоринг стабильности сетевого туннеля (от 0 до 100%), рассчитываемый в реальном времени.
                • Объединяет оценку качества для чатов/медиафайлов (55%) и аудио/видеозвонков (45%).

                ПОЧЕМУ ЭТО ВАЖНО:
                • Позволяет моментально определить пригодность соединения для всех сервисов Telegram.

                ОРИЕНТИРЫ И НОРМЫ:
                • 90–100%: идеальный прямой канал без задержек.
                • 75–89%: стабильное рабочее соединение.
                • 50–74%: задержки на стороне оператора связи.
                • Ниже 50%: деградация канала или потеря пакетов.
                """.trimIndent()
            ),
            "chats_sqi" to Pair(
                "Качество для чатов и медиа (TCP)",
                """
                КАК РАБОТАЕТ:
                • Оценивает надежность и скорость передачи текстовых сообщений, стикеров, фото и видеофайлов по защищенному протоколу TCP/TLS.
                • Алгоритм отдает 50% приоритета надежности доставки пакетов, 30% времени отклика (RTT) и 20% стабильности очередей.

                ПОЧЕМУ ЭТО ВАЖНО:
                • Для чатов и медиа задержка до 200 мс не ощущается, но критически важно отсутствие потерь пакетов и раздувания буферов оборудования.

                ОРИЕНТИРЫ И НОРМЫ:
                • 90–100%: идеальная мгновенная отправка и быстрая загрузка медиа.
                • 75–89%: стабильный комфортный обмен сообщениями.
                • 50–74%: умеренная скорость передачи тяжелых файлов.
                • Ниже 50%: задержки и сбои загрузки.
                """.trimIndent()
            ),
            "latency_sparkline" to Pair(
                "Динамика задержки (Sparkline 60 сек)",
                """
                КАК РАБОТАЕТ ГРАФИК:
                • Отображает историю каждого замера времени отклика (RTT) туннеля за последнюю минуту в реальном времени.
                • Зеленые точки — идеальный отклик (< 80 мс), синие — стандартный (80–180 мс), желтые — повышенный, красные — сетевой сбой или таймаут.

                ПОЧЕМУ ЭТО ВАЖНО:
                • Позволяет визуально выявить микро-скачки (Jitter Spikes), переключение сотовых вышек (Cell Handover) и нестабильность Wi-Fi.
                • Вы можете коснуться любой точки на графике пальцем для просмотра точного времени и задержки конкретного замера.
                """.trimIndent()
            ),
            "readiness_grid" to Pair(
                "Готовность сервисов Telegram",
                """
                КАК РАБОТАЕТ МАТРИЦА:
                • Анализирует физические сетевые метрики (задержку, джиттер, потери, размер буферов и пула сокетов) и оценивает готовность 4 ключевых типов сервисов Telegram в реальном времени.

                ТИПЫ СЕРВИСОВ:
                • Чаты и стикеры: готовность к мгновенной отправке сообщений без буферных задержек (< 80 мс).
                • Фото и голосовые: высокая скорость передачи медиапотока без потерь (>= 95%).
                • Файлы и 4K видео: пропускная способность пула сокетов и буфера (до 2 МБ).
                • Звонки и видео HD: качество аудиокодека Opus и отсутствие лагов видеосвязи.
                """.trimIndent()
            ),
            "readiness_text" to Pair(
                "Текстовые чаты и стикеры",
                """
                ТЕХНИЧЕСКИЕ ТРЕБОВАНИЯ:
                • Текстовый трафик передается короткими TCP/TLS пакетами (50–300 байт).
                • При задержке RTT < 80 мс отправка сообщений происходит мгновенно («одна галочка» появляется за доли секунды).
                • Включенный режим TCP_NODELAY исключает буферную паузу алгоритма Нагла.
                """.trimIndent()
            ),
            "readiness_media" to Pair(
                "Фото и голосовые сообщения",
                """
                ТЕХНИЧЕСКИЕ ТРЕБОВАНИЯ:
                • Требует стабильного потока со скоростью от 150 КБ/с и надежности доставки пакетов >= 95%.
                • Голосовые сообщения кэшируются фрагментами: при отсутствии джиттера воспроизведение начинается без пауз.
                """.trimIndent()
            ),
            "readiness_files" to Pair(
                "Тяжелые файлы и 4K видео",
                """
                ТЕХНИЧЕСКИЕ ТРЕБОВАНИЯ:
                • Для скачивания файлов гигабайтами и 4K-стриминга ядро автоматически масштабирует пул сокетов до 16 потоков и буфер до 2 МБ.
                • Контроль Bufferbloat предотвращает зависание оборудования при максимальной нагрузке канала.
                """.trimIndent()
            ),
            "readiness_calls" to Pair(
                "Голосовые и видеозвонки HD",
                """
                ТЕХНИЧЕСКИЕ ТРЕБОВАНИЯ:
                • Голосовой трафик кодируется широкополосным кодеком Opus (частота дискретизации 48 кГц).
                • Для идеального разговора без роботизации звука джиттер не должен превышать 15–25 мс, а потери пакетов — не более 2%.
                """.trimIndent()
            ),
            "network_path" to Pair(
                "Сетевой тракт и задержка",
                """
                КАК РАБОТАЕТ:
                • Диагностический модуль измеряет три ключевые метрики сетевого пути: задержку кругового прохождения сигнала (RTT), стабильность интервалов (джиттер) и процент успешно доставленных TCP/WSS сегментов.

                ПОЧЕМУ ЭТО ВАЖНО:
                • Низкая задержка обеспечивает мгновенный отклик приложения, а отсутствие джиттера исключает прерывания в звонках Telegram.
                """.trimIndent()
            ),
            "rtt" to Pair(
                "Задержка туннеля (EWMA RTT)",
                """
                КАК РАБОТАЕТ:
                • Экспоненциально сглаженное время кругового прохождения сигнала (Round-Trip Time) от смартфона до узла Cloudflare Edge по алгоритму RFC 6298.
                • Исключает случайные одиночные всплески и отражает реальную скорость отклика.

                ПОЧЕМУ ЭТО ВАЖНО:
                • Влияет на мгновенность появления статуса отправки сообщений, скорость загрузки профилей и списков диалогов.

                ОРИЕНТИРЫ И НОРМЫ:
                • До 80 мс: превосходный отклик.
                • 80–180 мс: стабильная работа в мобильных сетях.
                • Свыше 250 мс: возможны задержки при интерактивном общении.
                """.trimIndent()
            ),
            "jitter" to Pair(
                "Вариация задержки (LTE Jitter)",
                """
                КАК РАБОТАЕТ:
                • Разброс и колебание задержки между последовательными контрольными пакетами данных.

                ПОЧЕМУ ЭТО ВАЖНО:
                • Высокий джиттер возникает из-за нестабильности радиосигнала сотовой вышки (переключение вышек в движении, помехи, загрузка базовой станции).
                • Приводит к задержкам и прерываниям в аудио- и видеозвонках.

                ОРИЕНТИРЫ И НОРМЫ:
                • До 15 мс: стабильный радиоканал.
                • 15–40 мс: допустимая вариация мобильной сети.
                • Выше 50 мс: сильная нестабильность радиоканала.
                """.trimIndent()
            ),
            "delivery" to Pair(
                "Доставка контрольных пакетов",
                """
                КАК РАБОТАЕТ:
                • Процент успешно доставленных тестовых сетевых зондов до воркера за последние 10 циклов измерений.

                ПОЧЕМУ ЭТО ВАЖНО:
                • Потеря даже 5% пакетов заставляет сетевой стек TCP повторно отправлять сегменты (Retransmission), из-за чего скорость загрузки медиафайлов может падать в несколько раз.

                ОРИЕНТИРЫ И НОРМЫ:
                • 100%: идеальная доставка без потерь.
                • 90–99%: допустимо в мобильных сетях.
                • Ниже 80%: потеря пакетов на стороне провайдера связи.
                """.trimIndent()
            ),
            "localization" to Pair(
                "Локализация узких мест",
                """
                КАК РАБОТАЕТ:
                • Разделяет сетевой маршрут на два независимых участка: от смартфона до базовой станции (Last-Mile) и от базовой станции до Cloudflare Edge (Core).

                ПОЧЕМУ ЭТО ВАЖНО:
                • Помогает точно определить источник неполадок: вызван ли сбой слабым сигналом сотовой связи или недоступностью облачного узла.
                """.trimIndent()
            ),
            "bottleneck_radar" to Pair(
                "Радар узких мест (Hop-by-Hop)",
                """
                КАК РАБОТАЕТ ЦЕПОЧКА:
                • Отслеживает прохождение трафика Telegram через 5 ключевых узлов:
                  1. Устройство (:10808) — локальный native-прокси на Android.
                  2. Вышка / Wi-Fi — радиоканал последней мили.
                  3. Провайдер / DPI — магистраль оператора и системы ТСПУ/DPI.
                  4. Cloudflare Edge — облачный воркер и WSS-туннель.
                  5. Telegram DC — целевые дата-центры мессенджера (DC 1–5).

                ПОЧЕМУ ЭТО ВАЖНО:
                • При возникновении задержки система подсвечивает конкретный проблемный узел (желтым или красным), избавляя от гаданий.
                """.trimIndent()
            ),
            "smart_insights" to Pair(
                "Интеллектуальные рекомендации движка",
                """
                КАК РАБОТАЕТ СИСТЕМА СОВЕТОВ:
                • Аналитический модуль оценивает текущие параметры задержки, джиттера, раздувания буферов и потерь пакетов, формируя контекстные советы по улучшению качества связи в реальном времени.

                ОСНОВНЫЕ СЦЕНАРИИ:
                • Высокий джиттер мобильной сети: рекомендация переключиться на стабильный Wi-Fi для исключения прерываний в звонках.
                • Раздувание буфера (Bufferbloat): рекомендация разгрузить локальный роутер от фоновых скачиваний.
                • Магистральные задержки: предложение проверить узел Cloudflare Worker.
                """.trimIndent()
            ),
            "hop_device" to Pair(
                "Устройство (Локальный прокси)",
                """
                УЗЕЛ #1:
                • Приложение Mirrly TG Proxy запускает локальный прокси-сервер на порту 10808 (127.0.0.1).
                • Клиент Telegram перенаправляет весь свой трафик в этот сокет.
                """.trimIndent()
            ),
            "hop_isp" to Pair(
                "Провайдер связи и DPI",
                """
                УЗЕЛ #3:
                • Сегмент оператора связи (МТС, Билайн, Мегафон, Ростелеком и др.).
                • Прокси использует маскировку WSS и защищенный SNI, предотвращая блокировку ТСПУ/DPI middlebox-системами.
                """.trimIndent()
            ),
            "hop_tg_dc" to Pair(
                "Дата-центры Telegram",
                """
                УЗЕЛ #5:
                • Конечные серверы Telegram (DC 1 — Майами, DC 2/4 — Амстердам, DC 5 — Сингапур).
                • Модуль DC Affinity поддерживает выделенные сокеты для каждого дата-центра.
                """.trimIndent()
            ),
            "last_mile" to Pair(
                "Сегмент оператора связи (Last-Mile)",
                """
                КАК РАБОТАЕТ:
                • Участок сетевого тракта от радиомодуля вашего смартфона до ближайшей вышки сотовой связи или Wi-Fi роутера.

                ПОЧЕМУ ЭТО ВАЖНО:
                • Если у вас высокий джиттер, но воркер работает исправно — причина сбоев кроется в слабом сигнале сети смартфона, а не в прокси-сервере.

                ОРИЕНТИРЫ И НОРМЫ:
                • Статус «Норма» означает отсутствие помех и потерь пакетов на радиоканале.
                """.trimIndent()
            ),
            "cf_edge" to Pair(
                "Сегмент Cloudflare Worker (Core)",
                """
                КАК РАБОТАЕТ:
                • Состояние пограничного сервера Cloudflare Edge, обрабатывающего WebSocket туннелирование трафика и DNS-over-HTTPS.

                ПОЧЕМУ ЭТО ВАЖНО:
                • Показывает, не превышен ли суточный лимит запросов на воркере (HTTP 429) и не замедлен ли отклик инфраструктуры Cloudflare.

                ОРИЕНТИРЫ И НОРМЫ:
                • Статус «Доступен» подтверждает корректную передачу данных через защищенный сокет.
                """.trimIndent()
            ),
            "config" to Pair(
                "Конфигурация туннеля",
                """
                КАК РАБОТАЕТ:
                • Отображает текущие параметры активного туннеля: выбранный Cloudflare Worker, локальный порт прослушивания и криптографический режим.
                """.trimIndent()
            ),
            "active_worker" to Pair(
                "Активный узел туннелирования (Predictive Failover)",
                """
                КАК РАБОТАЕТ:
                • Конкретный узел Cloudflare Worker, через который в данный момент ретранслируется трафик Telegram.
                • Система непрерывно отслеживает тренд задержки и Bufferbloat. При монотонном росте RTT или деградации узла предиктивный триггер упреждающе переключает связь на здоровый резервный узел до полного обрыва.

                ПОЧЕМУ ЭТО ВАЖНО:
                • Защищает голосовые звонки и загрузку медиа от зависаний без необходимости перезапускать сокеты или ожидать жестких таймаутов.

                ОРИЕНТИРЫ И НОРМЫ:
                • Личный домен воркера имеет наивысший приоритет и не подвержен публичным нагрузкам.
                """.trimIndent()
            ),
            "protocol_mode" to Pair(
                "Протокол туннелирования",
                """
                КАК РАБОТАЕТ:
                • Режим работы локального прокси-сервера на устройстве.

                ПОЧЕМУ ЭТО ВАЖНО:
                • SOCKS5 (порт 10808) передает весь трафик Telegram (чаты, медиафайлы, голосовые и видеозвонки).
                • MTProto (порт 1443) использует криптографический протокол Telegram FakeTLS.

                ОРИЕНТИРЫ И НОРМЫ:
                • Оба протокола инкапсулируются в защищенный WebSocket-канал с шифрованием TLS 1.3.
                """.trimIndent()
            ),
            "mos" to Pair(
                "Качество голосовой связи (ITU-T MOS)",
                """
                КАК РАБОТАЕТ:
                • Стандартизированная оценка качества речи (Mean Opinion Score) по математической модели ITU-T G.107 E-Model в диапазоне от 1.00 до 4.50.
                • Учитывает эффективную одностороннюю задержку d = RTT/2 + 2*Jitter, фактор искажений широкополосного кодека Opus (Ie) и процент потери пакетов.

                ПОЧЕМУ ЭТО ВАЖНО:
                • Показывает, насколько комфортно будут проходить аудио- и видеозвонки в Telegram без эффекта эха, задержек и обрывов речи.

                ОРИЕНТИРЫ И НОРМЫ:
                • 4.20–4.50: HD Voice — идеальное студийное качество без задержек.
                • 3.80–4.19: Хорошее качество — комфортный естественный разговор.
                • 3.10–3.79: Приемлемо — минимальные задержки, голос разборчив.
                • 2.40–3.09: С помехами — возможны заикания и проглатывание слогов.
                • Ниже 2.40: Непригодно для звонков.
                """.trimIndent()
            ),
            "bufferbloat" to Pair(
                "Буферизация и Min-RTT (Bufferbloat)",
                """
                КАК РАБОТАЕТ:
                • Алгоритм скользящего окна Min-RTT (60 секунд, как в Google BBR) определяет чистую физическую задержку кабельного/радиоканала без очередей.
                • Индекс Bufferbloat (ΔRTT = SRTT - Min-RTT) вычисляет скрытое накопление сетевых пакетов в буферах оборудования оператора при активной передаче данных.
                • Динамический фильтр (адаптивный α от 0.125 до 0.50) подстраивает скорость сглаживания под текущую стабильность канала.

                ПОЧЕМУ ЭТО ВАЖНО:
                • Показывает, не раздуваются ли буферы сотовой вышки во время скачивания медиафайлов, что вызывает задержки и «залипание» отправки сообщений.

                ОРИЕНТИРЫ И НОРМЫ:
                • A+ (до 10 мс): идеальный канал без очередей.
                • A (10–30 мс): незначительное накопление буфера.
                • B (30–75 мс): умеренное раздувание буферов.
                • C (75–150 мс): повышенные задержки в очередях.
                • D (свыше 150 мс): тяжелый Bufferbloat.
                """.trimIndent()
            ),
            "math_model" to Pair(
                "Математическая модель SQI и ITU-T MOS",
                """
                ИНДЕКС СТАБИЛЬНОСТИ SQI (0–100%):
                • Нелинейная сигмоидальная функция полезности:
                  - 45% вес RTT (плавный спад свыше 60 мс).
                  - 25% вес LTE/Wi-Fi Jitter (штраф за вариацию свыше 10 мс).
                  - 30% вес надежности доставки зондов с степенным фактором потерь.
                • Вычитаются штрафы за блокировки DPI, сбои TLS и лимиты Cloudflare 429.

                ГОЛОСОВОЙ РЕЙТИНГ ITU-T G.107 (MOS 1.00–4.50):
                • Расчет фактора передачи R = 93.2 - Id(задержка) - Ie(потери Opus).
                • Полиномиальное преобразование R в шкалу восприятия речи MOS.
                """.trimIndent()
            ),
            "cf_quota" to Pair(
                "Расход квоты Cloudflare Workers",
                """
                ЛИМИТЫ БЕСПЛАТНОГО ТАРИФА:
                • Cloudflare предоставляет 100 000 бесплатных запросов в сутки.
                • WSS-туннель списывает 1 запрос только при старте, после чего трафик передается бесплатно.
                • В приложении доступен интерактивный график аналитики за сессию, 1ч, 5ч, 12ч, 24ч, 7д и 30д.
                """.trimIndent()
            )
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
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
            // ── HERO: DUAL QUALITY GAUGES (CHATS VS CALLS) ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .staggeredEntrance(index = 0),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1. LEFT HERO CARD: ЧАТЫ И МЕДИА
                val chatColor = when {
                    chatScore >= 90 -> Color(0xFF00FF87)
                    chatScore >= 75 -> Color(0xFF00E676)
                    chatScore >= 50 -> Color(0xFFFFB703)
                    else -> Color(0xFFFF0055)
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Transparent)
                        .border(1.dp, Color(0xFF1E2333), RoundedCornerShape(20.dp))
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            infoKey = "chats_sqi"
                        }
                        .padding(14.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "ЧАТЫ И МЕДИА",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.1.sp,
                                color = TextMuted
                            )
                            InfoButton { infoKey = "chats_sqi" }
                        }

                        LiquidWaveQualityCircle(
                            score = chatScore,
                            isProxyActive = isProxyActive,
                            isSocks5 = isSocks5,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                infoKey = "chats_sqi"
                            },
                            modifier = Modifier.size(64.dp)
                        )

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = if (isProxyActive) "$chatScore%" else "—",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Black,
                                color = TextWhite
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (isProxyActive) chatVerdict else "Остановлен",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = chatColor,
                                maxLines = 1
                            )
                        }
                    }
                }

                // 2. RIGHT HERO CARD: ЗВОНКИ И ВИДЕО
                val mosColor = when {
                    mosScore >= 4.20 -> Color(0xFF00FF87)
                    mosScore >= 3.80 -> Color(0xFF38BDF8)
                    mosScore >= 3.10 -> Color(0xFFB388FF)
                    mosScore >= 2.40 -> Color(0xFFFFB703)
                    else -> Color(0xFFFF0055)
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Transparent)
                        .border(1.dp, Color(0xFF1E2333), RoundedCornerShape(20.dp))
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            infoKey = "mos"
                        }
                        .padding(14.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "ЗВОНКИ И ВИДЕО",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.1.sp,
                                color = TextMuted
                            )
                            InfoButton { infoKey = "mos" }
                        }

                        LiquidWaveQualityCircle(
                            score = callScore,
                            isProxyActive = isProxyActive,
                            isSocks5 = isSocks5,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                infoKey = "mos"
                            },
                            modifier = Modifier.size(64.dp)
                        )

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = if (isProxyActive) String.format(java.util.Locale.US, "%.2f MOS", mosScore) else "—",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Black,
                                color = TextWhite
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (isProxyActive) mosGrade else "Остановлен",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = mosColor,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            // ── SMART NETWORK INSIGHTS (RECOMMENDATION BANNER) ──
            SmartNetworkInsightsCard(
                pingMs = pingMs,
                jitterMs = jitterMs,
                bufferbloatMs = bufferbloatMs,
                successRate = successRate,
                healthScore = healthScore,
                chatScore = chatScore,
                mosScore = mosScore,
                lastFailureType = lastFailureType,
                isProxyActive = isProxyActive,
                onInfoClick = { infoKey = "smart_insights" }
            )

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF161A26)))

            // ── LIVE LATENCY SPARKLINE GRAPH (30-60 SEC) ──
            LiveLatencySparklineCard(
                rttHistory = rttHistory,
                currentPingMs = pingMs,
                isProxyActive = isProxyActive,
                isSocks5 = isSocks5,
                onInfoClick = { infoKey = "latency_sparkline" }
            )

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF161A26)))

            // ── TELEGRAM SERVICES READINESS GRID ──
            ContentReadinessGrid(
                pingMs = pingMs,
                jitterMs = jitterMs,
                successRate = successRate,
                bufferbloatMs = bufferbloatMs,
                mosScore = mosScore,
                poolSize = poolSize,
                isProxyActive = isProxyActive,
                onItemClick = { key -> infoKey = key },
                onInfoClick = { infoKey = "readiness_grid" }
            )

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF161A26)))

            // ── SECTION 1: СЕТЕВОЙ ТРАКТ И ЗАДЕРЖКА ──
            Column(
                modifier = Modifier.staggeredEntrance(index = 3),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "СЕТЕВОЙ ТРАКТ И ЗАДЕРЖКА",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.3.sp,
                        color = TextMuted
                    )
                    InfoButton { infoKey = "network_path" }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Transparent)
                        .border(1.dp, Color(0xFF1E2333), RoundedCornerShape(20.dp))
                ) {
                    Column {
                        DiagnosticMetricRow(
                            iconRes = R.drawable.ic_diag_rtt,
                            iconColor = Color(0xFF38BDF8),
                            title = "Задержка туннеля (EWMA RTT)",
                            value = if (pingMs > 0) "$pingMs мс" else "—",
                            badgeText = if (pingMs in 1..100) "Отлично" else if (pingMs <= 200) "В норме" else "Повышен",
                            badgeColor = if (pingMs in 1..100) Color(0xFF00FF87) else if (pingMs <= 200) Color(0xFF00E676) else Color(0xFFFFB703),
                            onInfoClick = { infoKey = "rtt" }
                        )

                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF141824)))

                        DiagnosticMetricRow(
                            iconRes = R.drawable.ic_diag_jitter,
                            iconColor = Color(0xFFB388FF),
                            title = "Вариация задержки (LTE Jitter)",
                            value = "±$jitterMs мс",
                            badgeText = if (jitterMs <= 15) "Стабильно" else if (jitterMs <= 35) "Умеренно" else "Нестабильно",
                            badgeColor = if (jitterMs <= 15) Color(0xFF00FF87) else if (jitterMs <= 35) Color(0xFF00E676) else Color(0xFFFFB703),
                            onInfoClick = { infoKey = "jitter" }
                        )

                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF141824)))

                        DiagnosticMetricRow(
                            iconRes = R.drawable.ic_diag_delivery,
                            iconColor = Color(0xFF00FF87),
                            title = "Доставка контрольных пакетов",
                            value = "$successRate%",
                            badgeText = if (successRate >= 95) "100% норма" else if (successRate >= 75) "Потери пакетов" else "Сбои",
                            badgeColor = if (successRate >= 95) Color(0xFF00FF87) else if (successRate >= 75) Color(0xFFFFB703) else Color(0xFFFF0055),
                            onInfoClick = { infoKey = "delivery" }
                        )

                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF141824)))

                        val mosBadgeColor = when {
                            mosScore >= 4.20 -> Color(0xFF00FF87)
                            mosScore >= 3.80 -> Color(0xFF00E676)
                            mosScore >= 3.10 -> Color(0xFFFFB703)
                            else -> Color(0xFFFF0055)
                        }

                        DiagnosticMetricRow(
                            iconRes = R.drawable.ic_diag_voip,
                            iconColor = Color(0xFF38BDF8),
                            title = "Качество звонков (ITU-T MOS)",
                            value = if (isProxyActive && pingMs > 0) "${String.format(java.util.Locale.US, "%.2f", mosScore)} / 4.50 (${mosGrade})" else "—",
                            badgeText = if (isProxyActive && pingMs > 0) (if (isCallRecommended) "HD Voice" else "Помехи") else "Ожидание",
                            badgeColor = if (isProxyActive && pingMs > 0) mosBadgeColor else Color(0xFF38BDF8),
                            onInfoClick = { infoKey = "mos" }
                        )
                    }
                }
            }

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF161A26)))

            // ── SECTION 2: ЛОКАЛИЗАЦИЯ УЗКИХ МЕСТ (HOP-BY-HOP RADAR) ──
            Column(
                modifier = Modifier.staggeredEntrance(index = 4),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HopByHopBottleneckRadar(
                    pingMs = pingMs,
                    jitterMs = jitterMs,
                    bufferbloatMs = bufferbloatMs,
                    successRate = successRate,
                    lastFailureType = lastFailureType,
                    isProxyActive = isProxyActive,
                    isSocks5 = isSocks5,
                    onInfoClick = { infoKey = "bottleneck_radar" },
                    onHopClick = { key -> infoKey = key }
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Transparent)
                        .border(1.dp, Color(0xFF1E2333), RoundedCornerShape(20.dp))
                ) {
                    Column {
                        DiagnosticMetricRow(
                            iconRes = R.drawable.ic_diag_antenna,
                            iconColor = Color(0xFF00E676),
                            title = "Сегмент оператора / Wi-Fi",
                            value = if (jitterMs <= 25) "Радиоканал стабилен" else "Помехи вышки / Wi-Fi",
                            badgeText = if (jitterMs <= 25) "Норма" else "Внимание",
                            badgeColor = if (jitterMs <= 25) Color(0xFF00FF87) else Color(0xFFFFB703),
                            onInfoClick = { infoKey = "last_mile" }
                        )

                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF141824)))

                        DiagnosticMetricRow(
                            iconRes = R.drawable.ic_diag_cloudflare,
                            iconColor = Color(0xFF38BDF8),
                            title = "Сегмент Cloudflare Worker",
                            value = if (pingMs > 0) "Туннель WSS активен" else "Ожидание пробы",
                            badgeText = if (pingMs in 1..250) "Доступен" else if (pingMs > 250) "Задержка" else "Сбой",
                            badgeColor = if (pingMs in 1..250) Color(0xFF00FF87) else Color(0xFFFFB703),
                            onInfoClick = { infoKey = "cf_edge" }
                        )

                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF141824)))

                        val bloatBadgeColor = when {
                            bufferbloatGrade.startsWith("A+") -> Color(0xFF00FF87)
                            bufferbloatGrade.startsWith("A") -> Color(0xFF00E676)
                            bufferbloatGrade.startsWith("B") -> Color(0xFF38BDF8)
                            bufferbloatGrade.startsWith("C") -> Color(0xFFFFB703)
                            else -> Color(0xFFFF0055)
                        }

                        DiagnosticMetricRow(
                            iconRes = R.drawable.ic_diag_bufferbloat,
                            iconColor = Color(0xFFFFB703),
                            title = "Буферизация (Bufferbloat)",
                            value = if (isProxyActive && minRttMs > 0) "+${bufferbloatMs} мс (Min: ${minRttMs} мс, α=${String.format(java.util.Locale.US, "%.2f", currentAlpha)})" else "—",
                            badgeText = if (isProxyActive && minRttMs > 0) bufferbloatGrade else "Ожидание",
                            badgeColor = if (isProxyActive && minRttMs > 0) bloatBadgeColor else Color(0xFFFFB703),
                            onInfoClick = { infoKey = "bufferbloat" }
                        )
                    }
                }
            }

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF161A26)))

            // ── SECTION 3: КОНФИГУРАЦИЯ ТУННЕЛЯ ──
            Column(
                modifier = Modifier.staggeredEntrance(index = 3),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "КОНФИГУРАЦИЯ ТУННЕЛЯ",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.3.sp,
                        color = TextMuted
                    )
                    InfoButton { infoKey = "config" }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Transparent)
                        .border(1.dp, Color(0xFF1E2333), RoundedCornerShape(20.dp))
                ) {
                    Column {
                        // In developer worker: do NOT print internal CF worker domain!
                        val workerDisplayValue = if (activeWorker.isDeveloperWorker) {
                            activeWorker.name
                        } else {
                            "${activeWorker.name} (${activeWorker.domain})"
                        }

                        DiagnosticMetricRow(
                            iconRes = R.drawable.ic_diag_worker,
                            iconColor = Color(0xFFB388FF),
                            title = "Активный узел туннелирования",
                            value = workerDisplayValue,
                            badgeText = if (activeWorker.isDeveloperWorker) "Встроенный" else "Личный",
                            badgeColor = Color(0xFF38BDF8),
                            onInfoClick = { infoKey = "active_worker" }
                        )

                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF141824)))

                        DiagnosticMetricRow(
                            iconRes = R.drawable.ic_diag_protocol,
                            iconColor = if (isSocks5) Color(0xFF818CF8) else Color(0xFF00FF87),
                            title = "Протокол и локальный порт",
                            value = if (isSocks5) "SOCKS5 TCP Relay (:10808)" else "MTProto TLS Relay (:1443)",
                            badgeText = "WSS TLS 1.3",
                            badgeColor = if (isSocks5) Color(0xFF818CF8) else Color(0xFF00FF87),
                            onInfoClick = { infoKey = "protocol_mode" }
                        )

                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF141824)))

                        DiagnosticMetricRow(
                            iconRes = R.drawable.ic_diag_formula,
                            iconColor = if (isSocks5) Color(0xFF818CF8) else Color(0xFF00FF87),
                            title = "Расход квоты Cloudflare",
                            value = "График и аналитика запросов",
                            badgeText = "100k / день",
                            badgeColor = if (isSocks5) Color(0xFF818CF8) else Color(0xFF00FF87),
                            onInfoClick = {
                                if (onOpenAnalytics != null) {
                                    onOpenAnalytics()
                                } else {
                                    infoKey = "cf_quota"
                                }
                            }
                        )
                    }
                }
            }

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF161A26)))

            // ── SECTION 4: МАТЕМАТИЧЕСКАЯ МОДЕЛЬ SQI ──
            Column(
                modifier = Modifier.staggeredEntrance(index = 4),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "МАТЕМАТИЧЕСКАЯ МОДЕЛЬ SQI & ITU-T MOS",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.3.sp,
                        color = TextMuted
                    )
                    InfoButton { infoKey = "math_model" }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Transparent)
                        .border(1.dp, Color(0xFF1E2333), RoundedCornerShape(20.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            infoKey = "math_model"
                        }
                        .padding(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_diag_formula),
                                contentDescription = null,
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Нелинейная модель SQI и E-Model",
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite
                            )
                        }

                        Text(
                            text = "Индекс SQI (0–100%) рассчитывается непрерывной нелинейной функцией восприятия задержки (RTT 45% + Jitter 25% + Доставка 30%). Оценка качества звонков вычисляется по стандарту ITU-T G.107 (шкала MOS 1.00–4.50 для широкополосного кодека Opus).",
                            color = TextMuted,
                            fontSize = 12.sp,
                            lineHeight = 16.5.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
        }

        // Top Bar
        NetworkDiagnosticTopBar(
            isSocks5 = isSocks5,
            onBack = onBack
        )

        // Floating Cyber Particles Overlay
        CyberParticlesOverlay(
            modifier = Modifier.fillMaxSize(),
            particleCount = 10,
            alphaMultiplier = 0.40f
        )

        // Detailed Explanation Modal Dialog
        infoKey?.let { key ->
            val info = infoData[key]
            if (info != null) {
                InfoDialog(
                    title = info.first,
                    body = info.second,
                    onDismiss = { infoKey = null }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NetworkDiagnosticTopBar(
    isSocks5: Boolean,
    onBack: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.98f),
                        Color.Black.copy(alpha = 0.94f),
                        Color.Black.copy(alpha = 0.72f),
                        Color.Black.copy(alpha = 0.00f)
                    )
                )
            )
    ) {
        TopAppBar(
            title = {
                Text(
                    text = "Качество сети",
                    color = TextWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    maxLines = 1
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
            actions = {
                Box(
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSocks5) Color(0xFF818CF8).copy(alpha = 0.12f) else Color(0xFF00FF87).copy(alpha = 0.12f))
                        .border(
                            0.8.dp,
                            if (isSocks5) Color(0xFF818CF8).copy(alpha = 0.45f) else Color(0xFF00FF87).copy(alpha = 0.45f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (isSocks5) "SOCKS5" else "MTProto",
                        color = if (isSocks5) Color(0xFF818CF8) else Color(0xFF00FF87),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
        )
    }
}

@Composable
fun DiagnosticMetricRow(
    iconRes: Int,
    iconColor: Color,
    title: String,
    value: String,
    badgeText: String,
    badgeColor: Color,
    onInfoClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onInfoClick()
            }
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
                    .background(iconColor.copy(alpha = 0.12f))
                    .border(1.dp, iconColor.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
            ) {
                Icon(
                    painter = painterResource(id = iconRes),
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(16.dp)
                )
            }

            Column {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite
                )
                Text(
                    text = value,
                    fontSize = 11.5.sp,
                    color = TextMuted
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(badgeColor.copy(alpha = 0.12f))
                    .border(0.8.dp, badgeColor.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            ) {
                Text(
                    text = badgeText,
                    color = badgeColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            InfoButton { onInfoClick() }
        }
    }
}

@Composable
fun LiveLatencySparklineCard(
    rttHistory: List<PingHistoryPoint>,
    currentPingMs: Long,
    isProxyActive: Boolean,
    isSocks5: Boolean,
    onInfoClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val primaryColor = if (isSocks5) Color(0xFF818CF8) else Color(0xFF00FF87)
    var selectedIndex by remember { mutableStateOf<Int?>(null) }

    val validPoints = remember(rttHistory) {
        rttHistory.filter { it.rttMs > 0L }
    }

    val minRtt = remember(validPoints) { validPoints.minOfOrNull { it.rttMs } ?: 0L }
    val maxRtt = remember(validPoints) { validPoints.maxOfOrNull { it.rttMs } ?: 0L }
    val avgRtt = remember(validPoints) {
        if (validPoints.isNotEmpty()) validPoints.map { it.rttMs }.average().toLong() else 0L
    }

    Column(
        modifier = modifier.staggeredEntrance(index = 1),
        verticalArrangement = Arrangement.spacedBy(12.dp)
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
                Text(
                    text = "ДИНАМИКА ЗАДЕРЖКИ (60 СЕК)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.3.sp,
                    color = TextMuted
                )
                InfoButton(onClick = onInfoClick)
            }

            if (isProxyActive && validPoints.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(primaryColor.copy(alpha = 0.12f))
                        .border(0.8.dp, primaryColor.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "Live: ${if (currentPingMs > 0) "$currentPingMs мс" else "—"}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = primaryColor
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Transparent)
                .border(1.dp, Color(0xFF1E2333), RoundedCornerShape(20.dp))
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Top Summary Metrics
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SparklineStatPill(
                        label = "МИН",
                        value = if (isProxyActive && minRtt > 0) "$minRtt мс" else "—",
                        color = Color(0xFF00FF87)
                    )
                    SparklineStatPill(
                        label = "СРЕДНЕЕ",
                        value = if (isProxyActive && avgRtt > 0) "$avgRtt мс" else "—",
                        color = Color(0xFF38BDF8)
                    )
                    SparklineStatPill(
                        label = "МАКС",
                        value = if (isProxyActive && maxRtt > 0) "$maxRtt мс" else "—",
                        color = if (maxRtt > 200) Color(0xFFFF0055) else if (maxRtt > 120) Color(0xFFFFB703) else Color(0xFF00FF87)
                    )
                    SparklineStatPill(
                        label = "ЗАМЕРОВ",
                        value = if (isProxyActive) "${rttHistory.size}" else "0",
                        color = TextMuted
                    )
                }

                // Interactive Canvas Chart
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                ) {
                    if (!isProxyActive || rttHistory.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (!isProxyActive) "Прокси остановлен" else "Сбор первых контрольных проб задержки...",
                                fontSize = 12.sp,
                                color = TextMuted,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    } else {
                        val points = rttHistory
                        val path = remember { Path() }
                        val fillPath = remember { Path() }

                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(points) {
                                    detectTapGestures(
                                        onPress = { offset ->
                                            val idx = findClosestPointIndex(offset.x, size.width.toFloat(), points.size)
                                            selectedIndex = idx
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            tryAwaitRelease()
                                            selectedIndex = null
                                        }
                                    )
                                }
                                .pointerInput(points) {
                                    detectDragGestures(
                                        onDragStart = { offset ->
                                            val idx = findClosestPointIndex(offset.x, size.width.toFloat(), points.size)
                                            selectedIndex = idx
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        },
                                        onDragEnd = { selectedIndex = null },
                                        onDragCancel = { selectedIndex = null },
                                        onDrag = { change, _ ->
                                            val idx = findClosestPointIndex(change.position.x, size.width.toFloat(), points.size)
                                            if (idx != selectedIndex) {
                                                selectedIndex = idx
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                        }
                                    )
                                }
                        ) {
                            val w = size.width
                            val h = size.height
                            val n = points.size

                            val maxVal = maxOf(100L, (points.maxOfOrNull { it.rttMs } ?: 100L) + 20L).toFloat()
                            val minVal = 0f

                            // 1. Guidelines (50ms, 100ms)
                            val guide50Y = h * (1f - (50f / maxVal).coerceIn(0f, 1f))
                            drawLine(
                                color = Color(0xFF1E2333),
                                start = Offset(0f, guide50Y),
                                end = Offset(w, guide50Y),
                                strokeWidth = 1.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                            )

                            val guide100Y = h * (1f - (100f / maxVal).coerceIn(0f, 1f))
                            drawLine(
                                color = Color(0xFF1E2333),
                                start = Offset(0f, guide100Y),
                                end = Offset(w, guide100Y),
                                strokeWidth = 1.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                            )

                            // 2. Compute Coordinates
                            val coords = points.mapIndexed { idx, pt ->
                                val x = if (n > 1) (idx.toFloat() / (n - 1)) * w else w / 2f
                                val rtt = if (pt.rttMs > 0) pt.rttMs.toFloat() else maxVal
                                val y = (h * (1f - ((rtt - minVal) / (maxVal - minVal)))).coerceIn(4f, h - 4f)
                                Offset(x, y)
                            }

                            // 3. Draw Spline/Line Path
                            path.reset()
                            fillPath.reset()

                            if (coords.isNotEmpty()) {
                                path.moveTo(coords[0].x, coords[0].y)
                                fillPath.moveTo(coords[0].x, h)
                                fillPath.lineTo(coords[0].x, coords[0].y)

                                for (i in 0 until coords.size - 1) {
                                    val p0 = coords[i]
                                    val p1 = coords[i + 1]
                                    val midX = (p0.x + p1.x) / 2f
                                    path.cubicTo(midX, p0.y, midX, p1.y, p1.x, p1.y)
                                    fillPath.cubicTo(midX, p0.y, midX, p1.y, p1.x, p1.y)
                                }

                                fillPath.lineTo(coords.last().x, h)
                                fillPath.close()

                                // Gradient fill under curve
                                drawPath(
                                    path = fillPath,
                                    brush = Brush.verticalGradient(
                                        colors = listOf(
                                            primaryColor.copy(alpha = 0.25f),
                                            primaryColor.copy(alpha = 0.02f),
                                            Color.Transparent
                                        ),
                                        startY = 0f,
                                        endY = h
                                    )
                                )

                                // Stroke line
                                drawPath(
                                    path = path,
                                    color = primaryColor,
                                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                                )

                                // Draw Dots
                                coords.forEachIndexed { idx, pt ->
                                    val pointData = points[idx]
                                    val isHovered = selectedIndex == idx
                                    val dotColor = when {
                                        !pointData.isSuccess || pointData.rttMs <= 0 -> Color(0xFFFF0055)
                                        pointData.rttMs <= 80 -> Color(0xFF00FF87)
                                        pointData.rttMs <= 180 -> Color(0xFF38BDF8)
                                        else -> Color(0xFFFFB703)
                                    }

                                    if (isHovered) {
                                        drawCircle(
                                            color = dotColor.copy(alpha = 0.40f),
                                            radius = 7.dp.toPx(),
                                            center = pt
                                        )
                                        drawCircle(
                                            color = Color.White,
                                            radius = 4.dp.toPx(),
                                            center = pt
                                        )
                                    } else if (n <= 25 || idx == n - 1 || idx % 2 == 0) {
                                        drawCircle(
                                            color = dotColor,
                                            radius = 2.5.dp.toPx(),
                                            center = pt
                                        )
                                    }
                                }
                            }

                            // 4. Guideline for Scrubber
                            selectedIndex?.let { idx ->
                                if (idx in coords.indices) {
                                    val sel = coords[idx]
                                    drawLine(
                                        color = Color.White.copy(alpha = 0.40f),
                                        start = Offset(sel.x, 0f),
                                        end = Offset(sel.x, h),
                                        strokeWidth = 1.dp.toPx(),
                                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                                    )
                                }
                            }
                        }

                        // Scrubber Tooltip
                        selectedIndex?.let { idx ->
                            if (idx in points.indices) {
                                val pt = points[idx]
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF090D16).copy(alpha = 0.94f))
                                        .border(1.dp, primaryColor.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = if (pt.isSuccess) "Замер #${idx + 1}: ${pt.rttMs} мс" else "Замер #${idx + 1}: Сбой / Таймаут",
                                        color = if (pt.isSuccess) TextWhite else Color(0xFFFF0055),
                                        fontSize = 11.sp,
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
}

@Composable
private fun SparklineStatPill(
    label: String,
    value: String,
    color: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            fontSize = 9.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.8.sp,
            color = TextMuted
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

private fun findClosestPointIndex(touchX: Float, totalWidth: Float, pointCount: Int): Int {
    if (pointCount <= 1 || totalWidth <= 0f) return 0
    val fraction = (touchX / totalWidth).coerceIn(0f, 1f)
    return (fraction * (pointCount - 1)).toInt().coerceIn(0, pointCount - 1)
}

@Composable
fun ContentReadinessGrid(
    pingMs: Long,
    jitterMs: Long,
    successRate: Int,
    bufferbloatMs: Long,
    mosScore: Double,
    poolSize: Int,
    isProxyActive: Boolean,
    onItemClick: (String) -> Unit,
    onInfoClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 1. Текстовые чаты
    val textStatus = when {
        !isProxyActive -> "Прокси остановлен"
        pingMs in 1..80 -> "Мгновенно (<80 мс)"
        pingMs in 81..180 -> "Быстро (норма)"
        pingMs > 180 -> "С задержкой"
        else -> "Ожидание"
    }
    val textBadge = when {
        !isProxyActive -> "Остановлен"
        pingMs in 1..80 -> "0% задержка"
        pingMs in 81..180 -> "Норма"
        else -> "Задержка"
    }
    val textBadgeColor = when {
        !isProxyActive -> TextMuted
        pingMs in 1..80 -> Color(0xFF00FF87)
        pingMs in 81..180 -> Color(0xFF38BDF8)
        else -> Color(0xFFFFB703)
    }

    // 2. Фото и медиа
    val mediaStatus = when {
        !isProxyActive -> "Прокси остановлен"
        successRate >= 95 && jitterMs <= 25 -> "Высокая скорость"
        successRate >= 75 -> "Стабильная загрузка"
        else -> "Потери пакетов"
    }
    val mediaBadge = when {
        !isProxyActive -> "Остановлен"
        successRate >= 95 -> "Без потерь"
        successRate >= 75 -> "Буферизация"
        else -> "Сбои"
    }
    val mediaBadgeColor = when {
        !isProxyActive -> TextMuted
        successRate >= 95 -> Color(0xFF00FF87)
        successRate >= 75 -> Color(0xFFFFB703)
        else -> Color(0xFFFF0055)
    }

    // 3. Тяжелые файлы и 4K
    val filesStatus = when {
        !isProxyActive -> "Прокси остановлен"
        bufferbloatMs <= 25 && poolSize >= 4 -> "Turbo поток (Пул: $poolSize)"
        bufferbloatMs <= 75 -> "Стандартный поток"
        else -> "Ограничение очередей"
    }
    val filesBadge = when {
        !isProxyActive -> "Остановлен"
        bufferbloatMs <= 25 -> "2 МБ буфер"
        bufferbloatMs <= 75 -> "Balanced"
        else -> "Bufferbloat"
    }
    val filesBadgeColor = when {
        !isProxyActive -> TextMuted
        bufferbloatMs <= 25 -> Color(0xFF818CF8)
        bufferbloatMs <= 75 -> Color(0xFF38BDF8)
        else -> Color(0xFFFFB703)
    }

    // 4. Звонки и видео
    val callsStatus = when {
        !isProxyActive -> "Прокси остановлен"
        mosScore >= 4.20 -> "HD Voice (Opus 48k)"
        mosScore >= 3.80 -> "Хорошая разборчивость"
        mosScore >= 3.10 -> "Приемлемое аудио"
        else -> "Не рекомендуется"
    }
    val callsBadge = when {
        !isProxyActive -> "Остановлен"
        mosScore >= 4.20 -> "HD 1080p"
        mosScore >= 3.80 -> "HD 720p"
        mosScore >= 3.10 -> "SD звонок"
        else -> "Помехи"
    }
    val callsBadgeColor = when {
        !isProxyActive -> TextMuted
        mosScore >= 4.20 -> Color(0xFF00FF87)
        mosScore >= 3.80 -> Color(0xFF38BDF8)
        mosScore >= 3.10 -> Color(0xFFFFB703)
        else -> Color(0xFFFF0055)
    }

    Column(
        modifier = modifier.staggeredEntrance(index = 2),
        verticalArrangement = Arrangement.spacedBy(12.dp)
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
                Text(
                    text = "ГОТОВНОСТЬ СЕРВИСОВ TELEGRAM",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.3.sp,
                    color = TextMuted
                )
                InfoButton(onClick = onInfoClick)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ContentReadinessItem(
                    iconRes = R.drawable.ic_send,
                    iconColor = Color(0xFF38BDF8),
                    title = "Чаты и стикеры",
                    statusText = textStatus,
                    badgeText = textBadge,
                    badgeColor = textBadgeColor,
                    onClick = { onItemClick("readiness_text") },
                    modifier = Modifier.weight(1f)
                )

                ContentReadinessItem(
                    iconRes = R.drawable.ic_diag_media,
                    iconColor = Color(0xFF00FF87),
                    title = "Фото и голосовые",
                    statusText = mediaStatus,
                    badgeText = mediaBadge,
                    badgeColor = mediaBadgeColor,
                    onClick = { onItemClick("readiness_media") },
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ContentReadinessItem(
                    iconRes = R.drawable.ic_diag_files,
                    iconColor = Color(0xFFB388FF),
                    title = "Файлы и 4K видео",
                    statusText = filesStatus,
                    badgeText = filesBadge,
                    badgeColor = filesBadgeColor,
                    onClick = { onItemClick("readiness_files") },
                    modifier = Modifier.weight(1f)
                )

                ContentReadinessItem(
                    iconRes = R.drawable.ic_diag_voip,
                    iconColor = Color(0xFF818CF8),
                    title = "Звонки и видео HD",
                    statusText = callsStatus,
                    badgeText = callsBadge,
                    badgeColor = callsBadgeColor,
                    onClick = { onItemClick("readiness_calls") },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun ContentReadinessItem(
    iconRes: Int,
    iconColor: Color,
    title: String,
    statusText: String,
    badgeText: String,
    badgeColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Transparent)
            .border(1.dp, Color(0xFF1E2333), RoundedCornerShape(16.dp))
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(iconColor.copy(alpha = 0.12f))
                        .border(0.8.dp, iconColor.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                ) {
                    Icon(
                        painter = painterResource(id = iconRes),
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(15.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(badgeColor.copy(alpha = 0.12f))
                        .border(0.6.dp, badgeColor.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = badgeText,
                        color = badgeColor,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Column {
                Text(
                    text = title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(1.dp))
                Text(
                    text = statusText,
                    fontSize = 10.5.sp,
                    color = TextMuted,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
fun HopByHopBottleneckRadar(
    pingMs: Long,
    jitterMs: Long,
    bufferbloatMs: Long,
    successRate: Int,
    lastFailureType: FailureType,
    isProxyActive: Boolean,
    isSocks5: Boolean,
    onInfoClick: () -> Unit,
    onHopClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val primaryColor = if (isSocks5) Color(0xFF818CF8) else Color(0xFF00FF87)

    // Hop States: 0 = Off, 1 = Ok, 2 = Warn, 3 = Error
    val hop1State = if (isProxyActive) 1 else 0

    val hop2State = when {
        !isProxyActive -> 0
        jitterMs > 45L || bufferbloatMs >= 150L -> 2
        jitterMs > 25L || bufferbloatMs >= 75L -> 2
        else -> 1
    }

    val hop3State = when {
        !isProxyActive -> 0
        lastFailureType == FailureType.DPI_BLOCKED -> 3
        successRate < 75 -> 2
        else -> 1
    }

    val hop4State = when {
        !isProxyActive -> 0
        lastFailureType == FailureType.TLS_HANDSHAKE_FAILED || lastFailureType == FailureType.RATE_LIMITED_429 -> 3
        pingMs > 250L -> 2
        else -> 1
    }

    val hop5State = when {
        !isProxyActive -> 0
        lastFailureType != FailureType.NONE -> 2
        else -> 1
    }

    val (bottleneckTitle, bottleneckColor) = when {
        !isProxyActive -> Pair("Прокси остановлен", TextMuted)
        lastFailureType == FailureType.DPI_BLOCKED -> Pair("Узкое место: DPI фильтрация оператора", Color(0xFFFF0055))
        lastFailureType == FailureType.TLS_HANDSHAKE_FAILED -> Pair("Узкое место: Сбой TLS рукопожатия узла", Color(0xFFFF0055))
        lastFailureType == FailureType.RATE_LIMITED_429 -> Pair("Узкое место: Лимит запросов воркера (429)", Color(0xFFFF0055))
        jitterMs > 40L -> Pair("Узкое место: Радиоканал (LTE Jitter: ±${jitterMs}мс)", Color(0xFFFFB703))
        bufferbloatMs >= 100L -> Pair("Узкое место: Раздувание буфера роутера (+${bufferbloatMs}мс)", Color(0xFFFFB703))
        pingMs > 250L -> Pair("Узкое место: Магистральная задержка (${pingMs}мс)", Color(0xFFFFB703))
        successRate < 85 -> Pair("Узкое место: Потери пакетов на маршруте", Color(0xFFFFB703))
        else -> Pair("Узких мест нет • Тракт чист и стабилен", Color(0xFF00FF87))
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "РАДАР УЗКИХ МЕСТ (HOP-BY-HOP)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.3.sp,
                    color = TextMuted
                )
                InfoButton(onClick = onInfoClick)
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(bottleneckColor.copy(alpha = 0.12f))
                    .border(0.8.dp, bottleneckColor.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text(
                    text = if (isProxyActive) (if (bottleneckColor == Color(0xFF00FF87)) "Тракт OK" else "Диагноз") else "Остановлен",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = bottleneckColor
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Transparent)
                .border(1.dp, Color(0xFF1E2333), RoundedCornerShape(20.dp))
                .padding(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 1. Horizontal 5-Hop Chain
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    HopNode(
                        label = "Клиент",
                        subLabel = ":10808",
                        state = hop1State,
                        primaryColor = primaryColor,
                        onClick = { onHopClick("hop_device") }
                    )
                    HopLink(isActive = isProxyActive, isAlert = hop2State >= 2)
                    HopNode(
                        label = "Вышка/Wi-Fi",
                        subLabel = if (isProxyActive && jitterMs > 0) "±${jitterMs}мс" else "Радио",
                        state = hop2State,
                        primaryColor = primaryColor,
                        onClick = { onHopClick("last_mile") }
                    )
                    HopLink(isActive = isProxyActive, isAlert = hop3State >= 2)
                    HopNode(
                        label = "Оператор",
                        subLabel = if (hop3State == 3) "DPI" else "ISP",
                        state = hop3State,
                        primaryColor = primaryColor,
                        onClick = { onHopClick("hop_isp") }
                    )
                    HopLink(isActive = isProxyActive, isAlert = hop4State >= 2)
                    HopNode(
                        label = "Cloudflare",
                        subLabel = if (isProxyActive && pingMs > 0) "${pingMs}мс" else "WSS",
                        state = hop4State,
                        primaryColor = primaryColor,
                        onClick = { onHopClick("cf_edge") }
                    )
                    HopLink(isActive = isProxyActive, isAlert = hop5State >= 2)
                    HopNode(
                        label = "Telegram",
                        subLabel = "DC1-5",
                        state = hop5State,
                        primaryColor = primaryColor,
                        onClick = { onHopClick("hop_tg_dc") }
                    )
                }

                // 2. Bottleneck Verdict Banner
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(bottleneckColor.copy(alpha = 0.08f))
                        .border(0.8.dp, bottleneckColor.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(bottleneckColor)
                        )
                        Text(
                            text = bottleneckTitle,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isProxyActive) TextWhite else TextMuted
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HopNode(
    label: String,
    subLabel: String,
    state: Int,
    primaryColor: Color,
    onClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val nodeColor = when (state) {
        1 -> Color(0xFF00FF87)
        2 -> Color(0xFFFFB703)
        3 -> Color(0xFFFF0055)
        else -> TextMuted.copy(alpha = 0.35f)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        }
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(nodeColor.copy(alpha = 0.12f))
                .border(1.dp, nodeColor.copy(alpha = 0.5f), CircleShape)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(nodeColor)
            )
        }
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = label,
            fontSize = 8.5.sp,
            fontWeight = FontWeight.Bold,
            color = TextWhite,
            maxLines = 1
        )
        Text(
            text = subLabel,
            fontSize = 8.sp,
            color = nodeColor,
            maxLines = 1
        )
    }
}

@Composable
private fun HopLink(
    isActive: Boolean,
    isAlert: Boolean
) {
    val lineColor = when {
        !isActive -> Color(0xFF1E2333)
        isAlert -> Color(0xFFFFB703).copy(alpha = 0.5f)
        else -> Color(0xFF00FF87).copy(alpha = 0.35f)
    }
    Box(
        modifier = Modifier
            .width(10.dp)
            .height(1.dp)
            .background(lineColor)
    )
}

@Composable
fun SmartNetworkInsightsCard(
    pingMs: Long,
    jitterMs: Long,
    bufferbloatMs: Long,
    successRate: Int,
    healthScore: Int,
    chatScore: Int,
    mosScore: Double,
    lastFailureType: FailureType,
    isProxyActive: Boolean,
    onInfoClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current

    val (badgeText, insightText, accentColor) = when {
        !isProxyActive -> Triple(
            "Ожидание",
            "Служба прокси остановлена. Запустите прокси для непрерывного математического анализа параметров соединения.",
            TextMuted
        )
        lastFailureType == FailureType.RATE_LIMITED_429 -> Triple(
            "Квота Worker",
            "Превышен суточный лимит запросов публичного воркера (HTTP 429). Рекомендуется настроить собственный Cloudflare Worker в настройках.",
            Color(0xFFFF0055)
        )
        lastFailureType == FailureType.DPI_BLOCKED -> Triple(
            "DPI Фильтрация",
            "Провайдер связи блокирует прямые сокеты. WSS-туннель Mirrly маскирует трафик под защищенный веб-трафик для обхода фильтрации.",
            Color(0xFFFF0055)
        )
        jitterMs > 35L -> Triple(
            "LTE Jitter",
            "Высокая вариация задержки радиоканала (джиттер ±${jitterMs}мс). Для стабильных голосовых и видеозвонков без прерываний рекомендуется подключиться к сети Wi-Fi.",
            Color(0xFFFFB703)
        )
        bufferbloatMs >= 100L -> Triple(
            "Bufferbloat",
            "Обнаружено раздувание очереди буфера (+${bufferbloatMs}мс). Локальный роутер перегружен параллельными загрузками. Ядро автоматически включило режим сбережения очередей.",
            Color(0xFFFFB703)
        )
        pingMs > 250L -> Triple(
            "Высокий RTT",
            "Повышенная магистральная задержка (${pingMs}мс). Если вы используете мобильную сеть, переключитесь на Wi-Fi для более быстрого отклика.",
            Color(0xFFFFB703)
        )
        successRate < 85 -> Triple(
            "Потери пакетов",
            "Зафиксированы потери ${100 - successRate}% контрольных пакетов. Рекомендуется переподключиться к стабильной Wi-Fi сети с надежным приемом.",
            Color(0xFFFF0055)
        )
        mosScore < 3.80 -> Triple(
            "Звонки HD",
            "Качество аудиосвязи снижено (${String.format(java.util.Locale.US, "%.2f", mosScore)} MOS). Для идеальных звонков высокой четкости рекомендуется использовать Wi-Fi.",
            Color(0xFFFFB703)
        )
        else -> Triple(
            "Идеально",
            "Параметры соединения в норме (SQI ${healthScore}%). Сетевой тракт на 100% готов к мгновенным чатам, 4K видео и кристально чистым звонкам.",
            Color(0xFF00FF87)
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(accentColor.copy(alpha = 0.07f))
            .border(1.dp, accentColor.copy(alpha = 0.30f), RoundedCornerShape(16.dp))
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onInfoClick()
            }
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(accentColor.copy(alpha = 0.15f))
                    .border(0.8.dp, accentColor.copy(alpha = 0.40f), RoundedCornerShape(10.dp))
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_speed_auto),
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(16.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ИНСАЙТ ДВИЖКА",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp,
                        color = accentColor
                    )

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(accentColor.copy(alpha = 0.12f))
                            .border(0.6.dp, accentColor.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = badgeText,
                            color = accentColor,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Text(
                    text = insightText,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    color = if (isProxyActive) TextWhite else TextMuted,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

