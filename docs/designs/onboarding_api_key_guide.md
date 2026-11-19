# AKeyboard 首次使用引導：Google AI Studio API Key 申請與設定清單

*   **Author:** Max97k
*   **Status:** Implemented
*   **Date:** 2026-11-19

--------------------------------------------------------------------------------

## Overview

AKeyboard 是 BYOK（自帶 API Key）應用：沒有 Google AI Studio 的 API Key，語音輸入無法使用。目前第一次開啟 App 時，使用者看到的是資料揭露畫面，按下同意後直接進入設定頁，頁面上只有一個空的「API Key」欄位，沒有說明 Key 從哪裡來、適用的方案或如何取得。

本設計加入三件事：

1.  在首次揭露畫面說明「需要一把 Google AI Studio API Key（適用地區提供免費層）」。
2.  在設定頁 API Key 區塊加上「前往 Google AI Studio 申請 Key」按鈕與「從剪貼簿貼上」按鈕。
3.  在設定頁上方（「開啟 PC 藍牙語音遙控」按鈕下方）加上「開始使用」純狀態摘要清單，把「取得 Key、啟用鍵盤、允許麥克風」三件事的完成狀態逐項列出，全部完成後自動隱藏。

## The Problem

*   新使用者缺乏取得 Google AI Studio API Key 的指引與入口，目前僅在未設定時顯示純文字錯誤，沒有申請說明或跳轉按鈕。
*   AKeyboard 本身是純語音鍵盤，**沒有任何字元按鍵**。API Key 是一長串英數字，使用者無法用 AKeyboard 輸入，只能切換到其他鍵盤（如 Gboard）或從瀏覽器複製後貼上，目前 UI 沒有顯性的貼上入口。
*   必要設定分散在設定頁不同區塊（鍵盤啟用、麥克風、API Key），使用者無法一眼看出還缺哪幾步。
*   介面不能承諾「免費額度一定足夠使用」或寫死具體額度：Google 的免費額度依模型與地區而異、會動態調整，且部分地區可能不提供免費層。

## Goals

*   新使用者在離開 App 前，即可於設定頁得知需要 API Key 與取得方式，並一鍵跳轉至 Google AI Studio 的 API Key 頁面（`https://aistudio.google.com/apikey`）。
*   使用者從瀏覽器回到 App 後，能用單一按鈕把剪貼簿中的 Key 貼進欄位，免去切換鍵盤或長按選單，並在剪貼簿無可用文字時獲得清楚提示。
*   設定頁上方顯示「開始使用」狀態清單，每一項的完成狀態直接由既有狀態即時計算（是否已存 Key、鍵盤是否已啟用、麥克風權限是否已授予），不新增獨立的進度儲存，三項全部完成時自動隱藏。
*   三種語系（英文、繁體中文、簡體中文）的新字串同步補齊。
*   在首次揭露畫面與隱私政策清楚說明語音送至 Google Gemini API 並依使用者所選方案適用 Google Gemini API 條款。

## Non-Goals

*   不做多頁輪播式（pager）歡迎導覽，也不新增獨立的引導 Activity。
*   不在 App 內嵌 WebView 開啟 Google AI Studio（使用者需要在瀏覽器登入 Google 帳號，WebView 不適合）。
*   不自動讀取剪貼簿（只在使用者按下「從剪貼簿貼上」時讀取），不做 Key 格式自動偵測。
*   不在介面中寫出具體額度數字（RPM/RPD/Token），只引導至 AI Studio 查看即時額度。
*   不改動 Remote（PC 語音遙控）頁面與鍵盤面板的既有流程，僅沿用其未設定 API Key 時導向設定頁的既有行為。
*   不處理付費升級流程。

## Open Questions

*   **Q1（待查證，出貨前必做）**：確認免費層在目標上架國家／地區的可用性與 Google Gemini API 最新服務條款原文，於上架前依實測與條款核對 [play-store-checklist.md](file:///c:/Users/b/akeyboard/docs/play-store-checklist.md) 項目。

## Critical User Journeys (CUJs)

### CUJ 1：全新使用者，沒有 API Key

*   **Given**：剛安裝 App，沒有儲存 Key，鍵盤尚未啟用，麥克風權限未授予。
*   **When**：開啟 App，閱讀揭露並按「同意並繼續」，看到「開始使用」清單顯示三項待完成後，在 Section 2 閱讀說明並點「前往 Google AI Studio 申請 Key」。
*   **Then**：手機瀏覽器開啟 `https://aistudio.google.com/apikey`。使用者登入、建立並複製 Key，回到 App（捲動位置仍停在 Section 2），按「從剪貼簿貼上」填入 Key，按「驗證並儲存」，上方清單第 1 項打勾。

### CUJ 2：手機上沒有任何瀏覽器

*   **Given**：裝置沒有可處理 `https` 連結的 App。
*   **When**：使用者按「前往 Google AI Studio 申請 Key」。
*   **Then**：App 不閃退，於該按鈕下方顯示「找不到瀏覽器，請手動前往 aistudio.google.com/apikey」。

### CUJ 3：已完成設定的回訪使用者

*   **Given**：Key 已儲存、鍵盤已在系統設定啟用、麥克風已授權。
*   **When**：開啟設定頁。
*   **Then**：「開始使用」清單不顯示；API Key 區塊仍保留申請與貼上按鈕（供更換 Key 使用）。

## Technical Plan (Architecture)

### 設計選擇

*   **引導形式：設定頁的純狀態摘要卡片，不新增畫面也不重複放置動作按鈕。** 理由：Material 與 Android 對權限與引導的建議是「引導只說明價值、不要在冷啟動連續請求多個權限、在使用者操作到功能時才請求」。Section 1 與 Section 2 已經包含啟用鍵盤、授權麥克風（含永久拒絕時的應用程式資訊退路）與 API Key 輸入；將清單卡片做為「開啟 PC 藍牙語音遙控」按鈕下方的純狀態摘要（顯示打勾或步驟編號，不放重複按鈕），可避免按鈕與錯誤處理（無瀏覽器提示、權限永久拒絕）在兩個地方分岔，也避免點清單跳去瀏覽器後回到頁面頂端卻找不到下方貼上按鈕的斷層。
*   **開啟網頁：`ACTION_VIEW` Intent**（作者已選擇，不新增依賴）。呼叫 `startActivity` 時捕捉 `ActivityNotFoundException`（以及 `SecurityException`），失敗時在按鈕下方顯示行內提示文字，之後再次成功開啟時重設提示。Android 11 以上對直接 `startActivity` 開啟 `https` 連結不需要在 Manifest 宣告 `<queries>`，只有先呼叫 `resolveActivity` 才需要，因此不呼叫它。
*   **貼上：只在按下按鈕時讀取剪貼簿。** 使用 Compose 的 `LocalClipboard`（`getClipEntry()`，suspend 函式），不使用已棄用的 `LocalClipboardManager`（`app/build.gradle.kts` 設定 `warningsAsErrors = true`）。讀取時先檢查 `clipData?.itemCount > 0` 並包在例外保護內；取得非空白文字後先 `trim()` 再交給既有的 `viewModel.onKeyInputChange()`。若剪貼簿為空、非文字或讀取失敗，**不覆蓋**欄位既有內容，改用標準 `Toast.makeText` 顯示簡短提示（避免為此引入額外 `SnackbarHostState`）。
*   **完成狀態不新增儲存。** 由純函式 `isSetupComplete(hasKey, imeEnabled, micGranted)` 與既有狀態即時計算，不新增列舉或進度儲存欄位（任一條件日後失效如清除 Key 或關閉權限時，清單會自動重新顯示；不額外提供手動展開開關）：

    | 項目 | 完成條件 | 資料來源 |
    | :--- | :--- | :--- |
    | 1. 取得並儲存 API Key | `hasStoredKey == true` | `SettingsUiState.hasStoredKey`（不額外綁定模型清單筆數） |
    | 2. 在系統設定啟用 AKeyboard | `isImeEnabled == true` | `SettingsUiState.isImeEnabled`（不綁定 `isImeSelected`，避免切換日常鍵盤打字時反覆觸發） |
    | 3. 允許麥克風 | `RECORD_AUDIO` 已授予 | `SettingsContent` 內既有的 `hasMicPerm` |

*   **揭露畫面**：在既有 `disclosure_body` 下方加一段獨立的 `disclosure_setup_hint`，說明「使用 AKeyboard 需要 Google AI Studio API Key（適用地區提供免費層），資料處理依您選用的免費或計費方案適用 Google Gemini API 條款」。**不**在揭露畫面放外部跳轉按鈕，維持同意閘門單純。
*   **額度文字**：只寫「額度依地區與模型而異，請至 AI Studio 查看」，不寫任何數字。

### 畫面配置

```text
設定頁 (SettingsContent)
┌──────────────────────────────────────────┐
│ [開啟 PC 藍牙語音遙控]                    │
├──────────────────────────────────────────┤
│ [開始使用]  (三項皆完成時自動隱藏)        │
│  [✓ / 1] 取得並儲存 API Key               │
│  [✓ / 2] 在系統設定啟用 AKeyboard         │
│  [✓ / 3] 允許麥克風權限                   │
├──────────────────────────────────────────┤
│ 1. 系統設定（權限狀態／啟用與切換鍵盤按鈕） │
│ 2. Google AI Studio API Key               │
│    說明文字（適用地區提供免費層／依模型異）│
│    [前往 Google AI Studio 申請 Key]       │
│    (若無瀏覽器：顯示手動網址提示)         │
│    [API Key 欄位]                         │
│    [從剪貼簿貼上] (空剪貼簿時 Toast 提示) │
│    [驗證並儲存] [清除]                    │
└──────────────────────────────────────────┘

資料流：
 SettingsViewModel.uiState ─┐
 hasMicPerm (Composable) ───┼─> isSetupComplete() ─> SetupChecklistCard (純狀態)
                            │
 [前往申請 Key] ──> startActivity(ACTION_VIEW https://aistudio.google.com/apikey)
                       └─ ActivityNotFoundException / SecurityException ─> 行內提示
 [從剪貼簿貼上] ──> LocalClipboard.getClipEntry() (檢查 itemCount > 0 + try/catch)
                       ├─ 非空白文字 ─> trim ─> viewModel.onKeyInputChange()
                       └─ 空／非文字／失敗 ─> Toast 提示（不覆蓋欄位）
```

### 範圍與限制

*   AKeyboard 沒有字元按鍵，因此「從剪貼簿貼上」提供單鍵的貼上入口，免去切換鍵盤或長按呼叫系統選單的步驟；使用者仍可長按欄位使用系統選單貼上。
*   鍵盤面板的 `err_no_key`（`API key not set`）錯誤列與 Remote 頁面的 `remote_blocker_no_key` 提示卡行為不變，點擊後皆開啟設定頁，使用者即可在設定頁看到狀態清單與申請／貼上按鈕。

## Alternatives Considered

### Alternative A：多頁輪播式歡迎導覽（獨立 Activity 或 Pager）

*   **Pros**：單一步驟聚焦於單一畫面，適合第一次使用的使用者。
*   **Cons**：新增畫面與導覽狀態，需要儲存「是否看過」；使用者中途離開（例如去瀏覽器申請 Key）回來後要還原位置；每次想檢查設定是否完成還是得回設定頁。Android 與 Material 的建議是引導只說明價值，不連續請求權限。
*   **Reason for Rejection**: Rejected by author: 狀態摘要直接從現有狀態計算，不需要新的畫面、導覽與儲存，維護成本較低。

### Alternative B：在 App 內用 WebView 開啟 Google AI Studio

*   **Pros**：使用者不離開 App。
*   **Cons**：使用者需要在頁面登入 Google 帳號，WebView 不共用瀏覽器的登入狀態與密碼管理員，且部分 Google 登入流程會拒絕內嵌 WebView。App 也要承擔 WebView 的安全面（JavaScript、Cookie）。
*   **Reason for Rejection**: Rejected by author: 登入限制與額外安全維護成本高於單頁流程需求。

### Alternative C：Chrome Custom Tabs（`androidx.browser`），含或不含 `ACTION_VIEW` 退回

*   **Pros**：Google 對「App 內開外部網頁」的官方建議；使用者留在 App 脈絡內，共用瀏覽器的 Google 登入狀態，按返回或關閉鈕可直接回到設定頁的 Task Stack，不需從多工切換器找回 App。
*   **Cons**：需要新增 `androidx.browser` 依賴；此流程通常只開一次申請頁，Custom Tabs 的預熱與自訂工具列效益有限。
*   **Reason for Rejection**: Rejected by author: 選擇 `ACTION_VIEW` Intent，不新增依賴。

### Alternative D：App 自動偵測剪貼簿內的 Key 並預先填入

*   **Pros**：使用者從瀏覽器回來後什麼都不用按。
*   **Cons**：在使用者沒有操作時讀取剪貼簿屬於隱私敏感行為（Android 12 以上會顯示提示），可能讓使用者擔心；剪貼簿內容可能非 API Key；需要維護 Key 格式規則，Google 改格式就失效。
*   **Reason for Rejection**: Rejected by author: 只在使用者按「從剪貼簿貼上」時讀取，隱私風險最低。

--------------------------------------------------------------------------------

## Detailed Implementation Plan

### Target Files to Create/Modify

#### 1. `app/src/main/java/com/akeyboard/settings/SettingsActivity.kt` (Modify)

*   **Type / Scope**: Kotlin Compose UI 與設定頁輔助函式（不新增獨立檔案或列舉）。
*   **Proposed Changes**:
    *   新增純函式 `internal fun isSetupComplete(hasKey: Boolean, imeEnabled: Boolean, micGranted: Boolean): Boolean = hasKey && imeEnabled && micGranted` 與常數 `internal const val AI_STUDIO_API_KEY_URL = "https://aistudio.google.com/apikey"`。
    *   新增 `private fun openAiStudioApiKeyPage(context: Context): Boolean`：建立 `Intent(Intent.ACTION_VIEW, Uri.parse(AI_STUDIO_API_KEY_URL))` 並呼叫 `context.startActivity(intent)`；捕捉 `ActivityNotFoundException` 與 `SecurityException` 回傳 `false`，成功回傳 `true`。不呼叫 `resolveActivity`，不新增 `<queries>`。
    *   新增純狀態摘要 Composable `SetupChecklistCard(hasKey: Boolean, imeEnabled: Boolean, micGranted: Boolean)`：使用 Material 3 `Card`（`testTag("setup_checklist_card")`），標題為 `R.string.setup_checklist_title`，列出三列純狀態項目（不放重複動作按鈕）：
        1.  `R.string.setup_step_api_key`（`testTag("setup_step_api_key")`）
        2.  `R.string.setup_step_keyboard`（`testTag("setup_step_keyboard")`）
        3.  `R.string.setup_step_mic`（`testTag("setup_step_mic")`）
        每列左側完成時顯示 `Icon(painter = painterResource(R.drawable.ic_check), contentDescription = null, tint = MaterialTheme.colorScheme.primary)`（因 `ic_check.xml` 原生為 `#000000`），未完成時顯示純數字編號 `"1"` / `"2"` / `"3"`；步驟文字加 `Modifier.weight(1f)` 避免窄螢幕換行擠壓。
    *   `DisclosureScreen`：在既有 `disclosure_body` 的 `Text` 之後加入 `Text(stringResource(R.string.disclosure_setup_hint), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("disclosure_setup_hint"))`。不加任何按鈕或連結。
    *   `SettingsContent`：
        *   若 `!isSetupComplete(state.hasStoredKey, state.isImeEnabled, hasMicPerm)`，在頂端既有的 `btn_open_pc_remote` 按鈕**下方**、Section 1 分隔線之前顯示 `SetupChecklistCard`；三項皆完成時不顯示。
        *   Section 2（API Key）：在標題與既有 `api_key_saved_hint` 之間加入 `Text(stringResource(R.string.api_key_help))`；在 `settings_api_key_input` 之前加入 `OutlinedButton`（`R.string.btn_get_api_key`，`testTag("settings_get_key_btn")`），點擊時呼叫 `openAiStudioApiKeyPage(context)`，成功時重設無瀏覽器提示為 `false`，失敗時設為 `true` 並在按鈕下方顯示 `Text(stringResource(R.string.api_key_no_browser), modifier = Modifier.testTag("settings_no_browser_hint"))`。
        *   在 `settings_api_key_input` 下方、「驗證並儲存」按鈕列之前加入 `OutlinedButton`（`R.string.btn_paste_key`，`testTag("settings_paste_key_btn")`）。點擊時於協程中以 `try/catch` 讀取 `LocalClipboard.current.getClipEntry()`，確認 `clipData != null && clipData.itemCount > 0` 後取第 0 項文字並 `trim()`；若非空白則呼叫 `viewModel.onKeyInputChange(text)`，否則維持原欄位不變並以 `Toast.makeText(context, R.string.api_key_clipboard_empty, Toast.LENGTH_SHORT).show()` 提示。

#### 2. `app/src/main/res/values/strings.xml` (Modify)

*   **Type / Scope**: 英文字串。
*   **Proposed Changes**: 新增下表共 10 個字串；英文欄為值。

#### 3. `app/src/main/res/values-zh-rTW/strings.xml` (Modify)

*   **Type / Scope**: 繁體中文字串。
*   **Proposed Changes**: 新增同樣 10 個 key，值取下表繁體欄。

#### 4. `app/src/main/res/values-zh-rCN/strings.xml` (Modify)

*   **Type / Scope**: 簡體中文字串。
*   **Proposed Changes**: 新增同樣 10 個 key，值取下表簡體欄。

字串表（共 9 組 key；不寫「一定免費」或固定額度數字）：

| key | English | 繁體中文 | 简体中文 |
| :--- | :--- | :--- | :--- |
| `setup_checklist_title` | Get started | 開始使用 | 开始使用 |
| `setup_step_api_key` | Get and save your Google AI Studio API key | 取得並儲存 Google AI Studio API Key | 获取并保存 Google AI Studio API Key |
| `setup_step_keyboard` | Enable AKeyboard in system settings | 在系統設定啟用 AKeyboard | 在系统设置启用 AKeyboard |
| `setup_step_mic` | Allow microphone access | 允許麥克風權限 | 允许麦克风权限 |
| `btn_get_api_key` | Get a key at Google AI Studio | 前往 Google AI Studio 申請 Key | 前往 Google AI Studio 申请 Key |
| `btn_paste_key` | Paste from clipboard | 從剪貼簿貼上 | 从剪贴板粘贴 |
| `api_key_help` | Google AI Studio offers a free API key tier in eligible regions. Sign in with a Google account, create a key, copy it, then return here and tap Paste. Limits vary by model and region; check AI Studio for your current limits. | Google AI Studio 在適用地區提供免費層 API Key。使用 Google 帳號登入、建立並複製 Key，回到此處按「從剪貼簿貼上」。額度依地區與模型而異，請至 AI Studio 查看目前的限制。 | Google AI Studio 在适用地区提供免费层 API Key。使用 Google 账号登录、创建并复制 Key，回到此处点按「从剪贴板粘贴」。额度因地区与模型而异，请到 AI Studio 查看当前的限制。 |
| `api_key_no_browser` | No browser found. Open aistudio.google.com/apikey manually. | 找不到瀏覽器，請手動前往 aistudio.google.com/apikey。 | 找不到浏览器，请手动前往 aistudio.google.com/apikey。 |
| `api_key_clipboard_empty` | Clipboard has no text to paste. | 剪貼簿目前沒有可貼上的文字。 | 剪贴板当前没有可粘贴的文字。 |
| `disclosure_setup_hint` | Using AKeyboard requires your own Google AI Studio API key (free tier available in eligible regions; see the next screen for setup steps). Data handling depends on the Google AI Studio plan you choose (free or paid) under Google's Gemini API terms. | 使用 AKeyboard 需要您自己的 Google AI Studio API Key（適用地區提供免費層，下一步會引導設定）。資料處理方式依您在 Google AI Studio 選用的方案（免費或計費）適用 Google 的 Gemini API 條款。 | 使用 AKeyboard 需要您自己的 Google AI Studio API Key（适用地区提供免费层，下一步会引导设置）。数据处理方式依您在 Google AI Studio 选用的方案（免费或计费）适用 Google 的 Gemini API 条款。 |

#### 5. `app/src/test/java/com/akeyboard/settings/SetupChecklistTest.kt` (Create)

*   **Type / Scope**: JVM 單元測試，不使用 Robolectric 與 Mockito，風格比照既有的 `SettingsImeStatusTest.kt`。
*   **Proposed Changes**: 測試 `isSetupComplete`：全 `false` 回傳 `false`、任兩項為 `true` 一項為 `false` 時回傳 `false`（覆蓋 3 個缺少條件的分支）、三項皆為 `true` 回傳 `true`。

#### 6. `.maestro/04_api_key_onboarding.yaml` (Create)

*   **Type / Scope**: Maestro E2E 流程，只使用 `id:` 選擇器（含既有的 `disclosure_agree_btn`、`btn_open_pc_remote`、`settings_api_key_input` 與新增的 `testTag`）。
*   **Proposed Changes**: `appId: com.akeyboard`；`launchApp` 搭配 `clearState: true`；`assertVisible` `disclosure_setup_hint`；`tapOn` `disclosure_agree_btn`；`assertVisible` `setup_checklist_card`、`setup_step_api_key`、`setup_step_keyboard`、`setup_step_mic`；`scrollUntilVisible`（`direction: DOWN`）`settings_get_key_btn` 與 `settings_paste_key_btn` 並 `assertVisible`；`tapOn` `settings_paste_key_btn` 後 `assertVisible` `settings_api_key_input`（空剪貼簿時維持欄位不閃退）。**不點**「取得 Key」按鈕：模擬器的瀏覽器首次啟動會出現歡迎畫面，會讓流程不穩定；開啟網頁改在實體手機手動驗證。

#### 7. `.maestro/01_onboarding_and_settings.yaml`、`.maestro/02_remote_permissions_and_controls.yaml`、`.maestro/03_remote_after_permissions_granted.yaml` (Modify，條件式)

*   **Type / Scope**: 既有 Maestro 流程。
*   **Proposed Changes**: `SetupChecklistCard` 位於 `btn_open_pc_remote` 下方，因此 `.maestro/02` 與 `.maestro/03` 的 `btn_open_pc_remote` 仍在頂端不受影響。唯 `.maestro/01_onboarding_and_settings.yaml` 對 Section 1 與 Section 2 多個元件（包含 `settings_api_key_input` 與 `settings_validate_save_btn`）執行 `assertVisible`，因上方清單卡片與 Section 2 新增說明及按鈕將其**往下推**，若實測超出螢幕可視範圍，在對應的 `assertVisible` 之前加上 `scrollUntilVisible`（`direction: DOWN`）。

#### 8. `README.md` 與 `README_zh.md` (Modify)

*   **Type / Scope**: 文件。
*   **Proposed Changes**:
    *   `README.md`：在 `## 🚀 Getting Started` 下新增小節 `### First-Time Setup`：引導至 `https://aistudio.google.com/apikey` 取得 Key，並依設定頁的 **Get started** 清單完成三項準備（不寫額度數字）。
    *   `README_zh.md`：目前無對應段落，於 `## 🛡️ 隱私與安全設計` 之前新增頂層段落 `## 🚀 快速開始`，說明至 `https://aistudio.google.com/apikey` 取得 Key，並依設定頁的「開始使用」清單完成三項準備（不寫額度數字）。

#### 9. `docs/play-store-checklist.md` (Modify)

*   **Type / Scope**: 文件。
*   **Proposed Changes**: 新增一項核取（保持 `- [ ]` 格式）：`- [ ] First-run disclosure and setup hint match Google's current Gemini API terms (free vs paid data handling), and the in-app wording does not promise unconditional free usage.`

#### 10. `PRIVACY.md` (Modify)

*   **Type / Scope**: 文件。
*   **Proposed Changes**: 在 `## 3. Third-Party Processing` 段落末尾加一句：`Whether Google may use content sent under free (unpaid) Gemini API usage to improve its products depends on Google's current Gemini API terms and the plan you choose in Google AI Studio.`

### Verification Plan

*   **Compilation/Build**: `.\gradlew.bat :app:assembleDebug --console=plain`。若 `LocalClipboard` 在目前 Compose 版本無法編譯，改為 `LocalContext.current.getSystemService(android.content.ClipboardManager::class.java)?.primaryClip` 的同步讀取（系統 API，沒有棄用警告），不要改用 `LocalClipboardManager`。
*   **Unit Tests**: `.\gradlew.bat :app:testDebugUnitTest --console=plain`，包含 `SetupChecklistTest` 全部案例與既有 32 個測試，0 失敗。
*   **Static Analysis & Linting**: `.\gradlew.bat :app:lintDebug --console=plain`，0 issues（`warningsAsErrors = true`）。新增 10 組字串必須三個語系齊全且皆被使用。
*   **E2E/Integration**: `powershell -NoProfile -ExecutionPolicy Bypass -File .agents/scripts/run_maestro_e2e.ps1`，`.maestro/` 下 4 支流程全數通過。
*   **手動驗證（實體手機，例如 Pixel 8 Pro）**：(1) 全新安裝後清單顯示三項；(2) 點「前往 Google AI Studio 申請 Key」瀏覽器開啟 `https://aistudio.google.com/apikey`；(3) 在 AI Studio 複製 Key 後回到 App，按「從剪貼簿貼上」欄位填入；(4) 驗證成功後第 1 項打勾；(5) 三項全完成後清單自動隱藏；(6) 以繁體、簡體、英文三種系統語言各確認一次新增字串。
*   **出貨前查證**：對照 Gemini API 服務條款原文確認 Q1，再送審 Google Play。
