package dev.fxforge.shizuku

enum class ShizukuPrivilege(val label: String) {
    ROOT("Root"),
    ADB_SHELL("ADB shell"),
    OTHER("Other UID"),
    UNAVAILABLE("Unavailable");

    companion object {
        fun fromUid(uid: Int): ShizukuPrivilege = when {
            uid < 0 -> UNAVAILABLE
            uid == 0 -> ROOT
            uid == 2000 -> ADB_SHELL
            else -> OTHER
        }
    }
}

data class ShizukuStatus(
    val binderAvailable: Boolean = false,
    val permissionGranted: Boolean = false,
    val uid: Int? = null
) {
    val privilege: ShizukuPrivilege
        get() = uid?.let(ShizukuPrivilege::fromUid) ?: ShizukuPrivilege.UNAVAILABLE
}
