# AKeyboard Release & Version Alignment Workflow

This project distributes updates via **Google Play (Internal Testing)** while keeping **GitHub Releases** (`Max97k/voice-keyboard-pc-bridge`) as the canonical source of release notes.

## 1. Version Mapping Rule (Single Source of Truth)

| Artifact | Format | Example | Notes |
| :--- | :--- | :--- | :--- |
| `app/build.gradle.kts` `versionName` | `X.Y.Z` (SemVer) | `0.3.0` | Human-facing version; matches GitHub Release tag without `v` |
| `app/build.gradle.kts` `versionCode` | Strictly increasing integer | `2` | Required by Google Play for every new `.aab` upload |
| GitHub Git Tag & Release | `vX.Y.Z` | `v0.3.0` | Canonical full Markdown release notes (`https://github.com/Max97k/voice-keyboard-pc-bridge/releases/tag/v0.3.0`) |
| Google Play Release Name | `vX.Y.Z (<versionCode>)` | `v0.3.0 (2)` | Links Play track release back to GitHub tag + build number |
| In-App `ⓘ` Button (`SettingsActivity`) | `.../releases/tag/v<versionName>` | `.../releases/tag/v0.3.0` | Opens the installed version's GitHub Release Notes |

> **Note:** GitHub already has tags `v0.1.0` and `v0.2.0`. Starting from the next release, pass `--version-name 0.3.0` (and continue incrementing `versionName` monotonically alongside `versionCode`) so every Play release resolves to a fresh GitHub Release tag.

## 2. Release Notes Alignment (GitHub ↔ Google Play)

- **GitHub Release (`vX.Y.Z`)**: Holds the full Markdown changelog, Play track name, and `versionCode` (no sideload APK attachment is required when distributing through Google Play).
- **Google Play Release Notes (`en-US`, `zh-TW`)**: Automatically formatted by `tools/publish_play_internal.py` to keep the summary within Google Play's **500-character limit** while always preserving the direct link `https://github.com/Max97k/voice-keyboard-pc-bridge/releases/tag/vX.Y.Z`.

## 3. Standard Release Steps

1. Commit and push your changes on `main` (working tree must be clean and synced with `origin/main` when using `--github-release`).
2. Preview the release plan without modifying files:
   ```powershell
   python tools/publish_play_internal.py --dry-run --version-name 0.3.0 --github-release
   ```
3. Build, verify, publish to Google Play `internal` track, and create/update the GitHub Release:
   ```powershell
   python tools/publish_play_internal.py --version-name 0.3.0 --notes-file notes.md --github-release
   ```
   *(Or pass a short summary inline with `--release-notes "..."`.)*
4. Commit the updated `versionCode` / `versionName` in `app/build.gradle.kts` to `main` (and push) after publishing, or bump `versionName` before committing if you prefer the GitHub tag to match the exact `build.gradle.kts` commit.
