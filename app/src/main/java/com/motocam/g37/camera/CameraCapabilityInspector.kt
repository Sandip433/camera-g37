package com.motocam.g37.camera

import android.app.ActivityManager
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import androidx.camera.core.CameraSelector
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext

data class DeviceCameraAudit(
    val deviceModel: String,
    val socModel: String,
    val androidRelease: String,
    val sdkInt: Int,
    val totalRamMb: Long,
    val availableRamMb: Long,
    val rearCameraId: String,
    val hardwareLevel: String,
    val supportsManualSensor: Boolean,
    val supportsRawDng: Boolean,
    val supportsYuvReprocessing: Boolean,
    val supportsBurstCapture: Boolean,
    val hasOpticalStabilization: Boolean,
    val maxJpegResolution: String,
    val maxYuvResolution: String,
    val isoRange: IntRange?,
    val exposureTimeRangeNs: LongRange?,
    val exposureCompensationRange: IntRange,
    val minFocusDistanceDiopters: Float,
    val supportedOemExtensions: List<String>
)

object CameraCapabilityInspector {

    suspend fun inspectMotoG37(context: Context): DeviceCameraAudit = withContext(Dispatchers.IO) {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val rearId = cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: "0"

        val chars = cameraManager.getCameraCharacteristics(rearId)
        val hwLevelInt = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
        val hwLevelName = when (hwLevelInt) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEVEL_3 -> "LEVEL_3 (Full + RAW + YUV Reprocessing)"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL (Per-Frame Manual Controls)"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED (Partial Manual / EV Bracketing)"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            else -> "LEGACY"
        }

        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val hasManualSensor = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
        val hasRaw = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)
        val hasYuvReproc = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING)
        val hasBurst = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE)

        val oisModes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: IntArray(0)
        val hasOis = oisModes.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)

        val streamMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val maxJpeg = streamMap?.getOutputSizes(ImageFormat.JPEG)
            ?.maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?.let { "${it.width}x${it.height} (${String.format("%.1f", it.width * it.height / 1_000_000f)} MP)" }
            ?: "Unknown"

        val maxYuv = streamMap?.getOutputSizes(ImageFormat.YUV_420_888)
            ?.maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?.let { "${it.width}x${it.height} (${String.format("%.1f", it.width * it.height / 1_000_000f)} MP)" }
            ?: "Unknown"

        val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let {
            it.lower..it.upper
        }
        val expRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let {
            it.lower..it.upper
        }
        val aeCompRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.let {
            it.lower..it.upper
        } ?: (-6..6)

        val minFocusDist = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

        // Probe OEM CameraX Extensions implemented by Motorola on XT2625-5
        val supportedExtensions = mutableListOf<String>()
        try {
            val cameraProvider = ProcessCameraProvider.getInstance(context).await()
            val extensionsManager = ExtensionsManager.getInstanceAsync(context, cameraProvider).await()
            val modes = listOf(
                ExtensionMode.NIGHT to "OEM Night Vision",
                ExtensionMode.HDR to "OEM HDR",
                ExtensionMode.BOKEH to "OEM Portrait Bokeh",
                ExtensionMode.FACE_RETOUCH to "OEM Face Retouch",
                ExtensionMode.AUTO to "OEM Auto Extension"
            )
            for ((mode, name) in modes) {
                if (extensionsManager.isExtensionAvailable(CameraSelector.DEFAULT_BACK_CAMERA, mode)) {
                    supportedExtensions.add(name)
                }
            }
        } catch (_: Exception) {
            // Extensions OEM stub not present or failed to query
        }

        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.takeIf { it.isNotBlank() } ?: Build.HARDWARE
        } else {
            Build.HARDWARE
        }

        DeviceCameraAudit(
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})",
            socModel = socModel,
            androidRelease = "Android ${Build.VERSION.RELEASE}",
            sdkInt = Build.VERSION.SDK_INT,
            totalRamMb = memInfo.totalMem / (1024 * 1024),
            availableRamMb = memInfo.availMem / (1024 * 1024),
            rearCameraId = rearId,
            hardwareLevel = hwLevelName,
            supportsManualSensor = hasManualSensor,
            supportsRawDng = hasRaw,
            supportsYuvReprocessing = hasYuvReproc,
            supportsBurstCapture = hasBurst,
            hasOpticalStabilization = hasOis,
            maxJpegResolution = maxJpeg,
            maxYuvResolution = maxYuv,
            isoRange = isoRange,
            exposureTimeRangeNs = expRange,
            exposureCompensationRange = aeCompRange,
            minFocusDistanceDiopters = minFocusDist,
            supportedOemExtensions = supportedExtensions
        )
    }
}
