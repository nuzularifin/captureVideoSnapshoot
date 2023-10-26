package com.nuzul.capturesnapshot.ui

import android.Manifest
import android.content.ComponentName
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
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
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.nuzul.capturesnapshot.MainActivity
import com.nuzul.capturesnapshot.R
import com.nuzul.capturesnapshot.databinding.FragmentVideoCaptureSnapshotBinding
import com.nuzul.capturesnapshot.extension.getFormattedVideoSizeInMB
import com.nuzul.capturesnapshot.extension.getPath
import com.nuzul.capturesnapshot.extension.getRealSizeFromUri
import com.nuzul.capturesnapshot.extension.getSizeFileUri
import com.nuzul.capturesnapshot.ui.adapter.CaptureImageAdapter
import com.nuzul.capturesnapshot.viewmodel.SharedViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


class VideoCaptureSnapshotFragment : Fragment() {

    private lateinit var _binding: FragmentVideoCaptureSnapshotBinding

    private lateinit var cameraExecutor: ExecutorService

    private val sharedViewModel: SharedViewModel by activityViewModels()

    private val captureImageAdapter = CaptureImageAdapter()

    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraInfo: CameraInfo? = null
    private var cameraSelector: CameraSelector? = null
    private lateinit var imageCapture: ImageCapture
    private lateinit var videoCapture: VideoCapture<Recorder>
    private var recordingState: RecordingState = RecordingState.STOPPED
    private var activeRecording: Recording? = null

    private lateinit var timer: Timer
    private var timerTask: TimerTask? = null
    private var duration: Int = 0
    private var preview: Preview? = null

    enum class RecordingState {
        RECORDING, PAUSED, STOPPED
    }

    companion object {
        private val TAG: String = VideoCaptureSnapshotFragment::class.java.simpleName
        private const val MINUTE: Int = 60
        private const val HOUR: Int = MINUTE * 60
        const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        const val ACTION_START_WITH_PREVIEW: String = "start_recording"
        const val BIND_USE_CASE: String = "bind_usecase"

        const val CHANNEL_ID: String = "media_recorder_service"
        const val CHANNEL_NAME: String = "Media recording service"
        const val ONGOING_NOTIFICATION_ID: Int = 2345

        fun newInstance() = VideoCaptureSnapshotFragment()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentVideoCaptureSnapshotBinding.inflate(inflater, container, false)
        return _binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        cameraExecutor = Executors.newSingleThreadExecutor()
        timer = Timer()

        sharedViewModel.isPermissionGranted.observe(viewLifecycleOwner) {
            if (!it) {
                if (activity is MainActivity) {
                    (activity as MainActivity).requestAllPermission()
                }
            }
        }

        _binding.btnGallery.setOnClickListener {

        }

        _binding.rvPhoto.apply {
            adapter = captureImageAdapter
            layoutManager =
                LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        }

        captureImageAdapter.setData(sharedViewModel.getDummyTotalData())

        _binding.btnTakePhoto.setOnClickListener {
            val position = checkingPosition()

            if (position != -1) {
                _binding.ivTakePhoto.setImageBitmap(_binding.pvVideo.bitmap)
                showTakePhoto()
            } else {
                Toast.makeText(
                    requireContext(),
                    "Melebihi batas total pengambilan data gambar",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        _binding.btnRetakePhoto.setOnClickListener {
            hideTakePhoto()
        }

        _binding.btnVideoRecord.setOnClickListener {
            startVideo()
        }

        _binding.btnNext.setOnClickListener {
            hideTakePhoto()
            saveImage(_binding.ivTakePhoto.drawable.toBitmap())
            val position = checkingPosition()
            captureImageAdapter.updateImageUriItem(position, _binding.pvVideo.bitmap)
            if (checkingPosition() != -1) {
                setToolbar(
                    title = captureImageAdapter.captureImages[checkingPosition()].title.orEmpty(),
                    totalData = captureImageAdapter.captureImages.size,
                    currentPosition = checkingPosition() + 1
                )
            }
        }

        setToolbar(
            title = captureImageAdapter.captureImages[checkingPosition()].title.orEmpty(),
            totalData = captureImageAdapter.captureImages.size,
            currentPosition = checkingPosition() + 1
        )

        startCamera()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())

        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            cameraProvider = cameraProviderFuture.get()

            // Preview
            preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(_binding.pvVideo.surfaceProvider)
                }

            // Video
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        Quality.HIGHEST,
                        FallbackStrategy.higherQualityOrLowerThan(Quality.SD)
                    )
                )
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            imageCapture = ImageCapture.Builder().apply {
                setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                setFlashMode(ImageCapture.FLASH_MODE_AUTO)
            }.build()

            // Select back camera as a default
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider?.unbindAll()

                val useCaseGroup = UseCaseGroup.Builder()
                    .addUseCase(imageCapture)
                    .addUseCase(videoCapture)
                    .addUseCase(preview!!)
                    .build()

                // Bind use cases to camera

                cameraInfo = cameraProvider?.bindToLifecycle(this, cameraSelector!!, useCaseGroup)?.cameraInfo
                observeCameraState(cameraInfo)
//                startVideo()
            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun observeCameraState(
        cameraInfo: CameraInfo?,
    ) {
        cameraInfo?.cameraState?.observe(viewLifecycleOwner) { cameraState ->
            run {
                when(cameraState.type){
                    CameraState.Type.PENDING_OPEN -> {
                        Log.d(TAG, "observeCameraState: PENDING-OPEN")
                    }
                    CameraState.Type.OPENING -> {
                        Log.d(TAG, "observeCameraState: OPENING")
                    }
                    CameraState.Type.OPEN -> {
                        // Setup Camera resources and begin processing
                        Log.d(TAG, "observeCameraState: OPEN")
                        startVideo()
                    }
                    CameraState.Type.CLOSING -> {
                        // Close camera UI
                        Log.d(TAG, "observeCameraState: CLOSING")
                    }
                    CameraState.Type.CLOSED -> {
                        // Free camera resources
                        Log.d(TAG, "observeCameraState: CLOSED")
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

    private fun startVideo() {
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
            .Builder(
                requireContext().contentResolver,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            )
            .setContentValues(contentValues)
            .build()

        activeRecording = videoCapture.output
            .prepareRecording(requireContext(), mediaStoreOutputOptions)
            .apply {
                if (PermissionChecker.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PermissionChecker.PERMISSION_GRANTED) {
                    withAudioEnabled()
                }
            }
            .start(ContextCompat.getMainExecutor(requireContext())) { recordEvent ->
                when (recordEvent) {
                    is VideoRecordEvent.Start -> {
                        Log.d(TAG, "captureVideo: videoRecordEvent Start")
                        recordingState = RecordingState.RECORDING
                        startTrackingTime()
                    }

                    is VideoRecordEvent.Finalize -> {
                        Log.d(TAG, "captureVideo: videoRecordEvent Stop")
                        recordingState = RecordingState.STOPPED

                        duration = 0
                        timerTask?.cancel()
                        if (!recordEvent.hasError()) {
                            val msg = "Video capture succeeded: ${recordEvent.outputResults.outputUri}"
                            Log.d(TAG, msg)
                            val fileSize = "Video capture succeeded: ${getSizeFileUri(requireContext(), recordEvent.outputResults.outputUri)} mb"
                            Log.d(TAG, fileSize)
                            _binding.tvCapacity.text = fileSize
                        } else {
                            activeRecording?.close()
                            activeRecording = null
                            Log.e(TAG, "Video capture ends with error: ${recordEvent.error}")
                        }
                    }
                }
            }
    }
    private fun saveImage(bitmap: Bitmap) {
        val resolver: ContentResolver = requireContext().contentResolver
        val contentValues = ContentValues()
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, FILENAME_FORMAT)
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            contentValues.put(
                MediaStore.Video.Media.RELATIVE_PATH,
                "DCIM/moladin/${System.currentTimeMillis()}.jpg"
            )
        }
        val imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        resolver.openOutputStream(imageUri!!)?.let {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            it.flush()
            it.close()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        stopRecording()
        timerTask?.cancel()
    }

    fun onNewData(duration: Int) {
        requireActivity().runOnUiThread {
            with(_binding) {
                var seconds = duration
                var minutes = seconds / MINUTE
                seconds %= MINUTE
                val hours = minutes / HOUR
                minutes %= HOUR

                val hoursString = if (hours >= 10) hours.toString() else "0$hours"
                val minutesString = if (minutes >= 10) minutes.toString() else "0$minutes"
                val secondsString = if (seconds >= 10) seconds.toString() else "0$seconds"
                tvTimer.text = "$hoursString:$minutesString:$secondsString"
            }
        }
    }

    private fun showTakePhoto() {
        with(_binding) {
            pvVideo.visibility = View.GONE
            clPhoto.visibility = View.VISIBLE
        }
    }

    private fun hideTakePhoto() {
        with(_binding) {
            pvVideo.visibility = View.VISIBLE
            clPhoto.visibility = View.GONE
        }
    }

    private fun setToolbar(title: String, totalData: Int, currentPosition: Int) {
        _binding.toolbar.apply {
            setTitle(title)
            setTitleTextColor(ContextCompat.getColor(requireContext(), R.color.white))
            _binding.tvTotalCountData.text = "$currentPosition/$totalData"
        }
    }

    private fun checkingPosition(): Int {
        return captureImageAdapter.captureImages.indexOfFirst { it.uriImage == null }
    }

    private fun getQualitySelector(): QualitySelector {
        return QualitySelector.from(
            Quality.HIGHEST,
            FallbackStrategy.higherQualityOrLowerThan(Quality.SD)
        )
    }

    private fun startTrackingTime() {
        timerTask = object: TimerTask() {
            override fun run() {
                if (recordingState == RecordingState.RECORDING) {
                    duration += 1
                    onNewData(duration)
                }
            }
        }
        timer.scheduleAtFixedRate(timerTask, 1000, 1000)
    }

    fun stopRecording(){
        activeRecording?.stop()
        activeRecording = null
    }
}