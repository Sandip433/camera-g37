package com.motocam.g37.processing

import android.graphics.Bitmap
import java.nio.ByteBuffer

object NativeComputationalProcessor {
    init {
        System.loadLibrary("motocam_native")
    }

    external fun analyzeLumaFrameNative(
        yBuffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        computeFocusPeaking: Boolean,
        outPeakingMask: IntArray?
    ): FloatArray?

    external fun initBurstStackerNative(
        width: Int,
        height: Int,
        isHdrExposureFusion: Boolean
    ): Long

    external fun addFrameToStackerNative(
        contextHandle: Long,
        bitmap: Bitmap
    )

    external fun finishBurstStackerNative(
        contextHandle: Long,
        outBitmap: Bitmap
    )

    external fun masterPhotoNative(
        bitmap: Bitmap,
        exposureEv: Float,
        shadowLift: Float,
        highlightRecovery: Float,
        contrast: Float,
        warmth: Float,
        saturation: Float,
        denoiseStrength: Float,
        sharpenStrength: Float
    )

    fun analyzeLumaFrame(
        yBuffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        computeFocusPeaking: Boolean,
        outPeakingMask: IntArray?
    ): FloatArray? = analyzeLumaFrameNative(
        yBuffer, width, height, rowStride, computeFocusPeaking, outPeakingMask
    )

    fun initBurstStacker(width: Int, height: Int, isHdrExposureFusion: Boolean): Long =
        initBurstStackerNative(width, height, isHdrExposureFusion)

    fun addFrameToStacker(handle: Long, bitmap: Bitmap) =
        addFrameToStackerNative(handle, bitmap)

    fun finishBurstStacker(handle: Long, outBitmap: Bitmap) =
        finishBurstStackerNative(handle, outBitmap)

    fun masterPhoto(
        bitmap: Bitmap,
        exposureEv: Float = 0.08f,
        shadowLift: Float = 0.22f,
        highlightRecovery: Float = 0.25f,
        contrast: Float = 1.06f,
        warmth: Float = 0.0f,
        saturation: Float = 1.08f,
        denoiseStrength: Float = 0.35f,
        sharpenStrength: Float = 0.45f
    ) = masterPhotoNative(
        bitmap,
        exposureEv,
        shadowLift,
        highlightRecovery,
        contrast,
        warmth,
        saturation,
        denoiseStrength,
        sharpenStrength
    )
}
