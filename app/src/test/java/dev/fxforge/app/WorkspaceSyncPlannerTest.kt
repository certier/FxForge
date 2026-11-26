package dev.fxforge.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WorkspaceSyncPlannerTest {
    @Test
    fun copiesPrivateChangesToStorage() {
        val plan = WorkspaceSyncPlanner.create(
            baseline = mapOf("app/src/Main.kt" to "old"),
            privateFiles = mapOf("app/src/Main.kt" to "private"),
            storageFiles = mapOf("app/src/Main.kt" to "old")
        )

        assertEquals(
            listOf(WorkspaceSyncChange("app/src/Main.kt", WorkspaceSyncAction.COPY_TO_STORAGE)),
            plan.changes
        )
        assertEquals("private", plan.baselineAfter?.get("app/src/Main.kt"))
        assertEquals(emptyList<WorkspaceSyncConflict>(), plan.conflicts)
    }

    @Test
    fun copiesExternalChangesIntoPrivateWorkspace() {
        val plan = WorkspaceSyncPlanner.create(
            baseline = mapOf("README.md" to "old"),
            privateFiles = mapOf("README.md" to "old"),
            storageFiles = mapOf("README.md" to "external")
        )

        assertEquals(
            listOf(WorkspaceSyncChange("README.md", WorkspaceSyncAction.COPY_TO_PRIVATE)),
            plan.changes
        )
        assertEquals("external", plan.baselineAfter?.get("README.md"))
    }

    @Test
    fun propagatesDeletionOnlyWhenOtherSideIsUnchanged() {
        val plan = WorkspaceSyncPlanner.create(
            baseline = mapOf("obsolete.txt" to "old", "external.txt" to "old"),
            privateFiles = mapOf("external.txt" to "old"),
            storageFiles = mapOf("obsolete.txt" to "old")
        )

        assertEquals(
            setOf(
                WorkspaceSyncChange("obsolete.txt", WorkspaceSyncAction.DELETE_FROM_STORAGE),
                WorkspaceSyncChange("external.txt", WorkspaceSyncAction.DELETE_FROM_PRIVATE)
            ),
            plan.changes.toSet()
        )
        assertEquals(emptyList<WorkspaceSyncConflict>(), plan.conflicts)
        assertEquals(emptyMap<String, String>(), plan.baselineAfter)
    }

    @Test
    fun reportsConflictWhenBothCopiesChangedDifferently() {
        val plan = WorkspaceSyncPlanner.create(
            baseline = mapOf("src/Main.kt" to "base"),
            privateFiles = mapOf("src/Main.kt" to "local-edit"),
            storageFiles = mapOf("src/Main.kt" to "external-edit")
        )

        assertEquals(emptyList<WorkspaceSyncChange>(), plan.changes)
        assertEquals(1, plan.conflicts.size)
        assertEquals("src/Main.kt", plan.conflicts.single().path)
        assertNull(plan.baselineAfter)
    }

    @Test
    fun reportsConflictForIndependentCreationAtSamePath() {
        val plan = WorkspaceSyncPlanner.create(
            baseline = emptyMap(),
            privateFiles = mapOf("notes.txt" to "local"),
            storageFiles = mapOf("notes.txt" to "external")
        )

        assertEquals("notes.txt", plan.conflicts.single().path)
        assertNull(plan.baselineAfter)
    }

    @Test
    fun acceptsIdenticalConcurrentChanges() {
        val plan = WorkspaceSyncPlanner.create(
            baseline = mapOf("README.md" to "base"),
            privateFiles = mapOf("README.md" to "same-new-value"),
            storageFiles = mapOf("README.md" to "same-new-value")
        )

        assertEquals(emptyList<WorkspaceSyncChange>(), plan.changes)
        assertEquals(emptyList<WorkspaceSyncConflict>(), plan.conflicts)
        assertEquals("same-new-value", plan.baselineAfter?.get("README.md"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsParentTraversalPaths() {
        WorkspaceSyncPlanner.create(
            baseline = mapOf("../escape.txt" to "hash"),
            privateFiles = emptyMap(),
            storageFiles = emptyMap()
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAbsolutePaths() {
        WorkspaceSyncPlanner.create(
            baseline = emptyMap(),
            privateFiles = mapOf("/tmp/escape.txt" to "hash"),
            storageFiles = emptyMap()
        )
    }
}
