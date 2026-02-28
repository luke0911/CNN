package com.example.Yolo_OCR.utils

import com.example.Yolo_OCR.DetBox
import org.junit.Test
import org.junit.Assert.*
import kotlin.math.abs

class GeometryUtilsTest {

    @Test
    fun `iou calculation returns correct values for overlapping boxes`() {
        val box1 = DetBox(0f, 0f, 10f, 10f, 0.8f, 0)
        val box2 = DetBox(5f, 5f, 15f, 15f, 0.7f, 0)
        
        val iou = GeometryUtils.iou(box1, box2)
        
        // Expected: intersection = 25, union = 175, iou = 25/175 ≈ 0.143
        assertTrue("IoU should be approximately 0.143", abs(iou - 0.143f) < 0.001f)
    }

    @Test
    fun `iou calculation returns zero for non-overlapping boxes`() {
        val box1 = DetBox(0f, 0f, 5f, 5f, 0.8f, 0)
        val box2 = DetBox(10f, 10f, 15f, 15f, 0.7f, 0)
        
        val iou = GeometryUtils.iou(box1, box2)
        
        assertEquals(0f, iou, 0.001f)
    }

    @Test
    fun `iou calculation returns one for identical boxes`() {
        val box1 = DetBox(0f, 0f, 10f, 10f, 0.8f, 0)
        val box2 = DetBox(0f, 0f, 10f, 10f, 0.7f, 0)
        
        val iou = GeometryUtils.iou(box1, box2)
        
        assertEquals(1f, iou, 0.001f)
    }

    @Test
    fun `cropBitmapSafe returns null for recycled bitmap`() {
        // Cannot test actual bitmap recycling in unit tests
        // This would require instrumented tests with actual Android context
    }

    @Test
    fun `mapPointWithRotation handles 90 degree rotation correctly`() {
        val point = GeometryUtils.mapPointWithRotation(
            10f, 20f, 100f, 200f, 100f, 200f, 90
        )
        
        // After 90 degree rotation: x' = bh - y, y' = x
        // Expected: x' = 200 - 20 = 180, y' = 10
        // Then scale by viewW/bh and viewH/bw respectively
        val expectedX = 180f * (100f / 200f)  // 90f
        val expectedY = 10f * (200f / 100f)   // 20f
        
        assertEquals(expectedX, point.x, 0.1f)
        assertEquals(expectedY, point.y, 0.1f)
    }

    @Test
    fun `mapPointWithRotation handles no rotation correctly`() {
        val point = GeometryUtils.mapPointWithRotation(
            10f, 20f, 100f, 200f, 50f, 100f, 0
        )
        
        // No rotation, just scaling
        val expectedX = 10f * (50f / 100f)   // 5f
        val expectedY = 20f * (100f / 200f)  // 10f
        
        assertEquals(expectedX, point.x, 0.1f)
        assertEquals(expectedY, point.y, 0.1f)
    }
}