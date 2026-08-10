<div align="center">

<img src="docs/assets/logo.png" alt="Mirrly TG Proxy Logo" width="220" />

# Mirrly TG Proxy для Android

**Легковесный локальный MTProto & WebSocket прокси для стабильного подключения Telegram без VPN**

[![Android](https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9%2B-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/UI-Jetpack_Compose-4285F4?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Version](https://img.shields.io/badge/Релиз-v1.0.8-00E676?style=for-the-badge)](https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/releases)
[![Downloads](https://img.shields.io/github/downloads/joycecurcirt539-dot/Mirrly-TG-Proxy/total?style=for-the-badge&logo=github&logoColor=white&color=0088cc&label=Скачиваний)](https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/releases)
[![License](https://img.shields.io/badge/Лицензия-GPLv3-blue?style=for-the-badge)](LICENSE)

*Оптимизация сетевых маршрутов к дата-центрам Telegram через защищенные WebSocket-соединения Cloudflare. Работает локально в фоновом режиме с минимальным расходом батареи.*

---

</div>

## Оглавление

1. [О проекте и принцип работы](#1-о-проекте-и-принцип-работы)
2. [Ключевые возможности](#2-ключевые-возможности)
3. [Архитектура системы](#3-архитектура-системы)
4. [Поддерживаемые клиенты Telegram](#4-поддерживаемые-клиенты-telegram)
5. [Скриншоты интерфейса](#5-скриншоты-интерфейса)
6. [Быстрый старт и установка](#6-быстрый-старт-и-установка)
7. [Конфигурация и сборка](#7-конфигурация-и-сборка)
8. [Безопасность и лицензия](#8-безопасность-и-лицензия)

---

## 1. О проекте и принцип работы

**Mirrly TG Proxy** поднимает локальный прокси-сервер на адресе `127.0.0.1:1443` на Android-устройстве. Приложение принимает MTProto-трафик от Telegram и передает его через защищенные HTTPS WebSocket-сессии к серверам Cloudflare, стабилизируя соединение с дата-центрами мессенджера.

### Преимущества подхода:
- **Стабильное соединение с DC1-DC5**: Трафик идет по защищенным маршрутам Cloudflare, снижая потери пакетов и задержки.
- **Без использования VpnService**: Работает строго как локальный прокси. Не перехватывает трафик других приложений, не создает VPN-профиль и не расходует лишний заряд аккумулятора.
- **Прозрачность и безопасность**: Все сообщения и медиа остаются зашифрованными сквозным шифрованием Telegram (MTProto). Прокси не имеет доступа к ключам шифрования переписки.

---

## 2. Ключевые возможности

- **Быстрый отклик (предварительный прогрев сокетов)**: Поддерживает готовые соединения к дата-центрам Telegram, исключая задержку при отправке сообщений.
- **Умное восстановление сети**: Автоматически сбрасывает разорванные сессии и прогревает свежие сокеты при переключении Wi-Fi ↔ LTE или выходе из зоны плохого покрытия.
- **Поддержка собственных доменов Cloudflare Worker**: Возможность указать свой личный домен Worker в настройках.
- **Энергоэффективность**: Остановка фоновых анимаций и датчиков при сворачивании приложения для нулевого влияния на автономность.
- **Интеграция с быстрыми настройками Android**: Переключатель в шторке уведомлений (Quick Settings Tile) для включения в один клик.
- **Наглядный статус и статистика**: Отображение реальной скорости входящего/исходящего трафика и понятный журнал событий.

---

## 3. Архитектура системы

```mermaid
flowchart TD
    subgraph AndroidDevice ["Android-устройство"]
        TGClient["Telegram / AyuGram / NekoGram"] -->|MTProto / SOCKS5| LocalServer["Локальный прокси-сервер\n127.0.0.1:1443"]
        LocalServer -->|Нативное ядро| PoolMgr{"Выбор маршрута"}
    end

    subgraph Internet ["Сеть Cloudflare"]
        PoolMgr -->|HTTPS WSS Туннель| CFWorker["Cloudflare Worker / CDN"]
        PoolMgr -->|Резервный TCP (при VPN)| DirectTCP["Прямое TCP соединение"]
    end

    subgraph TelegramDCs ["Дата-центры Telegram"]
        CFWorker --> DC1["DC1 - DC5 Telegram"]
        DirectTCP --> DC2["DC2 Telegram"]
    end
```

---

## 4. Поддерживаемые клиенты Telegram

Приложение автоматически определяет установленные клиенты и позволяет подключить прокси в один клик:

- **Официальные клиенты**: Telegram Official, Telegram X
- **Популярные форки**: AyuGram, NekoGram, Nagram, ExteraGram, Plus Messenger
- **Другие клиенты**: Cherrygram, Nicegram, iMe Messenger, Telegraph, MDGram, Dahl, Litegram, Nullgram, ForkClient, BifToGram

---

## 5. Скриншоты интерфейса

<div align="center">

| Главный экран | Настройки | Журнал событий |
| :-: | :-: | :-: |
| <img src="docs/assets/home.jpg" alt="Главный экран" width="240" /> | <img src="docs/assets/settings.jpg" alt="Экран настроек" width="240" /> | <img src="docs/assets/logs.jpg" alt="Экран логов" width="240" /> |

<br />

| Проверка соединения в Telegram |
| :-: |
| <img src="docs/assets/ping.jpg" alt="Проверка пинга" width="360" /> |

</div>

---

## 6. Быстрый старт и установка

1. Скачайте официальный файл `app-release.apk` со страницы [Релизы GitHub](https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/releases).
2. Установите APK и откройте **Mirrly TG Proxy**.
3. Нажмите центральную кнопку включения.
4. Нажмите кнопку **«В Telegram»** и выберите ваш клиент для автоматической настройки.

---

## 7. Конфигурация и сборка

### Параметры конфигурации:
| Параметр | По умолчанию | Описание |
| :--- | :--- | :--- |
| `bindHost` / `bindPort` | `127.0.0.1:1443` | Локальный адрес и порт прокси |
| `secretHex` | `ee000000000000000000000000000000` | Секрет MTProto-соединения |
| `cfProxyEnabled` | `true` | Использование туннелирования Cloudflare |
| `customCfDomain` | `""` | Собственный домен Cloudflare Worker |
| `poolSize` | `8` (от 2 до 16) | Размер пула прогретых сокетов |
| `autostartOnBoot` | `false` | Автозапуск при включении устройства |

### Сборка из исходного кода:
```bash
# Клонирование репозитория
git clone https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy.git
cd Mirrly-TG-Proxy

# Сборка Release APK
.\gradlew.bat assembleRelease
```

---

## 8. Безопасность и лицензия

- **Открытый исходный код**: Приложение не собирает персональные данные, историю переписки или аналитику. Трафик не покидает устройство в незашифрованном виде.
- **Проверка подписи**: Встроенная система обновлений проверяет цифровую подпись разработчика (SHA-256) перед установкой любых обновлений.
- **Благодарности**: Проект вдохновлен наработками **Flowseal** (tg-ws-proxy) и сообществом разработчиков открытого ПО.

### Отказ от ответственности (Disclaimer)

Проект создан в исследовательских и образовательных целях для анализа сетевой связности и оптимизации задержек MTProto через WebSocket.

- Программное обеспечение предоставляется «КАК ЕСТЬ» («AS IS») без явных или подразумеваемых гарантий.
- Пользователь самостоятельно несет ответственность за соблюдение локального законодательства и правил используемых сервисов.

### Лицензия

Проект распространяется под свободной копилефтной лицензией [GNU General Public License v3 (GPLv3)](LICENSE). Дополнительные условия использования описаны в [TERMS_OF_USE.md](TERMS_OF_USE.md).
