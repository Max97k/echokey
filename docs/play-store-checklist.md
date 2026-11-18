# Google Play Store Checklist (v1)

- [x] `compileSdk = 36`, `minSdk = 36`, `targetSdk = 36` (meets August 31, 2026 Play requirement).
- [x] Edge-to-edge enabled (`enableEdgeToEdge()` in `SettingsActivity`, `WindowInsets.navigationBars` in `KeyboardScreen`).
- [x] Prominent in-app disclosure before first use (`DisclosureScreen` in `SettingsActivity`).
- [x] Password and `IME_FLAG_NO_PERSONALIZED_LEARNING` fields disable voice input.
- [x] `allowBackup="false"` and `data_extraction_rules.xml` exclude `files/datastore/`.
- [ ] Host `PRIVACY.md` at a public HTTPS URL and enter it in Play Console.
- [ ] Complete Play Console **Data Safety** form:
  - Audio: Collected & shared with third party (Google Gemini API), App functionality, User-initiated.
- [ ] Provide a test Google AI Studio API key in **App access** instructions for Play reviewers.
- [ ] Create `keystore.properties` with Play App Signing upload key and run `.\gradlew.bat :app:bundleRelease`.
- [ ] Upload to Internal Testing track and verify **Pre-launch Report**.
