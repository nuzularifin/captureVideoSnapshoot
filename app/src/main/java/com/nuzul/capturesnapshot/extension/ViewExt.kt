package com.nuzul.capturesnapshot.extension

import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.media.Image
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.viewbinding.ViewBinding
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt


inline fun <T : ViewBinding> AppCompatActivity.viewBinding(
    crossinline bindingInflater: (LayoutInflater) -> T) =
    lazy(LazyThreadSafetyMode.NONE) {
        bindingInflater.invoke(layoutInflater)
    }

fun Image.toBitmap(): Bitmap {
    val buffer = planes[0].buffer
    buffer.rewind()
    val bytes = ByteArray(buffer.capacity())
    buffer.get(bytes)
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}

fun Bitmap.rotate(degrees: Float): Bitmap =
    Bitmap.createBitmap(this, 0, 0, width, height, Matrix().apply { postRotate(degrees) }, true)

fun ByteBuffer.toByteArray(): ByteArray {
    rewind()    // Rewind the buffer to zero
    val data = ByteArray(remaining())
    get(data)   // Copy the buffer into a byte array
    return data // Return the byte array
}

fun createFile(baseFolder: File, format: String, extension: String) =
    File(baseFolder, SimpleDateFormat(format, Locale.US)
        .format(System.currentTimeMillis()) + extension)

fun getResolutions(selector: CameraSelector,
                   provider: ProcessCameraProvider
): Map<Quality, Size> {
    return selector.filter(provider.availableCameraInfos).firstOrNull()
        ?.let { camInfo ->
            QualitySelector.getSupportedQualities(camInfo).associateWith { quality ->
                    QualitySelector.getResolution(camInfo, quality)!!
                }
        } ?: emptyMap()
}

@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
fun isBackCameraLevel3Device(cameraProvider: ProcessCameraProvider) : Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        return CameraSelector.DEFAULT_BACK_CAMERA
            .filter(cameraProvider.availableCameraInfos)
            .firstOrNull()
            ?.let { Camera2CameraInfo.from(it) }
            ?.getCameraCharacteristic(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ==
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3
    }
    return false
}

fun getFormattedVideoSizeInMB(videoFilePath: String): Double {
    val videoFile = File(videoFilePath)
    if (!videoFile.exists()) {
        return 0.00
    }
    val sizeInBytes = videoFile.length()
    val sizeInMB = sizeInBytes.toDouble() / (1024 * 1024) // Convert bytes to MB
    return (sizeInMB * 10.0).roundToInt() / 10.0
}

fun getRealSizeFromUri(context: Context, uri: Uri): String? {
    var cursor: Cursor? = null
    return try {
        val proj = arrayOf(MediaStore.Audio.Media.SIZE)
        cursor = context.contentResolver.query(uri, proj, null, null, null)
        val column_index = cursor!!.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
        cursor.moveToFirst()
        cursor.getString(column_index)
    } finally {
        cursor?.close()
    }
}

fun getPath(context: Context, uri: Uri): String? {
    val projection = arrayOf(MediaStore.Images.Media.DATA)
    val cursor: Cursor =
        context.contentResolver.query(uri, projection, null, null, null)
            ?: return null
    val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
    cursor.moveToFirst()
    val s = cursor.getString(columnIndex)
    cursor.close()
    return s
}

fun Context.getVideoFromUri(videoUri: Uri): File? {
    if (videoUri.scheme == "file") {
        return File(videoUri.path ?: "")
    }

    val contentResolver = this.contentResolver
    val projection = arrayOf(MediaStore.Video.Media.DATA)
    val cursor = contentResolver.query(videoUri, projection, null, null, null)

    cursor?.use { it->
        if (it.moveToFirst()) {
            val columnIndex = it.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            val filePath = it.getString(columnIndex)
            return File(filePath)
        }
    }

    return null
}

fun getSizeFileUri(context: Context, uri: Uri): String {
    val fileSize = File(getPath(context, uri)).length()
    val sizeInMb = fileSize / (1024.0 * 1024)
    return "%.2f".format(sizeInMb)
}

fun getVideoResolutionFromUri(context: Context, videoUri: Uri): Pair<Int, Int>? {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, videoUri)

        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt()
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt()
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt()

        if (width != null && height != null) {
            // Adjust width and height based on rotation
            return if (rotation == 90 || rotation == 270) {
                Pair(height, width) // Swap width and height for portrait videos
            } else {
                Pair(width, height)
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    } finally {
        retriever.release()
    }
    return null
}

fun getVideoBitrate(context: Context, uri: Uri): String { //kbps
    val retriever = MediaMetadataRetriever()

    try {
        retriever.setDataSource(context, uri)
        val bitrateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
        val bitrate = bitrateStr?.toIntOrNull() ?: 0
        return "${bitrate / 1000} kbps"
    } catch (e: Exception) {
        e.printStackTrace()
    } finally {
        retriever.release()
    }

    return ""
}