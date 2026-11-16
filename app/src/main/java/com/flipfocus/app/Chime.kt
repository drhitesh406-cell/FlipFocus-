package com.flipfocus.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/** Synthesised bell "ting" (no audio assets). play(1) = ting, play(2) = double ting. Uses the alarm volume. */
object Chime {
    private const val SR = 22050

    private fun one() = ShortArray((SR * 0.5).toInt()) { i ->
        val t = i / SR.toDouble()
        val env = exp(-7.0 * t) * min(1.0, i / 60.0)
        val v = (sin(2 * PI * 1568 * t) * 0.7 + sin(2 * PI * 3136 * t) * 0.3) * env
        (v * 22000).toInt().toShort()
    }

    fun play(times: Int) {
        Thread {
            try {
                val o = one(); val gap = (SR * 0.15).toInt()
                val d = ShortArray(o.size * times + gap * (times - 1))
                for (k in 0 until times) o.copyInto(d, k * (o.size + gap))
                val at = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SR).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(d.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
                at.write(d, 0, d.size)
                at.play()
                Thread.sleep(d.size * 1000L / SR + 300)
                at.release()
            } catch (_: Throwable) {}
        }.start()
    }
}
