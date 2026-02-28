# 📍 POI Recorder - MainActivity 연동 완료

**작성일:** 2025-01-23
**목적:** Global Yaw 기반 OCR + 방위각 기록 시스템

---

## ✅ 완료된 작업

### 1. **poi_coordination.kt 업데이트**
- ✅ 기준점 설정 기능
- ✅ 상대 각도 계산
- ✅ CSV 저장 기능
- ✅ POI 검색 기능

### 2. **MainActivity.kt 연동**
- ✅ SensorEventListener 구현
- ✅ Rotation Vector Sensor 추가 (Global Yaw 추적)
- ✅ POI coordination 인스턴스 생성
- ✅ OCR 텍스트 감지 시 자동 POI 기록
- ✅ 센서 등록/해제 (onResume/onPause)
- ✅ POI 관련 함수 4개 추가

### 3. **activity_main.xml 업데이트**
- ✅ 3개 버튼 추가 (화면 왼쪽 하단)
  - 📍 기준점 설정 (녹색)
  - 💾 POI 저장 (파란색)
  - 🔄 POI 초기화 (빨간색)

### 4. **AndroidManifest.xml 권한 추가**
- ✅ READ_EXTERNAL_STORAGE
- ✅ WRITE_EXTERNAL_STORAGE
- ✅ 센서 권한 (자이로, 가속도계)
- ✅ requestLegacyExternalStorage (Android 10+)

---

## 🎯 사용 방법

### 1단계: 앱 실행 및 카메라 시작
```
1. 앱 실행
2. [시작] 버튼 클릭 → 카메라 & YOLO 감지 시작
```

### 2단계: 기준점 설정
```
사용자: 핸드폰을 원하는 방향(예: 출구)으로 향함
        👇 [📍 기준점 설정] 버튼 클릭

시스템: "기준점 설정 완료! 현재 방향(XX°)을 0°로 설정했습니다."
        로그: "Reference angle set: XX.X°"
```

### 3단계: 360도 회전하며 자동 기록
```
사용자: 천천히 360도 회전 (시계 방향 또는 반시계 방향)

시스템: OCR이 텍스트를 인식할 때마다 자동 기록
        로그 예시:
        - "Recorded: 'EXIT' at abs=45°, rel=0°"
        - "Recorded: '화장실' at abs=90°, rel=45°"
        - "Recorded: '엘리베이터' at abs=135°, rel=90°"
```

### 4단계: CSV 저장
```
사용자: 👇 [💾 POI 저장] 버튼 클릭

시스템: "POI 저장 완료!"
        파일: /storage/emulated/0/Download/poi_record_YYYYMMDD_HHmmss.csv

        Logcat에도 전체 로그 출력:
        ========= POI ANGLE LOG =========
        Reference: 45.0°
        Total entries: 10
        ---------------------------------
        #1 [16:30:00] abs=45.0°, rel=0.0°, text='EXIT'
        #2 [16:30:05] abs=90.0°, rel=45.0°, text='화장실'
        ...
        =================================
```

### 5단계: 초기화 (새 기록 시작)
```
사용자: 👇 [🔄 POI 초기화] 버튼 클릭

시스템: "POI 데이터 초기화 완료"
        모든 POI 데이터 삭제, 기준점 리셋
```

---

## 📊 CSV 파일 예시

### 파일 위치
```
/storage/emulated/0/Download/poi_record_20250123_163045.csv
```

### 파일 내용
```csv
Timestamp,AbsoluteAngle,RelativeAngle,Text
2025-01-23 16:30:00.123,45.2,0.0,EXIT
2025-01-23 16:30:05.456,90.5,45.3,화장실
2025-01-23 16:30:10.789,135.8,90.6,엘리베이터
2025-01-23 16:30:15.012,180.1,134.9,비상구
2025-01-23 16:30:20.345,225.4,180.2,계단
2025-01-23 16:30:25.678,270.7,225.5,소화기
2025-01-23 16:30:30.901,315.0,269.8,정수기
2025-01-23 16:30:35.234,360.3,315.1,입구
```

### 컬럼 설명
- **Timestamp**: 기록 시간 (밀리초 단위)
- **AbsoluteAngle**: 절대 방위각 (0-360도, 북쪽 기준)
- **RelativeAngle**: 상대 방위각 (기준점으로부터의 각도)
- **Text**: OCR로 인식한 텍스트

---

## 🔍 코드 흐름

### Global Yaw 업데이트 (자동, 20Hz)
```
Rotation Vector Sensor
  ↓
onSensorChanged()
  ↓
Rotation Matrix → Orientation Angles
  ↓
globalYaw 변수 업데이트 (0-360도)
```

### OCR 텍스트 → POI 기록 (자동)
```
카메라 프레임
  ↓
YOLO 감지
  ↓
OCR 처리 (ocr.process())
  ↓
텍스트 감지 시
  ↓
recordPoiIfNeeded(box.text)
  ↓
poiCoordination.addPoi(globalYaw, text)
  ↓
PoiAngleEntry 생성 및 저장
  - absoluteAngle = globalYaw
  - relativeAngle = globalYaw - referenceAngle
  - text = OCR 텍스트
  - timestamp = 현재 시간
```

---

## 🎨 UI 배치

```
┌──────────────────────────────────────┐
│                                 [⚙️]  │ ← 설정
│                          ┌──────────┐│
│                          │글자→사물 ││ ← 모드 전환
│                          └──────────┘│
│                                      │
│                                      │
│            카메라 프리뷰              │
│         (YOLO + OCR 실시간)          │
│                                      │
│                                      │
│ ┌──────────┐                         │
│ │📍 기준점  │                         │ ← POI 버튼
│ │   설정   │                         │
│ ├──────────┤                         │
│ │💾 POI    │                         │
│ │   저장   │                         │
│ ├──────────┤                         │
│ │🔄 POI    │                         │
│ │  초기화  │                         │
│ └──────────┘                         │
│                                      │
│ [시작]  [중지]                       │ ← 감지 버튼
└──────────────────────────────────────┘
```

---

## 🔧 주요 함수

### MainActivity.kt

| 함수 | 설명 | 호출 시점 |
|------|------|----------|
| `onSensorChanged()` | Global Yaw 업데이트 | 센서 이벤트 (20Hz) |
| `recordPoiIfNeeded(text)` | POI 기록 | OCR 텍스트 감지 시 |
| `setPoiReferenceAngle()` | 기준점 설정 | [기준점 설정] 버튼 클릭 |
| `savePoiToCsv()` | CSV 저장 | [POI 저장] 버튼 클릭 |
| `clearPoiData()` | 데이터 초기화 | [POI 초기화] 버튼 클릭 |

### poi_coordination.kt

| 함수 | 설명 |
|------|------|
| `setReferenceAngle(angle)` | 기준점 설정 (현재 각도 → 0도) |
| `getRelativeAngle(angle)` | 상대 각도 계산 |
| `addPoi(angle, text)` | POI 추가 (절대 + 상대 각도) |
| `saveToCSV(context)` | CSV 파일 저장 |
| `getAll()` | 전체 POI 리스트 반환 |
| `getPoiNearAngle(angle, tolerance)` | 특정 각도 근처 POI 검색 |
| `clear()` | 모든 데이터 삭제 |
| `printLogToLogcat()` | Logcat에 예쁘게 출력 |

---

## 📱 테스트 방법

### 1. 빌드 및 설치
```bash
cd /Users/idohun/WorkSpace/CNN/App
./gradlew assembleDebug installDebug
```

### 2. 로그 확인
```bash
adb logcat -c  # 로그 초기화
adb logcat | grep -E "POI|Sensor|Yaw"
```

**확인할 로그:**
```
MainActivity: Rotation Vector Sensor registered
MainActivity: Sensor accuracy: HIGH
POI: Reference angle set: 45.2°
POI: Recorded: 'EXIT' at abs=45°, rel=0°
POI: Recorded: '화장실' at abs=90°, rel=45°
POI: CSV saved: /storage/emulated/0/Download/poi_record_20250123_163045.csv
========= POI ANGLE LOG =========
...
```

### 3. CSV 파일 확인
```bash
# 디바이스에서 PC로 파일 복사
adb pull /storage/emulated/0/Download/poi_record_*.csv ./

# 파일 내용 확인
cat poi_record_*.csv
```

---

## 🐛 문제 해결

### Q1. "방위각 센서가 없습니다" 메시지
```
문제: Rotation Vector Sensor가 없는 기기
해결: 대부분의 최신 기기는 지원함
     구형 기기는 TYPE_ACCELEROMETER + TYPE_MAGNETIC_FIELD 조합 사용 필요
```

### Q2. Global Yaw 값이 이상함
```
문제: 센서 노이즈 또는 자기장 간섭
해결:
  1. 핸드폰을 8자 모양으로 움직여 센서 캘리브레이션
  2. 금속 물체에서 멀리 떨어진 곳에서 사용
  3. 로그에서 "Sensor accuracy: HIGH" 확인
```

### Q3. POI가 기록되지 않음
```
문제: 기준점이 설정되지 않음
해결: [📍 기준점 설정] 버튼을 먼저 클릭

확인: 로그에서 "Reference angle set: XX.X°" 메시지 확인
```

### Q4. CSV 저장이 안 됨
```
문제: 저장소 권한 없음
해결:
  1. 설정 → 앱 → Yolo_OCR → 권한 → 저장소 허용
  2. Android 11 이상: 설정 → 모든 파일 접근 허용 (옵션)
```

### Q5. OCR은 되는데 POI 기록이 안 됨
```
확인 사항:
  1. 기준점 설정 여부: poiCoordination.hasReferenceAngle()
  2. Logcat에서 "Recorded: ..." 메시지 확인
  3. 텍스트가 비어있지 않은지 확인
```

---

## 📈 데이터 활용 예시

### 1. Excel/Google Sheets 분석
```
1. CSV 파일을 컴퓨터로 복사
2. Excel/Google Sheets로 열기
3. 차트 생성:
   - 막대 그래프: RelativeAngle vs Text
   - 극좌표 플롯: 360도 파노라마 뷰
```

### 2. Python 시각화
```python
import pandas as pd
import matplotlib.pyplot as plt
import numpy as np

# CSV 읽기
df = pd.read_csv('poi_record_20250123_163045.csv')

# 극좌표 플롯
fig = plt.figure(figsize=(10, 10))
ax = fig.add_subplot(111, projection='polar')

# 각도를 라디안으로 변환
angles = np.deg2rad(df['RelativeAngle'])

# 점 표시
ax.scatter(angles, [1]*len(df), c='red', s=100, alpha=0.7)

# 텍스트 레이블
for i, row in df.iterrows():
    angle = np.deg2rad(row['RelativeAngle'])
    ax.text(angle, 1.1, row['Text'], ha='center', fontsize=10)

ax.set_theta_zero_location('N')  # 북쪽을 위로
ax.set_theta_direction(-1)  # 시계 방향
plt.title('POI 360도 분포', fontsize=16)
plt.tight_layout()
plt.savefig('poi_distribution.png', dpi=300)
plt.show()
```

### 3. 특정 각도의 POI 검색
```kotlin
// 45도 근처 ±10도의 POI 검색
val nearbyPois = poiCoordination.getPoiNearAngle(45f, 10f, useRelative = true)
nearbyPois.forEach { poi ->
    println("${poi.relativeAngle}°: ${poi.text}")
}
```

---

## 🎓 핵심 개념

### Global Yaw vs Azimuth

**Global Yaw (사용):**
- Rotation Vector Sensor 기반
- 자이로 + 가속도계 + 자기장 센서 융합
- 높은 정확도, 낮은 노이즈
- 0-360도 범위

**Azimuth (나침반):**
- 자기장 센서만 사용
- 노이즈 많음, 금속 간섭 발생
- Global Yaw보다 부정확

**선택 이유:** Global Yaw가 훨씬 정확하고 안정적

### 절대 각도 vs 상대 각도

```
예시:
- 사용자가 북동쪽(45도)을 바라보며 [기준점 설정]
- 기준점 = 45도 (절대)

회전 시:
- 동쪽(90도) 방향에서 "EXIT" 감지
  → 절대 각도: 90도
  → 상대 각도: 90 - 45 = 45도

- 남쪽(180도) 방향에서 "화장실" 감지
  → 절대 각도: 180도
  → 상대 각도: 180 - 45 = 135도

- 북쪽(0도) 방향에서 "입구" 감지
  → 절대 각도: 0도
  → 상대 각도: 0 - 45 = -45도 (또는 315도)
```

**장점:** 사용자 관점에서 "정면 = 0도"로 직관적

---

## 🚀 향상 가능성

### 1. UI 개선
- [ ] 현재 Yaw 각도 실시간 표시
- [ ] POI 개수 카운터
- [ ] 기록 상태 표시 (기준점 설정 여부)

### 2. 기능 추가
- [ ] POI 편집 기능 (텍스트 수정, 삭제)
- [ ] POI 시각화 (미니맵)
- [ ] 자동 360도 회전 가이드
- [ ] 음성 안내 ("90도 지점입니다")

### 3. 데이터 분석
- [ ] 앱 내에서 POI 통계 표시
- [ ] 가장 많이 감지된 텍스트 TOP 10
- [ ] 각도별 POI 분포 차트

---

## ✅ 체크리스트

### 개발 완료
- [x] poi_coordination.kt 구현
- [x] MainActivity에 센서 추가
- [x] OCR 텍스트 → POI 자동 기록
- [x] UI 버튼 추가
- [x] AndroidManifest 권한 설정
- [x] 사용 가이드 문서 작성

### 테스트 필요
- [ ] 실기기에서 빌드 및 실행
- [ ] 센서 정상 작동 확인
- [ ] 기준점 설정 테스트
- [ ] OCR → POI 기록 확인
- [ ] CSV 저장 및 파일 확인
- [ ] 360도 회전 시나리오 테스트

---

## 📞 참고

- **상세 가이드:** `POI_RECORDER_README.md`
- **코드 예시:** `POI_USAGE_EXAMPLE.kt`
- **최적화 보고서:** `OPTIMIZATION_REPORT_2025.md`

---

**작성:** 2025-01-23
**버전:** 1.0
**상태:** 연동 완료, 테스트 필요
