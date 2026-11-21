# Android 纯语音输入法键盘（AKeyboard）

*   **Author:** b
*   **Status:** Proposed
*   **Date:** 2026-11-18

--------------------------------------------------------------------------------

## Overview

AKeyboard 是一款面向 Android 16（API Level 36）的系统级语音输入法（`InputMethodService`），仅提供语音转文字输入与游标控制，不包含传统字符键盘或候选词栏。用户输入自己的 Google AI Studio API Key（BYOK），应用通过客户端直连 Google Gemini Live API 的实时语音转写模型（默认预设 `gemini-3.5-transcribe-live`），实现按住说话、边说边预览、松手提交，并在面板其余区域提供 2D 触控板用于移动输入游标。v1 目标为上架 Google Play 商店。

## The Problem

1. **现有系统与第三方输入法语音能力受限**：传统输入法自带的语音识别在中英混输（code-switching）、自动标点与口语去冗余（如去除语气词）上能力有限，难以直接利用最新的云端多模态大模型。
2. **大模型语音工具未下沉至系统输入法层**：用户若想使用 Google AI Studio 的语音转写能力，只能在独立应用或网页中录音后再复制粘贴到目标应用，无法在任意输入框内直接上屏。
3. **缺少可控的个人 Key 直连方案**：现有云端大模型语音工具多由第三方服务端代理，用户无法使用个人 Google AI Studio API Key 控制模型选择、计费与直连链路。

## Goals

*   **Android 16 与 Google Play v1 上架**：仅支持 Android 16（`minSdk = 36`、`targetSdk = 36`、`compileSdk = 36`），满足 2026 年 8 月 31 日起生效的 Google Play 新应用 `targetSdk >= 36` 要求，并完成 Edge-to-Edge 适配、隐私政策与 Data Safety 申报。
*   **按住说话（Push-to-Talk）低延迟上屏**：用户按住麦克风按钮即开始采集 16kHz PCM 音频并实时推流，输入框内通过 Composing Text（未确认的组合预览文本）实时显示中间结果，松手后提交最终带标点文本。目标首字预览延迟 < 800ms（在 Step 0 真机/CLI 实测验证）。
*   **键盘区域触控板（Touchpad）游标控制**：除录音键、右列 3 个最小辅助键（退格、回车/发送、切换输入法）及左右边缘手势安全区外，面板其余区域作为 2D 触控板，按住上下左右滑动即可精确移动游标（步长可在设置页调节）。
*   **BYOK 与动态模型发现**：设置页通过 Android Keystore（AES-256-GCM）与 DataStore 加密保存用户 API Key；验证 Key 后通过 `models.list` 动态拉取支持 `bidiGenerateContent` 的模型列表供用户切换（默认预设 `gemini-3.5-transcribe-live`）。
*   **多语言支持**：v1 提供英文（默认）、繁体中文（`zh-rTW`）与简体中文（`zh-rCN`）界面文案。

## Non-Goals

*   **不提供传统打字键盘**：不实现 26 键、九宫格、手写、滑行输入、候选词联想栏、Emoji 面板或剪贴板历史。
*   **不做双向语音对话助手**：输入法不播放 AI 语音回复（TTS），也不将口述内容当作问答指令生成回复。
*   **不搭建 AKeyboard 自有后端代理**：客户端直连 Google AI Studio 官方接口，不经由开发者自建服务器中转音频或 Key（注：Google 作为 API 提供方会接收并处理音频流）。
*   **不支持历史 Android 版本**：v1 明确将安装范围限定在 Android 16（API 36）及以上设备，不为旧版系统做兼容降级。
*   **不支持离线本地语音识别**：v1 不内置端侧 ASR 模型（亦不使用系统 `SpeechRecognizer`），无网或弱网下直接提示网络错误。

## Resolved Decisions（原开放问题已收敛）

1. **最小辅助控制键**：面板右侧保留「退格删除」、「回车/动作键（`performEditorAction`）」、「切换输入法（地球键）」3 个最小按键。无退格键时错字无法修改；无切换键不符合输入法基础可用性与 Play 审核预期。
2. **对话型 Live 模型的兼容边界**：设置页下拉列表展示全部支持 `bidiGenerateContent` 的模型，并将名称含 `transcribe` 的模型置顶。若用户选择对话型模型（如 `gemini-3.8-live`），客户端在 `setup` 中请求 `responseModalities = ["TEXT"]`（若原生音频模型拒绝 `TEXT`，则回退为 `["AUDIO"]` 并静默丢弃服务端音频输出），仅提取输入侧转写文本。

## Critical User Journeys (CUJs)

### CUJ 1: 首次配置 API Key 与选择模型

*   **Given**: 用户已在 Android 16 设备安装 AKeyboard，首次打开 `SettingsActivity`。
*   **When**: 用户阅读并同意显著隐私披露，授予以 `RECORD_AUDIO` 权限、在系统设置启用 AKeyboard，随后粘贴 Google AI Studio API Key 并点击「验证并保存」。
*   **Then**: 应用调用 `GET /v1beta/models` 验证 Key 并拉取实时模型列表，使用 Keystore 加密保存 Key 后默认选中 `gemini-3.5-transcribe-live`（若不可用则选列表中首个 `transcribe` 模型，再其次首个可用模型），并提示用户可切换至该输入法使用。

### CUJ 2: 按住说话实时语音转文字上屏

*   **Given**: 用户已完成 Key 与权限配置，在非密码类的文本输入框中切换至 AKeyboard（输入法视图已弹出并完成 WebSocket 预热）。
*   **When**: 用户按住麦克风按钮并口述中英混杂的句子，随后松开手指。
*   **Then**: 按住瞬间发送 `activityStart` 并推流 16kHz PCM 音频，输入框以 Composing Text 实时刷新预览；松手后发送 `activityEnd`，等待最终转写结果并一次性提交最终文本。若断网、配额不足或 Key 失效，状态栏提示错误并提供跳转设置页入口。

### CUJ 3: 触控板手势控制输入游标

*   **Given**: 用户正在使用 AKeyboard，当前处于 `IDLE` 或 `WARM` 状态（未录音），输入框内已有文本。
*   **When**: 用户在触控板有效区域（排除录音键、右侧 3 键及左右 24dp 边缘防误触区）按住并上下左右滑动。
*   **Then**: 按设置页配置的灵敏度步长（默认水平 `16dp`、垂直 `28dp`）换算为 `DPAD` 方向键事件并触发轻微触觉反馈，移动输入游标（垂直移动仅在多行输入框生效）。

## Technical Plan (Architecture)

### 1. 平台与 Google Play 合规基线

*   **SDK 与全面屏**：`compileSdk = 36`、`targetSdk = 36`、`minSdk = 36`。Android 16 已移除 `windowOptOutEdgeToEdgeEnforcement` 逃生通道，`SettingsActivity` 必须调用 `enableEdgeToEdge()`，IME 面板需消费 `WindowInsets.navigationBars` 与 `displayCutout` 防止按钮被导航条遮挡。
*   **权限最小化**：仅声明 `android.permission.RECORD_AUDIO`、`android.permission.INTERNET`、`android.permission.ACCESS_NETWORK_STATE`（用于按住前快速检查网络状态），并声明 `<uses-feature android:name="android.hardware.microphone" android:required="false"/>`。
*   **权限申请与隐私边界**：`InputMethodService` 无法可靠弹出系统权限弹窗，统一在 `SettingsActivity` 申请 `RECORD_AUDIO`；若权限被撤回，IME 状态栏显示提示与深链跳转按钮。应用**自身不将任何音频写入本地磁盘**，亦不建后端存储；仅在用户按住录音期间采集并直连发送至 Google Gemini API。
*   **密码与隐私框保护**：`onStartInputView` 检测 `EditorInfo.inputType`（按 `TYPE_MASK_CLASS` 与 `TYPE_MASK_VARIATION` 掩码比对密码类型，以及 `IME_FLAG_NO_PERSONALIZED_LEARNING`）时，禁用麦克风按钮并提示不支持语音输入。

### 2. 系统架构与核心数据流

```text
+-----------------------------------------------------------------------+
|                           Android 16 System                           |
|  [Target App InputConnection] <-------> [SettingsActivity (Compose)]  |
+---------------^-----------------------------------^-------------------+
                | setComposingText / commitText /   | Save Key / Select
                | sendKeyEvent (DPAD/DEL/ENTER)     | Model / Sensitivity
+---------------+-----------------------------------+-------------------+
|           AKeyboardImeService (InputMethodService + Lifecycle)        |
|  +-------------------------+       +-------------------------------+  |
|  |   KeyboardScreen (UI)   |       |   TouchpadCursorController    |  |
|  | - Push-to-Talk Mic      |------>| - Step Accumulator (dp -> px) |  |
|  | - 3 Aux Keys (DEL/ENT/  |       | - Multiline Guard & Haptics   |  |
|  |   Switch IME)           |       +-------------------------------+  |
|  | - TouchpadSurface       |                                          |
|  +------------+------------+                                          |
|               |                                                       |
|               v                                                       |
|  +-------------------------+       +-------------------------------+  |
|  |   AudioCaptureManager   |------>|       GeminiLiveClient        |  |
|  | (AudioRecord 16kHz PCM  | Frame | (OkHttp WSS + Pre-warm +      |  |
|  |  40ms / 1280B Channel)  |       |  Transcript Buffer & Commit)  |  |
|  +-------------------------+       +---------------+---------------+  |
+----------------------------------------------------+------------------+
                                                     | Read Key / Model
                                     +---------------v---------------+
                                     | ApiKeyStore & SettingsRepo    |
                                     | (Keystore AES-256-GCM +       |
                                     |  Preferences DataStore)       |
                                     +---------------^---------------+
                                                     | Validate & List
                                     +---------------+---------------+
                                     |      ModelRegistryClient      |
                                     | (GET /v1beta/models via       |
                                     |  x-goog-api-key header)       |
                                     +-------------------------------+
```

### 3. 核心机制与状态机

*   **统一状态机（5 状态）**：`IDLE`（未连网/待机）→ `WARM`（`onStartInputView` 触发预热完成）→ `RECORDING`（按住推流中）→ `FINALIZING`（松手等待末尾转写，最长 2000ms）→ 回到 `WARM`/`IDLE`；任意环节出错进入 `ERROR(reason)`，在用户下次点击触控/录音或切换输入框时重置回 `IDLE`/`WARM`。
*   **WebSocket 预热与冷启动回退**：为消除按住说话时的 TCP+TLS+WS 握手延迟（约 300–800ms），在 `onStartInputView`（且非密码框、Key 与权限均就绪）时预先建立 WebSocket 并发送 `setup` 握手进入 `WARM` 状态，空闲超时（TTL 45s）或 `onFinishInputView`/`onWindowHidden` 时主动关闭。若用户按住时连接尚未 `WARM` 或已过期断开，则即时发起冷连接，并在握手期间使用有界环形缓冲区（容量 50 帧 = 2s）暂存开头音频，确保不丢字。
*   **手动 VAD 与单一文本提交规则**：
    *   `setup` 消息显式关闭服务端自动 VAD（`realtimeInputConfig.automaticActivityDetection.disabled = true`），并配置 `inputAudioTranscription` 与 `responseModalities = ["TEXT"]`。
    *   按住（`awaitEachGesture` 按下）立即发送 `realtimeInput.activityStart` 并推流；松开或手势取消（`waitForUpOrCancellation() == null`）发送 `realtimeInput.activityEnd`，进入 `FINALIZING`（硬超时 2000ms，单次按住上限 60s）。
    *   **唯一提交规则**：`GeminiLiveClient` 内部维护单次录音的当前转写缓冲；收到转写分片时一律通过 `InputConnection.setComposingText` 实时刷新预览（具体累积或增量拼接方式在 Step 0 Spike 验证后定型）；仅在收到最终完成信号或触发 2000ms 超时后，统一调用一次 `commitText`（或 `finishComposingText` 确保不重复）提交最终文本并清空缓冲。`FINALIZING` 期间忽略新的按住事件；在 `onFinishInputView` 时对 `currentInputConnection` 判空并安全收尾。
*   **API Key 传递与脱敏**：遵循 Google 官方文档做法，WebSocket 握手使用 URL 查询参数 `?key=`，REST `models.list` 使用请求头 `x-goog-api-key`。客户端严禁开启 OkHttp HTTP 日志拦截器，并在 `Failure(cause)` 映射至 UI 错误或崩溃上报前强制正则脱敏 URL 中的 `key=` 参数。
*   **触控板手势与防冲突**：非录音/非辅助键区域为触控板，并在左右两侧各保留 `24dp` 起始死区（手势起点落在死区内时不拦截，避免与 Android 16 侧滑返回冲突；起点在有效区内的横向拖拽不受影响），同时配合 `systemGestureExclusion`。垂直方向通过 `EditorInfo.inputType` 按掩码判断是否多行文本，单行框忽略垂直位移以免焦点跳出。

--------------------------------------------------------------------------------

## Alternatives Considered

### Alternative A: 非流式转写（录完整段后上传 `gemini-3.5-transcribe`，REST 调用）

*   **Pros**: 单次 HTTP 请求，无需维护 WebSocket 连接与超时状态机；支持说话人分离等离线分析特性。
*   **Cons**: 推理必须等待松手且整段音频上传完毕后才开始，延迟随说话时长线性增加，且无法边说边预览。
*   **Reason for Rejection**: Rejected by author: 非流式方案无法满足低延迟实时上屏与边说边预览的核心体验需求。

### Alternative B: 点击开始 / 再次点击结束的录音模式

*   **Pros**: 长段口述时无需持续按住屏幕。
*   **Cons**: 录音期间需要额外的全局停止状态与防误触设计，容易与键盘面板的“非录音区即触控板”单指滑动手势产生状态混淆。
*   **Reason for Rejection**: Rejected by author: 按住说话（Push-to-Talk）交互链路更短，且能与触控板手势天然隔离。

### Alternative C: 通过自建后端代理调用 Gemini

*   **Pros**: 可隐藏客户端 API Key，或签发短期 Ephemeral Token 用于 WebSocket 认证。
*   **Cons**: 需要运维中转服务器并经手用户语音数据，破坏 BYOK 数据直连隐私边界并增加运营成本。
*   **Reason for Rejection**: Rejected by author: 产品采用无后端 BYOK 模式，避免第三方服务器接触用户音频与 Key。

### Alternative D: 仅限专用转写模型，禁止选择对话型 Live 模型

*   **Pros**: 客户端无需处理原生音频模型的 `responseModalities` 兼容与回复丢弃逻辑。
*   **Cons**: 限制了用户在其 API Key 可用资源中的选择自由度；若未来 Google 调整模型命名，硬过滤会导致列表为空。
*   **Reason for Rejection**: Rejected by author: 保留全部 `bidiGenerateContent` 模型可见（`transcribe` 置顶），由客户端兼容输入侧转写提取。

### Alternative E: 端侧离线语音识别（Android `SpeechRecognizer` 或内置模型）

*   **Pros**: 无网络依赖，音频完全不离开设备。
*   **Cons**: 无法使用 Google AI Studio 大模型的语义纠错、中英混输与智能标点能力；内置端侧模型会显著增大安装包体积。
*   **Reason for Rejection**: Rejected by author: 产品定位为直连 Google AI Studio 线上模型的语音输入法。

### Alternative F: 使用 `androidx.security:security-crypto`（`EncryptedSharedPreferences`）

*   **Pros**: 封装接口简单。
*   **Cons**: 该 Jetpack 库已在 1.1.0 后正式标记为废弃（Deprecated），不再获得维护且在部分 OEM 机型的密钥库异常恢复上存在历史缺陷。
*   **Reason for Rejection**: Rejected by author: 采用官方当前推荐的 Android Keystore（AES-256-GCM）+ Jetpack DataStore 组合替代。

--------------------------------------------------------------------------------

## Risks & Mitigations

| 风险类别 | 具体风险描述 | 缓解措施 |
| :--- | :--- | :--- |
| **API 合约与模型漂移** | `gemini-3.5-transcribe-live` 及 `inputAudioTranscription` 预览期字段/错误码可能与文档有差异，或返回分片格式（累积 vs 增量）不明确。 | 将 **Step 0 Spike** 作为编码前强制前置验证，用真实 WAV 音频直连 `BidiGenerateContent` 记录完整报文与首字延迟；JSON 解析开启 `ignoreUnknownKeys = true`。 |
| **API Key 泄露风险** | WebSocket URL 携带 `?key=` 可能在异常堆栈、网络拦截器或系统代理中暴露；本地加密密钥若被备份迁移会引发异常。 | 严禁启用 HTTP Body/URL 日志；异常上报与 UI 错误文本前强制清洗 `key=` 参数；`allowBackup="false"` 且 `data_extraction_rules` 排除 `files/datastore/`；设置页开启 `FLAG_SECURE`。 |
| **音频隐私与 Play 审核** | 输入法同时拥有 `RECORD_AUDIO` 与 `INTERNET` 权限，若未显著披露或误在密码框录音将违反 Google Play 政策（含免费层 Key 数据可能被 Google 用于模型改进的风险）。 | 首次使用设置页强制展示显著披露并记录同意状态；提供公开 `PRIVACY.md` 并准确申报 Play Data Safety（收集并传输音频至 Google）；`onStartInputView` 遇到密码框或隐身标志强制禁用麦克风。 |
| **配额、计费与网络异常** | BYOK 免费层 RPM/并发受限，长按可能耗尽配额；Gemini Invalid Key 常通过 HTTP 400 `API_KEY_INVALID` 或 WS Close Code `1007/1008` 返回而非仅 401/403；弱网下可能丢帧或超时。 | 映射 HTTP 400/401/403/429 及 WS `1007/1008` 为明确错误态；单次按住硬上限 60s 防止误触持续扣费；预热设 45s 空闲 TTL 避免长期占线；有界音频队列防 OOM，断线不自动重连以免重复计费。 |
| **硬件与系统冲突** | 录音期间被系统来电抢占麦克风、用户开启系统麦克风隐私开关、或横向滑动触控板误触发 Android 16 预测性返回手势。 | `AudioRecord` 检查 `STATE_INITIALIZED` 与 `read()` 错误码并在 `onFinishInputView`/`onWindowHidden` 立即释放；触控板设置左右 `24dp` 起始死区结合 `systemGestureExclusion`；目标 app 忽略 `DPAD` 键时记录为已知限制（部分 WebView/游戏）。 |

--------------------------------------------------------------------------------

## Assumptions to Verify（Step 0 前置验证清单）

在编写正式 App 代码前，必须通过独立 CLI/测试脚本连线真实 Google AI Studio Key 核实以下 5 项假设：
1. `gemini-3.5-transcribe-live` 在 `GET /v1beta/models` 与 `BidiGenerateContent` 中真实可用。
2. `setup` 消息中 `inputAudioTranscription` 接受的具体字段（是否支持 `languageCodes` / `mode: "SMART"` 或仅为空对象 `{}`）。
3. 服务端转写返回字段结构（是否存在独立的 `interimInputTranscription`，抑或统一通过 `serverContent.inputTranscription` 下发，以及文本是增量 delta 还是全量累积）。
4. `realtimeInput.activityStart` / `activityEnd` 在关闭自动 VAD 后的真实触发与 `turnComplete` 时序，以及对话型模型（如 `gemini-3.8-live`）对 `responseModalities: ["TEXT"]` 的兼容表现。
5. 冷连接与预热连接两种模式下的实测首字延迟（TTFT）。

--------------------------------------------------------------------------------

## Detailed Implementation Plan

> **构建基线（实现前于官方发布页复核）**：单模块 `:app`，`compileSdk = 36`、`minSdk = 36`、`targetSdk = 36`，JVM Target 17。参考版本基线：AGP `9.5.1`（内置 Kotlin 支持）、Kotlin `2.4.21`、Compose BOM `2026.09.00`；核心依赖：`lifecycle-service`、`datastore-preferences`、`activity-compose`、`okhttp`、`kotlinx-serialization-json`。

### 模块与接口概览

| 模块 / 组件 | 职责定位 | 关键接口与实现约束 |
| :--- | :--- | :--- |
| **`AKeyboardApp` & `DataStore` / `ApiKeyStore`** | 应用级组合根与加密存储 | 提供进程内唯一 `Preferences DataStore` 实例与共享 `OkHttpClient`；使用 Android Keystore（AES-256-GCM，12B IV，128-bit Tag）加密 API Key 后以 Base64 字符串写入 DataStore；配置 `allowBackup="false"` 与 `data_extraction_rules` 排除 `files/datastore/`。 |
| **`ModelRegistryClient`** | 模型发现与 Key 验证 | 请求 `GET /v1beta/models?pageSize=1000`（带 `x-goog-api-key` 头，支持 `nextPageToken` 翻页）；过滤 `supportedGenerationMethods` 含 `bidiGenerateContent` 的模型，名称含 `transcribe` 置顶；拦截 `location is not supported` / `FAILED_PRECONDITION` 映射为 `RegionNotSupported`，400 `API_KEY_INVALID`/401/403 为 Key 无效错误。 |
| **`AudioCaptureManager`** | 低延迟 PCM 音频采集 | 基于 `AudioRecord`（`VOICE_RECOGNITION`、16kHz、Mono、16-bit PCM）；校验 `STATE_INITIALIZED` 与 `read()` 错误码；按 40ms（1280B）切片写入容量 50 帧的有界 `Channel`（`DROP_OLDEST`）。 |
| **`GeminiLiveClient`** | Live API WebSocket 引擎 | 管理 `WARM` 预热连接（45s TTL）与冷连接回退；配置 `explicitNulls = false` 避免冗余 null 字段；发送 `setup`、手动 `activityStart`/`activityEnd` 与 Base64（`NO_WRAP`）音频帧；`finishUtterance` 先 join 串流协程并确保已送出 `activityStart` 再发 `activityEnd` 防止时序竞争；暴露 `Flow<LiveEvent>`；拦截 `location is not supported` 映射为 `RegionNotSupported`，映射 HTTP 400/401/403/429 及 WS Close Code `1007/1008` 并脱敏 URL 中的 `key=`。支持注入 `baseUrl` 供 `MockWebServer` 测试。 |
| **`TouchpadCursorController`** | 纯 Kotlin 游标手势引擎 | 接收 `dx`/`dy`（px）与可配置的 `stepXPx`/`stepYPx`、`multiline` 标志及 `onKey`/`onHaptic` 回调；按步长累加并触发 `DPAD` 事件与 `CLOCK_TICK` 触觉；单行框丢弃垂直位移；单次事件限制最大发键数防止甩动失控。 |
| **`AKeyboardImeService` & `KeyboardScreen`** | IME 服务与 Compose 面板 | 实现 `LifecycleOwner`、`ViewModelStoreOwner`、`SavedStateRegistryOwner`（`ServiceLifecycleDispatcher` + `SavedStateRegistryController`）；管理 5 状态机；`onStartInputView` 检测密码框禁用录音并触发预热；`KeyboardScreen` 包含按住录音键（`awaitEachGesture`）、右列 3 辅助键与带 `24dp` 左右起始死区的 `TouchpadSurface`。 |
| **`SettingsActivity` & `SettingsViewModel`** | 配置、披露与权限入口 | 开启 `enableEdgeToEdge()` 与 `FLAG_SECURE`；首次强制显著隐私披露；引导申请 `RECORD_AUDIO` 与系统启用输入法；提供 Key 验证保存、动态模型下拉框、触控板灵敏度滑块与清除 Key 功能；支持 `en`、`zh-rTW`、`zh-rCN`。 |

### Verification Plan

*   **Step 0: Live API 合约 Spike（编码前必做）**
    *   编写独立 CLI/单元测试脚本，读取本地测试 Key 与 16kHz WAV 文件直连 `BidiGenerateContent`，验证上述「Assumptions to Verify」全部 5 项，并实测记录冷连接与预热连接的首字预览延迟（目标 < 800ms）。
*   **Build & Static Analysis**
    *   `./gradlew :app:assembleDebug`、`./gradlew :app:bundleRelease`（验证 R8 压缩及 `kotlinx.serialization` Keep 规则不破坏反序列化）。
    *   `./gradlew :app:lintDebug`（开启警告检查，重点排查 Edge-to-Edge Insets、权限与无障碍标签）。
*   **Unit & Instrumentation Tests**
    *   `./gradlew :app:testDebugUnitTest`：覆盖 `TouchpadCursorController`（步长累加、方向反转、单行拦截）、`GeminiLiveClient`（使用 `MockWebServer` 验证预热、握手缓存、`2000ms` 超时提交、错误码映射与 URL 脱敏）以及 `ModelRegistryClient`（分页、过滤排序、400/401 错误映射）。
    *   `./gradlew :app:connectedDebugAndroidTest`（Android 16 模拟器/真机）：验证 `ApiKeyStore` Keystore 加解密与清除恢复、以及 IME 启用后的密码框禁麦与基础交互。
*   **Manual & Play Store Pre-Launch Checks**
    *   真机实测 3 条 CUJ（含中文、英文、中英混输、微信/备忘录/浏览器多行与单行输入框），确认横向滑动游标不误触发系统侧滑返回。
    *   测试飞行模式、无效 Key、撤回麦克风权限、来电打断 4 类异常路径。
    *   上传内部测试轨道并检查 Google Play Console Pre-launch Report、Data Safety 表单及隐私政策公网链接。

--------------------------------------------------------------------------------

*Drafted with EGM*
