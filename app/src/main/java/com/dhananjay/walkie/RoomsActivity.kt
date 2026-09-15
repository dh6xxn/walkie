package com.dhananjay.walkie

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Stroke
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
private const val PREFS_NAME = "walkie_rooms"
private const val PREF_ROOMS = "rooms"

class RoomsActivity : ComponentActivity() {
    private var room: Room? = null
    private var roomEventsJob: Job? = null
    private var talkRequestJob: Job? = null
    private var connected by mutableStateOf(false)
    private var connecting by mutableStateOf(false)
    private var reconnecting by mutableStateOf(false)
    private var talking by mutableStateOf(false)
    private var requestingTalk by mutableStateOf(false)
    private var speakerIdentity by mutableStateOf<String?>(null)
    private var status by mutableStateOf("Not connected")
    private var currentRoomName by mutableStateOf("")
    private var participants by mutableStateOf<List<String>>(emptyList())
    private var savedRooms by mutableStateOf(loadSavedRooms())
    private var localIdentity = ""
    private val pendingTalkRequests = mutableMapOf<String, String>()

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) status = "Microphone permission is required"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestMicrophonePermissionIfNeeded()
        setContent {
            WalkieRoomsApp(
                onConnect = { connectToRoom(it) },
                onDisconnect = { disconnectFromRoom() },
                onTalkStart = { requestTalk() },
                onTalkEnd = { releaseTalk() },
                onCreateRoom = { createRoom(it) },
                savedRooms = savedRooms,
                currentRoomName = currentRoomName,
                participants = participants,
                isConnected = connected,
                isConnecting = connecting,
                isReconnecting = reconnecting,
                isTalking = talking,
                isRequestingTalk = requestingTalk,
                hasRemoteSpeaker = speakerIdentity != null && speakerIdentity != localIdentity,
                status = status
            )
        }
    }

    private fun loadSavedRooms(): List<String> {
        val rooms = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getStringSet(PREF_ROOMS, setOf(DEFAULT_ROOM))
            ?.filter { it.isNotBlank() }
            ?.toMutableSet() ?: mutableSetOf(DEFAULT_ROOM)
        rooms.add(DEFAULT_ROOM)
        return rooms.sorted()
    }

    private fun saveRoom(roomName: String) {
        val normalized = roomName.trim()
        if (normalized.isBlank()) return
        val rooms = savedRooms.toMutableSet()
        rooms.add(normalized)
        savedRooms = rooms.sorted()
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putStringSet(PREF_ROOMS, rooms)
            .apply()
    }

    private fun createRoom(roomName: String) {
        val normalized = roomName.trim()
        if (!isValidRoomName(normalized)) {
            status = "Use 1–32 letters, numbers, spaces, _ or -"
            return
        }
        saveRoom(normalized)
        connectToRoom(normalized)
    }

    private fun isValidRoomName(roomName: String): Boolean =
        roomName.length in 1..32 && roomName.all { it.isLetterOrDigit() || it == ' ' || it == '_' || it == '-' }

    private fun requestMicrophonePermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun connectToRoom(roomName: String) {
        val normalized = roomName.trim()
        if (!isValidRoomName(normalized)) {
            status = "Enter a valid room name"
            return
        }
        if (connected || connecting) {
            if (normalized == currentRoomName) return
            disconnectFromRoom { connectToRoom(normalized) }
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            status = "Allow microphone permission, then join the room"
            return
        }

        saveRoom(normalized)
        connecting = true
        reconnecting = false
        status = "Requesting connection token…"
        lifecycleScope.launch {
            try {
                val identity = "android-${UUID.randomUUID()}"
                localIdentity = identity
                val tokenResult = fetchToken(identity, normalized)
                status = "Connecting to $normalized…"
                val createdRoom = LiveKit.create(applicationContext)
                createdRoom.connect(tokenResult.second.ifBlank { LIVEKIT_URL }, tokenResult.first)
                room = createdRoom
                currentRoomName = normalized
                createdRoom.localParticipant.setMicrophoneEnabled(false)
                updateParticipants(createdRoom)
                roomEventsJob?.cancel()
                roomEventsJob = lifecycleScope.launch {
                    createdRoom.events.collect { event -> handleRoomEvent(createdRoom, event) }
                }
                connected = true
                status = "Connected • $normalized"
            } catch (e: Exception) {
                e.printStackTrace()
                try { room?.disconnect() } catch (_: Exception) { }
                room = null
                connected = false
                reconnecting = false
                currentRoomName = ""
                participants = emptyList()
                resetTalkState()
                status = "Connection failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                connecting = false
            }
        }
    }

    private fun updateParticipants(currentRoom: Room) {
        participants = currentRoom.remoteParticipants.values
            .map { it.identity.toString() }
            .sorted()
    }

    private suspend fun handleRoomEvent(currentRoom: Room, event: RoomEvent) {
        when (event) {
            is RoomEvent.DataReceived -> {
                val sender = event.participant?.identity?.toString() ?: return
                if (sender == localIdentity || event.topic != TALK_TOPIC) return
                val message = runCatching { JSONObject(String(event.data, Charsets.UTF_8)) }.getOrNull() ?: return
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
                            if (!talking) status = "Listening • $currentRoomName"
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
            is RoomEvent.ParticipantConnected -> {
                updateParticipants(currentRoom)
            }
            is RoomEvent.ParticipantDisconnected -> {
                val departed = event.participant.identity.toString()
                pendingTalkRequests.entries.removeIf { it.value == departed }
                if (speakerIdentity == departed) {
                    speakerIdentity = null
                    if (!talking) status = "Listening • $currentRoomName"
                }
                updateParticipants(currentRoom)
            }
            is RoomEvent.Reconnecting -> {
                reconnecting = true
                status = "Reconnecting to $currentRoomName…"
            }
            is RoomEvent.Reconnected -> {
                reconnecting = false
                updateParticipants(currentRoom)
                status = "Connected • $currentRoomName"
            }
            is RoomEvent.Disconnected -> {
                connected = false
                reconnecting = false
                participants = emptyList()
                resetTalkState()
                status = "Disconnected"
            }
            is RoomEvent.FailedToConnect -> {
                connected = false
                reconnecting = false
                participants = emptyList()
                resetTalkState()
                status = "Connection failed: ${event.error.message ?: "Unable to connect"}"
            }
            else -> Unit
        }
    }

    private fun requestTalk() {
        val currentRoom = room ?: return
        if (!connected || reconnecting || talking || requestingTalk) return
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
            if (!requestingTalk || !connected || reconnecting || speakerIdentity != null) return@launch
            val winner = pendingTalkRequests.entries.minByOrNull { it.key }?.value
            if (winner == localIdentity) {
                pendingTalkRequests.clear()
                speakerIdentity = localIdentity
                requestingTalk = false
                val enabled = currentRoom.localParticipant.setMicrophoneEnabled(true)
                if (enabled) {
                    talking = true
                    publishTalkMessage(currentRoom, JSONObject().apply {
                        put("type", "talk_start")
                        put("speaker", localIdentity)
                    })
                    status = "Transmitting microphone audio"
                } else {
                    speakerIdentity = null
                    status = "Microphone could not be enabled"
                }
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
                status = "Listening • $currentRoomName"
            }
        } else {
            pendingTalkRequests.entries.removeIf { it.value == localIdentity }
            status = "Listening • $currentRoomName"
        }
    }

    private suspend fun publishTalkMessage(currentRoom: Room, message: JSONObject) {
        currentRoom.localParticipant.publishData(
            message.toString().toByteArray(Charsets.UTF_8),
            topic = TALK_TOPIC
        )
    }

    private fun displaySpeaker(identity: String): String =
        if (identity.startsWith("android-")) "Friend" else identity

    private fun resetTalkState() {
        talkRequestJob?.cancel()
        talking = false
        requestingTalk = false
        speakerIdentity = null
        pendingTalkRequests.clear()
    }

    private fun disconnectFromRoom(onFinished: (() -> Unit)? = null) {
        talkRequestJob?.cancel()
        roomEventsJob?.cancel()
        lifecycleScope.launch {
            try {
                room?.localParticipant?.setMicrophoneEnabled(false)
                room?.disconnect()
            } finally {
                room = null
                connected = false
                connecting = false
                reconnecting = false
                talking = false
                requestingTalk = false
                speakerIdentity = null
                pendingTalkRequests.clear()
                participants = emptyList()
                currentRoomName = ""
                status = "Not connected"
                onFinished?.invoke()
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
private fun WalkieRoomsApp(
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onTalkStart: () -> Unit,
    onTalkEnd: () -> Unit,
    onCreateRoom: (String) -> Unit,
    savedRooms: List<String>,
    currentRoomName: String,
    participants: List<String>,
    isConnected: Boolean,
    isConnecting: Boolean,
    isReconnecting: Boolean,
    isTalking: Boolean,
    isRequestingTalk: Boolean,
    hasRemoteSpeaker: Boolean,
    status: String
) {
    var roomInput by rememberSaveable { mutableStateOf("") }
    var showRooms by rememberSaveable { mutableStateOf(false) }
    var showCreate by rememberSaveable { mutableStateOf(false) }
    var createInput by rememberSaveable { mutableStateOf("") }
    val background = Color(0xFFFFF7FF)
    val primaryText = Color(0xFF171217)
    val secondaryText = Color(0xFF6F6870)
    val red = Color(0xFFE83232)
    val disabledRed = Color(0xFFE99A9A)
    val latestTalkStart by rememberUpdatedState(onTalkStart)
    val latestTalkEnd by rememberUpdatedState(onTalkEnd)

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = background) {
            if (!isConnected) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(70.dp))
                    Text("Walkie", style = MaterialTheme.typography.displaySmall, color = primaryText)
                    Spacer(Modifier.height(10.dp))
                    Text(status, style = MaterialTheme.typography.bodyLarge, color = secondaryText)
                    Spacer(Modifier.height(38.dp))
                    OutlinedTextField(
                        value = roomInput,
                        onValueChange = { roomInput = it },
                        singleLine = true,
                        label = { Text("Room name or code") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { onConnect(roomInput) },
                        enabled = !isConnecting && roomInput.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (isConnecting) "Connecting…" else "JOIN ROOM") }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { showRooms = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("MY ROOMS") }
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { showCreate = true }) { Text("＋ Create New Room") }
                    Spacer(Modifier.weight(1f))
                    Text("Enter a room name and share it with your friends.", color = secondaryText, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(60.dp))
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(58.dp))
                    Text("Walkie", style = MaterialTheme.typography.displaySmall, color = primaryText)
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Canvas(Modifier.size(12.dp)) { drawCircle(if (isReconnecting) Color(0xFFFFB020) else Color(0xFF42D56B)) }
                        Spacer(Modifier.width(7.dp))
                        Text(if (isReconnecting) "Reconnecting" else "Connected", style = MaterialTheme.typography.titleLarge, color = primaryText)
                    }
                    Spacer(Modifier.height(5.dp))
                    Text("Room: $currentRoomName", style = MaterialTheme.typography.bodyLarge, color = secondaryText)
                    Spacer(Modifier.height(8.dp))
                    Text("${participants.size + 1} people in room", style = MaterialTheme.typography.bodyMedium, color = secondaryText)

                    Spacer(Modifier.weight(1f))
                    when {
                        hasRemoteSpeaker -> {
                            MicIndicator(Modifier.size(210.dp), Color(0xFFB92D32))
                            Spacer(Modifier.height(20.dp))
                            Text("Friend is talking", style = MaterialTheme.typography.titleLarge, color = primaryText)
                            Text("Please wait to speak", color = secondaryText)
                        }
                        isTalking -> {
                            MicIndicator(Modifier.size(210.dp), red)
                            Spacer(Modifier.height(20.dp))
                            Text("You are talking", style = MaterialTheme.typography.titleLarge, color = primaryText)
                            Text("Release to listen", color = secondaryText)
                        }
                        else -> Spacer(Modifier.height(210.dp))
                    }
                    Spacer(Modifier.height(24.dp))

                    val talkEnabled = !hasRemoteSpeaker && !isRequestingTalk && !isReconnecting
                    Box(
                        modifier = Modifier.size(168.dp).pointerInput(Unit) {
                            detectTapGestures(onPress = {
                                if (!talkEnabled && !isTalking) return@detectTapGestures
                                if (!isTalking) latestTalkStart()
                                try { tryAwaitRelease() } finally { latestTalkEnd() }
                            })
                        },
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            shape = MaterialTheme.shapes.extraLarge,
                            color = if (talkEnabled || isTalking) red else disabledRed,
                            tonalElevation = 4.dp
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    MicGlyph(Modifier.size(45.dp), Color.White)
                                    Spacer(Modifier.height(10.dp))
                                    Text(
                                        when {
                                            isTalking -> "RELEASE"
                                            isRequestingTalk -> "WAIT…"
                                            else -> "HOLD TO TALK"
                                        },
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Text(
                        when {
                            hasRemoteSpeaker -> "Busy — wait for them to finish"
                            isRequestingTalk -> "Waiting for microphone…"
                            isTalking -> "Release to listen"
                            isReconnecting -> "Connection interrupted"
                            else -> "Press and hold to talk"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = secondaryText
                    )
                    Spacer(Modifier.height(18.dp))
                    OutlinedButton(onClick = { showRooms = true }) { Text("MY ROOMS") }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onDisconnect) { Text("DISCONNECT") }
                    Spacer(Modifier.height(45.dp))
                }
            }
        }
    }

    if (showRooms) {
        AlertDialog(
            onDismissRequest = { showRooms = false },
            title = { Text("My Rooms") },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(savedRooms) { roomName ->
                        TextButton(
                            onClick = {
                                showRooms = false
                                if (!isConnected) onConnect(roomName) else if (roomName != currentRoomName) onDisconnect()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                                Text(roomName, color = primaryText)
                                Text(if (roomName == currentRoomName && isConnected) "Current room" else "Tap to join", color = secondaryText, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showCreate = true; showRooms = false }) { Text("CREATE ROOM") } },
            dismissButton = { TextButton(onClick = { showRooms = false }) { Text("CLOSE") } }
        )
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("Create New Room") },
            text = {
                OutlinedTextField(
                    value = createInput,
                    onValueChange = { newInput: String -> createInput = newInput },
                    singleLine = true,
                    label = { Text("Room name") },
                    supportingText = { Text("1–32 letters, numbers, spaces, _ or -") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = createInput.trim()
                        if (name.isNotBlank()) {
                            showCreate = false
                            createInput = ""
                            onCreateRoom(name)
                        }
                    }
                ) { Text("CREATE & JOIN") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("CANCEL") } }
        )
    }
}

@Composable
private fun MicIndicator(modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier) {
        val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension * 0.43f
        drawCircle(color.copy(alpha = 0.045f), radius, center)
        drawCircle(color.copy(alpha = 0.07f), radius * 0.72f, center)
        drawCircle(color.copy(alpha = 0.10f), radius * 0.50f, center)
        drawMic(center, radius * 0.27f, color)
    }
}

@Composable
private fun MicGlyph(modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier) {
        drawMic(androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f), size.minDimension * 0.32f, color)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMic(
    center: androidx.compose.ui.geometry.Offset,
    scale: Float,
    color: Color
) {
    val micWidth = scale * 0.72f
    val micHeight = scale * 1.35f
    val left = center.x - micWidth / 2f
    val top = center.y - micHeight * 0.55f
    drawRoundRect(color = color, topLeft = androidx.compose.ui.geometry.Offset(left, top), size = androidx.compose.ui.geometry.Size(micWidth, micHeight), cornerRadius = androidx.compose.ui.geometry.CornerRadius(scale * 0.18f, scale * 0.18f))
    val arcLeft = center.x - scale * 0.55f
    val arcTop = center.y - scale * 0.25f
    val arcSize = androidx.compose.ui.geometry.Size(scale * 1.1f, scale * 1.05f)
    drawArc(color = color, startAngle = 0f, sweepAngle = 180f, useCenter = false, topLeft = androidx.compose.ui.geometry.Offset(arcLeft, arcTop), size = arcSize, style = Stroke(width = scale * 0.08f))
    drawLine(color = color, start = androidx.compose.ui.geometry.Offset(center.x, center.y + scale * 0.30f), end = androidx.compose.ui.geometry.Offset(center.x, center.y + scale * 0.72f), strokeWidth = scale * 0.08f)
    drawLine(color = color, start = androidx.compose.ui.geometry.Offset(center.x - scale * 0.34f, center.y + scale * 0.75f), end = androidx.compose.ui.geometry.Offset(center.x + scale * 0.34f, center.y + scale * 0.75f), strokeWidth = scale * 0.08f)
}
