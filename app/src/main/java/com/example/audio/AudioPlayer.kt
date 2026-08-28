package com.example.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class AudioPlayer {

    private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _playbackAmplitude = MutableStateFlow(0f)
    val playbackAmplitude = _playbackAmplitude.asStateFlow()

    fun playAudioBase64(base64Data: String, mimeType: String = "audio/pcm", onComplete: () -> Unit = {}) {
        try {
            val audioBytes = Base64.decode(base64Data, Base64.DEFAULT)
            playAudioBytes(audioBytes, mimeType, onComplete)
        } catch (e: Exception) {
            onComplete()
        }
    }

    fun playAudioBytes(bytes: ByteArray, mimeType: String = "audio/pcm", onComplete: () -> Unit = {}) {
        stop()

        playbackJob = scope.launch {
            try {
                // Determine sample rate and PCM offset
                var sampleRate = 24000
                var pcmBytes = bytes
                var offset = 0

                // Check for RIFF WAV header
                if (bytes.size > 44 &&
                    bytes[0] == 'R'.code.toByte() &&
                    bytes[1] == 'I'.code.toByte() &&
                    bytes[2] == 'F'.code.toByte() &&
                    bytes[3] == 'F'.code.toByte()
                ) {
                    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    sampleRate = buffer.getInt(24)
                    offset = 44
                    pcmBytes = bytes.copyOfRange(offset, bytes.size)
                } else if (mimeType.contains("rate=")) {
                    val rateMatch = Regex("rate=(\\d+)").find(mimeType)
                    if (rateMatch != null) {
                        sampleRate = rateMatch.groupValues[1].toIntOrNull() ?: 24000
                    }
                }

                val channelConfig = AudioFormat.CHANNEL_OUT_MONO
                val audioEncoding = AudioFormat.ENCODING_PCM_16BIT
                val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioEncoding)

                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()

                val format = AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .setEncoding(audioEncoding)
                    .build()

                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(minBufferSize.coerceAtLeast(pcmBytes.size))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                _isPlaying.value = true
                audioTrack?.play()

                val chunkSize = 2048
                var currentPos = 0

                while (isActive && currentPos < pcmBytes.size && _isPlaying.value) {
                    val length = (pcmBytes.size - currentPos).coerceAtMost(chunkSize)
                    val written = audioTrack?.write(pcmBytes, currentPos, length) ?: -1
                    if (written > 0) {
                        // Compute playback amplitude
                        var sum = 0L
                        for (i in currentPos until (currentPos + written) step 2) {
                            if (i + 1 < pcmBytes.size) {
                                val sample = (pcmBytes[i + 1].toInt() shl 8) or (pcmBytes[i].toInt() and 0xFF)
                                sum += abs(sample.toShort().toLong())
                            }
                        }
                        val sampleCount = (written / 2).coerceAtLeast(1)
                        val avg = sum.toFloat() / sampleCount
                        val normalized = (avg / 10000f).coerceIn(0f, 1f)
                        _playbackAmplitude.value = normalized

                        currentPos += written
                    } else {
                        break
                    }
                }

                // Wait for playback to drain
                if (isActive && _isPlaying.value) {
                    kotlinx.coroutines.delay(100)
                }
            } catch (e: Exception) {
                // Ignore playback errors
            } finally {
                _isPlaying.value = false
                _playbackAmplitude.value = 0f
                try {
                    audioTrack?.stop()
                    audioTrack?.release()
                } catch (e: Exception) {
                    // Ignore
                } finally {
                    audioTrack = null
                }
                onComplete()
            }
        }
    }

    fun stop() {
        _isPlaying.value = false
        _playbackAmplitude.value = 0f
        playbackJob?.cancel()
        playbackJob = null

        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            // Ignore
        } finally {
            audioTrack = null
        }
    }
}
