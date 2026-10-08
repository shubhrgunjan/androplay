# Implementation plan

Status: ✅ done · 🚧 in progress · ⏳ next

| # | Phase | Scope | Status |
|---|---|---|---|
| 0 | Repo & docs | README, architecture, plan, Gradle skeleton (AGP 9.4, Kotlin 2.4, Gradle 9.8) | ✅ |
| 1 | Shared protocol | Content types, binary command codec, playback state, fingerprint, unit tests | ✅ |
| 2 | Single app shell | `:app` module, role picker (Board/Admin), backend config screen + setup QR | 🚧 |
| 3 | Redis layer | TLS client, key schema, heartbeat, stream consumer with cursor, pub/sub commands & state | ⏳ |
| 4 | Storage layer | S3/R2 SigV4 signing, resumable multipart upload, ranged resumable download, fingerprint verify | ⏳ |
| 5 | Board role | Sync service, local cache + indexer, player engine (A/B ExoPlayer, images, PDF, external docs), HID keys, boot/kiosk | ⏳ (engine prototyped) |
| 6 | Admin role | Library by type, per-board sync badges, upload queue (WorkManager), share target, remote bar | ⏳ |
| 7 | BLE fast path | Board GATT server + admin central, auth token, auto-fallback to Redis | ⏳ (prototyped) |
| 8 | Hardening | Reconnect/backoff, storage quota & eviction, crash-safe state, CI build, release signing | ⏳ |

## Milestones

- **M1, "it plays"**: phases 2–5, with content pushed from Redis/R2 and played on the board.
- **M2, "it's a remote"**: phase 6, a full admin app with live state.
- **M3, "no-Wi-Fi safe"**: phase 7, with BLE taking over automatically.

## Decisions

- **Files are not stored in Redis.** Redis holds metadata, events, commands and state. Files go to S3-compatible storage (R2 default) and are cached on the board.
- **Commands are binary** (same codec over BLE and Redis), 1–20 bytes.
- **Content id is a fingerprint**, which gives free dedupe, resume and integrity checks.
- **minSdk 28** (admin phones); the board runs Android 13 (API 33).
