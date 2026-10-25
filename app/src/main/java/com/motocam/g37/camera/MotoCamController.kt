package com.motocam.g37.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.LifecycleOwner
import com.motocam.g37.processing.GeminiMasteringRecipe
import com.motocam.g37.processing.GeminiPhotoMaster
import com.motocam.g37.processing.NativeComputationalProcessor
import com.motocam.g37.processing.PortraitSegmenter
import com.motocam.g37.ui.CaptureMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@OptIn(ExperimentalCamera2Interop::class)
class MotoCamController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val gyroMonitor: GyroStabilityMonitor,
    private val onTelemetry: (LiveTelemetry) -> Unit,
    private val onStatusUpdate: (String, Boolean) -> Unit,
    private val onGeminiInsight: (String, GeminiMasteringRecipe?) -> Unit
) {
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val captureExecutor = Executors.newSingleThreadExecutor()

    var focusPeakingEnabled: Boolean = false
    var currentZoomRatio: Float = 1.0f

    suspend fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        useOemNightExtension: Boolean = false
    ) {
        val cameraProvider = ProcessCameraProvider.getInstance(context).await()
        cameraProvider.unbindAll()

        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }

        // Keep live analysis strictly at 640x480 to guarantee 60fps preview & low battery drain
        val analysisResolution = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(640, 480),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                )
            )
            .build()

        val imageAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(analysisResolution)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also {
                it.setAnalyzer(
                    analysisExecutor,
                    LiveFrameAnalyzer(
                        enableFocusPeakingProvider = { focusPeakingEnabled },
                        onTelemetryUpdated = onTelemetry
                    )
                )
            }

        val captureBuilder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)

        imageCapture = captureBuilder.build()

        var selector = CameraSelector.DEFAULT_BACK_CAMERA
        if (useOemNightExtension) {
            try {
                val extManager = ExtensionsManager.getInstanceAsync(context, cameraProvider).await()
                if (extManager.isExtensionAvailable(selector, ExtensionMode.NIGHT)) {
                    selector = extManager.getExtensionEnabledCameraSelector(selector, ExtensionMode.NIGHT)
                }
            } catch (_: Exception) {
            }
        }

        camera = cameraProvider.bindToLifecycle(
            lifecycleOwner,
            selector,
            preview,
            imageAnalysis,
            imageCapture
        )
    }

    fun applyManualParameters(
        manualEnabled: Boolean,
        iso: Int,
        shutterMs: Float,
        evIndex: Int,
        focusDiopters: Float
    ) {
        val cam = camera ?: return
        val camera2Control = Camera2CameraControl.from(cam.cameraControl)

        cam.cameraControl.setExposureCompensationIndex(evIndex)

        val optionsBuilder = androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
        if (manualEnabled) {
            optionsBuilder
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
                .setCaptureRequestOption(
                    CaptureRequest.SENSOR_EXPOSURE_TIME,
                    (shutterMs * 1_000_000L).toLong()
                )
        } else {
            optionsBuilder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_ON
            )
        }

        if (focusDiopters > 0f) {
            optionsBuilder
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focusDiopters)
        } else {
            optionsBuilder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
        }

        camera2Control.setCaptureRequestOptions(optionsBuilder.build())
    }

    fun setZoom(ratio: Float) {
        currentZoomRatio = ratio
        camera?.cameraControl?.setZoomRatio(ratio)
    }

    fun triggerTapToFocus(previewView: PreviewView, x: Float, y: Float) {
        val factory = previewView.meteringPointFactory
        val point = factory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .build()
        camera?.cameraControl?.startFocusAndMetering(action)
    }

    /**
     * Executes the complete computational capture pipeline based on the active [CaptureMode]:
     * 1. Gyro-Gated Zero-Shake Shutter Wait (compensates for lack of OIS on XT2625-5)
     * 2. Multi-Frame Burst / Exposure Bracket Acquisition (Memory-Safe Streaming Accumulator)
     * 3. Native C++17 Alignment, De-Ghosting, Chroma/Luma Bilateral Denoise & Unsharp Mask
     * 4. Optional ML Kit Portrait Bokeh or Google Gemini 2.5 Flash AI Cloud Mastering
     */
    fun executeComputationalCapture(
        mode: CaptureMode,
        geminiApiKey: String
    ) {
        val capture = imageCapture ?: return
        scope.launch {
            try {
                onStatusUpdate("Waiting for zero-shake gyro window...", true)
                val speed = gyroMonitor.awaitStableWindow(maxWaitMs = 350L)

                val masterBitmap: Bitmap = when (mode) {
                    CaptureMode.STANDARD, CaptureMode.PORTRAIT_BOKEH, CaptureMode.GEMINI_AI_MASTER -> {
                        onStatusUpdate("Capturing low-noise frame (${String.format("%.2f", speed)} rad/s)...", true)
                        captureSingleBitmap(capture)
                    }
                    CaptureMode.NIGHT_STACK_4X -> {
                        captureAndStackFrames(
                            capture = capture,
                            evOffsets = listOf(0, 0, 0, 0),
                            isHdrMode = false,
                            label = "Night Stack 4x"
                        )
                    }
                    CaptureMode.HDR_BRACKET_3X -> {
                        captureAndStackFrames(
                            capture = capture,
                            evOffsets = listOf(-4, 0, 4), // -1.5 EV, 0 EV, +1.5 EV
                            isHdrMode = true,
                            label = "HDR Bracket 3x"
                        )
                    }
                }

                // Post-capture processing on background thread
                withContext(Dispatchers.Default) {
                    when (mode) {
                        CaptureMode.STANDARD -> {
                            onStatusUpdate("Applying C++ Luma/Chroma Denoise & Unsharp Mask...", true)
                            NativeComputationalProcessor.masterPhoto(
                                bitmap = masterBitmap,
                                exposureEv = 0.05f,
                                shadowLift = 0.18f,
                                highlightRecovery = 0.25f,
                                contrast = 1.06f,
                                warmth = 0.0f,
                                saturation = 1.08f,
                                denoiseStrength = 0.35f,
                                sharpenStrength = 0.45f
                            )
                        }
                        CaptureMode.NIGHT_STACK_4X -> {
                            onStatusUpdate("Finishing 4-Frame Night Sight Tone & Chroma Polish...", true)
                            NativeComputationalProcessor.masterPhoto(
                                bitmap = masterBitmap,
                                exposureEv = 0.22f,
                                shadowLift = 0.45f,
                                highlightRecovery = 0.35f,
                                contrast = 1.10f,
                                warmth = 0.04f,
                                saturation = 1.12f,
                                denoiseStrength = 0.65f,
                                sharpenStrength = 0.50f
                            )
                        }
                        CaptureMode.HDR_BRACKET_3X -> {
                            onStatusUpdate("Finishing Mertens Exposure Fusion Micro-Contrast...", true)
                            NativeComputationalProcessor.masterPhoto(
                                bitmap = masterBitmap,
                                exposureEv = 0.0f,
                                shadowLift = 0.30f,
                                highlightRecovery = 0.50f,
                                contrast = 1.12f,
                                warmth = 0.02f,
                                saturation = 1.10f,
                                denoiseStrength = 0.30f,
                                sharpenStrength = 0.55f
                            )
                        }
                        CaptureMode.PORTRAIT_BOKEH -> {
                            onStatusUpdate("Running On-Device AI Subject Segmentation & Bokeh...", true)
                            PortraitSegmenter.applySingleCameraPortraitBokeh(masterBitmap)
                            NativeComputationalProcessor.masterPhoto(
                                bitmap = masterBitmap,
                                exposureEv = 0.05f,
                                shadowLift = 0.20f,
                                highlightRecovery = 0.20f,
                                contrast = 1.05f,
                                warmth = 0.05f,
                                saturation = 1.06f,
                                denoiseStrength = 0.30f,
                                sharpenStrength = 0.40f
                            )
                        }
                        CaptureMode.GEMINI_AI_MASTER -> {
                            if (geminiApiKey.isBlank()) {
                                onGeminiInsight("Enter your Gemini API Key in the top bar to enable cloud AI mastering. Applied default local C++ mastering.", null)
                                NativeComputationalProcessor.masterPhoto(masterBitmap)
                            } else {
                                onStatusUpdate("Consulting Google Gemini 2.5 Flash for AI Mastering Recipe...", true)
                                val recipe = GeminiPhotoMaster.analyzeAndGenerateRecipe(
                                    apiKey = geminiApiKey,
                                    sourceBitmap = masterBitmap
                                )
                                onGeminiInsight(recipe.sceneCritiqueAndAdvice, recipe)
                                onStatusUpdate("Applying Gemini AI Recipe at full resolution in C++...", true)
                                NativeComputationalProcessor.masterPhoto(
                                    bitmap = masterBitmap,
                                    exposureEv = recipe.exposureEv,
                                    shadowLift = recipe.shadowLift,
                                    highlightRecovery = recipe.highlightRecovery,
                                    contrast = recipe.contrast,
                                    warmth = recipe.warmth,
                                    saturation = recipe.saturation,
                                    denoiseStrength = recipe.denoiseStrength,
                                    sharpenStrength = recipe.sharpenStrength
                                )
                            }
                        }
                    }

                    onStatusUpdate("Saving 100%-Quality JPEG to Gallery...", true)
                    val savedName = saveBitmapToMediaStore(masterBitmap, mode.name)
                    masterBitmap.recycle()
                    onStatusUpdate("Saved $savedName", false)
                }
            } catch (e: Exception) {
                onStatusUpdate("Capture error: ${e.localizedMessage}", false)
            }
        }
    }

    /**
     * Memory-Safe Streaming Burst Acquisition:
     * Decodes only 1 frame at a time on the 4GB RAM Moto G37, feeds it into the C++ accumulator,
     * and immediately recycles the Bitmap before capturing the next frame.
     */
    private suspend fun captureAndStackFrames(
        capture: ImageCapture,
        evOffsets: List<Int>,
        isHdrMode: Boolean,
        label: String
    ): Bitmap {
        var stackerHandle = 0L
        var width = 0
        var height = 0

        for ((index, ev) in evOffsets.withIndex()) {
            if (isHdrMode) {
                camera?.cameraControl?.setExposureCompensationIndex(ev)?.await()
                delay(90L) // Allow AE sensor gain to settle for bracket step
            }
            onStatusUpdate("$label: Capturing frame ${index + 1}/${evOffsets.size}...", true)
            val frameBmp = captureSingleBitmap(capture)

            withContext(Dispatchers.Default) {
                if (index == 0) {
                    width = frameBmp.width
                    height = frameBmp.height
                    stackerHandle = NativeComputationalProcessor.initBurstStacker(
                        width = width,
                        height = height,
                        isHdrExposureFusion = isHdrMode
                    )
                }
                onStatusUpdate("$label: Aligning & fusing frame ${index + 1}/${evOffsets.size} in C++...", true)
                NativeComputationalProcessor.addFrameToStacker(stackerHandle, frameBmp)
                frameBmp.recycle() // Crucial for 4GB RAM budget!
            }
        }

        if (isHdrMode) {
            camera?.cameraControl?.setExposureCompensationIndex(0)
        }

        return withContext(Dispatchers.Default) {
            val outBmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            NativeComputationalProcessor.finishBurstStacker(stackerHandle, outBmp)
            outBmp
        }
    }

    private suspend fun captureSingleBitmap(capture: ImageCapture): Bitmap =
        suspendCancellableCoroutine { cont ->
            capture.takePicture(
                captureExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            val bmp = imageProxyToRightSideUpBitmap(image)
                            image.close()
                            cont.resume(bmp)
                        } catch (e: Exception) {
                            image.close()
                            cont.resumeWithException(e)
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        cont.resumeWithException(exception)
                    }
                }
            )
        }

    private fun imageProxyToRightSideUpBitmap(image: ImageProxy): Bitmap {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        val rawBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        val rotation = image.imageInfo.rotationDegrees
        if (rotation == 0) return rawBitmap

        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
        if (rotated != rawBitmap) rawBitmap.recycle()
        return rotated
    }

    private fun saveBitmapToMediaStore(bitmap: Bitmap, modeTag: String): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val filename = "MOTOCAM_G37_${modeTag}_$timestamp.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MotoCamG37")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return "Failed"

        resolver.openOutputStream(uri)?.use { outStream: OutputStream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outStream)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return filename
    }
}
