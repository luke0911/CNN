package com.example.Yolo_OCR

import android.graphics.Bitmap
import android.util.Log
import com.example.Yolo_OCR.ocr.BoxMerger
import com.example.Yolo_OCR.ocr.OcrProcessor
import com.example.Yolo_OCR.utils.Constants
import com.example.Yolo_OCR.utils.GeometryUtils
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.round
import kotlin.math.max

class OcrEngine(
    private val lastRotation: () -> Int,
    private val minOcrIntervalMs: Long = Constants.OCR_MIN_INTERVAL_MS,
    private val boxExpireMs: Long = Constants.OCR_BOX_EXPIRE_MS
) {
    companion object {
        private const val TAG = "OcrEngine"
    }

    data class TrackedBox(
        val id: String,
        var box: DetBox,
        var frameCount: Int,
        var lastOcrText: String?,
        var isStable: Boolean,
        var lastSeen: Long,
        var lastOcrTs: Long = 0L
    )

    private val boxMerger = BoxMerger()
    private val ocrProcessor = OcrProcessor(lastRotation)

    // UI 즉시 반영용 콜백 (OCR 성공 시 현재 박스와 텍스트를 전달)
    var onTextAvailable: ((DetBox, String) -> Unit)? = null

    private val ocrExecutor = ThreadPoolExecutor(
        1, Constants.OCR_MAX_CONCURRENT, Constants.OCR_THREAD_KEEP_ALIVE_MS, TimeUnit.MILLISECONDS,
        LinkedBlockingQueue<Runnable>(Constants.OCR_QUEUE_SIZE),
        ThreadPoolExecutor.DiscardPolicy()
    )

    private val tracker = mutableMapOf<String, TrackedBox>()
    private val activeOcrCount = AtomicInteger(0)

    /** Clear tracking state (call on model switch / pipeline restart) */
    fun reset() {
        synchronized(tracker) { tracker.clear() }
        activeOcrCount.set(0)
    }

    /**
     * 감지 박스들에 대해 트래킹/병합/비동기 OCR 스케줄링 후, 현재까지의 best 텍스트를 즉시 반영
     */
    fun process(boxes: List<DetBox>, bitmap: Bitmap): List<DetBox> {
        val now = System.currentTimeMillis()
        if (boxes.isEmpty()) {
            synchronized(tracker) {
                // Avoid tracker growing when feed is empty for a while
                tracker.clear()
            }
            return boxes
        }

        val merged = boxMerger.mergeNearbyBoxes(boxes)

        // 만료 정리 (최적화: 스냅샷 없이 직접 제거)
        synchronized(tracker) {
            tracker.entries.removeIf { (_, t) -> now - t.lastSeen > boxExpireMs }
        }

        val trackedSnapshot: List<TrackedBox> = synchronized(tracker) { tracker.values.toList() }

        // 최적화: List allocation 최소화
        return merged.map { box ->
            // Associate with existing tracked entry by IoU to keep IDs stable
            var assocId: String? = null
            var assocTracked: TrackedBox? = null
            var bestIou = 0f

            // 최적화: IoU 임계값 적용하여 조기 종료
            for (t in trackedSnapshot) {
                val i = iou(box, t.box)
                if (i > bestIou) {
                    bestIou = i
                    assocId = t.id
                    assocTracked = t
                    // IoU가 0.9 이상이면 완벽한 매칭으로 간주하고 조기 종료
                    if (i >= 0.9f) break
                }
            }

            val id = assocId ?: generateId(box)
            var tracked = synchronized(tracker) { tracker[id] }

            if (assocTracked != null && tracked == null) {
                synchronized(tracker) {
                    tracker.remove(assocTracked!!.id)?.let { old ->
                        tracker[id] = old.copy(id = id)
                    }
                    tracked = tracker[id]
                }
            }

            if (tracked == null) {
                synchronized(tracker) { tracker[id] = TrackedBox(id, box, 1, null, false, now) }
                triggerOcrAsync(id, box, bitmap)
                box.copy(text = null)
            } else {
                synchronized(tracker) {
                    tracked!!.box = box
                    tracked!!.frameCount++
                    tracked!!.lastSeen = now
                }

                val needOcr = (!tracked!!.isStable && tracked!!.frameCount >= Constants.OCR_STABLE_FRAMES)
                if (needOcr) {
                    synchronized(tracker) { tracked!!.isStable = true }
                    triggerOcrAsync(id, box, bitmap)
                }

                val lastText = synchronized(tracker) { tracked!!.lastOcrText }
                box.copy(text = lastText)
            }
        }
    }

    private fun triggerOcrAsync(id: String, box: DetBox, bmp: Bitmap) {
        // Check bitmap validity first
        if (bmp.isRecycled) return

        // 최소크기 검증
        val bw = box.right - box.left
        val bh = box.bottom - box.top
        if (bw < Constants.OCR_MIN_ROI_WIDTH || bh < Constants.OCR_MIN_ROI_HEIGHT) return

        // Validate box bounds
        if (box.left >= bmp.width || box.top >= bmp.height || box.right <= 0 || box.bottom <= 0) return

        val now = System.currentTimeMillis()
        val tracked = synchronized(tracker) { tracker[id] }

        // Adaptive OCR interval: longer interval for boxes with successful text
        val adaptiveInterval = if (tracked?.lastOcrText.isNullOrBlank()) {
            minOcrIntervalMs  // No text yet: use default interval
        } else {
            minOcrIntervalMs * 3  // Has text: use 3x longer interval (reduce re-OCR)
        }

        if (tracked != null && now - tracked.lastOcrTs < adaptiveInterval) return

        if (activeOcrCount.get() >= Constants.OCR_MAX_CONCURRENT) return

        activeOcrCount.incrementAndGet()

        ocrExecutor.execute {
            try {
                ocrProcessor.processImage(box, bmp) { result ->
                    handleOcrResult(id, result)
                }
            } catch (e: Exception) {
                handleOcrResult(id, null)
            } finally {
                activeOcrCount.decrementAndGet()
            }
        }
    }

    private fun handleOcrResult(id: String, result: String?) {
        synchronized(tracker) {
            tracker[id]?.let { tracked ->
                tracked.lastOcrText = result
                tracked.lastOcrTs = System.currentTimeMillis()
                if (!result.isNullOrBlank()) {
                    onTextAvailable?.invoke(tracked.box, result)
                }
            }
        }
    }

    private fun generateId(box: DetBox): String {
        val cx = (box.left + box.right) * 0.5f
        val cy = (box.top + box.bottom) * 0.5f
        val w = (box.right - box.left)
        val h = (box.bottom - box.top)
        val q = Constants.ID_QUANTIZATION_STEP
        val qcX = (round(cx / q) * q).toInt()
        val qcY = (round(cy / q) * q).toInt()
        val qW = (round(w / q) * q).toInt()
        val qH = (round(h / q) * q).toInt()
        return "${qcX}_${qcY}_${qW}_${qH}"
    }

    private fun iou(a: DetBox, b: DetBox): Float = GeometryUtils.iou(a, b)



}