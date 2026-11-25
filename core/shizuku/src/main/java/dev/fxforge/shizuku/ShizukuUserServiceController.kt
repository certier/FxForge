package dev.fxforge.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

data class AndroidDeviceFacts(
    val uid: Int,
    val androidRelease: String,
    val apiLevel: Int,
    val manufacturer: String,
    val model: String,
    val fingerprint: String,
    val supportedAbis: List<String>
) {
    companion object {
        fun from(bundle: Bundle) = AndroidDeviceFacts(
            uid = bundle.getInt(AndroidDeviceUserService.KEY_UID, -1),
            androidRelease = bundle.getString(AndroidDeviceUserService.KEY_ANDROID_RELEASE).orEmpty(),
            apiLevel = bundle.getInt(AndroidDeviceUserService.KEY_API_LEVEL, -1),
            manufacturer = bundle.getString(AndroidDeviceUserService.KEY_MANUFACTURER).orEmpty(),
            model = bundle.getString(AndroidDeviceUserService.KEY_MODEL).orEmpty(),
            fingerprint = bundle.getString(AndroidDeviceUserService.KEY_FINGERPRINT).orEmpty(),
            supportedAbis = bundle.getStringArray(AndroidDeviceUserService.KEY_ABIS)?.toList().orEmpty()
        )
    }
}

data class ShizukuUserServiceStatus(
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val deviceFacts: AndroidDeviceFacts? = null,
    val error: String? = null
)

class ShizukuUserServiceController(context: Context) {
    private val appContext = context.applicationContext
    private val mutableStatus = MutableStateFlow(ShizukuUserServiceStatus())
    val status: StateFlow<ShizukuUserServiceStatus> = mutableStatus.asStateFlow()

    private var service: IAndroidDeviceService? = null
    private var reconnectWhenAvailable = false

    private val serviceArgs = Shizuku.UserServiceArgs(
        ComponentName(appContext, AndroidDeviceUserService::class.java)
    )
        .tag("fxforge-android-device-service")
        .version(SERVICE_VERSION)
        .daemon(false)
        .processNameSuffix("android-device")

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null) {
                service = null
                mutableStatus.value = ShizukuUserServiceStatus(
                    error = "Shizuku connected without a device-service binder"
                )
                return
            }
            service = IAndroidDeviceService.Stub.asInterface(binder)
            mutableStatus.value = ShizukuUserServiceStatus(connected = true)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            mutableStatus.value = ShizukuUserServiceStatus(
                error = "Shizuku device service disconnected"
            )
        }

        override fun onBindingDied(name: ComponentName?) {
            service = null
            mutableStatus.value = ShizukuUserServiceStatus(
                error = "Shizuku device-service binding died"
            )
        }

        override fun onNullBinding(name: ComponentName?) {
            service = null
            mutableStatus.value = ShizukuUserServiceStatus(
                error = "Shizuku returned an empty device-service binding"
            )
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        if (reconnectWhenAvailable) connect()
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        service = null
        mutableStatus.value = ShizukuUserServiceStatus(
            error = "Shizuku disconnected; reconnect when it is available"
        )
    }

    init {
        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
    }

    fun connect() {
        if (!Shizuku.pingBinder()) {
            setError("Shizuku binder is not connected")
            return
        }
        if (Shizuku.isPreV11()) {
            setError("Shizuku API v11 or newer is required for UserService")
            return
        }
        val permissionGranted = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: IllegalStateException) {
            setError("Shizuku disconnected while checking permission")
            return
        }
        if (!permissionGranted) {
            setError("Grant FxForge Shizuku permission before connecting")
            return
        }
        if (mutableStatus.value.connected || mutableStatus.value.connecting) return

        reconnectWhenAvailable = true
        mutableStatus.value = ShizukuUserServiceStatus(connecting = true)
        try {
            Shizuku.bindUserService(serviceArgs, serviceConnection)
        } catch (exception: RuntimeException) {
            setError("Could not start Shizuku device service: ${exception.message ?: "service error"}")
        }
    }

    fun disconnect() {
        reconnectWhenAvailable = false
        service = null
        mutableStatus.value = ShizukuUserServiceStatus()
        if (!Shizuku.pingBinder()) return
        try {
            Shizuku.unbindUserService(serviceArgs, serviceConnection, true)
        } catch (exception: RuntimeException) {
            setError("Could not stop Shizuku device service: ${exception.message ?: "service error"}")
        }
    }

    suspend fun readDeviceInfo() {
        val activeService = service
        if (activeService == null) {
            setError("Connect the Shizuku device service before requesting device information")
            return
        }
        try {
            val bundle = withContext(Dispatchers.IO) { activeService.getDeviceInfo() }
            mutableStatus.value = mutableStatus.value.copy(
                deviceFacts = AndroidDeviceFacts.from(bundle),
                error = null
            )
        } catch (exception: RemoteException) {
            setError("Device information request failed: ${exception.message ?: "remote service error"}")
        }
    }

    private fun setError(message: String) {
        mutableStatus.value = mutableStatus.value.copy(
            connecting = false,
            error = message
        )
    }

    companion object {
        private const val SERVICE_VERSION = 1
    }
}
