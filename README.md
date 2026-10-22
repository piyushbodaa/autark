# Autark

A private AI that lives on your phone. Autark downloads a small open language model once and
then chats entirely offline: no account, no cloud, and conversations never leave the device.

- **Engine:** [llama.cpp](https://github.com/ggml-org/llama.cpp) v0.7.0, compiled for the phone with
  every Arm CPU variant (armv8.0 → dotprod → i8mm → SVE) and KleidiAI kernels; the best one is
  picked at runtime.
- **Memory:** weights are memory-mapped, so they live in the page cache rather than the app's heap.
  Only the conversation (KV cache) is allocated. Follow-up messages reuse the cached prompt, so
  only new text is processed.
- **Model:** no picker. Autark chooses for the phone: Qwen 3.5 2B on 6 GB+ RAM, Qwen 3.5 0.8B
  otherwise. Each is pinned to a Hugging Face commit and checked against its SHA-256 after download.
  Upgrading from 0.1.0 deletes any other downloaded models.
- **UI:** Kotlin + Jetpack Compose, Material 3, Inter Tight. No analytics, no network calls except the
  model download.

| Model | Size | Phone RAM | License |
|---|---|---|---|
| Qwen 3.5 0.8B | 533 MB | under 6 GB | Apache 2.0 |
| Qwen 3.5 2B | 1.3 GB | 6 GB+ | Apache 2.0 |

## Layout

```
app/src/main/cpp/autark_jni.cpp    JNI bridge: load, chat-template rendering, prefix-cached prompt, streaming
app/src/main/java/.../engine/      LlamaEngine: one model, one dedicated thread
app/src/main/java/.../models/      Catalog (model per RAM tier) + ModelManager (DownloadManager, SHA-256 check)
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
