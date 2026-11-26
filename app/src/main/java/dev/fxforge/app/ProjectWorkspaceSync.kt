package dev.fxforge.app

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

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
        val parent = destination.absoluteFile.parentFile
            ?: throw IOException("The private workspace has no parent directory.")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Could not create the private workspace parent directory.")
        }

        // Never destroy the active workspace before the replacement has been
        // copied and validated. SAF providers can fail halfway through a read;
        // staging keeps the user's previous files intact in that case.
        val staging = File.createTempFile(".${destination.name}.", ".staging", parent)
        if (!staging.delete() || !staging.mkdir()) {
            throw IOException("Could not prepare a staging directory for the project.")
        }
        val backup = File.createTempFile(".${destination.name}.", ".backup", parent)
        if (!backup.delete()) {
            staging.deleteRecursively()
            throw IOException("Could not prepare a workspace recovery directory.")
        }

        var previousWorkspaceMoved = false
        try {
            val counter = CopyCounter()
            copyDirectory(context, source, staging, counter, 0)

            if (destination.exists()) {
                if (!destination.renameTo(backup)) {
                    throw IOException("Could not safely preserve the previous workspace.")
                }
                previousWorkspaceMoved = true
            }
            if (!staging.renameTo(destination)) {
                if (previousWorkspaceMoved && !backup.renameTo(destination)) {
                    throw IOException("Could not activate the new workspace or restore the previous workspace. Recovery copy: ${backup.absolutePath}")
                }
                previousWorkspaceMoved = false
                throw IOException("Could not activate the copied project workspace.")
            }
            if (previousWorkspaceMoved) backup.deleteRecursively()
            return WorkspaceCopyResult(destination, counter.files, counter.bytes)
        } catch (exception: Exception) {
            staging.deleteRecursively()
            if (previousWorkspaceMoved && !destination.exists() && backup.exists()) {
                if (!backup.renameTo(destination)) {
                    throw IOException(
                        "Workspace replacement failed and automatic recovery could not restore the previous files. Recovery copy: ${backup.absolutePath}",
                        exception
                    )
                }
            }
            throw exception
        }
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
        return writeSafFileAtomically(
            context = context,
            destinationFolder = destinationFolder,
            name = outputName,
            mimeType = "application/vnd.android.package-archive"
        ) { output ->
            artifact.inputStream().use { input ->
                val copied = input.copyTo(output, BUFFER_SIZE)
                if (copied != artifact.length()) {
                    throw IOException("The generated APK changed while it was being copied.")
                }
            }
        }
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
                val sourceLength = local.length()
                writeSafFileAtomically(
                    context = context,
                    destinationFolder = destination,
                    name = name,
                    mimeType = java.net.URLConnection.guessContentTypeFromName(name)
                        ?: "application/octet-stream"
                ) { output ->
                    local.inputStream().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var copiedForFile = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            copiedForFile += count
                            counter.bytes += count
                            if (copiedForFile > MAX_FILE_BYTES || counter.bytes > MAX_TOTAL_BYTES) {
                                throw IOException("Workspace exceeds FxForge's transfer-size limit.")
                            }
                            output.write(buffer, 0, count)
                        }
                        if (copiedForFile != sourceLength) {
                            throw IOException("Workspace file changed while it was being synced: " + name)
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

    /**
     * Writes a complete sibling document first, then swaps it into place.
     * Existing user files are never truncated in-place. Providers that do not
     * support rename fail safely and leave the previous document untouched.
     */
    private fun writeSafFileAtomically(
        context: Context,
        destinationFolder: DocumentFile,
        name: String,
        mimeType: String,
        writeContent: (java.io.OutputStream) -> Unit
    ): DocumentFile {
        val temporaryName = ".fxforge-tmp-" + UUID.randomUUID().toString()
        val backupName = ".fxforge-backup-" + UUID.randomUUID().toString()
        val staged = destinationFolder.createFile(mimeType, temporaryName)
            ?: throw IOException("Could not create a temporary SAF file for: " + name)
        var backup: DocumentFile? = null
        try {
            val output = context.contentResolver.openOutputStream(staged.uri, "wt")
                ?: throw IOException("Could not open temporary SAF file for writing: " + name)
            output.use(writeContent)

            val existing = destinationFolder.findFile(name)
            if (existing != null) {
                if (!existing.renameTo(backupName)) {
                    throw IOException(
                        "The selected storage provider cannot safely replace " + name +
                            ". The original file was left unchanged."
                    )
                }
                backup = destinationFolder.findFile(backupName) ?: existing
            }

            if (!staged.renameTo(name)) {
                val restored = backup?.renameTo(name) ?: (backup == null)
                if (!restored) {
                    throw IOException(
                        "Could not activate the new file or restore the previous file. " +
                            "The original is preserved as " + backupName
                    )
                }
                backup = null
                throw IOException("The storage provider could not activate the new file: " + name)
            }

            backup?.let(::deleteSafTree)
            return destinationFolder.findFile(name)
                ?: throw IOException("The storage provider did not return the completed file: " + name)
        } catch (exception: Exception) {
            staged.delete()
            val recoveryDocument = backup ?: destinationFolder.findFile(backupName)
            if (recoveryDocument != null && destinationFolder.findFile(name) == null) {
                if (!recoveryDocument.renameTo(name)) {
                    throw IOException(
                        "Workspace sync failed and the previous file remains available as " + backupName,
                        exception
                    )
                }
            }
            throw exception
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
