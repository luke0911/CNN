# 📍 POI Recorder - 방위각 기반 OCR 기록 시스템

**작성일:** 2025-01-23
**목적:** 핸드폰을 360도 회전하면서 OCR 텍스트와 방위각을 함께 기록

---

## 🎯 주요 기능

### 1. **기준점 설정**
- 버튼을 눌러 현재 방향을 0도로 설정
- 이후 모든 각도는 기준점으로부터의 상대 각도로 계산

### 2. **자동 기록**
- YOLO 모델이 텍스트를 인식할 때마다 자동 저장
- 절대 방위각 + 상대 방위각 + OCR 텍스트 저장

### 3. **CSV 저장**
- Downloads 폴더에 CSV 파일로 저장
- 파일명: `poi_record_YYYYMMDD_HHmmss.csv`

---

## 📊 CSV 파일 형식

```csv
Timestamp,AbsoluteAngle,RelativeAngle,Text
2025-01-23 16:30:00.123,20.5,0.0,EXIT
2025-01-23 16:30:02.456,45.2,24.7,화장실
2025-01-23 16:30:05.789,90.0,69.5,엘리베이터
2025-01-23 16:30:08.012,180.3,159.8,비상구
2025-01-23 16:30:10.345,270.1,249.6,계단
2025-01-23 16:30:12.678,350.0,329.5,소화기
```

### 컬럼 설명

| 컬럼 | 설명 | 예시 |
|------|------|------|
| `Timestamp` | 기록 시간 | 2025-01-23 16:30:00.123 |
| `AbsoluteAngle` | 절대 방위각 (0-360도) | 45.2 |
| `RelativeAngle` | 기준점으로부터의 상대 각도 | 24.7 |
| `Text` | OCR로 인식한 텍스트 | "화장실" |

---

## 🔧 사용 방법

### 1단계: 기준점 설정

```
사용자: 핸드폰을 원하는 방향으로 향함
        (예: 정면을 EXIT 표지판으로)

        👇 [기준점 설정] 버튼 클릭

시스템: 현재 방향(예: 북쪽 20도)을 0도로 설정
        "기준점 설정 완료!" 메시지 표시
```

### 2단계: 360도 회전하며 자동 기록

```
사용자: 천천히 360도 회전 (시계 방향 또는 반시계 방향)

시스템: OCR이 텍스트를 인식할 때마다 자동 저장
        - 0도: EXIT
        - 24.7도: 화장실
        - 69.5도: 엘리베이터
        - 159.8도: 비상구
        ...
```

### 3단계: CSV 저장

```
사용자: 👇 [저장] 버튼 클릭

시스템: Downloads 폴더에 CSV 파일 저장
        "/storage/emulated/0/Download/poi_record_20250123_163000.csv"
        "저장 완료!" 메시지 표시
```

---

## 💡 실제 사용 시나리오

### 시나리오 1: 실내 네비게이션 데이터 수집

```
1. 사용자가 복도 중앙에 서서 정면(출구)을 바라봄
2. [기준점 설정] 버튼 클릭 → 정면 = 0도
3. 천천히 360도 회전
4. 시스템이 자동으로 기록:
   - 0도: "출구"
   - 45도: "화장실"
   - 90도: "엘리베이터"
   - 135도: "비상구"
   - 180도: "계단"
   - 270도: "소화기"
5. [저장] 버튼 클릭 → CSV 파일 생성
```

### 결과 CSV:
```csv
Timestamp,AbsoluteAngle,RelativeAngle,Text
2025-01-23 10:00:00,120.0,0.0,출구
2025-01-23 10:00:05,165.0,45.0,화장실
2025-01-23 10:00:10,210.0,90.0,엘리베이터
2025-01-23 10:00:15,255.0,135.0,비상구
2025-01-23 10:00:20,300.0,180.0,계단
2025-01-23 10:00:25,30.0,270.0,소화기
```

### 시나리오 2: 부분 회전 (180도만 회전)

```
1. 정면을 바라보며 [기준점 설정]
2. 왼쪽으로만 180도 회전
3. [저장] → 앞쪽 반원 데이터만 기록됨
```

---

## 📱 MainActivity 연동 예시

### 1. 인스턴스 생성

```kotlin
class MainActivity : ComponentActivity(), SensorEventListener {

    private val poiCoordination = poi_coordination()

    // 센서 매니저
    private lateinit var sensorManager: SensorManager
    private var magnetometer: Sensor? = null
    private var accelerometer: Sensor? = null

    // 현재 방위각
    private var currentAzimuth: Float = 0f
}
```

### 2. 센서 초기화

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // 센서 매니저 초기화
    sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
    magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
}

override fun onResume() {
    super.onResume()
    magnetometer?.let {
        sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
    }
    accelerometer?.let {
        sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
    }
}

override fun onPause() {
    super.onPause()
    sensorManager.unregisterListener(this)
}
```

### 3. 방위각 계산

```kotlin
private val gravity = FloatArray(3)
private val geomagnetic = FloatArray(3)

override fun onSensorChanged(event: SensorEvent) {
    when (event.sensor.type) {
        Sensor.TYPE_ACCELEROMETER -> {
            System.arraycopy(event.values, 0, gravity, 0, 3)
        }
        Sensor.TYPE_MAGNETIC_FIELD -> {
            System.arraycopy(event.values, 0, geomagnetic, 0, 3)
        }
    }

    val R = FloatArray(9)
    val I = FloatArray(9)

    if (SensorManager.getRotationMatrix(R, I, gravity, geomagnetic)) {
        val orientation = FloatArray(3)
        SensorManager.getOrientation(R, orientation)

        // 방위각 (라디안 → 도)
        currentAzimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
        if (currentAzimuth < 0) currentAzimuth += 360f

        // UI 업데이트 (현재 각도 표시)
        updateAzimuthUI(currentAzimuth)
    }
}

override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
    // 필요 시 정확도 경고 표시
}
```

### 4. 버튼 이벤트

```kotlin
// 기준점 설정 버튼
btnSetReference.setOnClickListener {
    poiCoordination.setReferenceAngle(currentAzimuth)
    Toast.makeText(
        this,
        "기준점 설정 완료! (${currentAzimuth.toInt()}° → 0°)",
        Toast.LENGTH_SHORT
    ).show()
}

// OCR 텍스트 감지 시 자동 호출
fun onOcrTextDetected(text: String) {
    if (poiCoordination.hasReferenceAngle()) {
        poiCoordination.addPoi(currentAzimuth, text)

        val relAngle = poiCoordination.getRelativeAngle(currentAzimuth)
        Log.d("POI", "Recorded: '$text' at ${relAngle?.toInt()}°")
    }
}

// 저장 버튼
btnSave.setOnClickListener {
    val path = poiCoordination.saveToCSV(this)
    if (path != null) {
        Toast.makeText(this, "저장 완료!\n$path", Toast.LENGTH_LONG).show()
        poiCoordination.printLogToLogcat()
    } else {
        Toast.makeText(this, "저장 실패: 데이터가 없습니다", Toast.LENGTH_SHORT).show()
    }
}

// 초기화 버튼
btnReset.setOnClickListener {
    poiCoordination.clear()
    Toast.makeText(this, "초기화 완료", Toast.LENGTH_SHORT).show()
}
```

### 5. UI 레이아웃 (activity_main.xml)

```xml
<LinearLayout
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:padding="16dp">

    <!-- 현재 각도 표시 -->
    <TextView
        android:id="@+id/tvCurrentAngle"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="현재 방향: 0°"
        android:textSize="20sp"
        android:textStyle="bold"/>

    <!-- 상대 각도 표시 -->
    <TextView
        android:id="@+id/tvRelativeAngle"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="기준점으로부터: -°"
        android:textSize="16sp"/>

    <!-- 기록된 POI 수 -->
    <TextView
        android:id="@+id/tvPoiCount"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="기록된 POI: 0개"
        android:textSize="16sp"/>

    <!-- 버튼들 -->
    <Button
        android:id="@+id/btnSetReference"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="📍 기준점 설정 (현재 방향 → 0°)"/>

    <Button
        android:id="@+id/btnSave"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="💾 CSV 저장"/>

    <Button
        android:id="@+id/btnReset"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="🔄 초기화"/>

</LinearLayout>
```

---

## 📋 권한 설정 (AndroidManifest.xml)

```xml
<manifest>
    <!-- 센서 권한 -->
    <uses-feature android:name="android.hardware.sensor.compass" android:required="false"/>
    <uses-feature android:name="android.hardware.sensor.accelerometer" android:required="true"/>

    <!-- 저장소 권한 -->
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"/>
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"/>
</manifest>
```

---

## 🔍 데이터 활용 예시

### 1. 특정 각도의 POI 검색

```kotlin
// 45도 근처(±5도)의 POI 검색
val nearbyPois = poiCoordination.getPoiNearAngle(45f, toleranceDeg = 5f)
nearbyPois.forEach { poi ->
    println("${poi.relativeAngle}°: ${poi.text}")
}
// 출력: 43.2°: 화장실
//      47.8°: 정수기
```

### 2. CSV 파일을 엑셀/구글 스프레드시트에서 분석

```
1. CSV 파일을 컴퓨터로 복사
2. Excel/Google Sheets에서 열기
3. 차트 생성:
   - X축: RelativeAngle
   - Y축: Text (카테고리)
   → 360도 파노라마 뷰 생성
```

### 3. Python으로 시각화

```python
import pandas as pd
import matplotlib.pyplot as plt

# CSV 읽기
df = pd.read_csv('poi_record_20250123_163000.csv')

# 극좌표 플롯
fig, ax = plt.subplots(subplot_kw={'projection': 'polar'})
angles = df['RelativeAngle'] * (3.14159 / 180)  # 도 → 라디안
ax.scatter(angles, [1]*len(df), c='red', s=100)

# 텍스트 표시
for i, row in df.iterrows():
    ax.text(angles[i], 1.1, row['Text'], ha='center')

plt.show()
```

---

## 🎓 핵심 개념 설명

### 절대 방위각 vs 상대 방위각

```
예시: 북쪽이 0도인 나침반

1. 절대 방위각 (AbsoluteAngle)
   - 북쪽을 기준(0도)으로 한 각도
   - 예: 동쪽 = 90도, 남쪽 = 180도, 서쪽 = 270도

2. 상대 방위각 (RelativeAngle)
   - 사용자가 설정한 기준점(0도)으로부터의 각도
   - 예: 기준점을 동쪽(90도)로 설정하면
     → 남쪽(180도) = 90도 (상대)
     → 서쪽(270도) = 180도 (상대)
     → 북쪽(0도/360도) = 270도 (상대)
```

### 왜 상대 각도가 필요한가?

```
사용자 관점에서 "정면"이 중요!

시나리오:
- 복도에서 정면이 "출구"
- 절대 방위각으로는 "북동쪽 45도"
- 하지만 사용자에게는 "정면 = 0도"가 직관적

따라서:
- 기준점 설정 → 정면 = 0도
- 오른쪽 90도 = 상대 90도
- 왼쪽 90도 = 상대 -90도 (또는 270도)
```

---

## ✅ 체크리스트

### 코딩 전

- [ ] AndroidManifest.xml에 센서 권한 추가
- [ ] AndroidManifest.xml에 저장소 권한 추가
- [ ] activity_main.xml에 UI 버튼 추가

### 코딩 중

- [ ] SensorManager 초기화
- [ ] 센서 리스너 등록/해제
- [ ] 방위각 계산 로직 구현
- [ ] poi_coordination 인스턴스 생성
- [ ] 버튼 이벤트 연결

### 테스트

- [ ] 센서가 정상 작동하는지 확인 (Logcat)
- [ ] 기준점 설정이 제대로 되는지 확인
- [ ] OCR 텍스트 + 각도가 제대로 기록되는지 확인
- [ ] CSV 파일이 Downloads 폴더에 저장되는지 확인
- [ ] CSV 파일을 열어서 데이터 확인

---

## 🐛 문제 해결

### Q1. 센서 값이 이상해요
```kotlin
// 센서 필터링 (노이즈 제거)
private fun lowPassFilter(input: Float, output: Float): Float {
    val alpha = 0.8f  // 0~1 (1에 가까울수록 부드러움)
    return output + alpha * (input - output)
}
```

### Q2. CSV 저장이 안 돼요
```
1. 권한 확인: Settings → Apps → Your App → Permissions → Storage
2. Android 10 이상: Scoped Storage 정책
   → Environment.getExternalStoragePublicDirectory() 사용
```

### Q3. 각도 계산이 이상해요
```
문제: 359도 → 0도 넘어갈 때 점프
해결: normalizeAngle() 함수 사용 (이미 구현됨)
```

---

## 📞 참고 자료

- Android Sensors: https://developer.android.com/guide/topics/sensors
- SensorManager: https://developer.android.com/reference/android/hardware/SensorManager
- CSV 파일 형식: https://en.wikipedia.org/wiki/Comma-separated_values

---

**작성:** 2025-01-23
**버전:** 1.0
