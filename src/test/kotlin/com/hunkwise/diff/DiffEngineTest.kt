package com.hunkwise.diff

import org.junit.Assert.*
import org.junit.Test

class DiffEngineTest {

    @Test
    fun `no diff for identical content`() {
        val hunks = DiffEngine.computeHunks("hello\nworld", "hello\nworld")
        assertTrue(hunks.isEmpty())
    }

    @Test
    fun `detect added lines`() {
        val hunks = DiffEngine.computeHunks("line1\nline3", "line1\nline2\nline3")
        assertEquals(1, hunks.size)
        val hunk = hunks[0]
        assertEquals(1, hunk.newLines)
        assertEquals(0, hunk.oldLines)
        assertEquals(listOf("line2"), hunk.addedContent)
        assertTrue(hunk.isInsert)
    }

    @Test
    fun `detect removed lines`() {
        val hunks = DiffEngine.computeHunks("line1\nline2\nline3", "line1\nline3")
        assertEquals(1, hunks.size)
        val hunk = hunks[0]
        assertEquals(0, hunk.newLines)
        assertEquals(1, hunk.oldLines)
        assertEquals(listOf("line2"), hunk.removedContent)
        assertTrue(hunk.isDelete)
    }

    @Test
    fun `detect modified lines`() {
        val hunks = DiffEngine.computeHunks("line1\nold\nline3", "line1\nnew\nline3")
        assertEquals(1, hunks.size)
        val hunk = hunks[0]
        assertTrue(hunk.isModify)
        assertEquals(listOf("old"), hunk.removedContent)
        assertEquals(listOf("new"), hunk.addedContent)
    }

    @Test
    fun `null baseline means new file`() {
        val hunks = DiffEngine.computeHunks(null, "line1\nline2")
        assertEquals(1, hunks.size)
        assertEquals(2, hunks[0].newLines)
        assertEquals(0, hunks[0].oldLines)
    }

    @Test
    fun `empty baseline vs content`() {
        val hunks = DiffEngine.computeHunks("", "new content")
        assertEquals(1, hunks.size)
        assertEquals(1, hunks[0].newLines)
    }

    @Test
    fun `content vs empty means deletion`() {
        val hunks = DiffEngine.computeHunks("old content", "")
        assertEquals(1, hunks.size)
        assertEquals(1, hunks[0].oldLines)
        assertEquals(0, hunks[0].newLines)
    }

    @Test
    fun `both empty no diff`() {
        val hunks = DiffEngine.computeHunks("", "")
        assertTrue(hunks.isEmpty())
    }

    @Test
    fun `hunk id is deterministic`() {
        val hunks1 = DiffEngine.computeHunks("a\nb\nc", "a\nX\nc")
        val hunks2 = DiffEngine.computeHunks("a\nb\nc", "a\nX\nc")
        assertEquals(hunks1[0].id, hunks2[0].id)
    }

    @Test
    fun `multiple hunks`() {
        val baseline = "line1\nline2\nline3\nline4\nline5"
        val current = "line1\nchanged2\nline3\nline4\nchanged5"
        val hunks = DiffEngine.computeHunks(baseline, current)
        assertEquals(2, hunks.size)
    }

    @Test
    fun `hunk id format`() {
        val hunks = DiffEngine.computeHunks("a\nb", "a\nc")
        val hunk = hunks[0]
        assertEquals("${hunk.newStart}:${hunk.newLines}:${hunk.oldStart}:${hunk.oldLines}", hunk.id)
    }
}
