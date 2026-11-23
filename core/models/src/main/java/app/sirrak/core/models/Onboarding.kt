package app.sirrak.core.models

import kotlinx.coroutines.flow.StateFlow

/** ONB-1: exactly three first-run screens, in this order. */
enum class OnboardingStep {
    PRIVACY,
    LANGUAGE,
    MODEL;

    companion object {
        fun ordered(): List<OnboardingStep> = entries
    }
}

/** Persisted first-run state. [skipped] means "try without a model", which is a real choice (ONB-5). */
data class OnboardingState(
    val completed: Boolean = false,
    val skipped: Boolean = false,
    val completedAtMs: Long = 0L,
)

interface OnboardingStore {
    val state: StateFlow<OnboardingState>
    fun complete(skipped: Boolean)
    fun reset()
}

/**
 * Pure navigation over the three steps. The UI owns the current-step state; this object owns the
 * order and the skip rule, so adding a step is one enum value and no screen logic changes.
 */
object OnboardingFlow {

    fun ordered(): List<OnboardingStep> = OnboardingStep.ordered()

    fun count(): Int = OnboardingStep.entries.size

    fun index(step: OnboardingStep): Int = step.ordinal

    /** Next step, or null when the current step is the last one (finish). */
    fun next(step: OnboardingStep): OnboardingStep? = OnboardingStep.entries.getOrNull(step.ordinal + 1)

    /** Previous step, or null on the first step. */
    fun previous(step: OnboardingStep): OnboardingStep? = OnboardingStep.entries.getOrNull(step.ordinal - 1)

    /** Every step can be skipped: the demo engine makes chat usable without a model. */
    fun canSkip(step: OnboardingStep): Boolean = true
}
