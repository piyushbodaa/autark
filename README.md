# Autark

A private AI that lives on your phone. Autark downloads a small open language model once and
then chats entirely offline: no account, no cloud, and conversations never leave the device.

- **No choices to make:** Autark picks the model for the phone. Phones with 6 GB of memory or
  more get Qwen 3.5 2B (1.3 GB); smaller ones get Qwen 3.5 0.8B (533 MB). Both are Apache 2.0,
  pinned to a Hugging Face commit, and checked against their SHA-256 after download.
- **Engine:** [llama.cpp](https://github.com/ggml-org/llama.cpp) v0.7.0 with Arm's KleidiAI kernels.
  The CPU backend is built for four Arm feature levels (armv8.0 → dotprod → i8mm → SME), and the
  phone loads the best one its CPU can run. SVE2 is left out: phones run it at 128 bits, where it
  measured 6–27% slower than i8mm. Thread counts follow the phone's big cores, tuned on real
  phones (below).
- **Only new text is processed:** Qwen 3.5 mixes attention with recurrent layers, which cannot be
  rewound, so each follow-up is appended to exactly what the model has already seen, and
  checkpoints of the recurrent state make Retry and new chats cheap. While the user types, the
  chat so far is processed ahead of time. When Android takes the memory back, the conversation
  is saved to disk and picked up again in well under a second.
- **Memory:** weights are repacked for the fast kernels as they load, and the memory-mapped file
  pages they came from are then let go, so the model isn't held twice.
- **Light:** Kotlin + Jetpack Compose, one variable font (Inter Tight), no images, no analytics,
  and no network calls except the one model download. Native code loads straight from the APK
  (16 MB on arm64) instead of being unpacked into a second copy.

## Layout

```
app/src/main/cpp/engine.cpp        The engine: prompts, prompt cache, checkpoints, prefill, saved sessions
app/src/main/cpp/autark_jni.cpp    JNI bridge to the engine
app/src/main/java/.../engine/      LlamaEngine: one model, one dedicated thread
app/src/main/java/.../models/      Which model this phone gets, and getting it onto the phone
app/src/main/java/.../data/        Chats (one JSON file each) and settings
app/src/main/java/.../ui/          Compose screens, styled after piyushbodaa.com
tools/engine-test                  Runs the engine on a desktop with a real model, and checks it
tools/device-test                  Runs that test and llama-bench on phones in Firebase Test Lab
third_party/llama.cpp              llama.cpp, as a git submodule pinned to v0.7.0
```

## Build

Needs JDK 17+, Android SDK 36, NDK 29.0.14206865 and CMake 3.31.6.

```sh
git clone --recursive https://github.com/piyushbodaa/autark   # or: git submodule update --init
./gradlew assembleDebug          # app/build/outputs/apk/debug/
./gradlew bundleRelease          # app/build/outputs/bundle/release/ (for Google Play)
```

The first build compiles llama.cpp for two ABIs and takes about 10–15 minutes; later builds take
about a minute.

The engine also builds on a desktop, to try changes against a real model without a phone. The
test drives scripted chats the way the app does (follow-ups, Retry, a new chat, reopening a chat,
Stop, thinking, a saved session), prints how much of each prompt had to be processed, and exits
non-zero if a check fails:

```sh
cmake -S tools/engine-test -B build/engine-test -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build build/engine-test
build/engine-test/engine-test path/to/Qwen3.5-0.8B-Q4_K_M.gguf
```

The same test, along with llama.cpp's `llama-bench`, also runs on phones you don't own, through
[Firebase Test Lab](https://firebase.google.com/docs/test-lab) (free for 5 physical devices a day).
`app/src/androidTest/.../devicetest/DeviceTest.kt` downloads the model the app would pick for the
phone, times every CPU variant the phone can run and every thread count, runs the engine test,
and goes through the app's own `LlamaEngine`. Each test runs in its own process, and the results
are downloaded to `build/device-test/`:

```sh
gcloud auth login && gcloud config set project YOUR_FIREBASE_PROJECT
gcloud firebase test android models list --filter=form=PHYSICAL   # what's available
tools/device-test/run.sh tokay,version=35 a15,version=34          # Pixel 9, Galaxy A15
tools/device-test/summarize.py build/device-test/autark-...       # one summary per phone
```

Release builds are signed with the upload key named in `keystore.properties` (never committed):

```properties
storeFile=/absolute/path/to/autark-upload.jks
storePassword=...
keyAlias=upload
keyPassword=...
```

## Publishing on Google Play

1. **Internal testing** (minutes, no review): upload the `.aab`, add testers by email.
2. **Closed testing**: personal developer accounts created after November 2023 must run a closed
   test with at least 12 opted-in testers for 14 days in a row before production is unlocked.
3. **Production** needs a store listing, a privacy policy URL, the Data safety form ("no data
   collected or shared"), a content rating and a target audience.
4. Already handled in the app: target API 36, 16 KB page-size compatible native libraries,
   in-app reporting of AI responses (Play's AI-generated content policy), no executable code
   downloaded (models are data), and chats excluded from cloud backup.

## License

Autark is released under the [MIT License](LICENSE).

It builds on llama.cpp (MIT) and bundles Inter Tight, licensed under the SIL Open Font License
(`app/src/main/assets/licenses/OFL-InterTight.txt`). The models are downloaded on the phone, not
distributed with the app, and are licensed by their makers under Apache 2.0.
