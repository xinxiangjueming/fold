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
    var isDark: Boolean,
    var selectionMode: Boolean = false,
    var selectedFiles: Set<String> = emptySet(),
    var onToggleSelection: ((FileItem) -> Unit)? = null,
    var viewMode: ViewMode = ViewMode.LIST
) : ListAdapter<FileItem, RecyclerView.ViewHolder>(Diff) {

    init { setHasStableIds(true) }

    private object Diff : DiffUtil.ItemCallback<FileItem>() {
        override fun areItemsTheSame(a: FileItem, b: FileItem) = a.path == b.path
        override fun areContentsTheSame(a: FileItem, b: FileItem) = a == b
    }

    override fun getItemId(position: Int): Long = getItem(position).path.hashCode().toLong()

    override fun getItemViewType(position: Int): Int = if (viewMode == ViewMode.GRID) TYPE_GRID else TYPE_LIST

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_GRID) {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_file_grid, parent, false)
            GridVH(view)
        } else {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
            VH(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val file = getItem(position)
        val isSelected = selectedFiles.contains(file.path)
        when (holder) {
            is VH -> holder.bind(file, isDark, isSelected, selectionMode, onClick, onLongPress, onToggleSelection)
            is GridVH -> holder.bind(file, isDark, isSelected, selectionMode, onClick, onLongPress, onToggleSelection)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        when (holder) {
            is VH -> holder.recycle()
            is GridVH -> holder.recycle()
        }
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

    fun forceRefreshSelection() {
        val rv = recyclerView
        android.util.Log.d("FileListAdapter", "forceRefreshSelection: rv=${rv != null}, childCount=${rv?.childCount ?: 0}")
        if (rv == null) return
        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i)
            val pos = rv.getChildAdapterPosition(child)
            if (pos >= 0 && pos < currentList.size) {
                val holder = rv.getChildViewHolder(child)
                if (holder is VH) {
                    val file = currentList[pos]
                    val isSelected = selectedFiles.contains(file.path)
                    android.util.Log.d("FileListAdapter", "  bind[$i]: pos=$pos, file=${file.name}, selected=$isSelected, selectionMode=$selectionMode")
                    holder.bind(file, isDark, isSelected, selectionMode, onClick, onLongPress, onToggleSelection)
                }
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

    class GridVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.icon)
        private val iconBackground: View = itemView.findViewById(R.id.icon_background)
        private val thumbnail: ImageView = itemView.findViewById(R.id.thumbnail)
        private val title: TextView = itemView.findViewById(R.id.title)
        private val selectionIcon: ImageView? = itemView.findViewById(R.id.selection_icon)

        fun bind(file: FileItem, isDark: Boolean, isSelected: Boolean, selectionMode: Boolean,
                 onClick: (FileItem) -> Unit, onLongPress: (FileItem) -> Unit,
                 onToggleSelection: ((FileItem) -> Unit)?) {
            val hasThumb = VH.hasThumbnail(file)

            if (hasThumb) {
                iconBackground.visibility = View.GONE
                icon.visibility = View.GONE
                thumbnail.visibility = View.VISIBLE
                val isApk = file.extension.equals("apk", ignoreCase = true)
                if (!isApk) {
                    val bgDrawable = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 12f * itemView.context.resources.displayMetrics.density
                        setColor(if (isDark) 0xFF2A2A2A.toInt() else 0xFFF5F5F5.toInt())
                    }
                    thumbnail.background = bgDrawable
                } else {
                    thumbnail.background = null
                }
                ThumbnailLoader.loadThumbnail(thumbnail, File(file.path), isDark)
            } else {
                iconBackground.visibility = View.VISIBLE
                icon.visibility = View.VISIBLE
                thumbnail.visibility = View.GONE
                val bgColor = VH.getIconBackgroundColor(file, isDark)
                val bgDrawable = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(bgColor)
                }
                iconBackground.background = bgDrawable
                ThumbnailLoader.cancelLoad(thumbnail)
                icon.setImageResource(VH.getIconRes(file))
            }

            title.text = file.name
            val titleColor = if (isDark) 0xFFE0E0E0.toInt() else 0xFF1F1F1F.toInt()
            title.setTextColor(titleColor)

            if (selectionMode) {
                android.util.Log.d("FileListAdapter", "  selectionIcon=${selectionIcon != null}, setting VISIBLE")
                selectionIcon?.visibility = View.VISIBLE
                selectionIcon?.setImageResource(if (isSelected) R.drawable.ic_check_circle_filled else R.drawable.ic_check_circle_outline)
                selectionIcon?.setColorFilter(if (isSelected) 0xFF2196F3.toInt() else if (isDark) 0xFF666666.toInt() else 0xFFAAAAAA.toInt())
                itemView.alpha = if (isSelected) 0.85f else 1.0f
                if (isSelected) {
                    val radius = 14f * itemView.context.resources.displayMetrics.density
                    itemView.background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = radius
                        setColor(if (isDark) 0x402196F3.toInt() else 0x202196F3.toInt())
                    }
                } else {
                    itemView.background = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
                }
            } else {
                selectionIcon?.visibility = View.GONE
                itemView.alpha = 1.0f
                itemView.background = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            }

            itemView.setOnClickListener {
                if (selectionMode) onToggleSelection?.invoke(file) else onClick(file)
            }
            itemView.setOnLongClickListener {
                if (selectionMode) onToggleSelection?.invoke(file) else onLongPress(file)
                true
            }
        }

        fun recycle() {
            val thumbnail = itemView.findViewById<ImageView>(R.id.thumbnail)
            ThumbnailLoader.cancelLoad(thumbnail)
        }
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.icon)
        private val iconBackground: View = itemView.findViewById(R.id.icon_background)
        private val thumbnail: ImageView = itemView.findViewById(R.id.thumbnail)
        private val title: TextView = itemView.findViewById(R.id.title)
        private val subtitle: TextView = itemView.findViewById(R.id.subtitle)
        private val arrow: ImageView = itemView.findViewById(R.id.arrow)
        private var currentPath: String? = null

        fun bind(file: FileItem, isDark: Boolean, isSelected: Boolean, selectionMode: Boolean,
                 onClick: (FileItem) -> Unit, onLongPress: (FileItem) -> Unit,
                 onToggleSelection: ((FileItem) -> Unit)?) {
            currentPath = file.path

            val hasThumb = hasThumbnail(file)

            if (hasThumb) {
                iconBackground.visibility = View.GONE
                icon.visibility = View.GONE
                thumbnail.visibility = View.VISIBLE
                val isApk = file.extension.equals("apk", ignoreCase = true)
                if (!isApk) {
                    val bgDrawable = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 8f * itemView.context.resources.displayMetrics.density
                        setColor(if (isDark) 0xFF2A2A2A.toInt() else 0xFFF5F5F5.toInt())
                    }
                    thumbnail.background = bgDrawable
                } else {
                    thumbnail.background = null
                }
                ThumbnailLoader.loadThumbnail(thumbnail, File(file.path), isDark, apkIconScale = 0.8f)
            } else {
                iconBackground.visibility = View.VISIBLE
                icon.visibility = View.VISIBLE
                thumbnail.visibility = View.GONE
                val bgColor = getIconBackgroundColor(file, isDark)
                val bgDrawable = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(bgColor)
                }
                iconBackground.background = bgDrawable
                ThumbnailLoader.cancelLoad(thumbnail)
                icon.setImageResource(getIconRes(file))
            }

            title.text = file.name
            val titleColor = if (isDark) 0xFFE0E0E0.toInt() else 0xFF1F1F1F.toInt()
            title.setTextColor(titleColor)

            if (!file.isDirectory) {
                subtitle.text = "${formatFileSizeCompat(file.size)}  ·  ${formatTimestamp(file.lastModifiedTimestamp)}"
                val subtitleColor = if (isDark) 0xFF9E9E9E.toInt() else 0xFF8A8A8A.toInt()
                subtitle.setTextColor(subtitleColor)
                subtitle.visibility = View.VISIBLE
            } else {
                subtitle.visibility = View.GONE
            }

            arrow.visibility = if (file.isDirectory && !selectionMode) View.VISIBLE else View.GONE

            if (selectionMode) {
                itemView.alpha = if (isSelected) 0.85f else 1.0f
                if (isSelected) {
                    val radius = 16f * itemView.context.resources.displayMetrics.density
                    itemView.background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = radius
                        setColor(if (isDark) 0x402196F3.toInt() else 0x202196F3.toInt())
                    }
                } else {
                    itemView.background = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
                }
            } else {
                itemView.alpha = 1.0f
                itemView.background = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            }

            itemView.setOnClickListener {
                if (selectionMode) {
                    onToggleSelection?.invoke(file)
                } else {
                    onClick(file)
                }
            }
            itemView.setOnLongClickListener {
                if (selectionMode) {
                    onToggleSelection?.invoke(file)
                } else {
                    onLongPress(file)
                }
                true
            }
        }

        fun recycle() {
            ThumbnailLoader.cancelLoad(thumbnail)
            currentPath = null
        }

        companion object {
            private fun formatFileSizeCompat(bytes: Long): String {
                if (bytes < 1024) return "$bytes B"
                val kb = bytes / 1024.0
                if (kb < 1024) return "%.1f KB".format(kb)
                val mb = kb / 1024.0
                if (mb < 1024) return "%.1f MB".format(mb)
                val gb = mb / 1024.0
                return "%.1f GB".format(gb)
            }

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
        const val TYPE_LIST = 0
        const val TYPE_GRID = 1
    }
}
