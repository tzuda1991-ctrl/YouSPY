package com.you.spy

import android.app.Application
import com.you.spy.util.Config
import com.you.spy.util.Queue

class SpyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Config.init(this)
        Queue.init(this)
    }
}
