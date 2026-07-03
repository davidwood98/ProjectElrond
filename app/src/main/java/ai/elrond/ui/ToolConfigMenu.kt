package ai.elrond.ui

import ai.elrond.domain.HighlighterColor
import ai.elrond.domain.HighlighterWidth
import ai.elrond.domain.InkLineType
import ai.elrond.domain.PenColor
import ai.elrond.ui.theme.LeapTheme
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * FA-23 per-tool configuration menus, shown by tapping the toolbar icon of the ALREADY-selected
 * tool (inside a `DropdownMenu` anchored below it — the More-menu precedent). Kept deliberately
 * small: a swatch row, and for pen/pencil an expandable line-type list whose glyphs are drawn
 * from the same [InkLineType] pattern spec the baked segments use, so they can't drift.
 */

@Composable
internal fun PenConfigMenu(
    selectedColor: PenColor,
    onColor: (PenColor) -> Unit,
    selectedLineType: InkLineType,
    onLineType: (InkLineType) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        ColorSwatchRow(
            colors = PenColor.entries.map { it.name to it.argb },
            selectedName = selectedColor.name,
            onSelect = { name -> onColor(PenColor.fromName(name)) },
        )
        LineTypeSection(selectedLineType, onLineType)
    }
}

@Composable
internal fun HighlighterConfigMenu(
    selectedColor: HighlighterColor,
    onColor: (HighlighterColor) -> Unit,
    selectedWidth: HighlighterWidth,
    onWidth: (HighlighterWidth) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        ColorSwatchRow(
            // Swatches render the base colour opaque — the low ink alpha would wash them out.
            colors = HighlighterColor.entries.map { it.name to (it.argb or 0xFF000000.toInt()) },
            selectedName = selectedColor.name,
            onSelect = { name -> onColor(HighlighterColor.fromName(name)) },
        )
        WidthDotRow(selectedWidth, onWidth)
    }
}

@Composable
internal fun PencilConfigMenu(
    selectedLineType: InkLineType,
    onLineType: (InkLineType) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        LineTypeSection(selectedLineType, onLineType, startExpanded = true)
    }
}

/** A row of 24dp colour circles; the selected one gets an accent ring. */
@Composable
private fun ColorSwatchRow(
    colors: List<Pair<String, Int>>,
    selectedName: String,
    onSelect: (String) -> Unit,
) {
    val accent = LeapTheme.tokens.accent
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        colors.forEach { (name, argb) ->
            val selected = name == selectedName
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .then(
                        if (selected) {
                            Modifier.border(2.dp, accent, CircleShape)
                        } else {
                            Modifier.border(1.dp, Color(0xFFE4E5E6), CircleShape)
                        },
                    )
                    .padding(3.dp)
                    .background(Color(argb), CircleShape)
                    .clickable { onSelect(name) }
                    .semantics { contentDescription = "$name ink" },
            )
        }
    }
}

/** A "line" glyph that expands the five line-type rows; the selected row gets a soft accent tile. */
@Composable
private fun LineTypeSection(
    selected: InkLineType,
    onSelect: (InkLineType) -> Unit,
    startExpanded: Boolean = false,
) {
    var expanded by rememberSaveable { mutableStateOf(startExpanded) }
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(top = 8.dp)
                .background(
                    if (expanded) LeapTheme.tokens.accentSoft else Color.Transparent,
                    RoundedCornerShape(8.dp),
                )
                .clickable { expanded = !expanded }
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .semantics { contentDescription = "Line type" },
        ) {
            LineTypeGlyph(selected, color = MaterialTheme.colorScheme.onSurface)
        }
        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 4.dp)) {
                InkLineType.entries.forEach { type ->
                    val isSelected = type == selected
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isSelected) LeapTheme.tokens.accentSoft else Color.Transparent,
                                RoundedCornerShape(8.dp),
                            )
                            .clickable { onSelect(type); expanded = false }
                            .padding(horizontal = 8.dp, vertical = 7.dp)
                            .semantics { contentDescription = lineTypeLabel(type) },
                    ) {
                        LineTypeGlyph(type, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

/** Draws [type]'s actual pattern as a short horizontal line (48×2dp within its own bounds). */
@Composable
private fun LineTypeGlyph(type: InkLineType, color: Color) {
    Canvas(modifier = Modifier.size(width = 48.dp, height = 12.dp)) {
        // Glyph pattern at a fixed "brush size" so all five read clearly at menu scale.
        val intervals = type.dashIntervals(GLYPH_BRUSH_SIZE)
        val effect = if (intervals.isEmpty()) null else PathEffect.dashPathEffect(intervals)
        drawLine(
            color = color,
            start = Offset(0f, size.height / 2f),
            end = Offset(size.width, size.height / 2f),
            strokeWidth = GLYPH_STROKE_PX,
            cap = StrokeCap.Round,
            pathEffect = effect,
        )
    }
}

/** Three dots (6/10/14dp) for fine / standard / thick; the selected one gets an accent ring. */
@Composable
private fun WidthDotRow(selected: HighlighterWidth, onSelect: (HighlighterWidth) -> Unit) {
    val accent = LeapTheme.tokens.accent
    val dotSizes = mapOf(
        HighlighterWidth.FINE to 6.dp,
        HighlighterWidth.STANDARD to 10.dp,
        HighlighterWidth.THICK to 14.dp,
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 10.dp),
    ) {
        HighlighterWidth.entries.forEach { width ->
            val isSelected = width == selected
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(26.dp)
                    .then(
                        if (isSelected) {
                            Modifier.border(2.dp, accent, CircleShape)
                        } else {
                            Modifier
                        },
                    )
                    .clickable { onSelect(width) }
                    .semantics { contentDescription = "${width.name} tip" },
            ) {
                Box(
                    modifier = Modifier
                        .size(dotSizes.getValue(width))
                        .background(MaterialTheme.colorScheme.onSurface, CircleShape),
                )
            }
        }
    }
}

private fun lineTypeLabel(type: InkLineType): String = when (type) {
    InkLineType.SOLID -> "Solid line"
    InkLineType.CENTRELINE -> "Centreline"
    InkLineType.DASHED -> "Dashed line"
    InkLineType.DOTTED -> "Dotted line"
    InkLineType.DASH_DOT -> "Dash-dot line"
}

private const val GLYPH_BRUSH_SIZE = 2f
private const val GLYPH_STROKE_PX = 4f
