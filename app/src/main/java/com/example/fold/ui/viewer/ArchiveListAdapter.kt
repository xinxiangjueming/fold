package com.example.fold.ui.viewer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.fold.R
import com.example.fold.data.archive.ArchiveEntry

class ArchiveListAdapter : ListAdapter<ArchiveEntry, ArchiveListAdapter.VH>(Diff) {

    init { setHasStableIds(true) }

    private object Diff : DiffUtil.ItemCallback<ArchiveEntry>() {
        override fun areItemsTheSame(a: ArchiveEntry, b: ArchiveEntry) = a.name == b.name
        override fun areContentsTheSame(a: ArchiveEntry, b: ArchiveEntry) = a == b
    }

    override fun getItemId(position: Int): Long {
        val item = getItem(position)
        return item.name.hashCode().toLong()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_archive_entry, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.icon)
        private val name: TextView = itemView.findViewById(R.id.name)
        private val size: TextView = itemView.findViewById(R.id.size)

        fun bind(entry: ArchiveEntry) {
            val isDark = (itemView.context.resources.configuration.uiMode and 
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) == 
                android.content.res.Configuration.UI_MODE_NIGHT_YES

            // Set icon
            icon.setImageResource(if (entry.isDirectory) R.drawable.ic_file_folder else R.drawable.ic_file_generic)
            icon.setColorFilter(
                if (entry.isDirectory) if (isDark) 0xFFFFB300.toInt() else 0xFFFFB300.toInt()
                else if (isDark) 0xFF9E9E9E.toInt() else 0xFF757575.toInt()
            )

            // Set name
            name.text = entry.name.substringAfterLast('/').ifEmpty { entry.name }
            name.setTextColor(if (isDark) 0xFFE0E0E0.toInt() else 0xFF1F1F1F.toInt())

            // Set size
            if (!entry.isDirectory && entry.size > 0) {
                size.text = formatSize(entry.size)
                size.setTextColor(if (isDark) 0xFF9E9E9E.toInt() else 0xFF8A8A8A.toInt())
                size.visibility = View.VISIBLE
            } else {
                size.visibility = View.GONE
            }
        }

        private fun formatSize(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024) return "%.1f KB".format(kb)
            val mb = kb / 1024.0
            if (mb < 1024) return "%.1f MB".format(mb)
            val gb = mb / 1024.0
            return "%.1f GB".format(gb)
        }
    }
}
