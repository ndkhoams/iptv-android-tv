package com.iptv.tvapp.model

data class Category(
    val name: String,
    val channels: MutableList<Channel> = mutableListOf()
)
