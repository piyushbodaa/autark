# Autark — handover (22 Oct 2026)

**What:** Android app (package `com.piyushbodaa.autark`) to chat with small AI models fully offline. Kotlin + Jetpack Compose UI, llama.cpp via JNI (`app/src/main/cpp`, submodule `third_party/llama.cpp`).

## State
- 0.1.0 (versionCode 1) live on Play **internal testing** since 5:02 PM IST, 22 Oct.
- 0.2.0 (versionCode 2) built and emulator-tested (`~/Downloads/Autark-0.2.0.aab` on lappy), **not yet uploaded**. Release notes: "New design. Autark now picks the best model for your phone automatically."
- Signing: upload key is kept locally on lappy (`keystore.properties` -> `~/Keys/autark`). It is NOT in this repo, so signed release builds can only be made on lappy.

## Recent decisions (0.2.0)
- Redesign to match piyushbodaa.com: Inter Tight font, paper/ink/grey palette, uppercase two-tone headlines, night setup screen + drawer, rounded composer, "A + orange dot" icon.
- No model picker: Qwen 3.5 2B on 6 GB+ RAM devices, Qwen 3.5 0.8B otherwise; single "Download · 1.3 GB" first-run button. Models screen and GGUF import removed; upgrading from 0.1.0 deletes other models.

## Next
- Upload 0.2.0 to internal testing; gather Sir's feedback (screenshots) and iterate.

## Release flow
1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. On lappy: `./gradlew bundleRelease` (needs `keystore.properties`), copy aab to `~/Downloads/Autark-<ver>.aab`.
3. Upload to Play Console > Internal testing > Create release, name = version, notes en-US, Save and publish. Internal testing only.
