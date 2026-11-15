package com.flipfocus.app

import android.app.Activity
import android.graphics.Color as AColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

object C {
    val bg = Color(0xFF000000)       // OLED black
    val surface = Color(0xFF0D0F12)
    val line = Color(0xFF1C1F24)
    val text = Color(0xFFFFFFFF)
    val muted = Color(0xFF646464)
    val accent = Color(0xFF7FA87F)
    val bad = Color(0xFFD93025)
}

val Geist = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
    Font(R.font.geist_bold, FontWeight.Bold),
)
val Mono = FontFamily(Font(R.font.geist_mono_medium, FontWeight.Medium))

private fun TextStyle.g() = copy(fontFamily = Geist)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val t = Typography()
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = C.bg, surface = C.surface, onSurface = C.text, onBackground = C.text,
            primary = C.accent, onPrimary = Color.Black, surfaceVariant = C.surface,
            onSurfaceVariant = C.muted, outline = C.line, surfaceContainerHigh = C.surface,
        ),
        typography = t.copy(
            displayLarge = t.displayLarge.g(), displayMedium = t.displayMedium.g(), headlineLarge = t.headlineLarge.g(),
            headlineMedium = t.headlineMedium.g(), headlineSmall = t.headlineSmall.g(), titleLarge = t.titleLarge.g(),
            titleMedium = t.titleMedium.g(), titleSmall = t.titleSmall.g(), bodyLarge = t.bodyLarge.g(),
            bodyMedium = t.bodyMedium.g(), bodySmall = t.bodySmall.g(), labelLarge = t.labelLarge.g(),
            labelMedium = t.labelMedium.g(), labelSmall = t.labelSmall.g(),
        ),
        content = content,
    )
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
        )
        setContent { AppTheme { App() } }
    }
}
