# AKeyboard Windows 11 藍牙語音輸入橋接（Bluetooth Voice Bridge）

*   **Author:** Max97k
*   **Status:** Approved
*   **Date:** 2026-11-18

--------------------------------------------------------------------------------

## Overview

本設計為現有 **AKeyboard（Android 語音輸入法）** 新增「**電腦遙控輸入畫面（PC Bluetooth Bridge）**」，並搭配一支**免安裝、零第三方套件相依的單一 Python 接收端腳本（`akeyboard_bridge.py`）**執行於 Windows 11。

使用者在無法直接連線 Google AI Studio API 的受限網路環境（例如公司配發電腦）中，可由手機透過行動網路呼叫 Gemini Live API 完成語音轉文字與智慧斷句（含繁體中文 `zh-TW`、中英混打、標點與 `SMART` / `VERBATIM` 模式），再經由藍牙近端連線將最終確認的 UTF-8 文字（與 `Enter` / `Backspace` 鍵）傳送至 Windows 11 電腦，直接注入目前焦點視窗的游標位置。

## The Problem

1. **企業網路封鎖導致無法在電腦端直接使用語音 AI API**：公司電腦的防火牆或 DLP 政策常封鎖 `generativelanguage.googleapis.com` 等外部 AI 端點，使電腦端無法直接執行呼叫 Google AI Studio 的語音輸入工具。
2. **純藍牙 HID 鍵盤無法處理繁體中文與 Unicode 輸出**：若僅將 Android 手機模擬為標準藍牙 HID 鍵盤，協定層只能傳送實體按鍵掃描碼（Scan Code），無法直接輸出繁體中文、日文或 Gemini 已完成斷句與校正的完整 Unicode 字串。
3. **企業環境對未簽章執行檔與第三方套件的部署門檻**：公司電腦通常禁止執行未簽章的第三方 `.exe` 安裝程式，也不便安裝額外 pip 套件；但若公司電腦已因工作需求預裝且允許執行 Python，一支僅使用 Python 標準函式庫的純文字腳本即可兼顧透明審查性與可攜性。

## Goals

*   **G1 — 專屬電腦遙控語音輸入畫面**：在 AKeyboard App 內新增專屬「電腦遙控輸入」畫面，抽出並共用現有語音擷取與 Gemini Live 轉錄核心，將最終確認文字（Final Commit）與基本編輯鍵（Backspace、Enter）透過藍牙傳送至已配對的 Windows 11 電腦。
*   **G2 — 完整保留 Unicode 與中文轉錄品質**：Windows 11 接收端能將手機傳來的 UTF-8 字串（包含繁體中文、英文、數字與標點符號）無亂碼地原樣注入目前前景視窗的輸入游標位置，不經由 Windows 內建注音／倉頡二次選字。
*   **G3 — 零第三方套件、單檔可攜的 Windows 11 接收端**：Windows 11 端僅需單一 `.py` 腳本，僅使用 Python 標準函式庫（`socket`、`select`、`json`、`ctypes`、`time`），不需 `pip install`、不需安裝程式、不需系統管理員權限（一般使用者權限即可執行）。
*   **G4 — 維持資安邊界與不靜默遺失文字**：Windows 11 電腦端不接觸麥克風音訊、不儲存 Google AI Studio API Key、也不對外連線任何雲端服務；透過單行應用層回執（ACK/NACK）確保寫入緩衝或 UIPI 失敗時不會靜默丟失使用者剛說完的內容。

## Non-Goals

*   **NG1 — 不開發獨立的 `.exe` 安裝包或系統服務（Windows Service）**：本規格不產出 MSI/EXE 安裝檔，也不處理沒有預裝 Python 環境的電腦打包（如 PyInstaller / Embeddable Python 發行）。
*   **NG2 — 不支援純藍牙 HID（無接收端程式）模式**：不在本規格中實作 Android `BluetoothHidDevice` 掃描碼模擬。
*   **NG3 — 不支援透過 Wi-Fi、區網 HTTP/WebSocket 或雲端中繼傳送文字**：傳輸層嚴格限定為藍牙近端連線。
*   **NG4 — 不保證在提權視窗（UAC / 系統管理員終端機）或鎖定畫面輸入**：受限於 Windows UIPI（使用者介面權限隔離），一般權限執行的 Python 腳本無法對高權限視窗送出 `SendInput`（但須偵測並回報失敗，不可靜默吞字）。
*   **NG5 — 不在 Windows 11 端即時同步「尚未確認的臨時辨識文字（Composing / Interim Text）」**：為避免跨裝置反覆刪除與重打造成游標錯亂，僅在放開麥克風按鈕、取得 Final Commit（或逾時備援結果）後傳送最終字串（手機端畫面仍顯示即時辨識預覽）。
*   **NG6 — 不轉傳滑鼠或 2D 觸控板游標移動**：電腦端已有實體滑鼠與鍵盤，不處理手機端 2D 觸控板滑動事件，也不模擬滑鼠移動／點擊或方向鍵。

## Critical User Journeys (CUJs)

### CUJ 1: 公司電腦日常語音輸入繁體中文與中英混打

*   **Given**: 使用者的手機與公司 Windows 11 電腦已完成藍牙配對；公司電腦已使用預裝 Python 啟動 `akeyboard_bridge.py` 並顯示「等待手機連線」；使用者從手機 `SettingsActivity` 進入「電腦遙控輸入」畫面並連上該電腦。
*   **When**: 使用者在公司電腦將游標點入 Slack / Teams / Word / 瀏覽器輸入框，按住手機遙控畫面上的麥克風按鈕說出「請幫我確認一下 PR 的 merge 時間」，放開麥克風按鈕。
*   **Then**: 手機透過行動網路完成 Gemini Live 轉錄並顯示即時預覽；放開按鈕後將最終 UTF-8 文字透過藍牙傳送至 `akeyboard_bridge.py`，收到電腦端 `{"ok":true}` 回執後完成送出，文字原樣出現在公司電腦目前的輸入游標位置，且不觸發公司網路對外 API 存取。

### CUJ 2: 語音輸入後直接送出換行或刪除最後一句

*   **Given**: 手機「電腦遙控輸入」畫面已與 `akeyboard_bridge.py` 保持連線。
*   **When**: 使用者在語音輸入完畢後，點擊遙控畫面上的 Enter 鍵送出訊息，或點擊 Backspace 刪除誤觸／多餘的字元。
*   **Then**: 手機將對應的 Enter 或 Backspace 指令透過藍牙傳送至 Windows 11，`akeyboard_bridge.py` 轉譯為 `VK_RETURN` 或 `VK_BACK` 作用於電腦目前游標位置。

### CUJ 3: 未連線暫存、斷線提示與高權限視窗失敗保護

*   **Given**: 手機處於「電腦遙控輸入」畫面，尚未連線至電腦、電腦休眠斷線，或電腦前景視窗為系統管理員權限視窗（UIPI 阻擋）。
*   **When**: 使用者按住麥克風說完一段話並放開。
*   **Then**:
    *   若處於未連線狀態，或藍牙寫入／等待 2 秒回執逾時失敗，或收到 `{"ok":false,"err":"uipi"}`，手機端不清除文字，而是將該段文字保留（若已有未送出文字則以換行附加）於畫面暫存區並顯示「重送」按鈕與錯誤提示。
    *   待電腦恢復連線或切換至一般權限視窗後，使用者點擊「重送」即可補送，不遺失任何已轉錄完成的內容。

## Non-Functional Requirements

*   **Security & Privacy**:
    *   Google AI Studio API Key 僅存於手機端 `ApiKeyStore`（Android Keystore AES-256-GCM 加密），不傳送或儲存於公司電腦。
    *   `akeyboard_bridge.py` 僅監聽本機藍牙 RFCOMM，不開啟任何 TCP/UDP 網路埠，不將接收到的文字寫入磁碟日誌，並限制單行接收緩衝上限為 64 KB 防止記憶體耗盡。
    *   `akeyboard_bridge.py` 以純文字原始碼形式交付且零第三方依賴，方便企業資安或 IT 人員直接檢視程式碼。
*   **Performance & Latency**:
    *   放開麥克風取得最終文字後，藍牙 SPP 單行 JSON 傳輸、`SendInput` 分段批次注入與回執往返應於 100 ms 內完成（不含 Gemini Live 轉錄本身延遲）。
    *   `SendInput` 每次最多送出 32 個 UTF-16 碼元（且不拆斷 Emoji Surrogate Pairs），兼顧 Electron / 瀏覽器輸入佇列穩定性與低延遲。
*   **Rollout & Compatibility**:
    *   將共用錄音與 Gemini Live 狀態機抽出為 `VoiceDictationSession` 供 IME 與遙控畫面共用，透過既有的 Android 單元測試（目前為 9 項測試）與 `lintDebug`（`warningsAsErrors = true`）驗證不影響既有鍵盤輸入路徑。
    *   Windows 11 端需預裝支援 `socket.AF_BLUETOOTH` 的官方 CPython 3.10+ Windows 版本。
    *   **企業環境前置風險**：即使公司允許標準藍牙無線鍵盤（HID），IT 群組原則（GPO）仍可能單獨封鎖藍牙序列埠（RFCOMM/SPP）；實作 Android UI 前須先以最小 Python 原型於目標公司電腦驗證 `AF_BLUETOOTH` 與 `WSASetServiceW` 可用性。

## Technical Plan (Architecture)

本架構分為「**Android 端專屬遙控模組（含共用錄音協調器）**」與「**Windows 11 單檔 Python 接收腳本**」，兩端透過標準藍牙 SPP（RFCOMM）與單行 JSON 協定通訊。

```text
[手機端 Android (行動網路)]                         [公司 Windows 11 (受限內網)]
┌──────────────────────────────────┐               ┌──────────────────────────────┐
│ RemoteDictationActivity / VM     │               │ akeyboard_bridge.py (Python) │
│  ├── VoiceDictationSession (共用) │               │  ├── AF_BLUETOOTH + select() │
│  │    ├── AudioCaptureManager    │               │  │   + WSASetServiceW (SDP)   │
│  │    └── GeminiLiveClient (WSS) │               │  └── user32.SendInput        │
│  └── BluetoothBridgeClient       │ ◄── BT SPP ─► │      (KEYEVENTF_UNICODE)     │
│       (RFCOMM Client, JSON + ACK)│               │      + 單行 ACK/NACK 回執     │
└──────────────────────────────────┘               └──────────────┬───────────────┘
                                                                  ▼
                                                        [目前前景視窗游標位置]
```

### 1. Android 端架構（抽出共用錄音核心，不更動既有 IME 行為）

*   **`VoiceDictationSession`（從 `AKeyboardImeService` 抽出的薄層共用類別）**：
    *   將原本耦合於 `AKeyboardImeService` 的 `AudioCaptureManager`（2 秒 `Channel` 環形緩衝）、`GeminiLiveClient` 預熱（`warmUp`）、`MAX_HOLD_DURATION_MS` 錄音上限與 `LiveEvent.Final` / `TurnComplete` / `finalizeTimeout()`（2000 ms 備援）狀態機抽出為純 Kotlin 類別，透過 `onInterim`、`onFinal`、`onError` callback 對外通知。
    *   `AKeyboardImeService` 與 `RemoteDictationViewModel` 皆透過此類別驅動語音轉錄，避免維護兩套相同的 Gemini Live 生命週期。
    *   於 `AudioCaptureManager` 支援指定偏好麥克風裝置（`AudioDeviceInfo.TYPE_BUILTIN_MIC`），防止手機與 Windows 11 配對後被藍牙 HFP/SCO 設定檔搶佔音訊輸入。
*   **`RemoteDictationActivity` 與 `RemoteDictationViewModel`（Compose Material 3）**：
    *   僅由 `SettingsActivity` 內的按鈕進入（`android:exported="false"`，跟隨系統螢幕方向與系統休眠設定，不強制恆亮）。
    *   `RemoteDictationViewModel` 透過 `ViewModelProvider.Factory` 從 `AKeyboardApp` 手動注入 `okHttpClient`、`apiKeyStore` 與 `settingsRepository`（比照 `SettingsViewModel.Factory`）。
    *   狀態機包含統一的 `ConnectionState`（`Disconnected`、`Connecting`、`Connected(deviceName)`、`Error(message)`）、`DictationState`（`Idle`、`Listening`、`Finalizing`、`Error(message)`）與 `pendingResendText`。明確處理無 API Key、無麥克風權限及 `LiveEvent.Failed`。
    *   Push-to-Talk 麥克風按鈕在未連線時仍可點擊使用，轉錄結果直接存入 `pendingResendText`；UI 僅包含**已配對電腦選單與連線狀態列**、**即時預覽／暫存重送區（含「重送」按鈕）**、**Push-to-Talk 麥克風按鈕**、以及 **Backspace / Enter 鍵**。
*   **`BluetoothBridgeClient`（藍牙 RFCOMM Client + JSON 序列化）**：
    *   在 `AndroidManifest.xml` 宣告 `BLUETOOTH_CONNECT` 權限（因 `minSdk = 36`，不需舊版藍牙權限，亦不需 `BLUETOOTH_SCAN` 或定位權限）；透過 `context.getSystemService(BluetoothManager::class.java).adapter` 取得配接器（禁止使用已棄用的 `BluetoothAdapter.getDefaultAdapter()`），並加上 `@SuppressLint("MissingPermission")` 與執行期權限檢查以通過 `lintDebug`（`warningsAsErrors = true`）。
    *   直接使用專案既有的 `kotlinx.serialization.json`（`@Serializable`）編碼指令並內聚於 `BluetoothBridgeClient.kt`。
    *   `send(payload)` 為 `suspend` 函式並於 `Dispatchers.IO` 執行（加鎖保護序列化寫入與讀取）：寫入單行 JSON 後同步等待 1 行 ACK 回應（設 2 秒 `soTimeout` 或 coroutine timeout）。
    *   藍牙 Socket 維持於 `RemoteDictationViewModel` 生命週期內，於使用者關閉遙控頁面觸發 `onCleared()` 時自動釋放連線。

### 2. 藍牙傳輸協定（Newline-Delimited UTF-8 JSON + 單行回執）

每則指令與回應皆為單行 UTF-8 JSON，以 `\n` 結尾：

```json
// Android -> Windows（送出轉錄文字）
{"type":"text","value":"請幫我確認一下 PR 的 merge 時間"}

// Android -> Windows（送出編輯按鍵：ENTER / BACKSPACE）
{"type":"key","key":"ENTER"}

// Windows -> Android（SendInput 執行結果回執）
{"ok":true}
{"ok":false,"err":"uipi"}
```

### 3. Windows 11 端架構（`tools/akeyboard_bridge.py`）

單一 Python 3.10+ 腳本，**僅使用 Python 標準函式庫**（`socket`、`select`、`json`、`ctypes`、`time`）：

*   **藍牙 RFCOMM Server 與 SDP 廣告（64 位元 `ctypes` 約束）**：
    *   Python `socket` 模組匯入時已自動初始化 Winsock，不需定義 `WSADATA` 或呼叫 `WSAStartup`。
    *   使用 `socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)` 綁定 `("00:00:00:00:00:00", 0)`（不依賴未保證存在的 `socket.PORT_ANY`），並以 `sock.getsockname()[1]` 取得系統配發的 RFCOMM Port。
    *   透過 `ctypes` 呼叫 `ws2_32.dll` `WSASetServiceW(RNRSERVICE_REGISTER)` 註冊專屬 `BRIDGE_UUID`（`8e7b3f20-4c6a-4d8e-9a1b-2c3d4e5f6a7b`）：`SOCKADDR_BTH` 強制設定 `_pack_ = 1`（30 bytes），並採用預先編碼的靜態 SPP SDP 位元組模板（僅於執行期填入 1-byte Port）或省略 `lpBlob` 由系統自動產生 SDP 紀錄。
    *   外層包覆 `OSError` 自動重建迴圈（`while True: try: ... except OSError: time.sleep(2)`），確保 Windows 休眠喚醒（`WSAENETDOWN`）後能自動重開 Socket 並重新註冊 SDP。
*   **`select()` 單線接管與串流解碼**：
    *   使用 `select.select([server_sock, client_sock], [], [])` 同時監聽新連線與已連線客戶端；只要新連線連入即關閉舊的（可能已 half-open 的）`client_sock` 並接管，不需額外 `ping`/`pong` 心跳協定。
    *   以 `bytes` 緩衝區累積資料並依 `b"\n"` 切分完整行後才呼叫 `.decode("utf-8")`（防止多位元組中文字在 `recv()` 邊界被切斷），單行上限 64 KB，超過則丟棄並關閉連線。
*   **Win32 `SendInput` 分段注入與測試縫**：
    *   於模組層級保護 `ctypes.windll` 存取，使單元測試可在跨平台／無 Win32 環境安全匯入；將 `process_line(line: str, send_input_fn=...)` 抽為可注入函式。
    *   `INPUT` Union 必須包含 `MOUSEINPUT`、`KEYBDINPUT` 與 `HARDWAREINPUT`（確保 64 位元下 `sizeof(INPUT) == 40` bytes），`dwExtraInfo` 使用 `ctypes.c_size_t`。
    *   收到 `text` 時，先將 `\r\n` / `\n` 切分並轉譯為 `VK_RETURN`（`0x0D`），其餘文字轉為 UTF-16LE（`KEYEVENTF_UNICODE = 0x0004`、`wVk = 0`、`wScan = code_unit`），以每批最多 32 個 UTF-16 碼元分段送出（且確保不拆斷 Emoji Surrogate Pair）。
    *   檢查 `SendInput` 回傳值：若成功送出預期事件數則回傳 `{"ok":true}\n`；若回傳 `0` 或少於預期（例如前景視窗為 UIPI 提權視窗），回傳 `{"ok":false,"err":"uipi"}\n`。

## Alternatives Considered

### Alternative A: Windows 11 端直接呼叫 Gemini Live API（不經手機藍牙）

*   **Pros**: 延遲最低，不依賴手機電量與藍牙配對。
*   **Cons**: 無法通過公司防火牆與 DLP 政策（公司網路封鎖 Google AI Studio 端點），且需電腦具備麥克風。
*   **Reason for Rejection**: Rejected by author: 公司網路會阻擋 Google AI Studio API 呼叫，必須由手機透過行動網路完成語音辨識後再以藍牙近端傳送文字。

### Alternative B: 純藍牙 HID 鍵盤（Android `BluetoothHidDevice`，Windows 端零程式）

*   **Pros**: Windows 11 端完全不需執行任何腳本或程式，也不受 AppLocker / EDR 限制，可在 UAC 與登入畫面輸入。
*   **Cons**: 藍牙 HID 僅支援實體按鍵掃描碼（Scan Code），無法直接傳送繁體中文等 Unicode 字元；若改送注音鍵碼會破壞 Gemini 斷句與同音字準確率。
*   **Reason for Rejection**: Rejected by author: 無法可靠支援繁體中文與中英混打輸出。

### Alternative C: Windows 11 單檔 `.exe` 執行檔或內建 PowerShell（`.ps1`）腳本

*   **Pros**: 不依賴電腦預裝 Python（PowerShell 為 Windows 11 內建，`.exe` 則可完全自包含）。
*   **Cons**: 未簽章 `.exe` 易被公司 AppLocker / WDAC 阻擋；PowerShell 在受限語言模式（CLM）下會封鎖 `Add-Type` 呼叫 `SendInput`。
*   **Reason for Rejection**: Rejected by author: 公司電腦已預裝 Python，採用純標準函式庫 `.py` 純文字腳本可避免未簽章 `.exe` 與 PowerShell CLM 限制，且便於資安審查。

### Alternative D: 藍牙虛擬 COM 埠（`\\.\COMx`）或 Android 端擔任 RFCOMM Server

*   **Pros**: 虛擬 COM 埠可省去 Winsock SDP 註冊程式碼；Android 當 Server 則可在電腦端腳本啟動時自動搜尋手機連線。
*   **Cons**: 虛擬 COM 埠每次配對或換機都須手動進 Windows 藍牙進階設定配置埠號；Android 端常駐 RFCOMM Server 較耗電且易被系統背景休眠中斷。
*   **Reason for Rejection**: Rejected by author: 選擇由電腦端 Python 常駐等待連線，手機僅在開啟遙控畫面時主動連向電腦，免設 COM 埠且省電。

### Alternative E: 在現有鍵盤面板（IME）加入切換鈕並轉傳 2D 觸控板游標

*   **Pros**: 不需額外建立遙控畫面 Activity，且可兼作電腦觸控板。
*   **Cons**: 電腦端已有實體滑鼠與鍵盤，轉傳觸控板屬多餘複雜度；且 Android IME 必須在手機上有輸入框焦點時才會彈出，操作流程繁瑣。
*   **Reason for Rejection**: Rejected by author: 電腦已有滑鼠，且打開 App 專屬遙控畫面即可直接對電腦語音輸入，不需依賴手機輸入框。

--------------------------------------------------------------------------------

## Detailed Implementation Plan

### Target Files to Create/Modify

#### 1. `app/src/main/AndroidManifest.xml` (Modify)

*   **Type / Scope**: Android Manifest Configuration
*   **Proposed Changes**: 新增 `<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />`，並註冊 `.remote.RemoteDictationActivity`（`android:exported="false"`）。

#### 2. `app/src/main/java/com/akeyboard/audio/AudioCaptureManager.kt` (Modify)

*   **Type / Scope**: Kotlin Audio Capture Helper
*   **Proposed Changes**: 新增可選參數 `preferBuiltInMic: Boolean = false`；啟用時於 `AudioRecord` 透過 `AudioManager.getDevices(GET_DEVICES_INPUTS)` 尋找到 `TYPE_BUILTIN_MIC` 後呼叫 `setPreferredDevice(...)`，避免遠端口述期間被 Windows HFP/SCO 搶佔麥克風。

#### 3. `app/src/main/java/com/akeyboard/live/VoiceDictationSession.kt` (Create) & `app/src/main/java/com/akeyboard/ime/AKeyboardImeService.kt` (Modify)

*   **Type / Scope**: Kotlin Shared Voice Session Orchestrator
*   **Proposed Changes**:
    *   將 `AKeyboardImeService` 內的 `AudioCaptureManager` 擷取、`GeminiLiveClient` 預熱（`warmUp`）、`MAX_HOLD_DURATION_MS` 錄音上限與 `LiveEvent.Final` / `TurnComplete` / `finalizeTimeout()`（2000 ms 備援）抽出為 `VoiceDictationSession`，以 callback（`onInterim`、`onFinal`、`onError`）通知呼叫端。
    *   更新 `AKeyboardImeService` 改由 `VoiceDictationSession` 驅動，維持原有 IME 輸入與既有測試行為。

#### 4. `app/src/main/java/com/akeyboard/bridge/BluetoothBridgeClient.kt` (Create)

*   **Type / Scope**: Kotlin Android Bluetooth RFCOMM Client & Serialization
*   **Proposed Changes**:
    *   定義 `BRIDGE_UUID`、`BridgeKey`（`ENTER`、`BACKSPACE`）、`ConnectionState` 密封介面（`Disconnected`、`Connecting`、`Connected(deviceName)`、`Error(message)`），以及使用 `kotlinx.serialization.json`（`@Serializable`）的 `BridgeCommand` / `BridgeResponse` 模型。
    *   透過 `BluetoothManager.adapter`（禁止 `getDefaultAdapter()`）與 `@SuppressLint("MissingPermission")` 提供 `bondedDevices()`、`suspend fun connect(device)`、`suspend fun sendCommand(cmd): Result<Unit>`（寫入單行 JSON 後於 2 秒逾時內讀取並驗證 `{"ok":true}` 回執）與 `disconnect()`。

#### 5. `app/src/main/java/com/akeyboard/remote/RemoteDictationViewModel.kt` (Create)

*   **Type / Scope**: Kotlin ViewModel & Factory
*   **Proposed Changes**:
    *   提供 `RemoteDictationViewModel.Factory` 從 `AKeyboardApp` 注入 `okHttpClient`、`apiKeyStore` 與 `settingsRepository`。
    *   協調 `VoiceDictationSession` 與 `BluetoothBridgeClient`：未連線時仍允許 Push-to-Talk，轉錄結果直接存入（或以 `\n` 附加至）`pendingResendText`；已連線時透過 `sendCommand` 送出，僅於收到 `{"ok":true}` 後清除，失敗或收到 `uipi` 錯誤時保留並提示。
    *   於 `onCleared()` 自動釋放 `VoiceDictationSession` 與 `BluetoothBridgeClient` 連線。

#### 6. `app/src/main/java/com/akeyboard/remote/RemoteDictationActivity.kt` (Create)

*   **Type / Scope**: Kotlin Compose Material 3 Activity
*   **Proposed Changes**:
    *   使用 `enableEdgeToEdge()`（跟隨系統螢幕方向與休眠設定），於啟動時檢查並請求 `RECORD_AUDIO` 與 `BLUETOOTH_CONNECT` 權限。
    *   頂部顯示已配對電腦選單與連線狀態；中央顯示即時預覽文字、錯誤提示與 `pendingResendText`（含「重送」與「清除」按鈕）；底部配置 Push-to-Talk 麥克風按鈕及 `Backspace`、`Enter` 鍵。

#### 7. `app/src/main/java/com/akeyboard/settings/SettingsActivity.kt` (Modify)

*   **Type / Scope**: Kotlin Compose UI Entry Point
*   **Proposed Changes**: 在設定頁新增「開啟電腦藍牙遙控輸入」按鈕，點擊後以 `Intent` 啟動 `RemoteDictationActivity`。

#### 8. `app/src/main/res/values/strings.xml`, `values-zh-rTW/strings.xml`, `values-zh-rCN/strings.xml` (Modify)

*   **Type / Scope**: Android String Resources
*   **Proposed Changes**: 新增遙控畫面標題、藍牙權限提示、已配對裝置選單、連線狀態、UIPI / 斷線重送提示與按鈕文案之三語資源。

#### 9. `app/src/test/java/com/akeyboard/bridge/BluetoothBridgeModelTest.kt` (Create)

*   **Type / Scope**: Kotlin Unit Test
*   **Proposed Changes**: 驗證 `BridgeCommand`（繁體中文、換行與跳脫字元）與 `BridgeResponse`（`{"ok":true}` / `{"ok":false,"err":"uipi"}`）之 `kotlinx.serialization.json` 編解碼契約。

#### 10. `tools/akeyboard_bridge.py` (Create)

*   **Type / Scope**: Windows 11 Standalone Python 3.10+ Script (Stdlib Only)
*   **Proposed Changes**:
    *   保護模組層級 `ctypes.windll` 匯入以便單元測試；定義 `_pack_ = 1` 的 `SOCKADDR_BTH`（30 bytes）、`WSAQUERYSETW`、`CSADDR_INFO` 與包含 `MOUSEINPUT` 的 `INPUT` Union（x64 `sizeof(INPUT) == 40`）。
    *   使用 `socket.AF_BLUETOOTH` 綁定 `("00:00:00:00:00:00", 0)` 並呼叫 `WSASetServiceW(RNRSERVICE_REGISTER)` 註冊 `BRIDGE_UUID`（外層包覆 `OSError` 自動重建迴圈應對系統休眠喚醒）。
    *   以 `select.select` 監聽新連線並搶佔關閉舊 zombie socket；以 `bytes` 緩衝區依 `b"\n"` 切分並限制單行上限 64 KB。
    *   實作 `process_line(line: str, send_input_fn=...)`：`\r\n`/`\n` 轉為 `VK_RETURN`，其餘文字每批最多 32 個 UTF-16 碼元（不拆斷 Surrogate Pair）送入 `SendInput`，並依回傳事件數回寫 `{"ok":true}\n` 或 `{"ok":false,"err":"uipi"}\n`。

#### 11. `tools/test_akeyboard_bridge.py` (Create)

*   **Type / Scope**: Python `unittest` Suite (Stdlib Only)
*   **Proposed Changes**: 驗證 `ctypes.sizeof(INPUT) == 40`（於 64 位元 Windows）與 `sizeof(SOCKADDR_BTH) == 30`、分段批次不拆斷 Surrogate Pair、`\n` 轉 `VK_RETURN`、`SendInput` 回傳 `0` 時回報 `uipi` 錯誤、以及無效 JSON / 超長行容錯。

#### 12. `README.md` & `README_zh.md` (Modify)

*   **Type / Scope**: Project Documentation
*   **Proposed Changes**: 補充「Windows 11 藍牙語音遙控輸入」使用步驟與限制說明（需預裝官方 CPython 3.10+、不支援 UAC 提權視窗、企業 GPO 須允許藍牙 RFCOMM）。

### Verification Plan

*   **Step 0 — Corporate Bluetooth Pre-Flight Spike (Windows 11)**:
    *   在正式實作 Android UI 前，先於目標公司 Windows 11 電腦以最小 Python 腳本確認 `socket.AF_BLUETOOTH` bind 與 `WSASetServiceW` 未被公司 GPO 封鎖。
*   **Android Compilation & Lint**:
    *   `./gradlew :app:lintDebug`（須在 `warningsAsErrors = true` 下零錯誤通過）
*   **Android Unit Tests**:
    *   `./gradlew :app:testDebugUnitTest`（包含 `BluetoothBridgeModelTest` 與既有 9 項測試全數通過）
*   **Python Unit Tests (Windows 11)**:
    *   `python -m unittest tools/test_akeyboard_bridge.py`（所有測試案例全數通過）
*   **Manual E2E Integration (Pixel / Android 16+ ↔ Windows 11)**:
    1. 手機與 Windows 11 完成藍牙配對，電腦執行 `python tools/akeyboard_bridge.py` 確認無 SDP 註冊錯誤並進入等待連線。
    2. 手機從 `SettingsActivity` 開啟「電腦遙控輸入」並連上電腦，於 Windows 11 記事本 / Slack / 瀏覽器輸入繁體中文、中英混雜長句（含換行）與標點，確認游標處原樣輸出。
    3. 測試 `Enter`、`Backspace`、未連線先說話暫存、切換至系統管理員視窗觸發 `uipi` 錯誤提示保留文字，以及電腦休眠喚醒後的重連接管。

--------------------------------------------------------------------------------

*Drafted with EGM*
