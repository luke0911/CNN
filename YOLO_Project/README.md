# YOLO 프로젝트

Android 앱용 YOLO 물체 감지 및 텍스트 인식 모델 학습/최적화 프로젝트

## 📁 프로젝트 구조

```
YOLO_Project/
├── 앱_모델/                      # Android 앱용 모델 파일 (171MB)
│   ├── PT/                       # PyTorch 모델 (43MB)
│   │   ├── yolov8s.pt           # 베이스 모델 22MB
│   │   └── sign_v8s_896_mobile.pt  # 학습된 모델 21MB
│   └── ONNX/                    # 배포용 ONNX 모델 (128MB)
│       ├── Yolo.onnx            # 텍스트 896x896
│       ├── Yolo_640_7.onnx      # 텍스트 640x640
│       └── object.onnx          # 물체 감지 640x640
│
├── docs/                        # 문서 및 가이드
│   ├── QUICKSTART.md
│   ├── README_OPTIMIZATION.md
│   └── README_YOLO640_OPTIMIZATION.md
│
├── logs/                        # 학습 및 최적화 로그
│   ├── training_log.txt
│   ├── optimization_log.txt
│   └── 학습_로그.txt
│
├── datasets_sign/              # 표지판 학습 데이터셋 (18GB)
├── object_detection/           # 물체 감지 데이터셋 (883MB)
│   ├── merged_yolov8/         # 병합된 데이터
│   └── stair.v1i.yolov8/      # 계단 데이터
│
├── runs/                       # 학습 결과 (526MB)
│   ├── sign_yolov8s_img896_e50_mps8/
│   ├── sign_yolov8s_img1024_ft/
│   └── detect/
│
├── samples/                    # 테스트 샘플 이미지
└── tests/                      # 테스트 코드
```

## 🐍 Python 스크립트

### 학습 스크립트

| 파일 | 설명 |
|------|------|
| `계단_추가_학습.py` | 계단 인식 클래스 추가 학습 (merged + stair 데이터셋 병합) |
| `욜로_나노_학습.py` | YOLOv8n 초경량 모델 학습 (< 10MB 목표) |

### 최적화 스크립트

| 파일 | 설명 |
|------|------|
| `욜로_최적화_FP16.py` | PT/ONNX 모델 FP16 변환 (크기 50% 감소) |
| `욜로_최적화_프루닝.py` | 프루닝 + Fine-tuning + 양자화 파이프라인 |
| `모델_분석_통합.py` | 모델 정보, 압축 가능성, 성능 비교 분석 |
| `욜로_성능_테스트.py` | FP32/FP16 정확도, 추론 속도, 메모리 측정 |

### 실시간 테스트

| 파일 | 설명 |
|------|------|
| `욜로_웹캠_테스트.py` | 웹캠 실시간 YOLO 추론 (FPS 측정) |
| `욜로_OCR_실시간.py` | YOLO + OCR 파이프라인 (EasyOCR/PaddleOCR) |
| `OCR_확인도구.py` | 이미지 배치 OCR 처리 도구 |

## 🚀 빠른 시작

### 1. 계단 추가 학습

```bash
cd /Users/idohun/WorkSpace/CNN/YOLO_Project
python3 계단_추가_학습.py
```

자동으로 다음 작업 수행:
1. 데이터셋 병합 (merged_yolov8 + stair.v1i.yolov8)
2. 모델 학습 (50 에포크)
3. ONNX 변환
4. 앱에 적용 가능한 `계단_인식_모델.onnx` 생성

### 2. 모델 최적화

```bash
# FP16 변환 (대화형 메뉴)
python3 욜로_최적화_FP16.py

# 프루닝 최적화 (대화형 메뉴)
python3 욜로_최적화_프루닝.py
```

### 3. 모델 분석

```bash
# 모델 정보 확인
python3 모델_분석_통합.py

# 성능 테스트
python3 욜로_성능_테스트.py
```

### 4. 웹캠 테스트

```bash
# YOLO 실시간 추론
python3 욜로_웹캠_테스트.py

# YOLO + OCR
python3 욜로_OCR_실시간.py
```

## 📦 앱에 모델 적용

```bash
# 1. 앱 모델 디렉토리에서 파일 확인
ls -lh 앱_모델/ONNX/

# 2. 앱 assets로 복사
cp 앱_모델/ONNX/*.onnx \
   /Users/idohun/WorkSpace/CNN/App/app/src/main/assets/

# 3. 앱 리빌드
cd /Users/idohun/WorkSpace/CNN/App
./gradlew assembleDebug
```

## 🎯 모델 스펙

### ONNX 모델

| 모델 | 입력 | 출력 | 용도 | 클래스 |
|------|------|------|------|--------|
| Yolo.onnx | 896×896 | [1,5,N] | 텍스트 인식 | - |
| Yolo_640_7.onnx | 640×640 | [1,5,N] | 텍스트 인식 | - |
| object.onnx | 640×640 | [1,11,8400] | 물체 감지 | 7개 |

### Object Detection 클래스 (object.onnx)

0. Door (문)
1. Exit Sign (비상구 표지판)
2. Fire Extinguisher (소화기)
3. Elevator (엘리베이터)
4. Restroom Sign (화장실 표지판)
5. Water Dispenser (정수기)
6. Stair Sign (계단 표지판)

## 🔧 최적화 기법

| 기법 | 크기 감소 | 속도 향상 | 정확도 손실 |
|------|-----------|-----------|-------------|
| FP16 | ~50% | ~20% | < 0.1% |
| 프루닝 (30%) | ~30% | ~15% | 1-2% (Fine-tuning 후) |
| 양자화 INT8 | ~75% | ~40% | 2-5% (실험적) |

## 📊 데이터셋 정보

- **표지판 데이터**: 18GB (datasets_sign/)
- **물체 감지 데이터**: 883MB (object_detection/)
  - merged_yolov8: 7개 클래스 (문, 비상구, 소화기 등)
  - stair.v1i.yolov8: 계단 이미지

## ⚙️ 환경 설정

```bash
# 필수 라이브러리
pip install ultralytics torch onnx onnxruntime opencv-python

# FP16 변환용
pip install onnxconverter-common

# OCR (선택)
pip install easyocr paddleocr
```

## 📝 주요 변경 사항

### 2025-10-26
- ✅ Python 파일명 한글로 통일 (18개 → 9개)
- ✅ 중복 기능 통합 (모델 분석 4개 → 1개, FP16 4개 → 1개 등)
- ✅ 앱 모델 전용 폴더 생성 (앱_모델/PT, 앱_모델/ONNX)
- ✅ 문서 정리 (docs/), 로그 정리 (logs/)
- ✅ 불필요한 파일 삭제 (epoch 체크포인트 35개, ~2.6GB 절약)

## 🔗 관련 링크

- Android 앱: `/Users/idohun/WorkSpace/CNN/App`
- 앱 모델 코드: `App/app/src/main/java/com/example/Yolo_OCR/`
  - DetTypes.kt - 모델 스펙 정의
  - YoloEngine.kt - ONNX 추론
  - YoloOutputParser.kt - 출력 파싱

## 📖 문서

상세한 가이드는 `docs/` 디렉토리 참조:
- QUICKSTART.md - 빠른 시작 가이드
- README_OPTIMIZATION.md - 최적화 가이드
- README_YOLO640_OPTIMIZATION.md - Yolo_640_7 최적화

## 💡 팁

1. **학습 전**: GPU 메모리 확인 (MPS 사용 권장)
2. **최적화**: FP16 먼저 시도 → 프루닝은 정확도 필요 시
3. **테스트**: 실제 이미지로 정확도 확인 필수
4. **앱 배포**: ONNX Runtime 1.20.0, IR version 11 확인

## ❓ 문제 해결

**Q: 학습이 느려요**
- A: 배치 크기 줄이기 (16 → 8), 이미지 크기 줄이기 (896 → 640)

**Q: 앱에서 모델 로드 실패**
- A: ONNX Runtime 버전, IR 버전 확인

**Q: 정확도가 떨어졌어요**
- A: Fine-tuning 에포크 늘리기, 프루닝 비율 줄이기

---

**프로젝트 문의**: 프로젝트 디렉토리의 스크립트 파일 참조
