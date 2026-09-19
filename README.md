# 📻 Walkie

<p align="center">
  <strong>Push-to-talk voice rooms for Android.</strong><br/>
  Talk with friends over Wi-Fi or the internet with a simple, walkie-talkie style experience.
</p>

<p align="center">
  <a href="https://github.com/dh6xxn/walkie/actions/workflows/android.yml">
    <img src="https://github.com/dh6xxn/walkie/actions/workflows/android.yml/badge.svg" alt="Android Build"/>
  </a>
  <img src="https://img.shields.io/badge/Android-26%2B-3DDC84?logo=android&logoColor=white" alt="Android 26+"/>
  <img src="https://img.shields.io/badge/Kotlin-2.3-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin"/>
  <img src="https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white" alt="Jetpack Compose"/>
  <img src="https://img.shields.io/badge/LiveKit-Cloud-111827?logo=webrtc&logoColor=white" alt="LiveKit Cloud"/>
</p>

<p align="center">
  <a href="#-what-is-walkie">What is Walkie?</a> •
  <a href="#-features">Features</a> •
  <a href="#-how-it-works">How it works</a> •
  <a href="#-tech-stack">Tech stack</a> •
  <a href="#-build">Build</a> •
  <a href="#-roadmap">Roadmap</a>
</p>

---

## 🗣️ What is Walkie?

**Walkie** is an Android push-to-talk (**PTT**) voice app built around the feel of a real walkie-talkie: enter a room, see who is there, **hold to talk**, and release to stop transmitting.

The current **V4 — Walkie Rooms** build is a functional multi-room PTT MVP backed by **LiveKit Cloud**, with local room persistence and a cloud-based token API.

> **Project status:** Beta / Functional PTT MVP

---

## ✨ Features

| | Capability | Details |
|---|---|---|
| 🎙️ | **Push-to-talk audio** | Hold a dedicated button to transmit; release to stop. |
| 🚦 | **Speaker coordination** | PTT access is coordinated so participants wait while another person is speaking. |
| 🌐 | **Realtime voice rooms** | Audio rooms powered by LiveKit Cloud. |
| 🔑 | **Join by room code** | Enter a room name/code and join directly. |
| ➕ | **Create a room** | Create a new room and automatically join it. |
| 💾 | **My Rooms** | Recently used rooms are stored locally on the device. |
| 🔄 | **Multiple rooms** | Switch between saved rooms. |
| 👥 | **Participants** | See the current participant count and basic presence. |
| 📡 | **Connection states** | Connected, reconnecting, disconnected, and connection-error states. |
| 📱 | **Compact PTT UI** | A focused red Hold-to-Talk interface designed around one core action. |
| ☁️ | **Cloud builds** | Android debug APK builds are produced by GitHub Actions. |
| 🔐 | **Secret-based Firebase config** | Firebase configuration is restored at build time from a GitHub Actions secret. |

---

## 🧭 How it works

```text
                 ┌──────────────────┐
                 │   Enter / Create │
                 │      a room      │
                 └────────┬─────────┘
                          │
                          ▼
                 ┌──────────────────┐
                 │  Vercel Token API│
                 │  issues LiveKit  │
                 │      access      │
                 └────────┬─────────┘
                          │
                          ▼
                 ┌──────────────────┐
                 │   LiveKit Cloud  │
                 │   realtime audio │
                 └────────┬─────────┘
                          │
              ┌───────────┴───────────┐
              ▼                       ▼
       ┌─────────────┐         ┌─────────────┐
       │  Android A  │ ◄─────► │  Android B  │
       │  Hold TALK  │         │  Hear / TALK│
       └─────────────┘         └─────────────┘
```

### Typical flow

**1. Join**

Enter a room name/code and select **JOIN ROOM**.

**2. Save**

Joined rooms appear in **MY ROOMS** for quick access later.

**3. Talk**

Press and hold **HOLD TO TALK** to request the microphone.

**4. Release**

Let go of the button to stop transmitting.

**5. Switch**

Create another room or select a saved room to move between conversations.

---

## 🏗️ Architecture

Walkie is structured as a small cloud-first system:

```text
Android app
│
├── Kotlin
├── Jetpack Compose
├── Firebase
│   ├── Authentication
│   ├── Firestore
│   └── Cloud Messaging
│
└── LiveKit Android SDK
        │
        ▼
   LiveKit Cloud
        ▲
        │
Vercel token endpoint
        │
        └── LiveKit server SDK
```

The repository contains:

- **Android client** in `app/`
- **Token API** in `api/token.js`
- **Vercel configuration** in `vercel.json`
- **GitHub Actions build pipeline** in `.github/workflows/android.yml`
- **Build/automation scripts** in `scripts/`

---

## 🧰 Tech Stack

### Android

- **Kotlin**
- **Jetpack Compose**
- **Material 3**
- **Android SDK 35**
- **minSdk 26 / Android 8.0+**

### Backend & realtime

- **LiveKit Cloud** — realtime voice
- **livekit-server-sdk** — server-side token generation
- **Vercel** — token API hosting

### Firebase

- Firebase Authentication
- Cloud Firestore
- Firebase Cloud Messaging

### CI/CD

- GitHub Actions
- Java 17
- Gradle 8.11.1
- Automated debug APK artifact generation

---

## 🔐 Configuration

Firebase configuration is intentionally **not committed** to the repository.

The GitHub Actions workflow expects a repository secret named:

```text
FIREBASE_GOOGLE_SERVICES_JSON
```

During CI, the workflow restores this value to:

```text
app/google-services.json
```

The token API also requires the relevant LiveKit credentials to be supplied through the deployment environment.

---

## 🔨 Build

### GitHub Actions

Every push to `main` and every pull request runs the Android build workflow.

The workflow:

1. Checks out the repository.
2. Installs Java 17.
3. Configures Gradle 8.11.1.
4. Restores Firebase configuration from secrets.
5. Applies the PTT gesture fix script.
6. Builds the debug APK.
7. Uploads the APK as the `walkie-debug-apk` artifact.

### Local development

Open the repository in **Android Studio**, configure the required Firebase and LiveKit environment values, then build the Android app through Gradle.

The project is designed as a **cloud-first MVP**, so the development workflow does not require a local backend server.

---

## 📂 Project Structure

```text
walkie/
├── app/                     # Android application
├── api/
│   └── token.js             # LiveKit token endpoint
├── scripts/
│   └── apply_ptt_fix.py     # CI-applied PTT gesture patch
├── .github/
│   └── workflows/
│       └── android.yml      # Android CI/CD
├── build.gradle.kts         # Root Gradle configuration
├── settings.gradle.kts      # Gradle project settings
├── package.json              # Vercel/API dependencies
├── vercel.json               # Vercel configuration
└── README.md
```

---

## 🗺️ Roadmap

The current V4 build focuses on the core room-based PTT experience.

### Next up

- [ ] Usernames and user accounts
- [ ] Invite-only signup and administrator approval
- [ ] User profiles
- [ ] Friend requests and friends list
- [ ] Online/offline friend presence
- [ ] Public and private rooms
- [ ] Room invitations
- [ ] Room membership management
- [ ] Admin controls for users and rooms

---

## 📊 Current Status

**V4 — Walkie Rooms**

> **Beta · Functional PTT MVP**

The repository currently represents the room-based MVP rather than the full social communication platform described in the roadmap.

---

## 🤝 Contributing

Contributions, fixes, and ideas are welcome.

For larger changes, open an issue first so the approach can be discussed before implementation.

---

## 📄 License

No license file is currently present in this repository. Add a license before publishing Walkie for third-party reuse.

---

<p align="center">
  Built with Kotlin, Jetpack Compose, Firebase & LiveKit.
</p>
