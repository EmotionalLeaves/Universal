package com.emotionalleaves.universe

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val version = AppInfo.version(this)
        setContent {
            var crash by remember { mutableStateOf(CrashReporter.read(this)) }
            val report = crash
            if (report != null) {
                CrashScreen(
                    report = report,
                    onContinue = {
                        CrashReporter.clear(this)
                        crash = null
                    },
                )
            } else {
                HomeScreen(version = version)
            }
        }
    }
}

@Composable
private fun HomeScreen(version: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .safeDrawingPadding(),
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Universe Explorer",
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Version $version",
                color = Color(0xFF8A8FA3),
                fontSize = 14.sp,
            )
        }
        // Lets you confirm the crash screen works. Remove in Round 14.
        TextButton(
            onClick = { throw RuntimeException("Test crash from the Round 0 button") },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
        ) {
            Text("Test crash screen", color = Color(0xFF5A5F73), fontSize = 13.sp)
        }
    }
}

@Composable
private fun CrashScreen(report: String, onContinue: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF120A0A))
            .safeDrawingPadding()
            .padding(16.dp),
    ) {
        Text(
            text = "The app crashed last time",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Tap Copy and paste this to the AI that is building the app.",
            color = Color(0xFFC9B8B8),
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF1E1414))
                .padding(12.dp)
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState()),
        ) {
            Text(
                text = report,
                color = Color(0xFFFFD6D6),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = {
                    clipboard.setText(AnnotatedString(report))
                    Toast.makeText(context, "Crash report copied", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f),
            ) { Text("Copy") }
            OutlinedButton(
                onClick = onContinue,
                modifier = Modifier.weight(1f),
            ) { Text("Continue", color = Color.White) }
        }
    }
}
