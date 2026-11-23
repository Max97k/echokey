# Privacy Policy / 隱私權政策 — AKeyboard

*Last updated: 2026-11-23 / 最後更新日期：2026 年 11 月 23 日*

[English](#english) • [繁體中文](#繁體中文)

---

<a id="english"></a>
## English Version

### 1. Overview
**AKeyboard** is an open-source, voice-only Android Input Method Editor (IME) application designed with privacy as a foundational principle. AKeyboard operates strictly under a **Bring Your Own Key (BYOK)** architecture. **The developer does not operate, host, or maintain any intermediary backend servers, proxy servers, databases, or cloud storage.** All speech-to-text processing occurs via direct, encrypted client-to-cloud connections between your device and Google's Gemini Live API using your personal API key.

---

### 2. Audio Capture & Data Transmission
- **User-Initiated Push-to-Talk**: The microphone is accessed and audio is recorded **only** while you explicitly press and hold the microphone button on the keyboard surface. There is no background listening, hotword detection, or passive recording.
- **Direct Encrypted Transmission**: Captured audio (16 kHz, 16-bit mono PCM) is streamed in real time over an encrypted WebSocket connection (`wss://generativelanguage.googleapis.com`) directly to Google LLC's Gemini API endpoints.
- **Zero Local Audio Storage**: Audio frames are kept only in volatile memory during active streaming and are immediately discarded. AKeyboard never saves, caches, or writes audio recordings to local disk or device storage.
- **No Keystroke or Sensitive Input Logging**: AKeyboard does not track, collect, or store keystrokes, clipboard history, or input content. In sensitive fields (such as password fields, PIN inputs, or fields with `IME_FLAG_NO_PERSONALIZED_LEARNING`), voice input and microphone access are automatically disabled.

---

### 3. Bring Your Own Key (BYOK) & Security
- **Hardware-Backed Encryption**: Your Google AI Studio API key is encrypted on your device using the **Android Keystore system (AES-256-GCM)**.
- **Secure Local Storage**: The encrypted key is stored exclusively in application-private storage using Jetpack DataStore.
- **Excluded from Backups**: Backups and device migration are disabled (`android:allowBackup="false"` and `data_extraction_rules.xml`). Your API key is never uploaded to Google Drive backups or transferred during device setup.
- **Screen Protection**: The settings screen enforces `WindowManager.LayoutParams.FLAG_SECURE` to prevent accidental screenshot capture or screen-recording leakage of your API key.

---

### 4. Bluetooth Bridge Mode (Local Remote Input)
AKeyboard includes an optional **Bluetooth Bridge** feature allowing you to dictate text from your phone directly into a paired Windows PC:
- **Local Peer-to-Peer Only**: Text and virtual key commands (`ENTER`, `BACKSPACE`) are transmitted strictly over standard Bluetooth RFCOMM (Serial Port Profile) directly to the paired computer running the AKeyboard companion desktop utility (`tools/akeyboard_bridge.py`).
- **No Intermediary Relays**: Bluetooth transmissions are purely local and near-field; no Bluetooth payload or transmitted text is routed through or uploaded to external servers or cloud services.

---

### 5. Android Permissions & Purpose Justification
AKeyboard requests only the minimum set of permissions necessary to deliver its core functionality:

| Permission | Technical Name | Purpose & Justification |
| :--- | :--- | :--- |
| **Microphone** | `android.permission.RECORD_AUDIO` | Required to record voice input when the user presses and holds the mic button for speech transcription. Microphone hardware is marked as optional (`android.hardware.microphone`, `required=false`) to support external headsets and audio devices. |
| **Internet Access** | `android.permission.INTERNET` | Required to establish encrypted WebSocket (`wss://`) connections to Google Gemini Live API and fetch the user's available model list (`models.list`). |
| **Network State** | `android.permission.ACCESS_NETWORK_STATE` | Used to inspect network connectivity status to present immediate offline/error indicators when internet access is unavailable. |
| **Bluetooth Connect** | `android.permission.BLUETOOTH_CONNECT` | Required on Android 12 (API 31) and higher to discover paired Bluetooth devices and send text to your own Windows PC when Bluetooth Bridge mode is activated by the user. |

---

### 6. Third-Party Services & Data Processors
When using AKeyboard, speech audio and model listing requests are transmitted directly to:
- **Google LLC (Google AI Studio / Gemini API)**:
  - **Endpoint**: `generativelanguage.googleapis.com`
  - **Terms & Privacy**: Interactions with Google's API are governed by the [Google Privacy Policy](https://policies.google.com/privacy) and the [Google AI Studio & Gemini API Terms of Service](https://ai.google.dev/terms).
  - **Data Handling**: Depending on whether you use an unpaid/free tier or a paid tier in Google AI Studio, Google's handling and retention of API payloads may differ in accordance with Google's terms. You maintain direct control over your API account and usage tier at [Google AI Studio](https://aistudio.google.com/).

---

### 7. Zero Telemetry, Analytics & Advertising
- **No Advertising SDKs**: AKeyboard contains no third-party advertisements or ad networks.
- **No Tracking or Analytics SDKs**: There are zero tracking, behavioral analysis, or telemetry libraries (e.g., no Google Analytics, Firebase Analytics, Facebook SDK, Mixpanel, etc.).
- **Local Diagnostics Only**: Diagnostic logs (`DiagnosticLog`) reside strictly in a transient in-memory ring buffer for voluntary on-device troubleshooting by the user and are never transmitted automatically.

---

### 8. Data Retention, Control & Deletion
- **Instant Deletion upon Clear / Uninstall**: Because all user data (encrypted API key, language preferences, model selection, and local API token usage counters) is stored locally on-device, you can permanently delete all data at any time by:
  1. Opening **Settings** in AKeyboard and clicking **Clear Key** / **Reset stats**; or
  2. Going to Android **System Settings > Apps > AKeyboard > Storage > Clear Data**; or
  3. **Uninstalling** the AKeyboard application from your device.
- **No Server-Side Retention**: Since the developer operates no servers, there is no server-side user data to retain or delete.

---

### 9. Children's Privacy
AKeyboard is not directed toward children under the age of 13 (or the applicable legal age in your jurisdiction). We do not knowingly collect, request, or solicit personal information from children.

---

### 10. Changes to This Privacy Policy
Any updates to this policy will be posted to our open-source repository and reflected on the hosted GitHub Pages privacy URL. Significant changes will be noted with an updated "Last updated" date at the top of this document.

---

### 11. Contact Us
If you have questions, feedback, or concerns regarding this Privacy Policy or your data, please contact the developer:
- **Developer / Maintainer**: Max97k
- **Email**: [Max97k@users.noreply.github.com](mailto:Max97k@users.noreply.github.com)
- **Repository Issues**: [https://github.com/Max97k/voice-keyboard-pc-bridge/issues](https://github.com/Max97k/voice-keyboard-pc-bridge/issues)

---
---

<a id="繁體中文"></a>
## 繁體中文版

### 1. 概述
**AKeyboard** 是一款以保護使用者隱私為核心設計原則的 Android 開源純語音輸入法（IME）。AKeyboard 嚴格遵循 **BYOK（Bring Your Own Key，自帶金鑰）** 架構。**本軟體開發者未架設、未營運，亦未維護任何中繼伺服器、代理伺服器、資料庫或雲端儲存空間。** 所有的語音轉文字過程，皆由您的行動裝置使用您個人的 API 金鑰，透過加密通道直接與 Google Gemini Live API 進行通訊。

---

### 2. 語音擷取與資料傳輸機制
- **使用者主動觸發（按住說話）**：麥克風僅在使用者於鍵盤介面上主動按住麥克風按鈕時才會被啟動錄音。本應用程式絕無任何背景竊聽、喚醒詞常駐監聽或被動錄音行為。
- **端對端加密直連傳輸**：錄製的音訊（16 kHz, 16-bit 單聲道 PCM）會即時透過 TLS 加密 WebSocket（`wss://generativelanguage.googleapis.com`）直接串流至 Google LLC 的 Gemini API 端點。
- **本機絕不儲存錄音檔**：音訊影格在串流傳輸過程中僅暫存於揮發性記憶體（RAM），傳輸完畢後立即釋放。AKeyboard 絕不將錄音資料寫入或快取於裝置本機儲存空間或 SD 卡中。
- **不記錄按鍵與敏感內容**：AKeyboard 絕不記錄、收集或儲存使用者的任何輸入文字、按鍵內容或剪貼簿紀錄。若輸入框屬於密碼欄位、PIN 碼或標記有 `IME_FLAG_NO_PERSONALIZED_LEARNING`（禁止個人化學習）的隱私輸入框，語音輸入與麥克風功能將被自動完全停用。

---

### 3. 自帶金鑰（BYOK）與本機安全保護
- **硬體層級金鑰加密**：您在設定中所輸入的 Google AI Studio API Key，在本機皆使用 **Android Keystore 系統（AES-256-GCM 硬體隔離保護）** 進行加密。
- **隔離安全儲存**：加密後的密文僅存放於應用程式私有目錄下的 Jetpack DataStore。
- **排除雲端備份與遷移**：本應用程式停用雲端備份（`android:allowBackup="false"` 與 `data_extraction_rules.xml`），API 金鑰不會被上傳至 Google Drive 雲端備份，亦不會在裝置轉移更換手機時外洩。
- **防截圖與防側錄**：設定頁面強制啟用 `FLAG_SECURE`，防止螢幕截圖、螢幕錄影或多工任務快照洩漏您的 API 金鑰。

---

### 4. 藍牙語音遙控輸入（Bluetooth Bridge）
AKeyboard 提供可選的 **藍牙語音遙控輸入** 功能，允許您將手機上的語音聽寫文字即時輸入至已配對的 Windows 電腦：
- **近端點對點傳輸**：轉錄文字與虛擬控制鍵（`ENTER`、`BACKSPACE`）僅透過近距離藍牙 RFCOMM（串列埠規範 SPP）直接傳送至執行 AKeyboard 電腦端接收腳本（`tools/akeyboard_bridge.py`）的 Windows 裝置。
- **不經過任何雲端中繼**：藍牙傳輸完全限定於本機近場裝置之間，絕不將通訊內容轉發或上傳至外部伺服器或第三方雲端服務。

---

### 5. Android 系統權限與用途說明
AKeyboard 僅宣告並請求維持核心功能運作所不可或缺的權限：

| 權限名稱 | 技術宣告代碼 | 權限用途與必要性說明 |
| :--- | :--- | :--- |
| **麥克風錄音** | `android.permission.RECORD_AUDIO` | 用於在使用者按住麥克風按鈕時擷取語音輸入並進行語音辨識。麥克風硬體特性標註為非強制（`required=false`），相容無內建麥克風但外接耳機或音訊輸入裝置之場景。 |
| **網際網路** | `android.permission.INTERNET` | 用於透過加密 WebSocket（`wss://`）連線至 Google Gemini Live API 端點，以及查詢該金鑰可用之模型清單（`models.list`）。 |
| **網路連線狀態** | `android.permission.ACCESS_NETWORK_STATE` | 用於檢測目前行動裝置是否已連線至網路，以便在無網路或離線時即時提供友善狀態回饋。 |
| **藍牙連線** | `android.permission.BLUETOOTH_CONNECT` | 適用於 Android 12（API 31）以上版本，僅在使用者啟用藍牙橋接功能時，用於搜尋已配對的藍牙裝置並將文字指令發送至您自己的 Windows 電腦。 |

---

### 6. 第三方服務與資料處理者
當您使用 AKeyboard 進行語音聽寫與模型查詢時，相關資料將直連傳送至：
- **Google LLC（Google AI Studio / Gemini API）**：
  - **服務端點**：`generativelanguage.googleapis.com`
  - **政策規範**：與 Google API 之間的互動適用 [Google 隱私權政策](https://policies.google.com/privacy) 與 [Google AI Studio & Gemini API 服務條款](https://ai.google.dev/terms)。
  - **資料處理機制**：依您在 Google AI Studio 帳號所選用的計費方案（免費版 Free Tier 或付費版 Paid Tier），Google 對於 API 傳輸內容之保留與處理方式各有不同規範，詳情請參閱 Google 官方條款。使用者可隨時至 [Google AI Studio](https://aistudio.google.com/) 自主管理金鑰與帳號權限。

---

### 7. 零追蹤、零數據分析與無廣告（Zero Telemetry）
- **無任何廣告 SDK**：AKeyboard 絕無內嵌任何第三方廣告元件或廣告追蹤程式碼。
- **無任何使用者追蹤或分析 SDK**：本軟體不含任何使用者行為追蹤、統計分析或遙測工具（例如：無 Google Analytics、無 Firebase Analytics、無 Facebook SDK 等）。
- **僅限本機揮發性診斷**：除錯日誌（`DiagnosticLog`）僅於記憶體內的暫態環形緩衝區運作，供使用者於本機自我排查異常，絕不主動對外回傳。

---

### 8. 資料保留、使用者控制與刪除機制
- **即時且徹底的資料刪除**：本應用程式的所有使用者資料（加密金鑰、語言設定、偏好設定與本機 API 用量統計）皆僅存於使用者本機裝置。您可以透過以下任一方式永久銷毀所有個人設定與金鑰：
  1. 在 AKeyboard 設定介面中點選「**清除 Key**」／「**重設統計**」；或
  2. 至 Android **系統設定 > 應用程式 > AKeyboard > 儲存空間 > 清除所有資料**；或
  3. 直接從裝置中 **解除安裝 AKeyboard**。
- **無伺服器端資料留存**：由於開發者未營運伺服器，因此本專案不存在任何伺服器端使用者資料可供保留或需提出刪除請求。

---

### 9. 兒童隱私保護
本應用程式並非針對 13 歲（或您所在司法管轄區法定年齡）以下之兒童所設計，我們亦絕不蓄意收集任何兒童之個人資訊。

---

### 10. 隱私權政策修訂
本隱私權政策如有任何修訂，將公布於開源專案儲存庫與託管於 GitHub Pages 之官方隱私權頁面，並更新本文件頂部之「最後更新日期」。

---

### 11. 聯絡開發者
若您對本隱私權政策或資料安全有任何疑問或建議，歡迎透過以下方式與開發者聯繫：
- **開發者**：Max97k
- **電子信箱**：[Max97k@users.noreply.github.com](mailto:Max97k@users.noreply.github.com)
- **專案 Issue 追蹤**：[https://github.com/Max97k/voice-keyboard-pc-bridge/issues](https://github.com/Max97k/voice-keyboard-pc-bridge/issues)
