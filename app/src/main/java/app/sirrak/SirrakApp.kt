package app.sirrak

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sirrak.core.design.LocalAppContext
import app.sirrak.core.design.LocalSirrakTokens
import app.sirrak.core.design.ProvideFontScale
import app.sirrak.core.design.SirrakShell
import app.sirrak.core.design.SirrakTheme
import app.sirrak.core.models.OnboardingStore
import app.sirrak.feature.models.OnboardingScreen
import app.sirrak.core.runtime.AppContext
import app.sirrak.core.runtime.AppNavigator
import app.sirrak.core.runtime.SettingsService
import app.sirrak.core.runtime.findActivity

/** Composition root of the UI: services in, shell out. No destination names appear here. */
@Composable
fun SirrakApp(container: AppContext) {
    val settings = container.service(SettingsService::class.java)
    val themeMode by settings.themeMode.collectAsStateWithLifecycle()
    val themePackId by settings.themePackId.collectAsStateWithLifecycle()
    // M6 THM-6: the reader's text size is a persisted preference, applied once at the root by
    // scaling LocalDensity.fontScale. Every sp below follows; no activity recreate, no per-screen work.
    val fontScaleId by settings.fontScaleId.collectAsStateWithLifecycle()
    val destinations = remember(container) { container.destinations.all() }
    // Lives in the app container, not in the composition: a language switch recreates the Activity
    // and must not throw the reader back to the first tab.
    val navigator = container.service(AppNavigator::class.java)
    // M11 ONB-1: the first run is a three-screen gate (privacy, language, model). It is not a
    // destination and not a slot: until onboarding completes, the shell does not exist at all, so a
    // skipped setup can never leave a half-configured chat behind.
    val onboardingStore = container.service(OnboardingStore::class.java)
    val onboarding by onboardingStore.state.collectAsStateWithLifecycle()

    // One rule for every surface: a page pops before the system handles back. Android's predictive
    // back still sees a disabled handler at the root, so the app closes instead of blinking away.
    BackHandler(enabled = navigator.canGoBack) { navigator.back() }

    SirrakTheme(mode = themeMode, packId = themePackId) {
        // Text scale wraps the whole shell (not just Settings) but not the theme itself: dp geometry,
        // insets and touch targets stay put while sp text follows the preset immediately.
        ProvideFontScale(fontScaleId) {
            val tokens = LocalSirrakTokens.current
            val view = LocalView.current
            SideEffect {
                val activity = view.context.findActivity() ?: return@SideEffect
                WindowCompat.getInsetsController(activity.window, view).apply {
                    isAppearanceLightStatusBars = !tokens.dark
                    isAppearanceLightNavigationBars = !tokens.dark
                }
            }
            CompositionLocalProvider(LocalAppContext provides container) {
                if (onboarding.completed) {
                    SirrakShell(
                        destinations = destinations,
                        navigator = navigator,
                        pageContent = { route ->
                            val page = remember(route.id) { container.pages.find(route.id) }
                            if (page != null) {
                                page.content(route.argument)
                            } else {
                                // A page id nobody contributed is a composition bug, not a user error:
                                // close it instead of showing a blank screen.
                                LaunchedEffect(route) { navigator.closePages() }
                            }
                        },
                    )
                } else {
                    OnboardingScreen(container)
                }
            }
        }
    }
}

@Suppress("unused")
private val unusedInsets = WindowInsets(0, 0, 0, 0)
