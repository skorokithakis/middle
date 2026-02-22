#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <LittleFS.h>
#include <driver/i2s_std.h>
#include <driver/rtc_io.h>
#include <esp_sleep.h>
// NimBLE API for direct notification calls with congestion retry. The Arduino
// BLE wrapper calls ble_gatts_notify_custom but silently aborts on non-zero
// return (e.g. BLE_HS_ENOMEM when the mbuf pool is exhausted). We call it
// ourselves so we can retry instead of losing data.
#include <host/ble_gatt.h>
#include <host/ble_hs_mbuf.h>

static const int pin_button = 12;
static const int pin_battery = 1;

// INMP441 I2S pin assignments.
static const int pin_i2s_sck = 4;
static const int pin_i2s_ws = 5;
static const int pin_i2s_sd = 6;

static const int sample_rate = 16000;
static const unsigned long minimum_recording_milliseconds = 1000;

// Samples to discard after I2S init to skip the INMP441's internal startup
// transient (~100ms at 16 kHz).
static const size_t i2s_startup_discard_samples = 1600;

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

// Lock-free single-producer single-consumer ring buffer for draining ADPCM
// output to LittleFS. The sampling loop (producer) and a separate flash-
// writer FreeRTOS task (consumer) run on different cores so flash page-erase
// stalls never block sample capture. At 16 kHz ADPCM (8 KB/s), 32 KB gives
// ~4 seconds of headroom to absorb worst-case LittleFS page-erase stalls.
static const size_t ring_buffer_capacity = 32768;
static uint8_t ring_buffer[ring_buffer_capacity];
static volatile size_t ring_buffer_head = 0; // read index (consumer)
static volatile size_t ring_buffer_tail = 0; // write index (producer)

static void ring_buffer_reset() {
  ring_buffer_head = 0;
  ring_buffer_tail = 0;
}

static void ring_buffer_push(uint8_t byte) {
  size_t next_tail = (ring_buffer_tail + 1) % ring_buffer_capacity;
  if (next_tail == ring_buffer_head) {
    // Buffer full — sample lost. Shouldn't happen with the writer task
    // draining continuously, but prevents corruption if it does.
    return;
  }
  ring_buffer[ring_buffer_tail] = byte;
  ring_buffer_tail = next_tail;
}

// Writer task state — offloads flash writes to core 0 so the sampling
// loop on core 1 never stalls on LittleFS page erases.
static volatile bool writer_active = false;
static volatile bool writer_error = false;
static volatile bool writer_done = false;

static void flash_writer_task(void *param) {
  File *file = (File *)param;
  while (writer_active || ring_buffer_head != ring_buffer_tail) {
    size_t h = ring_buffer_head;
    size_t t = ring_buffer_tail;
    if (h == t) {
      vTaskDelay(1);
      continue;
    }
    // Write the largest contiguous chunk available.
    size_t contiguous = (t > h) ? (t - h) : (ring_buffer_capacity - h);
    size_t written = file->write(&ring_buffer[h], contiguous);
    if (written != contiguous) {
      writer_error = true;
      break;
    }
    ring_buffer_head = (h + written) % ring_buffer_capacity;
  }
  writer_done = true;
  vTaskDelete(nullptr);
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
static const char *characteristic_voltage_uuid =
    "19b10005-e8f2-537e-4f6c-d104768a1214";

static const uint8_t command_request_next = 0x01;
static const uint8_t command_ack_received = 0x02;
static const uint8_t command_sync_done = 0x03;
static const unsigned long ble_keepalive_milliseconds = 10000;

static BLEServer *ble_server = nullptr;
static BLECharacteristic *file_count_characteristic = nullptr;
static BLECharacteristic *file_info_characteristic = nullptr;
static BLECharacteristic *audio_data_characteristic = nullptr;
static BLECharacteristic *voltage_characteristic = nullptr;
static BLEAdvertising *ble_advertising = nullptr;

static volatile uint8_t pending_command = 0;
static volatile bool client_connected = false;
static volatile uint16_t pending_recording_count = 0;
static volatile bool sleep_requested = false;
static bool littlefs_ready = false;
static bool littlefs_mount_attempted = false;
static String current_stream_path = "";
static unsigned long ble_active_until_milliseconds = 0;
static unsigned long hard_sleep_deadline_milliseconds = 0;

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

static bool ble_window_active() {
  return (long)(ble_active_until_milliseconds - millis()) > 0;
}

static void start_ble_advertising() {
  if (ble_advertising == nullptr) {
    return;
  }
  ble_active_until_milliseconds = millis() + ble_keepalive_milliseconds;
  hard_sleep_deadline_milliseconds = millis() + 30000;
  if (!ble_advertising->start()) {
    Serial.printf("[ble] advertising start failed\r\n");
  }
}

static void configure_button_wakeup() {
  esp_sleep_enable_ext0_wakeup((gpio_num_t)pin_button, 0);
  rtc_gpio_pullup_en((gpio_num_t)pin_button);
  rtc_gpio_pulldown_dis((gpio_num_t)pin_button);
}

static void enter_deep_sleep() {
  set_status_led_off();
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
  littlefs_ready = LittleFS.begin(false);
  if (!littlefs_ready) {
    littlefs_ready = LittleFS.begin(true);
  }
  return littlefs_ready;
}

// Extracts the numeric ID from a recording filename like "rec_000012.ima".
// Returns -1 if the name doesn't match the expected pattern.
static long parse_recording_id(const String &name) {
  String stripped = name;
  if (stripped.startsWith("/")) {
    stripped = stripped.substring(1);
  }
  if (!stripped.startsWith("rec_")) {
    return -1;
  }
  int dot = stripped.indexOf('.');
  if (dot < 0) {
    return -1;
  }
  String suffix = stripped.substring(dot);
  if (suffix != ".ima" && suffix != ".raw") {
    return -1;
  }
  String id_str = stripped.substring(4, dot);
  if (id_str.length() == 0) {
    return -1;
  }
  return id_str.toInt();
}

// Returns the next available recording ID by scanning existing filenames.
static long next_recording_id() {
  if (!ensure_littlefs_ready()) {
    return 1;
  }

  long max_id = 0;
  File root = LittleFS.open("/");
  File entry = root.openNextFile();
  while (entry) {
    long id = parse_recording_id(String(entry.name()));
    if (id > max_id) {
      max_id = id;
    }
    entry = root.openNextFile();
  }
  return max_id + 1;
}

static int count_recordings() {
  if (!ensure_littlefs_ready()) {
    return 0;
  }

  int count = 0;
  File root = LittleFS.open("/");
  File entry = root.openNextFile();
  while (entry) {
    if (parse_recording_id(String(entry.name())) >= 0) {
      count++;
    }
    entry = root.openNextFile();
  }
  return count;
}

// Returns the path of the oldest recording (lowest numeric ID).
static String next_recording_path() {
  if (!ensure_littlefs_ready()) {
    return "";
  }

  long lowest_id = -1;
  String lowest_path = "";

  File root = LittleFS.open("/");
  File entry = root.openNextFile();
  while (entry) {
    String name = String(entry.name());
    long id = parse_recording_id(name);
    if (id >= 0 && (lowest_id < 0 || id < lowest_id)) {
      lowest_id = id;
      lowest_path = normalize_path(entry.name());
    }
    entry = root.openNextFile();
  }
  return lowest_path;
}

static void update_file_count() {
  uint16_t file_count = (uint16_t)count_recordings();
  pending_recording_count = file_count;
  if (file_count_characteristic != nullptr) {
    file_count_characteristic->setValue(file_count);
  }
}

// Buffer size for each i2s_channel_read() call. In stereo mode each frame
// contains a left and a right 32-bit sample, so 512 frames = 1024 int32_t
// values and yields 256 usable mono samples (~16ms at 16 kHz).
static const size_t i2s_read_frames = 512;

static i2s_chan_handle_t i2s_rx_channel = nullptr;

static bool i2s_init() {
  i2s_chan_config_t channel_config = I2S_CHANNEL_DEFAULT_CONFIG(
      I2S_NUM_AUTO, I2S_ROLE_MASTER);
  if (i2s_new_channel(&channel_config, nullptr, &i2s_rx_channel) != ESP_OK) {
    Serial.printf("[rec] i2s_new_channel failed\r\n");
    return false;
  }

  i2s_std_config_t std_config = {
      .clk_cfg = I2S_STD_CLK_DEFAULT_CONFIG(sample_rate),
      .slot_cfg = I2S_STD_PHILIPS_SLOT_DEFAULT_CONFIG(I2S_DATA_BIT_WIDTH_32BIT,
                                                       I2S_SLOT_MODE_STEREO),
      .gpio_cfg = {
          .mclk = I2S_GPIO_UNUSED,
          .bclk = (gpio_num_t)pin_i2s_sck,
          .ws   = (gpio_num_t)pin_i2s_ws,
          .dout = I2S_GPIO_UNUSED,
          .din  = (gpio_num_t)pin_i2s_sd,
          .invert_flags = {
              .mclk_inv = false,
              .bclk_inv = false,
              .ws_inv   = false,
          },
      },
  };

  if (i2s_channel_init_std_mode(i2s_rx_channel, &std_config) != ESP_OK) {
    Serial.printf("[rec] i2s_channel_init_std_mode failed\r\n");
    i2s_del_channel(i2s_rx_channel);
    i2s_rx_channel = nullptr;
    return false;
  }

  if (i2s_channel_enable(i2s_rx_channel) != ESP_OK) {
    Serial.printf("[rec] i2s_channel_enable failed\r\n");
    i2s_del_channel(i2s_rx_channel);
    i2s_rx_channel = nullptr;
    return false;
  }

  return true;
}

static void i2s_deinit() {
  if (i2s_rx_channel != nullptr) {
    i2s_channel_disable(i2s_rx_channel);
    i2s_del_channel(i2s_rx_channel);
    i2s_rx_channel = nullptr;
  }
}

static bool record_and_save() {
  bool recording_saved = false;

  if (!i2s_init()) {
    return false;
  }

  do {
    if (!ensure_littlefs_ready()) {
      break;
    }

    char filename[40];
    snprintf(filename, sizeof(filename), "/rec_%06ld.ima", next_recording_id());

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

    // Start the flash writer on core 0 so page-erase stalls never block
    // the sampling loop running here on core 1.
    writer_active = true;
    writer_error = false;
    writer_done = false;
    TaskHandle_t writer_handle = nullptr;
    if (xTaskCreatePinnedToCore(flash_writer_task, "flash_wr", 4096, &file,
                                1, &writer_handle, 0) != pdPASS) {
      file.close();
      LittleFS.remove(filename);
      break;
    }

    // In stereo mode each frame has two 32-bit slots (left + right).
    // The INMP441 outputs 24-bit audio left-justified in the left slot;
    // >> 16 yields a signed 16-bit sample.
    static int32_t i2s_buf[i2s_read_frames * 2];
    size_t bytes_read = 0;

    // Discard the first ~100ms of samples to skip the INMP441 startup
    // transient. In stereo mode each frame is two int32_t values (L+R),
    // so divide by 2 to count mono samples.
    size_t discarded = 0;
    while (discarded < i2s_startup_discard_samples) {
      esp_err_t err = i2s_channel_read(i2s_rx_channel, i2s_buf, sizeof(i2s_buf),
                                       &bytes_read, portMAX_DELAY);
      if (err != ESP_OK) {
        Serial.printf("[rec] i2s_channel_read error %d in discard loop\r\n", err);
        break;
      }
      discarded += bytes_read / sizeof(int32_t) / 2;
    }

    unsigned long record_start_milliseconds = millis();
    uint32_t sample_count = 0;
    // Tracks whether we're holding an incomplete byte (the low nibble has
    // been written but the high nibble hasn't arrived yet).
    bool nibble_pending = false;
    uint8_t packed_byte = 0;

    while (digitalRead(pin_button) == LOW && !writer_error) {
      esp_err_t err = i2s_channel_read(i2s_rx_channel, i2s_buf,
                                       sizeof(i2s_buf), &bytes_read,
                                       portMAX_DELAY);
      if (err != ESP_OK) {
        Serial.printf("[rec] i2s_channel_read error %d\r\n", err);
        break;
      }

      size_t total_samples = bytes_read / sizeof(int32_t);
      for (size_t i = 0; i < total_samples; i += 2) {
        // Stereo interleave: even indices are left channel (INMP441 data).
        int16_t sample_16 = (int16_t)(i2s_buf[i] >> 16);
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
      }
    }

    // Flush the trailing nibble if the sample count was odd.
    if (nibble_pending) {
      ring_buffer_push(packed_byte);
    }

    // Signal the writer task to drain remaining data and wait for it.
    writer_active = false;
    while (!writer_done) {
      delay(1);
    }

    unsigned long duration_milliseconds = millis() - record_start_milliseconds;
    if (duration_milliseconds < minimum_recording_milliseconds || writer_error) {
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

  i2s_deinit();
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

static uint16_t read_battery_millivolts() {
  // Throwaway read to pre-charge the ADC's sample-and-hold capacitor,
  // which otherwise doesn't fully settle through the 180k voltage divider.
  analogRead(pin_battery);
  delayMicroseconds(100);

  uint32_t sum = 0;
  for (int i = 0; i < 10; i++) {
    sum += analogReadMilliVolts(pin_battery);
  }
  // Voltage divider halves the battery voltage, so multiply by 2 to recover it.
  // Non-linear correction for ADC reading low due to 180k source impedance.
  // Correction factor = 1.302 - 0.000065 * raw_mV, i.e. ~1.04 at 4V, ~1.05 at 3.85V.
  uint32_t raw = (sum / 10) * 2;
  uint32_t factor = 13020 - 65 * raw / 100;
  return (uint16_t)(raw * factor / 10000);
}

class server_callbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *server) override {
    client_connected = true;
    ble_active_until_milliseconds = millis() + ble_keepalive_milliseconds;
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
  BLEDevice::setPower(ESP_PWR_LVL_P9, ESP_BLE_PWR_TYPE_ADV);
  BLEDevice::setPower(ESP_PWR_LVL_P9, ESP_BLE_PWR_TYPE_DEFAULT);
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

  voltage_characteristic = service->createCharacteristic(
      characteristic_voltage_uuid, BLECharacteristic::PROPERTY_READ);
  uint16_t initial_voltage = 0;
  voltage_characteristic->setValue(initial_voltage);

  update_file_count();
  uint32_t file_size = 0;
  file_info_characteristic->setValue(file_size);

  service->start();
  ble_advertising = BLEDevice::getAdvertising();
  ble_advertising->addServiceUUID(service_uuid);
  ble_advertising->setScanResponse(true);
}

static bool ble_initialized = false;

static void start_ble_if_needed() {
  update_file_count();
  if (pending_recording_count > 0) {
    if (!ble_initialized) {
      init_ble();
      ble_initialized = true;
    }
    uint16_t millivolts = read_battery_millivolts();
    voltage_characteristic->setValue(millivolts);
    Serial.printf("[bat] Battery: %u mV\r\n", millivolts);
    start_ble_advertising();
  }
}

void setup() {
  set_status_led_off();

  pinMode(pin_button, INPUT_PULLUP);

  configure_button_wakeup();
  esp_sleep_wakeup_cause_t wakeup_cause = esp_sleep_get_wakeup_cause();

  if (wakeup_cause != ESP_SLEEP_WAKEUP_EXT0 &&
      wakeup_cause != ESP_SLEEP_WAKEUP_EXT1) {
    enter_deep_sleep();
  }

  int button = digitalRead(pin_button);
  if (button == LOW) {
    record_and_save();
  }
}

void loop() {
  set_status_led_off();
  static int last_button_state = HIGH;
  static bool initial_ble_check_done = false;

  // After the first recording in setup(), we enter loop with the button
  // already released. Check once whether there are files to sync.
  if (!initial_ble_check_done) {
    initial_ble_check_done = true;
    start_ble_if_needed();
  }

  int button_state = digitalRead(pin_button);
  if (button_state != last_button_state) {
    last_button_state = button_state;
    if (button_state == LOW) {
      record_and_save();
      start_ble_if_needed();
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

  if (button_state == HIGH && hard_sleep_deadline_milliseconds != 0 &&
      (long)(millis() - hard_sleep_deadline_milliseconds) >= 0) {
    enter_deep_sleep();
  }

  if (sleep_requested ||
      (!client_connected && pending_command == 0 && button_state == HIGH &&
       !ble_window_active())) {
    sleep_requested = false;
    enter_deep_sleep();
  }

  delay(20);
}
