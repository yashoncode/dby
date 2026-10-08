package com.dby.mobile.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** The canvas's stroke icons (24 × 24, round caps), as vectors so they tint like text. */
object DbyIcons {
    val Back = icon("m15 6-6 6 6 6")
    val Chevron = icon("m9 6 6 6-6 6")
    val ChevronDown = icon("m7 10 5 5 5-5")
    val Plus = icon("M12 5v14", "M5 12h14")
    val Close = icon("M6 6l12 12", "M18 6 6 18")
    val Check = icon("m5 12.5 4.5 4.5L19 7.5")
    val Search = icon(circle(11f, 11f, 7f), "m20 20-3.5-3.5")
    val More = icon(circle(5f, 12f, 0.8f), circle(12f, 12f, 0.8f), circle(19f, 12f, 0.8f), width = 2.4f)
    val Database = icon(ellipse(12f, 5.5f, 8f, 3f), "M4 5.5v13c0 1.66 3.58 3 8 3s8-1.34 8-3v-13", "M4 12c0 1.66 3.58 3 8 3s8-1.34 8-3")
    val Query = icon(rect(3f, 4f, 18f, 16f, 3f), "m7.5 9 3 3-3 3", "M13 15h4")
    val History = icon("M3 12a9 9 0 1 0 3-6.7L3 8", "M3 3v5h5", "M12 7v5l3 2")
    val Settings = icon("M4 7h9", "M19 7h1", circle(16f, 7f, 2.5f), "M4 17h1", "M11 17h9", circle(8f, 17f, 2.5f))
    val Table = icon(rect(3f, 4f, 18f, 16f, 2.5f), "M3 10h18", "M9 10v10")
    val View = icon(rect(3f, 4f, 18f, 16f, 2.5f), "M3 10h18", "M7 14h10", "M7 17h6")
    val Lock = icon(rect(5f, 11f, 14f, 10f, 2f), "M8 11V7a4 4 0 0 1 8 0v4")
    val Unlock = icon(rect(5f, 11f, 14f, 10f, 2f), "M8 11V7a4 4 0 0 1 7.75-1.4")
    val Bookmark = icon("M6 3h12a1 1 0 0 1 1 1v17l-7-4-7 4V4a1 1 0 0 1 1-1z")
    val Info = icon(circle(12f, 12f, 9f), "M12 11v5", "M12 8h.01")
    val Play = icon("M7 4.5v15a1 1 0 0 0 1.5.86l12.5-7.5a1 1 0 0 0 0-1.72L8.5 3.64A1 1 0 0 0 7 4.5z")
    val Open = icon("M7 17 17 7", "M8 7h9v9")
    val Export = icon("M12 15V3", "m7.5 7.5 4.5-4.5 4.5 4.5", "M4 14v4a3 3 0 0 0 3 3h10a3 3 0 0 0 3-3v-4")
    val Filter = icon("M3 5h18l-7 8v6l-4 2v-8z")
    val Sort = icon("M7 4v16", "m3 16 4 4 4-4", "M14 6h7", "M14 12h5", "M14 18h3")
    val Cards = icon(rect(3f, 4f, 18f, 7f, 2f), rect(3f, 13f, 18f, 7f, 2f))
    val Grid = icon(rect(3f, 4f, 18f, 16f, 2.5f), "M3 10h18", "M3 15h18", "M10 4v16")
    val Key = icon(circle(8f, 15f, 4f), "m11 12 9-9", "m17 6 3 3")
    val ArrowDown = icon("M12 5v14", "m6 13 6 6 6-6")
    val ArrowUp = icon("M12 19V5", "m6 11 6-6 6 6")
    val Pin = icon("M12 17v5", "M9 3h6l-1 7 4 4H6l4-4z")
    val Trash = icon("M4 7h16", "M10 11v6", "M14 11v6", "M6 7l1 13a1 1 0 0 0 1 1h8a1 1 0 0 0 1-1l1-13", "M9 7V4h6v3")
    val Copy = icon(rect(8f, 8f, 12f, 12f, 2f), "M16 8V5a1 1 0 0 0-1-1H5a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h3")
    val Refresh = icon("M20 12a8 8 0 1 1-2.34-5.66", "M20 4v5h-5")
    val Edit = icon("M4 20h4L19 9l-4-4L4 16z", "m13.5 6.5 4 4")
    val Unplug = icon("M9 7V3", "M15 7V3", "M7 7h10v4a5 5 0 0 1-10 0z", "M12 16v5")
    val Download = icon("M12 3v12", "m7.5 10.5 4.5 4.5 4.5-4.5", "M4 15v3a3 3 0 0 0 3 3h10a3 3 0 0 0 3-3v-3")
}

private fun icon(vararg paths: String, width: Float = 1.8f): ImageVector =
    ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
        for (d in paths) {
            addPath(
                pathData = addPathNodes(d),
                stroke = SolidColor(Color.White),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

private fun circle(cx: Float, cy: Float, r: Float) = ellipse(cx, cy, r, r)

private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float) =
    "M${cx - rx} ${cy}a$rx $ry 0 1 0 ${2 * rx} 0a$rx $ry 0 1 0 ${-2 * rx} 0"

private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float) =
    "M${x + r} ${y}h${w - 2 * r}a$r $r 0 0 1 $r ${r}v${h - 2 * r}a$r $r 0 0 1 ${-r} ${r}" +
        "h${-(w - 2 * r)}a$r $r 0 0 1 ${-r} ${-r}v${-(h - 2 * r)}a$r $r 0 0 1 $r ${-r}z"
