package dev.fxforge.app

import android.app.Application
import dev.fxforge.shizuku.ShizukuStatusRepository
import dev.fxforge.shizuku.ShizukuUserServiceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class FxForgeApplication : Application() {
    // Long environment installs must survive navigation and Activity recreation.
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val shizukuStatusRepository: ShizukuStatusRepository by lazy {
        ShizukuStatusRepository()
    }
    val shizukuUserServiceController: ShizukuUserServiceController by lazy {
        ShizukuUserServiceController(this)
    }
    val linuxRuntimeManager: LinuxRuntimeManager by lazy {
        LinuxRuntimeManager(this)
    }
    val androidMcpBridge: AndroidMcpBridge by lazy {
        AndroidMcpBridge(this, shizukuUserServiceController)
    }

    override fun onCreate() {
        super.onCreate()
        shizukuStatusRepository
        shizukuUserServiceController.connectWhenAvailable()
    }
}
