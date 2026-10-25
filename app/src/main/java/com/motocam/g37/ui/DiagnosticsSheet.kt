package com.motocam.g37.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motocam.g37.camera.DeviceCameraAudit

@Composable
fun DiagnosticsSheet(
    audit: DeviceCameraAudit?,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        color = Color(0xFF141820),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "Moto G37 5G (XT2625-5) Hardware & Camera2 Audit",
                color = Color(0xFF4ADE80),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (audit == null) {
                Text("Probing Camera2 HAL & Extensions...", color = Color.White)
            } else {
                AuditRow("Model / Device", audit.deviceModel)
                AuditRow("SoC / Platform", audit.socModel)
                AuditRow("OS / API Level", "${audit.androidRelease} (API ${audit.sdkInt})")
                AuditRow("RAM (Available / Total)", "${audit.availableRamMb} MB / ${audit.totalRamMb} MB")
                AuditRow("Camera2 Hardware Level", audit.hardwareLevel)
                AuditRow("Max JPEG Output", audit.maxJpegResolution)
                AuditRow("Max YUV_420_888 Stream", audit.maxYuvResolution)
                AuditRow("Manual Sensor (ISO/Shutter)", if (audit.supportsManualSensor) "SUPPORTED" else "LIMITED (EV Only)")
                AuditRow("RAW_SENSOR (DNG)", if (audit.supportsRawDng) "SUPPORTED" else "NOT EXPOSED BY HAL")
                AuditRow("Hardware OIS", if (audit.hasOpticalStabilization) "PRESENT" else "NONE (Gyro-Gate Active)")
                AuditRow("ISO Sensitivity Range", audit.isoRange?.let { "${it.first} .. ${it.last}" } ?: "Auto Only")
                AuditRow("Min Focus Distance", "${audit.minFocusDistanceDiopters} diopters")
                AuditRow(
                    "Motorola OEM Extensions",
                    if (audit.supportedOemExtensions.isEmpty()) "None detected (Using Custom C++ Stack)"
                    else audit.supportedOemExtensions.joinToString(", ")
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
            ) {
                Text("Close Hardware Inspector", color = Color.White)
            }
        }
    }
}

@Composable
private fun AuditRow(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(Color(0xFF1E2430), RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        Text(text = label, color = Color(0xFF94A3B8), fontSize = 12.sp)
        Text(text = value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}
