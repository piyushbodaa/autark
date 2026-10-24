// Autark's inference engine: one model and one conversation at a time, on top of llama.cpp.
//
// Plain C++ with no JNI, so the same code runs in the app and in tools/engine-test on a
// desktop. Every call must come from the same thread, except stop() and cancel_prefill(),
// which only flip flags.

#pragma once

#include <atomic>
#include <cstdint>
#include <string>
#include <vector>

#include "chat.h"
#include "common.h"
#include "llama.h"
#include "sampling.h"

namespace autark {

// Error codes returned to Kotlin; keep in sync with LlamaEngine.
constexpr int ERR_NO_MODEL    = -1;
constexpr int ERR_LOAD_FAILED = -2;
constexpr int ERR_CONTEXT     = -3;
constexpr int ERR_TOO_LONG    = -4;
constexpr int ERR_DECODE      = -5;
constexpr int ERR_ABORTED     = -6;
constexpr int ERR_TEMPLATE    = -7;

// How the last reply ended; keep in sync with LlamaEngine.
enum class End : int {
    none    = 0,  // still going, or nothing generated yet
    done    = 1,  // the model finished it
    length  = 2,  // it reached max_tokens, and resume() can carry on
    stopped = 3,  // stop() ended it
    full    = 4,  // the context ran out of room
    failed  = 5,  // decoding failed
};

struct Message {
    std::string role;  // "system", "user" or "assistant"
    std::string content;
};

struct ReplyOptions {
    bool     thinking         = false;
    float    temperature      = 0.7f;
    float    top_p            = 0.95f;
    int      top_k            = 40;
    float    min_p            = 0.05f;
    float    presence_penalty = 0.0f;  // counted over the whole reply, the way Qwen's model cards mean it
    int      max_tokens       = 1024;
    uint32_t seed             = LLAMA_DEFAULT_SEED;
};

// What the last prompt() call did, for logs and tests.
struct PromptStats {
    int  tokens    = 0;      // prompt length
    int  reused    = 0;      // tokens that were already in the cache
    int  processed = 0;      // tokens that had to go through the model
    bool appended  = false;  // the new message was appended to the cached conversation
    bool edited    = false;  // the user's last message was replaced, from the turn before it
};

class Engine {
public:
    Engine() = default;
    ~Engine();
    Engine(const Engine &) = delete;
    Engine & operator=(const Engine &) = delete;

    // Loads the CPU backend variants from `lib_dir` and initializes llama.cpp. Call once.
    static void init(const std::string & lib_dir);

    // Returns the context size actually allocated, or a negative error code. `n_threads` runs
    // generation, `n_threads_batch` prompt processing.
    int load(const std::string & path, int n_ctx, int n_threads, int n_threads_batch);
    void unload();

    std::string describe() const;
    bool supports_thinking() const;

    // Prepares a reply to the conversation. Returns the prompt length in tokens, or a negative
    // error code. Replacing the user's last message (an edit) is about as cheap as Retry.
    int prompt(const std::vector<Message> & messages, const ReplyOptions & options);

    // Carries on with a reply that ran out of room (ended() was End::length), or that the app
    // never saw finish. `messages` ends with that reply as the app has it. Returns the prompt
    // length in tokens, or a negative error code; next() then continues the reply.
    int resume(const std::vector<Message> & messages, const ReplyOptions & options);

    // Generates one token. Sets `piece` to its text (possibly empty while a multi-byte
    // character is still being assembled) and returns true, or returns false when the reply
    // is finished. When thinking, the reasoning is closed after three quarters of max_tokens,
    // so that there is always room left for an answer.
    bool next(std::string & piece);

    // Why the last reply ended.
    End ended() const { return how_ended; }

    // Processes a conversation ahead of time, while the user is still typing, so the next
    // prompt() only has to process their new message. `messages` is the chat as it is (ending
    // with the last reply, or holding just the system prompt). Returns the number of tokens in
    // the cache, or a negative error code.
    int prefill(const std::vector<Message> & messages, bool thinking, int max_tokens);

    // Keeps the conversation in the cache on disk, for when Android stops the app or takes the
    // model's memory back: loading it again is much faster than processing the chat again.
    // save_session() writes only if something changed; load_session() goes right after load().
    bool save_session(const std::string & path);
    bool load_session(const std::string & path);

    // Empties the cache, for when the user deletes chats.
    void forget();

    // Both safe to call from any thread.
    void stop() { stopping = true; }               // ends the reply being prepared or generated
    void cancel_prefill() { cancelling = true; }   // ends a prefill() part-way

    const PromptStats & last_prompt() const { return stats; }

private:
    // State after the model has seen the first `n_tokens` cached tokens. Only kept for models
    // with recurrent layers (Qwen 3.5), which cannot rewind; it holds just that recurrent state.
    struct Checkpoint {
        size_t               n_tokens = 0;
        std::vector<uint8_t> data;
    };

    // Tokens the cache should hold, and where to keep checkpoints while processing them.
    struct Target {
        llama_tokens tokens;
        size_t       system_end = 0;      // after the system prompt, shared by every new chat
        size_t       user_end   = 0;      // after the user's latest message, for Retry
        bool         opens_thinking = false;  // the prompt leaves the model inside <think>
    };

    // A conversation whose start the cache holds: the messages of a prompt, the reply to them,
    // and the number of cached tokens they take.
    struct Turn {
        std::vector<Message> conv;
        std::string          reply;
        bool                 reply_closed = false;
        size_t               n_tokens = 0;
    };

    bool render(const std::vector<Message> & messages, bool add_generation_prompt, bool thinking,
                common_chat_prompt & out) const;
    llama_tokens tokenize(const std::vector<common_chat_prompt_part> & parts, bool add_special,
                          std::vector<size_t> * ends) const;
    bool repeat(const std::vector<Message> & messages, bool thinking, Target & target) const;
    bool continuation(const std::vector<Message> & messages, bool thinking, const Turn & base, Target & target) const;
    int  whole(const std::vector<Message> & messages, bool thinking, int reserve, Target & target) const;
    bool holds_reply(const std::vector<Message> & asked, const Message & answer) const;

    size_t rewind(size_t keep);
    size_t restore_best(size_t limit);
    void   truncated(size_t n_tokens);
    int    process(const Target & target, size_t from, bool want_logits);
    void   save(Checkpoint & checkpoint);
    bool   restore(const Checkpoint & checkpoint);
    void   clear_cache();
    void   forget_conversation();
    bool   interrupted() const;
    bool   finish();
    bool   fail();
    bool   close_thought(std::string & piece);
    void   start_reply(const ReplyOptions & options, size_t reply_start);
    std::string identity() const;

    static bool should_abort(void * self);

    enum class Busy { idle, prompt, prefill };

    llama_model *             model   = nullptr;
    llama_context *           ctx     = nullptr;
    common_sampler *          sampler = nullptr;
    common_chat_templates_ptr templates;
    common_batch              batch;
    llama_tokens              cached;  // tokens in the KV cache, position i holds cached[i]
    bool                      can_rewind = true;    // the cache can drop its tail (no recurrent layers)
    bool                      unsaved = false;      // the cache changed since the last save_session()
    bool                      logits_ready = false; // the context holds the logits after cached.back()
    Checkpoint                ckpt_system;
    Checkpoint                ckpt_turn;
    Checkpoint                ckpt_previous;  // where `previous` ends, for an edited last message

    // The conversation the cache holds: the messages of the last prompt, then the reply to them.
    std::vector<Message>      conv;
    std::string               reply;          // text generated since, as the app received it
    bool                      reply_closed = false;  // the reply ended with an end-of-turn token
    Target                    asked;          // the last prompt; cached[0..asked.tokens.size()) holds it
    bool                      asked_thinking = false;
    Turn                      previous;       // the turn before it, if the last prompt was appended to it

    int                       n_ctx       = 0;
    int                       n_generated = 0;
    int                       max_tokens  = 0;
    bool                      in_thought  = false;  // the reply is inside its <think> block
    int                       thought_tokens = 0;
    int                       thought_budget = 0;
    End                       how_ended = End::none;
    std::string               pending;  // trailing bytes of an unfinished UTF-8 character
    std::string               prefix;   // text to emit before the first generated token
    PromptStats               stats;
    std::atomic<Busy>         busy{Busy::idle};
    std::atomic<bool>         stopping{false};
    std::atomic<bool>         cancelling{false};
};

}  // namespace autark
