package com.example.Yolo_OCR.ocr

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.example.Yolo_OCR.DetBox
import com.example.Yolo_OCR.utils.Constants
import com.example.Yolo_OCR.utils.GeometryUtils
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

class OcrProcessor(private val lastRotation: () -> Int) {
    
    companion object {
        private const val TAG = "OcrProcessor"
    }
    
    private val koreanOcr = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    private val latinOcr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    
    fun processImage(box: DetBox, bitmap: Bitmap, onResult: (String?) -> Unit) {
        // Check if source bitmap is valid
        if (bitmap.isRecycled) {
            Log.w(TAG, "Source bitmap is recycled, skipping OCR")
            onResult(null)
            return
        }
        
        val enhancedBitmap = preprocessBitmap(box, bitmap)
        if (enhancedBitmap == null) {
            Log.w(TAG, "Failed to preprocess bitmap for OCR")
            onResult(null)
            return
        }
        
        // Validate processed bitmap
        if (enhancedBitmap.isRecycled) {
            Log.w(TAG, "Processed bitmap is recycled, skipping OCR")
            onResult(null)
            return
        }
        
        val rotationDeg = normalizeRotation(lastRotation())
        val inputImage = try {
            InputImage.fromBitmap(enhancedBitmap, rotationDeg)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create InputImage: ${e.message}")
            // Clean up
            if (enhancedBitmap != bitmap && !enhancedBitmap.isRecycled) {
                enhancedBitmap.recycle()
            }
            onResult(null)
            return
        }
        
        runDualOcr(inputImage, enhancedBitmap, bitmap, onResult)
    }
    
    private fun preprocessBitmap(box: DetBox, sourceBitmap: Bitmap): Bitmap? {
        // Validate input
        if (sourceBitmap.isRecycled) {
            Log.w(TAG, "Source bitmap is recycled")
            return null
        }
        
        // Validate box dimensions
        val boxW = box.right - box.left
        val boxH = box.bottom - box.top
        if (boxW <= 0 || boxH <= 0) {
            Log.w(TAG, "Invalid box dimensions: ${boxW}x${boxH}")
            return null
        }
        
        val padding = calculatePadding(box)
        val cropped = GeometryUtils.cropBitmapSafe(sourceBitmap, box, padding)
        
        if (cropped == null) {
            Log.w(TAG, "Failed to crop bitmap")
            return null
        }
        
        if (cropped.isRecycled) {
            Log.w(TAG, "Cropped bitmap is recycled")
            return null
        }
        
        if (!isValidForOcr(cropped)) {
            Log.w(TAG, "Cropped bitmap too small for OCR: ${cropped.width}x${cropped.height}")
            if (cropped != sourceBitmap) {
                cropped.recycle()
            }
            return null
        }
        
        val scaled = scaleForOcr(cropped)
        
        if (scaled != cropped && scaled != sourceBitmap && !scaled.isRecycled) {
            Log.d(TAG, "Preprocessed bitmap: ${scaled.width}x${scaled.height}")
        }
        
        return scaled
    }
    
    private fun calculatePadding(box: DetBox): Float {
        val boxW = box.right - box.left
        val boxH = box.bottom - box.top
        return max(8f, 0.2f * max(boxW, boxH))
    }
    
    private fun isValidForOcr(bitmap: Bitmap): Boolean {
        return bitmap.width >= Constants.OCR_MIN_ROI_WIDTH && 
               bitmap.height >= Constants.OCR_MIN_ROI_HEIGHT
    }
    
    private fun scaleForOcr(bitmap: Bitmap): Bitmap {
        if (bitmap.isRecycled) {
            Log.w(TAG, "Input bitmap is recycled in scaleForOcr")
            return bitmap
        }
        
        val upscale = calculateUpscale(bitmap.height)
        val upscaled = if (upscale > 1f) {
            try {
                val newW = (bitmap.width * upscale).toInt()
                val newH = (bitmap.height * upscale).toInt()
                Log.d(TAG, "Upscaling bitmap: ${bitmap.width}x${bitmap.height} -> ${newW}x${newH}")
                Bitmap.createScaledBitmap(bitmap, newW, newH, true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to upscale bitmap: ${e.message}")
                bitmap
            }
        } else bitmap
        
        if (upscaled.isRecycled) {
            Log.w(TAG, "Upscaled bitmap is recycled")
            return bitmap
        }
        
        val downscale = calculateDownscale(upscaled)
        val result = if (downscale < 1f) {
            try {
                val newW = (upscaled.width * downscale).toInt()
                val newH = (upscaled.height * downscale).toInt()
                Log.d(TAG, "Downscaling bitmap: ${upscaled.width}x${upscaled.height} -> ${newW}x${newH}")
                Bitmap.createScaledBitmap(upscaled, newW, newH, true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to downscale bitmap: ${e.message}")
                upscaled
            }
        } else upscaled
        
        // Clean up intermediate bitmaps
        try {
            if (upscaled != bitmap && upscaled != result && !upscaled.isRecycled) {
                upscaled.recycle()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error recycling intermediate bitmap: ${e.message}")
        }
        
        return result
    }
    
    private fun calculateUpscale(height: Int): Float {
        return if (height < 2 * Constants.OCR_MIN_CHAR_HEIGHT) {
            ((2f * Constants.OCR_MIN_CHAR_HEIGHT) / height).coerceAtLeast(1.5f)
        } else 1f
    }
    
    private fun calculateDownscale(bitmap: Bitmap): Float {
        return minOf(
            Constants.OCR_MAX_SIDE.toFloat() / bitmap.width,
            Constants.OCR_MAX_SIDE.toFloat() / bitmap.height,
            1f
        )
    }
    
    private fun normalizeRotation(rotation: Int): Int {
        val r = ((rotation % 360) + 360) % 360
        return when {
            r in 315..359 || r in 0..44 -> 0
            r in 45..134 -> 90
            r in 135..224 -> 180
            else -> 270
        }
    }
    
    private fun runDualOcr(input: InputImage, enhancedBitmap: Bitmap, originalBitmap: Bitmap, onResult: (String?) -> Unit) {
        val decided = AtomicBoolean(false)
        var bestResult: String? = null
        
        // Cleanup function
        val cleanup = {
            try {
                if (enhancedBitmap != originalBitmap && !enhancedBitmap.isRecycled) {
                    enhancedBitmap.recycle()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error during cleanup: ${e.message}")
            }
        }
        
        // Korean OCR
        koreanOcr.process(input)
            .addOnSuccessListener { text ->
                try {
                    extractText(text)?.let { result ->
                        Log.d(TAG, "Korean OCR success: '$result'")
                        if (decided.compareAndSet(false, true)) {
                            cleanup()
                            onResult(result)
                        } else {
                            bestResult = result
                        }
                    } ?: run {
                        Log.d(TAG, "Korean OCR returned empty text")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing Korean OCR result: ${e.message}")
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Korean OCR failed: ${e.message}")
                // Don't cleanup here, Latin OCR might still succeed
            }
        
        // Latin OCR
        latinOcr.process(input)
            .addOnSuccessListener { text ->
                try {
                    extractText(text)?.let { result ->
                        Log.d(TAG, "Latin OCR success: '$result'")
                        if (decided.compareAndSet(false, true)) {
                            cleanup()
                            onResult(result)
                        } else {
                            bestResult = result
                        }
                    } ?: run {
                        Log.d(TAG, "Latin OCR returned empty text")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing Latin OCR result: ${e.message}")
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Latin OCR failed: ${e.message}")
                // Don't cleanup here, timeout might still trigger
            }
        
        // Timeout handling
        setTimeout(Constants.OCR_TIMEOUT_MS) {
            if (decided.compareAndSet(false, true)) {
                Log.d(TAG, "OCR timeout, using best result: '$bestResult'")
                cleanup()
                onResult(bestResult)
            }
        }
    }
    
    private fun extractText(text: Text): String? {
        val result = text.textBlocks.joinToString(" ") { block ->
            block.lines.joinToString(" ") { line ->
                line.elements.joinToString("") { it.text }
            }
        }.trim()
        
        return if (result.isBlank()) null else result
    }
    
    private fun setTimeout(timeoutMs: Long, action: () -> Unit) {
        Thread {
            try {
                Thread.sleep(timeoutMs)
                action()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }.start()
    }
}