package com.middle.app

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.middle.app.data.WebhookLog
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Takes one photo with the rear camera and saves it to Pictures/Middle, with no
 * UI of its own.
 *
 * Android 11+ blocks camera access from a background service, so the TAKE_PHOTO
 * action starts this transparent activity instead. It always finishes: after a
 * save, after a failure, and after [TIMEOUT_MILLIS] so a stuck camera cannot
 * leave it open. A success or failure notification reports the outcome.
 */
class PhotoCaptureActivity : ComponentActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val finishing = AtomicBoolean(false)
    private val timeoutRunnable = Runnable { fail("timed out") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The capture may run while the phone is locked or the screen is off.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }

        // The action checked these before starting the activity, but a
        // permission can be revoked in between.
        if (!hasCameraPermission()) {
            fail(
                "CAMERA is not granted",
                notificationText = getString(R.string.take_photo_permission_notification_text),
            )
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !hasStoragePermission()) {
            fail(
                "WRITE_EXTERNAL_STORAGE is not granted",
                notificationText = getString(R.string.take_photo_storage_permission_notification_text),
            )
            return
        }

        handler.postDelayed(timeoutRunnable, TIMEOUT_MILLIS)
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener(
            {
                // The timeout can finish the activity before a slow provider
                // resolves; do not take a photo for a capture that is over.
                if (finishing.get()) return@addListener
                val provider = try {
                    providerFuture.get()
                } catch (exception: Exception) {
                    fail("camera provider unavailable", exception)
                    return@addListener
                }
                capture(provider)
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    override fun onDestroy() {
        // A capture callback that arrives after the activity is gone must not
        // post a second notification.
        finishing.set(true)
        handler.removeCallbacks(timeoutRunnable)
        super.onDestroy()
    }

    private fun capture(provider: ProcessCameraProvider) {
        val imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
        try {
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, imageCapture)
        } catch (exception: Exception) {
            fail("could not open the rear camera", exception)
            return
        }

        val outputOptions = try {
            outputOptions()
        } catch (exception: Exception) {
            fail("could not create the photo", exception)
            return
        }
        try {
            imageCapture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        if (!beginFinish()) return
                        try {
                            Log.i(TAG, "[action] photo saved to ${outputFileResults.savedUri}")
                            WebhookLog.info("Photo saved to Pictures/Middle")
                            postNotification(
                                getString(R.string.take_photo_saved_notification_text),
                                MiddleApplication.ACTIONS_NOTIFICATION_ID,
                            )
                        } finally {
                            // A notification failure must not leave the activity open.
                            finish()
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        fail("capture failed", exception)
                    }
                },
            )
        } catch (exception: Exception) {
            fail("could not start the capture", exception)
        }
    }

    /**
     * Writes through MediaStore on every supported version. Android 10+ names
     * the folder with RELATIVE_PATH and hides the row until it is complete;
     * older versions need an explicit DATA path and the storage permission.
     */
    private fun outputOptions(): ImageCapture.OutputFileOptions {
        val name = "Middle_${LocalDateTime.now().format(FILE_TIMESTAMP)}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/$ALBUM",
                )
            } else {
                val directory = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    ALBUM,
                )
                if (!directory.isDirectory && !directory.mkdirs()) {
                    throw IOException("Could not create $directory")
                }
                put(MediaStore.Images.Media.DATA, File(directory, name).absolutePath)
            }
        }
        return ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values,
        ).build()
    }

    /** Reports [message] and closes the activity, unless it is already closing. */
    private fun fail(
        message: String,
        exception: Throwable? = null,
        notificationText: String = getString(R.string.take_photo_failed_notification_text),
    ) {
        if (!beginFinish()) return
        try {
            if (exception == null) {
                Log.w(TAG, "[action] take photo failed: $message")
            } else {
                Log.w(TAG, "[action] take photo failed: $message", exception)
            }
            WebhookLog.error("Take photo failed: $message")
            postNotification(notificationText, MiddleApplication.ACTIONS_INFO_NOTIFICATION_ID)
        } finally {
            // A notification failure must not leave the activity open.
            finish()
        }
    }

    // Success, failure and the timeout can race, so only the first one wins.
    private fun beginFinish(): Boolean {
        if (!finishing.compareAndSet(false, true)) return false
        handler.removeCallbacks(timeoutRunnable)
        return true
    }

    private fun postNotification(text: String, notificationId: Int) {
        val notification = NotificationCompat.Builder(this, MiddleApplication.ACTIONS_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openMiddlePendingIntent())
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(notificationId, notification)
    }

    private fun openMiddlePendingIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasStoragePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "PhotoCapture"
        private const val ALBUM = "Middle"
        // Long enough for a slow camera to open, short enough that a broken
        // capture never leaves an invisible activity in front of the user.
        private const val TIMEOUT_MILLIS = 15_000L
        private val FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")
    }
}
