package com.dhananjay.walkie

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        setContent { WalkieApp() }
    }
}

@Composable
private fun WalkieApp() {
    var talking by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf(false) }
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(40.dp))
                Text("Walkie", style = MaterialTheme.typography.headlineLarge)
                Text(if (connected) "Connected" else "Not connected")
                Spacer(Modifier.weight(1f))
                Button(onClick = { connected = !connected }) { Text(if (connected) "Disconnect" else "Connect") }
                Spacer(Modifier.height(24.dp))
                Button(
                    modifier = Modifier.size(220.dp),
                    onClick = { if (connected) talking = !talking },
                    colors = ButtonDefaults.buttonColors()
                ) { Text(if (talking) "RELEASE" else "HOLD TO TALK") }
                Spacer(Modifier.height(24.dp))
                Text(if (talking) "You are transmitting" else "Listening")
                Spacer(Modifier.weight(1f))
            }
        }
    }
}
