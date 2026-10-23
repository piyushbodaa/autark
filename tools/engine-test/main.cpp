// Drives Autark's engine (app/src/main/cpp/engine.cpp) through scripted chats on a desktop,
// the same way the app does, and reports how much of each prompt had to be processed.
// Replies are greedy, so some steps can check themselves: Retry must reproduce the reply it
// replaces, and a new chat must match the same chat run on an empty cache.
//
//   engine-test MODEL.gguf [--ctx N] [--threads N] [--threads-batch N] [--max-tokens N]
//                          [--turns N] [--verbose]

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
    "Answer clearly and briefly. Reply in the language the user writes in.";

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

    // Asks for a reply the way AppViewModel does. Stops after `stop_after` pieces if set.
    std::string ask(const Chat & chat, const std::string & label, int stop_after = -1) {
        Step step;
        step.label = label;
        const auto start = Clock::now();
        const int rc = engine.prompt(chat, options);
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
        const int rc = engine.prefill(chat, options.thinking, options.max_tokens);
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
        printf("\n%-16s %7s %7s %9s %10s %12s %5s %7s\n", "step", "prompt", "reused", "processed", "prompt ms",
               "1st token ms", "gen", "tok/s");
        int processed = 0;
        double waited = 0;
        for (const auto & s : steps) {
            printf("%-16s %7d %7d %9d %10.0f %12.0f %5d %7.1f  %s%s\n", s.label.c_str(), s.stats.tokens, s.stats.reused,
                   s.stats.processed, s.prompt_ms, s.first_ms, s.generated,
                   s.gen_ms > 0 ? s.generated * 1000.0 / s.gen_ms : 0.0, s.stats.appended ? "appended " : "",
                   s.note.c_str());
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
                        "[--turns N] [--verbose]\n", argv[0]);
        return 1;
    }
    const std::string model_path = argv[1];
    int n_ctx = 4096, n_threads = 4, n_threads_batch = 0, max_tokens = 256;
    int n_turns = static_cast<int>(QUESTIONS.size());
    bool verbose = false;
    for (int i = 2; i < argc; ++i) {
        const bool has_value = i + 1 < argc;
        if (!strcmp(argv[i], "--ctx") && has_value) n_ctx = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--threads") && has_value) n_threads = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--threads-batch") && has_value) n_threads_batch = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--max-tokens") && has_value) max_tokens = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--turns") && has_value) n_turns = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--verbose")) verbose = true;
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
        run.check(saved && loaded, "saved and picked up again");
        remove(session.c_str());
    }
    run.prefill(chat, "after reload");
    run.check(run.steps.back().prompt_ms < 50, "nothing left to process");
    chat.push_back({"user", "And what's 5 + 5?"});
    run.ask(chat, "ask after reload");
    run.check(run.steps.back().stats.appended, "appended");

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
    run.options = plain;

    run.print();
    printf("memory: %s now, %.0f MiB at peak\n", memory_now().c_str(), memory("VmHWM"));
    if (run.failures) printf("%d check(s) FAILED\n", run.failures);
    return run.failures ? 1 : 0;
}
