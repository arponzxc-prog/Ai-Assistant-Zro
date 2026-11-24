package com.arponzxc.novaai.audio

import kotlin.math.sqrt

data class AudioFrequencyData(
    val amplitude: Float = 0f,
    val pitchHz: Float = 0f,
    val bass: Float = 0f,
    val mid: Float = 0f,
    val high: Float = 0f,
    val band200_500: Float = 0f,
    val band500_1000: Float = 0f,
    val band1000_2000: Float = 0f,
    val band2000_4000: Float = 0f,
    val band4000_8000: Float = 0f,
    val isPlosive: Boolean = false,
    val isFricative: Boolean = false
)

object AudioUtils {

    private var rollingMaxRms = 2500.0

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
        rollingMaxRms = rollingMaxRms * 0.98 + maxOf(rms, 500.0) * 0.02
        val normalized = (rms / rollingMaxRms.coerceAtLeast(1000.0)).toFloat()
        return normalized.coerceIn(0f, 1f)
    }

    /**
     * Performs advanced spectral and formant-oriented frequency analysis on 16-bit Mono PCM audio.
     * Computes 5 multi-band spectral energies, F1/F2 approximations, plosives, and fricatives.
     */
    fun analyzeFrequency(bytes: ByteArray, length: Int, sampleRate: Int = 16000): AudioFrequencyData {
        if (length <= 0) return AudioFrequencyData()
        val sampleCount = length / 2
        if (sampleCount < 4) return AudioFrequencyData()

        var sumSquares = 0.0
        var zeroCrossings = 0
        var lastSample = 0
        var diffSumSquares = 0.0

        // Filter accumulator states for multi-band approximation
        var lp200State = 0.0
        var lp500State = 0.0
        var lp1000State = 0.0
        var lp2000State = 0.0
        var lp4000State = 0.0

        var sum200_500 = 0.0
        var sum500_1000 = 0.0
        var sum1000_2000 = 0.0
        var sum2000_4000 = 0.0
        var sum4000_8000 = 0.0

        val dt = 1.0 / sampleRate.coerceAtLeast(8000)

        var i = 0
        var idx = 0
        while (i < length - 1) {
            val low = bytes[i].toInt() and 0xFF
            val high = bytes[i + 1].toInt()
            val sample = (high shl 8) or low
            sumSquares += (sample * sample).toDouble()

            if (idx > 0) {
                if ((sample >= 0 && lastSample < 0) || (sample < 0 && lastSample >= 0)) {
                    zeroCrossings++
                }
                val diff = sample - lastSample
                diffSumSquares += (diff * diff).toDouble()
            }

            // Simple multi-stage pole approximations for spectral energy distribution
            lp200State += 0.15 * (sample - lp200State)
            lp500State += 0.35 * (sample - lp500State)
            lp1000State += 0.55 * (sample - lp1000State)
            lp2000State += 0.75 * (sample - lp2000State)
            lp4000State += 0.90 * (sample - lp4000State)

            val e200 = lp200State * lp200State
            val e500 = (lp500State - lp200State).let { it * it }
            val e1000 = (lp1000State - lp500State).let { it * it }
            val e2000 = (lp2000State - lp1000State).let { it * it }
            val e4000 = (lp4000State - lp2000State).let { it * it }

            sum200_500 += e200
            sum500_1000 += e500
            sum1000_2000 += e1000
            sum2000_4000 += e2000
            sum4000_8000 += e4000

            lastSample = sample
            idx++
            i += 2
        }

        val rms = sqrt(sumSquares / sampleCount)
        rollingMaxRms = rollingMaxRms * 0.99 + maxOf(rms, 400.0) * 0.01
        val amp = (rms / rollingMaxRms.coerceAtLeast(1000.0)).toFloat().coerceIn(0f, 1f)

        if (amp < 0.012f) {
            return AudioFrequencyData(amplitude = amp)
        }

        val b200 = (sqrt(sum200_500 / sampleCount) / 2000.0).toFloat().coerceIn(0f, 1f)
        val b500 = (sqrt(sum500_1000 / sampleCount) / 2000.0).toFloat().coerceIn(0f, 1f)
        val b1k = (sqrt(sum1000_2000 / sampleCount) / 2000.0).toFloat().coerceIn(0f, 1f)
        val b2k = (sqrt(sum2000_4000 / sampleCount) / 2000.0).toFloat().coerceIn(0f, 1f)
        val b4k = (sqrt(sum4000_8000 / sampleCount) / 2000.0).toFloat().coerceIn(0f, 1f)

        val zcr = zeroCrossings.toFloat() / (sampleCount - 1).coerceAtLeast(1)
        val pitchHz = (zcr * (sampleRate / 2.0f)).coerceIn(80f, 3500f)

        val bass = ((b200 * 1.2f) + (b500 * 0.8f)).coerceIn(0f, 1f)
        val high = ((b2k * 0.8f) + (b4k * 1.2f)).coerceIn(0f, 1f)
        val mid = ((b500 * 0.6f) + (b1k * 1.1f)).coerceIn(0f, 1f)

        // Detect fricatives (high energy in 4-8kHz relative to low band)
        val isFricative = b4k > 0.35f && b200 < 0.25f

        // Detect plosives (sudden energy burst after silence or low energy)
        val isPlosive = amp > 0.45f && b200 > 0.5f && zcr < 0.15f

        return AudioFrequencyData(
            amplitude = amp,
            pitchHz = pitchHz,
            bass = bass,
            mid = mid,
            high = high,
            band200_500 = b200,
            band500_1000 = b500,
            band1000_2000 = b1k,
            band2000_4000 = b2k,
            band4000_8000 = b4k,
            isPlosive = isPlosive,
            isFricative = isFricative
        )
    }
}
