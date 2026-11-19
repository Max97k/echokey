# AKeyboard 跨版本（Android 12–17 與 Windows 10–11）相容性與引導流程改進計畫

*   **Author**: Max97k
*   **Status**: Implemented
*   **Date**: 2026-11-19

--------------------------------------------------------------------------------

## Overview

本設計為現有 **AKeyboard（Android 純語音輸入法與 Windows 藍牙遙控系統）** 規劃跨版本相容性改進與操作引導流程修補。現有系統包含四個協作部分：
*   **Android 系統輸入法（`AKeyboardImeService` 與 `KeyboardScreen`）**：無傳統字元鍵盤，僅提供按住說話麥克風按鈕、2D 游標觸控板與右側三個輔助鍵（退格刪除、Enter／動作鍵、可選的切換輸入法鍵）。
*   **設定頁與本機儲存（`SettingsActivity`、`ApiKeyStore`、`SettingsRepository`）**：透過 Android Keystore 加密保存使用者的 Google AI Studio API Key，並選擇語音轉寫專用模型（`Transcribe`，支援語言代碼與自動濾除語氣詞／口語重複的「智慧去贅詞模式」）或一般對話模型（`Live`）。
*   **共用轉錄核心（`VoiceDictationSession` 與 `AudioCaptureManager`）**：負責 16 kHz PCM 錄音、即時預覽，以及放開按鈕後進入等待雲端最終斷句結果的 `Finalizing` 收尾階段。
*   **Windows 藍牙遙控端（`RemoteDictationActivity`、`BluetoothBridgeClient` 與 `tools/akeyboard_bridge.py`）**：將手機口述文字以單行 UTF-8 JSON 經藍牙 SPP（服務 UUID `8e7b3f20-4c6a-4d8e-9a1b-2c3d4e5f6a7b`，成功回執 `{"ok":true}`）送至 Windows 電腦游標處；當未連線或傳送失敗時，手機將文字累積於區域暫存區 `pendingResendText` 待補送。

本次計畫將 Android 安裝門檻由 Android 16（`minSdk = 36`）下修至 **Android 12（`minSdk = 31`）**，同步將目標 SDK 升至 **Android 17（`targetSdk = 37`，對齊既有的 `compileSdk = 37`）**，維持 Windows 端「單檔、零第三方套件、官方 CPython 3.10+」邊界，並修補藍牙斷線死鎖、跨執行緒清理競態與權限／藍牙引導斷點。

## The Problem

目前版本在 Android 16 實機與藍牙已配對開啟下的基礎單行語音輸入與正常封包送字可運行，但在跨版本裝置與異常路徑中存在四類明確缺陷：

1.  **Android 系統跨度（API 31–37）與 `targetSdk = 37` 行為落差**：
    *   `app/build.gradle.kts` 將 `minSdk` 設為 `36`，導致 Android 12–15（API 31–35）無法安裝；`AndroidManifest.xml` 使用 API 33+ 的 `enableOnBackInvokedCallback` 屬性而未標註相容保護，且深色模式啟動視窗主題、動態色彩與 Keystore 金鑰建立缺乏舊版 OEM 容錯保護。
    *   `BluetoothBridgeClient.kt` 目前在所有 API 版本皆因 `withTimeoutOrNull` 包裹阻塞式 `readLine()` 而在收到遠端 EOF（`null`）時誤報 `Ack timeout`，且在真正逾時時會鎖死整個 I/O 互斥鎖；升級至 `targetSdk = 37`（Android 17）後，平台強制 RFCOMM `InputStream.read()` 於遠端關閉時回傳 `-1`，同時取消大螢幕（`sw >= 600dp`）方向與縮放豁免，現有固定 `216.dp` 鍵盤與無寬度／高度保護的遙控畫面在窄高分屏下會遭壓縮裁切。
2.  **藍牙連線、離線選單與權限流程缺乏前置診斷與引導**：
    *   `BluetoothBridgeClient.bondedDevices()` 在權限缺失或藍牙關閉時只靜默回傳空集合；Android 在藍牙關閉（`STATE_OFF`）時系統亦會回傳空的已配對清單，導致裝置選單無法顯示目標電腦、「連線」按鈕變灰或嘗試連線後只丟出一般 `Bluetooth connect failed` 錯誤。
    *   `RemoteDictationActivity` 未於返回前景時重檢權限、缺乏永久拒絕時的「開啟應用程式設定」跳轉、缺 API Key 時無跳轉設定頁按鈕、麥克風按鈕缺乏壓按觸覺／脈衝／`Finalizing` 視覺狀態，且未監聽藍牙開關廣播與即時 RFCOMM 斷線，造成電腦腳本已關閉時畫面仍長期顯示已連線。
3.  **輸入法、音訊擷取與模型設定的執行期相容缺陷**：
    *   `AKeyboardImeService.kt` 未檢查 `EditorInfo.IME_FLAG_NO_ENTER_ACTION`，使設有該旗標的多行聊天室輸入框仍顯示傳送圖示並誤觸送出動作而非換行。
    *   錄音中途若因通話搶佔或系統音訊異常導致 `AudioRecord.read()` 失敗，擷取迴圈僅靜默結束而不回報錯誤；遙控模式寫死強制使用手機內建麥克風，且設定頁未區分 `Transcribe` 與 `Live` 模型對語言代碼／智慧去贅詞模式的支援差異。
4.  **Windows Python 橋接腳本（`tools/akeyboard_bridge.py`）的環境與生命週期缺陷**：
    *   在不含 `socket.AF_BLUETOOTH` 的非官方或精簡 Python 發行版啟動會直接拋出未捕捉的 `AttributeError`；電腦藍牙關閉或服務停用時每 2 秒重複印出警告而未分類原因；`WSASetServiceW` 註冊失敗時仍繼續監聽於手機無法探測的通道。
    *   單一客戶端異常斷線觸發的 `OSError` 會連帶拆毀並重建整個 `server_sock` 與 SDP，反之若在 client 迴圈捕捉寬泛 `OSError` 又會吞掉本機藍牙停用（`WSAENETDOWN`）錯誤；終端機視窗關閉時 `finally:` 來不及清除 SDP 紀錄；接收緩衝區在含換行的超長行路徑（>64 KB）會靜默忽略；`SendInput` 未填入虛擬鍵掃描碼且將部分寫入失敗一律誤標為 `uipi`。

## Goals

*   **G1 — 調整 Android SDK 邊界並通過 API 31／API 37 驗證矩陣**：在不新增舊版藍牙定位權限的前提下，將 `minSdk` 降至 `31`、將 `targetSdk` 升至 `37`（對齊 `compileSdk = 37`），補齊 Manifest 屬性保護、OEM 主題／Keystore 異常復原、RFCOMM EOF 與真逾時分離，以及 `sw >= 600dp`／分屏自適應排版，並通過 API 31 模擬器與 API 37 實機測試矩陣。
*   **G2 — 狀態導向的藍牙診斷、權限引導與遙控體驗修補**：在 `RemoteDictationActivity` 建立單一優先序診斷檢查（硬體缺失 → 權限缺漏 → 藍牙關閉 → 未配對電腦 → 未設 API Key），保留記住上次電腦與「未開藍牙時按連線即開啟藍牙並自動接續連線」體驗，補齊系統設定跳轉、背景即時斷線偵測、按行防超限重送，以及按壓觸覺／脈衝／`Finalizing` 視覺回饋；於 `SettingsActivity` 既有系統設定區塊內嵌「IME 啟用與設為預設」即時狀態。
*   **G3 — 修正 IME 換行、音訊中斷回報與模型／麥克風切換**：統一依 `IME_FLAG_NO_ENTER_ACTION` 連動 Enter 圖示與換行行為；將錄音中斷安全回報為 `MIC_BUSY`；區分 `Transcribe` 與 `Live` 模型能力提示；並於遙控頁提供切換使用「手機內建麥克風」或「系統預設／外接有線與 USB 耳麥」的開關。
*   **G4 — 強化 Windows Python 單檔腳本的啟動診斷、安全退出與輸入分類**：維持 Windows 10/11 官方 CPython 3.10+ 零第三方相依邊界，加入啟動環境與終端機 UTF-8 檢查、Winsock `winerror` 分類退避、主執行緒協調的視窗關閉 SDP 清理、對端斷線隔離、`line_too_long` 雙路徑回應、硬體掃描碼注入與無焦點／UIPI／部分失敗錯誤分類。

## Non-Goals

*   **NG1 — 不支援 Android 11（API 30）及更舊版本**：不引入需配合 `ACCESS_FINE_LOCATION` 的舊版藍牙權限模型。
*   **NG2 — 不將 Python 腳本擴展至 macOS 或 Linux**：維持 Windows 10/11 單檔標準庫實作，不打包 `.exe`。
*   **NG3 — 不改為 Wi-Fi、TCP/UDP 或雲端中繼**：藍牙遙控與 Windows 橋接嚴格維持僅開啟本機藍牙 RFCOMM，不開啟任何 TCP/UDP 連接埠。
*   **NG4 — 不繞過 Windows UIPI 或跨行程查詢行程 Token**：不以管理員或 `uiAccess` 執行腳本，亦不對其他視窗呼叫 `OpenProcess` / `OpenProcessToken`。
*   **NG5 — 不實作經典藍牙 HFP/SCO 耳麥收音路由**：不呼叫 `AudioManager.setCommunicationDevice()` 切換電話音訊模式；切換為「系統預設麥克風」僅涵蓋手機本機、有線或 USB 耳麥。

## Resolved Decisions

*   **D1 — 目標平台相容邊界**：Android 鎖定 `minSdk = 31` 至 `targetSdk = 37`（`compileSdk = 37`），Windows 鎖定 Windows 10/11 官方 CPython 3.10+ 零第三方套件。
*   **D2 — 藍牙關閉時保留一鍵接續連線體驗（方向 II）**：不自動在開啟遙控頁時背景連線；持久化記憶 `lastRemoteDeviceAddress` 與供藍牙關閉時離線顯示用的 `lastRemoteDeviceName`。只要已有記憶裝置，藍牙關閉時「連線」按鈕維持可點擊，且僅在主動點擊「連線」時寫入具短效期限的 `PendingConnect`（引導卡上的「開啟藍牙」按鈕單純開啟藍牙、不觸發自動連線）；開啟後立即檢查適配器狀態並於必要時等待 `STATE_ON` 或切回對帳，避免設定頁回退與事件競態。
*   **D3 — 遙控模式麥克風來源策略**：預設維持「手機內建麥克風優先」避開 Windows 藍牙免持干擾，並於遙控頁提供切換開關讓使用者改用系統預設（外接有線／USB 耳麥）。
*   **D4 — Windows 端 UIPI 偵測與失敗分類邊界**：為避免企業 EDR 告警風險，不跨行程呼叫 `OpenProcess` / `OpenProcessToken`，改以無跨行程句柄需求的 `GetForegroundWindow()` 偵測無焦點／鎖定畫面（`no_focus`），並依 `SendInput` 回傳計數直接區分全數阻擋（`sent == 0 -> uipi`）與部分寫入失敗（`0 < sent < expected -> send_failed`）。
*   **D5 — 跨執行緒斷線與控制台安全退出架構**：Android 端改用單一專責阻塞式背景讀取協程取代冗餘的 `ACTION_ACL_DISCONNECTED` 轉發與 `coroutineScope` 逾時死鎖；Windows 端使用 `select()` 短逾時搭配事件旗標由主執行緒執行唯一一次 SDP 清理，不建立任何 loopback TCP 喚醒埠。

## Critical User Journeys (CUJs)

### CUJ 1: 藍牙關閉、權限缺漏或未配對時的引導與一鍵接續連線

*   **Given**: 使用者開啟「電腦藍牙語音遙控」頁面，但手機缺少權限、藍牙處於關閉狀態、尚未配對電腦，或未設定 API Key。
*   **When**: 使用者進入頁面，或（若先前已連過某台電腦）在藍牙關閉狀態下直接點擊「連線」。
*   **Then**:
    *   畫面頂端依唯一阻塞優先序（無藍牙硬體 → 權限未授與 → 藍牙未開啟 → 尚未配對電腦 → 未設定 API Key）顯示單一張診斷卡與對應跳轉按鈕（授權／前往應用程式設定／開啟藍牙／開啟系統藍牙配對／前往設定頁）。
    *   若先前曾記住目標電腦，選單離線顯示該電腦名稱；使用者點擊「連線」時跳出系統詢問開啟藍牙對話框（若系統禁止則退回藍牙設定頁），切回 App 且系統確認藍牙開啟後自動接續發起連線（若使用者拒絕、取消或逾時未開啟則清楚提示並結束待連線狀態）。
    *   切回 App 時自動重檢權限、藍牙狀態與 API Key（已補齊則清除對應過時錯誤橫幅並預熱連線）。

### CUJ 2: Windows 端環境診斷、斷線隔離與安全關閉清理

*   **Given**: 使用者在 Windows 10/11 執行 `python tools/akeyboard_bridge.py`，可能遇上非官方缺藍牙 Python 環境、電腦藍牙關閉、手機暫時離線斷線，或直接點擊視窗右上角關閉按鈕。
*   **When**: 腳本啟動、手機於使用或待機期間中斷連線、或使用者關閉終端機視窗。
*   **Then**:
    *   若環境不支援或藍牙關閉，腳本立即輸出具體原因與設定指引（並於待機錯誤時以退避間隔等待而不高頻洗刷訊息）；手機端於閒置或送字期間皆可即時察覺 `akeyboard_bridge.py` 關閉並轉為未連線或顯示可辨識提示。
    *   手機端單純中斷連線時，Python 僅釋放該客戶端工作階段而不重建伺服器 Socket 與 SDP；使用者按 `Ctrl+C` 或關閉終端機視窗時，皆在系統關閉時限內執行單次乾淨 SDP 註銷與資源釋放。

### CUJ 3: Android 12–17 多視窗與多語音情境操作

*   **Given**: 使用者在 Android 12（API 31）至 Android 17（API 37）手機、平板或上下分屏視窗中使用 AKeyboard 輸入法或設定頁。
*   **When**: 使用者在設定頁檢視系統狀態與選擇模型，並於聊天室多行輸入框（設有 `IME_FLAG_NO_ENTER_ACTION`）口述或於高權限／鎖定電腦視窗重送文字。
*   **Then**:
    *   設定頁 section 1 顯示準確的輸入法啟用／預設狀態，模型選單與提示依目前選用之 `Transcribe` 或 `Live` 模型準確說明支援度（離線時亦能提示）。
    *   聊天室鍵盤按鈕正確顯示換行符號並插入換行；分屏／橫屏下鍵盤輔助鍵與遙控麥克風按鈕皆不重疊或遭裁切；錄音中若麥克風被搶佔會即時中止並顯示錯誤，且累積多行待重送文字可逐行送出不卡死在 64 KB 上限。

## Technical Plan (Architecture)

本架構圍繞「**Android 跨版本與防呆 UI 層**」、「**單一讀取協程的藍牙用戶端**」、「**共用音訊與 IME 核心**」及「**故障隔離的 Windows Python 接收端**」四個邊界展開。

```text
[Android 12-17 (minSdk 31, targetSdk 37)]
+-------------------------------------------------------------------------+
| RemoteDictationActivity / SettingsActivity (Adaptive Compose Layout)    |
|  +-- ViewModel Derived Blocker & Readiness (Permissions / BT / Key)     |
|  +-- PendingConnect Reconciliation (ActivityResult + STATE_ON + Resume) |
+------------------------------------+------------------------------------+
                                     |
+------------------------------------v------------------------------------+
| VoiceDictationSession + AudioCaptureManager + AKeyboardImeService       |
|  +-- Atomic stopping guard + Main-scope error reset (MIC_BUSY)          |
|  +-- Dynamic mic provider & IME_FLAG_NO_ENTER_ACTION icon/key sync      |
+------------------------------------+------------------------------------+
                                     |
+------------------------------------v------------------------------------+
| BluetoothBridgeClient (Owns BT Broadcasts & Single Reader Coroutine)    |
|  +-- Pure evaluateReadiness() & cancellable connectingSocket            |
|  +-- Writer Mutex + 1 pendingAck Deferred + Timeout socket-close guard  |
+------------------------------------+------------------------------------+
                                     | BT SPP (Newline UTF-8 JSON)
                                     v
[Windows 10/11 (Official CPython 3.10+, Stdlib Only, Zero TCP/UDP)]
+-------------------------------------------------------------------------+
| tools/akeyboard_bridge.py                                               |
|  +-- Startup Env & UTF-8 Check + WinError Backoff + SDP Retry Guard     |
|  +-- Console Close Handler (stop_event + cleanup_done, select timeout)  |
|  +-- Peer-Only Client Isolation + ScanCode + Focus/UIPI Classification  |
+-------------------------------------------------------------------------+
```

### 1. Android 跨版本相容性與自適應邊界（API 31–37）

*   **建置與系統行為對齊**：將應用程式最低支援對齊 Android 12（免除定位權限需求），目標 SDK 升級至 Android 17，補齊舊版屬性與圖示相容宣告；為深色模式提供無額外相依的原生啟動視窗主題，並於動態取色與 Keystore 金鑰操作加上可復原的例外備援。
*   **大螢幕與分屏防裁切**：配合 Android 17 強制可縮放規範，為設定頁與遙控頁套用統一最大寬度置中容器與系統安全邊距合併；鍵盤面板依據視窗高度動態調整整體高度與輔助按鈕／麥克風尺寸，防止窄矮畫面下的元件溢位或提示重疊。

### 2. 藍牙連線架構與遙控引導狀態機

*   **單一背景讀取協程消除死鎖與閒置盲區**：放棄原本「寫入後才在 `coroutineScope` 內阻塞讀取」以及依賴 `ACTION_ACL_DISCONNECTED` 的設計，改由 `BluetoothBridgeClient` 在連線成功後啟動單一阻塞式背景讀取協程，配合寫入互斥鎖與單筆待回覆信號完成命令往返。當遠端正常關閉（EOF）、逾時強制關閉通訊端或發生 I/O 例外時，讀取協程立即清理連線狀態，使閒置斷線偵測與送字逾時皆不卡死；同時將藍牙狀態／配對廣播監聽內聚於 `BluetoothBridgeClient`，並抽出純函式評估就緒狀態以利單元測試。
*   **方向 II「記憶電腦與按需接續連線」協調**：`SettingsRepository` 持久化最近目標電腦的位址與供藍牙關閉時離線呈現的名稱；ViewModel 統一收斂裝置選取、單一優先序阻塞卡片（含硬體缺失與永久拒權重導至應用設定），並將「開啟藍牙引導鈕」與「點擊連線建立 `PendingConnect`」嚴格分離。無論開啟結果來自系統彈窗即時完成、事件廣播或從系統設定頁切回重檢，皆透過同一套對帳邏輯判斷是否立即發起連線或逾時結束。
*   **按行重送與型別化錯誤**：`RemoteDictationViewModel` 使用型別化錯誤列舉取代原始錯誤字串，並在重送長段累積文字時改採逐行發送與逐段移除前綴，避免觸及單行協定上限或長時等待重複輸入。

### 3. IME 輸入法、音訊與設定頁精簡整合

*   **IME 動作同步與音訊錯誤隔離**：`AKeyboardImeService` 將 `IME_FLAG_NO_ENTER_ACTION` 的檢查納入輸入視窗初始化與 Enter 鍵處理，確保鍵盤圖示與實際換行行為一致。`AudioCaptureManager` 透過提供函式讀取偏好路由（避免為遷就單一測試替身而扭曲生產介面），加上停止旗標防止正常釋放麥克風時誤觸發中斷錯誤，並在真正硬體讀取失敗時切回主協程執行完整的連線與狀態重置。
*   **設定頁精簡就緒檢查與離線模型提示**：不重複在 `SettingsActivity` 頂端建立與現有區塊重疊的摘要卡片，亦不納入無關的藍牙權限檢查，僅在現有系統設定區塊內嵌「輸入法已啟用」與「設為預設輸入法」唯讀狀態（採 API 31+ 相容方式查詢並於視窗焦點回復時刷新）。模型標籤與離線選擇提示統一區分 `Transcribe` 與 `Live` 模型能力差異。

### 4. Windows 10/11 Python 橋接腳本故障隔離與安全退出

*   **無 TCP 埠的跨執行緒安全退出與錯誤域分段**：將 Windows 控制台事件處理常式回呼固定於模組全域以避免記憶體回收崩潰，利用 `select()` 短逾時與雙事件協調機制交由主執行緒在統一清理區塊註銷 SDP 紀錄，完全維持零 TCP/UDP 埠邊界。內層接收迴圈僅隔離對端中斷錯誤，將本機藍牙配接器停用等堆疊錯誤正常交由外層退避重建與診斷提示處理，同時修補超長行緩衝區在含換行符分支的靜默漏失。
*   **輸入相容與最小測試縫**：所有 Win32/Winsock 動態連結庫呼叫皆明確宣告型別簽章；送字前先以輕量焦點檢查辨識鎖定或無焦點視窗，再依虛擬鍵補入硬體掃描碼與 `SendInput` 實際送出數簡明分類為完全阻擋或部分寫入失敗，不觸碰任何跨行程 Token API。

## Alternatives Considered

### Alternative A1: 維持 `minSdk = 36`（僅支援 Android 16+）

*   **Pros**: 不需考慮 Android 12–15 的動態色彩、Manifest 屬性與輸入法查詢 API 差異。
*   **Cons**: 直接排除目前仍廣泛使用的 Android 12–15 裝置，與擴展相容性的核心需求相悖。
*   **Reason for Rejection**: 以少量版本保護即可安全覆蓋 Android 12–15，放棄這批裝置的機會成本過高。

### Alternative A2: 下修至 `minSdk <= 30`（Android 11 以下）並將 Python 腳本擴展至 macOS / Linux

*   **Pros**: 涵蓋最舊世代 Android 手機與非 Windows 桌面作業系統。
*   **Cons**: Android 11 以下需引入 `ACCESS_FINE_LOCATION` 才能探索藍牙裝置，會加重商店隱私審查負擔；macOS 輔助使用授權鎖定與 Linux Wayland 全域輸入限制亦無法維持單檔零第三方套件的可攜接收端。
*   **Reason for Rejection**: 優先守住最小權限與零依賴單檔部署邊界，因此將範圍鎖定在 Android 12–17 與 Windows 10/11。

### Alternative B1: 進入遙控頁即自動背景嘗試重連

*   **Pros**: 電腦橋接腳本恰好已啟動時，使用者打開畫面即可免點擊連線。
*   **Cons**: 若電腦尚未啟動腳本或使用者僅想在通勤途中先離線語音暫存，每次開頁都會先經歷數秒藍牙探測等待與連線失敗錯誤。
*   **Reason for Rejection**: 避免未請求連線即干擾離線使用流程，改為保留手動點擊觸發或藍牙關閉下的按需接續。

### Alternative B2: 藍牙關閉時強制停用「連線」鈕的純兩步手動解鎖

*   **Pros**: 不需離線記憶目標名稱或協調跨彈窗與廣播狀態，實作複雜度最低。
*   **Cons**: 當使用者忘記先開藍牙便想直接連線時，必須先按「開啟藍牙」、允許系統彈窗、再額外按一次「連線」。
*   **Reason for Rejection**: 為貼近一氣呵成的藍牙啟用體驗，決定承擔離線名稱快取與 `PendingConnect` 對帳邏輯來保留一鍵接續流程。

### Alternative C1: 遙控模式繼續寫死強制手機內建麥克風，或全面改採 `setCommunicationDevice()` 支援經典藍牙耳麥

*   **Pros**: 強制內建麥克風實作零介面更動；導入 `setCommunicationDevice()` 理論上能涵蓋經典藍牙 HFP 耳麥。
*   **Cons**: 寫死內建會阻斷有線／USB 耳麥收音；而維護通話音訊路由模式極易與系統音訊焦點及雙裝置 HFP 產生不穩定與音質劣化。
*   **Reason for Rejection**: 採取可控的「系統預設／外接有線與 USB 耳麥」切換開關，同時明確排除不穩定的經典藍牙 HFP/SCO 路由。

### Alternative D1: 跨行程呼叫 `OpenProcess` / `OpenProcessToken` 事前檢查 UIPI 權限等級

*   **Pros**: 能在注入任何字元前先判斷前景視窗是否為高權限管理員視窗。
*   **Cons**: 在受嚴格 EDR / DLP 管控的公司電腦上，從一般權限 Python 行程跨行程讀取其他視窗行程的存取權杖容易觸發資安告警或遭到拒絕。
*   **Reason for Rejection**: 企業環境誤報風險高於收益，維持僅在送字當下使用無跨行程句柄需求的焦點檢查與 `SendInput` 回傳計數判斷。

### Alternative D2: 在單次 `SendInput` 失敗回應中回傳已注入偏移並於手機端自動裁切重送

*   **Pros**: 嘗試避免長句半途被切換視窗打斷後重複輸出前半段。
*   **Cons**: `SendInput` 回傳值僅代表事件已進入 Windows 作業系統佇列，不保證目標應用程式已實際繪製或消費完成，依偏移裁切有永久遺失交界文字的風險，且須擴充雙向協定。
*   **Reason for Rejection**: 為維持文字不靜默遺失邊界，單筆指令失敗仍保留完整句子，改由傳送端按換行分段降低單批失敗衝擊。

### Alternative E1: 呼叫 `BluetoothApis.dll` 事前探測無線電狀態

*   **Pros**: 在建立 Winsock Socket 前即可列舉無線電硬體。
*   **Cons**: 引入額外 Win32 DLL 與 64 位元句柄生命週期管理風險，且 Winsock 錯誤碼已能明確區分藍牙關閉、缺少硬體與權限封鎖。
*   **Reason for Rejection**: 沿用既有 Winsock 錯誤碼分類即可達成同樣診斷效果且無額外句柄洩漏或截斷風險。

### Alternative E2: 將 `bind` 連接埠由 `0xFFFFFFFF` 改回 `0`（對應先前的 [windows_bluetooth_voice_bridge.md](docs/designs/windows_bluetooth_voice_bridge.md) 文件描述）

*   **Pros**: 字面與舊設計文件記錄一致。
*   **Cons**: 經本機 Windows 11 CPython 3.12.10 實測，`bind(("00:00:00:00:00:00", 0))` 只會停在無效的 port `0`，必須傳入 `BT_PORT_ANY`（`0xFFFFFFFF`）才會由系統實際配發可用的 RFCOMM 通道。
*   **Reason for Rejection**: 實測驗證 `0xFFFFFFFF` 為必要參數，保留現有實作並加上 `port > 0` 校驗。

### Alternative F: `AudioCaptureManager` 改以 `Channel<AudioEvent>` 傳遞音訊幀與擷取錯誤

*   **Pros**: 單一密封通道型別統一資料流與錯誤事件。
*   **Cons**: 須重寫 `AudioCaptureResult.Started`、`GeminiLiveClient.startUtterance` 與既有模擬測試替身，為罕見的中斷錯誤大幅更動熱路徑封裝。
*   **Reason for Rejection**: 維持既有 `Channel<ByteArray>` 零包裝熱路徑，透過受停止旗標保護的 `onCaptureError` 回呼切回主協程處理即可。

### Alternative G: 擴充 `ApiKeyStore.load()` 回傳密封結果型別至整個轉錄鏈路

*   **Pros**: 當金鑰解密損毀被清除時能即時傳播「金鑰已損毀」原因給語音轉錄端。
*   **Cons**: 須連動修改 `VoiceDictationSession`、`AKeyboardImeService` 與 `RemoteDictationViewModel` 的載入契約，但使用者最終仍只能回到設定頁重新輸入金鑰。
*   **Reason for Rejection**: 維持 `load(): String?` 簡潔契約僅共用金鑰刪除輔助避免殘留損毀別名；僅在使用者主動按儲存的 `save()` 失敗時呈現明確 `StorageError`。

### Alternative H: 在 Python 端開啟 TCP Loopback Wakeup Socket 喚醒無逾時 `select()`

*   **Pros**: 控制台關閉時可立即微秒級喚醒阻塞中的 `select()`。
*   **Cons**: 違反 [windows_bluetooth_voice_bridge.md](docs/designs/windows_bluetooth_voice_bridge.md) 建立的「不開啟任何 TCP/UDP 網路埠」資安隔離設計，並可能在受管電腦觸發本機防火牆彈窗。
*   **Reason for Rejection**: 採用 0.5 秒 `select()` 逾時與事件旗標即可在 Windows 5 秒寬限期內乾淨退出且維持零網路埠。

## Non-Functional Requirements

*   **Security & Privacy**:
    *   不引入 `ACCESS_FINE_LOCATION` 或舊版藍牙權限；所有藍牙狀態廣播接收器僅向系統註冊為非匯出接收器。
    *   Windows 端嚴格禁止開啟任何 TCP/UDP 埠與跨行程呼叫 `OpenProcess` / `OpenProcessToken`，不記錄任何口述明文日誌並維持 64 KB 單行上限。
*   **Compatibility & Build Quality**:
    *   所有 Win32/Winsock `ctypes` 宣告皆須顯式指定 `.argtypes` 與 `.restype`，並於模組全域保存回呼函式參考防止 GC 回收存取違規。
    *   維持 Gradle `:app:lintDebug` 在 `warningsAsErrors = true`（移除 `OldTargetApi` 後）零警告／零錯誤通過；使用原生夜間主題資源消除深色模式閃白，不新增 `appcompat` 等 XML 依賴。
*   **Performance & Responsiveness**:
    *   Android 端背景藍牙讀取協程由核心態阻塞等待 I/O（閒置不消耗輪詢 CPU）；Windows 端在藍牙停用或註冊失敗退避期間以休眠等待取代忙等輪詢。

## Risks & Mitigations

*   **Windows UIPI 靜默吞鍵假陰性風險**：部分高權限管理員視窗或受保護介面在被 `SendInput` 注入時，Windows 可能回傳全數送出事件數（導致腳本回傳 `{"ok":true}`）卻實際在視窗佇列丟棄按鍵。**緩解**：屬選擇不提權與不跨行程檢查權杖權限（D4）下的已知系統限制，於文件與 FAQ 明確說明若切換一般視窗仍無文字應自行切換焦點重試。
*   **經典藍牙 HFP/SCO 耳麥不支援風險**：切換為「系統預設麥克風」後，若使用者配戴經典藍牙免持耳機未進入通話模式，Android 仍會使用手機內建麥克風收音。**緩解**：介面文字精確標示為「系統預設（外接有線／USB 耳麥）」，避免誤導支援藍牙耳機通話收音。
*   **Windows 控制台強制結束 5 秒上限與非法殺行程**：透過工作管理員結束行程、斷電或系統未等待滿時限強制結束時，仍可能短暫殘留舊 SDP 紀錄。**緩解**：在一般關閉事件以 `0.5s` `select()` 配合最長 `3.5s` 同步等待於時限內主動註銷 SDP；下次腳本啟動註冊時亦會重新覆寫對應服務。
*   **Keystore 金鑰損毀清除後須重新輸入**：系統升級或鎖屏原則變更造成 Keystore 金鑰無法解密時，`load()` 會刪除舊密文並清除損毀別名。**緩解**：重設後確保下一次 `save()` 不再卡在壞金鑰上，並由設定頁與遙控頁清楚引導使用者重新填入與驗證 API Key。
*   **Android 12–15 與多廠牌 OEM 實機覆蓋限制**：若缺乏多款中間版本（API 32–35）與客製化 ROM 實機，動態色彩或特殊藍牙實作行為僅能透過防禦式例外回退與模擬器驗證。**緩解**：在代碼加入無害回退並於下述驗證矩陣明訂 API 31 與 API 37 雙端基準測試。

--------------------------------------------------------------------------------

## Detailed Implementation Plan

實作依相依順序劃分為四個獨立可建置與驗證的階段，每階段結束須通過對應檢查點方可進入下一階段。

### Phase 1: Windows 10/11 Python 橋接與單元測試

#### 1. `tools/akeyboard_bridge.py` (Modify)

*   **Type / Scope**: Windows 10/11 Standalone Python Bridge (Stdlib Only)
*   **Proposed Changes**:
    *   **環境預檢與終端機編碼**：新增可注入參數的 `check_environment(platform=sys.platform, version_info=sys.version_info, has_af_bluetooth=..., has_btproto_rfcomm=...) -> tuple[bool, str]`，檢查 `win32`、Python `>= 3.10` 及藍牙 Socket 支援；於啟動時呼叫支援 `reconfigure(encoding="utf-8", errors="replace")` 的標準輸出入，未通過預檢時印出指引並回傳非零退出碼 `1`。
    *   **Win32 `ctypes` 型別與控制台退出協調**：為 `user32`（`SendInput`、`MapVirtualKeyW`、`GetForegroundWindow`）、`ws2_32`（`WSASetServiceW`、`WSAGetLastError`）與 `kernel32`（`SetConsoleCtrlHandler`）全面顯式宣告 `.argtypes` 與 `.restype`（特別是 64 位元 `HANDLE`／指標）。於模組層級保存 `WINFUNCTYPE` 控制處理常式強參考：`CTRL_C_EVENT` 回傳 `False` 交給 `KeyboardInterrupt`，`CTRL_CLOSE_EVENT` / `CTRL_LOGOFF_EVENT` / `CTRL_SHUTDOWN_EVENT` 設定 `stop_event` 並呼叫 `cleanup_done.wait(3.5)`；主迴圈使用 `select.select(watch, [], [], 0.5)`，退出後於單一 `finally:` 完成唯一一次 `RNRSERVICE_DELETE` 與 Socket 關閉，最後設定 `cleanup_done`。
    *   **Winsock 錯誤診斷、SDP 重試與客戶端隔離**：維持 `BT_PORT_ANY = 0xFFFFFFFF` 綁定並校驗 `port > 0`；若 `WSASetServiceW` 註冊失敗則警告手機端無法依 `BRIDGE_UUID` 探測，關閉 `server_sock` 並於外層採退避重試（`2s → 5s → 10s`，成功進入 `accept` 就緒後重置退避，同一連續錯誤只印一次提示）；外層依 `winerror`（`10047`、`10050`、`10049`、`10013`）印出對應藍牙關閉或政策封鎖修復提示。內層 Client 迴圈僅捕捉對端斷線錯誤（`ConnectionResetError`、`ConnectionAbortedError`、`BrokenPipeError`、Windows `10054` / `10053` / `10058` / `10060`）並只關閉 `client_sock`；讓 `WSAENETDOWN`（`10050`）等本機堆疊錯誤正常向上拋至外層。抽離 `_handle_client_data(client_sock, rx_buffer, process_fn)` 輔助函式，使原本 L345 與 L355 兩處超過 `MAX_LINE_BYTES`（含無 `\n` 與含 `\n` 分支）皆先嘗試回傳 `{"ok":false,"err":"line_too_long"}\n` 再關閉客戶端。
    *   **焦點／掃描碼與簡化 `SendInput` 錯誤分類**：封裝預設可注入的 Win32 注入介面（預設實作於 `_user32 is None` 時安全退回），為 `build_virtual_key_inputs(vk, map_vk_fn=...)` 透過 `MapVirtualKeyW(vk, 0)` 填入硬體掃描碼 `wScan`。將 `send_virtual_key` 與 `send_unicode_text` 改為回傳錯誤字串 `str | None`（成功回 `None`、全阻擋 `sent == 0` 回 `"uipi"`、部分寫入 `0 < sent < len(inputs)` 回 `"send_failed"`，不額外使用 `get_last_error_fn`）。`process_line(..., send_input_fn=..., has_focus_fn=..., map_vk_fn=...)` 必須先完成 JSON 解析與欄位驗證後再檢查 `has_focus_fn()`（預設 `GetForegroundWindow() != 0`，無焦點則回 `{"ok":false,"err":"no_focus"}`）。
*   **Rationale**: 消除非標準 Python 閃退、視窗關閉殘留 SDP、藍牙停用卡死與超長行靜默漏包，同時維持零 TCP 埠、零跨行程存取與零第三方套件邊界。

#### 2. `tools/test_akeyboard_bridge.py` (Modify)

*   **Type / Scope**: Python `unittest` Suite
*   **Proposed Changes**: 更新既有 `test_process_line_unicode_and_newline`、`test_process_line_keys_and_uipi_failure` 與 `test_process_line_invalid_inputs` 傳入 `has_focus_fn=lambda: True` 及 `map_vk_fn=lambda vk: 0x1C`；新增測試驗證 `wScan` 填入、`has_focus_fn=lambda: False` 回傳 `no_focus`、部分寫入（`0 < sent < len`）回傳 `send_failed`、無效 JSON 優先於焦點檢查、`check_environment` 異常診斷、`_handle_client_data` 於含換行／無換行兩種超長行均回傳 `line_too_long`，以及對端斷線隔離與本機 `OSError` 上拋行為。
*   **Rationale**: 避免既有案例在背景／無頭環境下因預設焦點檢查失敗，並完整驗證新增之隔離與錯誤分類邏輯。

*   **Phase 1 Checkpoint**: 執行 `python -m unittest tools/test_akeyboard_bridge.py`，既有 5 項與新增測試全數通過。

---

### Phase 2: Android SDK 跨版本、資料與共用輔助、音訊與 IME 核心

#### 3. `app/build.gradle.kts` (Modify)

*   **Type / Scope**: Gradle Build & Lint Configuration
*   **Proposed Changes**: 將 `defaultConfig.minSdk` 改為 `31`、將 `defaultConfig.targetSdk` 改為 `37`（對齊既有 `compileSdk = 37`）；從 `lint.disable` 移除 `"OldTargetApi"` 並更新註解。
*   **Rationale**: 開放 Android 12–17（API 31–37）安裝並啟用 API 37 平台行為，移除過時目標 API 的 Lint 抑制。

#### 4. `app/src/main/AndroidManifest.xml` (Modify)

*   **Type / Scope**: Android Manifest Configuration
*   **Proposed Changes**: 在 `<manifest>` 加入 `xmlns:tools="http://schemas.android.com/tools"`，並於 `<application>` 為 `android:enableOnBackInvokedCallback="true"` 加上 `tools:targetApi="33"`；建置時一併確認 `app/src/main/res/mipmap-anydpi/ic_launcher.xml` 與 `ic_launcher_round.xml` 的 `<monochrome>` 標籤通過 API 31 Lint（若 Lint 報錯則補上對應 `tools:targetApi`）。
*   **Rationale**: 防止 `minSdk` 降至 `31` 後在 `warningsAsErrors = true` 下因 API 33 屬性觸發 Lint 失敗。

#### 5. `app/src/main/res/values-night/themes.xml` (Create) & `app/src/main/java/com/akeyboard/ui/Theme.kt` (Modify)

*   **Type / Scope**: Android Night Theme & Compose Material 3 Theme
*   **Proposed Changes**: 新增 `values-night/themes.xml` 宣告 `<style name="Theme.AKeyboard" parent="android:Theme.Material.NoActionBar" />`（不使用 `appcompat`）；在 `Theme.kt` 將 `dynamicDarkColorScheme(context)` / `dynamicLightColorScheme(context)` 包覆 `runCatching`，失敗時回退 `darkColorScheme()` / `lightColorScheme()`。
*   **Rationale**: 消除深色模式下啟動 Activity 短暫閃白，並防止客製 ROM 缺乏動態壁紙色彩服務時崩潰。

#### 6. `app/src/main/java/com/akeyboard/util/SystemChecks.kt` (Create)

*   **Type / Scope**: Shared Context Extension Helpers
*   **Proposed Changes**: 抽出 `Context.hasPermission(permission: String): Boolean` 與 `Context.isNetworkAvailable(): Boolean`，取代分散在 `AKeyboardImeService.kt` 與 `RemoteDictationViewModel.kt` 的重複實作，亦供 Activity 使用。
*   **Rationale**: 消除跨模組重複的權限與網路狀態檢查邏輯。

#### 7. `app/src/main/java/com/akeyboard/data/SettingsRepository.kt` (Modify)

*   **Type / Scope**: DataStore Preferences Repository
*   **Proposed Changes**: 在 `UserSettings` 新增 `lastRemoteDeviceAddress: String? = null`、`lastRemoteDeviceName: String? = null`（僅作為藍牙關閉時離線下拉選單顯示備援）與 `remotePreferBuiltInMic: Boolean = true`；新增 `setLastRemoteDevice(address: String?, name: String?)`（當引數為 `null` 時呼叫 `prefs.remove(key)`）與 `setRemotePreferBuiltInMic(preferBuiltIn: Boolean)`。
*   **Rationale**: 持久化遙控目標電腦（支援方向 II 離線顯示）與麥克風路由偏好，同時確保正確清除 DataStore 空值。

#### 8. `app/src/main/java/com/akeyboard/data/ApiKeyStore.kt` (Modify)

*   **Type / Scope**: Android Keystore Encryption Store
*   **Proposed Changes**: 抽出共用的私有 `deleteSecretKey()` 清理 `AndroidKeyStore` 中之 `keyAlias`。在 `load(): String?` 遇到 `GeneralSecurityException` 時呼叫 `deleteSecretKey()` 與 `clear()`（維持 `String?` 回傳契約）；在 `save(apiKey)` 若首次取得金鑰或 `Cipher.init` 拋出 `GeneralSecurityException`，先呼叫 `deleteSecretKey()` 後重試生成並加密一次，若重試仍失敗則將 `GeneralSecurityException` / `IOException` 直接向上拋出。
*   **Rationale**: 清理損毀的 Keystore 別名以恢復後續寫入，並在寫入仍失敗時將具型別例外交給設定頁提示。

#### 9. `app/src/main/java/com/akeyboard/audio/AudioCaptureManager.kt` (Modify) & `app/src/main/java/com/akeyboard/live/VoiceDictationSession.kt` (Modify)

*   **Type / Scope**: Audio Capture & Shared Dictation Session
*   **Proposed Changes**:
    *   `AudioCaptureManager` 建構參數新增 `private val preferBuiltInMicProvider: () -> Boolean = { preferBuiltInMic }` 與 `var onCaptureError: ((AudioFailureReason) -> Unit)? = null`，更新 `open fun start(scope: CoroutineScope): AudioCaptureResult` 內部每次啟動時透過 `preferBuiltInMicProvider()` 判斷是否綁定 `TYPE_BUILTIN_MIC`（不改動 `start(scope)` 單參數簽章，避免與現有 `VoiceDictationSessionTest` 測試替身衝突，亦不增加無用 mutator 鏈）。加入 `private val isStopping = AtomicBoolean(false)`（於 `start()` 設 `false`、`stop()` 設 `true`）；當背景 `Dispatchers.IO` 讀取迴圈遇到 `ERROR_INVALID_OPERATION`、`ERROR_DEAD_OBJECT` 或 `ERROR_BAD_VALUE` 且 `!isStopping.get()` 時，觸發 `onCaptureError?.invoke(AudioFailureReason.MIC_UNAVAILABLE)`。
    *   `VoiceDictationSession` 初始化時註冊 `audioManager.onCaptureError`，收到通知後透過 `scope.launch` 切回主調度器檢查 `phase == VoiceSessionPhase.RECORDING`，隨即取消 `maxDurationJob` / `finalizeJob`、呼叫 `audioManager.stop()` 與 `liveClient.close()`、若有未提交的 `runningComposingText` 則呼叫 `onFinishComposing()` 並清空緩衝、重設 `hasCommittedCurrentTurn = false`、設定 `phase = VoiceSessionPhase.IDLE`，最後發送 `onError(VoiceSessionError.MIC_BUSY)`。
*   **Rationale**: 以動態提供函式與停止旗標徹底根除執行緒競態、正常放開誤報 `MIC_BUSY` 及多層設定推送冗餘。

#### 10. `app/src/main/java/com/akeyboard/ime/AKeyboardImeService.kt` (Modify) & `app/src/main/java/com/akeyboard/ui/KeyboardScreen.kt` (Modify)

*   **Type / Scope**: IME Service & Adaptive Keyboard Panel
*   **Proposed Changes**:
    *   在 `AKeyboardImeService` 改用 `SystemChecks` 並抽出純伴生物件函式 `resolveEffectiveImeAction(imeOptions: Int): Int`：若 `(imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0` 則回傳 `EditorInfo.IME_ACTION_NONE`，否則回傳 `imeOptions and EditorInfo.IME_MASK_ACTION`。於 `onStartInputView()` 使用該函式更新 `_imeAction.value`，並於 `performEnter()` 先檢查 `((info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0` 才執行 `performEditorAction(action)`。
    *   在 `KeyboardScreen.kt` 依 `LocalConfiguration.current.screenHeightDp` 計算外層高度 `panelHeight = (screenHeightDp * 0.34f).dp.coerceIn(168.dp, 216.dp)`：當 `panelHeight < 196.dp` 時將右側按鈕切換為緊湊尺寸（`auxBtnSize = if (showSwitchIme) 34.dp else 44.dp`、`auxSpacing = if (showSwitchIme) 4.dp else 8.dp`、麥克風尺寸 `68.dp` 並隱藏頂部 `touchpad_hint`），同時套用 `navigationBarsPadding()` 與 `windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))`；當 `StatusBar` 顯示可操作錯誤時，整行文字加 `.clickable(onClick = onOpenSettings)`。
*   **Rationale**: 使聊天室換行鍵圖示與行為同步，並保證緊湊高度下三鍵區與脈衝麥克風零溢位裁切。

#### 11. Phase 2 Unit Tests

*   **Type / Scope**: Android JUnit Unit Tests
*   **Proposed Changes**:
    *   `app/src/test/java/com/akeyboard/data/SettingsRepositoryTest.kt` (Create)：以暫存 `PreferenceDataStoreFactory` 驗證 `lastRemoteDeviceAddress`、`lastRemoteDeviceName`、`remotePreferBuiltInMic` 之預設值、更新與傳入 `null` 時正確移除鍵值。
    *   `app/src/test/java/com/akeyboard/live/VoiceDictationSessionTest.kt` (Modify)：新增測試驗證錄音期間觸發 `audioManager.onCaptureError?.invoke(AudioFailureReason.MIC_UNAVAILABLE)` 時會關閉 `liveClient`、清空緩衝回到 `IDLE`（後續可重新按住說話）並發送 `VoiceSessionError.MIC_BUSY`。
    *   `app/src/test/java/com/akeyboard/ime/ImeActionResolverTest.kt` (Create)：測試 `AKeyboardImeService.resolveEffectiveImeAction` 於 `IME_ACTION_SEND` 搭配與未搭配 `IME_FLAG_NO_ENTER_ACTION` 之回傳值。

*   **Phase 2 Checkpoint**: `./gradlew :app:testDebugUnitTest :app:lintDebug` 通過，且在 Android 12（API 31）與 Android 17（API 37）模擬器建置無編譯或 Manifest 錯誤。

---

### Phase 3: 藍牙橋接、遙控與設定畫面與三語資源

#### 12. `app/src/main/java/com/akeyboard/bridge/BluetoothBridgeClient.kt` (Modify)

*   **Type / Scope**: Android Bluetooth RFCOMM Client
*   **Proposed Changes**:
    *   定義 `enum class BridgeDeliveryError { UIPI, NO_FOCUS, SEND_FAILED, PC_DISCONNECTED }`、`BridgeDeliveryException(val error: BridgeDeliveryError, message: String)`，以及 `sealed interface BluetoothReadiness`（`NoHardware`、`PermissionMissing`、`BluetoothDisabled(val turningOn: Boolean)`、`NoPairedDevices`、`Ready(val devices: List<BluetoothDevice>)`）。抽出純函式 `evaluateReadiness(hasHardware: Boolean, hasPermission: Boolean, adapterState: Int, bondedDevices: List<BluetoothDevice>): BluetoothReadiness` 供 JVM 單元測試，由 `readiness()` 呼叫。
    *   內聚廣播生命週期：於 `BluetoothBridgeClient` 提供 `startMonitoring(onEnvironmentChanged: () -> Unit)` 與 `stopMonitoring()`，以 `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)` 監聽 `BluetoothAdapter.ACTION_STATE_CHANGED` 與 `BluetoothDevice.ACTION_BOND_STATE_CHANGED`（移除多餘的 `ACTION_ACL_DISCONNECTED` 監聽與 `IntentCompat` 解析）；當系統藍牙轉為 `STATE_OFF` / `STATE_TURNING_OFF` 時立即呼叫 `disconnect()`，每次事件皆觸發 `onEnvironmentChanged()`。
    *   連線與單一背景讀取協程：在 `connect(device)` 建立 `BluetoothSocket` 後先寫入 `@Volatile private var connectingSocket: BluetoothSocket?`，重設 `@Volatile private var connectCancelled = false`；支援 `cancelConnect()` 關閉 `connectingSocket` 並於 `catch (IOException)` 檢查 `connectCancelled` 以轉為 `ConnectionState.Disconnected`（同時捕捉 `SecurityException`、於 `finally` 清除 `connectingSocket`）。連線成功後在 `Dispatchers.IO` 啟動單一專責讀取協程持續執行 `reader.readLine()`：若回傳回應行且有等待中的 `pendingAck: CompletableDeferred<String?>` 則喚醒之；若 `readLine()` 回傳 `null`（EOF `-1`）或拋出 `IOException`，僅當 `this.socket === rfcomm` 時關閉資源、將尚在等待的 `pendingAck` 設為 `null` 並設狀態為 `ConnectionState.Disconnected`（若正在等待 ACK 則讓 `sendCommand()` 回傳 `BridgeDeliveryException(PC_DISCONNECTED, ...)`）。`sendCommand()` 僅在 `ioMutex` 內序列寫入命令並等待 `withTimeoutOrNull(ACK_TIMEOUT_MS)`（`ACK_TIMEOUT_MS = 3_000L`）接收 `pendingAck`，若真正逾時則關閉 `activeSocket`（由讀取協程收尾）並回傳逾時失敗，不再於 `coroutineScope` 卡死。
*   **Rationale**: 以最小單讀取協程同時解決逾時死鎖、取消競態、閒置 EOF 察覺與廣播職責錯置，並使就緒狀態可直接於 JVM 測試。

#### 13. `app/src/main/java/com/akeyboard/remote/RemoteDictationViewModel.kt` (Modify)

*   **Type / Scope**: Remote Dictation ViewModel
*   **Proposed Changes**:
    *   建構階段向 `BluetoothBridgeClient` 註冊 `startMonitoring { refreshEnvironmentState() }`（於 `onCleared()` 呼叫 `stopMonitoring()`），並將 `{ preferBuiltInMic.value }` 傳給 `AudioCaptureManager`；訂閱 `settingsRepository.settings` 初始化並同步 `preferBuiltInMic` 與 `lastRemoteDeviceAddress`/`lastRemoteDeviceName`。
    *   定義優先序阻塞模型 `sealed interface RemoteBlocker`（`NoHardware`、`PermissionsMissing(val missingBt: Boolean, val missingMic: Boolean)`、`BluetoothDisabled(val turningOn: Boolean)`、`NoPairedDevices`、`ApiKeyMissing`）與統一派生函式。暴露 `selectedDevice: StateFlow<BluetoothDevice?>`、`offlineTargetName: StateFlow<String?>`（藍牙關閉時取自 `lastRemoteDeviceName`）、`hasApiKey: StateFlow<Boolean?>`（初值 `null`，載入完成後始更新避免閃爍）、`deliveryError: StateFlow<BridgeDeliveryError?>` 與 `pendingConnectActive: StateFlow<Boolean>`。
    *   **方向 II 接續連線狀態機**：保留 `private var pendingConnect: PendingConnect? = null`（含 `address: String` 與 `expiresAtElapsedMs: Long`，存於 ViewModel 以存活螢幕旋轉）。僅在使用者點擊「連線」時：若藍牙已開且 `selectedDevice != null` 直接連線並寫入 `SettingsRepository`；若藍牙關閉且存在記憶 `address`，則設定 `pendingConnect`（TTL `15_000L` ms）並發射開啟藍牙要求給 Activity（引導卡上的純「開啟藍牙」按鈕不建立 `pendingConnect`）。在 `onBluetoothEnableResult(granted)`：若 `!granted` 立即清除 `pendingConnect` 並提示取消；若 `granted` 立即重檢適配器狀態，若已為 `STATE_ON` 則馬上發起連線，否則啟動 5 秒等待計時器。若進入系統設定頁回退，於 `refreshEnvironmentState()`（`ON_RESUME`）檢查若 `pendingConnect` 未過期且已達 `BluetoothReadiness.Ready` 即消費之並發起連線；5 秒逾時或裝置已消失則清除 `pendingConnect` 並顯示明確錯誤提示。
    *   更新 `refreshEnvironmentState()` 重檢 `hasApiKey`、藍牙就緒狀態，並在權限與金鑰恢復就緒時清除過時的 `DictationState.Error(NO_KEY / NO_PERMISSION)` 且重跑 `session.warmUp()`；更新 `resendPending()` 採按 `\n` 逐行傳送（單行超過 16 KB 時按字元邊界分段），每段確認 `ok: true` 後自 `_pendingResendText` 移出已送前綴；將協定 `line_too_long` 對應至 `BridgeDeliveryError.SEND_FAILED`。
*   **Rationale**: 讓方向 II 的所有狀態轉換（包含旋轉、即時開啟、設定頁回退與逾時失敗）完全無競態，並避免離線累積重送觸發協定上限。

#### 14. `app/src/main/java/com/akeyboard/remote/RemoteDictationActivity.kt` (Modify)

*   **Type / Scope**: Remote Dictation Compose Activity
*   **Proposed Changes**:
    *   新增 `ON_RESUME` 觀察者更新 `hasBtPerm` / `hasMicPerm`（呼叫 `SystemChecks`）與 `viewModel.refreshEnvironmentState()`；使用 `rememberSaveable` 追蹤會話內是否已請求過權限，一旦某項權限曾遭拒絕且 `!shouldShowRequestPermissionRationale`，於權限引導卡同時顯示「授權」與「前往應用程式設定」（`ACTION_APPLICATION_DETAILS_SETTINGS`，不寫入 DataStore）。移除啟動無條件彈窗 `LaunchedEffect(Unit)`，改依 `RemoteBlocker` 優先序於頂端顯示單一引導卡（含 `NoHardware`、`BluetoothDisabled` 附開啟藍牙鈕、`NoPairedDevices` 附前往藍牙配對、`ApiKeyMissing` 附前往 `SettingsActivity`）。
    *   於 `TopAppBar` 加入設定頁圖示按鈕；於藍牙裝置列在 `BluetoothDisabled` 且存在 `offlineTargetName` 時顯示該名稱並保持「連線」鈕可點擊（若為 `Connecting` 則顯示「取消」鈕呼叫 `cancelConnect()`）；於裝置列正下方放置 `FilterChip` 切換「內建麥克風優先」與「系統預設（外接有線／USB 耳麥）」。
    *   將外層版面包覆於 `Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter)` 中的 `Column(Modifier.widthIn(max = 600.dp).fillMaxSize())`，讓 `Scaffold` 合併 `WindowInsets.safeDrawing`（含 `displayCutout`）；中間預覽區置於 `Modifier.weight(1f)`（`heightIn(min = 120.dp)` + 內部垂直捲動），確保底部麥克風操作列在橫屏／分屏時不被擠出畫面。麥克風按鈕保留獨立於本頁並改用 `awaitEachGesture` + `LongPress` 觸覺回饋、`Listening` 脈衝圓環與 `Finalizing` 內嵌 `CircularProgressIndicator`；擴充 `errBanner` 完整映射 `BridgeDeliveryError`（`UIPI`、`NO_FOCUS`、`SEND_FAILED`、`PC_DISCONNECTED`）與 `VoiceSessionError`（含 `MIC_BUSY` 對應 `err_mic_busy` 與 `NO_PERMISSION` 對應 `err_no_permission`）。
*   **Rationale**: 實踐完整的診斷引導與自適應排版，讓遙控體驗、狀態回饋與錯誤提示完全一致且不遺漏任何狀態。

#### 15. `app/src/main/java/com/akeyboard/settings/SettingsViewModel.kt` & `app/src/main/java/com/akeyboard/settings/SettingsActivity.kt` (Modify)

*   **Type / Scope**: Settings ViewModel & Compose UI
*   **Proposed Changes**:
    *   在 `SettingsViewModel` 抽出伴生純函式 `parseDefaultImePackage(defaultIme: String?): String?`（內部使用 `ComponentName.unflattenFromString`）；於 `SettingsUiState` 新增 `isImeEnabled: Boolean = false` 與 `isImeSelected: Boolean = false`（不加入藍牙權限、亦不加入冗餘 `setRemotePreferBuiltInMic`），提供 `refreshImeStatus(isEnabled: Boolean, isSelected: Boolean)` 由 Activity 於 `ON_RESUME` 與 `onWindowFocusChanged` 呼叫（不使用 `ContentObserver`，亦不傳入 `Context` 至 ViewModel 建構子）。
    *   於 `KeyValidationStatus` 新增 `data object StorageError : KeyValidationStatus()`；在 `refreshModels()` 呼叫 `apiKeyStore.save(apiKey)` 外包覆 `try/catch`（顯式重新拋出 `CancellationException`，捕捉 `GeneralSecurityException` / `IOException` 更新為 `StorageError` 且不進入 `hasStoredKey = true` / `Valid`），並同步更新 `KeyStatusText` 之 `when` 與 `isError` 集合。
    *   在 `SettingsActivity` 既有「1. 系統設定（`section_permissions`）」內嵌唯讀狀態文字顯示「AKeyboard 輸入法已啟用／未啟用」與「已設為目前預設輸入法／尚未切換」（不另建頂部摘要卡片）。於 `DisclosureScreen` 與 `SettingsContent` 皆使用 `Box(fillMaxSize(), TopCenter)` 包覆 `widthIn(max = 600.dp)` 並套用 `WindowInsets.safeDrawing`；模型下拉選單各選項加註 `[Transcribe]` 或 `[Live]` 標籤，且無論 `state.models` 是否為空，只要 `!state.settings.selectedModelId.contains("transcribe", ignoreCase = true)` 即於第 4 節語言與智慧模式區顯示能力限制提示。
*   **Rationale**: 精簡去除無關與重複檢查，同時補齊 API 31+ 輸入法偵測、`StorageError` 安全處理與大螢幕置中排版。

#### 16. `app/src/main/res/values/strings.xml`, `values-zh-rTW/strings.xml`, `values-zh-rCN/strings.xml` (Modify)

*   **Type / Scope**: Android Localized String Resources (en / zh-TW / zh-CN)
*   **Proposed Changes**: 不新增額外 `drawable/*.xml` 圖示，直接使用既有矢量圖示與 Material 3 元件。更新並新增下列資源鍵值：
    *   修正既有：`remote_error_no_network`（三語皆改為「無網路連線／No network connection.」，移除行動網路限定字眼）。
    *   新增（三語同步）：`key_storage_error`、`ime_status_enabled`、`ime_status_disabled`、`ime_status_selected`、`ime_status_not_selected`、`model_badge_transcribe`、`model_badge_live`、`model_non_transcribe_hint`、`remote_blocker_no_hardware`、`remote_blocker_bt_off`、`remote_blocker_bt_turning_on`、`remote_blocker_no_paired`、`remote_blocker_no_key`、`remote_btn_enable_bt`、`remote_btn_pair_bt`、`remote_btn_open_settings`、`remote_btn_cancel`、`remote_mic_built_in`、`remote_mic_system`、`remote_error_no_focus`、`remote_error_send_failed`、`remote_error_pc_disconnected`、`remote_error_bt_enable_timeout` 與 `remote_error_bt_enable_denied`。
*   **Rationale**: 明確固定三語字串資源鍵值與語意，避免實作階段遺漏或各語言文案偏移。

#### 17. Phase 3 Unit Tests

*   **Type / Scope**: Android JUnit Unit Tests
*   **Proposed Changes**:
    *   `app/src/test/java/com/akeyboard/bridge/BluetoothBridgeModelTest.kt` (Modify)：補充 `evaluateReadiness` 全狀態轉換、`BridgeDeliveryError` 列舉契約，以及抽出／測試 `BluetoothBridgeClient` 之連線取消、EOF（`PC_DISCONNECTED`）與真逾時回應處理測試。
    *   `app/src/test/java/com/akeyboard/remote/RemoteDictationLogicTest.kt` (Create)：針對 `RemoteDictationViewModel` 抽出的純狀態導出函式（`RemoteBlocker` 優先序、`PendingConnect` TTL 過期判斷、`resendPending` 逐行移除與 `BridgeDeliveryError` 映射）撰寫單元測試。
    *   `app/src/test/java/com/akeyboard/settings/SettingsImeStatusTest.kt` (Create)：驗證 `SettingsViewModel.parseDefaultImePackage` 處理縮寫 `.ime.AKeyboardImeService` 與完整組件名稱字串之準確性。

*   **Phase 3 Checkpoint**: 執行 `./gradlew :app:testDebugUnitTest :app:lintDebug`，所有既有 13 項加上新增 Kotlin 單元測試全數通過且 Lint 零警告。

---

### Phase 4: 文件與發布檢核同步

#### 18. `README.md`, `README_zh.md`, `docs/play-store-checklist.md` (Modify)

*   **Type / Scope**: Project & Release Documentation
*   **Proposed Changes**:
    *   更新 `README.md` 與 `README_zh.md` 頂端平台標章為 `Android 12+ (API 31–37)`，更新 `README.md` `Prerequisites` 與 `README_zh.md` Windows 橋接章節為 Windows 10/11 與標準庫邊界說明。
    *   更新 `docs/play-store-checklist.md` 第 1 項為 `compileSdk = 37, minSdk = 31, targetSdk = 37`。
*   **Rationale**: 使對外文件與發布清單與實際建置組態一致。

*   **Phase 4 Checkpoint**: 最終執行一次 `./gradlew :app:testDebugUnitTest :app:lintDebug` 與 `python -m unittest tools/test_akeyboard_bridge.py` 確認全綠。

---

### Verification Plan

*   **自動化單元測試與靜態檢查**:
    *   Android 靜態檢查：`./gradlew :app:lintDebug`（在 `warningsAsErrors = true` 與移除 `OldTargetApi` 後零錯誤通過）。
    *   Android 單元測試：`./gradlew :app:testDebugUnitTest`（通過既有 5 支測試檔共 **13 項** `@Test` 加上 Phase 2 / Phase 3 新增測試；異常回退路徑如 `Theme.kt` 與 `ApiKeyStore` 損毀復原主要於單元／測試替身驗證）。
    *   Windows Python 單元測試：`python -m unittest tools/test_akeyboard_bridge.py`（通過既有 **5 項**加上 Phase 1 新增全部測試）。
*   **跨版本與實機整合測試矩陣**:
    *   **Android 12（API 31 模擬器）與 Android 17（API 37 實機）**：驗證深色模式啟動無閃白、IME 啟用／切換狀態即時更新、多行聊天框（`IME_FLAG_NO_ENTER_ACTION`）顯示換行圖示並正確換行、分屏／橫屏（含 `sw >= 600dp` 模擬）下鍵盤緊湊模式與遙控畫面無裁切。*(註：中間版本 API 32–35 與特殊 OEM ROM 主要依賴防禦式回退保護，尚未列入多款實機發布強制清單。)*
    *   **Windows 10/11 與 Android 藍牙實機聯測**：
        1. **方向 II 藍牙關閉接續與永久拒權**：先連過一次 PC 後關閉手機藍牙並重新開啟遙控頁，確認選單顯示記憶名稱且點擊「連線」觸發系統開啟藍牙彈窗、確認開啟後自動接續連上電腦；測完取消與拒絕流程，並測永久拒絕權限後透過「前往應用程式設定」授權返回即消退警告。
        2. **閒置斷線與送字 EOF 分離測試**：連線後閒置不動直接關閉 Windows 終端機視窗，驗證手機無須切換或等待下次送字即主動切為已斷線；另模擬送字瞬間終端機關閉，驗證 `sendCommand()` 立即回傳 `PC_DISCONNECTED` 並保留 `pendingResendText`。
        3. **Python 故障隔離與按行重送**：測試缺少 `AF_BLUETOOTH` 的環境診斷訊息、關閉電腦藍牙後的退避提示、手機短暫斷線重連不中斷 `server_sock`、使用有線／USB 耳麥切換開關、鎖定畫面（`no_focus`）與高權限視窗（`uipi`），以及累積多行 `pendingResendText` 逐行重送成功。

--------------------------------------------------------------------------------

*Drafted with EGM*
