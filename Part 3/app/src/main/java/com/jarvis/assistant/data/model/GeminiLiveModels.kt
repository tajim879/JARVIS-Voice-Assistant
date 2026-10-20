package com.jarvis.assistant.data.model

import com.google.gson.annotations.SerializedName

object GeminiConstants {
    // Gemini's current voice-first Live model.  It defaults to minimal thinking,
    // which is the lowest-latency setting for a conversational assistant.
    const val DEFAULT_MODEL = "models/gemini-3.1-flash-live-preview"

    // One supported Live configuration prevents a saved legacy selection from
    // silently opting out of the latency settings below.
    val SUPPORTED_MODELS = listOf(DEFAULT_MODEL)

    const val DEFAULT_VOICE = "Puck"

    val SUPPORTED_VOICES = listOf(
        "Aoede",
        "Charon",
        "Kore",
        "Fenrir",
        "Puck",
        "Leda",
        "Orus",
        "Zephyr"
    )

    const val PERSONALITY_GIRLFRIEND = "Girlfriend Mode"
    const val PERSONALITY_PROFESSIONAL = "Professional Mode"
    const val PERSONALITY_ASSISTANT = "Assistant Mode"

    val SUPPORTED_PERSONALITIES = listOf(
        PERSONALITY_ASSISTANT,
        PERSONALITY_GIRLFRIEND,
        PERSONALITY_PROFESSIONAL
    )

    const val WS_BASE_URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    const val KEEPALIVE_INTERVAL_SEC = 8L
    const val SESSION_RENEWAL_MS = 9 * 60 * 1000L // 9 minutes
}

// Request Models
data class GeminiBidiSetup(
    @SerializedName("setup") val setup: SetupPayload
)

data class SetupPayload(
    @SerializedName("model") val model: String,
    @SerializedName("generationConfig") val generationConfig: GenerationConfigPayload,
    @SerializedName("inputAudioTranscription") val inputAudioTranscription: EmptyConfig = EmptyConfig(),
    @SerializedName("outputAudioTranscription") val outputAudioTranscription: EmptyConfig = EmptyConfig(),
    @SerializedName("systemInstruction") val systemInstruction: ContentPayload? = null
)

data class GenerationConfigPayload(
    @SerializedName("responseModalities") val responseModalities: List<String> = listOf("AUDIO"),
    @SerializedName("speechConfig") val speechConfig: SpeechConfigPayload
)

data class EmptyConfig(val unused: String? = null)

data class SpeechConfigPayload(
    @SerializedName("voiceConfig") val voiceConfig: VoiceConfigPayload
)

data class VoiceConfigPayload(
    @SerializedName("prebuiltVoiceConfig") val prebuiltVoiceConfig: PrebuiltVoiceConfigPayload
)

data class PrebuiltVoiceConfigPayload(
    @SerializedName("voiceName") val voiceName: String
)

data class ContentPayload(
    @SerializedName("parts") val parts: List<PartTextPayload>
)

data class PartTextPayload(
    @SerializedName("text") val text: String
)

data class GeminiRealtimeInput(
    @SerializedName("realtimeInput") val realtimeInput: RealtimeAudioInput
)

data class RealtimeAudioInput(
    @SerializedName("audio") val audio: RealtimeAudioPayload? = null,
    // Signals a locally detected end of speech so Gemini need not wait for its
    // slower server-side silence timeout before starting a reply.
    @SerializedName("audioStreamEnd") val audioStreamEnd: Boolean? = null
)

data class RealtimeAudioPayload(
    @SerializedName("mimeType") val mimeType: String = "audio/pcm;rate=16000",
    @SerializedName("data") val data: String
)

// Response Models
data class GeminiBidiServerResponse(
    @SerializedName("serverContent") val serverContent: ServerContentPayload? = null
)

data class ServerContentPayload(
    @SerializedName("modelTurn") val modelTurn: ModelTurnPayload? = null,
    @SerializedName("turnComplete") val turnComplete: Boolean = false,
    @SerializedName("interrupted") val interrupted: Boolean = false
)

data class ModelTurnPayload(
    @SerializedName("parts") val parts: List<ModelPartPayload>? = null
)

data class ModelPartPayload(
    @SerializedName("text") val text: String? = null,
    @SerializedName("inlineData") val inlineData: InlineAudioPayload? = null
)

data class InlineAudioPayload(
    @SerializedName("mimeType") val mimeType: String? = null,
    @SerializedName("data") val data: String? = null
)
