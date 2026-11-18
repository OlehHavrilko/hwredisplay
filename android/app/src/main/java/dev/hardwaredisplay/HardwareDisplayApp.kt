package dev.hardwaredisplay

import android.app.Application
import dev.hardwaredisplay.data.AppDatabase
import dev.hardwaredisplay.data.SettingsStore
import dev.hardwaredisplay.data.TokenStore
import dev.hardwaredisplay.session.SessionRepository

class HardwareDisplayApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.create(this) }
    val settings: SettingsStore by lazy { SettingsStore(this) }
    val tokens: TokenStore by lazy { TokenStore(this) }
    val repository: SessionRepository by lazy {
        SessionRepository(this, database, settings, tokens)
    }
}
