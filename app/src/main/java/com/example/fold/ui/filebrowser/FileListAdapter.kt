package com.example.fold.ui.filebrowser

import android.graphics.Typeface
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.fold.data.model.FileItem

class FileListAdapter(
    private val onClick: (FileItem) -> Unit,
    private val onLongPress: (FileItem) -> Unit,
    private val isDark: Boolean
) : ListAdapter<FileItem, FileListAdapter.VH>(Diff) {

    init { setHasStableIds(true) }

    private object Diff : DiffUtil.ItemCallback<FileItem>() {
        override fun areItemsTheSame(a: FileItem, b: FileItem) = a.path == b.path
        override fun areContentsTheSame(a: FileItem, b: FileItem) = a == b
    }

    override fun getItemId(position: Int): Long = getItem(position).path.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val dp = ctx.resources.displayMetrics.density

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
            isClickable = true
            isFocusable = true
            // 禁用无障碍，消除每帧 28KB 事务
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        val icon = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams((22 * dp).toInt(), (22 * dp).toInt())
        }

        val textCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                marginStart = (12 * dp).toInt()
            }
        }

        val titleTv = TextView(ctx).apply {
            textSize = 15f
            maxLines = 1
            setTypeface(typeface, Typeface.BOLD)
        }

        val subtitleTv = TextView(ctx).apply {
            textSize = 12f
            maxLines = 1
        }

        textCol.addView(titleTv)
        textCol.addView(subtitleTv)
        row.addView(icon)
        row.addView(textCol)

        return VH(row, icon, titleTv, subtitleTv)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val file = getItem(position)
        val dp = holder.itemView.context.resources.displayMetrics.density

        holder.icon.setImageResource(getIconRes(file))
        holder.icon.setColorFilter(getIconColor(file))
        holder.titleTv.text = file.name
        holder.titleTv.setTextColor(if (isDark) 0xFFE0E0E0.toInt() else 0xFF1F1F1F.toInt())

        if (!file.isDirectory) {
            holder.subtitleTv.text = "${formatFileSize(file.size)}  ·  ${formatTimestamp(file.lastModifiedTimestamp)}"
            holder.subtitleTv.setTextColor(if (isDark) 0xFF9E9E9E.toInt() else 0xFF8A8A8A.toInt())
            holder.subtitleTv.visibility = android.view.View.VISIBLE
        } else {
            holder.subtitleTv.visibility = android.view.View.GONE
        }

        holder.itemView.setOnClickListener { onClick(file) }
        holder.itemView.setOnLongClickListener { onLongPress(file); true }
    }

    class VH(
        itemView: android.view.View,
        val icon: ImageView,
        val titleTv: TextView,
        val subtitleTv: TextView
    ) : RecyclerView.ViewHolder(itemView)

    companion object {
        fun getIconRes(file: FileItem): Int = when {
            file.isDirectory -> android.R.drawable.ic_menu_sort_by_size
            file.extension.equals("txt", true) -> android.R.drawable.ic_menu_edit
            file.extension.equals("epub", true) -> android.R.drawable.ic_menu_gallery
            file.extension.equals("pdf", true) -> android.R.drawable.ic_menu_agenda
            file.extension in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp") -> android.R.drawable.ic_menu_gallery
            file.extension in setOf("mp3", "wav", "flac", "aac", "ogg") -> android.R.drawable.ic_btn_speak_now
            file.extension in setOf("mp4", "mkv", "avi", "mov", "webm") -> android.R.drawable.ic_media_play
            file.extension in setOf("zip", "rar", "7z", "tar", "gz") -> android.R.drawable.ic_menu_save
            file.extension == "apk" -> android.R.drawable.ic_menu_add
            else -> android.R.drawable.ic_menu_save
        }

        fun getIconColor(file: FileItem): Int = when {
            file.isDirectory -> 0xFFFFB300.toInt() // gold
            file.extension.equals("txt", true) -> 0xFF4CAF50.toInt() // green
            file.extension.equals("epub", true) -> 0xFF9C27B0.toInt() // purple
            file.extension.equals("pdf", true) -> 0xFFF44336.toInt() // red
            file.extension in setOf("jpg", "jpeg", "png", "gif", "webp") -> 0xFF2196F3.toInt() // blue
            else -> 0xFF757575.toInt() // gray
        }
    }
}
