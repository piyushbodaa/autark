// Asks Autark's engine a list of questions, each in a new chat with the app's system prompt, and
// prints the replies as Markdown: for comparing sampling settings by reading them side by side.
//
//   engine-ask MODEL.gguf QUESTIONS.txt [--temp T] [--top-p P] [--top-k K] [--min-p M]
//              [--presence P] [--seeds N] [--max-tokens N] [--thinking] [--threads N]
//              [--system TEXT]
//
// QUESTIONS.txt holds one question per line. Each is asked once per seed (1, 2, ...).

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

// Settings > Instructions in the app, by default.
const char * SYSTEM_PROMPT =
    "You are Autark, a helpful assistant that runs privately on the user's phone. "
    "Answer clearly and briefly. Reply in the language the user writes in. "
    "Write maths in plain text, not LaTeX.";

}  // namespace

int main(int argc, char ** argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: %s MODEL.gguf QUESTIONS.txt [--temp T] [--top-p P] [--top-k K] [--min-p M] "
                        "[--presence P] [--seeds N] [--max-tokens N] [--thinking] [--threads N] [--system TEXT]\n",
                argv[0]);
        return 1;
    }
    autark::ReplyOptions options;
    options.max_tokens = 512;
    int seeds = 1, n_threads = 4;
    std::string system = SYSTEM_PROMPT;
    for (int i = 3; i < argc; ++i) {
        const bool has_value = i + 1 < argc;
        if (!strcmp(argv[i], "--temp") && has_value) options.temperature = static_cast<float>(atof(argv[++i]));
        else if (!strcmp(argv[i], "--top-p") && has_value) options.top_p = static_cast<float>(atof(argv[++i]));
        else if (!strcmp(argv[i], "--top-k") && has_value) options.top_k = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--min-p") && has_value) options.min_p = static_cast<float>(atof(argv[++i]));
        else if (!strcmp(argv[i], "--presence") && has_value) options.presence_penalty = static_cast<float>(atof(argv[++i]));
        else if (!strcmp(argv[i], "--seeds") && has_value) seeds = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--max-tokens") && has_value) options.max_tokens = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--threads") && has_value) n_threads = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--system") && has_value) system = argv[++i];
        else if (!strcmp(argv[i], "--thinking")) options.thinking = true;
    }

    std::vector<std::string> questions;
    std::ifstream in(argv[2]);
    for (std::string line; std::getline(in, line);) {
        if (!line.empty()) questions.push_back(line);
    }

    autark::Engine::init(".");
    autark::Engine engine;
    if (engine.load(argv[1], 4096, n_threads, n_threads) < 0) {
        fprintf(stderr, "load failed\n");
        return 1;
    }
    printf("# temperature %.2f, top-p %.2f, top-k %d, min-p %.2f, presence penalty %.2f%s\n\n", options.temperature,
           options.top_p, options.top_k, options.min_p, options.presence_penalty, options.thinking ? ", thinking" : "");

    for (const auto & question : questions) {
        printf("## %s\n\n", question.c_str());
        for (int seed = 1; seed <= seeds; ++seed) {
            options.seed = static_cast<uint32_t>(seed);
            const auto start = Clock::now();
            engine.begin_reply();
            if (engine.prompt({{"system", system}, {"user", question}}, options) < 0) {
                fprintf(stderr, "prompt failed\n");
                return 1;
            }
            std::string reply, piece;
            int tokens = 0;
            while (engine.next(piece)) {
                reply += piece;
                ++tokens;
            }
            const double seconds = std::chrono::duration<double>(Clock::now() - start).count();
            const char * ended = engine.ended() == autark::End::length ? ", cut off" : "";
            printf("**Seed %d** (%d tokens, %.0f s%s)\n\n%s\n\n", seed, tokens, seconds, ended, reply.c_str());
            fflush(stdout);
        }
    }
    return 0;
}
