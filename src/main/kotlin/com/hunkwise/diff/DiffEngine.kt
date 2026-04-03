package com.hunkwise.diff

import com.github.difflib.DiffUtils

object DiffEngine {

    fun computeHunks(baseline: String?, current: String): List<ParsedHunk> {
        val baselineText = baseline ?: ""
        val baselineLines = splitLines(baselineText)
        val currentLines = splitLines(current)

        val patch = DiffUtils.diff(baselineLines, currentLines)

        return patch.deltas.map { delta ->
            ParsedHunk(
                oldStart = delta.source.position + 1,
                oldLines = delta.source.size(),
                newStart = delta.target.position + 1,
                newLines = delta.target.size(),
                removedContent = delta.source.lines,
                addedContent = delta.target.lines
            )
        }
    }

    private fun splitLines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        return text.split("\n")
    }
}
