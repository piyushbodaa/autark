// Drives Autark's engine (app/src/main/cpp/engine.cpp) through scripted chats on a desktop,
// the same way the app does, and reports how much of each prompt had to be processed.
// Replies are greedy, so some steps can check themselves: Retry must reproduce the reply it
// replaces, and a new chat must match the same chat run on an empty cache.
//
//   engine-test MODEL.gguf [--ctx N] [--threads N] [--threads-batch N] [--max-tokens N]
//                          [--turns N] [--verbose] [--load-only]
//
// --load-only stops after loading the model, to time loads.

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>

#include "engine.h"

namespace {

using Clock = std::chrono::steady_clock;
using Chat = std::vector<autark::Message>;

double ms_since(Clock::time_point t) {
    return std::chrono::duration<double, std::milli>(Clock::now() - t).count();
}

const char * SYSTEM_PROMPT =
    "You are Autark, a helpful assistant that runs privately on the user's phone. "
    "Answer clearly and briefly. Reply in the language the user writes in. "
    "Write maths in plain text, not LaTeX.";

const std::vector<std::string> QUESTIONS = {
    "Explain how UPI works, simply.",
    "Is it safe to share my UPI ID with strangers?",
    "Write a short, polite leave request to my manager for Friday.",
    "Make it a little more formal.",
    "Give me a 20-minute home workout with no equipment.",
    "Which of those exercises are good for lower back pain?",
    "मुझे हिंदी में एक छोटी कहानी सुनाओ।",
    "Summarize everything we talked about in three bullet points.",
};

struct Step {
    std::string label;
    autark::PromptStats stats;
    double prompt_ms = 0, first_ms = 0, gen_ms = 0;
    int generated = 0;
    std::string note;
};

// Mirrors Thinking.split(text).answer in the app: earlier replies go back without reasoning.
std::string answer_of(const std::string & text) {
    const size_t start = text.find("<think>");
    if (start == std::string::npos || text.find_first_not_of(" \t\r\n") < start) return text;
    const size_t end = text.find("</think>", start);
    if (end == std::string::npos) return {};
    const size_t b = text.find_first_not_of(" \t\r\n", end + 8);
    return b == std::string::npos ? std::string() : text.substr(b);
}

struct Runner {
    autark::Engine & engine;
    autark::ReplyOptions options;
    bool verbose = false;
    std::vector<Step> steps;
    int failures = 0;

    // Asks for a reply the way AppViewModel does. Stops after `stop_after` pieces if set. With
    // `resume`, the chat ends with a reply that was cut off, and this carries on with it.
    std::string ask(const Chat & chat, const std::string & label, int stop_after = -1, bool resume = false) {
        Step step;
        step.label = label;
        const auto start = Clock::now();
        engine.begin_reply();
        const int rc = resume ? engine.resume(chat, options, "engine-test") : engine.prompt(chat, options, "engine-test");
        step.prompt_ms = ms_since(start);
        if (rc < 0) {
            fprintf(stderr, "%s: prompt failed with %d\n", label.c_str(), rc);
            exit(1);
        }
        step.stats = engine.last_prompt();

        std::string reply, piece;
        const auto gen_start = Clock::now();
        while (engine.next(piece)) {
            if (step.generated == 0) step.first_ms = ms_since(start);
            ++step.generated;
            reply += piece;
            if (verbose) {
                fputs(piece.c_str(), stdout);
                fflush(stdout);
            }
            if (step.generated == stop_after) engine.stop();
        }
        step.gen_ms = ms_since(gen_start);
        if (verbose) fputs("\n\n", stdout);
        steps.push_back(step);
        return reply;
    }

    void prefill(const Chat & chat, const std::string & label) {
        Step step;
        step.label = label;
        const auto start = Clock::now();
        const int rc = engine.prefill(chat, options.thinking, options.max_tokens, "engine-test");
        step.prompt_ms = ms_since(start);
        if (rc < 0) {
            fprintf(stderr, "%s: prefill failed with %d\n", label.c_str(), rc);
            exit(1);
        }
        step.stats.tokens = rc;
        steps.push_back(step);
    }

    void check(bool ok, const std::string & what) {
        steps.back().note = ok ? "ok: " + what : "FAILED: " + what;
        if (!ok) ++failures;
    }

    void print() const {
        printf("\n%-17s %7s %7s %9s %10s %12s %5s %7s\n", "step", "prompt", "reused", "processed", "prompt ms",
               "1st token ms", "gen", "tok/s");
        int processed = 0;
        double waited = 0;
        for (const auto & s : steps) {
            printf("%-17s %7d %7d %9d %10.0f %12.0f %5d %7.1f  %s%s\n", s.label.c_str(), s.stats.tokens, s.stats.reused,
                   s.stats.processed, s.prompt_ms, s.first_ms, s.generated,
                   s.gen_ms > 0 ? s.generated * 1000.0 / s.gen_ms : 0.0,
                   s.stats.appended ? "appended " : s.stats.edited ? "edited " : "", s.note.c_str());
            processed += s.stats.processed;
            waited += s.first_ms;
        }
        printf("total: %d prompt tokens processed, %.1f s waiting for first tokens\n", processed, waited / 1000.0);
    }
};

// Resident memory of this process in MiB, as the kernel reports it: "VmRSS" now, "VmHWM" at its
// peak, and of the resident memory, "RssAnon" the process's own and "RssFile" mapped from files.
double memory(const char * key) {
    std::ifstream status("/proc/self/status");
    std::string line;
    const size_t n = strlen(key);
    while (std::getline(status, line)) {
        if (line.compare(0, n, key) == 0 && line.size() > n && line[n] == ':') return atof(line.c_str() + n + 1) / 1024;
    }
    return 0;
}

std::string memory_now() {
    char text[128];
    snprintf(text, sizeof(text), "%.0f MiB (%.0f anonymous, %.0f files)", memory("VmRSS"), memory("RssAnon"),
             memory("RssFile"));
    return text;
}

}  // namespace

int main(int argc, char ** argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: %s MODEL.gguf [--ctx N] [--threads N] [--threads-batch N] [--max-tokens N] "
                        "[--turns N] [--verbose] [--load-only]\n", argv[0]);
        return 1;
    }
    const std::string model_path = argv[1];
    int n_ctx = 4096, n_threads = 4, n_threads_batch = 0, max_tokens = 256;
    int n_turns = static_cast<int>(QUESTIONS.size());
    bool verbose = false, load_only = false;
    for (int i = 2; i < argc; ++i) {
        const bool has_value = i + 1 < argc;
        if (!strcmp(argv[i], "--ctx") && has_value) n_ctx = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--threads") && has_value) n_threads = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--threads-batch") && has_value) n_threads_batch = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--max-tokens") && has_value) max_tokens = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--turns") && has_value) n_turns = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--verbose")) verbose = true;
        else if (!strcmp(argv[i], "--load-only")) load_only = true;
    }
    if (n_threads_batch <= 0) n_threads_batch = n_threads;
    n_turns = std::min<int>(n_turns, static_cast<int>(QUESTIONS.size()));

    autark::Engine::init(".");
    autark::Engine engine;
    const auto load_start = Clock::now();
    const int allocated = engine.load(model_path, n_ctx, n_threads, n_threads_batch);
    if (allocated < 0) {
        fprintf(stderr, "load failed: %d\n", allocated);
        return 1;
    }
    printf("loaded %s (%d context tokens, %d/%d threads) in %.0f ms, memory: %s, %.0f MiB at peak\n",
           engine.describe().c_str(), allocated, n_threads, n_threads_batch, ms_since(load_start), memory_now().c_str(),
           memory("VmHWM"));
    if (load_only) return 0;

    Runner run{engine};
    run.verbose = verbose;
    run.options.temperature = 0.0f;  // greedy, so runs are repeatable
    run.options.max_tokens = max_tokens;
    run.options.seed = 42;

    // A one-question chat on an empty cache, to compare with the same chat later.
    const Chat fresh = {{"system", SYSTEM_PROMPT}, {"user", "What can you do offline?"}};
    const std::string fresh_reply = run.ask(fresh, "cold chat");

    // A long chat. After the second answer, tap Retry once.
    Chat chat = {{"system", SYSTEM_PROMPT}};
    for (int i = 0; i < n_turns; ++i) {
        chat.push_back({"user", QUESTIONS[i]});
        std::string reply = run.ask(chat, "turn " + std::to_string(i + 1));
        if (i == 1) {
            const std::string again = run.ask(chat, "retry");
            run.check(again == reply, "same reply as before");
            reply = again;
        }
        chat.push_back({"assistant", answer_of(reply)});
    }

    // A new chat shares only the system prompt with the cache.
    run.check(run.ask(fresh, "new chat") == fresh_reply, "same reply as on an empty cache");

    // Back to the long chat, the way the app does it once the user starts typing.
    run.prefill(chat, "reopen: prefill");
    chat.push_back({"user", "Thanks! One more thing: what is a UPI PIN?"});
    const std::string pin = run.ask(chat, "reopen: ask");
    run.check(run.steps.back().stats.appended, "only the new message processed");
    run.check(run.ask(chat, "reopen: retry") == pin, "same reply as before");
    chat.push_back({"assistant", answer_of(pin)});

    // Stop a reply early, then carry on.
    chat.push_back({"user", "Tell me a long story about a cat."});
    chat.push_back({"assistant", answer_of(run.ask(chat, "stopped", 8))});
    run.check(run.steps.back().generated == 8, "stopped after 8 pieces");
    chat.push_back({"user", "Thanks, that's enough. What's 2 + 2?"});
    chat.push_back({"assistant", answer_of(run.ask(chat, "after stop"))});
    const bool trimmed = !run.steps.back().stats.appended;  // the chat no longer fits a small context
    run.check(run.steps.back().stats.appended || run.steps.back().stats.tokens < allocated / 2,
              "appended, or trimmed to half the context");
    chat.push_back({"user", "And 3 + 3?"});
    chat.push_back({"assistant", answer_of(run.ask(chat, trimmed ? "after trim" : "one more"))});
    run.check(run.steps.back().stats.appended, "appended");

    // Android stops the app: the chat is saved, the model unloaded, and later loaded again.
    {
        const char * tmp = getenv("TMPDIR");  // the app's cache directory on a phone
        const std::string session = std::string(tmp && *tmp ? tmp : "/tmp") + "/engine-test-session.bin";
        Step step;
        step.label = "save + reload";
        const auto start = Clock::now();
        const bool saved = engine.save_session(session);
        engine.load(model_path, n_ctx, n_threads, n_threads_batch);
        const bool loaded = engine.load_session(session);
        step.prompt_ms = ms_since(start);
        run.steps.push_back(step);
        run.check(saved && loaded && engine.cached_chat_id() == "engine-test" &&
                  autark::Engine::session_chat_id(session) == "engine-test", "saved and picked up again with its owner");
        remove(session.c_str());
    }
    run.prefill(chat, "after reload");
    run.check(run.steps.back().prompt_ms < 50, "nothing left to process");
    chat.push_back({"user", "And what's 5 + 5?"});
    const std::string five = run.ask(chat, "ask after reload");
    run.check(run.steps.back().stats.appended, "appended");

    // Edit that last message, then put it back: each time the cache goes back to the end of the
    // reply before it, and the original question gets its original reply.
    chat.back().content = "And what's 6 + 6?";
    run.ask(chat, "edit");
    run.check(run.steps.back().stats.edited && run.steps.back().stats.processed < 64, "only the new message processed");
    chat.back().content = "And what's 5 + 5?";
    run.check(run.ask(chat, "edit back") == five, "same reply as before the edits");
    chat.push_back({"assistant", answer_of(five)});
    chat.push_back({"user", "Thanks!"});
    run.ask(chat, "after edits");
    run.check(run.steps.back().stats.appended, "appended");

    // A reply cut off at the length limit and continued reads the same as one written in one go.
    {
        const autark::ReplyOptions plain = run.options;
        Chat counting = {{"system", SYSTEM_PROMPT}, {"user", "Count from 1 to 40, separated by commas."}};
        run.options.max_tokens = 48;
        const std::string in_one_go = run.ask(counting, "48 tokens");
        run.options.max_tokens = 24;
        std::string cut = run.ask(counting, "24 tokens");
        run.check(engine.ended() == autark::End::length, "cut off at the limit");
        counting.push_back({"assistant", answer_of(cut)});
        cut += run.ask(counting, "continue", -1, true);
        run.check(run.steps.back().stats.processed == 0 && cut == in_one_go, "nothing to process, same text as in one go");
        // Once the cache has moved on, the reply is rebuilt from the chat.
        counting.back().content = answer_of(cut);
        // Changing only the owner while resuming cached tokens must still update the saved footer.
        const char * tmp = getenv("TMPDIR");
        const std::string owner_session = std::string(tmp && *tmp ? tmp : "/tmp") + "/engine-test-owner.bin";
        const bool saved_owner = engine.save_session(owner_session);
        engine.begin_reply();
        const int resumed = engine.resume(counting, run.options, "resumed-chat");
        const bool saved_resume = engine.save_session(owner_session);
        run.steps.push_back(Step{"resume cache owner"});
        run.check(saved_owner && resumed >= 0 && engine.last_prompt().processed == 0 && saved_resume &&
                  autark::Engine::session_chat_id(owner_session) == "resumed-chat", "saved new owner without decoding");
        remove(owner_session.c_str());
        run.prefill({{"system", SYSTEM_PROMPT}}, "new chat: prefill");
        run.ask(counting, "continue later", -1, true);
        run.check(run.steps.back().generated > 0 && engine.ended() != autark::End::failed, "carried on");
        run.options = plain;
    }

    // Thinking: the reply has to arrive with its <think> block for the app to fold it away.
    const autark::ReplyOptions plain = run.options;
    run.options.thinking = true;
    run.options.max_tokens = 1024;
    Chat thought = {{"system", SYSTEM_PROMPT}, {"user", "What is 17 * 23? Answer with just the number."}};
    const std::string reasoned = run.ask(thought, "thinking");
    run.check(reasoned.rfind("<think>", 0) == 0, "reply opens with <think>");
    thought.push_back({"assistant", answer_of(reasoned)});
    thought.push_back({"user", "And 17 * 24?"});
    run.ask(thought, "thinking 2");
    // A small model can think past the token limit; the reply then goes back empty, and the
    // cache goes back to the end of the question instead.
    if (reasoned.find("</think>") != std::string::npos) {
        run.check(run.steps.back().stats.appended, "appended after a thought-out reply");
    } else {
        run.check(run.steps.back().stats.processed < 64, "cut off while thinking: only the new message processed");
    }
    // Reasoning that would run past its budget is closed with room left for an answer.
    run.options.max_tokens = 160;
    Chat budget_chat = {{"system", SYSTEM_PROMPT},
                        {"user", "A shop sells pens at 7 rupees each. I buy 13 pens and pay with a "
                                 "100 rupee note. How much change do I get? Check your working twice."}};
    const std::string brief = run.ask(budget_chat, "thinking budget");
    run.check(brief.find("</think>") != std::string::npos && !answer_of(brief).empty(), "closed, then answered");
    run.options = plain;

    // An identical completed chat can take ownership without processing its tokens again.
    budget_chat.push_back({"assistant", answer_of(brief)});
    engine.prefill(budget_chat, false, plain.max_tokens, "matching-chat");
    run.steps.push_back(Step{"matching cache owner"});
    run.check(engine.cached_chat_id() == "matching-chat", "matching completed chat adopted the cache");
    engine.prefill(budget_chat, false, plain.max_tokens, "engine-test");

    // Visiting a chat whose prefill is a no-op, or stopping before its prompt, leaves the owner alone.
    run.steps.push_back(Step{"no-op cache owner"});
    engine.prefill(fresh, false, plain.max_tokens, "other-chat");
    run.check(engine.cached_chat_id() == "engine-test", "unanswered chat did not take over the cache");
    engine.begin_reply();
    engine.stop();
    run.steps.push_back(Step{"stopped cache owner"});
    run.check(engine.prompt(fresh, plain, "other-chat") == autark::ERR_ABORTED &&
              engine.cached_chat_id() == "engine-test", "stopped request did not take over the cache");

    // Stop during loading must survive until prompt/resume, and the next request must reset it.
    engine.begin_reply();
    engine.stop();
    const int short_ctx = engine.load(model_path, 512, n_threads, n_threads_batch);
    if (short_ctx < 0) return 1;
    run.steps.push_back(Step{"stop while loading"});
    run.check(engine.prompt(fresh, plain) == autark::ERR_ABORTED &&
              engine.resume({fresh[0], fresh[1], {"assistant", "Partial reply"}}, plain) == autark::ERR_ABORTED,
              "loading did not clear Stop");

    // Shared CJK, Indic and emoji text is fitted by tokens in the actual short-context prompt.
    run.options.max_tokens = 1024;
    run.options.thinking = true;
    const Chat history = {{"system", SYSTEM_PROMPT}};
    const std::string instruction = "Summarize this text briefly:";
    for (const std::string & unit : {std::string("这是要总结的文章。"), std::string("यह संक्षेप करने का पाठ है।"),
                                    std::string("🌍📖🙂")}) {
        std::string text;
        for (int i = 0; i < 500; ++i) text += unit;
        const int keep = engine.fit_shared(history, instruction, text, true, run.options.max_tokens);
        const bool valid = keep > 0 && static_cast<size_t>(keep) < text.size() &&
                           (static_cast<unsigned char>(text[keep]) & 0xC0) != 0x80;
        run.steps.push_back(Step{"fit shared UTF-8"});
        run.check(valid, "cut at a character boundary");
        if (!valid) continue;
        Chat fitted = history;
        fitted.push_back({"user", instruction + "\n\n" + text.substr(0, static_cast<size_t>(keep))});
        run.ask(fitted, "shared: short ctx", 1);
        run.check(run.steps.back().stats.tokens + std::min(run.options.max_tokens, short_ctx / 2) <= short_ctx,
                  "prompt fits with its answer reserve");
        run.steps.push_back(Step{"instruction too long"});
        run.check(engine.fit_shared(history, text, "small share", true, run.options.max_tokens) == autark::ERR_TOO_LONG,
                  "oversized instruction rejected before sending");
    }

    // A reply limit much larger than the available context must still allow a visible answer.
    std::string material;
    for (int i = 0; i < 500; ++i) material += " one two three";
    const std::string think_instruction = "Count from 1 to 1000 in your thinking, then answer with OK. Text:";
    const int keep = engine.fit_shared(history, think_instruction, material, true, run.options.max_tokens);
    if (keep < 0) return 1;
    Chat near_full = history;
    near_full.push_back({"user", think_instruction + "\n\n" + material.substr(0, static_cast<size_t>(keep))});
    const std::string bounded = run.ask(near_full, "thinking: short ctx");
    run.check(bounded.find("</think>") != std::string::npos && !answer_of(bounded).empty() &&
              engine.ended() != autark::End::failed, "closed and answered within the context");
    run.options = plain;

    run.print();
    printf("memory: %s now, %.0f MiB at peak\n", memory_now().c_str(), memory("VmHWM"));
    if (run.failures) printf("%d check(s) FAILED\n", run.failures);
    return run.failures ? 1 : 0;
}
