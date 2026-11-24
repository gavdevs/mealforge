package com.mealforge.lightphone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure cook-view visibility contract in `docs/light-phone-contracts.md` §5 ("best-effort wake lock on
 * enter, released on leave") plus the cook-mode scrolling rules in §6.
 *
 * These assert the model's intent only: entering the cook view asks to keep the display awake,
 * leaving releases it without losing the cook's place, and the hardware wheel is a viewing gesture
 * rather than a completion action. Whether the platform actually holds the display on is a Compose
 * and LightOS concern and is deliberately not claimed here.
 */
class CookVisibilityTest {
    @Test
    fun showHoldsTheAwakeRequestAndHideReleasesItWithoutLosingTheStep() {
        val session = CookSession("1. One\n2. Two\n3. Three")

        val fresh = session.state.value
        assertFalse("a cook screen must not request the display before it is shown", fresh.visible)
        assertFalse(fresh.keepAwake)

        session.show()
        val shown = session.state.value
        assertTrue("cooking should hold the display awake", shown.visible)
        assertTrue(shown.keepAwake)

        session.next()
        assertEquals(1, session.state.value.index)

        session.hide()
        val hidden = session.state.value
        assertFalse("leaving the cook screen must release the display", hidden.keepAwake)
        assertEquals("leaving must not rewind the cook", 1, hidden.index)
        assertFalse("the cook screen reports itself hidden after leaving", hidden.visible)

        session.show()
        val resumed = session.state.value
        assertTrue(resumed.keepAwake)
        assertEquals("re-entering resumes the step the cook left", 1, resumed.index)
        assertEquals("Two", resumed.current)
    }

    @Test
    fun wheelMovesOnlyWhileShownAndClampsAtTheEndsWithoutFinishing() {
        val session = CookSession("1. One\n2. Two")

        session.wheel(forward = true)
        assertEquals("a hidden cook screen must ignore the wheel", 0, session.state.value.index)
        assertFalse(session.state.value.finished)

        session.show()
        session.wheel(forward = true)
        assertEquals(1, session.state.value.index)

        session.wheel(forward = true)
        assertEquals("the wheel cannot step past the last instruction", 1, session.state.value.index)
        assertFalse("only the explicit Done action finishes a cook", session.state.value.finished)
        assertTrue("the last instruction is still cooking, so stay awake", session.state.value.keepAwake)

        session.wheel(forward = false)
        session.wheel(forward = false)
        assertEquals("the wheel cannot step before the first instruction", 0, session.state.value.index)
        assertFalse(session.state.value.finished)

        session.wheel(forward = true)
        session.next()
        assertTrue("the explicit Done action completes the cook", session.state.value.finished)
        assertFalse("a finished cook no longer needs the display", session.state.value.keepAwake)
    }

    @Test
    fun finishedCookCannotBeWokenOrMovedAgainByShowAndWheel() {
        val session = CookSession("1. One\n2. Two\n3. Three")

        session.show()
        session.next()
        session.next()
        session.exit()
        val finished = session.state.value
        assertTrue(finished.finished)
        assertFalse("exit releases the display", finished.keepAwake)
        assertEquals(2, finished.index)

        session.show()
        val afterShow = session.state.value
        assertTrue(afterShow.finished)
        assertEquals("showing a finished cook must not rewind it", 2, afterShow.index)
        assertFalse("a finished cook must not re-acquire the display", afterShow.keepAwake)

        session.wheel(forward = false)
        session.wheel(forward = true)
        val afterWheel = session.state.value
        assertEquals("the wheel cannot move a finished cook", 2, afterWheel.index)
        assertTrue(afterWheel.finished)
        assertFalse(afterWheel.keepAwake)
    }
}
