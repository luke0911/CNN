package com.example.Yolo_OCR.OCR_recorder

import android.content.Context
import android.widget.Toast

/**
 * POI Coordination 사용 예시
 *
 * 핸드폰을 정면으로 두고 360도 회전하면서 OCR 텍스트와 방위각을 기록하는 방법
 */

class POI_USAGE_EXAMPLE(private val context: Context) {

    // POI Coordination 인스턴스
    private val poiCoordination = poi_coordination()

    // 센서에서 방위각 가져오기 (예시)
    private var currentAzimuth: Float = 0f  // 실제로는 센서에서 업데이트됨

    /**
     * 사용 흐름:
     *
     * 1. 사용자가 "기준점 설정" 버튼을 누름
     *    → setReferenceAngle() 호출
     *
     * 2. 사용자가 핸드폰을 들고 360도 회전
     *    → OCR이 텍스트를 인식할 때마다 addPoi() 호출
     *
     * 3. 회전 완료 후 "저장" 버튼을 누름
     *    → saveToCSV() 호출
     */

    // ========================================
    // 1. 기준점 설정 (버튼 클릭 시 호출)
    // ========================================
    fun onSetReferenceButtonClicked() {
        // 현재 방위각을 0도 기준점으로 설정
        poiCoordination.setReferenceAngle(currentAzimuth)

        Toast.makeText(
            context,
            "기준점 설정 완료! 현재 방향을 0도로 설정했습니다.\n이제 360도 회전하세요.",
            Toast.LENGTH_LONG
        ).show()
    }

    // ========================================
    // 2. OCR 텍스트 인식 시 호출 (자동)
    // ========================================
    fun onOcrTextDetected(ocrText: String?, currentAzimuth: Float) {
        // OCR이 텍스트를 감지할 때마다 자동으로 호출됨
        this.currentAzimuth = currentAzimuth
        poiCoordination.addPoi(currentAzimuth, ocrText)

        // 예시: "EXIT"라는 텍스트가 20도 방향에서 감지됨
        // → CSV에 기록: 절대각도=20°, 상대각도=20°, Text=EXIT
    }

    // ========================================
    // 3. CSV 저장 (버튼 클릭 시 호출)
    // ========================================
    fun onSaveButtonClicked() {
        val savedPath = poiCoordination.saveToCSV(context)

        if (savedPath != null) {
            Toast.makeText(
                context,
                "저장 완료!\n$savedPath",
                Toast.LENGTH_LONG
            ).show()

            // Logcat에도 출력
            poiCoordination.printLogToLogcat()
        } else {
            Toast.makeText(
                context,
                "저장 실패: 데이터가 없습니다",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ========================================
    // 4. 초기화 (새로운 기록 시작)
    // ========================================
    fun onResetButtonClicked() {
        poiCoordination.clear()

        Toast.makeText(
            context,
            "모든 데이터가 초기화되었습니다",
            Toast.LENGTH_SHORT
        ).show()
    }

    // ========================================
    // 5. 특정 각도 근처의 POI 조회
    // ========================================
    fun searchPoiNear(targetAngle: Float, tolerance: Float = 5f) {
        // 예: 45도 근처(±5도)에서 감지된 텍스트 조회
        val nearbyPois = poiCoordination.getPoiNearAngle(targetAngle, tolerance, useRelative = true)

        if (nearbyPois.isEmpty()) {
            Toast.makeText(
                context,
                "${targetAngle}° 근처에 POI가 없습니다",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            val texts = nearbyPois.joinToString(", ") { it.text }
            Toast.makeText(
                context,
                "${targetAngle}° 근처 POI: $texts",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ========================================
    // 6. 현재 상태 확인
    // ========================================
    fun getStatus(): String {
        val hasRef = poiCoordination.hasReferenceAngle()
        val count = poiCoordination.getAll().size

        return if (hasRef) {
            "기록 중: $count 개 POI 저장됨"
        } else {
            "대기 중: 기준점을 설정하세요"
        }
    }
}
