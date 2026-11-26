package dev.fxforge.app

/**
 * Compares the last successfully synchronized workspace with both current
 * copies. The plan never chooses a winner when both sides changed differently.
 */
internal enum class WorkspaceSyncAction {
    COPY_TO_PRIVATE,
    COPY_TO_STORAGE,
    DELETE_FROM_PRIVATE,
    DELETE_FROM_STORAGE
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

            when {
                base == null && privateHash == null && storageHash != null -> {
                    changes += WorkspaceSyncChange(path, WorkspaceSyncAction.COPY_TO_PRIVATE)
                    mergedBaseline[path] = storageHash
                }
                base == null && privateHash != null && storageHash == null -> {
                    changes += WorkspaceSyncChange(path, WorkspaceSyncAction.COPY_TO_STORAGE)
                    mergedBaseline[path] = privateHash
                }
                base == null -> {
                    conflicts += WorkspaceSyncConflict(path, null, privateHash, storageHash)
                }
                privateHash == base && storageHash == null -> {
                    changes += WorkspaceSyncChange(path, WorkspaceSyncAction.DELETE_FROM_PRIVATE)
                }
                privateHash == base && storageHash != null -> {
                    changes += WorkspaceSyncChange(path, WorkspaceSyncAction.COPY_TO_PRIVATE)
                    mergedBaseline[path] = storageHash
                }
                storageHash == base && privateHash == null -> {
                    changes += WorkspaceSyncChange(path, WorkspaceSyncAction.DELETE_FROM_STORAGE)
                }
                storageHash == base && privateHash != null -> {
                    changes += WorkspaceSyncChange(path, WorkspaceSyncAction.COPY_TO_STORAGE)
                    mergedBaseline[path] = privateHash
                }
                else -> conflicts += WorkspaceSyncConflict(path, base, privateHash, storageHash)
            }
        }

        return WorkspaceSyncPlan(
            changes = changes,
            conflicts = conflicts,
            baselineAfter = if (conflicts.isEmpty()) mergedBaseline else null
        )
    }

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
