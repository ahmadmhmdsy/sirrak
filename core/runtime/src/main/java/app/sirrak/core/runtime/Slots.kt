package app.sirrak.core.runtime

import androidx.compose.runtime.Composable

@JvmInline
value class SlotKey(val id: String)

enum class SlotKind { Single, List }

class SlotDeclaration(val key: SlotKey, val kind: SlotKind, val owner: String) {
    init {
        require(key.id.isNotBlank()) { "slot id must not be blank" }
    }
}

/**
 * The complete catalogue of typed UI extension points. Declaring a slot is claiming it: only the
 * owner renders it, and registering into an undeclared slot throws. Kept deliberately small —
 * `single` and `list` only, per the frontend council's ruling.
 */
object Slots {
    val ChatTopBarTrailing = SlotKey("chat.topBar.trailing")
    val ChatComposerTrailing = SlotKey("chat.composer.trailing")

    /**
     * A full-width row directly above the composer. M10 dictation renders its recording/transcribing/
     * failure strip here; the chat screen stays unaware of who contributes it.
     */
    val ChatComposerAbove = SlotKey("chat.composer.above")

    /**
     * Extra per-answer actions (M13 read-aloud, P3 report). The chat screen renders these inside a
     * [ChatAnswerContext] provider, so a contributor can act on the answer it is attached to.
     */
    val ChatAnswerActions = SlotKey("chat.answer.actions")
    val SettingsSections = SlotKey("settings.sections")

    val declarations: List<SlotDeclaration> = listOf(
        SlotDeclaration(ChatTopBarTrailing, SlotKind.List, owner = "feature:chat"),
        SlotDeclaration(ChatComposerTrailing, SlotKind.List, owner = "feature:chat"),
        SlotDeclaration(ChatComposerAbove, SlotKind.List, owner = "feature:chat"),
        SlotDeclaration(ChatAnswerActions, SlotKind.List, owner = "feature:chat"),
        SlotDeclaration(SettingsSections, SlotKind.List, owner = "feature:settings"),
    )
}

typealias SlotContent = @Composable () -> Unit

class SlotRegistration(val pluginId: String, val order: Int, val content: SlotContent)

class SlotRegistry {
    private val declarations = Slots.declarations.associateBy { it.key.id }
    private val single = LinkedHashMap<String, SlotRegistration>()
    private val lists = LinkedHashMap<String, MutableList<SlotRegistration>>()

    fun declareIfAbsent(key: SlotKey): SlotDeclaration =
        declarations[key.id] ?: error("Slot '${key.id}' is not declared in Slots.declarations")

    fun provideSingle(key: SlotKey, pluginId: String, content: SlotContent) {
        val declaration = declareIfAbsent(key)
        check(declaration.kind == SlotKind.Single) { "Slot '${key.id}' is a list slot" }
        check(!single.containsKey(key.id)) {
            "Slot '${key.id}' already has a provider (${single[key.id]?.pluginId}) — refusing $pluginId"
        }
        single[key.id] = SlotRegistration(pluginId, 0, content)
    }

    fun provideList(key: SlotKey, pluginId: String, order: Int = 0, content: SlotContent) {
        val declaration = declareIfAbsent(key)
        check(declaration.kind == SlotKind.List) { "Slot '${key.id}' is a single slot" }
        lists.getOrPut(key.id) { mutableListOf() }.add(SlotRegistration(pluginId, order, content))
    }

    fun singleContent(key: SlotKey): SlotContent? = single[key.id]?.content

    fun listContent(key: SlotKey): List<SlotContent> =
        lists[key.id].orEmpty().sortedBy { it.order }.map { it.content }

    fun clear() {
        single.clear()
        lists.clear()
    }
}
