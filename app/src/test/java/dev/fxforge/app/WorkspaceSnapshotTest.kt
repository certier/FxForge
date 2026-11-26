package dev.fxforge.app

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceSnapshotTest {
    @Test
    fun createsAnIsolatedSnapshotAndExcludesGeneratedFiles() {
        val root = Files.createTempDirectory("fxforge-workspace-test").toFile()
        try {
            val source = File(root, "source").apply { mkdirs() }
            File(source, "README.md").writeText("# project")
            File(source, "local.properties").writeText("sdk.dir=/host/sdk")
            File(source, "build/intermediates/output.tmp").apply {
                parentFile.mkdirs()
                writeText("generated")
            }
            File(source, ".gradle/caches/state.bin").apply {
                parentFile.mkdirs()
                writeText("cache")
            }
            File(source, "app/src/Main.kt").apply {
                parentFile.mkdirs()
                writeText("fun main() = Unit")
            }
            File(source, "empty-folder").mkdirs()

            val destination = File(root, "snapshot")
            val result = ProjectWorkspaceSync.copyPrivateWorkspaceSnapshot(source, destination)

            assertTrue(destination.isDirectory)
            assertEquals(destination.canonicalFile, result.directory.canonicalFile)
            assertEquals(2, result.filesCopied)
            assertEquals("# project", File(destination, "README.md").readText())
            assertEquals("fun main() = Unit", File(destination, "app/src/Main.kt").readText())
            assertTrue(File(destination, "empty-folder").isDirectory)
            assertFalse(File(destination, "local.properties").exists())
            assertFalse(File(destination, "build").exists())
            assertFalse(File(destination, ".gradle").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun workspaceManifestIncludesEmptyAndNonEmptyFolders() {
        val root = Files.createTempDirectory("fxforge-workspace-test").toFile()
        try {
            val workspace = File(root, "workspace").apply { mkdirs() }
            File(workspace, "empty-folder").mkdirs()
            File(workspace, "src/nested").mkdirs()
            File(workspace, "src/nested/Main.kt").writeText("fun main() = Unit")

            val manifest = ProjectWorkspaceSync.snapshotPrivateFiles(workspace)

            assertEquals(WORKSPACE_DIRECTORY_HASH, manifest["empty-folder"])
            assertEquals(WORKSPACE_DIRECTORY_HASH, manifest["src"])
            assertEquals(WORKSPACE_DIRECTORY_HASH, manifest["src/nested"])
            assertTrue(manifest["src/nested/Main.kt"]?.matches(Regex("[0-9a-f]{64}")) == true)
            assertEquals(4, manifest.size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun refusesToOverwriteAnExistingBuildDirectory() {
        val root = Files.createTempDirectory("fxforge-workspace-test").toFile()
        try {
            val source = File(root, "source").apply { mkdirs() }
            File(source, "README.md").writeText("project")
            val destination = File(root, "snapshot").apply {
                mkdirs()
                resolve("keep.txt").writeText("keep")
            }

            try {
                ProjectWorkspaceSync.copyPrivateWorkspaceSnapshot(source, destination)
                throw AssertionError("Expected the existing build directory to be preserved.")
            } catch (_: IOException) {
                assertEquals("keep", File(destination, "keep.txt").readText())
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun refusesToCopySymbolicLinksIntoBuildSnapshot() {
        val root = Files.createTempDirectory("fxforge-workspace-test").toFile()
        try {
            val source = File(root, "source").apply { mkdirs() }
            val outside = File(root, "outside.txt").apply { writeText("outside") }
            Files.createSymbolicLink(File(source, "linked.txt").toPath(), outside.toPath())

            try {
                ProjectWorkspaceSync.copyPrivateWorkspaceSnapshot(source, File(root, "snapshot"))
                throw AssertionError("Expected symbolic links to be rejected.")
            } catch (_: IOException) {
                assertFalse(File(root, "snapshot").exists())
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
