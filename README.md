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

## Recovery release

Version 0.9.5 restores the pre-configuration-change v0.9.1 app with a higher Android version code. The private recovery APK retains the original lab backend migration behavior and the remote-button repeat fix.

## Features

- One APK, role picked on first launch (Board / Admin)
- Real-time sync via Redis Streams; offline boards catch up when they reconnect
- Resumable uploads and downloads (survive bad Wi-Fi), with integrity check by content fingerprint
- Content sorted by type: Videos · Images · Audio · PDFs · Documents · Other
- Board-side files (USB, Downloads, Movies…) are indexed and controllable too
- Instant switching through a two-player A/B swap and image/PDF page pre-rendering
- **Slideshows**: put any videos, images, audio and PDFs in a series. Videos play to the end, images and PDF pages show for a set time, and an optional loop starts it again. Start one from the phone or by touch on the board
- Bluetooth HID remotes and presenter clickers work out of the box (media keys, Page Up/Down)
- Kiosk-ready: auto-start on boot, optional home-launcher mode

## Quality

Nothing is ever compressed or re-encoded. The admin uploads the **original file byte-for-byte** (no resizing, transcoding or re-encoding at any step), and the board downloads exactly those bytes:

- Every S3 request is SHA-256 signed over its payload, so a corrupted part is rejected by storage.
- The board checks each downloaded file against its content fingerprint (size + SHA-256 of the head/tail) before it is ever played.
- The app declares native HEVC / HDR10 / HDR10+ / HLG / Dolby Vision support (`res/xml/media_capabilities.xml`), so Android 12+ never hands it a transcoded AVC copy.
- With *media location* permission, photos and videos keep their original EXIF/location metadata (`MediaStore.setRequireOriginal`).
- Thumbnails are separate small JPEGs; the original is never touched. On the board, images are decoded at the panel's full physical resolution (4K) and videos play at their native resolution.

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
adb shell appops set $PKG REQUEST_INSTALL_PACKAGES allow   # in-app updates
# never sleep / no screen saver while locked or presenting
adb shell pm grant $PKG android.permission.WRITE_SECURE_SETTINGS
adb shell appops set $PKG WRITE_SETTINGS allow
# optional kiosk mode (app becomes the home screen)
adb shell pm enable $PKG/.KioskLauncher
```

Open the app on the board, choose **Board**, and enter the backend config, or paste a **setup code** copied from an admin device. Headless provisioning:

```bash
adb shell am start -n app.androplay/.MainActivity --es role BOARD --es config <setup-code>
```

### Zero-setup lab builds (optional)

For a private lab recovery build, put defaults in the ignored `local.properties` and explicitly build with `-Pandroplay.embedBackend=true`. Keep credential-bearing APKs in private storage.

```properties
androplay.redisUrl=rediss://default:<password>@<host>:6379
androplay.s3Endpoint=https://<account-id>.r2.cloudflarestorage.com
androplay.s3Bucket=androplay
androplay.s3Region=auto
androplay.s3AccessKey=...
androplay.s3SecretKey=...
androplay.s3FallbackBuckets=androplay1,androplay2,androplay3
```

### Admin login

Use your lab's existing local admin account. Ask the deployment administrator for its credentials.
It protects:

- the Admin dashboard (login screen on first open; **Settings → Log out** to sign out)
- settings and role changes on any device (a fresh device can pick **Board / Player** without it)
- unlocking a locked board

The account is hardcoded; only a SHA-256 is stored. To change it, set `CREDENTIAL_HASH` in `app/src/main/java/app/androplay/core/AdminAuth.kt` to `sha256("USERNAME:password")` with the username in uppercase, e.g. `printf 'USERNAME:password' | shasum -a 256`.

## Updates (no GitHub needed on devices)

From v0.6.0 the app updates itself. A release is published like this:

```bash
# 1. bump versionCode / versionName in app/build.gradle.kts
# 2. build, verify the private bucket copy, and announce via Redis:
scripts/release.sh "What changed"
```

- **Phones** show an *Update available* banner with an **Update** button.
- **Boards** update automatically, but only when nothing is on screen, then restart into the player.
- One-time per device: allow *Install unknown apps* for AndroPlay. The board's setup banner offers it, or use `adb shell appops set app.androplay REQUEST_INSTALL_PACKAGES allow`. Android asks to confirm the first in-app update; on Android 12+ later updates are silent.
- Builds must be signed with the lab key (cert SHA-256 `8bf73d57…85ffcd`, originally the lab iMac's `~/.android/debug.keystore`), or devices reject the update. `scripts/release.sh` refuses to publish anything else. Never regenerate or lose this keystore.
- To build or release from another machine: copy the lab keystore and `local.properties` over securely (not via git), and point `androplay.keystore=/path/to/lab.keystore` at it in `local.properties` (passwords default to `android`; override with `androplay.keystorePassword` / `androplay.keyAlias` / `androplay.keyPassword`). Then run `scripts/release.sh`. If a device has a build signed with another key, uninstall it once.

## Presentation safeguards (board)

- **Performance guard.** While something plays, the board watches for dropped video frames, playback stalls, UI jank and low memory. When it detects lag it clears other apps' background processes, frees image caches and pauses its own downloads for 90 s. Before every Play it clears background apps in advance. Downloads are throttled (~6 MB/s) and low-priority while content is on screen.
- **Screen keeper.** While the board is locked or showing anything, it holds a screen-on wake lock (this also covers documents opened in other apps), turns off Android's screen saver and stretches the screen timeout. Original settings come back when the board goes idle. The last two need the adb grants above.
- **Limits.** Without root, an app can't kill foreground services, system apps or LG's own system processes, and can't override LG firmware power settings. Turn those off once in the board's settings (e.g. auto power-off / eco mode), or disable unneeded vendor apps with `adb shell pm disable-user --user 0 <package>`.

## Docs

- [Architecture](docs/ARCHITECTURE.md): components, Redis key schema, sync and command flows, latency budget
- [Implementation plan](docs/PLAN.md): phases and status

## Security note

v1 keeps the Redis and storage credentials on trusted lab devices (app-private storage, entered once or provisioned with a setup code). A thin auth API that hands out scoped tokens is planned before any wider rollout. See the architecture doc.
