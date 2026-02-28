package com.example.Yolo_OCR

import org.junit.Test
import org.junit.Assert.*

class DetTypesTest {

    @Test
    fun `DetBox data class holds correct values`() {
        val box = DetBox(
            left = 10f,
            top = 20f,
            right = 50f,
            bottom = 60f,
            conf = 0.85f,
            cls = 1,
            text = "Test Text"
        )
        
        assertEquals(10f, box.left, 0.001f)
        assertEquals(20f, box.top, 0.001f)
        assertEquals(50f, box.right, 0.001f)
        assertEquals(60f, box.bottom, 0.001f)
        assertEquals(0.85f, box.conf, 0.001f)
        assertEquals(1, box.cls)
        assertEquals("Test Text", box.text)
    }

    @Test
    fun `DetBox without text defaults to null`() {
        val box = DetBox(
            left = 10f,
            top = 20f,
            right = 50f,
            bottom = 60f,
            conf = 0.85f,
            cls = 1
        )
        
        assertNull(box.text)
    }

    @Test
    fun `DetBox copy works correctly`() {
        val original = DetBox(10f, 20f, 50f, 60f, 0.85f, 1, "Original")
        val copied = original.copy(text = "Modified")
        
        assertEquals(original.left, copied.left, 0.001f)
        assertEquals(original.top, copied.top, 0.001f)
        assertEquals(original.right, copied.right, 0.001f)
        assertEquals(original.bottom, copied.bottom, 0.001f)
        assertEquals(original.conf, copied.conf, 0.001f)
        assertEquals(original.cls, copied.cls)
        assertEquals("Modified", copied.text)
        assertEquals("Original", original.text) // Original unchanged
    }

    @Test
    fun `YoloModel enum has correct values`() {
        val models = YoloModel.values()
        assertEquals(2, models.size)
        assertTrue(models.contains(YoloModel.M896))
        assertTrue(models.contains(YoloModel.M640))
    }

    @Test
    fun `YoloModel spec returns correct ModelSpec`() {
        val spec896 = YoloModel.M896.spec()
        assertEquals("Yolo.onnx", spec896.assetName)
        assertEquals(896, spec896.inputSize)
        
        val spec640 = YoloModel.M640.spec()
        assertEquals("Yolo_640_7.onnx", spec640.assetName)
        assertEquals(640, spec640.inputSize)
    }

    @Test
    fun `TrackedBox data class holds correct values`() {
        val detBox = DetBox(10f, 20f, 50f, 60f, 0.85f, 1)
        val trackedBox = TrackedBox(
            id = "test_id",
            box = detBox,
            frameCount = 5,
            lastOcrText = "Test OCR",
            isStable = true,
            lastSeen = 123456789L
        )
        
        assertEquals("test_id", trackedBox.id)
        assertEquals(detBox, trackedBox.box)
        assertEquals(5, trackedBox.frameCount)
        assertEquals("Test OCR", trackedBox.lastOcrText)
        assertTrue(trackedBox.isStable)
        assertEquals(123456789L, trackedBox.lastSeen)
    }

    @Test
    fun `ModelSpec data class holds correct values`() {
        val modelSpec = ModelSpec(
            assetName = "test.onnx",
            inputSize = 512
        )
        
        assertEquals("test.onnx", modelSpec.assetName)
        assertEquals(512, modelSpec.inputSize)
    }
}