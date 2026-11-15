package com.flipfocus.app

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val QUOTES = listOf(
    "The successful warrior is the average person, with laser-like focus.",
    "You can do anything, but not everything.",
    "Where your attention goes, your life goes.",
    "Discipline is choosing between what you want now and what you want most.",
    "Small distractions, repeated daily, become a lost year.",
    "Your future self is watching. Make them proud.",
    "Boredom is the doorway to deep work.",
    "Do the hard thing first. The scroll will still be there later.",
    "Focus is a muscle. This is the rep.",
    "Not now. You have something better to finish.",
)

class BlockActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(AColor.TRANSPARENT), SystemBarStyle.dark(AColor.TRANSPARENT))
        val pkg = intent.getStringExtra("pkg") ?: ""
        val label = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))).toString()
        } catch (e: Exception) { "This app" }
        val quote = QUOTES.random()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        })
        setContent {
            AppTheme {
                Column(
                    Modifier.fillMaxSize().background(C.bg).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Text("$label is blocked", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = C.text, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(20.dp))
                    Text("\u201c$quote\u201d", fontSize = 18.sp, color = C.muted, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(48.dp))
                    Pill("Return to home", true, { goHome() }, Modifier.fillMaxWidth())
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent); recreate()
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finishAndRemoveTask()
    }
}
