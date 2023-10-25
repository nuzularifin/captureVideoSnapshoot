package com.nuzul.capturesnapshot.ui

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.MediaController
import androidx.fragment.app.Fragment
import com.nuzul.capturesnapshot.databinding.FragmentVideoViewBinding

class VideoViewFragment(
    private val videoUri: Uri
) : Fragment() {

    companion object {
        fun newInstance(outputUri: Uri) = VideoViewFragment(outputUri)
    }

    private lateinit var _binding: FragmentVideoViewBinding

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVideoViewBinding.inflate(inflater, container, false)
        return _binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        _binding.videoView.setVideoURI(videoUri)
        _binding.videoView.start()

        val mediaController = MediaController(context)
        _binding.videoView.setMediaController(mediaController)
        mediaController.setAnchorView(_binding.frameLayout)
    }
}