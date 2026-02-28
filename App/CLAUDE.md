# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is an Android Kotlin application that combines YOLO object detection with OCR (Optical Character Recognition) using machine learning models. The app can operate in two modes:
1. **Text Recognition Mode**: Detects text regions and performs OCR using ML Kit
2. **Object Detection Mode**: Detects 7 specific object classes (door, exit sign, fire extinguisher, elevator, restroom sign, water dispenser, stair sign)

## Build Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Clean build
./gradlew clean

# Install debug APK on connected device
./gradlew installDebug

# Run tests
./gradlew test
```

## Architecture Overview

### Core Components

- **MainActivity**: Central activity managing camera, YOLO detection, and UI interactions
- **YoloEngine**: ONNX Runtime integration for running YOLO models
- **OcrEngine**: ML Kit OCR processing with text tracking and caching
- **OcrProcessor**: Handles individual OCR operations with dual Korean/Latin recognition
- **OverlayView**: Custom view for rendering detection boxes and text overlays

### Model Support

The app supports multiple YOLO models via the `YoloModel` enum:
- `M896`: Text detection (896x896 input, Yolo.onnx)
- `M640`: Text detection (640x640 input, Yolo_640_7.onnx) 
- `OBJECT`: Object detection (640x640 input, object.onnx with 7 classes)

All models are stored in `app/src/main/assets/` and loaded via ONNX Runtime Android.

### Detection Pipeline

1. **Camera Capture**: CameraX provides YUV frames
2. **Preprocessing**: YUV→RGB conversion, rotation correction, letterbox scaling
3. **YOLO Inference**: Model processes 640x640 or 896x896 input tensors
4. **Output Parsing**: `YoloOutputParser` handles various output formats ([1,11,8400], [1,5,N], etc.)
5. **Post-processing**: NMS (Non-Maximum Suppression), coordinate mapping back to original image
6. **OCR Processing**: For text mode, detected regions undergo ML Kit OCR
7. **UI Rendering**: Results displayed via `OverlayView` with different colors per mode

### Key Files Structure

```
app/src/main/java/com/example/Yolo_OCR/
├── MainActivity.kt                    # Main activity with camera and detection logic
├── DetTypes.kt                       # Data classes and enums (DetBox, YoloModel, ObjectClass)
├── YoloEngine.kt                     # ONNX Runtime integration
├── OcrEngine.kt                      # OCR coordination and box tracking
├── OverlayView.kt                    # Custom view for drawing detections
├── ocr/
│   ├── OcrProcessor.kt              # ML Kit OCR processing
│   └── BoxMerger.kt                 # Box merging algorithms
├── yolo/
│   └── YoloOutputParser.kt          # YOLO output format parsing
├── utils/
│   ├── Constants.kt                 # Configuration constants
│   ├── GeometryUtils.kt             # Geometry and coordinate utilities
│   └── BitmapUtils.kt               # Bitmap processing utilities
└── performance/
    └── PerformanceMonitor.kt        # Performance tracking
```

## Configuration

### Detection Thresholds
- Confidence threshold: 0.20 (text), 0.1 (object - for debugging)
- IoU threshold: 0.10 
- Minimum box size: 8x8 pixels
- OCR timeout: 400ms

### Object Detection Classes
The object.onnx model detects 7 classes (defined in `ObjectClass` enum):
0. Door (문)
1. Exit Sign (비상구 표지판) 
2. Fire Extinguisher (소화기)
3. Elevator (엘리베이터)
4. Restroom Sign (화장실 표지판)
5. Water Dispenser (정수기)
6. Stair Sign (계단 표지판)

## Development Notes

### Model Debugging
The app includes extensive logging for debugging model issues:
- `YoloEngine`: Logs output shapes and detection counts
- `YoloOutputParser`: Logs array dimensions and parsing paths
- `MainActivity`: Logs detection filtering and mode switching

Key debugging log tags: `YOLO_DEBUG`, `YoloOutputParser`, `YoloEngine`, `OBJECT_DEBUG`

### ONNX Runtime Compatibility
- Current version: 1.20.0
- The object.onnx model requires IR version 11 support
- Output format: [1, 11, 8400] where 11 = 4 bbox coords + 7 class confidences

### Common Issues
1. **Object detection showing 0 detections**: Check confidence thresholds, model output parsing, and class filtering logic
2. **Model loading failures**: Verify ONNX Runtime version compatibility and model file integrity
3. **UI crashes**: Ensure proper bitmap lifecycle management and null checks

### UI Controls
- Detection start/stop: Bottom toggle buttons
- Mode switching: Top-right "글자↔사물" button  
- Object class filtering: 7 toggle buttons (only visible in object mode)
- Model switching: 896↔640 button (text mode only)

### Threading
- Camera analysis: Single background executor
- OCR processing: ThreadPoolExecutor with configurable concurrency
- UI updates: Main thread via coroutines

The app uses a sophisticated tracking system to maintain detection consistency across frames and provides real-time filtering based on user-selected object classes.