from pathlib import Path

path = Path("app/src/main/java/com/dhananjay/walkie/MainActivity.kt")
text = path.read_text()

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

path.write_text(text)
print("Push-to-talk gesture fix applied")
