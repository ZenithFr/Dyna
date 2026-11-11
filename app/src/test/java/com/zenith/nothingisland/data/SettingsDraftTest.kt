package com.zenith.nothingisland.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDraftTest {

    @Test
    fun newDraftIsClean() {
        val draft = SettingsDraft(IslandSettings())
        assertFalse(draft.isDirty)
        assertNull(draft.draft)
        assertNull(draft.takeForCommit())
    }

    @Test
    fun fiftyDragTicksLeaveOneDraftAndOneCommit() {
        val draft = SettingsDraft(IslandSettings())
        // A 5 s drag at 60 fps: every tick lands in the same draft, so one take persists the last one.
        repeat(50) { tick -> draft.preview { it.copy(widthDp = 40 + tick) } }
        assertTrue(draft.isDirty)
        assertEquals(89, draft.draft?.widthDp)
        val committed = draft.takeForCommit()
        assertEquals(89, committed?.widthDp)
        assertFalse(draft.isDirty)
    }

    @Test
    fun takeForCommitReturnsTheDraftOnlyOnce() {
        val draft = SettingsDraft(IslandSettings())
        draft.preview { it.copy(widthDp = 120) }
        assertEquals(120, draft.takeForCommit()?.widthDp)
        assertNull(draft.takeForCommit())
    }

    @Test
    fun previewAfterCommitIsRelativeToTheNewCommittedValue() {
        val draft = SettingsDraft(IslandSettings(widthDp = 100))
        draft.preview { it.copy(widthDp = 120) }
        draft.onCommitted(draft.takeForCommit()!!)
        draft.preview { it.copy(heightDp = 40) }
        val next = draft.draft!!
        assertEquals(120, next.widthDp)
        assertEquals(40, next.heightDp)
    }

    @Test
    fun onCommittedWhileDirtyDoesNotDropTheDraft() {
        val draft = SettingsDraft(IslandSettings())
        draft.preview { it.copy(widthDp = 140) }
        // A write for an older tick lands while the thumb is still moving.
        draft.onCommitted(IslandSettings(widthDp = 100, heightDp = 40))
        assertEquals(140, draft.draft?.widthDp)
        assertEquals(40, draft.committed.heightDp)
    }

    @Test
    fun transformChainingComposes() {
        val draft = SettingsDraft(IslandSettings())
        draft.preview { it.copy(widthDp = 100) }
        draft.preview { it.copy(heightDp = 40) }
        draft.preview { it.copy(cornerRadiusDp = 20) }
        val settings = draft.takeForCommit()!!
        assertEquals(100, settings.widthDp)
        assertEquals(40, settings.heightDp)
        assertEquals(20, settings.cornerRadiusDp)
    }
}
