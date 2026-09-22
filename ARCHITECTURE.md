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
│       │   ├── Action.kt               # Action data class + ALARM/CALENDAR/WEBHOOK/FAKE_CALL/PLAY_MEDIA/MEDIA_KEY enum; list persisted as one JSON array string, click actions as one JSON object
│       │   ├── ActionMatcher.kt        # Pure ordered pattern/rest rules, no time parsing (unit tested)
│       │   ├── ActionRunner.kt         # Runs ALARM/CALENDAR/FAKE_CALL/PLAY_MEDIA/MEDIA_KEY hits: time-parse, clock app, calendar write, Telecom fake call, Spotify Web API search or a media transport key
│       │   ├── ClickActionRunner.kt    # Runs the action bound to a ring button click count (one best-effort attempt, no queueing)
│       │   ├── PipelineQueue.kt        # Durable transcribe-then-webhook jobs; runs the action phase after a transcription
│       │   ├── PipelinePolicy.kt       # Pure backoff/outcome rules (unit tested)
│       │   ├── Recording.kt            # Data class; parses filename for timestamp + duration
│       │   ├── RecordingsRepository.kt # StateFlow of recordings; encodes IMA→M4A on save
│       │   ├── RecordingSaver.kt       # Saves phone PCM16 as M4A then queues transcription/webhook
│       │   ├── Settings.kt             # EncryptedSharedPreferences wrapper (keys, toggles, calendar, actions, device); migrates the legacy webhook; version 2 JSON backup
│       │   ├── WebhookClient.kt        # OkHttp POST, Basic Auth from URL credentials, $transcript/$rest body substitution
│       │   └── WebhookLog.kt           # In-memory StateFlow log (max 50 entries) for the UI
│       ├── telecom/      # Fake incoming calls handed to Android Telecom
│       │   ├── FakeCallAccount.kt      # PhoneAccount handle/registration + Calling accounts settings opener
│       │   └── FakeCallConnectionService.kt # Managed ConnectionService: rings, answers, rejects, 45 s missed timeout
│       ├── audio/        # IMA ADPCM decoder, audio encoder, phone mic capture and VAD
│       │   ├── ImaAdpcmDecoder.kt      # Pure-Kotlin ADPCM decoder (mirrors firmware exactly)
│       │   ├── PhoneRecorder.kt        # AudioRecord mic capture → PCM16, 5-minute cap, optional endpointer
│       │   ├── SpeechEndpointer.kt     # Reframes PCM16 chunks into 1024-byte frames; continue/save/discard decisions
│       │   ├── SileroClassifier.kt     # Silero VAD adapter for SpeechEndpointer (JitPack dependency)
│       │   ├── CaptureEndReason.kt     # Why a mic capture ended (speech/no speech/cap/failed)
│       │   └── AudioEncoder.kt         # MediaCodec AAC encoder → M4A via MediaMuxer
│       ├── transcription/
│       │   ├── SpotifySearchClient.kt  # Spotify Web API client-credentials token + top-track search (play-media action)
│       │   ├── TimeParseClient.kt      # OpenAI chat completion that extracts a time/title as strict JSON (gpt-5.6-luna)
│       │   └── TranscriptionClient.kt  # OpenAI gpt-4o-transcribe via raw OkHttp multipart POST
│       ├── ui/           # Compose screens
│       │   ├── ActionsScreen.kt        # Ordered action list; add by type, edit pattern/stop/webhook/fake-call (voice picker and preview)/play-media, reorder/delete; contact picker, calendar picker and overlay-permission card
│       │   ├── FakeCallVoice.kt        # Screen-scoped TextToSpeech listing the installed voices and previewing a fake call's message
│       │   ├── RecordingsScreen.kt     # List of recordings with play/share/delete/retry-pipeline
│       │   ├── SettingsScreen.kt       # Provider/API key, Spotify Client ID/Secret, toggles, sync device and ring picker, settings backup export/import
│       │   ├── LogScreen.kt            # Pipeline and webhook delivery log (monospace, error-coloured)
│       │   └── theme/Theme.kt          # Material3 theme
│       ├── viewmodel/
│       │   ├── ActionsViewModel.kt     # Reads/writes the ordered action list; adds by type with its default pattern, moves, lists calendars for the picker
│       │   ├── RecordingsViewModel.kt  # Playback (MediaPlayer), delete, manual pipeline retry
│       │   └── SettingsViewModel.kt    # Thin wrapper exposing Settings as StateFlows; reads bonded devices for the ring picker; reads/writes backup files
│       ├── MainActivity.kt             # Permission request, starts SyncForegroundService, nav host
│       ├── AssistActivity.kt           # System ASSIST/VOICE_COMMAND target: VAD mic capture in a dialog card
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
- **Language**: Kotlin 2.2.21 (AGP 8.13.2, Gradle 8.14.3)
- **UI**: Jetpack Compose + Material3 (`compose-bom:2025.09.00`)
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
- **Speech detection**: Silero VAD (`com.github.gkonovalov.android-vad:silero:2.0.10`,
  only published on JitPack) drives automatic capture stop for the phone mic and the
  system-assistant path
- **SDK levels**: minSdk 26 / targetSdk 35 / compileSdk 36 (compileSdk 36 because the haversine AAR declares minCompileSdk 36)

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
  → optional actions (ActionMatcher → ActionRunner: clock app, calendar event, fake call or play media)
  → optional per-action webhook delivery (POST with a JSON body template, $transcript/$rest)
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
  death). The file stores the stage: `TRANSCRIBE`, `RUN_ACTIONS` or `WEBHOOK`,
  and for a `WEBHOOK` job the ordered `webhookActionIds` still to deliver. The
  old `filesDir/webhooks/` format is discarded on startup, not migrated.
- A saved recording is enqueued at `TRANSCRIBE`; a successful transcription runs
  the action phase (see Actions), then advances the job to `WEBHOOK` with the
  matched WEBHOOK action ids, or deletes the job when none matched.
- Webhook delivery walks the ids in order. An id leaves the job only when it
  succeeds or is abandoned, so a retried id stays first and later ids cannot
  overtake it; an id whose action was deleted, disabled or blanked is skipped.
  If the job file was removed (recording deleted), delivery stops without
  recreating it.
- A manual retry always replans from the recording: a recording with no
  transcript is queued at `TRANSCRIBE`, and one whose transcript already exists
  is queued at `RUN_ACTIONS`, which re-runs the full action phase and then
  advances to `WEBHOOK` exactly as a successful transcription would. An existing
  `WEBHOOK` job is replaced, so an ALARM re-fires. `RUN_ACTIONS` rewrites itself
  as `TRANSCRIBE` when the transcript is missing at run time, and drops the job
  when the recording is gone. A `TRANSCRIBE` job whose transcript is already on
  disk (a crash between saving it and advancing the job) is still planned by
  pattern only, so a crash cannot re-fire an alarm.
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
  timeout, Basic Auth extracted from URL credentials). The body template
  substitutes `$transcript` and `$rest` (JSON-escaped, single pass) and defaults
  to `{"phrase": "$transcript"}`.
- Manual retry is available from `RecordingsViewModel.retryPipeline()` (triggered
  from the recordings list UI).

---

## Actions

Actions are user-defined rules that run once against a transcript after it is
produced, before any webhook delivery. `Action.kt` is the data class and the
`ALARM`/`CALENDAR`/`WEBHOOK`/`FAKE_CALL`/`PLAY_MEDIA`/`MEDIA_KEY` enum,
`ActionMatcher.kt` holds the pure ordered matching rules (unit-tested by
`ActionMatcherTest.kt`; `ActionTest.kt` covers JSON parsing and
round-tripping), `ActionRunner.kt` performs the
ALARM/CALENDAR/FAKE_CALL/PLAY_MEDIA/MEDIA_KEY side effects (unit-tested by
`ActionRunnerTest.kt`), and `TimeParseClient.kt` asks the model for a time
(unit-tested by `TimeParseClientTest.kt`). A separate `ClickActionRunner.kt`
runs one action for a ring button click (see Ring button clicks).

Key details:
- The whole list is one JSON array string under a single `Settings` key
  (`actions`), and the array order is the evaluation order. An absent key reads
  as an empty list; an entry with an unknown type or malformed fields is skipped
  and logged rather than failing the list.
- Every action has a case-insensitive regex `pattern`, an `enabled` flag and a
  `stop` flag. `WEBHOOK` actions also carry `webhookUrl` and
  `webhookBodyTemplate`; `$rest` is the transcript after that pattern's match,
  trimmed (empty for the `.*` catch-all). `FAKE_CALL` actions also carry the
  caller `callerName`, `callerNumber`, the spoken `message` and the `voiceName`
  (all serialized always and read as empty when absent); a blank `voiceName`
  means the engine's default voice. `PLAY_MEDIA` actions carry no extra
  fields: their query is
  `$rest`. Its default pattern is `^play\b`, anchored at the start so a normal
  note like "I will play tennis" does not fire.
- Evaluation happens exactly once, in
  `PipelineQueue.advanceAfterTranscription()` on the only successful
  transcription of a recording, on the IO dispatcher. `ActionMatcher.plan()`
  walks the list in order and collects each enabled action whose pattern matches,
  up to and including the first hit with `stop = true`. An invalid pattern is
  reported and never matches, but does not stop the list.
- `stop` is applied optimistically: if a stopping ALARM/CALENDAR/PLAY_MEDIA hit
  produces nothing (no time, LLM failure, blank query), evaluation resumes after
  it with `ActionMatcher.planFrom()`. WEBHOOK hits are collected into the job's
  `webhookActionIds`, for delivery in that order.
- ALARM/CALENDAR need an OpenAI key: `TimeParseClient` sends the whole transcript,
  the local date/time, weekday and zone, and the command kind (`CommandKind.ALARM`
  for an ALARM hit, `CommandKind.REMINDER` for a CALENDAR hit), and asks the
  `gpt-5.6-luna` model for a strict JSON object `{start, end, allDay, title}` with
  local `YYYY-MM-DDTHH:MM` times. An explicit am/pm or 24-hour time is taken
  literally; a bare hour is read as a morning time for an alarm and as a time in
  the 08:00–22:00 waking range for a reminder, with the nearest future reading
  winning when both are plausible. A missing key, an HTTP/parse failure, or a null
  start posts an info notification and counts as no result.
- An ALARM starts the system clock app with `AlarmClock.ACTION_SET_ALARM`
  (hour/minute, `EXTRA_SKIP_UI`, the parsed title or app name). A time more than
  24 hours ahead is rejected (exactly 24 hours is allowed); an all-day command
  has no clock time and is treated as no time.
- A CALENDAR writes directly to the calendar chosen in Actions (its
  `calendarId`) through `CalendarContract.Events`, without opening the calendar
  app. A timed event uses the parsed end, or half an hour after the start, in the
  system zone, and adds a `CalendarContract.Reminders` row at 0 minutes. An
  all-day event sets `ALL_DAY=1`, timezone `UTC`, and spans UTC midnight of the
  date to UTC midnight of the next date; the parsed time of day is ignored. A
  missing or failed reminder row is logged but does not fail the event.
- A FAKE_CALL hands the call to Android Telecom through Middle's managed
  calling account (`telecom/FakeCallAccount.kt` and
  `telecom/FakeCallConnectionService.kt`, declared in `AndroidManifest.xml` with
  `BIND_TELECOM_CONNECTION_SERVICE`). The account is registered with
  `CAPABILITY_CALL_PROVIDER` and stays disabled until the user enables it once
  in the system Calling accounts settings. The enabled state cannot be read
  without a phone permission, so the action just attempts the call: while the
  account is not enabled, `addNewIncomingCall` throws a `SecurityException`,
  which the action logs, turns into an info notification and treats as no
  result. Otherwise
  `TelecomManager.addNewIncomingCall` is called with the `tel:` address from
  `callerNumber` and the caller name and spoken `message` in custom extras, and
  the system dialer shows its own incoming-call screen with the user's ringtone
  and DND rules. No overlay permission is needed. The connection rings for 45 s,
  then disconnects as missed; answering activates it, rejecting or disconnecting
  ends it.
- An answered FAKE_CALL speaks its `message` through `android.speech.tts.TextToSpeech`
  as call audio. The engine is created when the connection starts ringing (so it
  is initialised by the time the user answers) and is set to
  `USAGE_VOICE_COMMUNICATION`/`CONTENT_TYPE_SPEECH`. The action's `voiceName` is
  selected when the engine lists it; a blank name keeps the engine default, and
  an unknown name (the voice was uninstalled after the action was saved) is
  logged and also keeps the default. On answer, after a 1 s
  delay, the message's non-blank lines are queued in order with a 2 s silent
  pause after each (4 s after the last), and the final pause's completion queues
  the next cycle, so it repeats until the call ends. A blank message or a failed
  TTS init stays silent and is logged. Any reject, disconnect or missed-call
  timeout stops and shuts the engine down and drops pending callbacks. The
  Actions screen keeps one `TextToSpeech` instance of its own to list the
  installed voices for the picker and preview the first non-blank message line;
  it offers the device language's offline, installed voices sorted by name, plus
  "Default" and any saved name that is no longer installed.
- A PLAY_MEDIA resolves `$rest` through Spotify. "Liked songs" and "my liked
  songs" (case-insensitive) skip the search and open the fixed
  `spotify:collection:tracks` URI, labelled "Liked Songs"; a client-credentials
  token cannot read the user's library, so the saved-tracks URI is the only way
  to reach them. Any other query is searched on Spotify's Web API:
  `SpotifySearchClient` fetches a client-credentials token (Basic auth with the
  Client ID/Secret configured in Settings, body `grant_type=client_credentials`,
  one token per play, never cached or logged), then calls
  `GET /v1/search?q=…&type=track&limit=1` with the Bearer token and takes the
  first `tracks.items[0]` as `artist - name`. The URI is opened with
  `Intent.ACTION_VIEW` addressed to `com.spotify.music`, carrying the app's
  package as `EXTRA_REFERRER`, so the Spotify app comes to the front and starts
  playback. Like the alarm, that start needs the overlay permission; without it
  a tap-to-play notification with the same "Playing …" text is posted instead.
  A blank query, a missing Client ID/Secret, an empty result, a failed search
  and a missing Spotify app each post a distinct info notification and count as
  no result.
- The clock app is only started when the app can draw overlays. Without
  `SYSTEM_ALERT_WINDOW` the alarm is posted as a notification the user taps;
  `ActionsScreen.kt` shows a card to grant it. The alarm intent needs
  `com.android.alarm.permission.SET_ALARM`, and reminders need
  `READ_CALENDAR`/`WRITE_CALENDAR`.
- All action notifications use the `middle_actions` channel. Successes, the
  tap-to-set alarm and tap-to-play media use ID 5 so a later result replaces the
  pending one; failures and other info use ID 6 so they cannot replace it. The
  tap-to-set alarm and tap-to-play media are the only notifications that do not
  open the app — every other one opens the app.
- The legacy global webhook settings are migrated into a catch-all WEBHOOK
  action (`pattern = ".*"`, `stop = false`) by
  `Settings.migrateGlobalWebhookToAction()`. A non-empty legacy URL migrates
  whether or not it was enabled, and the action's `enabled` flag mirrors the
  legacy `webhookEnabled`; the migration guard is the only thing that stops a
  duplicate. It runs on `MiddleApplication`
  startup and again at the end of every backup import, and it appends the new
  action so existing rule order is preserved; the legacy keys are then cleared
  and a guard stops it repeating. A version 1 backup's webhook is migrated the
  same way.
- Action handling is best-effort: a runner exception on one ALARM/CALENDAR hit
  is logged and treated as producing nothing, so the ids already collected are
  kept and planning continues. If the phase still throws outside a single hit,
  it falls back to collecting WEBHOOK ids by pattern alone; with no matching
  WEBHOOK action the job is deleted.
- A manual retry runs the action phase again from the existing transcript, so an
  ALARM re-fires and webhooks are collected anew; it never re-transcribes. The
  retry only writes the job file and wakes the worker, so the runner never blocks
  the main thread. A `TRANSCRIBE` job whose transcript is already on disk (a
  crash between saving it and advancing the job) is still planned by pattern
  only. `$rest` is recomputed at delivery with `ActionMatcher.restFor()`.

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

The recordings screen's status bar is shown for both devices. The pendant fills
in its status text and its voltage characteristic's reading. The ring
deliberately shows a fixed `Index` label rather than transfer progress (the
vendor does report per-collection status, but the bar does not surface it) and
reads voltage from the `batteryVoltageMilliV` field of the vendor's collection
metadata during a transfer. It is a per-collection value, not a live battery
query. Readings are tracked per device (the device type, plus the ring's
address) so switching device type or ring never shows another device's value.
For the ring a missing (null or zero) reading leaves the current value alone
instead of becoming `0.00V`; the pendant path is unchanged, so a non-null
pendant 0mV still displays and persists as `0.00V` and still runs the
low-battery alert. Only the pendant's low-battery alert is posted; the ring has
no alert.

`BootReceiver` restarts `SyncForegroundService` after `BOOT_COMPLETED` when the
runtime permissions it needs are already granted; otherwise the user must open
the app so it can request them.

`Settings` exposes `addDeviceTypeListener`/`removeDeviceTypeListener` because
`EncryptedSharedPreferences` change listeners only fire on the writing instance,
and the settings UI and the service each build their own `Settings`.

---

## Ring button clicks

The Index 01 ring reports button presses through the vendor's transfer statuses,
not as a separate command. `IndexSyncLoop` feeds every `TransferStatus` to the
vendor's `ButtonSequenceDebouncer`, which merges the ring's partial sequences
over a 700 ms window into whole gestures; when a gesture completes,
`parseRingButtonClickCount()` turns it into a click count. Only sequences made
entirely of `short` presses count: a `long` token means a hold (or the first half
of hold-to-record), and counts outside 1..3 are not clicks.

`Settings.clickActions` maps each count (1, 2, 3) to at most one `Action`,
persisted as one JSON object under a single preference key. A click with no bound
action, or one whose action is disabled, does nothing.

A click is a bare collection: it carries no audio and no transcript, so it is not
run through the durable pipeline queue. `SyncForegroundService` receives each
click through `IndexSyncLoop`'s `onClicks` callback and launches
`ClickActionRunner` on the service scope's IO dispatcher — deliberately not the
ring session's scope, so a session restart cannot cancel an in-flight webhook.
The runner reads the bound action and:

- `WEBHOOK`: one best-effort POST via `WebhookClient` with `$transcript` and
  `$rest` both empty and the action's body template (or the default). There is no
  queueing and no retry; a blank URL is logged and skipped. The outcome is logged
  through `WebhookLog`.
- `FAKE_CALL` / `MEDIA_KEY`: run directly through `ActionRunner` with an empty
  transcript.
- Any other type is logged and ignored; the click-action UI never offers the
  transcript-dependent types.

Each click is caught individually, so a failure is logged and can never crash the
sync service. A non-audio collection is not followed by `TransferComplete`, so
`IndexSyncLoop` commits the click's collection index at
`TransferStatus.TransferTypeDetermined`; without that the ring would re-deliver
the same click every session.

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
| Recordings | `recordings` | List of synced recordings (newest first). Each card shows timestamp, duration, transcript preview (3 lines), and play/share/delete/retry-pipeline buttons. A header card always shows the selected device's sync status (a fixed `Index` label for the ring) and its battery voltage. A hold-to-record mic button saves a phone voice note through the same transcribe/webhook pipeline (no new-recording notification). |
| Actions | `actions` | Ordered list of actions. Each card has a type label, enable toggle, editable pattern, a "stop after this action" switch, move up/down and delete; webhook cards add URL and body template fields, and fake-call cards add caller name/number fields, a multiline message field (prefilled with filler lines), a contact picker and a hint with a button to open the phone app's Calling accounts settings. Top bar adds an alarm, reminder, webhook, fake-call or play-media action. A calendar row picks the calendar for reminders, and a card is shown when the overlay permission is missing. |
| Log | `log` | Monospace pipeline and webhook delivery log (last 50 entries, errors in red). |
| Settings | `settings` | Sync device choice (pendant or ring) with a bonded-ring picker when ring is selected, transcription provider and its API key (masked), Spotify Client ID and Client Secret (masked), background sync toggle, transcription toggle, pairing token and unpair, settings backup export/import, and a link to the system's digital-assistant picker. |
| Assistant | `ASSIST` / `VOICE_COMMAND` | Not a nav route: a dialog-style card shown when the system assistant is triggered (long-press power). Shows "Listening…" and elapsed time with a Stop button; Silero VAD ends the recording when the speaker stops, then it saves through the same pipeline. Has no launcher icon and is excluded from recents. |

Navigation uses a `ModalNavigationDrawer` (hamburger icon in each screen's top bar).

The system assistant target is `AssistActivity`. Android's assistant role
(`ROLE_ASSISTANT`) cannot be requested, so the Settings screen only deep-links to
`Settings.ACTION_VOICE_INPUT_SETTINGS` for the user to pick Middle. Once selected,
long-pressing power starts the activity, which captures from the phone mic with a
Silero VAD endpointer (`audio/SpeechEndpointer.kt` wrapping `audio/SileroClassifier.kt`)
instead of running until the user stops. The model's 1500 ms silence hysteresis
supplies the speech-to-silence edge, and a 5 s no-speech timeout discards a capture
in which nobody spoke. After the save the card stays open: it shows `Transcribing…`,
then the transcript once the pipeline writes it (observed through
`RecordingsRepository.recordings`), and closes 3 s later or immediately when the
user taps outside. With transcription disabled, no transcript within 30 s, or a
failed or empty save, it shows a short message instead. Leaving while still
recording stops and saves without waiting for the transcript, like releasing the
in-app record button, and the save runs on the application scope so finishing the
dialog cannot cut it short. The Silero model and its ONNX runtime are
only published on JitPack (`com.github.gkonovalov.android-vad:silero`), which is why
the build adds the JitPack Maven repository.

The Settings screen can export the configuration to a JSON file and import one
back. The file is one flat object tagged `version: 2` (`BACKUP_VERSION`), keyed
by the same preference names `Settings.kt` uses. An import parses and
type-checks the whole file before showing a confirmation dialog and writing
nothing until it is confirmed; keys absent from the file keep their current
value and unknown keys are ignored. The import writes through the `Settings`
property setters so a device type or ring address change restarts the sync
service. Actions are included, as their serialized JSON array under the same
`actions` key. Version 1 files still import: their global webhook keys are
written first, then migrated into a WEBHOOK action (see Actions). The file holds
the API keys and pairing token as plain text, so the user must keep it private.

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
  outcome classification; `ActionTest.kt` covers action JSON parsing and
  round-tripping; `ActionMatcherTest.kt` the ordered pattern/rest rules;
  `ActionRunnerTest.kt` the time/calendar helpers; `ClickActionRunnerTest.kt` the
  ring-click dispatch rules; `WebhookClientTest.kt` the
  body template substitution; `TimeParseClientTest.kt` the time-parse response;
  and `SpeechEndpointerTest.kt` the VAD endpointer's reframing and stop/discard
  rules. There are no firmware tests, Python tests, or Android instrumentation
  tests. `AGENTS.md` documents the intended commands.
- **`backgroundSyncEnabled` setting is stored but not enforced**: `Settings.kt`
  exposes the toggle and `SettingsScreen.kt` renders it, but
  `SyncForegroundService` does not read it — the service always scans regardless
  of the toggle value. Both foreground and background scan profiles use
  `SCAN_MODE_LOW_LATENCY`; the only difference is the period (3 s foreground,
  3 s background — currently identical). Constants live in `BleConstants.kt`.
- **ExoPlayer dependency is declared but unused**: `media3-exoplayer:1.2.1` is in
  `build.gradle.kts` but playback uses `MediaPlayer` directly in
  `RecordingsViewModel.kt`.
