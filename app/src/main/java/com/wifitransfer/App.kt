package com.wifitransfer

import android.app.Application

/**
 * Application class - global app instance access ke liye.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
