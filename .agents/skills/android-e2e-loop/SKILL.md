---
name: android-e2e-loop
description: Autonomous Android build, headless emulator lifecycle, and Maestro E2E verification loop for AKeyboard. Use when adding/modifying Android UI or behavior under app/src/main/ and authoring or running .maestro/*.yaml flows.
---

# AKeyboard Android E2E Loop

## Tool Roles

- **Android CLI (`android`) + Gradle (`.\gradlew.bat`)**: Build `assembleDebug`, check `android install`, inspect UI tree (`android layout`), capture screenshots (`android screen capture`, note `SettingsActivity` has `FLAG_SECURE`), and manage devices (`adb`).
- **Maestro MCP (`maestro mcp`)**: Use during interactive flow authoring to inspect live screen elements (`inspect_screen`, `list_devices`, `run`) on a booted emulator.
- **Maestro Runner (`.agents/scripts/run_maestro_e2e.ps1`)**: Standard verification entrypoint. Handles build, headless AVD lifecycle (`akeyboard_api36`), APK install, `maestro test .maestro/`, `.maestro/.last_run.json` digest stamping, and emulator cleanup.

## Workflow After Changing `app/src/main/`

1. Expose stable selectors on new/updated Compose elements via `Modifier.testTag("<screen>_<element>")` under a container with `Modifier.semantics { testTagsAsResourceId = true }`.
2. Add or update `.maestro/<nn>_<feature>.yaml` using `id: "..."` selectors so flows pass across `en`, `zh-rTW`, and `zh-rCN`.
   - `RemoteDictationActivity` is `android:exported="false"`: launch `SettingsActivity` (`com.akeyboard`) and tap `btn_open_pc_remote` rather than deep-linking directly.
   - `SettingsActivity` keeps `FLAG_SECURE`: screenshots appear black, while Maestro accessibility/resource-id queries work normally.
3. Run unit tests + lint:
   ```powershell
   .\gradlew.bat :app:testDebugUnitTest :app:lintDebug --console=plain
   ```
4. Run full headless Maestro E2E:
   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File .agents/scripts/run_maestro_e2e.ps1
   ```
   - While iterating on a failing flow, pass `-KeepEmulator` (and `-SkipBuild` if Kotlin/resources did not change) and inspect `android layout` or `maestro` MCP; then run one clean invocation without `-KeepEmulator` before finishing.
5. Respect the 3-retry limit on E2E repair loops before reporting blockers.
