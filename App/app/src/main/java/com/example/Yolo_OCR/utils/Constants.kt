package com.example.Yolo_OCR.utils

object Constants {
    
    // Math constants
    const val EPSILON = 1e-6f
    
    // YOLO constants
    const val YOLO_MIN_CHANNELS = 84
    const val YOLO_STANDARD_CHANNELS_5 = 5
    const val YOLO_STANDARD_CHANNELS_6 = 6
    const val YOLO_STANDARD_CHANNELS_11 = 11  // Legacy: 4(bbox) + 7(classes)
    const val YOLO_STANDARD_CHANNELS_12 = 12  // Object detection: 4(bbox) + 8(classes)
    const val YOLO_CONFIDENCE_SCALING = 1.5f
    
    // OCR constants
    const val OCR_TIMEOUT_MS = 400L
    const val OCR_MIN_INTERVAL_MS = 500L  // OCR 실행 간격
    const val OCR_BOX_EXPIRE_MS = 1000L
    const val OCR_MIN_CHAR_HEIGHT = 20 // Reduced from 28
    const val OCR_MAX_SIDE = 640
    const val OCR_MIN_ROI_WIDTH = 20  // Reduced from 32
    const val OCR_MIN_ROI_HEIGHT = 12 // Reduced from 16
    const val OCR_STABLE_FRAMES = 3  // 연속 3프레임 안정적인 박스만 OCR 실행
    const val OCR_MAX_CONCURRENT = 2
    const val OCR_QUEUE_SIZE = 3
    const val OCR_THREAD_KEEP_ALIVE_MS = 2000L
    
    // UI constants
    const val UI_MAX_DETECTIONS_DEFAULT = 10
    const val UI_TEXT_TTL_MS = 1500L
    const val UI_IOU_THRESHOLD_FOR_TEXT_MATCHING = 0.2f
    const val UI_IOU_THRESHOLD_FOR_BOX_MATCHING = 0.5f
    const val UI_STATS_UPDATE_INTERVAL_MS = 2000L
    const val UI_LOG_EVERY_N_FRAMES = 1L
    
    // Camera constants
    const val CAMERA_DELAY_LOG_THRESHOLD_MS = 50f
    const val CAMERA_YUV_JPEG_QUALITY = 85

    // Frame skip optimization (카메라 프레임 레이트 제한으로 불필요해짐)
    // FRAME_SKIP_INTERVAL = 1 means process every 2nd frame (skip 1, process 1)
    // FRAME_SKIP_INTERVAL = 2 means process every 3rd frame (skip 2, process 1)
    // 카메라 프레임 레이트가 10-15fps로 제한되어 있으므로 스킵 간격 감소
    const val FRAME_SKIP_INTERVAL = 0  // Process every frame (카메라 자체가 10-15fps)

    // Performance constants
    const val PERF_MONITOR_INTERVAL_MS = 2000L
    
    // Detection thresholds (defaults)
    const val DEFAULT_CONF_THRESHOLD = 0.20f
    const val DEFAULT_IOU_THRESHOLD = 0.10f
    const val DEFAULT_MIN_BOX_WIDTH = 8f
    const val DEFAULT_MIN_BOX_HEIGHT = 8f
    
    // Bitmap processing
    const val BITMAP_COMPRESS_QUALITY = 92
    const val MIN_BOX_SIZE_FOR_OCR = 20
    
    // File saving
    const val SAVE_MIN_INTERVAL_MS = 1500L
    
    // Box merging constants
    const val BOX_MERGE_VERTICAL_OVERLAP_THRESHOLD = 0.4f
    const val BOX_MERGE_HORIZONTAL_OVERLAP_THRESHOLD = 0.3f
    const val BOX_MERGE_HORIZONTAL_GAP_MULTIPLIER = 1.2f
    const val BOX_MERGE_VERTICAL_GAP_MULTIPLIER = 0.6f
    
    // ID generation
    const val ID_QUANTIZATION_STEP = 64f
}