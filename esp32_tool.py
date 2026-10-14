#!/usr/bin/env python3
"""
ESP32 Serial Monitor & Data Exporter
===================================
Solves the Linux CP210x DTR/RTS auto-reset issue and lets you:
  - View live survey progress
  - Send commands: 'survey', 'status', 'dump', 'clear'
  - Save the dumped CSV directly to wifi_fingerprints.csv
"""

import sys
import time
import serial

PORT = "/dev/ttyUSB0"
BAUD = 115200

def main():
    print(f"🔌 Connecting to ESP32 on {PORT} at {BAUD} baud...")
    try:
        ser = serial.Serial(PORT, BAUD, timeout=0.5)
    except serial.SerialException as e:
        print(f"\n❌ Port Busy or Not Found: {e}")
        print("💡 Make sure to CLOSE the Arduino IDE Serial Monitor window first!\n")
        return

    # CRITICAL FOR LINUX CP210x: Release DTR and RTS so ESP32 is NOT held in reset!
    ser.setDTR(False)
    ser.setRTS(False)
    time.sleep(0.1)

    # Clean reboot pulse
    ser.setDTR(False)
    ser.setRTS(True)
    time.sleep(0.05)
    ser.setRTS(False)

    print("✅ Connected! ESP32 released from reset.")
    print("Commands: Type 'survey', 'status', 'dump', 'clear' (or press physical BOOT button)")
    print("Press Ctrl+C to exit.\n")
    print("-" * 50)

    import threading

    def read_serial():
        saving_dump = False
        dump_lines = []
        try:
            while True:
                line = ser.readline().decode("utf-8", errors="replace")
                if line:
                    sys.stdout.write(line)
                    sys.stdout.flush()

                    if "START OF EXPORT" in line:
                        saving_dump = True
                        dump_lines = []
                    elif "END OF EXPORT" in line:
                        saving_dump = False
                        if dump_lines:
                            out_file = "esp32_fingerprints.csv"
                            backup_file = f"esp32_fingerprints_{int(time.time())}.csv"
                            with open(out_file, "w") as f:
                                f.write("".join(dump_lines))
                            with open(backup_file, "w") as f:
                                f.write("".join(dump_lines))
                            print(f"\n🎉 Saved {len(dump_lines)} lines to '{out_file}' and backup '{backup_file}' (Phone CSV protected)!\n")
                    elif saving_dump and not line.startswith("="):
                        dump_lines.append(line)
        except Exception:
            pass

    t = threading.Thread(target=read_serial, daemon=True)
    t.start()

    try:
        while True:
            cmd = input()
            if cmd.strip():
                ser.write((cmd.strip() + "\n").encode("utf-8"))
    except KeyboardInterrupt:
        print("\n👋 Disconnected.")
    finally:
        ser.close()

if __name__ == "__main__":
    main()
