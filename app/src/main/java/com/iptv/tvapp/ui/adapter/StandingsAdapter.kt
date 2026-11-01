package com.iptv.tvapp.ui.adapter

import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.iptv.tvapp.R
import com.iptv.tvapp.data.SportsLiveManager
import com.iptv.tvapp.data.SportsStandingsManager
import com.iptv.tvapp.databinding.ItemStandingRowBinding

class StandingsAdapter : RecyclerView.Adapter<StandingsAdapter.ViewHolder>() {

    private val items = mutableListOf<SportsStandingsManager.StandingEntry>()
    private var highlightTeam1Canon: String = ""
    private var highlightTeam2Canon: String = ""

    fun submitList(
        newItems: List<SportsStandingsManager.StandingEntry>,
        homeTeam: String = "",
        awayTeam: String = ""
    ) {
        items.clear()
        items.addAll(newItems)
        highlightTeam1Canon = SportsLiveManager.canonicalizeTeam(homeTeam)
        highlightTeam2Canon = SportsLiveManager.canonicalizeTeam(awayTeam)
        notifyDataSetChanged()
    }

    fun getHighlightedIndex(): Int {
        for (i in items.indices) {
            val cTeam = SportsLiveManager.canonicalizeTeam(items[i].teamName)
            if (isMatch(cTeam)) return i
        }
        return -1
    }

    private fun isMatch(cTeam: String): Boolean {
        if (highlightTeam1Canon.isNotBlank() && (cTeam.contains(highlightTeam1Canon) || highlightTeam1Canon.contains(cTeam))) {
            return true
        }
        if (highlightTeam2Canon.isNotBlank() && (cTeam.contains(highlightTeam2Canon) || highlightTeam2Canon.contains(cTeam))) {
            return true
        }
        return false
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemStandingRowBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], position)
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemStandingRowBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: SportsStandingsManager.StandingEntry, position: Int) {
            val context = itemView.context
            binding.tvStandingRank.text = String.format("%02d", entry.rank)
            binding.tvStandingTeam.text = entry.shortName.ifBlank { entry.teamName }
            binding.tvStandingPlayed.text = entry.played.toString()
            binding.tvStandingGd.text = entry.gd
            binding.tvStandingPoints.text = entry.points.toString()

            if (!entry.logoUrl.isNullOrBlank()) {
                binding.ivStandingLogo.visibility = View.VISIBLE
                Glide.with(context)
                    .load(entry.logoUrl)
                    .placeholder(R.drawable.ic_tv_placeholder)
                    .error(R.drawable.ic_tv_placeholder)
                    .into(binding.ivStandingLogo)
            } else {
                binding.ivStandingLogo.visibility = View.GONE
            }

            val cTeam = SportsLiveManager.canonicalizeTeam(entry.teamName)
            val isHighlighted = isMatch(cTeam)

            if (isHighlighted) {
                // Highlight nổi bật 2 đội đang thi đấu với khung cam & chữ trắng đậm
                binding.layoutStandingItem.setBackgroundResource(R.drawable.bg_standing_highlight)
                binding.tvStandingRank.setTextColor(ContextCompat.getColor(context, R.color.fpt_orange))
                binding.tvStandingTeam.setTextColor(Color.WHITE)
                binding.tvStandingTeam.typeface = Typeface.DEFAULT_BOLD
                binding.tvStandingPoints.setTextColor(Color.parseColor("#FF8533"))
                binding.tvStandingPoints.typeface = Typeface.DEFAULT_BOLD
            } else {
                if (position % 2 == 1) {
                    binding.layoutStandingItem.setBackgroundColor(Color.parseColor("#171717"))
                } else {
                    binding.layoutStandingItem.setBackgroundColor(Color.TRANSPARENT)
                }
                binding.tvStandingRank.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                binding.tvStandingTeam.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                binding.tvStandingTeam.typeface = Typeface.DEFAULT
                binding.tvStandingPoints.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                binding.tvStandingPoints.typeface = Typeface.DEFAULT_BOLD
            }
        }
    }
}

