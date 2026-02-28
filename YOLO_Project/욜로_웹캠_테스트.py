from ultralytics import YOLO
import cv2, time, numpy as np

MODEL = '/Users/idohun/WorkSpace/CNN/YOLO_Project/weights_sign_current.pt'
CONF, IOU, MAXDET, IMSZ = 0.45, 0.60, 100, 1024   # confidence 0.45 적용

det = YOLO(MODEL)

cap = cv2.VideoCapture(0)
assert cap.isOpened(), "웹캠을 열 수 없습니다. macOS 카메라 권한을 확인하세요."

t0, frames = time.time(), 0
while True:
    ok, frame = cap.read()
    if not ok: break

    res = det(frame, imgsz=IMSZ, conf=CONF, iou=IOU, max_det=MAXDET, device='mps', verbose=False)[0]
    boxes = res.boxes.xyxy.cpu().numpy() if res.boxes is not None else np.empty((0,4))
    scores = res.boxes.conf.cpu().numpy() if res.boxes is not None else np.empty((0,))

    im = frame.copy()

    for (x1, y1, x2, y2), score in zip(boxes, scores):
        p1, p2 = (int(x1), int(y1)), (int(x2), int(y2))
        label = f"sign {score:.2f}"
        cv2.rectangle(im, p1, p2, (0, 255, 0), 2)
        cv2.putText(im, label, (p1[0], max(0, p1[1] - 8)),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 255, 0), 2)

    frames += 1
    if frames % 30 == 0:
        fps = frames / (time.time() - t0)
        cv2.putText(im, f"FPS: {fps:.1f}", (10, 30),
                    cv2.FONT_HERSHEY_SIMPLEX, 1, (0, 255, 0), 2)

    cv2.imshow('YOLO Live (ESC to quit)', im)
    if cv2.waitKey(1) & 0xFF == 27:
        break

cap.release()
cv2.destroyAllWindows()