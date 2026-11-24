package com.mealforge.lightphone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ports the cook-mode contract of `apps/web/src/lib/steps.ts` and the local
 * [CookSession] navigation rules in `docs/light-phone-contracts.md` §6.
 *
 * These are pure checks: no HTTP, no clock, no Android lifecycle. Screen-wake
 * behaviour is a Compose concern and is deliberately not claimed here.
 */
class CookingStepsTest {
    @Test
    fun numberedLinesSplitOnBothMarkersAndTrimEachStep() {
        val recipeMarkdown = "1. Season chicken\nwith herbs\n\n2) Cook for six minutes.\n3. Serve."

        assertEquals(
            listOf("Season chicken\nwith herbs", "Cook for six minutes.", "Serve."),
            splitCookingSteps(recipeMarkdown),
        )
    }

    @Test
    fun preambleIsDroppedAndTrailingTextFoldsIntoTheLastStep() {
        val markdown = buildString {
            appendLine("Some intro the cook should never see.")
            appendLine()
            appendLine("1. Preheat the oven.")
            appendLine("more heat")
            appendLine()
            appendLine("2) Mix everything.")
            appendLine("Final stir.")
        }

        assertEquals(
            listOf("Preheat the oven.\nmore heat", "Mix everything.\nFinal stir."),
            splitCookingSteps(markdown),
        )

        // The number may be indented; blank separators between steps vanish.
        assertEquals(
            listOf("One", "Two"),
            splitCookingSteps("   1. One\n\n\t2) Two"),
        )
    }

    @Test
    fun unnumberedMarkdownFallsBackToBlankLineParagraphs() {
        assertEquals(
            listOf("Warm beans.", "Serve in bowls."),
            splitCookingSteps("Warm beans.\n\nServe in bowls."),
        )

        // Single newlines are only folded inside a numbered step; without any
        // number the paragraph separator is two or more newlines.
        assertEquals(
            listOf("Warm beans.\nServe in bowls."),
            splitCookingSteps("Warm beans.\nServe in bowls."),
        )

        // Whitespace-only paragraphs are dropped rather than shown as steps.
        assertEquals(listOf("Only paragraph."), splitCookingSteps("\n\n  Only paragraph.  \n\n"))
    }

    @Test
    fun emptyOrBlankMarkdownStillYieldsOneEmptyStep() {
        assertEquals(listOf(""), splitCookingSteps(""))
        assertEquals(listOf(""), splitCookingSteps("   \n\n \t "))
    }

    @Test
    fun sessionStartsAtFirstStepAndPreviousClampsAtZero() {
        val session = CookSession("1. One\n2. Two\n3. Three")

        val start = session.state.value
        assertEquals(listOf("One", "Two", "Three"), start.steps)
        assertEquals(0, start.index)
        assertFalse(start.finished)
        assertEquals("One", start.current)

        session.previous()
        assertEquals(0, session.state.value.index)
        assertFalse(session.state.value.finished)
    }

    @Test
    fun nextAdvancesThenReachingTheLastStepRequiresNextToFinish() {
        val session = CookSession("1. One\n2. Two\n3. Three")

        session.next()
        assertEquals(1, session.state.value.index)
        assertEquals("Two", session.state.value.current)

        session.next()
        assertEquals(2, session.state.value.index)
        assertEquals("Three", session.state.value.current)
        assertFalse("the last step is still shown before Done", session.state.value.finished)

        // The "Done — serve it" action is another next(), not an index bump.
        session.next()
        assertEquals(2, session.state.value.index)
        assertTrue(session.state.value.finished)
    }

    @Test
    fun finishedSessionIgnoresFurtherNavigation() {
        val session = CookSession("1. One\n2. Two")

        session.next()
        session.next()
        assertTrue(session.state.value.finished)

        session.previous()
        assertEquals(1, session.state.value.index)
        assertTrue(session.state.value.finished)

        session.next()
        assertEquals(1, session.state.value.index)
        assertTrue(session.state.value.finished)

        session.exit()
        assertEquals(1, session.state.value.index)
        assertTrue(session.state.value.finished)
    }

    @Test
    fun exitFinishesEarlyAtTheCurrentStepAndSingleStepSessionsFinishOnNext() {
        val paragraphSession = CookSession("Warm beans.\n\nServe in bowls.")
        paragraphSession.next()
        assertEquals(1, paragraphSession.state.value.index)
        assertEquals("Serve in bowls.", paragraphSession.state.value.current)

        paragraphSession.exit()
        assertTrue(paragraphSession.state.value.finished)
        assertEquals("exiting does not rewind or advance", 1, paragraphSession.state.value.index)

        paragraphSession.previous()
        assertEquals(1, paragraphSession.state.value.index)
        paragraphSession.next()
        assertEquals(1, paragraphSession.state.value.index)

        // An empty recipe must still render one empty step, and Next from that
        // single step is the Done action.
        val emptySession = CookSession("")
        assertEquals(listOf(""), emptySession.state.value.steps)
        assertEquals("", emptySession.state.value.current)
        assertFalse(emptySession.state.value.finished)
        emptySession.next()
        assertTrue(emptySession.state.value.finished)
        assertEquals(0, emptySession.state.value.index)
    }
}
