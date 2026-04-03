package com.hunkwise.git

import com.hunkwise.util.PathNormalize
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.PathMatcher
import java.nio.file.Paths

/**
 * Manages ignore patterns from .gitignore files and custom hunkwise ignore patterns.
 * Uses simple glob matching for pattern evaluation.
 */
class GitignoreManager(private val workspaceRoot: String) {

    private val ignorePatterns = mutableListOf<IgnoreRule>()

    data class IgnoreRule(
        val pattern: String,
        val basePath: String,
        val negated: Boolean,
        val directoryOnly: Boolean
    )

    /**
     * Load all gitignore rules from workspace.
     */
    fun loadGitignores() {
        ignorePatterns.clear()
        loadGitignoreFile(File(workspaceRoot, ".gitignore"), workspaceRoot)
        // Recursively find sub-directory .gitignore files
        findSubGitignores(File(workspaceRoot))
    }

    /**
     * Set custom ignore patterns (from hunkwise settings).
     */
    fun setCustomPatterns(patterns: List<String>) {
        // Remove old custom patterns
        ignorePatterns.removeAll { it.basePath == "" }
        // Add new ones
        for (pattern in patterns) {
            ignorePatterns.add(IgnoreRule(
                pattern = pattern,
                basePath = "",
                negated = false,
                directoryOnly = false
            ))
        }
    }

    /**
     * Check if a file path should be ignored.
     */
    fun shouldIgnore(filePath: String, isDirectory: Boolean = false): Boolean {
        val normalized = PathNormalize.normalize(filePath)
        val relativePath = Paths.get(workspaceRoot).relativize(Paths.get(normalized)).toString()

        // Check custom patterns first (simple name matching)
        for (rule in ignorePatterns) {
            if (rule.basePath == "") {
                // Custom pattern: match against filename or path component
                if (matchesCustomPattern(relativePath, rule.pattern, isDirectory)) {
                    return !rule.negated
                }
            }
        }

        // Check gitignore rules (evaluated in order, last match wins)
        var ignored = false
        for (rule in ignorePatterns) {
            if (rule.basePath == "") continue // skip custom, already checked
            if (rule.directoryOnly && !isDirectory) continue

            val ruleRelBase = Paths.get(workspaceRoot).relativize(Paths.get(rule.basePath)).toString()
            val pathToMatch = if (ruleRelBase.isNotEmpty() && relativePath.startsWith(ruleRelBase)) {
                relativePath.removePrefix(ruleRelBase).removePrefix(File.separator)
            } else if (ruleRelBase.isEmpty()) {
                relativePath
            } else {
                continue
            }

            if (matchesGitignorePattern(pathToMatch, rule.pattern)) {
                ignored = !rule.negated
            }
        }
        return ignored
    }

    private fun loadGitignoreFile(file: File, basePath: String) {
        if (!file.exists()) return
        try {
            file.readLines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach

                var pattern = trimmed
                val negated = pattern.startsWith("!")
                if (negated) pattern = pattern.substring(1)
                val directoryOnly = pattern.endsWith("/")
                if (directoryOnly) pattern = pattern.removeSuffix("/")

                ignorePatterns.add(IgnoreRule(pattern, basePath, negated, directoryOnly))
            }
        } catch (_: Exception) {
            // Skip unreadable gitignore files
        }
    }

    private fun findSubGitignores(dir: File) {
        dir.listFiles()?.forEach { entry ->
            if (entry.isDirectory && entry.name != ".git") {
                val gitignoreFile = File(entry, ".gitignore")
                if (gitignoreFile.exists()) {
                    loadGitignoreFile(gitignoreFile, entry.absolutePath)
                }
                findSubGitignores(entry)
            }
        }
    }

    private fun matchesCustomPattern(relativePath: String, pattern: String, isDirectory: Boolean): Boolean {
        // Simple matching: check if any path component equals the pattern
        val components = relativePath.split(File.separator)
        if (components.any { it == pattern }) return true
        // Also try glob matching
        return try {
            val matcher: PathMatcher = FileSystems.getDefault().getPathMatcher("glob:$pattern")
            matcher.matches(Paths.get(relativePath))
        } catch (_: Exception) {
            false
        }
    }

    private fun matchesGitignorePattern(pathToMatch: String, pattern: String): Boolean {
        // Convert gitignore pattern to glob
        val globPattern = if (pattern.contains("/")) {
            // Anchored pattern
            pattern
        } else {
            // Unanchored — match in any directory
            "**/$pattern"
        }

        return try {
            val matcher: PathMatcher = FileSystems.getDefault().getPathMatcher("glob:$globPattern")
            matcher.matches(Paths.get(pathToMatch))
        } catch (_: Exception) {
            false
        }
    }
}
