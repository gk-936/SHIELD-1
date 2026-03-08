package com.dearmoon.shield.features

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.dearmoon.shield.R

data class FeatureItem(val iconRes: Int, val title: String, val desc: String)

class FeaturesAdapter(private val items: List<FeatureItem>) : RecyclerView.Adapter<FeaturesAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.featureIcon)
        val title: TextView = view.findViewById(R.id.featureTitle)
        val desc: TextView = view.findViewById(R.id.featureDesc)
        val hint: TextView = view.findViewById(R.id.featureHint)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_feature_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.icon.setImageResource(item.iconRes)
        holder.title.text = item.title
        holder.desc.text = item.desc
        
        if (position == items.size - 1) {
            holder.hint.text = "you've caught up"
        } else {
            holder.hint.text = "scroll up to explore"
        }
    }

    override fun getItemCount() = items.size
}
