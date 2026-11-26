package dev.fxforge.app

internal const val WORKSPACE_DIRECTORY_HASH = "@fxforge-directory-v1"

/**
 * Compares the last common snapshot with each current copy. It never chooses a
 * winner when two sides changed the same path differently.
 */
internal enum class WorkspaceSyncAction {
    COPY_TO_PRIVATE,
    COPY_TO_STORAGE,
    CREATE_DIRECTORY_PRIVATE,
    CREATE_DIRECTORY_STORAGE,
    DELETE_FROM_PRIVATE,
    DELETE_FROM_STORAGE,
    DELETE_DIRECTORY_PRIVATE,
    DELETE_DIRECTORY_STORAGE
}

internal data class WorkspaceSyncChange(
    val path: String,
    val action: WorkspaceSyncAction
)

internal data class WorkspaceSyncConflict(
    val path: String,
    val baselineHash: String?,
    val privateHash: String?,
    val storageHash: String?
)

internal data class WorkspaceSyncPlan(
    val changes: List<WorkspaceSyncChange>,
    val conflicts: List<WorkspaceSyncConflict>,
    val baselineAfter: Map<String, String>?
)

internal object WorkspaceSyncPlanner {
    fun isDirectoryEntry(hash: String?): Boolean = hash == WORKSPACE_DIRECTORY_HASH

    fun copyAction(targetPrivate: Boolean, sourceHash: String): WorkspaceSyncAction =
        if (isDirectoryEntry(sourceHash)) {
            if (targetPrivate) WorkspaceSyncAction.CREATE_DIRECTORY_PRIVATE
            else WorkspaceSyncAction.CREATE_DIRECTORY_STORAGE
        } else {
            if (targetPrivate) WorkspaceSyncAction.COPY_TO_PRIVATE
            else WorkspaceSyncAction.COPY_TO_STORAGE
        }

    fun deleteAction(targetPrivate: Boolean, currentHash: String): WorkspaceSyncAction =
        if (isDirectoryEntry(currentHash)) {
            if (targetPrivate) WorkspaceSyncAction.DELETE_DIRECTORY_PRIVATE
            else WorkspaceSyncAction.DELETE_DIRECTORY_STORAGE
        } else {
            if (targetPrivate) WorkspaceSyncAction.DELETE_FROM_PRIVATE
            else WorkspaceSyncAction.DELETE_FROM_STORAGE
        }

    fun create(
        baseline: Map<String, String>,
        privateFiles: Map<String, String>,
        storageFiles: Map<String, String>
    ): WorkspaceSyncPlan {
        (baseline.keys + privateFiles.keys + storageFiles.keys).forEach(::requireSafeRelativePath)

        val changes = mutableListOf<WorkspaceSyncChange>()
        val conflicts = mutableListOf<WorkspaceSyncConflict>()
        val mergedBaseline = linkedMapOf<String, String>()
        val paths = (baseline.keys + privateFiles.keys + storageFiles.keys).toSortedSet()

        for (path in paths) {
            val base = baseline[path]
            val privateHash = privateFiles[path]
            val storageHash = storageFiles[path]

            if (privateHash == storageHash) {
                privateHash?.let { mergedBaseline[path] = it }
                continue
            }

            // A file and a directory cannot occupy the same path. Never infer
            // that one should replace the other during a bidirectional sync.
            if (privateHash != null && storageHash != null &&
                isDirectoryEntry(privateHash) != isDirectoryEntry(storageHash)
            ) {
                conflicts += WorkspaceSyncConflict(path, base, privateHash, storageHash)
                continue
            }

            when {
                base == null && privateHash == null && storageHash != null -> {
                    changes += WorkspaceSyncChange(path, copyAction(true, storageHash))
                    mergedBaseline[path] = storageHash
                }
                base == null && privateHash != null && storageHash == null -> {
                    changes += WorkspaceSyncChange(path, copyAction(false, privateHash))
                    mergedBaseline[path] = privateHash
                }
                base == null -> {
                    conflicts += WorkspaceSyncConflict(path, null, privateHash, storageHash)
                }
                privateHash == base && storageHash == null -> {
                    changes += WorkspaceSyncChange(path, deleteAction(true, base))
                }
                privateHash == base && storageHash != null -> {
                    changes += WorkspaceSyncChange(path, copyAction(true, storageHash))
                    mergedBaseline[path] = storageHash
                }
                storageHash == base && privateHash == null -> {
                    changes += WorkspaceSyncChange(path, deleteAction(false, base))
                }
                storageHash == base && privateHash != null -> {
                    changes += WorkspaceSyncChange(path, copyAction(false, privateHash))
                    mergedBaseline[path] = privateHash
                }
                else -> conflicts += WorkspaceSyncConflict(path, base, privateHash, storageHash)
            }
        }

        // Create directories before their children. Delete them only after all
        // contained files have been reconciled, deepest directory first.
        val orderedChanges = changes.sortedWith(
            compareBy<WorkspaceSyncChange> { actionOrder(it.action) }
                .thenBy {
                    when (it.action) {
                        WorkspaceSyncAction.CREATE_DIRECTORY_PRIVATE,
                        WorkspaceSyncAction.CREATE_DIRECTORY_STORAGE -> pathDepth(it.path)
                        WorkspaceSyncAction.DELETE_DIRECTORY_PRIVATE,
                        WorkspaceSyncAction.DELETE_DIRECTORY_STORAGE -> -pathDepth(it.path)
                        else -> 0
                    }
                }
                .thenBy { it.path }
        )

        return WorkspaceSyncPlan(
            changes = orderedChanges,
            conflicts = conflicts,
            baselineAfter = if (conflicts.isEmpty()) mergedBaseline else null
        )
    }

    private fun actionOrder(action: WorkspaceSyncAction): Int = when (action) {
        WorkspaceSyncAction.CREATE_DIRECTORY_PRIVATE,
        WorkspaceSyncAction.CREATE_DIRECTORY_STORAGE -> 0
        WorkspaceSyncAction.COPY_TO_PRIVATE,
        WorkspaceSyncAction.COPY_TO_STORAGE -> 1
        WorkspaceSyncAction.DELETE_FROM_PRIVATE,
        WorkspaceSyncAction.DELETE_FROM_STORAGE -> 2
        WorkspaceSyncAction.DELETE_DIRECTORY_PRIVATE,
        WorkspaceSyncAction.DELETE_DIRECTORY_STORAGE -> 3
    }

    private fun pathDepth(path: String): Int = path.count { it == '/' }

    private fun requireSafeRelativePath(path: String) {
        require(path.isNotBlank() && !path.startsWith('/') && !path.startsWith('\\')) {
            "Workspace paths must be non-empty relative paths."
        }
        require(path.split('/').all { segment ->
            segment.isNotBlank() && segment != "." && segment != ".." && !segment.contains('\\')
        }) {
            "Workspace path contains an unsafe segment."
        }
    }
}
