package com.example.Yolo_OCR

data class DetBox(
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    val conf: Float, val cls: Int,
    val text: String? = null
)

enum class YoloModel { M896, M640, OBJECT }

data class ModelSpec(
    val assetName: String,
    val inputSize: Int // 896 or 640 (square)
)

fun YoloModel.spec(): ModelSpec = when (this) {
    YoloModel.M896 -> ModelSpec("Yolo.onnx", 896)
    YoloModel.M640 -> ModelSpec("Yolo_640_7.onnx", 640)
    YoloModel.OBJECT -> ModelSpec("object.onnx", 640)
}

// Object detection classes for object.onnx model
enum class ObjectClass(val id: Int, val displayName: String) {
    DOOR(0, "문"),
    EXIT_SIGN(1, "비상구 표지판"),
    FIRE_EXTINGUISHER(2, "소화기"),
    ELEVATOR(3, "엘리베이터"),
    RESTROOM_SIGN(4, "화장실 표지판"),
    WATER_DISPENSER(5, "정수기"),
    STAIR_SIGN(6, "계단 표지판"),
    STAIRS(7, "계단");

    companion object {
        fun fromId(id: Int): ObjectClass? = values().find { it.id == id }
        fun getAllClasses(): List<ObjectClass> = values().toList()
    }
}

// Detection mode - Text recognition or Object detection
enum class DetectionMode {
    TEXT,    // 글자 인식 모드 (기존)
    OBJECT   // 사물 인식 모드 (새로 추가)
}

data class TrackedBox(
    val id: String,
    var box: DetBox,
    var frameCount: Int = 0,
    var lastOcrText: String? = null,
    var isStable: Boolean = false,
    var lastSeen: Long = System.currentTimeMillis()
)