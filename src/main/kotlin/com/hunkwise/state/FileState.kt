package com.hunkwise.state

enum class FileStatus {
    IDLE,
    REVIEWING
}

data class FileState(
    val status: FileStatus,
    val baseline: String?
) {
    val isNew: Boolean get() = baseline == null
}
