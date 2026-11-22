// PocketAI — JNI bridge to llama.cpp
// Keeps the KV cache between turns and only evaluates the part of the prompt
// that changed (prefix reuse), so long chats stay fast on a phone.

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cstring>
#include <string>
#include <vector>

#include "llama.h"

#define TAG "PocketAI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Engine {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    std::vector<llama_token> cached;   // tokens currently in the KV cache (seq 0)
    std::atomic<bool> stop{false};
    int n_batch = 512;
};

void log_cb(ggml_log_level level, const char *text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, TAG, "%s", text);
}

std::string bytes_to_string(JNIEnv *env, jbyteArray arr) {
    if (!arr) return {};
    jsize len = env->GetArrayLength(arr);
    std::string s(static_cast<size_t>(len), '\0');
    if (len > 0) env->GetByteArrayRegion(arr, 0, len, reinterpret_cast<jbyte *>(&s[0]));
    return s;
}

jbyteArray string_to_bytes(JNIEnv *env, const std::string &s) {
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(s.size()));
    if (arr && !s.empty())
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(s.size()),
                                reinterpret_cast<const jbyte *>(s.data()));
    return arr;
}

std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text, bool add_special) {
    int n = -llama_tokenize(vocab, text.data(), (int32_t) text.size(), nullptr, 0, add_special, true);
    std::vector<llama_token> out(n > 0 ? n : 0);
    if (n > 0) {
        int got = llama_tokenize(vocab, text.data(), (int32_t) text.size(), out.data(), n, add_special, true);
        if (got < 0) out.clear(); else out.resize(got);
    }
    return out;
}

std::string piece(const llama_vocab *vocab, llama_token t) {
    char buf[256];
    int n = llama_token_to_piece(vocab, t, buf, sizeof(buf), 0, false);
    if (n < 0) {
        std::string big(static_cast<size_t>(-n), '\0');
        n = llama_token_to_piece(vocab, t, &big[0], -n, 0, false);
        return n > 0 ? big.substr(0, n) : std::string();
    }
    return std::string(buf, n);
}

Engine *E(jlong h) { return reinterpret_cast<Engine *>(h); }

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_dozy_pocketai_llm_LlamaNative_backendInit(JNIEnv *, jobject) {
    llama_log_set(log_cb, nullptr);
    llama_backend_init();
}

JNIEXPORT jlong JNICALL
Java_com_dozy_pocketai_llm_LlamaNative_load(JNIEnv *env, jobject, jstring jpath, jint n_ctx, jint n_threads,
                                            jint n_threads_batch, jboolean kv_q8) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    mp.use_mmap = true;
    llama_model *model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);
    if (!model) { LOGE("model load failed"); return 0; }

    llama_context_params cp = llama_context_default_params();
    int train = llama_model_n_ctx_train(model);
    cp.n_ctx = (uint32_t) ((train > 0 && n_ctx > train) ? train : n_ctx);
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = n_threads;              // generation: memory-bound, big cores only
    cp.n_threads_batch = n_threads_batch;  // prompt reading: compute-bound, all cores
    cp.no_perf = true;
    // flash attention + 8-bit KV cache: half the memory per token, faster long contexts
    cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
    if (kv_q8) {
        cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED; // quantized V cache needs flash attention
        cp.type_k = GGML_TYPE_Q8_0;
        cp.type_v = GGML_TYPE_Q8_0;
    }
    llama_context *ctx = llama_init_from_model(model, cp);
    if (!ctx && kv_q8) {
        // model without flash-attention support: fall back to an f16 cache
        cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
        cp.type_k = GGML_TYPE_F16;
        cp.type_v = GGML_TYPE_F16;
        ctx = llama_init_from_model(model, cp);
    }
    if (!ctx) { LOGE("context init failed"); llama_model_free(model); return 0; }

    // warm-up: touches every weight once (pages the mmap in, repacks Q4_0 for ARM),
    // so the first real answer starts without a long pause
    {
        const llama_vocab *v = llama_model_get_vocab(model);
        llama_token t = llama_vocab_bos(v);
        if (t == LLAMA_TOKEN_NULL) t = 0;
        llama_set_warmup(ctx, true);
        llama_batch b = llama_batch_get_one(&t, 1);
        llama_decode(ctx, b);
        llama_synchronize(ctx);
        llama_set_warmup(ctx, false);
        llama_memory_clear(llama_get_memory(ctx), true);
    }

    auto *e = new Engine();
    e->model = model;
    e->ctx = ctx;
    e->vocab = llama_model_get_vocab(model);
    e->n_batch = (int) cp.n_batch;
    LOGI("model loaded, n_ctx=%u", llama_n_ctx(ctx));
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT void JNICALL
Java_com_dozy_pocketai_llm_LlamaNative_free(JNIEnv *, jobject, jlong h) {
    Engine *e = E(h);
    if (!e) return;
    if (e->ctx) llama_free(e->ctx);
    if (e->model) llama_model_free(e->model);
    delete e;
}

JNIEXPORT jint JNICALL
Java_com_dozy_pocketai_llm_LlamaNative_contextSize(JNIEnv *, jobject, jlong h) {
    Engine *e = E(h);
    return e ? (jint) llama_n_ctx(e->ctx) : 0;
}

JNIEXPORT jstring JNICALL
Java_com_dozy_pocketai_llm_LlamaNative_description(JNIEnv *env, jobject, jlong h) {
    Engine *e = E(h);
    char buf[256] = {0};
    if (e) llama_model_desc(e->model, buf, sizeof(buf));
    return env->NewStringUTF(buf);
}

JNIEXPORT jint JNICALL
Java_com_dozy_pocketai_llm_LlamaNative_tokenCount(JNIEnv *env, jobject, jlong h, jbyteArray text) {
    Engine *e = E(h);
    if (!e) return 0;
    return (jint) tokenize(e->vocab, bytes_to_string(env, text), true).size();
}

// Formats a conversation with the model's own chat template.
// roles: "system" | "user" | "assistant"; contents are UTF-8 bytes.
JNIEXPORT jbyteArray JNICALL
Java_com_dozy_pocketai_llm_LlamaNative_applyTemplate(JNIEnv *env, jobject, jlong h,
                                                     jobjectArray jroles, jobjectArray jcontents,
                                                     jboolean add_assistant) {
    Engine *e = E(h);
    if (!e) return nullptr;
    jsize n = env->GetArrayLength(jroles);
    std::vector<std::string> roles(n), contents(n);
    std::vector<llama_chat_message> msgs(n);
    size_t total = 0;
    for (jsize i = 0; i < n; i++) {
        auto jr = (jstring) env->GetObjectArrayElement(jroles, i);
        const char *r = env->GetStringUTFChars(jr, nullptr);
        roles[i] = r;
        env->ReleaseStringUTFChars(jr, r);
        env->DeleteLocalRef(jr);
        auto jc = (jbyteArray) env->GetObjectArrayElement(jcontents, i);
        contents[i] = bytes_to_string(env, jc);
        env->DeleteLocalRef(jc);
        total += contents[i].size() + roles[i].size();
    }
    for (jsize i = 0; i < n; i++) msgs[i] = {roles[i].c_str(), contents[i].c_str()};

    const char *tmpl = llama_model_chat_template(e->model, nullptr);
    std::vector<char> buf(total * 2 + 1024);
    int len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), add_assistant, buf.data(), (int32_t) buf.size());
    if (len < 0) {
        // Unknown template — fall back to ChatML, which most small instruct models understand.
        len = llama_chat_apply_template("chatml", msgs.data(), msgs.size(), add_assistant, buf.data(), (int32_t) buf.size());
    }
    if (len < 0) return nullptr;
    if ((size_t) len > buf.size()) {
        buf.resize(len + 1);
        len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), add_assistant, buf.data(), (int32_t) buf.size());
        if (len < 0)
            len = llama_chat_apply_template("chatml", msgs.data(), msgs.size(), add_assistant, buf.data(), (int32_t) buf.size());
        if (len < 0) return nullptr;
    }
    return string_to_bytes(env, std::string(buf.data(), len));
}

JNIEXPORT void JNICALL
Java_com_dozy_pocketai_llm_LlamaNative_stop(JNIEnv *, jobject, jlong h) {
    Engine *e = E(h);
    if (e) e->stop = true;
}

JNIEXPORT void JNICALL
Java_com_dozy_pocketai_llm_LlamaNative_resetCache(JNIEnv *, jobject, jlong h) {
    Engine *e = E(h);
    if (!e) return;
    llama_memory_clear(llama_get_memory(e->ctx), true);
    e->cached.clear();
}

// Runs a completion for an already-templated prompt. Every generated piece is
// passed to callback.onToken(byte[]) as raw UTF-8 bytes (may be a partial
// character — the Kotlin side decodes the stream). Returning false from the
// callback stops generation.
// Returns: number of generated tokens, -1 bad handle, -2 prompt too long, -3 decode error.
JNIEXPORT jint JNICALL
Java_com_dozy_pocketai_llm_LlamaNative_completion(JNIEnv *env, jobject, jlong h, jbyteArray jprompt,
                                                  jint max_tokens, jfloat temp, jfloat top_p, jint top_k,
                                                  jfloat repeat_penalty, jint seed, jobject callback) {
    Engine *e = E(h);
    if (!e) return -1;
    e->stop = false;

    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(cbClass, "onToken", "([B)Z");

    const std::string prompt = bytes_to_string(env, jprompt);
    std::vector<llama_token> tokens = tokenize(e->vocab, prompt, true);
    const int n_ctx = (int) llama_n_ctx(e->ctx);
    if (tokens.empty() || (int) tokens.size() >= n_ctx - 8) return -2;

    // ---- prefix reuse ----
    size_t n_keep = 0;
    while (n_keep < e->cached.size() && n_keep < tokens.size() && e->cached[n_keep] == tokens[n_keep]) n_keep++;
    if (n_keep == tokens.size()) n_keep--; // need at least one token to get fresh logits
    llama_memory_t mem = llama_get_memory(e->ctx);
    if (!llama_memory_seq_rm(mem, 0, (llama_pos) n_keep, -1)) {
        llama_memory_clear(mem, true);
        n_keep = 0;
    }
    e->cached.resize(n_keep);

    for (size_t i = n_keep; i < tokens.size(); i += e->n_batch) {
        if (e->stop) return 0;
        int n = (int) std::min<size_t>(e->n_batch, tokens.size() - i);
        llama_batch batch = llama_batch_get_one(tokens.data() + i, n);
        if (llama_decode(e->ctx, batch) != 0) {
            llama_memory_clear(mem, true);
            e->cached.clear();
            return -3;
        }
        e->cached.insert(e->cached.end(), tokens.begin() + i, tokens.begin() + i + n);
    }

    // ---- sampler ----
    llama_sampler_chain_params sp = llama_sampler_chain_default_params();
    sp.no_perf = true;
    llama_sampler *smpl = llama_sampler_chain_init(sp);
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(64, repeat_penalty, 0.0f, 0.0f));
    if (temp <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        if (top_k > 0) llama_sampler_chain_add(smpl, llama_sampler_init_top_k(top_k));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(top_p, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_min_p(0.05f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(seed < 0 ? LLAMA_DEFAULT_SEED : (uint32_t) seed));
    }

    int generated = 0;
    while (generated < max_tokens && !e->stop) {
        if ((int) e->cached.size() >= n_ctx - 1) break;
        llama_token t = llama_sampler_sample(smpl, e->ctx, -1);
        if (llama_vocab_is_eog(e->vocab, t)) break;

        std::string p = piece(e->vocab, t);
        llama_batch batch = llama_batch_get_one(&t, 1);
        if (llama_decode(e->ctx, batch) != 0) break;
        e->cached.push_back(t);
        generated++;

        if (!p.empty()) {
            jbyteArray arr = string_to_bytes(env, p);
            jboolean keep = env->CallBooleanMethod(callback, onToken, arr);
            env->DeleteLocalRef(arr);
            if (env->ExceptionCheck()) { env->ExceptionClear(); break; }
            if (!keep) break;
        }
    }
    llama_sampler_free(smpl);
    return generated;
}

} // extern "C"
