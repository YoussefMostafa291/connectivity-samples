# WifiRttScan laptop receiver

This receiver accepts one JSON HTTP POST for each successful Wi-Fi RTT result and appends it to `measurements.csv`.

## 1. Start the server on Windows

Open Command Prompt in this `server` folder and run:

```bat
python --version
python receiver.py
```

Leave the terminal window open. It listens on port `5000` and saves data to `measurements.csv` in this folder. No third-party Python packages are needed.

## 2. Find the laptop's Wi-Fi IPv4 address

On the laptop, open another Command Prompt and run:

```bat
ipconfig
```

Under the Wi-Fi adapter that is connected to the same network as the phone, find `IPv4 Address` (for example, `192.168.1.23`). Use your actual address, not this example.

## 3. Verify the server

On the laptop, open a browser at:

```text
http://127.0.0.1:5000/health
```

It should return JSON containing `"ok": true`.

Then, while the phone and laptop are connected to the same Wi-Fi/LAN, open this on the phone's browser, replacing the example address:

```text
http://192.168.1.23:5000/health
```

If the phone cannot open it, check that both devices are on the same network and allow Python through Windows Defender Firewall on a **Private network**. Do not expose this simple receiver to the public internet.

## 4. Configure the Android app

On the selected Access Point measurement screen, set the server URL to:

```text
http://192.168.1.23:5000/measurements
```

Replace `192.168.1.23` with the laptop's real IPv4 address. Enter X and Y in meters. The app posts each successful RTT reading with the coordinates, RSSI, timestamp, AP BSSID/SSID, and RTT standard deviation.

## 5. Confirm the data is arriving

- The app status should show `Server connected — last upload HTTP 201`.
- The server terminal should print a `saved x=... y=... RTT=... RSSI=...` line for each request.
- Open `measurements.csv` after a few readings and check that the row count increases and the X/Y values match the point you entered.

The server responds to successful writes with HTTP `201`. HTTP `404` usually means the URL path is wrong; connection failures commonly mean a wrong laptop IP, different Wi-Fi network, firewall block, or a stopped server.
