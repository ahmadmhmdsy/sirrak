package app.sirrak.core.design

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.LocalLibrary
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.sirrak.core.runtime.AppNavigator
import app.sirrak.core.runtime.Destination
import app.sirrak.core.runtime.NavIcon
import app.sirrak.core.runtime.PageRoute
import androidx.compose.animation.core.tween

private fun iconFor(icon: NavIcon) = when (icon) {
    NavIcon.CHAT -> Icons.Outlined.ChatBubbleOutline
    NavIcon.LIBRARY -> Icons.Outlined.LocalLibrary
    NavIcon.SETTINGS -> Icons.Outlined.Tune
}

/**
 * The app shell: bottom navigation built only from contributed destinations, one AnimatedContent
 * host. No route names live here; removing a feature removes its tab.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SirrakShell(
    destinations: List<Destination>,
    navigator: AppNavigator,
    modifier: Modifier = Modifier,
    /**
     * Renders a page the navigator pushed. The host resolves the page id through its registry, so the
     * shell never names a page and a feature can add one without touching this file. When null, pages
     * are not supported by this composition and only destinations render.
     */
    pageContent: (@Composable (PageRoute) -> Unit)? = null,
) {
    if (destinations.isEmpty()) {
        Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        return
    }
    val current = destinations.firstOrNull { it.id == navigator.currentId } ?: destinations.first()
    val page = navigator.currentPage
    val motion = LocalSirrakTokens.current.motion
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        // While the keyboard is up the bottom bar is covered by it, so keeping it composed only
        // reserves its height above the keyboard and leaves a blank band under the composer.
        // Hiding it gives the transcript that height back exactly when the user is typing.
        bottomBar = {
            // A page is a focused surface: it covers the whole window and takes the bottom bar with
            // it, so a half-visible tab bar can never be mistaken for a way out.
            if (!WindowInsets.isImeVisible && page == null) {
                Column {
                    Hairline()
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp,
                    ) {
                        destinations.forEach { destination ->
                            val selected = destination.id == current.id
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigator.select(destination.id) },
                                icon = { Icon(iconFor(destination.icon), contentDescription = null) },
                                label = {
                                    Text(
                                        text = stringResource(destination.labelRes),
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(bottom = innerPadding.calculateBottomPadding())) {
            if (page != null && pageContent != null) {
                Box(Modifier.fillMaxSize()) { pageContent.invoke(page) }
            } else {
            AnimatedContent(
                targetState = current,
                transitionSpec = {
                    (fadeIn(tween(motion.standard)) +
                        slideInVertically(tween(motion.standard)) { height -> height / 28 })
                        .togetherWith(fadeOut(tween(motion.quick)))
                },
                label = "destination",
            ) { destination ->
                Box(Modifier.fillMaxSize()) { destination.content() }
            }
            }
        }
    }
}
