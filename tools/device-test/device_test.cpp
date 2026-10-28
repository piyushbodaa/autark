// JNI side of the on-device tests (app/src/androidTest/.../DeviceTest.kt). Built into debug
// builds only, as libautark-tests.so: it runs tools/engine-test and llama.cpp's llama-bench
// inside the test process, which is the only way to run code on Firebase Test Lab's phones.

#include <dlfcn.h>
#include <fcntl.h>
#include <jni.h>
#include <unistd.h>

#include <cstdio>
#include <string>
#include <vector>

#include "ggml-backend.h"

int engine_test_main(int argc, char ** argv);  // tools/engine-test/main.cpp, built with main renamed
int llama_bench(int argc, char ** argv);       // third_party/llama.cpp/tools/llama-bench

namespace {

std::string string_from(JNIEnv * env, jstring s) {
    const char * chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

std::string variant_library(JNIEnv * env, jstring variant) {
    return "libggml-cpu-" + string_from(env, variant) + ".so";
}

// Runs a command-line tool with `jargs`, its standard output going to `jout` and its errors to
// `jerr` (an app's own are /dev/null). Returns the tool's exit code.
int run(int (*tool)(int, char **), JNIEnv * env, jobjectArray jargs, jstring jout, jstring jerr) {
    std::vector<std::string> args = {"tool"};
    for (jsize i = 0; i < env->GetArrayLength(jargs); ++i) {
        auto arg = static_cast<jstring>(env->GetObjectArrayElement(jargs, i));
        args.push_back(string_from(env, arg));
        env->DeleteLocalRef(arg);
    }
    std::vector<char *> argv;
    for (auto & arg : args) argv.push_back(arg.data());
    argv.push_back(nullptr);

    const int out = open(string_from(env, jout).c_str(), O_WRONLY | O_CREAT | O_TRUNC, 0644);
    const int err = open(string_from(env, jerr).c_str(), O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (out < 0 || err < 0) return -1;
    fflush(stdout);
    fflush(stderr);
    const int saved_out = dup(STDOUT_FILENO);
    const int saved_err = dup(STDERR_FILENO);
    dup2(out, STDOUT_FILENO);
    dup2(err, STDERR_FILENO);
    close(out);
    close(err);

    const int rc = tool(static_cast<int>(args.size()), argv.data());

    fflush(stdout);
    fflush(stderr);
    dup2(saved_out, STDOUT_FILENO);
    dup2(saved_err, STDERR_FILENO);
    close(saved_out);
    close(saved_err);
    return rc;
}

}  // namespace

extern "C" {

// The CPU backend variant's own verdict on this CPU: 0 if it can't run here, higher for more
// features, or -1 if the app doesn't ship it.
JNIEXPORT jint JNICALL
Java_com_piyushbodaa_autark_devicetest_DeviceTools_score(JNIEnv * env, jobject, jstring variant) {
    void * handle = dlopen(variant_library(env, variant).c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!handle) return -1;
    auto score = reinterpret_cast<int (*)()>(dlsym(handle, "ggml_backend_score"));
    const int s = score ? score() : 0;
    dlclose(handle);
    return s;
}

// Makes `variant` this process's CPU backend. Engine::init and llama-bench then use it instead
// of picking one themselves.
JNIEXPORT jboolean JNICALL
Java_com_piyushbodaa_autark_devicetest_DeviceTools_useVariant(JNIEnv * env, jobject, jstring variant) {
    return ggml_backend_load(variant_library(env, variant).c_str()) != nullptr;
}

JNIEXPORT jint JNICALL
Java_com_piyushbodaa_autark_devicetest_DeviceTools_engineTest(JNIEnv * env, jobject, jobjectArray args, jstring out,
                                                            jstring err) {
    return run(engine_test_main, env, args, out, err);
}

JNIEXPORT jint JNICALL
Java_com_piyushbodaa_autark_devicetest_DeviceTools_llamaBench(JNIEnv * env, jobject, jobjectArray args, jstring out,
                                                            jstring err) {
    return run(llama_bench, env, args, out, err);
}

}  // extern "C"
