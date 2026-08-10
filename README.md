<div align="center">

<img src="docs/assets/logo.png" alt="Mirrly TG Proxy Logo" width="220" />

# Mirrly TG Proxy для Android

**Локальный MTProto & SOCKS5 прокси-сервер с поддержкой личных Cloudflare Worker для подключения Telegram без VPN**

[![Android](https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9%2B-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/UI-Jetpack_Compose-4285F4?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Version](https://img.shields.io/badge/Релиз-v1.0.9-00E676?style=for-the-badge)](https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/releases)
[![Downloads](https://img.shields.io/github/downloads/joycecurcirt539-dot/Mirrly-TG-Proxy/total?style=for-the-badge&logo=github&logoColor=white&color=0088cc&label=Скачиваний)](https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/releases)
[![Stars](https://img.shields.io/github/stars/joycecurcirt539-dot/Mirrly-TG-Proxy?style=for-the-badge&logo=github&logoColor=white&color=f5a623&label=Звёзд)](https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/stargazers)
[![Changelog](https://img.shields.io/badge/История_изменений-CHANGELOG-blue?style=for-the-badge)](CHANGELOG.md)
[![License](https://img.shields.io/badge/Лицензия-GPLv3-lightgrey?style=for-the-badge)](LICENSE)

*Оптимизация маршрутизации трафика Telegram через зашифрованные WebSocket-сессии Cloudflare и личные Cloudflare Worker. Работает локально в фоновом режиме без прав администратора и создания VPN-профиля.*

---

</div>

## Оглавление

1. [Принцип работы](#1-принцип-работы)
2. [Личные Cloudflare Worker и Безопасность](#2-личные-cloudflare-worker-и-безопасность)
3. [Ключевые возможности](#3-ключевые-возможности)
4. [Архитектура системы](#4-архитектура-системы)
5. [Поддерживаемые клиенты Telegram](#5-поддерживаемые-клиенты-telegram)
6. [Интерфейс приложения](#6-интерфейс-приложения)
7. [Быстрый старт и установка](#7-быстрый-старт-и-установка)
8. [Конфигурация и параметры](#8-конфигурация-и-параметры)
9. [Сборка из исходного кода](#9-сборка-из-исходного-кода)
10. [Безопасность и условия использования](#10-безопасность-и-условия-использования)

---

## 1. Принцип работы

Mirrly TG Proxy запускает на Android-устройстве локальный прокси-сервер с двумя независимыми шлюзами:
* **MTProto Proxy (Порт 1443)**: нативный протокол для мгновенной загрузки сообщений, медиафайлов и каналов.
* **SOCKS5 Proxy (Порт 10808)**: прозрачный TCP-релей для обхода блокировок голосовых и видеовызовов Telegram.

Приложение инкапсулирует пакеты Telegram в зашифрованные HTTPS WebSocket-сессии (`wss://...`) и направляет их через глобальную сеть Cloudflare CDN (300+ дата-центров), обеспечивая стабильный и непрерывный обход блокировок DPI/ТСПУ без использования системного VPN.

### Преимущества архитектуры:
* **Без системного VpnService**: работает как локальный сокет, не перехватывает трафик сторонних приложений и не расходует лишний заряд аккумулятора.
* **Сквозное шифрование Telegram**: весь трафик защищен нативным шифрованием Telegram. Прокси выполняет транспортную роль и не имеет доступа к содержимому сообщений.
* **Маскировка под HTTPS**: провайдеры видят только стандартный легитимный HTTPS-трафик к Cloudflare.

---

## 2. Личные Cloudflare Worker и Безопасность

Mirrly TG Proxy спроектирован с упором на использование **ЛИЧНЫХ Cloudflare Worker**.

### Зачем нужен Личный Cloudflare Worker?
1. **100% Защита Личных Данных**: При использовании личного воркера ваш трафик туннелируется только через ваш персональный аккаунт Cloudflare. Ключи, логи и параметры доступа принадлежат исключительно вам.
2. **Персональный Лимит 100 000 Запросов в День**: Бесплатный тариф Cloudflare предоставляет 100 000 бесплатных запросов ежедневно лично на ваш аккаунт.
3. **Максимальная Нерасшаренная Скорость**: Отсутствие зависимости от сторонних серверов обеспечивает максимальную пропускную способность для звонков и 4K-медиа.

### Тестовые воркеры разработчика:
В приложение встроены тестовые воркеры разработчика Mirrly, созданные исключительно как наглядный демонстрационный пример технологии и для быстрой проверки работы приложения сразу после установки («из коробки»). Для постоянного использования рекомендуется развернуть собственный воркер за 2 минуты по встроенной пошаговой инструкции (исходный код воркера также доступен в репозитории: [`docs/cloudflare_worker.js`](docs/cloudflare_worker.js)).

---

## 3. Ключевые возможности

* **Двойной шлюз маршрутизации (MTProto + SOCKS5)**:
  * **MTProto Proxy (Порт 1443)**: нативный движок с WsPool, Fake-TLS маскировкой и быстрой загрузкой чатов.
  * **SOCKS5 Proxy (Порт 10808, БЕТА)**: поддержка обхода блокировок для голосовых и видеовызовов Telegram.
* **Унифицированное WSS-туннелирование Cloudflare Worker**: единый протокол туннелирования `wss://$cfDomain/tcp?target=$dcIp:443` через `cloudflare:sockets` для MTProto и SOCKS5.
* **Раздельные настройки тумблеров**: независимое управление вызовом тестового воркера для SOCKS5 и MTProto с 100% приоритетом собственного домена пользователя.
* **Умные действия в 1 клик**: автоматическое формирование нужного формата ссылки (`tg://socks` или `tg://proxy`) на кнопках «В Telegram» и «Скопировать».
* **Полная сохранность ключей шифрования**: сгенерированный секретный ключ MTProto (`secretHex`) надежно изолирован и сохраняется при любых переключениях между режимами SOCKS5 и MTProto.
* **Профили производительности (Speed Presets)**: `Турбо` (16 сокетов / 2 МБ буфер), `Баланс` (8 сокетов / 256 КБ буфер), `Эко` (2 сокета / 32 КБ буфер).
* **Мгновенная отдача (TCP_NODELAY)**: отключение алгоритма Нагла устраняет сетевые задержки (40–200 мс).
* **Умный таймер сна (Sleep Timer)**: автовыключение прокси по интервалу (от 15 мин до 6 ч) с уведомлениями и продлением в один клик.
* **Встроенный установщик обновлений**: проверка целостности по SHA-256 и верификация подписи разработчика (C++ NDK SignatureVerifier).
* **Чистый визуал в стиле вкладок-переходников**: все диалоги оформлены с размытием фона `FLAG_BLUR_BEHIND` и очищены от эмодзи.

---

## 4. Архитектура системы

```mermaid
flowchart TD
    subgraph UI_Layer ["Интерфейс приложения (Jetpack Compose & State Manager)"]
        HomeScreen["Главный экран\n(Статус, Капсула времени, Умные кнопки action)"]
        SettingsScreen["Экран настроек\n(Переключатель режимов, Кастомный домен, Тумблеры воркеров)"]
        Dialogs["Интерактивные диалоги\n(Инструкция воркера, Предупреждение SOCKS5, Таймер сна)"]
        PrefMgr[("PreferencesManager\n(SharedPreferences: secret_hex, proxy_mode)")]
        
        HomeScreen <--> SettingsScreen
        SettingsScreen <--> Dialogs
        SettingsScreen --> PrefMgr
    end

    subgraph Service_Layer ["Фоновая служба & Жизненный цикл (Android Foreground Service)"]
        ProxyService["ProxyForegroundService\n(Уведомления в шторке, Auto-reconnect)"]
        SleepTimer["SleepTimerManager\n(Таймер автоотключения)"]
        ServerCore["LocalProxyServer\n(Центральный менеджер прокси-серверов)"]
        
        PrefMgr --> ProxyService
        ProxyService --> ServerCore
        SleepTimer --> ProxyService
    end

    subgraph ModeSelector {"Переключатель режимов (proxyModeName)"}
        ServerCore --> ModeSelector
    end

    subgraph MTProto_Branch ["РЕЖИМ MTPROTO (Порт 1443) — Чаты, Медиа и Каналы"]
        MTProtoEngineSelector{"Проверка наличия воркера\n(getEffectiveCfDomain)"}
        
        ModeSelector -->|proxyMode = MTPROTO| MTProtoEngineSelector
        
        TgWsBridge["TgWsBridge (Kotlin-движок)\nWSS TCP туннелирование /tcp?target=..."]
        NativeEngine["NativeProxy.so (C++ NDK)\nFake-TLS dd... & Прямые WSS"]
        WsPool["WsPool Manager\n(Прогретый пул WSS-сокетов DC 1..5)"]
        FastFail["Smart Fast-Fail Fallback\n(2.5с таймаут прямого TCP)"]
        
        MTProtoEngineSelector -->|Воркер активен| TgWsBridge
        MTProtoEngineSelector -->|Без воркера| NativeEngine
        NativeEngine -.-> WsPool
        TgWsBridge -.-> WsPool
        NativeEngine -.-> FastFail
    end

    subgraph SOCKS5_Branch ["РЕЖИМ SOCKS5 (Порт 10808) — Чаты и Голосовые/Видеозвонки"]
        SocksEngine["Socks5WsBridge (Kotlin SOCKS5 Движок)\nСлушатель сокета 10808 & Прозрачный TCP Relay"]
        
        ModeSelector -->|proxyMode = SOCKS5| SocksEngine
    end

    subgraph Resolver_Layer ["Логика выбора Cloudflare Worker (getEffectiveCfDomain)"]
        WorkerResolver{"Приоритет домена воркера"}
        UserCustom["1. Кастомный домен пользователя\n(customCfDomain)"]
        DefaultWorker["2. Тестовый воркер Mirrly\n(useDefaultWorkerSocks5 / useDefaultWorkerMtproto)"]
        DirectRoute["3. Прямой маршрут без воркера"]
        
        WorkerResolver -->|Если заполнен customCfDomain| UserCustom
        WorkerResolver -->|Если пусто & включен тумблер| DefaultWorker
        WorkerResolver -->|Если пусто & выключен тумблер| DirectRoute
    end

    TgWsBridge --> WorkerResolver
    SocksEngine --> WorkerResolver

    subgraph Cloudflare_Edge ["Инфраструктура Cloudflare CDN (Edge Nodes)"]
        WssTunnel["WSS TLS-туннель (wss://.../tcp?target=...)\nМаскировка трафика под HTTPS"]
        CFWorker["Cloudflare Worker (V8 Edge Script)\nimport { connect } from 'cloudflare:sockets'"]
        
        UserCustom --> WssTunnel
        DefaultWorker --> WssTunnel
        WssTunnel --> CFWorker
    end

    subgraph Telegram_Infrastructure ["Дата-центры & Рефлекторы Telegram"]
        TelegramDC["Telegram DCs (DC 1 - DC 5)\n149.154.167.x:443 / 91.108.56.x:443"]
        VoIPNodes["Telegram VoIP Reflectors\n(Рефлекторы голосовых и видеовызовов)"]
        
        CFWorker -->|Прямой TCP сокет| TelegramDC
        CFWorker -->|Прямой TCP сокет| VoIPNodes
        DirectRoute -.->|Прямой TCP без CF| TelegramDC
    end

    TGApp["Клиенты Telegram\n(Official / AyuGram / NekoGram)"] -->|Подключение к 127.0.0.1:1443| MTProto_Branch
    TGApp -->|Подключение к 127.0.0.1:10808| SOCKS5_Branch
```

---

## 5. Поддерживаемые клиенты Telegram

Приложение автоматически сканирует установленные пакеты и позволяет подключить прокси в один клик:

* **Официальные клиенты**: Telegram, Telegram X
* **Расширенные клиенты**: AyuGram, NekoGram, Nagram, ExteraGram, Plus Messenger
* **Сторонние клиенты**: Cherrygram, Nicegram, iMe Messenger, Telegraph, MDGram, Dahl, Litegram, Nullgram, ForkClient, BifToGram

---

## 6. Интерфейс приложения

<div align="center">

| Главный экран | Таймер сна | История сессий (Временный интерфейс) |
| :-: | :-: | :-: |
| <img src="docs/assets/home.jpg" alt="Главный экран" width="240" /> | <img src="docs/assets/timer_sleep.jpg" alt="Таймер сна" width="240" /> | <img src="docs/assets/history.jpg" alt="История сессий" width="240" /> |

<br />

| Настройки (1 часть) | Настройки (2 часть) | Журнал событий |
| :-: | :-: | :-: |
| <img src="docs/assets/settings_1.jpg" alt="Настройки - Параметры" width="240" /> | <img src="docs/assets/settings_2.jpg" alt="Настройки - Диагностика" width="240" /> | <img src="docs/assets/logs.jpg" alt="Экран логов" width="240" /> |

</div>

---

## 7. Быстрый старт и установка

1. Скачайте официальный установочный пакет `app-release.apk` со страницы [Релизы GitHub](https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/releases).
2. Установите APK на устройство под управлением Android 8.0 или новее.
3. Запустите **Mirrly TG Proxy** и нажмите центральную кнопку включения.
4. Нажмите кнопку **«В Telegram»** для автоматического применения настроек в выбранном мессенджере.
5. Для 100% защиты данных нажмите **«ИНСТРУКЦИЯ ВОРКЕРА»** в Настройках и разверните свой личный бесплатный Cloudflare Worker за 2 минуты.

---

## 8. Конфигурация и параметры

| Параметр | По умолчанию | Описание |
| :--- | :--- | :--- |
| `proxyModeName` | `MTPROTO` | Текущий активный режим работы прокси (`MTPROTO` или `SOCKS5`) |
| `bindHost` / `bindPort` | `127.0.0.1:1443` | Локальный IP-адрес и TCP-порт для MTProto |
| `socks5Port` | `10808` | Локальный TCP-порт для SOCKS5 |
| `secretHex` | `ee000000...` | Секретный ключ MTProto (поддерживается Fake TLS `dd`) |
| `customCfDomain` | `""` | Персональный домен личного Cloudflare Worker пользователя |
| `useDefaultWorkerSocks5` | `true` | Использовать тестовый воркер разработчика для SOCKS5 при пустом поле домена |
| `useDefaultWorkerMtproto` | `false` | Использовать тестовый воркер разработчика для MTProto при пустом поле домена |
| `speedPreset` | `BALANCED` | Профиль скорости: `TURBO` (2 МБ / 16 сокетов), `BALANCED` (256 КБ / 8 сокетов), `ECO` (32 КБ / 2 сокета) |
| `tcpNoDelay` | `true` | Мгновенная отправка сетевых пакетов без задержек (TCP_NODELAY) |
| `poolSize` | `8` | Количество удерживаемых открытыми WSS-сокетов на каждый дата-центр |
| `autostartOnBoot` | `false` | Автоматический запуск службы при загрузке операционной системы |
| `disableAnimations` | `false` | Отключение визуальных эффектов для снижения энергопотребления |

---

## 9. Сборка из исходного кода

### Требования:
* Android SDK (API 34, Build-Tools 34.0.0)
* Android NDK (версия 25+)
* JDK 17+

```bash
# Клонирование репозитория
git clone https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy.git
cd Mirrly-TG-Proxy

# Сборка оптимизированного релизного пакета
./gradlew assembleRelease
```

Собранный файл будет доступен по пути: `app/build/outputs/apk/release/app-release.apk`.

---

## 10. Безопасность и условия использования

* **Отсутствие сбора данных**: приложение не содержит встроенной аналитики, телеметрии, рекламных SDK и не собирает пользовательские данные.
* **Криптографическая верификация**: встроенный механизм обновлений проверяет совпадение контрольных сумм SHA-256 и отпечатка ключа подписи разработчика перед установкой новых версий.
* **История изменений**: подробный список нововведений всех версий приведен в документе [CHANGELOG.md](CHANGELOG.md).
* **Лицензирование**: исходный код распространяется под лицензией [GNU General Public License v3 (GPLv3)](LICENSE).
* **Пользовательское соглашение**: правила распространения сборок и ограничения описаны в документе [TERMS_OF_USE.md](TERMS_OF_USE.md).
