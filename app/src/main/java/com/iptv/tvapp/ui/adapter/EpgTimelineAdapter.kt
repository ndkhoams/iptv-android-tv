package com.iptv.tvapp.ui.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.iptv.tvapp.R
import com.iptv.tvapp.databinding.ItemEpgTimelineBinding
import com.iptv.tvapp.model.EpgProgram
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EpgTimelineAdapter : RecyclerView.Adapter<EpgTimelineAdapter.EpgViewHolder>() {

    private val programs = mutableListOf<EpgProgram>()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun submitList(list: List<EpgProgram>) {
        programs.clear()
        programs.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpgViewHolder {
        val binding = ItemEpgTimelineBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return EpgViewHolder(binding)
    }

    override fun onBindViewHolder(holder: EpgViewHolder, position: Int) {
        holder.bind(programs[position])
    }

    override fun getItemCount(): Int = programs.size

    inner class EpgViewHolder(
        private val binding: ItemEpgTimelineBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(program: EpgProgram) {
            val now = System.currentTimeMillis()
            val isCurrent = program.isCurrent(now)
            val isUpcoming = program.isUpcoming(now)

            val startStr = timeFormat.format(Date(program.startTime))
            val endStr = timeFormat.format(Date(program.endTime))
            binding.tvProgramTime.text = "$startStr - $endStr"
            binding.tvProgramTitle.text = program.title

            if (isCurrent) {
                binding.tvTimelineDot.text = "●"
                binding.tvTimelineDot.setTextColor(Color.parseColor("#FF6500"))
                binding.tvProgramTime.setTextColor(Color.parseColor("#FF6500"))
                binding.tvProgramTitle.setTextColor(Color.parseColor("#FFFFFF"))
                binding.tvProgramTitle.textSize = 12.5f
                binding.tvLiveBadge.visibility = View.VISIBLE
                binding.root.setBackgroundResource(R.drawable.bg_epg_current_item)
                binding.root.setPadding(14, 8, 14, 8)
            } else if (isUpcoming) {
                binding.tvTimelineDot.text = "○"
                binding.tvTimelineDot.setTextColor(Color.parseColor("#64748B"))
                binding.tvProgramTime.setTextColor(Color.parseColor("#94A3B8"))
                binding.tvProgramTitle.setTextColor(Color.parseColor("#CBD5E1"))
                binding.tvProgramTitle.textSize = 11.5f
                binding.tvLiveBadge.visibility = View.GONE
                binding.root.background = null
                binding.root.setPadding(6, 6, 6, 6)
            } else {
                binding.tvTimelineDot.text = "·"
                binding.tvTimelineDot.setTextColor(Color.parseColor("#475569"))
                binding.tvProgramTime.setTextColor(Color.parseColor("#64748B"))
                binding.tvProgramTitle.setTextColor(Color.parseColor("#64748B"))
                binding.tvProgramTitle.textSize = 11.5f
                binding.tvLiveBadge.visibility = View.GONE
                binding.root.background = null
                binding.root.setPadding(6, 6, 6, 6)
            }
        }
    }
}
