package com.example.Yolo_OCR.yolo

import android.util.Log
import com.example.Yolo_OCR.DetBox
import com.example.Yolo_OCR.utils.Constants
import kotlin.math.max

class YoloOutputParser(private val inputSize: Int) {
    
    companion object {
        private const val TAG = "YoloOutputParser"
    }
    
    fun parseYoloOutputs(as2d: Array<FloatArray>?, raw: Any, confThresh: Float): MutableList<DetBox> {
        val dets = mutableListOf<DetBox>()
        
        Log.d(TAG, "parseYoloOutputs: as2d=${if(as2d != null) "Array<FloatArray>[${as2d.size}]" else "null"}")
        Log.d(TAG, "parseYoloOutputs: raw type=${raw::class.java.simpleName}, confThresh=$confThresh")
        
        when (raw) {
            is Array<*> -> {
                Log.d(TAG, "Raw array size: ${raw.size}")
                if (raw.isNotEmpty()) {
                    val first = raw[0]
                    Log.d(TAG, "First element type: ${first?.javaClass?.simpleName}")
                    if (first is Array<*>) {
                        Log.d(TAG, "Nested array structure: ${raw.size}x${first.size}")
                        if (first.isNotEmpty()) {
                            val thirdDim = (first[0] as? FloatArray)?.size ?: "not FloatArray"
                            Log.d(TAG, "Third dimension size: $thirdDim")
                            
                            // For object detection model [1, 11, 8400], log some sample values
                            if (first[0] is FloatArray && first.size == 8400) {
                                val sampleRow = first[0] as FloatArray
                                if (sampleRow.size == 11) {
                                    Log.d(TAG, "Object model detected: [1, 11, 8400] format")
                                    Log.d(TAG, "Sample row [0]: bbox=[${sampleRow[0]}, ${sampleRow[1]}, ${sampleRow[2]}, ${sampleRow[3]}]")
                                    Log.d(TAG, "Sample row [0]: classes=[${sampleRow.slice(4..10).joinToString(", ") { "%.3f".format(it) }}]")
                                    
                                    // Check a few more rows for variety
                                    for (i in listOf(100, 1000, 4000)) {
                                        if (i < first.size) {
                                            val row = first[i] as FloatArray
                                            val maxClass = row.slice(4..10).withIndex().maxByOrNull { it.value }
                                            Log.d(TAG, "Sample row [$i]: max_class=${maxClass?.value?.let { "%.3f".format(it) }}, idx=${maxClass?.index}")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        if (as2d != null) {
            Log.d(TAG, "Using parseExplicit2D path")
            parseExplicit2D(as2d, dets, confThresh)
        } else {
            Log.d(TAG, "Using parseGeneric path")
            parseGeneric(raw, dets, confThresh)
        }
        
        Log.d(TAG, "Total detections parsed: ${dets.size}")
        return dets
    }
    
    private fun parseExplicit2D(as2d: Array<FloatArray>, dets: MutableList<DetBox>, confThresh: Float) {
        val nc = as2d.firstOrNull()?.size ?: 0
        Log.d(TAG, "parseExplicit2D: ${as2d.size} rows, $nc columns per row")
        
        when {
            nc == Constants.YOLO_STANDARD_CHANNELS_12 -> {
                Log.d(TAG, "Using emitRowRaw (12 channels - object detection 8 classes)")
                for (row in as2d) emitRowRaw(row, confThresh, dets)
            }
            nc == Constants.YOLO_STANDARD_CHANNELS_11 -> {
                Log.d(TAG, "Using emitRowRaw (11 channels - object detection 7 classes)")
                for (row in as2d) emitRowRaw(row, confThresh, dets)
            }
            nc == Constants.YOLO_STANDARD_CHANNELS_6 -> {
                Log.d(TAG, "Using emitRow6 (6 channels)")
                for (row in as2d) emitRow6(row, dets, confThresh)
            }
            nc == Constants.YOLO_STANDARD_CHANNELS_5 -> {
                Log.d(TAG, "Using emitRow5 (5 channels)")
                for (row in as2d) emitRow5(row, dets, confThresh)
            }
            nc >= Constants.YOLO_MIN_CHANNELS -> {
                Log.d(TAG, "Using emitRowRaw ($nc channels, >= ${Constants.YOLO_MIN_CHANNELS})")
                for (row in as2d) emitRowRaw(row, confThresh, dets)
            }
            else -> {
                Log.d(TAG, "Using parseAs2DCxN ($nc channels)")
                parseAs2DCxN(as2d, dets, confThresh)
            }
        }
    }
    
    
    private fun parseAs2DCxN(as2d: Array<FloatArray>, dets: MutableList<DetBox>, confThresh: Float) {
        val c = as2d.size
        val n = as2d[0].size

        if (c == Constants.YOLO_STANDARD_CHANNELS_12 || c == Constants.YOLO_STANDARD_CHANNELS_11 || c == Constants.YOLO_STANDARD_CHANNELS_6 || c == Constants.YOLO_STANDARD_CHANNELS_5 || c >= Constants.YOLO_MIN_CHANNELS) {
            // Use local buffer for thread safety
            val row = FloatArray(c)

            for (j in 0 until n) {
                for (i in 0 until c) {
                    row[i] = as2d[i][j]
                }
                when (c) {
                    Constants.YOLO_STANDARD_CHANNELS_12 -> emitRowRaw(row, confThresh, dets)
                    Constants.YOLO_STANDARD_CHANNELS_11 -> emitRowRaw(row, confThresh, dets)
                    Constants.YOLO_STANDARD_CHANNELS_6 -> emitRow6(row, dets, confThresh)
                    Constants.YOLO_STANDARD_CHANNELS_5 -> emitRow5(row, dets, confThresh)
                    else -> emitRowRaw(row, confThresh, dets)
                }
            }
        }
    }
    
    private fun parseGeneric(rawAny: Any, dets: MutableList<DetBox>, confThresh: Float) {
        forEachRow(rawAny) { row ->
            when {
                row.size == Constants.YOLO_STANDARD_CHANNELS_12 -> emitRowRaw(row, confThresh, dets)
                row.size == Constants.YOLO_STANDARD_CHANNELS_11 -> emitRowRaw(row, confThresh, dets)
                row.size == Constants.YOLO_STANDARD_CHANNELS_6 -> emitRow6(row, dets, confThresh)
                row.size == Constants.YOLO_STANDARD_CHANNELS_5 -> emitRow5(row, dets, confThresh)
                row.size >= Constants.YOLO_MIN_CHANNELS -> emitRowRaw(row, confThresh, dets)
            }
        }
    }
    
    private fun emitRow6(row: FloatArray, dets: MutableList<DetBox>, confThresh: Float) {
        if (row.size != Constants.YOLO_STANDARD_CHANNELS_6) return
        val score = row[4]
        if (score >= confThresh) {
            val clsId = max(0f, row[5]).toInt()
            dets += DetBox(row[0], row[1], row[2], row[3], score, clsId)
        }
    }
    
    private fun emitRow5(row: FloatArray, dets: MutableList<DetBox>, confThresh: Float) {
        if (row.size != Constants.YOLO_STANDARD_CHANNELS_5) return
        val cx0 = row[0]; val cy0 = row[1]; val w0 = row[2]; val h0 = row[3]
        val obj = row[4].coerceAtLeast(0f)
        
        val maxAbs = max(max(kotlin.math.abs(cx0), kotlin.math.abs(cy0)), max(kotlin.math.abs(w0), kotlin.math.abs(h0)))
        val scaleToPx = if (maxAbs <= Constants.YOLO_CONFIDENCE_SCALING) inputSize.toFloat() else 1f
        
        val cx = cx0 * scaleToPx
        val cy = cy0 * scaleToPx
        val w = w0 * scaleToPx
        val h = h0 * scaleToPx
        
        if (obj >= confThresh) {
            val x1 = cx - w / 2f
            val y1 = cy - h / 2f
            val x2 = cx + w / 2f
            val y2 = cy + h / 2f
            dets += DetBox(x1, y1, x2, y2, obj, 0)
        }
    }
    
    private fun emitRowRaw(row: FloatArray, confThresh: Float, dets: MutableList<DetBox>) {
        if (row.size < 6) return
        val cx = row[0]; val cy = row[1]; val w = row[2]; val h = row[3]
        
        val (score, clsId) = findBestClassification(row)
        
        if (dets.size < 3) { // Log first few detections for debugging
            Log.v(TAG, "emitRowRaw: row.size=${row.size}, score=$score, clsId=$clsId, confThresh=$confThresh")
            Log.v(TAG, "  bbox: cx=$cx, cy=$cy, w=$w, h=$h")
            if (row.size >= 11) {
                Log.v(TAG, "  classes: [${row.slice(4..10).joinToString(", ")}]")
            }
        }
        
        if (clsId < 0 || score < confThresh) return
        
        val x1 = cx - w / 2f
        val y1 = cy - h / 2f
        val x2 = cx + w / 2f
        val y2 = cy + h / 2f
        dets += DetBox(x1, y1, x2, y2, score, clsId)
    }
    
    private fun findBestClassification(row: FloatArray): Pair<Float, Int> {
        // For object.onnx models:
        // - [1, 12, 8400]: 4(bbox) + 8(classes) with NO objectness - NEW MODEL
        // - [1, 11, 8400]: 4(bbox) + 7(classes) with NO objectness - LEGACY

        if (row.size == 12) {
            // Object detection model: classes are at indices 4-11 (8 classes)
            val (bestScore, bestIdx) = findBestClass(row, 4)

            // Debug logging for object detection
            if (bestScore > 0.001f) { // Log promising detections
                Log.v(TAG, "findBestClassification OBJECT (8 classes): row.size=${row.size}")
                Log.v(TAG, "  bbox: cx=${row[0]}, cy=${row[1]}, w=${row[2]}, h=${row[3]}")
                Log.v(TAG, "  classes [4-11]: [${row.slice(4..11).joinToString(", ") { "%.3f".format(it) }}]")
                Log.v(TAG, "  best_class: idx=$bestIdx, score=$bestScore")
            }

            return bestScore to bestIdx
        }

        if (row.size == 11) {
            // Legacy object detection model: classes are at indices 4-10 (7 classes)
            val (bestScore, bestIdx) = findBestClass(row, 4)

            // Debug logging for object detection
            if (bestScore > 0.001f) { // Log promising detections
                Log.v(TAG, "findBestClassification OBJECT (7 classes): row.size=${row.size}")
                Log.v(TAG, "  bbox: cx=${row[0]}, cy=${row[1]}, w=${row[2]}, h=${row[3]}")
                Log.v(TAG, "  classes [4-10]: [${row.slice(4..10).joinToString(", ") { "%.3f".format(it) }}]")
                Log.v(TAG, "  best_class: idx=$bestIdx, score=$bestScore")
            }

            return bestScore to bestIdx
        }
        
        // Fallback for other model formats
        // Strategy A: no objectness → classes at 4..K
        val (bestScoreA, bestIdxA) = findBestClass(row, 4)
        
        // Strategy B: objectness * class (obj=row[4], classes at 5..K)
        val (bestScoreB, bestIdxB) = if (row.size >= 6) {
            val obj = row[4].coerceAtLeast(0f)
            val (bestCls, bestK) = findBestClass(row, 5)
            if (bestK >= 0) obj * bestCls to bestK else 0f to -1
        } else 0f to -1
        
        val result = if (bestScoreB > bestScoreA) bestScoreB to bestIdxB else bestScoreA to bestIdxA
        
        // Debug logging for other models
        if (row.size >= 6) {
            Log.v(TAG, "findBestClassification OTHER: row.size=${row.size}")
            Log.v(TAG, "  Strategy A (classes 4+): score=$bestScoreA, idx=$bestIdxA")
            Log.v(TAG, "  Strategy B (obj*cls 5+): score=$bestScoreB, idx=$bestIdxB")
            Log.v(TAG, "  Final result: score=${result.first}, class=${result.second}")
        }
        
        return result
    }
    
    private fun findBestClass(row: FloatArray, startIdx: Int): Pair<Float, Int> {
        var bestScore = 0f
        var bestIdx = -1
        for (i in startIdx until row.size) {
            if (row[i] > bestScore) {
                bestScore = row[i]
                bestIdx = i - startIdx
            }
        }
        return bestScore to bestIdx
    }
    
    private fun forEachRow(rawAny: Any, onRow: (FloatArray) -> Unit) {
        when (rawAny) {
            is Array<*> -> processArrayOutput(rawAny, onRow)
        }
    }
    
    private fun processArrayOutput(rawArray: Array<*>, onRow: (FloatArray) -> Unit) {
        if (rawArray.isEmpty()) return
        
        val firstElement = rawArray[0]
        when (firstElement) {
            is FloatArray -> processFloatArrayOutput(rawArray as Array<FloatArray>, onRow)
            is Array<*> -> processNestedArrayOutput(rawArray, onRow)
        }
    }
    
    private fun processFloatArrayOutput(arr: Array<FloatArray>, onRow: (FloatArray) -> Unit) {
        for (row in arr) onRow(row)
    }
    
    private fun processNestedArrayOutput(rawArray: Array<*>, onRow: (FloatArray) -> Unit) {
        if (rawArray.isEmpty()) return
        val firstNested = rawArray[0] as? Array<*> ?: return
        if (firstNested.isEmpty() || firstNested[0] !is FloatArray) return
        
        val dimensions = analyzeDimensions(rawArray)
        val twoD = if (dimensions.dim0 == 1) rawArray[0] as Array<*> else rawArray
        
        processChannelDimensions(twoD as Array<*>, onRow)
    }
    
    private data class ArrayDimensions(val dim0: Int, val dim1: Int, val dim2: Int)
    
    private fun analyzeDimensions(rawArray: Array<*>): ArrayDimensions {
        val dim0 = rawArray.size
        val firstElement = rawArray[0] as Array<*>
        val dim1 = firstElement.size
        val dim2 = (firstElement[0] as? FloatArray)?.size ?: 0
        return ArrayDimensions(dim0, dim1, dim2)
    }
    
    private fun processChannelDimensions(twoD: Array<*>, onRow: (FloatArray) -> Unit) {
        val A = twoD.size
        val B = ((twoD[0] as? FloatArray)?.size) ?: return
        
        val cIsA = isChannelDimension(A)
        val cIsB = isChannelDimension(B)
        
        when {
            cIsA && !cIsB -> processAsCxN(twoD, A, B, onRow)
            !cIsA && cIsB -> processAsNxC(twoD, A, onRow)
            cIsA && cIsB -> processAmbiguousDimensions(twoD, A, B, onRow)
            else -> processAsNxC(twoD, A, onRow) // Fallback
        }
    }
    
    private fun isChannelDimension(size: Int): Boolean {
        return size == Constants.YOLO_STANDARD_CHANNELS_12 ||
               size == Constants.YOLO_STANDARD_CHANNELS_11 ||
               size == Constants.YOLO_STANDARD_CHANNELS_6 ||
               size == Constants.YOLO_STANDARD_CHANNELS_5 ||
               size >= Constants.YOLO_MIN_CHANNELS
    }
    
    private fun processAsCxN(twoD: Array<*>, C: Int, N: Int, onRow: (FloatArray) -> Unit) {
        // Use local buffer for thread safety
        val row = FloatArray(C)

        for (j in 0 until N) {
            for (i in 0 until C) {
                row[i] = (twoD[i] as FloatArray)[j]
            }
            onRow(row)
        }
    }
    
    private fun processAsNxC(twoD: Array<*>, N: Int, onRow: (FloatArray) -> Unit) {
        for (i in 0 until N) {
            val row = (twoD[i] as? FloatArray) ?: continue
            onRow(row)
        }
    }
    
    private fun processAmbiguousDimensions(twoD: Array<*>, A: Int, B: Int, onRow: (FloatArray) -> Unit) {
        val chooseAasC = A <= B
        if (chooseAasC) {
            Log.d(TAG, "forEachRow: ambiguous [A=$A,B=$B], choosing A as channels (C=$A,N=$B)")
            processAsCxN(twoD, A, B, onRow)
        } else {
            Log.d(TAG, "forEachRow: ambiguous [A=$A,B=$B], choosing B as channels (C=$B,N=$A)")
            processAsNxC(twoD, A, onRow)
        }
    }
}