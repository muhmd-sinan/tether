package dev.tether.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object Tokens {
    val Bg = Color(0xFF151311)
    val Surface = Color(0xFF201D1A)
    val Raised = Color(0xFF2A2622)
    val Ink = Color(0xFFECE6DC)
    val Dim = Color(0xFFA39B91)
    val Accent = Color(0xFFF0743E)
    val OnAccent = Color(0xFF1A1410)
    val Good = Color(0xFF8FBF8A)
    val Warn = Color(0xFFE2B95A)
    val Bad = Color(0xFFE5484D)
}

@Composable
fun TetherTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Tokens.Accent,
            onPrimary = Tokens.OnAccent,
            background = Tokens.Bg,
            onBackground = Tokens.Ink,
            surface = Tokens.Surface,
            onSurface = Tokens.Ink,
            surfaceVariant = Tokens.Raised,
            onSurfaceVariant = Tokens.Dim,
            surfaceContainer = Tokens.Surface,
            surfaceContainerHigh = Tokens.Raised,
            surfaceContainerHighest = Tokens.Raised,
            outline = Tokens.Raised,
            error = Tokens.Bad,
        ),
        shapes = Shapes(
            small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(8.dp),
            large = RoundedCornerShape(16.dp),
            extraLarge = RoundedCornerShape(16.dp),
        ),
        content = content,
    )
}
