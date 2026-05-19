package com.rokid.glass.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.rokid.glesse.R

class MenuCardAdapter(
    private val cards: List<MenuCardData>,
    private val onItemClick: (Int) -> Unit,
) : RecyclerView.Adapter<MenuCardAdapter.ViewHolder>() {

    data class MenuCardData(
        val iconResId: Int,
        val labelResId: Int,
        val iconChar: String? = null,
    )

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val card: FrameLayout = itemView.findViewById(R.id.itemCard)
        val icon: ImageView = itemView.findViewById(R.id.ivCardIcon)
        val iconText: TextView = itemView.findViewById(R.id.tvCardIconText)
        val label: TextView = itemView.findViewById(R.id.tvCardLabel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_menu_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val card = cards[position]
        if (card.iconChar != null) {
            holder.iconText.visibility = View.VISIBLE
            holder.iconText.text = card.iconChar
            holder.icon.visibility = View.GONE
        } else {
            holder.iconText.visibility = View.GONE
            holder.icon.setImageResource(card.iconResId)
            holder.icon.visibility = View.VISIBLE
        }
        holder.label.setText(card.labelResId)
        holder.card.setOnClickListener { onItemClick(position) }
    }

    override fun getItemCount(): Int = cards.size
}
