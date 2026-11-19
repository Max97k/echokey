<div align="center">

# 🎙️ AKeyboard — Voice-Only AI Keyboard for Android

**A minimalist, BYOK Android voice-only Input Method (IME) powered by Google AI Studio's Gemini Live API (`gemini-3.5-transcribe-live`), with a built-in 2D touchpad for cursor control.**

[![Platform](https://img.shields.io/badge/Platform-Android%2012%2B%20(API%2031--37)-3DDC84?style=flat-square&logo=android&logoColor=white)](https://android.com)
[![Version](https://img.shields.io/badge/Version-v0.2.0-blue?style=flat-square)](https://github.com/Max97k/echokey)
[![Tests](https://img.shields.io/badge/Tests-Passing-brightgreen?style=flat-square)](https://github.com/Max97k/echokey)
[![Target Device](https://img.shields.io/badge/Tested%20On-Google%20Pixel%208%20Pro-4285F4?style=flat-square&logo=google&logoColor=white)](https://store.google.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.21-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose%20%7C%20Material%203-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![BYOK](https://img.shields.io/badge/Privacy-BYOK%20Direct%20%7C%20No%20Backend-success?style=flat-square&logo=shield&logoColor=white)](#-privacy--security-by-design)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg?style=flat-square)](LICENSE)

[English](README.md) • [繁體中文](README_zh.md) • [Download APK](https://github.com/Max97k/echokey/releases/latest) • [Design Doc](docs/designs/akeyboard_voice_ime.md)

</div>

---

## 📖 Overview

**AKeyboard** is an open-source Android system Input Method (`InputMethodService`) built with **Kotlin** and **Jetpack Compose (Material 3)**. Designed strictly for voice dictation without any character keys, it streams 16 kHz PCM audio directly from your device to Google AI Studio's **Gemini Live API** (`BidiGenerateContent` WebSocket) using your own API key (BYOK).

Live interim results appear in real time as composing text while you hold the microphone button, and release commits the final punctuated transcript. The rest of the keyboard surface doubles as a **2D cursor touchpad**—slide up, down, left, or right to move the text cursor precisely.

---

## ✨ Key Features

| Feature | Description |
| :--- | :--- |
| 🎙️ **Push-to-Talk Live Dictation** | Hold the mic button to stream 16 kHz 16-bit mono PCM audio over WebSocket (`gemini-3.5-transcribe-live`), with real-time composing preview and instant final commit on release. |
| ⚡ **Socket Pre-Warming** | Pre-warms the Gemini Live WebSocket when the keyboard opens (45 s idle TTL) and falls back cleanly to a 2 s ring buffer on cold start so opening words are never clipped. |
| 👆 **2D Touchpad Cursor Control** | Slide anywhere on the non-mic panel area to emit `DPAD` cursor movements with haptic ticks, multiline guard, and 24 dp edge dead zones to avoid Android back-gesture conflicts. |
| 🌐 **Language & Smart Mode** | Choose your transcription language (`zh-TW` default, `zh-TW + en-US`, Auto-detect 85+ languages, `zh-CN`, `en-US`, `ja-JP`, `ko-KR`) and toggle `SMART` disfluency removal vs. `VERBATIM`. |
| 🔑 **BYOK & Dynamic Model Discovery** | Enter your own Google AI Studio API key in Settings; AKeyboard queries `models.list` (`bidiGenerateContent`) so you can switch between available live models. |
| 🎨 **Material 3 & Pixel-Ready** | Dynamic Color theming, Material Rounded vector icons, contextual IME action key (`Search`, `Send`, `Next`, `Done`, `Return`), hold-to-repeat Backspace, and optional Switch-IME key. |

---

## 🛡️ Privacy & Security by Design

* **Zero Developer Backend**: Audio streams directly from your device to `generativelanguage.googleapis.com` over TLS (`wss://`) using your own API key. No audio is written to local disk.
* **Hardware-Backed Key Storage**: Your API key is encrypted with **Android Keystore (AES-256-GCM)** and stored in Jetpack DataStore, excluded from cloud backup and device transfer (`allowBackup="false"`, `data_extraction_rules.xml`).
* **Password & Incognito Guard**: Voice input is automatically disabled in password fields and `IME_FLAG_NO_PERSONALIZED_LEARNING` contexts.
* **Prominent Disclosure & `FLAG_SECURE`**: First-run data disclosure before setup and `FLAG_SECURE` on `SettingsActivity` to prevent screenshot leakage of your API key.

---

## 🏗️ Architecture

```text
Android InputConnection  <--->  AKeyboardImeService (Compose + Lifecycle)
                                  ├── KeyboardScreen & TouchpadSurface (2D DPAD)
                                  ├── AudioCaptureManager (16kHz PCM, 40ms frames)
                                  └── GeminiLiveClient (OkHttp WSS BidiGenerateContent)
                                        └── ApiKeyStore (Keystore AES-GCM) & SettingsRepository
```

---

## 🚀 Getting Started

### Prerequisites
* Android 12 (API 31) or higher (targetSdk 37 / Android 17)
* A Google AI Studio API key

### First-Time Setup
1. Open [Google AI Studio](https://aistudio.google.com/apikey) (or tap **Get a key at Google AI Studio** in AKeyboard Settings), sign in, create an API key, and copy it.
2. Return to AKeyboard Settings, tap **Paste from clipboard**, and tap **Validate & Save Key**.
3. Follow the **Get started** checklist at the top of Settings to enable AKeyboard in system settings and grant microphone access.

### Build & Test

```bash
# Run unit tests
./gradlew :app:testDebugUnitTest

# Run Lint (warningsAsErrors = true)
./gradlew :app:lintDebug

# Build & install Debug APK
./gradlew :app:installDebug
```

---

## 💻 Windows 10/11 Bluetooth Voice Bridge

For restricted corporate PCs that block `generativelanguage.googleapis.com` or unsigned `.exe` installers, AKeyboard includes a zero-dependency Python 3.10+ receiver (`tools/akeyboard_bridge.py`) for Windows 10/11 and an in-app **PC Bluetooth Remote** screen:

1. Pair your Android phone and Windows 10/11 PC over Bluetooth.
2. On Windows 10/11, run `python tools/akeyboard_bridge.py` (uses official Python 3.10+ standard library only; no `pip install` or admin rights required).
3. On your phone, open **AKeyboard Settings → Open PC Bluetooth Voice Remote**, connect to your PC, and hold the mic button to dictate directly to your PC cursor.

*(Limitations: requires official CPython 3.10+ with Bluetooth socket support on Windows 10/11, corporate GPO must allow Bluetooth RFCOMM, a target window must have focus, and UIPI blocks `SendInput` into elevated Admin/UAC windows.)*

---

## 📄 License

Distributed under the MIT License. See [LICENSE](LICENSE) for more information.
