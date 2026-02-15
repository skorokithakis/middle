# Middle — Architecture Document

## Product Concept

A small wearable pendant with a button and microphone. Press and hold the button, speak your thought, release, and the recording is stored on-device and synced to your phone over BLE when available. Designed for capturing fleeting ideas before they slip away.

### Design Principles

- **Hold-to-record**: Recording starts on button press, stops on release. Walkie-talkie style.
- **Offline-first**: Recordings saved to on-device flash. No internet or phone connection required at time of recording.
- **Private by design**: No recording until the button is physically pressed. No always-on microphone. No cloud dependency.
- **Ultra-low power**: Deep sleep between recordings. Hundreds of recordings per charge on a small LiPo.
- **Sync-when-available**: BLE advertises briefly after each recording. Phone syncs all pending files if nearby. If not, recordings accumulate safely on flash.

---

## Hardware

### Bill of Materials

| Component | Part | Notes |
|---|---|---|
| Microcontroller | ESP32-S3 with PSRAM | BLE 5.0, deep sleep ~7µA. Board with USB-C charging preferred (e.g. Seeed XIAO ESP32S3). PSRAM used for audio buffering during recording. |
| Microphone | MAX9814 breakout | Analog mic with automatic gain control (AGC). Output is an analog signal read via ESP32 ADC. |
| Battery | LiPo 3.7V, 100–150mAh | Form factors: 401230 (110mAh) or 502020 (150mAh). Charged via board's USB-C. |
| Button | 4mm SMD tactile switch | e.g. ALPS SKQG series. Active LOW with internal pull-up. Wakes ESP32 from deep sleep via ext_wakeup. |
| Enclosure | 3D printed pendant | Target ~25×25×10mm with loop/bail for chain or cord. |

### Wiring

**MAX9814 → ESP32-S3:**

| MAX9814 Pin | ESP32-S3 Pin | Notes |
|---|---|---|
| VDD | 3.3V | |
| GND | GND | |
| OUT | GPIO4 | Any ADC-capable GPIO works |
| GAIN | Jumper to VDD | Sets 40dB gain, appropriate for normal speech distance. 60dB (no jumper) is too hot and will clip. |

**Button:**

| Button Pin | ESP32-S3 Pin | Notes |
|---|---|---|
| Pin 1 | GPIO7 | Configure with internal pull-up in firmware |
| Pin 2 | GND | |

GPIO7 is configured as `ext0_wakeup` source (trigger on LOW) to bring the ESP32 out of deep sleep.

**Battery:** Connect to the board's dedicated battery pads (BAT+/BAT−) or JST connector. If the board has onboard LiPo charging (like the XIAO), USB-C handles charging automatically. If not, add a TP4054 charge controller between USB 5V and the battery.

### Notes on Alternative Microphones

The MAX9814 is an analog mic read via ADC. This is noisier than a digital I2S mic but simpler to wire (3 wires vs 5). An **INMP441** (I2S MEMS mic) would give cleaner audio if desired. The wiring for that would be:

| INMP441 Pin | ESP32-S3 Pin |
|---|---|
| VDD | 3.3V |
| GND | GND |
| SD (data) | GPIO4 |
| WS (word select) | GPIO5 |
| SCK (bit clock) | GPIO6 |
| L/R | GND (selects left channel) |

The firmware would need to use the ESP-IDF I2S driver instead of `analogRead()`. Everything else in the architecture stays the same.

### LM393 — Not Suitable

An LM393-based "sound sensor" module outputs only a digital HIGH/LOW when sound crosses a threshold. It cannot capture audio waveforms and is not usable for this project.

---

## Power Model

| State | Current Draw | Duration | Notes |
|---|---|---|---|
| Deep sleep | ~7µA | 99.9% of time | ESP32-S3 with ext_wakeup configured |
| Recording (ADC + PSRAM) | ~50mA | Seconds (length of speech) | ADC sampling + PSRAM writes |
| BLE advertising | ~80–100mA | ~5 seconds after recording | Advertising and optional data transfer |
| BLE transfer | ~80–100mA | Seconds (depends on file count/size) | Only if phone connects |

On a 110mAh battery, standby alone lasts over a year. The active budget is roughly 300–400 recording+sync cycles per charge, assuming ~15 seconds awake per cycle at ~100mA average.

---

## Firmware Architecture

### Device Lifecycle

```
[Deep Sleep] → button press → [Wake] → [Record to PSRAM] → button release →
  → if duration >= 1000ms: save PSRAM → flash (LittleFS)
  → if duration < 1000ms: discard (treat as sync-only tap)
→ [Start BLE advertising, 5 second window]
  → if phone connects: sync all pending recordings → delete synced files
  → if no connection: recordings remain on flash for next time
→ [Deep Sleep]
```

The entire firmware runs in `setup()`. The `loop()` function is never reached — every wake cycle ends in `esp_deep_sleep_start()`.

### Audio Recording

- **Format**: 8-bit unsigned, 16kHz mono (~16KB/sec)
- **Input**: MAX9814 analog output read via `analogRead()` on an ADC-capable GPIO
- **ADC config**: 12-bit resolution, 11dB attenuation (full 0–3.3V range). The 12-bit ADC value is right-shifted to 8-bit for storage.
- **Buffering**: Samples written to a PSRAM buffer (`ps_malloc()`) during recording. Buffer size supports up to 60 seconds (SAMPLE_RATE × 60 = ~960KB).
- **Fallback**: If PSRAM is not available, fall back to regular RAM with a 10-second max buffer.
- **Timing**: A microsecond-level sample interval loop (`1000000 / SAMPLE_RATE` µs per sample) ensures consistent sample rate. Recording continues while the button is held LOW.
- **Minimum duration**: Recordings shorter than 1000ms are discarded. This allows a quick tap to trigger BLE sync without creating a junk file.

### Flash Storage

- **Filesystem**: LittleFS on the ESP32's internal flash.
- **File naming**: `rec_<millis>_<counter>.raw` — unique per recording.
- **Capacity**: ~3MB usable flash = ~3 minutes of 8-bit 16kHz audio. Plenty of buffer for periods away from the phone.
- **Lifecycle**: Files are created after recording, persist through deep sleep cycles, and are deleted only after the phone explicitly acknowledges receipt.

### BLE Protocol

**Service UUID**: `19b10000-e8f2-537e-4f6c-d104768a1214`

| Characteristic | UUID suffix | Properties | Purpose |
|---|---|---|---|
| File Count | `...1214` → `0001` | Read | Number of pending recordings on flash |
| File Info | `...1214` → `0002` | Read | Size in bytes of the current file being sent |
| Audio Data | `...1214` → `0003` | Notify | Chunked audio data stream (20-byte chunks) |
| Command | `...1214` → `0004` | Write | Commands from the phone to the pendant |

**Commands (phone → pendant):**

| Command | Value | Meaning |
|---|---|---|
| REQUEST_NEXT | 0x01 | Phone requests the next pending file |
| ACK_RECEIVED | 0x02 | Phone confirms successful receipt of the current file |
| SYNC_DONE | 0x03 | Phone signals it's done syncing |

**Sync flow:**

1. Pendant wakes, saves recording, starts BLE advertising.
2. Phone's companion app (running background BLE scan) detects the pendant.
3. Phone connects, reads File Count characteristic.
4. Phone writes `REQUEST_NEXT` to Command characteristic.
5. Pendant reads the next file from flash, updates File Info with the file size, and streams the file as a series of 20-byte Notify packets on Audio Data.
6. Phone reassembles the file, writes `ACK_RECEIVED`.
7. Pendant deletes the file from flash, updates File Count.
8. Repeat from step 4 until File Count reaches 0.
9. Phone writes `SYNC_DONE` (or simply disconnects).
10. Pendant enters deep sleep.

**Timeouts:**

- BLE advertising window: 5 seconds (if no connection, go to sleep).
- Sync session timeout: 30 seconds (safety net to avoid draining the battery if something hangs).

**MTU**: Uses platform-default BLE MTU negotiation. Audio packets use 20-byte Notify chunks for reliable transfers across phone stacks.

### Sync-Only Tap

A short tap (< 1000ms) wakes the device and activates BLE without saving a recording. This provides a manual way to force a sync of pending recordings without needing to make a new one. No special firmware logic is required — the standard flow naturally handles it since recordings under 1000ms are discarded before the save step.

---

## Companion App

**Recommended approach**: Web Bluetooth page running in Chrome on the phone. No native app required.

The app needs to:

1. Run a background BLE scan for the pendant's service UUID.
2. On detection, connect and execute the sync flow described above.
3. Reassemble chunked audio data into files.
4. Save recordings locally or integrate with a notes/reminder app.
5. Optionally run speech-to-text (on-device or via API) to transcribe recordings.

**Audio playback**: The raw files are 8-bit unsigned 16kHz mono PCM. To play them in a browser, they need to be loaded into an AudioBuffer at the correct sample rate. No container format or headers — just raw samples.

---

## Enclosure

3D printed pendant housing. Target dimensions: ~25×25×10mm.

**Requirements:**

- Hole or slot for the microphone to pick up sound clearly.
- Tactile button accessible and flush-mounted.
- Access to USB-C port for charging (either a cutout or a removable lid).
- Loop, bail, or hole for attaching to a chain, cord, or lanyard.

---

## Future Considerations

- **Audio compression**: ADPCM or Opus encoding on the ESP32 before saving to flash would roughly halve file sizes and BLE transfer times. Adds firmware complexity.
- **I2S mic upgrade**: Swapping the MAX9814 for an INMP441 would give cleaner, lower-noise audio. Requires 5 wires instead of 3 and I2S driver code instead of ADC reads.
- **Haptic feedback**: A small LRA vibration motor (e.g. LRA 0825, 8×2.5mm) could provide tactile confirmation that recording has started/stopped.
- **Ring form factor**: The original Pebble Index 01 concept was a ring. A pendant is the practical first iteration; a ring would require a custom PCB and significantly smaller components.
- **On-device transcription**: ESP32-S3 can run small neural networks. A tiny wake-word or keyword model is feasible; full transcription is not (do it on the phone).
- **Multiple buttons or gestures**: Double-tap for tagging/categorizing recordings, long-press for different modes, etc.
