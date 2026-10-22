// JNI bridge between Autark and llama.cpp.
//
// One model and one conversation are live at a time. Every call must come from the same
// thread (LlamaEngine guarantees this), except abort(), which only flips an atomic flag.
//
// Strings cross the boundary as UTF-8 byte arrays rather than jstrings, because JNI's
// "modified UTF-8" mangles emoji and other characters outside the Basic Multilingual Plane.

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <string>
#include <vector>

#include "chat.h"
#include "common.h"
#include "llama.h"
#include "sampling.h"

#define TAG "AutarkNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

constexpr int BATCH_SIZE = 512;

// Error codes returned to Kotlin; keep in sync with LlamaEngine.
constexpr int ERR_NO_MODEL      = -1;
constexpr int ERR_LOAD_FAILED   = -2;
constexpr int ERR_CONTEXT       = -3;
constexpr int ERR_TOO_LONG      = -4;
constexpr int ERR_DECODE        = -5;
constexpr int ERR_ABORTED       = -6;
constexpr int ERR_TEMPLATE      = -7;

struct Session {
    llama_model *             model   = nullptr;
    llama_context *           ctx     = nullptr;
    common_sampler *          sampler = nullptr;
    common_chat_templates_ptr templates;
    common_batch              batch;
    llama_tokens              cached;      // tokens in the KV cache, position i holds cached[i]
    int                       n_ctx       = 0;
    int                       n_generated = 0;
    int                       max_tokens  = 0;
    std::string               pending;     // trailing bytes of an unfinished UTF-8 character
};

Session g;
std::atomic<bool> g_abort{false};

void log_to_logcat(ggml_log_level level, const char * text, void *) {
    int prio = ANDROID_LOG_DEBUG;
    if (level == GGML_LOG_LEVEL_ERROR) prio = ANDROID_LOG_ERROR;
    else if (level == GGML_LOG_LEVEL_WARN) prio = ANDROID_LOG_WARN;
    else if (level == GGML_LOG_LEVEL_INFO) prio = ANDROID_LOG_INFO;
    __android_log_write(prio, "llama.cpp", text);
}

bool should_abort(void *) { return g_abort.load(); }

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

// Length of the longest prefix of `s` that does not end inside a multi-byte character.
size_t complete_utf8_length(const std::string & s) {
    const size_t len = s.size();
    for (size_t i = 1; i <= 4 && i <= len; ++i) {
        const auto c = static_cast<unsigned char>(s[len - i]);
        if ((c & 0xC0) == 0x80) continue;  // continuation byte, keep looking for the lead byte
        size_t need = 1;
        if ((c & 0xE0) == 0xC0) need = 2;
        else if ((c & 0xF0) == 0xE0) need = 3;
        else if ((c & 0xF8) == 0xF0) need = 4;
        return need > i ? len - i : len;
    }
    return len;
}

void free_sampler() {
    if (g.sampler) {
        common_sampler_free(g.sampler);
        g.sampler = nullptr;
    }
}

void free_session() {
    free_sampler();
    g.templates.reset();
    g.batch = common_batch();
    if (g.ctx) llama_free(g.ctx);
    if (g.model) llama_model_free(g.model);
    g.ctx = nullptr;
    g.model = nullptr;
    g.cached.clear();
    g.pending.clear();
    g.n_ctx = 0;
}

void forget_cache() {
    llama_memory_clear(llama_get_memory(g.ctx), true);
    g.cached.clear();
}

// Renders the conversation with the model's own chat template and tokenizes it.
// Text the user typed is tokenized without parsing special tokens, so typing
// "<|im_end|>" into the chat cannot break out of the user's turn.
bool render(const std::vector<common_chat_msg> & messages, bool thinking, llama_tokens & out) {
    const llama_vocab * vocab = llama_model_get_vocab(g.model);

    common_chat_templates_inputs inputs;
    inputs.messages = messages;
    inputs.add_generation_prompt = true;
    inputs.enable_thinking = thinking;
    inputs.add_bos = llama_vocab_get_add_bos(vocab);

    common_chat_params params;
    try {
        inputs.use_jinja = true;
        params = common_chat_templates_apply(g.templates.get(), inputs);
    } catch (const std::exception & e) {
        LOGW("Jinja template failed (%s); falling back to the built-in template", e.what());
        try {
            inputs.use_jinja = false;
            params = common_chat_templates_apply(g.templates.get(), inputs);
        } catch (const std::exception & e2) {
            LOGE("Chat template failed: %s", e2.what());
            return false;
        }
    }

    out.clear();
    bool first = true;
    for (const auto & part : params.prompt.parts) {
        auto tokens = common_tokenize(vocab, part.text, /* add_special */ first, /* parse_special */ !part.is_input);
        out.insert(out.end(), tokens.begin(), tokens.end());
        first = false;
    }
    return !out.empty();
}

// Feeds tokens[from..] into the model, asking for logits only on the very last one.
int decode_from(const llama_tokens & tokens, size_t from) {
    for (size_t i = from; i < tokens.size(); i += BATCH_SIZE) {
        if (g_abort.load()) return ERR_ABORTED;
        const size_t end = std::min(tokens.size(), i + BATCH_SIZE);
        g.batch.clear();
        for (size_t j = i; j < end; ++j) {
            g.batch.add(tokens[j], static_cast<llama_pos>(j), 0, j == tokens.size() - 1);
        }
        const int rc = llama_process(g.ctx, LLAMA_PROCESS_TYPE_DECODE, g.batch.get());
        if (rc != 0) {
            LOGE("Prompt decode failed with %d", rc);
            return rc == 2 ? ERR_ABORTED : ERR_DECODE;
        }
        g.cached.insert(g.cached.end(), tokens.begin() + i, tokens.begin() + end);
    }
    return 0;
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_piyushbodaa_autark_engine_Native_init(JNIEnv * env, jobject, jstring native_lib_dir) {
    llama_log_set(log_to_logcat, nullptr);
    // The CPU backend ships as several .so variants (armv8.0, dotprod, i8mm, sve...);
    // this picks the best one the phone's CPU supports.
    ggml_backend_load_all_from_path(path_string(env, native_lib_dir).c_str());
    llama_backend_init();
    LOGI("%s", llama_print_system_info());
}

// Returns the context size actually allocated, or a negative error code.
JNIEXPORT jint JNICALL
Java_com_piyushbodaa_autark_engine_Native_load(JNIEnv * env, jobject, jstring jpath, jint n_ctx, jint n_threads) {
    free_session();

    const std::string path = path_string(env, jpath);
    llama_model_params mparams = llama_model_default_params();
    mparams.load_mode = LLAMA_LOAD_MODE_MMAP;  // weights stay in the page cache, not the app's heap

    g.model = llama_model_load_from_file(path.c_str(), mparams);
    if (!g.model) {
        LOGE("Could not load %s", path.c_str());
        return ERR_LOAD_FAILED;
    }

    const int trained = llama_model_n_ctx_train(g.model);
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(trained > 0 ? std::min<int>(n_ctx, trained) : n_ctx);
    cparams.n_batch = BATCH_SIZE;
    cparams.n_ubatch = BATCH_SIZE;
    cparams.n_threads = n_threads;
    cparams.n_threads_batch = n_threads;
    cparams.no_perf = true;

    g.ctx = llama_init_from_model(g.model, cparams);
    if (!g.ctx) {
        LOGE("Could not create a context of %d tokens", n_ctx);
        free_session();
        return ERR_CONTEXT;
    }
    llama_set_abort_callback(g.ctx, should_abort, nullptr);

    g.n_ctx = static_cast<int>(llama_n_ctx(g.ctx));
    g.templates = common_chat_templates_init(g.model, "");
    g.batch = common_batch(g.ctx);
    LOGI("Loaded %s with %d context tokens on %d threads", path.c_str(), g.n_ctx, n_threads);
    return g.n_ctx;
}

JNIEXPORT jbyteArray JNICALL
Java_com_piyushbodaa_autark_engine_Native_describe(JNIEnv * env, jobject) {
    if (!g.model) return nullptr;
    char desc[256];
    llama_model_desc(g.model, desc, sizeof(desc));
    return to_bytes(env, desc);
}

JNIEXPORT jboolean JNICALL
Java_com_piyushbodaa_autark_engine_Native_supportsThinking(JNIEnv *, jobject) {
    return g.templates && common_chat_templates_support_enable_thinking(g.templates.get());
}

// Prepares a reply to the conversation. Only the part of the prompt that differs from what is
// already in the KV cache gets processed, so follow-up messages start quickly.
// Returns the prompt length in tokens, or a negative error code.
JNIEXPORT jint JNICALL
Java_com_piyushbodaa_autark_engine_Native_prompt(JNIEnv * env, jobject, jobjectArray jroles, jobjectArray jcontents,
                                              jboolean thinking, jfloat temperature, jfloat top_p, jint max_tokens) {
    if (!g.ctx) return ERR_NO_MODEL;
    g_abort.store(false);

    std::vector<common_chat_msg> messages;
    const jsize count = env->GetArrayLength(jroles);
    for (jsize i = 0; i < count; ++i) {
        auto role = static_cast<jbyteArray>(env->GetObjectArrayElement(jroles, i));
        auto content = static_cast<jbyteArray>(env->GetObjectArrayElement(jcontents, i));
        common_chat_msg msg;
        msg.role = utf8_string(env, role);
        msg.content = utf8_string(env, content);
        messages.push_back(std::move(msg));
        env->DeleteLocalRef(role);
        env->DeleteLocalRef(content);
    }

    // Leave room for the reply; drop the oldest turns (never the system prompt) until it fits.
    const int reserve = std::min<int>(max_tokens, g.n_ctx / 2);
    llama_tokens tokens;
    while (true) {
        if (!render(messages, thinking, tokens)) return ERR_TEMPLATE;
        if (static_cast<int>(tokens.size()) + reserve <= g.n_ctx) break;
        const size_t first = (!messages.empty() && messages[0].role == "system") ? 1 : 0;
        if (messages.size() - first <= 1) return ERR_TOO_LONG;
        // Drop a whole exchange: some templates (Gemma) reject a history that opens with the assistant.
        messages.erase(messages.begin() + static_cast<long>(first));
        while (messages.size() - first > 1 && messages[first].role != "user") {
            messages.erase(messages.begin() + static_cast<long>(first));
        }
        LOGI("Conversation too long for the context; dropped the oldest exchange");
    }

    // Reuse the longest common prefix with what is already in the cache.
    size_t keep = 0;
    while (keep < g.cached.size() && keep < tokens.size() && g.cached[keep] == tokens[keep]) ++keep;
    if (keep == tokens.size()) --keep;  // always decode at least one token to get fresh logits
    if (keep < g.cached.size()) {
        // Recurrent and hybrid models (Qwen 3.5, LFM) cannot rewind part-way; start them over.
        if (!llama_memory_seq_rm(llama_get_memory(g.ctx), 0, static_cast<llama_pos>(keep), -1)) {
            llama_memory_clear(llama_get_memory(g.ctx), true);
            keep = 0;
        }
        g.cached.resize(keep);
    }
    LOGI("Prompt: %zu tokens, %zu reused from cache", tokens.size(), keep);

    const int rc = decode_from(tokens, keep);
    if (rc != 0) {
        forget_cache();  // a half-finished batch leaves the cache in an unknown state
        return rc;
    }

    free_sampler();
    common_params_sampling sparams;
    sparams.temp = temperature;
    sparams.top_p = top_p;
    sparams.seed = LLAMA_DEFAULT_SEED;
    g.sampler = common_sampler_init(g.model, sparams);

    g.n_generated = 0;
    g.max_tokens = max_tokens;
    g.pending.clear();
    return static_cast<jint>(tokens.size());
}

// Generates one token. Returns its text (possibly empty while a multi-byte character is
// still being assembled), or null when the reply is finished.
JNIEXPORT jbyteArray JNICALL
Java_com_piyushbodaa_autark_engine_Native_next(JNIEnv * env, jobject) {
    if (!g.ctx || !g.sampler || g_abort.load()) return nullptr;
    if (g.n_generated >= g.max_tokens || static_cast<int>(g.cached.size()) >= g.n_ctx) return nullptr;

    const llama_token token = common_sampler_sample(g.sampler, g.ctx, -1);
    common_sampler_accept(g.sampler, token, true);

    g.batch.clear();
    g.batch.add(token, static_cast<llama_pos>(g.cached.size()), 0, true);
    if (llama_process(g.ctx, LLAMA_PROCESS_TYPE_DECODE, g.batch.get()) != 0) {
        LOGE("Decode failed while generating");
        forget_cache();
        return nullptr;
    }
    g.cached.push_back(token);
    ++g.n_generated;

    // The end-of-turn token stays in the cache so the next prompt can reuse everything before it.
    if (llama_vocab_is_eog(llama_model_get_vocab(g.model), token)) return nullptr;

    g.pending += common_token_to_piece(g.ctx, token, /* special */ true);
    const size_t ready = complete_utf8_length(g.pending);
    std::string out = g.pending.substr(0, ready);
    g.pending.erase(0, ready);
    return to_bytes(env, out);
}

JNIEXPORT void JNICALL
Java_com_piyushbodaa_autark_engine_Native_abort(JNIEnv *, jobject) {
    g_abort.store(true);
}

JNIEXPORT void JNICALL
Java_com_piyushbodaa_autark_engine_Native_unload(JNIEnv *, jobject) {
    free_session();
}

}  // extern "C"
