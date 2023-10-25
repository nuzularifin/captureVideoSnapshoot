package com.nuzul.capturesnapshot.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.nuzul.capturesnapshot.MainActivity
import com.nuzul.capturesnapshot.R
import com.nuzul.capturesnapshot.databinding.FragmentVideoCaptureSnapshotBinding
import com.nuzul.capturesnapshot.service.BackgroundRecordService
import com.nuzul.capturesnapshot.ui.adapter.CaptureImageAdapter
import com.nuzul.capturesnapshot.viewmodel.SharedViewModel
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


class VideoCaptureSnapshotFragment : Fragment(), BackgroundRecordService.DataListener {

    private lateinit var _binding: FragmentVideoCaptureSnapshotBinding

    private lateinit var cameraExecutor: ExecutorService

    private val sharedViewModel: SharedViewModel by activityViewModels()

    private val captureImageAdapter = CaptureImageAdapter()

    companion object {
        private val TAG: String = VideoCaptureSnapshotFragment::class.java.simpleName
        private const val MINUTE: Int = 60
        private const val HOUR: Int = MINUTE * 60

        fun newInstance() = VideoCaptureSnapshotFragment()
    }

    private var recordingService: BackgroundRecordService? = null

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

        sharedViewModel.isPermissionGranted.observe(viewLifecycleOwner) {
            if (!it) {
                if (activity is MainActivity) {
                    (activity as MainActivity).requestAllPermission()
                }
            }
        }

        _binding.btnGallery.setOnClickListener {

        }

        setupBackgroundRecordService()

        _binding.rvPhoto.apply {
            adapter = captureImageAdapter
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        }

        captureImageAdapter.setData(sharedViewModel.getDummyTotalData())

        _binding.btnTakePhoto.setOnClickListener {
            val position = checkingPosition()

            if (position != -1){
//                onCaptureScreenClick()
                _binding.ivTakePhoto.setImageBitmap(_binding.pvVideo.bitmap)

                showTakePhoto()
                val position = checkingPosition()
                captureImageAdapter.updateImageUriItem(position, _binding.pvVideo.bitmap)
                setToolbar(
                    title = captureImageAdapter.captureImages[position].title.orEmpty(),
                    totalData = captureImageAdapter.captureImages.size,
                    currentPosition = position + 1
                )
            } else {
                Toast.makeText(requireContext(), "Melebihi batas total pengambilan data gambar", Toast.LENGTH_SHORT).show()
            }
        }

        _binding.btnRetakePhoto.setOnClickListener {
            hideTakePhoto()
        }

        _binding.btnVideoRecord.setOnClickListener {
            onPausedRecordClick()
        }

        _binding.btnNext.setOnClickListener {
            hideTakePhoto()
            saveBitmapToFile(_binding.ivTakePhoto.drawable.toBitmap())
            if (checkingPosition() != -1){
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
    }

    private fun saveBitmapToFile(bitmap: Bitmap) {
        val fileFormat = SimpleDateFormat(BackgroundRecordService.FILENAME_FORMAT, Locale.getDefault()).format(System.currentTimeMillis()) +".jpg"
        val directory = Environment.getExternalStorageDirectory().absolutePath + "/" + fileFormat
        try {
            FileOutputStream(directory).use { out ->
                bitmap.compress(
                    Bitmap.CompressFormat.PNG,
                    100,
                    out
                ) // bmp is your Bitmap instance
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun setupBackgroundRecordService() {
        val serviceIntent = Intent(requireActivity(), BackgroundRecordService::class.java)
        serviceIntent.action = BackgroundRecordService.ACTION_START_WITH_PREVIEW
        requireActivity().startService(serviceIntent)
        requireActivity().bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun stopBackgroundRecordService() {
        val serviceIntent = Intent(context, BackgroundRecordService::class.java)
        requireActivity().stopService(serviceIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        stopBackgroundRecordService()
    }

    private val serviceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(p0: ComponentName?, service: IBinder?) {
            recordingService = (service as BackgroundRecordService.RecordingServiceBinder).getService()
            onServiceBound(recordingService)
        }

        override fun onServiceDisconnected(p0: ComponentName?) {
            Log.d(TAG, "onServiceDisconnected")
        }
    }

    private fun onServiceBound(recordingService: BackgroundRecordService?) {
        when(recordingService?.getRecordingState()){
            BackgroundRecordService.RecordingState.RECORDING -> {
                recordingService?.stopRecording()
            }
            BackgroundRecordService.RecordingState.STOPPED -> {
                _binding.tvTimer.text = "00:00:00"
            }
            else -> {}
        }
        recordingService?.addListener(this)
        recordingService?.bindPreviewUseCase(_binding.pvVideo.surfaceProvider)
    }

    override fun onNewData(duration: Int) {
        requireActivity().runOnUiThread {
            with(_binding){
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

    override fun onSuccessTakePhoto(imageFileUri: Uri?) {
        requireActivity().runOnUiThread {
            Glide.with(requireActivity()).load(imageFileUri).into(_binding.ivTakePhoto)
            showTakePhoto()
            val position = checkingPosition()
//            captureImageAdapter.updateImageUriItem(position, imageFileUri.toString())
            setToolbar(
                title = captureImageAdapter.captureImages[position].title.orEmpty(),
                totalData = captureImageAdapter.captureImages.size,
                currentPosition = position + 1
            )
        }
    }

    override fun onCameraOpened() {
    }

    override fun onRecordingEvent(it: VideoRecordEvent?) {
        when (it) {
            is VideoRecordEvent.Start -> {
            }

            is VideoRecordEvent.Finalize -> {
                Toast.makeText(requireContext(), "Finalize Recording", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun onPausedRecordClick(){
        when(recordingService?.getRecordingState()){
            BackgroundRecordService.RecordingState.RECORDING -> {
                _binding.tvTimer.text = "00:00:00"
                recordingService?.stopRecording()
            }
            BackgroundRecordService.RecordingState.STOPPED -> {
                recordingService?.captureVideo()
            }
            else -> {}
        }
    }

    private fun onCaptureScreenClick(){
        recordingService?.takePhoto()
    }

    override fun onStop() {
        super.onStop()
        if (recordingService?.getRecordingState() == BackgroundRecordService.RecordingState.STOPPED){
            recordingService?.let {
                ServiceCompat.stopForeground(it, ServiceCompat.STOP_FOREGROUND_REMOVE)
                recordingService?.stopSelf()
            }
        } else {
            recordingService?.startRunningInForeground()
        }
        recordingService?.unbindPreview()
        recordingService?.removeListener(this)
    }

    private fun showTakePhoto(){
        with(_binding){
            pvVideo.visibility = View.GONE
            clPhoto.visibility = View.VISIBLE
        }
    }

    private fun hideTakePhoto(){
        with(_binding){
            pvVideo.visibility = View.VISIBLE
            clPhoto.visibility = View.GONE
        }
    }

    private fun setToolbar(title: String, totalData: Int, currentPosition: Int){
        _binding.toolbar.apply {
            setTitle(title)
            setTitleTextColor(ContextCompat.getColor(requireContext(), R.color.white))
            _binding.tvTotalCountData.text = "$currentPosition/$totalData"
        }
    }

    private fun checkingPosition() : Int {
        return captureImageAdapter.captureImages.indexOfFirst { it.uriImage == null}
    }
}