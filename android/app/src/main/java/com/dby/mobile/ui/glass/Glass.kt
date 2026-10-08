package com.dby.mobile.ui.glass

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.dby.mobile.DbyApp
import com.dby.mobile.ui.theme.Dby
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangularShape

/** True where blur materials really blur: API 31+ and Reduce blur off (spec §11). Elsewhere they are solid. */
@Composable
fun blurOn(): Boolean = Build.VERSION.SDK_INT >= 31 && !DbyApp.instance.prefs.reduceBlur

/** Light glass: no capture and no blur, so it costs nothing per frame. Cards, lists, fields, chips. */
fun Modifier.lightGlass(shape: Shape): Modifier = this
    .clip(shape)
    .background(Dby.GlassFill)
    .border(0.5.dp, Brush.verticalGradient(0f to Color(0x33FFFFFF), 0.25f to Dby.GlassBorder), shape)

/** What blur materials become on old phones and with Reduce blur on. */
fun Modifier.solidGlass(shape: Shape): Modifier = this.clip(shape).background(Dby.Solid).border(0.5.dp, Dby.GlassBorder, shape)

/** Liquid glass: refraction, light blur and vibrancy. The tab pill and floating buttons. */
@Composable
fun Modifier.liquidGlass(backdrop: Backdrop, shape: RoundedRectangularShape = Capsule()): Modifier =
    if (!blurOn()) {
        solidGlass(shape)
    } else {
        drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(8.dp.toPx())
                lens(24.dp.toPx(), 24.dp.toPx(), depthEffect = true, chromaticAberration = true)
            },
            onDrawSurface = { drawRect(Color(0x66121212)) },
        )
    }

/** Frosted glass: heavy blur over what scrolls underneath. The pager bar and sheets. */
@Composable
fun Modifier.frostedGlass(backdrop: Backdrop, shape: RoundedRectangularShape): Modifier =
    if (!blurOn()) {
        solidGlass(shape)
    } else {
        drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                colorControls(saturation = 1.8f)
                blur(24.dp.toPx())
            },
            shadow = null,
            onDrawSurface = { drawRect(Dby.GlassFill) },
        ).border(0.5.dp, Dby.GlassBorder, shape)
    }

/**
 * A screen whose content is recorded as the backdrop its floating glass draws from. The
 * background is drawn inside the recording, so blur always has opaque pixels to work with.
 */
@Composable
fun GlassHost(
    modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.(Backdrop) -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    val backdrop = rememberLayerBackdrop()
    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop).background(Dby.Bg), content = content)
        overlay(backdrop)
    }
}
