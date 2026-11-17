package com.example.jpsub

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var bar: ProgressBar
    private lateinit var btn: Button
    private val reqPerm = 1
    private val reqCap = 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        status = TextView(this).apply {
            text = "대기 중. [캡처 시작]을 누른 뒤 유튜브를 재생하세요."
            textSize = 16f
        }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        btn = Button(this).apply {
            text = "캡처 시작"
            setOnClickListener { toggle() }
        }
        root.addView(status)
        root.addView(bar)
        root.addView(btn)
        setContentView(root)

        CaptureService.listener = { msg, level ->
            runOnUiThread {
                status.text = msg
                bar.progress = (level * 100).toInt()
                btn.text = if (CaptureService.running) "중지" else "캡처 시작"
            }
        }
        btn.text = if (CaptureService.running) "중지" else "캡처 시작"
    }

    private fun toggle() {
        if (CaptureService.running) {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
            return
        }
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isNotEmpty()) {
            requestPermissions(need.toTypedArray(), reqPerm)
        } else {
            askCapture()
        }
    }

    private fun askCapture() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), reqCap)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == reqPerm) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                askCapture()
            else status.text = "마이크(오디오) 권한이 필요합니다. 설정에서 허용해 주세요."
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqCap) {
            if (resultCode == RESULT_OK && data != null) {
                val i = Intent(this, CaptureService::class.java)
                    .putExtra(CaptureService.EXTRA_CODE, resultCode)
                    .putExtra(CaptureService.EXTRA_DATA, data)
                startForegroundService(i)
            } else status.text = "화면 공유(캡처) 권한이 거부되었습니다."
        }
    }

    override fun onDestroy() {
        CaptureService.listener = null
        super.onDestroy()
    }
}
