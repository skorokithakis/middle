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
│       ├── ble/          # BLE managers, sync loops and foreground sync service
│       │   ├── BleConstants.kt         # UUIDs and command bytes (single source of truth for Android)
│       │   ├── PendantBleManager.kt    # Nordic BLE manager: scan, connect, sync orchestration
│       │   ├── IndexSyncLoop.kt        # Drives the vendor library for the Index 01 ring
│       │   └── SyncForegroundService.kt# Foreground service keeping BLE sync alive in background
│       ├── data/         # Recordings, actions, webhook client, pipeline queue, settings
│       │   ├── Action.kt               # User-defined action; list persisted as one JSON array string
│       │   ├── ActionMatcher.kt        # Pure pattern/when rules evaluated against a transcript (unit tested)
│       │   ├── AlarmActionRunner.kt    # Starts the system clock app or posts a tap-to-set notification
│       │   ├── PipelineQueue.kt        # Durable transcribe-then-webhook jobs (one JSON file per recording)
│       │   ├── PipelinePolicy.kt       # Pure backoff/outcome rules (unit tested)
│       │   ├── Recording.kt            # Data class; parses filename for timestamp + duration
│       │   ├── RecordingsRepository.kt # StateFlow of recordings; encodes IMA→M4A on save
│       │   ├── Settings.kt             # EncryptedSharedPreferences wrapper (API key, toggles, webhook, sync device type, ring address); JSON backup export/import
│       │   ├── WebhookClient.kt        # OkHttp POST with Basic Auth from URL credentials
│       │   └── WebhookLog.kt           # In-memory StateFlow log (max 50 entries) for the UI
│       ├── audio/        # IMA ADPCM decoder, audio encoder
│       │   ├── ImaAdpcmDecoder.kt      # Pure-Kotlin ADPCM decoder (mirrors firmware exactly)
│       │   └── AudioEncoder.kt         # MediaCodec AAC encoder → M4A via MediaMuxer
│       ├── transcription/
│       │   └── TranscriptionClient.kt  # OpenAI gpt-4o-transcribe via raw OkHttp multipart POST
│       ├── ui/           # Compose screens
│       │   ├── ActionsScreen.kt        # Action list; add/delete/toggle/edit pattern and webhook suppression
│       │   ├── RecordingsScreen.kt     # List of recordings with play/share/delete/retry-pipeline
│       │   ├── SettingsScreen.kt       # API key, toggles, webhook, sync device and ring picker, settings backup export/import
│       │   ├── LogScreen.kt            # Webhook delivery log (monospace, error-coloured)
│       │   └── theme/Theme.kt          # Material3 theme
│       ├── viewmodel/
│       │   ├── ActionsViewModel.kt     # Reads/writes the action list as a StateFlow
│       │   ├── RecordingsViewModel.kt  # Playback (MediaPlayer), delete, manual pipeline retry
│       │   └── SettingsViewModel.kt    # Thin wrapper exposing Settings as StateFlows; reads bonded devices for the ring picker; reads/writes backup files
│       ├── MainActivity.kt             # Permission request, starts SyncForegroundService, nav host
│       ├── BootReceiver.kt             # Restarts SyncForegroundService after reboot when permissions allow
│       └── MiddleApplication.kt        # App singleton: RecordingsRepository, PipelineQueue, notification channels
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
- **BLE**: Nordic BLE library (`no.nordicsemi.android:ble:2.7.4` + `-ktx`) for the
  pendant; haversine Android library (`io.github.coredevices.haversine:haversine-android`)
  for the Index 01 ring
- **HTTP**: OkHttp 4.12.0 (webhook delivery and transcription API calls)
- **Transcription**: OpenAI API via OkHttp (raw HTTP multipart, not SDK)
- **Storage**: Encrypted SharedPreferences (`security-crypto`) for API key and all
  settings; plain JSON job files under `filesDir/pipeline/` for the queue;
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
  → optional actions (ActionMatcher → AlarmClock, or suppress webhook)
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

## Pipeline queue

Transcription and webhook delivery are handled by
`android/.../data/PipelineQueue.kt`; `PipelinePolicy.kt` holds the pure
backoff and outcome rules (unit-tested by `PipelinePolicyTest.kt`).

Key details:
- One JSON job file per recording under `filesDir/pipeline/` (survives process
  death). The file stores only the stage: `TRANSCRIBE` or `WEBHOOK`. The old
  `filesDir/webhooks/` format is discarded on startup, not migrated.
- A saved recording is enqueued at `TRANSCRIBE`; success advances the job to
  `WEBHOOK` when a webhook is configured, otherwise the job is deleted.
- Backoff is in memory, not persisted: starts at 2 s, doubles per failed
  attempt, capped at 30 minutes. A restart resets per-job state.
- A missing API key is not an attempt: it waits a fixed 60 s recheck instead of
  the attempt backoff.
- A `ConnectivityManager` callback provides an advisory offline gate. A job
  parked offline is let through once it has been offline for the 30-minute cap,
  so a wrong offline signal cannot hold it forever.
- Error classes: transcription errors are transient (retry), auth (401/403,
  skips the rest of this pass), or bad-file (400/413/415/422, drops the job);
  webhook responses are success, drop (4xx except 408/429), or retry. Nothing
  is abandoned except transcription bad-file and webhook 4xx.
- HTTP delivery is in `WebhookClient.kt` (OkHttp, 10 s connect / 30 s read
  timeout, Basic Auth extracted from URL credentials).
- Manual retry is available from `RecordingsViewModel.retryPipeline()` (triggered
  from the recordings list UI).

---

## Actions

Actions are user-defined rules that run once against a transcript after it is
produced, before any webhook delivery. `Action.kt` is the data class,
`ActionMatcher.kt` holds the pure matching rules (unit-tested by
`ActionMatcherTest.kt`; `ActionTest.kt` covers JSON round-tripping), and
`AlarmActionRunner.kt` performs the side effect.

Key details:
- The whole list is one JSON array string under a single `Settings` key
  (`actions`). An absent key reads as an empty list; an entry with an unknown
  type or malformed fields is skipped and logged rather than failing the list.
- The only type today is `ALARM`. An enabled action's `pattern` is compiled as a
  case-insensitive regex; the transcript text after the match is parsed as a time
  (bare `7` = 07:00, `7pm` = 19:00, optional minutes, `am`/`pm` case- and
  dot-insensitive). A pattern that does not compile is reported and never
  matches.
- Evaluation happens exactly once, in
  `PipelineQueue.advanceAfterTranscription()` on the only successful
  transcription of a recording. The clock app resolves a past time to tomorrow.
- The alarm is set by starting the system clock app with
  `AlarmClock.ACTION_SET_ALARM` (hour, minute, `EXTRA_SKIP_UI`, fixed message),
  which the manifest may open with `com.android.alarm.permission.SET_ALARM`.
  Starting an activity from the background needs the "Display over other apps"
  permission, so without `SYSTEM_ALERT_WINDOW` the alarm is posted as a
  notification the user taps instead; `ActionsScreen.kt` shows a card to grant it.
- Alarm confirmations and the tap-to-set notification use the `middle_actions`
  channel with ID 5, so a later confirmation replaces the pending tap-to-set
  alarm. Informational notifications (no parseable time, missing clock app, or a
  `SecurityException` from the clock app) use ID 6 so they cannot replace it.
- Action handling is best-effort: a failure while matching or running actions is
  logged and the job still advances with `suppressWebhook = false`, so a failed
  action cannot leave the recording queued for re-transcription.
- An action with `suppressWebhook` suppresses the webhook stage only when an
  alarm actually fired; a no-time match still lets the transcript through.
- Manual retry (`ensureJobLocked`) re-evaluates the pattern only to decide
  suppression and never fires the alarm again, so a suppressed transcript cannot
  leak its webhook on retry.

---

## Sync device selection

The app syncs from one device at a time, chosen by the `deviceType` setting
(`pendant` or `ring`, pendant by default so existing installs keep working).

`SyncForegroundService` starts either `runPendantSyncLoop()` (the original
custom-GATT scan loop) or `runRingSyncLoop()` (which drives `IndexSyncLoop` and
the vendor haversine library). The two loops share nothing at the transport
level; the only seam is `RecordingsRepository`, which both feed.

Each loop restarts itself after a failed or finished session, and the service
cancels the running loop when the setting changes, so no app restart is needed.
Both paths hand each saved recording to the process-wide `PipelineQueue` (via
`MiddleApplication`), which transcribes and delivers it outside the sync
session, so a cancelled session cannot affect an in-flight job.

`BootReceiver` restarts `SyncForegroundService` after `BOOT_COMPLETED` when the
runtime permissions it needs are already granted; otherwise the user must open
the app so it can request them.

`Settings` exposes `addDeviceTypeListener`/`removeDeviceTypeListener` because
`EncryptedSharedPreferences` change listeners only fire on the writing instance,
and the settings UI and the service each build their own `Settings`.

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
| Recordings | `recordings` | List of synced recordings (newest first). Each card shows timestamp, duration, transcript preview (3 lines), and play/share/delete/retry-pipeline buttons. Sync status and battery voltage shown in a header card. |
| Actions | `actions` | List of actions with per-card enable toggle, editable pattern, webhook-suppression toggle and delete. Top bar adds a new alarm action with the default pattern. An overlay-permission card is shown when the permission is missing. |
| Log | `log` | Monospace webhook delivery log (last 50 entries, errors in red). |
| Settings | `settings` | Sync device choice (pendant or ring) with a bonded-ring picker when ring is selected, OpenAI API key (masked), background sync toggle, transcription toggle, webhook toggle + URL + body template. |

Navigation uses a `ModalNavigationDrawer` (hamburger icon in each screen's top bar).

The Settings screen can export the configuration to a JSON file and import one
back. The file is one flat object tagged `version: 1`, keyed by the same
preference names `Settings.kt` uses. An import parses and type-checks the whole
file before showing a confirmation dialog and writing nothing until it is
confirmed; keys absent from the file keep their current value and unknown keys
are ignored. The import writes through the `Settings` property setters so a
device type or ring address change restarts the sync service. Actions are
included, as their serialized JSON array under the same `actions` key. The file
holds the API keys and pairing token as plain text, so the user must keep it
private.

A new recording saved by either sync path posts a "New recording added" notification on its own channel, separate from the battery alerts channel. A fixed notification ID means several files saved in one sync collapse into a single notification, and tapping it opens the app on the Recordings screen.

---

## Key hotspots

| Path | Reason |
|---|---|
| `src/main.cpp` | Entire firmware: recording, BLE server, ADPCM encoder, notification retry |
| `src/main.cpp:send_notification()` (line 570) | NimBLE notification retry loop (up to 200 attempts, 5 ms delay) |
| `src/main.cpp:record_and_save()` (line 434) | I2S capture, ring buffer, FreeRTOS writer task, ADPCM encoding |
| `sync.py:sync_recordings()` (line 210) | BLE transfer loop with per-file retry (`MAX_FILE_TRANSFER_ATTEMPTS=3`) and stall/total timeouts |
| `android/.../PipelineQueue.kt` | Durable transcribe/webhook jobs, in-memory backoff, network gate |
| `android/.../PipelinePolicy.kt` | Pure backoff and outcome classification rules |
| `android/.../WebhookClient.kt` | OkHttp POST, Basic Auth from URL credentials |
| `android/.../BootReceiver.kt` | Restarts the sync service after reboot when permissions allow |
| `android/.../PendantBleManager.kt` | Nordic BLE manager: scan, connect, sync orchestration |
| `android/.../SyncForegroundService.kt` | Foreground service: selects the pendant or ring loop, pairing handshake, per-file sync, enqueues saved recordings for the pipeline |
| `android/.../IndexSyncLoop.kt` | Ring path: awaits Bluetooth, collects vendor satellite statuses, persists completed ring audio |
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
  immediately (not retried). `TranscriptionClient.transcribe()` returns a
  `TranscriptionResult` (`Success`/`HttpError`/`NetworkError`/`ParseError`);
  `PipelinePolicy` classifies it into retry/skip/drop.
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
- **Limited automated tests**: `PipelinePolicyTest.kt` unit-tests backoff and
  outcome classification; `ActionTest.kt` covers action JSON serialization and
  `ActionMatcherTest.kt` the pattern/time matching rules. There are no firmware
  tests, Python tests, or Android instrumentation tests. `AGENTS.md` documents
  the intended commands.
- **`backgroundSyncEnabled` setting is stored but not enforced**: `Settings.kt`
  exposes the toggle and `SettingsScreen.kt` renders it, but
  `SyncForegroundService` does not read it — the service always scans regardless
  of the toggle value. Both foreground and background scan profiles use
  `SCAN_MODE_LOW_LATENCY`; the only difference is the period (3 s foreground,
  3 s background — currently identical). Constants live in `BleConstants.kt`.
- **ExoPlayer dependency is declared but unused**: `media3-exoplayer:1.2.1` is in
  `build.gradle.kts` but playback uses `MediaPlayer` directly in
  `RecordingsViewModel.kt`.
