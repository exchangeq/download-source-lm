package com.downloadsource

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

class CrashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val stack = intent.getStringExtra(EXTRA_STACK) ?: ""
        setContent { CrashScreen(stack) { restart() } }
    }

    private fun restart() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    companion object { const val EXTRA_STACK = "stack" }
}

@Composable
private fun CrashScreen(stack: String, onRestart: () -> Unit) {
    val ctx = LocalContext.current
    Box(
        Modifier.fillMaxSize().background(
            Brush.linearGradient(listOf(Color(0xFF1E1B4B), Color(0xFF4C1D95), Color(0xFF831843)))
        )
    ) {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("发生错误", color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("应用遇到未捕获的异常，堆栈如下。", color = Color(0xFFE9D5FF))
            Box(
                Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.4f)).padding(12.dp)
            ) {
                Text(stack, color = Color(0xFFF5D0FE), modifier = Modifier.verticalScroll(rememberScrollState()))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onRestart, modifier = Modifier.weight(1f)) { Text("重启") }
                OutlinedButton(
                    onClick = { (ctx as? ComponentActivity)?.finishAffinity() },
                    modifier = Modifier.weight(1f)
                ) { Text("退出", color = Color.White) }
            }
        }
    }
}
