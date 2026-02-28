# YoloEngine 최적화 가이드

## 🔥 현재 문제점

`YoloEngine.kt` 72-75줄에서 ONNX Runtime 설정이 최적화되지 않음:

```kotlin
// ❌ 현재 (느림)
val so = OrtSession.SessionOptions().apply {
    setIntraOpNumThreads(1)  // 싱글 스레드
    setInterOpNumThreads(1)  // 싱글 스레드
    // GPU 가속 꺼짐
}
```

**결과:**
- YOLO 추론이 CPU 1개 코어만 사용
- GPU 가속 없음
- Total 시간 1207ms

---

## ✅ 최적화 방법

### 1단계: 기본 멀티스레딩 활성화

```kotlin
val so = OrtSession.SessionOptions().apply {
    setIntraOpNumThreads(4)  // 4개 스레드 (CPU 코어 수에 맞게)
    setInterOpNumThreads(4)
    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
}
```

**예상 개선:** 2-3배 빠름 (400-600ms)

---

### 2단계: NNAPI 가속 활성화 (권장)

```kotlin
val so = OrtSession.SessionOptions().apply {
    setIntraOpNumThreads(4)
    setInterOpNumThreads(4)
    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)

    // NNAPI (Android Neural Networks API) 활성화
    addNnapi()
}
```

**예상 개선:** 5-10배 빠름 (100-200ms)

**주의사항:**
- NNAPI는 Android 8.1 (API 27) 이상 필요
- 일부 디바이스에서 FP16 모델만 지원

---

### 3단계: 완전 최적화 (추천)

```kotlin
import ai.onnxruntime.OrtSession
import android.os.Build
import android.util.Log

val so = OrtSession.SessionOptions().apply {
    // 기본 최적화
    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)

    // CPU 멀티스레딩
    val numCores = Runtime.getRuntime().availableProcessors()
    val threads = maxOf(4, minOf(numCores, 8))  // 4-8개 사이
    setIntraOpNumThreads(threads)
    setInterOpNumThreads(threads)

    // 메모리 최적화
    setMemoryPatternOptimization(true)
    setCPUArenaAllocator(true)

    // GPU/NNAPI 가속 (디바이스 지원 여부에 따라)
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            addNnapi()
            Log.d("YoloEngine", "NNAPI enabled")
        }
    } catch (e: Exception) {
        Log.w("YoloEngine", "NNAPI not available, using CPU", e)
    }
}
```

**예상 개선:**
- NNAPI 지원 기기: **5-10배 빠름** (100-200ms) ✅
- NNAPI 미지원 기기: **2-3배 빠름** (400-600ms)

---

## 🚀 추가 최적화 팁

### 1. FP16 모델 사용 (이미 완료)
- ✅ `yolo_640_7_re.onnx` (21MB FP16)
- NNAPI와 함께 사용하면 최고 성능

### 2. 입력 이미지 크기 줄이기
현재 640x640 → 416x416 또는 512x512로 줄이면:
- 속도: 2배 빠름
- 정확도: 약간 감소 (5-10%)

```kotlin
// DetTypes.kt에서
YoloModel.M416 -> ModelSpec("yolo_416_fp16.onnx", 416)
```

### 3. YOLOv8n (Nano) 모델 사용
- 크기: 6MB (현재 21MB의 1/4)
- 속도: 3-5배 빠름
- 파라미터: 3M (현재 11M의 1/4)

---

## 📊 예상 성능 비교

| 최적화 단계 | Total 시간 | 개선율 | 발열 |
|------------|-----------|--------|------|
| **현재** (1 thread, CPU) | 1207ms | - | 🔥🔥🔥 |
| 1단계 (4 threads, CPU) | 400-600ms | 2-3배 | 🔥🔥 |
| 2단계 (NNAPI) | 100-200ms | 6-12배 | 🔥 |
| 3단계 (완전 최적화) | **100-150ms** | **8-12배** ✅ | 🔥 |
| + YOLOv8n | **50-100ms** | **12-24배** ✅✅ | ❄️ |

---

## 🛠️ 구현 방법

### YoloEngine.kt 수정

**파일:** `/Users/idohun/WorkSpace/CNN/App/app/src/main/java/com/example/Yolo_OCR/YoloEngine.kt`

**수정 위치:** 72-76줄

**변경 전:**
```kotlin
val so = OrtSession.SessionOptions().apply {
    setIntraOpNumThreads(1)
    setInterOpNumThreads(1)
    // TODO: enable NNAPI/XNNPACK here if desired
}
```

**변경 후:** (3단계 완전 최적화)
```kotlin
val so = OrtSession.SessionOptions().apply {
    // 기본 최적화
    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)

    // CPU 멀티스레딩
    val numCores = Runtime.getRuntime().availableProcessors()
    val threads = maxOf(4, minOf(numCores, 8))
    setIntraOpNumThreads(threads)
    setInterOpNumThreads(threads)
    Log.d(TAG, "ONNX Runtime: Using $threads threads (cores=$numCores)")

    // 메모리 최적화
    setMemoryPatternOptimization(true)
    setCPUArenaAllocator(true)

    // NNAPI 가속 시도
    try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            addNnapi()
            Log.d(TAG, "ONNX Runtime: NNAPI GPU acceleration enabled")
        }
    } catch (e: Exception) {
        Log.w(TAG, "ONNX Runtime: NNAPI not available, using optimized CPU", e)
    }
}
```

---

## ✅ 테스트 방법

1. 코드 수정 후 앱 재빌드
2. 디바이스에서 실행
3. Logcat에서 `PERF_FRAME` 로그 확인:
   ```
   PERF_FRAME: ... yolo=XX.X ... total=XXX.X ms
   ```

**기대 결과:**
- `yolo=` 시간이 **20-50ms**로 감소
- `total=` 시간이 **100-200ms**로 감소

---

## 🎯 최종 목표 달성 로드맵

1. ✅ **1단계 적용** → 400-600ms (즉시 적용 가능)
2. ✅ **2단계 적용** → 100-200ms (5분 작업)
3. ✅ **FP16 모델 교체** → 이미 완료 (yolo_640_7_re.onnx)
4. ⏳ **YOLOv8n 학습** → 50-100ms (20분 학습)

**현실적 목표:**
- **즉시 (코드만 수정):** 100-200ms ✅
- **YOLOv8n 추가:** 50-100ms ✅✅

---

## 📝 참고자료

- ONNX Runtime Android: https://onnxruntime.ai/docs/execution-providers/NNAPI-ExecutionProvider.html
- NNAPI: https://developer.android.com/ndk/guides/neuralnetworks
