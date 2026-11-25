package dev.fxforge.shizuku

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ShizukuStatusRepository {
    private val mutableStatus = MutableStateFlow(ShizukuStatus())
    val status: StateFlow<ShizukuStatus> = mutableStatus.asStateFlow()

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        mutableStatus.value = ShizukuStatus()
    }
    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
            if (requestCode == PERMISSION_REQUEST_CODE) refresh()
        }

    init {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        refresh()
    }

    fun refresh() {
        if (!Shizuku.pingBinder()) {
            mutableStatus.value = ShizukuStatus()
            return
        }

        try {
            val uid = Shizuku.getUid()
            val permissionGranted = !Shizuku.isPreV11() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            mutableStatus.value = ShizukuStatus(
                binderAvailable = true,
                permissionGranted = permissionGranted,
                uid = uid
            )
        } catch (_: IllegalStateException) {
            // The binder can die between pingBinder() and the subsequent API calls.
            mutableStatus.value = ShizukuStatus()
        }
    }

    fun requestPermission() {
        if (!mutableStatus.value.binderAvailable) {
            throw IllegalStateException("Shizuku binder is not connected")
        }
        Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 7314
    }
}
