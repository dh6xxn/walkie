from pathlib import Path

main_path = Path("app/src/main/java/com/dhananjay/walkie/MainActivity.kt")
text = main_path.read_text()

# Keep the push-to-talk gesture stable while Compose state changes during arbitration.
old_anchor = '''                } else {\n                    val talkEnabled = !hasRemoteSpeaker && !isRequestingTalk\n                    Box(\n'''
new_anchor = '''                } else {\n                    val talkEnabled = !hasRemoteSpeaker && !isRequestingTalk\n                    val currentOnTalkStart = rememberUpdatedState(onTalkStart)\n                    val currentOnTalkEnd = rememberUpdatedState(onTalkEnd)\n                    val currentIsTalking = rememberUpdatedState(isTalking)\n                    val currentHasRemoteSpeaker = rememberUpdatedState(hasRemoteSpeaker)\n                    Box(\n'''
if old_anchor not in text:
    raise SystemExit("Expected talk UI anchor not found")
text = text.replace(old_anchor, new_anchor, 1)

old_gesture = '''                            .pointerInput(hasRemoteSpeaker, isRequestingTalk, isTalking) {\n                                detectTapGestures(\n                                    onPress = {\n                                        if (!talkEnabled && !isTalking) return@detectTapGestures\n                                        if (!isTalking) onTalkStart()\n                                        try {\n                                            tryAwaitRelease()\n                                        } finally {\n                                            onTalkEnd()\n                                        }\n                                    }\n                                )\n                            },\n'''
new_gesture = '''                            .pointerInput(Unit) {\n                                detectTapGestures(\n                                    onPress = {\n                                        // Do not restart this gesture when requestingTalk/talking changes.\n                                        // The request must survive until arbitration completes.\n                                        if (currentHasRemoteSpeaker.value && !currentIsTalking.value) {\n                                            return@detectTapGestures\n                                        }\n                                        if (!currentIsTalking.value) currentOnTalkStart.value()\n                                        try {\n                                            tryAwaitRelease()\n                                        } finally {\n                                            currentOnTalkEnd.value()\n                                        }\n                                    }\n                                )\n                            },\n'''
if old_gesture not in text:
    raise SystemExit("Expected pointerInput block not found")
text = text.replace(old_gesture, new_gesture, 1)

# Add a visible disconnect control whenever the room is connected.
disconnect_anchor = '''                    Text(\n                        text = "Room: friends",\n                        style = MaterialTheme.typography.bodyLarge,\n                        color = secondaryText\n                    )\n                } else {\n'''
disconnect_replacement = '''                    Text(\n                        text = "Room: friends",\n                        style = MaterialTheme.typography.bodyLarge,\n                        color = secondaryText\n                    )\n                    Spacer(Modifier.height(12.dp))\n                    OutlinedButton(onClick = onDisconnect) {\n                        Text("Disconnect")\n                    }\n                } else {\n'''
if 'Text("Disconnect")' not in text:
    if disconnect_anchor not in text:
        raise SystemExit("Expected connected-room UI anchor not found")
    text = text.replace(disconnect_anchor, disconnect_replacement, 1)

main_path.write_text(text)

# The room screens use rememberSaveable and Compose TextField lambdas. Keep the
# generated/build-time versions compatible with the current Compose compiler.
for name in ("RoomsActivity.kt", "MultiRoomActivity.kt"):
    path = Path("app/src/main/java/com/dhananjay/walkie") / name
    text = path.read_text()

    if "import androidx.compose.runtime.saveable.rememberSaveable" not in text:
        anchor = "import androidx.compose.runtime.*\n"
        if anchor not in text:
            raise SystemExit(f"Compose runtime import anchor not found in {name}")
        text = text.replace(anchor, anchor + "import androidx.compose.runtime.saveable.rememberSaveable\n", 1)

    text = text.replace(
        'OutlinedTextField(roomInput, { roomInput = it }, singleLine = true, label = { Text("Room name or code") }, modifier = Modifier.fillMaxWidth())',
        'OutlinedTextField(\n                    value = roomInput,\n                    onValueChange = { newInput: String -> roomInput = newInput },\n                    singleLine = true,\n                    label = { Text("Room name or code") },\n                    modifier = Modifier.fillMaxWidth()\n                )'
    )
    text = text.replace(
        'OutlinedTextField(createInput, { createInput = it }, singleLine = true, label = { Text("Room name") }, supportingText = { Text("1–32 letters, numbers, spaces, _ or -") })',
        'OutlinedTextField(\n                    value = createInput,\n                    onValueChange = { newInput: String -> createInput = newInput },\n                    singleLine = true,\n                    label = { Text("Room name") },\n                    supportingText = { Text("1–32 letters, numbers, spaces, _ or -") }\n                )'
    )
    path.write_text(text)

print("Push-to-talk, disconnect, and room Compose compilation fixes applied")