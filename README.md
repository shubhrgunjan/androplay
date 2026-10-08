# AndroPlay

Zero-lag content playback for the **LG CreateBoard (TR3DK, Android 13)** — controlled from a phone, a Bluetooth remote, or anywhere over the internet. Nobody has to touch the board.

One Android app, two roles:

| Role | Runs on | What it does |
|---|---|---|
| **Board** | The CreateBoard | Full-screen player. Auto-downloads all content, plays from local storage instantly, obeys remote commands. Starts on boot. |
| **Admin** | Any Android phone/tablet | Upload videos, images, PDFs and docs. See per-board sync status. Tap to play, plus play/pause, seek, next/prev, volume. |

```
 Admin phone ──upload──▶ Object storage (R2/S3) ──download──▶ Board (local cache)
      │                                                         ▲
      └──── metadata · sync events · commands · state ──── Cloud Redis
      └──────────────── BLE fast path (in the room) ────────────┘
```

**Why it's fast:** the board always plays from **local storage** (content is pre-synced), keeps the **next item preloaded** in a standby player, and receives commands over **Redis pub/sub** (~20–60 ms, works from anywhere) or **BLE** (~10–30 ms, works without Wi-Fi).

## Features

- One APK, role picked on first launch (Board / Admin)
- Real-time sync via Redis Streams; offline boards catch up when they reconnect
- Resumable uploads and downloads (survive bad Wi-Fi), with integrity check by content fingerprint
- Content sorted by type: Videos · Images · Audio · PDFs · Documents · Other
- Board-side files (USB, Downloads, Movies…) are indexed and controllable too
- Instant switching through a two-player A/B swap and image/PDF page pre-rendering
- Bluetooth HID remotes and presenter clickers work out of the box (media keys, Page Up/Down)
- Kiosk-ready: auto-start on boot, optional home-launcher mode

## Tech

Kotlin · Jetpack Compose · Media3 ExoPlayer · Redis (Jedis, TLS) · S3-compatible storage (Cloudflare R2 by default) · BLE GATT · WorkManager

## Repo layout

```
app/        single Android app (board + admin roles)       ← in progress
protocol/   shared models, binary command codec, fingerprint (pure Kotlin, unit-tested)
docs/       ARCHITECTURE.md, PLAN.md
```

## Getting started

Requirements: Android Studio (latest), JDK 17+, a cloud Redis URL (`rediss://…`), and an S3-compatible bucket.

```bash
./gradlew :protocol:test          # shared protocol tests
./gradlew :app:assembleDebug      # build the APK
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Board setup (one-time, via adb)

```bash
PKG=app.androplay
adb shell pm grant $PKG android.permission.BLUETOOTH_ADVERTISE
adb shell pm grant $PKG android.permission.BLUETOOTH_CONNECT
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS
adb shell appops set $PKG MANAGE_EXTERNAL_STORAGE allow
adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow
# optional kiosk mode (app becomes the home screen)
adb shell pm enable $PKG/.KioskLauncher
```

Open the app on the board, choose **Board**, and enter the backend config (or scan the setup QR from an admin device).

## Docs

- [Architecture](docs/ARCHITECTURE.md): components, Redis key schema, sync and command flows, latency budget
- [Implementation plan](docs/PLAN.md): phases and status

## Security note

v1 keeps the Redis and storage credentials on trusted lab devices (encrypted prefs, entered once or provisioned by QR). A thin auth API that hands out scoped tokens is planned before any wider rollout. See the architecture doc.
