package dev.fxforge.shizuku

import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal data class ApkInstallResult(
    val success: Boolean,
    val output: String,
    val error: String? = null
)

internal object ShizukuApkInstaller {
    const val MAX_APK_BYTES = 512L * 1024 * 1024
    private const val MAX_OUTPUT_CHARS = 8 * 1024
    private const val INSTALL_TIMEOUT_SECONDS = 120L
    private const val OUTPUT_TIMEOUT_SECONDS = 2L
    private const val TEMP_DIRECTORY = "/data/local/tmp"

    fun supportsUid(uid: Int): Boolean = uid == 0 || uid == 2000

    fun installationReportedSuccess(exitCode: Int, output: String, truncated: Boolean): Boolean =
        exitCode == 0 &&
            !truncated &&
            output.lineSequence().any { line ->
                val value = line.trim()
                value == "Success" || value.startsWith("Success:")
            }

    fun install(apkDescriptor: ParcelFileDescriptor, uid: Int): ApkInstallResult {
        if (!supportsUid(uid)) {
            apkDescriptor.close()
            return ApkInstallResult(
                success = false,
                output = "",
                error = "APK installation requires Shizuku root or ADB-shell UID; current UID is $uid"
            )
        }

        val apkFile = try {
            File.createTempFile("fxforge-", ".apk", File(TEMP_DIRECTORY))
        } catch (exception: IOException) {
            apkDescriptor.close()
            return ApkInstallResult(
                success = false,
                output = "",
                error = "Cannot create a temporary APK for the package installer: ${exception.message ?: "storage error"}"
            )
        } catch (exception: SecurityException) {
            apkDescriptor.close()
            return ApkInstallResult(
                success = false,
                output = "",
                error = "Shizuku cannot write to $TEMP_DIRECTORY: ${exception.message ?: "permission denied"}"
            )
        }

        val result = try {
            copyApk(apkDescriptor, apkFile)
            if (!apkFile.setReadable(true, false)) {
                ApkInstallResult(
                    success = false,
                    output = "",
                    error = "Could not make the temporary APK readable by Android's package manager"
                )
            } else {
                runInstaller(apkFile)
            }
        } catch (exception: IOException) {
            ApkInstallResult(
                success = false,
                output = "",
                error = exception.message ?: "Could not prepare the APK"
            )
        } catch (exception: SecurityException) {
            ApkInstallResult(
                success = false,
                output = "",
                error = "Android denied APK access: ${exception.message ?: "permission denied"}"
            )
        }
        val deleted = apkFile.delete()
        return if (!deleted && apkFile.exists()) {
            result.copy(
                error = listOfNotNull(
                    result.error,
                    "Temporary APK cleanup failed; the file remains at ${apkFile.absolutePath}"
                ).joinToString(". ")
            )
        } else {
            result
        }
    }

    private fun copyApk(apkDescriptor: ParcelFileDescriptor, destination: File) {
        ParcelFileDescriptor.AutoCloseInputStream(apkDescriptor).use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var copied = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (copied + count > MAX_APK_BYTES) {
                        throw IOException("APK exceeds the 512 MiB installation limit")
                    }
                    output.write(buffer, 0, count)
                    copied += count
                }
                if (copied == 0L) throw IOException("The selected APK is empty")
                output.flush()
            }
        }
    }

    private fun runInstaller(apkFile: File): ApkInstallResult {
        val process = try {
            ProcessBuilder("/system/bin/pm", "install", "-r", apkFile.absolutePath)
                .redirectErrorStream(true)
                .start()
        } catch (exception: IOException) {
            return ApkInstallResult(
                success = false,
                output = "",
                error = "Could not start Android's package installer: ${exception.message ?: "process error"}"
            )
        } catch (exception: SecurityException) {
            return ApkInstallResult(
                success = false,
                output = "",
                error = "Android denied the package-install request: ${exception.message ?: "permission denied"}"
            )
        }

        val outputTask = FutureTask {
            process.inputStream.use { stream ->
                InputStreamReader(stream, StandardCharsets.UTF_8).use { reader ->
                    val output = StringBuilder()
                    val buffer = CharArray(2048)
                    var truncated = false
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        val remaining = MAX_OUTPUT_CHARS - output.length
                        if (count > remaining) {
                            output.append(buffer, 0, remaining)
                            truncated = true
                            process.destroyForcibly()
                            break
                        }
                        output.append(buffer, 0, count)
                    }
                    InstallCommandOutput(output.toString(), truncated)
                }
            }
        }
        Thread(outputTask, "fxforge-package-installer").apply {
            isDaemon = true
            start()
        }

        return try {
            if (!process.waitFor(INSTALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                ApkInstallResult(
                    success = false,
                    output = "",
                    error = "Android package installation timed out after $INSTALL_TIMEOUT_SECONDS seconds"
                )
            } else {
                val output = outputTask.get(OUTPUT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                val exitCode = process.exitValue()
                val success = installationReportedSuccess(exitCode, output.text, output.truncated)
                ApkInstallResult(
                    success = success,
                    output = output.text,
                    error = when {
                        output.truncated -> "Package installer output exceeded the 8 KiB limit"
                        exitCode != 0 -> "Package installer exited with code $exitCode"
                        !success -> "Package manager did not report a successful installation"
                        else -> null
                    }
                )
            }
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            process.destroyForcibly()
            ApkInstallResult(false, "", "Package installation was interrupted")
        } catch (exception: ExecutionException) {
            process.destroyForcibly()
            ApkInstallResult(
                false,
                "",
                "Could not read package-installer output: ${exception.cause?.message ?: "stream error"}"
            )
        } catch (exception: TimeoutException) {
            process.destroyForcibly()
            ApkInstallResult(false, "", "Timed out while reading package-installer output")
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}

private data class InstallCommandOutput(val text: String, val truncated: Boolean)
