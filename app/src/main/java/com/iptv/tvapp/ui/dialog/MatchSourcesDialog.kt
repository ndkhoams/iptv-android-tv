package com.iptv.tvapp.ui.dialog

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.Window
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.iptv.tvapp.R
import com.iptv.tvapp.data.SportsLiveManager
import com.iptv.tvapp.databinding.DialogMatchSourcesBinding
import com.iptv.tvapp.databinding.ItemMatchSourceBinding
import com.iptv.tvapp.model.Channel

class MatchSourcesDialog(
    context: Context,
    private val matchGroup: SportsLiveManager.MatchGroup,
    private val onSourceSelected: (Channel) -> Unit
) : Dialog(context, R.style.TvDialogTheme) {

    private lateinit var binding: DialogMatchSourcesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        binding = DialogMatchSourcesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvMatchTitle.text = "${matchGroup.homeTeam} vs ${matchGroup.awayTeam}"
        val leagueStr = if (matchGroup.leagueName.isNotBlank()) " • ${matchGroup.leagueName}" else ""
        binding.tvMatchSubtitle.text = "${matchGroup.time} Hôm nay$leagueStr • ${matchGroup.sources.size} nguồn phát trực tiếp"

        val adapter = MatchSourcesAdapter(matchGroup.sources) { selectedChannel ->
            dismiss()
            onSourceSelected(selectedChannel)
        }

        binding.rvMatchSources.layoutManager = LinearLayoutManager(context)
        binding.rvMatchSources.adapter = adapter

        binding.rvMatchSources.post {
            binding.rvMatchSources.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                ?: binding.rvMatchSources.requestFocus()
        }
    }

    private class MatchSourcesAdapter(
        private val sources: List<Channel>,
        private val onClick: (Channel) -> Unit
    ) : RecyclerView.Adapter<MatchSourcesAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemMatchSourceBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(sources[position], position + 1)
        }

        override fun getItemCount(): Int = sources.size

        inner class ViewHolder(private val binding: ItemMatchSourceBinding) :
            RecyclerView.ViewHolder(binding.root) {

            init {
                binding.root.setOnClickListener {
                    val pos = adapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        onClick(sources[pos])
                    }
                }
                binding.root.setOnFocusChangeListener { v, hasFocus ->
                    if (hasFocus) {
                        v.animate().scaleX(1.02f).scaleY(1.02f).setDuration(120).start()
                    } else {
                        v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                    }
                }
            }

            fun bind(channel: Channel, index: Int) {
                binding.tvSourceIndex.text = String.format("%02d", index)
                binding.tvSourceName.text = formatSourceTitle(channel)

                val upperName = channel.name.uppercase()
                binding.tvSourceBadge.text = when {
                    upperName.contains("4K") -> "4K"
                    upperName.contains("FHD") -> "FHD"
                    upperName.contains("720") -> "HD"
                    else -> "HD"
                }

                if (!channel.logoUrl.isNullOrBlank()) {
                    Glide.with(itemView.context)
                        .load(channel.logoUrl)
                        .placeholder(R.drawable.ic_tv_placeholder)
                        .error(R.drawable.ic_tv_placeholder)
                        .into(binding.ivSourceLogo)
                } else {
                    binding.ivSourceLogo.setImageResource(R.drawable.ic_tv_placeholder)
                }
            }

            private fun formatSourceTitle(channel: Channel): String {
                val name = channel.name
                val group = channel.groupTitle
                val blvMatch = Regex("""\((?:BLV\s+)?([^\)]+)\)""", RegexOption.IGNORE_CASE).find(name)
                val blv = blvMatch?.groupValues?.get(1)?.trim()
                    ?.replace(Regex("""(?i)\b(hls|fhd|hd|sd|geo)\b"""), "")?.trim()

                val sourcePrefix = when {
                    group.contains("Chuối Chiên") || name.contains("Chuối Chiên") -> "🍌 Chuối Chiên"
                    group.contains("Gà Vàng") || name.contains("Gà Vàng") -> "🐔 Gà Vàng"
                    group.contains("Xôi Lạc") || name.contains("Xôi Lạc") -> "🍚 Xôi Lạc"
                    group.contains("Cà Khịa") || name.contains("Cà Khịa") -> "🌶️ Cà Khịa"
                    group.contains("Bia Ôm") || name.contains("Bia Ôm") -> "🍺 Bia Ôm"
                    group.contains("S8") || name.contains("S8") -> "🎱 S8 TV"
                    group.contains("Cola") || name.contains("Cola") -> "🥤 Cola TV"
                    group.contains("Phá Làng") || name.contains("Phá Làng") -> "💥 Phá Làng"
                    group.contains("Vua Sân Cỏ") || name.contains("Vua Sân Cỏ") -> "👑 Vua Sân Cỏ"
                    group.contains("Khô Gà") || name.contains("Khô Gà") -> "🍗 Khô Gà"
                    group.contains("Giờ Vàng") || name.contains("Giờ Vàng") -> "⏰ Giờ Vàng"
                    group.contains("Sao Kê") || name.contains("Sao Kê") -> "📜 Sao Kê"
                    group.contains("Xôi Gấc") || name.contains("Xôi Gấc") -> "🍧 Xôi Gấc"
                    else -> group.ifBlank { "⚽ Trực tiếp" }
                }

                return if (!blv.isNullOrBlank() && !blv.equals("Nhà đài", ignoreCase = true) && !blv.equals("hls", ignoreCase = true)) {
                    "$sourcePrefix • BLV $blv"
                } else {
                    sourcePrefix
                }
            }
        }
    }
}

