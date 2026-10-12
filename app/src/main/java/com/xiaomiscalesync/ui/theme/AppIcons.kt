package com.xiaomiscalesync.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The handful of 24dp glyphs the app needs, drawn from Material Symbols
 * outlines — hand-built so the app carries no icon-library dependency.
 * Tinted by the caller (`Icon` applies LocalContentColor).
 */
object AppIcons {

    private fun icon(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .path(fill = SolidColor(Color.Black), pathBuilder = block)
            .build()

    /** House outline. */
    val Home: ImageVector = icon("home") {
        moveTo(12f, 3f); lineTo(2f, 12f); horizontalLineTo(5f); verticalLineTo(20f)
        horizontalLineTo(10f); verticalLineTo(14f); horizontalLineTo(14f); verticalLineTo(20f)
        horizontalLineTo(19f); verticalLineTo(12f); horizontalLineTo(22f); close()
    }

    /** Three list bars with bullets. */
    val History: ImageVector = icon("history") {
        moveTo(3f, 5f); horizontalLineTo(5f); verticalLineTo(7f); horizontalLineTo(3f); close()
        moveTo(7f, 5f); horizontalLineTo(21f); verticalLineTo(7f); horizontalLineTo(7f); close()
        moveTo(3f, 11f); horizontalLineTo(5f); verticalLineTo(13f); horizontalLineTo(3f); close()
        moveTo(7f, 11f); horizontalLineTo(21f); verticalLineTo(13f); horizontalLineTo(7f); close()
        moveTo(3f, 17f); horizontalLineTo(5f); verticalLineTo(19f); horizontalLineTo(3f); close()
        moveTo(7f, 17f); horizontalLineTo(21f); verticalLineTo(19f); horizontalLineTo(7f); close()
    }

    /** Person: head circle + shoulders. */
    val Person: ImageVector = icon("person") {
        moveTo(12f, 4f)
        arcToRelative(4f, 4f, 0f, true, true, 0f, 8f)
        arcToRelative(4f, 4f, 0f, true, true, 0f, -8f)
        close()
        moveTo(4f, 20f); verticalLineTo(18.5f)
        curveTo(4f, 15.5f, 9f, 14f, 12f, 14f)
        curveTo(15f, 14f, 20f, 15.5f, 20f, 18.5f)
        verticalLineTo(20f); close()
    }

    /** Vertical ellipsis (overflow menu). */
    val More: ImageVector = icon("more") {
        for (cy in listOf(5f, 12f, 19f)) {
            moveTo(12f, cy - 2f)
            arcToRelative(2f, 2f, 0f, true, true, 0f, 4f)
            arcToRelative(2f, 2f, 0f, true, true, 0f, -4f)
            close()
        }
    }

    /** Close (×). */
    val Close: ImageVector = icon("close") {
        moveTo(6.4f, 5f); lineTo(12f, 10.6f); lineTo(17.6f, 5f); lineTo(19f, 6.4f)
        lineTo(13.4f, 12f); lineTo(19f, 17.6f); lineTo(17.6f, 19f); lineTo(12f, 13.4f)
        lineTo(6.4f, 19f); lineTo(5f, 17.6f); lineTo(10.6f, 12f); lineTo(5f, 6.4f); close()
    }

    /** Trash can. */
    val Delete: ImageVector = icon("delete") {
        moveTo(9f, 3f); verticalLineTo(4f); horizontalLineTo(4f); verticalLineTo(6f)
        horizontalLineTo(20f); verticalLineTo(4f); horizontalLineTo(15f); verticalLineTo(3f); close()
        moveTo(6f, 7f); verticalLineTo(19f); arcToRelative(2f, 2f, 0f, false, false, 2f, 2f)
        horizontalLineTo(16f); arcToRelative(2f, 2f, 0f, false, false, 2f, -2f); verticalLineTo(7f); close()
    }
}
