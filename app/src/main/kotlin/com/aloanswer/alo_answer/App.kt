package com.aloanswer.alo_answer

import android.app.Application

/** Runs before any screen, service or receiver of the app. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Android 13+: hand a language picked in an older version over to the system once.
        Settings.migrateLanguage(this)
    }
}
