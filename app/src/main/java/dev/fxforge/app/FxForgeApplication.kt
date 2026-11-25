package dev.fxforge.app

import android.app.Application
import dev.fxforge.shizuku.ShizukuStatusRepository
import dev.fxforge.shizuku.ShizukuUserServiceController

class FxForgeApplication : Application() {
    val shizukuStatusRepository: ShizukuStatusRepository by lazy {
        ShizukuStatusRepository()
    }
    val shizukuUserServiceController: ShizukuUserServiceController by lazy {
        ShizukuUserServiceController(this)
    }

    override fun onCreate() {
        super.onCreate()
        shizukuStatusRepository
        shizukuUserServiceController
    }
}
