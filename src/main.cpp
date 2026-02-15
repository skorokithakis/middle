#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <LittleFS.h>
#include <driver/rtc_io.h>
#include <esp_sleep.h>

static const int pin_button = 12;
static const int pin_mic = 9;
static const int pin_mic_power = 8;

static const int sample_rate = 16000;
static const unsigned long sample_interval_microseconds = 1000000 / sample_rate;
static const unsigned long minimum_recording_milliseconds = 1000;
static const size_t maximum_recording_samples = sample_rate * 10;
static const size_t microphone_startup_fade_samples = sample_rate / 200;

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
static const int ble_chunk_size = 20;
static const int ble_chunk_gap_milliseconds = 20;
static const unsigned long ble_keepalive_milliseconds = 5000;

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
        (name.startsWith("rec_") || name.startsWith("/rec_"))) {
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
        (name.startsWith("rec_") || name.startsWith("/rec_"))) {
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
  uint8_t *recording_buffer = nullptr;

  do {
    recording_buffer = (uint8_t *)malloc(maximum_recording_samples);
    if (!recording_buffer) {
      break;
    }

    unsigned long record_start_milliseconds = millis();
    size_t sample_count = 0;

    while (digitalRead(pin_button) == LOW && sample_count < maximum_recording_samples) {
      unsigned long sample_start_microseconds = micros();
      uint16_t raw = analogRead(pin_mic);
      uint8_t sample = (uint8_t)(raw >> 4);
      if (sample_count < microphone_startup_fade_samples) {
        int16_t centered = (int16_t)sample - 128;
        int32_t scaled = (int32_t)centered * (int32_t)(sample_count + 1) /
                         (int32_t)microphone_startup_fade_samples;
        sample = (uint8_t)(scaled + 128);
      }
      recording_buffer[sample_count++] = sample;

      while (micros() - sample_start_microseconds < sample_interval_microseconds) {
      }
    }

    unsigned long duration_milliseconds = millis() - record_start_milliseconds;
    if (duration_milliseconds < minimum_recording_milliseconds) {
      break;
    }

    char filename[40];
    snprintf(filename, sizeof(filename), "/rec_%lu.raw", millis());

    if (!ensure_littlefs_ready()) {
      break;
    }

    File file = LittleFS.open(filename, FILE_WRITE);
    if (!file) {
      break;
    }

    size_t written = file.write(recording_buffer, sample_count);
    file.close();

    recording_saved = written == sample_count;
    if (recording_saved) {
      update_file_count();
    }
  } while (false);

  if (recording_buffer != nullptr) {
    free(recording_buffer);
  }
  set_microphone_power(false);
  return recording_saved;
}

static void stream_current_file() {
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

  uint8_t chunk[ble_chunk_size];
  while (file.available()) {
    int bytes_read = file.read(chunk, ble_chunk_size);
    if (bytes_read > 0) {
      audio_data_characteristic->setValue(chunk, bytes_read);
      audio_data_characteristic->notify();
      delay(ble_chunk_gap_milliseconds);
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
  BLEServer *server = BLEDevice::createServer();
  server->setCallbacks(new server_callbacks());

  BLEService *service = server->createService(service_uuid);
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
    ble_active_until_milliseconds = millis() + ble_keepalive_milliseconds;
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
      bool recording_saved = record_and_save();
      if (!recording_saved) {
        ble_active_until_milliseconds = millis() + ble_keepalive_milliseconds;
      }
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
      (!client_connected && pending_recording_count == 0 &&
       pending_command == 0 && button_state == HIGH && !ble_window_active())) {
    sleep_requested = false;
    enter_deep_sleep();
  }

  delay(20);
}
