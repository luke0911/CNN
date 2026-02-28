package com.example.Yolo_OCR.utils

import android.graphics.*
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import kotlin.math.max

object BitmapUtils {
    
    fun yuvToRgbSafe(image: ImageProxy): Bitmap {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val ySize = width * height
        val uvSize = width * height / 2
        val nv21 = ByteArray(ySize + uvSize)

        copyYPlane(yPlane, nv21, width, height, ySize)
        copyUVPlanes(uPlane, vPlane, nv21, width, height, ySize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), Constants.CAMERA_YUV_JPEG_QUALITY, out)
        val bytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
    
    private fun copyYPlane(yPlane: ImageProxy.PlaneProxy, nv21: ByteArray, width: Int, height: Int, ySize: Int) {
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride

        if (yRowStride == width) {
            yBuffer.get(nv21, 0, ySize)
        } else {
            var dstIndex = 0
            for (row in 0 until height) {
                val srcPos = row * yRowStride
                yBuffer.position(srcPos)
                yBuffer.get(nv21, dstIndex, width)
                dstIndex += width
            }
        }
    }
    
    private fun copyUVPlanes(
        uPlane: ImageProxy.PlaneProxy,
        vPlane: ImageProxy.PlaneProxy,
        nv21: ByteArray,
        width: Int,
        height: Int,
        ySize: Int
    ) {
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride

        var uvIndex = ySize
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                val uIndex = row * uRowStride + col * uPixelStride
                val vIndex = row * vRowStride + col * vPixelStride

                nv21[uvIndex++] = vBuffer.get(vIndex)
                nv21[uvIndex++] = uBuffer.get(uIndex)
            }
        }
    }
    
    fun ensureRotated(src: Bitmap, rotation: Int): Bitmap {
        val rot = ((rotation % 360) + 360) % 360
        if (rot == 0) return src
        
        val matrix = Matrix().apply { postRotate(rot.toFloat()) }
        val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        
        if (rotated != src && !src.isRecycled) {
            src.recycle()
        }
        
        return rotated
    }
    
    fun bitmapToFloat32Tensor(bmp: Bitmap): Array<Array<Array<FloatArray>>> {
        val w = bmp.width
        val h = bmp.height
        val out = Array(1) { Array(3) { Array(h) { FloatArray(w) } } }
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        // Optimized: Direct array references to reduce indexing
        val channel0 = out[0][0]
        val channel1 = out[0][1]
        val channel2 = out[0][2]

        var idx = 0
        for (y in 0 until h) {
            val row0 = channel0[y]
            val row1 = channel1[y]
            val row2 = channel2[y]

            for (x in 0 until w) {
                val p = pixels[idx++]
                row0[x] = ((p shr 16) and 0xFF) * 0.003921569f  // Faster than /255f
                row1[x] = ((p shr 8) and 0xFF) * 0.003921569f
                row2[x] = (p and 0xFF) * 0.003921569f
            }
        }
        return out
    }
    
    fun enhanceForOcr(bitmap: Bitmap): Bitmap {
        val targetSize = 160
        val scaledBitmap = if (bitmap.width < targetSize || bitmap.height < targetSize) {
            val scale = max(targetSize.toFloat() / bitmap.width, targetSize.toFloat() / bitmap.height)
            val newWidth = (bitmap.width * scale).toInt()
            val newHeight = (bitmap.height * scale).toInt()
            Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        } else {
            bitmap
        }

        val enhanced = Bitmap.createBitmap(scaledBitmap.width, scaledBitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(enhanced)

        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply {
                set(floatArrayOf(
                    1.4f, 0f, 0f, 0f, -40f,
                    0f, 1.4f, 0f, 0f, -40f,
                    0f, 0f, 1.4f, 0f, -40f,
                    0f, 0f, 0f, 1f, 0f
                ))
            })
        }

        canvas.drawBitmap(scaledBitmap, 0f, 0f, paint)

        if (scaledBitmap != bitmap) {
            scaledBitmap.recycle()
        }

        return enhanced
    }
    
    fun sanitizeFilename(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }
}