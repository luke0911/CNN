# 앱 모델 파일

이 디렉토리는 Android 앱에서 사용하는 YOLO 모델 파일들을 관리합니다.

## 📁 디렉토리 구조

```
앱_모델/
├── PT/           # PyTorch 모델 파일
│   ├── yolov8s.pt                # 기본 YOLOv8s 모델 (22MB)
│   └── sign_v8s_896_mobile.pt    # 학습된 표지판 모델 896x896 (21MB)
│
└── ONNX/         # Android 앱용 ONNX 모델
    ├── Yolo.onnx              # 텍스트 인식 896x896 (43MB)
    ├── Yolo_640_7.onnx        # 텍스트 인식 640x640 (43MB)
    └── object.onnx            # 물체 감지 640x640 (43MB)
```

## 🔧 모델 설명

### PT 모델 (학습/변환용)

**yolov8s.pt**
- 용도: Ultralytics 공식 YOLOv8s 베이스 모델
- 사용: Fine-tuning 시작점, 새로운 학습

**sign_v8s_896_mobile.pt**
- 용도: 표지판 인식 학습된 모델
- 입력: 896x896
- 클래스: 7개 (문, 비상구, 소화기, 엘리베이터, 화장실, 정수기, 계단표지판)
- 변환: Yolo.onnx로 변환됨

### ONNX 모델 (앱 배포용)

**Yolo.onnx**
- 모드: 텍스트 인식 (TEXT)
- 입력: 896x896
- 출력: [1, 5, N] - 4(bbox) + 1(conf)
- 앱 연결: YoloModel.M896

**Yolo_640_7.onnx**
- 모드: 텍스트 인식 (TEXT)
- 입력: 640x640
- 출력: [1, 5, N]
- 앱 연결: YoloModel.M640

**object.onnx**
- 모드: 물체 감지 (OBJECT)
- 입력: 640x640
- 출력: [1, 11, 8400] - 4(bbox) + 7(classes)
- 클래스: 7개 (ObjectClass enum)
- 앱 연결: YoloModel.OBJECT

## 🔄 모델 업데이트 방법

### 1. PT 모델 학습 후 ONNX 변환

```bash
# 학습된 PT 모델을 ONNX로 변환
cd /Users/idohun/WorkSpace/CNN/YOLO_Project
python3 욜로_최적화_FP16.py

# 또는 직접 변환
from ultralytics import YOLO
model = YOLO('sign_v8s_896_mobile.pt')
model.export(format='onnx', imgsz=896)
```

### 2. 앱에 적용

```bash
# ONNX 파일을 앱 assets로 복사
cp 앱_모델/ONNX/Yolo.onnx \
   /Users/idohun/WorkSpace/CNN/App/app/src/main/assets/

cp 앱_모델/ONNX/Yolo_640_7.onnx \
   /Users/idohun/WorkSpace/CNN/App/app/src/main/assets/

cp 앱_모델/ONNX/object.onnx \
   /Users/idohun/WorkSpace/CNN/App/app/src/main/assets/
```

### 3. 앱 리빌드

```bash
cd /Users/idohun/WorkSpace/CNN/App
./gradlew assembleDebug
```

## ⚠️ 주의사항

1. **ONNX Runtime 버전**: 앱은 ONNX Runtime 1.20.0 사용
2. **IR 버전**: ONNX 모델은 IR version 11 지원 필요
3. **출력 형식**: 앱의 YoloOutputParser가 예상하는 형식과 일치해야 함
   - TEXT 모드: [1, 5, N]
   - OBJECT 모드: [1, 11, 8400]
4. **클래스 순서**: object.onnx의 클래스 순서는 변경 금지
   - 0: door, 1: exit_sign, 2: fire_extinguisher, 3: elevator
   - 4: restroom_sign, 5: water_dispenser, 6: stair_sign

## 📊 모델 성능 확인

```bash
# 모델 분석
python3 모델_분석_통합.py

# 성능 테스트
python3 욜로_성능_테스트.py
```

## 🔖 관련 파일

- 앱 코드: `/Users/idohun/WorkSpace/CNN/App/app/src/main/java/com/example/Yolo_OCR/`
  - `DetTypes.kt` - 모델 스펙 정의
  - `YoloEngine.kt` - ONNX 추론 엔진
  - `YoloOutputParser.kt` - 출력 파싱

- 학습 스크립트:
  - `계단_추가_학습.py` - 계단 클래스 추가 학습
  - `욜로_나노_학습.py` - YOLOv8n 경량 모델 학습

- 최적화 스크립트:
  - `욜로_최적화_FP16.py` - FP16 변환
  - `욜로_최적화_프루닝.py` - 프루닝 최적화
