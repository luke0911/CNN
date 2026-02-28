# -*- coding: utf-8 -*-
"""
OCR_Check.py (EasyOCR 버전)
- 콘솔에서 이미지 경로를 입력받아 배치 OCR 실행
- EasyOCR 사용 (ko+en), 업스케일/전처리 옵션 제공
사용 예:
    $ python OCR_Check.py
    Enter image file paths (comma or space separated): captures/*.jpg /path/to/img.png
"""

import os
import sys
import glob
import shlex 
from typing import List, Tuple

import numpy as np

# ---- Tuning constants ----
MIN_REC_SCORE = 0.10      # 더 관대하게
UPSCALE_MAX = 1280        # 업스케일 상한 ↑
PREPROCESS = True         # 전처리 사용
ANGLES = [-8, -4, 0, 4, 8]  # 회전 스윕 각도(도)
TRY_INVERT = True         # 반전(네거티브)도 시도

try:
    import cv2
except Exception:
    cv2 = None

try:
    import easyocr
except Exception:
    print("[ERROR] easyocr 가 설치되어 있지 않습니다.\n  -> pip install easyocr")
    raise

# --------------- Utils ---------------

def parse_easyocr_result(res) -> List[Tuple[str, float]]:
    """EasyOCR 결과를 (text, score) 리스트로 통일"""
    out: List[Tuple[str, float]] = []
    if not res:
        return out
    for item in res:
        # 일반적으로 (bbox, text, conf)
        if isinstance(item, (list, tuple)):
            if len(item) >= 3:
                text = item[1]
                score = float(item[2]) if item[2] is not None else 0.0
                out.append((str(text or ''), score))
            elif len(item) == 2:
                # 드물게 (text, conf)
                text, score = item[0], float(item[1])
                out.append((str(text or ''), score))
    return out


def easyocr_run(reader: "easyocr.Reader", img_or_path):
    """EasyOCR 실행. ndarray(RGB/BGR) 또는 파일경로 처리."""
    # EasyOCR는 경로 문자열 또는 ndarray를 받음. ndarray는 RGB 권장.
    return reader.readtext(img_or_path, detail=1, paragraph=False)


def expand_paths(raw: str) -> List[str]:
    # 따옴표/공백 안전 분리 + 글로브 + 존재 파일만
    tokens = shlex.split(raw.replace(',', ' '))
    paths: List[str] = []
    for t in tokens:
        t = t.strip('\'"')
        t = os.path.expanduser(t)
        if not t:
            continue
        matches = glob.glob(t)
        if matches:
            paths.extend(matches)
        else:
            paths.append(t)
    paths = [p for p in paths if os.path.isfile(p)]
    # 중복 제거(입력 순서 유지)
    seen = set(); uniq = []
    for p in paths:
        if p not in seen:
            uniq.append(p); seen.add(p)
    return uniq


# 전처리: 그레이→CLAHE→언샤프→적응형 이진화

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
    # keep full image after rotation
    (h, w) = img.shape[:2]
    center = (w // 2, h // 2)
    M = cv2.getRotationMatrix2D(center, angle, 1.0)
    cos = abs(M[0, 0]); sin = abs(M[0, 1])
    nW = int((h * sin) + (w * cos))
    nH = int((h * cos) + (w * sin))
    M[0, 2] += (nW / 2) - center[0]
    M[1, 2] += (nH / 2) - center[1]
    return cv2.warpAffine(img, M, (nW, nH), flags=cv2.INTER_CUBIC, borderMode=cv2.BORDER_REPLICATE)

def build_variants(bgr: np.ndarray) -> list[tuple[str, np.ndarray]]:
    variants: list[tuple[str, np.ndarray]] = []
    # base variants
    base = bgr
    proc = preprocess_bgr(bgr) if PREPROCESS else bgr
    variants.append(("orig", base))
    if PREPROCESS:
        variants.append(("proc", proc))
    if TRY_INVERT:
        variants.append(("orig_inv", cv2.bitwise_not(base)))
        if PREPROCESS:
            variants.append(("proc_inv", cv2.bitwise_not(proc)))
    return variants

def run_candidates(reader, bgr: np.ndarray):
    """Generate rotated/inverted/preprocessed candidates, run EasyOCR, and pick best."""
    best_text, best_score = "", 0.0
    all_pairs: list[tuple[str, float, str, float]] = []  # (text, score, tag, angle)
    for tag, img in build_variants(bgr):
        for ang in ANGLES:
            img_rot = rotate_bound(img, ang) if ang != 0 else img
            rgb = cv2.cvtColor(img_rot, cv2.COLOR_BGR2RGB)
            res = easyocr_run(reader, rgb)
            pairs = parse_easyocr_result(res)
            # sort pairs by x-coordinate of bounding box
            if res:
                sorted_res = sorted(res, key=lambda x: x[0][0][0])  # sort by left x of bbox
                texts = [item[1] for item in sorted_res]
                scores = [float(item[2]) if item[2] is not None else 0.0 for item in sorted_res]
                merged_text = ''.join(texts)
                avg_score = sum(scores) / len(scores) if scores else 0.0
            else:
                merged_text = ''
                avg_score = 0.0
            all_pairs.append((merged_text or "", avg_score, tag, float(ang)))
            if merged_text and avg_score > best_score:
                best_text, best_score = merged_text, avg_score
    return best_text, best_score, all_pairs


# --------------- Main ----------------

def main():
    # EasyOCR 초기화 (GPU가 있으면 gpu=True 가능)
    reader = easyocr.Reader(['ko', 'en'], gpu=False, verbose=False)

    print("Enter image file paths (comma or space separated).\n" \
          "- 글로브 패턴도 가능: captures/*.jpg  \n" \
          "- 드래그&드롭으로 경로 붙여넣기도 가능")
    raw = input("Paths: ").strip()
    paths = expand_paths(raw)

    if not paths:
        print("No valid files provided.")
        return

    print(f"\n[INFO] {len(paths)} file(s) selected. Running OCR...\n")
    for p in paths:
        try:
            print(f"==> {p}")
            if cv2 is None:
                print("  [ERROR] OpenCV(cv2) 필요: ndarray 전처리 및 회전 스윕에 사용됩니다.")
                print("-")
                continue

            bgr = cv2.imread(p)
            if bgr is None:
                print("  [WARN] 이미지 읽기 실패")
                print("-")
                continue

            # 업스케일
            h, w = bgr.shape[:2]
            long_side = max(h, w)
            if long_side > 0 and long_side < UPSCALE_MAX:
                scale = UPSCALE_MAX / long_side
                bgr = cv2.resize(bgr, (int(w * scale), int(h * scale)), interpolation=cv2.INTER_CUBIC)

            # 멀티패스 실행
            best_text, best_score, all_pairs = run_candidates(reader, bgr)

            if best_text and best_score >= MIN_REC_SCORE:
                print(f"  {best_text}  [score={best_score:.3f}]")
            else:
                print("  (no confident text) candidates:")
                # 상위 몇 개만 힌트로 출력
                all_pairs.sort(key=lambda x: x[1], reverse=True)
                for t, s, tag, ang in all_pairs[:5]:
                    print(f"    '{t}' [score={s:.3f}] ({tag}, rot={int(ang)}°)")
            print("-")
        except Exception as e:
            print(f"  [ERROR] {e}")

if __name__ == "__main__":
    main()