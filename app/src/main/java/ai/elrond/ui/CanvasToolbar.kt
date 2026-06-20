package ai.elrond.ui

import ai.elrond.domain.ToolSelectedTreatment
import ai.elrond.ui.theme.LeapTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp

/**
 * Note-toolbar building blocks, styled from the Claude Design "Note Tool Icons" handoff: a white,
 * hairline-bordered, softly-shadowed rounded container holding 46dp line-icon tiles. The active tool
 * is shown with one of three [ToolSelectedTreatment]s (A soft tile / B filled / C underline), driven
 * by the user's setting. Colours/radii come from [LeapTheme.tokens] so a future handoff is a
 * one-place edit.
 */

/** A rounded white toolbar pod (the handoff's isolated/main containers). */
@Composable
fun LeapToolbarContainer(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val tokens = LeapTheme.tokens
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(tokens.containerRadius),
        color = tokens.toolbarSurface,
        border = BorderStroke(1.dp, tokens.toolbarBorder),
        shadowElevation = 6.dp, // ≈ handoff --shadow-md
    ) {
        Row(
            modifier = Modifier.padding(TOOLBAR_PADDING),
            horizontalArrangement = Arrangement.spacedBy(TILE_GAP),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** Thin vertical rule separating tool groups (handoff: 1×28, border colour). */
@Composable
fun ToolbarDivider() {
    Box(
        modifier = Modifier
            .padding(horizontal = 6.dp)
            .width(1.dp)
            .height(28.dp)
            .background(LeapTheme.tokens.toolbarBorder),
    )
}

/**
 * One 46dp toolbar tile. [selected] applies [treatment]; actions (undo/redo) pass `selected=false`
 * and rely on [enabled] for the greyed-out look. [badge] (optional) overlays a count/indicator.
 */
@Composable
fun ToolbarButton(
    painter: Painter,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    treatment: ToolSelectedTreatment = ToolSelectedTreatment.SOFT_TILE,
    badge: (@Composable () -> Unit)? = null,
) {
    val tokens = LeapTheme.tokens
    val showSoft = selected && treatment == ToolSelectedTreatment.SOFT_TILE
    val showFilled = selected && treatment == ToolSelectedTreatment.FILLED
    val showUnderline = selected && treatment == ToolSelectedTreatment.UNDERLINE

    val background = when {
        showSoft -> tokens.accentSoft
        showFilled -> tokens.accent
        else -> Color.Transparent
    }
    val tint = when {
        !enabled -> tokens.iconDisabled
        showFilled -> MaterialTheme.colorScheme.onPrimary // Leap Black on the accent fill
        showSoft || showUnderline -> tokens.accentStrong
        else -> tokens.iconDefault
    }

    Box(
        modifier = modifier
            .size(TILE_SIZE)
            .clip(RoundedCornerShape(tokens.tileRadius))
            .background(background)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val icon = @Composable {
            Icon(painter, contentDescription, tint = tint, modifier = Modifier.size(ICON_SIZE))
        }
        if (badge != null) {
            BadgedBox(badge = { badge() }) { icon() }
        } else {
            icon()
        }
        if (showUnderline) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 6.dp)
                    .width(22.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(tokens.accent),
            )
        }
    }
}

val TILE_SIZE = 46.dp
val ICON_SIZE = 26.dp
private val TILE_GAP = 4.dp
private val TOOLBAR_PADDING = 8.dp
