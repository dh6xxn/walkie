package com.dhananjay.walkie

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.livekit.android.LiveKit
import io.livekit.android.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

private const val LIVEKIT_URL = "wss://walkie-c8ioe9eg.livekit.cloud"
private const val TOKEN_URL = "https://walkie.vercel.app/api/token"
private const val DEFAULT_ROOM = "friends"

class MainActivity : ComponentActivity() {
    private var room: Room? = null
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        setContent {
            WalkieApp(
                onConnect = { connectToRoom() },
                onDisconnect = { disconnectFromRoom() },
                onTalkStart = { setMicrophone(true) },
                onTalkEnd = { setMicrophone(false) },
                isConnected = room != null
            )
        }
    }

    private fun connectToRoom() {
        if (room != null) return
        kotlinx.coroutines.MainScope().launch {
            try {
                val identity = "android-${UUID.randomUUID()}"
                val token = fetchToken(identity, DEFAULT_ROOM)
                
                // Use LiveKit.connect() to create and connect to a room
                val connectedRoom = LiveKit.connect(
                    applicationContext,
                    LIVEKIT_URL,
                    token
                )
                room = connectedRoom
                setMicrophone(false)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun disconnectFromRoom() {
        kotlinx.coroutines.MainScope().launch {
            try {
                room?.localParticipant?.setMicrophoneEnabled(false)
                room?.disconnect()
            } finally {
                room = null
            }
        }
    }

    private fun setMicrophone(enabled: Boolean) {
        val currentRoom = room ?: return
        kotlinx.coroutines.MainScope().launch {
            try {
                currentRoom.localParticipant.setMicrophoneEnabled(enabled)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private suspend fun fetchToken(identity: String, roomName: String): String = withContext(Dispatchers.IO) {
        val connection = (URL(TOKEN_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 10_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            val body = JSONObject().apply {
                put("identity", identity)
                put("room", roomName)
            }.toString()
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val response = if (connection.responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Token request failed"
            }
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException(response)
            }
            JSONObject(response).getString("token")
        } finally {
            connection.disconnect()
        }
    }
}

@Composable
private fun WalkieApp(
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onTalkStart: () -> Unit,
    onTalkEnd: () -> Unit,
    isConnected: Boolean
) {
    var talking by remember { mutableStateOf(false) }

    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(40.dp))
                Text("Walkie", style = MaterialTheme.typography.headlineLarge)
                Text(if (isConnected) "Connected • Friends" else "Not connected")
                Spacer(Modifier.weight(1f))

                Button(onClick = {
                    if (isConnected) onDisconnect() else onConnect()
                }) {
                    Text(if (isConnected) "Disconnect" else "Connect")
                }

                Spacer(Modifier.height(24.dp))

                Box(
                    modifier = Modifier
                        .size(220.dp)
                        .pointerInput(isConnected) {
                            detectTapGestures(
                                onPress = {
                                    if (!isConnected) return@detectTapGestures
                                    talking = true
                                    onTalkStart()
                                    tryAwaitRelease()
                                    talking = false
                                    onTalkEnd()
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        shape = MaterialTheme.shapes.extraLarge,
                        tonalElevation = 6.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(if (talking) "RELEASE" else "HOLD TO TALK")
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
                Text(
                    when {
                        !isConnected -> "Connect to start"
                        talking -> "Transmitting microphone audio"
                        else -> "Listening"
                    }
                )
                Spacer(Modifier.weight(1f))
            }
        }
    }
}
