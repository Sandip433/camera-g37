package com.motocam.g37.processing

import android.graphics.Bitmap
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class GeminiMasteringRecipe(
    val exposureEv: Float = 0.08f,
    val shadowLift: Float = 0.25f,
    val highlightRecovery: Float = 0.30f,
    val contrast: Float = 1.08f,
    val warmth: Float = 0.0f,
    val saturation: Float = 1.08f,
    val denoiseStrength: Float = 0.40f,
    val sharpenStrength: Float = 0.45f,
    val sceneCritiqueAndAdvice: String = "Balanced computational mastering applied."
)

object GeminiPhotoMaster {

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    suspend fun analyzeAndGenerateRecipe(
        apiKey: String,
        sourceBitmap: Bitmap
    ): GeminiMasteringRecipe = withContext(Dispatchers.IO) {
        val maxDim = 1024
        val scale = maxDim.toFloat() / maxOf(sourceBitmap.width, sourceBitmap.height).toFloat()
        val thumbW = (sourceBitmap.width * scale).toInt().coerceAtLeast(256)
        val thumbH = (sourceBitmap.height * scale).toInt().coerceAtLeast(256)
        val proxyBitmap = Bitmap.createScaledBitmap(sourceBitmap, thumbW, thumbH, true)

        try {
            val model = GenerativeModel(
                modelName = "gemini-2.5-flash",
                apiKey = apiKey,
                generationConfig = generationConfig {
                    temperature = 0.2f
                    responseMimeType = "application/json"
                }
            )

            val prompt = """
                You are a senior computational photography colorist and ISP engineer tuning a photo captured on a Motorola Moto G37 5G (50MP f/1.8 0.61um Quad-Bayer sensor, binned to 12.5MP).
                Analyze this image's dynamic range, shadow noise, highlight clipping, white balance, and subject composition.
                Return ONLY a valid JSON object matching this exact schema:
                {
                  "exposureEv": float between -1.0 and 1.0,
                  "shadowLift": float between 0.0 and 0.8,
                  "highlightRecovery": float between 0.0 and 0.8,
                  "contrast": float between 0.85 and 1.25,
                  "warmth": float between -0.5 (cooler) and 0.5 (warmer),
                  "saturation": float between 0.8 and 1.3,
                  "denoiseStrength": float between 0.1 and 0.9,
                  "sharpenStrength": float between 0.1 and 0.8,
                  "sceneCritiqueAndAdvice": "1-2 concise sentences explaining what you adjusted and 1 practical composition/lighting tip for the photographer."
                }
            """.trimIndent()

            val response = model.generateContent(
                content {
                    image(proxyBitmap)
                    text(prompt)
                }
            )

            val rawJson = response.text?.trim() ?: return@withContext GeminiMasteringRecipe()
            val recipe = jsonParser.decodeFromString<GeminiMasteringRecipe>(rawJson)
            recipe.copy(
                exposureEv = recipe.exposureEv.coerceIn(-1.0f, 1.0f),
                shadowLift = recipe.shadowLift.coerceIn(0.0f, 0.85f),
                highlightRecovery = recipe.highlightRecovery.coerceIn(0.0f, 0.85f),
                contrast = recipe.contrast.coerceIn(0.85f, 1.25f),
                warmth = recipe.warmth.coerceIn(-0.5f, 0.5f),
                saturation = recipe.saturation.coerceIn(0.8f, 1.3f),
                denoiseStrength = recipe.denoiseStrength.coerceIn(0.05f, 0.9f),
                sharpenStrength = recipe.sharpenStrength.coerceIn(0.05f, 0.85f)
            )
        } catch (e: Exception) {
            GeminiMasteringRecipe(
                sceneCritiqueAndAdvice = "Fallback local mastering applied (${e.localizedMessage ?: "Network/API error"})."
            )
        } finally {
            if (proxyBitmap != sourceBitmap) {
                proxyBitmap.recycle()
            }
        }
    }
}
