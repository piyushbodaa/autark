# Autark — handover (23 Oct 2026)

**What:** Android app (package `com.piyushbodaa.autark`) to chat with small AI models fully offline. Kotlin + Jetpack Compose UI, llama.cpp via JNI (`app/src/main/cpp`, submodule `third_party/llama.cpp`).

## State
- 0.1.0 (versionCode 1) live on Play **internal testing** since 5:02 PM IST, 22 Oct.
- 0.3.0 (versionCode 3) built on 23 Oct (`~/Downloads/Autark-0.3.0.aab` on lappy), **not yet uploaded**: 0.2.0's design and icon, plus the performance pass and the tuning from real phones below. 0.2.0 (versionCode 2) was never uploaded. Release notes: "New design, and Autark picks the best model for your phone. Much faster replies: follow-ups start in about a second, even in long chats, and your chat is prepared while you type. Lighter on memory and tuned to your phone's processor."
- Play: production access needs a closed test with 12+ testers opted in for 14 days. Closed testing unlocks once Play Console's "Set up your app" tasks are done (none were on 23 Oct).
- Signing: upload key is kept locally on lappy (`keystore.properties` -> `~/Keys/autark`). It is NOT in this repo, so signed release builds can only be made on lappy.

## Recent decisions (0.2.0)
- Redesign to match piyushbodaa.com: Inter Tight font, paper/ink/grey palette, uppercase two-tone headlines, night setup screen + drawer, rounded composer.
- New icon (23 Oct): Inter Tight's A with an orange core where the crossbar was, legs in a headline's two tones, on night. Source, tools and design board in `design/`.
- No model picker: Qwen 3.5 2B on 6 GB+ RAM devices, Qwen 3.5 0.8B otherwise; single "Download · 1.3 GB" first-run button. Models screen and GGUF import removed; upgrading from 0.1.0 deletes other models.

## Performance pass (23 Oct)
Measured with `tools/engine-test` on a laptop CPU (4 threads) and checked on the emulator:
- **Follow-ups no longer reprocess the chat.** Qwen 3.5's template re-renders earlier replies without their empty `<think></think>`, which never matched the cache, and its recurrent layers can't rewind, so every message reprocessed the whole conversation (0 tokens reused, first token after 4.7 s by turn 8 on the 0.8B). Now follow-ups append to the cache and checkpoints of the recurrent state cover Retry and new chats: 20–30 tokens processed per turn, first token in 0.1–0.3 s at any length. Over 8 turns plus Retry, Stop and new chats: 5,460 → ~420 prompt tokens processed.
- **Prefill while typing:** the first keystroke in a chat gets the model through it ahead of the send (and reloads the model if Android took it).
- **Saved sessions:** the cache is written to `cacheDir/session.bin` when the app is hidden or the model released (~40 MiB for Qwen 3.5, mostly recurrent state), and picked up after a reload in ~50 ms. Deleted chats delete it.
- **Overflowing chats** drop old turns down to half the context, so they are reprocessed once every few turns instead of every turn.
- **Stop** no longer wipes the cache (generation is only stopped between tokens).
- **Memory:** file pages of repacked weights are released after load (2B on x86: 1.96 → 1.49 GB resident; on Arm, where Q6_K is repacked too, expect more). `n_outputs_max = 1` cuts the reserved compute buffer from 513 to 60 MiB (virtual, not resident).
- **Load peak:** those pages are now also released while the model loads (every 5% of it, from llama.cpp's progress callback), so the app never holds the repacked weights twice. Peak memory, 0.8B on Test Lab's virtual Arm phones: 1,317 → 1,014 MiB, the same as after loading; 2B on x86: 1,852 → 1,469 MiB. Those phones had 1.2–1.9 GB free.
- **Size:** `common` is linked statically into `libautark.so` (only JNI symbols exported); 2 redundant Arm and 8 x86 CPU variants dropped; native libraries load from the APK (`useLegacyPackaging = false`, variants opened by name in `Engine::init`). arm64 native code on the phone: 29.7 → 16.1 MB.
- **Thinking mode** replies now start with `<think>` so the app folds the reasoning away (before, the reasoning showed inline with a stray `</think>`).
- Threads now come from the kernel's core capacities ("big" = at least half the fastest core's), tuned on Test Lab phones: generation big − 1 (2–4); prompt processing big (2–5), or cores − 3 (up to 5) when there are only two big cores, where the little ones help (Galaxy A15: +27%). Generation is memory-bound and gains nothing past that; more prompt threads only wait on each other and on the rest of the phone. With the old rule (gen min(big, 4), prompts min(big, 8)), a Pixel 11 read prompts on all 7 cores, 43% slower than on 5, and a Galaxy S24 on 6, 30% slower than on 5.
- CPU variant: the SVE2 one (`android_armv9.0_1`) is gone. Measured there and back on a Pixel 9 and a Pixel 11, it read prompts 10–27% slower than i8mm (`android_armv8.6_1`) and replied 6–14% slower, and every core with SVE2 has i8mm. The SME variant (`android_armv9.2_2`) is still tried first, but no phone tested so far has SME.

## Device tests (23 Oct)
No phone was at hand, so the engine is measured on phones in Firebase Test Lab: project `autark-device-tests` on the developer's Google account, on the free Spark plan (5 physical and 10 virtual device runs a day, no billing). The Google Cloud CLI is at `~/.local/share/google-cloud-sdk` on lappy, signed in to that account.
- `app/src/androidTest/.../devicetest/DeviceTest.kt` (debug builds only, through `libautark-tests.so`): device facts, every CPU variant the phone can run, every thread count (llama-bench), `tools/engine-test`, and the app's own `LlamaEngine`.
- `tools/device-test/run.sh DEVICE,version=API ...` runs it and downloads the results to `build/device-test/`; `tools/device-test/summarize.py` prints one summary per phone. Test Lab phones download slowly (3–13 MB/s, and two stalled), so both models are in `gs://test-lab-4tqmpm9aky7bi-mm34972k40iax/models/` and `MODELS=gs://test-lab-4tqmpm9aky7bi-mm34972k40iax/models` has run.sh copy them onto each phone first.
- Phones throttle within a minute or two, so a fixed test order measures heat as much as speed: the CPU variants now run there and back (ABBA) and the thread counts in three rounds of different orders.
- The free `MediumPhone.arm` and `MediumPhone_ps16k.arm` (16 KB pages) virtual devices run the whole suite in ~3 minutes: check changes there before spending physical runs.

Measured on 23 Oct with the 2B model (the app's pick on all of them), all checks passing; speeds with the CPU variant and thread counts the app now uses, phone cool to warm:

| Phone | SoC | Big cores | Generation t/s | Prompt t/s | Load | Follow-up first token |
|---|---|---|---|---|---|---|
| Galaxy S24 | Snapdragon 8 Gen 3 | 6 of 8 | 19–25 | 97–113 | 6.7 s | 0.4–0.7 s |
| Pixel 11 | Tensor G6 | 7 of 7 | 19–26 | 92–140 | 6.6–7.7 s | 0.4–0.6 s |
| Galaxy A35 | Exynos 1380 | 4 of 8 | 9.1 | 56–60 | 7.9 s | 0.7–1.0 s |
| Pixel 9 | Tensor G4 | 4 of 8 | 11–14 | 49–70 | 7.5–10.4 s | 0.7–0.9 s (was 1.0–1.3) |
| Galaxy A15 | Helio G99 | 2 of 8 | 6.7–7.4 | 29–34 | 9.8–11.4 s | 1.0–1.5 s |

- Resident memory with the 2B: 1.6–1.7 GB. On the two 6 GB Galaxies, Android killed background apps while it was loaded.
- Without appending follow-ups, turn 8 on the Pixel 9 would have reprocessed ~1,000 tokens at ~35 t/s (~29 s); it now takes 1.2 s.
- Loading is the slowest step left (7–10 s): llama.cpp repacks the weights on one thread. Caching the repacked weights on disk would make it near-instant and turn 1.3 GB of the app's own memory into reclaimable file pages, but needs changes inside ggml's CPU backend.

## Next
- Upload `~/Downloads/Autark-0.3.0.aab` to internal testing (release notes in State).
- Next Test Lab round (free quota resets at midnight Pacific, 12:30 IST): a Galaxy S26 (`m1q`) or Z Flip8 (`b8q`) to see whether any has SME and whether `android_armv9.2_2` then beats i8mm; the Galaxy A15 again for its load peak (2.1 GB against 1.6 GB loaded, only on that phone; `engine-memory.txt` now records memory every 50 ms) and to confirm 5 prompt threads in the app.
- 6 GB phones get the 2B: 7–9 t/s on a Galaxy A15/A35, but Android kills background apps while it is loaded. Worth deciding whether 6 GB phones should get the 0.8B.
- Upload to internal testing; gather Sir's feedback (screenshots) and iterate.
- Finish Play's setup tasks and start a closed test with 12+ testers. Keep testers' email addresses out of this repo: it's public.

## Release flow
1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. On lappy: `./gradlew bundleRelease` (needs `keystore.properties`), copy aab to `~/Downloads/Autark-<ver>.aab`.
3. Upload to Play Console > Internal testing > Create release, name = version, notes en-US, Save and publish. Internal testing only.
