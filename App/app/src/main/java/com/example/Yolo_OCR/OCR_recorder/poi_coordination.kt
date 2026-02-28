package com.example.Yolo_OCR.OCR_recorder

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// POI(텍스트)와 방위각을 함께 기록하는 데이터 클래스
data class PoiAngleEntry(
    val absoluteAngle: Float,     // 절대 방위각(0~360도)
    val relativeAngle: Float,     // 기준점으로부터의 상대 각도
    val text: String,             // OCR로 읽힌 텍스트
    val timestamp: Long = System.currentTimeMillis() // 기록 시간
)

class poi_coordination {

    // 내부 메모리에만 유지되는 로그 리스트
    private val poiLog: MutableList<PoiAngleEntry> = mutableListOf()

    // 기준점 각도 (버튼을 눌렀을 때의 각도를 0도로 설정)
    private var referenceAngle: Float? = null

    // 기록 시작 시간
    private var recordingStartTime: Long = 0L

    // 기준점 설정 (현재 각도를 0도로 설정)
    fun setReferenceAngle(currentAngle: Float) {
        referenceAngle = normalizeAngle(currentAngle)
        recordingStartTime = System.currentTimeMillis()
        Log.d("PoiCoordination", "Reference angle set to: ${referenceAngle}° (absolute: $currentAngle°)")
    }

    // 기준점 초기화
    fun resetReferenceAngle() {
        referenceAngle = null
        Log.d("PoiCoordination", "Reference angle reset")
    }

    // 기준점이 설정되었는지 확인
    fun hasReferenceAngle(): Boolean = referenceAngle != null

    // 현재 상대 각도 계산 (기준점으로부터)
    fun getRelativeAngle(absoluteAngle: Float): Float? {
        val ref = referenceAngle ?: return null
        val normalized = normalizeAngle(absoluteAngle)

        // 상대 각도 계산 (-180 ~ +180도 범위)
        var relative = normalized - ref
        if (relative > 180f) relative -= 360f
        if (relative < -180f) relative += 360f

        return relative
    }

    // 방위각 + OCR 텍스트 한 건 기록
    fun addPoi(angleDeg: Float, rawText: String?) {
        val text = rawText?.trim().orEmpty()
        if (text.isEmpty()) return  // 빈 문자열은 기록 안 함

        val normalizedAngle = normalizeAngle(angleDeg)

        // 상대 각도 계산 (기준점이 없으면 절대 각도 사용)
        val relAngle = getRelativeAngle(angleDeg) ?: normalizedAngle

        val entry = PoiAngleEntry(
            absoluteAngle = normalizedAngle,
            relativeAngle = relAngle,
            text = text
        )

        poiLog.add(entry)

        Log.d("PoiCoordination", "Add POI: abs=${normalizedAngle}°, rel=${relAngle}°, text='${text}'")
    }

    // 저장된 전체 로그 반환 (복사본)
    fun getAll(): List<PoiAngleEntry> = poiLog.toList()

    // 특정 각도 근처(±toleranceDeg)에서 탐지된 POI만 가져오기 (상대 각도 기준)
    fun getPoiNearAngle(targetAngle: Float, toleranceDeg: Float = 5f, useRelative: Boolean = true): List<PoiAngleEntry> {
        return poiLog.filter { entry ->
            val angle = if (useRelative) entry.relativeAngle else entry.absoluteAngle
            val diff = smallestAngleDiff(targetAngle, angle)
            diff <= toleranceDeg
        }
    }

    // 로그 전체 삭제
    fun clear() {
        poiLog.clear()
        referenceAngle = null
        recordingStartTime = 0L
        Log.d("PoiCoordination", "Clear all POI logs and reset reference angle")
    }

    // CSV 파일로 저장
    fun saveToCSV(context: Context, fileName: String? = null): String? {
        if (poiLog.isEmpty()) {
            Log.w("PoiCoordination", "No data to save")
            return null
        }

        try {
            // 파일명 생성 (기본값: poi_record_YYYYMMDD_HHmmss.csv)
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val finalFileName = fileName ?: "poi_record_$timestamp.csv"

            // 저장 경로: Downloads 폴더
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadsDir, finalFileName)

            FileWriter(file).use { writer ->
                // CSV 헤더
                writer.append("Timestamp,AbsoluteAngle,RelativeAngle,Text\n")

                // 데이터 행
                poiLog.forEach { entry ->
                    val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
                        .format(Date(entry.timestamp))

                    // CSV 형식: 텍스트에 쉼표가 있으면 따옴표로 감싸기
                    val escapedText = if (entry.text.contains(",") || entry.text.contains("\"")) {
                        "\"${entry.text.replace("\"", "\"\"")}\""
                    } else {
                        entry.text
                    }

                    writer.append("$timeStr,${entry.absoluteAngle},${entry.relativeAngle},$escapedText\n")
                }
            }

            val fullPath = file.absolutePath
            Log.d("PoiCoordination", "CSV saved: $fullPath (${poiLog.size} entries)")
            return fullPath

        } catch (e: Exception) {
            Log.e("PoiCoordination", "Failed to save CSV", e)
            return null
        }
    }

    // 디버깅용: 전체 로그를 Logcat에 예쁘게 출력
    fun printLogToLogcat() {
        if (poiLog.isEmpty()) {
            Log.d("PoiCoordination", "No POI logs recorded")
            return
        }

        val refStr = if (referenceAngle != null) "Reference: ${referenceAngle}°" else "No reference set"

        Log.d("PoiCoordination", "========= POI ANGLE LOG =========")
        Log.d("PoiCoordination", refStr)
        Log.d("PoiCoordination", "Total entries: ${poiLog.size}")
        Log.d("PoiCoordination", "---------------------------------")

        poiLog.forEachIndexed { index, entry ->
            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                .format(Date(entry.timestamp))
            Log.d(
                "PoiCoordination",
                "#${index + 1} [${timeStr}] abs=${entry.absoluteAngle}°, rel=${entry.relativeAngle}°, text='${entry.text}'"
            )
        }
        Log.d("PoiCoordination", "=================================")
    }

    // 0~360도로 정규화
    private fun normalizeAngle(angleDeg: Float): Float {
        var a = angleDeg % 360f
        if (a < 0f) a += 360f
        return a
    }

    // 두 각도 사이의 최소 차이(0~180도)를 반환
    private fun smallestAngleDiff(a: Float, b: Float): Float {
        var diff = (a - b) % 360f
        if (diff < -180f) diff += 360f
        if (diff > 180f) diff -= 360f
        return kotlin.math.abs(diff)
    }
}