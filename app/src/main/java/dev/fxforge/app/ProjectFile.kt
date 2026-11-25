package dev.fxforge.app

internal data class ProjectFile(
    val uri: String,
    val name: String,
    val canWrite: Boolean
)
