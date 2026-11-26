package dev.fxforge.app

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
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
    private val ignoredSyncNames = setOf(".fxforge-tmp-", ".fxforge-backup-", ".fxforge-delete-")

    internal data class SyncResult(
        val copiedToPrivate: Int,
        val copiedToStorage: Int,
        val directoriesCreatedPrivate: Int,
        val directoriesCreatedStorage: Int,
        val deletedFromPrivate: Int,
        val deletedFromStorage: Int,
        val directoriesDeletedPrivate: Int,
        val directoriesDeletedStorage: Int,
        val conflicts: List<WorkspaceSyncConflict>,
        val baselineAfter: Map<String, String>?
    )

    /**
     * Reconciles the private Linux copy and the user-selected SAF tree against
     * the last common snapshot. Conflicts are never resolved implicitly.
     */
    internal fun synchronize(
        context: Context,
        storageRoot: DocumentFile,
        privateRoot: File,
        baseline: Map<String, String>,
        preferPrivateOnConflicts: Boolean? = null
    ): SyncResult {
        if (!storageRoot.isDirectory || !storageRoot.canRead() || !storageRoot.canWrite()) {
            throw IOException("The selected project folder is no longer readable and writable.")
        }
        if (!privateRoot.isDirectory) {
            throw IOException("The private Linux workspace is unavailable.")
        }

        val privateSnapshot = snapshotPrivate(privateRoot)
        val storageSnapshot = snapshotSaf(context, storageRoot)
        val plan = WorkspaceSyncPlanner.create(baseline, privateSnapshot, storageSnapshot)
        if (plan.conflicts.isNotEmpty() && preferPrivateOnConflicts == null) {
            return SyncResult(0, 0, 0, 0, 0, 0, 0, 0, plan.conflicts, null)
        }

        val conflictChanges = plan.conflicts.map { conflict ->
            val action = if (preferPrivateOnConflicts == true) {
                val currentStorage = conflict.storageHash
                    ?: throw IOException("Storage version disappeared while resolving conflict: " + conflict.path)
                if (conflict.privateHash == null) {
                    WorkspaceSyncPlanner.deleteAction(targetPrivate = false, currentHash = currentStorage)
                } else {
                    WorkspaceSyncPlanner.copyAction(targetPrivate = false, sourceHash = conflict.privateHash)
                }
            } else {
                val currentPrivate = conflict.privateHash
                    ?: throw IOException("Private version disappeared while resolving conflict: " + conflict.path)
                if (conflict.storageHash == null) {
                    WorkspaceSyncPlanner.deleteAction(targetPrivate = true, currentHash = currentPrivate)
                } else {
                    WorkspaceSyncPlanner.copyAction(targetPrivate = true, sourceHash = conflict.storageHash)
                }
            }
            WorkspaceSyncChange(conflict.path, action)
        }
        val actions = WorkspaceSyncPlanner.orderChanges(plan.changes + conflictChanges)

        var copiedToPrivate = 0
        var copiedToStorage = 0
        var directoriesCreatedPrivate = 0
        var directoriesCreatedStorage = 0
        var deletedFromPrivate = 0
        var deletedFromStorage = 0
        var directoriesDeletedPrivate = 0
        var directoriesDeletedStorage = 0
        for (change in actions) {
            val expectedHash = expectedHashForAction(
                action = change.action,
                path = change.path,
                baseline = baseline,
                privateFiles = privateSnapshot,
                storageFiles = storageSnapshot
            ) ?: throw IOException("Workspace changed while preparing sync: " + change.path)

            when (change.action) {
                WorkspaceSyncAction.COPY_TO_PRIVATE -> {
                    copySafFileToPrivate(context, storageRoot, privateRoot, change.path, expectedHash)
                    copiedToPrivate++
                }
                WorkspaceSyncAction.COPY_TO_STORAGE -> {
                    copyPrivateFileToSaf(context, privateRoot, storageRoot, change.path, expectedHash)
                    copiedToStorage++
                }
                WorkspaceSyncAction.CREATE_DIRECTORY_PRIVATE -> {
                    createPrivateDirectory(privateRoot, change.path)
                    directoriesCreatedPrivate++
                }
                WorkspaceSyncAction.CREATE_DIRECTORY_STORAGE -> {
                    createStorageDirectory(storageRoot, change.path)
                    directoriesCreatedStorage++
                }
                WorkspaceSyncAction.DELETE_FROM_PRIVATE -> {
                    deletePrivateFile(privateRoot, change.path, expectedHash)
                    deletedFromPrivate++
                }
                WorkspaceSyncAction.DELETE_FROM_STORAGE -> {
                    deleteStorageFile(context, storageRoot, change.path, expectedHash)
                    deletedFromStorage++
                }
                WorkspaceSyncAction.DELETE_DIRECTORY_PRIVATE -> {
                    deletePrivateDirectory(privateRoot, change.path)
                    directoriesDeletedPrivate++
                }
                WorkspaceSyncAction.DELETE_DIRECTORY_STORAGE -> {
                    deleteStorageDirectory(storageRoot, change.path)
                    directoriesDeletedStorage++
                }
            }
        }

        // Do not advance the common baseline unless both copies actually match.
        // A file or folder edited during sync is left as a detectable change.
        val privateAfter = snapshotPrivate(privateRoot)
        val storageAfter = snapshotSaf(context, storageRoot)
        if (privateAfter != storageAfter) {
            throw IOException("Workspace changed during synchronization. Nothing was marked as fully synced; run Sync again.")
        }

        return SyncResult(
            copiedToPrivate = copiedToPrivate,
            copiedToStorage = copiedToStorage,
            directoriesCreatedPrivate = directoriesCreatedPrivate,
            directoriesCreatedStorage = directoriesCreatedStorage,
            deletedFromPrivate = deletedFromPrivate,
            deletedFromStorage = deletedFromStorage,
            directoriesDeletedPrivate = directoriesDeletedPrivate,
            directoriesDeletedStorage = directoriesDeletedStorage,
            conflicts = emptyList(),
            baselineAfter = privateAfter
        )
    }

    internal fun snapshotPrivateFiles(root: File): Map<String, String> = snapshotPrivate(root)

    internal fun expectedHashForAction(
        action: WorkspaceSyncAction,
        path: String,
        baseline: Map<String, String>,
        privateFiles: Map<String, String>,
        storageFiles: Map<String, String>
    ): String? = when (action) {
        WorkspaceSyncAction.COPY_TO_PRIVATE,
        WorkspaceSyncAction.CREATE_DIRECTORY_PRIVATE -> storageFiles[path]
        WorkspaceSyncAction.COPY_TO_STORAGE,
        WorkspaceSyncAction.CREATE_DIRECTORY_STORAGE -> privateFiles[path]
        // Deletions verify the current entry on the side being removed, not the
        // old common baseline. This also covers explicit conflict choices.
        WorkspaceSyncAction.DELETE_FROM_PRIVATE,
        WorkspaceSyncAction.DELETE_DIRECTORY_PRIVATE -> privateFiles[path]
        WorkspaceSyncAction.DELETE_FROM_STORAGE,
        WorkspaceSyncAction.DELETE_DIRECTORY_STORAGE -> storageFiles[path]
    }

    private fun snapshotPrivate(root: File): Map<String, String> {
        if (!root.isDirectory) throw IOException("The private workspace directory is unavailable.")
        val canonicalRoot = root.canonicalFile
        val result = linkedMapOf<String, String>()
        var totalBytes = 0L

        fun visit(directory: File, relativeDirectory: String, depth: Int) {
            if (depth > 32) throw IOException("Workspace folder nesting is too deep.")
            val children = directory.listFiles()
                ?: throw IOException("Could not read workspace directory: " + directory.name)
            children.sortedBy { it.name }.forEach { child ->
                val name = child.name
                validatePathSegment(name)
                if (child.isDirectory && name in ignoredDirectoryNames) return@forEach
                if (child.isFile && name in ignoredFileNames) return@forEach
                if (isReservedSyncName(name)) return@forEach
                if (java.nio.file.Files.isSymbolicLink(child.toPath())) {
                    throw IOException("Workspace contains a symbolic link that cannot be synchronized safely: " + name)
                }
                val canonical = child.canonicalFile
                if (!canonical.path.startsWith(canonicalRoot.path + File.separator)) {
                    throw IOException("Workspace contains an unsafe path: " + name)
                }
                val relative = if (relativeDirectory.isEmpty()) name else "$relativeDirectory/$name"
                if (child.isDirectory) {
                    result[relative] = WORKSPACE_DIRECTORY_HASH
                    if (result.size > MAX_FILES) {
                        throw IOException("Workspace contains more than 10,000 workspace entries.")
                    }
                    visit(child, relative, depth + 1)
                } else if (child.isFile) {
                    if (child.length() > MAX_FILE_BYTES) {
                        throw IOException("Workspace file exceeds the 512 MiB limit: " + relative)
                    }
                    totalBytes += child.length()
                    if (totalBytes > MAX_TOTAL_BYTES) {
                        throw IOException("Workspace exceeds FxForge's 2 GiB synchronization limit.")
                    }
                    result[relative] = hashFile(child)
                    if (result.size > MAX_FILES) {
                        throw IOException("Workspace contains more than 10,000 workspace entries.")
                    }
                }
            }
        }

        visit(root, "", 0)
        return result
    }

    private fun snapshotSaf(context: Context, root: DocumentFile): Map<String, String> {
        val result = linkedMapOf<String, String>()
        var totalBytes = 0L

        fun visit(directory: DocumentFile, relativeDirectory: String, depth: Int) {
            if (depth > 32) throw IOException("Workspace folder nesting is too deep.")
            val children = try {
                directory.listFiles().sortedBy { it.name ?: "" }
            } catch (exception: SecurityException) {
                throw IOException("Android denied access while scanning the workspace.", exception)
            }
            children.forEach { child ->
                val name = child.name ?: throw IOException("A workspace item has no filename.")
                validatePathSegment(name)
                if (child.isDirectory && name in ignoredDirectoryNames) return@forEach
                if (child.isFile && name in ignoredFileNames) return@forEach
                if (name.startsWith(".fxforge-tmp-") || name.startsWith(".fxforge-backup-") ||
                    name.startsWith(".fxforge-delete-")) return@forEach
                val relative = if (relativeDirectory.isEmpty()) name else "$relativeDirectory/$name"
                if (child.isDirectory) {
                    result[relative] = WORKSPACE_DIRECTORY_HASH
                    if (result.size > MAX_FILES) {
                        throw IOException("Workspace contains more than 10,000 workspace entries.")
                    }
                    visit(child, relative, depth + 1)
                } else if (child.isFile) {
                    if (!child.canRead()) throw IOException("Cannot read workspace file: " + relative)
                    val digest = MessageDigest.getInstance("SHA-256")
                    var fileBytes = 0L
                    val input = context.contentResolver.openInputStream(child.uri)
                        ?: throw IOException("Could not open workspace file: " + relative)
                    input.use { stream ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            fileBytes += count
                            totalBytes += count
                            if (fileBytes > MAX_FILE_BYTES || totalBytes > MAX_TOTAL_BYTES) {
                                throw IOException("Workspace exceeds FxForge's transfer-size limit.")
                            }
                            digest.update(buffer, 0, count)
                        }
                    }
                    result[relative] = digest.digest().toHex()
                    if (result.size > MAX_FILES) {
                        throw IOException("Workspace contains more than 10,000 files.")
                    }
                }
            }
        }

        visit(root, "", 0)
        return result
    }

    private fun copySafFileToPrivate(
        context: Context,
        storageRoot: DocumentFile,
        privateRoot: File,
        relativePath: String,
        expectedHash: String
    ) {
        val source = findSafFile(storageRoot, relativePath)
            ?: throw IOException("Storage file disappeared during sync: " + relativePath)
        if (!source.isFile || !source.canRead()) throw IOException("Cannot read storage file: " + relativePath)
        val target = resolvePrivateFile(privateRoot, relativePath)
        val parent = target.parentFile ?: throw IOException("Invalid workspace destination: " + relativePath)
        if (!parent.exists() && !parent.mkdirs()) throw IOException("Could not create workspace directory: " + relativePath)
        val staging = File.createTempFile(".fxforge-sync-", ".tmp", parent)
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            val input = context.contentResolver.openInputStream(source.uri)
                ?: throw IOException("Could not open storage file: " + relativePath)
            input.use { sourceStream ->
                FileOutputStream(staging).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var countTotal = 0L
                    while (true) {
                        val count = sourceStream.read(buffer)
                        if (count < 0) break
                        countTotal += count
                        if (countTotal > MAX_FILE_BYTES) throw IOException("Workspace file exceeds 512 MiB: " + relativePath)
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (digest.digest().toHex() != expectedHash) {
                throw IOException("Storage file changed while it was being synchronized: " + relativePath)
            }
            replacePrivateFile(staging, target, relativePath)
        } catch (exception: Exception) {
            staging.delete()
            throw exception
        }
    }

    private fun copyPrivateFileToSaf(
        context: Context,
        privateRoot: File,
        storageRoot: DocumentFile,
        relativePath: String,
        expectedHash: String
    ) {
        val source = resolvePrivateFile(privateRoot, relativePath)
        if (!source.isFile || java.nio.file.Files.isSymbolicLink(source.toPath())) {
            throw IOException("Private workspace file disappeared or became unsafe: " + relativePath)
        }
        val parent = ensureSafParent(storageRoot, relativePath)
        val digest = MessageDigest.getInstance("SHA-256")
        writeSafFileAtomically(
            context = context,
            destinationFolder = parent,
            name = relativePath.substringAfterLast('/'),
            mimeType = java.net.URLConnection.guessContentTypeFromName(relativePath)
                ?: "application/octet-stream"
        ) { output ->
            source.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                var copied = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    copied += count
                    if (copied > MAX_FILE_BYTES) throw IOException("Workspace file exceeds 512 MiB: " + relativePath)
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
            }
            if (digest.digest().toHex() != expectedHash) {
                throw IOException("Private workspace file changed while it was being synchronized: " + relativePath)
            }
        }
    }

    private fun createPrivateDirectory(root: File, relativePath: String) {
        val target = resolvePrivateFile(root, relativePath)
        if (target.exists()) {
            if (target.isDirectory && !java.nio.file.Files.isSymbolicLink(target.toPath())) return
            throw IOException("A file or symbolic link blocks the workspace folder path: " + relativePath)
        }
        val parent = target.parentFile
            ?: throw IOException("Invalid workspace folder path: " + relativePath)
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Could not create workspace folder parent: " + relativePath)
        }
        if (!target.mkdir()) {
            throw IOException("Could not create workspace folder: " + relativePath)
        }
    }

    private fun createStorageDirectory(root: DocumentFile, relativePath: String) {
        var current = root
        safeSegments(relativePath).forEach { segment ->
            val child = current.findFile(segment)
            current = when {
                child == null -> current.createDirectory(segment)
                    ?: throw IOException("Could not create storage folder: " + relativePath)
                child.isDirectory -> child
                else -> throw IOException("A file blocks the storage folder path: " + relativePath)
            }
        }
    }

    private fun deletePrivateDirectory(root: File, relativePath: String) {
        val target = resolvePrivateFile(root, relativePath)
        if (!target.exists()) return
        if (!target.isDirectory || java.nio.file.Files.isSymbolicLink(target.toPath())) {
            throw IOException("Workspace folder changed type before deletion: " + relativePath)
        }
        val children = target.listFiles()
            ?: throw IOException("Could not inspect workspace folder before deletion: " + relativePath)
        if (children.isNotEmpty()) {
            throw IOException("Workspace folder is not empty and was preserved: " + relativePath)
        }
        if (!target.delete()) {
            throw IOException("Could not remove empty workspace folder: " + relativePath)
        }
    }

    private fun deleteStorageDirectory(root: DocumentFile, relativePath: String) {
        val target = findSafNode(root, relativePath) ?: return
        if (!target.isDirectory) {
            throw IOException("Storage folder changed type before deletion: " + relativePath)
        }
        if (target.listFiles().isNotEmpty()) {
            throw IOException("Storage folder is not empty and was preserved: " + relativePath)
        }
        if (!target.delete()) {
            throw IOException("Could not remove empty storage folder: " + relativePath)
        }
    }

    private fun deletePrivateFile(root: File, relativePath: String, expectedHash: String) {
        val target = resolvePrivateFile(root, relativePath)
        if (!target.exists()) return
        if (!target.isFile || hashFile(target) != expectedHash) {
            throw IOException("Private workspace file changed before deletion: " + relativePath)
        }
        if (!target.delete()) throw IOException("Could not remove deleted workspace file: " + relativePath)
    }

    private fun deleteStorageFile(
        context: Context,
        root: DocumentFile,
        relativePath: String,
        expectedHash: String
    ) {
        val target = findSafFile(root, relativePath) ?: return
        if (!target.isFile || hashSafFile(context, target) != expectedHash) {
            throw IOException("Storage file changed before deletion: " + relativePath)
        }
        if (!target.delete()) throw IOException("Could not remove deleted storage file: " + relativePath)
    }

    private fun hashSafFile(context: Context, file: DocumentFile): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = context.contentResolver.openInputStream(file.uri)
            ?: throw IOException("Could not open storage file for verification.")
        input.use { stream ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun hashFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun findSafNode(root: DocumentFile, relativePath: String): DocumentFile? {
        var current = root
        val segments = safeSegments(relativePath)
        segments.forEachIndexed { index, segment ->
            current = current.findFile(segment) ?: return null
            if (index < segments.lastIndex && !current.isDirectory) return null
        }
        return current
    }

    private fun findSafFile(root: DocumentFile, relativePath: String): DocumentFile? =
        findSafNode(root, relativePath)?.takeIf { it.isFile }

    private fun ensureSafParent(root: DocumentFile, relativePath: String): DocumentFile {
        var current = root
        val segments = safeSegments(relativePath)
        segments.dropLast(1).forEach { segment ->
            val child = current.findFile(segment)
            current = when {
                child == null -> current.createDirectory(segment)
                    ?: throw IOException("Could not create storage directory: " + segment)
                child.isDirectory -> child
                else -> throw IOException("A file blocks workspace directory creation: " + segment)
            }
        }
        return current
    }

    private fun resolvePrivateFile(root: File, relativePath: String): File {
        val segments = safeSegments(relativePath)
        val rootCanonical = root.canonicalFile
        val target = segments.fold(rootCanonical) { current, segment -> File(current, segment) }
        if (!target.canonicalPath.startsWith(rootCanonical.path + File.separator)) {
            throw IOException("Workspace path escapes the private root: " + relativePath)
        }
        return target
    }

    private fun replacePrivateFile(staging: File, target: File, relativePath: String) {
        if (java.nio.file.Files.isSymbolicLink(target.toPath()) ||
            (target.exists() && !target.isFile)
        ) {
            throw IOException("A folder or symbolic link blocks the synchronized file path: " + relativePath)
        }
        val backup = File(target.parentFile, ".fxforge-backup-" + UUID.randomUUID().toString())
        var movedOldFile = false
        if (target.exists()) {
            if (!target.renameTo(backup)) {
                throw IOException("Could not preserve the old workspace file before replacement: " + relativePath)
            }
            movedOldFile = true
        }
        if (!staging.renameTo(target)) {
            if (movedOldFile && !backup.renameTo(target)) {
                throw IOException("Could not replace or restore workspace file. Recovery copy: " + backup.absolutePath)
            }
            throw IOException("Could not activate synchronized workspace file: " + relativePath)
        }
        // A failed cleanup must not turn an already-committed replacement into
        // a reported failure. Keep the backup for recovery/cleanup instead.
        if (movedOldFile) backup.delete()
    }

    private fun safeSegments(relativePath: String): List<String> {
        if (relativePath.isBlank() || relativePath.startsWith('/')) {
            throw IOException("Workspace path must be a non-empty relative path.")
        }
        val segments = relativePath.split('/')
        segments.forEach(::validatePathSegment)
        return segments
    }

    private fun isReservedSyncName(name: String): Boolean =
        ignoredSyncNames.any(name::startsWith)

    private fun validatePathSegment(name: String) {
        if (name.isBlank() || name == "." || name == ".." || name.contains('/') || name.contains('\\')) {
            throw IOException("Workspace contains an unsafe path segment.")
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    /**
     * Imports a SAF project only when the source and private copy are still
     * identical after transfer. Intended for new staging/build directories,
     * never for replacing an existing user's active workspace.
     */
    internal fun copyFromSafVerified(
        context: Context,
        source: DocumentFile,
        destination: File
    ): WorkspaceCopyResult {
        if (destination.exists()) {
            throw IOException("Verified project import requires a new staging directory.")
        }
        val before = snapshotSaf(context, source)
        val copied = copyFromSaf(context, source, destination)
        try {
            val privateCopy = snapshotPrivate(destination)
            val after = snapshotSaf(context, source)
            if (before != privateCopy || before != after) {
                throw IOException(
                    "The selected project changed while it was being imported. " +
                        "The incomplete private copy was removed; select the folder again to retry."
                )
            }
            return copied
        } catch (exception: Exception) {
            destination.deleteRecursively()
            throw exception
        }
    }

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

    /**
     * Copies the live private Workspace into an isolated build directory.
     * Each file is checked against a pre-copy hash and the source tree is
     * rescanned afterwards, so an editor write during staging cannot silently
     * create a mixed project snapshot.
     */
    internal fun copyPrivateWorkspaceSnapshot(
        source: File,
        destination: File
    ): WorkspaceCopyResult {
        if (!source.isDirectory || java.nio.file.Files.isSymbolicLink(source.toPath())) {
            throw IOException("The private project workspace is unavailable or unsafe.")
        }
        if (destination.exists()) {
            throw IOException("The isolated build workspace already exists.")
        }
        val parent = destination.absoluteFile.parentFile
            ?: throw IOException("The build workspace has no parent directory.")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Could not create the isolated build workspace parent.")
        }
        val staging = File(parent, ".fxforge-build-snapshot-" + UUID.randomUUID().toString())
        if (!staging.mkdirs()) throw IOException("Could not create the build snapshot staging directory.")

        try {
            val expectedSnapshot = snapshotPrivate(source)
            val counter = CopyCounter()

            fun copyDirectory(current: File, targetDirectory: File, relativeDirectory: String, depth: Int) {
                if (depth > 32) throw IOException("Workspace folder nesting is too deep.")
                val children = current.listFiles()
                    ?: throw IOException("Could not read workspace directory: " + current.name)
                children.sortedBy { it.name }.forEach { child ->
                    val name = child.name
                    validatePathSegment(name)
                    if (child.isDirectory && name in ignoredDirectoryNames) return@forEach
                    if (child.isFile && name in ignoredFileNames) return@forEach
                    if (isReservedSyncName(name)) return@forEach
                    if (java.nio.file.Files.isSymbolicLink(child.toPath())) {
                        throw IOException("Workspace contains a symbolic link that cannot be built safely: " + name)
                    }
                    val canonical = child.canonicalFile
                    if (!canonical.path.startsWith(source.canonicalPath + File.separator)) {
                        throw IOException("Workspace contains an unsafe path: " + name)
                    }
                    val relative = if (relativeDirectory.isEmpty()) name else "$relativeDirectory/$name"
                    val target = File(targetDirectory, name)
                    if (child.isDirectory) {
                        if (!target.mkdirs() && !target.isDirectory) {
                            throw IOException("Could not create build snapshot directory: " + relative)
                        }
                        copyDirectory(child, target, relative, depth + 1)
                    } else if (child.isFile) {
                        if (child.length() > MAX_FILE_BYTES) {
                            throw IOException("Workspace file exceeds the 512 MiB limit: " + relative)
                        }
                        val expectedHash = expectedSnapshot[relative]
                            ?: throw IOException("Workspace changed while preparing the build: " + relative)
                        val digest = MessageDigest.getInstance("SHA-256")
                        var copied = 0L
                        child.inputStream().use { input ->
                            FileOutputStream(target).use { output ->
                                val buffer = ByteArray(BUFFER_SIZE)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    copied += count
                                    counter.bytes += count
                                    if (copied > MAX_FILE_BYTES || counter.bytes > MAX_TOTAL_BYTES) {
                                        throw IOException("Workspace exceeds FxForge's build snapshot transfer limit.")
                                    }
                                    digest.update(buffer, 0, count)
                                    output.write(buffer, 0, count)
                                }
                            }
                        }
                        if (digest.digest().toHex() != expectedHash) {
                            throw IOException("Workspace file changed while preparing the build: " + relative)
                        }
                        child.lastModified().takeIf { it > 0 }?.let(target::setLastModified)
                        counter.files++
                        if (counter.files > MAX_FILES) {
                            throw IOException("Workspace contains more than 10,000 files.")
                        }
                    }
                }
            }

            copyDirectory(source, staging, "", 0)
            if (snapshotPrivate(source) != expectedSnapshot ||
                snapshotPrivate(staging) != expectedSnapshot
            ) {
                throw IOException("Workspace changed while the build snapshot was being prepared. Save and retry the build.")
            }
            if (!staging.renameTo(destination)) {
                throw IOException("Could not activate the isolated build snapshot.")
            }
            return WorkspaceCopyResult(destination, counter.files, counter.bytes)
        } catch (exception: Exception) {
            staging.deleteRecursively()
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
        if (name.isBlank() || name == "." || name == ".." ||
            name.contains('/') || name.contains('\\')
        ) {
            throw IOException("A synchronized document must have a single safe filename.")
        }
        val temporaryName = ".fxforge-tmp-" + UUID.randomUUID().toString()
        val backupName = ".fxforge-backup-" + UUID.randomUUID().toString()
        val staged = destinationFolder.createFile(mimeType, temporaryName)
            ?: throw IOException("Could not create a temporary SAF file for: " + name)
        var backup: DocumentFile? = null
        var activated = false
        try {
            val output = context.contentResolver.openOutputStream(staged.uri, "wt")
                ?: throw IOException("Could not open temporary SAF file for writing: " + name)
            output.use(writeContent)

            val existing = destinationFolder.findFile(name)
            if (existing?.isDirectory == true) {
                throw IOException("A folder already exists at the destination for " + name + ". Rename it before synchronizing this file.")
            }
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
            activated = true

            val result = destinationFolder.findFile(name)
                ?: staged.takeIf { it.isFile && it.name == name }
                ?: throw IOException(
                    "The replacement was activated but the storage provider could not verify it. " +
                        "The previous file, when present, is preserved as " + backupName
                )

            // Never report a committed replacement as failed just because the
            // provider refuses to delete its hidden recovery document.
            backup?.delete()
            return result
        } catch (exception: Exception) {
            if (!activated) staged.delete()
            if (!activated) {
                val recoveryDocument = backup ?: destinationFolder.findFile(backupName)
                if (recoveryDocument != null && destinationFolder.findFile(name) == null) {
                    if (!recoveryDocument.renameTo(name)) {
                        throw IOException(
                            "Workspace sync failed and the previous file remains available as " + backupName,
                            exception
                        )
                    }
                }
            }
            throw exception
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
            if (isReservedSyncName(name)) return@forEach
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
