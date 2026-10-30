package com.iptv.tvapp.model

import java.io.Serializable

data class EpgProgram(
    val channelId: String,
    val startTime: Long,
    val endTime: Long,
    val title: String,
    val desc: String? = null
) : Serializable {

    fun isCurrent(now: Long = System.currentTimeMillis()): Boolean {
        return now in startTime until endTime
    }

    fun isUpcoming(now: Long = System.currentTimeMillis()): Boolean {
        return startTime > now
    }
}
