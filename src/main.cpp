#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <LittleFS.h>

static const int pin_button = 12;
static const int pin_mic = 9;

static const int sample_rate = 16000;
static const unsigned long sample_interval_microseconds = 1000000 / sample_rate;
static const unsigned long minimum_recording_milliseconds = 500;
static const size_t maximum_recording_samples = sample_rate * 10;

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

static BLECharacteristic *file_count_characteristic = nullptr;
static BLECharacteristic *file_info_characteristic = nullptr;
static BLECharacteristic *audio_data_characteristic = nullptr;
static BLEAdvertising *ble_advertising = nullptr;

static volatile uint8_t pending_command = 0;
static volatile bool client_connected = false;
static String current_stream_path = "";

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

static void start_ble_advertising() {
  if (ble_advertising == nullptr) {
    return;
  }
  ble_advertising->start();
  Serial.println("[ble] advertising");
}

static int count_recordings() {
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
  file_count_characteristic->setValue(file_count);
  Serial.printf("[flash] pending files: %u\r\n", file_count);
}

static bool record_and_save() {
  uint8_t *recording_buffer = (uint8_t *)malloc(maximum_recording_samples);
  if (!recording_buffer) {
    Serial.println("[rec] buffer allocation failed");
    return false;
  }

  Serial.println("[rec] recording started");
  unsigned long record_start_milliseconds = millis();
  size_t sample_count = 0;

  while (digitalRead(pin_button) == LOW && sample_count < maximum_recording_samples) {
    unsigned long sample_start_microseconds = micros();
    uint16_t raw = analogRead(pin_mic);
    recording_buffer[sample_count++] = (uint8_t)(raw >> 4);

    while (micros() - sample_start_microseconds < sample_interval_microseconds) {
    }
  }

  unsigned long duration_milliseconds = millis() - record_start_milliseconds;
  if (duration_milliseconds < minimum_recording_milliseconds) {
    Serial.printf("[rec] too short (%lu ms), discarded\r\n", duration_milliseconds);
    free(recording_buffer);
    return false;
  }

  char filename[40];
  snprintf(filename, sizeof(filename), "/rec_%lu.raw", millis());
  File file = LittleFS.open(filename, FILE_WRITE);
  if (!file) {
    Serial.println("[rec] failed to open output file");
    free(recording_buffer);
    return false;
  }

  size_t written = file.write(recording_buffer, sample_count);
  file.close();
  free(recording_buffer);

  Serial.printf("[rec] saved %s (%u/%u bytes)\r\n", filename,
                (unsigned int)written, (unsigned int)sample_count);
  update_file_count();
  return written == sample_count;
}

static void stream_current_file() {
  current_stream_path = next_recording_path();
  if (current_stream_path.length() == 0) {
    Serial.println("[ble] no recording available for stream");
    return;
  }

  File file = LittleFS.open(current_stream_path, FILE_READ);
  if (!file) {
    Serial.println("[ble] failed to open recording file");
    current_stream_path = "";
    return;
  }

  uint32_t file_size = file.size();
  file_info_characteristic->setValue(file_size);
  Serial.printf("[ble] streaming %s (%u bytes)\r\n",
                current_stream_path.c_str(), file_size);

  uint8_t chunk[ble_chunk_size];
  int chunk_count = 0;
  while (file.available()) {
    int bytes_read = file.read(chunk, ble_chunk_size);
    if (bytes_read > 0) {
      audio_data_characteristic->setValue(chunk, bytes_read);
      audio_data_characteristic->notify();
      chunk_count++;
      delay(20);
    }
  }
  file.close();

  Serial.printf("[ble] stream complete: %d chunks\r\n", chunk_count);
}

class server_callbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *server) override {
    client_connected = true;
    Serial.println("[ble] client connected");
  }

  void onDisconnect(BLEServer *server) override {
    client_connected = false;
    Serial.println("[ble] client disconnected");
    start_ble_advertising();
  }
};

class command_callbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *characteristic) override {
    String value = characteristic->getValue();
    if (value.length() > 0) {
      pending_command = (uint8_t)value[0];
      Serial.printf("[ble] command: 0x%02X\r\n", pending_command);
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
  Serial.begin(115200);
  delay(3000);

  pinMode(pin_button, INPUT_PULLUP);
  analogReadResolution(12);
  analogSetAttenuation(ADC_11db);

  Serial.println();
  Serial.println("[boot] middle running");

  if (!LittleFS.begin(true)) {
    Serial.println("[flash] littlefs mount failed");
  } else {
    Serial.println("[flash] littlefs mounted");
  }

  init_ble();

  if (count_recordings() > 0) {
    start_ble_advertising();
  }
}

void loop() {
  static int last_button_state = HIGH;

  int button_state = digitalRead(pin_button);
  if (button_state != last_button_state) {
    last_button_state = button_state;
    Serial.printf("[button] %s\r\n", button_state == LOW ? "pressed" : "released");
    if (button_state == LOW) {
      if (record_and_save()) {
        start_ble_advertising();
      }
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
        Serial.printf("[ble] ack delete %s -> %s\r\n", path_to_delete.c_str(),
                      removed ? "ok" : "failed");
        if (removed) {
          current_stream_path = "";
        }
      } else {
        Serial.println("[ble] ack received but no file path to delete");
      }
      update_file_count();
    } else if (command == command_sync_done) {
      Serial.println("[ble] sync done");
    }
  }

  delay(20);
}
