# PhotoAPI – Android HTTP Camera Server

PhotoAPI turns your Android device into a simple camera server that can capture photos on demand via HTTP. Designed for automation tasks such as digitizing photo slides using microcontrollers like NodeMCU or ESP32.

---

## 🚀 Features

- HTTP server listening on port `8080`.
- `/capture` endpoint with full control over camera parameters.
- Query string parameters supported:
  - `width`: image width (default: 1920)
  - `height`: image height (default: 1080)
  - `focus`: manual focus distance (0.0 = infinity)
  - `af`: autofocus (`true` or `false`)
  - `exposure`: shutter time in nanoseconds (e.g. `50000000` = 50ms)
  - `iso`: ISO sensitivity (e.g. 400)
- Real-time camera log shown on screen.
- Image preview shown after each capture.
- Manual capture interface included in the app with test fields.

---

## 📱 Usage

1. Install the app on an Android device connected to Wi-Fi.
2. Open the app and grant camera permissions.
3. On a device in the same network (e.g. NodeMCU), send a request like:
   GET http://<ANDROID_IP>:8080/capture?width=1920&height=1080&focus=0.0&af=false&exposure=50000000&iso=400
4. View a built-in help page from a browser:
   http://<ANDROID_IP>:8080/index.html

---

## 🧪 Manual Capture UI

The app includes a built-in test interface with:

- Input fields for width, height, focus, exposure, ISO.
- A toggle for autofocus.
- A **Capture** button.
- On-screen preview of the last captured photo.

---

## 🛠 Requirements

- Android 6.0+ with Camera2 API support.
- Camera permission.
- Wi-Fi connection.
- Optional: `ACCESS_WIFI_STATE` permission to auto-detect local IP address.

---

## 📂 Project Structure

- `MainActivity.kt`: contains the HTTP server, camera control, and UI.
- HTTP server uses [`androidasync`](https://github.com/koush/AndroidAsync).
- All logic is contained in a single activity for simplicity.

---

## 📸 Example use case

This project was built to automate slide scanning using:

- NodeMCU (ESP32/8266)
- Mechanical slide feeder
- HTTP call to Android to take and store the photo

---

## 🔓 License

MIT License – use freely, modify, and adapt to your needs.
