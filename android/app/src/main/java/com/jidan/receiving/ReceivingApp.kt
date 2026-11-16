package com.jidan.receiving

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import com.jidan.receiving.data.Prefs

class ReceivingApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun attachBaseContext(base: Context) {
        if (Prefs.simBigFontEnabled(base)) {
            val cfg = Configuration(base.resources.configuration)
            cfg.fontScale = 1.3f
            super.attachBaseContext(base.createConfigurationContext(cfg))
        } else {
            super.attachBaseContext(base)
        }
    }

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
