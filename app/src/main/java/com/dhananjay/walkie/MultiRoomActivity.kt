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

private const val MULTI_LIVEKIT_URL = "wss://walkie-c8ioe9eg.livekit.cloud"
private const val MULTI_TOKEN_URL = "https://walkie-tan.vercel.app/api/token"
private const val MULTI_DEFAULT_ROOM = "friends"
private const val MULTI_TALK_TOPIC = "walkie-talk-lock-v1"
private const val MULTI_ARBITRATION_MS = 700L
private const val MULTI_PREFS = "walkie_rooms"
private const val MULTI_ROOMS_KEY = "rooms"

class MultiRoomActivity : ComponentActivity() {
    private var room: Room? = null
    private var eventsJob: Job? = null
    private var talkJob: Job? = null
    private var connected by mutableStateOf(false)
    private var connecting by mutableStateOf(false)
    private var reconnecting by mutableStateOf(false)
    private var talking by mutableStateOf(false)
    private var requestingTalk by mutableStateOf(false)
    private var speakerIdentity by mutableStateOf<String?>(null)
    private var roomName by mutableStateOf("")
    private var status by mutableStateOf("Not connected")
    private var participantNames by mutableStateOf<List<String>>(emptyList())
    private var savedRooms by mutableStateOf(emptyList<String>())
    private var localIdentity = ""
    private val pendingRequests = mutableMapOf<String, String>()

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) status = "Microphone permission is required"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedRooms = loadRooms()
        requestMicrophonePermissionIfNeeded()
        setContent {
            MultiRoomUi(
                isConnected = connected,
                isConnecting = connecting,
                isReconnecting = reconnecting,
                isTalking = talking,
                isRequestingTalk = requestingTalk,
                hasRemoteSpeaker = speakerIdentity != null && speakerIdentity != localIdentity,
                roomName = roomName,
                participants = participantNames,
                savedRooms = savedRooms,
                status = status,
                onJoin = ::joinRoom,
                onCreate = ::createRoom,
                onDisconnect = { leaveRoom() },
                onTalkStart = { requestTalk() },
                onTalkEnd = { releaseTalk() }
            )
        }
    }

    private fun loadRooms(): List<String> {
        val stored = getSharedPreferences(MULTI_PREFS, MODE_PRIVATE)
            .getStringSet(MULTI_ROOMS_KEY, null)
            ?.filter { it.isNotBlank() }
            ?.toMutableSet() ?: mutableSetOf()
        stored.add(MULTI_DEFAULT_ROOM)
        return stored.sorted()
    }

    private fun saveRoom(name: String) {
        val normalized = name.trim()
        if (normalized.isBlank()) return
        val rooms = savedRooms.toMutableSet()
        rooms.add(normalized)
        savedRooms = rooms.sorted()
        getSharedPreferences(MULTI_PREFS, MODE_PRIVATE)
            .edit()
            .putStringSet(MULTI_ROOMS_KEY, rooms)
            .apply()
    }

    private fun validRoomName(name: String): Boolean =
        name.length in 1..32 && name.all { it.isLetterOrDigit() || it == ' ' || it == '_' || it == '-' }

    private fun createRoom(name: String) {
        val normalized = name.trim()
        if (!validRoomName(normalized)) {
            status = "Use 1–32 letters, numbers, spaces, _ or -"
            return
        }
        saveRoom(normalized)
        joinRoom(normalized)
    }

    private fun requestMicrophonePermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun joinRoom(name: String) {
        val normalized = name.trim()
        if (!validRoomName(normalized)) {
            status = "Enter a valid room name"
            return
        }
        saveRoom(normalized)
        if (connecting) return
        if (connected) {
            if (normalized == roomName) return
            leaveRoom { joinRoom(normalized) }
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            status = "Allow microphone permission, then join the room"
            return
        }

        connecting = true
        reconnecting = false
        status = "Requesting connection token…"
        lifecycleScope.launch {
            try {
                val identity = "android-${UUID.randomUUID()}"
                localIdentity = identity
                val (token, serverUrl) = fetchToken(identity, normalized)
                status = "Connecting to $normalized…"
                val newRoom = LiveKit.create(applicationContext)
                newRoom.connect(serverUrl.ifBlank { MULTI_LIVEKIT_URL }, token)
                room = newRoom
                roomName = normalized
                newRoom.localParticipant.setMicrophoneEnabled(false)
                updateParticipants(newRoom)
                eventsJob?.cancel()
                eventsJob = lifecycleScope.launch {
                    newRoom.events.collect { event -> handleEvent(newRoom, event) }
                }
                connected = true
                status = "Connected • $normalized"
            } catch (e: Exception) {
                e.printStackTrace()
                try { room?.disconnect() } catch (_: Exception) { }
                room = null
                connected = false
                reconnecting = false
                roomName = ""
                participantNames = emptyList()
                resetTalkState()
                status = "Connection failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                connecting = false
            }
        }
    }

    private fun updateParticipants(currentRoom: Room) {
        participantNames = currentRoom.remoteParticipants.values
            .map { it.identity.toString() }
            .sorted()
    }

    private suspend fun handleEvent(currentRoom: Room, event: RoomEvent) {
        when (event) {
            is RoomEvent.DataReceived -> handleTalkData(currentRoom, event)
            is RoomEvent.ParticipantConnected -> updateParticipants(currentRoom)
            is RoomEvent.ParticipantDisconnected -> {
                val departed = event.participant.identity.toString()
                pendingRequests.entries.removeIf { it.value == departed }
                if (speakerIdentity == departed) {
                    speakerIdentity = null
                    if (!talking) status = "Listening • $roomName"
                }
                updateParticipants(currentRoom)
            }
            is RoomEvent.Reconnecting -> {
                reconnecting = true
                status = "Reconnecting to $roomName…"
            }
            is RoomEvent.Reconnected -> {
                reconnecting = false
                updateParticipants(currentRoom)
                status = "Connected • $roomName"
            }
            is RoomEvent.Disconnected -> {
                connected = false
                reconnecting = false
                participantNames = emptyList()
                resetTalkState()
                status = "Disconnected"
            }
            is RoomEvent.FailedToConnect -> {
                connected = false
                reconnecting = false
                participantNames = emptyList()
                resetTalkState()
                status = "Connection failed: ${event.error.message ?: "Unable to connect"}"
            }
            else -> Unit
        }
    }

    private suspend fun handleTalkData(currentRoom: Room, event: RoomEvent.DataReceived) {
        val sender = event.participant?.identity?.toString() ?: return
        if (sender == localIdentity || event.topic != MULTI_TALK_TOPIC) return
        val message = runCatching { JSONObject(String(event.data, Charsets.UTF_8)) }.getOrNull() ?: return
        when (message.optString("type")) {
            "talk_request" -> {
                val requestId = message.optString("requestId")
                if (requestId.isBlank()) return
                if (speakerIdentity != null) {
                    publishTalk(currentRoom, JSONObject().apply {
                        put("type", "talk_busy")
                        put("speaker", speakerIdentity)
                    })
                } else {
                    pendingRequests[requestId] = sender
                }
            }
            "talk_start" -> {
                val speaker = message.optString("speaker").ifBlank { sender }
                pendingRequests.clear()
                talkJob?.cancel()
                requestingTalk = false
                speakerIdentity = speaker
                if (speaker != localIdentity && talking) {
                    currentRoom.localParticipant.setMicrophoneEnabled(false)
                    talking = false
                }
                status = if (speaker == localIdentity) "Transmitting microphone audio" else "Friend is talking"
            }
            "talk_end" -> {
                val speaker = message.optString("speaker").ifBlank { sender }
                if (speakerIdentity == speaker) {
                    speakerIdentity = null
                    if (!talking) status = "Listening • $roomName"
                }
            }
            "talk_busy" -> {
                val speaker = message.optString("speaker")
                if (requestingTalk && speaker.isNotBlank()) {
                    requestingTalk = false
                    talkJob?.cancel()
                    speakerIdentity = speaker
                    status = "Friend is talking"
                }
            }
        }
    }

    private fun requestTalk() {
        val currentRoom = room ?: return
        if (!connected || reconnecting || talking || requestingTalk) return
        if (speakerIdentity != null) {
            status = "Friend is talking"
            return
        }
        requestingTalk = true
        status = "Requesting microphone…"
        val requestId = "${System.currentTimeMillis()}-$localIdentity"
        pendingRequests[requestId] = localIdentity
        talkJob?.cancel()
        talkJob = lifecycleScope.launch {
            publishTalk(currentRoom, JSONObject().apply {
                put("type", "talk_request")
                put("requestId", requestId)
                put("identity", localIdentity)
            })
            delay(MULTI_ARBITRATION_MS)
            if (!requestingTalk || !connected || reconnecting || speakerIdentity != null) return@launch
            val winner = pendingRequests.entries.minByOrNull { it.key }?.value
            if (winner != localIdentity) {
                requestingTalk = false
                status = "Another person got the microphone"
                return@launch
            }
            pendingRequests.clear()
            speakerIdentity = localIdentity
            requestingTalk = false
            val enabled = currentRoom.localParticipant.setMicrophoneEnabled(true)
            if (!enabled) {
                speakerIdentity = null
                status = "Microphone could not be enabled"
                return@launch
            }
            talking = true
            publishTalk(currentRoom, JSONObject().apply {
                put("type", "talk_start")
                put("speaker", localIdentity)
            })
            status = "Transmitting microphone audio"
        }
    }

    private fun releaseTalk() {
        val currentRoom = room ?: return
        if (!talking && !requestingTalk) return
        talkJob?.cancel()
        requestingTalk = false
        if (talking) {
            talking = false
            speakerIdentity = null
            lifecycleScope.launch {
                currentRoom.localParticipant.setMicrophoneEnabled(false)
                publishTalk(currentRoom, JSONObject().apply {
                    put("type", "talk_end")
                    put("speaker", localIdentity)
                })
                status = "Listening • $roomName"
            }
        } else {
            pendingRequests.entries.removeIf { it.value == localIdentity }
            status = "Listening • $roomName"
        }
    }

    private suspend fun publishTalk(currentRoom: Room, message: JSONObject) {
        currentRoom.localParticipant.publishData(
            message.toString().toByteArray(Charsets.UTF_8),
            topic = MULTI_TALK_TOPIC
        )
    }

    private fun resetTalkState() {
        talkJob?.cancel()
        talking = false
        requestingTalk = false
        speakerIdentity = null
        pendingRequests.clear()
    }

    private fun leaveRoom(onFinished: (() -> Unit)? = null) {
        talkJob?.cancel()
        eventsJob?.cancel()
        lifecycleScope.launch {
            try {
                room?.localParticipant?.setMicrophoneEnabled(false)
                room?.disconnect()
            } finally {
                room = null
                connected = false
                connecting = false
                reconnecting = false
                roomName = ""
                participantNames = emptyList()
                resetTalkState()
                status = "Not connected"
                onFinished?.invoke()
            }
        }
    }

    private suspend fun fetchToken(identity: String, selectedRoom: String): Pair<String, String> = withContext(Dispatchers.IO) {
        var targetUrl = MULTI_TOKEN_URL
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
                val body = JSONObject().apply {
                    put("identity", identity)
                    put("room", selectedRoom)
                }.toString()
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                if (code == 301 || code == 302 || code == 307 || code == 308) {
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
        talkJob?.cancel()
        eventsJob?.cancel()
        room?.disconnect()
        super.onDestroy()
    }
}

@Composable
private fun MultiRoomUi(
    isConnected: Boolean,
    isConnecting: Boolean,
    isReconnecting: Boolean,
    isTalking: Boolean,
    isRequestingTalk: Boolean,
    hasRemoteSpeaker: Boolean,
    roomName: String,
    participants: List<String>,
    savedRooms: List<String>,
    status: String,
    onJoin: (String) -> Unit,
    onCreate: (String) -> Unit,
    onDisconnect: () -> Unit,
    onTalkStart: () -> Unit,
    onTalkEnd: () -> Unit
) {
    var roomInput by rememberSaveable { mutableStateOf("") }
    var createInput by rememberSaveable { mutableStateOf("") }
    var showRooms by rememberSaveable { mutableStateOf(false) }
    var showCreate by rememberSaveable { mutableStateOf(false) }
    val bg = Color(0xFFFFF7FF)
    val text = Color(0xFF171217)
    val secondary = Color(0xFF6F6870)
    val red = Color(0xFFE83232)
    val disabledRed = Color(0xFFE99A9A)
    val latestStart by rememberUpdatedState(onTalkStart)
    val latestEnd by rememberUpdatedState(onTalkEnd)

    Surface(Modifier.fillMaxSize(), color = bg) {
        if (!isConnected) {
            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(70.dp))
                Text("Walkie", style = MaterialTheme.typography.displaySmall, color = text)
                Spacer(Modifier.height(10.dp))
                Text(status, style = MaterialTheme.typography.bodyLarge, color = secondary)
                Spacer(Modifier.height(38.dp))
                OutlinedTextField(roomInput, { roomInput = it }, singleLine = true, label = { Text("Room name or code") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Button(onClick = { onJoin(roomInput) }, enabled = !isConnecting && roomInput.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Text(if (isConnecting) "CONNECTING…" else "JOIN ROOM")
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { showRooms = true }, modifier = Modifier.fillMaxWidth()) { Text("MY ROOMS") }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { showCreate = true }) { Text("＋ CREATE NEW ROOM") }
                Spacer(Modifier.weight(1f))
                Text("Room names are shared with friends as join codes.", color = secondary, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(60.dp))
            }
        } else {
            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(55.dp))
                Text("Walkie", style = MaterialTheme.typography.displaySmall, color = text)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(12.dp)) { drawCircle(if (isReconnecting) Color(0xFFFFB020) else Color(0xFF42D56B)) }
                    Spacer(Modifier.width(7.dp))
                    Text(if (isReconnecting) "Reconnecting" else "Connected", style = MaterialTheme.typography.titleLarge, color = text)
                }
                Spacer(Modifier.height(5.dp))
                Text("Room: $roomName", style = MaterialTheme.typography.bodyLarge, color = secondary)
                Spacer(Modifier.height(4.dp))
                Text("${participants.size + 1} people in room", style = MaterialTheme.typography.bodyMedium, color = secondary)
                if (participants.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(participants.joinToString(" • ") { if (it.startsWith("android-")) "Friend" else it }, color = secondary, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }

                Spacer(Modifier.weight(1f))
                when {
                    hasRemoteSpeaker -> {
                        MicIndicator(Modifier.size(210.dp), Color(0xFFB92D32))
                        Spacer(Modifier.height(20.dp))
                        Text("Friend is talking", style = MaterialTheme.typography.titleLarge, color = text)
                        Text("Please wait to speak", color = secondary)
                    }
                    isTalking -> {
                        MicIndicator(Modifier.size(210.dp), red)
                        Spacer(Modifier.height(20.dp))
                        Text("You are talking", style = MaterialTheme.typography.titleLarge, color = text)
                        Text("Release to listen", color = secondary)
                    }
                    else -> Spacer(Modifier.height(210.dp))
                }
                Spacer(Modifier.height(24.dp))
                val enabled = !hasRemoteSpeaker && !isRequestingTalk && !isReconnecting
                Box(
                    Modifier.size(168.dp).pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            if (!enabled && !isTalking) return@detectTapGestures
                            if (!isTalking) latestStart()
                            try { tryAwaitRelease() } finally { latestEnd() }
                        })
                    },
                    contentAlignment = Alignment.Center
                ) {
                    Surface(Modifier.fillMaxSize(), shape = MaterialTheme.shapes.extraLarge, color = if (enabled || isTalking) red else disabledRed, tonalElevation = 4.dp) {
                        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            MicGlyph(Modifier.size(45.dp), Color.White)
                            Spacer(Modifier.height(10.dp))
                            Text(if (isTalking) "RELEASE" else if (isRequestingTalk) "WAIT…" else "HOLD TO TALK", color = Color.White, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text(
                    when {
                        hasRemoteSpeaker -> "Busy — wait for them to finish"
                        isRequestingTalk -> "Waiting for microphone…"
                        isTalking -> "Release to listen"
                        isReconnecting -> "Connection interrupted"
                        else -> "Press and hold to talk"
                    },
                    color = secondary,
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = { showRooms = true }) { Text("MY ROOMS") }
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onDisconnect) { Text("DISCONNECT") }
                Spacer(Modifier.height(40.dp))
            }
        }
    }

    if (showRooms) {
        AlertDialog(
            onDismissRequest = { showRooms = false },
            title = { Text("My Rooms") },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(savedRooms) { name ->
                        TextButton(onClick = { showRooms = false; onJoin(name) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                                Text(name, color = text)
                                Text(if (name == roomName && isConnected) "Current room" else "Tap to join", color = secondary, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showRooms = false; showCreate = true }) { Text("CREATE ROOM") } },
            dismissButton = { TextButton(onClick = { showRooms = false }) { Text("CLOSE") } }
        )
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("Create New Room") },
            text = { OutlinedTextField(createInput, { createInput = it }, singleLine = true, label = { Text("Room name") }, supportingText = { Text("1–32 letters, numbers, spaces, _ or -") }) },
            confirmButton = {
                TextButton(onClick = {
                    val name = createInput.trim()
                    if (name.isNotBlank()) {
                        showCreate = false
                        createInput = ""
                        onCreate(name)
                    }
                }) { Text("CREATE & JOIN") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("CANCEL") } }
        )
    }
}

@Composable
private fun MicIndicator(modifier: Modifier, color: Color) {
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
private fun MicGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        drawMic(androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f), size.minDimension * 0.32f, color)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMic(center: androidx.compose.ui.geometry.Offset, scale: Float, color: Color) {
    val micWidth = scale * 0.72f
    val micHeight = scale * 1.35f
    val left = center.x - micWidth / 2f
    val top = center.y - micHeight * 0.55f
    drawRoundRect(color, androidx.compose.ui.geometry.Offset(left, top), androidx.compose.ui.geometry.Size(micWidth, micHeight), androidx.compose.ui.geometry.CornerRadius(micWidth / 2f))
    drawArc(color, 0f, 180f, false, androidx.compose.ui.geometry.Offset(center.x - scale * 0.55f, center.y - scale * 0.25f), androidx.compose.ui.geometry.Size(scale * 1.1f, scale * 1.05f), style = androidx.compose.ui.graphics.drawscope.Stroke(scale * 0.13f))
    drawLine(color, androidx.compose.ui.geometry.Offset(center.x, center.y + scale * 0.30f), androidx.compose.ui.geometry.Offset(center.x, center.y + scale * 0.72f), scale * 0.13f)
    drawLine(color, androidx.compose.ui.geometry.Offset(center.x - scale * 0.34f, center.y + scale * 0.75f), androidx.compose.ui.geometry.Offset(center.x + scale * 0.34f, center.y + scale * 0.75f), scale * 0.13f)
}
