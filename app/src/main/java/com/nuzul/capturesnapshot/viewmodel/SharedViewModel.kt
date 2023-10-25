package com.nuzul.capturesnapshot.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.nuzul.capturesnapshot.ui.adapter.CaptureImage

class SharedViewModel: ViewModel() {
    private val _isPermissionGranted = MutableLiveData(false)
    val isPermissionGranted: LiveData<Boolean> = _isPermissionGranted

    fun setPermission(isGranted: Boolean) {
        _isPermissionGranted.value = isGranted
    }

    fun getDummyTotalData() : List<CaptureImage>{
        return listOf(
            CaptureImage(
                title = "Foto Depan Serong Kanan",
                description = "Poin Selanjutnya : Foto Depan",
                type = "photo-only"
            ),
            CaptureImage(
                title = "Foto Depan Serong Kiri",
                description = "Poin Selanjutnya : Foto Depan Tengah",
                type = "photo-with-condition"
            )
        )
    }

}