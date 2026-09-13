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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

private const val LIVEKIT_URL = "wss://walkie-c8ioe9eg.livekit.cloud"
private const val TOKEN_URL = "https://walkie-tan.vercel.app/api/token"
private const val DEFAULT_ROOM = "friends"
private const val TALK_TOPIC = "walkie-talk-lock-v1"
private const val TALK_ARBITRATION_MS = 700L

class MainActivity : ComponentActivity() {
    private var room: Room? = null
    private var roomEventsJob: Job? = null
    private var talkRequestJob: Job? = null
    private var connected by mutableStateOf(false)
    private var connecting by mutableStateOf(false)
    private var talking by mutableStateOf(false)
    private var requestingTalk by mutableStateOf(false)
    private var speakerIdentity by mutableStateOf<String?>(null)
    private var status by mutableStateOf("Not connected")
    private var localIdentity = ""
    private val pendingTalkRequests = mutableMapOf<String, String>()

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) status = "Microphone permission is required"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestMicrophonePermissionIfNeeded()
        setContent {
            WalkieApp(
                onConnect = { connectToRoom() },
                onDisconnect = { disconnectFromRoom() },
                onTalkStart = { requestTalk() },
                onTalkEnd = { releaseTalk() },
                isConnected = connected,
                isConnecting = connecting,
                isTalking = talking,
                isRequestingTalk = requestingTalk,
                hasRemoteSpeaker = speakerIdentity != null && speakerIdentity != localIdentity,
                status = status
            )
        }
    }

    private fun requestMicrophonePermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
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
                localIdentity = identity
                val tokenResult = fetchToken(identity, DEFAULT_ROOM)
                status = "Connecting to voice server…"
                val createdRoom = LiveKit.create(applicationContext)
                createdRoom.connect(tokenResult.second.ifBlank { LIVEKIT_URL }, tokenResult.first)
                room = createdRoom
                createdRoom.localParticipant.setMicrophoneEnabled(false)
                roomEventsJob?.cancel()
                roomEventsJob = lifecycleScope.launch {
                    createdRoom.events.collect { event -> handleRoomEvent(createdRoom, event) }
                }
                connected = true
                status = "Connected • Friends"
            } catch (e: Exception) {
                e.printStackTrace()
                try { room?.disconnect() } catch (_: Exception) { }
                room = null
                connected = false
                talking = false
                requestingTalk = false
                speakerIdentity = null
                status = "Connection failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                connecting = false
            }
        }
    }

    private suspend fun handleRoomEvent(currentRoom: Room, event: RoomEvent) {
        when (event) {
            is RoomEvent.DataReceived -> {
                val sender = event.participant?.identity?.toString() ?: return
                if (sender == localIdentity) return
                val message = runCatching { JSONObject(String(event.data, Charsets.UTF_8)) }.getOrNull() ?: return
                if (event.topic != TALK_TOPIC) return
                when (message.optString("type")) {
                    "talk_request" -> {
                        val requestId = message.optString("requestId")
                        if (requestId.isBlank()) return
                        if (speakerIdentity != null) {
                            publishTalkMessage(currentRoom, JSONObject().apply {
                                put("type", "talk_busy")
                                put("speaker", speakerIdentity)
                            })
                        } else {
                            pendingTalkRequests[requestId] = sender
                        }
                    }
                    "talk_start" -> {
                        val speaker = message.optString("speaker").ifBlank { sender }
                        pendingTalkRequests.clear()
                        talkRequestJob?.cancel()
                        requestingTalk = false
                        speakerIdentity = speaker
                        if (speaker != localIdentity && talking) {
                            currentRoom.localParticipant.setMicrophoneEnabled(false)
                            talking = false
                        }
                        status = if (speaker == localIdentity) "Transmitting microphone audio" else "${displaySpeaker(speaker)} is talking"
                    }
                    "talk_end" -> {
                        val speaker = message.optString("speaker").ifBlank { sender }
                        if (speakerIdentity == speaker) {
                            speakerIdentity = null
                            if (!talking) status = "Listening"
                        }
                    }
                    "talk_busy" -> {
                        val speaker = message.optString("speaker")
                        if (requestingTalk && speaker.isNotBlank()) {
                            requestingTalk = false
                            talkRequestJob?.cancel()
                            speakerIdentity = speaker
                            status = "${displaySpeaker(speaker)} is talking"
                        }
                    }
                }
            }
            is RoomEvent.ParticipantDisconnected -> {
                val departed = event.participant.identity?.toString() ?: return
                pendingTalkRequests.entries.removeIf { it.value == departed }
                if (speakerIdentity == departed) {
                    speakerIdentity = null
                    if (!talking) status = "Listening"
                }
            }
            else -> Unit
        }
    }

    private fun requestTalk() {
        val currentRoom = room ?: return
        if (!connected || talking || requestingTalk) return
        if (speakerIdentity != null) {
            status = "${displaySpeaker(speakerIdentity!!)} is talking"
            return
        }
        requestingTalk = true
        status = "Requesting microphone…"
        val requestId = "${System.currentTimeMillis()}-$localIdentity"
        pendingTalkRequests[requestId] = localIdentity
        talkRequestJob?.cancel()
        talkRequestJob = lifecycleScope.launch {
            publishTalkMessage(currentRoom, JSONObject().apply {
                put("type", "talk_request")
                put("requestId", requestId)
                put("identity", localIdentity)
            })
            delay(TALK_ARBITRATION_MS)
            if (!requestingTalk || !connected || speakerIdentity != null) return@launch
            val winner = pendingTalkRequests.entries.minByOrNull { it.key }?.value
            if (winner == localIdentity) {
                pendingTalkRequests.clear()
                speakerIdentity = localIdentity
                talking = true
                requestingTalk = false
                currentRoom.localParticipant.setMicrophoneEnabled(true)
                publishTalkMessage(currentRoom, JSONObject().apply {
                    put("type", "talk_start")
                    put("speaker", localIdentity)
                })
                status = "Transmitting microphone audio"
            } else {
                requestingTalk = false
                status = "Another person got the microphone"
            }
        }
    }

    private fun releaseTalk() {
        val currentRoom = room ?: return
        if (!talking && !requestingTalk) return
        talkRequestJob?.cancel()
        requestingTalk = false
        if (talking) {
            talking = false
            speakerIdentity = null
            lifecycleScope.launch {
                currentRoom.localParticipant.setMicrophoneEnabled(false)
                publishTalkMessage(currentRoom, JSONObject().apply {
                    put("type", "talk_end")
                    put("speaker", localIdentity)
                })
                status = "Listening"
            }
        } else {
            pendingTalkRequests.entries.removeIf { it.value == localIdentity }
            status = "Listening"
        }
    }

    private suspend fun publishTalkMessage(currentRoom: Room, message: JSONObject) {
        currentRoom.localParticipant.publishData(
            message.toString().toByteArray(Charsets.UTF_8),
            topic = TALK_TOPIC
        )
    }

    private fun displaySpeaker(identity: String): String {
        return if (identity.startsWith("android-")) "Friend" else identity
    }

    private fun disconnectFromRoom() {
        talkRequestJob?.cancel()
        roomEventsJob?.cancel()
        lifecycleScope.launch {
            try {
                room?.localParticipant?.setMicrophoneEnabled(false)
                room?.disconnect()
            } finally {
                room = null
                connected = false
                talking = false
                requestingTalk = false
                speakerIdentity = null
                pendingTalkRequests.clear()
                status = "Not connected"
            }
        }
    }

    private suspend fun fetchToken(identity: String, roomName: String): Pair<String, String> =
        withContext(Dispatchers.IO) {
            var targetUrl = TOKEN_URL
            var redirects = 0
            while (redirects < 5) {
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
                        if (++redirects >= 5) throw IllegalStateException("Token server redirected too many times")
                        targetUrl = URL(URL(targetUrl), location).toString()
                    } else {
                        val response = if (code in 200..299) connection.inputStream.bufferedReader().use { it.readText() } else connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                        val json = runCatching { JSONObject(response) }.getOrElse { throw IllegalStateException("Token server returned HTTP $code: $response") }
                        if (code !in 200..299) throw IllegalStateException(json.optString("error", "Token request failed (HTTP $code)"))
                        val token = json.optString("participant_token").ifBlank { json.optString("token") }
                        if (token.isBlank()) throw IllegalStateException("Token server returned no access token")
                        return@withContext Pair(token, json.optString("server_url"))
                    }
                } finally {
                    connection.disconnect()
                }
            }
            throw IllegalStateException("Token server redirected too many times")
        }

    override fun onDestroy() {
        talkRequestJob?.cancel()
        roomEventsJob?.cancel()
        room?.disconnect()
        room = null
        super.onDestroy()
    }
}

@Composable
private fun WalkieApp(
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onTalkStart: () -> Unit,
    onTalkEnd: () -> Unit,
    isConnected: Boolean,
    isConnecting: Boolean,
    isTalking: Boolean,
    isRequestingTalk: Boolean,
    hasRemoteSpeaker: Boolean,
    status: String
) {
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(40.dp))
                Text("Walkie", style = MaterialTheme.typography.headlineLarge)
                Text(status)
                Spacer(Modifier.weight(1f))
                Button(
                    enabled = !isConnecting,
                    onClick = { if (isConnected) onDisconnect() else onConnect() }
                ) {
                    Text(if (isConnected) "Disconnect" else if (isConnecting) "Connecting…" else "Connect")
                }
                Spacer(Modifier.height(24.dp))
                val talkEnabled = isConnected && !hasRemoteSpeaker && !isRequestingTalk
                Box(
                    Modifier
                        .size(170.dp)
                        .pointerInput(isConnected, hasRemoteSpeaker, isRequestingTalk) {
                            detectTapGestures(
                                onPress = {
                                    if (!talkEnabled) return@detectTapGestures
                                    onTalkStart()
                                    try {
                                        tryAwaitRelease()
                                    } finally {
                                        onTalkEnd()
                                    }
                                }
                            )
                        }
                ) {
                    Surface(
                        Modifier.fillMaxSize(),
                        shape = MaterialTheme.shapes.extraLarge,
                        color = if (talkEnabled || isTalking) Color(0xFFE53935) else Color(0xFFEAA0A0),
                        tonalElevation = 6.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                when {
                                    isTalking -> "RELEASE"
                                    hasRemoteSpeaker -> "BUSY"
                                    isRequestingTalk -> "WAIT…"
                                    else -> "HOLD TO TALK"
                                },
                                color = Color.White
                            )
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    when {
                        !isConnected -> "Connect to start"
                        hasRemoteSpeaker -> "Wait for ${displayNameForUi(status)} to finish"
                        isRequestingTalk -> "Waiting for microphone…"
                        else -> "Hold to transmit • Release to listen"
                    }
                )
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

private fun displayNameForUi(status: String): String =
    if (status.contains("is talking")) status.substringBefore(" is talking") else "them"
