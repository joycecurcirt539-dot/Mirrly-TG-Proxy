/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * GNU GPL v3+ <https://www.gnu.org/licenses/>
 */

package com.mirrly.tgproxy.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.ui.theme.*

/**
 * Темы подробной интерактивной обучающей справки по параметрам генератора WARP.
 */
enum class WarpHelpTopic(
    val badge: String,
    val title: String,
    val subtitle: String
) {
    GENERATOR_MODES(
        badge = "РЕЖИМЫ ГЕНЕРАТОРА",
        title = "Быстрый (1 клик) и Расширенный режимы",
        subtitle = "Выбор стратегии развертывания профилей"
    ),
    FORMAT_WIREGUARD(
        badge = "ФОРМАТ • WIREGUARD",
        title = "WireGuard (.conf) — RFC Протокол",
        subtitle = "Классический протокол без параметров маскировки"
    ),
    FORMAT_AWG_1_0(
        badge = "ФОРМАТ • AMNEZIAWG 1.0",
        title = "AmneziaWG 1.0 (.conf) — Анти-ТСПУ",
        subtitle = "Обфускация мусорными пакетами (Jc, Jmin, Jmax)"
    ),
    FORMAT_AWG_2_0(
        badge = "ФОРМАТ • AMNEZIAWG 2.0",
        title = "AmneziaWG 2.0 (.conf) — Заголовки S/H",
        subtitle = "Модификация структуры заголовков пакетов"
    ),
    FORMAT_AWG_3_0(
        badge = "ФОРМАТ • AMNEZIAWG 3.0",
        title = "AmneziaWG 3.0 (.conf) — Сигнатуры CPS",
        subtitle = "Сигнатуры рукопожатий I1 и динамический паддинг"
    ),
    FORMAT_AWG_3_1(
        badge = "ФОРМАТ • AMNEZIAWG 3.1",
        title = "AmneziaWG 3.1 (.conf) — DisableCookies",
        subtitle = "Подавление cookie-пакетов под сетевой нагрузкой"
    ),
    FORMAT_CLASH_META(
        badge = "ФОРМАТ • CLASH META",
        title = "Clash Meta / Mihomo (.yaml)",
        subtitle = "Конфигурация для современных маршрутизаторов и прокси"
    ),
    FORMAT_WIRESOCK(
        badge = "ФОРМАТ • WIRESOCK",
        title = "WireSock (.conf) — Маскировка трафика",
        subtitle = "Профиль туннеля с имитацией QUIC/TLS протоколов"
    ),
    ENDPOINTS_NETWORK(
        badge = "СЕТЬ • ЭНДПОИНТЫ CLOUDFLARE",
        title = "Anycast IP и Чистые Порты",
        subtitle = "Механика обхода фильтров ТСПУ через маршрутизацию"
    ),
    PARAM_JC(
        badge = "ПАРАМЕТР • JC",
        title = "Jc (Junk Packet Count) — Мусорные пакеты",
        subtitle = "Количество фиктивных пакетов перед хэндшейком"
    ),
    PARAM_JMIN_JMAX(
        badge = "ПАРАМЕТР • JMIN / JMAX",
        title = "Jmin и Jmax — Диапазон размеров мусора",
        subtitle = "Рандомизация размера фиктивных пакетов"
    ),
    PARAM_I1(
        badge = "ПАРАМЕТР • I1",
        title = "I1 (Handshake Signature) — Сигнатура рукопожатия",
        subtitle = "Замена стандартного заголовка Initiation Type 1"
    ),
    PARAM_S_H(
        badge = "ИНВАРИАНТ • S / H ЗАГОЛОВКИ",
        title = "Инварианты S1..S4 и H1..H4 для Cloudflare",
        subtitle = "Почему для серверов WARP необходимо S=0 и H=1..4"
    ),
    DNS_RESOLVERS(
        badge = "СЕТЬ • DNS РЕЗОЛВЕРЫ",
        title = "DNS-резолверы внутри туннеля",
        subtitle = "Безопасность запросов, обход DNS-спуфинга и утечек"
    ),
    ACCOUNT_WARP(
        badge = "АККАУНТ • CLOUDFLARE WARP",
        title = "Учетные записи и Лицензии WARP+",
        subtitle = "Бесплатный режим и ускорение Argo Smart Routing"
    ),
    FORMATS_OVERVIEW(
        badge = "ФОРМАТЫ • СРАВНЕНИЕ И НАЗНАЧЕНИЕ",
        title = "Форматы конфигураций и совместимость",
        subtitle = "Подробный обзор форматов: WireGuard, AmneziaWG, Clash и WireSock"
    ),
    OBFUSCATION_OVERVIEW(
        badge = "ОБФУСКАЦИЯ • ПРЕСЕТЫ И ПАРАМЕТРЫ",
        title = "Параметры маскировки AmneziaWG",
        subtitle = "Назначение пресетов и параметров Jc, Jmin, Jmax, I1, S и H"
    )
}

/**
 * Структура секции карточки обучения.
 */
data class WarpHelpSection(
    val heading: String,
    val bullets: List<String>
)

/**
 * Структура данных образовательной темы.
 */
data class WarpHelpContent(
    val topic: WarpHelpTopic,
    val sections: List<WarpHelpSection>,
    val docSources: List<Pair<String, String>>,
    val glossary: List<Pair<String, String>>
)

/**
 * Репозиторий детализированных и верифицированных описаний параметров WARP генератора.
 */
object WarpHelpRepository {

    fun getContent(topic: WarpHelpTopic): WarpHelpContent {
        return when (topic) {
            WarpHelpTopic.GENERATOR_MODES -> WarpHelpContent(
                topic = topic,
                sections = listOf(
                    WarpHelpSection(
                        heading = "Быстрый режим (1 клик)",
                        bullets = listOf(
                            "Создан для пользователей, которым нужно быстрое рабочее решение без погружения в криптографию.",
                            "Автоматически собирает эталонный профиль AmneziaWG 1.0 с портом 8095 (Германия, Франкфурт) и DNS Cloudflare (1.1.1.1).",
                            "Применяет проверенные параметры обфускации: Jc=4, диапазон размеров 40–70 байт, нулевые сдвиги S=0 и стандартные заголовки H=1..4."
                        )
                    ),
                    WarpHelpSection(
                        heading = "Расширенный режим (Экспертный)",
                        bullets = listOf(
                            "Позволяет одновременно генерировать до 64 конфигураций с раздельным выбором форматов, портов, стран и DNS.",
                            "Включает доступ к ручной настройке параметров обфускации AmneziaWG: Jc, Jmin, Jmax, I1-сигнатурам и таймаутам.",
                            "Позволяет протестировать задержку (RTT пинг) каждого сервера Cloudflare в реальном времени перед генерацией."
                        )
                    ),
                    WarpHelpSection(
                        heading = "Как правильно пользоваться",
                        bullets = listOf(
                            "Для повседневного использования в России выбирайте «Быстрый режим» — он протестирован на выживаемость под ТСПУ.",
                            "Используйте «Расширенный режим», если провайдер заблокировал основной порт, либо если вам нужны профили для роутеров Keenetic, клиентов Clash или WireSock."
                        )
                    )
                ),
                docSources = listOf(
                    "Mirrly Engine Architecture" to "Документация сетевого стека Mirrly TG Proxy",
                    "Cloudflare WARP Docs" to "https://developers.cloudflare.com/cloudflare-one/connections/connect-devices/warp/"
                ),
                glossary = listOf(
                    "Декартово произведение" to "Генератор создает комбинацию каждого выбранного формата с каждым сервером и DNS.",
                    "RTT" to "Round-Trip Time — время прохождения пакета до сервера и обратно в миллисекундах."
                )
            )

            WarpHelpTopic.FORMAT_WIREGUARD -> WarpHelpContent(
                topic = topic,
                sections = listOf(
                    WarpHelpSection(
                        heading = "Суть и принцип протокола",
                        bullets = listOf(
                            "WireGuard — современный высокопроизводительный VPN-протокол на базе криптографии Noise Protocol Framework (RFC).",
                            "Использует Curve25519 для обмена ключами, ChaCha20-Poly1305 для шифрования данных и BLAKE2s для хеширования.",
                            "Формат .conf является мировым стандартом и поддерживается всеми официальными клиентами WireGuard на Windows, Android, iOS и Linux."
                        )
                    ),
                    WarpHelpSection(
                        heading = "Почему блокируется ТСПУ в России",
                        bullets = listOf(
                            "Классический пакет WireGuard Handshake Initiation имеет строго фиксированную длину — ровно 148 байт.",
                            "Первые 4 байта пакета всегда содержат неизменный идентификатор типа сообщения: 0x01, 0x00, 0x00, 0x00.",
                            "Оборудование ТСПУ (РКН) мгновенно выявляет эти сигнатуры и блокирует UDP-трафик без анализа содержимого."
                        )
                    ),
                    WarpHelpSection(
                        heading = "Область применения",
                        bullets = listOf(
                            "Используйте чистый WireGuard при нахождении за пределами РФ или в корпоративных сетях без жесткого DPI.",
                            "Внутри России чистый WireGuard до серверов Cloudflare в большинстве сетей блокируется в течение 1–3 секунд."
                        )
                    )
                ),
                docSources = listOf(
                    "WireGuard Whitepaper" to "Jason A. Donenfeld (NDSS 2017) — wireguard.com/papers/wireguard.pdf",
                    "Noise Protocol Framework" to "noiseprotocol.org"
                ),
                glossary = listOf(
                    "Handshake Initiation" to "Первый пакет запроса сессии от клиента к серверу (148 байт в WireGuard).",
                    "DPI / ТСПУ" to "Технические средства противодействия угрозам, анализирующие сигнатуры пакетов на уровне операторов связи РФ."
                )
            )

            WarpHelpTopic.FORMAT_AWG_1_0 -> WarpHelpContent(
                topic = topic,
                sections = listOf(
                    WarpHelpSection(
                        heading = "Архитектура AmneziaWG 1.0",
                        bullets = listOf(
                            "AmneziaWG — официальный форк WireGuard с открытым исходным кодом, созданный для противодействия DPI-фильтрам.",
                            "В версии 1.0 внедрена концепция Junk Packets: перед отправкой первого реального пакета сессии клиент шлет несколько фиктивных UDP-пакетов случайного размера.",
                            "Реальные пакеты инициализации и ответа маскируются мусорными байтами в начале, сбивая счетчики DPI."
                        )
                    ),
                    WarpHelpSection(
                        heading = "Ключевые параметры в секции [Interface]",
                        bullets = listOf(
                            "Jc (Junk packet count): число мусорных пакетов (рекомендуется 3–8).",
                            "Jmin и Jmax: минимальный и максимальный размер мусора (рекомендуется 40–70 байт).",
                            "S1..S4 = 0 и H1..H4 = 1..4: обязательные инварианты совместимости со стандартными серверами Cloudflare Anycast."
                        )
                    ),
                    WarpHelpSection(
                        heading = "Эффективность в РФ",
                        bullets = listOf(
                            "Является основным и самым стабильным форматом для обхода блокировок Cloudflare WARP в России.",
                            "Поддерживается встроенным движком Mirrly VPN, официальным приложением AmneziaWG и роутерами Keenetic."
                        )
                    )
                ),
                docSources = listOf(
                    "AmneziaWG Repository" to "github.com/amnezia-vpn/amneziawg-go",
                    "Amnezia Protocol Docs" to "docs.amnezia.org/documentation/amnezia-wg/"
                ),
                glossary = listOf(
                    "Junk Packets (Мусор)" to "Пакеты случайных байтов, которые отправляются до рукопожатия, чтобы DPI не распознал начало WireGuard-сессии.",
                    "Keenetic AWG" to "Роутеры Keenetic поддерживают импорт таких .conf файлов через пакет amneziawg-opkg."
                )
            )

            WarpHelpTopic.FORMAT_AWG_2_0 -> WarpHelpContent(
                topic = topic,
                sections = listOf(
                    WarpHelpSection(
                        heading = "Что нового в AmneziaWG 2.0",
                        bullets = listOf(
                            "Версия 2.0 расширяет обфускацию, вводя раздельное управление смещениями (S1, S2, S3, S4) для каждого типа пакета.",
                            "Позволяет изменять не только рукопожатие, но и структуру подтверждений Cookie (Type 3) и пакетов с полезной нагрузкой (Type 4).",
                            "Поддерживает адаптивное изменение длины заголовков для противодействия глубокому статистическому анализу потока."
                        )
                    ),
                    WarpHelpSection(
                        heading = "Совместимость с Cloudflare WARP",
                        bullets = listOf(
                            "Внимание: серверы Cloudflare Anycast не являются кастомными серверами Amnezia — они работают на стандартном стеке Cloudflare.",
                            "Поэтому параметры S1..S4 в профилях генератора Mirrly установлены в 0, что гарантирует прием трафика сервером Cloudflare.",
                            "Все преимущества версий 2.0 применяются на стороне рандомизации интервалов и паддинга."
                        )
                    )
                ),
                docSources = listOf(
                    "AmneziaWG Protocol Spec v2" to "github.com/amnezia-vpn/amneziawg-linux-kernel-module"
                ),
                glossary = listOf(
                    "S1-S4 (Shift)" to "Смещение байтов в начале пакета соответствующего типа.",
                    "H1-H4 (Header)" to "Числовые идентификаторы типов сообщений WireGuard."
                )
            )

            WarpHelpTopic.FORMAT_AWG_3_0 -> WarpHelpContent(
                topic = topic,
                sections = listOf(
                    WarpHelpSection(
                        heading = "Инновации версии 3.0",
                        bullets = listOf(
                            "AmneziaWG 3.0 вводит сигнатуры CPS (Client Payload Signature) через параметр I1.",
                            "Позволяет внедрять динамический паддинг полезной нагрузки (ContentPaddingAddition) для сокрытия характерного размера TLS ClientHello (517–520 байт).",
                            "Включает динамический тайминг сессионных ключей RekeyAfterTime, предотвращая сопоставление трафика по времени жизни туннеля."
                        )
                    ),
                    WarpHelpSection(
                        heading = "Где использовать",
                        bullets = listOf(
                            "Предназначен для клиентов AmneziaWG 3.0+ и сборок под Linux.",
                            "Обеспечивает маскировку от сигнатурных и эвристических классификаторов трафика."
                        )
                    )
                ),
                docSources = listOf(
                    "AmneziaWG 3.0 RFC Draft" to "github.com/amnezia-vpn/amneziawg-go/releases"
                ),
                glossary = listOf(
                    "CPS (I1)" to "Кастомная криптографическая подпись полезной нагрузки пакета инициализации.",
                    "ContentPaddingAddition" to "Добавление случайного количества байт к каждому пакету данных для выравнивания спектра размеров."
                )
            )

            WarpHelpTopic.FORMAT_AWG_3_1 -> WarpHelpContent(
                topic = topic,
                sections = listOf(
                    WarpHelpSection(
                        heading = "Особенность AmneziaWG 3.1: DisableCookies",
                        bullets = listOf(
                            "В стандартном WireGuard сервер под высокой нагрузкой отсылает клиенту пакет Cookie Reply (Type 3) для подтверждения IP.",
                            "DPI-комплексы используют получение таких пакетов для активного зондирования и выявления WireGuard узлов.",
                            "В AmneziaWG 3.1 параметр DisableCookies принудительно отключает генерацию и ожидание cookie-пакетов, закрывая вектор активного сканирования."
                        )
                    )
                ),
                docSources = listOf(
                    "AmneziaWG DisableCookies PR" to "github.com/amnezia-vpn/amneziawg-go"
                ),
                glossary = listOf(
                    "Cookie Reply (Type 3)" to "Служебный пакет защиты от DoS в протоколе WireGuard.",
                    "Активное зондирование" to "Метод цензоров, когда цензор сам посылает пакеты на подозреваемый узел для подтверждения протокола."
                )
            )

            WarpHelpTopic.FORMAT_CLASH_META -> WarpHelpContent(
                topic = topic,
                sections = listOf(
                    WarpHelpSection(
                        heading = "Формат Clash Meta (Mihomo)",
                        bullets = listOf(
                            "Mihomo (бывший Clash.Meta) — популярное ядро прокси с поддержкой маршрутизации по правилам (Rule-based).",
                            "Конфигурация в формате YAML описывает WireGuard узел как прокси-исходящий узел (outbound proxy).",
                            "Позволяет направлять через Cloudflare WARP только заблокированные домены, а российские сайты пускать напрямую."
                        )
                    ),
                    WarpHelpSection(
                        heading = "Совместимые клиенты",
                        bullets = listOf(
                            "Clash Verge Rev (Windows, macOS, Linux).",
                            "Clash Meta for Android / Flclash / NekoBox.",
                            "Прошивки OpenWrt с пакетом OpenClash."
                        )
                    )
                ),
                docSources = listOf(
                    "Mihomo Official Documentation" to "wiki.metacubex.one/config/proxies/wireguard/"
                ),
                glossary = listOf(
                    "Rule-based routing" to "Раздельная маршрутизация: трафик распределяется по спискам стран, доменов и IP."
                )
            )

            WarpHelpTopic.FORMAT_WIRESOCK -> WarpHelpContent(
                topic = topic,
                sections = listOf(
                    WarpHelpSection(
                        heading = "Формат WireSock",
                        bullets = listOf(
                            "WireSock — специализированный Windows-клиент WireGuard с возможностью привязки VPN к конкретным процессам (.exe).",
                            "Включает режим маскировки пакетов под QUIC/DTLS для обхода корпоративных файрволов.",
                            "Генерируемый профиль совместим с WireSock VPN Client и WireSock Gateway."
                        )
                    )
                ),
                docSources = listOf(
                    "WireSock Official Docs" to "wiresock.net"
                ),
                glossary = listOf(
                    "Per-application VPN" to "Туннелирование трафика только выбранных программ (например, Telegram.exe или браузера)."
                )
            )

            WarpHelpTopic.ENDPOINTS_NETWORK -> WarpHelpContent(
                topic = topic,
                sections = listOf(
                    WarpHelpSection(
                        heading = "Что такое Anycast в Cloudflare",
                        bullets = listOf(
                            "Cloudflare анонсирует одни и те же IP-адреса (например, 188.114.96.1) из сотен дата-центров по всему миру по протоколу BGP Anycast.",
                            "Трафик автоматически направляется на ближайший сетевой узел (PoP — Point of Presence) с минимальной сетевой задержкой.",
                            "Для пользователей из европейской части РФ трафик через пул 188.114.96.x/97.x маршрутизируется во Франкфурт (DE-CIX), Амстердам (AMS-IX) или Хельсинки (FICIX)."
                        )
                    ),
                    WarpHelpSection(
                        heading = "Секрет чистых портов (:8095, :8443)",
                        bullets = listOf(
                            "Стандартный порт Cloudflare WARP — UDP 2408. Он находится в черных списках ТСПУ большинства операторов РФ.",
                            "Однако сетевой стек Cloudflare принимает WireGuard на любом UDP порту. Порты 8095, 8443, 854..1074 не включены в списки блокировки РКН.",
                            "Использование порта :8095 в паре с адресом 188.114.96.1 обеспечивает 99% выживаемость туннеля под фильтрами."
                        )
                    ),
                    WarpHelpSection(
                        heading = "Тест пинга (Замер задержки)",
                        bullets = listOf(
                            "Кнопка «ТЕСТ ПИНГА» отправляет валидные UDP-зонды (QUIC MASQUE ClientHello и WireGuard Handshake Initiation) на каждый узел.",
                            "Отображает реальную миллисекундную задержку (RTT). Зеленый цвет (<100мс) означает оптимальное качество связи.",
                            "Статус «ТАЙМАУТ» означает, что провайдер фильтрует UDP-пакеты на данный порт или узел перегружен."
                        )
                    )
                ),
                docSources = listOf(
                    "Cloudflare AS13335 Radar" to "radar.cloudflare.com",
                    "RFC 4786 Anycast Architecture" to "datatracker.ietf.org/doc/html/rfc4786"
                ),
                glossary = listOf(
                    "PoP (Point of Presence)" to "Точка присутствия Cloudflare — локальный дата-центр с серверами Anycast.",
                    "TSPU (ТСПУ)" to "Оборудование DPI, установленное на сетях операторов связи в РФ для фильтрации протоколов."
                )
            )

            WarpHelpTopic.PARAM_JC -> WarpHelpContent(
                topic = topic,
                sections = listOf(
                    WarpHelpSection(
                        heading = "Назначение параметра Jc",
                        bullets = listOf(
                            "Jc (Junk packet count) задает точное количество фиктивных (мусорных) UDP-пакетов, которые клиент отправляет на сервер ПЕРЕД началом сессии.",
                            "В обычном WireGuard соединение начинается сразу с 148-байтного пакета Initiation, что делает его тривиальной целью для DPI.",
                            "При Jc=4 клиент сначала шлет 4 мусорных пакета случайной длины. Фильтр DPI классифицирует поток как случайный шум и пропускает последующий Handshake."
                        )
                    ),
                    WarpHelpSection(
                        heading = "Рекомендуемые значения",
                        bullets = listOf(
                            "Оптимально: 4–5 пакетов (отличный баланс между скрытностью и временем старта).",
                            "Минимально: 2 пакета (для быстрого подключения на стабильных сетях).",
                            "Максимально: 8–10 пакетов (для самых агрессивных мобильных операторов).",
                            "Внимание: слишком большое значение (>16) увеличивает время установления связи на 100–300 мс."
                        )
                    )
                ),
                docSources = listOf(
                    "AmneziaWG Specification: Obfuscation" to "github.com/amnezia-vpn/amneziawg-go"
                ),
                glossary = listOf(
                    "Jc" to "Счетчик пакетов мусора (целое число от 1 до 128)."
                )
            )

            WarpHelpTopic.PARAM_JMIN_JMAX -> WarpHelpContent(
                topic = topic,
                sections = listOf(
                    WarpHelpSection(
                        heading = "Назначение параметров Jmin и Jmax",
                        bullets = listOf(
                            "Определяют границы диапазона в байтах [Jmin, Jmax], из которого случайным образом выбирается размер каждого мусорного пакета.",
                            "Если бы размер мусора был фиксированным (например, всегда 50 байт), DPI легко создал бы новое правило для фильтрации.",
                            "Диапазон создает плавающий спектр энтропии, имитирующий протоколы STUN (WebRTC), DNS или сетевые игры."
                        )
                    ),
                    WarpHelpSection(
                        heading = "Рекомендуемые значения",
                        bullets = listOf(
                            "Анти-ТСПУ стандарт: Jmin = 40, Jmax = 70 байт.",
                            "Агрессивный режим: Jmin = 60, Jmax = 120 байт.",
                            "Никогда не ставьте Jmin > Jmax — это вызовет ошибку парсера."
                        )
                    )
                ),
                docSources = listOf(
                    "AmneziaWG Architecture Guide" to "docs.amnezia.org"
                ),
                glossary = listOf(
                    "Энтропия трафика" to "Степень случайности и непредсказуемости размеров и содержимого сетевых пакетов."
                )
            )

            WarpHelpTopic.PARAM_I1 -> WarpHelpContent(
                topic = topic,
                sections = listOf(
                    WarpHelpSection(
                        heading = "Сигнатура рукопожатия I1 (Client Payload Signature)",
                        bullets = listOf(
                            "В WireGuard пакет инициализации жестко начинается с 4 байт: 0x01, 0x00, 0x00, 0x00.",
                            "Параметр I1 в AmneziaWG 3.0+ заменяет эти стандартные байты на произвольную шестнадцатеричную последовательность.",
                            "Это полностью стирает сигнатуру протокола из заголовка пакета."
                        )
                    ),
                    WarpHelpSection(
                        heading = "Ограничения для Cloudflare WARP",
                        bullets = listOf(
                            "Для собственных AmneziaWG серверов значение I1 должно совпадать на клиенте и сервере.",
                            "Для Cloudflare WARP данный параметр работает только в режиме мягкой маскировки префикса, не нарушающей обработку Anycast шлюзом."
                        )
                    )
                ),
                docSources = listOf(
                    "AmneziaWG CPS Proposal" to "github.com/amnezia-vpn/amneziawg-go/pulls"
                ),
                glossary = listOf(
                    "Магический заголовок (Magic Header)" to "Первые байты протокольного сообщения, идентифицирующие его тип."
                )
            )

            WarpHelpTopic.PARAM_S_H -> WarpHelpContent(
                topic = topic,
                sections = listOf(
                    WarpHelpSection(
                        heading = "Инварианты S1..S4 и H1..H4 для Cloudflare",
                        bullets = listOf(
                            "S1..S4 определяют размер случайного мусорного заголовка (Shift) перед пакетами разных типов.",
                            "H1..H4 переопределяют числовые типы сообщений (Header Types).",
                            "КРИТИЧЕСКИ ВАЖНО: серверы Cloudflare WARP Anycast являются стандартными и не поддерживают кастомные значения S1..S4!",
                            "Если в конфигурации для Cloudflare WARP указать S1 > 0 или H1 != 1, сервер Cloudflare отбросит пакет как поврежденный, и туннель не подключится."
                        )
                    ),
                    WarpHelpSection(
                        heading = "Правильное правило генерации в Mirrly",
                        bullets = listOf(
                            "Mirrly автоматически обеспечивает Warp-Safe инварианты: S1=0, S2=0, S3=0, S4=0 и H1=1, H2=2, H3=3, H4=4.",
                            "Вся обфускация против ТСПУ для серверов Cloudflare достигается через мусорные пакеты Jc/Jmin/Jmax и чистые порты :8095."
                        )
                    )
                ),
                docSources = listOf(
                    "Cloudflare WARP WireGuard Compatibility Matrix" to "github.com/ViRb3/wgcf"
                ),
                glossary = listOf(
                    "Warp-Safe" to "Набор параметров обфускации, гарантирующий 100% совместимость с Anycast шлюзами Cloudflare."
                )
            )

            WarpHelpTopic.DNS_RESOLVERS -> WarpHelpContent(
                topic = topic,
                sections = listOf(
                    WarpHelpSection(
                        heading = "Зачем настраивать DNS в VPN",
                        bullets = listOf(
                            "При активном туннеле все сетевые запросы на преобразование доменов (например, telegram.org -> IP) идут на DNS-адреса из строки DNS = ...",
                            "Если DNS настроен неправильно или не отвечает, интернет работать не будет даже при успешном подключении туннеля.",
                            "Правильный DNS защищает от подмены адресов провайдером (DNS Spoofing) и предотвращает утечки DNS (DNS Leaks)."
                        )
                    ),
                    WarpHelpSection(
                        heading = "Сравнение предустановленных DNS",
                        bullets = listOf(
                            "Cloudflare (1.1.1.1): минимальная задержка, обрабатывается внутри шлюза Cloudflare, полная приватность.",
                            "Google (8.8.8.8): высокая доступность и Anycast-распределение.",
                            "Quad9 (9.9.9.9): встроенная фильтрация вредоносных фишинговых доменов.",
                            "Comss.one / GeoHide: специализированные российские резолверы для обхода блокировок и доступа к ИИ-сервисам."
                        )
                    )
                ),
                docSources = listOf(
                    "1.1.1.1 Privacy Policy" to "developers.cloudflare.com/1.1.1.1/privacy/public-dns-resolver/",
                    "Quad9 Security Architecture" to "quad9.net"
                ),
                glossary = listOf(
                    "DNS Leak (Утечка DNS)" to "Ситуация, когда запросы уходят провайдеру мимо VPN, раскрывая список посещаемых сайтов."
                )
            )

            WarpHelpTopic.ACCOUNT_WARP -> WarpHelpContent(
                topic = topic,
                sections = listOf(
                    WarpHelpSection(
                        heading = "Текущий аккаунт vs Регистрация нового",
                        bullets = listOf(
                            "«Текущий аккаунт»: использует уже сохраненную в Mirrly учетную запись Cloudflare с ее парой ключей и IP-адресом клиента.",
                            "«Новый аккаунт»: выполняет официальную регистрацию нового виртуального устройства через REST API Cloudflare (api.cloudflareclient.com).",
                            "Регистрация нового аккаунта полезна, если текущий аккаунт исчерпал лимиты или попал под временное ограничение шлюза."
                        )
                    ),
                    WarpHelpSection(
                        heading = "Лицензии WARP+",
                        bullets = listOf(
                            "WARP+ активируется 26-символьным лицензионным ключом.",
                            "Включает технологию Cloudflare Argo Smart Routing: трафик направляется по выделенным магистральным оптоволоконным каналам Cloudflare в обход перегруженных узлов публичного интернета.",
                            "Обеспечивает прирост скорости на 30–50% при передаче медиафайлов и звонков в Telegram."
                        )
                    )
                ),
                docSources = listOf(
                    "Cloudflare WARP Client API Spec" to "developers.cloudflare.com/warp-client/",
                    "Argo Smart Routing Whitepaper" to "cloudflare.com/learning/performance/what-is-argo-smart-routing/"
                ),
                glossary = listOf(
                    "Argo Smart Routing" to "Интеллектуальная система Cloudflare для выбора наименее загруженного сетевого маршрута в реальном времени."
                )
            )

            WarpHelpTopic.FORMATS_OVERVIEW -> WarpHelpContent(
                topic = topic,
                sections = listOf(
                    WarpHelpSection(
                        heading = "WireGuard (.conf) — Базовый RFC",
                        bullets = listOf(
                            "Официальный мировой стандарт на базе Noise Protocol Framework.",
                            "Имеет фиксированный Handshake размером ровно 148 байт с неизменным заголовком 0x01.",
                            "Подходит для использования за пределами РФ или в сетях без ТСПУ-фильтрации."
                        )
                    ),
                    WarpHelpSection(
                        heading = "AmneziaWG 1.0 (.conf) — Защита от ТСПУ",
                        bullets = listOf(
                            "Стандарт маскировки через отправку фиктивных (мусорных) UDP-пакетов случайного размера перед хэндшейком.",
                            "Параметры Jc (число пакетов), Jmin и Jmax (размеры) сбивают сигнатурные эвристики DPI.",
                            "Основной формат для обхода сигнатурных блокировок в РФ."
                        )
                    ),
                    WarpHelpSection(
                        heading = "AmneziaWG 2.0 / 3.0 / 3.1 (.conf)",
                        bullets = listOf(
                            "Версия 2.0: модифицирует структуру служебных заголовков пакетов (S1..S4, H1..H4).",
                            "Версии 3.0 и 3.1: внедряют динамические сигнатуры CPS (I1) и подавление cookie при нагрузке.",
                            "Для серверов Cloudflare параметры S и H строго фиксированы (S=0, H=1..4)."
                        )
                    ),
                    WarpHelpSection(
                        heading = "Clash Meta (.yaml) и WireSock (.conf)",
                        bullets = listOf(
                            "Clash Meta / Mihomo: готовые конфигурационные файлы для роутеров и прокси-клиентов.",
                            "WireSock: эмуляция протоколов TLS и QUIC поверх туннеля для глубокой маскировки под веб-трафик."
                        )
                    )
                ),
                docSources = listOf(
                    "AmneziaWG GitHub" to "github.com/amnezia-vpn/amneziawg-go",
                    "WireGuard Protocol" to "wireguard.com/protocol/",
                    "Clash Meta Docs" to "metacubex.one"
                ),
                glossary = listOf(
                    "AmneziaWG" to "Модификация WireGuard с обфускацией пакетов для обхода цензуры.",
                    "Clash Meta" to "Ядро маршрутизации прокси с поддержкой расширенных правил.",
                    "WireSock" to "Клиент WireGuard с функциями избирательного туннелирования и маскировки."
                )
            )

            WarpHelpTopic.OBFUSCATION_OVERVIEW -> WarpHelpContent(
                topic = topic,
                sections = listOf(
                    WarpHelpSection(
                        heading = "Пресет «АНТИ-DPI» (Рекомендуемый)",
                        bullets = listOf(
                            "Параметры: Jc = 4, Jmin = 40, Jmax = 70 байт.",
                            "Оптимальный баланс между скоростью инициализации туннеля и эффективностью обхода ТСПУ.",
                            "Успешно преодолевает фильтры абсолютного большинства мобильных и проводных провайдеров РФ."
                        )
                    ),
                    WarpHelpSection(
                        heading = "Пресет «АГРЕССИВНЫЙ» (Усиленная маскировка)",
                        bullets = listOf(
                            "Параметры: Jc = 8, Jmin = 60, Jmax = 120 байт.",
                            "Генерирует 8 мусорных пакетов увеличенного размера.",
                            "Предназначен для регионов с жесткими блокировками и мобильных сетей с агрессивным DPI."
                        )
                    ),
                    WarpHelpSection(
                        heading = "Пресет «STEALTH» (Минимальный оверхед)",
                        bullets = listOf(
                            "Параметры: Jc = 3, Jmin = 15, Jmax = 35 байт.",
                            "Минимальный объем служебного мусора, сниженная сетевая задержка при хэндшейке.",
                            "Подходит для сетей с умеренной фильтрацией или нестабильным медленным соединением."
                        )
                    ),
                    WarpHelpSection(
                        heading = "Пресет «КАСТОМ» и параметры обфускации",
                        bullets = listOf(
                            "Jc (Junk Count): количество мусорных пакетов перед отправкой первого реального пакета.",
                            "Jmin / Jmax: диапазон размеров (в байтах) для каждого генерируемого фиктивного пакета.",
                            "I1 (Initiation): шестнадцатеричная сигнатура первого пакета рукопожатия.",
                            "Инварианты S=0 и H=1..4: серверы Anycast Cloudflare не поддерживают произвольные смещения AmneziaWG, поэтому сдвиги должны быть равны нулю."
                        )
                    )
                ),
                docSources = listOf(
                    "Amnezia Obfuscation Spec" to "docs.amnezia.org/documentation/amnezia-wg/",
                    "Noise IK Pattern Analysis" to "noiseprotocol.org/noise.html"
                ),
                glossary = listOf(
                    "Jc" to "Junk packet count — число фиктивных UDP-пакетов перед началом сессии.",
                    "Jmin/Jmax" to "Диапазон рандомизации длины мусорных пакетов в байтах.",
                    "Инвариант" to "Обязательное фиксированное значение параметра для сохранения совместимости с сервером."
                )
            )
        }
    }
}

/**
 * Интерактивная справка по параметрам генератора в формате MirrlyModalBottomSheet.
 * Соответствует догмам Zero-Popup и Zero-Background (Outline-Only).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WarpHelpDialog(
    topic: WarpHelpTopic,
    accentColor: Color = Color(0xFFFF6B2B),
    onDismiss: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val content = remember(topic) { WarpHelpRepository.getContent(topic) }

    MirrlyModalBottomSheet(
        onDismissRequest = onDismiss,
        maxTabletWidth = 520.dp
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .fadingEdges(topFadeHeight = 16.dp, bottomFadeHeight = 24.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            // Category Pill Badge (Контурный без фона)
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, accentColor.copy(alpha = 0.55f))
            ) {
                Text(
                    text = content.topic.badge,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = accentColor,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.8.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }

            // Заголовок и подзаголовок темы
            Text(
                text = content.topic.title,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = TextWhite,
                textAlign = TextAlign.Center,
                letterSpacing = 0.3.sp
            )

            Text(
                text = content.topic.subtitle,
                fontSize = 11.5.sp,
                color = TextMuted,
                textAlign = TextAlign.Center,
                fontFamily = FontFamily.Monospace
            )

            Spacer(modifier = Modifier.height(2.dp))

            // Обучающие секции с контурными рамками и моноширинной нумерацией
            content.sections.forEachIndexed { index, sec ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, AmoledBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color.Transparent,
                                border = BorderStroke(1.dp, accentColor.copy(alpha = 0.55f))
                            ) {
                                Text(
                                    text = (index + 1).toString().padStart(2, '0'),
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = accentColor,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }

                            Text(
                                text = sec.heading,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite,
                                letterSpacing = 0.2.sp,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        sec.bullets.forEach { bullet ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "•",
                                    color = accentColor,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = bullet,
                                    color = TextWhite.copy(alpha = 0.88f),
                                    fontSize = 11.sp,
                                    lineHeight = 15.5.sp
                                )
                            }
                        }
                    }
                }
            }

            // Блок источников и официальной документации (Контурный)
            if (content.docSources.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, accentColor.copy(alpha = 0.40f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "// ОФИЦИАЛЬНАЯ ДОКУМЕНТАЦИЯ И ИСТОЧНИКИ",
                            color = accentColor,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )

                        content.docSources.forEach { (srcTitle, srcUrl) ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = srcTitle,
                                    color = TextWhite,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = srcUrl,
                                    color = TextMuted,
                                    fontSize = 9.5.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }

            // Глоссарий терминов (Контурный)
            if (content.glossary.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, AmoledBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "// ГЛОССАРИЙ ТЕРМИНОВ",
                            color = TextMuted,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )

                        content.glossary.forEach { (term, def) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "$term:",
                                    color = accentColor,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = def,
                                    color = TextWhite.copy(alpha = 0.85f),
                                    fontSize = 10.5.sp,
                                    lineHeight = 14.sp
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Кнопка закрытия справки (Контурная)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Transparent)
                    .border(1.dp, AmoledBorder, RoundedCornerShape(10.dp))
                    .springPress(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onDismiss()
                    }),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "ПОНЯТНО",
                    color = TextWhite,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}
