package app.sirrak.core.design

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The answer a chat action is attached to. The chat screen provides it around
 * `Slots.ChatAnswerActions`; contributors read it instead of the slot receiving parameters, so the
 * slot contract stays "no parameters, no hidden state" while a report or read-aloud action still
 * knows exactly which answer it acts on.
 */
data class ChatAnswerContext(
    val messageId: String,
    val answerText: String,
)

val LocalChatAnswerContext = staticCompositionLocalOf<ChatAnswerContext?> { null }
