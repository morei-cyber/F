package jp.focusroom.nativeapp

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.max
import kotlin.random.Random

/** Offline-generated ambient noise: no network or sound files required. */
class NoisePlayer {
    @Volatile private var active = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null
    @Volatile var volume: Float = 0.16f

    @Synchronized fun start(kind: String) {
        stop()
        if (kind == "OFF") return
        val sampleRate = 22050
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)
        val player = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuffer * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = player
        active = true
        player.play()
        thread = Thread {
            val random = Random.Default
            val samples = ShortArray(2048)
            var last = 0.0
            var brown = 0.0
            while (active) {
                for (i in samples.indices) {
                    val white = random.nextDouble(-1.0, 1.0)
                    val v = when (kind) {
                        "WHITE" -> white
                        "PINK" -> {
                            last = 0.93 * last + 0.07 * white
                            (0.72 * last + 0.28 * white) * 2.2
                        }
                        "BROWN" -> {
                            brown = (brown + 0.018 * white).coerceIn(-1.0, 1.0)
                            brown * 2.2
                        }
                        "RAIN" -> {
                            last = 0.88 * last + 0.12 * white
                            (0.72 * last + 0.28 * white) * 1.8
                        }
                        "OCEAN" -> {
                            brown = (brown + 0.012 * white).coerceIn(-1.0, 1.0)
                            brown * 2.8
                        }
                        else -> white
                    }
                    val scaled = (v * volume.coerceIn(0f, 0.6f) * 12500.0).toInt()
                    samples[i] = scaled.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
                try { player.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING) }
                catch (_: Exception) { break }
            }
        }.apply { name = "FocusRoom-Ambience"; isDaemon = true; start() }
    }

    @Synchronized fun stop() {
        active = false
        try { thread?.join(250) } catch (_: InterruptedException) { }
        thread = null
        try { track?.pause(); track?.flush(); track?.stop() } catch (_: Exception) { }
        try { track?.release() } catch (_: Exception) { }
        track = null
    }
}
