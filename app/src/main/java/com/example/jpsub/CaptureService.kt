package com.example.jpsub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import kotlin.math.abs
import kotlin.math.sqrt

class CaptureService : Service() {
    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "stop"
        @Volatile var listener: ((String, Float) -> Unit)? = null
        @Volatile var running = false
    }

    private var projection: MediaProjection? = null
    private var worker: Thread? = null
    @Volatile private var stopFlag = false
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    private fun post(msg: String, level: Float = 0f) {
        main.post { listener?.invoke(msg, level) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf(); return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("cap", "Audio capture", NotificationManager.IMPORTANCE_LOW)
        )
        val n: Notification = Notification.Builder(this, "cap")
            .setContentTitle("JP Sub")
            .setContentText("시스템 소리 캡처 중")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val code = intent.getIntExtra(EXTRA_CODE, 0)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = mpm.getMediaProjection(code, data)
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, main)
        projection = p

        running = true
        stopFlag = false
        worker = Thread { captureLoop(p) }.also { it.start() }
        return START_NOT_STICKY
    }

    private fun captureLoop(p: MediaProjection) {
        val cfg = AudioPlaybackCaptureConfiguration.Builder(p)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        var rec: AudioRecord? = null
        var rate = 0
        for (r in intArrayOf(16000, 44100, 48000)) {
            val min = AudioRecord.getMinBufferSize(
                r, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            try {
                val a = AudioRecord.Builder()
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(r)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(min * 4)
                    .setAudioPlaybackCaptureConfig(cfg)
                    .build()
                if (a.state == AudioRecord.STATE_INITIALIZED) { rec = a; rate = r; break }
                a.release()
            } catch (e: Exception) { /* 다음 샘플레이트 시도 */ }
        }
        if (rec == null) {
            post("오류: AudioRecord 초기화 실패 (캡처 불가)")
            main.post { stopSelf() }
            return
        }

        rec.startRecording()
        post("캡처 시작됨 (${rate}Hz). 유튜브를 재생해 보세요.")
        val buf = ShortArray(rate / 10)  // 100ms
        var silentMs = 0
        while (!stopFlag) {
            val n = rec.read(buf, 0, buf.size)
            if (n <= 0) continue
            var sum = 0.0
            var peak = 0
            for (i in 0 until n) {
                val v = buf[i].toInt()
                sum += v.toDouble() * v
                if (abs(v) > peak) peak = abs(v)
            }
            val rms = sqrt(sum / n)
            val level = minOf(1f, (rms / 32768.0 * 8).toFloat())
            if (peak < 5) silentMs += 100 else silentMs = 0
            val msg = if (silentMs >= 3000)
                "무음 (재생 중인데 계속 이러면 해당 앱이 캡처를 막고 있을 수 있음)"
            else "소리 감지됨 (${rate}Hz, peak=$peak)"
            post(msg, level)
        }
        rec.stop()
        rec.release()
    }

    override fun onDestroy() {
        stopFlag = true
        worker?.join(1000)
        projection?.stop()
        projection = null
        running = false
        post("중지됨")
        super.onDestroy()
    }
}
