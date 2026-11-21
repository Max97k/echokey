<div align="center">

# 🎙️ AKeyboard — Android 純語音 AI 輸入法

**一款採用 BYOK（自带金鑰）模式的極簡 Android 語音輸入法，直連 Google AI Studio Gemini Live API（`gemini-3.5-transcribe-live`），並將非錄音區域化為 2D 游標觸控板。**

[![Platform](https://img.shields.io/badge/Platform-Android%2012%2B%20(API%2031--37)-3DDC84?style=flat-square&logo=android&logoColor=white)](https://android.com)
[![Version](https://img.shields.io/badge/Version-v0.3.0-blue?style=flat-square)](https://github.com/Max97k/echokey)
[![Tests](https://img.shields.io/badge/Tests-Passing-brightgreen?style=flat-square)](https://github.com/Max97k/echokey)
[![Target Device](https://img.shields.io/badge/Tested%20On-Google%20Pixel%208%20Pro-4285F4?style=flat-square&logo=google&logoColor=white)](https://store.google.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.21-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose%20%7C%20Material%203-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![BYOK](https://img.shields.io/badge/Privacy-BYOK%20Direct%20%7C%20No%20Backend-success?style=flat-square&logo=shield&logoColor=white)](#-隱私與安全設計)
[![Privacy Policy](https://img.shields.io/badge/Privacy%20Policy-線上網頁-blue?style=flat-square&logo=githubpages&logoColor=white)](https://max97k.github.io/echokey/privacy.html)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg?style=flat-square)](LICENSE)

[English](README.md) • [繁體中文](README_zh.md) • [隱私權政策 (Privacy Policy)](https://max97k.github.io/echokey/privacy.html) ([Markdown](PRIVACY.md)) • [下載 APK](https://github.com/Max97k/echokey/releases/latest) • [設計文件](docs/designs/akeyboard_voice_ime.md)

</div>

---

## 📖 簡介

**AKeyboard** 是以 **Kotlin** 與 **Jetpack Compose (Material 3)** 開發的開源 Android 系統輸入法（`InputMethodService`）。它移除所有傳統打字按鍵，專注提供「按住說話、即時上屏」的語音轉文字體驗，並使用使用者自己的 Google AI Studio API Key（BYOK）直連 Gemini Live API。

按住麥克風期間即時以 Composing Text 預覽辨識結果，放開後自動提交帶標點的最終文字；鍵盤其餘空白區域則作為 **2D 觸控板**，上下左右滑動即可精確移動文字游標。

---

## ✨ 核心功能

| 功能 | 說明 |
| :--- | :--- |
| 🎙️ **按住說話即時轉寫** | 按住麥克風以 16 kHz 16-bit 單聲道 PCM 串流至 `gemini-3.5-transcribe-live`，邊說邊預覽，放開即上屏。 |
| ⚡ **WebSocket 預熱連線** | 輸入法彈出時預先建立連線（45 秒閒置 TTL），冷啟動時以 2 秒環形緩衝暫存開頭音訊，避免漏字。 |
| 👆 **2D 觸控板游標控制** | 在非麥克風區域滑動即可發送 `DPAD` 方向鍵移動游標，支援震動回饋、多行保護與左右 `24dp` 防側滑返回死區。 |
| 🌐 **語言與智慧轉錄模式** | 支援預設語言選擇（預設 `zh-TW` 繁體中文、中英混輸、自動偵測 85+ 語言、簡中、英、日、韓）與 `SMART` 贅詞過濾 / `VERBATIM` 逐字模式切換。 |
| 🔑 **BYOK 與動態模型清單** | 設定頁輸入個人 Google AI Studio API Key 後自動查詢 `models.list`（`bidiGenerateContent`），可自由切換即時模型。 |
| 🎨 **Material 3 原生手感** | 動態取色、Material Rounded 矢量圖示、語意動作鍵（搜尋／送出／下一步／完成／換行）、長按退格連續刪除與可選的切換輸入法鍵。 |

---

## 🚀 快速開始

> [!IMPORTANT]
> **地區可用性與計費限制提醒**
> 本應用程式僅支援 **Google AI Studio (Gemini API)**，使用權限受 Google 官方地區白名單規範：
> - **不支援地區：** 中國大陸、香港、澳門、俄羅斯等地區無法使用（若身處支援地區但掛載上述地區之 VPN 節點亦會遭阻擋）。
> - **歐盟 / EEA / 英國 / 瑞士：** 依當地法規不提供免費額度（Free Tier），須於 Google Cloud 綁定計費帳號後方可呼叫 API。
> - 設定前請先確認 [Google 官方支援地區名單](https://ai.google.dev/gemini-api/docs/available-regions)。

1. 前往 [Google AI Studio](https://aistudio.google.com/apikey)（或在 AKeyboard 設定頁點選「**前往 Google AI Studio 申請 Key**」），登入 Google 帳號、建立並複製 API Key。
2. 回到 AKeyboard 設定頁，點選「**從剪貼簿貼上**」後按「**驗證並儲存 Key**」。
3. 依設定頁上方的「**開始使用**」清單，在系統設定中啟用 AKeyboard 並允許麥克風權限。

---

## 🛡️ 隱私與安全設計

* **無開發者後端**：音訊僅在按住錄音時經加密通道（`wss://`）直連 Google Gemini API，本機不寫入任何錄音檔。
* **硬體級金鑰加密**：API Key 使用 **Android Keystore (AES-256-GCM)** 加密後存入 Jetpack DataStore，並關閉雲端備份與裝置遷移。
* **密碼與隱私欄位保護**：偵測到密碼框或 `IME_FLAG_NO_PERSONALIZED_LEARNING` 時自動停用麥克風。
* **顯著揭露與防截圖**：首次使用前強制展示資料揭露聲明，設定頁啟用 `FLAG_SECURE` 防止金鑰外洩。

---

## 💻 Windows 10/11 藍牙語音遙控輸入

針對封鎖 Google AI Studio 連線或禁止未簽章 `.exe` 的公司電腦，AKeyboard 提供 Windows 10/11 專用、免安裝且僅使用標準函式庫的 Python 3.10+ 接收腳本（`tools/akeyboard_bridge.py`）與 App 內專屬「**電腦藍牙語音遙控**」畫面：

1. 先將手機與 Windows 10/11 電腦完成藍牙配對。
2. 使用前請先將 Windows 輸入法切換為**英文**（若使用 Windows 注音輸入法會導致標點符號跑掉），並在 Windows 10/11 執行 `python tools/akeyboard_bridge.py`（僅使用官方 CPython 3.10+ 標準函式庫，免 `pip install`、免系統管理員權限）。
3. 在手機打開 **AKeyboard 設定 → 開啟電腦藍牙語音遙控輸入**，連上電腦後按住麥克風說話，即可將繁體中文與中英混雜文字直接打在電腦游標處。

*（限制：Windows 10/11 需安裝具備藍牙 Socket 支援的官方 Python 3.10+、公司群組原則須允許藍牙 RFCOMM；目標視窗須處於前景焦點，且一般權限腳本受 UIPI 限制無法輸入至系統管理員 UAC 提權視窗。）*

---

## 📄 授權條款

採用 MIT 授權條款，詳見 [LICENSE](LICENSE)。
