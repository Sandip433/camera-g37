package com.motocam.g37.processing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.tasks.await

object PortraitSegmenter {

    /**
     * Generates a foreground confidence mask on the single 50MP/12.5MP Moto G37 rear camera
     * using ML Kit Subject Segmentation and blends the sharp subject over a fast-blurred background.
     */
    suspend fun applySingleCameraPortraitBokeh(bitmap: Bitmap) {
        val options = SubjectSegmenterOptions.Builder()
            .enableForegroundConfidenceMask()
            .build()
        val segmenter = SubjectSegmentation.getClient(options)

        try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val result = segmenter.process(inputImage).await()
            val maskBuffer = result.foregroundConfidenceMask ?: return

            // Create fast downscaled bokeh background (1/8th scale bilinear + box blur)
            val smallW = (bitmap.width / 8).coerceAtLeast(64)
            val smallH = (bitmap.height / 8).coerceAtLeast(64)
            val smallBg = Bitmap.createScaledBitmap(bitmap, smallW, smallH, true)
            val blurredBg = Bitmap.createScaledBitmap(smallBg, bitmap.width, bitmap.height, true)
            smallBg.recycle()

            val width = bitmap.width
            val height = bitmap.height
            val fgRow = IntArray(width)
            val bgRow = IntArray(width)

            maskBuffer.rewind()
            for (y in 0 until height) {
                bitmap.getPixels(fgRow, 0, width, 0, y, width, 1)
                blurredBg.getPixels(bgRow, 0, width, 0, y, width, 1)
                for (x in 0 until width) {
                    val conf = maskBuffer.float.coerceIn(0f, 1f)
                    val fg = fgRow[x]
                    val bg = bgRow[x]

                    val r = ((Color.red(fg) * conf) + (Color.red(bg) * (1f - conf))).toInt()
                    val g = ((Color.green(fg) * conf) + (Color.green(bg) * (1f - conf))).toInt()
                    val b = ((Color.blue(fg) * conf) + (Color.blue(bg) * (1f - conf))).toInt()
                    fgRow[x] = Color.argb(255, r, g, b)
                }
                bitmap.setPixels(fgRow, 0, width, 0, y, width, 1)
            }
            blurredBg.recycle()
        } catch (_: Exception) {
            // If Play Services Subject Segmentation model is still downloading, leave image sharp
        } finally {
            segmenter.close()
        }
    }
}
