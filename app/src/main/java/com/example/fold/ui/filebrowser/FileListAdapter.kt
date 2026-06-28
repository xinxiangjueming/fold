package com.example.fold.ui.filebrowser

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.fold.R
import com.example.fold.data.model.FileItem
import java.io.File

class FileListAdapter(
    private val onClick: (FileItem) -> Unit,
    private val onLongPress: (FileItem) -> Unit,
    var isDark: Boolean
) : ListAdapter<FileItem, FileListAdapter.VH>(Diff) {

    init { setHasStableIds(true) }

    private object Diff : DiffUtil.ItemCallback<FileItem>() {
        override fun areItemsTheSame(a: FileItem, b: FileItem) = a.path == b.path
        override fun areContentsTheSame(a: FileItem, b: FileItem) = a == b
    }

    override fun getItemId(position: Int): Long = getItem(position).path.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val file = getItem(position)
        holder.bind(file, isDark, onClick, onLongPress)
    }

    override fun onViewRecycled(holder: VH) {
        super.onViewRecycled(holder)
        holder.recycle()
    }

    fun updateTheme(newIsDark: Boolean) {
        if (isDark != newIsDark) {
            isDark = newIsDark
            val rv = recyclerView ?: return
            val first = rv.getChildAdapterPosition(rv.getChildAt(0))
            val last = rv.getChildAdapterPosition(rv.getChildAt(rv.childCount - 1))
            if (first >= 0 && last >= 0) {
                notifyItemRangeChanged(first, last - first + 1, PAYLOAD_THEME)
            }
        }
    }

    private var recyclerView: RecyclerView? = null

    override fun onAttachedToRecyclerView(rv: RecyclerView) {
        super.onAttachedToRecyclerView(rv)
        recyclerView = rv
    }

    override fun onDetachedFromRecyclerView(rv: RecyclerView) {
        super.onDetachedFromRecyclerView(rv)
        recyclerView = null
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.icon)
        private val iconBackground: View = itemView.findViewById(R.id.icon_background)
        private val thumbnail: ImageView = itemView.findViewById(R.id.thumbnail)
        private val title: TextView = itemView.findViewById(R.id.title)
        private val subtitle: TextView = itemView.findViewById(R.id.subtitle)
        private val arrow: ImageView = itemView.findViewById(R.id.arrow)
        private var currentPath: String? = null

        fun bind(file: FileItem, isDark: Boolean, onClick: (FileItem) -> Unit, onLongPress: (FileItem) -> Unit) {
            currentPath = file.path

            val hasThumb = hasThumbnail(file)

            // Set icon background and visibility
            if (hasThumb) {
                // Hide circular background and icon for thumbnails
                iconBackground.visibility = View.GONE
                icon.visibility = View.GONE
                thumbnail.visibility = View.VISIBLE

                // Set rounded background for thumbnail
                val bgDrawable = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 8f * itemView.context.resources.displayMetrics.density
                    setColor(if (isDark) 0xFF2A2A2A.toInt() else 0xFFF5F5F5.toInt())
                }
                thumbnail.background = bgDrawable

                // Load thumbnail
                ThumbnailLoader.loadThumbnail(thumbnail, File(file.path), isDark)
            } else {
                // Show circular background and icon
                iconBackground.visibility = View.VISIBLE
                icon.visibility = View.VISIBLE
                thumbnail.visibility = View.GONE

                // Set circular background color
                val bgColor = getIconBackgroundColor(file, isDark)
                val bgDrawable = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(bgColor)
                }
                iconBackground.background = bgDrawable

                // Set icon
                ThumbnailLoader.cancelLoad(thumbnail)
                icon.setImageResource(getIconRes(file))
            }

            // Set title
            title.text = file.name
            val titleColor = if (isDark) 0xFFE0E0E0.toInt() else 0xFF1F1F1F.toInt()
            title.setTextColor(titleColor)

            // Set subtitle
            if (!file.isDirectory) {
                subtitle.text = "${formatFileSize(file.size)}  ·  ${formatTimestamp(file.lastModifiedTimestamp)}"
                val subtitleColor = if (isDark) 0xFF9E9E9E.toInt() else 0xFF8A8A8A.toInt()
                subtitle.setTextColor(subtitleColor)
                subtitle.visibility = View.VISIBLE
            } else {
                subtitle.visibility = View.GONE
            }

            // Show arrow for directories
            arrow.visibility = if (file.isDirectory) View.VISIBLE else View.GONE

            // Click handlers
            itemView.setOnClickListener { onClick(file) }
            itemView.setOnLongClickListener { onLongPress(file); true }
        }

        fun recycle() {
            ThumbnailLoader.cancelLoad(thumbnail)
            currentPath = null
        }

        companion object {
            fun hasThumbnail(file: FileItem): Boolean {
                val ext = file.extension.lowercase()
                return ext in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "mp4", "mkv", "avi", "mov", "webm", "pdf", "apk")
            }

            fun getIconRes(file: FileItem): Int = when {
                file.isDirectory -> R.drawable.ic_file_folder
                file.extension.equals("txt", true) -> R.drawable.ic_file_text
                file.extension.equals("epub", true) -> R.drawable.ic_file_epub
                file.extension.equals("pdf", true) -> R.drawable.ic_file_pdf
                file.extension.lowercase() in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp") -> R.drawable.ic_file_image
                file.extension.lowercase() in setOf("mp3", "wav", "flac", "aac", "ogg") -> R.drawable.ic_file_audio
                file.extension.lowercase() in setOf("mp4", "mkv", "avi", "mov", "webm") -> R.drawable.ic_file_video
                file.extension.lowercase() in setOf("zip", "rar", "7z", "tar", "gz") -> R.drawable.ic_file_archive
                else -> R.drawable.ic_file_generic
            }

            fun getIconBackgroundColor(file: FileItem, isDark: Boolean): Int = when {
                file.isDirectory -> if (isDark) 0x33FFB300 else 0x1AFFB300
                file.extension.equals("txt", true) -> if (isDark) 0x334CAF50 else 0x1A4CAF50
                file.extension.equals("epub", true) -> if (isDark) 0x339C27B0 else 0x1A9C27B0
                file.extension.equals("pdf", true) -> if (isDark) 0x33F44336 else 0x1AF44336
                file.extension.lowercase() in setOf("jpg", "jpeg", "png", "gif", "webp") -> if (isDark) 0x332196F3 else 0x1A2196F3
                file.extension.lowercase() in setOf("mp3", "wav", "flac", "aac", "ogg") -> if (isDark) 0x33FF9800 else 0x1AFF9800
                file.extension.lowercase() in setOf("mp4", "mkv", "avi", "mov", "webm") -> if (isDark) 0x33F44336 else 0x1AF44336
                file.extension.lowercase() in setOf("zip", "rar", "7z", "tar", "gz") -> if (isDark) 0x33795548 else 0x1A795548
                else -> if (isDark) 0x33607D8B else 0x1A607D8B
            }
        }
    }

    companion object {
        const val PAYLOAD_THEME = 1
    }
}
