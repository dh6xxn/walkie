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
import androidx.lifecycle.lifecycleScope
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
    private var connected by mutableStateOf(false)
    private var connecting by mutableStateOf(false)
    private var talking by mutableStateOf(false)
    private var status by mutableStateOf("Not connected")

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) status = "Microphone permission is required"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestMicrophonePermissionIfNeeded()
        setContent {
            WalkieApp(
                onConnect = { connectToRoom() }, onDisconnect = { disconnectFromRoom() },
                onTalkStart = { setMicrophone(true) }, onTalkEnd = { setMicrophone(false) },
                isConnected = connected, isConnecting = connecting, isTalking = talking, status = status
            )
        }
    }

    private fun requestMicrophonePermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun connectToRoom() {
        if (connected || connecting) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            status = "Allow microphone permission, then Connect"
            return
        }
        connecting = true
        status = "Requesting connection token…"
        lifecycleScope.launch {
            try {
                val identity = "android-${UUID.randomUUID()}"
                val tokenResult = fetchToken(identity, DEFAULT_ROOM)
                status = "Connecting to voice server…"
                val createdRoom = LiveKit.create(applicationContext)
                createdRoom.connect(tokenResult.second.ifBlank { LIVEKIT_URL }, tokenResult.first)
                room = createdRoom
                createdRoom.localParticipant.setMicrophoneEnabled(false)
                connected = true
                status = "Connected • Friends"
            } catch (e: Exception) {
                e.printStackTrace()
                try { room?.disconnect() } catch (_: Exception) { }
                room = null
                connected = false
                talking = false
                status = "Connection failed: ${e.message ?: e.javaClass.simpleName}"
            } finally { connecting = false }
        }
    }

    private fun disconnectFromRoom() {
        lifecycleScope.launch {
            try { room?.localParticipant?.setMicrophoneEnabled(false); room?.disconnect() }
            finally { room = null; connected = false; talking = false; status = "Not connected" }
        }
    }

    private fun setMicrophone(enabled: Boolean) {
        val currentRoom = room ?: return
        if (!connected) return
        talking = enabled
        lifecycleScope.launch {
            try {
                currentRoom.localParticipant.setMicrophoneEnabled(enabled)
                status = if (enabled) "Transmitting microphone audio" else "Listening"
            } catch (e: Exception) {
                talking = false
                status = "Microphone error: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private suspend fun fetchToken(identity: String, roomName: String): Pair<String, String> = withContext(Dispatchers.IO) {
        var targetUrl = TOKEN_URL
        var redirects = 0
        while (true) {
            val connection = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
                instanceFollowRedirects = false
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }
            try {
                val body = JSONObject().apply { put("identity", identity); put("room", roomName) }.toString()
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode

                if (code == HttpURLConnection.HTTP_MOVED_TEMP || code == HttpURLConnection.HTTP_MOVED_PERM || code == 307 || code == 308) {
                    val location = connection.getHeaderField("Location")
                    if (location.isNullOrBlank()) throw IllegalStateException("Token server returned HTTP $code without a redirect location")
                    if (++redirects > 5) throw IllegalStateException("Token server redirected too many times")
                    targetUrl = URL(URL(targetUrl), location).toString()
                    continue
                }

                val response = if (code in 200..299) connection.inputStream.bufferedReader().use { it.readText() } else connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
                val json = runCatching { JSONObject(response) }.getOrElse { throw IllegalStateException("Token server returned HTTP $code: $response") }
                if (code !in 200..299) throw IllegalStateException(json.optString("error", "Token request failed (HTTP $code)"))
                val token = json.optString("participant_token").ifBlank { json.optString("token") }
                if (token.isBlank()) throw IllegalStateException("Token server returned no access token")
                return@withContext Pair(token, json.optString("server_url"))
            } finally { connection.disconnect() }
        }
    }

    override fun onDestroy() { room?.disconnect(); room = null; super.onDestroy() }
}

@Composable
private fun WalkieApp(
    onConnect: () -> Unit, onDisconnect: () -> Unit, onTalkStart: () -> Unit, onTalkEnd: () -> Unit,
    isConnected: Boolean, isConnecting: Boolean, isTalking: Boolean, status: String
) {
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(40.dp)); Text("Walkie", style = MaterialTheme.typography.headlineLarge); Text(status); Spacer(Modifier.weight(1f))
                Button(enabled = !isConnecting, onClick = { if (isConnected) onDisconnect() else onConnect() }) { Text(if (isConnected) "Disconnect" else if (isConnecting) "Connecting…" else "Connect") }
                Spacer(Modifier.height(24.dp))
                Box(Modifier.size(220.dp).pointerInput(isConnected) { detectTapGestures(onPress = { if (!isConnected) return@detectTapGestures; onTalkStart(); try { tryAwaitRelease() } finally { onTalkEnd() } }) }) {
                    Surface(Modifier.fillMaxSize(), shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) { Box(contentAlignment = Alignment.Center) { Text(if (isTalking) "RELEASE" else "HOLD TO TALK", style = MaterialTheme.typography.headlineSmall) } }
                }
                Spacer(Modifier.height(24.dp)); Text(if (isConnected) "Hold to transmit" else "Connect to start"); Spacer(Modifier.weight(1f))
            }
        }
    }
}
