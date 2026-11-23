package com.webcodelab.smsvault

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ContactAdapter(
    private val onItemClick: (ContactItem) -> Unit
) : RecyclerView.Adapter<ContactAdapter.ContactViewHolder>() {

    private val items = mutableListOf<ContactItem>()

    fun submitList(newItems: List<ContactItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_contact, parent, false)
        return ContactViewHolder(view)
    }

    override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
        holder.bind(items[position], onItemClick)
    }

    override fun getItemCount(): Int = items.size

    class ContactViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tvContactName)
        private val tvPhone: TextView = itemView.findViewById(R.id.tvContactPhone)
        private val tvStatus: TextView = itemView.findViewById(R.id.tvContactStatus)

        fun bind(item: ContactItem, onItemClick: (ContactItem) -> Unit) {
            tvName.text = item.name
            tvPhone.text = item.phoneNumber

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
