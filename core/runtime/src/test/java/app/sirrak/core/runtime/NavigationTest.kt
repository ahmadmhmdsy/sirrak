package app.sirrak.core.runtime

import androidx.compose.runtime.Composable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Routing is pure state: the shell only renders what these tests pin. */
class NavigationTest {

    private fun page(id: String, label: Int = 0, content: @Composable (String?) -> Unit = {}) =
        PageContribution(id = id, labelRes = label, content = content)

    @Test
    fun `a tab switch clears any open page`() {
        val navigator = AppNavigator(initial = "chat")
        navigator.push(PageIds.MODELS)
        assertTrue(navigator.canGoBack)

        navigator.select("library")

        assertEquals("library", navigator.currentId)
        assertFalse(navigator.canGoBack)
        assertNull(navigator.currentPage)
    }

    @Test
    fun `push and back behave as a stack`() {
        val navigator = AppNavigator(initial = "library")
        navigator.push(PageIds.MODELS)
        navigator.push(PageIds.MODEL_DETAIL, argument = "qwen2.5-0.5b-q4_k_m.gguf")

        assertEquals(PageIds.MODEL_DETAIL, navigator.currentPage?.id)
        assertEquals("qwen2.5-0.5b-q4_k_m.gguf", navigator.currentPage?.argument)

        assertTrue(navigator.back())
        assertEquals(PageIds.MODELS, navigator.currentPage?.id)

        assertTrue(navigator.back())
        assertFalse(navigator.canGoBack)

        // An empty stack refuses to pop so the back handler can fall through to the system.
        assertFalse(navigator.back())
    }

    @Test
    fun `closing pages returns to the destination root`() {
        val navigator = AppNavigator(initial = "library")
        navigator.push(PageIds.DOWNLOADS)
        navigator.push(PageIds.MEDIA)

        navigator.closePages()

        assertEquals(0, navigator.pages.size)
        assertFalse(navigator.canGoBack)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `page ids must not be blank`() {
        PageRoute(id = " ")
    }

    @Test
    fun `a page id may only be contributed once across plugins`() {
        val registry = PageRegistry()
        registry.add("feature:library", page(PageIds.DOWNLOADS))

        try {
            registry.add("feature:settings", page(PageIds.DOWNLOADS))
            throw AssertionError("duplicate page contributions must be rejected")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("already contributed"))
        }
        assertEquals(1, registry.all().size)
    }

    @Test
    fun `pages are found by id and cleared with the context`() {
        val context = AppContext(AppConfig())
        context.install(
            listOf(
                object : Plugin {
                    override val id = "test:pages"
                    override fun install(scope: PluginScope) {
                        scope.page(page(PageIds.MEDIA, label = 1))
                    }
                },
            ),
        )
        assertTrue(context.pages.find(PageIds.MEDIA) != null)
        assertNull(context.pages.find(PageIds.MODELS))

        context.close()
        assertNull(context.pages.find(PageIds.MEDIA))
    }
}
