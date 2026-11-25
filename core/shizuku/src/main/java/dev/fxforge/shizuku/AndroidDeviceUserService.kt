package dev.fxforge.shizuku

import android.os.Build
import android.os.Bundle
import android.os.Process
import androidx.annotation.Keep

@Keep
class AndroidDeviceUserService : IAndroidDeviceService.Stub() {
    override fun getDeviceInfo(): Bundle = Bundle().apply {
        putInt(KEY_UID, Process.myUid())
        putString(KEY_ANDROID_RELEASE, Build.VERSION.RELEASE)
        putInt(KEY_API_LEVEL, Build.VERSION.SDK_INT)
        putString(KEY_MANUFACTURER, Build.MANUFACTURER)
        putString(KEY_MODEL, Build.MODEL)
        putString(KEY_FINGERPRINT, Build.FINGERPRINT)
        putStringArray(KEY_ABIS, Build.SUPPORTED_ABIS)
    }

    override fun destroy() {
        System.exit(0)
    }

    companion object {
        const val KEY_UID = "uid"
        const val KEY_ANDROID_RELEASE = "androidRelease"
        const val KEY_API_LEVEL = "apiLevel"
        const val KEY_MANUFACTURER = "manufacturer"
        const val KEY_MODEL = "model"
        const val KEY_FINGERPRINT = "fingerprint"
        const val KEY_ABIS = "abis"
    }
}
