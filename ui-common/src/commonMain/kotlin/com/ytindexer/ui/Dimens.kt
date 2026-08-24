package com.ytindexer.ui

import androidx.compose.ui.unit.dp

/**
 * Spacing tokens shared by the phone and TV surfaces.
 *
 * Deliberately minimal: the full design system (color, typography, focus states,
 * TV-specific scaling) is its own Phase 0 ticket. This just gives the scaffolding
 * screens something shared to consume so the module wiring is exercised.
 */
object Dimens {
    val SpaceXs = 2.dp
    val SpaceS = 8.dp
    val SpaceM = 16.dp
    val SpaceL = 24.dp

    /** TV surfaces need a wider safe area to survive overscan on real panels. */
    val TvOverscanPadding = 48.dp
}
