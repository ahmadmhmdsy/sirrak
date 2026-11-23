package app.sirrak.feature.chat

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * M9 (VIS-4): prompt shortcuts for an image turn. They are prompts, not an OCR engine: the loaded
 * vision model reads the pixels, so the shortcut works for any supported VL model and adds no
 * dependency, no download and no cloud call. The list is data-driven — a new shortcut is one enum
 * entry plus its two strings, and the composer picks it up.
 */
enum class ImagePromptSuggestion(@StringRes val promptRes: Int) {
    /** VIS-4: extract the visible text verbatim (the "OCR-style" shortcut from the product spec). */
    EXTRACT_TEXT(R.string.image_prompt_extract_text),

    /** A short description is the lowest-friction first question for a new image. */
    DESCRIBE(R.string.image_prompt_describe),
}

/** Renders the shortcut chips; each entry resolves its own localised prompt text. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImagePromptSuggestionRow(
    suggestions: List<ImagePromptSuggestion>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (suggestions.isEmpty()) return
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        suggestions.forEach { suggestion ->
            val text = stringResource(suggestion.promptRes)
            AssistChip(
                onClick = { onSelect(text) },
                label = { Text(text = text, maxLines = 1) },
            )
        }
    }
}
