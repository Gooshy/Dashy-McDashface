package net.gooshy.omodadash

import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import net.gooshy.omodadash.R
import kotlin.math.tan

/** Colour tokens from the design's `TH` table. */
data class Palette(
    val bg: Color, val panel: Color, val ink: Color, val mut: Color, val dim: Color,
    val off: Color, val line: Color, val red: Color, val amb: Color, val grn: Color,
    val pur: Color, val yel: Color, val blu: Color, val tex: Color, val night: Boolean,
) {
    companion object {
        val NIGHT = Palette(
            bg = Color(0xFF000000), panel = Color(0xFF0A0A0B), ink = Color(0xFFF4F1EA), mut = Color(0xFF9A948C),
            dim = Color(0xFF34302C), off = Color(0xFF1A0907), line = Color(0xFF24211E), red = Color(0xFFFF2B1C),
            amb = Color(0xFFFFB21A), grn = Color(0xFF1FE07A), pur = Color(0xFFB566FF), yel = Color(0xFFFFE03A),
            blu = Color(0xFF3DA8FF), tex = Color(1f, 1f, 1f, 0.03f), night = true,
        )
        val DAY = Palette(
            bg = Color(0xFFEEECE6), panel = Color(0xFFFFFFFF), ink = Color(0xFF050505), mut = Color(0xFF4A4540),
            dim = Color(0xFFC9C4BA), off = Color(0xFFDEDAD1), line = Color(0xFFCFC9BE), red = Color(0xFFD0120A),
            amb = Color(0xFFA96400), grn = Color(0xFF00874A), pur = Color(0xFF7420D6), yel = Color(0xFF806A00),
            blu = Color(0xFF0057C2), tex = Color(0f, 0f, 0f, 0.035f), night = false,
        )
    }
}

val Mono = FontFamily(Font(R.font.share_tech_mono))
val Saira = FontFamily(
    Font(R.font.saira_condensed_semibold, FontWeight.SemiBold),
    Font(R.font.saira_condensed_bold, FontWeight.Bold),
    Font(R.font.saira_condensed_extrabold, FontWeight.ExtraBold),
)

/** `clip-path: polygon(0 0, 100%-c 0, 100% c, 100% 100%, 0 100%)`. */
fun cutCorner(c: Float) = GenericShape { size, _ ->
    moveTo(0f, 0f); lineTo(size.width - c, 0f); lineTo(size.width, c)
    lineTo(size.width, size.height); lineTo(0f, size.height); close()
}

/** The design's `skewX(-9deg)` on the big numerals, around the element's centre. */
fun Modifier.italic(deg: Float = 9f): Modifier = drawWithContent {
    val k = tan(Math.toRadians(deg.toDouble())).toFloat()
    drawIntoCanvas { c ->
        c.nativeCanvas.save()
        c.nativeCanvas.translate(k * size.height / 2, 0f)
        c.nativeCanvas.skew(-k, 0f)
        drawContent()
        c.nativeCanvas.restore()
    }
}

/** Carbon-fibre cross-hatch: 2 px lines every 6 px at ±45°, built once per size. */
fun Modifier.carbon(p: Palette, both: Boolean = true): Modifier = drawWithCache {
    val path = Path()
    val span = size.width + size.height
    var d = -size.height
    while (d < span) {
        path.moveTo(d, 0f); path.lineTo(d + size.height, size.height)
        if (both) { path.moveTo(d + size.height, 0f); path.lineTo(d, size.height) }
        d += 6f
    }
    onDrawBehind {
        drawRect(p.panel)
        drawPath(path, p.tex, style = Stroke(width = 2f))
    }
}

/** One reusable framework paint for glows; all drawing happens on the main thread. */
private val glowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

/** A filled rect or path with a coloured glow — the canvas `shadowBlur` of the design. */
fun DrawScope.glowRect(color: Color, left: Float, top: Float, w: Float, h: Float, blur: Float, alpha: Float = 1f) {
    drawIntoCanvas { c ->
        glowPaint.style = android.graphics.Paint.Style.FILL
        glowPaint.color = color.copy(alpha = color.alpha * alpha).toArgb()
        if (blur > 0.5f) glowPaint.setShadowLayer(blur, 0f, 0f, color.copy(alpha = alpha).toArgb()) else glowPaint.clearShadowLayer()
        c.nativeCanvas.drawRect(left, top, left + w, top + h, glowPaint)
    }
}

fun DrawScope.glowPath(color: Color, path: android.graphics.Path, blur: Float) {
    drawIntoCanvas { c ->
        glowPaint.style = android.graphics.Paint.Style.FILL
        glowPaint.color = color.toArgb()
        if (blur > 0.5f) glowPaint.setShadowLayer(blur, 0f, 0f, color.toArgb()) else glowPaint.clearShadowLayer()
        c.nativeCanvas.drawPath(path, glowPaint)
    }
}

fun DrawScope.glowCircle(color: Color, center: Offset, radius: Float, glow: Color, blur: Float) {
    drawIntoCanvas { c ->
        glowPaint.style = android.graphics.Paint.Style.FILL
        glowPaint.color = color.toArgb()
        glowPaint.setShadowLayer(blur, 0f, 0f, glow.toArgb())
        c.nativeCanvas.drawCircle(center.x, center.y, radius, glowPaint)
        glowPaint.clearShadowLayer()
    }
}

/** CSS `color-mix(in srgb, c pct%, base)`. */
fun mix(c: Color, pct: Float, base: Color) = Color(
    red = c.red * pct + base.red * (1 - pct),
    green = c.green * pct + base.green * (1 - pct),
    blue = c.blue * pct + base.blue * (1 - pct),
)
