package com.webcodelab.smsvault

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class SmsAdapter(
    private val onItemClick: (SmsItem) -> Unit
) : RecyclerView.Adapter<SmsAdapter.SmsViewHolder>() {

    private val items = mutableListOf<SmsItem>()

    fun submitList(newItems: List<SmsItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SmsViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_sms, parent, false)
        return SmsViewHolder(view)
    }

    override fun onBindViewHolder(holder: SmsViewHolder, position: Int) {
        holder.bind(items[position], onItemClick)
    }

    override fun getItemCount(): Int = items.size

    class SmsViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvSender: TextView = itemView.findViewById(R.id.tvSender)
        private val tvStatus: TextView = itemView.findViewById(R.id.tvStatus)
        private val tvBody: TextView = itemView.findViewById(R.id.tvBody)
        private val tvTimestamp: TextView = itemView.findViewById(R.id.tvTimestamp)

        fun bind(item: SmsItem, onItemClick: (SmsItem) -> Unit) {
            tvSender.text = item.sender
            tvBody.text = item.body
            tvTimestamp.text = item.receivedAtIso

            if (item.isSynced) {
                tvStatus.text = "Synced"
                tvStatus.setTextColor(Color.parseColor("#15803D"))
                tvStatus.background?.setTint(Color.parseColor("#DCFCE7"))
            } else {
                tvStatus.text = "Pending"
                tvStatus.setTextColor(Color.parseColor("#B45309"))
                tvStatus.background?.setTint(Color.parseColor("#FEF3C7"))
            }

            itemView.setOnClickListener { onItemClick(item) }
        }
    }
}
