package com.jarvis.assistant.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AudioPlayer(
    private val onPlaybackStateChanged: (isPlaying: Boolean) -> Unit,
    private val onPlaybackAmplitude: (amplitude: Float) -> Unit
) {
    companion object {
        private const val TAG = "AudioPlayer"
        const val SAMPLE_RATE = 24000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioTrack: AudioTrack? = null
    private val audioQueue = LinkedBlockingQueue<ByteArray>()
    private val isRunning = AtomicBoolean(false)
    private val isPlayingState = AtomicBoolean(false)
    private var playbackThread: Thread? = null

    init {
        initAudioTrack()
    }

    private fun initAudioTrack() {
        val minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = maxOf(minBufferSize * 2, 9600)

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(CHANNEL_CONFIG)
            .setEncoding(AUDIO_FORMAT)
            .build()

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }

    private val isTurnComplete = AtomicBoolean(false)

    fun notifyTurnComplete() {
        isTurnComplete.set(true)
    }

    fun start() {
        if (isRunning.get()) return
        isRunning.set(true)

        playbackThread = Thread {
            var silenceCounter = 0
            while (isRunning.get()) {
                try {
                    // If not currently playing, wait for chunks and pre-buffer before starting AudioTrack.play()
                    if (!isPlayingState.get()) {
                        val firstChunk = audioQueue.poll(30, TimeUnit.MILLISECONDS)
                        if (firstChunk != null && firstChunk.isNotEmpty()) {
                            isTurnComplete.set(false)
                            audioTrack?.let { track ->
                                if (track.state == AudioTrack.STATE_INITIALIZED) {
                                    track.write(firstChunk, 0, firstChunk.size)
                                    // Fill the hardware buffer with any already queued chunks
                                    while (audioQueue.isNotEmpty()) {
                                        val next = audioQueue.poll() ?: break
                                        track.write(next, 0, next.size)
                                    }
                                    try {
                                        track.play()
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error calling play(): ${e.message}")
                                    }
                                }
                            }
                            silenceCounter = 0
                            isPlayingState.set(true)
                            onPlaybackStateChanged(true)
                            val amplitude = AudioUtils.calculateRmsNormalized(firstChunk, firstChunk.size)
                            onPlaybackAmplitude(amplitude)
                        }
                        continue
                    }

                    // Currently playing: stream incoming chunks to the hardware AudioTrack
                    val chunk = audioQueue.poll(20, TimeUnit.MILLISECONDS)
                    if (chunk != null && chunk.isNotEmpty()) {
                        silenceCounter = 0
                        val amplitude = AudioUtils.calculateRmsNormalized(chunk, chunk.size)
                        onPlaybackAmplitude(amplitude)

                        audioTrack?.write(chunk, 0, chunk.size)
                    } else {
                        silenceCounter++
                        // Clean turn completion:
                        // 1. If Gemini signaled turnComplete: wait 12 cycles (~240ms) of empty queue
                        //    so the hardware AudioTrack buffer has finished rendering out to the speaker.
                        // 2. If Gemini is still generating (turnComplete not yet received): wait at least
                        //    150 cycles (~3000ms) to tolerate network packet jitter without cutting off mid-speech.
                        val shouldFinish = (isTurnComplete.get() && silenceCounter >= 12) || (silenceCounter >= 150)
                        if (shouldFinish && isPlayingState.get()) {
                            isPlayingState.set(false)
                            try {
                                audioTrack?.let {
                                    if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                                        it.stop()
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error stopping AudioTrack: ${e.message}")
                            }
                            onPlaybackStateChanged(false)
                            onPlaybackAmplitude(0f)
                        }
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error in playback worker: ${e.message}")
                }
            }
        }.apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun enqueueAudio(pcmData: ByteArray) {
        if (pcmData.isNotEmpty()) {
            audioQueue.offer(pcmData)
        }
    }

    /**
     * Instantly flushes audio playback upon interruption.
     */
    fun flush() {
        audioQueue.clear()
        isTurnComplete.set(true)
        try {
            audioTrack?.let {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    it.pause()
                    it.flush()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error flushing AudioTrack: ${e.message}")
        }
        if (isPlayingState.getAndSet(false)) {
            onPlaybackStateChanged(false)
            onPlaybackAmplitude(0f)
        }
    }

    fun isPlaying(): Boolean = isPlayingState.get()

    fun release() {
        isRunning.set(false)
        playbackThread?.interrupt()
        playbackThread = null
        audioQueue.clear()

        try {
            audioTrack?.let {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioTrack: ${e.message}")
        } finally {
            audioTrack = null
        }
    }
}
