package app.sirrak.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatBudgetTest {

    @Test
    fun `prompt estimate covers turns plus the system prompt`() {
        val turns = listOf(ChatTurn(Role.USER, "abcd")) // 1 token + 4 overhead
        val budget = ChatBudget.compute(
            turns = turns,
            systemPrompt = "abcde", // 2 tokens + 4 overhead
            contextTokens = 2048,
            maxAnswerTokens = 512,
        )

        assertEquals(1 + TokenEstimate.MESSAGE_OVERHEAD + 2 + TokenEstimate.MESSAGE_OVERHEAD, budget.estimatedTokens)
    }

    @Test
    fun `the answer reservation shrinks the prompt budget`() {
        val budget = ChatBudget.compute(emptyList(), null, contextTokens = 2048, maxAnswerTokens = 512)

        assertEquals(2048, budget.contextTokens)
        assertEquals(512, budget.reservedForAnswer)
        assertEquals(1536, budget.promptBudget)
        assertFalse(budget.overflow)
        assertFalse(budget.nearLimit)
    }

    @Test
    fun `a prompt over budget is reported as overflow, not as merely near the limit`() {
        val long = "a".repeat(4 * 2000)
        val budget = ChatBudget.compute(
            turns = listOf(ChatTurn(Role.USER, long)),
            systemPrompt = null,
            contextTokens = 2048,
            maxAnswerTokens = 512,
        )

        assertTrue(budget.overflow)
        assertFalse(budget.nearLimit)
    }

    @Test
    fun `a prompt past 85 percent of its budget is near the limit`() {
        // 1314 tokens of prompt against a 1536 prompt budget is ~85.5%.
        val budget = ChatBudget.compute(
            turns = listOf(ChatTurn(Role.USER, "a".repeat(4 * 1310))),
            systemPrompt = null,
            contextTokens = 2048,
            maxAnswerTokens = 512,
        )

        assertFalse(budget.overflow)
        assertTrue(budget.nearLimit)
    }

    @Test
    fun `a zero context window has no room and never counts as near the limit`() {
        val budget = ChatBudget.compute(
            turns = listOf(ChatTurn(Role.USER, "abcd")),
            systemPrompt = null,
            contextTokens = 0,
            maxAnswerTokens = 512,
        )

        assertEquals(0, budget.promptBudget)
        assertTrue(budget.overflow)
        assertFalse(budget.nearLimit)
    }

    @Test
    fun `speed needs both tokens and elapsed time`() {
        // 400 characters estimate to 100 tokens in two seconds: 50 tokens per second.
        assertEquals(50f, GenerationSpeed.tokensPerSecond("a".repeat(400), 2000L)!!, 0.01f)
        assertNull(GenerationSpeed.tokensPerSecond("a".repeat(400), 0L))
        assertNull(GenerationSpeed.tokensPerSecond("   ", 1000L))
    }
}
