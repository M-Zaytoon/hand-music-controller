package com.example.handmusiccontroler

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult

class OverlayView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {
    private var results: HandLandmarkerResult? = null
    private val linePaint = Paint().apply { color = Color.WHITE; strokeWidth = 4f; style = Paint.Style.STROKE }
    private val pointPaint = Paint().apply { color = Color.GREEN; strokeWidth = 8f; style = Paint.Style.FILL }

    fun setResults(result: HandLandmarkerResult) {
        results = result
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        results?.let { res ->
            for (landmarks in res.landmarks()) {
                // Draw the hand skeleton (connections)
                HandLandmarker.HAND_CONNECTIONS.forEach {
                    val start = landmarks[it.start()]
                    val end = landmarks[it.end()]
                    canvas.drawLine(start.x()*width, start.y()*height, end.x()*width, end.y()*height, linePaint)
                }
                // Draw the landmark points
                for (lm in landmarks) {
                    canvas.drawCircle(lm.x()*width, lm.y()*height, 6f, pointPaint)
                }
            }
        }
    }
}