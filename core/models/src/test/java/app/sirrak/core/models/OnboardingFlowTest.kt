package app.sirrak.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingFlowTest {

    @Test
    fun onboardingHasExactlyThreeScreensInOrder() {
        assertEquals(
            listOf(OnboardingStep.PRIVACY, OnboardingStep.LANGUAGE, OnboardingStep.MODEL),
            OnboardingFlow.ordered(),
        )
        assertEquals(3, OnboardingFlow.count())
    }

    @Test
    fun navigationMovesForwardAndStopsAtTheEnd() {
        assertEquals(OnboardingStep.LANGUAGE, OnboardingFlow.next(OnboardingStep.PRIVACY))
        assertEquals(OnboardingStep.MODEL, OnboardingFlow.next(OnboardingStep.LANGUAGE))
        assertNull(OnboardingFlow.next(OnboardingStep.MODEL))
    }

    @Test
    fun navigationMovesBackAndStopsAtTheStart() {
        assertNull(OnboardingFlow.previous(OnboardingStep.PRIVACY))
        assertEquals(OnboardingStep.PRIVACY, OnboardingFlow.previous(OnboardingStep.LANGUAGE))
        assertEquals(OnboardingStep.LANGUAGE, OnboardingFlow.previous(OnboardingStep.MODEL))
    }

    @Test
    fun everyStepCanBeSkippedBecauseTheDemoEngineWorksWithoutAModel() {
        OnboardingFlow.ordered().forEach { step ->
            assertTrue(OnboardingFlow.canSkip(step))
        }
    }

    @Test
    fun indexMatchesTheOrdinal() {
        assertEquals(0, OnboardingFlow.index(OnboardingStep.PRIVACY))
        assertEquals(1, OnboardingFlow.index(OnboardingStep.LANGUAGE))
        assertEquals(2, OnboardingFlow.index(OnboardingStep.MODEL))
    }

    @Test
    fun defaultStateIsNotCompletedAndNotSkipped() {
        val state = OnboardingState()
        assertEquals(false, state.completed)
        assertEquals(false, state.skipped)
        assertEquals(0L, state.completedAtMs)
    }
}
