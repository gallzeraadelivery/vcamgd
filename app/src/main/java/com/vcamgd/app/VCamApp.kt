package com.vcamgd.app

import android.app.Application
import com.vcamgd.app.data.AppSettings

class VCamApp : Application() {
    lateinit var settings: AppSettings
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = AppSettings(this)
    }

    companion object {
        lateinit var instance: VCamApp
            private set
    }
}
