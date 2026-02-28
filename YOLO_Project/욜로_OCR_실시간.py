 # -*- coding: utf-8 -*-
"""
YOLO + Async OCR (EasyOCR) pipeline
- YOLO detects text regions
- Adjacent boxes are merged to one group (connect split detections)
- IOU tracker stabilizes boxes; only stable boxes are OCR'ed once
- OCR runs in background worker threads and is throttled to keep FPS high
- Duplicate text suppression: recent same text is skipped
"""

import os
import time
import threading
from collections import deque
from queue import Queue
from typing import List, Tuple, Dict

import numpy as np
import cv2
from ultralytics import YOLO

# --- Unicode label (Korean) via PIL ---
try:
    from PIL import ImageFont, ImageDraw, Image
    HAS_PIL = True
except Exception:
    HAS_PIL = False

# --------- OCR (EasyOCR) ----------
try:
    import easyocr  # type: ignore
    HAS_EASYOCR = True
except Exception:
    HAS_EASYOCR = False
    easyocr = None  # type: ignore

# --------- OCR (PaddleOCR) ----------
try:
    from paddleocr import PaddleOCR  # type: ignore
    HAS_PADDLE = True
except Exception:
    HAS_PADDLE = False
    PaddleOCR = None  # type: ignore

# ================== CONFIG ==================
MODEL = '/Users/idohun/WorkSpace/CNN/YOLO_Project/sign_v8s_896_mobile.pt'

# Camera & inference
CAM_WIDTH = 1280
CAM_HEIGHT = 720
DETECT_IMG_SIZE = 512        # smaller for higher FPS

# Detection filter / merging
CONF_THRESH = 0.60           # filter low conf boxes
MERGE_GAP = 20               # px: connect close boxes (word grouping)
MERGE_IOU = 0.10             # small overlap also merges
VERTICAL_OVERLAP_MIN = 0.55  # share same line if vertical overlap >= this

# Post-merge suppression of nested/duplicate boxes
SUPPRESS_CONTAINED = True
CONTAINMENT_IOU = 0.50        # if IoU >= this, keep larger only
CONTAINMENT_MARGIN = 4         # px margin for full-containment test

# Tracking (stabilize)
STABLE_MS = 300              # ms to consider track stable
TRACK_TTL_MS = 4500          # ms to keep lost tracks
IOU_MATCH_TH = 0.35           # IOU threshold for track matching

# OCR (EasyOCR)
OCR_ENABLED = True  # enable; backend availability checked below
OCR_LANGS = ['ko', 'en']     # Korean + English
# OCR backend: 'paddle' or 'easyocr'
OCR_BACKEND = 'paddle' if HAS_PADDLE else ('easyocr' if HAS_EASYOCR else 'none')
# Always prefer PaddleOCR if present
if OCR_BACKEND != 'paddle' and HAS_PADDLE:
    OCR_BACKEND = 'paddle'
OCR_WORKERS = 2
OCR_MAX_QUEUE = 8
OCR_PAD = 30                 # crop pad (px)
MIN_OCR_W = 20
MIN_OCR_H = 16
MIN_OCR_AREA = 900           # skip tiny crops (px^2)
UPSCALE_MAX = 1024           # up-scale long side to this (max) for OCR
MIN_REC_SCORE = 0.10         # accept low scores (we also de-dup later)
# Expand merged boxes by this percent of width/height (visual + OCR crop)
BOX_EXPAND_PCT = 0.30
# Larger visual boxes help readability; OCR crop also uses expanded region

# OCR candidate generation (lean for realtime)
ANGLES = [-4, 0, 4]          # small rotation sweep
PREPROCESS_OCR = True        # CLAHE + sharpen + adaptive thresh
TRY_INVERT = True            # try negative as alternative

 # Throttling
PER_FRAME_OCR_LIMIT = 3      # max crops sent per frame
GLOBAL_OCR_RATE_MS = 120     # min ms between enqueue bursts
# If a job stays pending too long, mark as done to avoid stuck yellow
PENDING_TIMEOUT_MS = 2000

#
# Sidebar (turned off to reduce render cost)
SHOW_SIDEBAR = False  # NOTE: high render cost, keep off in realtime

# Fonts for Unicode labels (macOS candidates)
FONT_PATHS = [
    "/System/Library/Fonts/AppleSDGothicNeo.ttc",
    "/Library/Fonts/AppleGothic.ttf",
    "/System/Library/Fonts/Supplemental/NotoSansKR-Regular.otf",
]
FONT_SIZE = 18

# Save options (optional)
SAVE_ON_OCR = False
SAVE_DIR = '/Users/idohun/WorkSpace/CNN/YOLO_Project/captures'

# Duplicate suppression
DEDUP_WINDOW_MS = 5000       # don't re-report same text within this window
DEDUP_MAX_ITEMS = 200

# Debug
DEBUG_OCR_LOG = False

# =====================================================

# -------------------- Utils: geometry ----------------
def iou(a: Tuple[int,int,int,int], b: Tuple[int,int,int,int]) -> float:
    ax1, ay1, ax2, ay2 = a
    bx1, by1, bx2, by2 = b
    xA = max(ax1, bx1)
    yA = max(ay1, by1)
    xB = min(ax2, bx2)
    yB = min(ay2, by2)
    interW = max(0, xB - xA)
    interH = max(0, yB - yA)
    inter = interW * interH
    if inter <= 0:
        return 0.0
    areaA = max(0, ax2 - ax1) * max(0, ay2 - ay1)
    areaB = max(0, bx2 - bx1) * max(0, by2 - by1)
    denom = float(areaA + areaB - inter)
    return inter / denom if denom > 0 else 0.0

def vertical_overlap_ratio(a, b):
    ay1, ay2 = a[1], a[3]
    by1, by2 = b[1], b[3]
    interH = max(0, min(ay2, by2) - max(ay1, by1))
    ha = max(1, ay2 - ay1)
    hb = max(1, by2 - by1)
    return interH / float(min(ha, hb))

def boxes_close(a, b, gap=MERGE_GAP):
    # horizontally close + enough vertical overlap OR small IoU
    ax1, ay1, ax2, ay2 = a
    bx1, by1, bx2, by2 = b
    horiz_gap = max(0, max(bx1 - ax2, ax1 - bx2))
    vov = vertical_overlap_ratio(a, b)
    return (vov >= VERTICAL_OVERLAP_MIN and horiz_gap <= gap) or (iou(a, b) >= MERGE_IOU)

def merge_two(a, b):
    return (min(a[0], b[0]), min(a[1], b[1]), max(a[2], b[2]), max(a[3], b[3]))

def merge_adjacent_boxes(boxes: List[Tuple[int,int,int,int]],
                         gap: int = MERGE_GAP, min_iou: float = MERGE_IOU) -> List[Tuple[int,int,int,int]]:
    if not boxes:
        return []
    merged = boxes[:]
    changed = True
    while changed:
        changed = False
        out = []
        used = [False]*len(merged)
        for i in range(len(merged)):
            if used[i]: continue
            cur = merged[i]
            for j in range(i+1, len(merged)):
                if used[j]: continue
                if boxes_close(cur, merged[j], gap) or iou(cur, merged[j]) >= min_iou:
                    cur = merge_two(cur, merged[j]); used[j] = True; changed = True
            used[i] = True; out.append(cur)
        merged = out
    return merged

# --- Suppress nested/duplicate boxes ---
def is_contained(inner, outer, margin=CONTAINMENT_MARGIN):
    x1, y1, x2, y2 = inner
    X1, Y1, X2, Y2 = outer
    return (x1 >= X1 - margin and y1 >= Y1 - margin and x2 <= X2 + margin and y2 <= Y2 + margin)

def suppress_nested_boxes(boxes: List[Tuple[int,int,int,int]]) -> List[Tuple[int,int,int,int]]:
    if not boxes:
        return []
    keep = [True] * len(boxes)
    areas = [(b[2]-b[0])*(b[3]-b[1]) for b in boxes]
    for i in range(len(boxes)):
        if not keep[i]:
            continue
        for j in range(i+1, len(boxes)):
            if not keep[j]:
                continue
            bi, bj = boxes[i], boxes[j]
            iou_ij = iou(bi, bj)
            if is_contained(bi, bj) or is_contained(bj, bi) or iou_ij >= CONTAINMENT_IOU:
                # drop the smaller one
                if areas[i] >= areas[j]:
                    keep[j] = False
                else:
                    keep[i] = False
                    break
    return [b for k, b in enumerate(boxes) if keep[k]]

# NMS to keep only larger boxes among overlaps
def nms_keep_larger(boxes: List[Tuple[int,int,int,int]], iou_th: float = 0.55) -> List[Tuple[int,int,int,int]]:
    if not boxes:
        return []
    areas = np.array([(b[2]-b[0])*(b[3]-b[1]) for b in boxes], dtype=np.float32)
    order = np.argsort(-areas)  # large first
    keep_idx = []
    while order.size > 0:
        i = order[0]
        keep_idx.append(i)
        rest = order[1:]
        survivors = []
        for j in rest:
            if iou(boxes[i], boxes[j]) < iou_th:
                survivors.append(j)
        order = np.array(survivors, dtype=np.int64)
    return [boxes[i] for i in keep_idx]

# Helper to expand a box by pct of width/height and clamp to image bounds
def expand_box(box: Tuple[int,int,int,int], w: int, h: int, pct: float) -> Tuple[int,int,int,int]:
    x1, y1, x2, y2 = box
    bw, bh = (x2 - x1), (y2 - y1)
    dx = int(bw * pct)
    dy = int(bh * pct)
    nx1 = max(0, x1 - dx)
    ny1 = max(0, y1 - dy)
    nx2 = min(w - 1, x2 + dx)
    ny2 = min(h - 1, y2 + dy)
    # ensure valid
    if nx2 <= nx1: nx2 = min(w - 1, nx1 + max(1, bw))
    if ny2 <= ny1: ny2 = min(h - 1, ny1 + max(1, bh))
    return (nx1, ny1, nx2, ny2)

# -------------------- Tracker ------------------------
class SimpleTracker:
    """ IOU-based tracker. Stable tracks get OCR once. """
    def __init__(self):
        self.tracks: Dict[int, Dict] = {}
        self.next_id = 1

    def _prune(self):
        now = int(time.time()*1000)
        gone = [tid for tid,t in self.tracks.items() if now - t['last_seen'] > TRACK_TTL_MS]
        for tid in gone:
            del self.tracks[tid]

    def _match(self, dets: List[Tuple[int,int,int,int]]):
        assigned = {}
        used = set()
        for tid, t in self.tracks.items():
            best_iou, best_j = 0.0, -1
            for j, d in enumerate(dets):
                if j in used: continue
                val = iou(t['box'], d)
                if val > best_iou: best_iou, best_j = val, j
            if best_iou >= IOU_MATCH_TH and best_j >= 0:
                assigned[tid] = best_j; used.add(best_j)
        return assigned, used

    def update(self, dets: List[Tuple[int,int,int,int]]):
        self._prune()
        now = int(time.time()*1000)
        mapping, used = self._match(dets)
        for tid, j in mapping.items():
            self.tracks[tid]['box'] = dets[j]
            self.tracks[tid]['last_seen'] = now
            self.tracks[tid]['hits'] = self.tracks[tid].get('hits', 0) + 1
        for j, d in enumerate(dets):
            if j in used:
                continue
            # skip creating near-duplicate tracks (high IoU with any existing track)
            dup = False
            for tt in self.tracks.values():
                if iou(d, tt['box']) >= 0.70:
                    dup = True
                    break
            if dup:
                continue
            self.tracks[self.next_id] = {
                'box': d, 'first_seen': now, 'last_seen': now,
                'pending': False, 'ocr_done': False, 'text': None, 'last_ocr_try': 0,
                'hits': 1
            }
            self.next_id += 1

    def ready(self, tid: int) -> bool:
        t = self.tracks[tid]
        now = int(time.time()*1000)
        stable_time = (t['last_seen'] - t['first_seen'] >= max(200, STABLE_MS))
        stable_hits = (t.get('hits', 0) >= 2)
        cooldown = (now - t.get('last_ocr_try', 0) >= 200)
        return (not t['ocr_done']) and (not t['pending']) and (stable_time or stable_hits) and cooldown

    def mark_done(self, tid: int, text: str):
        self.tracks[tid]['ocr_done'] = True
        self.tracks[tid]['text'] = text

# -------------------- OCR Helpers (EasyOCR) --------------------

def preprocess_bgr(img_bgr: np.ndarray) -> np.ndarray:
    gray = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2GRAY)
    clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8,8))
    eq = clahe.apply(gray)
    blur = cv2.GaussianBlur(eq, (0,0), 1.0)
    sharp = cv2.addWeighted(eq, 1.5, blur, -0.5, 0)
    th = cv2.adaptiveThreshold(sharp, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C,
                               cv2.THRESH_BINARY, 21, 8)
    return cv2.cvtColor(th, cv2.COLOR_GRAY2BGR)

def rotate_bound(img: np.ndarray, angle: float) -> np.ndarray:
    (h, w) = img.shape[:2]
    center = (w // 2, h // 2)
    M = cv2.getRotationMatrix2D(center, angle, 1.0)
    cos = abs(M[0, 0]); sin = abs(M[0, 1])
    nW = int((h * sin) + (w * cos))
    nH = int((h * cos) + (w * sin))
    M[0, 2] += (nW / 2) - center[0]
    M[1, 2] += (nH / 2) - center[1]
    return cv2.warpAffine(img, M, (nW, nH), flags=cv2.INTER_CUBIC, borderMode=cv2.BORDER_REPLICATE)


def merge_easyocr_line(res) -> Tuple[str, float]:
    """Merge EasyOCR pieces into one line: left->right, average score."""
    if not res:
        return '', 0.0
    try:
        # sort by left x of bbox
        sorted_res = sorted(res, key=lambda x: x[0][0][0])
        texts = [str(item[1] or '') for item in sorted_res]
        scores = [float(item[2]) if item[2] is not None else 0.0 for item in sorted_res]
        merged_text = ''.join(texts)
        avg_score = (sum(scores) / len(scores)) if scores else 0.0
        return merged_text, avg_score
    except Exception:
        return '', 0.0


def build_variants(bgr: np.ndarray) -> List[Tuple[str, np.ndarray]]:
    variants: List[Tuple[str, np.ndarray]] = []
    base = bgr
    proc = preprocess_bgr(bgr) if PREPROCESS_OCR else bgr
    variants.append(("orig", base))
    if PREPROCESS_OCR:
        variants.append(("proc", proc))
    if TRY_INVERT:
        variants.append(("orig_inv", cv2.bitwise_not(base)))
        if PREPROCESS_OCR:
            variants.append(("proc_inv", cv2.bitwise_not(proc)))
    return variants


def run_candidates(reader, bgr: np.ndarray) -> Tuple[str, float]:
    """Run EasyOCR on multiple variants/angles and return best merged line."""
    best_text, best_score = '', 0.0
    for tag, img in build_variants(bgr):
        for ang in ANGLES:
            img_rot = rotate_bound(img, ang) if ang != 0 else img
            rgb = cv2.cvtColor(img_rot, cv2.COLOR_BGR2RGB)
            res = reader.readtext(rgb, detail=1, paragraph=False)
            merged_text, avg_score = merge_easyocr_line(res)
            if merged_text and avg_score > best_score:
                best_text, best_score = merged_text, avg_score
    return best_text, best_score

# -------------------- Global OCR infra ---------------
ocr_reader = None
if OCR_ENABLED and (OCR_BACKEND != 'none'):
    try:
        if OCR_BACKEND == 'paddle' and HAS_PADDLE:
            try:
                ocr_reader = PaddleOCR(use_textline_orientation=True, lang='korean', show_log=False)
            except TypeError:
                ocr_reader = PaddleOCR(use_angle_cls=True, lang='korean')
            print('[INFO] PaddleOCR ready.')
        elif OCR_BACKEND == 'easyocr' and HAS_EASYOCR:
            print('[INFO] Loading EasyOCR ...')
            ocr_reader = easyocr.Reader(OCR_LANGS, gpu=False, verbose=False)
            print('[INFO] EasyOCR ready.')
        else:
            print('[WARN] No OCR backend available. OCR disabled.')
            OCR_ENABLED = False
    except Exception as e:
        print(f'[WARN] OCR disabled: {e}')
        OCR_ENABLED = False

ocr_queue: Queue[Tuple[int, np.ndarray]] = Queue(maxsize=OCR_MAX_QUEUE)
tracker_lock = threading.Lock()
_last_enqueue_ts = 0  # global rate limiter
 # ---- PaddleOCR helpers ----
def parse_paddle_result(res):
    pairs = []
    if res is None:
        return pairs
    if isinstance(res, dict) and 'data' in res:
        res = res.get('data')
    if isinstance(res, list):
        if not res:
            return pairs
        if isinstance(res[0], dict):
            for d in res:
                txt = d.get('text', d.get('transcription', ''))
                sc = d.get('score', 0.0) or 0.0
                try:
                    sc = float(sc)
                except Exception:
                    sc = 0.0
                pairs.append((str(txt or ''), sc))
        elif isinstance(res[0], list):
            for line in res[0]:
                if isinstance(line, (list, tuple)) and len(line) >= 2:
                    meta = line[1]
                    if isinstance(meta, (list, tuple)) and len(meta) >= 2:
                        pairs.append((str(meta[0] or ''), float(meta[1] or 0.0)))
    return pairs

def paddle_run(ocr, rgb, rec_only=True):
    try:
        if rec_only:
            return ocr.predict(rgb, det=False, rec=True)
        else:
            return ocr.predict(rgb)
    except AttributeError:
        if rec_only:
            return ocr.ocr(rgb, det=False, rec=True)
        else:
            return ocr.ocr(rgb)

def run_ocr_backend(crop_bgr: np.ndarray) -> Tuple[str, float]:
    # Resize up to UPSCALE_MAX
    h, w = crop_bgr.shape[:2]
    long_side = max(h, w)
    if long_side > 0 and long_side < UPSCALE_MAX:
        scale = UPSCALE_MAX / long_side
        crop_bgr = cv2.resize(crop_bgr, (int(w*scale), int(h*scale)), interpolation=cv2.INTER_CUBIC)

    if OCR_BACKEND == 'paddle' and HAS_PADDLE and ocr_reader is not None:
        rgb = cv2.cvtColor(crop_bgr, cv2.COLOR_BGR2RGB)
        res = paddle_run(ocr_reader, rgb, rec_only=True)
        pairs = parse_paddle_result(res)
        if not pairs or not any(t for t,_ in pairs):
            res = paddle_run(ocr_reader, rgb, rec_only=False)
            pairs = parse_paddle_result(res)
        valid = [(t, s) for (t, s) in pairs if t and float(s) >= MIN_REC_SCORE]
        if valid:
            text = ''.join([t for (t, _) in valid])
            score = float(sum(s for (_, s) in valid)) / len(valid)
            return text, score
        return '', 0.0

    if OCR_BACKEND == 'easyocr' and HAS_EASYOCR and ocr_reader is not None:
        return run_candidates(ocr_reader, crop_bgr)

    return '', 0.0

# Duplicate suppression store
recent_texts = deque(maxlen=DEDUP_MAX_ITEMS)  # holds (text, ts)

def is_duplicate(text: str) -> bool:
    if not text:
        return False
    now = int(time.time()*1000)
    # prune old
    while recent_texts and now - recent_texts[0][1] > DEDUP_WINDOW_MS:
        recent_texts.popleft()
    for t, ts in recent_texts:
        if t == text and now - ts <= DEDUP_WINDOW_MS:
            return True
    return False


def ocr_worker_loop():
    while True:
        # gather up to 4 jobs per loop for better locality
        jobs = []
        try:
            tid, crop = ocr_queue.get(timeout=0.2)
            jobs.append((tid, crop))
            for _ in range(3):
                try:
                    jobs.append(ocr_queue.get_nowait())
                except Exception:
                    break
        except Exception:
            continue

        for (tid, crop) in jobs:
            text = ''
            try:
                if crop is not None and crop.size > 0 and ocr_reader is not None:
                    best_text, best_score = run_ocr_backend(crop)
                    if best_text and best_score >= MIN_REC_SCORE:
                        text = best_text
                        # duplicate suppression
                        if is_duplicate(text):
                            text = ''  # suppress
                        else:
                            recent_texts.append((text, int(time.time()*1000)))
            except Exception as e:
                if DEBUG_OCR_LOG:
                    print(f"[DBG] OCR worker error: {e}")
            finally:
                with tracker_lock:
                    if 'tracker' in globals() and tid in tracker.tracks:
                        tracker.tracks[tid]['pending'] = False
                        # Mark as done regardless of text so color changes to orange once OCR attempted
                        tracker.tracks[tid]['ocr_done'] = True
                        if text:
                            tracker.tracks[tid]['text'] = text
                            if SAVE_ON_OCR:
                                try:
                                    os.makedirs(SAVE_DIR, exist_ok=True)
                                    ts = int(time.time()*1000)
                                    path = os.path.join(SAVE_DIR, f"ocr_{tid}_{ts}.jpg")
                                    cv2.imwrite(path, crop)
                                except Exception:
                                    pass
                ocr_queue.task_done()

if OCR_ENABLED and ocr_reader is not None:
    for _ in range(OCR_WORKERS):
        t = threading.Thread(target=ocr_worker_loop, daemon=True)
        t.start()

# -------------------- Sidebar (optional, off by default) ------------------------
def wrap_text(text: str, n: int) -> List[str]:
    out = []
    s = text.strip()
    while len(s) > n:
        out.append(s[:n]); s = s[n:]
    if s: out.append(s)
    return out

def render_sidebar(h: int, tracker: SimpleTracker, width: int = 360, max_lines: int = 18) -> np.ndarray:
    panel = np.zeros((h, width, 3), dtype=np.uint8)
    panel[:] = (20,20,20)
    y, pad, lh = 24, 10, 22
    cv2.putText(panel, 'OCR Results', (pad, y), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (230,230,230), 2)
    y += int(lh*1.2)
    items = []
    with tracker_lock:
        for tid, t in tracker.tracks.items():
            if t.get('text'):
                items.append((t['last_seen'], tid, t['text']))
    items.sort(key=lambda x:x[0], reverse=True)
    shown = 0
    for _, tid, txt in items:
        if shown >= max_lines: break
        cv2.putText(panel, f"ID {tid}", (pad, y), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (180,200,255), 2); y += lh; shown += 1
        for seg in wrap_text(txt, 28):
            if shown >= max_lines: break
            cv2.putText(panel, seg, (pad, y), cv2.FONT_HERSHEY_SIMPLEX, 0.55, (220,220,220), 1); y += lh; shown += 1
        y += 6
    if shown == 0:
        cv2.putText(panel, '— (no OCR yet) —', (pad, y), cv2.FONT_HERSHEY_SIMPLEX, 0.55, (150,150,150), 1)
    return panel

# ===================== Unicode label helpers ==========================
def has_non_ascii(s: str) -> bool:
    try:
        s.encode("ascii")
        return False
    except Exception:
        return True

def draw_text_unicode(img_bgr: np.ndarray, text: str, x: int, y: int, color=(0,200,255)):
    # PIL이 없거나 폰트가 없으면 OpenCV로 폴백
    if not HAS_PIL:
        cv2.putText(img_bgr, text, (x, y), cv2.FONT_HERSHEY_SIMPLEX, 0.6, color, 2)
        return img_bgr
    font_path = None
    for p in FONT_PATHS:
        if os.path.isfile(p):
            font_path = p
            break
    if font_path is None:
        cv2.putText(img_bgr, text, (x, y), cv2.FONT_HERSHEY_SIMPLEX, 0.6, color, 2)
        return img_bgr
    # PIL로 그리기 (BGR→RGB→BGR)
    img_rgb = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2RGB)
    pil_img = Image.fromarray(img_rgb)
    draw = ImageDraw.Draw(pil_img)
    font = ImageFont.truetype(font_path, FONT_SIZE)
    draw.text((x, y), text, font=font, fill=(color[2], color[1], color[0]))
    return cv2.cvtColor(np.array(pil_img), cv2.COLOR_RGB2BGR)

# ===================== MAIN ==========================

def main():
    global tracker, _last_enqueue_ts
    # Model
    model = YOLO(MODEL)

    # Camera
    cap = cv2.VideoCapture(0)
    cap.set(cv2.CAP_PROP_FRAME_WIDTH, CAM_WIDTH)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, CAM_HEIGHT)
    cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)
    cv2.setUseOptimized(True)

    if not cap.isOpened():
        print("❌ 카메라를 열 수 없습니다.")
        return

    os.makedirs(SAVE_DIR, exist_ok=True)

    # Tracker
    tracker = SimpleTracker()

    print("\n=== YOLO + Async EasyOCR Preview ===")
    start_time = time.time()
    frame_count = 0

    while True:
        ok, frame = cap.read()
        if not ok:
            break
        frame_count += 1

        # Run detection directly on original frame (Ultralytics handles letterbox internally)
        try:
            results = model.predict(source=frame, conf=CONF_THRESH, iou=0.5,
                                    imgsz=DETECT_IMG_SIZE, device=None, verbose=False)
        except Exception as e:
            print(f"[ERROR] YOLO predict: {e}")
            break

        # Build detections (already in original image coordinates)
        det_boxes: List[Tuple[int,int,int,int]] = []
        if results and len(results) > 0 and getattr(results[0], 'boxes', None) is not None:
            try:
                boxes_xyxy = results[0].boxes.xyxy.cpu().numpy()
                confs = results[0].boxes.conf.cpu().numpy()
            except Exception:
                boxes_xyxy = np.zeros((0, 4), dtype=np.float32)
                confs = np.zeros((0,), dtype=np.float32)

            for box, conf in zip(boxes_xyxy, confs):
                if conf <= CONF_THRESH:
                    continue
                x1, y1, x2, y2 = map(int, box)
                if x2 > x1 and y2 > y1:
                    det_boxes.append((x1, y1, x2, y2))

        # Merge & Track (connect split boxes into a single group)
        merged = merge_adjacent_boxes(det_boxes, gap=MERGE_GAP * 2, min_iou=MERGE_IOU * 1.5)
        if SUPPRESS_CONTAINED:
            merged = suppress_nested_boxes(merged)
        # final NMS to drop overlaps, keep larger
        merged = nms_keep_larger(merged, iou_th=0.55)
        tracker.update(merged)

        display = frame.copy()

        # Enqueue OCR for stable tracks (non-blocking, throttled)
        if OCR_ENABLED and ocr_reader is not None:
            now_ms = int(time.time()*1000)
            if now_ms - _last_enqueue_ts >= GLOBAL_OCR_RATE_MS:
                h, w = display.shape[:2]
                sent = 0
                enq_count = 0
                with tracker_lock:
                    items = sorted(list(tracker.tracks.items()), key=lambda kv: kv[1]['last_seen'], reverse=True)
                    for tid, t in items:
                        if sent >= PER_FRAME_OCR_LIMIT:
                            break
                        if not tracker.ready(tid):
                            continue
                        x1, y1, x2, y2 = t['box']
                        ww, hh = max(0, x2 - x1), max(0, y2 - y1)
                        if ww*hh < MIN_OCR_AREA or ww < MIN_OCR_W or hh < MIN_OCR_H:
                            continue
                        # expand base box first
                        ex1, ey1, ex2, ey2 = expand_box((x1, y1, x2, y2), w, h, BOX_EXPAND_PCT)
                        # then apply small extra pad
                        xx1, yy1 = max(0, ex1 - OCR_PAD), max(0, ey1 - OCR_PAD)
                        xx2, yy2 = min(w-1, ex2 + OCR_PAD), min(h-1, ey2 + OCR_PAD)
                        if xx2 <= xx1 or yy2 <= yy1:
                            continue
                        # Crop strictly inside the (expanded + padded) detection box for OCR
                        crop = display[yy1:yy2, xx1:xx2]
                        # Drop oldest if queue is full
                        if ocr_queue.full():
                            try:
                                drop_tid, _ = ocr_queue.get_nowait()
                                if drop_tid in tracker.tracks:
                                    tracker.tracks[drop_tid]['pending'] = False
                                ocr_queue.task_done()
                            except Exception:
                                pass
                        try:
                            ocr_queue.put_nowait((tid, crop))
                            tracker.tracks[tid]['pending'] = True
                            tracker.tracks[tid]['last_ocr_try'] = now_ms
                            sent += 1
                            enq_count = sent
                        except Exception:
                            tracker.tracks[tid]['pending'] = False
                if sent > 0:
                    _last_enqueue_ts = now_ms

        # Draw
        with tracker_lock:
            for tid, t in tracker.tracks.items():
                now_ms = int(time.time()*1000)
                if t.get('pending') and (now_ms - t.get('last_ocr_try', 0) > PENDING_TIMEOUT_MS):
                    t['pending'] = False
                    t['ocr_done'] = True
                x1, y1, x2, y2 = t['box']
                H, W = display.shape[:2]
                ex1, ey1, ex2, ey2 = expand_box((x1, y1, x2, y2), W, H, BOX_EXPAND_PCT)
                color = (0, 255, 0)  # default green
                if t.get('pending') and not t.get('ocr_done'):
                    color = (0, 255, 255)  # pending yellow
                elif t.get('ocr_done'):
                    color = (0, 140, 255)  # done orange (even if no text)
                cv2.rectangle(display, (ex1, ey1), (ex2, ey2), color, 2)
                label = f"ID:{tid}"
                if t.get('text'):
                    label += f" | {t['text'][:12]}"
                elif t.get('ocr_done'):
                    label += " | (no text)"
                label_y = max(16, y1 - 8)
                if has_non_ascii(label):
                    display = draw_text_unicode(display, label, x1, label_y, color=color)
                else:
                    cv2.putText(display, label, (x1, label_y), cv2.FONT_HERSHEY_SIMPLEX, 0.6, color, 2)

        # Sidebar compose
        composed = display
        if SHOW_SIDEBAR:
            sidebar = render_sidebar(display.shape[0], tracker, width=340, max_lines=18)
            if sidebar.shape[0] != display.shape[0]:
                sidebar = cv2.resize(sidebar, (340, display.shape[0]))
            composed = np.hstack([display, sidebar])

        # FPS
        tnow = time.time()
        fps = frame_count / max(1e-6, (tnow - start_time))
        cv2.putText(composed, f"FPS: {fps:.1f}", (10, 28), cv2.FONT_HERSHEY_SIMPLEX, 0.8, (255,255,255), 2)
        # ENQ counter (debug)
        try:
            enq_count
        except NameError:
            enq_count = 0
        cv2.putText(composed, f"ENQ: {enq_count}", (10, 54), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0,255,255), 2)

        cv2.imshow("YOLO + OCR", composed)
        if cv2.waitKey(1) & 0xFF == 27:  # ESC
            break

    cap.release()
    cv2.destroyAllWindows()

if __name__ == "__main__":
    main()