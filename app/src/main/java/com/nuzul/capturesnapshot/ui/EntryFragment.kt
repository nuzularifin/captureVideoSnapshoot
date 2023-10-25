package com.nuzul.capturesnapshot.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.nuzul.capturesnapshot.R
import com.nuzul.capturesnapshot.databinding.FragmentEntryBinding

class EntryFragment : Fragment() {

    companion object {
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
    }
}