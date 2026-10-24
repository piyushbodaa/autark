# Autark — handover (25 Oct 2026)

**What:** Android app (package `com.piyushbodaa.autark`) to chat with small AI models fully offline. Kotlin + Jetpack Compose UI, llama.cpp via JNI (`app/src/main/cpp`, submodule `third_party/llama.cpp`).

## State
- 0.4.1 (versionCode 5) is built and signed (`~/Downloads/Autark-0.4.1.aab`, 25 Oct) but not uploaded: 0.4.0, the font and drawer fixes, and the 0.4.1 work below. 0.4.0 (versionCode 4, `~/Downloads/Autark-0.4.0.aab`: one model, Qwen 3.5 2B, for every phone) wasn't uploaded as of 23 Oct; if it still isn't, skip it and upload 0.4.1. Proposed release notes: "Edit your last message, continue a reply that was cut off, and ask Autark about text from any app: select it and tap Ask Autark, or share it to Autark. Tables, links and code show properly, Think before answering always ends with an answer, and the model loads about a third faster. Autark now runs Qwen 3.5 2B on every phone."
- Store listing (23 Oct, not submitted): six 1080×1920 phone screenshots in `design/store/screenshots/` (24-bit PNG, in upload order), a draft name and descriptions in `design/store/listing.md`, the feature graphic in `design/store/` and the 512 px icon in `design/icon/`. The screenshots come from the emulator with `-skin 1080x1920 -qemu -cpu host`, airplane mode on and the status bar in demo mode; every reply in them is real model output.
- 0.3.0 (versionCode 3) live on Play **internal testing** since 4:09 PM IST, 23 Oct: 0.2.0's design, the new icon, and the performance pass and tuning from real phones below. Release notes: "New design, and Autark picks the best model for your phone. Much faster replies: follow-ups start in about a second, even in long chats, and your chat is prepared while you type. Lighter on memory and tuned to your phone's processor." A new install downloads 6.9 MB (0.2.0: 9.6 MB).
- Before it, on 22 Oct: 0.1.0 (versionCode 1) at 5:02 PM IST, and 0.2.0 (versionCode 2) at 6:14 PM IST, uploaded before the icon was redrawn ("New design. Autark now picks the best model for your phone automatically.").
- Internal testing goes to two email lists: "Me" (the developer) and "Testers" (7 friends, added 23 Oct). Testers join through the opt-in link on the Testers tab, opened in a browser: on a phone, pasted into Chrome's address bar. Tapped in the Gmail app, it goes straight to the Play Store app, which skips "Accept invite", and installing fails with "Something went wrong on our end" (a tester, 23 Oct; pasting it into Chrome fixed it). Until the app is set up and reviewed, they see it as "com.piyushbodaa.autark (unreviewed)".
- Play's Automatic protection is on (since 0.2.0 at least): Play adds an installer check to the app it delivers, and with it the `com.android.vending.CHECK_LICENSE` permission, which isn't in the manifest.
- Play: production access needs a closed test with 12+ testers opted in for 14 days. Closed testing unlocks once Play Console's "Set up your app" tasks are done (none were on 23 Oct).
- Signing: upload key is kept locally on lappy (`keystore.properties` -> `~/Keys/autark`). It is NOT in this repo, so signed release builds can only be made on lappy.

## Recent decisions (0.2.0 to 0.4.0)
- Redesign to match piyushbodaa.com: Inter Tight font, paper/ink/grey palette, uppercase two-tone headlines, night setup screen + drawer, rounded composer.
- New icon (23 Oct): Inter Tight's A with an orange core where the crossbar was, legs in a headline's two tones, on night. Source, tools and design board in `design/`.
- One model for every phone: Qwen 3.5 2B (the developer's call on 23 Oct, for 0.4.0; until 0.3.0, phones under 6 GB got Qwen 3.5 0.8B). No model picker and no memory check; a single "Download · 1.3 GB" first-run button, and the chat opens as soon as DownloadManager reports the download finished. The SHA-256 pass after it ("Checking the file") is gone too: the app records the finished download and checks the size. Size alone can't tell, because DownloadManager sets aside the whole file when it starts. Models screen and GGUF import removed; upgrading deletes other models, so phones that had the 0.8B download the 2B.
- Font weights (23 Oct, after the 0.4.0 bundle): Inter Tight's three weights are instances of one variable font, and `Font(res, weight)` doesn't set its wght axis, so every text had been drawn at Regular and Markdown bold didn't show. `Theme.kt` now sets the axis. The chat drawer, always night, now also gets light status-bar icons (the clock was dark on dark).

## 0.4.1 (24–25 Oct)
For people using it:
- **Edit** under your last message puts it back in the composer; sending replaces it and its reply. The engine goes back just one turn, from a checkpoint of the recurrent state taken where that message starts (~19 MiB), so it's as cheap as Retry: on the emulator an edited follow-up reused 176 of its 193 tokens.
- **Continue** under a reply that hit the length limit, or that the app never saw finish, carries on from the cached reply with nothing processed again (`Engine::resume`; if the cache has moved on, it rebuilds the reply from the chat). Continued text is the same as a reply written in one go (engine-test checks it).
- **Retry** under a question that never got an answer, and under a reply stopped while thinking: that used to stay on "Thinking…" with no answer and no buttons, and now says "Stopped before answering."
- **Ask Autark from any app:** "Ask Autark" in the text-selection menu (`PROCESS_TEXT`) and Autark in the share sheet (`SEND`, text/plain) open a new chat about the text, with Summarize, Explain, Reply and Fix grammar, or any question typed about it. MainActivity is `singleTask`, so the text opens in Autark's own task. Shared text is cut at 6,000 characters (~1,500 tokens of English, which fits the default memory setting).
- **Markdown:** tables (columns as wide as their widest cell, up to 220 dp; wide ones scroll sideways), quotes, rules, nested lists, links, strikethrough, and code blocks with their language and a Copy button. The 2B fenced code 8 times out of 9 in a test, and asking for code blocks in the system prompt didn't help (7 of 9), so the prompt doesn't ask.
- **Thinking ends with an answer:** the reasoning is closed after three quarters of the length limit, with the sentence Qwen documents for a thinking budget, and the model answers from there. On four maths questions × 2 seeds at 1,024 tokens, 6 of the 8 thoughts needed it and all 8 answers were right; without it, those 6 would have ended with no answer.
- **Replies survive Android:** a reply is saved every 2 s while it streams in, and when the app leaves the screen, marked cut until it finishes, so if Android stops the app halfway the chat keeps it and offers Continue.
- **Back on screen:** if Android took the model's memory while the app was away, it loads again as soon as the app is visible, not at the first keystroke.
- While a reply waits for its first word: "Waking up" while the model loads, "Reading" once the chat has taken over a second to read.
- Suggestion pills: the three that came out wrong on 23 Oct (UPI, workout, Hindi story) are now the three that came out well (bakery names, photosynthesis for a 10-year-old, a birthday message for mom).
- The system prompt adds "Write maths in plain text, not LaTeX." Phones that kept the old default get the new one.

Engine:
- **Loading on every core:** `third_party/patches/llama-load-in-parallel.patch` has llama.cpp's loader repack the weights on worker threads (biggest first, at nice +10 so the UI stays smooth) while the calling thread reports progress every 10 ms. `patches.cmake` compiles the patched file in place of the original, so the submodule stays on upstream's v0.7.0; it isn't upstreamed. Engine loads on Test Lab (24 Oct, the same phones as 23 Oct): Galaxy S24 6.7 → 4.5 s, Pixel 9 7.5–10.4 → 5.4 s, Galaxy A15 9.8–11.4 → 6.2–7.5 s; laptop 0.96 → 0.77 s. The repack phase alone, at the same phone temperature: A15 8.4–8.8 → 4.8–6.4 s cool, 9.8–10.0 → 6.1–6.4 s hot. Physical phones can wait half an hour in Test Lab's queue before they start.
- On Arm, the output weights (a third of the model) are repacked as one tensor on one thread and are now the long pole: on the virtual phone every other tensor is done in ~0.3 s and that one takes ~1.5 s more. Its file pages couldn't be released until it finished, so the app now also releases them every 100 ms during the load: the virtual phone's load peak went 1,898 → 1,651 MiB, the same as after loading.
- On the Galaxy A15, ~430 MiB of repacking's freed scratch memory stayed resident for minutes after loading (the other phones never had it); the engine now asks the allocator to give free memory back after loading (`mallopt(M_PURGE_ALL)`, or `M_PURGE` before Android 14). After it, the A15 holds 1,620 MiB once loaded, as it did before the patch; its brief peak while loading (~2.1 GB) is the same as with the old loader, and only that phone has one.
- Sampling options for top-k, min-p and a presence penalty, counted over the whole reply (llama.cpp reads a window of -1 as "off", so the engine passes the context size).

Sampling, read side by side (`engine-ask`, 9 questions × 2 seeds):
- Qwen's model card recommends temperature 1.0, top-p 1.0, top-k 20, min-p 0 and a presence penalty of 2.0 for the 2B. With them, replies got worse: one said a UPI PIN may be shared "only in the safety of an encrypted channel", the UPI explanation was nonsense, the Hindi story worse, a list of 5 names had 4, and a train arrival came out "8:12 PM". A middle setting (0.7 / 0.8 / 20 / 0 / 1.5) said "Never. Share the UPI PIN only with trusted individuals… delivery partners" and got the train time wrong once. A penalty over the whole reply punishes repeated tokens, and working out a sum repeats digits.
- So chats keep 0.7 / 0.95 / top-k 40 / min-p 0.05 / no penalty (sums right both times, "never share the PIN" both times), and thinking uses the card's setting for precise work: top-p 0.95, top-k 20, min-p 0, no penalty.

Decided against, for now:
- **Replies that keep going in the background** (a foreground service): even `shortService`, which needs no special permission, has to be declared in Play Console's App content with a demo video, a new setup task on the way to the closed test. Replies are saved as they stream instead.
- **Photos:** Qwen 3.5 2B can read images, and Unsloth ships its encoder (`mmproj-F16.gguf`, 668 MB; no quantized one). On the laptop, which reads prompts about as fast as a Galaxy S24: a full-resolution screenshot is ~2,000 tokens, 81 s before the first word and 3.2 GB at peak; capped at 256 tokens it takes 6 s and 2.8 GB but can't read text (it called Autark's chat screen "WhatsApp"). Not for 6 GB phones yet; worth another look with a quantized encoder or a smaller vision model.

Tools:
- `tools/engine-test/engine-ask` with `questions.txt` prints replies to a list of questions as Markdown, to compare settings by reading them.
- engine-test now also checks an edit, editing back (the same reply as before), Continue (the same text as in one go, nothing processed), Continue after the cache moved on, and the thinking budget; `--load-only` times a load.
- run.sh no longer downloads the model back with each run's results: Test Lab files the preloaded model under the results, which was 1.3–1.8 GB per run.

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
- `tools/device-test/run.sh DEVICE,version=API ...` runs it and downloads the results to `build/device-test/`; `tools/device-test/summarize.py` prints one summary per phone. Test Lab phones download slowly (3–13 MB/s, and two stalled), so the model is in `gs://test-lab-4tqmpm9aky7bi-mm34972k40iax/models/` and `MODELS=gs://test-lab-4tqmpm9aky7bi-mm34972k40iax/models` has run.sh copy it onto each phone first.
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
- Loading was the slowest step left (7–10 s), with llama.cpp repacking the weights on one thread; since 0.4.1 it repacks on every core (4.5–6.2 s, above). Caching the repacked weights on disk would make it near-instant and turn 1.3 GB of the app's own memory into reclaimable file pages, but needs changes inside ggml's CPU backend.

## Next
- Next Test Lab round (free quota resets at midnight Pacific, 12:30 IST): a Galaxy S26 (`m1q`) or Z Flip8 (`b8q`) to see whether any has SME and whether `android_armv9.2_2` then beats i8mm.
- The next load win: the output weights' repack (one tensor, a third of the model) runs on one thread inside ggml's KleidiAI code; splitting its rows across threads would take ~1.5 s off on four cores. Whether to offer the loader patch to llama.cpp upstream (a public PR) is the developer's call.
- The thinking budget (three quarters of the length limit) closed 6 of 8 maths thoughts at 768 tokens; a smaller one might answer as well, sooner.
- Every phone gets the 2B now (1.6–1.7 GB resident). 6 GB phones run it at 7–9 t/s (Galaxy A15/A35) while Android kills background apps; phones with 3–4 GB are untested and may not be able to load it. If they can't, Play Console's device catalog can exclude phones by RAM.
- Facts and Hindi are where the 2B is weakest: on 23 and 24 Oct it explained UPI wrongly with every setting tried, and its Hindi stories had grammar and sense errors. The suggestion pills now avoid both.
- Gather Sir's and the testers' feedback (screenshots) and iterate. Anyone else trying builds must first be added to an internal-testing email list.
- Finish Play's setup tasks and start a closed test with 12+ testers. Keep testers' email addresses out of this repo: it's public.
- Play's bundle report: R8 runs in full mode with resource shrinking, but optimized resource shrinking and class repackaging are off, and it suggests AGP 9 (we're on 8.13.2). DEX is 2.2 MB uncompressed, so these are small size wins.

## Release flow
1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. On lappy: `./gradlew bundleRelease` (needs `keystore.properties`), copy aab to `~/Downloads/Autark-<ver>.aab`.
3. Upload to Play Console > Internal testing > Create release, name = version, notes en-US, Save and publish. Internal testing only. Claude in Chrome can't attach files over 10 MB (the bundle is ~27 MB), so this step is done by hand or by an agent that can upload it.
