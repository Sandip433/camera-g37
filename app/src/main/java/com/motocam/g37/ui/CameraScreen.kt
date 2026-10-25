package com.motocam.g37.ui

import android.graphics.Bitmap
import android.view.MotionEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.motocam.g37.BuildConfig
import com.motocam.g37.camera.*
import com.motocam.g37.processing.GeminiMasteringRecipe
import kotlinx.coroutines.launch

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val gyroMonitor = remember { GyroStabilityMonitor(context) }
    val gyroState by gyroMonitor.gyroState.collectAsState()

    var audit by remember { mutableStateOf<DeviceCameraAudit?>(null) }
    var showAuditDialog by remember { mutableStateOf(false) }
    var showGeminiKeyDialog by remember { mutableStateOf(false) }
    var geminiApiKey by remember { mutableStateOf(BuildConfig.DEFAULT_GEMINI_API_KEY) }

    var telemetry by remember { mutableStateOf<LiveTelemetry?>(null) }
    var statusText by remember { mutableStateOf("Ready • Moto G37 5G Computational Pipeline") }
    var isProcessing by remember { mutableStateOf(false) }
    var geminiAdvice by remember { mutableStateOf<String?>(null) }
    var lastRecipe by remember { mutableStateOf<GeminiMasteringRecipe?>(null) }

    var selectedMode by remember { mutableStateOf(CaptureMode.STANDARD) }
    var showGrid by remember { mutableStateOf(true) }
    var showFocusPeaking by remember { mutableStateOf(false) }
    var showManualDeck by remember { mutableStateOf(false) }

    var manualModeEnabled by remember { mutableStateOf(false) }
    var isoValue by remember { mutableStateOf(200f) }
    var shutterMs by remember { mutableStateOf(16f) } // ~1/60s
    var evIndex by remember { mutableStateOf(0f) }
    var focusDiopters by remember { mutableStateOf(0f) }
    var zoomRatio by remember { mutableStateOf(1.0f) }

    val previewView = remember { PreviewView(context) }

    val controller = remember {
        MotoCamController(
            context = context,
            scope = scope,
            gyroMonitor = gyroMonitor,
            onTelemetry = { telemetry = it },
            onStatusUpdate = { msg, busy ->
                statusText = msg
                isProcessing = busy
            },
            onGeminiInsight = { critique, recipe ->
                geminiAdvice = critique
                lastRecipe = recipe
            }
        )
    }

    LaunchedEffect(Unit) {
        gyroMonitor.start()
        audit = CameraCapabilityInspector.inspectMotoG37(context)
        controller.bindCamera(lifecycleOwner, previewView)
    }

    DisposableEffect(Unit) {
        onDispose { gyroMonitor.stop() }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // 1. Live CameraX Viewfinder with Tap-to-Focus
        AndroidView(
            factory = { previewView },
            modifier = Modifier
                .fillMaxSize()
                .pointerInteropFilter { event ->
                    if (event.action == MotionEvent.ACTION_UP) {
                        controller.triggerTapToFocus(previewView, event.x, event.y)
                    }
                    true
                }
        )

        // 2. Real-Time Computational HUD Overlay (Rule of Thirds, Gyro Horizon Level, Focus Peaking)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // Focus Peaking & Highlight Zebra Bitmap Overlay
            val currentTel = telemetry
            if (showFocusPeaking && currentTel?.peakingOverlayMask != null) {
                val maskBmp = Bitmap.createBitmap(
                    currentTel.peakingOverlayMask,
                    currentTel.maskWidth,
                    currentTel.maskHeight,
                    Bitmap.Config.ARGB_8888
                )
                drawImage(
                    image = maskBmp.asImageBitmap(),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(w.toInt(), h.toInt())
                )
            }

            // Rule-of-Thirds Composition Grid
            if (showGrid) {
                val gridColor = Color.White.copy(alpha = 0.28f)
                drawLine(gridColor, Offset(w / 3f, 0f), Offset(w / 3f, h), strokeWidth = 1.5f)
                drawLine(gridColor, Offset(2f * w / 3f, 0f), Offset(2f * w / 3f, h), strokeWidth = 1.5f)
                drawLine(gridColor, Offset(0f, h / 3f), Offset(w, h / 3f), strokeWidth = 1.5f)
                drawLine(gridColor, Offset(0f, 2f * h / 3f), Offset(w, 2f * h / 3f), strokeWidth = 1.5f)
            }

            // S26 Ultra Inspired Gyro Horizon Level + Non-OIS Stability Ring
            val center = Offset(w / 2f, h / 2f)
            val levelColor = if (gyroState.isHorizonLevel) Color(0xFF4ADE80) else Color(0xFFFACC15)
            rotate(degrees = -gyroState.rollDegrees, pivot = center) {
                drawLine(
                    color = levelColor,
                    start = Offset(center.x - 110f, center.y),
                    end = Offset(center.x + 110f, center.y),
                    strokeWidth = if (gyroState.isHorizonLevel) 4f else 2.5f
                )
            }
            val stabilityColor = if (gyroState.isHandSteady) Color(0xFF4ADE80) else Color(0xFFEF4444)
            drawCircle(
                color = stabilityColor.copy(alpha = 0.75f),
                radius = 36f + (gyroState.angularSpeedRadPerSec * 180f).coerceAtMost(60f),
                center = center,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
            )
        }

        // 3. Top Telemetry Bar: Live Luma Histogram, Scene Coach, Hardware Inspector & Gemini Key
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Live Luma Histogram Canvas
                Canvas(
                    modifier = Modifier
                        .width(120.dp)
                        .height(42.dp)
                        .background(Color(0xFF111827), RoundedCornerShape(6.dp))
                        .padding(4.dp)
                ) {
                    val hist = telemetry?.histogram
                    if (hist != null) {
                        val barW = size.width / 64f
                        for (i in 0 until 64) {
                            val binVal = hist[i * 4].coerceIn(0f, 1f)
                            val barH = binVal * size.height
                            drawLine(
                                color = if (i > 58) Color(0xFFEF4444) else Color(0xFF38BDF8),
                                start = Offset(i * barW, size.height),
                                end = Offset(i * barW, size.height - barH),
                                strokeWidth = barW
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmallChip(
                        text = if (showFocusPeaking) "Peaking ON" else "Peaking",
                        active = showFocusPeaking
                    ) {
                        showFocusPeaking = !showFocusPeaking
                        controller.focusPeakingEnabled = showFocusPeaking
                    }
                    SmallChip(text = "Manual", active = showManualDeck) {
                        showManualDeck = !showManualDeck
                    }
                    SmallChip(text = "HAL Audit", active = false) {
                        showAuditDialog = true
                    }
                    SmallChip(text = "Gemini Key", active = geminiApiKey.isNotBlank()) {
                        showGeminiKeyDialog = true
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Real-Time Scene Recommendation & Stability Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = telemetry?.recommendedScene?.label ?: "Analyzing scene...",
                    color = Color(0xFF93C5FD),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (gyroState.isHandSteady) "GYRO STEADY (${String.format("%.2f", gyroState.angularSpeedRadPerSec)} rad/s)"
                    else "HOLD STEADY (${String.format("%.2f", gyroState.angularSpeedRadPerSec)} rad/s)",
                    color = if (gyroState.isHandSteady) Color(0xFF4ADE80) else Color(0xFFF87171),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Optional Gemini AI Critique Banner
            geminiAdvice?.let { advice ->
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    color = Color(0xFF1E1B4B).copy(alpha = 0.9f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().clickable { geminiAdvice = null }
                ) {
                    Text(
                        text = "✨ Gemini 2.5 AI Master: $advice (Tap to dismiss)",
                        color = Color(0xFFE0E7FF),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }

        // 4. Bottom Controls: Zoom Toggle (1x / 2x Lossless Center Crop), Manual Sliders, Mode Bar & Shutter
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.72f))
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Zoom Toggle (1.0x vs 2.0x Sensor Center Crop)
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                listOf(1.0f to "1x (24mm)", 2.0f to "2x (48mm Crop)").forEach { (ratio, label) ->
                    SmallChip(text = label, active = zoomRatio == ratio) {
                        zoomRatio = ratio
                        controller.setZoom(ratio)
                    }
                }
            }

            // Expandable Pro Manual Deck (ISO, Shutter, EV, Focus Distance)
            if (showManualDeck) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1E293B), RoundedCornerShape(12.dp))
                        .padding(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Manual Exposure Override (Camera2)", color = Color.White, fontSize = 12.sp)
                        Switch(
                            checked = manualModeEnabled,
                            onCheckedChange = {
                                manualModeEnabled = it
                                controller.applyManualParameters(
                                    manualModeEnabled, isoValue.toInt(), shutterMs, evIndex.toInt(), focusDiopters
                                )
                            }
                        )
                    }
                    Text("EV Compensation: ${evIndex.toInt()}", color = Color.LightGray, fontSize = 11.sp)
                    Slider(
                        value = evIndex,
                        onValueChange = {
                            evIndex = it
                            controller.applyManualParameters(
                                manualModeEnabled, isoValue.toInt(), shutterMs, evIndex.toInt(), focusDiopters
                            )
                        },
                        valueRange = -6f..6f
                    )
                    if (manualModeEnabled) {
                        Text("ISO: ${isoValue.toInt()} | Shutter: ${shutterMs.toInt()} ms", color = Color.LightGray, fontSize = 11.sp)
                        Slider(
                            value = isoValue,
                            onValueChange = {
                                isoValue = it
                                controller.applyManualParameters(
                                    manualModeEnabled, isoValue.toInt(), shutterMs, evIndex.toInt(), focusDiopters
                                )
                            },
                            valueRange = 100f..3200f
                        )
                        Slider(
                            value = shutterMs,
                            onValueChange = {
                                shutterMs = it
                                controller.applyManualParameters(
                                    manualModeEnabled, isoValue.toInt(), shutterMs, evIndex.toInt(), focusDiopters
                                )
                            },
                            valueRange = 1f..250f
                        )
                    }
                    Text(
                        text = if (focusDiopters == 0f) "Focus: Continuous Auto-Focus"
                        else "Manual Focus: ${String.format("%.1f", focusDiopters)} diopters",
                        color = Color.LightGray,
                        fontSize = 11.sp
                    )
                    Slider(
                        value = focusDiopters,
                        onValueChange = {
                            focusDiopters = it
                            controller.applyManualParameters(
                                manualModeEnabled, isoValue.toInt(), shutterMs, evIndex.toInt(), focusDiopters
                            )
                        },
                        valueRange = 0f..(audit?.minFocusDistanceDiopters?.coerceAtLeast(10f) ?: 10f)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Capture Mode Selector
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 10.dp)
            ) {
                items(CaptureMode.entries) { mode ->
                    val selected = mode == selectedMode
                    Surface(
                        color = if (selected) Color(0xFF2563EB) else Color(0xFF1F2937),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.clickable { selectedMode = mode }
                    ) {
                        Text(
                            text = mode.displayName,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            // Status Line
            Text(
                text = "${selectedMode.badgeText} • $statusText",
                color = Color(0xFFCBD5E1),
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 10.dp)
            )

            // Shutter Button
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(76.dp)
                    .border(4.dp, if (gyroState.isHandSteady) Color(0xFF4ADE80) else Color.White, CircleShape)
                    .padding(6.dp)
                    .background(if (isProcessing) Color.Gray else Color.White, CircleShape)
                    .clickable(enabled = !isProcessing) {
                        controller.executeComputationalCapture(selectedMode, geminiApiKey)
                    }
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(
                        color = Color(0xFF2563EB),
                        modifier = Modifier.size(32.dp),
                        strokeWidth = 3.dp
                    )
                }
            }
        }
    }

    if (showAuditDialog) {
        Dialog(onDismissRequest = { showAuditDialog = false }) {
            DiagnosticsSheet(audit = audit, onDismiss = { showAuditDialog = false })
        }
    }

    if (showGeminiKeyDialog) {
        AlertDialog(
            onDismissRequest = { showGeminiKeyDialog = false },
            title = { Text("Google Gemini 2.5 Flash API Key") },
            text = {
                Column {
                    Text(
                        "Enter your Google AI Studio API Key to enable Gemini 2.5 Flash Scene Coaching & Cloud JSON Auto-Mastering Recipes:",
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = geminiApiKey,
                        onValueChange = { geminiApiKey = it.trim() },
                        label = { Text("AIzaSy...") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showGeminiKeyDialog = false }) {
                    Text("Save Key")
                }
            }
        )
    }
}

@Composable
private fun SmallChip(text: String, active: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (active) Color(0xFF059669) else Color(0xFF334155),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}
