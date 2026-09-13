# План Технического Аудита и Исправления Архитектуры VLESS (Mirrly TG Proxy)

## 1. Общие сведения и цели аудита

Документ содержит подробный инженерный аудит текущей реализации протокола **VLESS** в нативном ядре (`mirrlyengine / vless.rs`) и прикладных модулях Kotlin (`:core / VlessPreset.kt`).

Текущее состояние подсистемы VLESS в проекте является усеченным макетом (заглушкой), ориентированным исключительно на приватные воркеры Cloudflare Pages. Оно не поддерживает стандартные для современного обхода ТСПУ протоколы (VLESS Reality, VLESS Vision, прямой TCP, gRPC, uTLS fingerprinting) и отбрасывает подавляющее большинство реальных пользовательских конфигураций.

Цель аудита — декомпозиция всех ограничений и формирование пошагового плана превращения VLESS в полноценный, промышленный антицензурный транспортный стек.

---

## 2. Список выявленных проблем и задач на исправление

### Блок 1. Протокольный уровень и Парсер VLESS URI (Kotlin Core)

* **TSK-V01: Блокировка протокола REALITY в парсере URI**
  * **Проблема**: В [VlessPreset.kt](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/core/src/main/kotlin/com/mirrly/tgproxy/core/VlessPreset.kt) зашита строгая отсечка `if (security == "reality") return null;`. Это исключает использование протокола VLESS-Reality — главного инструмента обхода ТСПУ в РФ в 2024–2026 годах, маскирующего соединения под чужие легитимные сертификаты (Microsoft, Apple, Google, Amazon).
  * **Требуемое исправление**: Удалить блокировку `reality`. Добавить в модель `VlessPreset` поля для параметров Reality: `publicKey` (`pbk`), `shortId` (`sid`), `fingerprint` (`fp`), `spiderX` (`spx`).
  * **Файлы**: [core/src/main/kotlin/com/mirrly/tgproxy/core/VlessPreset.kt](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/core/src/main/kotlin/com/mirrly/tgproxy/core/VlessPreset.kt), [core/src/main/kotlin/com/mirrly/tgproxy/core/ProxyConfig.kt](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/core/src/main/kotlin/com/mirrly/tgproxy/core/ProxyConfig.kt), [app/src/main/java/com/mirrly/tgproxy/service/PreferencesManager.kt](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/app/src/main/java/com/mirrly/tgproxy/service/PreferencesManager.kt), [core/src/test/kotlin/com/mirrly/tgproxy/core/VlessPresetsRepositoryTest.kt](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/core/src/test/kotlin/com/mirrly/tgproxy/core/VlessPresetsRepositoryTest.kt)
  * **Статус**: ВЫПОЛНЕНО. Разблокирован протокол Reality в парсере URI, добавлены поля `security`, `publicKey`, `shortId`, `fingerprint`, `spiderX`, реализована сериализация/десериализация в динамическом пуле и `PreferencesManager`, генерация ссылок `toShareableUri()`, добавлены модульные тесты.

* **TSK-V02: Блокировка прямого TCP-транспорта (Direct TCP)**
  * **Проблема**: В парсере [VlessPreset.kt](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/core/src/main/kotlin/com/mirrly/tgproxy/core/VlessPreset.kt) стоял фильтр `if (transport != "ws" && !path.contains("ws")) return null;`. Из-за этого отбрасывались все классические высокоскоростные VLESS-конфигурации поверх сырого TCP с TLS/Reality, работающие без WebSocket.
  * **Требуемое исправление**: Реализовать поддержку `transport = "tcp"`, включая обработку поля `flow` (`xtls-rprx-vision`) и `headerType`.
  * **Файлы**: [core/src/main/kotlin/com/mirrly/tgproxy/core/VlessPreset.kt](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/core/src/main/kotlin/com/mirrly/tgproxy/core/VlessPreset.kt), [core/src/main/kotlin/com/mirrly/tgproxy/core/ProxyConfig.kt](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/core/src/main/kotlin/com/mirrly/tgproxy/core/ProxyConfig.kt), [app/src/main/java/com/mirrly/tgproxy/service/PreferencesManager.kt](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/app/src/main/java/com/mirrly/tgproxy/service/PreferencesManager.kt), [core/src/test/kotlin/com/mirrly/tgproxy/core/VlessPresetsRepositoryTest.kt](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/core/src/test/kotlin/com/mirrly/tgproxy/core/VlessPresetsRepositoryTest.kt)
  * **Статус**: ВЫПОЛНЕНО. Разрешен прямой TCP-транспорт (`transport = "tcp"`), добавлены поля `flow` (с поддержкой `xtls-rprx-vision`) и `headerType`, реализована сериализация/десериализация в кэше пула и `PreferencesManager`, генерация ссылок `toShareableUri()` и `getVlessShareUrl()`, написаны модульные тесты.

* **TSK-V03: Невозможность разделения Server IP, TLS SNI и WebSocket Host (Clean IP / Fronting)**
  * **Проблема**: В текущей структуре `VlessPreset` и нативной `VlessConfig` есть только одно поле `domain`. Из-за этого невозможно использовать Clean IP (IP-fronting), когда подключение TCP идет на незаблокированный IP-адрес или прокси-IP Cloudflare, а в TLS SNI и HTTP Host передается целевой рабочий домен.
  * **Требуемое исправление**: Разделить сетевые параметры в `VlessPreset` и нативном FFI:
    1. `serverAddress` (реальный IP или домен сервера для сетевого сокета).
    2. `serverPort` (порт сервера, например 443, 8443, 2053, 2083, 2087, 2096).
    3. `tlsSni` (домен для TLS ClientHello / ServerName).
    4. `hostHeader` (заголовок HTTP `Host:` для WebSocket).
  * **Файлы**: [core/src/main/kotlin/com/mirrly/tgproxy/core/VlessPreset.kt](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/core/src/main/kotlin/com/mirrly/tgproxy/core/VlessPreset.kt), [core/src/main/kotlin/com/mirrly/tgproxy/core/ProxyConfig.kt](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/core/src/main/kotlin/com/mirrly/tgproxy/core/ProxyConfig.kt), [core/src/main/kotlin/com/mirrly/tgproxy/core/NativeProxy.kt](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/core/src/main/kotlin/com/mirrly/tgproxy/core/NativeProxy.kt), [core/src/main/kotlin/com/mirrly/tgproxy/core/LocalProxyServer.kt](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/core/src/main/kotlin/com/mirrly/tgproxy/core/LocalProxyServer.kt), [app/src/main/java/com/mirrly/tgproxy/service/PreferencesManager.kt](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/app/src/main/java/com/mirrly/tgproxy/service/PreferencesManager.kt), [app/src/main/java/com/mirrly/tgproxy/ui/SettingsScreen.kt](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/app/src/main/java/com/mirrly/tgproxy/ui/SettingsScreen.kt), [mirrlyengine/src/ws.rs](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/mirrlyengine/src/ws.rs), [mirrlyengine/src/cfproxy.rs](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/mirrlyengine/src/cfproxy.rs), [mirrlyengine/src/vless.rs](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/mirrlyengine/src/vless.rs), [mirrlyengine/src/lib.rs](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/mirrlyengine/src/lib.rs)
  * **Статус**: ВЫПОЛНЕНО. Сетевые параметры разделены на `serverAddress`, `serverPort`, `tlsSni`, `hostHeader` как в Kotlin-моделях (`VlessPreset`, `ProxyConfig`, `PreferencesManager`, `LocalProxyServer`, `SettingsScreen`), так и в нативном движке Rust (`VlessConfig`, `cf_connect_fronted`, `ws_handshake_split_host`, FFI `SetVlessNetworkConfig`). Протестирован парсинг URI, сохранение пула, генерация ссылок, пересобраны и верифицированы нативные библиотеки для всех 4 платформ Android (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`), тесты успешно пройдены.

* **TSK-V04: Неработоспособность встроенных пресетов и скачивания подписок**
  * **Проблема**: Встроенный список пресетов в `VlessPresetsRepository` состоит из публичных доменов Cloudflare Pages (`free-vless.pages.dev`, `bpb-vless.pages.dev`), которые в РФ либо внесены в реестр блокировок РКН, либо исчерпали лимиты запросов (HTTP 429 / 10006). Автоматическое скачивание подписок через `HttpURLConnection` падает, так как GitHub и jsdelivr блокируются ТСПУ при прямом обращении.
  * **Требуемое исправление**:
    1. Маршрутизировать загрузку подписок через локальный прокси (`127.0.0.1:10808`), если он запущен, либо через DoH + TLS-фрагментацию.
    2. Заменить дефолтные пресеты на проверенные и устойчивые узлы.
    3. Добавить поддержку парсинга base64-подписок с Reality и TCP-конфигами.
  * **Файлы**: [core/src/main/kotlin/com/mirrly/tgproxy/core/VlessPreset.kt](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/core/src/main/kotlin/com/mirrly/tgproxy/core/VlessPreset.kt), [core/src/main/kotlin/com/mirrly/tgproxy/core/TlsFragmentingSocketFactory.kt](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/core/src/main/kotlin/com/mirrly/tgproxy/core/TlsFragmentingSocketFactory.kt), [app/src/main/java/com/mirrly/tgproxy/service/ProxyForegroundService.kt](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/app/src/main/java/com/mirrly/tgproxy/service/ProxyForegroundService.kt), [app/src/main/java/com/mirrly/tgproxy/ui/SettingsScreen.kt](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/app/src/main/java/com/mirrly/tgproxy/ui/SettingsScreen.kt), [core/src/test/kotlin/com/mirrly/tgproxy/core/VlessPresetsRepositoryTest.kt](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/core/src/test/kotlin/com/mirrly/tgproxy/core/VlessPresetsRepositoryTest.kt)
  * **Статус**: ВЫПОЛНЕНО.
    1. Маршрутизация загрузки подписок: реализован двухконтурный механизм загрузки через `buildSubscriptionHttpClient` и `isLocalSocks5Active`. При активном локальном SOCKS5 (:10808) загрузка подписок туннелируется через текущий рабочий прокси, а в качестве резерва (или при отключенном прокси) используется защищенный `DohOkHttpDns` в сочетании с динамической фрагментацией пакета TLS ClientHello (`TlsFragmentingSocketFactory`), что обходит блокировки GitHub/jsdelivr на ТСПУ.
    2. Встроенные пресеты заменены на устойчивые узлы с распределением по Clean IP Anycast CDN и прямым VLESS Reality узлам с SNI `gateway.icloud.com` и `www.microsoft.com`. Добавлены проверенные зеркала подписок (`ghfast.top`, `ghproxy.net`).
    3. Парсер `parseSubscriptionStream` переработан: добавлена поддержка стандартного Base64, MIME с переносами строк (`\r\n`), URL-safe Base64 без паддинга, построчного Base64, а также сохранение параметров Reality (`security=reality`, `pbk`, `sid`, `fp`, `spx`) и TCP Vision (`transport=tcp`, `flow=xtls-rprx-vision`).
    4. Все модульные тесты добавлены и успешно пройдены (`BUILD SUCCESSFUL in 32s`).

---

### Блок 2. Нативное ядро VLESS (Rust Engine / `mirrlyengine`)

* **TSK-V05: Жесткая привязка к Cloudflare CDN в нативном релее**
  * **Проблема**: В [vless.rs](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/mirrlyengine/src/vless.rs#L266) соединение принудительно вызывается через `cf_connect_domain(&vless_domain, &ws_path, 5.0)`. Это означает, что движок пытается резолвить любой VLESS-сервер через Anycast-пул Cloudflare и общаться с ним как с воркером. Подключение к обычному независимому VPS (Xray/Sing-box на Ubuntu/Debian) завершается крахом.
  * **Требуемое исправление**: Реализовать универсальный диалер: если домен/IP не относится к пулу Cloudflare, устанавливать прямое TCP-соединение на `server_address:server_port` с последующим TLS/Reality хэндшейком без утечки/отката в публичные воркеры Cloudflare.
  * **Файлы**: [mirrlyengine/src/vless.rs](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/mirrlyengine/src/vless.rs), [mirrlyengine/src/socks5.rs](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/mirrlyengine/src/socks5.rs), [mirrlyengine/src/cfproxy.rs](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/mirrlyengine/src/cfproxy.rs), [mirrlyengine/src/ws.rs](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/mirrlyengine/src/ws.rs), [mirrlyengine/src/lib.rs](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/mirrlyengine/src/lib.rs), [core/src/main/kotlin/com/mirrly/tgproxy/core/NativeProxy.kt](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/core/src/main/kotlin/com/mirrly/tgproxy/core/NativeProxy.kt), [core/src/main/kotlin/com/mirrly/tgproxy/core/LocalProxyServer.kt](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/core/src/main/kotlin/com/mirrly/tgproxy/core/LocalProxyServer.kt)
  * **Статус**: ВЫПОЛНЕНО.
    1. Реализован детектор пула Cloudflare: функции `is_cloudflare_domain`, `is_cloudflare_ip` (проверка по всем 15 префиксам IPv4 и 7 диапазонам IPv6 Cloudflare) и `is_cloudflare_target`.
    2. В `cfproxy.rs` метод `resolve_dual_stack_ips` изолирован: инъекция Anycast IP Cloudflare выполняется строго для Cloudflare-доменов, а для независимых VPS внедрен метод `resolve_clean_dual_stack_ips` без подмешивания пула Anycast.
    3. Создан универсальный диалер `vless_acquire_uplink`:
       - Ветвь прямого VPS (`is_direct_vps()`): разрешает «чистые» адреса узла, устанавливает прямое TCP-соединение через `happy_eyeballs_tcp_connect` на `server_address:server_port`, производит хэндшейк TLS или Reality и передает сырые байты потока (без оборачивания в WebSocket), исключая аварийный откат на публичные воркеры при сбое.
       - Ветвь Cloudflare: сохраняет логику WebSocket WSS и гонку воркеров Happy Eyeballs.
    4. В `socks5.rs` расширен `SocksUplink` (`Tcp`, `Tls`), реализована обобщенная функция моста `bridge_socks5_stream` с передачей начального пакета VLESS downlink и подсчетом трафика.
    5. Добавлен FFI-экспорт `SetVlessExtendedConfig` в `lib.rs` со всеми параметрами безопасности/транспорта и сквозной проброс через `NativeProxy.kt` и `LocalProxyServer.kt`.
    6. Все нативные библиотеки для 4 платформ Android (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) успешно пересобраны и верифицированы, все юнит-тесты пройдены.

* **TSK-V06: Реализация протокола VLESS-Reality в Rust**
  * **Проблема**: Полное отсутствие криптографического стека Reality в нативном коде. Reality требует генерации эфемерной пары ключей X25519, подстановки публичного ключа сервера `pbk`, вычисления общего секрета, зашифрования auth tag в поле Session ID пакета TLS 1.3 ClientHello и проверки ответа ServerHello.
  * **Требуемое исправление**: Интегрировать модуль Reality-хэндшейкера на базе `ring` / `x25519-dalek` в `mirrlyengine`, реализующий клиентскую часть протокола Reality для маскировки под доверенные SNI без необходимости в собственном сертификате.
  * **Файлы**: [mirrlyengine/src/vless.rs](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/mirrlyengine/src/vless.rs), [mirrlyengine/src/reality.rs](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/mirrlyengine/src/reality.rs)
  * **Статус**: ВЫПОЛНЕНО.
    1. В [reality.rs](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/mirrlyengine/src/reality.rs) реализован полноценный клиентский криптографический стек протокола REALITY (в соответствии со спецификацией Xray-core Reality):
       - Декодирование ключа сервера `pbk` (32 байта X25519 из Base64, URL-Safe Base64 без паддинга или Hex) и идентификатора `short_id` (0..8 байт).
       - Генерация эфемерной пары ключей X25519 через `ring::agreement::EphemeralPrivateKey` с вычислением открытого ключа `client_pub`.
       - Вычисление общего секрета ECDH `X25519(client_priv, server_pbk)` через `ring::agreement::agree_ephemeral`.
       - Формирование 32 байт `client_random`, где первые 20 байт служат солью для HKDF, а последние 12 байт — Nonce для шифрования AES-256-GCM.
       - Вывод ключа аутентификации `AuthKey` (32 байта) через функцию `HKDF-SHA256(salt, shared_secret, info = "REALITY")`.
       - Формирование 16-байтовой полезной нагрузки `Session ID`: версия ядра (1.8.23), зарезервированный байт 0, Unix timestamp (big-endian `u32`) и байты `short_id`.
       - Генерация валидного пакета TLS 1.3 `ClientHello`, имитирующего современный Google Chrome (набор шифров Chrome, расширения SNI, ALPN `h2,http/1.1`, supported_versions TLS 1.3/1.2, supported_groups `x25519`, key_share).
       - Аутентифицированное зашифрование метки в поле `Session ID` алгоритмом AES-256-GCM (`ring::aead`) с `AuthKey`, 12-байтовым Nonce и всем сообщением `ClientHello` (с нулевым Session ID) в качестве дополнительных аутентифицированных данных (AAD).
       - Парсинг и валидация ответа сервера `ServerHello` (тип записи 0x16, тип хэндшейка 0x02, проверка длины и отсечение TLS Alert 0x15).
       - Поддержка пропуска фиктивных записей TLS 1.3 `ChangeCipherSpec` (`0x14, 0x03, 0x03, 0x00, 0x01, 0x01`) в сокете и парсере ответа VLESS.
    2. Добавлены всесторонние модульные тесты: декодирование ключей и short_id, детерминированность HKDF-SHA256, полный цикл шифрования/дешифрования Reality Session ID между клиентом и сервером, валидация ServerHello.
    3. Библиотеки успешно пересобраны для всех архитектур Android (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`), тесты Gradle пройдены.

* **TSK-V07: Реализация протокола Flow `xtls-rprx-vision` (Vision)**
  * **Проблема**: В функции `build_vless_header` зашит байт `addons_len = 0` ([vless.rs:141](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/mirrlyengine/src/vless.rs#L141)). Протокол Vision необходим для борьбы с DPI: он распознает вложенный TLS-трафик Telegram/HTTPS, убирает двойное шифрование и рандомизирует размеры пакетов (Padding), разрушая тайминг-сигнатуры ТСПУ.
  * **Требуемое исправление**: Реализовать поддержку расширения VLESS Addons: передача строки flow `xtls-rprx-vision` и обработка контрольных фреймов Vision (direct/padding state machine).
  * **Файлы**: [mirrlyengine/src/vision.rs](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/mirrlyengine/src/vision.rs), [mirrlyengine/src/vless.rs](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/mirrlyengine/src/vless.rs), [mirrlyengine/src/socks5.rs](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/mirrlyengine/src/socks5.rs), [mirrlyengine/src/lib.rs](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/mirrlyengine/src/lib.rs)
  * **Статус**: ВЫПОЛНЕНО.
    1. Создан модуль [mirrlyengine/src/vision.rs](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/mirrlyengine/src/vision.rs), реализующий полную спецификацию протокола XTLS Vision (`xtls-rprx-vision`):
       - Сериализация Protobuf-сообщения `Addons` (поле 1 `Flow = "xtls-rprx-vision"`, тег `0x0A`, длина 16, 18 байт суммарно).
       - Упаковка фреймов Vision `pack_vision_frame`: опциональный 16-байтный префикс `UUID` для первого фрейма, 1 байт команды (`0x00` continue, `0x01` end, `0x02` direct), 2 байта длины данных (`content_len`, big-endian `u16`), 2 байта длины паддинга (`padding_len`, big-endian `u16`), тело данных и криптографически случайный паддинг.
       - Алгоритм рандомизации размеров (Padding): маскировка TLS ClientHello / ServerHello длиной до 900–1400 байт (`random_add` до 500 байт), разрушающая тайминги и сигнатуры ТСПУ/DPI.
       - Детектор вложенного TLS-трафика (`is_tls_client_hello`, `is_tls_application_data`, `is_complete_tls_record`).
       - Автомат состояний восходящего потока `VisionFramer`: начальная фаза с паддингом и UUID, фильтрация пакетов, переход в режим `Direct` при получении первой записи Application Data (`0x17, 0x03, 0x03`) с отправкой команды `0x02` (`CMD_PADDING_DIRECT`), либо переход по `0x01` (`CMD_PADDING_END`) для не-TLS трафика (MTProto/SOCKS5). В прямом режиме данные передаются без накладных расходов.
       - Декодер нисходящего потока `VisionUnpadder`: потоковая сборка фреймов из TCP-сокета, автоматическое отсечение 16-байтного префикса UUID, извлечение полезной нагрузки, отбрасывание паддинга и переключение в сквозной режим при получении команд `0x01`/`0x02`.
    2. В [vless.rs](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/mirrlyengine/src/vless.rs):
       - Расширен конструктор заголовка `build_vless_header_ext` с передачей строки `flow`: при включенном Vision байт `addons_len` равен `18` (`0x12`), за которым следует Protobuf `Addons`. Для обратной совместимости сохранена функция `build_vless_header`.
       - `VlessConfig` дополнен методами `is_vision()` и `effective_flow()`.
       - В `vless_acquire_uplink` сформирован `VisionContext` с UUID клиента, который передается в `VlessUplink::Tcp` и `VlessUplink::Tls`.
    3. В [socks5.rs](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/mirrlyengine/src/socks5.rs):
       - Варианты `SocksUplink::Tcp` и `SocksUplink::Tls` обновлены для хранения `Option<VisionContext>`.
       - В `bridge_socks5_stream` интегрированы независимые асинхронные задачи: восходящий поток обрабатывается через `VisionFramer`, нисходящий — через `VisionUnpadder` (включая предварительный буфер `initial_downlink`), с мгновенным переключением на прямой TCP-сплайсинг без блокировок и мьютексов.
    4. Добавлен модуль `vision` в [lib.rs](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/mirrlyengine/src/lib.rs).
    5. Добавлен комплекс модульных тестов: кодирование Protobuf Addons, детекция TLS, поблочная и фрагментированная распаковка фреймов, жизненный цикл автоматов состояний для TLS и не-TLS протоколов, валидация заголовка VLESS с расширением Addons.
    6. Все 4 архитектуры Android (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) успешно пересобраны через `build_native.ps1`, тесты Gradle пройдены.


* **TSK-V08: Поддержка команды VLESS UDP (0x02) для голосовых и видеозвонков**
  * **Проблема**: В `build_vless_header` жестко зашита команда `0x01` (TCP) ([vless.rs:143](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/mirrlyengine/src/vless.rs#L143)). Трафик голосовых и видеозвонков Telegram идет по протоколу UDP через SOCKS5 UDP Associate. Текущий код не поддерживал проксирование UDP-пакетов через VLESS.
  * **Требуемое исправление**: Добавить обработку команды `0x02` (UDP), упаковку заголовка длины и формата адресации (RFC 1928) для трансляции датаграмм Telegram VoIP через VLESS-релей.
  * **Файлы**: [mirrlyengine/src/vless.rs](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/mirrlyengine/src/vless.rs), [mirrlyengine/src/socks5.rs](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/mirrlyengine/src/socks5.rs)
  * **Статус**: ВЫПОЛНЕНО.
  * **Реализация**:
    1. В [vless.rs](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/mirrlyengine/src/vless.rs):
       - Определены константы команд: `VLESS_CMD_TCP = 0x01`, `VLESS_CMD_UDP = 0x02`, `VLESS_CMD_MUX = 0x03`.
       - Функция `build_vless_header_cmd` принимает параметр `command: u8`, обеспечивая упаковку команды `0x02` (UDP) без наложения надстройки Vision (Protobuf Addons передаются только для TCP).
       - Реализовано обрамление датаграмм VLESS UDP: `pack_vless_udp_packet` (2-байтный big-endian префикс длины) и `unpack_vless_udp_packets` (потоковый парсер пакетов с сохранением незавершенных фрагментов в буфере).
       - Реализованы структуры и функции сериализации/парсинга датаграмм RFC 1928: `parse_socks5_udp_packet` (поддержка IPv4, FQDN Domain, IPv6) и `build_socks5_udp_packet`.
       - Добавлена функция `vless_acquire_uplink_cmd(target_addr, command, cancel_token)` и сохранен обратный фасад `vless_acquire_uplink`.
    2. В [socks5.rs](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/mirrlyengine/src/socks5.rs):
       - В цикле обработки входящих SOCKS5-запросов добавлен перехват команды `0x03` (`UDP ASSOCIATE`) с передачей управления в `handle_socks5_udp_associate`.
       - Создается и привязывается локальный UDP-сокет на `127.0.0.1:0`, клиенту возвращается успешный ответ с фактическим назначенным портом `BND.PORT`.
       - В соответствии с RFC 1928 жизненный цикл UDP-ассоциации строго привязан к TCP-соединению: фоновая задача отслеживает закрытие TCP-сокета и инициирует корректное завершение UDP-релея.
       - Реализован мультиплексор целевых сессий (`sessions: Arc<Mutex<HashMap<String, mpsc::Sender<Vec<u8>>>>>`) для независимого параллельного туннелирования VoIP-пакетов к разным рефлекторам Telegram.
       - Модуль `bridge_vless_udp_target` и обобщенный `bridge_vless_udp_stream` обеспечивают двунаправленный асинхронный мост: восходящие датаграммы упаковываются в длину VLESS UDP и отправляются в аплинк (WS, TCP или TLS), а входящие потоковые чанки распаковываются и капсулируются в RFC 1928 SOCKS5 UDP пакеты для отправки обратно клиенту.
    3. Добавлены модульные тесты: валидация заголовка VLESS с `VLESS_CMD_UDP`, упаковка и фрагментированная потоковая распаковка VLESS UDP, сериализация и десериализация SOCKS5 UDP пакетов для IPv4 и доменных имен.
    4. Все 4 архитектуры Android (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) успешно пересобраны через `build_native.ps1`, тесты Gradle пройдены.

* **TSK-V09: Маскировка отпечатка TLS ClientHello (uTLS Fingerprint)**
  * **Проблема**: В [ws.rs:38-47](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/mirrlyengine/src/ws.rs#L38-L47) использовался стандартный клиент `rustls`. Его TLS ClientHello имел характерный фиксированный порядок ciphersuites и расширений (JA3/JA4 hash) без ALPN и GREASE, который идентифицировался ТСПУ как неопознанный бот / VPN-клиент и сбрасывался пакетом TCP RST.
  * **Требуемое исправление**: Реализовать эмуляцию отпечатка современных браузеров (Chrome / Firefox / Safari): корректный порядок расширений TLS, поддержка GREASE (Generate Random Extensions And Sustain Extensibility) и ALPN `h2,http/1.1`.
  * **Файлы**: [mirrlyengine/src/reality.rs](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/mirrlyengine/src/reality.rs), [mirrlyengine/src/ws.rs](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/mirrlyengine/src/ws.rs), [mirrlyengine/src/vless.rs](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/mirrlyengine/src/vless.rs)
  * **Статус**: ВЫПОЛНЕНО.
  * **Реализация**:
    1. В [reality.rs](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/mirrlyengine/src/reality.rs):
       - Определены константы и генератор RFC 8701 GREASE (`GREASE_VALUES`, `distinct_grease(count)`).
       - Реализована эмуляция структуры пакета TLS ClientHello под профили современных браузеров в `build_reality_client_hello_msg_ext`:
         - **Chrome / Safari / iOS**: инъекция псевдослучайных GREASE-значений в шифр-сьюты (позиция 0), расширения (первое и замыкающее), `supported_groups`, `supported_versions` и `key_share`. Добавлено расширение ALPN `h2, http/1.1`.
         - **Firefox**: точный порядок ciphersuites (`0x1301`, `0x1303`, `0x1302`), расширений в соответствии со спецификацией Mozilla, ALPN `h2, http/1.1`, без использования GREASE.
       - В `reality_connect_ext` внедрена фрагментация TCP ClientHello: первые 64 байта отправляются отдельным сегментом со сбросом буфера (`flush`) и микрозадержкой в 3 мс перед передачей остатка записи. Это разрывает заголовок TLS-записи и строку SNI по разным TCP-сегментам, нейтрализуя сигнатурный анализ ТСПУ DPI.
       - Предоставлена функция `reality_connect_ext` с параметром `fingerprint` и сохранена обертка `reality_connect`.
    2. В [ws.rs](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/mirrlyengine/src/ws.rs):
       - Разработана логика приоритизации шифров `order_cipher_suites_for_profile` для `rustls` (дифференциация для Chrome, Firefox, Safari/iOS).
       - Реализован генератор конфигураций `build_tls_config_for_fingerprint` с явным указанием ALPN `["h2", "http/1.1"]`, кэшированием сессий (128 записей) и пулом статических инстансов `TLS_CONFIG_CHROME`, `TLS_CONFIG_FIREFOX`, `TLS_CONFIG_SAFARI`.
       - Функция `get_tls_config_for_fingerprint` поддерживает профили `"chrome"`, `"firefox"`, `"safari"`, `"ios"` и `"randomized"` (динамическая ротация профилей).
       - Реализованы асинхронные функции `ws_handshake_split_host_fp` и `ws_handshake_over_stream_fp`, принимающие параметр `fingerprint`.
    3. В [vless.rs](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/mirrlyengine/src/vless.rs):
       - Структура `VlessConfig` дополнена методом `effective_fingerprint()`.
       - В `vless_acquire_uplink_cmd` значение `fingerprint` извлекается из конфигурации и пробрасывается во все типы аплинка: Reality (`reality_connect_ext`), прямой TCP с TLS (`get_tls_config_for_fingerprint`) и WebSocket над TLS (`ws_handshake_split_host_fp`).
    4. Добавлены модульные тесты: `test_distinct_grease`, `test_build_reality_client_hello_chrome_vs_firefox`, `test_tls_config_alpn_and_profiles`.
    5. Все 4 нативные библиотеки Android (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) успешно скомпилированы через `build_native.ps1`, тесты Gradle `:core:test` успешно пройдены.

* **TSK-V10: Поддержка Early Data в VLESS over WebSocket (0-RTT Handshake)**
  * **Проблема**: При использовании WebSocket соединение требует двух полных RTT (TCP handshake + TLS handshake + HTTP Upgrade + VLESS handshake). Параметр `?ed=2048` сейчас просто висит в строке URL, но сам заголовок `Sec-WebSocket-Protocol` с полезной нагрузкой 0-RTT не передается.
  * **Требуемое исправление**: Реализовать передачу начального фрейма VLESS в заголовке `Sec-WebSocket-Protocol: base64(vless_header + initial_data)` при рукопожатии HTTP Upgrade, сокращая время установки связи на 100–150 мс.
  * **Файлы**: [mirrlyengine/src/ws.rs](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/mirrlyengine/src/ws.rs), [mirrlyengine/src/cfproxy.rs](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/mirrlyengine/src/cfproxy.rs), [mirrlyengine/src/vless.rs](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/mirrlyengine/src/vless.rs), [tools/deploy-worker/worker.js](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/tools/deploy-worker/worker.js), [docs/cloudflare_worker.js](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/docs/cloudflare_worker.js)
  * **Статус**: ВЫПОЛНЕНО.
    * **Выполненные работы**:
      1. В [ws.rs](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/mirrlyengine/src/ws.rs):
         - Реализовано кодирование Early Data `encode_ws_early_data(data: &[u8]) -> String` с использованием URL-Safe Base64 без паддинга (`URL_SAFE_NO_PAD` по стандарту Xray/RFC 8441).
         - Реализован парсер длины `parse_early_data_header_len(path: &str) -> Option<usize>`, извлекающий лимит байт из query-параметра `?ed=NNNN`.
         - Добавлен метод `RawWebSocket::is_early_data_sent(&self) -> bool` и поле `early_data_sent: bool`.
         - Функция `ws_handshake_split_host_ext` принимает `early_data: Option<&[u8]>`: при его наличии формирует заголовок `Sec-WebSocket-Protocol: <base64url>` и помечает `early_data_sent: true`. При отсутствии отправляет дефолтный `Sec-WebSocket-Protocol: binary`.
         - Функция `ws_connect_happy_eyeballs_ext` расширена для проброса `early_data` в параллельные гонки сокетов.
      2. В [cfproxy.rs](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/mirrlyengine/src/cfproxy.rs):
         - Добавлены функции `cf_connect_domain_ext` и `cf_connect_fronted_ext` с параметром `early_data: Option<&[u8]>`.
         - Стандартные функции `cf_connect_domain` и `cf_connect_fronted` делегируют вызовы в `_ext` с `None`.
      3. В [vless.rs](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/mirrlyengine/src/vless.rs):
         - Все 4 точки установки WebSocket-соединения адаптированы под 0-RTT:
           1) Direct VPS WebSocket over TLS (`ws_handshake_split_host_ext`).
           2) Priority 0: Preset / Fronting (`cf_connect_fronted_ext`).
           3) Priority 1: Custom Worker domain (`cf_connect_domain_ext`).
           4) Priority 2: Staggered concurrent fallback race (`cf_connect_domain_ext`).
         - Если `ws.is_early_data_sent()` возвращает `true`, повторная отправка VLESS-заголовка через `ws.send(&vless_req)` пропускается, исключая дублирование данных на стороне сервера.
      4. В Cloudflare Worker ([tools/deploy-worker/worker.js](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/tools/deploy-worker/worker.js) и [docs/cloudflare_worker.js](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/docs/cloudflare_worker.js)):
         - Добавлена функция `decodeBase64Url(str)` для разбора URL-safe unpadded Base64.
         - Реализовано извлечение полезной нагрузки Early Data из заголовка `Sec-WebSocket-Protocol`.
         - В `handleVlessWebSocket` при наличии 0-RTT данных инициируется немедленный TCP-коннект к Telegram и отправка ответа `[0x00, 0x00]` до получения первого WS-фрейма.
      5. Добавлены unit-тесты `test_early_data_encoding_and_path_parsing`.
      6. Все 4 нативные сборки Android (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) успешно скомпилированы через `build_native.ps1`, тесты Gradle `:core:test` успешно пройдены.

---

### Блок 3. Маршрутизация, Диагностика и Отказоустойчивость

* **TSK-V11: Устранение параллельного спама соединений в Happy Eyeballs**
  * **Проблема**: Текущий цикл [vless.rs:414-472](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/mirrlyengine/src/vless.rs#L414-L472) при каждом запросе запускает параллельную гонку по десяткам воркеров. Это перегружает сетевой стек Android, создает паразитный трафик и триггерит rate-limits ТСПУ на частые синхронные SYN-пакеты.
  * **Требуемое исправление**: Внедрить кэширование и скоринг активного быстрого узла (Sticky Session с периодическим фоновым Health Check раз в 30–60 секунд). Переключать узел только при фактическом сбое или RTO > 1500 мс.
  * **Файлы**: [mirrlyengine/src/vless.rs](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/mirrlyengine/src/vless.rs), [mirrlyengine/src/lib.rs](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/mirrlyengine/src/lib.rs)
  * **Статус**: ВЫПОЛНЕНО.
    * **Выполненные работы**:
      1. В [vless.rs](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/mirrlyengine/src/vless.rs):
         - Разработана структура `VlessStickyNode` (активный домен, метка времени успешного коннекта, сглаженный RTT в мс по формуле `(old * 7 + new * 3) / 10` и счетчик последовательных сбоев `consecutive_failures`).
         - Разработан менеджер скоринга `VlessScorer` с глобальным потокобезопасным экземпляром `VLESS_SCORER` (`Lazy<RwLock<VlessScorer>>`).
         - Реализован **Sticky Session Fast-Path**: при наличии валидного закешированного узла соединение устанавливается напрямую к нему с жестким лимитом тайм-аута RTO 1500 мс. При успехе полностью исключается запуск параллельной гонки Happy Eyeballs, предотвращая лавинный спам SYN-пакетов при одновременном открытии множества сокетов клиентом Telegram.
         - Реализовано переключение узла только при фактическом сбое или превышении RTO 1500 мс: фиксируется отказ узла (`record_failure`), при двух последовательных сбоях узел дисквалифицируется, и управление передается оптимизированной гонке Happy Eyeballs.
         - Оптимизирована гонка Happy Eyeballs: активные кандидаты сортируются по историческому RTT-скорингу, а размер пула параллельной гонки ограничен топ-4 узлами с подавлением спама.
         - Добавлен механизм фоновой очистки (drain) соединений, завершившихся позже победителя гонки (runner-ups закрываются в фоне через `tokio::spawn(ws.close())`).
         - Реализован периодический фоновый Health Check (`maybe_trigger_vless_background_probe`): раз в 45–60 секунд в неблокирующей фоновой корутине зондируются кандидаты из пула резервных узлов; при обнаружении альтернативного узла, опережающего текущий более чем на 80 мс (порог гистерезиса для защиты от дрожания мобильной сети LTE), активный sticky-узел плавно переключается.
      2. В [lib.rs](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/mirrlyengine/src/lib.rs):
         - Интегрирован вызов `vless::reset_vless_scorer()` при остановке службы (`StopProxy`), смене сетевых интерфейсов (`ResetNetworkSockets`), старте прокси и обновлении параметров Cloudflare Worker.
      3. Добавлены модульные тесты: `test_vless_scorer_sticky_flow_and_demotion`, `test_vless_scorer_probe_timing`.
      4. Все 4 нативные сборки Android (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) успешно пересобраны через `build_native.ps1`, тесты Gradle `:core:test` успешно пройдены.

* **TSK-V12: Сквозное измерение RTT и доступности VLESS-узлов в UI**
  * **Проблема**: В [NodeHealthProber.kt](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/core/src/main/kotlin/com/mirrly/tgproxy/core/NodeHealthProber.kt) и [NodeHealthCard.kt](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/app/src/main/java/com/mirrly/tgproxy/ui/NodeHealthCard.kt) проверка VLESS узлов выполняется поверхностно, отображая фиктивные статусы задержки без реальной валидации прохождения байт через VLESS-релей.
  * **Требуемое исправление**: Добавить в зондировщик реальный тест прохождения данных через нативный VLESS-канал с замером RTT и отображением точной задержки (мс) в карточке узла.
  * **Файлы**: [core/src/main/kotlin/com/mirrly/tgproxy/core/NodeHealthProber.kt](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/core/src/main/kotlin/com/mirrly/tgproxy/core/NodeHealthProber.kt), [app/src/main/java/com/mirrly/tgproxy/ui/NodeHealthCard.kt](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/app/src/main/java/com/mirrly/tgproxy/ui/NodeHealthCard.kt)
  * **Статус**: ВЫПОЛНЕНО.
    * **Реализация**:
      1. В [NodeHealthProber.kt](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/core/src/main/kotlin/com/mirrly/tgproxy/core/NodeHealthProber.kt):
         - Реализован сквозной VLESS E2E зонд (`probeVlessE2E`): полный путь TCP -> TLS -> WS 101 Upgrade -> отправка бинарного VLESS header (26 байт, version 0x00 + UUID + target 149.154.167.51:443 Telegram DC2) через WebSocket binary frame -> получение и валидация VLESS response (version 0x00 + addons_len).
         - Реализован низкоуровневый WebSocket binary framing: `sendWsBinaryFrame` (клиент -> сервер, с маскированием) и `readWsBinaryFrame` (сервер -> клиент, без маски) для обмена бинарными VLESS пакетами поверх WS-соединения.
         - Реализован сборщик VLESS probe header (`buildVlessProbeHeader`) и парсер UUID (`parseUuidHexToBytes`).
         - Модифицирован `probeSingle`: добавлен третий параллельный `vlessE2eDeferred` для узлов VLESS_PAGES и WORKER; результат записывается в `nodeStatus`/`nodeLatencyMs`/`nodeDetail`.
      2. В [NodeHealthCard.kt](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/app/src/main/java/com/mirrly/tgproxy/ui/NodeHealthCard.kt):
         - Третья колонка таблицы переименована в `СКВОЗНОЙ E2E` и отображает результат `probeVlessE2E` для узлов VLESS/Workers (бирюзовый цвет, префикс E2E, точная задержка в мс) или каскад через VLESS для Opera VPN.
         - `StatusBadge` расширен параметром `isVlessE2E` с визуально выделенным бирюзовым цветом `0xFF4DD0E1` и контекстными текстами ошибок (`E2E сбой`, `E2E тайм`, `ТСПУ E2E`).
         - В панель статистики добавлен счетчик `E2E: N` (количество узлов, прошедших сквозной VLESS relay тест).

* **TSK-V13: Полная интеграция FFI параметров между Kotlin и Rust**
  * **Проблема**: Метод `SetVlessConfig` в [lib.rs:444](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/mirrlyengine/src/lib.rs#L444) принимает всего три строки: `uuid`, `path`, `domain`. Передать параметры Reality (`pbk`, `sid`, `spx`), тип транспорта (`tcp`/`ws`/`grpc`), flow (`vision`) или кастомный SNI невозможно.
  * **Требуемое исправление**: Расширить нативный интерфейс FFI (`SetVlessConfigExtended` / JSON-конфиг) для передачи полной спецификации ноды VLESS в нативное ядро.
  * **Файлы**: [mirrlyengine/src/lib.rs](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/mirrlyengine/src/lib.rs), [core/src/main/kotlin/com/mirrly/tgproxy/core/NativeProxy.kt](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/core/src/main/kotlin/com/mirrly/tgproxy/core/NativeProxy.kt)
  * **Статус**: ВЫПОЛНЕНО.
    * **Реализация**:
      1. В [mirrlyengine/src/vless.rs](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/mirrlyengine/src/vless.rs):
         - Реализованы структуры `VlessConfigUpdate` (с `#[derive(Deserialize)]`) и `VlessConfigDto` (с `#[derive(Serialize)]`) для сквозного JSON-конфигурирования VLESS ядра со всеми полями (`uuid`, `path`, `domain`, `server_address`, `server_port`, `tls_sni`, `host_header`, `transport`, `security`, `public_key`, `short_id`, `fingerprint`, `spider_x`, `flow`, `header_type`).
         - Реализованы функции `set_vless_config_json` (безопасное обновление без затирания опущенных полей) и `get_vless_config_json` (сериализация текущего состояния).
         - Исправлены `set_vless_config` и `set_vless_network_config`: устранен баг затирания расширенных полей Reality (`public_key`, `short_id`, `spider_x`), Vision (`flow`) и транспорта пустыми строками при вызове базовых сеттеров.
         - Добавлены Rust unit-тесты `test_vless_json_config_full_and_partial_update` и `test_vless_basic_config_preserves_extended_settings`.
      2. В [mirrlyengine/src/lib.rs](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/mirrlyengine/src/lib.rs):
         - Добавлены FFI экспорты `SetVlessConfigJson` и `GetVlessConfigJson`.
      3. В [core/src/main/kotlin/com/mirrly/tgproxy/core/NativeProxy.kt](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/core/src/main/kotlin/com/mirrly/tgproxy/core/NativeProxy.kt):
         - В `ProxyLibrary` объявлены `SetVlessConfigJson` и `GetVlessConfigJson`.
         - В объекте `NativeProxy` реализованы методы `setVlessConfigJson` и `getVlessConfigJson`.
         - `setVlessExtendedConfig` переведен на приоритетную передачу полной спецификации через `SetVlessConfigJson` с каскадным фоллбеком на `SetVlessExtendedConfig` -> `SetVlessNetworkConfig` -> `SetVlessConfig`.
         - `setVlessConfig` обновлен: формирует частичный JSON без затирания ранее сохраненных свойств Reality/Vision.
      4. В [core/src/main/kotlin/com/mirrly/tgproxy/core/LocalProxyServer.kt](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/core/src/main/kotlin/com/mirrly/tgproxy/core/LocalProxyServer.kt):
         - Добавлен метод `applyVlessPreset(preset: VlessPreset)`, передающий всю спецификацию ноды (включая Reality, Vision, Clean IP, HostHeader, SNI, Transport) в конфигурацию и нативное ядро.
         - Каскадный failover `STAGE_3_VLESS_PRESET` переведен на `applyVlessPreset(preset)`.
      5. В [SettingsScreen.kt](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/app/src/main/java/com/mirrly/tgproxy/ui/SettingsScreen.kt) и [NodeHealthCard.kt](file:///c:/projects/Mirrly%20dev/Mirrly%20TG%20Proxy/app/src/main/java/com/mirrly/tgproxy/ui/NodeHealthCard.kt):
         - Выбор пресетов Pages, импорт кастомных VLESS ссылок и применение узлов из карточки здоровья переведены на `applyVlessPreset`, гарантируя сохранность параметров Reality и Vision.
      6. Написаны и успешно пройдены модульные тесты в `:core:test`.

