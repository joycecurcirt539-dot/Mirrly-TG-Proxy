# 🛠 Инструменты и скрипты сборки (Tools)

В этой папке собраны все вспомогательные утилиты для разработки, сборки и деплоя компонентов Mirrly TG Proxy.

---

## 📁 Структура каталога

### 1. [`tools/deploy-worker/`](deploy-worker/) — Автодеплой Cloudflare Worker
Интерактивный инструмент для автоматического создания, деплоя и управления личными воркерами Cloudflare:
* **`deploy.bat`** — Запуск в 1 клик на Windows (двойным щелчком).
* **`deploy.ps1`** — Интерактивный PowerShell комбайн (с поддержкой смены аккаунтов, авто-истории в `my_workers.txt` и генерации deep-ссылок `mirrly://`).
* **`deploy.sh`** — Версия для Linux, macOS и WSL.
* **`worker.js`** — Исходный JS-код воркера с поддержкой `cloudflare:sockets`, TCP-туннелирования и защитой от open-relay.

### 2. [`tools/build/`](build/) — Сборка нативного ядра Rust (`mirrlyengine`)
Скрипты компиляции нативной библиотеки `libmirrlyengine.so` под 4 целевые Android ABI архитектуры (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`):
* **`build_native.ps1`** — Сборка через Android NDK Clang и Cargo на Windows.
* **`build_native.sh`** — Сборка на Linux / macOS / CI.
* **`clean_all.ps1`** — Полная очистка кэшей Gradle, Cargo, CXX и временных артефактов.
