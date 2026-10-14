/*
 * ESP32 Standalone Wi-Fi Fingerprint Surveyor (Option A)
 * ======================================================
 * Features:
 *   - 100% Standalone (Powered by Powerbank / USB battery; NO PC connection needed while surveying).
 *   - Press the built-in "BOOT" button (GPIO 0) to start a 50-iteration survey for the current room.
 *   - Built-in Blue LED (GPIO 2) blinks rapidly during scanning and turns solid when saved.
 *   - Spots auto-increment (Spot 1, Spot 2, Spot 3...) and persist in LittleFS across power-offs.
 *   - Dynamic AP discovery: No pre-configured BSSIDs needed.
 *   - High Storage Efficiency: Converts strings to integer IDs post-survey. Can hold 100,000+ iterations in Flash.
 *   - Export Mode: Plug into PC via USB, open Serial Monitor at 115200 baud, and type "dump"
 *     to output the complete CSV formatted ready for analyze_fingerprints.py.
 */

#include <WiFi.h>
#include <LittleFS.h>

// Configuration
#define BUTTON_PIN 0          // Built-in BOOT button on ESP32 NodeMCU
#define LED_PIN 2             // Built-in Blue LED
const int TARGET_SAMPLES = 385;       // Iterations per survey spot
const int CHECKPOINT_INTERVAL = 25;    // Checkpoint interval: flush to Flash every 25 scans
const int MAX_APS = 35;                // Max APs buffered per checkpoint

// In-Memory Survey Buffer (Temporary RAM for active checkpoint)
struct ApSurveyBuffer {
  String bssid;
  String ssid;
  int rssiSamples[CHECKPOINT_INTERVAL]; // Standard int (checkpointing keeps memory tiny!)
  int count;
};

ApSurveyBuffer dynamicAps[MAX_APS];
int totalApsFound = 0;

int nextSpotId = 1;
int nextApId = 0;
bool isSurveying = false;

// Scan existing files on boot to resume ID counting across power cycles
void loadExistingIds() {
  // Read highest spot_id
  if (LittleFS.exists("/spots.csv")) {
    File file = LittleFS.open("/spots.csv", FILE_READ);
    while (file.available()) {
      String line = file.readStringUntil('\n');
      line.trim();
      int commaIdx = line.indexOf(',');
      if (commaIdx > 0) {
        int id = line.substring(0, commaIdx).toInt();
        if (id >= nextSpotId) nextSpotId = id + 1;
      }
    }
    file.close();
  }

  // Read highest ap_id
  if (LittleFS.exists("/aps.csv")) {
    File file = LittleFS.open("/aps.csv", FILE_READ);
    while (file.available()) {
      String line = file.readStringUntil('\n');
      line.trim();
      int commaIdx = line.indexOf(',');
      if (commaIdx > 0) {
        int id = line.substring(0, commaIdx).toInt();
        if (id >= nextApId) nextApId = id + 1;
      }
    }
    file.close();
  }

  Serial.printf("[INIT] Ready. Next Spot ID: %d | Total Unique APs Known: %d\n", nextSpotId, nextApId);
}

// Retrieve existing AP ID from flash registry or assign a new one
int getOrRegisterApId(String bssid, String ssid) {
  if (LittleFS.exists("/aps.csv")) {
    File file = LittleFS.open("/aps.csv", FILE_READ);
    while (file.available()) {
      String line = file.readStringUntil('\n');
      line.trim();
      if (line.length() == 0) continue;

      int firstComma = line.indexOf(',');
      int secondComma = line.indexOf(',', firstComma + 1);
      if (firstComma > 0 && secondComma > firstComma) {
        int id = line.substring(0, firstComma).toInt();
        String savedBssid = line.substring(firstComma + 1, secondComma);
        if (savedBssid.equalsIgnoreCase(bssid)) {
          file.close();
          return id;
        }
      }
    }
    file.close();
  }

  // New AP encountered -> Register in /aps.csv
  int assignedId = nextApId++;
  File file = LittleFS.open("/aps.csv", FILE_APPEND);
  if (file) {
    file.printf("%d,%s,%s\n", assignedId, bssid.c_str(), ssid.c_str());
    file.close();
  }
  return assignedId;
}

// Flush buffered checkpoint samples directly to Flash
void flushCheckpoint(int currentSpotId) {
  File dataFile = LittleFS.open("/measurements.csv", FILE_APPEND);
  if (!dataFile) {
    Serial.println("❌ Error opening /measurements.csv for checkpoint!");
    return;
  }

  for (int i = 0; i < totalApsFound; i++) {
    if (dynamicAps[i].count > 0) {
      int apId = getOrRegisterApId(dynamicAps[i].bssid, dynamicAps[i].ssid);
      for (int k = 0; k < dynamicAps[i].count; k++) {
        // Format: spot_id,ap_id,rssi
        dataFile.printf("%d,%d,%d\n", currentSpotId, apId, dynamicAps[i].rssiSamples[k]);
      }
      dynamicAps[i].count = 0; // Empty the buffer for the next checkpoint
    }
  }
  dataFile.close();
}

// Execute survey with periodic checkpoint flushes
void runSurvey() {
  isSurveying = true;
  totalApsFound = 0;
  int currentSpotId = nextSpotId++;

  String spotLabel = "Spot_" + String(currentSpotId);

  // Record spot metadata
  File spotFile = LittleFS.open("/spots.csv", FILE_APPEND);
  if (spotFile) {
    spotFile.printf("%d,%s\n", currentSpotId, spotLabel.c_str());
    spotFile.close();
  }

  Serial.println("\n==================================================");
  Serial.printf("📍 STARTING SURVEY: %s (Spot ID: %d) [%d scans]\n", spotLabel.c_str(), currentSpotId, TARGET_SAMPLES);
  Serial.printf("   Auto-checkpointing to Flash every %d scans\n", CHECKPOINT_INTERVAL);
  Serial.println("==================================================");

  for (int iter = 0; iter < TARGET_SAMPLES; iter++) {
    // LED toggles on every scan so it visibly blinks without needing any artificial delay
    digitalWrite(LED_PIN, (iter % 2 == 0) ? HIGH : LOW);

    int n = WiFi.scanNetworks();

    for (int i = 0; i < n; i++) {
      String bssid = WiFi.BSSIDstr(i);
      String ssid = (WiFi.SSID(i).length() == 0) ? "<Hidden>" : WiFi.SSID(i);
      int rssi = WiFi.RSSI(i);

      int idx = -1;
      for (int j = 0; j < totalApsFound; j++) {
        if (dynamicAps[j].bssid.equalsIgnoreCase(bssid)) {
          idx = j;
          break;
        }
      }

      if (idx == -1 && totalApsFound < MAX_APS) {
        idx = totalApsFound++;
        dynamicAps[idx].bssid = bssid;
        dynamicAps[idx].ssid = ssid;
        dynamicAps[idx].count = 0;
      }

      if (idx != -1 && dynamicAps[idx].count < CHECKPOINT_INTERVAL) {
        dynamicAps[idx].rssiSamples[dynamicAps[idx].count++] = rssi;
      }
    }

    int percent = ((iter + 1) * 100) / TARGET_SAMPLES;
    Serial.printf("  Progress: %d/%d (%d%%) - %d visible APs\n", iter + 1, TARGET_SAMPLES, percent, n);

    // CHECKPOINT: Flush to Flash every CHECKPOINT_INTERVAL scans (or on final scan)
    if ((iter + 1) % CHECKPOINT_INTERVAL == 0 || (iter + 1) == TARGET_SAMPLES) {
      flushCheckpoint(currentSpotId);
      Serial.printf("  💾 [CHECKPOINT] Scans up to #%d safely written to Flash!\n", iter + 1);
    }
  }

  digitalWrite(LED_PIN, LOW); // Turn off when survey finishes
  Serial.printf("✅ Completed '%s' (%d scans)! Move to next room and press BOOT again.\n\n", spotLabel.c_str(), TARGET_SAMPLES);
  isSurveying = false;
}

// Statically allocated export buffers (in BSS, 0 bytes on stack) to prevent ESP32 8KB stack overflow
struct SpotMeta {
  int id;
  char name[32];
};

struct ApMeta {
  int id;
  char bssid[20];
  char ssid[34];
};

const int MAX_EXPORT_SPOTS = 50;
const int MAX_EXPORT_APS = 100;
const int MAX_EXPORT_SAMPLES = 1000;

static SpotMeta exportSpots[MAX_EXPORT_SPOTS];
static ApMeta exportAps[MAX_EXPORT_APS];
static int exportReadings[MAX_EXPORT_SAMPLES];

// Export Flash data into the standard CSV format expected by analyze_fingerprints.py
void dumpDataToSerial() {
  Serial.println("\n========== START OF EXPORT (wifi_fingerprints.csv) ==========");
  Serial.println("timestamp,spot_label,bssid,ssid,sample_count,samples,avg_rssi");

  if (!LittleFS.exists("/measurements.csv") || !LittleFS.exists("/spots.csv") || !LittleFS.exists("/aps.csv")) {
    Serial.println("# No data found in Flash memory.");
    Serial.println("======================== END OF EXPORT ========================\n");
    return;
  }

  // Load spots into memory (0 bytes on stack)
  int spotCount = 0;
  File sf = LittleFS.open("/spots.csv", FILE_READ);
  if (sf) {
    char buf[64];
    while (sf.available() && spotCount < MAX_EXPORT_SPOTS) {
      int len = sf.readBytesUntil('\n', buf, sizeof(buf) - 1);
      buf[len] = '\0';
      char* comma = strchr(buf, ',');
      if (comma) {
        *comma = '\0';
        exportSpots[spotCount].id = atoi(buf);
        char* name = comma + 1;
        while (*name == ' ') name++;
        int nlen = strlen(name);
        while (nlen > 0 && (name[nlen - 1] == '\r' || name[nlen - 1] == '\n' || name[nlen - 1] == ' ')) {
          name[--nlen] = '\0';
        }
        strncpy(exportSpots[spotCount].name, name, sizeof(exportSpots[spotCount].name) - 1);
        exportSpots[spotCount].name[sizeof(exportSpots[spotCount].name) - 1] = '\0';
        spotCount++;
      }
    }
    sf.close();
  }

  // Load APs into memory (0 bytes on stack)
  int apCount = 0;
  File af = LittleFS.open("/aps.csv", FILE_READ);
  if (af) {
    char buf[96];
    while (af.available() && apCount < MAX_EXPORT_APS) {
      int len = af.readBytesUntil('\n', buf, sizeof(buf) - 1);
      buf[len] = '\0';
      char* c1 = strchr(buf, ',');
      if (c1) {
        *c1 = '\0';
        int id = atoi(buf);
        char* bssid = c1 + 1;
        char* c2 = strchr(bssid, ',');
        if (c2) {
          *c2 = '\0';
          char* ssid = c2 + 1;
          int slen = strlen(ssid);
          while (slen > 0 && (ssid[slen - 1] == '\r' || ssid[slen - 1] == '\n')) {
            ssid[--slen] = '\0';
          }
          exportAps[apCount].id = id;
          strncpy(exportAps[apCount].bssid, bssid, sizeof(exportAps[apCount].bssid) - 1);
          exportAps[apCount].bssid[sizeof(exportAps[apCount].bssid) - 1] = '\0';
          strncpy(exportAps[apCount].ssid, ssid, sizeof(exportAps[apCount].ssid) - 1);
          exportAps[apCount].ssid[sizeof(exportAps[apCount].ssid) - 1] = '\0';
          apCount++;
        }
      }
    }
    af.close();
  }

  // Parse measurements per (spot_id, ap_id) and stream to Serial
  unsigned long exportTimestamp = millis();

  for (int s = 0; s < spotCount; s++) {
    int sId = exportSpots[s].id;
    const char* sName = exportSpots[s].name;

    for (int a = 0; a < apCount; a++) {
      int aId = exportAps[a].id;
      const char* aBssid = exportAps[a].bssid;
      const char* aSsid = exportAps[a].ssid;

      File mf = LittleFS.open("/measurements.csv", FILE_READ);
      if (!mf) continue;

      int readCount = 0;
      char mbuf[48];
      while (mf.available() && readCount < MAX_EXPORT_SAMPLES) {
        int len = mf.readBytesUntil('\n', mbuf, sizeof(mbuf) - 1);
        mbuf[len] = '\0';
        int rowSpot = 0, rowAp = 0, rowRssi = 0;
        if (sscanf(mbuf, "%d,%d,%d", &rowSpot, &rowAp, &rowRssi) == 3) {
          if (rowSpot == sId && rowAp == aId) {
            exportReadings[readCount++] = rowRssi;
          }
        }
      }
      mf.close();

      if (readCount >= 5) {
        long sum = 0;
        for (int k = 0; k < readCount; k++) {
          sum += exportReadings[k];
        }
        int avgRssi = round((float)sum / readCount);

        // Stream output directly to UART without gigantic String or printf buffer
        Serial.print(exportTimestamp);
        Serial.print(",");
        Serial.print(sName);
        Serial.print(",");
        Serial.print(aBssid);
        Serial.print(",");
        Serial.print(aSsid);
        Serial.print(",");
        Serial.print(readCount);
        Serial.print(",\"[");
        for (int k = 0; k < readCount; k++) {
          Serial.print(exportReadings[k]);
          if (k < readCount - 1) {
            Serial.print(";");
          }
        }
        Serial.print("]\",");
        Serial.println(avgRssi);
      }
    }
  }

  Serial.println("======================== END OF EXPORT ========================\n");
}

void clearFlashData() {
  LittleFS.remove("/measurements.csv");
  LittleFS.remove("/spots.csv");
  LittleFS.remove("/aps.csv");
  nextSpotId = 1;
  nextApId = 0;
  Serial.println("🗑️ All saved spots, APs, and measurements deleted from Flash!");
}

void printStatus() {
  Serial.println("\n--- ESP32 Surveyor Status ---");
  Serial.printf("Current Spots Count: %d\n", nextSpotId - 1);
  Serial.printf("Unique APs Discovered: %d\n", nextApId);
  if (LittleFS.exists("/measurements.csv")) {
    File f = LittleFS.open("/measurements.csv", FILE_READ);
    Serial.printf("Storage Used: %d bytes\n", f.size());
    f.close();
  } else {
    Serial.println("Storage: Empty");
  }
  Serial.println("Commands available in Serial Monitor: 'dump', 'clear', 'status'\n");
}

void setup() {
  Serial.begin(115200);
  pinMode(BUTTON_PIN, INPUT_PULLUP); // BOOT button active LOW
  pinMode(LED_PIN, OUTPUT);
  digitalWrite(LED_PIN, LOW);

  delay(1000);

  if (!LittleFS.begin(true)) {
    Serial.println("❌ LittleFS Mount Failed!");
    return;
  }

  WiFi.mode(WIFI_STA);
  WiFi.disconnect();

  loadExistingIds();
  printStatus();
}

void loop() {
  // 1. Check physical BOOT button press
  if (digitalRead(BUTTON_PIN) == LOW && !isSurveying) {
    delay(50); // Debounce
    if (digitalRead(BUTTON_PIN) == LOW) {
      // Wait for release
      while (digitalRead(BUTTON_PIN) == LOW) { delay(10); }
      runSurvey();
    }
  }

  // 2. Check Serial commands (when connected to PC)
  if (Serial.available()) {
    String cmd = Serial.readStringUntil('\n');
    cmd.trim();
    cmd.toLowerCase();

    if (cmd == "dump" || cmd == "export") {
      dumpDataToSerial();
    } else if (cmd == "clear" || cmd == "reset") {
      clearFlashData();
    } else if (cmd == "status") {
      printStatus();
    } else if (cmd == "survey") {
      runSurvey();
    }
  }
}
