# AKeyboard Windows Voice Bridge 錯誤代碼與狀態說明書 (ERROR_CODES.md)

本文件為 `tools/akeyboard_bridge.py`（完整版）與 `tools/akeyboard_bridge.min.py`（極簡版）的錯誤代碼與通訊協定規範對照表。使用極簡版時，終端機僅輸出短代碼（如 `[E01]`、`[W10047]`、`[SDP:10013]`、`[WARN:E04]`），請依本手冊進行排錯。

---

## 1. 終端機狀態代碼 (Status Tags)

| 狀態代碼 | 說明 | 備註 |
| :--- | :--- | :--- |
| `[START]` | 橋接程式啟動並完成 Console 編碼與環境檢查 | 初始化階段 |
| `[READY:ch=<port>]` | 藍牙 RFCOMM 伺服端已成功繫結並完成 SDP 註冊 | `<port>` 為指派的 RFCOMM 通訊頻道 (1～30) |
| `[CONN]` | 手機端已建立藍牙 RFCOMM 連線 | 採 Last-One-Wins 機制，新連線自動搶占舊連線 |
| `[DISC]` | 手機端已離線（正常中斷或連線逾時） | 伺服端繼續於原 channel 等待下一次連線 |

---

## 2. 啟動與環境錯誤代碼 (E01 ～ E04)

當程式在初始化或資料傳輸時發生嚴重錯誤，終端機將標註以下錯誤代碼並中斷或採取防禦動作：

| 錯誤代碼 | 名稱 | 觸發條件 | 原因與解決步驟 |
| :--- | :--- | :--- | :--- |
| **`[E01]`** | 環境/版本不符<br>`(Unsupported Environment)` | `sys.platform != "win32"`<br>或 `sys.version_info < (3, 10)` | **原因**：AKeyboard Bridge 僅支援 64 位元 Windows 10/11 平台，且依賴 Python 3.10+。<br>**排錯與解決**：<br>1. 確認作業系統為 Windows 10 或 Windows 11。<br>2. 請至 [python.org 官方網站](https://www.python.org/downloads/windows/) 下載安裝官方 64-bit CPython 3.10 以上版本。 |
| **`[E02]`** | 缺少藍牙 Socket 支援<br>`(Missing Bluetooth Socket)` | `hasattr(socket, "AF_BLUETOOTH")` 為 `False`<br>或 `hasattr(socket, "BTPROTO_RFCOMM")` 為 `False` | **原因**：目前執行的 Python 直譯器缺少 Windows 原生藍牙 Socket 支援。常見於 **Microsoft Store 安裝版、MSYS2 / MinGW、Conda / Anaconda** 或精簡嵌入式 Python。<br>**排錯與解決**：<br>1. 請解除安裝非官方 Python 直譯器。<br>2. 至 [python.org](https://www.python.org/downloads/windows/) 安裝標準 Windows 官方安裝程式，並在安裝時務必勾選「Add python.exe to PATH」。 |
| **`[E03]`** | 無效 RFCOMM 通訊埠<br>`(Invalid RFCOMM Channel)` | `server_sock.bind()` 成功但 `getsockname()[1] <= 0` | **原因**：Windows Winsock RFCOMM 通訊端無法配置有效的頻道編號（可能藍牙堆疊耗盡或通訊埠被佔用）。<br>**排錯與解決**：<br>1. 重新開啟 Windows「設定」>「藍牙與裝置」中的藍牙開關。<br>2. 若仍發生，請重新啟動電腦以釋放 Windows 藍牙通訊資源。 |
| **`[WARN:E04]`** | 單行緩衝區上限溢位<br>`(Line Buffer Limit Exceeded)` | 接收到的單行緩衝資料超過 64KB (65,536 bytes) 且未遇到換行符 `\n` | **原因**：客戶端傳輸異常或遭受非預期的惡意串流轟炸。<br>**安全防禦**：<br>1. 終端機輸出 `[WARN:E04]`。<br>2. 立即透過藍牙回傳 `{"ok":false,"err":"line_too_long"}\n`。<br>3. 立即強制關閉該連線以防止伺服端記憶體耗盡。 |

---

## 3. Winsock 與 SDP 錯誤代碼對照表

當通訊埠開啟、SDP 服務探索註冊或傳輸發生系統層錯誤時，終端機會輸出 `[W<winerror>]` 或 `[SDP:<wsa_error>]`，並依 `(2, 5, 10)` 秒指數退避策略自動重試。常見代碼如下：

| 錯誤代碼 (WinError / WSAError) | Winsock 常數名稱 | 觸發情境與原因 | Windows 排錯步驟 |
| :--- | :--- | :--- | :--- |
| **`10013`** | `WSAEACCES` | 權限不足 / 存取遭拒。<br>無法建立 RFCOMM 監聽端或 SDP 註冊被攔截。 | 1. 檢查 Windows「設定」>「隱私權與安全性」>「其他裝置 / 藍牙」是否開放權限。<br>2. 檢查是否有第三方防毒軟體、Windows 防火牆或企業群組原則 (GPO) 阻擋通訊端監聽。<br>3. 嘗試以系統管理員身分執行 Command Prompt / PowerShell。 |
| **`10047`** | `WSAEAFNOSUPPORT` | 位址系列不受支援。<br>系統找不到可用的藍牙 RFCOMM 驅動堆疊。 | 1. 確認電腦具備實體藍牙硬體或藍牙 USB Dongle。<br>2. 開啟「裝置管理員」，確認「藍牙」類別中的介面卡運作正常且未被停用。<br>3. 按 `Win + R` 輸入 `services.msc`，確認「Bluetooth 支援服務 (`bthserv`)」已設定為「自動」且處於「執行中」狀態。 |
| **`10049`** | `WSAEADDRNOTAVAIL` | 要求的位址無法指定。<br>無法繫結至本機藍牙無線電介面卡。 | 1. 確認 PC 藍牙處於「開啟」狀態。<br>2. 確認電腦未開啟「飛航模式」。<br>3. 在裝置管理員中對藍牙介面卡按右鍵「停用」後再次「啟用」。 |
| **`10050`** | `WSAENETDOWN` | 網路子系統故障 / 藍牙已關閉。<br>在程式執行中藍牙介面卡斷電或關閉。 | 1. 請檢查 Windows 工具列是否誤按關閉藍牙或誤啟動飛航模式。<br>2. 重新開啟藍牙開關，橋接程式會自動退避重試並於藍牙恢復後重新上線。 |
| **`10053`** / **`10054`**<br>**`10058`** / **`10060`** | `WSAECONNABORTED`<br>`WSAECONNRESET`<br>`WSAESHUTDOWN`<br>`WSAETIMEDOUT` | 遠端連線已被中斷 / 重設 / 逾時。 | 屬於客戶端離線的正常現象（手機超出通訊範圍、關閉 App、手機端藍牙斷開）。橋接器會自動釋放連線資源並標註 `[DISC]`，不影響伺服端主迴圈繼續等待下次連線。 |

---

## 4. 藍牙通訊協定 JSON 回傳錯誤 (`err` 欄位)

橋接程式與手機端溝通均採用 **JSON Lines**（每則訊息以 `\n` 結尾）。
成功時回傳 `{"ok":true}\n`；失敗時回傳 `{"ok":false,"err":"<代碼>"}\n`。

| `err` 代碼 | 嚴重度 | 說明與觸發情境 | 建議處理與排錯 |
| :--- | :--- | :--- | :--- |
| **`no_focus`** | 提示 (Safe Drop) | 當前 Windows 沒有活躍的前景焦點視窗 (`GetForegroundWindow() == 0`)。 | 點擊桌面、編輯器或欲輸入之應用程式視窗，使其取得焦點後再使用語音輸入。此保護機制避免盲打至未知視窗。 |
| **`uipi`** | 警告 (Permission) | **使用者介面權限隔離** (User Interface Privilege Isolation) 阻擋。<br>目標焦點視窗以高權限（系統管理員 Elevated / Administrator）執行，但橋接腳本僅具一般使用者權限，Win32 `SendInput` 被作業系統核心安全攔截 (`SendInput == 0`)。 | **請以系統管理員身分執行**啟動橋接腳本的命令提示字元（Command Prompt）或 PowerShell 視窗，使橋接器具備向管理員層級視窗模擬輸入的權限。 |
| **`bad_json`** | 錯誤 | 手機端傳入的字串無法以 JSON 解析。 | 請檢查手機端傳輸格式是否包含截斷、損壞或非 UTF-8 字元。 |
| **`bad_payload`** | 錯誤 | JSON 根物件並非字典格式（例如傳入 Array 或 Primitive 值）。 | 手機端必須傳送 JSON Object，如 `{"type":"text", ...}`。 |
| **`bad_value`** | 錯誤 | `type: "text"` 時，未包含 `value` 欄位或 `value` 不是字串型別。 | 請檢查手機端 JSON 欄位是否正確傳入字串。 |
| **`bad_key`** | 錯誤 | `type: "key"` 時，傳入不受支援的按鍵名稱。 | 目前 Win32 虛擬鍵僅支援 `"ENTER"` 與 `"BACKSPACE"`。 |
| **`unknown_type`** | 錯誤 | 訊息物件中的 `type` 不是 `"text"` 亦不是 `"key"`。 | 僅支援 `text` 與 `key` 兩種訊息類別。 |
| **`utf8`** | 錯誤 | 接收到的位元組資料無法以 UTF-8 編碼解碼。 | 手機端文字編碼必須固定為標準 UTF-8。 |
| **`line_too_long`** | 安全攔截 | 單行訊息超過 64KB 上限（同 `[WARN:E04]`）。 | 縮短單次語音或鍵盤指令傳輸長度。 |
| **`send_failed`** | 系統錯誤 | Win32 `SendInput` 回傳注入事件數量與預期不符。 | 目標視窗處理不及或作業系統暫態攔截，通常重試即可。 |

---

## 5. 使用重要注意事項 (Critical Notices)

> [!IMPORTANT]
> **1. Windows 輸入法切換為英文模式 (English Mode Required)**
> * 在使用 AKeyboard 語音打字或發送符號前，**必須將目標焦點視窗之 Windows 輸入法切換至純英文模式（ENG）**。
> * **原因**：若 Windows 停留在微軟注音、倉頡等中文輸入法狀態下，Windows IME 會強制攔截 Unicode 注入字元或將半形標點符號轉換為全形選字，導致輸入內容與標點符號跑版。

> [!NOTE]
> **2. 雙版本同步維護規範 (Dual-Script Sync Rules)**
> * `tools/akeyboard_bridge.py` 為本專案的唯一功能基準（Single Source of Truth, SSOT）。
> * 今後只要 `tools/akeyboard_bridge.py` 有任何按鍵對應、協定欄位、錯誤代碼或 Win32 API 呼叫的變更，**必須在同一次 Commit / 修改中同步更新 `tools/akeyboard_bridge.min.py` 與 `tools/ERROR_CODES.md`**，確保行為定義完全一致。
