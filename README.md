# CNN

YOLO 기반 실시간 OCR 텍스트 인식 시스템 - Python 학습 및 Android 모바일 애플리케이션

## 개요

이 프로젝트는 **YOLOv8 객체 탐지 모델**과 **OCR(광학 문자 인식)** 기술을 결합한 엔드-투-엔드 텍스트 인식 시스템입니다.
서버 및 모바일 환경에서 실시간으로 이미지/비디오의 텍스트 영역을 감지하고, 인식된 텍스트를 추출합니다.

### 주요 특징
- **YOLO 기반 텍스트 탐지**: YOLOv8 모델을 사용한 고속 텍스트 영역 감지
- **다중 OCR 백엔드 지원**: EasyOCR, PaddleOCR을 활용한 텍스트 인식
- **실시간 처리**: 카메라 입력에서 낮은 지연 시간으로 처리
- **모바일 최적화**: Android 앱에서 ONNX Runtime을 활용한 경량 모델 실행
- **멀티스레드 처리**: 비동기 OCR 처리로 높은 FPS 유지
- **객체 감지 모드**: 텍스트 외에 특정 객체(문, 계단, 소화기 등) 감지 지원

## 기술 스택

### 백엔드 (Python)
- **YOLO Framework**: `ultralytics` - YOLOv8 모델 학습 및 추론
- **OCR 엔진**:
  - EasyOCR: 한국어, 영어 텍스트 인식
  - PaddleOCR: 경량 OCR 백엔드
- **이미지 처리**: OpenCV (cv2), PIL/Pillow
- **모델 변환**: ONNX Runtime으로 모델 최적화 및 추론

### 모바일 (Android)
- **언어**: Kotlin (최신 버전)
- **카메라**: CameraX - 최신 안드로이드 카메라 API
- **ML 추론**: ONNX Runtime Android
- **OCR**: Google ML Kit Text Recognition
- **UI Framework**: Jetpack Compose (최신 구성)
- **빌드 시스템**: Gradle with Kotlin DSL

### 데이터셋
- Scene Text Detection Dataset
- 커스텀 표지판 감지 데이터셋 (`datasets_sign/`)
- 계단, 문, 소화기, 엘리베이터, 화장실, 정수기 등 7개 클래스

## 프로젝트 구조

```
CNN/
├── YOLO_Project/                    # Python 모델 학습 및 테스트
│   ├── 욜로_OCR_실시간.py           # 실시간 카메라 OCR 파이프라인
│   ├── 욜로_나노_학습.py            # YOLOv8n (Nano) 경량 모델 학습
│   ├── 욜로_웹캠_테스트.py          # 웹캠 기반 실시간 테스트
│   ├── 욜로_최적화_프루닝.py        # 모델 프루닝으로 크기 감소
│   ├── 욜로_최적화_FP16.py          # 반정밀도(FP16) 최적화
│   ├── OCR_확인도구.py              # OCR 결과 시각화 및 검증
│   ├── 통합_성능_분석.py            # 전체 파이프라인 성능 분석
│   ├── export_m416_model.py         # ONNX 모델 변환 (416x416 입력)
│   ├── datasets_sign/               # 커스텀 학습 데이터셋
│   ├── object_detection/            # 객체 감지 모델 및 학습 결과
│   ├── runs/                        # 학습 결과 저장
│   ├── yolov8s.pt                  # YOLOv8 Small 모델 (22.6MB)
│   ├── Yolo.onnx                   # ONNX 형식 모델 (44.7MB)
│   └── Yolo_640_7.onnx             # 640x640 최적화 ONNX 모델
│
├── App/                             # Android OCR 애플리케이션
│   ├── app/src/main/
│   │   ├── java/com/example/Yolo_OCR/
│   │   │   ├── MainActivity.kt              # 메인 액티비티 (카메라 + 탐지)
│   │   │   ├── YoloEngine.kt               # ONNX Runtime 통합
│   │   │   ├── OcrEngine.kt                # OCR 조정 및 박스 추적
│   │   │   ├── OverlayView.kt              # 탐지 결과 시각화
│   │   │   ├── DetTypes.kt                 # 데이터 클래스 및 열거형
│   │   │   ├── yolo/YoloOutputParser.kt    # YOLO 출력 파싱
│   │   │   ├── ocr/OcrProcessor.kt         # ML Kit OCR 처리
│   │   │   ├── ocr/BoxMerger.kt            # 박스 병합 알고리즘
│   │   │   ├── utils/                      # 유틸리티 (좌표, 이미지 처리)
│   │   │   └── performance/                # 성능 모니터링
│   │   └── assets/
│   │       ├── Yolo.onnx                  # 텍스트 탐지 모델
│   │       ├── Yolo_640_7.onnx            # 640x640 모델
│   │       └── object.onnx                # 객체 탐지 모델
│   ├── build.gradle.kts              # 프로젝트 설정
│   └── gradle.properties
│
├── one_check.py                      # ONNX 모델 검증 및 전처리 테스트
│
├── data/                             # 샘플 데이터 및 테스트 이미지
├── scene-text-dataset-master/        # Scene Text 데이터셋
└── parity_out/                       # 처리 결과 저장
```

## 주요 기능

### 1. YOLO 모델 학습 및 최적화

#### 기본 학습
```python
# YOLOv8 모델 미세 조정
from ultralytics import YOLO
model = YOLO('yolov8s.pt')
results = model.train(data='datasets_sign/data.yaml', epochs=100, imgsz=640)
```

#### 경량화 옵션
- **YOLOv8n (Nano)**: 매개변수 3M, 크기 6MB (YOLOv8s의 1/7 크기)
- **모델 프루닝**: 불필요한 가중치 제거로 크기 30-40% 감소
- **반정밀도 (FP16)**: 정밀도 손실 최소화하며 크기 50% 감소
- **ONNX 변환**: 416x416 입력으로 추론 속도 50% 향상

#### 지원 모델 입력 크기
- **M896**: 896x896 (높은 정확도)
- **M640**: 640x640 (균형잡힌 선택)
- **M416**: 416x416 (최고 속도)

### 2. 실시간 텍스트 감지 및 OCR

**Python 파이프라인** (`욜로_OCR_실시간.py`):
1. **입력**: 웹캠 비디오 스트림 (1280x720)
2. **YOLO 탐지**: 텍스트 영역 감지 (신뢰도 임계값: 0.60)
3. **박스 병합**: 인접한 탐지를 그룹화하여 단어/문장 형성
4. **추적**: IOU 기반 추적으로 안정적인 박스 유지
5. **OCR 처리**:
   - 비동기 워커 스레드에서 OCR 실행
   - PaddleOCR 또는 EasyOCR 사용
   - 한국어 + 영어 이중 언어 지원
6. **중복 제거**: 5초 이내 동일 텍스트 재보고 방지
7. **시각화**: 실시간 박스 및 인식 텍스트 표시

**처리 파라미터**:
- 신뢰도 임계값: 0.20 (텍스트), 0.10 (객체)
- NMS 임계값: 0.10
- 최소 박스 크기: 8x8 픽셀
- 병합 갭: 20px (인접 박스 연결)

### 3. Android 모바일 애플리케이션

#### 주요 모드
1. **텍스트 인식 모드**:
   - YOLO로 텍스트 영역 감지
   - Google ML Kit로 OCR 수행
   - 인식된 텍스트 실시간 표시
   - 모델 전환: 896 ↔ 640 해상도

2. **객체 감지 모드**:
   - 7개 클래스 감지: 문, 비상구, 소화기, 엘리베이터, 화장실, 정수기, 계단
   - 클래스별 토글 필터
   - 높은 정확도의 객체 레이블링

#### 기술 상세
- **카메라**: CameraX로 YUV 프레임 캡처
- **전처리**: YUV→RGB 변환, 회전 보정, Letterbox 스케일링
- **추론**: ONNX Runtime (v1.20.0) 기반 모델 실행
- **출력 파싱**: 다양한 YOLO 출력 형식 지원 ([1,11,8400], [1,5,N] 등)
- **후처리**: NMS, 좌표 매핑
- **UI**: OverlayView로 탐지 박스와 텍스트 렌더링

### 4. ONNX 모델 검증 및 전처리

**one_check.py**:
- ONNX 모델 구조 분석 (입력/출력 형식)
- 다양한 전처리 조합 테스트:
  - RGB/BGR 색공간
  - 정규화 (0-255 범위, ImageNet 정규화)
  - 레이아웃 (NCHW, NHWC)
- 모델 추론 테스트 및 결과 검증

사용 예:
```bash
python one_check.py --model Yolo.onnx --image sample.jpg --size 896 --run
```

### 5. 성능 분석 및 모니터링

#### 측정 항목
- **추론 속도**: YOLO 처리 시간 (ms)
- **FPS**: 전체 파이프라인 프레임률
- **메모리 사용**: 모델 로딩 및 처리 메모리
- **정확도**: mAP@50, mAP@75 등

#### 파이프라인 지연 시간
- M896: 약 38ms (서버)
- M640: 약 20-25ms
- M416: 약 17-20ms (모바일 최적)

### 6. 멀티스레드 OCR 처리

**특징**:
- OCR 큐 기반 비동기 처리 (최대 8개 작업)
- 워커 스레드 풀 (2개 스레드)
- 프레임당 OCR 제한: 최대 3개
- 전역 rate limiting: 최소 120ms 간격
- 대기 작업 타임아웃: 2초

**색상 코드** (박스):
- 🟢 초록색: 추적 중 (미처리)
- 🟡 노랑색: OCR 처리 중
- 🟠 주황색: OCR 완료

## 설정 및 사용

### Python 환경 설정

```bash
# 가상환경 생성
python3 -m venv .venv
source .venv/bin/activate

# 의존성 설치
pip install ultralytics opencv-python easyocr paddleocr numpy onnx onnxruntime pillow
```

### 모델 학습

```bash
# YOLOv8 Small 모델 학습
python YOLO_Project/욜로_나노_학습.py

# 최적화 적용
python YOLO_Project/욜로_최적화_FP16.py
python YOLO_Project/욜로_최적화_프루닝.py
```

### 실시간 OCR 실행

```bash
# 웹캠 기반 실시간 처리
python YOLO_Project/욜로_OCR_실시간.py

# ONNX 모델 검증
python one_check.py --model Yolo.onnx --image test.jpg --size 896 --run
```

### Android 앱 빌드

```bash
cd App/
./gradlew assembleDebug      # Debug APK 빌드
./gradlew assembleRelease    # Release APK 빌드
./gradlew installDebug       # 연결된 기기에 설치
```

## 모델 상세 정보

### YOLO 출력 형식
- **문법**: [배치, 클래스+좌표, 앵커포인트]
- **형식**: [1, 11, 8400] (4 bbox 좌표 + 7 클래스 확률)
- **처리**: Sigmoid 활성화 (신뢰도), Softmax (클래스)

### OCR 지원 언어
- 한국어 (ko)
- 영어 (en)

### 객체 감지 클래스
```kotlin
0 - 문 (Door)
1 - 비상구 표지판 (Exit Sign)
2 - 소화기 (Fire Extinguisher)
3 - 엘리베이터 (Elevator)
4 - 화장실 표지판 (Restroom Sign)
5 - 정수기 (Water Dispenser)
6 - 계단 표지판 (Stair Sign)
```

## 주요 성능 지표

| 모델 | 입력 크기 | 파라미터 | 크기 | 추론 시간 | 정확도 |
|------|---------|--------|------|---------|-------|
| YOLOv8n | 640x640 | 3M | 6MB | 15-20ms | 높음 |
| YOLOv8s | 640x640 | 11M | 22.6MB | 20-25ms | 매우 높음 |
| M896 | 896x896 | 11M | 44.7MB | 38ms | 최고 |
| M640 | 640x640 | 11M | 44.7MB | 20-25ms | 균형 |
| M416 | 416x416 | 11M | 44.7MB | 17-20ms | 빠름 |

## 참고 자료

- **YOLO 문서**: https://docs.ultralytics.com/
- **ONNX Runtime**: https://onnxruntime.ai/
- **EasyOCR**: https://github.com/JaidedAI/EasyOCR
- **PaddleOCR**: https://github.com/PaddlePaddle/PaddleOCR
- **CameraX**: https://developer.android.com/training/camerax
- **ML Kit**: https://developers.google.com/ml-kit

## 라이선스

이 프로젝트는 연구 및 개발 목적으로 작성되었습니다.

## 개발자

개발자: idohun
최종 업데이트: 2026년 2월
