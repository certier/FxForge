package dev.fxforge.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
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
    val logcatLoading: Boolean = false,
    val logcatOutput: String? = null,
    val logcatTruncated: Boolean = false,
    val logcatError: String? = null,
    val error: String? = null
)

data class ShizukuApkInstallResult(
    val success: Boolean,
    val output: String,
    val error: String?
)

data class ShizukuCommandResult(
    val output: String,
    val exitCode: Int?,
    val truncated: Boolean,
    val error: String?
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

    fun connectWhenAvailable() {
        reconnectWhenAvailable = true
        if (Shizuku.pingBinder()) connect()
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
        operationPermissionError()?.let {
            setError(it)
            return
        }
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

    suspend fun readLogcat(maxLines: Int = LogcatLimits.DEFAULT_LINES) {
        operationPermissionError()?.let {
            mutableStatus.value = mutableStatus.value.copy(
                logcatError = it,
                logcatLoading = false
            )
            return
        }
        val activeService = service
        if (activeService == null) {
            mutableStatus.value = mutableStatus.value.copy(
                logcatError = "Connect the Shizuku device service before reading logcat"
            )
            return
        }
        mutableStatus.value = mutableStatus.value.copy(
            logcatLoading = true,
            logcatError = null
        )
        try {
            val bundle = withContext(Dispatchers.IO) {
                activeService.readLogcat(LogcatLimits.lines(maxLines))
            }
            if (service !== activeService) return
            mutableStatus.value = mutableStatus.value.copy(
                logcatLoading = false,
                logcatOutput = bundle.getString(AndroidDeviceUserService.KEY_LOGCAT_OUTPUT).orEmpty(),
                logcatTruncated = bundle.getBoolean(AndroidDeviceUserService.KEY_LOGCAT_TRUNCATED),
                logcatError = bundle.getString(AndroidDeviceUserService.KEY_LOGCAT_ERROR)
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: RemoteException) {
            if (service !== activeService) return
            mutableStatus.value = mutableStatus.value.copy(
                logcatLoading = false,
                logcatError = "Logcat request failed: ${exception.message ?: "remote service error"}"
            )
        } catch (exception: RuntimeException) {
            if (service !== activeService) return
            mutableStatus.value = mutableStatus.value.copy(
                logcatLoading = false,
                logcatError = "Logcat request failed: ${exception.message ?: "device service error"}"
            )
        }
    }

    suspend fun executeCommand(command: String, timeoutSeconds: Int = 30): ShizukuCommandResult {
        operationPermissionError()?.let {
            return ShizukuCommandResult("", null, false, it)
        }
        val activeService = service
            ?: return ShizukuCommandResult(
                "", null, false,
                "Connect the Shizuku device service before running shell commands"
            )
        if (command.isBlank()) {
            return ShizukuCommandResult("", null, false, "Enter a command first")
        }
        if (command.length > MAX_COMMAND_CHARS) {
            return ShizukuCommandResult("", null, false, "Command exceeds the $MAX_COMMAND_CHARS character limit")
        }
        return try {
            val bundle = withContext(Dispatchers.IO) {
                activeService.executeCommand(command, timeoutSeconds.coerceIn(1, MAX_COMMAND_TIMEOUT_SECONDS))
            }
            if (service !== activeService) {
                ShizukuCommandResult("", null, false, "Shizuku disconnected while the command was running")
            } else {
                ShizukuCommandResult(
                    output = bundle.getString(AndroidDeviceUserService.KEY_COMMAND_OUTPUT).orEmpty(),
                    exitCode = bundle.getInt(AndroidDeviceUserService.KEY_COMMAND_EXIT_CODE, -1)
                        .takeIf { it >= 0 },
                    truncated = bundle.getBoolean(AndroidDeviceUserService.KEY_COMMAND_TRUNCATED),
                    error = bundle.getString(AndroidDeviceUserService.KEY_COMMAND_ERROR)
                )
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: RemoteException) {
            ShizukuCommandResult("", null, false, "Shell command failed: ${exception.message ?: "remote service error"}")
        } catch (exception: RuntimeException) {
            ShizukuCommandResult("", null, false, "Shell command failed: ${exception.message ?: "device service error"}")
        }
    }

    suspend fun installApk(apkDescriptor: ParcelFileDescriptor): ShizukuApkInstallResult {
        operationPermissionError()?.let {
            apkDescriptor.close()
            return ShizukuApkInstallResult(false, "", it)
        }
        val activeService = service
            ?: return ShizukuApkInstallResult(
                success = false,
                output = "",
                error = "Connect the Shizuku device service before installing an APK"
            )
        return try {
            val bundle = withContext(Dispatchers.IO) {
                activeService.installApk(apkDescriptor)
            }
            if (service !== activeService) {
                ShizukuApkInstallResult(false, "", "Shizuku disconnected during APK installation")
            } else {
                ShizukuApkInstallResult(
                    success = bundle.getBoolean(AndroidDeviceUserService.KEY_INSTALL_SUCCESS),
                    output = bundle.getString(AndroidDeviceUserService.KEY_INSTALL_OUTPUT).orEmpty(),
                    error = bundle.getString(AndroidDeviceUserService.KEY_INSTALL_ERROR)
                )
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: RemoteException) {
            ShizukuApkInstallResult(
                success = false,
                output = "",
                error = "APK installation request failed: ${exception.message ?: "remote service error"}"
            )
        } catch (exception: RuntimeException) {
            ShizukuApkInstallResult(
                success = false,
                output = "",
                error = "APK installation request failed: ${exception.message ?: "device service error"}"
            )
        }
    }

    private fun setError(message: String) {
        mutableStatus.value = mutableStatus.value.copy(
            connecting = false,
            error = message
        )
    }

    private fun operationPermissionError(): String? {
        if (!Shizuku.pingBinder()) return "Shizuku binder is not connected"
        return try {
            when {
                Shizuku.isPreV11() -> "Shizuku API v11 or newer is required for device operations"
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> null
                else -> "Grant FxForge Shizuku permission before using this device operation"
            }
        } catch (_: IllegalStateException) {
            "Shizuku disconnected while checking permission"
        }
    }

    companion object {
        private const val SERVICE_VERSION = 4
        private const val MAX_COMMAND_CHARS = 8192
        private const val MAX_COMMAND_TIMEOUT_SECONDS = 60
    }
}
