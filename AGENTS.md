# AKeyboard Agent Verification Rules

For any change under `app/src/main/`, complete ALL of the following before declaring the task done:

1. **Sync E2E Flows**: Add or update `.maestro/<feature>.yaml` covering the modified or new behavior using stable `id:` selectors (`testTag` with `testTagsAsResourceId = true`, never locale-dependent text alone).
2. **JVM Tests & Lint**: Run `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug --console=plain` and ensure 0 failures and 0 lint issues (`warningsAsErrors = true`).
3. **Headless Emulator + Maestro Gate**: Run `powershell -NoProfile -ExecutionPolicy Bypass -File .agents/scripts/run_maestro_e2e.ps1` (builds `assembleDebug`, reuses an online emulator or boots `emulator -avd akeyboard_api36 -no-window -no-audio`, installs the APK via `android install` / `adb`, runs `maestro test .maestro/`, and shuts down any emulator it started).
4. **Retry Cap**: If Maestro E2E fails, fix and re-run at most 3 rounds. If still blocked on environment/emulator/tooling after 3 attempts, stop and report the exact failure.

## Headless Emulator Constraints (Do Not Violate)

- **Do NOT** attempt real Windows Bluetooth Classic RFCOMM pairing, live voice streaming under `-no-audio`, or live paid Gemini API network calls in `.maestro/` tests. Keep flows focused on deterministic local UI/state, permission gates, IME selection/reconciliation, offline/BT-off Direction II UI, Settings validation, and layout checks.
- **Do NOT** remove `WindowManager.LayoutParams.FLAG_SECURE` from `SettingsActivity` or set `android:exported="true"` on `RemoteDictationActivity` for testing convenience.

## Windows Voice Bridge Dual-Script Sync Rules (雙版本同步規則)

`tools/akeyboard_bridge.py` 為唯一功能基準（Single Source of Truth, SSOT）。
**今後只要 `tools/akeyboard_bridge.py` 有任何邏輯、按鍵映射、協定或錯誤處理的變動，必須在同一次修改中同步更新 `tools/akeyboard_bridge.min.py` 與 `tools/ERROR_CODES.md`**，確保三者行為與錯誤代碼定義永遠一致。
