package com.nuzul.capturesnapshot.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.TaskStackBuilder
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.lifecycle.LifecycleService
import com.nuzul.capturesnapshot.MainActivity
import com.nuzul.capturesnapshot.R
import com.nuzul.capturesnapshot.extension.toByteArray
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BackgroundRecordService : LifecycleService() {

    private lateinit var imageCapture: ImageCapture
    private lateinit var videoCapture: VideoCapture<Recorder>
    private lateinit var imageAnalysis: ImageAnalysis

    // camera
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null

    private lateinit var timer: Timer
    private var timerTask: TimerTask? = null

    private val listeners = HashSet<DataListener>(1)
    private val pendingActions: HashMap<String, Runnable> = hashMapOf()
    private var activeRecording: Recording? = null
    private var recordingState: RecordingState = RecordingState.STOPPED
    private lateinit var recordingServiceBinder: RecordingServiceBinder
    private var duration: Int = 0

    companion object {
        private var TAG = BackgroundRecordService::class.java.simpleName
        const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        const val ACTION_START_WITH_PREVIEW: String = "start_recording"
        const val BIND_USE_CASE: String = "bind_usecase"

        const val CHANNEL_ID: String = "media_recorder_service"
        const val CHANNEL_NAME: String = "Media recording service"
        const val ONGOING_NOTIFICATION_ID: Int = 2345
    }

    enum class RecordingState {
        RECORDING, PAUSED, STOPPED
    }

    class RecordingServiceBinder(private val service: BackgroundRecordService) : Binder() {
        fun getService(): BackgroundRecordService {
            return service
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "BackgroundTaskService is ready to conquer!")
        Toast.makeText(applicationContext, "Start Recording Video", Toast.LENGTH_SHORT).show()
        recordingServiceBinder = RecordingServiceBinder(this)
        timer = Timer()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "BackgroundTaskService says goodbye!")
        activeRecording?.stop()
        timerTask?.cancel()
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return recordingServiceBinder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when(intent?.action){
            ACTION_START_WITH_PREVIEW -> {
                setupCamera()
            }
        }
        return START_NOT_STICKY
    }

    private fun setupCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            val qualitySelector = getQualitySelector()
            val recorder = Recorder.Builder()
                .setQualitySelector(qualitySelector)
                .build()

            videoCapture = VideoCapture.withOutput(recorder)
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            imageCapture = ImageCapture.Builder().apply {
                setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                setFlashMode(ImageCapture.FLASH_MODE_AUTO)
            }.build()

            try {
                cameraProvider?.unbindAll()

                val useCaseGroup = UseCaseGroup.Builder()
                    .addUseCase(imageCapture)
                    .addUseCase(videoCapture)
                    .build()
                // Bind use cases to camera
                cameraProvider?.bindToLifecycle(this, cameraSelector, useCaseGroup)
                captureVideo()
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed, ", exc)
            }
            val action = pendingActions[BIND_USE_CASE]
            action?.run()
            pendingActions.remove(BIND_USE_CASE)
        }, ContextCompat.getMainExecutor(this))


    }

    private fun getQualitySelector(): QualitySelector {
        return QualitySelector.from(
            Quality.HIGHEST,
            FallbackStrategy.higherQualityOrLowerThan(Quality.SD)
        )
    }

    fun getRecordingState(): RecordingState {
        return recordingState
    }

    fun addListener(listener: DataListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: DataListener) {
        listeners.remove(listener)
    }

    fun bindPreviewUseCase(surfaceProvider: Preview.SurfaceProvider) {
        if (cameraProvider != null){
            bindInternal(surfaceProvider)
        } else {
            pendingActions[BIND_USE_CASE] = Runnable {
                bindInternal(surfaceProvider)
            }
        }
    }

    fun unbindPreview() {
        // Just remove the surface provider. I discovered that for some reason if you unbind the Preview usecase the camera willl stop recording the video.
        preview?.setSurfaceProvider(null)
    }

    private fun bindInternal(surfaceProvider: Preview.SurfaceProvider) {
        if (preview != null){
            cameraProvider?.unbindAll()
        }
        setupPreviewUseCase()
        preview?.setSurfaceProvider(surfaceProvider)
        val cameraInfo: CameraInfo? = cameraProvider?.bindToLifecycle(
            this@BackgroundRecordService,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview
        )?.cameraInfo
        observeCameraState(cameraInfo, this)
    }

    private fun observeCameraState(
        cameraInfo: CameraInfo?,
        context: Context
    ) {
        cameraInfo?.cameraState?.observe(this) { cameraState ->
            run {
                when(cameraState.type){
                    CameraState.Type.PENDING_OPEN -> {

                    }
                    CameraState.Type.OPENING -> {
                        // Show the Camera UI
                        for (listener in listeners) {
                            listener.onCameraOpened()
                        }
                    }
                    CameraState.Type.OPEN -> {
                        // Setup Camera resources and begin processing
                    }
                    CameraState.Type.CLOSING -> {
                        // Close camera UI
                    }
                    CameraState.Type.CLOSED -> {
                        // Free camera resources
                    }
                }
            }
            cameraState.error?.let { error ->
                when(error.code){
                    // Open errors
                    CameraState.ERROR_STREAM_CONFIG -> {
                        // Make sure to setup the use cases properly
                        Toast.makeText(context, "Stream config error. Restart application", Toast.LENGTH_SHORT).show()
                    }
                    // Opening errors
                    CameraState.ERROR_CAMERA_IN_USE -> {
                        // Close the camera or ask user to close another camera app that's using the
                        // camera
                        Toast.makeText(context, "Camera in use. Close any apps that are using the camera", Toast.LENGTH_SHORT).show()
                    }
                    CameraState.ERROR_MAX_CAMERAS_IN_USE -> {
                        // Close another open camera in the app, or ask the user to close another
                        // camera app that's using the camera
                    }
                    CameraState.ERROR_OTHER_RECOVERABLE_ERROR -> {

                    }
                    // Closing errors
                    CameraState.ERROR_CAMERA_DISABLED -> {
                        // Ask the user to enable the device's cameras
                        Toast.makeText(context, "Camera disabled", Toast.LENGTH_SHORT).show()
                    }
                    CameraState.ERROR_CAMERA_FATAL_ERROR -> {
                        // Ask the user to reboot the device to restore camera function
                        Toast.makeText(context, "Fatal error", Toast.LENGTH_SHORT).show()
                    }
                    // Closed errors
                    CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED -> {
                        // Ask the user to disable the "Do Not Disturb" mode, then reopen the camera
                        Toast.makeText(context, "Do not disturb mode enabled", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun setupPreviewUseCase() {
        preview?.setSurfaceProvider(null)
        preview = Preview.Builder().build()
    }

    interface DataListener {
        fun onNewData(duration: Int)
        fun onSuccessTakePhoto(imageUri: Uri?)
        fun onCameraOpened()
        fun onRecordingEvent(it: VideoRecordEvent?)
    }

    fun captureVideo() {
        // If there is an active recording in progress, stop it and release the current recording.
        // We will be notified when the captured video file is ready to be used by our application.
        val curRecording = activeRecording
        if (curRecording != null) {
            curRecording.stop()
            activeRecording = null
            return
        }

        // To start recording, we create a new recording session.
        // First we create our intended MediaStore video content object,
        // with system timestamp as the display name(so we could capture multiple videos).
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraX-Video")
            }
        }

        val mediaStoreOutputOptions = MediaStoreOutputOptions
            .Builder(applicationContext.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()

        activeRecording = videoCapture.output
            .prepareRecording(applicationContext, mediaStoreOutputOptions)
            .apply {
                // Enable Audio for recording
                if (PermissionChecker.checkSelfPermission(applicationContext, Manifest.permission.RECORD_AUDIO) == PermissionChecker.PERMISSION_GRANTED) {
                    withAudioEnabled()
                }
            }
            .start(ContextCompat.getMainExecutor(applicationContext)) { recordEvent ->
                when (recordEvent) {
                    is VideoRecordEvent.Start -> {
                        Log.d(TAG, "captureVideo: videoRecordEvent Start")
                        startTrackingTime()
                        recordingState = RecordingState.RECORDING
                    }

                    is VideoRecordEvent.Finalize -> {
                        Log.d(TAG, "captureVideo: videoRecordEvent Stop")
                        recordingState = RecordingState.STOPPED
                        duration = 0
                        timerTask?.cancel()
                        if (!recordEvent.hasError()) {
                            val msg = "Video capture succeeded: ${recordEvent.outputResults.outputUri}"
                            Log.d(TAG, msg)
                        } else {
                            activeRecording?.close()
                            activeRecording = null
                            Log.e(TAG, "Video capture ends with error: ${recordEvent.error}")
                        }
                    }
                }
                for (listener in listeners) {
                    listener.onRecordingEvent(recordEvent)
                }
            }
        recordingState = RecordingState.RECORDING
    }

    private fun startTrackingTime() {
        timerTask = object: TimerTask() {
            override fun run() {
                if (recordingState == RecordingState.RECORDING) {
                    duration += 1
                    for (listener in listeners) {
                        listener.onNewData(duration)
                    }
                }
            }
        }
        timer.scheduleAtFixedRate(timerTask, 1000, 1000)
    }

    fun startRunningInForeground() {
        val parentStack = TaskStackBuilder.create(this)
            .addNextIntentWithParentStack(Intent(this, MainActivity::class.java))

        val pendingIntent1 = parentStack.getPendingIntent(0, 0)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Video Recording")
            .setContentText("CameraX Vide Recording")
            .setSmallIcon(R.drawable.ic_camera)
            .setContentIntent(pendingIntent1)
            .build()
        startForeground(ONGOING_NOTIFICATION_ID, notification)
    }

    fun takePhoto() {
        // Get a stable reference of the modifiable image capture use cases
        val imageCapture = this.imageCapture

        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-image")
            }
        }

        // Create output options object which contains file + metadata
        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            applicationContext.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(applicationContext),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val msg = "Photo capture succeeded : ${outputFileResults.savedUri}"
                    Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()

                    for (listener in listeners) {
                        listener.onSuccessTakePhoto(outputFileResults.savedUri)
                    }
                    Log.d(TAG, msg)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo Capture failed: ${exception.message}", exception)
                }
            }
        )
    }

    private fun setupImageAnalysis() {
        imageAnalysis = ImageAnalysis.Builder().apply {
            setImageQueueDepth(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        }.build()
        imageAnalysis.setAnalyzer(Executors.newSingleThreadExecutor(), LuminosityAnalyzer())
    }

    private class LuminosityAnalyzer : ImageAnalysis.Analyzer {
        private var lastAnalyzedTimestamp = 0L

        /**
         * Helper extension function used to extract a byte array from an
         * image plane buffer
         */
        override fun analyze(image: ImageProxy) {
            val currentTimestamp = System.currentTimeMillis()
            // Calculate the average luma no more often than every second
            if (currentTimestamp - lastAnalyzedTimestamp >=
                TimeUnit.SECONDS.toMillis(1)
            ) {
                // Since format in ImageAnalysis is YUV, image.planes[0]
                // contains the Y (luminance) plane
                val buffer = image.planes[0].buffer
                // Extract image data from callback object
                val data = buffer.toByteArray()
                // Convert the data into an array of pixel values
                val pixels = data.map { it.toInt() and 0xFF }
                // Compute average luminance for the image
                val luma = pixels.average()
                // Log the new luma value
                Log.d("CameraXApp", "Average luminosity: $luma")
                // Update timestamp of last analyzed frame
                lastAnalyzedTimestamp = currentTimestamp
            }
            image.close()
        }
    }

    fun stopRecording(){
        activeRecording?.stop()
        activeRecording = null
    }

}