package com.example.Yolo_OCR.performance

import android.util.Log
import com.example.Yolo_OCR.utils.Constants
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap

class PerformanceMonitor {

    companion object {
        private const val TAG = "PerformanceMonitor"
        private const val STATS_LOG_INTERVAL_MS = 10000L // Log stats every 10 seconds
    }

    private var frameCount = 0
    private var lastFpsTime = System.currentTimeMillis()
    private val ocrSuccessCount = AtomicInteger(0)
    private val ocrAttemptCount = AtomicInteger(0)
    private val frameId = AtomicLong(0)

    // Performance statistics tracking
    private val modelStats = ConcurrentHashMap<String, ModelStats>()
    private var lastStatsLogTime = System.currentTimeMillis()

    data class ModelStats(
        var frameCount: Int = 0,
        var totalYuvMs: Double = 0.0,
        var totalRotMs: Double = 0.0,
        var totalYoloMs: Double = 0.0,
        var totalOcrMs: Double = 0.0,
        var totalUiMs: Double = 0.0,
        var totalMs: Double = 0.0,
        var totalBoxCount: Int = 0,
        var totalTextCount: Int = 0,
        var minYoloMs: Double = Double.MAX_VALUE,
        var maxYoloMs: Double = 0.0,
        var minOcrMs: Double = Double.MAX_VALUE,
        var maxOcrMs: Double = 0.0,
        var minTotalMs: Double = Double.MAX_VALUE,
        var maxTotalMs: Double = 0.0
    ) {
        fun avgYuvMs() = if (frameCount > 0) totalYuvMs / frameCount else 0.0
        fun avgRotMs() = if (frameCount > 0) totalRotMs / frameCount else 0.0
        fun avgYoloMs() = if (frameCount > 0) totalYoloMs / frameCount else 0.0
        fun avgOcrMs() = if (frameCount > 0) totalOcrMs / frameCount else 0.0
        fun avgUiMs() = if (frameCount > 0) totalUiMs / frameCount else 0.0
        fun avgTotalMs() = if (frameCount > 0) totalMs / frameCount else 0.0
        fun avgBoxCount() = if (frameCount > 0) totalBoxCount.toDouble() / frameCount else 0.0
        fun avgTextCount() = if (frameCount > 0) totalTextCount.toDouble() / frameCount else 0.0
        fun fps() = if (avgTotalMs() > 0) 1000.0 / avgTotalMs() else 0.0
    }
    
    fun incrementFrame(): Long {
        frameCount++
        return frameId.incrementAndGet()
    }
    
    fun recordOcrAttempt() {
        ocrAttemptCount.incrementAndGet()
    }
    
    fun recordOcrSuccess() {
        ocrSuccessCount.incrementAndGet()
    }
    
    fun logPerformanceStats(ocrCacheSize: Int, activeOcrCount: Int) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastFpsTime > Constants.PERF_MONITOR_INTERVAL_MS) {
            val fps = frameCount * 1000.0 / (currentTime - lastFpsTime)
            val successRate = if (ocrAttemptCount.get() > 0) {
                (ocrSuccessCount.get() * 100.0 / ocrAttemptCount.get()).toInt()
            } else 0
            
            Log.d(TAG,
                "FPS: ${fps.toInt()}, " +
                "OCR 캐시: $ocrCacheSize, " +
                "활성 OCR: $activeOcrCount, " +
                "OCR 성공률: $successRate% (${ocrSuccessCount.get()}/${ocrAttemptCount.get()})"
            )
            
            frameCount = 0
            lastFpsTime = currentTime
        }
    }
    
    fun reset() {
        frameCount = 0
        lastFpsTime = System.currentTimeMillis()
        ocrSuccessCount.set(0)
        ocrAttemptCount.set(0)
        frameId.set(0)
    }
    
    data class FrameTiming(
        val frameId: Long,
        val camDelayMs: Double,
        val yuvMs: Double,
        val rotMs: Double,
        val yoloMs: Double,
        val ocrMs: Double,
        val uiMs: Double,
        val totalMs: Double,
        val boxCount: Int,
        val textCount: Int
    )
    
    fun logFrameTiming(timing: FrameTiming, modelName: String) {
        // Update statistics
        val stats = modelStats.getOrPut(modelName) { ModelStats() }
        synchronized(stats) {
            stats.frameCount++
            stats.totalYuvMs += timing.yuvMs
            stats.totalRotMs += timing.rotMs
            stats.totalYoloMs += timing.yoloMs
            stats.totalOcrMs += timing.ocrMs
            stats.totalUiMs += timing.uiMs
            stats.totalMs += timing.totalMs
            stats.totalBoxCount += timing.boxCount
            stats.totalTextCount += timing.textCount

            // Track min/max
            if (timing.yoloMs < stats.minYoloMs) stats.minYoloMs = timing.yoloMs
            if (timing.yoloMs > stats.maxYoloMs) stats.maxYoloMs = timing.yoloMs
            if (timing.ocrMs < stats.minOcrMs) stats.minOcrMs = timing.ocrMs
            if (timing.ocrMs > stats.maxOcrMs) stats.maxOcrMs = timing.ocrMs
            if (timing.totalMs < stats.minTotalMs) stats.minTotalMs = timing.totalMs
            if (timing.totalMs > stats.maxTotalMs) stats.maxTotalMs = timing.totalMs
        }

        // Log individual frame timing
        val logEvery = Constants.UI_LOG_EVERY_N_FRAMES
        if (timing.frameId % logEvery == 0L) {
            Log.d("PERF_FRAME",
                "model=$modelName fid=${timing.frameId} " +
                "cam=${"%.1f".format(timing.camDelayMs)} " +
                "yuv=${"%.1f".format(timing.yuvMs)} " +
                "rot=${"%.1f".format(timing.rotMs)} " +
                "yolo=${"%.1f".format(timing.yoloMs)} " +
                "ocr=${"%.1f".format(timing.ocrMs)} " +
                "ui=${"%.1f".format(timing.uiMs)} " +
                "total=${"%.1f".format(timing.totalMs)} ms " +
                "(boxes=${timing.boxCount} txt=${timing.textCount})"
            )
        }

        // Log aggregated statistics periodically
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastStatsLogTime > STATS_LOG_INTERVAL_MS) {
            logAggregatedStats()
            lastStatsLogTime = currentTime
        }
    }

    private fun logAggregatedStats() {
        if (modelStats.isEmpty()) return

        Log.d(TAG, "==================== PERFORMANCE STATISTICS ====================")
        modelStats.forEach { (model, stats) ->
            synchronized(stats) {
                if (stats.frameCount == 0) return@forEach

                Log.d(TAG, "")
                Log.d(TAG, "Model: $model (${stats.frameCount} frames)")
                Log.d(TAG, "  FPS: ${"%.1f".format(stats.fps())}")
                Log.d(TAG, "  Avg Total: ${"%.1f".format(stats.avgTotalMs())} ms (min: ${"%.1f".format(stats.minTotalMs)}, max: ${"%.1f".format(stats.maxTotalMs)})")
                Log.d(TAG, "  Breakdown:")
                Log.d(TAG, "    - YUV conversion: ${"%.1f".format(stats.avgYuvMs())} ms (${"%.1f".format(stats.avgYuvMs() / stats.avgTotalMs() * 100)}%)")
                Log.d(TAG, "    - Rotation: ${"%.1f".format(stats.avgRotMs())} ms (${"%.1f".format(stats.avgRotMs() / stats.avgTotalMs() * 100)}%)")
                Log.d(TAG, "    - YOLO inference: ${"%.1f".format(stats.avgYoloMs())} ms (${"%.1f".format(stats.avgYoloMs() / stats.avgTotalMs() * 100)}%) [min: ${"%.1f".format(stats.minYoloMs)}, max: ${"%.1f".format(stats.maxYoloMs)}]")
                Log.d(TAG, "    - OCR processing: ${"%.1f".format(stats.avgOcrMs())} ms (${"%.1f".format(stats.avgOcrMs() / stats.avgTotalMs() * 100)}%) [min: ${"%.1f".format(stats.minOcrMs)}, max: ${"%.1f".format(stats.maxOcrMs)}]")
                Log.d(TAG, "    - UI update: ${"%.1f".format(stats.avgUiMs())} ms (${"%.1f".format(stats.avgUiMs() / stats.avgTotalMs() * 100)}%)")
                Log.d(TAG, "  Detection:")
                Log.d(TAG, "    - Avg boxes per frame: ${"%.1f".format(stats.avgBoxCount())}")
                Log.d(TAG, "    - Avg texts per frame: ${"%.1f".format(stats.avgTextCount())}")
            }
        }
        Log.d(TAG, "================================================================")
    }

    fun getPerformanceReport(): String {
        val sb = StringBuilder()
        sb.appendLine("==================== PERFORMANCE REPORT ====================")

        modelStats.forEach { (model, stats) ->
            synchronized(stats) {
                if (stats.frameCount == 0) return@forEach

                sb.appendLine("")
                sb.appendLine("Model: $model")
                sb.appendLine("  Frames: ${stats.frameCount}")
                sb.appendLine("  FPS: ${"%.2f".format(stats.fps())}")
                sb.appendLine("  Avg Total Time: ${"%.2f".format(stats.avgTotalMs())} ms")
                sb.appendLine("  Avg YOLO: ${"%.2f".format(stats.avgYoloMs())} ms")
                sb.appendLine("  Avg OCR: ${"%.2f".format(stats.avgOcrMs())} ms")
                sb.appendLine("  Avg Boxes: ${"%.2f".format(stats.avgBoxCount())}")
                sb.appendLine("  Avg Texts: ${"%.2f".format(stats.avgTextCount())}")
            }
        }

        sb.appendLine("============================================================")
        return sb.toString()
    }

    fun getModelStats(modelName: String): ModelStats? {
        return modelStats[modelName]
    }

    fun getAllStats(): Map<String, ModelStats> {
        return modelStats.toMap()
    }
}