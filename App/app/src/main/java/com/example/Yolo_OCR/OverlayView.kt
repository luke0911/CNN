package com.example.Yolo_OCR

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.View
import com.example.Yolo_OCR.utils.Constants
import com.example.Yolo_OCR.utils.GeometryUtils
import kotlin.math.max
import kotlin.math.min

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "OverlayView"
    }

    private val boxes = mutableListOf<DetBox>()
    private var bmpW = 0
    private var bmpH = 0
    private var rotation = 0 // degrees: 0, 90, 180, 270

    // 텍스트 유지(프레임 간 보존)용 상태
    private val textTtlMs = Constants.UI_TEXT_TTL_MS
    private val lastBoxesWithText = mutableListOf<DetBox>()

    private val boxPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 32f
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(6f, 2f, 2f, Color.BLACK)
    }

    private val labelBgPaint = Paint().apply {
        color = Color.parseColor("#CC000000") // 80% 투명도
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val confidencePaint = Paint().apply {
        color = Color.YELLOW
        textSize = 24f
        isAntiAlias = true
        typeface = Typeface.DEFAULT
        setShadowLayer(4f, 1f, 1f, Color.BLACK)
    }

    /**
     * 현재 원본 비트맵 크기와 회전 정보 업데이트
     */
    fun updateFrameInfo(bitmapW: Int, bitmapH: Int, rotationDeg: Int) {
        bmpW = bitmapW
        bmpH = bitmapH
        rotation = ((rotationDeg % 360) + 360) % 360
        Log.d(TAG, "Frame info updated: ${bitmapW}x${bitmapH}, rotation: ${rotation}°")
        invalidate()
    }

    /**
     * UI 표시할 박스들 업데이트
     * - 이전 프레임에서 텍스트가 있었던 박스의 텍스트를 IoU로 새 박스에 붙여줌
     */
    fun setBoxes(newBoxes: List<DetBox>) {
        val now = System.currentTimeMillis()
        val ttl = textTtlMs
        val prevSnapshot: List<DetBox> = synchronized(lastBoxesWithText) { lastBoxesWithText.filter { !it.text.isNullOrBlank() } }

        // 1) 새 프레임 박스에 이전 텍스트를 IoU로 붙이기
        val updated = newBoxes.map { nb ->
            if (!nb.text.isNullOrBlank()) return@map nb // 이미 텍스트 있으면 그대로

            var best: DetBox? = null
            var bestIou = 0f
            for (pb in prevSnapshot) {
                val i = iou(nb, pb)
                if (i > bestIou) {
                    bestIou = i
                    best = pb
                }
            }
            // 임계치 살짝 낮게(흔들림 허용)
            if (best != null && bestIou >= Constants.UI_IOU_THRESHOLD_FOR_TEXT_MATCHING && !best!!.text.isNullOrBlank()) {
                nb.copy(text = best!!.text)
            } else nb
        }

        // 2) 내부 상태 갱신: 최신 텍스트 보유 스냅샷을 유지하되 TTL 내 항목만 남김
        synchronized(lastBoxesWithText) {
            lastBoxesWithText.clear()
            lastBoxesWithText.addAll(updated)
        }

        // 3) 실제 그릴 리스트 교체
        synchronized(boxes) {
            boxes.clear()
            boxes.addAll(updated)
        }
        Log.d(TAG, "Updated ${updated.size} boxes, ${updated.count { !it.text.isNullOrBlank() }} with text")
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Layout Editor 프리뷰용
        if (isInEditMode) {
            drawPreviewDemo(canvas)
            return
        }

        val viewW = width.toFloat()
        val viewH = height.toFloat()

        if (viewW <= 0 || viewH <= 0) return

        val bw = if (bmpW > 0) bmpW.toFloat() else viewW
        val bh = if (bmpH > 0) bmpH.toFloat() else viewH

        // 박스들 그리기
        val snapshot: List<DetBox> = synchronized(boxes) { boxes.toList() }

        for ((index, box) in snapshot.withIndex()) {
            drawDetectionBox(canvas, box, bw, bh, viewW, viewH, index)
        }

        // 통계 정보 표시
        drawStats(canvas, snapshot, viewW, viewH)
    }

    private fun drawDetectionBox(
        canvas: Canvas,
        box: DetBox,
        bw: Float,
        bh: Float,
        viewW: Float,
        viewH: Float,
        index: Int
    ) {
        // 좌표 변환
        val p1 = GeometryUtils.mapPointWithRotation(box.left, box.top, bw, bh, viewW, viewH, rotation)
        val p2 = GeometryUtils.mapPointWithRotation(box.right, box.bottom, bw, bh, viewW, viewH, rotation)

        val left = min(p1.x, p2.x)
        val right = max(p1.x, p2.x)
        val top = min(p1.y, p2.y)
        val bottom = max(p1.y, p2.y)

        // 박스 크기가 너무 작으면 스킵
        if (right - left < 10 || bottom - top < 10) return

        // 신뢰도에 따른 색상 변화 (사물 인식의 경우 다른 색상 사용)
        val confidence = box.conf
        boxPaint.color = if (box.text?.contains(Regex("[가-힣]")) == true &&
                           (box.text == "문" || box.text == "비상구 표지판" || box.text == "소화기" ||
                            box.text == "엘리베이터" || box.text == "화장실 표지판" ||
                            box.text == "정수기" || box.text == "계단 표지판" || box.text == "계단")) {
            // Object detection colors
            when {
                confidence > 0.8f -> Color.MAGENTA
                confidence > 0.6f -> Color.CYAN
                confidence > 0.4f -> Color.parseColor("#FF9800") // Orange
                else -> Color.parseColor("#E91E63") // Pink
            }
        } else {
            // Text detection colors (original)
            when {
                confidence > 0.8f -> Color.GREEN
                confidence > 0.6f -> Color.YELLOW
                confidence > 0.4f -> Color.BLUE
                else -> Color.RED
            }
        }

        // 텍스트가 있는 박스는 더 굵게
        boxPaint.strokeWidth = if (box.text.isNullOrBlank()) 3f else 5f

        // 박스 그리기
        canvas.drawRect(left, top, right, bottom, boxPaint)

        // 텍스트 표시
        val text = box.text
        if (!text.isNullOrBlank()) {
            drawTextLabel(canvas, text, left, top, bottom, confidence)
        } else {
            // 텍스트가 없는 경우 신뢰도만 표시
            drawConfidenceLabel(canvas, confidence, left, top)
        }
    }

    private fun drawTextLabel(
        canvas: Canvas,
        text: String,
        left: Float,
        top: Float,
        bottom: Float,
        confidence: Float
    ) {
        // 긴 텍스트 자르기
        val displayText = if (text.length > 50) {
            text.substring(0, 47) + "..."
        } else text

        // 텍스트 크기 측정
        val bounds = Rect()
        textPaint.getTextBounds(displayText, 0, displayText.length, bounds)

        val padding = 8f
        val textWidth = bounds.width() + padding * 2
        val textHeight = bounds.height() + padding * 2

        // 텍스트 위치 결정 (박스 위쪽 또는 아래쪽) with clamping
        val desiredTopY = top - 8f
        val desiredBottomY = bottom + textHeight + 8f
        val textY = when {
            // Prefer drawing above if it fits
            desiredTopY - textHeight > 0f -> desiredTopY
            // Else try below; if it overflows, clamp to bottom of the view
            else -> min(height - 8f, desiredBottomY)
        }

        // 배경 그리기
        val bgLeft = left
        val bgRight = left + textWidth
        val bgTop = textY - textHeight
        val bgBottom = textY

        val clampedBgLeft = max(0f, bgLeft)
        val clampedBgRight = min(width.toFloat(), bgRight)
        val clampedBgTop = max(0f, bgTop)
        val clampedBgBottom = min(height.toFloat(), bgBottom)

        canvas.drawRoundRect(clampedBgLeft, clampedBgTop, clampedBgRight, clampedBgBottom, 6f, 6f, labelBgPaint)

        // 텍스트 그리기
        canvas.drawText(displayText, max(0f, left + padding), min(textY - padding, clampedBgBottom - padding), textPaint)

        // 신뢰도 표시 (작게)
        val confText = "${(confidence * 100).toInt()}%"
        canvas.drawText(confText, max(0f, left + padding), min(textY - padding - bounds.height() - 4, clampedBgBottom - padding), confidencePaint)
    }

    private fun drawConfidenceLabel(canvas: Canvas, confidence: Float, left: Float, top: Float) {
        val confText = "${(confidence * 100).toInt()}%"
        val bounds = Rect()
        confidencePaint.getTextBounds(confText, 0, confText.length, bounds)

        val padding = 4f
        val bgLeft = left
        val bgRight = left + bounds.width() + padding * 2
        val bgTop = top - bounds.height() - padding * 2
        val bgBottom = top

        val clampedBgLeft = max(0f, bgLeft)
        val clampedBgRight = min(width.toFloat(), bgRight)
        val clampedBgTop = max(0f, bgTop)
        val clampedBgBottom = min(height.toFloat(), bgBottom)

        // 작은 배경
        labelBgPaint.alpha = 180
        canvas.drawRoundRect(clampedBgLeft, clampedBgTop, clampedBgRight, clampedBgBottom, 4f, 4f, labelBgPaint)
        labelBgPaint.alpha = 204 // 원래 투명도로 복원

        val textY = max(padding, min(top - padding, height - padding))
        canvas.drawText(confText, max(0f, left + padding), textY, confidencePaint)
    }

    private fun drawStats(canvas: Canvas, boxes: List<DetBox>, viewW: Float, viewH: Float) {
        if (boxes.isEmpty()) return

        val totalBoxes = boxes.size
        val boxesWithText = boxes.count { !it.text.isNullOrBlank() }
        val avgConfidence = boxes.map { it.conf }.average()

        val statsText = "Boxes: $totalBoxes | OCR: $boxesWithText | Avg Conf: ${(avgConfidence * 100).toInt()}%"

        val statsPaint = Paint().apply {
            color = Color.WHITE
            textSize = 28f
            isAntiAlias = true
            setShadowLayer(4f, 1f, 1f, Color.BLACK)
        }

        val bounds = Rect()
        statsPaint.getTextBounds(statsText, 0, statsText.length, bounds)

        val padding = 12f
        val bgLeft = 10f
        val bgRight = bgLeft + bounds.width() + padding * 2
        val bgBottom = viewH - 20f
        val bgTop = bgBottom - bounds.height() - padding * 2

        // 반투명 배경
        val statsBgPaint = Paint().apply {
            color = Color.parseColor("#80000000")
            isAntiAlias = true
        }

        canvas.drawRoundRect(bgLeft, bgTop, bgRight, bgBottom, 8f, 8f, statsBgPaint)
        canvas.drawText(statsText, bgLeft + padding, bgBottom - padding, statsPaint)
    }

    private fun drawPreviewDemo(canvas: Canvas) {
        val vw = width.toFloat()
        val vh = height.toFloat()
        val demoLeft = vw * 0.2f
        val demoTop = vh * 0.25f
        val demoRight = vw * 0.8f
        val demoBottom = vh * 0.65f

        canvas.drawRect(demoLeft, demoTop, demoRight, demoBottom, boxPaint)

        val demoText = "DEMO TEXT"
        drawTextLabel(canvas, demoText, demoLeft, demoTop, demoBottom, 0.85f)
    }


    // 커스터마이징 API들
    fun setBoxColor(color: Int) {
        boxPaint.color = color
        invalidate()
    }

    fun setBoxStrokeWidth(px: Float) {
        boxPaint.strokeWidth = px
        invalidate()
    }

    fun setTextSize(px: Float) {
        textPaint.textSize = px
        invalidate()
    }
}
    private fun iou(a: DetBox, b: DetBox): Float = GeometryUtils.iou(a, b)