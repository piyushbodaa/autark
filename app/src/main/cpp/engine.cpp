#include "engine.h"

#include <algorithm>
#include <chrono>
#include <cinttypes>
#include <cstdio>
#include <cstdlib>
#include <cstring>

#if defined(__linux__)
#include <dlfcn.h>
#include <malloc.h>
#include <sys/mman.h>
#include <sys/stat.h>
#endif

#ifdef __ANDROID__
#include <android/log.h>
#define LOG(prio, ...) __android_log_print(prio, "AutarkNative", __VA_ARGS__)
#define LOGI(...) LOG(ANDROID_LOG_INFO, __VA_ARGS__)
#define LOGW(...) LOG(ANDROID_LOG_WARN, __VA_ARGS__)
#define LOGE(...) LOG(ANDROID_LOG_ERROR, __VA_ARGS__)
#else
#define LOG(tag, ...) (fprintf(stderr, "[engine] " tag " " __VA_ARGS__), fputc('\n', stderr))
#define LOGI(...) LOG("I", __VA_ARGS__)
#define LOGW(...) LOG("W", __VA_ARGS__)
#define LOGE(...) LOG("E", __VA_ARGS__)
#endif

namespace autark {

namespace {

constexpr int BATCH_SIZE = 512;

constexpr const char * THINK_OPEN  = "<think>";
constexpr const char * THINK_CLOSE = "</think>";

// Closes reasoning that has run out of room, telling the model to answer from what it has thought
// so far (the way Qwen documents a thinking budget).
constexpr const char * THINK_OUT_OF_ROOM =
    "\n\nConsidering the limited time by the user, I have to give the solution based on the thinking directly now.\n"
    "</think>\n\n";

void log_callback(ggml_log_level level, const char * text, void *) {
#ifdef __ANDROID__
    int prio = ANDROID_LOG_DEBUG;
    if (level == GGML_LOG_LEVEL_ERROR) prio = ANDROID_LOG_ERROR;
    else if (level == GGML_LOG_LEVEL_WARN) prio = ANDROID_LOG_WARN;
    else if (level == GGML_LOG_LEVEL_INFO) prio = ANDROID_LOG_INFO;
    __android_log_write(prio, "llama.cpp", text);
#else
    static const bool verbose = getenv("AUTARK_VERBOSE") != nullptr;
    if (verbose || level == GGML_LOG_LEVEL_ERROR || level == GGML_LOG_LEVEL_WARN) fputs(text, stderr);
#endif
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

// Weights that llama.cpp repacks for faster kernels are copied out of the memory-mapped file
// while loading, but the file pages they were read from stay mapped and count against the app,
// almost doubling its footprint. Dropping the mapping's pages is safe: it is a read-only file
// mapping, so the few pages still used (embedding rows, norms) are read back on demand.
// Returns the size of the mappings released.
size_t release_mapped_pages(const std::string & path) {
    size_t released = 0;
#if defined(__linux__)
    struct stat st;
    if (stat(path.c_str(), &st) != 0) return 0;
    FILE * maps = fopen("/proc/self/maps", "r");
    if (!maps) return 0;
    char line[1024];
    while (fgets(line, sizeof(line), maps)) {
        uintptr_t start = 0, end = 0;
        unsigned long long inode = 0;
        if (sscanf(line, "%" SCNxPTR "-%" SCNxPTR " %*s %*s %*s %llu", &start, &end, &inode) != 3) continue;
        if (inode != static_cast<unsigned long long>(st.st_ino) || end <= start) continue;
        if (madvise(reinterpret_cast<void *>(start), end - start, MADV_DONTNEED) == 0) released += end - start;
    }
    fclose(maps);
#else
    (void) path;
#endif
    return released;
}

// Loading releases the file's pages as it goes, not only at the end: otherwise, just before the
// end, the app holds every repacked weight twice, once copied and once in the file's pages.
// llama.cpp maps the file with MAP_POPULATE, so all of it is resident before the first tensor is
// read; the first call (at 0) lets that go before any copy is made. Progress only moves when a
// tensor is done, and on Arm the output weights, a third of the model, are repacked as one, so
// pages also go every 100 ms (third_party/patches has the loader call back that often while it
// repacks); any still to be read come back from the page cache.
struct LoadProgress {
    using Clock = std::chrono::steady_clock;
    const std::string & path;
    float               released_at = -1;
    Clock::time_point   released_time{};
};

bool on_load_progress(float progress, void * data) {
    auto * load = static_cast<LoadProgress *>(data);
    const auto now = LoadProgress::Clock::now();
    if (progress - load->released_at >= 0.05f || now - load->released_time >= std::chrono::milliseconds(100)) {
        release_mapped_pages(load->path);
        load->released_at = progress;
        load->released_time = now;
    }
    return true;
}

// Repacking allocates large scratch buffers on the loader's threads and frees them there, and the
// allocator may keep that memory for later instead of giving it back: on a Galaxy A15, half a
// gigabyte stayed resident for minutes after loading.
void return_free_memory() {
#if defined(__ANDROID__)
    if (mallopt(M_PURGE_ALL, 0) == 0) mallopt(M_PURGE, 0);  // M_PURGE_ALL needs Android 14
#elif defined(__GLIBC__)
    malloc_trim(0);
#endif
}

bool is_space(char c) { return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == '\v'; }

std::string trim(const std::string & s) {
    size_t b = 0, e = s.size();
    while (b < e && is_space(s[b])) ++b;
    while (e > b && is_space(s[e - 1])) --e;
    return s.substr(b, e - b);
}

// The answer part of a reply, the way Thinking.split() sees it in Kotlin: what the app sends
// back as the assistant's message on the next turn.
std::string answer_of(const std::string & text) {
    const size_t start = text.find(THINK_OPEN);
    if (start == std::string::npos || !trim(text.substr(0, start)).empty()) return text;
    const size_t end = text.find(THINK_CLOSE, start);
    if (end == std::string::npos) return {};
    size_t b = end + strlen(THINK_CLOSE);
    while (b < text.size() && is_space(text[b])) ++b;
    return text.substr(b);
}

bool same(const Message & a, const Message & b) { return a.role == b.role && a.content == b.content; }

constexpr uint32_t SESSION_MAGIC   = 0x4b545541;  // "AUTK"
constexpr uint32_t SESSION_VERSION = 1;

struct Writer {
    FILE * f;
    bool   ok = true;
    void bytes(const void * p, size_t n) { ok = ok && fwrite(p, 1, n, f) == n; }
    template <typename T> void pod(const T & v) { bytes(&v, sizeof(v)); }
    void str(const std::string & s) { pod<uint64_t>(s.size()); bytes(s.data(), s.size()); }
    template <typename T> void vec(const std::vector<T> & v) { pod<uint64_t>(v.size()); bytes(v.data(), v.size() * sizeof(T)); }
};

// Reads what Writer wrote, refusing lengths larger than what is left of the file.
struct Reader {
    FILE * f;
    size_t left;
    bool   ok = true;
    void bytes(void * p, size_t n) {
        ok = ok && n <= left && fread(p, 1, n, f) == n;
        if (ok) left -= n;
    }
    template <typename T> T pod() {
        T v{};
        bytes(&v, sizeof(v));
        return v;
    }
    size_t count(size_t item) {
        const uint64_t n = pod<uint64_t>();
        ok = ok && n <= left / item;
        return ok ? static_cast<size_t>(n) : 0;
    }
    std::string str() {
        std::string s(count(1), '\0');
        bytes(s.data(), s.size());
        return s;
    }
    template <typename T> std::vector<T> vec() {
        std::vector<T> v(count(sizeof(T)));
        bytes(v.data(), v.size() * sizeof(T));
        return v;
    }
};

// Templates usually trim what they are given, so a message's text can show up either way.
bool is_content(const common_chat_prompt_part & part, const std::string & content) {
    return part.is_input && (part.text == content || part.text == trim(content));
}

// Index of the last part before `before` that holds `content`, or -1.
int find_content(const std::vector<common_chat_prompt_part> & parts, const std::string & content, int before) {
    for (int i = std::min<int>(before, static_cast<int>(parts.size())) - 1; i >= 0; --i) {
        if (is_content(parts[i], content)) return i;
    }
    return -1;
}

bool ends_with_think_open(const std::string & text) {
    const std::string t = trim(text);
    const size_t n = strlen(THINK_OPEN);
    return t.size() >= n && t.compare(t.size() - n, n, THINK_OPEN) == 0;
}

// A reply still inside its reasoning, the way Thinking.split() sees it in Kotlin.
bool still_thinking(const std::string & text) {
    const size_t start = text.find(THINK_OPEN);
    return start != std::string::npos && trim(text.substr(0, start)).empty() &&
           text.find(THINK_CLOSE, start) == std::string::npos;
}

common_params_sampling sampling_for(const ReplyOptions & options, int n_ctx) {
    common_params_sampling sparams;
    sparams.temp = options.temperature;
    sparams.top_p = options.top_p;
    sparams.top_k = options.top_k;
    sparams.min_p = options.min_p;
    sparams.penalty_present = options.presence_penalty;
    // The penalty counts every token of the reply, not just the last 64. (llama.cpp reads -1 as
    // "off" here, so the window is the context size.)
    sparams.penalty_last_n = n_ctx;
    sparams.seed = options.seed;
    return sparams;
}

}  // namespace

Engine::~Engine() { unload(); }

// The CPU backend is built several times over for different instruction sets, most capable
// first (the order of ggml's own list); one that the CPU can't run refuses to load.
constexpr const char * CPU_VARIANTS[] = {
#if defined(__aarch64__)
    // No android_armv9.0_1 (SVE2): phones run SVE at 128 bits, where its kernels lose to the
    // i8mm ones (a Pixel 9 and a Pixel 11 read prompts 10-27% slower with it and reply 6-14%
    // slower), and every core with SVE2 has i8mm too.
    "android_armv9.2_2", "android_armv9.2_1", "android_armv8.6_1",
    "android_armv8.2_2", "android_armv8.2_1", "android_armv8.0_1",
#elif defined(__x86_64__)
    "sapphirerapids", "zen4", "cooperlake", "icelake", "cascadelake", "cannonlake", "skylakex",
    "alderlake", "haswell", "piledriver", "ivybridge", "sandybridge", "sse42", "x64",
#endif
};

void Engine::init(const std::string & lib_dir) {
    llama_log_set(log_callback, nullptr);
    // The variants stay inside the APK instead of being extracted on install, so they are
    // opened by name. Variants the app doesn't ship are skipped quietly. One may be loaded
    // already, by a test measuring it (tools/device-test).
    bool loaded = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU) != nullptr;
#if defined(__linux__)
    for (const char * variant : CPU_VARIANTS) {
        if (loaded) break;
        const std::string name = std::string("libggml-cpu-") + variant + ".so";
        void * handle = dlopen(name.c_str(), RTLD_NOW | RTLD_LOCAL);
        if (!handle) continue;
        loaded = ggml_backend_load(name.c_str()) != nullptr;
        dlclose(handle);
    }
#endif
    if (!loaded) ggml_backend_load_all_from_path(lib_dir.c_str());
    llama_backend_init();
    LOGI("%s", llama_print_system_info());
}

// Prompt processing can be interrupted part-way through a batch; generation is only ever
// stopped between tokens, so a single-token decode never leaves the cache half-updated.
bool Engine::should_abort(void * self) {
    const auto * engine = static_cast<Engine *>(self);
    return engine->busy != Busy::idle && engine->interrupted();
}

bool Engine::interrupted() const { return busy == Busy::prefill ? cancelling.load() : stopping.load(); }

bool Engine::finish() {
    if (sampler) {
        common_sampler_free(sampler);
        sampler = nullptr;
    }
    return false;
}

void Engine::unload() {
    finish();
    templates.reset();
    batch = common_batch();
    if (ctx) llama_free(ctx);
    if (model) llama_model_free(model);
    ctx = nullptr;
    model = nullptr;
    cached.clear();
    ckpt_system = {};
    ckpt_turn = {};
    ckpt_previous = {};
    previous = {};
    logits_ready = false;
    forget_conversation();
    pending.clear();
    prefix.clear();
    how_ended = End::none;
    n_ctx = 0;
}

int Engine::load(const std::string & path, int requested_ctx, int n_threads, int n_threads_batch) {
    unload();

    LoadProgress progress{path};
    llama_model_params mparams = llama_model_default_params();
    mparams.load_mode = LLAMA_LOAD_MODE_MMAP;
    mparams.progress_callback = on_load_progress;
    mparams.progress_callback_user_data = &progress;

    model = llama_model_load_from_file(path.c_str(), mparams);
    if (!model) {
        LOGE("Could not load %s", path.c_str());
        return ERR_LOAD_FAILED;
    }
    LOGI("Released %.0f MiB of mapped model pages", release_mapped_pages(path) / (1024.0 * 1024.0));
    return_free_memory();

    const int trained = llama_model_n_ctx_train(model);
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(trained > 0 ? std::min(requested_ctx, trained) : requested_ctx);
    cparams.n_batch = BATCH_SIZE;
    cparams.n_ubatch = BATCH_SIZE;
    // Only the last token of a batch ever needs logits. Left at its default (n_batch), the
    // graph reserves logits for 512 tokens: 485 MiB with Qwen 3.5's 248K-token vocabulary.
    cparams.n_outputs_max = 1;
    cparams.n_threads = n_threads;
    cparams.n_threads_batch = n_threads_batch;
    cparams.no_perf = true;

    ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        LOGE("Could not create a context of %d tokens", requested_ctx);
        unload();
        return ERR_CONTEXT;
    }
    llama_set_abort_callback(ctx, should_abort, this);

    // Qwen 3.5 and other hybrid models keep recurrent state that cannot be rewound part-way.
    can_rewind = common_context_can_seq_rm(ctx) == COMMON_CONTEXT_SEQ_RM_TYPE_PART;

    n_ctx = static_cast<int>(llama_n_ctx(ctx));
    templates = common_chat_templates_init(model, "");
    batch = common_batch(ctx);
    LOGI("Loaded %s with %d context tokens on %d/%d threads%s", path.c_str(), n_ctx, n_threads, n_threads_batch,
         can_rewind ? "" : " (recurrent, using checkpoints)");
    return n_ctx;
}

std::string Engine::describe() const {
    if (!model) return {};
    char desc[256];
    llama_model_desc(model, desc, sizeof(desc));
    return desc;
}

bool Engine::supports_thinking() const {
    return templates && common_chat_templates_support_enable_thinking(templates.get());
}

// Renders messages with the model's own chat template, split into template text and the
// messages' own text.
bool Engine::render(const std::vector<Message> & messages, bool add_generation_prompt, bool thinking,
                    common_chat_prompt & out) const {
    common_chat_templates_inputs inputs;
    for (const auto & m : messages) {
        common_chat_msg msg;
        msg.role = m.role;
        msg.content = m.content;
        inputs.messages.push_back(std::move(msg));
    }
    inputs.add_generation_prompt = add_generation_prompt;
    inputs.enable_thinking = thinking;
    inputs.add_bos = llama_vocab_get_add_bos(llama_model_get_vocab(model));

    try {
        inputs.use_jinja = true;
        out = common_chat_templates_apply(templates.get(), inputs).prompt;
    } catch (const std::exception & e) {
        LOGW("Jinja template failed (%s); falling back to the built-in template", e.what());
        try {
            inputs.use_jinja = false;
            out = common_chat_templates_apply(templates.get(), inputs).prompt;
        } catch (const std::exception & e2) {
            LOGE("Chat template failed: %s", e2.what());
            return false;
        }
    }
    return !out.parts.empty();
}

// Text the user typed is tokenized without parsing special tokens, so typing "<|im_end|>"
// into the chat cannot break out of the user's turn. `ends` receives the token count after
// each part.
llama_tokens Engine::tokenize(const std::vector<common_chat_prompt_part> & parts, bool add_special,
                              std::vector<size_t> * ends) const {
    const llama_vocab * vocab = llama_model_get_vocab(model);
    llama_tokens out;
    for (size_t i = 0; i < parts.size(); ++i) {
        const auto tokens = common_tokenize(vocab, parts[i].text, add_special && i == 0, !parts[i].is_input);
        out.insert(out.end(), tokens.begin(), tokens.end());
        if (ends) ends->push_back(out.size());
    }
    return out;
}

// Retry: the same messages as the last prompt, so the same tokens, which the cache still holds.
bool Engine::repeat(const std::vector<Message> & messages, bool thinking, Target & target) const {
    if (conv.empty() || messages.size() != conv.size() || thinking != asked_thinking) return false;
    if (asked.tokens.empty() || asked.tokens.size() > cached.size()) return false;
    for (size_t i = 0; i < conv.size(); ++i) {
        if (!same(messages[i], conv[i])) return false;
    }
    target = asked;
    return true;
}

// A follow-up to the reply in `base`: everything the model has seen stays exactly as it was,
// and only the new message is appended. Rendering the whole conversation again would not match
// what is cached (Qwen's template, for one, drops the empty <think></think> from earlier
// replies), and models with recurrent layers cannot go back to where the two differ. The base is
// the turn the cache holds, or for an edited last message, the turn before it.
bool Engine::continuation(const std::vector<Message> & messages, bool thinking, const Turn & base, Target & target) const {
    const size_t n = base.conv.size();
    if (n == 0 || base.n_tokens == 0 || base.n_tokens > cached.size() || messages.size() != n + 2) return false;
    for (size_t i = 0; i < n; ++i) {
        if (!same(messages[i], base.conv[i])) return false;
    }
    const Message & answer = messages[n];
    const Message & question = messages[n + 1];
    if (answer.role != "assistant" || question.role != "user" || answer.content != answer_of(base.reply)) return false;

    // The template text between the answer and the new message comes from rendering just
    // the last exchange (and the system prompt, which some templates insist on seeing).
    std::vector<Message> tail;
    if (messages[0].role == "system") tail.push_back(messages[0]);
    tail.push_back(base.conv.back());
    tail.push_back(answer);
    tail.push_back(question);
    common_chat_prompt prompt;
    if (!render(tail, true, thinking, prompt)) return false;
    const auto & parts = prompt.parts;

    const int q = find_content(parts, question.content, static_cast<int>(parts.size()));
    const int a = find_content(parts, answer.content, q);
    if (q < 0 || a < 0 || parts[a + 1].is_input) return false;

    // The template closes the answer with the end-of-turn token. If the reply ended on its own,
    // the cache already holds that token; if it was cut short, it still needs it.
    std::vector<common_chat_prompt_part> delta(parts.begin() + a + 1, parts.end());
    const llama_token eot_token =
        base.reply_closed ? cached[base.n_tokens - 1] : llama_vocab_eot(llama_model_get_vocab(model));
    if (eot_token == LLAMA_TOKEN_NULL) return false;
    const std::string eot = common_token_to_piece(ctx, eot_token, /* special */ true);
    if (delta[0].text.compare(0, eot.size(), eot) != 0) return false;
    if (base.reply_closed) delta[0].text.erase(0, eot.size());
    std::vector<size_t> ends;
    const llama_tokens tokens = tokenize(delta, false, &ends);
    target.tokens.assign(cached.begin(), cached.begin() + static_cast<long>(base.n_tokens));
    target.tokens.insert(target.tokens.end(), tokens.begin(), tokens.end());
    target.system_end = 0;
    target.user_end = base.n_tokens + ends[static_cast<size_t>(q - a - 1)];
    target.opens_thinking = ends_with_think_open(parts.back().text);
    return true;
}

// The whole conversation, rendered with the model's chat template. If it doesn't fit next to
// a reply of `reserve` tokens, the oldest exchanges go (never the system prompt), and then
// more of them, until it fills half of the room: models with recurrent layers have to process
// whatever is kept again, so this happens once every few turns rather than on every one.
int Engine::whole(const std::vector<Message> & input, bool thinking, int reserve, Target & target) const {
    std::vector<Message> messages = input;
    const size_t first = (!messages.empty() && messages[0].role == "system") ? 1 : 0;
    const int room = n_ctx - reserve;
    int goal = room;
    common_chat_prompt prompt;
    std::vector<size_t> ends;
    while (true) {
        if (!render(messages, true, thinking, prompt)) return ERR_TEMPLATE;
        ends.clear();
        target.tokens = tokenize(prompt.parts, true, &ends);
        const int n = static_cast<int>(target.tokens.size());
        if (n <= goal) break;
        if (messages.size() - first <= 1) {
            if (n <= room) break;
            return ERR_TOO_LONG;
        }
        // Drop a whole exchange: some templates (Gemma) reject a history that opens with the assistant.
        messages.erase(messages.begin() + static_cast<long>(first));
        while (messages.size() - first > 1 && messages[first].role != "user") {
            messages.erase(messages.begin() + static_cast<long>(first));
        }
        goal = room / 2;
        LOGI("Conversation too long for the context; dropped the oldest exchange");
    }

    const auto & parts = prompt.parts;
    const int q = find_content(parts, messages.back().content, static_cast<int>(parts.size()));
    const int s = first ? find_content(parts, messages[0].content, q) : -1;
    target.user_end = q >= 0 ? ends[static_cast<size_t>(q)] : 0;
    target.system_end = s >= 0 ? ends[static_cast<size_t>(s)] : 0;
    target.opens_thinking = ends_with_think_open(parts.back().text);
    return 0;
}

// Whether the cache already holds `answer` as the reply to `asked`.
bool Engine::holds_reply(const std::vector<Message> & asked, const Message & answer) const {
    if (conv.size() != asked.size() || answer_of(reply) != answer.content) return false;
    for (size_t i = 0; i < asked.size(); ++i) {
        if (!same(asked[i], conv[i])) return false;
    }
    return true;
}

void Engine::save(Checkpoint & checkpoint) {
    const size_t size = llama_state_seq_get_size_ext(ctx, 0, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY);
    checkpoint.data.resize(size);
    if (llama_state_seq_get_data_ext(ctx, checkpoint.data.data(), size, 0, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY) != size) {
        checkpoint.data.clear();
        return;
    }
    checkpoint.n_tokens = cached.size();
}

// Puts the recurrent state back and drops the attention cache past the checkpoint.
bool Engine::restore(const Checkpoint & checkpoint) {
    const size_t size = checkpoint.data.size();
    if (llama_state_seq_set_data_ext(ctx, checkpoint.data.data(), size, 0, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY) != size) {
        return false;
    }
    return llama_memory_seq_rm(llama_get_memory(ctx), 0, static_cast<llama_pos>(checkpoint.n_tokens), -1);
}

void Engine::forget_conversation() {
    conv.clear();
    reply.clear();
    reply_closed = false;
    asked = {};
}

void Engine::clear_cache() {
    llama_memory_clear(llama_get_memory(ctx), true);
    unsaved = !cached.empty();
    cached.clear();
    ckpt_system.data.clear();
    ckpt_turn.data.clear();
    truncated(0);
}

// The cache lost what it held past `n_tokens`: the logits no longer follow its last token, and
// the turn to go back to for an edit is gone if it ended later.
void Engine::truncated(size_t n_tokens) {
    logits_ready = false;
    if (previous.n_tokens > n_tokens) {
        previous = {};
        ckpt_previous.data.clear();
    }
}

// Goes back to the latest checkpoint at or before `limit`, or to an empty cache.
size_t Engine::restore_best(size_t limit) {
    Checkpoint * best = nullptr;
    for (Checkpoint * c : {&ckpt_turn, &ckpt_previous, &ckpt_system}) {
        if (c->data.empty() || c->n_tokens > limit || c->n_tokens > cached.size()) continue;
        if (!best || c->n_tokens > best->n_tokens) best = c;
    }
    if (best && restore(*best)) {
        unsaved = true;
        cached.resize(best->n_tokens);
        for (Checkpoint * c : {&ckpt_turn, &ckpt_previous, &ckpt_system}) {
            if (c->n_tokens > cached.size()) c->data.clear();
        }
        truncated(cached.size());
        return cached.size();
    }
    clear_cache();
    return 0;
}

// Drops cached tokens from `keep` on. Returns how many are left, which can be fewer than
// asked for: models with recurrent layers can only go back to a checkpoint.
size_t Engine::rewind(size_t keep) {
    if (keep >= cached.size()) return cached.size();
    if (can_rewind && llama_memory_seq_rm(llama_get_memory(ctx), 0, static_cast<llama_pos>(keep), -1)) {
        cached.resize(keep);
        unsaved = true;
        truncated(keep);
        return keep;
    }
    return restore_best(keep);
}

// Feeds target.tokens[from..] to the model, asking for logits only on the very last one.
// Batches end at the checkpoint positions, so the state there can be kept.
int Engine::process(const Target & target, size_t from, bool want_logits) {
    const auto & tokens = target.tokens;
    const size_t n = tokens.size();
    std::vector<size_t> stops;
    if (!can_rewind) {
        for (size_t p : {target.system_end, target.user_end}) {
            if (p > from && p < n) stops.push_back(p);
        }
        std::sort(stops.begin(), stops.end());
    }
    auto next_stop = stops.begin();

    for (size_t i = from; i < n;) {
        if (interrupted()) return ERR_ABORTED;  // between batches, the cache is consistent
        while (next_stop != stops.end() && *next_stop <= i) ++next_stop;
        size_t end = std::min(n, i + BATCH_SIZE);
        if (next_stop != stops.end()) end = std::min(end, *next_stop);

        batch.clear();
        for (size_t j = i; j < end; ++j) {
            batch.add(tokens[j], static_cast<llama_pos>(j), 0, want_logits && j == n - 1);
        }
        const int rc = llama_process(ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get());
        if (rc != 0) {
            // llama.cpp drops a failed batch from the attention cache, but recurrent state may
            // be half-updated.
            if (rc != 2) LOGE("Prompt decode failed with %d", rc);
            logits_ready = false;
            if (!can_rewind) restore_best(cached.size());
            return rc == 2 ? ERR_ABORTED : ERR_DECODE;
        }
        cached.insert(cached.end(), tokens.begin() + static_cast<long>(i), tokens.begin() + static_cast<long>(end));
        unsaved = true;
        logits_ready = want_logits && end == n;
        i = end;
        if (!can_rewind && i == target.system_end) save(ckpt_system);
        if (!can_rewind && i == target.user_end) save(ckpt_turn);
    }
    return 0;
}

int Engine::prompt(const std::vector<Message> & messages, const ReplyOptions & options) {
    if (!ctx) return ERR_NO_MODEL;
    stopping = false;
    finish();
    stats = {};
    how_ended = End::none;

    const int reserve = std::min(options.max_tokens, n_ctx / 2);
    const auto fits = [&](const Target & t) { return static_cast<int>(t.tokens.size()) + reserve <= n_ctx; };
    enum class From { repeat, current, previous, whole } from = From::whole;
    Target target;
    if (repeat(messages, options.thinking, target)) {
        from = From::repeat;
    } else if (continuation(messages, options.thinking, {conv, reply, reply_closed, cached.size()}, target) && fits(target)) {
        from = From::current;
    } else if (continuation(messages, options.thinking, previous, target) && fits(target)) {
        from = From::previous;  // the user edited their last message
    } else {
        target = {};
        const int rc = whole(messages, options.thinking, reserve, target);
        if (rc < 0) return rc;
    }
    if (target.tokens.empty()) return ERR_TEMPLATE;

    // The turn in the cache becomes the one to go back to if the user edits the message that
    // follows it. Retry and edits keep the one they started from.
    if (from == From::current) {
        previous = {conv, reply, reply_closed, cached.size()};
    } else if (from == From::whole) {
        previous = {};
        ckpt_previous.data.clear();
    }
    forget_conversation();  // the cache is about to change

    // Reuse the longest common prefix with what is already in the cache.
    const auto & tokens = target.tokens;
    size_t keep = 0;
    while (keep < cached.size() && keep < tokens.size() && cached[keep] == tokens[keep]) ++keep;
    if (keep == tokens.size()) --keep;  // always decode at least one token to get fresh logits
    keep = rewind(keep);
    if (from == From::current && !can_rewind) save(ckpt_previous);

    busy = Busy::prompt;
    const int rc = process(target, keep, true);
    busy = Busy::idle;

    stats.tokens = static_cast<int>(tokens.size());
    stats.reused = static_cast<int>(keep);
    stats.processed = static_cast<int>(tokens.size() - keep);
    stats.appended = from == From::current;
    stats.edited = from == From::previous;
    LOGI("Prompt: %d tokens, %d reused from cache%s", stats.tokens, stats.reused,
         stats.appended ? " (appended)" : stats.edited ? " (edited)" : "");
    if (rc != 0) return rc;

    conv = messages;
    asked = target;
    asked_thinking = options.thinking;
    // When the template opens the reasoning block itself, the app still needs to see it.
    prefix = target.opens_thinking ? THINK_OPEN : "";
    reply = prefix;
    in_thought = target.opens_thinking;
    pending.clear();
    start_reply(options, cached.size());
    return stats.tokens;
}

int Engine::resume(const std::vector<Message> & messages, const ReplyOptions & options) {
    if (!ctx) return ERR_NO_MODEL;
    if (messages.size() < 2 || messages.back().role != "assistant") return ERR_TEMPLATE;
    stopping = false;
    finish();
    stats = {};
    how_ended = End::none;

    const std::vector<Message> question(messages.begin(), messages.end() - 1);
    const Message & partial = messages.back();
    Target target;
    std::string text;         // the reply so far, as the engine keeps it
    size_t reply_start = 0;   // where its tokens start
    bool rebuilt = false;
    if (!reply_closed && holds_reply(question, partial)) {
        // The cache ends with the reply, the way next() left it.
        target = asked;
        target.tokens = cached;
        text = reply;
        reply_start = asked.tokens.size();
    } else {
        // The conversation with the reply so far as its last message, cut just before the
        // end-of-turn token that would close it. Earlier reasoning is gone by now.
        common_chat_prompt prompt;
        if (!render(messages, false, options.thinking, prompt)) return ERR_TEMPLATE;
        const auto & parts = prompt.parts;
        const int a = find_content(parts, partial.content, static_cast<int>(parts.size()));
        const int q = a > 0 ? find_content(parts, question.back().content, a) : -1;
        if (q < 0 || parts[a - 1].is_input) return ERR_TEMPLATE;
        std::vector<size_t> ends;
        target.tokens = tokenize(parts, true, &ends);
        target.tokens.resize(ends[static_cast<size_t>(a)]);
        target.user_end = ends[static_cast<size_t>(q)];
        if (messages[0].role == "system") {
            const int s = find_content(parts, messages[0].content, q);
            if (s >= 0) target.system_end = ends[static_cast<size_t>(s)];
        }
        text = partial.content;
        reply_start = ends[static_cast<size_t>(a - 1)];
        rebuilt = true;
    }
    if (static_cast<int>(target.tokens.size()) + 64 > n_ctx) return ERR_TOO_LONG;
    const Target prompt_part = rebuilt ? Target{} : asked;
    forget_conversation();

    const auto & tokens = target.tokens;
    size_t keep = 0;
    while (keep < cached.size() && keep < tokens.size() && cached[keep] == tokens[keep]) ++keep;
    if (keep == tokens.size() && !logits_ready) --keep;  // the last token's logits are needed again
    keep = rewind(keep);

    busy = Busy::prompt;
    const int rc = keep < tokens.size() ? process(target, keep, true) : 0;
    busy = Busy::idle;

    stats.tokens = static_cast<int>(tokens.size());
    stats.reused = static_cast<int>(keep);
    stats.processed = static_cast<int>(tokens.size() - keep);
    LOGI("Resume: %d tokens, %d reused from cache", stats.tokens, stats.reused);
    if (rc != 0) return rc;

    conv = question;
    if (rebuilt) {
        // Retry renders the question again instead: its reply may open differently.
        asked = {};
        asked_thinking = false;
    } else {
        asked = prompt_part;
    }
    reply = text;
    reply_closed = false;
    prefix.clear();
    if (rebuilt) pending.clear();  // otherwise it holds the start of a character the reply was cut inside
    in_thought = still_thinking(text);
    start_reply(options, reply_start);
    if (in_thought) thought_budget = 0;  // whatever was left to think about, the answer comes now
    return stats.tokens;
}

// Sets up sampling for a reply whose tokens start at `reply_start` in the cache; any that are
// already there count towards the penalties.
void Engine::start_reply(const ReplyOptions & options, size_t reply_start) {
    common_params_sampling sparams = sampling_for(options, n_ctx);
    sampler = common_sampler_init(model, sparams);
    for (size_t i = reply_start; i < cached.size(); ++i) common_sampler_accept(sampler, cached[i], false);
    n_generated = 0;
    max_tokens = options.max_tokens;
    thought_tokens = 0;
    thought_budget = options.max_tokens * 3 / 4;
}

bool Engine::next(std::string & piece) {
    piece.clear();
    if (!ctx || !sampler) return false;
    if (!prefix.empty()) {
        piece.swap(prefix);
        return true;
    }
    if (stopping.load()) {
        how_ended = End::stopped;
        return finish();
    }
    if (n_generated >= max_tokens) {
        how_ended = End::length;
        return finish();
    }
    if (static_cast<int>(cached.size()) >= n_ctx) {
        how_ended = End::full;
        return finish();
    }
    // A small model can think in circles (Qwen warns about it for the 2B): past its budget, the
    // reasoning is closed for it while there is room left for an answer.
    if (in_thought && thought_tokens >= thought_budget && pending.empty()) return close_thought(piece);

    const llama_token token = common_sampler_sample(sampler, ctx, -1);
    common_sampler_accept(sampler, token, true);

    batch.clear();
    batch.add(token, static_cast<llama_pos>(cached.size()), 0, true);
    if (llama_process(ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) return fail();
    cached.push_back(token);
    unsaved = true;
    logits_ready = true;
    ++n_generated;

    // The end-of-turn token stays in the cache, so the next message can follow it directly.
    if (llama_vocab_is_eog(llama_model_get_vocab(model), token)) {
        reply_closed = true;
        how_ended = End::done;
        return finish();
    }

    pending += common_token_to_piece(ctx, token, /* special */ true);
    const size_t ready = complete_utf8_length(pending);
    piece = pending.substr(0, ready);
    pending.erase(0, ready);
    reply += piece;
    if (in_thought) {
        ++thought_tokens;
        const size_t from = reply.size() - std::min(reply.size(), piece.size() + strlen(THINK_CLOSE));
        if (reply.find(THINK_CLOSE, from) != std::string::npos) in_thought = false;
    }
    return true;
}

// Feeds the model the end of its reasoning as if it had written it, and hands the app that text
// as the next piece.
bool Engine::close_thought(std::string & piece) {
    const llama_tokens tokens = common_tokenize(llama_model_get_vocab(model), THINK_OUT_OF_ROOM, false, true);
    batch.clear();
    for (size_t i = 0; i < tokens.size(); ++i) {
        batch.add(tokens[i], static_cast<llama_pos>(cached.size() + i), 0, i + 1 == tokens.size());
    }
    if (llama_process(ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) return fail();
    for (const llama_token token : tokens) common_sampler_accept(sampler, token, false);
    cached.insert(cached.end(), tokens.begin(), tokens.end());
    unsaved = true;
    logits_ready = true;
    in_thought = false;  // the model didn't write these tokens, so they don't count towards max_tokens
    LOGI("Closed the reasoning after %d tokens", thought_tokens);
    piece = THINK_OUT_OF_ROOM;
    reply += piece;
    return true;
}

// Decoding failed part-way through a reply: recurrent state may be half-updated, so the cache
// goes back to a checkpoint.
bool Engine::fail() {
    LOGE("Decode failed while generating");
    logits_ready = false;
    if (!can_rewind) restore_best(cached.size());
    forget_conversation();
    how_ended = End::failed;
    return finish();
}

void Engine::forget() {
    if (!ctx) return;
    finish();
    clear_cache();
    forget_conversation();
    unsaved = false;
}

// Which model a session belongs to: a state only fits the model it came from.
std::string Engine::identity() const {
    return describe() + "|" + std::to_string(llama_model_n_params(model)) + "|" + std::to_string(llama_model_size(model));
}

bool Engine::save_session(const std::string & path) {
    if (!ctx || busy != Busy::idle) return false;
    if (!unsaved) return true;
    if (conv.empty() || cached.empty()) {  // nothing worth picking up again
        remove(path.c_str());
        unsaved = false;
        return true;
    }
    std::vector<uint8_t> state(llama_state_seq_get_size_ext(ctx, 0, 0));
    if (llama_state_seq_get_data_ext(ctx, state.data(), state.size(), 0, 0) != state.size()) return false;

    const std::string temp = path + ".tmp";
    FILE * f = fopen(temp.c_str(), "wb");
    if (!f) return false;
    Writer w{f};
    w.pod(SESSION_MAGIC);
    w.pod(SESSION_VERSION);
    w.str(identity());
    w.vec(cached);
    w.pod<uint64_t>(conv.size());
    for (const auto & m : conv) {
        w.str(m.role);
        w.str(m.content);
    }
    w.str(reply);
    w.pod(reply_closed);
    w.pod<uint64_t>(asked.tokens.size());
    w.pod<uint64_t>(asked.system_end);
    w.pod<uint64_t>(asked.user_end);
    w.pod(asked.opens_thinking);
    w.pod(asked_thinking);
    w.pod<uint64_t>(ckpt_turn.data.empty() ? 0 : ckpt_turn.n_tokens);
    w.vec(ckpt_turn.data);
    w.vec(state);
    const bool ok = fclose(f) == 0 && w.ok && rename(temp.c_str(), path.c_str()) == 0;
    if (!ok) {
        remove(temp.c_str());
        return false;
    }
    unsaved = false;
    LOGI("Saved a %zu-token conversation (%.0f MiB)", cached.size(), (state.size() + ckpt_turn.data.size()) / 1048576.0);
    return true;
}

bool Engine::load_session(const std::string & path) {
    if (!ctx) return false;
    FILE * f = fopen(path.c_str(), "rb");
    if (!f) return false;
    fseek(f, 0, SEEK_END);
    const long size = ftell(f);
    fseek(f, 0, SEEK_SET);
    Reader r{f, size > 0 ? static_cast<size_t>(size) : 0};
    const bool header = r.pod<uint32_t>() == SESSION_MAGIC && r.pod<uint32_t>() == SESSION_VERSION && r.str() == identity();
    if (!header || !r.ok) {
        fclose(f);
        return false;
    }
    llama_tokens tokens = r.vec<llama_token>();
    std::vector<Message> messages(r.count(16));
    for (auto & m : messages) {
        m.role = r.str();
        m.content = r.str();
    }
    std::string text = r.str();
    const bool closed = r.pod<bool>();
    const size_t asked_len = r.pod<uint64_t>();
    Target prompt;
    prompt.system_end = r.pod<uint64_t>();
    prompt.user_end = r.pod<uint64_t>();
    prompt.opens_thinking = r.pod<bool>();
    const bool prompt_thinking = r.pod<bool>();
    Checkpoint turn;
    turn.n_tokens = r.pod<uint64_t>();
    turn.data = r.vec<uint8_t>();
    const std::vector<uint8_t> state = r.vec<uint8_t>();
    fclose(f);
    if (!r.ok || tokens.empty() || static_cast<int>(tokens.size()) >= n_ctx || asked_len > tokens.size()) return false;

    finish();
    clear_cache();
    forget_conversation();
    if (llama_state_seq_set_data_ext(ctx, state.data(), state.size(), 0, 0) != state.size()) {
        clear_cache();
        return false;
    }
    cached = std::move(tokens);
    conv = std::move(messages);
    reply = std::move(text);
    reply_closed = closed;
    prompt.tokens.assign(cached.begin(), cached.begin() + static_cast<long>(asked_len));
    asked = std::move(prompt);
    asked_thinking = prompt_thinking;
    if (!can_rewind && turn.n_tokens > 0 && turn.n_tokens <= cached.size()) ckpt_turn = std::move(turn);
    unsaved = false;
    LOGI("Picked up a %zu-token conversation", cached.size());
    return true;
}

int Engine::prefill(const std::vector<Message> & messages, bool thinking, int max_tokens_hint) {
    if (!ctx) return ERR_NO_MODEL;
    cancelling = false;
    if (messages.empty()) return static_cast<int>(cached.size());

    const bool has_question = std::any_of(messages.begin(), messages.end(), [](const Message & m) { return m.role == "user"; });
    Target target, next_asked;
    std::vector<Message> next_conv;
    std::string next_reply;
    common_chat_prompt prompt;
    std::vector<size_t> ends;

    if (has_question && messages.back().role == "assistant") {
        // Picking a chat back up: the cache should look as if its last reply had just been
        // generated, so the next message is simply appended.
        next_conv.assign(messages.begin(), messages.end() - 1);
        next_reply = messages.back().content;
        if (holds_reply(next_conv, messages.back())) return static_cast<int>(cached.size());
        if (!render(messages, false, thinking, prompt)) return ERR_TEMPLATE;
        const auto & parts = prompt.parts;
        const int a = find_content(parts, next_reply, static_cast<int>(parts.size()));
        if (a < 0 || a + 1 >= static_cast<int>(parts.size())) return static_cast<int>(cached.size());
        target.tokens = tokenize(parts, true, &ends);
        target.tokens.resize(ends[static_cast<size_t>(a)]);
        // ... followed by the end-of-turn token that closes the reply.
        const llama_vocab * vocab = llama_model_get_vocab(model);
        const auto closing = common_tokenize(vocab, parts[a + 1].text, false, true);
        if (closing.empty() || !llama_vocab_is_eog(vocab, closing[0])) return static_cast<int>(cached.size());
        target.tokens.push_back(closing[0]);
        const int q = find_content(parts, next_conv.back().content, a);
        target.user_end = q >= 0 ? ends[static_cast<size_t>(q)] : 0;
        next_asked.tokens.assign(target.tokens.begin(), target.tokens.begin() + static_cast<long>(ends[static_cast<size_t>(a - 1)]));
        next_asked.user_end = target.user_end;
    } else if (!has_question) {
        // A new chat: everything up to where the user's first message will go.
        std::vector<Message> probe = messages;
        probe.push_back({"user", "?"});
        if (!render(probe, true, thinking, prompt)) return ERR_TEMPLATE;
        const auto & parts = prompt.parts;
        const int q = find_content(parts, "?", static_cast<int>(parts.size()));
        if (q < 0) return static_cast<int>(cached.size());
        target.tokens = tokenize(parts, true, &ends);
        target.tokens.resize(q > 0 ? ends[static_cast<size_t>(q - 1)] : 0);
    } else {
        return static_cast<int>(cached.size());  // a question waiting for its reply: prompt() handles it
    }
    if (messages[0].role == "system") {
        const int s = find_content(prompt.parts, messages[0].content, static_cast<int>(prompt.parts.size()));
        if (s >= 0 && ends[static_cast<size_t>(s)] <= target.tokens.size()) target.system_end = ends[static_cast<size_t>(s)];
        next_asked.system_end = target.system_end;
    }
    // Too long to keep whole: prompt() will decide what to drop.
    if (static_cast<int>(target.tokens.size()) + std::min(max_tokens_hint, n_ctx / 2) > n_ctx) {
        return static_cast<int>(cached.size());
    }

    const auto & tokens = target.tokens;
    size_t keep = 0;
    while (keep < cached.size() && keep < tokens.size() && cached[keep] == tokens[keep]) ++keep;
    if (keep < tokens.size() || keep < cached.size()) {
        finish();
        forget_conversation();
        keep = rewind(keep);

        busy = Busy::prefill;
        const int rc = process(target, keep, false);
        busy = Busy::idle;
        LOGI("Prefill: %zu tokens, %zu reused from cache", tokens.size(), keep);
        if (rc != 0) return rc;
    }
    unsaved = unsaved || !holds_reply(next_conv, {"assistant", next_reply});
    conv = next_conv;
    reply = next_reply;
    reply_closed = !next_conv.empty();
    asked = next_asked;
    asked_thinking = false;  // the history renders earlier replies without their reasoning
    return static_cast<int>(cached.size());
}

}  // namespace autark
