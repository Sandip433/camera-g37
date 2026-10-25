package com.motocam.g37.ui

enum class CaptureMode(val displayName: String, val badgeText: String) {
    STANDARD("Zero-Shake Pro", "Gyro-Gated + C++ Denoise"),
    NIGHT_STACK_4X("Night Stack 4x", "4-Frame Align + De-Ghost"),
    HDR_BRACKET_3X("HDR Bracket 3x", "-1.5 / 0 / +1.5 EV Mertens Fusion"),
    PORTRAIT_BOKEH("AI Portrait", "ML Kit Subject Bokeh"),
    GEMINI_AI_MASTER("Gemini 2.5 AI", "Cloud AI Recipe + Full-Res C++")
}

enum class SceneRecommendation(val label: String) {
    STANDARD_ZERO_SHAKE("Scene: Balanced (Zero-Shake Standard)"),
    LOW_LIGHT_NIGHT_STACK("Scene: Low Light → Use Night Stack 4x"),
    BACKLIT_HDR_BRACKET("Scene: High Contrast → Use HDR Bracket 3x")
}
