package me.misa198.airmedy.ui.components

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * True when the app should suppress decorative blur and continuous/looping
 * animations for low-end or API-26..30 devices (true backdrop blur and the
 * expensive RenderEffect path require API 31). Reads stay cheap because the
 * value is provided once at the composition root and never changes per frame.
 *
 * The default is false; [me.misa198.airmedy.App] provides it from the
 * reduce-transparency preference plus the platform glass support check.
 */
val LocalReduceMotion = staticCompositionLocalOf { false }