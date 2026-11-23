package app.sirrak.feature.chat

import app.sirrak.core.runtime.AppNavigator
import app.sirrak.core.runtime.AttachmentStore
import app.sirrak.core.runtime.ContentRepository
import app.sirrak.core.runtime.ContextWindowProvider
import app.sirrak.core.runtime.ConversationLibrary
import app.sirrak.core.runtime.Destination
import app.sirrak.core.runtime.GenerationSettingsProvider
import app.sirrak.core.runtime.ImagePreparer
import app.sirrak.core.runtime.InferenceEngine
import app.sirrak.core.runtime.NavIcon
import app.sirrak.core.runtime.Plugin
import app.sirrak.core.runtime.PluginScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Chat as a feature plugin: one destination, six service dependencies, one optional store, no
 * knowledge of the host.
 *
 * [attachments] is deliberately not in [requires]: a composition that has not registered an
 * [AttachmentStore] still gets a working text-only chat, and only the attach flow is unavailable. When
 * it is present, the store owns the bytes and this plugin only passes the handle to [ChatStore].
 */
class ChatPlugin(
    private val attachments: AttachmentStore? = null,
    /** Optional M8 document grounding; null keeps the feature exactly as it was before M8. */
    private val rag: app.sirrak.core.rag.RagService? = null,
    /**
     * Optional M9 image preparation. The feature module owns no Android bitmap code; the host injects
     * the platform implementation and tests inject a fake, so vision stays a composition line.
     */
    private val imagePreparer: ImagePreparer? = null,
    /**
     * M10: a plugin-to-composer text channel. The host owns the instance and hands the same one to
     * the voice feature, so dictation appends here without either feature depending on the other.
     */
    private val composerTextRequests: app.sirrak.core.runtime.ComposerTextRequests? = null,
) : Plugin {
    override val id: String = "chat"

    override val requires: List<Class<*>> = listOf(
        InferenceEngine::class.java,
        ContentRepository::class.java,
        ConversationLibrary::class.java,
        ContextWindowProvider::class.java,
        AppNavigator::class.java,
        GenerationSettingsProvider::class.java,
    )

    override fun install(scope: PluginScope) {
        val engine = scope.service(InferenceEngine::class.java)
        val content = scope.service(ContentRepository::class.java)
        val conversations = scope.service(ConversationLibrary::class.java)
        val contextWindow = scope.service(ContextWindowProvider::class.java)
        val navigator = scope.service(AppNavigator::class.java)
        val generationSettings = scope.service(GenerationSettingsProvider::class.java)
        // The conversation outlives the screen: leaving the tab or recreating the Activity must not
        // throw away a streaming answer, so the store streams on an app-owned scope.
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val store = ChatStore(
            engine = engine,
            library = content,
            appScope = appScope,
            generationSettings = generationSettings,
            conversations = conversations,
            contextTokens = contextWindow.contextTokens(),
            attachments = attachments,
            imagePreparer = imagePreparer,
            rag = rag,
        )
        // Dispose by stopping the stream, not by wiping the transcript: the active conversation is
        // persisted and its file survives; only an explicit delete from the library removes history.
        scope.effect { appScope.cancel(); store.stop() }
        scope.destination(
            Destination(
                id = "chat",
                labelRes = R.string.nav_chat,
                icon = NavIcon.CHAT,
                order = 0,
                content = {
                    ChatScreen(
                        store = store,
                        onOpenSettings = { navigator.select("settings") },
                        composerTextRequests = composerTextRequests,
                    )
                },
            ),
        )
    }
}
