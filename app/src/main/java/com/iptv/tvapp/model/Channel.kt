package com.iptv.tvapp.model

import java.io.Serializable

data class Channel(
    val id: String,
    val name: String,
    val logoUrl: String? = null,
    val streamUrl: String,
    val groupTitle: String = "Mặc định",
    var number: Int = 0,
    var isFavorite: Boolean = false,
    var isDefaultStartup: Boolean = false
) : Serializable
