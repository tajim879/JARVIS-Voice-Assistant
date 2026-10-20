package com.jarvis.assistant.audio

import kotlin.math.sqrt

object AudioUtils {

    /**
     * Calculates normalized RMS (0.0 to 1.0) from 16-bit Mono PCM bytes.
     */
    fun calculateRmsNormalized(bytes: ByteArray, length: Int): Float {
        if (length <= 0) return 0f
        var sumSquares = 0.0
        val sampleCount = length / 2
        if (sampleCount == 0) return 0f

        var i = 0
        while (i < length - 1) {
            val low = bytes[i].toInt() and 0xFF
            val high = bytes[i + 1].toInt()
            val sample = (high shl 8) or low
            sumSquares += (sample * sample).toDouble()
            i += 2
        }

        val rms = sqrt(sumSquares / sampleCount)
        val normalized = (rms / 3500.0).toFloat()
        return normalized.coerceIn(0f, 1f)
    }
}
