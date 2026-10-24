// JNI bridge between Autark and the engine (engine.h).
//
// Strings cross the boundary as UTF-8 byte arrays rather than jstrings, because JNI's
// "modified UTF-8" mangles emoji and other characters outside the Basic Multilingual Plane.

#include <jni.h>

#include <string>
#include <vector>

#include "engine.h"

namespace {

autark::Engine g_engine;

std::string path_string(JNIEnv * env, jstring s) {
    const char * chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

std::string utf8_string(JNIEnv * env, jbyteArray bytes) {
    const jsize n = env->GetArrayLength(bytes);
    std::string out(static_cast<size_t>(n), '\0');
    env->GetByteArrayRegion(bytes, 0, n, reinterpret_cast<jbyte *>(out.data()));
    return out;
}

jbyteArray to_bytes(JNIEnv * env, const std::string & s) {
    jbyteArray out = env->NewByteArray(static_cast<jsize>(s.size()));
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte *>(s.data()));
    return out;
}

std::vector<autark::Message> messages_from(JNIEnv * env, jobjectArray jroles, jobjectArray jcontents) {
    std::vector<autark::Message> messages;
    const jsize count = env->GetArrayLength(jroles);
    messages.reserve(static_cast<size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        auto role = static_cast<jbyteArray>(env->GetObjectArrayElement(jroles, i));
        auto content = static_cast<jbyteArray>(env->GetObjectArrayElement(jcontents, i));
        messages.push_back({utf8_string(env, role), utf8_string(env, content)});
        env->DeleteLocalRef(role);
        env->DeleteLocalRef(content);
    }
    return messages;
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_piyushbodaa_autark_engine_Native_init(JNIEnv * env, jobject, jstring native_lib_dir) {
    autark::Engine::init(path_string(env, native_lib_dir));
}

JNIEXPORT jint JNICALL
Java_com_piyushbodaa_autark_engine_Native_load(JNIEnv * env, jobject, jstring jpath, jint n_ctx, jint n_threads,
                                            jint n_threads_batch) {
    return g_engine.load(path_string(env, jpath), n_ctx, n_threads, n_threads_batch);
}

JNIEXPORT jbyteArray JNICALL
Java_com_piyushbodaa_autark_engine_Native_describe(JNIEnv * env, jobject) {
    const std::string desc = g_engine.describe();
    return desc.empty() ? nullptr : to_bytes(env, desc);
}

JNIEXPORT jboolean JNICALL
Java_com_piyushbodaa_autark_engine_Native_supportsThinking(JNIEnv *, jobject) {
    return g_engine.supports_thinking();
}

JNIEXPORT jint JNICALL
Java_com_piyushbodaa_autark_engine_Native_prompt(JNIEnv * env, jobject, jobjectArray jroles, jobjectArray jcontents,
                                              jboolean resume, jboolean thinking, jfloat temperature, jfloat top_p,
                                              jint top_k, jfloat min_p, jfloat presence_penalty, jint max_tokens) {
    autark::ReplyOptions options;
    options.thinking = thinking;
    options.temperature = temperature;
    options.top_p = top_p;
    options.top_k = top_k;
    options.min_p = min_p;
    options.presence_penalty = presence_penalty;
    options.max_tokens = max_tokens;
    const auto messages = messages_from(env, jroles, jcontents);
    return resume ? g_engine.resume(messages, options) : g_engine.prompt(messages, options);
}

JNIEXPORT jint JNICALL
Java_com_piyushbodaa_autark_engine_Native_ended(JNIEnv *, jobject) {
    return static_cast<jint>(g_engine.ended());
}

JNIEXPORT jint JNICALL
Java_com_piyushbodaa_autark_engine_Native_prefill(JNIEnv * env, jobject, jobjectArray jroles, jobjectArray jcontents,
                                               jboolean thinking, jint max_tokens) {
    return g_engine.prefill(messages_from(env, jroles, jcontents), thinking, max_tokens);
}

// Returns the next piece of text (possibly empty), or null when the reply is finished.
JNIEXPORT jbyteArray JNICALL
Java_com_piyushbodaa_autark_engine_Native_next(JNIEnv * env, jobject) {
    std::string piece;
    return g_engine.next(piece) ? to_bytes(env, piece) : nullptr;
}

JNIEXPORT void JNICALL
Java_com_piyushbodaa_autark_engine_Native_stop(JNIEnv *, jobject) {
    g_engine.stop();
}

JNIEXPORT void JNICALL
Java_com_piyushbodaa_autark_engine_Native_cancelPrefill(JNIEnv *, jobject) {
    g_engine.cancel_prefill();
}

JNIEXPORT jboolean JNICALL
Java_com_piyushbodaa_autark_engine_Native_saveSession(JNIEnv * env, jobject, jstring jpath) {
    return g_engine.save_session(path_string(env, jpath));
}

JNIEXPORT jboolean JNICALL
Java_com_piyushbodaa_autark_engine_Native_loadSession(JNIEnv * env, jobject, jstring jpath) {
    return g_engine.load_session(path_string(env, jpath));
}

JNIEXPORT void JNICALL
Java_com_piyushbodaa_autark_engine_Native_forget(JNIEnv *, jobject) {
    g_engine.forget();
}

JNIEXPORT void JNICALL
Java_com_piyushbodaa_autark_engine_Native_unload(JNIEnv *, jobject) {
    g_engine.unload();
}

}  // extern "C"
