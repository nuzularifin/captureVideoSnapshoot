package com.nuzul.capturesnapshot.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageCapture
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
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.abedelazizshe.lightcompressorlibrary.CompressionListener
import com.abedelazizshe.lightcompressorlibrary.VideoCompressor
import com.abedelazizshe.lightcompressorlibrary.VideoQuality
import com.abedelazizshe.lightcompressorlibrary.config.Configuration
import com.abedelazizshe.lightcompressorlibrary.config.SaveLocation
import com.abedelazizshe.lightcompressorlibrary.config.SharedStorageConfiguration
import com.daasuu.mp4compose.FillMode
import com.daasuu.mp4compose.VideoFormatMimeType
import com.daasuu.mp4compose.composer.Mp4Composer
import com.nuzul.capturesnapshot.MainActivity
import com.nuzul.capturesnapshot.R
import com.nuzul.capturesnapshot.databinding.FragmentVideoCaptureSnapshotBinding
import com.nuzul.capturesnapshot.extension.getSizeFileUri
import com.nuzul.capturesnapshot.extension.getVideoFromUri
import com.nuzul.capturesnapshot.extension.getVideoResolutionFromUri
import com.nuzul.capturesnapshot.ui.adapter.CaptureImageAdapter
import com.nuzul.capturesnapshot.viewmodel.SharedViewModel
import kotlinx.coroutines.launch
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
    private var videoFile: File? = null
    private var uris = mutableListOf<Uri>()

    lateinit var supportedQualities: List<Quality>
    lateinit var filteredQualities: List<Quality>
    enum class RecordingState {
        RECORDING, PAUSED, STOPPED
    }

    private val getContent = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            showEventStatusRecord(it)
            compressVideo(it)
            compressVideoLightCompressor(it)
        }
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

        private const val BIT_RATE = 500000

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
            getContent.launch("video/*")
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

    private fun setupQualityListView(){
        _binding.lvAccordingVideo.apply {
            adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1,
                filteredQualities.map { it.qualityToString() })
        }
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
//            val preferredQuality = Quality.HD
            val recorder = Recorder.Builder()
                .setTargetVideoEncodingBitRate(BIT_RATE)
                .setQualitySelector(
                    getQualitySelector()
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

                cameraInfo = cameraProvider?.bindToLifecycle(
                    this,
                    cameraSelector!!,
                    useCaseGroup
                )?.cameraInfo
                observeCameraState(cameraInfo)
                supportedQualities = QualitySelector.getSupportedQualities(cameraInfo!!)
                filteredQualities = arrayListOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD).filter { supportedQualities.contains(it) }

                setupQualityListView()
//                val supportedQualities = QualitySelector.getSupportedQualities(cameraInfo!!)
//                for (quality in supportedQualities) {
//                    when (quality) {
//                        Quality.UHD -> {
//                            //Add "Ultra High Definition (UHD) - 2160p" to the list
//                            listOfQualitiesToEnumerate.add("Ultra High Definition (UHD) - 2160p")
//                        }
//
//                        Quality.FHD -> {
//                            //Add "Full High Definition (FHD) - 1080p" to the list
//                            listOfQualitiesToEnumerate.add("Full High Definition (FHD) - 1080p")
//                        }
//
//                        Quality.HD -> {
//                            //Add "High Definition (HD) - 720p" to the list
//                            listOfQualitiesToEnumerate.add("High Definition (HD) - 720p")
//                        }
//
//                        Quality.SD -> {
//                            //Add "Standard Definition (SD) - 480p" to the list
//                            listOfQualitiesToEnumerate.add("Standard Definition (SD) - 480p")
//                        }
//                    }
//                }
//                startVideo()
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun observeCameraState(
        cameraInfo: CameraInfo?,
    ) {
        cameraInfo?.cameraState?.observe(viewLifecycleOwner) { cameraState ->
            run {
                when (cameraState.type) {
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
                when (error.code) {
                    // Open errors
                    CameraState.ERROR_STREAM_CONFIG -> {
                        // Make sure to setup the use cases properly
                        Toast.makeText(
                            context,
                            "Stream config error. Restart application",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    // Opening errors
                    CameraState.ERROR_CAMERA_IN_USE -> {
                        // Close the camera or ask user to close another camera app that's using the
                        // camera
                        Toast.makeText(
                            context,
                            "Camera in use. Close any apps that are using the camera",
                            Toast.LENGTH_SHORT
                        ).show()
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
                        Toast.makeText(context, "Do not disturb mode enabled", Toast.LENGTH_SHORT)
                            .show()
                    }
                }
            }
        }
    }

    private fun showEventStatusRecord(outputUri: Uri) {
        val msg = "Video capture succeeded: $outputUri"
        Log.d(TAG, msg)
        val fileSize = "Video capture succeeded: ${getSizeFileUri(requireContext(), outputUri)} mb"
        Log.d(TAG, fileSize)
        _binding.tvCapacity.text = fileSize
        val data: Pair<Int, Int> =
            getVideoResolutionFromUri(requireContext(), outputUri) ?: Pair(854, 400)
        Log.d(TAG, "resolution : ${data.first} || ${data.second}} ")
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
//                if (PermissionChecker.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PermissionChecker.PERMISSION_GRANTED) {
//                    withAudioEnabled()
//                }
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
                            showEventStatusRecord(recordEvent.outputResults.outputUri)
                            compressVideo(recordEvent.outputResults.outputUri)
                            compressVideoLightCompressor(recordEvent.outputResults.outputUri)
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

    private fun getQualitySelector() = QualitySelector.fromOrderedList(
        listOf(Quality.SD),
        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
    )

    private fun startTrackingTime() {
        timerTask = object : TimerTask() {
            override fun run() {
                if (recordingState == RecordingState.RECORDING) {
                    duration += 1
                    onNewData(duration)
                }
            }
        }
        timer.scheduleAtFixedRate(timerTask, 1000, 1000)
    }

    private fun stopRecording() {
        activeRecording?.stop()
        activeRecording = null
    }

    private fun compressVideo(outputUri: Uri) {
        videoFile = requireContext().getVideoFromUri(outputUri)
        val vidRes =
            videoFile?.path?.toUri()?.let { getVideoResolutionFromUri(requireContext(), it) }
                ?: Pair(854, 480)
        videoFile?.let { videoFile ->
            val dirPathCompressed = requireContext().externalMediaDirs[0].absolutePath + "/media"
            if (!File(dirPathCompressed).exists()) File(dirPathCompressed).mkdir()
            val outputPath = dirPathCompressed + "/mp4Composer_${videoFile.name}"

            val width = if (vidRes.first > vidRes.second) 320 else 240
            val height = if (vidRes.first > vidRes.second) 240 else 320

            Mp4Composer(videoFile.path, outputPath)
                .size(width, height)
                .fillMode(FillMode.PRESERVE_ASPECT_FIT)
                .videoFormatMimeType(VideoFormatMimeType.AVC)
                .videoBitrate(BIT_RATE)
                .listener(object : Mp4Composer.Listener {
                    override fun onProgress(progress: Double) {
                        _binding.pbMp4compress.progress = (progress * 100).toInt()
                    }

                    override fun onCurrentWrittenVideoTime(timeUs: Long) {

                    }

                    override fun onCompleted() {
                        Log.d(TAG, "onCompleted: CompressVideo with mp4Composer")
                        val compressedVideoFile = File(outputPath)
                        val compressedVideoUri = Uri.fromFile(compressedVideoFile)

                        MediaScannerConnection.scanFile(
                            requireContext(),
                            arrayOf(compressedVideoUri.path, videoFile.path),
                            null
                        ) { _, _ -> }
//                        videoFile.delete()

//                        setResult(
//                            Activity.RESULT_OK,
//                            Intent().setData(compressedVideoUri)
//                        )
//                        finish()
                    }

                    override fun onCanceled() {
                        Log.d(TAG, "onCanceled: Video Compression canceled")
                    }

                    override fun onFailed(exception: Exception) {
                        Log.d(TAG, "onCanceled: Video Compression failed : ${exception.message}")
                    }
                })
                .start()
        } ?: run {
            Toast.makeText(requireContext(), "Video File Not Found", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun compressVideoLightCompressor(outputUri: Uri) {
        uris.clear()
        uris.add(outputUri)
        lifecycleScope.launch {
            VideoCompressor.start(
                context = requireContext(), // => This is required
                uris = uris,
                isStreamable = false,
                // THIS STORAGE
                sharedStorageConfiguration = SharedStorageConfiguration(
                    saveAt = SaveLocation.movies, // => default is movies
                    subFolderName = "LightCompress-videos" // => optional
                ),
                configureWith = Configuration(
                    videoNames = uris.map { uri -> uri.pathSegments.last() },
                    quality = VideoQuality.VERY_LOW,
                    isMinBitrateCheckEnabled = false,
//                    videoBitrateInMbps = 5, /*Int, ignore, or null*/
                    disableAudio = false, /*Boolean, or ignore*/
                    keepOriginalResolution = false, /*Boolean, or ignore*/
                    videoWidth = 240.0, /*Double, ignore, or null*/
                    videoHeight = 320.0 /*Double, ignore, or null*/
                ),
                listener = object : CompressionListener {
                    override fun onProgress(index: Int, percent: Float) {
                        // Update UI with progress value
                        requireActivity().runOnUiThread {
                            _binding.pbLightCompress.progress = percent.toInt()
                            Log.d(TAG, "onProgress: Light Compression status : ${percent.toInt()} %")
                        }
                    }

                    override fun onStart(index: Int) {
                        // Compression start
                        Log.d(TAG, "onStart Compress video: $index")
                    }

                    override fun onSuccess(index: Int, size: Long, path: String?) {
                        // On Compression success
                        Log.d(TAG, "onSuccess: $index, $path")
                    }

                    override fun onFailure(index: Int, failureMessage: String) {
                        Log.d(TAG, "onFailure: $index, $failureMessage")
                    }

                    override fun onCancelled(index: Int) {
                        // On Cancelled

                    }

                }
            )
        }
    }

    fun Quality.qualityToString(): String {
        return when (this) {
            Quality.UHD -> "UHD"
            Quality.FHD -> "FHD"
            Quality.HD -> "HD"
            Quality.SD -> "SD"
            else -> throw IllegalArgumentException()
        }
    }
}