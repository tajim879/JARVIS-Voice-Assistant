package com.jarvis.assistant.ui.currency

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.jarvis.assistant.databinding.ItemRateBinding

/** One display row for both the Crypto and Fiat lists. */
data class RateRow(
    val title: String,
    val subtitle: String,
    val value: String,
    val change: String?,
    /** >0 green, <0 red, 0 muted. */
    val changeSign: Int
)

class RateAdapter : RecyclerView.Adapter<RateAdapter.Holder>() {

    private var rows: List<RateRow> = emptyList()

    fun submit(newRows: List<RateRow>) {
        rows = newRows
        notifyDataSetChanged()
    }

    class Holder(val binding: ItemRateBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemRateBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        val b = holder.binding
        b.tvRateTitle.text = row.title
        b.tvRateSubtitle.text = row.subtitle
        b.tvRateValue.text = row.value
        if (row.change == null) {
            b.tvRateChange.visibility = View.GONE
        } else {
            b.tvRateChange.visibility = View.VISIBLE
            b.tvRateChange.text = row.change
            val ctx = b.root.context
            when {
                row.changeSign > 0 -> b.tvRateChange.setTextColor(
                    androidx.core.content.ContextCompat.getColor(ctx, com.jarvis.assistant.R.color.status_green)
                )
                row.changeSign < 0 -> b.tvRateChange.setTextColor(
                    androidx.core.content.ContextCompat.getColor(ctx, com.jarvis.assistant.R.color.status_red)
                )
            }
        }
    }
}
