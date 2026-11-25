package dev.fxforge.app

import android.app.Application
import dev.fxforge.shizuku.ShizukuStatusRepository

class FxForgeApplication : Application() {
    val shizukuStatusRepository: ShizukuStatusRepository by lazy {
        ShizukuStatusRepository()
    }

    override fun onCreate() {
        super.onCreate()
        shizukuStatusRepository
    }
}
