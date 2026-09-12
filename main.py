import math
import cv2
import mediapipe as mp
from pycaw.pycaw import AudioUtilities, IAudioEndpointVolume
from comtypes import CLSCTX_ALL


def clamp(value, low, high):
    return max(low, min(high, value))


def main():
    devices = AudioUtilities.GetSpeakers()
    interface = devices.Activate(IAudioEndpointVolume._iid_, CLSCTX_ALL, None)
    volume = interface.QueryInterface(IAudioEndpointVolume)
    min_db, max_db, _ = volume.GetVolumeRange()

    cap = cv2.VideoCapture(0)
    cap.set(cv2.CAP_PROP_FRAME_WIDTH, 1280)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 720)

    mp_hands = mp.solutions.hands
    hands = mp_hands.Hands(
        static_image_mode=False,
        max_num_hands=1,
        min_detection_confidence=0.6,
        min_tracking_confidence=0.6,
    )
    draw = mp.solutions.drawing_utils

    while True:
        ok, frame = cap.read()
        if not ok:
            break

        frame = cv2.flip(frame, 1)
        rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
        result = hands.process(rgb)
        gesture = "No hand detected"

        if result.multi_hand_landmarks:
            hand = result.multi_hand_landmarks[0]
            draw.draw_landmarks(frame, hand, mp_hands.HAND_CONNECTIONS)

            h, w, _ = frame.shape
            thumb = hand.landmark[4]
            index = hand.landmark[8]
            x1, y1 = int(thumb.x * w), int(thumb.y * h)
            x2, y2 = int(index.x * w), int(index.y * h)
            distance = math.hypot(x2 - x1, y2 - y1)

            # 30 px = minimum volume, 220 px = maximum volume.
            normalized = clamp((distance - 30) / 190, 0.0, 1.0)
            db = min_db + normalized * (max_db - min_db)
            volume.SetMasterVolumeLevel(db, None)
            percent = round(normalized * 100)
            gesture = f"Volume control: {percent}%"

            cv2.line(frame, (x1, y1), (x2, y2), (0, 255, 0), 3)
            cv2.circle(frame, (x1, y1), 9, (255, 0, 255), -1)
            cv2.circle(frame, (x2, y2), 9, (255, 0, 255), -1)

            bar_x, bar_y, bar_w, bar_h = 40, 100, 35, 400
            fill_h = int(bar_h * normalized)
            cv2.rectangle(frame, (bar_x, bar_y), (bar_x + bar_w, bar_y + bar_h), (255, 255, 255), 2)
            cv2.rectangle(frame, (bar_x, bar_y + bar_h - fill_h), (bar_x + bar_w, bar_y + bar_h), (0, 255, 0), -1)
            cv2.putText(frame, f"{percent}%", (30, 535), cv2.FONT_HERSHEY_SIMPLEX, 0.8, (255, 255, 255), 2)

        cv2.putText(frame, "Gesture Volume Control", (30, 45), cv2.FONT_HERSHEY_SIMPLEX, 1.0, (255, 255, 255), 2)
        cv2.putText(frame, gesture, (30, 590), cv2.FONT_HERSHEY_SIMPLEX, 0.8, (255, 255, 255), 2)
        cv2.putText(frame, "Press ESC to exit", (30, 630), cv2.FONT_HERSHEY_SIMPLEX, 0.65, (200, 200, 200), 2)

        cv2.imshow("Gesture Volume Control", frame)
        if cv2.waitKey(1) & 0xFF == 27:
            break

    cap.release()
    hands.close()
    cv2.destroyAllWindows()


if __name__ == "__main__":
    main()
