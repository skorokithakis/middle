# Middle — Architecture document

## Product concept

A small wearable pendant with a button and microphone. Press and hold the button, speak
your thought, release, and the recording is stored on-device and synced to your phone
over BLE when available.

---

## Repository layout

```
middle/
├── src/main.cpp          # ESP32-S3 firmware (Arduino via PlatformIO)
├── sync.py               # Host-side BLE sync + transcription (Python, uv script)
├── android/              # Android companion app (Kotlin + Jetpack Compose)
│   └── app/src/main/java/com/middle/app/
│       ├── ble/          # BLE manager and foreground sync service
│       │   ├── BleConstants.kt         # UUIDs and command bytes (single source of truth for Android)
│       │   ├── PendantBleManager.kt    # Nordic BLE manager: scan, connect, sync orchestration
│       │   └── SyncForegroundService.kt# Foreground service keeping BLE sync alive in background
│       ├── data/         # Recordings, webhook client, retry queue, settings
│       │   ├── Recording.kt            # Data class; parses filename for timestamp + duration
│       │   ├── RecordingsRepository.kt # StateFlow of recordings; encodes IMA→M4A on save
│       │   ├── Settings.kt             # EncryptedSharedPreferences wrapper (API key, toggles, webhook)
│       │   ├── WebhookClient.kt        # OkHttp POST with Basic Auth from URL credentials
│       │   ├── WebhookLog.kt           # In-memory StateFlow log (max 50 entries) for the UI
│       │   └── WebhookRetryQueue.kt    # JSON-file-backed retry queue with exponential backoff
│       ├── audio/        # IMA ADPCM decoder, audio encoder
│       │   ├── ImaAdpcmDecoder.kt      # Pure-Kotlin ADPCM decoder (mirrors firmware exactly)
│       │   └── AudioEncoder.kt         # MediaCodec AAC encoder → M4A via MediaMuxer
│       ├── transcription/
│       │   └── TranscriptionClient.kt  # OpenAI gpt-4o-transcribe via raw OkHttp multipart POST
│       ├── ui/           # Compose screens
│       │   ├── RecordingsScreen.kt     # List of recordings with play/share/delete/resend-webhook
│       │   ├── SettingsScreen.kt       # API key, background sync, transcription, webhook toggles
│       │   ├── LogScreen.kt            # Webhook delivery log (monospace, error-coloured)
│       │   └── theme/Theme.kt          # Material3 theme
│       ├── viewmodel/
│       │   ├── RecordingsViewModel.kt  # Playback (MediaPlayer), delete, manual webhook resend
│       │   └── SettingsViewModel.kt    # Thin wrapper exposing Settings as StateFlows
│       ├── MainActivity.kt             # Permission request, starts SyncForegroundService, nav host
│       └── MiddleApplication.kt        # App singleton: RecordingsRepository, WebhookRetryQueue, notification channel
├── platformio.ini        # PlatformIO build config
└── recordings/           # Output directory for sync.py (gitignored)
```

---

## Stack

### Firmware (`src/main.cpp`)
- **Platform**: ESP32-S3 (esp32-s3-devkitc-1)
- **Framework**: Arduino via PlatformIO (`platformio.ini`)
- **BLE**: Arduino BLE wrapper over NimBLE; `ble_gatts_notify_custom()` called
  directly to enable retry on mbuf exhaustion (the Arduino wrapper aborts on
  non-zero return, causing ~70–80% data loss).
- **Storage**: LittleFS (~3 MB partition, `huge_app.csv`)
- **Audio**: INMP441 I2S MEMS mic; IMA ADPCM encoding at 16 kHz mono (~4 KB/s)
- **Concurrency**: FreeRTOS — sampling loop on core 1, flash writer task on core 0

### Host sync script (`sync.py`)
- **Runtime**: Python ≥ 3.8 via `uv run --script` (inline dependency metadata)
- **BLE**: `bleak` (async BLE client)
- **Audio**: `lameenc` (MP3 encoding); IMA ADPCM decoded in pure Python
- **Transcription**: `openai` (GPT-4o Transcribe, optional via `OPENAI_API_KEY`)
- **Progress**: `tqdm`
- **Output format**: MP3 (64 kbps, mono, 16 kHz), saved to `recordings/`

### Android app (`android/`)
- **Language**: Kotlin
- **UI**: Jetpack Compose + Material3 (`compose-bom:2025.01.01`)
- **Navigation**: `navigation-compose` with a `ModalNavigationDrawer` (hamburger menu)
- **BLE**: Nordic BLE library (`no.nordicsemi.android:ble:2.7.4` + `-ktx`)
- **HTTP**: OkHttp 4.12.0 (webhook delivery and transcription API calls)
- **Transcription**: OpenAI API via OkHttp (raw HTTP multipart, not SDK)
- **Storage**: Encrypted SharedPreferences (`security-crypto`) for API key and all
  settings; plain JSON files under `filesDir/webhooks/` for retry queue;
  M4A files under `filesDir/recordings/`
- **Playback**: `MediaPlayer` (standard Android, not ExoPlayer despite the dependency)
- **Audio encoding**: `MediaCodec` AAC encoder + `MediaMuxer` → M4A (not MP3, because
  Android has no built-in MP3 encoder; OpenAI accepts M4A fine)
- **Min SDK**: 26 / Target SDK: 35

---

## BLE protocol

**Service UUID**: `19b10000-e8f2-537e-4f6c-d104768a1214`

| Characteristic | UUID suffix | Properties | Purpose |
|---|---|---|---|
| File Count   | `0001` | Read        | Number of pending recordings on flash (uint16 LE) |
| File Info    | `0002` | Read        | Byte size of the file currently being sent (uint32 LE) |
| Audio Data   | `0003` | Notify      | Chunked IMA ADPCM stream (MTU-sized packets) |
| Command      | `0004` | Write       | Commands from phone to pendant |
| Voltage      | `0005` | Read        | Battery millivolts (uint16 LE); optional — older firmware may omit it |
| Pairing      | `0006` | Read+Write  | Ownership token: read returns 0x00 (unclaimed) or 0x01 (claimed); write sends 16-byte token |

**Commands**: `REQUEST_NEXT=0x01`, `ACK_RECEIVED=0x02`, `SYNC_DONE=0x03`, `START_STREAM=0x04`

**MTU**: Firmware requests 517; chunk size = MTU − 3 (ATT header overhead).

**Sync sequence** (per file):
1. Phone reads `Pairing` characteristic; if pendant is unclaimed (0x00), phone writes a fresh 16-byte random token and stores it + the MAC. If pendant is already claimed (0x01) and the phone has a stored token, phone writes the stored token; firmware disconnects if it doesn't match.
2. Phone reads `File Count`.
3. Phone writes `REQUEST_NEXT`; firmware opens the file and sets `File Info` but does not stream yet.
4. Phone waits 100 ms, then reads `File Info` for expected byte count.
5. Phone writes `START_STREAM`; firmware begins sending the file as BLE notifications.
6. Phone reassembles chunks until `expected_size` bytes received (120 s total timeout).
7. Phone writes `ACK_RECEIVED`; firmware deletes the file from flash.
8. Repeat for each file.
9. Phone writes `SYNC_DONE` when all files are done.

**Retry**: up to 3 attempts per file on timeout; firmware retries each notification
up to 200 times (5 ms delay) on mbuf exhaustion.

---

## Audio pipeline

```
INMP441 (I2S, 32-bit stereo) → left channel >> 16 → int16 PCM
  → IMA ADPCM encoder (firmware, src/main.cpp)
  → packed nibbles in LittleFS (.ima file, 4-byte sample-count header)
  → BLE notify stream
  → reassembled on host/Android
  → IMA ADPCM decoder (sync.py or android/.../ImaAdpcmDecoder.kt)
  → signed 16-bit PCM
  → MP3 (lameenc, sync.py) or AAC/M4A (MediaCodec, Android)
  → optional transcription (OpenAI gpt-4o-transcribe)
  → optional webhook delivery (POST with configurable JSON body template)
```

**File format**: `.ima` — 4-byte little-endian uint32 sample count, followed by
packed IMA ADPCM nibbles (low nibble first, two samples per byte).

**Sample rate**: 16 kHz mono. Approximate data rate: ~4 KB/s ADPCM on flash,
~8 KB/s AAC at 64 kbps on Android.

**Startup discard**: first 1600 samples (~100 ms) are discarded after I2S init
to skip the INMP441's internal startup transient.

**Minimum recording**: recordings shorter than 1000 ms are discarded (used as
a sync-only tap gesture).

---

## Webhook retry / backoff

**All retry logic lives in one file**:
`android/app/src/main/java/com/middle/app/data/WebhookRetryQueue.kt`

Key details:
- Pending deliveries are persisted as JSON files under `filesDir/webhooks/*.json`
  (survives process death).
- **Backoff formula**: `min((1L shl retryCount) * 2_000ms, 24h)` — binary
  exponential backoff, capped at 24 hours.
- **Max retries**: 10. After 10 failures the entry is deleted and logged as
  abandoned.
- **4xx responses** are treated as permanent failures (deleted immediately, no
  retry). **5xx and exceptions** are retryable.
- The retry loop (`startRetryLoopIfNeeded`) runs as a coroutine on `Dispatchers.IO`
  and polls every 1 second while entries remain.
- HTTP delivery is in `WebhookClient.kt` (OkHttp, 10 s connect / 30 s read timeout,
  Basic Auth extracted from URL credentials).
- Manual resend is available from `RecordingsViewModel.sendWebhook()` (triggered
  from the recordings list UI).

---

## Firmware device lifecycle

```
[Deep sleep, ~7µA] → button press (ext0 wakeup)
  → if button LOW: record IMA ADPCM to LittleFS
  → if duration < 1000ms: discard (sync-only tap)
  → start BLE advertising (10 s window, 30 s hard deadline)
    → phone connects → sync all pending files → ACK → delete from flash
    → no connection → recordings accumulate on flash
  → deep sleep
```

**Battery reading**: 10-sample average via ADC on pin 1, through a 2× voltage
divider. Non-linear correction applied: `factor = 13020 − 65 × raw_mV / 100`.

---

## Android app screens

| Screen | Route | Description |
|---|---|---|
| Recordings | `recordings` | List of synced recordings (newest first). Each card shows timestamp, duration, transcript preview (3 lines), and play/share/delete/resend-webhook buttons. Sync status and battery voltage shown in a header card. |
| Log | `log` | Monospace webhook delivery log (last 50 entries, errors in red). |
| Settings | `settings` | OpenAI API key (masked), background sync toggle, transcription toggle, webhook toggle + URL + body template. |

Navigation uses a `ModalNavigationDrawer` (hamburger icon in each screen's top bar).

---

## Key hotspots

| Path | Reason |
|---|---|
| `src/main.cpp` | Entire firmware: recording, BLE server, ADPCM encoder, notification retry |
| `src/main.cpp:send_notification()` (line 570) | NimBLE notification retry loop (up to 200 attempts, 5 ms delay) |
| `src/main.cpp:record_and_save()` (line 434) | I2S capture, ring buffer, FreeRTOS writer task, ADPCM encoding |
| `sync.py:sync_recordings()` (line 210) | BLE transfer loop with per-file retry (`MAX_FILE_TRANSFER_ATTEMPTS=3`) and stall/total timeouts |
| `android/.../WebhookRetryQueue.kt` | Webhook persistence, exponential backoff, 4xx vs 5xx handling |
| `android/.../WebhookClient.kt` | OkHttp POST, Basic Auth from URL credentials |
| `android/.../PendantBleManager.kt` | Nordic BLE manager: scan, connect, sync orchestration |
| `android/.../SyncForegroundService.kt` | Foreground service: scan loop, pairing handshake, per-file sync, transcription dispatch |
| `android/.../TranscriptionClient.kt` | OpenAI transcription API calls |
| `android/.../AudioEncoder.kt` | MediaCodec AAC encoder + MediaMuxer → M4A |
| `android/.../ImaAdpcmDecoder.kt` | Pure-Kotlin ADPCM decoder (must stay in sync with firmware tables) |
| `android/.../BleConstants.kt` | Single source of truth for UUIDs and command bytes on Android |

---

## Build and run commands

### Firmware
```sh
pio run -e esp32-s3-devkitc-1          # build
pio run -e esp32-s3-devkitc-1 -t upload  # build + flash
pio device monitor -b 115200           # serial monitor
pio run -e esp32-s3-devkitc-1 -t uploadfs  # flash LittleFS image
pio check -e esp32-s3-devkitc-1        # static analysis
```

### Host sync script
```sh
uv run sync.py                         # run (fetches deps inline)
uv run python -m py_compile sync.py    # syntax check
```

### Android app
```sh
# from android/
./gradlew assembleDebug
./gradlew installDebug
```

---

## Conventions

- **Error handling**: fail fast; no silent swallowing. BLE and device errors are
  logged with context before returning. Webhook 4xx errors are abandoned
  immediately (not retried). `TranscriptionClient.transcribe()` returns null on
  failure (caller decides whether to skip or retry).
- **Logging**: firmware uses `Serial.printf` with subsystem tags (`[ble]`, `[rec]`,
  `[bat]`, `[flash]`). Python uses a timestamped `log()` helper. Android uses
  `android.util.Log` + `WebhookLog` (in-memory StateFlow for the UI).
- **Naming**: C++ uses `snake_case` throughout. Python uses `snake_case` functions /
  variables, `UPPER_SNAKE_CASE` constants. Kotlin follows standard Android
  conventions (`camelCase`, `PascalCase`).
- **Types**: Python functions are fully annotated. C++ uses fixed-width types
  (`uint8_t`, `uint16_t`, `uint32_t`) where protocol size matters.
- **Protocol constants**: BLE UUIDs and command bytes are defined in all three
  places — `src/main.cpp`, `sync.py`, and `android/.../BleConstants.kt` — any
  change must be coordinated across all three.
- **Settings storage**: all settings (including the OpenAI API key) are stored in
  `EncryptedSharedPreferences` using AES-256-GCM. No plaintext secrets on disk.
- **Audio format divergence**: `sync.py` outputs MP3 (via `lameenc`); the Android
  app outputs M4A/AAC (via `MediaCodec`). Both are accepted by OpenAI's API.

---

## Open questions / known gaps

- **No security on BLE**: any device that knows the service UUID can connect and
  download recordings. A pre-shared key is listed in `TODO.md` but not yet
  implemented.
- **No automated tests**: no firmware tests, no Python tests, no Android
  instrumentation tests. The `AGENTS.md` documents the intended test commands
  for when they are added.
- **`backgroundSyncEnabled` setting is stored but not enforced**: `Settings.kt`
  exposes the toggle and `SettingsScreen.kt` renders it, but
  `SyncForegroundService` does not read it — the service always scans regardless
  of the toggle value. Both foreground and background scan profiles use
  `SCAN_MODE_LOW_LATENCY`; the only difference is the period (3 s foreground,
  3 s background — currently identical). Constants live in `BleConstants.kt`.
- **ExoPlayer dependency is declared but unused**: `media3-exoplayer:1.2.1` is in
  `build.gradle.kts` but playback uses `MediaPlayer` directly in
  `RecordingsViewModel.kt`.
