package com.nanci.codexremote.ui.shell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

val ShellInk = Color(0xFF0D0D0D)
val ShellMuted = Color(0xFF5D5D5D)
val ShellBackground = Color(0xFFFAFAFA)
val ShellField = Color(0xFFF3F3F3)

/** Small line icons at the native app's 24 dp viewport, without a font dependency. */
@Composable
fun ShellGlyph(name: String, modifier: Modifier = Modifier.size(24.dp), tint: Color = ShellInk, description: String? = null) {
    Canvas(modifier.then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)) {
        withTransform({ scale(size.width / 24f, size.height / 24f, Offset.Zero) }) {
            val stroke = Stroke(1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            fun line(x: Float, y: Float, a: Float, b: Float) = drawLine(tint, Offset(x, y), Offset(a, b), 1.8f, StrokeCap.Round)
            fun path(vararg pts: Float) { val p = Path(); p.moveTo(pts[0], pts[1]); for (i in 2 until pts.size step 2) p.lineTo(pts[i], pts[i + 1]); drawPath(p, tint, style = stroke) }
            fun round(x: Float, y: Float, w: Float, h: Float, r: Float = 2.5f) = drawRoundRect(tint, Offset(x, y), Size(w, h), CornerRadius(r), style = stroke)
            when (name) {
                "plus" -> { line(4f, 12f, 20f, 12f); line(12f, 4f, 12f, 20f) }
                "x" -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
                "back" -> { path(10f, 5f, 3f, 12f, 10f, 19f); line(3f, 12f, 21f, 12f) }
                "chevron" -> path(6f, 9f, 12f, 15f, 18f, 9f)
                "right" -> path(9f, 5f, 16f, 12f, 9f, 19f)
                "search" -> { drawCircle(tint, 6.5f, Offset(10f, 10f), style = stroke); line(15f, 15f, 21f, 21f) }
                "menu" -> { line(4f, 8f, 20f, 8f); line(4f, 15f, 15f, 15f) }
                "laptop" -> { round(5f, 4f, 14f, 12f, 1.5f); path(5f, 16f, 2.5f, 19f, 21.5f, 19f, 19f, 16f); line(5f, 16f, 19f, 16f) }
                "cloud" -> { val p = Path(); p.moveTo(6f, 19f); p.cubicTo(0f, 19f, 0f, 10f, 6f, 10f); p.cubicTo(5f, 2f, 18f, 2f, 18f, 10f); p.cubicTo(24f, 9f, 24f, 19f, 18f, 19f); p.close(); drawPath(p, tint, style = stroke) }
                "folder" -> { val p = Path(); p.moveTo(3f, 8f); p.lineTo(3f, 6f); p.quadraticBezierTo(3f, 3.5f, 5.5f, 3.5f); p.lineTo(10f, 3.5f); p.lineTo(13f, 6f); p.lineTo(19f, 6f); p.quadraticBezierTo(21f, 6f, 21f, 8f); p.lineTo(21f, 18f); p.quadraticBezierTo(21f, 20f, 19f, 20f); p.lineTo(5f, 20f); p.quadraticBezierTo(3f, 20f, 3f, 18f); p.close(); drawPath(p, tint, style = stroke); line(3f, 9f, 21f, 9f) }
                "folder-down", "folder-plus", "folder-minus" -> { val p = Path(); p.moveTo(3f, 8f); p.lineTo(3f, 6f); p.quadraticBezierTo(3f, 3.5f, 5.5f, 3.5f); p.lineTo(10f, 3.5f); p.lineTo(13f, 6f); p.lineTo(19f, 6f); p.quadraticBezierTo(21f, 6f, 21f, 8f); p.lineTo(21f, 18f); p.quadraticBezierTo(21f, 20f, 19f, 20f); p.lineTo(5f, 20f); p.quadraticBezierTo(3f, 20f, 3f, 18f); p.close(); drawPath(p, tint, style = stroke)
                    when (name) { "folder-down" -> { line(12f, 9f, 12f, 17f); path(9f, 14f, 12f, 17f, 15f, 14f) }; "folder-plus" -> { line(8f, 13f, 16f, 13f); line(12f, 9f, 12f, 17f) }; else -> line(8f, 13f, 16f, 13f) } }
                "pencil", "new" -> { path(6f, 16f, 7f, 12f, 17f, 2f, 21f, 6f, 11f, 16f, 6f, 17f); line(15f, 4f, 19f, 8f); if (name == "new") { val p = Path(); p.moveTo(11f, 4f); p.lineTo(6f, 4f); p.quadraticBezierTo(3f, 4f, 3f, 7f); p.lineTo(3f, 18f); p.quadraticBezierTo(3f, 21f, 6f, 21f); p.lineTo(17f, 21f); p.quadraticBezierTo(20f, 21f, 20f, 18f); p.lineTo(20f, 13f); drawPath(p, tint, style = stroke) } }
                "more" -> listOf(5f, 12f, 19f).forEach { drawCircle(tint, 1.7f, Offset(12f, it)) }
                "copy" -> { round(8f, 3f, 12f, 14f); round(3f, 8f, 12f, 13f) }
                "archive" -> { round(4f, 7f, 16f, 14f, 2f); round(3f, 3f, 18f, 4f, 1f); line(9f, 11f, 15f, 11f) }
                "trash" -> { line(3f, 6f, 21f, 6f); path(9f, 6f, 9f, 3f, 15f, 3f, 15f, 6f); path(5f, 6f, 6f, 21f, 18f, 21f, 19f, 6f); line(10f, 10f, 10f, 17f); line(14f, 10f, 14f, 17f) }
                "pin", "unpin" -> { path(8f, 3f, 17f, 3f, 15f, 6f, 15f, 10f, 20f, 15f, 5f, 15f, 10f, 10f, 10f, 6f, 8f, 3f); line(12f, 15f, 12f, 22f); if (name == "unpin") line(3f, 2f, 22f, 21f) }
                "pin-tilted", "unpin-tilted" -> { withTransform({ rotate(40f, Offset(12f, 12f)) }) { path(8f, 3f, 17f, 3f, 15f, 6f, 15f, 10f, 19f, 14f, 5f, 14f, 9f, 10f, 9f, 6f, 8f, 3f); line(12f, 14f, 12f, 22f) }; if (name == "unpin-tilted") line(3f, 2f, 22f, 21f) }
                "unread" -> drawCircle(tint, 8f, Offset(12f, 12f), style = stroke)
                "check" -> path(4f, 12f, 9f, 17f, 20f, 5f)
                "clock" -> { drawCircle(tint, 9f, Offset(12f, 12f), style = stroke); path(12f, 6f, 12f, 12f, 8f, 16f) }
                "bell" -> { val p = Path(); p.moveTo(4f, 18f); p.cubicTo(4f, 15f, 6f, 15f, 6f, 9f); p.cubicTo(6f, 1f, 18f, 1f, 18f, 9f); p.cubicTo(18f, 15f, 20f, 15f, 20f, 18f); p.close(); drawPath(p, tint, style = stroke); drawArc(tint, 0f, 180f, false, Offset(9f, 17f), Size(6f, 5f), style = stroke) }
                "settings" -> { val p = Path(); val pts = arrayOf(9f to 2f, 15f to 2f, 17f to 6f, 21f to 7f, 22f to 13f, 19f to 16f, 18f to 21f, 12f to 22f, 8f to 19f, 4f to 19f, 2f to 13f, 4f to 9f, 4f to 5f, 8f to 5f); pts.forEachIndexed { i, a -> if (i == 0) p.moveTo(a.first, a.second) else p.lineTo(a.first, a.second) }; p.close(); drawPath(p, tint, style = stroke); drawCircle(tint, 3.5f, Offset(12f, 12f), style = stroke) }
                "camera" -> { val p = Path(); p.moveTo(3f, 7f); p.lineTo(7f, 7f); p.lineTo(9f, 4f); p.lineTo(15f, 4f); p.lineTo(17f, 7f); p.lineTo(21f, 7f); p.lineTo(21f, 20f); p.lineTo(3f, 20f); p.close(); drawPath(p, tint, style = stroke); drawCircle(tint, 4f, Offset(12f, 13f), style = stroke) }
                "photos" -> { round(7f, 3f, 14f, 15f, 2f); round(2f, 8f, 14f, 14f, 2f); path(3f, 19f, 7f, 14f, 11f, 18f, 14f, 15f, 16f, 18f); drawCircle(tint, 1.4f, Offset(6f, 12f)) }
                "file" -> { path(5f, 2f, 14f, 2f, 20f, 8f, 20f, 22f, 5f, 22f, 5f, 2f); path(14f, 2f, 14f, 8f, 20f, 8f) }
                "plan" -> { path(2f, 5f, 4f, 7f, 7f, 3f); line(11f, 5f, 21f, 5f); drawCircle(tint, 2f, Offset(4f, 13f), style = stroke); line(11f, 13f, 21f, 13f); drawCircle(tint, 2f, Offset(4f, 21f), style = stroke); line(11f, 21f, 21f, 21f) }
                "hand" -> { val p = Path(); p.moveTo(7f, 12f); p.lineTo(7f, 6f); p.cubicTo(7f, 3f, 10f, 3f, 10f, 6f); p.lineTo(10f, 11f); p.lineTo(10f, 3f); p.cubicTo(10f, 0f, 13f, 0f, 13f, 3f); p.lineTo(13f, 11f); p.lineTo(13f, 5f); p.cubicTo(13f, 2f, 16f, 2f, 16f, 5f); p.lineTo(16f, 12f); p.lineTo(16f, 8f); p.cubicTo(16f, 5f, 19f, 5f, 19f, 8f); p.lineTo(19f, 15f); p.cubicTo(19f, 23f, 9f, 25f, 5f, 17f); p.lineTo(3f, 13f); p.cubicTo(2f, 10f, 5f, 9f, 7f, 12f); drawPath(p, tint, style = stroke) }
                "shield", "readonly" -> { path(12f, 2f, 21f, 6f, 20f, 15f, 17f, 19f, 12f, 22f, 7f, 19f, 4f, 15f, 3f, 6f, 12f, 2f); if (name == "shield") path(8f, 12f, 11f, 15f, 17f, 8f) else { round(8f, 10f, 8f, 7f, 1f); drawArc(tint, 180f, 180f, false, Offset(9f, 6f), Size(6f, 8f), style = stroke) } }
                "warning" -> { path(12f, 3f, 22f, 21f, 2f, 21f, 12f, 3f); line(12f, 9f, 12f, 14f); drawCircle(tint, 1f, Offset(12f, 18f)) }
                "send" -> { path(5f, 10f, 12f, 3f, 19f, 10f); line(12f, 3f, 12f, 21f) }
                "stop" -> drawRoundRect(tint, Offset(7f, 7f), Size(10f, 10f), CornerRadius(1.5f))
                "link" -> { val p = Path(); p.moveTo(10f, 5f); p.cubicTo(15f, -1f, 24f, 6f, 18f, 12f); p.lineTo(15f, 15f); p.cubicTo(13f, 17f, 10f, 17f, 9f, 15f); drawPath(p, tint, style = stroke); val q = Path(); q.moveTo(14f, 19f); q.cubicTo(9f, 25f, 0f, 18f, 6f, 12f); q.lineTo(9f, 9f); q.cubicTo(11f, 7f, 14f, 7f, 15f, 9f); drawPath(q, tint, style = stroke) }
                else -> drawCircle(tint, 7f, Offset(12f, 12f), style = stroke)
            }
        }
    }
}
