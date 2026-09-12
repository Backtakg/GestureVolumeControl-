# Gesture Volume Control 🎛️

A free, local Windows desktop application that controls system audio with hand gestures and a webcam.

## ✨ Features

- 🖐️ Real-time hand landmark tracking with MediaPipe
- 🤏 Thumb + index finger distance controls volume from 0–100%
- ✊ Closed-fist gesture toggles mute
- 🎚️ Smooth volume transitions to reduce jitter
- 📊 Live volume bar and percentage
- ⚡ FPS counter for performance visibility
- 🔒 Fully local — no cloud service, account, or paid API
- 💻 Built with free/open-source software

## 🧰 Tech Stack

- Python 3.9+
- OpenCV
- MediaPipe
- Pycaw
- Comtypes
- NumPy

## 🖥️ Requirements

- Windows
- Python 3.9 or newer
- Working webcam
- Windows audio output device

## 🚀 Run Locally

```bash
pip install -r requirements.txt
python main.py
```

### Controls

| Gesture | Action |
|---|---|
| 🤏 Thumb + index apart | Increase volume |
| 🤏 Thumb + index together | Decrease volume |
| ✊ Closed fist | Toggle mute |
| `ESC` | Exit application |

## ⚙️ Configuration

The main settings are at the top of `main.py`:

- `CAMERA_INDEX` — select a webcam
- `FRAME_WIDTH` / `FRAME_HEIGHT` — camera resolution
- `MIN_PINCH_DISTANCE` / `MAX_PINCH_DISTANCE` — gesture sensitivity
- `SMOOTHING` — volume transition smoothness

## 🧠 How It Works

1. OpenCV captures frames from the webcam.
2. MediaPipe detects the hand and its landmarks.
3. The application measures the distance between the thumb and index finger.
4. That distance is mapped to a 0–100% volume value.
5. Pycaw sends the calculated value to the Windows audio endpoint.
6. A closed fist toggles the Windows mute state.

## 📁 Project Structure

```text
GestureVolumeControl-/
├── main.py
├── requirements.txt
└── README.md
```

## 💰 Cost

**NPR 0 / $0.** The project uses free/open-source packages and an existing webcam. No paid API, subscription, or cloud server is required.

## 💼 Portfolio Value

This project demonstrates:

- Real-time computer vision
- Hand landmark detection
- Gesture-based human-computer interaction
- Hardware/webcam input
- Windows system audio integration
- Real-time performance monitoring
- Python application development

## ⚠️ Platform Note

Gesture recognition is designed to be portable, but system-volume control currently uses Pycaw/Windows audio APIs and therefore targets Windows.
