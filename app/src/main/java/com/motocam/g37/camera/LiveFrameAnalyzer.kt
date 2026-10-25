package com.motocam.g37.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.motocam.g37.processing.NativeComputationalProcessor
import com.motocam.g37.ui.SceneRecommendation

data class LiveTelemetry(
    val histogram: FloatArray,
    val meanLuma: Float,
    val highlightClipRatio: Float,
    val focusSharpnessScore: Float,
    val peakingOverlayMask: IntArray?,
    val maskWidth: Int,
    val maskHeight: Int,
    val recommendedScene: SceneRecommendation
)

class LiveFrameAnalyzer(
    private val enableFocusPeakingProvider: () -> Boolean,
    private val onTelemetryUpdated: (LiveTelemetry) -> Unit
) : ImageAnalysis.Analyzer {

    private var frameCounter = 0
    private var reusableMask: IntArray? = null

    override fun analyze(image: ImageProxy) {
        try {
            // Throttle heavy histogram + Sobel analysis to every 2nd frame (~15-30fps) to save battery
            frameCounter++
            if (frameCounter % 2 != 0) {
                image.close()
                return
            }

            val yPlane = image.planes[0]
            val width = image.width
            val height = image.height
            val rowStride = yPlane.rowStride
            val computeMask = enableFocusPeakingProvider()

            val maskW = width / 2
            val maskH = height / 2
            if (computeMask && (reusableMask == null || reusableMask?.size != maskW * maskH)) {
                reusableMask = IntArray(maskW * maskH)
            }

            val stats = NativeComputationalProcessor.analyzeLumaFrame(
                yBuffer = yPlane.buffer,
                width = width,
                height = height,
                rowStride = rowStride,
                computeFocusPeaking = computeMask,
                outPeakingMask = if (computeMask) reusableMask else null
            )

            if (stats != null && stats.size >= 259) {
                val hist = stats.copyOfRange(0, 256)
                val meanLuma = stats[256]
                val clipRatio = stats[257]
                val sharpness = stats[258]

                val scene = when {
                    meanLuma < 52f -> SceneRecommendation.LOW_LIGHT_NIGHT_STACK
                    clipRatio > 0.08f && meanLuma < 150f -> SceneRecommendation.BACKLIT_HDR_BRACKET
                    else -> SceneRecommendation.STANDARD_ZERO_SHAKE
                }

                onTelemetryUpdated(
                    LiveTelemetry(
                        histogram = hist,
                        meanLuma = meanLuma,
                        highlightClipRatio = clipRatio,
                        focusSharpnessScore = sharpness,
                        peakingOverlayMask = if (computeMask) reusableMask?.clone() else null,
                        maskWidth = maskW,
                        maskHeight = maskH,
                        recommendedScene = scene
                    )
                )
            }
        } finally {
            image.close()
        }
    }
}
