<div align="center">

<img src="docs/assets/logo.png" alt="Mirrly TG Proxy Logo" width="220" />

# Mirrly TG Proxy для Android

**Локальный MTProto & SOCKS5 прокси-сервер с поддержкой личных Cloudflare Worker для подключения Telegram без VPN**

[![Android](https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9%2B-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/UI-Jetpack_Compose-4285F4?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Cloudflare](https://img.shields.io/badge/Cloudflare-Workers_V8-F38020?style=for-the-badge&logo=cloudflare&logoColor=white)](https://workers.cloudflare.com)
[![NDK](https://img.shields.io/badge/C%2B%2B-NDK_Native-00599C?style=for-the-badge&logo=cplusplus&logoColor=white)](https://developer.android.com/ndk)
<br/>
[![Version](https://img.shields.io/badge/Релиз-v1.1.0-00E676?style=for-the-badge)](https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/releases)
[![Downloads](https://img.shields.io/badge/Скачиваний-Релизы_GitHub-0088cc?style=for-the-badge&logo=github&logoColor=white)](https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/releases)
[![Stars](https://img.shields.io/badge/Звёзд-GitHub_Stars-f5a623?style=for-the-badge&logo=github&logoColor=white)](https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/stargazers)
[![Issues](https://img.shields.io/badge/Вопросы_и_баги-GitHub_Issues-E53935?style=for-the-badge&logo=github&logoColor=white)](https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/issues)
<br/>
[![Telegram](https://img.shields.io/badge/Telegram-Канал_сообщества-26A5E4?style=for-the-badge&logo=telegram&logoColor=white)](https://t.me/WhyOkyHb)
[![Privacy](https://img.shields.io/badge/Приватность-No_VPN_%7C_No_Logs-00C853?style=for-the-badge&logo=shield&logoColor=white)](#10-безопасность-и-условия-использования)
[![Worker Script](https://img.shields.io/badge/Код_Воркера-cloudflare__worker.js-F38020?style=for-the-badge&logo=javascript&logoColor=white)](docs/cloudflare_worker.js)
<br/>
[![Changelog](https://img.shields.io/badge/История-CHANGELOG-blue?style=for-the-badge)](CHANGELOG.md)
[![Terms](https://img.shields.io/badge/Условия-TERMS-informational?style=for-the-badge)](TERMS_OF_USE.md)
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

> [!IMPORTANT]
> **Обязательность воркера для SOCKS5 и Приоритет личного домена**:
> Режим SOCKS5 технически **не способен работать без Cloudflare Worker**, так как прокси требует ретрансляции сырых TCP-сессий к серверам и VoIP-рефлекторам Telegram через API `cloudflare:sockets`.
> Встроенный воркер разработчика был добавлен изначально для того, чтобы пользователи могли сразу опробовать SOCKS5 и протестировать гипотезу обхода блокировок голосовых и видеовызовов Telegram «из коробки». Однако работа звонков не гарантируется на 100% из-за особенностей сетевых провайдеров.
> **Настоятельно рекомендуется не использовать воркер разработчика для постоянного ношения и не доверять сторонним серверам**, а развернуть собственный воркер (бесплатно, за 2 минуты). Воркер разработчика легко отключается в Настройках специальными тумблерами.

### Зачем нужен Личный Cloudflare Worker?
1. **100% Защита Личных Данных**: При использовании личного воркера ваш трафик туннелируется только через ваш персональный аккаунт Cloudflare. Ключи, логи и параметры доступа принадлежат исключительно вам.
2. **Персональный Лимит 100 000 Запросов в День**: Бесплатный тариф Cloudflare предоставляет 100 000 бесплатных запросов ежедневно лично на ваш аккаунт.
3. **Максимальная Нерасшаренная Скорость**: Отсутствие зависимости от сторонних серверов обеспечивает максимальную пропускную способность для звонков и 4K-медиа.

### Зачем в приложении есть воркер разработчика и как его выключить?
Встроенный тестовый воркер разработчика присутствует в приложении со следующими целями и правилами использования:
* **Тестирование SOCKS5 и звонков «из коробки»**: Позволяет проверить работу SOCKS5 и звонков сразу после установки без предварительной регистрации в Cloudflare. На практике теория туннелирования голосового трафика зависит от качества линии и оператора, поэтому 100% стабильность вызовов не гарантируется.
* **Не используйте воркер разработчика на постоянной основе**: Настоятельно советуем не доверять сторонним серверам (включая сервер разработчика) — для полноценной конфиденциальности создайте свой воркер.
* **Независимые переключатели (Полное отключение)**: В Настройках приложения доступны тумблеры `SOCKS5 воркер по умолчанию` и `MTProto воркер по умолчанию`. Вы можете в любой момент **полностью выключить воркер разработчика**, переведя их в выключенное состояние. При указании собственного домена воркер разработчика также отключается автоматически. Исходный код скрипта доступен в файле [`docs/cloudflare_worker.js`](docs/cloudflare_worker.js).

---

## 3. Ключевые возможности

* **Двойной шлюз маршрутизации (MTProto + SOCKS5)**:
  * **MTProto Proxy (Порт 1443)**: нативный движок с WsPool, Fake-TLS маскировкой и быстрой загрузкой чатов.
  * **SOCKS5 Proxy (Порт 10808)**: поддержка прозрачного TCP-релея для обхода блокировок голосовых и видеовызовов Telegram.
* **Унифицированное WSS-туннелирование Cloudflare Worker**: единый протокол туннелирования `wss://$cfDomain/tcp?target=$dcIp:443` через `cloudflare:sockets` для MTProto и SOCKS5.
* **Раздельные настройки тумблеров**: независимое управление вызовом тестового воркера для SOCKS5 и MTProto с 100% приоритетом собственного домена пользователя и автосанитацией URL (`sanitizeDomain`).
* **Оптимизация производительности (R8 & Baseline Profiles)**:
  * Настроен профиль компиляции `baseline-prof.txt` для AOT-ускорения холодного старта.
  * Агрессивные правила минификации R8 (`-optimizations`) для максимального сжатия байткода.
  * Zero-Copy обработка буферов по оффсету в `MsgSplitter` без аллокаций `ByteArray` при передаче тяжелого медиа-трафика.
  * Потокобезопасный `AppLogger` на базе `SharedFlow` и изоляция корутин сокетов с `SupervisorJob()`.
* **Умные действия в 1 клик**: автоматическое формирование нужного формата ссылки (`tg://socks` или `tg://proxy`) на кнопках «В Telegram» и «Скопировать».
* **Полная сохранность ключей шифрования**: сгенерированный секретный ключ MTProto (`secretHex`) надежно изолирован и сохраняется при любых переключениях режимов SOCKS5 и MTProto.
* **Профили производительности (Speed Presets)**: `Турбо` (16 сокетов / 2 МБ буфер), `Баланс` (8 сокетов / 256 КБ буфер), `Эко` (2 сокета / 32 КБ буфер).
* **Мгновенная отдача (TCP_NODELAY)**: отключение алгоритма Нагла устраняет сетевые задержки (40–200 мс).
* **Умный таймер сна (Sleep Timer)**: автовыключение прокси по интервалу (от 15 мин до 6 ч) с уведомлениями и продлением в один клик.
* **Встроенный установщик обновлений**: валидация целостности по SHA-256 и верификация подписи разработчика в нативном NDK-слое (C++ `SignatureVerifier`).
* **Чистый визуал в стиле вкладок-переходников**: все диалоги оформлены с размытием фона `FLAG_BLUR_BEHIND` и очищены от эмодзи.

---

## 4. Архитектура системы

```mermaid
flowchart TD
    subgraph ClientLayer ["1. Клиенты Telegram (Android-устройство)"]
        TGApp["Клиенты Telegram<br/>(Official / AyuGram / NekoGram / ExteraGram / Telegram X)"]
    end

    subgraph ServiceLayer ["2. Локальный Прокси-сервер Mirrly (Android Service, Без VPN)"]
        ModeSwitch{"Переключатель режима<br/>(proxyModeName)"}
        
        subgraph SocksBox ["Режим SOCKS5 (Порт 10808)"]
            SocksEngine["Socks5WsBridge (Kotlin)<br/>Слушатель сокета 10808<br/>Прозрачный TCP Relay (Чаты & Звонки)"]
        end

        subgraph MtprotoBox ["Режим MTProto (Порт 1443)"]
            TgWsEngine["TgWsBridge (Kotlin)<br/>WSS TCP туннелирование"]
            NativeEngine["NativeProxy.so (C++ NDK)<br/>Fake-TLS & WsPool Manager"]
        end

        ModeSwitch -->|SOCKS5| SocksEngine
        ModeSwitch -->|MTProto + Воркер| TgWsEngine
        ModeSwitch -->|MTProto Без воркера| NativeEngine
    end

    subgraph ResolverLayer ["3. Движок Маршрутизации (getEffectiveCfDomain)"]
        WorkerResolver{"Приоритет выбора адреса"}
        UserWorker["1. Кастомный домен пользователя<br/>(100% Приоритет / Безопасность)"]
        DefaultWorker["2. Тестовый воркер Mirrly<br/>(SOCKS5 / MTProto тумблеры)"]
        DirectRoute["3. Прямой TCP Маршрут<br/>(Без использование воркера)"]

        WorkerResolver -->|Заполнен customCfDomain| UserWorker
        WorkerResolver -->|Включен дефолтный тумблер| DefaultWorker
        WorkerResolver -->|Тумблеры выключены| DirectRoute
    end

    subgraph InfrastructureLayer ["4. Инфраструктура Cloudflare CDN & Серверы Telegram"]
        subgraph CFEdge ["Сеть Cloudflare CDN (300+ Edge Data Centers)"]
            CFWorker["Cloudflare Worker (V8 Edge Script)<br/>WSS TLS-туннелирование & cloudflare:sockets"]
        end

        subgraph TelegramInfra ["Сеть Серверов Telegram"]
            TelegramDC["Telegram DCs (DC 1 - DC 5)<br/>Чаты, Медиафайлы, Каналы"]
            VoIPNodes["Telegram VoIP Reflectors<br/>Голосовые и видеовызовы"]
        end

        CFWorker -->|Прямые TCP сокеты| TelegramDC
        CFWorker -->|Прямые TCP сокеты| VoIPNodes
        DirectRoute -.->|Прямое TCP подключение| TelegramDC
    end

    TGApp -->|Подключение к 127.0.0.1:1443| ModeSwitch
    TGApp -->|Подключение к 127.0.0.1:10808| ModeSwitch
    
    SocksEngine --> WorkerResolver
    TgWsEngine --> WorkerResolver
    
    UserWorker --> CFWorker
    DefaultWorker --> CFWorker
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

1. Скачайте официальный установочный пакет со страницы [Релизы GitHub](https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/releases):
   * `app-universal-release.apk` — универсальная сборка под любое устройство.
   * `app-arm64-v8a-release.apk` — оптимизированная легкая сборка (~4.8 МБ) для современных 64-битных смартфонов.
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

Собранный универсальный файл будет доступен по пути: `app/build/outputs/apk/release/app-universal-release.apk`.

---

## 10. Безопасность и условия использования

* **Отсутствие сбора данных**: приложение не содержит встроенной аналитики, телеметрии, рекламных SDK и не собирает пользовательские данные.
* **Криптографическая верификация**: встроенный механизм обновлений проверяет совпадение контрольных сумм SHA-256 и отпечатка ключа подписи разработчика перед установкой новых версий.
* **История изменений**: подробный список нововведений всех версий приведен в документе [CHANGELOG.md](CHANGELOG.md).
* **Лицензирование**: исходный код распространяется под лицензией [GNU General Public License v3 (GPLv3)](LICENSE).
* **Пользовательское соглашение**: правила распространения сборок и ограничения описаны в документе [TERMS_OF_USE.md](TERMS_OF_USE.md).
