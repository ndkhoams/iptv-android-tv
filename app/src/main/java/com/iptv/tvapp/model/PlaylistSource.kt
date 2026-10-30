package com.iptv.tvapp.model

import java.io.Serializable

data class PlaylistSource(
    val id: String,
    val name: String,
    val url: String,
    var isSelected: Boolean = false
) : Serializable
