package app.sirrak.core.runtime

/**
 * How much of the loaded model's context window the current prompt already occupies. The engine can
 * drop the oldest turns silently once the prompt stops fitting, so the reading surface shows this
 * meter and warns before history disappears. The numbers are estimates (see TokenEstimate) and the
 * UI presents them as such, never as exact tokenizer output.
 */
data class TokenBudget(
    /** Estimated prompt tokens: the conversation plus the resolved system prompt. */
    val estimatedTokens: Int,
    /** The loaded model's context window; 0 when it is unknown. */
    val contextTokens: Int,
    /** Window space kept for the answer (the maxTokens parameter). */
    val reservedForAnswer: Int,
    /** What the prompt may occupy before the oldest turns are dropped. */
    val promptBudget: Int,
) {
    /** The prompt no longer fits: the next request will drop the oldest turns. */
    val overflow: Boolean get() = estimatedTokens > promptBudget

    /** Close enough that the user should be warned before the next long answer. */
    val nearLimit: Boolean get() = !overflow && TokenEstimate.nearLimit(estimatedTokens, promptBudget)
}

/**
 * The context window the engine was actually loaded with. Config owns it (app.json engine
 * contextTokens), so the meter asks a provider instead of guessing from the model's training size.
 */
fun interface ContextWindowProvider {
    fun contextTokens(): Int

    companion object {
        /** The shipped app.json default, used by previews and tests. */
        val Default = ContextWindowProvider { ChatBudget.DEFAULT_CONTEXT_TOKENS }
    }
}

/** Pure budgeting rules shared by the store, tests and any future surface. */
object ChatBudget {
    /** Matches assets/config/app.json engine.contextTokens. */
    const val DEFAULT_CONTEXT_TOKENS = 2048

    fun compute(
        turns: List<ChatTurn>,
        systemPrompt: String?,
        contextTokens: Int,
        maxAnswerTokens: Int,
    ): TokenBudget {
        val prompt = TokenEstimate.estimate(turns) +
            (systemPrompt?.takeIf { it.isNotBlank() }?.let {
                TokenEstimate.tokens(it) + TokenEstimate.MESSAGE_OVERHEAD
            } ?: 0)
        val context = contextTokens.coerceAtLeast(0)
        val reserve = if (context == 0) 0 else maxAnswerTokens.coerceIn(1, context)
        return TokenBudget(
            estimatedTokens = prompt,
            contextTokens = context,
            reservedForAnswer = reserve,
            promptBudget = (context - reserve).coerceAtLeast(0),
        )
    }
}

/**
 * Output speed for the answer just generated. Local generation reports tokens to the engine, not to
 * the UI, so the count is the same honest estimate the meter uses; the UI labels it as approximate.
 */
object GenerationSpeed {
    fun tokensPerSecond(text: String, durationMillis: Long): Float? {
        if (durationMillis <= 0L) return null
        val tokens = TokenEstimate.tokens(text)
        if (tokens <= 0) return null
        return tokens * 1_000f / durationMillis
    }
}
