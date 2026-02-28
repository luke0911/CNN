package com.example.Yolo_OCR.ocr

import com.example.Yolo_OCR.DetBox
import org.junit.Test
import org.junit.Assert.*
import org.junit.Before

class BoxMergerTest {

    private lateinit var boxMerger: BoxMerger

    @Before
    fun setup() {
        boxMerger = BoxMerger()
    }

    @Test
    fun `mergeNearbyBoxes returns same boxes when no merging needed`() {
        val boxes = listOf(
            DetBox(0f, 0f, 10f, 10f, 0.8f, 0),
            DetBox(50f, 50f, 60f, 60f, 0.7f, 0)
        )
        
        val result = boxMerger.mergeNearbyBoxes(boxes)
        
        assertEquals(2, result.size)
    }

    @Test
    fun `mergeNearbyBoxes merges horizontally adjacent boxes`() {
        val boxes = listOf(
            DetBox(0f, 0f, 10f, 10f, 0.8f, 0),
            DetBox(12f, 1f, 22f, 9f, 0.7f, 0)  // Horizontally adjacent with good vertical overlap
        )
        
        val result = boxMerger.mergeNearbyBoxes(boxes)
        
        assertEquals(1, result.size)
        val merged = result[0]
        assertEquals(0f, merged.left, 0.1f)
        assertEquals(0f, merged.top, 0.1f)
        assertEquals(22f, merged.right, 0.1f)
        assertEquals(10f, merged.bottom, 0.1f)
    }

    @Test
    fun `mergeNearbyBoxes merges vertically stacked boxes`() {
        val boxes = listOf(
            DetBox(0f, 0f, 20f, 10f, 0.8f, 0),
            DetBox(2f, 12f, 18f, 22f, 0.7f, 0)  // Vertically stacked with good horizontal overlap
        )
        
        val result = boxMerger.mergeNearbyBoxes(boxes)
        
        assertEquals(1, result.size)
        val merged = result[0]
        assertEquals(0f, merged.left, 0.1f)
        assertEquals(0f, merged.top, 0.1f)
        assertEquals(20f, merged.right, 0.1f)
        assertEquals(22f, merged.bottom, 0.1f)
    }

    @Test
    fun `mergeNearbyBoxes handles empty list`() {
        val result = boxMerger.mergeNearbyBoxes(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `mergeNearbyBoxes handles single box`() {
        val boxes = listOf(DetBox(0f, 0f, 10f, 10f, 0.8f, 0))
        
        val result = boxMerger.mergeNearbyBoxes(boxes)
        
        assertEquals(1, result.size)
        assertEquals(boxes[0], result[0])
    }

    @Test
    fun `mergeNearbyBoxes creates connected components correctly`() {
        val boxes = listOf(
            DetBox(0f, 0f, 10f, 10f, 0.8f, 0),     // Group 1
            DetBox(11f, 1f, 21f, 9f, 0.7f, 0),     // Group 1 (connected to first)
            DetBox(22f, 2f, 32f, 8f, 0.6f, 0),     // Group 1 (connected to second)
            DetBox(50f, 50f, 60f, 60f, 0.9f, 0)    // Group 2 (isolated)
        )
        
        val result = boxMerger.mergeNearbyBoxes(boxes)
        
        assertEquals(2, result.size)
        
        // Find the merged group (should span from 0 to 32 horizontally)
        val mergedGroup = result.find { it.right > 30f }
        assertNotNull(mergedGroup)
        assertEquals(0f, mergedGroup!!.left, 0.1f)
        assertEquals(32f, mergedGroup.right, 0.1f)
        
        // Find the isolated box
        val isolatedBox = result.find { it.left > 40f }
        assertNotNull(isolatedBox)
        assertEquals(50f, isolatedBox!!.left, 0.1f)
    }
}