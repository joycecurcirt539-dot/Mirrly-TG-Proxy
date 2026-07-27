# 🚀 Mirrly TG Proxy v1.0.0 - Initial Official Release

**Tag**: `v1.0.0`  
**Target Architecture**: `arm64-v8a`, `armeabi-v7a`  
**Minimum Android Version**: Android 8.0 (API Level 26+)

---

## 🌟 What is Mirrly TG Proxy?

**Mirrly TG Proxy** is a high-performance local MTProto and Cloudflare WebSocket proxy application engineered specifically for Android devices. It bypasses Internet Service Provider (ISP) censorship, DPI (Deep Packet Inspection) blocks, and network throttling imposed on Telegram, without needing full system-wide VPNs.

---

## 🚀 Key Features & Highlights in v1.0.0

### 1. ⚡ Native High-Speed Proxy Engine (`libtgwsproxy`)
- Powered by a standalone compiled Rust native library integrated directly via **Java Native Access (JNA)**.
- Delivers maximum throughput and minimal battery drain compared to traditional Java/Kotlin proxy implementations.

### 2. 🌐 Cloudflare WebSocket Tunneling & 0ms Pre-Warmed Socket Pool
- **WebSocket Tunneling**: Obfuscates MTProto TCP packets inside HTTPS/WebSocket traffic directed at Cloudflare CDN edge nodes.
- **Pre-warmed Pool (`WsPool`)**: Maintains up to 16 pre-established WebSocket connections per Telegram Data Center (DC1 through DC5), eliminating socket opening handshake latency.
- **Custom Worker Domain Support**: Allows users to specify their own Cloudflare Worker endpoint for private proxy routing.

### 3. 🎨 Modern Pure Black AMOLED Interface
- Built 100% with **Jetpack Compose** and **Material 3**.
- Features an ultra-dark True Black theme (`#000000`) optimized for OLED/AMOLED screens.
- Includes a standalone glowing power button with real-time breathing radial pulse animation.
- Live telemetry dashboard displaying real-time download/upload speeds, total data counters, and active socket connections.

### 4. ⚡ Quick Settings Tile & Background Resilience
- **Quick Settings Tile (`ProxyTileService`)**: Toggle proxy status directly from your Android notification shade / status bar.
- **`WakeLock` Management**: Smart CPU keep-alive with automatic 25-minute refresh cycles for uninterrupted operation in background sleep modes.
- **`NetworkChangeObserver`**: Seamlessly reconnects the proxy when switching between Wi-Fi and Cellular networks.
- **Boot Autostart**: Optional receiver (`BootReceiver`) to start the proxy on device power on.

### 5. 📱 One-Tap Integration with 17+ Telegram Clients
Includes one-click proxy configuration (`tg://proxy?...`) with automatic app launcher for:
- Official Telegram / Telegram X
- AyuGram Mobile
- ExteraGram
- Plus Messenger
- NekoGram / Nagram
- Cherrygram
- Nicegram
- iMe Messenger
- Telegraph, Nullgram, MDGram, ForkClient, Dahl, Litegram, and more.

### 6. 📜 Real-Time Log Viewer & Human Log Translator
- Built-in live log screen (`LogsScreen`) featuring automatic translation of complex native MTProto error codes into plain human-readable explanations.

---

## 📦 Release Assets

- `MirrlyTGProxy-v1.0.0-release.apk` (Official Release APK)
- `Source code (zip / tar.gz)`

---

## 🔧 Installation Instructions

1. Download `MirrlyTGProxy-v1.0.0-release.apk` on your Android device.
2. Allow installation from unknown sources if prompted.
3. Open **Mirrly TG Proxy**, tap the center power button to activate.
4. Tap **"В Telegram"** to automatically connect your Telegram client to the local proxy (`127.0.0.1:1443`).

---

## 📄 License
Released under the **MIT License**.
