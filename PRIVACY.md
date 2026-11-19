# Privacy Policy — AKeyboard

*Last updated: 2026-11-18*

## 1. Overview

AKeyboard is an Android voice-only keyboard application operating in Bring-Your-Own-Key (BYOK) mode. There is no developer-operated backend server.

## 2. Data Collection & Transmission

- **Audio Data**: Microphone audio is captured **only** while you actively press and hold the microphone button in the keyboard. Audio is streamed directly over TLS (`wss://`) from your device to Google's Gemini API (`generativelanguage.googleapis.com`) using your personal Google AI Studio API key.
- **No Local Audio Storage**: AKeyboard does not write recorded audio to device storage.
- **No Keystroke or Text Logging**: AKeyboard does not collect, store, or transmit text you type in other applications, and voice input is disabled in password and incognito fields.
- **API Key Storage**: Your Google AI Studio API key is encrypted on-device using Android Keystore (AES-256-GCM) and stored in app-private storage excluded from cloud backup and device transfer.

## 3. Third-Party Processing

Audio streams and model listing requests are sent directly to Google LLC (`generativelanguage.googleapis.com`) and are governed by your Google AI Studio / Gemini API terms and Google's Privacy Policy. Whether Google may use content sent under free (unpaid) Gemini API usage to improve its products depends on Google's current Gemini API terms and the plan you choose in Google AI Studio.

## 4. Analytics & Advertising

AKeyboard contains no third-party analytics, crash-reporting, or advertising SDKs.
