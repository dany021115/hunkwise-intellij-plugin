package com.hunkwise.diff

data class ParsedHunk(
    val oldStart: Int,
    val oldLines: Int,
    val newStart: Int,
    val newLines: Int,
    val removedContent: List<String>,
    val addedContent: List<String>
) {
    val id: String get() = "$newStart:$newLines:$oldStart:$oldLines"

    val isInsert: Boolean get() = oldLines == 0
    val isDelete: Boolean get() = newLines == 0
    val isModify: Boolean get() = oldLines > 0 && newLines > 0
}
