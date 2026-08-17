package com.vcamgd.app

import android.app.Application
import com.vcamgd.app.data.AppSettings

import com.vcamgd.app.util.KingVCamLog

class VCamApp : Application() {
    lateinit var settings: AppSettings
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = AppSettings(this)
        KingVCamLog.bind(this)
        KingVCamLog.i("app", "start v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    }

    companion object {
        lateinit var instance: VCamApp
            private set
    }
}
