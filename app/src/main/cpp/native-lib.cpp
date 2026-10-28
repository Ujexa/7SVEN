#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include <algorithm>
#include <thread>
#include <cstring>
#include <cstdio>
#include <cerrno>
#include <unistd.h>
#include "llama.h"

static llama_model *model = nullptr;
static llama_context *ctx = nullptr;
static const llama_vocab *vocab = nullptr;
static std::mutex model_mutex;

extern "C" JNIEXPORT jstring JNICALL
Java_com_sven_assistant_NativeEngine_loadModel(
        JNIEnv *env, jobject, jint fd) {
    std::lock_guard<std::mutex> lock(model_mutex);

    if (ctx) { llama_free(ctx); ctx = nullptr; }
    if (model) { llama_model_free(model); model = nullptr; }
    vocab = nullptr;

    int duplicated_fd = dup(fd);
    if (duplicated_fd < 0) {
        return env->NewStringUTF("ERROR: Could not duplicate the selected model file descriptor.");
    }

    FILE *stream = fdopen(duplicated_fd, "rb");
    if (!stream) {
        close(duplicated_fd);
        return env->NewStringUTF("ERROR: Could not open a readable stream for the selected model.");
    }

    llama_backend_init();
    auto mp = llama_model_default_params();
    mp.n_gpu_layers = 0;

    model = llama_model_load_from_file_ptr(stream, mp);
    fclose(stream);

    if (!model)
        return env->NewStringUTF("ERROR: llama.cpp could not load the model from the selected file stream. Check the native logs for the underlying reason.");

    auto cp = llama_context_default_params();
    cp.n_ctx = 2048;
    cp.n_batch = 2048;
    cp.n_ubatch = 512;
    cp.n_threads = std::max(2, (int)std::thread::hardware_concurrency() - 2);
    cp.n_threads_batch = cp.n_threads;
    cp.no_perf = true;

    ctx = llama_init_from_model(model, cp);
    if (!ctx) {
        llama_model_free(model);
        model = nullptr;
        return env->NewStringUTF("ERROR: Could not initialize the inference context.");
    }

    vocab = llama_model_get_vocab(model);
    return env->NewStringUTF("OK");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sven_assistant_NativeEngine_generate(
        JNIEnv *env, jobject, jstring jprompt, jint max_tokens) {
    std::lock_guard<std::mutex> lock(model_mutex);

    if (!model || !ctx || !vocab)
        return env->NewStringUTF("ERROR: Load a model first.");

    const char *prompt = env->GetStringUTFChars(jprompt, nullptr);
    if (!prompt) return env->NewStringUTF("ERROR: Could not read prompt.");

    int32_t needed = llama_tokenize(
        vocab, prompt, (int32_t)std::strlen(prompt), nullptr, 0, true, false);

    if (needed >= 0 || -((int64_t)needed) > 2000) {
        env->ReleaseStringUTFChars(jprompt, prompt);
        return env->NewStringUTF("ERROR: Prompt is too long or tokenization failed.");
    }

    std::vector<llama_token> tokens((size_t)(-needed) + 8);
    int32_t count = llama_tokenize(
        vocab, prompt, (int32_t)std::strlen(prompt),
        tokens.data(), (int32_t)tokens.size(), true, false);

    env->ReleaseStringUTFChars(jprompt, prompt);

    if (count <= 0)
        return env->NewStringUTF("ERROR: Could not tokenize prompt.");

    tokens.resize(count);
    llama_memory_clear(llama_get_memory(ctx), true);

    if (llama_decode(ctx, llama_batch_get_one(tokens.data(), count)) != 0)
        return env->NewStringUTF("ERROR: Prompt evaluation failed.");

    auto *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (!sampler) return env->NewStringUTF("ERROR: Could not initialize sampler.");

    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.90f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.70f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string output;
    std::vector<char> piece(8192);
    const int limit = std::max(1, std::min((int)max_tokens, 384));

    for (int i = 0; i < limit; ++i) {
        llama_token token = llama_sampler_sample(sampler, ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        llama_sampler_accept(sampler, token);

        int n = llama_token_to_piece(
            vocab, token, piece.data(), (int)piece.size(), 0, false);

        if (n < 0) {
            piece.resize((size_t)(-n) + 16);
            n = llama_token_to_piece(
                vocab, token, piece.data(), (int)piece.size(), 0, false);
        }

        if (n > 0) output.append(piece.data(), n);

        if (i + 1 < limit) {
            llama_token next = token;
            if (llama_decode(ctx, llama_batch_get_one(&next, 1)) != 0) break;
        }
    }

    llama_sampler_free(sampler);
    return env->NewStringUTF(output.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_sven_assistant_NativeEngine_unloadModel(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(model_mutex);
    if (ctx) { llama_free(ctx); ctx = nullptr; }
    if (model) { llama_model_free(model); model = nullptr; }
    vocab = nullptr;
}
