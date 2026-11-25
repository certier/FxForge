package dev.fxforge.shizuku

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuApkInstallerTest {
    @Test
    fun installationOnlySupportsRootOrAdbShellUids() {
        assertTrue(ShizukuApkInstaller.supportsUid(0))
        assertTrue(ShizukuApkInstaller.supportsUid(2000))
        assertFalse(ShizukuApkInstaller.supportsUid(10000))
        assertFalse(ShizukuApkInstaller.supportsUid(-1))
    }

    @Test
    fun installationSuccessRequiresExplicitPackageManagerConfirmation() {
        assertTrue(ShizukuApkInstaller.installationReportedSuccess(0, "Success\n", false))
        assertFalse(ShizukuApkInstaller.installationReportedSuccess(0, "", false))
        assertFalse(ShizukuApkInstaller.installationReportedSuccess(1, "Success\n", false))
        assertFalse(ShizukuApkInstaller.installationReportedSuccess(0, "Success\n", true))
    }
}
