package org.jjgroup.xproxy.project.core

data class ProjectRecord(
    val id: String,
    val displayName: String,
    val baseName: String,
    val createdDate: String,
    val projectDir: String,
    val dbPath: String,
    val createdAtMillis: Long,
    val lastOpenedMillis: Long
)
