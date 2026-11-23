package app.sirrak.core.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * The one motif of the world: an eight-point girih star. It is drawn, never a bitmap, so it stays
 * crisp at any size and scales with the density. Used sparingly: empty state mark, list ticks, and
 * one low-contrast band at the top of the conversation — never as a page background.
 */
private fun starPath(center: Offset, radius: Float, innerRatio: Float = 0.42f): Path {
    val path = Path()
    val points = 16
    for (i in 0 until points) {
        val angle = (Math.PI / 8.0) * i - Math.PI / 2.0
        val r = if (i % 2 == 0) radius else radius * innerRatio
        val x = center.x + (r * cos(angle)).toFloat()
        val y = center.y + (r * sin(angle)).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

@Composable
fun GirihMark(
    modifier: Modifier = Modifier,
    tint: Color = LocalSirrakTokens.current.decorations.brass,
    filled: Boolean = false,
) {
    Canvas(modifier) {
        val radius = size.minDimension / 2f - 1.dp.toPx()
        val path = starPath(center, radius)
        if (filled) drawPath(path, color = tint) else drawPath(path, color = tint, style = Stroke(width = 1.5.dp.toPx()))
    }
}

/** Low-contrast girih lattice band, faded downwards. Decorative only; hidden from accessibility. */
@Composable
fun GirihBand(
    modifier: Modifier = Modifier,
    rows: Int = 2,
    columns: Int = 7,
    alpha: Float = 0.10f,
) {
    val color = LocalSirrakTokens.current.decorations.girihLine
    Canvas(modifier.fillMaxWidth()) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val cell = size.width / columns
        val radius = cell * 0.34f
        for (row in 0 until rows) {
            val rowAlpha = alpha * (1f - row.toFloat() / (rows + 1).toFloat())
            for (column in 0 until columns) {
                val center = Offset(cell * column + cell / 2f, cell * row + cell / 2f)
                drawPath(
                    starPath(center, radius),
                    color = color.copy(alpha = rowAlpha),
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
        }
    }
}

/** A hairline that belongs to the product, not to a component border. */
@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = LocalSirrakTokens.current.decorations.hairline) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

/** A small filled star used as a bullet/tick. */
@Composable
fun GirihTick(modifier: Modifier = Modifier, tint: Color = LocalSirrakTokens.current.decorations.brass) {
    GirihMark(modifier = modifier, tint = tint, filled = true)
}
