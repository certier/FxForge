package dev.fxforge.shizuku

import org.junit.Assert.assertEquals
import org.junit.Test

class ShizukuStatusTest {
    @Test
    fun identifiesRootBackendFromReportedUid() {
        assertEquals(ShizukuPrivilege.ROOT, ShizukuPrivilege.fromUid(0))
    }

    @Test
    fun identifiesAdbShellBackendFromReportedUid() {
        assertEquals(ShizukuPrivilege.ADB_SHELL, ShizukuPrivilege.fromUid(2000))
    }

    @Test
    fun doesNotAssumeOtherUidsAreRoot() {
        assertEquals(ShizukuPrivilege.OTHER, ShizukuPrivilege.fromUid(10000))
    }

    @Test
    fun reportsPrivilegeUnavailableWithoutUid() {
        assertEquals(ShizukuPrivilege.UNAVAILABLE, ShizukuStatus().privilege)
    }
}
