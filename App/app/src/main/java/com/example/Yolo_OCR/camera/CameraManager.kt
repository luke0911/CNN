package com.example.Yolo_OCR.camera

import android.content.Context
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.Yolo_OCR.utils.Constants
import java.util.concurrent.Executor

class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val analysisExecutor: Executor
) {
    private var boundCameraProvider: ProcessCameraProvider? = null
    private var imageAnalyzer: ImageAnalysis? = null
    
    fun startCamera(
        previewView: PreviewView,
        analyzer: ImageAnalysis.Analyzer
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            boundCameraProvider = cameraProvider
            
            val preview = createPreview(previewView)
            val imageAnalysis = createImageAnalysis(analyzer)
            
            bindCamera(cameraProvider, preview, imageAnalysis)
        }, ContextCompat.getMainExecutor(context))
    }
    
    private fun createPreview(previewView: PreviewView): Preview {
        return Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }
    }
    
    private fun createImageAnalysis(analyzer: ImageAnalysis.Analyzer): ImageAnalysis {
        return ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also { 
                it.setAnalyzer(analysisExecutor, analyzer)
                imageAnalyzer = it
            }
    }
    
    private fun bindCamera(
        cameraProvider: ProcessCameraProvider,
        preview: Preview,
        imageAnalysis: ImageAnalysis
    ) {
        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            imageAnalysis
        )
    }
    
    fun restartPipeline() {
        boundCameraProvider?.unbindAll()
    }
    
    fun shutdown() {
        boundCameraProvider?.unbindAll()
        boundCameraProvider = null
        imageAnalyzer = null
    }
}