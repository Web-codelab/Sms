package com.webcodelab.smsvault

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AnnouncementAdapter(
    private val onClick: (AnnouncementItem) -> Unit
) : RecyclerView.Adapter<AnnouncementAdapter.AnnouncementViewHolder>() {

    private val items = mutableListOf<AnnouncementItem>()

    fun submitList(newItems: List<AnnouncementItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AnnouncementViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_announcement, parent, false)
        return AnnouncementViewHolder(view)
    }

    override fun onBindViewHolder(holder: AnnouncementViewHolder, position: Int) {
        holder.bind(items[position], onClick)
    }

    override fun getItemCount(): Int = items.size

    class AnnouncementViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tvAnnouncementTitle)
        private val tvMessage: TextView = itemView.findViewById(R.id.tvAnnouncementPreview)
        private val tvTime: TextView = itemView.findViewById(R.id.tvAnnouncementTime)
        private val tvBadge: TextView = itemView.findViewById(R.id.tvAnnouncementBadge)

        fun bind(item: AnnouncementItem, onClick: (AnnouncementItem) -> Unit) {
            tvTitle.text = item.title
            tvMessage.text = item.message
            tvTime.text = item.createdAtIso
            if (item.isRead) {
                tvBadge.text = "Read"
                tvBadge.setTextColor(Color.parseColor("#475569"))
                tvBadge.setBackgroundColor(Color.parseColor("#F1F5F9"))
            } else {
                tvBadge.text = "New"
                tvBadge.setTextColor(Color.parseColor("#1D4ED8"))
                tvBadge.setBackgroundColor(Color.parseColor("#DBEAFE"))
            }
            itemView.setOnClickListener { onClick(item) }
        }
    }
}
