package com.nuzul.capturesnapshot.ui.adapter

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.nuzul.capturesnapshot.databinding.ItemCaptureImageBinding

class CaptureImageAdapter : RecyclerView.Adapter<CaptureImageAdapter.CaptureImageViewHolder>() {

    var captureImages = mutableListOf<CaptureImage>()

    var OnUpdateToolbarAndData: (()-> Unit)? = null

    inner class CaptureImageViewHolder(
        private val binding: ItemCaptureImageBinding
    ) : RecyclerView.ViewHolder(binding.root){

        fun onBind(captureImage: CaptureImage) {
            with(binding){
                if (captureImage.uriImage == null){
                    tvTitle.visibility = View.GONE
                } else {
                    tvTitle.visibility = View.VISIBLE
                    tvTitle.text = captureImage.title
                    Glide.with(binding.root.context).load(captureImage.uriImage).into(imgPhoto)
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CaptureImageViewHolder {
        val view = ItemCaptureImageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return CaptureImageViewHolder(view)
    }

    override fun getItemCount(): Int = captureImages.size

    override fun onBindViewHolder(holder: CaptureImageViewHolder, position: Int) {
        holder.onBind(captureImages[position])
    }

    fun setData(datas: List<CaptureImage>){
        captureImages.clear()
        captureImages.addAll(datas)
        notifyDataSetChanged()
    }

    fun updateImageUriItem(position: Int, bitmap: Bitmap?){
        captureImages[position].uriImage = bitmap
        notifyItemChanged(position)
    }
}

data class CaptureImage(
    var title: String?,
    var description: String?,
    var uriImage: Bitmap? = null,
    var type: String?,
)