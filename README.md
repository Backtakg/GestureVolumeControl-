# Gesture Volume Control 🎛️

A free, local Windows desktop project that controls system volume using hand gestures and a webcam.

## Features

- Real-time hand tracking with MediaPipe
- Thumb-to-index finger distance controls volume from 0–100%
- Live volume percentage and visual volume bar
- No cloud service or paid API
- Runs locally with Python

## Tech stack

- Python
- OpenCV
- MediaPipe
- Pycaw
- NumPy

## Requirements

- Windows
- Python 3.9+
- Working webcam
- Windows audio output device

## Run locally

```bash
pip install -r requirements.txt
python main.py
```

Place your hand in front of the webcam. Move your thumb and index finger apart to increase volume and together to decrease it. Press **Esc** to exit.

## Portfolio value

This project demonstrates real-time computer vision, hand landmark tracking, hardware input, system audio integration, and human-computer interaction.

## Cost

**NPR 0 / $0** — uses free/open-source Python packages and your existing webcam.

## Note

The current implementation uses Windows audio APIs through Pycaw, so system-volume control is Windows-specific.
