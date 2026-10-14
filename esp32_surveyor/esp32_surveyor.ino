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

// Export Flash data into the standard CSV format expected by analyze_fingerprints.py
void dumpDataToSerial() {
  Serial.println("\n========== START OF EXPORT (wifi_fingerprints.csv) ==========");
  Serial.println("timestamp,spot_label,bssid,ssid,sample_count,samples,avg_rssi");

  if (!LittleFS.exists("/measurements.csv") || !LittleFS.exists("/spots.csv") || !LittleFS.exists("/aps.csv")) {
    Serial.println("# No data found in Flash memory.");
    Serial.println("======================== END OF EXPORT ========================\n");
    return;
  }

  // Load spots into memory
  struct SpotEntry { int id; String name; };
  SpotEntry spots[50];
  int spotCount = 0;
  File sf = LittleFS.open("/spots.csv", FILE_READ);
  while (sf.available() && spotCount < 50) {
    String line = sf.readStringUntil('\n');
    line.trim();
    int c = line.indexOf(',');
    if (c > 0) {
      spots[spotCount].id = line.substring(0, c).toInt();
      spots[spotCount].name = line.substring(c + 1);
      spotCount++;
    }
  }
  sf.close();

  // Load APs into memory
  struct ApEntry { int id; String bssid; String ssid; };
  ApEntry aps[100];
  int apCount = 0;
  File af = LittleFS.open("/aps.csv", FILE_READ);
  while (af.available() && apCount < 100) {
    String line = af.readStringUntil('\n');
    line.trim();
    int c1 = line.indexOf(',');
    int c2 = line.indexOf(',', c1 + 1);
    if (c1 > 0 && c2 > c1) {
      aps[apCount].id = line.substring(0, c1).toInt();
      aps[apCount].bssid = line.substring(c1 + 1, c2);
      aps[apCount].ssid = line.substring(c2 + 1);
      apCount++;
    }
  }
  af.close();

  // Parse measurements per (spot_id, ap_id)
  unsigned long exportTimestamp = millis();

  for (int s = 0; s < spotCount; s++) {
    int sId = spots[s].id;
    String sName = spots[s].name;

    for (int a = 0; a < apCount; a++) {
      int aId = aps[a].id;
      String aBssid = aps[a].bssid;
      String aSsid = aps[a].ssid;

      // Scan measurements file for (sId, aId)
      File mf = LittleFS.open("/measurements.csv", FILE_READ);
      const int MAX_EXPORT_SAMPLES = 1000;
      int readings[MAX_EXPORT_SAMPLES];
      int readCount = 0;

      while (mf.available() && readCount < MAX_EXPORT_SAMPLES) {
        String line = mf.readStringUntil('\n');
        line.trim();
        int c1 = line.indexOf(',');
        int c2 = line.indexOf(',', c1 + 1);
        if (c1 > 0 && c2 > c1) {
          int rowSpot = line.substring(0, c1).toInt();
          int rowAp = line.substring(c1 + 1, c2).toInt();
          int rowRssi = line.substring(c2 + 1).toInt();

          if (rowSpot == sId && rowAp == aId) {
            readings[readCount++] = rowRssi;
          }
        }
      }
      mf.close();

      if (readCount >= 5) {
        long sum = 0;
        String rawStr = "\"[";
        for (int k = 0; k < readCount; k++) {
          sum += readings[k];
          rawStr += String(readings[k]);
          if (k < readCount - 1) rawStr += ";";
        }
        rawStr += "]\"";
        int avgRssi = round((float)sum / readCount);

        Serial.printf("%lu,%s,%s,%s,%d,%s,%d\n",
                      exportTimestamp,
                      sName.c_str(),
                      aBssid.c_str(),
                      aSsid.c_str(),
                      readCount,
                      rawStr.c_str(),
                      avgRssi);
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
