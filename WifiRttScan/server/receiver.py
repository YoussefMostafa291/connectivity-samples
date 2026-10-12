#!/usr/bin/env python3
"""Tiny LAN receiver for WifiRttScan Android measurements (Python 3.7+)."""

import csv
import json
import os
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOST = "0.0.0.0"
PORT = 5000
CSV_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "measurements.csv")
FIELDS = [
    "timestamp", "x_m", "y_m", "rtt_m", "rssi_dbm",
    "rtt_stddev_m", "bssid", "ssid"
]


class MeasurementHandler(BaseHTTPRequestHandler):
    def _send_json(self, status, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.rstrip("/") == "/health":
            return self._send_json(200, {
                "ok": True,
                "service": "WifiRttScan receiver",
                "csv": os.path.basename(CSV_PATH)
            })
        return self._send_json(404, {"ok": False, "error": "Use GET /health or POST /measurements"})

    def do_POST(self):
        if self.path.rstrip("/") != "/measurements":
            return self._send_json(404, {"ok": False, "error": "Endpoint not found: " + self.path})
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0 or length > 1024 * 1024:
                return self._send_json(400, {"ok": False, "error": "Invalid Content-Length"})
            payload = json.loads(self.rfile.read(length).decode("utf-8"))
            for key in ("x_m", "y_m", "rtt_m", "rssi_dbm"):
                if key not in payload:
                    return self._send_json(400, {"ok": False, "error": "Missing field: " + key})
            row = {field: payload.get(field, "") for field in FIELDS}
            new_file = not os.path.exists(CSV_PATH) or os.path.getsize(CSV_PATH) == 0
            with open(CSV_PATH, "a", newline="", encoding="utf-8") as output:
                writer = csv.DictWriter(output, fieldnames=FIELDS)
                if new_file:
                    writer.writeheader()
                writer.writerow(row)
                output.flush()
                os.fsync(output.fileno())
            print("[{}] saved x={} y={} RTT={}m RSSI={}dBm AP={}".format(
                datetime.now().isoformat(timespec="seconds"),
                row["x_m"], row["y_m"], row["rtt_m"], row["rssi_dbm"], row["bssid"]
            ), flush=True)
            return self._send_json(201, {"ok": True, "saved": True})
        except (ValueError, UnicodeDecodeError, json.JSONDecodeError) as exc:
            return self._send_json(400, {"ok": False, "error": "Invalid JSON/data: " + str(exc)})
        except Exception as exc:
            print("ERROR saving measurement:", repr(exc), flush=True)
            return self._send_json(500, {"ok": False, "error": "Server failed to save measurement"})

    def log_message(self, fmt, *args):
        print("{} - {}".format(self.client_address[0], fmt % args), flush=True)


if __name__ == "__main__":
    server = ThreadingHTTPServer((HOST, PORT), MeasurementHandler)
    print("WifiRttScan receiver listening on http://{}:{}".format(HOST, PORT), flush=True)
    print("POST endpoint: /measurements | Health check: /health", flush=True)
    print("Saving CSV to: {}".format(CSV_PATH), flush=True)
    print("Keep this window open while collecting data. Press Ctrl+C to stop.", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nStopping receiver...", flush=True)
    finally:
        server.server_close()
