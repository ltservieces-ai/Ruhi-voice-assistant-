package com.example.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class AudioRecorder(private val context: Context) {

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _amplitude = MutableStateFlow(0f)
    val amplitude = _amplitude.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording = _isRecording.asStateFlow()

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat).coerceAtLeast(4096)

    private var pcmDataStream = ByteArrayOutputStream()

    @SuppressLint("MissingPermission")
    fun startRecording(): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return false
        }

        try {
            stopRecording()
            pcmDataStream = ByteArrayOutputStream()

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                return false
            }

            audioRecord?.startRecording()
            _isRecording.value = true

            recordingJob = scope.launch {
                val buffer = ByteArray(bufferSize)
                while (isActive && _isRecording.value) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        pcmDataStream.write(buffer, 0, read)

                        // Calculate normalized RMS amplitude for visualization
                        var sum = 0L
                        for (i in 0 until read step 2) {
                            if (i + 1 < read) {
                                val sample = (buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)
                                sum += abs(sample.toShort().toLong())
                            }
                        }
                        val sampleCount = (read / 2).coerceAtLeast(1)
                        val avg = sum.toFloat() / sampleCount
                        val normalized = (avg / 12000f).coerceIn(0f, 1f)
                        _amplitude.value = normalized
                    }
                }
            }
            return true
        } catch (e: Exception) {
            _isRecording.value = false
            return false
        }
    }

    fun stopRecording(): ByteArray {
        _isRecording.value = false
        _amplitude.value = 0f
        recordingJob?.cancel()
        recordingJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            // Ignore stop errors
        } finally {
            audioRecord = null
        }

        val rawPcm = pcmDataStream.toByteArray()
        return createWavFile(rawPcm, sampleRate, 1, 16)
    }

    private fun createWavFile(pcmData: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val totalAudioLen = pcmData.size
        val totalDataLen = totalAudioLen + 36
        val byteRate = sampleRate * channels * (bitsPerSample / 8)

        val header = ByteArray(44)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF header
        buffer.put('R'.code.toByte())
        buffer.put('I'.code.toByte())
        buffer.put('F'.code.toByte())
        buffer.put('F'.code.toByte())
        buffer.putInt(totalDataLen)
        buffer.put('W'.code.toByte())
        buffer.put('A'.code.toByte())
        buffer.put('V'.code.toByte())
        buffer.put('E'.code.toByte())

        // 'fmt ' chunk
        buffer.put('f'.code.toByte())
        buffer.put('m'.code.toByte())
        buffer.put('t'.code.toByte())
        buffer.put(' '.code.toByte())
        buffer.putInt(16) // Subchunk1Size for PCM
        buffer.putShort(1.toShort()) // AudioFormat 1 = PCM
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort((channels * (bitsPerSample / 8)).toShort()) // BlockAlign
        buffer.putShort(bitsPerSample.toShort())

        // 'data' chunk
        buffer.put('d'.code.toByte())
        buffer.put('a'.code.toByte())
        buffer.put('t'.code.toByte())
        buffer.put('a'.code.toByte())
        buffer.putInt(totalAudioLen)

        val wavStream = ByteArrayOutputStream(44 + totalAudioLen)
        wavStream.write(header)
        wavStream.write(pcmData)
        return wavStream.toByteArray()
    }
}
