package dev.fxforge.shizuku

import android.os.Build
import android.os.Bundle
import android.os.Process
import androidx.annotation.Keep
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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

    override fun readLogcat(maxLines: Int): Bundle {
        val lineLimit = LogcatLimits.lines(maxLines)
        val process = try {
            ProcessBuilder(
                "/system/bin/logcat",
                "-d",
                "-v",
                "threadtime",
                "-t",
                lineLimit.toString()
            ).redirectErrorStream(true).start()
        } catch (exception: IOException) {
            return logcatResult(error = "Could not start logcat: ${exception.message ?: "process error"}")
        } catch (exception: SecurityException) {
            return logcatResult(
                error = "Android denied logcat access: ${exception.message ?: "permission denied"}"
            )
        }

        val outputTask = FutureTask {
            process.inputStream.use { stream ->
                InputStreamReader(stream, StandardCharsets.UTF_8).use { reader ->
                    val output = StringBuilder()
                    val buffer = CharArray(4096)
                    var truncated = false
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        val remaining = LogcatLimits.MAX_OUTPUT_CHARS - output.length
                        if (count > remaining) {
                            output.append(buffer, 0, remaining)
                            truncated = true
                            process.destroyForcibly()
                            break
                        }
                        output.append(buffer, 0, count)
                    }
                    LogcatOutput(output.toString(), truncated)
                }
            }
        }
        Thread(outputTask, "fxforge-logcat-reader").apply {
            isDaemon = true
            start()
        }

        return try {
            if (!process.waitFor(LOGCAT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return logcatResult(error = "Logcat timed out after $LOGCAT_TIMEOUT_SECONDS seconds")
            }
            val output = outputTask.get(LOGCAT_READER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val exitCode = process.exitValue()
            logcatResult(
                output = output.text,
                truncated = output.truncated,
                error = if (exitCode == 0) null else "Logcat exited with code $exitCode"
            )
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            process.destroyForcibly()
            logcatResult(error = "Logcat request was interrupted")
        } catch (exception: ExecutionException) {
            process.destroyForcibly()
            logcatResult(error = "Could not read logcat output: ${exception.cause?.message ?: "stream error"}")
        } catch (exception: TimeoutException) {
            process.destroyForcibly()
            logcatResult(error = "Timed out while reading logcat output")
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    override fun destroy() {
        System.exit(0)
    }

    private fun logcatResult(
        output: String = "",
        truncated: Boolean = false,
        error: String? = null
    ) = Bundle().apply {
        putString(KEY_LOGCAT_OUTPUT, output)
        putBoolean(KEY_LOGCAT_TRUNCATED, truncated)
        putString(KEY_LOGCAT_ERROR, error)
    }

    companion object {
        const val KEY_UID = "uid"
        const val KEY_ANDROID_RELEASE = "androidRelease"
        const val KEY_API_LEVEL = "apiLevel"
        const val KEY_MANUFACTURER = "manufacturer"
        const val KEY_MODEL = "model"
        const val KEY_FINGERPRINT = "fingerprint"
        const val KEY_ABIS = "abis"
        const val KEY_LOGCAT_OUTPUT = "logcatOutput"
        const val KEY_LOGCAT_TRUNCATED = "logcatTruncated"
        const val KEY_LOGCAT_ERROR = "logcatError"
        private const val LOGCAT_TIMEOUT_SECONDS = 8L
        private const val LOGCAT_READER_TIMEOUT_SECONDS = 2L
    }
}

private data class LogcatOutput(val text: String, val truncated: Boolean)
