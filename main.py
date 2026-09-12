import math
import time
import cv2
import mediapipe as mp
from comtypes import CLSCTX_ALL
from pycaw.pycaw import AudioUtilities, IAudioEndpointVolume

CAMERA_INDEX = 0
FRAME_WIDTH = 1280
FRAME_HEIGHT = 720
MIN_PINCH_DISTANCE = 35
MAX_PINCH_DISTANCE = 220
SMOOTHING = 0.18


def clamp(value, low, high):
    return max(low, min(high, value))


def distance(a, b, width, height):
    return math.hypot((a.x - b.x) * width, (a.y - b.y) * height)


def is_fist(hand):
    tips = (8, 12, 16, 20)
    pips = (6, 10, 14, 18)
    return all(hand.landmark[t].y > hand.landmark[p].y for t, p in zip(tips, pips))


def main():
    devices = AudioUtilities.GetSpeakers()
    interface = devices.Activate(IAudioEndpointVolume._iid_, CLSCTX_ALL, None)
    volume = interface.QueryInterface(IAudioEndpointVolume)
    min_db, max_db, _ = volume.GetVolumeRange()

    cap = cv2.VideoCapture(CAMERA_INDEX)
    cap.set(cv2.CAP_PROP_FRAME_WIDTH, FRAME_WIDTH)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, FRAME_HEIGHT)

    mp_hands = mp.solutions.hands
    hands = mp_hands.Hands(
        static_image_mode=False,
        max_num_hands=1,
        min_detection_confidence=0.6,
        min_tracking_confidence=0.6,
    )
    draw = mp.solutions.drawing_utils

    current_percent = volume.GetMasterVolumeLevelScalar() * 100
    muted = False
    last_fist_time = 0.0
    previous_time = time.perf_counter()
    fps = 0.0

    while True:
        ok, frame = cap.read()
        if not ok:
            break

        frame = cv2.flip(frame, 1)
        rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
        result = hands.process(rgb)
        gesture = "Show your hand"

        now = time.perf_counter()
        elapsed = now - previous_time
        previous_time = now
        if elapsed > 0:
            fps = 0.9 * fps + 0.1 * (1 / elapsed)

        if result.multi_hand_landmarks:
            hand = result.multi_hand_landmarks[0]
            draw.draw_landmarks(frame, hand, mp_hands.HAND_CONNECTIONS)
            h, w, _ = frame.shape
            thumb, index = hand.landmark[4], hand.landmark[8]
            pinch = distance(thumb, index, w, h)
            x1, y1 = int(thumb.x * w), int(thumb.y * h)
            x2, y2 = int(index.x * w), int(index.y * h)

            if is_fist(hand) and now - last_fist_time > 0.8:
                muted = not muted
                volume.SetMute(muted, None)
                last_fist_time = now

            if muted:
                gesture = "MUTED - open hand to control volume"
            else:
                normalized = clamp(
                    (pinch - MIN_PINCH_DISTANCE) / (MAX_PINCH_DISTANCE - MIN_PINCH_DISTANCE),
                    0.0, 1.0,
                )
                target_percent = normalized * 100
                current_percent += (target_percent - current_percent) * SMOOTHING
                db = min_db + (current_percent / 100) * (max_db - min_db)
                volume.SetMasterVolumeLevel(db, None)
                gesture = f"Volume control: {round(current_percent)}%"
                cv2.line(frame, (x1, y1), (x2, y2), (0, 255, 0), 3)
                cv2.circle(frame, (x1, y1), 9, (255, 0, 255), -1)
                cv2.circle(frame, (x2, y2), 9, (255, 0, 255), -1)

            percent = 0 if muted else round(current_percent)
            bar_x, bar_y, bar_w, bar_h = 45, 120, 38, 380
            fill_h = int(bar_h * percent / 100)
            cv2.rectangle(frame, (bar_x, bar_y), (bar_x + bar_w, bar_y + bar_h), (255, 255, 255), 2)
            cv2.rectangle(frame, (bar_x, bar_y + bar_h - fill_h), (bar_x + bar_w, bar_y + bar_h), (0, 255, 0), -1)
            cv2.putText(frame, f"{percent}%", (35, 540), cv2.FONT_HERSHEY_SIMPLEX, 0.8, (255, 255, 255), 2)

        cv2.putText(frame, "GESTURE VOLUME CONTROL", (30, 48), cv2.FONT_HERSHEY_SIMPLEX, 1.0, (255, 255, 255), 2)
        cv2.putText(frame, gesture, (30, 590), cv2.FONT_HERSHEY_SIMPLEX, 0.78, (255, 255, 255), 2)
        cv2.putText(frame, f"FPS: {fps:.0f}", (30, 625), cv2.FONT_HERSHEY_SIMPLEX, 0.62, (200, 200, 200), 2)
        cv2.putText(frame, "Pinch = volume | Fist = mute | ESC = exit", (30, 665), cv2.FONT_HERSHEY_SIMPLEX, 0.62, (200, 200, 200), 2)

        cv2.imshow("Gesture Volume Control", frame)
        if cv2.waitKey(1) & 0xFF == 27:
            break

    cap.release()
    hands.close()
    cv2.destroyAllWindows()


if __name__ == "__main__":
    main()
