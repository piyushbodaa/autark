# Autark

A private AI that lives on your phone. Autark downloads a small open language model once and
then chats entirely offline: no account, no cloud, and conversations never leave the device.

- **Engine:** [llama.cpp](https://github.com/ggml-org/llama.cpp) v0.7.0, compiled for the phone with
  every Arm CPU variant (armv8.0 → dotprod → i8mm → SVE) and KleidiAI kernels; the best one is
  picked at runtime.
- **Memory:** weights are memory-mapped, so they live in the page cache rather than the app's heap.
  Only the conversation (KV cache) is allocated. Follow-up messages reuse the cached prompt, so
  only new text is processed.
- **Models:** a curated list (below), each pinned to a Hugging Face commit and checked against its
  SHA-256 after download, plus import of any GGUF file.
- **UI:** Kotlin + Jetpack Compose, Material 3. No analytics, no network calls except model downloads.

| Model | Size | Phone RAM | License |
|---|---|---|---|
| Qwen 3.5 0.8B | 533 MB | 3 GB+ | Apache 2.0 |
| LFM 2.5 1.2B | 731 MB | 4 GB+ | LFM Open License 1.0 |
| Gemma 3 1B | 806 MB | 4 GB+ | Gemma Terms |
| Llama 3.2 1B | 808 MB | 4 GB+ | Llama 3.2 Community |
| Qwen 3.5 2B (recommended, 6 GB+) | 1.3 GB | 6 GB+ | Apache 2.0 |
| Llama 3.2 3B | 2.0 GB | 6 GB+ | Llama 3.2 Community |
| Gemma 4 E2B (Indian languages) | 3.1 GB | 8 GB+ | Apache 2.0 |
| Qwen 3.5 4B | 2.7 GB | 8 GB+ | Apache 2.0 |

## Layout

```
app/src/main/cpp/autark_jni.cpp    JNI bridge: load, chat-template rendering, prefix-cached prompt, streaming
app/src/main/java/.../engine/      LlamaEngine: one model, one dedicated thread
app/src/main/java/.../models/      Catalog + ModelManager (DownloadManager, SHA-256 check, GGUF import)
app/src/main/java/.../data/        Chats (one JSON file each) and settings
app/src/main/java/.../ui/          Compose screens
third_party/llama.cpp              llama.cpp source, tag v0.7.0
```

## Build

Needs JDK 17+, Android SDK 36, NDK 29.0.14206865 and CMake 3.31.6.

```sh
git clone --depth 1 --branch v0.7.0 https://github.com/ggml-org/llama.cpp third_party/llama.cpp
./gradlew assembleDebug          # app/build/outputs/apk/debug/
./gradlew bundleRelease          # app/build/outputs/bundle/release/ (for Google Play)
```

Release builds are signed with the upload key named in `keystore.properties` (not committed):

```properties
storeFile=/absolute/path/to/autark-upload.jks
storePassword=...
keyAlias=upload
keyPassword=...
```

## Publishing on Google Play

The application ID `com.piyushbodaa.autark` is permanent once uploaded; rename before the first upload
if "Autark" changes.

1. **Internal testing** (minutes, no review): create the app in Play Console, upload the `.aab`,
   add testers by email, share the opt-in link. Installs come from the Play Store.
2. **Closed testing**: personal developer accounts created after November 2023 must run a closed
   test with at least 12 opted-in testers for 14 days in a row before production is unlocked.
3. **Production** needs: store listing (icon 512×512, feature graphic 1024×500, 2+ phone
   screenshots, short and full description), a privacy policy URL, the Data safety form
   ("no data collected or shared"), content rating, and target audience (18+ keeps it simple).
4. Already handled in the app: target API 36, 16 KB page-size compatible native libraries,
   in-app reporting of AI responses (Play's AI-generated content policy), no executable code
   downloaded (models are data), backups exclude chats from the cloud.
