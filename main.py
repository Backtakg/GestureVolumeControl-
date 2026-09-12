import math
import time
import cv2
import mediapipe as mp
from comtypes import CLSCTX_ALL
from pycaw.pycaw import AudioUtilities, IAudioEndpointVolume

CAMERA_INDEX = 0
FRAME_WIDTH = 1280
FRAME_HEIGHT = 720
MIN_PINCH_RATIO = 0.22
MAX_PINCH_RATIO = 1.45
SMOOTHING = 0.18


def clamp(value, low, high):
    return max(low, min(high, value))


def normalized_distance(a, b):
    return math.hypot(a.x - b.x, a.y - b.y)


def pinch_ratio(hand):
    thumb = hand.landmark[4]
    index = hand.landmark[8]
    index_mcp = hand.landmark[5]
    pinky_mcp = hand.landmark[17]
    palm_width = normalized_distance(index_mcp, pinky_mcp)
    if palm_width < 1e-6:
        return 0.0
    return normalized_distance(thumb, index) / palm_width


def draw_hand_overlay(frame, hand, draw):
    h, w, _ = frame.shape
    draw.draw_landmarks(
        frame,
        hand,
        mp.solutions.hands.HAND_CONNECTIONS,
        draw.DrawingSpec(color=(190, 190, 190), thickness=2, circle_radius=3),
        draw.DrawingSpec(color=(120, 120, 120), thickness=2),
    )

    thumb = hand.landmark[4]
    index = hand.landmark[8]
    tx, ty = int(thumb.x * w), int(thumb.y * h)
    ix, iy = int(index.x * w), int(index.y * h)

    cv2.line(frame, (tx, ty), (ix, iy), (0, 255, 0), 4)
    cv2.circle(frame, (tx, ty), 11, (255, 0, 255), -1)
    cv2.circle(frame, (ix, iy), 11, (255, 0, 255), -1)


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
    previous_time = time.perf_counter()
    fps = 0.0

    while True:
        ok, frame = cap.read()
        if not ok:
            break

        frame = cv2.flip(frame, 1)
        rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
        result = hands.process(rgb)

        now = time.perf_counter()
        elapsed = now - previous_time
        previous_time = now
        if elapsed > 0:
            fps = 0.9 * fps + 0.1 * (1 / elapsed)

        if result.multi_hand_landmarks:
            hand = result.multi_hand_landmarks[0]
            draw_hand_overlay(frame, hand, draw)

            ratio = pinch_ratio(hand)
            normalized = clamp(
                (ratio - MIN_PINCH_RATIO) / (MAX_PINCH_RATIO - MIN_PINCH_RATIO),
                0.0,
                1.0,
            )
            target_percent = normalized * 100
            current_percent += (target_percent - current_percent) * SMOOTHING
            db = min_db + (current_percent / 100) * (max_db - min_db)
            volume.SetMasterVolumeLevel(db, None)

            h, w, _ = frame.shape
            bar_x, bar_y, bar_w, bar_h = 45, 100, 38, 420
            percent = round(current_percent)
            fill_h = int(bar_h * percent / 100)
            cv2.rectangle(frame, (bar_x, bar_y), (bar_x + bar_w, bar_y + bar_h), (255, 255, 255), 2)
            cv2.rectangle(frame, (bar_x, bar_y + bar_h - fill_h), (bar_x + bar_w, bar_y + bar_h), (0, 255, 0), -1)
            cv2.circle(frame, (bar_x + bar_w // 2, bar_y + bar_h - fill_h), 9, (0, 255, 0), -1)

        cv2.imshow("Gesture Volume Control", frame)
        if cv2.waitKey(1) & 0xFF == 27:
            break

    cap.release()
    hands.close()
    cv2.destroyAllWindows()


if __name__ == "__main__":
    main()
