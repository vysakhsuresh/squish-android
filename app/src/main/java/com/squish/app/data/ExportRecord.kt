package com.squish.app.data

data class ExportRecord(
    val id: String,
    val title: String,
    val outputPath: String,
    val originalSizeBytes: Long,
    val outputSizeBytes: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val createdAtMillis: Long
)
