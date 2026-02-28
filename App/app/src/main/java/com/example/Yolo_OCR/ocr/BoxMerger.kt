package com.example.Yolo_OCR.ocr

import android.util.Log
import com.example.Yolo_OCR.DetBox
import com.example.Yolo_OCR.utils.Constants
import kotlin.math.max
import kotlin.math.min

class BoxMerger {
    
    companion object {
        private const val TAG = "BoxMerger"
    }
    
    fun mergeNearbyBoxes(boxes: List<DetBox>): List<DetBox> {
        if (boxes.isEmpty()) return boxes
        
        val adjacencyMatrix = buildAdjacencyMatrix(boxes)
        val groups = findConnectedComponents(boxes, adjacencyMatrix)
        val merged = mergeGroups(groups)
        
        Log.d(TAG, "mergeNearbyBoxes: in=${boxes.size}, groups=${groups.size}, out=${merged.size}")
        return merged
    }
    
    private fun buildAdjacencyMatrix(boxes: List<DetBox>): Array<BooleanArray> {
        val n = boxes.size
        val adj = Array(n) { BooleanArray(n) }
        
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                if (areNeighbors(boxes[i], boxes[j])) {
                    adj[i][j] = true
                    adj[j][i] = true
                }
            }
        }
        
        return adj
    }
    
    private fun areNeighbors(a: DetBox, b: DetBox): Boolean {
        val aH = a.bottom - a.top
        val bH = b.bottom - b.top
        val aW = a.right - a.left
        val bW = b.right - b.left
        
        val vertOverlap = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
        val horizOverlap = max(0f, min(a.right, b.right) - max(a.left, b.left))
        val minH = min(aH, bH)
        val avgH = (aH + bH) / 2f
        
        val horizGap = calculateHorizontalGap(a, b)
        val vertGap = calculateVerticalGap(a, b)
        
        // Same line (horizontal chain): sufficient vertical overlap and small horizontal gap
        val inline = (vertOverlap / minH) >= Constants.BOX_MERGE_VERTICAL_OVERLAP_THRESHOLD && 
                    (horizGap <= Constants.BOX_MERGE_HORIZONTAL_GAP_MULTIPLIER * max(aH, bH))
        
        // Stacked lines: sufficient horizontal overlap and small vertical gap
        val stacked = (horizOverlap / max(aW, bW)) >= Constants.BOX_MERGE_HORIZONTAL_OVERLAP_THRESHOLD && 
                     (vertGap <= Constants.BOX_MERGE_VERTICAL_GAP_MULTIPLIER * avgH)
        
        return inline || stacked
    }
    
    private fun calculateHorizontalGap(a: DetBox, b: DetBox): Float {
        return when {
            a.right < b.left -> b.left - a.right
            b.right < a.left -> a.left - b.right
            else -> 0f
        }
    }
    
    private fun calculateVerticalGap(a: DetBox, b: DetBox): Float {
        return when {
            a.bottom < b.top -> b.top - a.bottom
            b.bottom < a.top -> a.top - b.bottom
            else -> 0f
        }
    }
    
    private fun findConnectedComponents(boxes: List<DetBox>, adj: Array<BooleanArray>): List<List<DetBox>> {
        val n = boxes.size
        val visited = BooleanArray(n)
        val groups = mutableListOf<List<DetBox>>()
        
        for (i in 0 until n) {
            if (visited[i]) continue
            
            val component = mutableListOf<DetBox>()
            val queue = ArrayDeque<Int>()
            queue.add(i)
            visited[i] = true
            
            while (queue.isNotEmpty()) {
                val u = queue.removeFirst()
                component.add(boxes[u])
                
                for (v in 0 until n) {
                    if (!visited[v] && adj[u][v]) {
                        visited[v] = true
                        queue.add(v)
                    }
                }
            }
            
            groups.add(component)
        }
        
        return groups
    }
    
    private fun mergeGroups(groups: List<List<DetBox>>): List<DetBox> {
        return groups.map { group ->
            if (group.size == 1) group[0] else mergeGroup(group)
        }
    }
    
    private fun mergeGroup(boxes: List<DetBox>): DetBox {
        if (boxes.size == 1) return boxes[0]
        
        val left = boxes.minOf { it.left }
        val top = boxes.minOf { it.top }
        val right = boxes.maxOf { it.right }
        val bottom = boxes.maxOf { it.bottom }
        val avgConf = boxes.map { it.conf.toDouble() }.average().toFloat()
        
        Log.d(TAG, "Merged ${boxes.size} boxes into one: (${left},${top})-(${right},${bottom})")
        return DetBox(left, top, right, bottom, avgConf, boxes[0].cls)
    }
}