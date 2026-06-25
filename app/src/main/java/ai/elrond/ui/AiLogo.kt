package ai.elrond.ui

import ai.elrond.domain.AiColorMode
import ai.elrond.ui.icons.ElrondIcons
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The AI logo (FA-17, organic-loaders handoff icon 02c-04). The colour vs black variant follows the
 * app-wide [LocalAiColorMode] so it matches the loader; pass an explicit [colorMode] to override
 * (e.g. settings previews). It's a multi-colour brand mark, so it is **not** tinted at the call
 * site — `contentDescription` keeps it accessible.
 */
@Composable
fun AiLogo(
    modifier: Modifier = Modifier,
    colorMode: AiColorMode = LocalAiColorMode.current,
    contentDescription: String? = "AI",
) {
    val res = when (colorMode) {
        AiColorMode.COLOR -> ElrondIcons.AiLogoColor
        AiColorMode.BLACK -> ElrondIcons.AiLogoBlack
    }
    Image(
        painter = painterResource(res),
        contentDescription = contentDescription,
        modifier = modifier,
    )
}

/**
 * The AI logo inline before a to-do's source-note link, sized to the text line (FA-17 — replaces
 * the old "🔗" prefix). Only shown when a to-do actually links to a note; whole row taps through to
 * [onClick].
 */
@Composable
fun AiSourceLink(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    // Match the logo height to the text so it reads as an inline glyph, not a separate icon.
    val logoSize: Dp = with(LocalDensity.current) {
        (if (style.fontSize.isSp) style.fontSize.toDp() else 14.dp) * 1.15f
    }
    Row(
        modifier = modifier.clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AiLogo(modifier = Modifier.size(logoSize), contentDescription = "AI source")
        Text(
            text = title,
            style = style,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
