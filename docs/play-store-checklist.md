# Google Play Store Checklist (v1)

- [x] `compileSdk = 37`, `minSdk = 31`, `targetSdk = 37` (meets August 31, 2026 Play requirement).
- [x] Edge-to-edge enabled (`enableEdgeToEdge()` in `SettingsActivity`, `WindowInsets.navigationBars` in `KeyboardScreen`).
- [x] Prominent in-app disclosure before first use (`DisclosureScreen` in `SettingsActivity`).
- [ ] First-run disclosure and setup hint match Google's current Gemini API terms (free vs paid data handling), and the in-app wording does not promise unconditional free usage.
- [x] Password and `IME_FLAG_NO_PERSONALIZED_LEARNING` fields disable voice input.
- [x] `allowBackup="false"` and `data_extraction_rules.xml` exclude `files/datastore/`.
- [x] Host privacy policy at a public HTTPS URL and enter it in Play Console: [https://max97k.github.io/echokey/privacy.html](https://max97k.github.io/echokey/privacy.html) (source: [PRIVACY.md](../PRIVACY.md)).
- [ ] Complete Play Console **Data Safety** form:
  - Audio: Collected & shared with third party (Google Gemini API), App functionality, User-initiated.
- [ ] Provide a test Google AI Studio API key in **App access** instructions for Play reviewers.
- [ ] Create `keystore.properties` with Play App Signing upload key and run `.\gradlew.bat :app:bundleRelease`.
- [ ] Upload to Internal Testing track and verify **Pre-launch Report**.
