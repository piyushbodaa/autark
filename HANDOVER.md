# Autark — handover (23 Oct 2026)

**What:** Android app (package `com.piyushbodaa.autark`) to chat with small AI models fully offline. Kotlin + Jetpack Compose UI, llama.cpp via JNI (`app/src/main/cpp`, submodule `third_party/llama.cpp`).

## State
- 0.1.0 (versionCode 1) live on Play **internal testing** since 5:02 PM IST, 22 Oct.
- 0.2.0 (versionCode 2) built and emulator-tested (`~/Downloads/Autark-0.2.0.aab` on lappy), **not yet uploaded**. Release notes: "New design. Autark now picks the best model for your phone automatically." That aab predates the new icon (23 Oct), so rebuild it before uploading.
- Play: production access needs a closed test with 12+ testers opted in for 14 days. Closed testing unlocks once Play Console's "Set up your app" tasks are done (none were on 23 Oct).
- Signing: upload key is kept locally on lappy (`keystore.properties` -> `~/Keys/autark`). It is NOT in this repo, so signed release builds can only be made on lappy.

## Recent decisions (0.2.0)
- Redesign to match piyushbodaa.com: Inter Tight font, paper/ink/grey palette, uppercase two-tone headlines, night setup screen + drawer, rounded composer.
- New icon (23 Oct): Inter Tight's A with an orange core where the crossbar was, legs in a headline's two tones, on night. Source, tools and design board in `design/`.
- No model picker: Qwen 3.5 2B on 6 GB+ RAM devices, Qwen 3.5 0.8B otherwise; single "Download · 1.3 GB" first-run button. Models screen and GGUF import removed; upgrading from 0.1.0 deletes other models.

## Next
- Rebuild 0.2.0 with the new icon, upload it to internal testing; gather Sir's feedback (screenshots) and iterate.
- Finish Play's setup tasks and start a closed test with 12+ testers. Keep testers' email addresses out of this repo: it's public.

## Release flow
1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. On lappy: `./gradlew bundleRelease` (needs `keystore.properties`), copy aab to `~/Downloads/Autark-<ver>.aab`.
3. Upload to Play Console > Internal testing > Create release, name = version, notes en-US, Save and publish. Internal testing only.
