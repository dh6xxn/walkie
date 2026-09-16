# Walkie

Android push-to-talk voice app for friends over Wi-Fi or the internet.

## Current version
**V4 — Walkie Rooms**

The current Android build is a functional multi-room PTT MVP using LiveKit Cloud.

## Current features
- Push-to-talk voice communication with microphone permission handling
- Single-speaker PTT coordination so participants wait while another person is speaking
- LiveKit Cloud realtime audio rooms
- Join a room by name/code
- Create a room and automatically join it
- My Rooms list stored locally on the device
- Switch between multiple rooms
- Multiple participants can join the same room
- Participant count and basic participant presence
- Connected, reconnecting, disconnected, and connection-error states
- Red, compact Hold-to-Talk interface
- Cloud Android APK builds through GitHub Actions
- Firebase configuration restored securely through GitHub Actions secrets

## Current UI flow
1. Enter a room name or code and select **JOIN ROOM**.
2. Open **MY ROOMS** to select a saved room.
3. Select **CREATE NEW ROOM** to create and join another room.
4. Inside a room, participants are shown with the current room and participant count.
5. Hold **HOLD TO TALK** to request the microphone. Release to stop transmitting.

## Planned next features
These are planned and are not part of the current V4 build:
- Usernames and user accounts
- Invite-only signup and administrator approval
- User profiles
- Friend requests and friends list
- Online/offline presence for friends
- Public and private rooms
- Room invitations and room membership management
- Admin controls for users and rooms

## Stack
- Android: Kotlin + Jetpack Compose
- Auth/data: Firebase
- Voice: WebRTC via LiveKit Cloud
- Backend/token API: Vercel
- CI: GitHub Actions

This repository is being built as a cloud-first MVP; no local server is required for the development workflow.

## Build
The project can be built in GitHub Actions. Firebase configuration is injected at build time from the `FIREBASE_GOOGLE_SERVICES_JSON` repository secret, so `google-services.json` does not need to be committed to the repository.

## Project status
**Status:** Beta / Functional PTT MVP  
**Progress:** ~75%
