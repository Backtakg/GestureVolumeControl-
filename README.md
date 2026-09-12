# Gesture Volume Control 🎛️

A zero-cost computer-vision project that controls audio volume with hand gestures. It now has **two local implementations**:

- 🖥️ **Windows desktop:** Python + OpenCV + MediaPipe + Pycaw
- 📱 **Android phone:** Kotlin + CameraX + MediaPipe + Android AudioManager

## ✨ Features

- 🖐️ Real-time hand landmark tracking
- 🤏 Thumb + index finger distance controls volume
- ✊ Closed-fist gesture toggles mute
- 🎚️ Smoothed desktop volume transitions
- 📊 Live volume display
- ⚡ Desktop FPS counter
- 🔒 Fully local — no account, cloud server, or paid API
- 💻 Free/open-source development stack
- 📱 Native Android phone volume control

## 🖥️ Windows Desktop

### Requirements

- Windows
- Python 3.9+
- Webcam
- Windows audio output device

### Run

```bash
pip install -r requirements.txt
python main.py
```

| Gesture | Action |
|---|---|
| 🤏 Thumb + index apart | Increase volume |
| 🤏 Thumb + index together | Decrease volume |
| ✊ Closed fist | Toggle mute |
| `ESC` | Exit |

The Python version uses Pycaw to control the Windows system audio endpoint.

## 📱 Android Phone

The `android/` directory contains a native Android app. It uses the phone's **front camera** to detect the hand and Android's `AudioManager` to change `STREAM_MUSIC` volume directly on the device.

### Android gesture controls

| Gesture | Action |
|---|---|
| 🤏 Thumb + index farther apart | Increase media volume |
| 🤏 Thumb + index closer together | Decrease media volume |
| ✊ Closed fist | Toggle mute |

### Build

Open the `android/` folder in Android Studio and build the `app` module. The Gradle build downloads the MediaPipe hand-landmarker model automatically into the app's assets on the first build.

A physical Android phone with a camera is recommended for testing because gesture tracking needs a live camera feed.

## 🧰 Android Stack

- Kotlin
- Android SDK
- CameraX
- MediaPipe Tasks Vision
- Android `AudioManager`
- Gradle

## 🧠 How It Works

1. The camera captures a live frame.
2. MediaPipe detects hand landmarks locally on the device.
3. The app measures thumb/index distance.
4. The distance is mapped to a volume level.
5. Windows uses Pycaw; Android uses `AudioManager`.
6. A fist toggles mute, with edge detection so holding a fist does not repeatedly toggle mute.

## 📁 Project Structure

```text
GestureVolumeControl-/
├── main.py
├── requirements.txt
├── README.md
└── android/
    ├── build.gradle.kts
    ├── settings.gradle.kts
    └── app/
        ├── build.gradle.kts
        └── src/main/
            ├── AndroidManifest.xml
            ├── java/com/backtakg/gesturevolume/MainActivity.kt
            └── res/
                ├── layout/activity_main.xml
                └── values/styles.xml
```

## 💰 Cost

**NPR 0 / $0.** No paid API, cloud server, subscription, or special hardware is required. You can develop and test the Android version on your own Android phone.

## 💼 Portfolio Value

This project demonstrates:

- Computer vision
- Hand landmark detection
- Gesture-based human-computer interaction
- Native Android development
- CameraX camera processing
- Android system audio integration
- Windows system audio integration
- Kotlin + Python
- Real-time application development

## ⚠️ Platform Notes

- The Windows implementation targets Windows because Pycaw uses Windows audio APIs.
- The Android implementation controls the **phone's own media volume**. It does not remotely change a different device's volume.
- The Android model is downloaded during the Gradle build rather than committed as a large binary file, keeping the Git repository lightweight.
