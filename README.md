# Krypt 🔐

Privacy-first, zero-knowledge End-to-End Encrypted messenger.

## Architecture

- **Android Client** — Jetpack Compose, Room DB, OkHttp WebSockets, WebRTC, CameraX
- **Python Backend** — FastAPI WebSocket relay (never sees plaintext)

## Cryptography

- Identity: UUID + 2048-bit RSA Key Pair (generated on device, never leaves device)
- Messages: AES-256-GCM per message, AES key encrypted with recipient's RSA public key
- Files: Chunked (64KB), each chunk AES-256-GCM encrypted independently

## Setup

### 1. Deploy Backend

```bash
cd server
pip install -r requirements.txt
python main.py
# Or: docker build -t krypt-server . && docker run -p 8000:8000 krypt-server
```

### 2. Configure Android App

Edit `NetworkClient.kt` and set your server URL:
```kotlin
const val SERVER_URL = "ws://YOUR_SERVER_IP:8000/ws"
```

### 3. Build APK

Push to GitHub → Actions tab → Download `krypt-debug-apk` artifact.

## Features

- ✅ No accounts / No logins — UUID-based identity
- ✅ Zero-knowledge server — server routes ciphertext only
- ✅ E2EE text messaging
- ✅ E2EE file & photo sharing (chunked)
- ✅ WebRTC P2P audio/video calls
- ✅ 24-hour expiring encrypted statuses
- ✅ CameraX in-app camera
- ✅ Ultra-minimalist dark UI
