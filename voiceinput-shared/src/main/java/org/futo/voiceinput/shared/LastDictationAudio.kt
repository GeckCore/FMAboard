package org.futo.voiceinput.shared

/**
 * FMAboard: the audio of the last dictation (16 kHz mono floats), kept in memory only, so the
 * keyboard can have it transcribed again by a better model. Taken once, then dropped.
 */
object LastDictationAudio {
    const val SAMPLE_RATE = 16000

    @Volatile
    private var samples: FloatArray? = null

    fun put(audio: FloatArray) { samples = audio }

    fun take(): FloatArray? = samples.also { samples = null }
}
