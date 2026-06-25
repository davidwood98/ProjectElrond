package ai.elrond.ui

import ai.elrond.domain.AiColorMode
import ai.elrond.domain.AiLoaderStyle
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * App-wide AI-mark appearance (FA-17), provided once at [ai.elrond.MainActivity] from the persisted
 * settings so the AI logo and loader render consistently everywhere without threading the prefs
 * through every ViewModel — the same top-level approach as the app accent.
 *
 * [LocalAiColorMode] drives both the logo's drawable and the loader's palette; [LocalAiLoaderStyle]
 * picks which organic loader animates while the AI is thinking.
 */
val LocalAiColorMode = compositionLocalOf { AiColorMode.DEFAULT }

val LocalAiLoaderStyle = staticCompositionLocalOf { AiLoaderStyle.DEFAULT }
