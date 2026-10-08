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

Kotlin · Jetpack Compose · Media3 ExoPlayer · Redis (built-in RESP client over TLS) · S3-compatible storage (Cloudflare R2 by default) · BLE GATT · WorkManager

## Repo layout

```
app/        single Android app
  core/     Redis (RESP/TLS) client + key schema, S3/R2 client, settings
  board/    sync engine, local store, player engine, BLE server, board UI
  admin/    upload queue, repository, BLE client, admin UI
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

Open the app on the board, choose **Board**, and enter the backend config, or paste a **setup code** copied from an admin device. Headless provisioning:

```bash
adb shell am start -n app.androplay/.MainActivity --es role BOARD --es config <setup-code>
```

### Zero-setup lab builds (optional)

Put defaults in `local.properties` (never committed) and every device built from it comes preconfigured:

```properties
androplay.redisUrl=rediss://default:<password>@<host>:6379
androplay.s3Endpoint=https://<account-id>.r2.cloudflarestorage.com
androplay.s3Bucket=androplay
androplay.s3Region=auto
androplay.s3AccessKey=...
androplay.s3SecretKey=...
```

## Docs

- [Architecture](docs/ARCHITECTURE.md): components, Redis key schema, sync and command flows, latency budget
- [Implementation plan](docs/PLAN.md): phases and status

## Security note

v1 keeps the Redis and storage credentials on trusted lab devices (app-private storage, entered once or provisioned with a setup code). A thin auth API that hands out scoped tokens is planned before any wider rollout. See the architecture doc.
