package com.example.Yolo_OCR.utils

import android.graphics.*
import com.example.Yolo_OCR.DetBox
import kotlin.math.max
import kotlin.math.min

object GeometryUtils {
    
    fun iou(a: DetBox, b: DetBox): Float {
        val x1 = max(a.left, b.left)
        val y1 = max(a.top, b.top)
        val x2 = min(a.right, b.right)
        val y2 = min(a.bottom, b.bottom)
        val inter = max(0f, x2 - x1) * max(0f, y2 - y1)
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        return inter / (areaA + areaB - inter + Constants.EPSILON)
    }
    
    data class LetterboxResult(
        val bmp: Bitmap, 
        val scale: Float, 
        val padX: Float, 
        val padY: Float
    )
    
    fun letterbox(src: Bitmap, dstW: Int, dstH: Int): LetterboxResult {
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
    
    fun cropBitmapSafe(sourceBitmap: Bitmap, box: DetBox, padding: Float = 0f): Bitmap? {
        if (sourceBitmap.isRecycled) {
            android.util.Log.w("GeometryUtils", "Source bitmap is recycled")
            return null
        }
        
        // Validate box bounds
        if (box.left >= sourceBitmap.width || box.top >= sourceBitmap.height ||
            box.right <= 0 || box.bottom <= 0) {
            android.util.Log.w("GeometryUtils", "Box is outside bitmap bounds")
            return null
        }
        
        val x = max(0f, min(sourceBitmap.width - 1f, box.left - padding)).toInt()
        val y = max(0f, min(sourceBitmap.height - 1f, box.top - padding)).toInt()
        val maxW = sourceBitmap.width - x
        val maxH = sourceBitmap.height - y
        val w = max(1f, min(maxW.toFloat(), (box.right - box.left) + 2 * padding)).toInt()
        val h = max(1f, min(maxH.toFloat(), (box.bottom - box.top) + 2 * padding)).toInt()
        
        if (w <= 0 || h <= 0 || w > maxW || h > maxH) {
            android.util.Log.w("GeometryUtils", "Invalid crop dimensions: ${w}x${h} (max: ${maxW}x${maxH})")
            return null
        }
        
        return try {
            val cropped = Bitmap.createBitmap(sourceBitmap, x, y, w, h)
            android.util.Log.d("GeometryUtils", "Cropped bitmap: ${w}x${h} from (${x},${y})")
            cropped
        } catch (e: Exception) {
            android.util.Log.e("GeometryUtils", "Failed to crop bitmap: ${e.message}")
            null
        }
    }
    
    fun mapPointWithRotation(
        x: Float, y: Float, 
        bw: Float, bh: Float, 
        viewW: Float, viewH: Float, 
        rotation: Int
    ): PointF {
        var xx = x
        var yy = y
        
        when (rotation) {
            90 -> {
                val nx = bh - yy
                val ny = xx
                xx = nx
                yy = ny
            }
            180 -> {
                xx = bw - xx
                yy = bh - yy
            }
            270 -> {
                val nx = yy
                val ny = bw - xx
                xx = nx
                yy = ny
            }
        }
        
        val sx = viewW / if (rotation == 90 || rotation == 270) bh else bw
        val sy = viewH / if (rotation == 90 || rotation == 270) bw else bh
        
        return PointF(xx * sx, yy * sy)
    }
}