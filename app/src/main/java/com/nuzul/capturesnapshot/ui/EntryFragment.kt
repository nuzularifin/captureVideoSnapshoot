package com.nuzul.capturesnapshot.ui

import android.annotation.SuppressLint
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.abedelazizshe.lightcompressorlibrary.CompressionListener
import com.abedelazizshe.lightcompressorlibrary.VideoCompressor
import com.abedelazizshe.lightcompressorlibrary.VideoQuality
import com.abedelazizshe.lightcompressorlibrary.compressor.Compressor.compressVideo
import com.abedelazizshe.lightcompressorlibrary.config.Configuration
import com.abedelazizshe.lightcompressorlibrary.config.SaveLocation
import com.abedelazizshe.lightcompressorlibrary.config.SharedStorageConfiguration
import com.daasuu.mp4compose.FillMode
import com.daasuu.mp4compose.VideoFormatMimeType
import com.daasuu.mp4compose.composer.Mp4Composer
import com.nuzul.capturesnapshot.R
import com.nuzul.capturesnapshot.databinding.FragmentEntryBinding
import com.nuzul.capturesnapshot.extension.getVideoFromUri
import com.nuzul.capturesnapshot.extension.getVideoResolutionFromUri
import kotlinx.coroutines.launch
import java.io.File

class EntryFragment : Fragment() {

    private var videoFile: File? = null
    private val BIT_RATE = 500000
    private var uris = mutableListOf<Uri>()

    companion object {

        private const val TAG = "EntryFragment"
        fun newInstance() = EntryFragment()
    }

    private lateinit var _binding: FragmentEntryBinding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentEntryBinding.inflate(inflater, container, false)
        return _binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding.videoButton.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, VideoFragment.newInstance())
                .addToBackStack(null)
                .commit()
        }

        _binding.photoButton.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, MainFragment.newInstance())
                .addToBackStack(null)
                .commit()
        }

        _binding.imagePickerButton.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, PhotoPickerFragment.newInstance())
                .addToBackStack(null)
                .commit()
        }

        _binding.imageCaptureSnapshotButton.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, VideoCaptureSnapshotFragment.newInstance())
                .addToBackStack(null)
                .commit()
        }

        setupCompressView()
    }

    private fun setupCompressView() {
        with(_binding){
            btnCompressWithFfmpeg.setOnClickListener {
                pickVideo()
            }

            btnCompressWithLigth.setOnClickListener {
                pickVideo()
            }

            btnCompressWithMp4.setOnClickListener {
                pickVideoMp4()
            }
        }
    }

    private fun pickVideo() {
        compressLightLauncher.launch("video/*")
    }

    private fun pickVideoMp4(){
        compressMp4Launcher.launch("video/*")
    }

    private val compressLightLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            showEventStatusRecord(it)
            compressVideoLightCompressor(it)
        }
    }

    private val compressMp4Launcher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            compressVideoMp4(it)
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
                            _binding.pbLight.progress = percent.toInt()
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

    private fun compressVideoMp4(uri: Uri) {
        try {
            videoFile = requireContext().getVideoFromUri(uri)
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
                            _binding.pbMp4.progress = (progress * 100).toInt()
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
        } catch (e: Exception){
            Log.e(TAG, "compressVideoMp4: ${e.printStackTrace()}", )
        }
    }

    private fun showEventStatusRecord(uri: Uri) {

    }


}