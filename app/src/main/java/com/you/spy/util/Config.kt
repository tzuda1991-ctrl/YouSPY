package com.you.spy.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object Config {

    private const val FILE = "spy_cfg"
    private lateinit var p: SharedPreferences

    fun init(ctx: Context) {
        val key = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        p = EncryptedSharedPreferences.create(
            ctx, FILE, key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        if (get("token").isEmpty()) {
            set("token", com.you.spy.BuildConfig.TG_TOKEN)
            set("code", com.you.spy.BuildConfig.SETUP_CODE)
        }
    }

    private fun get(k: String) = p.getString(k, "") ?: ""
    private fun set(k: String, v: String) = p.edit().putString(k, v).apply()

    var token: String
        get() = get("token")
        set(v) = set("token", v)

    var chat: String
        get() = get("chat")
        set(v) = set("chat", v)

    var code: String
        get() = get("code")
        set(v) = set("code", v)

    var connected: Boolean
        get() = token.isNotEmpty() && chat.isNotEmpty()

    var offset: Long
        get() = p.getLong("offset", 0L)
        set(v) = p.edit().putLong("offset", v).apply()

    var running: Boolean
        get() = p.getBoolean("running", false)
        set(v) = p.edit().putBoolean("running", v).apply()

    var shellMode: String
        get() = p.getString("shell", "auto") ?: "auto"
        set(v) = set("shell", v)

    fun clear() {
        p.edit().clear().apply()
    }
}
