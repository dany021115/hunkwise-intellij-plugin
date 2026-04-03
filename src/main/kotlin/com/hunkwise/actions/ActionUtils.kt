package com.hunkwise.actions

import com.hunkwise.state.StateManager
import java.io.File

/**
 * Build a shouldIgnore function from current state manager settings.
 */
fun buildShouldIgnore(
    stateManager: StateManager,
    hunkwiseDir: String,
    workspaceRoot: String
): (String, Boolean) -> Boolean {
    return { filePath: String, _: Boolean ->
        val settings = stateManager.settings
        filePath.startsWith(hunkwiseDir) ||
            settings.ignorePatterns.any { pattern ->
                filePath.split(File.separator).any { it == pattern }
            }
    }
}
