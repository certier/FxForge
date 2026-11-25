package dev.fxforge.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectScaffolderTest {
    @Test
    fun acceptsValidProjectAndPackageNames() {
        assertNull(ProjectScaffolder.validate("MyApp_2", "dev.fxforge.my_app"))
    }

    @Test
    fun rejectsProjectNamesThatCouldBreakGeneratedFiles() {
        assertEquals(
            "Project name must start with a letter and contain only letters, numbers, _ or - (up to 40 characters).",
            ProjectScaffolder.validate("../MyApp", "dev.fxforge.myapp")
        )
    }

    @Test
    fun rejectsSingleSegmentOrMalformedPackageNames() {
        assertEquals(
            "Enter a valid package name, such as com.example.myapp.",
            ProjectScaffolder.validate("MyApp", "not-a-package")
        )
    }

    @Test
    fun rejectsEmptyValues() {
        assertEquals(
            "Project name must start with a letter and contain only letters, numbers, _ or - (up to 40 characters).",
            ProjectScaffolder.validate("", "")
        )
    }
}
