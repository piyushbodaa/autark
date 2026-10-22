# Autark

A private AI that lives on your phone. Autark downloads a small open language model once and
then chats entirely offline: no account, no cloud, and conversations never leave the device.

- **No choices to make:** Autark picks the model for the phone. Phones with 6 GB of memory or
  more get Qwen 3.5 2B (1.3 GB); smaller ones get Qwen 3.5 0.8B (533 MB). Both are Apache 2.0,
  pinned to a Hugging Face commit, and checked against their SHA-256 after download.
- **Engine:** [llama.cpp](https://github.com/ggml-org/llama.cpp) v0.7.0, compiled with every Arm CPU
  variant (armv8.0 → dotprod → i8mm → SVE) and KleidiAI kernels; the best one is picked at runtime.
- **Memory:** weights are memory-mapped, so they live in the page cache rather than the app's heap.
  Follow-up messages reuse the cached prompt, so only new text is processed.
- **Light:** Kotlin + Jetpack Compose, one variable font (Inter Tight), no images, no analytics,
  and no network calls except the one model download. About 22 MB installed.

## Layout

```
app/src/main/cpp/autark_jni.cpp    JNI bridge: load, chat-template rendering, prefix-cached prompt, streaming
app/src/main/java/.../engine/      LlamaEngine: one model, one dedicated thread
app/src/main/java/.../models/      Which model this phone gets, and getting it onto the phone
app/src/main/java/.../data/        Chats (one JSON file each) and settings
app/src/main/java/.../ui/          Compose screens, styled after piyushbodaa.com
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
