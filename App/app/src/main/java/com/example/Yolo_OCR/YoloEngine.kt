package com.example.Yolo_OCR

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.res.AssetManager
import android.graphics.*
import android.util.Log
import com.example.Yolo_OCR.utils.BitmapUtils
import com.example.Yolo_OCR.utils.Constants
import com.example.Yolo_OCR.utils.GeometryUtils
import com.example.Yolo_OCR.yolo.YoloOutputParser
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.max
import kotlin.math.min

class YoloEngine(
    private val assets: AssetManager,
    initialModel: YoloModel,
    private var confThresh: Float = Constants.DEFAULT_CONF_THRESHOLD,
    private var iouThresh: Float = Constants.DEFAULT_IOU_THRESHOLD,
    private var minW: Float = Constants.DEFAULT_MIN_BOX_WIDTH,
    private var minH: Float = Constants.DEFAULT_MIN_BOX_HEIGHT
) {
    private var session: OrtSession? = null
    private var model: YoloModel = initialModel
    private var spec: ModelSpec = initialModel.spec()
    private var outputParser: YoloOutputParser = YoloOutputParser(spec.inputSize)

    private val modelLock = ReentrantReadWriteLock()

    private val TAG = "YoloEngine"

    /** Describe the nested-array shape for debugging */
    private fun shapeOf(any: Any?): String {
        fun rec(o: Any?): String {
            return when (o) {
                is Array<*> -> {
                    val inner = if (o.isNotEmpty()) rec(o[0]) else ""
                    "[${o.size}]$inner"
                }
                is FloatArray -> "[${o.size}]"
                else -> ""
            }
        }
        return rec(any)
    }

    init {
        loadSession()
    }

    private fun loadSession() {
        modelLock.write {
            try {
                Log.d(TAG, "loadSession() START: Loading model ${spec.assetName}")
                session?.close()
                
                // Check if asset file exists and read it
                val inputStream = try {
                    assets.open(spec.assetName)
                } catch (e: Exception) {
                    Log.e(TAG, "loadSession() FAILED: Cannot open asset ${spec.assetName}", e)
                    throw e
                }
                
                val modelBytes = inputStream.use { it.readBytes() }
                Log.d(TAG, "loadSession() Model bytes loaded: ${modelBytes.size} bytes")

                val so = OrtSession.SessionOptions().apply {
                    // Single thread for mobile optimization (멀티스레드는 모바일에서 오히려 느림)
                    setIntraOpNumThreads(1)

                    // 최적화 레벨 설정
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)

                    // 메모리 최적화
                    setMemoryPatternOptimization(true)
                    setCPUArenaAllocator(true)

                    // NNAPI disabled: caused 22x slowdown (38ms → 830ms)
                }

                val env = OrtEnvironment.getEnvironment()
                Log.d(TAG, "loadSession() Creating ONNX session...")
                session = env.createSession(modelBytes, so)
                Log.d(TAG, "loadSession() Session created successfully")
                
                val s = session!!
                val inNames = s.inputNames.joinToString()
                val outNames = s.outputNames.joinToString()
                Log.d(TAG, "Loaded ${spec.assetName} (${spec.inputSize}), input=$inNames, output=$outNames")
                
                s.inputInfo.forEach { (name, info) ->
                    val shape = (info.info as? ai.onnxruntime.TensorInfo)?.shape?.joinToString()
                    Log.d(TAG, "Input[$name] shape=$shape")
                }
                
                s.outputInfo.forEach { (name, info) ->
                    val shape = (info.info as? ai.onnxruntime.TensorInfo)?.shape?.joinToString()
                    Log.d(TAG, "Output[$name] shape=$shape")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "loadSession() EXCEPTION: Failed to load model ${spec.assetName}", e)
                session = null
                throw e
            }
        }
    }

    fun switchModel(newModel: YoloModel) {
        modelLock.write {
            if (newModel == model) return
            Log.d(TAG, "switchModel() FROM ${model.name} TO ${newModel.name}")
            model = newModel
            spec = newModel.spec()
            outputParser = YoloOutputParser(spec.inputSize)
            Log.d(TAG, "switchModel() Created new outputParser with inputSize=${spec.inputSize}")
            loadSession()
            Log.d(TAG, "switchModel() COMPLETE: Now using ${model.name}")
        }
    }

    fun updateThresholds(conf: Float, iou: Float, minW: Float, minH: Float) {
        this.confThresh = conf
        this.iouThresh = iou
        this.minW = minW
        this.minH = minH
    }

    /** Bitmap 한 장을 넣어 박스 리스트 반환 (원본 좌표계로 변환된 결과) */
    fun detect(src: Bitmap): List<DetBox> = modelLock.read {
        Log.d(TAG, "detect() START: model=${model}, spec.assetName=${spec.assetName}")
        
        val s = session ?: run {
            Log.e(TAG, "detect() FAILED: session is null!")
            return emptyList()
        }
        
        Log.d(TAG, "detect() session OK, inputSize=${spec.inputSize}")
        val inSize = spec.inputSize
        val (resized, scale, padX, padY) = GeometryUtils.letterbox(src, inSize, inSize)
        Log.d(TAG, "detect() letterbox: scale=$scale, pad=($padX,$padY)")

        val tensorData = BitmapUtils.bitmapToFloat32Tensor(resized)
        Log.d(TAG, "detect() tensor created: shape=[${tensorData.size}]")
        val inputName = s.inputNames.iterator().next()
        Log.d(TAG, "detect() inputName=$inputName")

        try {
            OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), tensorData).use { input ->
                Log.d(TAG, "detect() running inference...")
                s.run(mapOf(inputName to input)).use { result ->
                    Log.d(TAG, "detect() inference SUCCESS, result.size=${result.size()}")
                    val out = result[0].value
                    Log.d(TAG, "OUT type=${out::class.java} shape=${shapeOf(out)} conf=$confThresh iou=$iouThresh")
                    val dets = outputParser.parseYoloOutputs(out as? Array<FloatArray>, out, confThresh)
                    logDetectionResults(dets)
                    val kept = nms(dets, iouThresh)
                    Log.d(TAG, "kept=${kept.size} after NMS(th=$iouThresh)")
                    val mapped = mapToOriginalCoordinates(kept, scale, padX, padY, src)
                    val filtered = filterByMinSize(mapped)
                    Log.d(TAG, "detect() RESULT: ${filtered.size} boxes after all filtering")
                    return@read filtered
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "detect() EXCEPTION: ${e.message}", e)
            return emptyList()
        }
    }

    // ===== 내부 유틸 =====
    private fun logDetectionResults(dets: List<DetBox>) {
        dets.take(3).forEachIndexed { i, d ->
            Log.d(TAG, "pre-unscale[$i] xyxy=(${d.left},${d.top},${d.right},${d.bottom}) conf=${d.conf} cls=${d.cls}")
        }
        Log.d(TAG, "parsed=${dets.size}")
    }

    private fun mapToOriginalCoordinates(
        boxes: List<DetBox>, 
        scale: Float, 
        padX: Float, 
        padY: Float, 
        src: Bitmap
    ): List<DetBox> {
        var logCount = 0
        return boxes.map { d ->
            val x1 = ((d.left - padX) / scale).coerceIn(0f, src.width.toFloat())
            val y1 = ((d.top  - padY) / scale).coerceIn(0f, src.height.toFloat())
            val x2 = ((d.right- padX) / scale).coerceIn(0f, src.width.toFloat())
            val y2 = ((d.bottom- padY) / scale).coerceIn(0f, src.height.toFloat())
            if (logCount < 3) {
                Log.d(TAG, "post-unscale[$logCount] xyxy=($x1,$y1,$x2,$y2)")
                logCount++
            }
            d.copy(left = x1, top = y1, right = x2, bottom = y2)
        }
    }

    private fun filterByMinSize(boxes: List<DetBox>): List<DetBox> {
        return boxes.filter { (it.right - it.left) >= this.minW && (it.bottom - it.top) >= this.minH }
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
                if (GeometryUtils.iou(a, b) > iouThresh) removed[j] = true
            }
        }
        return kept
    }

}