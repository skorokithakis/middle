#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <LittleFS.h>
#include <driver/rtc_io.h>
#include <esp_sleep.h>
// NimBLE API for direct notification calls with congestion retry. The Arduino
// BLE wrapper calls ble_gatts_notify_custom but silently aborts on non-zero
// return (e.g. BLE_HS_ENOMEM when the mbuf pool is exhausted). We call it
// ourselves so we can retry instead of losing data.
#include <host/ble_gatt.h>
#include <host/ble_hs_mbuf.h>

static const int pin_button = 12;
static const int pin_mic = 9;
static const int pin_mic_power = 8;

static const int sample_rate = 16000;
static const unsigned long sample_interval_microseconds = 1000000 / sample_rate;
static const unsigned long minimum_recording_milliseconds = 1000;
static const size_t microphone_startup_fade_samples = sample_rate / 200;

// IMA ADPCM step size table — indexed by step_index (0..88).
static const int16_t adpcm_step_table[89] = {
    7,     8,     9,     10,    11,    12,    13,    14,    16,    17,
    19,    21,    23,    25,    28,    31,    34,    37,    41,    45,
    50,    55,    60,    66,    73,    80,    88,    97,    107,   118,
    130,   143,   157,   173,   190,   209,   230,   253,   279,   307,
    337,   371,   408,   449,   494,   544,   598,   658,   724,   796,
    876,   963,   1060,  1166,  1282,  1411,  1552,  1707,  1878,  2066,
    2272,  2499,  2749,  3024,  3327,  3660,  4026,  4428,  4871,  5358,
    5894,  6484,  7132,  7845,  8630,  9493,  10442, 11487, 12635, 13899,
    15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767};

// Maps each encoded nibble to a step_index adjustment.
static const int8_t adpcm_index_table[16] = {-1, -1, -1, -1, 2, 4, 6, 8,
                                              -1, -1, -1, -1, 2, 4, 6, 8};

struct adpcm_state {
  int16_t predicted_sample;
  uint8_t step_index;
};

// Encode one 16-bit signed PCM sample into a 4-bit IMA ADPCM nibble.
static uint8_t adpcm_encode_sample(int16_t sample, adpcm_state &state) {
  int32_t difference = sample - state.predicted_sample;
  uint8_t nibble = 0;
  if (difference < 0) {
    nibble = 8;
    difference = -difference;
  }

  int16_t step = adpcm_step_table[state.step_index];
  // Quantize the difference against the current step size. Each bit in the
  // nibble represents whether the difference exceeds successively halved
  // fractions of the step.
  int32_t delta = step >> 3;
  if (difference >= step) {
    nibble |= 4;
    difference -= step;
    delta += step;
  }
  step >>= 1;
  if (difference >= step) {
    nibble |= 2;
    difference -= step;
    delta += step;
  }
  step >>= 1;
  if (difference >= step) {
    nibble |= 1;
    delta += step;
  }

  // Apply the reconstructed delta so the decoder stays in sync with us.
  if (nibble & 8) {
    state.predicted_sample -= delta;
  } else {
    state.predicted_sample += delta;
  }
  if (state.predicted_sample > 32767) {
    state.predicted_sample = 32767;
  } else if (state.predicted_sample < -32768) {
    state.predicted_sample = -32768;
  }

  int new_index = state.step_index + adpcm_index_table[nibble];
  if (new_index < 0) {
    new_index = 0;
  } else if (new_index > 88) {
    new_index = 88;
  }
  state.step_index = (uint8_t)new_index;

  return nibble;
}

// Ring buffer for draining ADPCM output to LittleFS. Sized to absorb
// worst-case flash write stalls (~10ms at 4 KB/s = ~40 bytes, but we use
// 4 KB for generous headroom).
static const size_t ring_buffer_capacity = 4096;
static uint8_t ring_buffer[ring_buffer_capacity];
static size_t ring_buffer_head = 0;
static size_t ring_buffer_count = 0;

static void ring_buffer_reset() {
  ring_buffer_head = 0;
  ring_buffer_count = 0;
}

static void ring_buffer_push(uint8_t byte) {
  size_t write_position = (ring_buffer_head + ring_buffer_count) % ring_buffer_capacity;
  ring_buffer[write_position] = byte;
  if (ring_buffer_count < ring_buffer_capacity) {
    ring_buffer_count++;
  } else {
    // Overflow — oldest byte is lost. This shouldn't happen with proper
    // flush cadence, but we advance head to keep the buffer consistent.
    ring_buffer_head = (ring_buffer_head + 1) % ring_buffer_capacity;
  }
}

// Flush up to `max_bytes` from the ring buffer to the open file. Returns
// the number of bytes written, or -1 on write error.
static int ring_buffer_flush(File &file, size_t max_bytes) {
  size_t to_write = ring_buffer_count;
  if (to_write > max_bytes) {
    to_write = max_bytes;
  }
  if (to_write == 0) {
    return 0;
  }

  size_t total_written = 0;
  while (to_write > 0) {
    // Write in contiguous chunks to avoid byte-at-a-time overhead.
    size_t contiguous = ring_buffer_capacity - ring_buffer_head;
    if (contiguous > to_write) {
      contiguous = to_write;
    }
    size_t written = file.write(&ring_buffer[ring_buffer_head], contiguous);
    if (written != contiguous) {
      return -1;
    }
    ring_buffer_head = (ring_buffer_head + written) % ring_buffer_capacity;
    ring_buffer_count -= written;
    to_write -= written;
    total_written += written;
  }
  return (int)total_written;
}

static const char *service_uuid = "19b10000-e8f2-537e-4f6c-d104768a1214";
static const char *characteristic_file_count_uuid =
    "19b10001-e8f2-537e-4f6c-d104768a1214";
static const char *characteristic_file_info_uuid =
    "19b10002-e8f2-537e-4f6c-d104768a1214";
static const char *characteristic_audio_data_uuid =
    "19b10003-e8f2-537e-4f6c-d104768a1214";
static const char *characteristic_command_uuid =
    "19b10004-e8f2-537e-4f6c-d104768a1214";

static const uint8_t command_request_next = 0x01;
static const uint8_t command_ack_received = 0x02;
static const uint8_t command_sync_done = 0x03;
static const unsigned long ble_keepalive_milliseconds = 5000;

static BLEServer *ble_server = nullptr;
static BLECharacteristic *file_count_characteristic = nullptr;
static BLECharacteristic *file_info_characteristic = nullptr;
static BLECharacteristic *audio_data_characteristic = nullptr;
static BLEAdvertising *ble_advertising = nullptr;

static volatile uint8_t pending_command = 0;
static volatile bool client_connected = false;
static volatile uint16_t pending_recording_count = 0;
static volatile bool sleep_requested = false;
static bool littlefs_ready = false;
static bool littlefs_mount_attempted = false;
static String current_stream_path = "";
static unsigned long ble_active_until_milliseconds = 0;

static String normalize_path(const char *name) {
  if (name == nullptr) {
    return "";
  }
  String path = String(name);
  if (path.startsWith("/")) {
    return path;
  }
  return String("/") + path;
}

static void set_status_led_off() {
#if defined(RGB_BUILTIN)
  rgbLedWrite(RGB_BUILTIN, 0, 0, 0);
#endif

#if defined(PIN_NEOPIXEL)
  pinMode(PIN_NEOPIXEL, OUTPUT);
  digitalWrite(PIN_NEOPIXEL, LOW);
#endif
}

static void set_microphone_power(bool enabled) {
  digitalWrite(pin_mic_power, enabled ? LOW : HIGH);
}

static bool ble_window_active() {
  return (long)(ble_active_until_milliseconds - millis()) > 0;
}

static void start_ble_advertising() {
  if (ble_advertising == nullptr) {
    return;
  }
  ble_active_until_milliseconds = millis() + ble_keepalive_milliseconds;
  ble_advertising->start();
}

static void configure_button_wakeup() {
  esp_sleep_enable_ext0_wakeup((gpio_num_t)pin_button, 0);
  rtc_gpio_pullup_en((gpio_num_t)pin_button);
  rtc_gpio_pulldown_dis((gpio_num_t)pin_button);
}

static void enter_deep_sleep() {
  set_status_led_off();
  set_microphone_power(false);
  if (ble_advertising != nullptr) {
    ble_advertising->stop();
  }
  delay(20);
  esp_deep_sleep_start();
}

static bool ensure_littlefs_ready() {
  if (littlefs_ready) {
    return true;
  }
  if (littlefs_mount_attempted) {
    return false;
  }

  littlefs_mount_attempted = true;
  littlefs_ready = LittleFS.begin(true);
  return littlefs_ready;
}

static int count_recordings() {
  if (!ensure_littlefs_ready()) {
    return 0;
  }

  int count = 0;
  File root = LittleFS.open("/");
  File entry = root.openNextFile();
  while (entry) {
    String name = String(entry.name());
    if (!entry.isDirectory() &&
        (name.startsWith("rec_") || name.startsWith("/rec_")) &&
        (name.endsWith(".ima") || name.endsWith(".raw"))) {
      count++;
    }
    entry = root.openNextFile();
  }
  return count;
}

static String next_recording_path() {
  if (!ensure_littlefs_ready()) {
    return "";
  }

  File root = LittleFS.open("/");
  File entry = root.openNextFile();
  while (entry) {
    String name = String(entry.name());
    if (!entry.isDirectory() &&
        (name.startsWith("rec_") || name.startsWith("/rec_")) &&
        (name.endsWith(".ima") || name.endsWith(".raw"))) {
      return normalize_path(entry.name());
    }
    entry = root.openNextFile();
  }
  return "";
}

static void update_file_count() {
  uint16_t file_count = (uint16_t)count_recordings();
  pending_recording_count = file_count;
  if (file_count_characteristic != nullptr) {
    file_count_characteristic->setValue(file_count);
  }
}

static bool record_and_save() {
  set_microphone_power(true);
  bool recording_saved = false;

  do {
    if (!ensure_littlefs_ready()) {
      break;
    }

    char filename[40];
    snprintf(filename, sizeof(filename), "/rec_%lu.ima", millis());

    File file = LittleFS.open(filename, FILE_WRITE);
    if (!file) {
      break;
    }

    // Reserve space for the sample count header — we'll fill it in after
    // recording finishes, once we know the actual count.
    uint32_t placeholder = 0;
    file.write((uint8_t *)&placeholder, sizeof(placeholder));

    ring_buffer_reset();
    adpcm_state encoder_state = {0, 0};

    unsigned long record_start_milliseconds = millis();
    uint32_t sample_count = 0;
    bool write_error = false;
    // Tracks whether we're holding an incomplete byte (the low nibble has
    // been written but the high nibble hasn't arrived yet).
    bool nibble_pending = false;
    uint8_t packed_byte = 0;

    while (digitalRead(pin_button) == LOW && !write_error) {
      unsigned long sample_start_microseconds = micros();
      uint16_t raw = analogRead(pin_mic);
      uint8_t sample = (uint8_t)(raw >> 4);

      if (sample_count < microphone_startup_fade_samples) {
        int16_t centered = (int16_t)sample - 128;
        int32_t scaled = (int32_t)centered * (int32_t)(sample_count + 1) /
                         (int32_t)microphone_startup_fade_samples;
        sample = (uint8_t)(scaled + 128);
      }

      // Convert unsigned 8-bit to signed 16-bit for the ADPCM encoder.
      int16_t sample_16 = ((int16_t)sample - 128) << 8;
      uint8_t nibble = adpcm_encode_sample(sample_16, encoder_state);
      sample_count++;

      // Pack two nibbles per byte, low nibble first.
      if (!nibble_pending) {
        packed_byte = nibble & 0x0F;
        nibble_pending = true;
      } else {
        packed_byte |= (nibble << 4);
        ring_buffer_push(packed_byte);
        nibble_pending = false;
      }

      // Flush when the ring buffer is half full to keep headroom for flash
      // write stalls.
      if (ring_buffer_count >= ring_buffer_capacity / 2) {
        if (ring_buffer_flush(file, ring_buffer_count) < 0) {
          write_error = true;
        }
      }

      while (micros() - sample_start_microseconds < sample_interval_microseconds) {
      }
    }

    // Flush the trailing nibble if the sample count was odd.
    if (nibble_pending) {
      ring_buffer_push(packed_byte);
    }

    // Drain any remaining data.
    if (!write_error && ring_buffer_count > 0) {
      if (ring_buffer_flush(file, ring_buffer_count) < 0) {
        write_error = true;
      }
    }

    unsigned long duration_milliseconds = millis() - record_start_milliseconds;
    if (duration_milliseconds < minimum_recording_milliseconds || write_error) {
      file.close();
      LittleFS.remove(filename);
      break;
    }

    // Seek back and write the actual sample count into the header.
    file.seek(0);
    file.write((uint8_t *)&sample_count, sizeof(sample_count));
    file.close();

    recording_saved = true;
    update_file_count();
  } while (false);

  set_microphone_power(false);
  return recording_saved;
}

// Send a BLE notification via NimBLE's ble_gatts_notify_custom(), retrying
// when the call fails due to mbuf pool exhaustion (BLE_HS_ENOMEM) or other
// transient congestion. The Arduino BLE wrapper also calls this function
// internally, but on any non-zero return it aborts the entire transfer —
// which caused ~70% of file data to be silently lost during streaming.
static bool send_notification(uint16_t connection_id, uint16_t attribute_handle,
                              uint8_t *data, int length) {
  for (int attempt = 0; attempt < 200; attempt++) {
    // ble_gatts_notify_custom consumes the mbuf regardless of success or
    // failure, so we must allocate a fresh one on every attempt.
    struct os_mbuf *om = ble_hs_mbuf_from_flat(data, length);
    if (om == nullptr) {
      delay(5);
      continue;
    }

    int rc = ble_gatts_notify_custom(connection_id, attribute_handle, om);
    if (rc == 0) {
      return true;
    }
    // Non-zero means congestion (BLE_HS_ENOMEM = 6, BLE_HS_EBUSY = 15, etc).
    // Wait briefly for the BLE stack to drain and retry.
    delay(5);
  }
  return false;
}

static void stream_current_file() {
  if (!client_connected || ble_server == nullptr) {
    return;
  }

  current_stream_path = next_recording_path();
  if (current_stream_path.length() == 0) {
    return;
  }

  File file = LittleFS.open(current_stream_path, FILE_READ);
  if (!file) {
    current_stream_path = "";
    return;
  }

  uint32_t file_size = file.size();
  file_info_characteristic->setValue(file_size);

  uint16_t connection_id = ble_server->getConnId();
  uint16_t attribute_handle = audio_data_characteristic->getHandle();

  // BLE notification payload is MTU minus 3 bytes of ATT header. Fall back to
  // 20 if the server reports an unexpectedly low value.
  uint16_t mtu = ble_server->getPeerMTU(connection_id);
  int chunk_size = (mtu > 3) ? (mtu - 3) : 20;
  uint8_t chunk[512];
  if (chunk_size > (int)sizeof(chunk)) {
    chunk_size = sizeof(chunk);
  }

  while (file.available() && client_connected) {
    int bytes_read = file.read(chunk, chunk_size);
    if (bytes_read > 0) {
      if (!send_notification(connection_id, attribute_handle, chunk,
                             bytes_read)) {
        break;
      }
    }
  }
  file.close();
}

class server_callbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *server) override {
    client_connected = true;
  }

  void onDisconnect(BLEServer *server) override {
    client_connected = false;
    pending_command = 0;
    if (pending_recording_count > 0) {
      start_ble_advertising();
    } else {
      sleep_requested = true;
    }
  }
};

class command_callbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *characteristic) override {
    String value = characteristic->getValue();
    if (value.length() > 0) {
      pending_command = (uint8_t)value[0];
    }
  }
};

static void init_ble() {
  BLEDevice::init("Middle");
  BLEDevice::setMTU(517);
  ble_server = BLEDevice::createServer();
  ble_server->setCallbacks(new server_callbacks());

  BLEService *service = ble_server->createService(service_uuid);
  file_count_characteristic = service->createCharacteristic(
      characteristic_file_count_uuid, BLECharacteristic::PROPERTY_READ);
  file_info_characteristic = service->createCharacteristic(
      characteristic_file_info_uuid, BLECharacteristic::PROPERTY_READ);
  audio_data_characteristic = service->createCharacteristic(
      characteristic_audio_data_uuid, BLECharacteristic::PROPERTY_NOTIFY);

  BLECharacteristic *command_characteristic = service->createCharacteristic(
      characteristic_command_uuid, BLECharacteristic::PROPERTY_WRITE);
  command_characteristic->setCallbacks(new command_callbacks());

  update_file_count();
  uint32_t file_size = 0;
  file_info_characteristic->setValue(file_size);

  service->start();
  ble_advertising = BLEDevice::getAdvertising();
  ble_advertising->addServiceUUID(service_uuid);
  ble_advertising->setScanResponse(true);
}

void setup() {
  set_status_led_off();

  pinMode(pin_mic_power, OUTPUT);
  set_microphone_power(false);

  pinMode(pin_button, INPUT_PULLUP);
  analogReadResolution(12);
  analogSetAttenuation(ADC_11db);

  configure_button_wakeup();
  esp_sleep_wakeup_cause_t wakeup_cause = esp_sleep_get_wakeup_cause();
  bool woke_from_button = wakeup_cause == ESP_SLEEP_WAKEUP_EXT0 ||
                          wakeup_cause == ESP_SLEEP_WAKEUP_EXT1;

  if (!woke_from_button) {
    enter_deep_sleep();
  }

  if (digitalRead(pin_button) == LOW) {
    record_and_save();
  }

  init_ble();
  update_file_count();

  if (woke_from_button) {
    start_ble_advertising();
  } else if (pending_recording_count > 0) {
    start_ble_advertising();
  }
}

void loop() {
  set_status_led_off();
  static int last_button_state = HIGH;

  int button_state = digitalRead(pin_button);
  if (button_state != last_button_state) {
    last_button_state = button_state;
    if (button_state == LOW) {
      record_and_save();
      start_ble_advertising();
    }
  }

  if (pending_command != 0) {
    uint8_t command = pending_command;
    pending_command = 0;

    if (command == command_request_next) {
      stream_current_file();
    } else if (command == command_ack_received) {
      String path_to_delete = current_stream_path;
      if (path_to_delete.length() == 0) {
        path_to_delete = next_recording_path();
      }

      if (path_to_delete.length() > 0) {
        bool removed = LittleFS.remove(path_to_delete);
        if (removed) {
          current_stream_path = "";
        }
      }
      update_file_count();
    }
  }

  if (sleep_requested ||
      (!client_connected && pending_command == 0 && button_state == HIGH &&
       !ble_window_active())) {
    sleep_requested = false;
    enter_deep_sleep();
  }

  delay(20);
}
