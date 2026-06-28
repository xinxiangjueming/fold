package com.example.fold.ui.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.fold.R

data class PlaylistItem(
    val path: String,
    val displayName: String,
    val index: Int
)

class PlaylistAdapter(
    private val onSelect: (Int) -> Unit
) : ListAdapter<PlaylistItem, PlaylistAdapter.VH>(Diff) {

    var currentIndex: Int = -1
        set(value) {
            if (field != value) {
                val old = field
                field = value
                if (old >= 0) notifyItemChanged(old)
                if (value >= 0) notifyItemChanged(value)
            }
        }

    private object Diff : DiffUtil.ItemCallback<PlaylistItem>() {
        override fun areItemsTheSame(a: PlaylistItem, b: PlaylistItem) = a.path == b.path
        override fun areContentsTheSame(a: PlaylistItem, b: PlaylistItem) = a == b
    }

    init { setHasStableIds(true) }

    override fun getItemId(position: Int): Long = getItem(position).path.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_playlist, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position), position == currentIndex, onSelect)
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val playingIcon: ImageView = itemView.findViewById(R.id.playing_icon)
        private val name: TextView = itemView.findViewById(R.id.name)

        fun bind(item: PlaylistItem, isPlaying: Boolean, onSelect: (Int) -> Unit) {
            val isDark = (itemView.context.resources.configuration.uiMode and 
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) == 
                android.content.res.Configuration.UI_MODE_NIGHT_YES

            // Show playing indicator
            playingIcon.visibility = if (isPlaying) View.VISIBLE else View.GONE

            // Set name
            name.text = item.displayName
            name.setTextColor(
                if (isPlaying) {
                    if (isDark) 0xFFBB86FC.toInt() else 0xFF6200EE.toInt()
                } else {
                    if (isDark) 0xFFE0E0E0.toInt() else 0xFF1F1F1F.toInt()
                }
            )

            // Click handler
            itemView.setOnClickListener { onSelect(item.index) }
        }
    }
}
