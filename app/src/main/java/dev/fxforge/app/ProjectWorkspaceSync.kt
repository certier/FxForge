package dev.fxforge.app

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

data class WorkspaceCopyResult(
    val directory: File,
    val filesCopied: Int,
    val bytesCopied: Long
)

object ProjectWorkspaceSync {
    private val ignoredDirectoryNames = setOf(".gradle", "build", ".idea", ".kotlin", "out")
    private val ignoredFileNames = setOf("local.properties", ".DS_Store")
    private const val MAX_FILES = 10_000
    private const val MAX_TOTAL_BYTES = 2L * 1024L * 1024L * 1024L
    private const val MAX_FILE_BYTES = 512L * 1024L * 1024L
    private const val BUFFER_SIZE = 64 * 1024

    fun copyFromSaf(
        context: Context,
        source: DocumentFile,
        destination: File
    ): WorkspaceCopyResult {
        if (!source.isDirectory || !source.canRead()) {
            throw IOException("The selected project folder cannot be read.")
        }
        if (destination.exists() && !destination.deleteRecursively()) {
            throw IOException("Could not clear the previous build workspace.")
        }
        if (!destination.mkdirs()) {
            throw IOException("Could not create the private build workspace.")
        }
        val counter = CopyCounter()
        copyDirectory(context, source, destination, counter, 0)
        return WorkspaceCopyResult(destination, counter.files, counter.bytes)
    }

    fun copyArtifactToSaf(
        context: Context,
        artifact: File,
        destinationFolder: DocumentFile,
        outputName: String
    ): DocumentFile {
        if (!artifact.isFile || artifact.length() <= 0) {
            throw IOException("The build did not produce a valid APK.")
        }
        if (artifact.length() > MAX_FILE_BYTES) {
            throw IOException("The generated APK is larger than the 512 MiB transfer limit.")
        }
        if (!destinationFolder.isDirectory || !destinationFolder.canWrite()) {
            throw IOException("The selected project folder is no longer writable.")
        }
        destinationFolder.findFile(outputName)?.delete()
        val destination = destinationFolder.createFile("application/vnd.android.package-archive", outputName)
            ?: throw IOException("Could not create the APK in the selected project folder.")
        val output = context.contentResolver.openOutputStream(destination.uri, "wt")
            ?: throw IOException("Could not open the destination APK for writing.")
        artifact.inputStream().use { input ->
            output.use { input.copyTo(it, BUFFER_SIZE) }
        }
        return destination
    }

    fun copyToSaf(
        context: Context,
        source: File,
        destination: DocumentFile
    ): WorkspaceCopyResult {
        if (!source.isDirectory || !destination.isDirectory || !destination.canWrite()) {
            throw IOException("The active workspace or SAF destination is unavailable.")
        }
        val counter = CopyCounter()
        mirrorDirectory(context, source, destination, counter, 0)
        return WorkspaceCopyResult(source, counter.files, counter.bytes)
    }

    private fun mirrorDirectory(
        context: Context,
        source: File,
        destination: DocumentFile,
        counter: CopyCounter,
        depth: Int
    ) {
        if (depth > 32) throw IOException("Workspace folder nesting is too deep.")
        val localChildren = source.listFiles()
            ?: throw IOException("Could not read workspace directory: " + source.name)
        val included = localChildren.filterNot { child ->
            (child.isDirectory && child.name in ignoredDirectoryNames) ||
                (child.isFile && child.name in ignoredFileNames)
        }
        val includedNames = included.map { it.name }.toSet()
        destination.listFiles().forEach { existing ->
            val name = existing.name ?: return@forEach
            if (existing.isDirectory && name in ignoredDirectoryNames) return@forEach
            if (existing.isFile && name in ignoredFileNames) return@forEach
            if (existing.isFile && name.endsWith(".apk", ignoreCase = true)) return@forEach
            if (name !in includedNames) {
                deleteSafTree(existing)
            }
        }
        included.forEach { child ->
            val name = child.name
            if (name.isBlank() || name == "." || name == ".." || name.contains('/') || name.contains('\\')) {
                throw IOException("The workspace contains an invalid filename.")
            }
            val local = File(source, name)
            if (local.canonicalFile.parentFile != source.canonicalFile) {
                throw IOException("The workspace contains an unsafe path.")
            }
            val existing = destination.findFile(name)
            if (local.isDirectory) {
                val directory = if (existing?.isDirectory == true) {
                    existing
                } else {
                    if (existing != null) deleteSafTree(existing)
                    destination.createDirectory(name)
                        ?: throw IOException("Could not create SAF directory: " + name)
                }
                mirrorDirectory(context, local, directory, counter, depth + 1)
            } else if (local.isFile) {
                if (local.length() > MAX_FILE_BYTES) {
                    throw IOException("Workspace file exceeds the 512 MiB limit: " + name)
                }
                val target = if (existing?.isFile == true) {
                    existing
                } else {
                    if (existing != null) deleteSafTree(existing)
                    destination.createFile(
                        java.net.URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream",
                        name
                    ) ?: throw IOException("Could not create SAF file: " + name)
                }
                val output = context.contentResolver.openOutputStream(target.uri, "wt")
                    ?: throw IOException("Could not open SAF file for writing: " + name)
                local.inputStream().use { input ->
                    output.use { outputStream ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            counter.bytes += count
                            if (counter.bytes > MAX_TOTAL_BYTES) {
                                throw IOException("Workspace exceeds FxForge's 2 GiB sync limit.")
                            }
                            outputStream.write(buffer, 0, count)
                        }
                    }
                }
                counter.files++
                if (counter.files > MAX_FILES) {
                    throw IOException("Workspace contains more than 10,000 files.")
                }
            }
        }
    }

    private fun deleteSafTree(document: DocumentFile) {
        if (document.isDirectory) {
            document.listFiles().forEach(::deleteSafTree)
        }
        if (!document.delete()) {
            throw IOException("Could not remove an obsolete SAF item: " + (document.name ?: "unnamed"))
        }
    }

    private fun copyDirectory(
        context: Context,
        source: DocumentFile,
        destination: File,
        counter: CopyCounter,
        depth: Int
    ) {
        if (depth > 32) throw IOException("Project folder nesting is too deep.")
        val entries = try {
            source.listFiles()
        } catch (exception: SecurityException) {
            throw IOException("Android denied access while reading the project folder.", exception)
        }
        entries.forEach { entry ->
            val name = entry.name ?: throw IOException("A project item has no filename.")
            if (name == "." || name == ".." || name.contains('/') || name.contains('\\')) {
                throw IOException("The project contains an invalid filename.")
            }
            if (entry.isDirectory && name in ignoredDirectoryNames) return@forEach
            if (entry.isFile && name in ignoredFileNames) return@forEach
            val target = File(destination, name)
            if (target.canonicalFile.parentFile != destination.canonicalFile) {
                throw IOException("The project contains an unsafe path.")
            }
            if (entry.isDirectory) {
                if (!target.mkdirs() && !target.isDirectory) {
                    throw IOException("Could not create workspace directory: " + name)
                }
                copyDirectory(context, entry, target, counter, depth + 1)
            } else if (entry.isFile) {
                if (!entry.canRead()) throw IOException("Cannot read project file: " + name)
                val reportedSize = entry.length()
                if (reportedSize > MAX_FILE_BYTES) {
                    throw IOException("Project file exceeds the 512 MiB limit: " + name)
                }
                val input = context.contentResolver.openInputStream(entry.uri)
                    ?: throw IOException("Could not open project file: " + name)
                input.use { sourceStream ->
                    FileOutputStream(target).use { targetStream ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var fileBytes = 0L
                        while (true) {
                            val count = sourceStream.read(buffer)
                            if (count < 0) break
                            fileBytes += count
                            counter.bytes += count
                            if (fileBytes > MAX_FILE_BYTES || counter.bytes > MAX_TOTAL_BYTES) {
                                throw IOException("The project exceeds FxForge's 2 GiB workspace transfer limit.")
                            }
                            targetStream.write(buffer, 0, count)
                        }
                    }
                }
                entry.lastModified().takeIf { it > 0 }?.let(target::setLastModified)
                counter.files++
                if (counter.files > MAX_FILES) {
                    throw IOException("The project contains more than 10,000 files.")
                }
            }
        }
    }

    private class CopyCounter(var files: Int = 0, var bytes: Long = 0)
}
