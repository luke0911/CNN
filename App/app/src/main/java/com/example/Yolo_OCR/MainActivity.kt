package com.example.Yolo_OCR

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import com.example.Yolo_OCR.R
import com.example.Yolo_OCR.camera.CameraManager
import com.example.Yolo_OCR.performance.PerformanceMonitor
import com.example.Yolo_OCR.utils.BitmapUtils
import com.example.Yolo_OCR.utils.Constants
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import android.provider.MediaStore
import android.content.ContentValues
import android.net.Uri
import java.io.OutputStream
import android.os.Build
import android.os.Environment
import android.media.MediaScannerConnection
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.ByteArrayOutputStream
import android.graphics.YuvImage
import android.graphics.ImageFormat
import kotlin.math.max
import kotlin.math.min
import java.util.concurrent.atomic.AtomicInteger
import android.util.Range
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.example.Yolo_OCR.OCR_recorder.poi_coordination
import com.example.Yolo_OCR.OCR_recorder.POI_USAGE_EXAMPLE
import android.util.Log
import android.content.Context

// import com.example.Yolo_OCR.YoloModel  // uncomment if IDE cannot resolve

class MainActivity : ComponentActivity(), SensorEventListener {

    // YOLO model selection (persisted)
    private var currentModel: YoloModel = YoloModel.M896

    // Camera rebind handles (restored for compatibility)
    private var boundCameraProvider: ProcessCameraProvider? = null
    private var imageAnalyzer: ImageAnalysis? = null

    // 순차 처리 (모바일에서는 single thread가 더 빠름)
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val saveExecutor = Executors.newSingleThreadExecutor()

    // Managers
    private lateinit var cameraManager: CameraManager
    private val performanceMonitor = PerformanceMonitor()

    // POI Coordination (방위각 + OCR 기록)
    private val poiCoordination = poi_coordination()

    // Sensor Manager (Global Yaw 추적)
    private lateinit var sensorManager: SensorManager
    private var rotationVectorSensor: Sensor? = null

    // Global Yaw 각도 (0-360도)
    @Volatile private var globalYaw: Float = 0f
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    // Engines (refactor): YOLO detection + OCR processing
    private lateinit var yolo: YoloEngine
    private lateinit var ocr: OcrEngine
    private var uiMaxDet: Int = Constants.UI_MAX_DETECTIONS_DEFAULT

    // 실시간 저장 관리
    @Volatile private var saveDetImages: Boolean = true
    private val lastSavedAt = ConcurrentHashMap<String, Long>()
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("yolo_prefs", MODE_PRIVATE)
    }

    // Detection state management
    @Volatile private var isDetectionRunning: Boolean = false
    @Volatile private var detectionMode: DetectionMode = DetectionMode.TEXT
    
    // Object class filtering
    private val selectedObjectClasses = mutableSetOf<ObjectClass>()
    
    // Latest frame info for overlay transform
    @Volatile private var lastBitmapW: Int = 0
    @Volatile private var lastBitmapH: Int = 0
    @Volatile private var lastRotationDeg: Int = 0

    // Live-tunable thresholds
    @Volatile private var uiConfThresh: Float = Constants.DEFAULT_CONF_THRESHOLD
    @Volatile private var uiIouThresh: Float = Constants.DEFAULT_IOU_THRESHOLD
    @Volatile private var uiMinW: Float = Constants.DEFAULT_MIN_BOX_WIDTH
    @Volatile private var uiMinH: Float = Constants.DEFAULT_MIN_BOX_HEIGHT

    // OCR 최적화 설정
    private val OCR_TIMEOUT_MS = 300L // 타임아웃 단축으로 지연 개선
    private val MIN_BOX_SIZE = 20 // 최소 크기 임계값
    private val MAX_CONCURRENT_OCR = 2 // 동시 OCR 제한 (하나로 조정)
    private val MAX_START_PER_BATCH = 2 // 한번에 시작할 OCR 작업 수
    private val OCR_RETRY_BACKOFF_MS = 2000L // 실패 후 백오프
    private val OCR_MIN_RETRY_GAP_MS = 1500L // 같은 박스 재시도 최소 간격

    // 프레임 관리 객체
    private val currentFrameBitmap = AtomicReference<Bitmap?>(null)
    private val frameId = AtomicLong(0)
    

    // 실시간 OCR 결과 + 현재 박스들
    private val ocrResults = ConcurrentHashMap<String, OcrResult>()
    private val lastOcrTriedAt = ConcurrentHashMap<String, Long>()
    private val failedBackoffUntil = ConcurrentHashMap<String, Long>()
    private val activeOcrCount = AtomicInteger(0)
    private val currentBoxes = mutableListOf<DetBox>()

    // UI 창조
    private var currentOverlay: OverlayView? = null

    // 마지막으로 그린 박스들을 보관 (즉시 OCR 콜백에서 IoU 매칭용)
    private var lastBoxes: List<DetBox> = emptyList()

    data class OcrResult(
        val text: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    private fun setupDetectionControls() {
        val btnStart = findViewById<Button>(R.id.btnStartDetection)
        val btnStop = findViewById<Button>(R.id.btnStopDetection)
        
        // Initially, start button is disabled until camera permission is granted
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
            != PackageManager.PERMISSION_GRANTED) {
            btnStart?.isEnabled = false
        } else {
            btnStart?.isEnabled = true
        }
        
        btnStart?.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
                != PackageManager.PERMISSION_GRANTED) {
                camPerm.launch(Manifest.permission.CAMERA)
            } else if (boundCameraProvider == null) {
                // Camera not started yet, start it first
                val previewView = findViewById<PreviewView>(R.id.previewView)
                val overlay = findViewById<OverlayView>(R.id.overlay)
                startCameraOnly(previewView, overlay)
                startDetection()
            } else {
                // Camera already running, just start detection
                startDetection()
            }
        }
        
        btnStop?.setOnClickListener {
            stopDetection()
        }
        
        updateDetectionControlsUI()
    }

    private fun startDetection() {
        if (isDetectionRunning) return
        
        isDetectionRunning = true
        updateDetectionControlsUI()
        
        Toast.makeText(this, "객체 탐지를 시작합니다.", Toast.LENGTH_SHORT).show()
    }

    private fun stopDetection() {
        if (!isDetectionRunning) return
        
        isDetectionRunning = false
        
        // Don't stop camera, just stop detection processing
        // Reset OCR engine
        if (this::ocr.isInitialized) {
            ocr.reset()
        }
        
        // Clear UI
        synchronized(currentBoxes) { currentBoxes.clear() }
        currentOverlay?.setBoxes(emptyList())
        currentOverlay?.postInvalidate()
        
        // Update detection count
        findViewById<TextView>(R.id.tvCount)?.text = "Detections: 0 (Model: ${currentModel.name})"
        
        updateDetectionControlsUI()
        Toast.makeText(this, "객체 탐지를 중지했습니다.", Toast.LENGTH_SHORT).show()
    }

    private fun updateDetectionControlsUI() {
        val btnStart = findViewById<Button>(R.id.btnStartDetection)
        val btnStop = findViewById<Button>(R.id.btnStopDetection)
        
        if (isDetectionRunning) {
            btnStart?.isEnabled = false
            btnStop?.isEnabled = true
        } else {
            btnStart?.isEnabled = true
            btnStop?.isEnabled = false
        }
    }

    private fun setupDetectionModeControls() {
        val btnToggleMode = findViewById<Button>(R.id.btnToggleDetectionMode)
        val objectClassButtons = findViewById<LinearLayout>(R.id.objectClassButtons)
        
        btnToggleMode?.setOnClickListener {
            when (detectionMode) {
                DetectionMode.TEXT -> {
                    detectionMode = DetectionMode.OBJECT
                    currentModel = YoloModel.OBJECT
                    btnToggleMode.text = "사물→글자"
                    btnToggleMode.setBackgroundColor(0xAACC6600.toInt())
                    objectClassButtons?.visibility = android.view.View.VISIBLE
                    
                    // Initialize YOLO engine with object model
                    try {
                        // Use very low confidence threshold for object detection debugging
                        val objectConfThresh = 0.01f  // Extremely low for debugging
                        android.util.Log.d("OBJECT_DEBUG", "Using confidence threshold: $objectConfThresh (vs text: $uiConfThresh)")
                        
                        yolo = YoloEngine(
                            assets,
                            initialModel = YoloModel.OBJECT,
                            confThresh = objectConfThresh,
                            iouThresh = uiIouThresh,
                            minW = uiMinW,
                            minH = uiMinH
                        )
                        Toast.makeText(this, "사물 인식 모드로 전환됨", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        android.util.Log.e("MODEL_LOAD", "Failed to load object.onnx model: ${e.message}", e)
                        
                        // Revert to text mode on failure
                        detectionMode = DetectionMode.TEXT
                        currentModel = YoloModel.M896
                        btnToggleMode.text = "글자→사물"
                        btnToggleMode.setBackgroundColor(0xAA0066CC.toInt())
                        objectClassButtons?.visibility = View.GONE
                        
                        val errorMsg = when {
                            e.message?.contains("IR version") == true -> {
                                android.util.Log.i("MODEL_INFO", "ONNX Runtime compatibility issue: ${e.message}")
                                "object.onnx 모델 버전이 호환되지 않습니다.\n현재 ONNX Runtime: 1.20.0\n필요: IR version 11 지원"
                            }
                            e.message?.contains("model.cc") == true ->
                                "object.onnx 모델 파일에 문제가 있습니다."
                            e.message?.contains("Failed to load model") == true ->
                                "모델 로딩 실패. 파일 확인 필요."
                            else -> 
                                "사물 인식 모델을 로드할 수 없습니다: ${e.message}"
                        }
                        
                        Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }
                }
                DetectionMode.OBJECT -> {
                    detectionMode = DetectionMode.TEXT
                    currentModel = YoloModel.M896
                    btnToggleMode.text = "글자→사물"
                    btnToggleMode.setBackgroundColor(0xAA0066CC.toInt())
                    objectClassButtons?.visibility = android.view.View.GONE
                    
                    // Initialize YOLO engine with text model
                    try {
                        yolo = YoloEngine(
                            assets,
                            initialModel = YoloModel.M896,
                            confThresh = uiConfThresh,
                            iouThresh = uiIouThresh,
                            minW = uiMinW,
                            minH = uiMinH
                        )
                        Toast.makeText(this, "글자 인식 모드로 전환됨", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        android.util.Log.e("MODEL_LOAD", "Failed to load text model: ${e.message}", e)
                        Toast.makeText(this, "텍스트 모델 로드 실패: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            updateModelDisplay()
        }
    }

    private fun setupObjectClassButtons() {
        val classButtons = mapOf(
            R.id.btnDoor to ObjectClass.DOOR,
            R.id.btnExitSign to ObjectClass.EXIT_SIGN,
            R.id.btnFireExtinguisher to ObjectClass.FIRE_EXTINGUISHER,
            R.id.btnElevator to ObjectClass.ELEVATOR,
            R.id.btnRestroomSign to ObjectClass.RESTROOM_SIGN,
            R.id.btnWaterDispenser to ObjectClass.WATER_DISPENSER,
            R.id.btnStairSign to ObjectClass.STAIR_SIGN
        )

        classButtons.forEach { (buttonId, objectClass) ->
            findViewById<android.widget.ToggleButton>(buttonId)?.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    selectedObjectClasses.add(objectClass)
                } else {
                    selectedObjectClasses.remove(objectClass)
                }

                val selectedCount = selectedObjectClasses.size
                val statusText = if (selectedCount == 0) {
                    "모든 사물 인식 중지"
                } else {
                    "${selectedCount}개 클래스 선택됨"
                }

                Toast.makeText(this, statusText, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupPoiButtons() {
        // 기준점 설정 버튼
        findViewById<Button>(R.id.btnSetPoiReference)?.setOnClickListener {
            setPoiReferenceAngle()
        }

        // POI 저장 버튼
        findViewById<Button>(R.id.btnSavePoi)?.setOnClickListener {
            savePoiToCsv()
        }

        // POI 초기화 버튼
        findViewById<Button>(R.id.btnClearPoi)?.setOnClickListener {
            clearPoiData()
        }
    }

    private fun updateModelDisplay() {
        val boxesNow = synchronized(currentBoxes) { currentBoxes.size }
        val modeText = when (detectionMode) {
            DetectionMode.TEXT -> "${currentModel.name}"
            DetectionMode.OBJECT -> "사물"
        }
        findViewById<TextView>(R.id.tvCount)?.text = "Detections: ${boxesNow} (${modeText})"
    }

    private fun updateModelButtonText() {
        val btnToggleModel = findViewById<Button>(R.id.btnToggleModel)

        // Show NEXT model that will be switched to
        val nextModel = when (currentModel) {
            YoloModel.M896 -> YoloModel.M640
            YoloModel.M640 -> YoloModel.M896
            else -> YoloModel.M896
        }

        val buttonText = when (nextModel) {
            YoloModel.M896 -> "→ M896"
            YoloModel.M640 -> "→ M640"
            else -> "모델 전환"
        }
        btnToggleModel?.text = buttonText

        // Change button color based on NEXT model
        val buttonColor = when (nextModel) {
            YoloModel.M896 -> 0xFF4CAF50.toInt()  // Green
            YoloModel.M640 -> 0xFFFF9800.toInt()  // Orange
            else -> 0xFF666666.toInt()  // Gray
        }
        btnToggleModel?.setBackgroundColor(buttonColor)
    }

    @OptIn(ExperimentalGetImage::class)
    private fun startCameraOnly(previewView: PreviewView, overlay: OverlayView) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            boundCameraProvider = cameraProvider
            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
            val analyzer = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                // Note: setTargetFrameRate not available in this CameraX version
                // Using FRAME_SKIP_INTERVAL instead for performance
                .build().also { ia ->
                    ia.setAnalyzer(analysisExecutor) { image ->
                        // Check if detection is running before processing
                        if (!isDetectionRunning) {
                            image.close()
                            return@setAnalyzer
                        }

                        // ===== PERF: Frame anchors =====
                        val fid = frameId.incrementAndGet()

                        // Frame skip optimization: process every Nth frame
                        if (fid % (Constants.FRAME_SKIP_INTERVAL + 1) != 0L) {
                            image.close()
                            return@setAnalyzer
                        }

                        val tsCamNs = image.imageInfo.timestamp
                        val t0Ns = SystemClock.elapsedRealtimeNanos() // analyzer entry time
                        val camDelayMs = (t0Ns - tsCamNs) / 1e6
                        android.util.Log.d(
                            "PERF_FRAME",
                            "model=${currentModel.name} fid=${fid} ENTER analyzer camDelay=${"%.1f".format(camDelayMs)} ms [SKIP=${Constants.FRAME_SKIP_INTERVAL}]"
                        )
                        try {
                            // 1) Convert YUV -> RGB Bitmap (safe)
                            val tYuv0Ns = SystemClock.elapsedRealtimeNanos()
                            val bmp = BitmapUtils.yuvToRgbSafe(image)
                            val tYuv1Ns = SystemClock.elapsedRealtimeNanos()
                            val yuvMs = (tYuv1Ns - tYuv0Ns) / 1e6
                            val rotation = image.imageInfo.rotationDegrees
                            val tRot0Ns = SystemClock.elapsedRealtimeNanos()
                            val displayBmp = BitmapUtils.ensureRotated(bmp, rotation)
                            val tRot1Ns = SystemClock.elapsedRealtimeNanos()
                            val rotMs = (tRot1Ns - tRot0Ns) / 1e6
                            currentFrameBitmap.set(displayBmp)
                            // 2) Update last frame info
                            lastBitmapW = displayBmp.width
                            lastBitmapH = displayBmp.height
                            lastRotationDeg = 0
                            // 3) YOLO detection
                            val tYolo0Ns = SystemClock.elapsedRealtimeNanos()
                            val rawDets: List<DetBox> = try {
                                val detections = yolo.detect(displayBmp)
                                android.util.Log.d("YOLO_DEBUG", "Mode: $detectionMode, Raw detections: ${detections.size}")
                                detections.forEachIndexed { i, det ->
                                    android.util.Log.v("YOLO_DEBUG", "Detection $i: cls=${det.cls}, conf=${det.conf}, box=(${det.left},${det.top},${det.right},${det.bottom})")
                                }
                                detections
                            } catch (e: Exception) {
                                android.util.Log.e("YOLO", "Detection failed", e)
                                emptyList()
                            }
                            val tYolo1Ns = SystemClock.elapsedRealtimeNanos()
                            val yoloMs = (tYolo1Ns - tYolo0Ns) / 1e6

                            // 4) Apply class filtering for object detection mode
                            val filteredDets = when (detectionMode) {
                                DetectionMode.TEXT -> rawDets
                                DetectionMode.OBJECT -> {
                                    android.util.Log.d("OBJECT_FILTER", "Raw detections: ${rawDets.size}, Selected classes: ${selectedObjectClasses.size}")
                                    
                                    if (selectedObjectClasses.isEmpty()) {
                                        android.util.Log.w("OBJECT_FILTER", "No classes selected! Showing all objects for debugging")
                                        // For debugging: show all objects when no classes selected
                                        rawDets.map { det ->
                                            val objectClass = ObjectClass.fromId(det.cls)
                                            android.util.Log.d("OBJECT_FILTER", "Raw detection: cls=${det.cls} (${objectClass?.displayName}), conf=${det.conf}")
                                            det
                                        }
                                    } else {
                                        val filtered = rawDets.filter { det ->
                                            val objectClass = ObjectClass.fromId(det.cls)
                                            val isSelected = objectClass != null && selectedObjectClasses.contains(objectClass)
                                            android.util.Log.v("OBJECT_FILTER", "Detection cls=${det.cls} (${objectClass?.displayName}), selected=$isSelected")
                                            isSelected
                                        }
                                        android.util.Log.d("OBJECT_FILTER", "After filtering: ${filtered.size} detections")
                                        filtered
                                    }
                                }
                            }

                            // 5) OCR processing (only for text mode)
                            val tOcr0Ns = SystemClock.elapsedRealtimeNanos()
                            val processedDets = when (detectionMode) {
                                DetectionMode.TEXT -> {
                                    ocr.process(filteredDets.take(uiMaxDet), displayBmp)
                                }
                                DetectionMode.OBJECT -> {
                                    // For object mode, add class names as text
                                    filteredDets.map { det ->
                                        val objectClass = ObjectClass.fromId(det.cls)
                                        det.copy(text = objectClass?.displayName ?: "알 수 없음")
                                    }
                                }
                            }
                            val tOcr1Ns = SystemClock.elapsedRealtimeNanos()
                            val ocrMs = (tOcr1Ns - tOcr0Ns) / 1e6

                            // POI 기록 (TEXT 모드일 때만, OCR 로직과 분리)
                            if (detectionMode == DetectionMode.TEXT) {
                                processedDets.forEach { box ->
                                    if (!box.text.isNullOrBlank()) {
                                        try {
                                            recordPoiIfNeeded(box.text!!)
                                        } catch (e: Exception) {
                                            android.util.Log.w("POI", "Failed to record POI: ${e.message}")
                                        }
                                    }
                                }
                            }

                            // 5) Update overlay and UI
                            val tEnd = SystemClock.elapsedRealtimeNanos()
                            val totalMs = (tEnd - t0Ns) / 1e6
                            mainScope.launch {
                                overlay.updateFrameInfo(displayBmp.width, displayBmp.height, 0)
                                overlay.setBoxes(processedDets)
                                synchronized(currentBoxes) {
                                    currentBoxes.clear()
                                    currentBoxes.addAll(processedDets)
                                }
                                lastBoxes = processedDets
                                updateModelDisplay()
                            }

                            performanceMonitor.logFrameTiming(
                                com.example.Yolo_OCR.performance.PerformanceMonitor.FrameTiming(
                                    frameId = fid,
                                    camDelayMs = camDelayMs,
                                    yuvMs = yuvMs,
                                    rotMs = rotMs,
                                    yoloMs = yoloMs,
                                    ocrMs = ocrMs,
                                    uiMs = 0.0, // UI timing not available in this context
                                    totalMs = totalMs,
                                    boxCount = processedDets.size,
                                    textCount = processedDets.count { !it.text.isNullOrBlank() }
                                ),
                                currentModel.name
                            )

                        } catch (e: Exception) {
                            android.util.Log.e("CAMERA", "Frame processing error", e)
                        } finally {
                            image.close()
                        }
                    }
                }

            imageAnalyzer = analyzer
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, analyzer
                )
            } catch (exc: Exception) {
                android.util.Log.e("CAMERA", "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun saveThresholds(conf: Float, iou: Float, minW: Float, minH: Float) {
        prefs.edit()
            .putFloat("conf", conf)
            .putFloat("iou", iou)
            .putFloat("minW", minW)
            .putFloat("minH", minH)
            .apply()
        uiConfThresh = conf
        uiIouThresh = iou
        uiMinW = minW
        uiMinH = minH
        if (this::yolo.isInitialized) {
            yolo.updateThresholds(uiConfThresh, uiIouThresh, uiMinW, uiMinH)
        }
    }

    private val camPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            // Permission granted, start camera and enable detection controls
            val previewView = findViewById<PreviewView>(R.id.previewView)
            val overlay = findViewById<OverlayView>(R.id.overlay)
            startCameraOnly(previewView, overlay)
            findViewById<Button>(R.id.btnStartDetection)?.isEnabled = true
            Toast.makeText(this, "카메라가 시작되었습니다. 객체 탐지를 시작해보세요.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "카메라 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContentView(R.layout.activity_main)
        val previewView = findViewById<PreviewView>(R.id.previewView)
        val overlay = findViewById<OverlayView>(R.id.overlay)
        currentOverlay = overlay

        // 저장된 값들 먼저 로드
        uiConfThresh = prefs.getFloat("conf", uiConfThresh)
        uiIouThresh = prefs.getFloat("iou", uiIouThresh)
        uiMinW = prefs.getFloat("minW", uiMinW)
        uiMinH = prefs.getFloat("minH", uiMinH)

        // Restore selected model (defaults to M896)
        runCatching {
            val saved = prefs.getString("model", YoloModel.M896.name) ?: YoloModel.M896.name
            currentModel = YoloModel.valueOf(saved)
        }

        // Initialize engines with current thresholds and selected model
        yolo = YoloEngine(
            assets,
            initialModel = currentModel,
            confThresh = uiConfThresh,
            iouThresh  = uiIouThresh,
            minW       = uiMinW,
            minH       = uiMinH
        )
        ocr = OcrEngine(lastRotation = { lastRotationDeg })

        // Initialize sensor manager for global yaw tracking
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        if (rotationVectorSensor == null) {
            Log.w("MainActivity", "Rotation Vector Sensor not available")
            Toast.makeText(this, "방위각 센서가 없습니다. POI 기록 기능을 사용할 수 없습니다.", Toast.LENGTH_LONG).show()
        }

        // NOW set up UI after engines are initialized
        // Show current model in UI
        findViewById<TextView>(R.id.tvCount)?.text = "Detections: 0 (Model: ${currentModel.name})"

        // Toggle button (only works in text mode)
        val btnToggleModel = findViewById<Button>(R.id.btnToggleModel)
        updateModelButtonText()

        btnToggleModel?.setOnClickListener {
            if (detectionMode == DetectionMode.TEXT) {
                toggleModel()
                updateModelDisplay()
                updateModelButtonText()
                Toast.makeText(this, "모델 전환: ${currentModel.name}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "사물 인식 모드에서는 모델 변경이 불가능합니다", Toast.LENGTH_SHORT).show()
            }
        }

        // Setup start/stop buttons
        setupDetectionControls()

        // Setup detection mode and class selection buttons
        setupDetectionModeControls()
        setupObjectClassButtons()

        // Setup POI recording buttons
        setupPoiButtons()

        // Start camera immediately if permission is granted
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // Permission will be requested when start button is pressed
        } else {
            // Start camera but detection is off by default
            startCameraOnly(previewView, overlay)
        }
        // OCR 결과 즉시 UI 반영 콜백 등록
        ocr.onTextAvailable = { detectedBox, ocrText ->
            android.util.Log.i("OCR_CALLBACK", "OCR callback received: text='${ocrText}' for box at (${detectedBox.left}, ${detectedBox.top})")

            mainScope.launch {
                val overlay = currentOverlay ?: return@launch

                // 현재 화면에 표시 중인 박스들 가져오기
                val currentSnapshot: List<DetBox> = synchronized(currentBoxes) { currentBoxes.toList() }

                // IoU 기반으로 매칭되는 박스 찾기 (임계값 0.5)
                val updatedBoxes = currentSnapshot.map { displayBox ->
                    val iouValue = com.example.Yolo_OCR.utils.GeometryUtils.iou(displayBox, detectedBox)
                    if (iouValue >= 0.5f) {
                        android.util.Log.i("OCR_CALLBACK", "Matched box with IoU=${iouValue}, adding text: '${ocrText}'")
                        displayBox.copy(text = ocrText)
                    } else displayBox
                }

                // 매칭되는 박스가 없으면(옵션) 추가할 수도 있음 — 현재는 로그만 남김
                val matched = updatedBoxes.any { it.text == ocrText }
                if (!matched) {
                    android.util.Log.w("OCR_CALLBACK", "No matching box found for OCR text '${ocrText}', IoU too low")
                    // 필요 시 아래 주석 해제하여 새 박스를 추가
                    // updatedBoxes = updatedBoxes + detectedBox.copy(text = ocrText)
                }

                // UI 상태 및 Overlay 갱신
                synchronized(currentBoxes) {
                    currentBoxes.clear()
                    currentBoxes.addAll(updatedBoxes)
                }
                lastBoxes = updatedBoxes
                overlay.setBoxes(updatedBoxes)
                overlay.postInvalidate() // 메인 스레드에서 안전한 무효화

                android.util.Log.i("OCR_CALLBACK", "UI updated with ${updatedBoxes.count { !it.text.isNullOrBlank() }} text boxes out of ${updatedBoxes.size} total boxes")
            }
        }
    }

    @OptIn(ExperimentalGetImage::class)
    private fun startCamera(previewView: PreviewView, overlay: OverlayView) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            boundCameraProvider = cameraProvider

            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            val analyzer = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                // Note: setTargetFrameRate not available in this CameraX version
                // Using FRAME_SKIP_INTERVAL instead for performance
                .build().also { ia ->
                    ia.setAnalyzer(analysisExecutor) { image ->
                        // ===== PERF: Frame anchors =====
                        val fid = frameId.incrementAndGet()

                        // Frame skip optimization: process every Nth frame
                        if (fid % (Constants.FRAME_SKIP_INTERVAL + 1) != 0L) {
                            image.close()
                            return@setAnalyzer
                        }

                        val tsCamNs = image.imageInfo.timestamp
                        val t0Ns = SystemClock.elapsedRealtimeNanos() // analyzer entry time
                        val camDelayMs = (t0Ns - tsCamNs) / 1e6
                        android.util.Log.d(
                            "PERF_FRAME",
                            "model=${currentModel.name} fid=${fid} ENTER analyzer camDelay=${"%.1f".format(camDelayMs)} ms [SKIP=${Constants.FRAME_SKIP_INTERVAL}]"
                        )
                        try {
                            // Check if detection is running before processing
                            if (!isDetectionRunning) {
                                image.close()
                                return@setAnalyzer
                            }
                            
                            // 1) Convert YUV -> RGB Bitmap (safe)
                            val tYuv0Ns = SystemClock.elapsedRealtimeNanos()
                            val bmp = yuvToRgbSafe(image)
                            val tYuv1Ns = SystemClock.elapsedRealtimeNanos()
                            val yuvMs = (tYuv1Ns - tYuv0Ns) / 1e6

                            val rotation = image.imageInfo.rotationDegrees
                            val tRot0Ns = SystemClock.elapsedRealtimeNanos()
                            val displayBmp = ensureRotated(bmp, rotation)
                            val tRot1Ns = SystemClock.elapsedRealtimeNanos()
                            val rotMs = (tRot1Ns - tRot0Ns) / 1e6
                            currentFrameBitmap.set(displayBmp)

                            // 2) Update last frame info
                            lastBitmapW = displayBmp.width
                            lastBitmapH = displayBmp.height
                            lastRotationDeg = 0

                            // 3) YOLO detection
                            val tYolo0Ns = SystemClock.elapsedRealtimeNanos()
                            val dets: List<DetBox> = try {
                                yolo.detect(displayBmp)
                            } catch (e: Exception) {
                                android.util.Log.e("YOLO", "detect failed: ${e.message}")
                                emptyList()
                            }
                            val tYolo1Ns = SystemClock.elapsedRealtimeNanos()
                            val yoloMs = (tYolo1Ns - tYolo0Ns) / 1e6

                            // 4) OCR processing with real-time results
                            android.util.Log.d("OCR_DEBUG", "Starting OCR processing for ${dets.size} detections")
                            val tProc0Ns = SystemClock.elapsedRealtimeNanos()
                            val withText: List<DetBox> = try {
                                val processedBoxes = ocr.process(dets, displayBmp)
                                android.util.Log.d("OCR_DEBUG", "OCR process returned ${processedBoxes.size} boxes")
                                // OCR 결과 로깅
                                processedBoxes.forEach { box ->
                                    if (!box.text.isNullOrBlank()) {
                                        android.util.Log.i("OCR_RESULT", "Text detected: '${box.text}' at (${box.left}, ${box.top})")
                                    } else {
                                        android.util.Log.d("OCR_DEBUG", "Box at (${box.left}, ${box.top}) has no text yet")
                                    }
                                }

                                // POI 기록 (별도로 처리, OCR 로직과 분리)
                                processedBoxes.forEach { box ->
                                    if (!box.text.isNullOrBlank()) {
                                        try {
                                            recordPoiIfNeeded(box.text!!)
                                        } catch (e: Exception) {
                                            // POI 기록 실패해도 OCR은 계속 진행
                                            android.util.Log.w("POI", "Failed to record POI: ${e.message}")
                                        }
                                    }
                                }
                                processedBoxes
                            } catch (e: Exception) {
                                android.util.Log.e("OCR", "process failed: ${e.message}", e)
                                dets
                            }
                            val tProc1Ns = SystemClock.elapsedRealtimeNanos()
                            val procMs = (tProc1Ns - tProc0Ns) / 1e6
                            val textCnt = withText.count { !it.text.isNullOrBlank() }

                            // 5) Take top-K for UI (reduce load)
                            val limited = withText.sortedByDescending { it.conf }.take(uiMaxDet)

                            // 현재 박스 상태 저장 (콜백에서 참조용)
                            synchronized(currentBoxes) {
                                currentBoxes.clear()
                                currentBoxes.addAll(limited)
                            }

                            // 6) Update overlay on Main thread - 즉시 반영
                            val tUiSchedNs = SystemClock.elapsedRealtimeNanos()
                            mainScope.launch {
                                val tUiStartNs = SystemClock.elapsedRealtimeNanos()
                                overlay.updateFrameInfo(
                                    bitmapW = lastBitmapW,
                                    bitmapH = lastBitmapH,
                                    rotationDeg = 0
                                )
                                lastBoxes = limited
                                overlay.setBoxes(limited)
                                overlay.postInvalidate() // invalidate() 대신 postInvalidate() 사용
                                // Update model badge with current count
                                val boxesCnt = limited.size
                                findViewById<TextView>(R.id.tvCount)?.text =
                                    "Detections: ${boxesCnt} (Model: ${currentModel.name})"
                                val tUiEndNs = SystemClock.elapsedRealtimeNanos()
                                val uiMs = (tUiEndNs - tUiStartNs) / 1e6
                                val schedToUiMs = (tUiStartNs - tUiSchedNs) / 1e6
                                val totalMs = (tUiEndNs - tsCamNs) / 1e6

                                val logEvery = 1L
                                if (fid % logEvery == 0L) {
                                    android.util.Log.d(
                                        "PERF_FRAME",
                                        "model=${currentModel.name} fid=${fid} cam=${"%.1f".format(camDelayMs)} yuv=${"%.1f".format(yuvMs)} rot=${"%.1f".format(rotMs)} " +
                                                "yolo=${"%.1f".format(yoloMs)} ocr=${"%.1f".format(procMs)} ui=${"%.1f".format(uiMs)} schedUi=${"%.1f".format(schedToUiMs)} " +
                                                "total=${"%.1f".format(totalMs)} ms (boxes=${boxesCnt} txt=${textCnt})"
                                    )
                                }
                            }

                            // 7) Performance monitoring
                            monitorPerformance()

                            // 8) OCR 결과가 있는 박스들 실시간 로깅
                            limited.filter { !it.text.isNullOrBlank() }.forEach { box ->
                                android.util.Log.i("REAL_TIME_OCR", "Box: conf=${box.conf}, text='${box.text}'")
                            }

                        } finally {
                            image.close()
                        }
                    }
                    imageAnalyzer = ia
                }

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analyzer
            )
        }, ContextCompat.getMainExecutor(this))
    }

    // DetCrop 저장용 헬퍼
    private fun cropBitmapForBox(sourceBitmap: Bitmap, box: DetBox): Bitmap? {
        if (sourceBitmap.isRecycled) return null
        val boxWidth = (box.right - box.left).toInt()
        val boxHeight = (box.bottom - box.top).toInt()
        if (boxWidth <= 0 || boxHeight <= 0) return null

        val x = max(0f, min(sourceBitmap.width - 1f, box.left)).toInt()
        val y = max(0f, min(sourceBitmap.height - 1f, box.top)).toInt()
        val w = max(1f, min(sourceBitmap.width - x.toFloat(), boxWidth.toFloat())).toInt()
        val h = max(1f, min(sourceBitmap.height - y.toFloat(), boxHeight.toFloat())).toInt()

        return try {
            Bitmap.createBitmap(sourceBitmap, x, y, w, h)
        } catch (_: Exception) {
            null
        }
    }

    private fun sanitizeFilename(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }

    private fun saveBitmapToDownloads(bmp: Bitmap, baseName: String): Uri? {
        val timestamp = System.currentTimeMillis()
        val displayName = sanitizeFilename("${baseName}_${timestamp}.jpg")

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // API 29+: Use MediaStore Downloads collection (scoped storage)
            val resolver = contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, "image/jpeg")
                put(MediaStore.Downloads.IS_PENDING, 1)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/yolo_img")
            }

            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            try {
                resolver.openOutputStream(uri)?.use { os: OutputStream ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 92, os)
                    os.flush()
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                null
            }
        } else {
            // API 28 이하: 직접 Downloads 경로에 저장 후 미디어 스캔
            try {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val appDir = File(downloadsDir, "yolo_img")
                if (!appDir.exists()) appDir.mkdirs()

                val outFile = File(appDir, displayName)
                FileOutputStream(outFile).use { fos ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 92, fos)
                    fos.flush()
                }

                // 새 파일을 갤러리/파일앱에 반영
                MediaScannerConnection.scanFile(
                    this,
                    arrayOf(outFile.absolutePath),
                    arrayOf("image/jpeg"),
                    null
                )

                Uri.fromFile(outFile)
            } catch (ioe: IOException) {
                null
            }
        }
    }

    private fun scheduleSaveDetCrop(box: DetBox, boxKey: String, ocrText: String? = null) {
        if (!saveDetImages) return
        val now = System.currentTimeMillis()
        val last = lastSavedAt[boxKey] ?: 0L
        if (now - last < Constants.SAVE_MIN_INTERVAL_MS) return
        lastSavedAt[boxKey] = now

        val src = currentFrameBitmap.get() ?: return
        val crop = cropBitmapForBox(src, box) ?: return

        saveExecutor.execute {
            val label = if (!ocrText.isNullOrBlank()) "_${sanitizeFilename(ocrText)}" else ""
            val base = "yolo_det_${boxKey}${label}"
            saveBitmapToDownloads(crop, base)
            runCatching { crop.recycle() }
        }
    }

    // OCR용 이미지 전처리 강화
    private fun enhanceImageForBetterOcr(bitmap: Bitmap): Bitmap {
        // 1) 이미지 확대 (최소 크기 보장)
        val targetSize = 160
        val scaledBitmap = if (bitmap.width < targetSize || bitmap.height < targetSize) {
            val scale = max(targetSize.toFloat() / bitmap.width, targetSize.toFloat() / bitmap.height)
            val newWidth = (bitmap.width * scale).toInt()
            val newHeight = (bitmap.height * scale).toInt()
            Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        } else {
            bitmap
        }

        // 2) 대비 및 밝기 개선 필터
        val enhanced = Bitmap.createBitmap(scaledBitmap.width, scaledBitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(enhanced)

        // 대비 강화 매트릭스
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply {
                set(floatArrayOf(
                    1.4f, 0f, 0f, 0f, -40f,    // Red (대비 강화)
                    0f, 1.4f, 0f, 0f, -40f,    // Green
                    0f, 0f, 1.4f, 0f, -40f,    // Blue
                    0f, 0f, 0f, 1f, 0f         // Alpha
                ))
            })
        }

        canvas.drawBitmap(scaledBitmap, 0f, 0f, paint)

        if (scaledBitmap != bitmap) {
            scaledBitmap.recycle()
        }

        return enhanced
    }



    // 기존 헬퍼 함수들
    private data class LetterboxResult(
        val bmp: Bitmap, val scale: Float, val padX: Float, val padY: Float
    )

    private fun letterbox(src: Bitmap, dstW: Int, dstH: Int): LetterboxResult {
        val w = src.width
        val h = src.height
        val r = min(dstW.toFloat() / w, dstH.toFloat() / h)
        val newW = (w * r).toInt()
        val newH = (h * r).toInt()

        val resized = Bitmap.createScaledBitmap(src, newW, newH, true)
        val out = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val padX = ((dstW - newW) / 2f)
        val padY = ((dstH - newH) / 2f)
        canvas.drawColor(Color.rgb(114, 114, 114))
        canvas.drawBitmap(resized, padX, padY, null)
        return LetterboxResult(out, r, padX, padY)
    }

    private fun bitmapToFloat32Tensor(bmp: Bitmap): Array<Array<Array<FloatArray>>> {
        val w = bmp.width
        val h = bmp.height
        val out = Array(1) { Array(3) { Array(h) { FloatArray(w) } } }
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        var idx = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = pixels[idx++]
                val r = ((p shr 16) and 0xFF) / 255f
                val g = ((p shr 8) and 0xFF) / 255f
                val b = (p and 0xFF) / 255f
                out[0][0][y][x] = r
                out[0][1][y][x] = g
                out[0][2][y][x] = b
            }
        }
        return out
    }

    private fun parseYoloOutputs(
        as2d: Array<FloatArray>?,
        raw: Any,
        confThresh: Float
    ): MutableList<DetBox> {
        val dets = mutableListOf<DetBox>()

        fun emitRow6(row: FloatArray) {
            if (row.size != 6) return
            val x1 = row[0]
            val y1 = row[1]
            val x2 = row[2]
            val y2 = row[3]
            val score = row[4]
            if (score < confThresh) return
            val clsId = kotlin.math.max(0f, row[5]).toInt()
            dets += DetBox(x1, y1, x2, y2, score, clsId)
        }

        when {
            raw is Array<*> && raw.size == 1 && raw[0] is Array<*> -> {
                val arrN = raw[0] as Array<FloatArray>
                for (j in arrN.indices) emitRow6(arrN[j])
            }
            as2d != null -> {
                val C = as2d.size
                val N = as2d[0].size
                if (C == 6) {
                    for (j in 0 until N) {
                        val row = FloatArray(6) { i -> as2d[i][j] }
                        emitRow6(row)
                    }
                }
            }
        }
        return dets
    }

    private fun nms(boxes: List<DetBox>, iouThresh: Float): List<DetBox> {
        val sorted = boxes.sortedByDescending { it.conf }.toMutableList()
        val kept = mutableListOf<DetBox>()
        val removed = BooleanArray(sorted.size)
        for (i in sorted.indices) {
            if (removed[i]) continue
            val a = sorted[i]
            kept += a
            for (j in i + 1 until sorted.size) {
                if (removed[j]) continue
                val b = sorted[j]
                if (a.cls != b.cls) continue
                if (com.example.Yolo_OCR.utils.GeometryUtils.iou(a, b) > iouThresh) removed[j] = true
            }
        }
        return kept
    }

    private fun iou(a: DetBox, b: DetBox): Float {
        val x1 = max(a.left, b.left)
        val y1 = max(a.top, b.top)
        val x2 = min(a.right, b.right)
        val y2 = min(a.bottom, b.bottom)
        val inter = max(0f, x2 - x1) * max(0f, y2 - y1)
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        return inter / (areaA + areaB - inter + 1e-6f)
    }

    override fun onDestroy() {
        // Stop detection and camera
        isDetectionRunning = false
        boundCameraProvider?.unbindAll()
        
        super.onDestroy()
        mainScope.cancel()
        analysisExecutor.shutdown()
        saveExecutor.shutdown()
        performanceMonitor.reset()

        // 메모리 정리
        currentFrameBitmap.get()?.let {
            if (!it.isRecycled) {
                it.recycle()
            }
        }
    }

    private fun restartCameraPipeline() {
        boundCameraProvider?.unbindAll()
        val previewView = findViewById<PreviewView>(R.id.previewView)
        val overlay = findViewById<OverlayView>(R.id.overlay)
        startCamera(previewView, overlay)
    }

    private fun switchModel(newModel: YoloModel) {
        if (newModel == currentModel) return

        try {
            // Switch the YOLO session (no need to unbind camera)
            yolo.switchModel(newModel)

            // Reset OCR tracking state to avoid stale boxes across model sizes
            if (this::ocr.isInitialized) {
                ocr.reset()
            }

            currentModel = newModel
            prefs.edit().putString("model", newModel.name).apply()

            // Clear current boxes
            synchronized(currentBoxes) { currentBoxes.clear() }
            currentOverlay?.setBoxes(emptyList())
            currentOverlay?.postInvalidate()

            android.util.Log.d("MODEL_SWITCH", "Successfully switched to model: ${newModel.name}")
        } catch (e: Exception) {
            android.util.Log.e("MODEL_SWITCH", "Failed to switch model to ${newModel.name}: ${e.message}", e)
            Toast.makeText(this, "모델 전환 실패: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // Convenience toggle (attach to a button/menu)
    private fun toggleModel() {
        val next = when (currentModel) {
            YoloModel.M896 -> YoloModel.M640
            YoloModel.M640 -> YoloModel.M896
            else -> YoloModel.M896 // fallback for OBJECT mode
        }
        switchModel(next)
    }
    
    // 회전 보정: Bitmap을 지정된 각도로 회전 (0이면 그대로 반환)
    private fun ensureRotated(src: Bitmap, rotation: Int): Bitmap {
        val rot = ((rotation % 360) + 360) % 360
        if (rot == 0) return src
        val m = Matrix().apply { postRotate(rot.toFloat()) }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out != src && !src.isRecycled) src.recycle()
        return out
    }
    
    // YUV to RGB conversion (restored)
    private fun yuvToRgbSafe(image: ImageProxy): Bitmap {
        return BitmapUtils.yuvToRgbSafe(image)
    }
    
    // Performance monitoring (restored)
    private fun monitorPerformance() {
        performanceMonitor.logPerformanceStats(ocrResults.size, activeOcrCount.get())
    }

    // ===== Sensor Management (Global Yaw Tracking) =====

    override fun onResume() {
        super.onResume()
        // 센서 등록
        rotationVectorSensor?.let {
            sensorManager.registerListener(
                this,
                it,
                SensorManager.SENSOR_DELAY_UI  // UI 업데이트용 (20Hz)
            )
            Log.d("MainActivity", "Rotation Vector Sensor registered")
        }
    }

    override fun onPause() {
        super.onPause()
        // 센서 해제 (배터리 절약)
        sensorManager.unregisterListener(this)
        Log.d("MainActivity", "Sensors unregistered")
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            // Rotation Vector → Rotation Matrix
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

            // Rotation Matrix → Orientation Angles (azimuth, pitch, roll)
            SensorManager.getOrientation(rotationMatrix, orientationAngles)

            // Azimuth (방위각) = orientationAngles[0]
            // - 범위: -π ~ +π (라디안)
            // - 북쪽: 0, 동쪽: π/2, 남쪽: ±π, 서쪽: -π/2

            // 라디안 → 도 변환, 0~360도로 정규화
            var azimuthDeg = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
            if (azimuthDeg < 0) azimuthDeg += 360f

            // Global Yaw 업데이트
            globalYaw = azimuthDeg

            // UI 업데이트 (디버깅용 - 옵션)
            // runOnUiThread {
            //     findViewById<TextView>(R.id.tvYaw)?.text = "Yaw: ${globalYaw.toInt()}°"
            // }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // 센서 정확도 변경 시 (필요 시 경고 표시)
        when (accuracy) {
            SensorManager.SENSOR_STATUS_UNRELIABLE -> {
                Log.w("MainActivity", "Sensor accuracy: UNRELIABLE")
            }
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> {
                Log.w("MainActivity", "Sensor accuracy: LOW")
            }
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> {
                Log.d("MainActivity", "Sensor accuracy: MEDIUM")
            }
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> {
                Log.d("MainActivity", "Sensor accuracy: HIGH")
            }
        }
    }

    // ===== POI Recording Functions =====

    // 기준점 설정 (버튼 클릭 시 호출)
    private fun setPoiReferenceAngle() {
        poiCoordination.setReferenceAngle(globalYaw)
        Toast.makeText(
            this,
            "기준점 설정 완료!\n현재 방향(${globalYaw.toInt()}°)을 0°로 설정했습니다.",
            Toast.LENGTH_LONG
        ).show()
        Log.d("POI", "Reference angle set: ${globalYaw}°")
    }

    // OCR 텍스트 감지 시 POI 기록 (기존 OCR 처리 코드에서 호출)
    private fun recordPoiIfNeeded(text: String) {
        val hasRef = poiCoordination.hasReferenceAngle()
        Log.d("POI_DEBUG", "recordPoiIfNeeded called: text='$text', hasRef=$hasRef, globalYaw=$globalYaw")

        if (hasRef && text.isNotBlank()) {
            poiCoordination.addPoi(globalYaw, text)

            val relAngle = poiCoordination.getRelativeAngle(globalYaw)
            Log.d("POI", "Recorded: '$text' at abs=${globalYaw.toInt()}°, rel=${relAngle?.toInt()}°")
        } else {
            Log.d("POI_DEBUG", "Skipped: hasRef=$hasRef, textBlank=${text.isBlank()}")
        }
    }

    // POI 데이터를 CSV로 저장 (버튼 클릭 시 호출)
    private fun savePoiToCsv() {
        val path = poiCoordination.saveToCSV(this)

        if (path != null) {
            Toast.makeText(
                this,
                "POI 저장 완료!\n$path",
                Toast.LENGTH_LONG
            ).show()

            // Logcat에도 출력
            poiCoordination.printLogToLogcat()

            Log.d("POI", "CSV saved: $path")
        } else {
            Toast.makeText(
                this,
                "저장 실패: POI 데이터가 없습니다.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // POI 데이터 초기화 (버튼 클릭 시 호출)
    private fun clearPoiData() {
        poiCoordination.clear()
        Toast.makeText(this, "POI 데이터 초기화 완료", Toast.LENGTH_SHORT).show()
        Log.d("POI", "POI data cleared")
    }

}
