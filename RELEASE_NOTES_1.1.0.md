Исправлены критические вылеты SOCKS5 и MTProto при смене режима, навигация к экрану обновлений, механизм проверки актуальности версии.

---
## 1. Контрольные отпечатки SHA-256
* **app-universal-release.apk SHA-256**: `AD6E5A4D54A97D150E4799FE700CE4E7442FD920E2834DB6A9AD7090F533903D`
* **app-arm64-v8a-release.apk SHA-256**: `BF4180AF8A9FD4AA288267C2DB7890E869505981FA212553672E46093E8A4541`
* **app-armeabi-v7a-release.apk SHA-256**: `0BE5E81E9B713EEB89FA837B29EA1D0AA1E7E1F60225A3B1D7D60257DE1E938C`
* **app-x86_64-release.apk SHA-256**: `DDB843BA5E4DF8C6ECD1F0075BB085415AB3661E92B2A765943DA70CF8E8A626`
* **app-x86-release.apk SHA-256**: `252D6EE760EB004CC2687045D8F4A78F1E49C26875964E5606B57F46427793E4`
* **SHA-256 подписи (Release)**: `97:73:5C:0A:20:70:7F:D4:E4:BD:93:A2:D8:48:CA:91:9A:C5:40:45:4A:62:16:E8:CC:7D:43:4F:1F:9F:0A:96`

---
## 2. Назначение установочных пакетов (APK) и применимость ABI

### Переход к целевым архитектурным сборкам (ABI Splits)
Переход от единого устаревшего файла `app-release.apk` к текущей структуре (`app-universal-release.apk` + ABI-сборки) обусловлен следующими техническими факторами:

* **Оптимизация размера установочных файлов**:
  Единый legacy-файл `app-release.apk` содержал скомпилированные нативные библиотеки (`.so`) одновременно для всех четырех архитектур (ARM64, ARMv7, x86, x86_64), что существенно увеличивало объем скачиваемых данных. В новой структуре сборка `app-arm64-v8a-release.apk` (для большинства современных смартфонов) весит ~4.8 МБ вместо ~7.1 МБ.
* **Строгая стандартизация артефактов и безопасность автообновлений**:
  Использование детерминированного имени `app-universal-release.apk` взамен общего `app-release.apk` исключает неопределенность при разборе артефактов. Встроенный модуль автообновлений (`UpdateChecker`) и нативный верификатор подписей (`SignatureVerifier`) используют имя универсального пакета для сопоставления и автоматической проверки целостности по отпечатку SHA-256 перед установкой.

### Назначение артефактов
* **app-universal-release.apk**: Универсальная сборка. Содержит нативные библиотеки под все архитектуры (ARM64, ARMv7, x86, x86_64). Гарантированно подходит для любого Android-устройства. Единый основной файл для автообновлений и публикации.
* **app-arm64-v8a-release.apk**: Сборка ARM64. Оптимизирована для большинства современных смартфонов и планшетов (64-битные процессоры ARM). Обладает меньшим размером.
* **app-armeabi-v7a-release.apk**: Сборка ARMv7. Для старых 32-битных Android-устройств.
* **app-x86_64-release.apk**: Сборка x86_64. Для 64-битных эмуляторов Android (Android Studio, LDPlayer, BlueStacks) и устройств на базах процессоров Intel/AMD.
* **app-x86-release.apk**: Сборка x86. Для 32-битных эмуляторов Android x86.

---
## 3. Ключевые изменения

### Критические исправления сетевого стека
* **Вылет SOCKS5 и повреждение состояния MTProto**:
  * **Корневая причина**: `NativeProxy.stopProxy()` вызывался при остановке независимо от режима работы — в том числе при SOCKS5, где нативный движок не запускался. Это повреждало внутреннее JNI-состояние, после чего MTProto при следующем старте падал.
  * Введен флаг `isNativeRunning` на уровне `LocalProxyServer` и защитная проверка `isStarted` на уровне `NativeProxy`. Вызовы `stopProxy()`, `setPoolSize()`, `setSecret()`, `getStats()` теперь пропускаются, если нативный движок не был запущен.
  * Сборка статистики (`NativeProxy.getStats()`) в цикле скорости теперь охвачена тем же флагом — устранен дополнительный путь к вылету.
* **`InterruptedIOException` не перехватывался в SOCKS5 и MTProto мостах**:
  * В `Socks5WsBridge` и `TgWsBridge` блоки `catch (e: Exception)` не перехватывали `InterruptedIOException`. Заменены на `catch (e: Throwable)` с фильтрацией `CancellationException` и `InterruptedIOException` — они не логируются как ошибки.
* **Предупреждение SOCKS5 показывалось при уже активном дефолтном воркере**:
  * В `SettingsScreen` диалог предупреждения теперь не появляется, если `useDefaultWorkerSocks5 = true`.
* **Утечка сообщений и потенциальный deadlock в `RawWebSocketClient`**:
  * `messageChannel` переведен на `Channel.UNLIMITED`. При `trySend` failure соединение закрывается явно — устранена тихая потеря TCP-фреймов.
  * `messageChannel.close()` добавлен в `onClosing`, `onFailure` и `onClosed` — устранен потенциальный deadlock в `consumeEach`.
* **`NativeProxy`: все JNI-вызовы защищены**:
  * `setCfProxyCacheDir`, `setCfProxyConfig`, `setSecret`, `getSecretWithPrefix`, `setPoolSize` — все вызовы обернуты в `try/catch` и проверки флага `isStarted`.

### Исправления системы обновлений
* **Плашка обновления не скрывалась при активации режима скрытия интерфейса**:
  * Блок плашки обернут в `AnimatedVisibility(visible = isUpdateAvailable && !isUiHidden)` с анимацией `shrinkVertically + fadeOut`. Фон плашки убран, оставлена только рамка.
* **Приложение предлагало обновиться до уже установленной версии**:
  * При HTTP `304 Not Modified` `_updateState` не сбрасывался. `UpdateManager` теперь кеширует данные последнего релиза в `SharedPreferences` и при 304 повторно проверяет версию через `isVersionNewer()`. Если версия совпадает или ниже — `_updateState` обнуляется.
* **Кнопки в настройках открывали диалог вместо экрана обновлений**:
  * `SettingsScreen` получил `onOpenUpdate: () -> Unit`. `UpdateDialog` (legacy) и `pendingUpdateRelease` удалены. Все точки срабатывания вызывают навигацию через `onOpenUpdate()`. Аналогично обновлен `OfficialSourceCard`.
* **Парсинг версий**:
  * `UpdateChecker.cleanVersionString()` теперь использует regex — корректно парсит нестандартные теги (`v1.1.0-release` и т.п.).

### Исправления ProxyConfig
* **Автосанитация пользовательского домена воркера**:
  * `sanitizeDomain()` очищает домен от `https://`, `wss://`, `http://`, путей и `@`-фрагментов. Если пользователь вставил полный URL — он применится корректно.

### Улучшения производительности
* **`MsgSplitter.split()`: устранена аллокация `ByteArray` на каждый пакет**:
  * Метод получил параметры `offset: Int` и `length: Int`. `TgWsBridge` передает их напрямую вместо `readBuffer.copyOfRange(0, readCount)`. Снижена нагрузка на GC при высоком трафике.
* **`AppLogger`: переход на `SharedFlow` с event-based обновлениями**:
  * `_logsFlow` переведен на `MutableSharedFlow` с событиями `LogEvent.Added / LogEvent.Cleared`. `LogEntry.humanMessage` и `formattedTime` теперь `lazy` — вычисляются только при первом обращении. Устранена полная копия `List<LogEntry>` при каждом добавлении записи.
* **`WsPool`: добавлен `SupervisorJob`**:
  * `CoroutineScope(Dispatchers.IO + SupervisorJob())` — исключение в одном `triggerRefill` больше не завершает весь скоуп пула.

### Навигация и интерфейс
* Кнопка обновлений в топбаре: всегда видна; при наличии обновления окрашивается в желтый с точкой-бейджем.
* Удалены дублированные импорты в `HomeScreen`.
* `currentUpdateInfo` вынесен на уровень `MainActivity` и передается в `UpdateScreen` через параметр.

### Тесты
* Добавлен `MTProtoCryptoTest`: покрытие handshake-логики и разбора заголовков MTProto.
* Добавлен `Socks5ProtocolTest`: покрытие SOCKS5 negotiation и address parsing.
