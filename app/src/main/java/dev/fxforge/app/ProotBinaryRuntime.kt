package dev.fxforge.app

import android.content.Context
import android.os.Build
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class ProotProbeResult(
    val available: Boolean,
    val output: String,
    val error: String? = null
)

object ProotBinaryRuntime {
    private const val PROOT_BINARY = "libfxforge_proot.so"
    private const val PROOT_LOADER = "libfxforge_proot_loader.so"
    private const val PROOT_LOADER_32 = "libfxforge_proot_loader32.so"
    private const val PROBE_TIMEOUT_SECONDS = 8L
    private const val MAX_OUTPUT_CHARS = 4096

    fun isBundled(context: Context): Boolean =
        Build.SUPPORTED_ABIS.any { it == "arm64-v8a" } &&
            binary(context).isFile &&
            loader(context).isFile

    fun probe(context: Context): ProotProbeResult {
        if (!Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }) {
            return ProotProbeResult(false, "", "The bundled PRoot runtime currently supports ARM64 only.")
        }
        val executable = binary(context)
        val loaderFile = loader(context)
        if (!executable.isFile || !loaderFile.isFile) {
            return ProotProbeResult(false, "", "The PRoot executable or its loader was not extracted from the APK.")
        }
        val temporaryDirectory = File(context.cacheDir, "proot-tmp")
        if (!temporaryDirectory.exists() && !temporaryDirectory.mkdirs()) {
            return ProotProbeResult(false, "", "Could not create the PRoot temporary directory.")
        }

        val process = try {
            ProcessBuilder(executable.absolutePath, "--version")
                .directory(context.filesDir)
                .redirectErrorStream(true)
                .apply {
                    environment()["PROOT_TMP_DIR"] = temporaryDirectory.absolutePath
                    environment()["PROOT_LOADER"] = loaderFile.absolutePath
                    environment()["PROOT_LOADER_32"] = loader32(context).absolutePath
                }
                .start()
        } catch (exception: IOException) {
            return ProotProbeResult(false, "", "Could not start the bundled PRoot binary: " + (exception.message ?: "exec failed"))
        } catch (exception: SecurityException) {
            return ProotProbeResult(false, "", "Android denied execution of the PRoot binary: " + (exception.message ?: "permission denied"))
        }

        return try {
            if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(1, TimeUnit.SECONDS)
                return ProotProbeResult(false, "", "PRoot version check timed out.")
            }
            val output = process.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(MAX_OUTPUT_CHARS)
                val count = reader.read(buffer)
                if (count <= 0) "" else String(buffer, 0, count)
            }
            val exitCode = process.exitValue()
            if (exitCode == 0) {
                ProotProbeResult(true, output.trim())
            } else {
                ProotProbeResult(false, output.trim(), "PRoot exited with code " + exitCode + ".")
            }
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            process.destroyForcibly()
            ProotProbeResult(false, "", "PRoot version check was interrupted.")
        } catch (exception: IOException) {
            process.destroyForcibly()
            ProotProbeResult(false, "", "Could not read PRoot output: " + (exception.message ?: "I/O error"))
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun binary(context: Context) =
        File(context.applicationInfo.nativeLibraryDir, PROOT_BINARY)

    private fun loader(context: Context) =
        File(context.applicationInfo.nativeLibraryDir, PROOT_LOADER)

    private fun loader32(context: Context) =
        File(context.applicationInfo.nativeLibraryDir, PROOT_LOADER_32)
}
